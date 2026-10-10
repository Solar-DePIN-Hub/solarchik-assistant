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
    ): Result<MobileWalletAdapterClient.AuthorizationResult> {
        override?.let { return it(pkg) }
        return transact(ctx, launcher, pkg, chain, legacyCluster, authToken) { _, _ -> Unit }.map { it.first }
    }

    /** Test seam for [transact]: the authorize result (the block then runs against [FAKE_CLIENT]). */
    @Volatile var FAKE_CLIENT: MobileWalletAdapterClient? = null
    @Volatile var transactOverride: (suspend (pkg: String?) -> Result<MobileWalletAdapterClient.AuthorizationResult>)? = null

    /**
     * One MWA session: associate, authorize (with the saved token when there is one), then [block] on the IO
     * thread with the live client (sign / sign-and-send; blocking futures are fine there).
     */
    suspend fun <T> transact(
        ctx: Context,
        launcher: WalletLauncher,
        pkg: String?,
        chain: String,
        legacyCluster: String,
        authToken: String?,
        signIn: com.solana.mobilewalletadapter.common.signin.SignInWithSolana.Payload? = null,
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
        val intent = LocalAssociationIntentCreator.createAssociationIntent(null, scenario.port, scenario.session)
        if (!pkg.isNullOrBlank()) intent.setPackage(pkg)
        val handlers = WalletDiag.handlers(ctx, intent)
        WalletDiag.log("intent", "port=" + scenario.port + " target=" + (pkg ?: "any wallet") + " uri=" + WalletDiag.safeUri(intent.data))
        WalletDiag.log("handlers", if (handlers.isEmpty()) "none" else handlers.joinToString(" | "))
        val sentAt = System.currentTimeMillis()
        val resultAtRef = java.util.concurrent.atomic.AtomicLong(0L)
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
            runCatching { scenario.close() }
            return Result.failure(WalletError(WalletError.Kind.NO_WALLET, "No compatible wallet found."))
        } catch (t: Throwable) {
            WalletDiag.error("intent failed", t)
            runCatching { scenario.close() }
            return Result.failure(WalletError(WalletError.Kind.FAILED, "intent: " + (t.message ?: "")))
        }
        WalletDiag.log("intent sent", "waiting for the wallet's session (up to " + START_WAIT_MS / 1000 + " s)")
        return withContext(Dispatchers.IO) {
            try {
                val future = scenario.start()
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
                        if (!authToken.isNullOrBlank()) client.reauthorize(identity, icon, IDENTITY_NAME, authToken)
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
                    // a saved token the wallet no longer accepts: one fresh authorize in the same session
                    if (code == -1 && !authToken.isNullOrBlank() && proto != SessionProperties.ProtocolVersion.LEGACY) {
                        WalletDiag.log("retry", "authorize without the saved token")
                        try {
                            client.authorize(identity, icon, IDENTITY_NAME, chain, null, null, null, null).get(AUTHORIZE_WAIT_MS, TimeUnit.MILLISECONDS)
                        } catch (e2: Throwable) {
                            WalletDiag.error("authorize failed again", (e2 as? ExecutionException)?.cause ?: e2)
                            return@withContext Result.failure(WalletError.classify("User did not authorize signing", (e2 as? ExecutionException)?.cause ?: e2))
                        }
                    } else return@withContext Result.failure(WalletError.classify(msg, c))
                } catch (e: TimeoutException) {
                    WalletDiag.error("authorize timed out", e)
                    return@withContext Result.failure(WalletError(WalletError.Kind.FAILED, "Timed out while waiting for result"))
                }
                val acct = auth.accounts?.firstOrNull()?.publicKey ?: auth.publicKey
                WalletDiag.log("authorized", "account " + (acct?.let { WalletDiag.shortAddr(Base58.encode(it)) } ?: "none") + ", accounts=" + (auth.accounts?.size ?: 0))
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
                if (out !is Unit) WalletDiag.log("request done")
                Result.success(auth to out)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                WalletDiag.error("connect failed", t)
                Result.failure(WalletError.classify(t.message, t))
            } finally {
                runCatching { scenario.close().get(5, TimeUnit.SECONDS) }
                WalletDiag.log("session closed")
            }
        }
    }
}
