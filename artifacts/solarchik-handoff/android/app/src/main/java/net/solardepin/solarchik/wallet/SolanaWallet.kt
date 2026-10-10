package net.solardepin.solarchik.wallet

import android.content.Context
import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.RpcCluster
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import net.solardepin.solarchik.BuildConfig
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.solana.Rpc
import org.sol4k.PublicKey
import java.time.LocalDate
import java.time.ZoneOffset

data class WalletSession(val address: String, val authToken: String)

data class ClockProof(
    val address: String,
    val signature: String,
    val cluster: String,
    val kind: String,
    val authToken: String,
)

data class SentTx(val address: String, val signature: String, val cluster: String)

/**
 * Typed wallet failure so the UI can show a localized line. [signOnlyMayHelp]: the wallet refused
 * sign-and-send itself (not the player, not a timeout), so the sign-only fallback is worth a try.
 */
class WalletError(val kind: Kind, detail: String = "", val signOnlyMayHelp: Boolean = false, val authRejected: Boolean = false) :
    Exception(detail.ifBlank { kind.name }) {
    enum class Kind { NO_WALLET, DECLINED, NETWORK, FAILED }

    companion object {
        /** User-facing text for a wallet / network failure (shared by the tabs and the run). */
        fun text(ctx: android.content.Context, t: Throwable?): String = when (t) {
            is WalletError -> when (t.kind) {
                Kind.NO_WALLET -> ctx.getString(net.solardepin.solarchik.R.string.err_no_wallet)
                Kind.DECLINED -> ctx.getString(net.solardepin.solarchik.R.string.err_declined)
                Kind.NETWORK -> ctx.getString(net.solardepin.solarchik.R.string.err_network)
                Kind.FAILED -> ctx.getString(net.solardepin.solarchik.R.string.err_failed, detail(ctx, t.message))
            }
            is java.io.IOException -> ctx.getString(net.solardepin.solarchik.R.string.err_network)
            else -> ctx.getString(net.solardepin.solarchik.R.string.err_failed, detail(ctx, t?.message))
        }

        /**
         * 1.2.5: a connect failure in plain words (Test connect and Connect): not "the wallet did not sign",
         * which is about payments. -32603 is Phantom's own "Internal error" answer to the request.
         */
        fun connectText(ctx: android.content.Context, t: Throwable?): String {
            val m = listOfNotNull(t?.message, t?.cause?.message).joinToString(" ").lowercase()
            val code = ((t as? WalletError)?.cause ?: t?.cause ?: t).let { (it as? com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client.JsonRpc20RemoteException)?.code }
            return when {
                t is WalletError && t.kind == Kind.NO_WALLET -> ctx.getString(net.solardepin.solarchik.R.string.err_no_wallet)
                code == -32603 || m.contains("-32603") || m.contains("internal error") -> ctx.getString(net.solardepin.solarchik.R.string.wc_internal)
                m.contains("local association") || m.contains("websocket") -> ctx.getString(net.solardepin.solarchik.R.string.wc_no_session)
                t is WalletError && t.kind == Kind.DECLINED -> ctx.getString(net.solardepin.solarchik.R.string.wc_declined)
                m.contains("timed out") -> ctx.getString(net.solardepin.solarchik.R.string.wc_timeout)
                else -> text(ctx, t)
            }
        }

        /**
         * 0.21.7: the English detail inside the localized sentence ("Помилка гаманця: Wallet sent no
         * signature") comes from string resources for every message the app itself raises; a raw
         * wallet/RPC text is never appended (generic localized line, raw text only in logcat).
         */
        fun detail(ctx: android.content.Context, message: String?): String {
            val m = (message ?: "").trim()
            val res = when {
                m.isEmpty() || m == "FAILED" -> net.solardepin.solarchik.R.string.err_d_unknown
                m.contains("local association", ignoreCase = true) -> net.solardepin.solarchik.R.string.err_d_assoc
                m == "No account" || m.endsWith("without account") -> net.solardepin.solarchik.R.string.err_d_no_account
                m == "Wallet sent no signature" -> net.solardepin.solarchik.R.string.err_d_no_sig
                m == "Wallet returned no transaction" -> net.solardepin.solarchik.R.string.err_d_no_tx
                m == "Server sent no transaction" -> net.solardepin.solarchik.R.string.err_d_server_no_tx
                m == "Wallet signed without a message" -> net.solardepin.solarchik.R.string.err_d_no_message
                m == "Built-in wallet has no devnet SOL" -> net.solardepin.solarchik.R.string.err_d_no_devnet_sol
                m == "Built-in wallet key is unavailable" -> net.solardepin.solarchik.R.string.err_d_local_key
                m.startsWith("Transaction failed on chain") -> return ctx.getString(net.solardepin.solarchik.R.string.err_d_chain, m.substringAfter(":", "").trim().take(60))
                else -> 0
            }
            if (res != 0) return ctx.getString(res)
            // Raw wallet/RPC text is English or JSON: logged for debugging, never shown inside a localized sentence.
            runCatching { android.util.Log.w("SolanaWallet", "wallet failure: ${m.take(200)}") }
            return ctx.getString(net.solardepin.solarchik.R.string.err_d_unknown)
        }

        /**
         * Maps an MWA failure to a kind. The clientlib's own message ("User did not authorize
         * signing") and the wallet's JSON-RPC error code both count, so a decline is never retried.
         */
        fun classify(message: String?, cause: Throwable?): WalletError {
            val code = (cause as? com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client.JsonRpc20RemoteException)?.code
            val text = listOfNotNull(message?.takeIf { it.isNotBlank() }, cause?.message?.takeIf { it.isNotBlank() && it != message }).joinToString(" | ")
            val m = text.lowercase()
            return when {
                code == ERR_AUTH -> WalletError(Kind.DECLINED, text, authRejected = true)
                code == ERR_NOT_SIGNED -> WalletError(Kind.DECLINED, text)
                m.contains("no wallet") || m.contains("no compatible wallet") -> WalletError(Kind.NO_WALLET, text)
                // 1.1.6: the wallet app opened but never started its MWA session ("Timed out waiting for /
                // Failed establishing / Local association was cancelled before connected"). Not a user decline.
                m.contains("local association") -> WalletError(Kind.FAILED, text)
                m.contains("declin") || m.contains("reject") || m.contains("cancel") || m.contains("denied") ||
                    m.contains("did not authorize") || m.contains("not signed") -> WalletError(Kind.DECLINED, text)
                m.contains("auth token invalid") -> WalletError(Kind.DECLINED, text, authRejected = true)
                m.contains("timed out") || m.contains("interrupted") || m.contains("io error") || cause is java.io.IOException ->
                    WalletError(Kind.FAILED, text)
                code == ERR_NOT_SUBMITTED || m.contains("not all transactions were submitted") ||
                    code != null || m.contains("remote exception") || m.contains("json-rpc") -> WalletError(Kind.FAILED, text, signOnlyMayHelp = true)
                else -> WalletError(Kind.FAILED, text)
            }
        }

        private const val ERR_AUTH = -1
        private const val ERR_NOT_SIGNED = -3
        private const val ERR_NOT_SUBMITTED = -4
    }
}

