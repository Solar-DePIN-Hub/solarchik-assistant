package net.solardepin.solarchik.swap

import android.content.Context
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.wallet.SentTx
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** The daily DCA agent: once a day it prepares one small buy; the user reviews and confirms it in the wallet. */
data class DcaAgent(val enabled: Boolean = false, val to: String = "USDC", val amountSol: Double = 0.01, val lastDay: String = "")

/** Policy, spend ledger and the DCA agent, on this phone only. */
class SwapStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("solarchik.swap", Context.MODE_PRIVATE)

    fun policy(): SwapPolicy = SwapPolicy(
        enabled = p.getBoolean("enabled", false),
        riskAcceptedAt = p.getLong("riskAt", 0L),
        // stored in lamports: a Float 0.05 reads back as 0.0500000007 and 0.02 as 0.0199999
        dayCapSol = p.getLong("capLamports", lamports(SwapPolicy.DEFAULT_DAY_CAP_SOL)) / 1e9,
        maxSlippageBps = p.getInt("slip", SwapPolicy.DEFAULT_SLIPPAGE_BPS),
    ).clamped()

    fun setPolicy(v: SwapPolicy) {
        val c = v.clamped()
        p.edit().putBoolean("enabled", c.enabled).putLong("riskAt", c.riskAcceptedAt)
            .putLong("capLamports", lamports(c.dayCapSol)).putInt("slip", c.maxSlippageBps).apply()
    }

    fun records(): List<SwapRecord> = runCatching {
        val a = JSONArray(p.getString("records", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SwapRecord(o.getLong("at"), o.getString("day"), o.getString("sig"), o.getString("from"), o.getString("to"),
                o.getLong("in"), o.getLong("out"), o.getDouble("sol"), o.optString("status", SwapRecord.STATUS_SENT), o.optString("by", ""))
        }
    }.getOrDefault(emptyList())

    fun add(r: SwapRecord) = writeRecords((records() + r).takeLast(60))

    fun setStatus(sig: String, status: String) = writeRecords(records().map { if (it.signature == sig) it.copy(status = status) else it })

    private fun writeRecords(list: List<SwapRecord>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("at", r.at).put("day", r.day).put("sig", r.signature).put("from", r.from).put("to", r.to)
                .put("in", r.inAmount).put("out", r.outAmount).put("sol", r.solValue).put("status", r.status).put("by", r.by))
        }
        p.edit().putString("records", a.toString()).apply()
    }

    fun dca(): DcaAgent = DcaAgent(
        p.getBoolean("dcaOn", false), p.getString("dcaTo", "USDC").orEmpty(),
        p.getLong("dcaLamports", 10_000_000L) / 1e9, p.getString("dcaDay", "").orEmpty(),
    )

    fun setDca(d: DcaAgent) {
        p.edit().putBoolean("dcaOn", d.enabled).putString("dcaTo", d.to).putLong("dcaLamports", lamports(d.amountSol)).putString("dcaDay", d.lastDay).apply()
    }

    fun clear() = p.edit().clear().apply()

    private fun lamports(sol: Double): Long = Math.round(sol * 1e9)
}

/** A reviewed swap, ready for the wallet. */
data class PreparedSwap(
    val request: SwapRequest,
    val quote: SwapQuote,
    val build: SwapBuild,
    val owner: String,
    val solValue: Double,
    val fees: SwapFees,
    val at: Long,
)

class SwapException(val blocks: List<SwapBlock>, detail: String = "") : Exception(detail.ifBlank { blocks.joinToString() })

/**
 * Prepares and executes real Jupiter swaps on mainnet. Every check runs twice (when preparing the review card and
 * again right before the wallet opens). The app never signs: [SolanaWallet.signAndSendPrebuilt] hands the
 * transaction to the wallet app, where the user approves or declines it.
 */
