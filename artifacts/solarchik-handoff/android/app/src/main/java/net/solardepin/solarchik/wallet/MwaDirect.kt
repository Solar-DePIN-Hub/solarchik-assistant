package net.solardepin.solarchik.wallet

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.withResumed
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import com.solana.mobilewalletadapter.common.protocol.SessionProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 1.2.0: the activity-result launcher for the wallet association intent, registered in MainActivity.onCreate
 * (before STARTED, as registerForActivityResult requires). One per activity; [current] is the live one.
 */
class WalletLauncher(private val activity: ComponentActivity) {
    private var callback: ((Int) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val c = callback
        callback = null
        c?.invoke(r.resultCode)
    }

    /** Launches [intent] once the activity is RESUMED; [onResult] gets the wallet activity's result code. */
    suspend fun launch(intent: Intent, onResult: (Int) -> Unit) {
        activity.lifecycle.withResumed {
            if (callback != null) WalletDiag.log("launcher", "a previous wallet result was still pending (replaced)")
            callback = onResult
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                callback = null
                throw e
            }
        }
    }

    /** 1.2.3: logs this activity's stop/start while a connect runs; call the returned function to stop. */
    fun watchLifecycle(): () -> Unit {
        var armed = false // addObserver replays CREATE/START/RESUME: those are not news
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (armed && e == androidx.lifecycle.Lifecycle.Event.ON_STOP || e == androidx.lifecycle.Lifecycle.Event.ON_START ||
                e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) WalletDiag.log("app", e.name.removePrefix("ON_").lowercase())
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post { activity.lifecycle.addObserver(obs); armed = true }
        return { main.post { activity.lifecycle.removeObserver(obs) } }
    }

    /** The app is in front again (the user came back from the wallet). */
    val resumed: Boolean get() = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    companion object {
        @Volatile var current: WalletLauncher? = null
    }
}

/**
 * 1.2.0: our own MWA connect (authorize), built on the clientlib's public LocalAssociationScenario. Differences
 * from clientlib-ktx 2.0.7 `connect`: it waits up to [START_WAIT_MS] for the wallet to open its session (ktx gives
 * up after 10 s, a cold-starting wallet on a slow tablet may need longer), it keeps waiting when the wallet's
 * activity answers RESULT_CANCELED early (some wallets run MWA in a separate task), it can target one wallet
 * package (Phantom / Solflare), and every step is logged to [WalletDiag]. Signing still goes through ktx with
 * the auth token from here.
 */
object MwaDirect {
    const val START_WAIT_MS = 45_000L
    /** 1.2.4: the wallet dropped the session after refusing the saved token; the caller asks again in a new one. */
    const val NEW_SESSION = "session closed after the saved token was refused"
    /** After the wallet activity returned AND the user is back in the app, how long a session may still take to appear. */
    const val AFTER_RESULT_MS = 6_000L
    const val AUTHORIZE_WAIT_MS = 120_000L

    /** Test seam: replaces the whole flow (unit tests have no wallet). */
    @Volatile var override: (suspend (pkg: String?) -> Result<MobileWalletAdapterClient.AuthorizationResult>)? = null

    fun resultName(code: Int): String = when (code) {
        Activity.RESULT_OK -> "RESULT_OK"
        Activity.RESULT_CANCELED -> "RESULT_CANCELED"
        else -> code.toString()
    }

    suspend fun authorize(
        ctx: Context,
        launcher: WalletLauncher,
        pkg: String?,
        chain: String,
        legacyCluster: String,
        authToken: String?,
        onTokenRejected: () -> Unit = {},
    ): Result<MobileWalletAdapterClient.AuthorizationResult> {
        override?.let { return it(pkg) }
        return transact(ctx, launcher, pkg, chain, legacyCluster, authToken, onTokenRejected = onTokenRejected) { _, _ -> Unit }.map { it.first }
    }

    /** Test seam for [transact]: the authorize result (the block then runs against [FAKE_CLIENT]). */
    @Volatile var FAKE_CLIENT: MobileWalletAdapterClient? = null
    @Volatile var transactOverride: (suspend (pkg: String?) -> Result<MobileWalletAdapterClient.AuthorizationResult>)? = null