/** One blockhash per user action: the sign-only retry signs the very same message, so if the first try did land the second is rejected as a duplicate instead of paying twice. */
class StickyBlockhash(private val fetch: suspend () -> ByteArray) {
    private var hash: ByteArray? = null
    suspend fun get(): ByteArray = hash ?: fetch().also { hash = it }
}

/**
 * 1.1.5: the dApp identity sent with every MWA authorize. The clientlib (2.0.7) REQUIRES the icon to be a
 * RELATIVE uri (resolved against identityUri) and throws "If non-null, iconRelativeUri must be a relative Uri"
 * for an absolute one, right after the wallet app was opened: 1.1.4 passed "https://solardepin.net/favicon.ico",
 * so Phantom opened, got no authorize request and every connect failed with "something went wrong".
 */
// 1.2.6: Phantom checks Digital Asset Links for the identity domain ("This app's identity could not be verified").
// app.solardepin.net (a Cloudflare Worker on our zone) serves /.well-known/assetlinks.json with this package and
// the release certificate, and /favicon.ico; the Framer site at solardepin.net cannot serve /.well-known files.
internal const val IDENTITY_URI = "https://app.solardepin.net"
internal const val ICON_RELATIVE_URI = "favicon.ico"
internal const val IDENTITY_NAME = "Solarchik"