class SwapDesk(
    context: Context,
    private val jup: JupiterApi = JupiterApi(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private val app = context.applicationContext
    val store = SwapStore(app)
    private val lock = Mutex()

    /** SOL-equivalent of [req]'s input: exact for SOL, a read-only Jupiter quote into SOL otherwise. */
    suspend fun solValue(req: SwapRequest): Double {
        if (req.from.mint == SwapTokens.SOL.mint) return SwapTokens.SOL.fromRaw(req.amountRaw)
        val q = jup.quote(req.from, SwapTokens.SOL, req.amountRaw, SwapPolicy.DEFAULT_SLIPPAGE_BPS)
        return SwapTokens.SOL.fromRaw(q.outAmount)
    }

    /** Builds the review: limits, quote, Jupiter tx for [wallet]'s account, fees. Nothing is signed. */
    suspend fun prepare(wallet: SolanaWallet, req: SwapRequest, rpc: Rpc = wallet.rpc): Result<PreparedSwap> = runCatching {
        val policy = store.policy()
        val owner = wallet.address
        if (owner.isBlank()) throw WalletError(WalletError.Kind.NO_WALLET)
        val early = SwapGuard.precheck(policy, store.records(), today(), req, 0.0, wallet.mainnet)
        if (early.isNotEmpty()) throw SwapException(early)
        val value = solValue(req)
        val pre = SwapGuard.precheck(policy, store.records(), today(), req, value, wallet.mainnet)
        if (pre.isNotEmpty()) throw SwapException(pre)
        var q = jup.quote(req.from, req.to, req.amountRaw, policy.maxSlippageBps)
        var build = jup.swapTx(q, owner)
        if (build.tx.size > TxCheck.MAX_TX_BYTES) {
            // a long route does not fit one transaction: ask for a smaller one
            q = jup.quote(req.from, req.to, req.amountRaw, policy.maxSlippageBps, JupiterApi.SMALL_MAX_ACCOUNTS)
            build = jup.swapTx(q, owner)
        }
        val qb = SwapGuard.checkQuote(policy, req, q)
        if (qb.isNotEmpty()) throw SwapException(qb)
        TxCheck.problem(build.tx, owner)?.let { throw SwapException(listOf(SwapBlock.BAD_TX), it) }
        build.simulationError?.let { throw SwapException(listOf(SwapBlock.SIMULATION_FAILED), it) }
        val needsAta = req.to.mint != SwapTokens.SOL.mint && runCatching { !hasTokenAccount(rpc, owner, req.to.mint) }.getOrDefault(false)
        PreparedSwap(
            req, q, build, owner, value,
            SwapFees(SwapFees.BASE_FEE_LAMPORTS, build.priorityFeeLamports, if (needsAta) SwapFees.ATA_RENT_LAMPORTS else 0L),
            clock(),
        )
    }

    /**
     * Opens the wallet with the reviewed swap. A stale review is re-quoted first and refused if the price moved
     * against the user by more than [SwapPolicy.REQUOTE_TOLERANCE_BPS]. The spend counts against today's cap as
     * soon as the wallet returns a signature; [track] follows it to confirmed / failed.
     */
    suspend fun execute(wallet: SolanaWallet, sender: ActivityResultSender, prepared: PreparedSwap): Result<SentTx> = lock.withLock {
        val policy = store.policy()
        val again = SwapGuard.precheck(policy, store.records(), today(), prepared.request, prepared.solValue, wallet.mainnet)
        if (again.isNotEmpty()) return@withLock Result.failure(SwapException(again))
        var use = prepared
        if (clock() - prepared.at > SwapPolicy.FRESH_MS) {
            val fresh = prepare(wallet, prepared.request).getOrElse { return@withLock Result.failure(it) }
            if (!SwapGuard.requoteOk(prepared.quote.minOut, fresh.quote.minOut)) return@withLock Result.failure(SwapException(listOf(SwapBlock.PRICE_MOVED)))
            use = fresh
        }
        val sent = wallet.signAndSendPrebuilt(sender) { owner ->
            if (owner != use.owner) throw SwapException(listOf(SwapBlock.BAD_TX), "the wallet picked another account; review again")
            TxCheck.problem(use.build.tx, owner)?.let { throw SwapException(listOf(SwapBlock.BAD_TX), it) }
            use.build.tx
        }
        sent.onSuccess {
            store.add(SwapRecord(clock(), today(), it.signature, use.request.from.symbol, use.request.to.symbol,
                use.quote.inAmount, use.quote.outAmount, use.solValue, SwapRecord.STATUS_SENT, use.request.by))
        }
        sent
    }

    /** Polls the signature (read-only) until confirmed or failed; a failed swap stops counting against the cap. */
    suspend fun track(rpc: Rpc, sig: String): String {
        repeat(40) { i ->
            val s = runCatching { rpc.signatureStatus(sig) }.getOrNull()
            if (s == "confirmed" || s == "finalized") { store.setStatus(sig, SwapRecord.STATUS_CONFIRMED); return SwapRecord.STATUS_CONFIRMED }
            if (s == "failed") { store.setStatus(sig, SwapRecord.STATUS_FAILED); return SwapRecord.STATUS_FAILED }
            kotlinx.coroutines.delay(if (i < 10) 1000L else 2000L)
        }
        return SwapRecord.STATUS_SENT
    }

    /** The DCA agent's proposal for today, or null (off, already proposed today, real swaps off, or no room under the cap). */
    fun dcaProposal(): SwapRequest? {
        val d = store.dca()
        val policy = store.policy()
        if (!d.enabled || !policy.live || d.lastDay == today()) return null
        val to = SwapTokens.bySymbol(d.to)?.takeIf { it.mint != SwapTokens.SOL.mint } ?: return null
        val amt = minOf(d.amountSol, SwapGuard.remaining(policy, store.records(), today()))
        if (amt < 0.001) return null
        return SwapRequest(SwapTokens.SOL, to, Math.round(amt * 1e9), by = "dca", reason = "daily")
    }

    fun dcaHandled() { store.setDca(store.dca().copy(lastDay = today())) }

    companion object {
        /** Read-only: does [owner] already hold a token account for [mint]? */
        suspend fun hasTokenAccount(rpc: Rpc, owner: String, mint: String): Boolean {
            val r = rpc.call("getTokenAccountsByOwner", buildJsonArray {
                add(JsonPrimitive(owner))
                add(buildJsonObject { put("mint", mint) })
                add(buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed") })
            })
            return ((r as? JsonObject)?.get("value") as? JsonArray)?.isNotEmpty() == true
        }
    }
}