    /**
     * One MWA session: associate, authorize (with the saved token when there is one), then [block] on the IO
     * thread with the live client (sign / sign-and-send; blocking futures are fine there).
     */
    /** The association intent exactly as the clientlib builds it (no endpoint prefix: plain solana-wallet:/v1/associate/local). */
    fun associationIntent(scenario: LocalAssociationScenario, pkg: String?): Intent =
        LocalAssociationIntentCreator.createAssociationIntent(null, scenario.port, scenario.session).also { if (!pkg.isNullOrBlank()) it.setPackage(pkg) }

    suspend fun <T> transact(
        ctx: Context,
        launcher: WalletLauncher,
        pkg: String?,
        chain: String,
        legacyCluster: String,
        authToken: String?,
        signIn: com.solana.mobilewalletadapter.common.signin.SignInWithSolana.Payload? = null,
        onTokenRejected: () -> Unit = {},
        block: (MobileWalletAdapterClient, MobileWalletAdapterClient.AuthorizationResult) -> T,
    ): Result<Pair<MobileWalletAdapterClient.AuthorizationResult, T>> {
        transactOverride?.let { o -> return o(pkg).map { a -> a to block(FAKE_CLIENT ?: error("no fake client"), a) } }
        WalletDiag.log("connect", WalletDiag.header(ctx))
        val scenario = try {
            LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        } catch (t: Throwable) {
            WalletDiag.error("scenario failed", t)
            return Result.failure(WalletError(WalletError.Kind.FAILED, "scenario: " + (t.message ?: "")))
        }
        val intent = associationIntent(scenario, pkg)
        val handlers = WalletDiag.handlers(ctx, intent)
        WalletDiag.log("intent", "port=" + scenario.port + " target=" + (pkg ?: "any wallet") + " uri=" + WalletDiag.safeUri(intent.data))
        WalletDiag.log("handlers", if (handlers.isEmpty()) "none" else handlers.joinToString(" | "))
        // 1.2.2: the URI judged against the MWA spec, and the WebSocket the app will dial, in words
        WalletDiag.log("uri check", MwaUri.summary(intent.data))
        WalletDiag.log("ws client", MwaUri.wsTarget(scenario) + " protocol=" + MwaUri.SUBPROTOCOL)
        // 1.2.3: the official order: start the scenario (its WebSocket client dials on its own thread, first try
        // after 150 ms, then backs off) BEFORE the wallet intent, with a keep-alive so the process can't be frozen
        // while the wallet is in front, and a probe that logs any freeze.
        MwaKeepAliveService.start(ctx.applicationContext)
        val probe = FreezeProbe().start()
        val lifeLog = launcher.watchLifecycle()
        val future = try {
            scenario.start().also { WalletDiag.log("scenario.start", "WebSocket client dialing from now (thread " + Thread.currentThread().name + ")") }
        } catch (t: Throwable) {
            WalletDiag.error("scenario.start failed", t)
            probe.stop(); lifeLog(); MwaKeepAliveService.stop(ctx.applicationContext)
            return Result.failure(WalletError(WalletError.Kind.FAILED, "scenario: " + (t.message ?: "")))
        }
        val sentAt = System.currentTimeMillis()
        val resultAtRef = java.util.concurrent.atomic.AtomicLong(0L)
        val done = {
            val gap = probe.stop()
            lifeLog()
            MwaKeepAliveService.stop(ctx.applicationContext)
            WalletDiag.log("connect window", "longest freeze " + gap + " ms")
        }
        try {
            withContext(Dispatchers.Main) {
                launcher.launch(intent) { code ->
                    val at = System.currentTimeMillis()
                    resultAtRef.set(at)
                    WalletDiag.log("wallet activity result", resultName(code) + " after " + (at - sentAt) + " ms")
                }
            }
        } catch (e: ActivityNotFoundException) {
            WalletDiag.error("no wallet app", e)
            done()
            runCatching { scenario.close() }
            return Result.failure(WalletError(WalletError.Kind.NO_WALLET, "No compatible wallet found."))
        } catch (t: Throwable) {
            WalletDiag.error("intent failed", t)
            done()
            runCatching { scenario.close() }
            return Result.failure(WalletError(WalletError.Kind.FAILED, "intent: " + (t.message ?: "")))
        }
        WalletDiag.log("intent sent", "waiting for the wallet's session (up to " + START_WAIT_MS / 1000 + " s)")
        return withContext(Dispatchers.IO) {
            try {
                var client: MobileWalletAdapterClient? = null
                var failure: Throwable? = null
                while (client == null && failure == null) {
                    try {
                        client = future.get(500, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        val now = System.currentTimeMillis()
                        val resultAt = resultAtRef.get()
                        if (now - sentAt > START_WAIT_MS) failure = TimeoutException("Timed out waiting for local association to be ready")
                        else if (resultAt > 0 && now - resultAt > AFTER_RESULT_MS && launcher.resumed) failure = TimeoutException("Timed out waiting for local association to be ready (wallet closed)")
                    } catch (e: ExecutionException) {
                        failure = e
                    }
                }
                if (client == null) {
                    WalletDiag.error("session not established after " + (System.currentTimeMillis() - sentAt) + " ms", failure)
                    val msg = if (failure is TimeoutException) failure.message ?: "Timed out waiting for local association to be ready" else "Failed establishing local association with wallet"
                    return@withContext Result.failure(WalletError.classify(msg, failure?.cause ?: failure))
                }
                val proto = runCatching { scenario.session.sessionProperties.protocolVersion }.getOrNull()
                WalletDiag.log("session established", "after " + (System.currentTimeMillis() - sentAt) + " ms, protocol " + proto)
                val identity = Uri.parse(IDENTITY_URI)
                val icon = Uri.parse(ICON_RELATIVE_URI)
                val auth = try {
                    val f = if (proto == SessionProperties.ProtocolVersion.LEGACY) {
                        if (!authToken.isNullOrBlank()) legacyWithToken(client, identity, icon, chain, authToken)
                        else client.authorize(identity, icon, IDENTITY_NAME, legacyCluster)
                    } else {
                        client.authorize(identity, icon, IDENTITY_NAME, chain, if (signIn != null) null else authToken?.takeIf { it.isNotBlank() }, null, null, signIn) // 1.2.0: SIWS rides on a fresh authorize
                    }
                    WalletDiag.log("authorize sent", "chain=" + (if (proto == SessionProperties.ProtocolVersion.LEGACY) legacyCluster else chain) + (if (authToken.isNullOrBlank()) "" else " (with saved token)"))
                    f.get(AUTHORIZE_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (e: ExecutionException) {
                    val c = e.cause
                    val code = (c as? JsonRpc20Client.JsonRpc20RemoteException)?.code
                    WalletDiag.error("authorize failed" + (code?.let { " (code $it)" } ?: ""), c ?: e)
                    val msg = when (code) {
                        -1 -> "Auth token invalid"
                        -3 -> "User did not authorize signing"
                        null -> c?.message ?: "Execution exception"
                        else -> "Remote exception"
                    }
                    // a saved token the wallet no longer accepts (Phantom answers a stale reauthorize with -1 in ~1 s):
                    // forget it and ask afresh in the same session, so the user sees the approve prompt, not an error.
                    // 1.2.4: the legacy path too (Phantom 26 runs as a legacy session: "could not parse session properties").
                    if (code == -1 && !authToken.isNullOrBlank()) {
                        onTokenRejected()
                        WalletDiag.log("retry", "saved token refused: fresh authorize without it")
                        try {
                            (if (proto == SessionProperties.ProtocolVersion.LEGACY) client.authorize(identity, icon, IDENTITY_NAME, legacyCluster)
                            else client.authorize(identity, icon, IDENTITY_NAME, chain, null, null, null, signIn)).get(AUTHORIZE_WAIT_MS, TimeUnit.MILLISECONDS)
                        } catch (e2: Throwable) {
                            val c2 = (e2 as? ExecutionException)?.cause ?: e2
                            val code2 = (c2 as? JsonRpc20Client.JsonRpc20RemoteException)?.code
                            WalletDiag.error("fresh authorize failed" + (code2?.let { " (code $it)" } ?: ""), c2)
                            // the wallet answered: a decline is final. No answer (session gone): the caller tries a new session.
                            return@withContext Result.failure(
                                if (code2 != null) WalletError.classify("User did not authorize signing", c2)
                                else WalletError(WalletError.Kind.FAILED, NEW_SESSION + ": " + (c2.message ?: ""), authRejected = true)
                            )
                        }
                    } else return@withContext Result.failure(WalletError.classify(msg, c))
                } catch (e: TimeoutException) {
                    WalletDiag.error("authorize timed out", e)
                    return@withContext Result.failure(WalletError(WalletError.Kind.FAILED, "Timed out while waiting for result"))
                }
                val acct = auth.accounts?.firstOrNull()?.publicKey ?: auth.publicKey
                WalletDiag.log("auth token", tokenNote(authToken, auth.authToken))
                WalletDiag.log("authorized", "account " + (acct?.let { WalletDiag.shortAddr(Base58.encode(it)) } ?: "none") + ", accounts=" + (auth.accounts?.size ?: 0) + ", " + (System.currentTimeMillis() - sentAt) + " ms after the intent")
                val out = try {
                    block(client, auth)
                } catch (e: ExecutionException) {
                    val c = e.cause
                    val code = (c as? JsonRpc20Client.JsonRpc20RemoteException)?.code
                    WalletDiag.error("request failed" + (code?.let { " (code $it)" } ?: ""), c ?: e)
                    val msg = when {
                        code == -3 -> "User did not authorize signing"
                        code == -4 || c is MobileWalletAdapterClient.NotSubmittedException -> "Not all transactions were submitted"
                        c is MobileWalletAdapterClient.InvalidPayloadsException -> "Transaction payloads invalid"
                        code != null -> "Remote exception"
                        else -> c?.message ?: "Execution exception"
                    }
                    return@withContext Result.failure(WalletError.classify(msg, c))
                } catch (e: TimeoutException) {
                    WalletDiag.error("request timed out", e)
                    return@withContext Result.failure(WalletError(WalletError.Kind.FAILED, "Timed out while waiting for result"))
                }
                // 1.2.7 timing (tablet: ~14 s from Phantom opening to its sign sheet): everything the app needs (blockhash,
                // build, simulation) is done before the intent; after it only authorize and the sign request go out.
                if (out !is Unit) WalletDiag.log("request done", (System.currentTimeMillis() - sentAt).toString() + " ms after the intent")
                Result.success(auth to out)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                WalletDiag.error("connect failed", t)
                Result.failure(WalletError.classify(t.message, t))
            } finally {
                runCatching { scenario.close().get(5, TimeUnit.SECONDS) }
                done()
                WalletDiag.log("session closed")
            }
        }
    }

    /**
     * 1.2.7 (tablet, Phantom 26 runs as a legacy session): Phantom showed "Connect" on every payment, and a token
     * that reauthorized at 20:12:39 was refused (-1) at 20:13:09. With a saved token we now send the MWA 2.0
     * `authorize` carrying `auth_token` (the spec's replacement for the deprecated `reauthorize`; a valid token is
     * reused without a prompt). Only when the wallet rejects that request shape (not -1 / -3) do we fall back to
     * `reauthorize`. -1 still means "token refused": the caller asks afresh in the same session.
     */
    private fun legacyWithToken(client: MobileWalletAdapterClient, identity: Uri, icon: Uri, chain: String, token: String): java.util.concurrent.Future<MobileWalletAdapterClient.AuthorizationResult> {
        val first = client.authorize(identity, icon, IDENTITY_NAME, chain, token, null, null, null)
        return object : java.util.concurrent.Future<MobileWalletAdapterClient.AuthorizationResult> {
            override fun cancel(b: Boolean) = first.cancel(b)
            override fun isCancelled() = first.isCancelled
            override fun isDone() = first.isDone
            override fun get(): MobileWalletAdapterClient.AuthorizationResult = get(AUTHORIZE_WAIT_MS, TimeUnit.MILLISECONDS)
            override fun get(t: Long, u: TimeUnit): MobileWalletAdapterClient.AuthorizationResult = try {
                first.get(t, u).also { WalletDiag.log("authorize", "saved token reused (authorize + auth_token)") }
            } catch (e: ExecutionException) {
                val code = (e.cause as? JsonRpc20Client.JsonRpc20RemoteException)?.code
                if (!reauthorizeFallback(code)) throw e
                WalletDiag.log("authorize", "wallet refused authorize + auth_token (code $code): reauthorize")
                client.reauthorize(identity, icon, IDENTITY_NAME, token).get(t, u)
            }
        }
    }

    /** -1 (token refused) and -3 (declined) are answers; anything else means the wallet didn't take the request shape. */
    internal fun reauthorizeFallback(code: Int?): Boolean = code != -1 && code != -3 && code != null

    /** Diagnostics: whether the wallet handed back the same token (a short fingerprint, never the token). */
    internal fun tokenNote(sent: String?, got: String?): String {
        fun fp(t: String?) = if (t.isNullOrBlank()) "none" else Integer.toHexString(t.hashCode()).takeLast(4)
        return "sent " + fp(sent) + ", got " + fp(got) + if (!sent.isNullOrBlank() && sent == got) " (same)" else ""
    }
}