class SolanaWallet(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE)

    /**
     * 0.21.9: the built-in devnet wallet ([LocalKey]) is in use. Chosen by the player when no wallet app
     * (Seed Vault, Phantom, Solflare) is installed; Mobile Wallet Adapter stays the preferred path.
     */
    val isLocal: Boolean get() = !mainnet && prefs.getString("kind", "") == KIND_LOCAL && LocalKey.exists(app)

    /**
     * Asked when an action needs a wallet, none is connected and no MWA wallet app is installed (or MWA
     * reports none). MainActivity shows the "built-in devnet wallet" offer, creates and funds the key, and
     * returns true when the built-in wallet is ready. Null in tests and background work: no offer.
     */
    @Volatile var offerBuiltIn: (suspend () -> Boolean)? = null

    internal fun app(): Context = app

    /** Is a Mobile Wallet Adapter wallet app installed? (`solana-wallet:` association intent) */
    fun hasWalletApp(): Boolean = walletAppCheck(app)

    /** Switches to the built-in devnet wallet (creates the key once). Returns its address. */
    fun useBuiltIn(): String {
        check(!mainnet) { "The built-in wallet is devnet dev mode only" }
        val addr = LocalKey.create(app)
        adapter.authToken = null
        prefs.edit().putString("kind", KIND_LOCAL).putString("address", addr).remove("auth").apply()
        return addr
    }

    /** Local wallet now, or offered now because there is no wallet app. False = use MWA. */
    private suspend fun useLocal(): Boolean {
        // 1.1.0: mainnet is Mobile Wallet Adapter only (Seed Vault, Phantom, Solflare). No hot wallet, no offer.
        if (mainnet) return false
        if (isLocal) return true
        if (connected) return false
        if (hasWalletApp()) return false
        return offerBuiltIn?.invoke() == true && isLocal
    }

    /** MWA said "no wallet": offer the built-in wallet before giving up. */
    private suspend fun offerAfterNoWallet(): Boolean = !mainnet && !connected && offerBuiltIn?.invoke() == true && isLocal

    private fun localKey(): org.sol4k.Keypair =
        LocalKey.keypair(app) ?: throw WalletError(WalletError.Kind.FAILED, "Built-in wallet key is unavailable")

    /** 1.2.4: waits (about 30 s at most) for a sent signature to be confirmed on chain. */
    suspend fun waitConfirmed(sig: String): Boolean {
        for (i in 0 until 25) {
            val seen = runCatching { rpc.signatureStatus(sig) }.getOrNull()
            if (seen == "confirmed" || seen == "finalized") return true
            if (seen == "failed") return false
            kotlinx.coroutines.delay(if (i < 10) 700L else 1500L)
        }
        return false
    }

    /** Sends a locally signed tx and waits until it is confirmed (or fails on chain). */
    private suspend fun sendConfirmed(raw: ByteArray): String {
        val sig = rpc.sendTransaction(raw)
        var seen: String? = null
        for (i in 0 until 40) {
            seen = runCatching { rpc.signatureStatus(sig) }.getOrNull()
            if (seen == "confirmed" || seen == "finalized") return sig
            if (seen == "failed") error("Transaction failed on chain: $sig")
            kotlinx.coroutines.delay(if (i < 10) 700L else 1500L)
        }
        error("Not confirmed yet: $sig")
    }

    /** Built-in wallet: top up from the devnet faucet when the balance is below [minLamports]. */
    suspend fun ensureLocalFunds(minLamports: Long): Boolean {
        if (mainnet || !isLocal) return true
        val have = runCatching { rpc.balanceLamports(address) }.getOrElse { return false }
        if (have >= minLamports) return true
        return LocalFunding.fund(this, minLamports).isSuccess
    }

    private suspend fun localSignAndSend(build: suspend (payer: PublicKey, blockhash: ByteArray) -> LegacyTx): Result<SentTx> = runCatching {
        val kp = localKey()
        // The built-in wallet starts with faucet SOL; below a Pro buy's cost it tops up first (a refusal is
        // not fatal: a free mint needs far less).
        runCatching { ensureLocalFunds(LOCAL_MIN_LAMPORTS) }
        val tx = build(kp.publicKey, rpc.latestBlockhash())
        tx.partialSign(kp)
        SentTx(kp.publicKey.toBase58(), sendConfirmed(tx.serialize()), clusterName)
    }.recoverCatching { throw localFailure(it) }

    /** "insufficient lamports" / "no record of a prior credit" from the RPC = the built-in wallet is empty. */
    private fun localFailure(t: Throwable): Throwable {
        val m = (t.message ?: "").lowercase()
        if (m.contains("insufficient") || m.contains("prior credit")) return WalletError(WalletError.Kind.FAILED, NO_DEVNET_SOL)
        return buildFailure(t)
    }

    init {
        migrateFrom01951(context.applicationContext)
    }

    /**
     * 0.19.51 kept only the MWA auth token. Its signed CLOCK IN remembered the account in the game
     * save, so an upgraded phone shows the same wallet instead of looking disconnected.
     */
    private fun migrateFrom01951(app: Context) {
        if (prefs.getString("address", "").orEmpty().isNotBlank()) return
        if (prefs.getString("auth", "").orEmpty().isBlank()) return
        val addr = app.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).getString("clockAddress", "").orEmpty()
        if (runCatching { Base58.decode(addr).size == 32 }.getOrDefault(false)) prefs.edit().putString("address", addr).apply()
    }
    val isSeeker: Boolean = SeekerDevice.isSeeker()

    /**
     * 1.1.0: false in release builds (mainnet by default). The unit suite sets the `solarchik.cluster=devnet`
     * system property so the older devnet tests keep their cluster; mainnet tests flip this to false.
     */
    @Volatile internal var devnetOnly: Boolean = BuildConfig.DEVNET_ONLY || System.getProperty("solarchik.cluster") == "devnet"

    /**
     * 1.1.0: hidden developer toggle (Settings → tap the version line 7 times). On = devnet dev mode, where
     * the built-in hot wallet and the devnet faucet still work for testing. Off (default) = Solana mainnet.
     * Switching drops the MWA session (the wallet authorized another cluster).
     */
    var forceDevnet: Boolean
        get() = prefs.getBoolean("forceDevnet", false)
        set(value) {
            if (value == prefs.getBoolean("forceDevnet", false)) return
            prefs.edit().putBoolean("forceDevnet", value).remove("auth").apply()
            adapter.authToken = null
            adapter.rpcCluster = rpcCluster()
        }

    /** Hidden developer options are visible (7 taps on the version line in Settings). */
    var devUnlocked: Boolean
        get() = prefs.getBoolean("devUnlocked", false)
        set(value) { prefs.edit().putBoolean("devUnlocked", value).apply() }

    /** 1.1.0: mainnet-beta on every device unless dev devnet mode is on. */
    val mainnet: Boolean get() = !devnetOnly && !forceDevnet
    val clusterName: String get() = if (mainnet) "mainnet" else "devnet"
    val rpcUrl: String get() = if (mainnet) SolarchikConfig.RPC_MAINNET else SolarchikConfig.RPC_DEVNET

    /** Explorer links for this cluster (Solscan; Orb as the second link on mainnet). */
    fun txUrl(sig: String): String = SolarchikConfig.solscanTx(sig, clusterName)
    fun accountUrl(addr: String = address): String = SolarchikConfig.solscanAccount(addr, clusterName)
    val rpc: Rpc get() = rpcOverride ?: Rpc(rpcUrl)

    /** Tests: a scripted RPC instead of the public devnet/mainnet node. */
    @Volatile internal var rpcOverride: Rpc? = null

    /** Last connected account (base58) or blank. */
    val address: String get() = prefs.getString("address", "").orEmpty()
    val connected: Boolean get() = address.isNotBlank()

    private fun rpcCluster(): RpcCluster = if (mainnet) RpcCluster.MainnetBeta else RpcCluster.Devnet

    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse(IDENTITY_URI),
            iconUri = Uri.parse(ICON_RELATIVE_URI),
            identityName = IDENTITY_NAME,
        )
    ).apply {
        rpcCluster = if (!devnetOnly && !prefs.getBoolean("forceDevnet", false)) RpcCluster.MainnetBeta else RpcCluster.Devnet
        val saved = prefs.getString("auth", "").orEmpty()
        if (saved.isNotBlank()) authToken = saved
    }

    private fun remember(token: String?, address: String? = null) {
        val edit = prefs.edit()
        if (!token.isNullOrBlank()) {
            adapter.authToken = token
            edit.putString("auth", token)
        }
        if (!address.isNullOrBlank()) edit.putString("address", address)
        edit.apply()
    }

    /** Forgets the session on this phone. The wallet app keeps its own list. */
    fun forget() {
        adapter.authToken = null
        // the built-in key itself stays (it may hold devnet SOL and agents); "Use built-in wallet" brings it back
        prefs.edit().remove("auth").remove("address").remove("kind").apply()
        adapter.rpcCluster = rpcCluster()
    }

    /** 1.2.0: the wallet app to open ("" = let Android pick / show the chooser). Set in Settings → Wallet diagnostics. */
    var walletPackage: String
        get() = prefs.getString("walletPkg", "").orEmpty()
        set(v) { prefs.edit().putString("walletPkg", v).apply() }

    /** Installed MWA wallets (package → name), in [WalletDiag.WALLETS] order. */
    fun installedWallets(): Map<String, String> = WalletDiag.WALLETS.filterKeys { pkg ->
        runCatching { app.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    suspend fun connect(sender: ActivityResultSender): Result<WalletSession> {
        if (useLocal()) return Result.success(WalletSession(address, ""))
        adapter.rpcCluster = rpcCluster()
        // 1.2.0: our own logged connect (longer wait, optional wallet package); ktx only when no launcher is live
        val launcher = directLauncher()
        if (launcher != null) {
            val pkg = walletPackage.takeIf { it.isNotBlank() && it in installedWallets() }
            val forget = { adapter.authToken = null; prefs.edit().remove("auth").apply() }
            var r = MwaDirect.authorize(app, launcher, pkg, if (mainnet) "solana:mainnet" else "solana:devnet", if (mainnet) "mainnet-beta" else "devnet", prefs.getString("auth", null), forget)
            // 1.2.4: the wallet refused the saved token and closed the session: one new session, no token (the approve prompt)
            if (r.exceptionOrNull()?.message?.startsWith(MwaDirect.NEW_SESSION) == true) {
                forget()
                val again = directLauncher()
                if (again != null) {
                    WalletDiag.log("retry", "new session without the saved token")
                    r = MwaDirect.authorize(app, again, pkg, if (mainnet) "solana:mainnet" else "solana:devnet", if (mainnet) "mainnet-beta" else "devnet", null, forget)
                }
            }
            return r.fold(
                onSuccess = { auth ->
                    val key = accountKey(auth) ?: return Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet connected without account"))
                    val addr = Base58.encode(key)
                    remember(auth.authToken, addr)
                    prefs.edit().remove("kind").apply()
                    Result.success(WalletSession(addr, auth.authToken ?: ""))
                },
                onFailure = { e ->
                    val err = e as? WalletError ?: WalletError.classify(e.message, e)
                    if (err.authRejected) { adapter.authToken = null; prefs.edit().remove("auth").apply() }
                    if (err.kind == WalletError.Kind.NO_WALLET && offerAfterNoWallet()) Result.success(WalletSession(address, "")) else Result.failure(err)
                },
            )
        }
        WalletDiag.log("connect (clientlib-ktx)", "no live launcher")
        return when (val result = adapter.connect(sender)) {
            is TransactionResult.Success -> {
                val auth = result.authResult
                val key = accountKey(auth) ?: return Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet connected without account"))
                val addr = Base58.encode(key)
                remember(auth.authToken, addr)
                prefs.edit().remove("kind").apply()
                Result.success(WalletSession(addr, auth.authToken ?: ""))
            }
            is TransactionResult.NoWalletFound ->
                if (offerAfterNoWallet()) Result.success(WalletSession(address, "")) else Result.failure(WalletError(WalletError.Kind.NO_WALLET))
            is TransactionResult.Failure -> { WalletDiag.log("ktx connect failed", result.message + " | " + WalletDiag.chain(result.e)); Result.failure(fail(result)) }
        }
    }

    /**
     * Signs and sends any transaction built by [build] with the connected MWA account as fee payer.
     * [build] runs after authorization with the payer key and a fresh blockhash, and may
     * partially sign (e.g. a new Core asset keypair) before the wallet signs slot 0.
     */
    suspend fun signAndSend(
        sender: ActivityResultSender,
        build: suspend (payer: PublicKey, blockhash: ByteArray) -> LegacyTx,
    ): Result<SentTx> {
        if (useLocal()) return localSignAndSend(build)
        val client = rpc
        val hash = StickyBlockhash { client.latestBlockhash() }
        val first = signAndSendOnce(sender, client, hash, build)
        val err = first.exceptionOrNull() ?: return first
        if (!shouldTrySignOnly(err)) return first
        // Some wallets (or Seed Vault builds) refuse signAndSend for a tx that already carries
        // another signer. Fall back to sign-only and broadcast through our own RPC, with the
        // same blockhash so a first attempt that did land cannot be paid twice.
        val second = signOnlyThenSend(sender, client, hash, build)
        return if (second.isSuccess) second else first
    }

    /** Only a wallet-side refusal of sign-and-send is retried; declines, timeouts and our own build errors are final. */
    internal fun shouldTrySignOnly(err: Throwable): Boolean =
        err is WalletError && err.kind == WalletError.Kind.FAILED && err.signOnlyMayHelp

    /** Classifies a failure; a rejected authorization drops the saved token so the next tap asks afresh. */
    /** The live direct-session launcher, when this app is in front (null in background work and the run screen). */
    private fun directLauncher(): WalletLauncher? = if (directEnabled) WalletLauncher.current?.takeIf { it.resumed } else null

    private suspend fun <T> direct(block: (MobileWalletAdapterClient, MobileWalletAdapterClient.AuthorizationResult) -> T): Result<Pair<MobileWalletAdapterClient.AuthorizationResult, T>>? {
        val l = directLauncher() ?: return null
        adapter.rpcCluster = rpcCluster()
        val pkg = walletPackage.takeIf { it.isNotBlank() && it in installedWallets() }
        return MwaDirect.transact(app, l, pkg, if (mainnet) "solana:mainnet" else "solana:devnet", if (mainnet) "mainnet-beta" else "devnet", prefs.getString("auth", null),
            onTokenRejected = { adapter.authToken = null; prefs.edit().remove("auth").apply() }, block = block)
    }

    private fun directFailure(e: Throwable): WalletError {
        val err = e as? WalletError ?: WalletError.classify(e.message, e)
        if (err.authRejected) { adapter.authToken = null; prefs.edit().remove("auth").apply() }
        return err
    }

    private fun fail(result: TransactionResult.Failure<*>): WalletError {
        WalletDiag.log("wallet request failed", result.message + " | " + WalletDiag.chain(result.e))
        val e = WalletError.classify(result.message, result.e)
        if (e.authRejected) {
            adapter.authToken = null
            prefs.edit().remove("auth").apply()
        }
        return e
    }

    /** Tests: sign requests made inside a wallet session. */
    @Volatile internal var signRequests = 0
    /** Tests: the wallet's answer to signAndSendTransactions (the signature for these bytes). */
    @Volatile internal var signSeam: ((ByteArray) -> ByteArray)? = null

    private suspend fun signAndSendOnce(
        sender: ActivityResultSender,
        client: Rpc,
        hash: StickyBlockhash,
        build: suspend (payer: PublicKey, blockhash: ByteArray) -> LegacyTx,
    ): Result<SentTx> {
        adapter.rpcCluster = rpcCluster()
        val cluster = clusterName
        var buildError: Throwable? = null
        // 1.2.6 (tablet: Phantom showed Connect, then went home, nothing to sign): the transaction is built with a
        // fresh blockhash BEFORE the wallet opens, for the connected account, so the session only has to
        // authorize and sign; nothing slow (RPC) runs while the wallet waits. A different account approved in
        // the wallet is rebuilt in the session (pure, the blockhash is already here).
        val saved = address
        val pre: Pair<String, ByteArray>? = if (saved.isBlank()) null else try {
            val bh = hash.get()
            saved to build(PublicKey(saved), bh).serialize()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            WalletDiag.error("tx build failed before the wallet opened", t)
            return Result.failure(buildFailure(t))
        }
        if (pre != null) WalletDiag.log("tx ready", "built for " + WalletDiag.shortAddr(saved) + " before opening the wallet")
        // 1.2.0: the logged direct session (same wait and wallet pick as connect) when the app is in front
        direct { client, auth ->
            val acct = accountKey(auth)?.let { Base58.encode(it) } ?: error("No account")
            val bytes = if (pre != null && pre.first == acct) pre.second else try {
                WalletDiag.log("tx rebuild", "wallet approved " + WalletDiag.shortAddr(acct))
                kotlinx.coroutines.runBlocking { build(PublicKey(acct), hash.get()) }.serialize()
            } catch (t: Throwable) {
                buildError = t
                throw t
            }
            WalletDiag.log("sign request", "signAndSendTransactions sent in the same session")
            signRequests++
            signSeam?.let { f -> return@direct MobileWalletAdapterClient.SignAndSendTransactionsResult(arrayOf(f(bytes))) }
            client.signAndSendTransactions(arrayOf(bytes), null).get(MwaDirect.AUTHORIZE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }?.let { r ->
            return r.fold(
                onSuccess = { (auth, res) ->
                    val addr = accountKey(auth)?.let { Base58.encode(it) }.orEmpty()
                    remember(auth.authToken, addr)
                    val sig = res.signatures?.firstOrNull()?.let { Base58.encode(it) }.orEmpty()
                    if (sig.isBlank()) Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet sent no signature")) else Result.success(SentTx(addr, sig, cluster))
                },
                onFailure = { Result.failure(buildError?.let(::buildFailure) ?: directFailure(it)) },
            )
        }
        val result = try {
            adapter.transact(sender) { auth ->
                val payer = PublicKey(accountKey(auth) ?: error("No account"))
                val tx = try {
                    build(payer, hash.get())
                } catch (t: Throwable) {
                    buildError = t
                    throw t
                }
                signAndSendTransactions(arrayOf(tx.serialize()))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && buildError == null) throw t
            return Result.failure(buildError?.let(::buildFailure) ?: WalletError.classify(t.message, t))
        }
        return when (result) {
            is TransactionResult.Success -> {
                val addr = accountKey(result.authResult)?.let { Base58.encode(it) }.orEmpty()
                remember(result.authResult.authToken, addr)
                val sig = result.payload.signatures.firstOrNull()?.let { Base58.encode(it) }.orEmpty()
                if (sig.isBlank()) Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet sent no signature"))
                else Result.success(SentTx(addr, sig, cluster))
            }
            is TransactionResult.NoWalletFound -> Result.failure(WalletError(WalletError.Kind.NO_WALLET))
            is TransactionResult.Failure ->
                Result.failure(buildError?.let(::buildFailure) ?: fail(result))
        }
    }

    private suspend fun signOnlyThenSend(
        sender: ActivityResultSender,
        client: Rpc,
        hash: StickyBlockhash,
        build: suspend (payer: PublicKey, blockhash: ByteArray) -> LegacyTx,
    ): Result<SentTx> {
        adapter.rpcCluster = rpcCluster()
        val cluster = clusterName
        var buildError: Throwable? = null
        direct { client, auth ->
            val payer = PublicKey(accountKey(auth) ?: error("No account"))
            val tx = try {
                kotlinx.coroutines.runBlocking { build(payer, hash.get()) }
            } catch (t: Throwable) {
                buildError = t
                throw t
            }
            client.signTransactions(arrayOf(tx.serialize())).get(MwaDirect.AUTHORIZE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }?.let { r ->
            return r.fold(
                onSuccess = { (auth, res) ->
                    val addr = accountKey(auth)?.let { Base58.encode(it) }.orEmpty()
                    remember(auth.authToken, addr)
                    val signed = res.signedPayloads?.firstOrNull()
                        ?: return Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet returned no transaction"))
                    runCatching { client.sendTransaction(signed) }.map { SentTx(addr, it, cluster) }.recoverCatching { throw buildFailure(it) }
                },
                onFailure = { Result.failure(buildError?.let(::buildFailure) ?: directFailure(it)) },
            )
        }
        val result = try {
            adapter.transact(sender) { auth ->
                val payer = PublicKey(accountKey(auth) ?: error("No account"))
                val tx = try {
                    build(payer, hash.get())
                } catch (t: Throwable) {
                    buildError = t
                    throw t
                }
                @Suppress("DEPRECATION")
                signTransactions(arrayOf(tx.serialize()))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && buildError == null) throw t
            return Result.failure(buildError?.let(::buildFailure) ?: WalletError.classify(t.message, t))
        }
        return when (result) {
            is TransactionResult.Success -> {
                val addr = accountKey(result.authResult)?.let { Base58.encode(it) }.orEmpty()
                remember(result.authResult.authToken, addr)
                val signed = result.payload.signedPayloads.firstOrNull()
                    ?: return Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet returned no transaction"))
                runCatching { client.sendTransaction(signed) }
                    .map { SentTx(addr, it, cluster) }
                    .recoverCatching { throw buildFailure(it) }
            }
            is TransactionResult.NoWalletFound -> Result.failure(WalletError(WalletError.Kind.NO_WALLET))
            is TransactionResult.Failure -> Result.failure(fail(result))
        }
    }

    suspend fun balanceSol(): Result<Double> = runCatching {
        val addr = address
        require(addr.isNotBlank()) { "not connected" }
        rpc.balanceLamports(addr) / SolarchikConfig.LAMPORTS_PER_SOL.toDouble()
    }

    /** Devnet only. Never called on mainnet (1.1.0: the faucet is refused before any request). */
    suspend fun airdrop(): Result<String> = runCatching {
        check(!mainnet) { "airdrop is devnet only" }
        if (isLocal) return LocalFunding.fund(this, LOCAL_MIN_LAMPORTS)
        check(!mainnet) { "airdrop is devnet only" }
        val addr = address
        require(addr.isNotBlank()) { "not connected" }
        rpc.requestAirdrop(addr, SolarchikConfig.lamports(SolarchikConfig.AIRDROP_SOL))
    }

    suspend fun clockInOnChain(
        sender: ActivityResultSender,
        meters: Int,
        score: Int,
        streak: Int,
        day: String = LocalDate.now(ZoneOffset.UTC).toString(),
    ): Result<ClockProof> {
        val memo = MemoTx.clockMemo(day, meters, streak)
        if (useLocal()) return localMemo(memo)
        val sent = sendMemo(sender, memo)
        if (sent.isSuccess) return sent
        val err = sent.exceptionOrNull()
        if (stopAfter(err)) return Result.failure(err ?: WalletError(WalletError.Kind.DECLINED))
        return signMessage(sender, memo)
    }

    /** CLOCK IN with the built-in wallet: a devnet memo tx (or a detached signature when it has no SOL for the fee). */
    private suspend fun localMemo(memo: String): Result<ClockProof> = runCatching {
        val kp = localKey()
        val addr = kp.publicKey.toBase58()
        ensureLocalFunds(10_000L)
        val sent = runCatching {
            val raw = LocalKey.signSlot(MemoTx.build(kp.publicKey.bytes(), rpc.latestBlockhash(), memo), kp)
            sendConfirmed(raw)
        }
        sent.fold(
            onSuccess = { ClockProof(addr, it, clusterName, "tx", "") },
            onFailure = { ClockProof(addr, Base58.encode(kp.sign(memo.encodeToByteArray())), clusterName, "message", "") },
        )
    }

    private suspend fun sendMemo(sender: ActivityResultSender, memo: String): Result<ClockProof> {
        adapter.rpcCluster = rpcCluster()
        val cluster = clusterName
        val blockhash = runCatching { rpc.latestBlockhash() }.getOrElse {
            return Result.failure(WalletError(WalletError.Kind.NETWORK, it.message ?: ""))
        }
        return when (
            val result = adapter.transact(sender) { auth ->
                val payer = accountKey(auth) ?: error("No account")
                val tx = MemoTx.build(payer, blockhash, memo)
                signAndSendTransactions(arrayOf(tx))
            }
        ) {
            is TransactionResult.Success -> {
                val payer = accountKey(result.authResult)?.let { Base58.encode(it) } ?: ""
                remember(result.authResult.authToken, payer)
                val sig = result.payload.signatures.firstOrNull()?.let { Base58.encode(it) } ?: ""
                if (sig.isBlank()) Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet sent no signature"))
                else Result.success(ClockProof(payer, sig, cluster, "tx", result.authResult.authToken ?: ""))
            }
            is TransactionResult.NoWalletFound -> Result.failure(WalletError(WalletError.Kind.NO_WALLET))
            is TransactionResult.Failure -> Result.failure(fail(result))
        }
    }

    /**
     * Strategy NFTs: the server built and co-signed [txs] with this wallet as owner/payer.
     * One wallet prompt signs them all (sign-only); they are then sent in order through our RPC,
     * each confirmed before the next (the second strategy tx needs the first on chain).
     */
    suspend fun signServerTxs(sender: ActivityResultSender, txs: List<ByteArray>): Result<String> {
        if (txs.isEmpty()) return Result.failure(WalletError(WalletError.Kind.FAILED, "Server sent no transaction"))
        if (useLocal()) return runCatching {
            val kp = localKey()
            var last = ""
            for (raw in txs) last = sendConfirmed(LocalKey.signSlot(raw, kp))
            last
        }.recoverCatching { throw localFailure(it) }
        adapter.rpcCluster = rpcCluster()
        val result = try {
            adapter.transact(sender) { _ ->
                @Suppress("DEPRECATION")
                signTransactions(txs.toTypedArray())
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return Result.failure(WalletError.classify(t.message, t))
        }
        return when (result) {
            is TransactionResult.Success -> runCatching {
                val signed = result.payload.signedPayloads
                check(signed.size == txs.size) { "Wallet returned ${signed.size} of ${txs.size} transactions" }
                var last = ""
                for (raw in signed) {
                    last = rpc.sendTransaction(raw)
                    var seen: String? = null
                    for (i in 0 until 30) {
                        seen = rpc.signatureStatus(last)
                        if (seen == "confirmed" || seen == "finalized") break
                        if (seen == "failed") error("Transaction failed on chain: $last")
                        kotlinx.coroutines.delay(1000)
                    }
                    check(seen == "confirmed" || seen == "finalized") { "Not confirmed yet: $last" }
                }
                last
            }.recoverCatching { throw buildFailure(it) }
            is TransactionResult.NoWalletFound -> Result.failure(WalletError(WalletError.Kind.NO_WALLET))
            is TransactionResult.Failure -> Result.failure(fail(result))
        }
    }

    /**
     * 1.1.0: signs and sends one prebuilt transaction (a Jupiter v0 swap) through Mobile Wallet Adapter.
     * The wallet shows it and the user approves or declines there; nothing is ever signed by the app.
     * [prepare] runs after authorization with the wallet's account and returns the serialized unsigned tx
     * (so the tx is built for the account the wallet actually picked). Mainnet MWA only.
     */
    suspend fun signAndSendPrebuilt(
        sender: ActivityResultSender,
        prepare: suspend (owner: String) -> ByteArray,
    ): Result<SentTx> {
        if (!mainnet && isLocal) return Result.failure(WalletError(WalletError.Kind.FAILED, "Real swaps need a mainnet wallet app"))
        adapter.rpcCluster = rpcCluster()
        val cluster = clusterName
        var buildError: Throwable? = null
        val result = try {
            adapter.transact(sender) { auth ->
                val owner = Base58.encode(accountKey(auth) ?: error("No account"))
                val tx = try { prepare(owner) } catch (t: Throwable) { buildError = t; throw t }
                signAndSendTransactions(arrayOf(tx))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && buildError == null) throw t
            return Result.failure(buildError?.let(::buildFailure) ?: WalletError.classify(t.message, t))
        }
        return when (result) {
            is TransactionResult.Success -> {
                val addr = accountKey(result.authResult)?.let { Base58.encode(it) }.orEmpty()
                remember(result.authResult.authToken, addr)
                val sig = result.payload.signatures.firstOrNull()?.let { Base58.encode(it) }.orEmpty()
                if (sig.isBlank()) Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet sent no signature"))
                else Result.success(SentTx(addr, sig, cluster))
            }
            is TransactionResult.NoWalletFound -> Result.failure(WalletError(WalletError.Kind.NO_WALLET))
            is TransactionResult.Failure -> Result.failure(buildError?.let(::buildFailure) ?: fail(result))
        }
    }

    /** 1.2.0: what the worker needs to check a Sign-In-With-Solana (raw bytes; the worker gets them base64). */
    class SiwsProof(val publicKey: ByteArray, val signedMessage: ByteArray, val signature: ByteArray)

    /**
     * 1.2.0 Verified Seeker: Sign In With Solana through MWA with the worker's payload. A wallet that answers the
     * sign_in_payload returns its sign_in_result; one that ignores it (older wallets) signs the same SIWS message
     * as a detached message instead, which the worker checks the same way (fields + Ed25519).
     */
    suspend fun signInWithSolana(payloadJson: org.json.JSONObject): Result<SiwsProof> {
        val payload = runCatching { com.solana.mobilewalletadapter.common.signin.SignInWithSolana.Payload.fromJson(payloadJson) }
            .getOrElse { return Result.failure(WalletError(WalletError.Kind.FAILED, "bad sign-in payload")) }
        if (useLocal()) return runCatching {
            val kp = localKey()
            val msg = payload.prepareMessage(kp.publicKey.bytes()).encodeToByteArray()
            SiwsProof(kp.publicKey.bytes(), msg, kp.sign(msg))
        }
        val l = directLauncher() ?: return Result.failure(WalletError(WalletError.Kind.FAILED, "Timed out waiting for local association to be ready"))
        val pkg = walletPackage.takeIf { it.isNotBlank() && it in installedWallets() }
        WalletDiag.log("sign in with solana", "nonce " + (payload.nonce ?: "").take(6) + "…")
        val r = MwaDirect.transact(app, l, pkg, if (mainnet) "solana:mainnet" else "solana:devnet", if (mainnet) "mainnet-beta" else "devnet", null, signIn = payload) { client, auth ->
            val si = auth.signInResult
            if (si != null && si.publicKey.size == 32 && si.signature.size == 64) SiwsProof(si.publicKey, si.signedMessage, si.signature)
            else {
                val key = accountKey(auth) ?: error("No account")
                val msg = payload.prepareMessage(key).encodeToByteArray()
                val signed = client.signMessagesDetached(arrayOf(msg), arrayOf(key)).get(MwaDirect.AUTHORIZE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                val sig = signed.messages.firstOrNull()?.signatures?.firstOrNull() ?: error("Wallet sent no signature")
                SiwsProof(key, msg, sig)
            }
        }
        return r.fold(
            onSuccess = { (auth, proof) ->
                WalletDiag.log("signed in", if (auth.signInResult != null) "sign_in_result" else "detached message")
                remember(auth.authToken, Base58.encode(proof.publicKey))
                Result.success(proof)
            },
            onFailure = { Result.failure(directFailure(it)) },
        )
    }

    /** Detached signature of [message] by the connected account (address + base58 signature). */
    suspend fun signText(sender: ActivityResultSender, message: String): Result<ClockProof> = signMessage(sender, message)

    private suspend fun signMessage(sender: ActivityResultSender, message: String): Result<ClockProof> {
        val bytes = message.encodeToByteArray()
        if (useLocal()) return runCatching {
            val kp = localKey()
            ClockProof(kp.publicKey.toBase58(), Base58.encode(kp.sign(bytes)), clusterName, "message", "")
        }
        val cluster = clusterName
        return when (
            val result = adapter.transact(sender) { auth ->
                val key = accountKey(auth) ?: error("No account")
                signMessagesDetached(arrayOf(bytes), arrayOf(key))
            }
        ) {
            is TransactionResult.Success -> {
                val signed = result.payload.messages.firstOrNull()
                    ?: return Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet signed without a message"))
                val rawSig = signed.signatures.firstOrNull()
                    ?: return Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet sent no signature"))
                val sig = Base58.encode(rawSig)
                if (sig.length < 32) return Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet sent no signature"))
                val fromMsg = signed.addresses.firstOrNull()?.let { Base58.encode(it) }.orEmpty()
                val payer = fromMsg.ifBlank {
                    accountKey(result.authResult)?.let { Base58.encode(it) } ?: ""
                }
                remember(result.authResult.authToken, payer)
                if (payer.isBlank()) Result.failure(WalletError(WalletError.Kind.FAILED, "Wallet signed without account"))
                else Result.success(ClockProof(payer, sig, cluster, "message", result.authResult.authToken ?: ""))
            }
            is TransactionResult.NoWalletFound -> Result.failure(WalletError(WalletError.Kind.NO_WALLET))
            is TransactionResult.Failure -> Result.failure(fail(result))
        }
    }

    companion object {
        const val KIND_LOCAL = "local"
        const val NO_DEVNET_SOL = "Built-in wallet has no devnet SOL"
        /** A Pro buy is 0.1 SOL + rent + fee: the built-in wallet tops up below this. */
        const val LOCAL_MIN_LAMPORTS = 115_000_000L

        /** Test seam for [hasWalletApp]. */
        /** 1.2.0: our logged direct MWA session (connect + sign). Tests that drive the clientlib-ktx path may turn it off. */
        @Volatile var directEnabled = true

        @Volatile var walletAppCheck: (Context) -> Boolean = { ctx ->
            val probe = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("solana-wallet:/v1/associate/local?association=probe&port=1"))
                .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            runCatching { ctx.packageManager.queryIntentActivities(probe, 0).isNotEmpty() }.getOrDefault(false)
        }
    }

    private fun buildFailure(t: Throwable): Throwable =
        if (t is java.io.IOException || t is net.solardepin.solarchik.solana.RpcException) WalletError(WalletError.Kind.NETWORK, t.message ?: "") else t

    private fun stopAfter(error: Throwable?): Boolean {
        if (error is WalletError) return error.kind == WalletError.Kind.NO_WALLET || error.kind == WalletError.Kind.DECLINED
        return false
    }

    private fun accountKey(auth: MobileWalletAdapterClient.AuthorizationResult): ByteArray? {
        val accounts = auth.accounts
        if (accounts != null && accounts.isNotEmpty()) return accounts[0].publicKey
        return auth.publicKey
    }
}

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val zeros = input.takeWhile { it.toInt() == 0 }.size
        val encoded = ByteArray(input.size * 2)
        var outputStart = encoded.size
        val bytes = input.copyOf()
        var start = zeros
        while (start < bytes.size) {
            var remainder = 0
            for (i in start until bytes.size) {
                val acc = (bytes[i].toInt() and 0xff) + remainder * 256
                bytes[i] = (acc / 58).toByte()
                remainder = acc % 58
            }
            encoded[--outputStart] = ALPHABET[remainder].code.toByte()
            while (start < bytes.size && bytes[start].toInt() == 0) start++
        }
        return "1".repeat(zeros) + String(encoded, outputStart, encoded.size - outputStart)
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val zeros = input.takeWhile { it == '1' }.length
        val bytes = ByteArray(input.length)
        var length = 0
        for (ch in input) {
            var carry = ALPHABET.indexOf(ch)
            require(carry >= 0) { "bad base58" }
            for (i in 0 until length) {
                carry += 58 * (bytes[i].toInt() and 0xff)
                bytes[i] = (carry % 256).toByte()
                carry /= 256
            }
            while (carry > 0) {
                bytes[length++] = (carry % 256).toByte()
                carry /= 256
            }
        }
        val out = ByteArray(zeros + length)
        for (i in 0 until length) out[zeros + length - 1 - i] = bytes[i]
        return out
    }
}
