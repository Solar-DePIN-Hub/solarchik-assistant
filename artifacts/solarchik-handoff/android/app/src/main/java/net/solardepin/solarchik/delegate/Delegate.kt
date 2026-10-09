package net.solardepin.solarchik.delegate

import android.content.Context
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.solana.SystemIx
import net.solardepin.solarchik.swap.JupiterApi
import net.solardepin.solarchik.swap.SwapGuard
import net.solardepin.solarchik.swap.SwapPolicy
import net.solardepin.solarchik.swap.SwapQuote
import net.solardepin.solarchik.swap.SwapRequest
import net.solardepin.solarchik.swap.SwapToken
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.swap.TxCheck
import net.solardepin.solarchik.wallet.Base58
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SentTx
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError
import org.json.JSONArray
import org.json.JSONObject
import org.sol4k.Keypair
import org.sol4k.PublicKey
import java.time.LocalDate

/**
 * 1.1.0 EXPERIMENTAL delegated limit. OFF by default, real funds, at the user's own risk.
 *
 * The user signs ONE SPL Token ApproveChecked in their wallet: the app's agent key may move up to N units of one
 * token (USDC by default) out of the user's own token account. The agent key then makes small Jupiter swaps
 * without a wallet prompt: (1) it pulls the amount into its own token account as the approved delegate,
 * (2) it swaps there via Jupiter with the output sent straight to the user's wallet (destinationTokenAccount /
 * nativeDestinationAccount). Jupiter's swap API cannot spend from a token account the signer only holds a
 * delegation on, hence the two steps. If step 2 fails the pulled tokens are sent back at once. Jupiter's v1 API
 * supports destinationTokenAccount but not nativeDestinationAccount, so a swap into SOL takes a third step: the
 * agent forwards exactly the SOL that arrived.
 * Every action is checked against the user's per-action and per-day caps, the on-chain remaining allowance and
 * an expiry. Revoke (signed in the wallet) and withdraw (agent SOL and tokens back to the user) are always available.
 * Actions are signed by the agent key, NOT the user's wallet / Seed Vault, so Seeker Season may not count them.
 */
data class DelegatePolicy(
    val enabled: Boolean = false,
    val riskAcceptedAt: Long = 0L,
    val token: String = "USDC",
    val output: String = "SOL",
    /** The allowance the user wants approved (raw units of [token]). */
    val allowanceRaw: Long = 5_000_000L,
    val perActionRaw: Long = 1_000_000L,
    val perDayRaw: Long = 2_000_000L,
    val expiryDays: Int = DEFAULT_EXPIRY_DAYS,
    val slippageBps: Int = SwapPolicy.DEFAULT_SLIPPAGE_BPS,
    /** When the user last signed an Approve, and for how much (raw). */
    val approvedAt: Long = 0L,
    val approvedRaw: Long = 0L,
    /** One small agent swap per day at a random time inside waking hours, without a prompt. */
    val autoDaily: Boolean = false,
) {
    val live: Boolean get() = enabled && riskAcceptedAt > 0L
    val tokenInfo: SwapToken get() = DelegateRules.tokenOf(token)
    val outputInfo: SwapToken get() = SwapTokens.bySymbol(output)?.takeIf { it.mint != tokenInfo.mint } ?: SwapTokens.SOL.takeIf { it.mint != tokenInfo.mint } ?: SwapTokens.USDC
    fun expiresAt(): Long = if (approvedAt <= 0L) 0L else approvedAt + expiryDays * 86_400_000L

    fun clamped(): DelegatePolicy {
        val t = DelegateRules.tokenOf(token)
        val hard = DelegateRules.hardAllowanceRaw(t)
        val min = DelegateRules.minActionRaw(t)
        val allowance = allowanceRaw.coerceIn(min, hard)
        val perDay = perDayRaw.coerceIn(min, allowance)
        return copy(
            token = t.symbol,
            allowanceRaw = allowance,
            perDayRaw = perDay,
            perActionRaw = perActionRaw.coerceIn(min, perDay),
            expiryDays = expiryDays.coerceIn(1, MAX_EXPIRY_DAYS),
            slippageBps = slippageBps.coerceIn(SwapPolicy.MIN_SLIPPAGE_BPS, SwapPolicy.HARD_SLIPPAGE_BPS),
            output = outputInfo.symbol,
        )
    }

    companion object {
        const val DEFAULT_EXPIRY_DAYS = 7
        const val MAX_EXPIRY_DAYS = 30
    }
}

/** On-chain facts, read before every action (read-only RPC). */
data class DelegateChainState(
    val ownerTokenAccount: String,
    val exists: Boolean,
    val balanceRaw: Long,
    val delegate: String?,
    val delegatedRaw: Long,
    val agentLamports: Long,
) {
    fun remainingFor(agent: String): Long = if (delegate == agent) delegatedRaw else 0L
}

data class DelegateRecord(
    val at: Long,
    val day: String,
    val kind: String,
    val amountRaw: Long,
    val outRaw: Long,
    val signature: String,
    val status: String,
    val note: String = "",
) {
    companion object {
        const val APPROVE = "approve"
        const val REVOKE = "revoke"
        const val SWAP = "swap"
        const val PULL = "pull"
        const val RETURN = "return"
        const val DELIVER = "deliver"
        const val WITHDRAW = "withdraw"
        const val TOPUP = "topup"
        const val OK = "confirmed"
        const val SENT = "sent"
        const val FAILED = "failed"
    }
}

enum class DelegateBlock {
    NOT_MAINNET, OFF, RISK_NOT_ACCEPTED, NO_AGENT, NOT_APPROVED, EXPIRED, ZERO_AMOUNT, OVER_ACTION_CAP, OVER_DAY_CAP,
    OVER_ALLOWANCE, OVER_BALANCE, LOW_AGENT_SOL, TOKEN_NOT_ALLOWED, QUOTE, BAD_TX, SIMULATION_FAILED, SEND_FAILED,
}

class DelegateException(val blocks: List<DelegateBlock>, detail: String = "") : Exception(detail.ifBlank { blocks.joinToString() })

object DelegateRules {
    /** Tokens that can be delegated (SPL Token program mints; native SOL cannot be delegated). */
    val TOKENS: List<SwapToken> = listOf(SwapTokens.USDC, SwapTokens.SKR, SwapTokens.JUP)

    fun tokenOf(symbol: String): SwapToken = TOKENS.firstOrNull { it.symbol.equals(symbol, true) } ?: SwapTokens.USDC

    /** Hard ceiling of what the app will ask the wallet to approve, in token units (fat-finger guard). */
    fun hardAllowanceRaw(t: SwapToken): Long = t.toRaw(when (t.symbol) { "USDC" -> 500.0; "JUP" -> 1_000.0; else -> 20_000.0 })
    fun minActionRaw(t: SwapToken): Long = t.toRaw(if (t.symbol == "USDC") 0.1 else 1.0)

    /** Agent SOL needed before an action: fees + the agent's own token account rent + Jupiter's temporary wSOL account. */
    const val MIN_AGENT_LAMPORTS = 6_000_000L
    const val DEFAULT_TOPUP_LAMPORTS = 10_000_000L
    const val FEE_LAMPORTS = 5_000L

    fun spentToday(records: List<DelegateRecord>, day: String): Long =
        records.filter { it.day == day && it.kind == DelegateRecord.SWAP && it.status != DelegateRecord.FAILED }.sumOf { it.amountRaw }

    fun remainingToday(policy: DelegatePolicy, records: List<DelegateRecord>, day: String): Long =
        maxOf(0L, policy.clamped().perDayRaw - spentToday(records, day))

    /** Every check before the agent signs anything. Empty = allowed. */
    fun check(
        policy: DelegatePolicy,
        agent: String?,
        chain: DelegateChainState?,
        records: List<DelegateRecord>,
        day: String,
        amountRaw: Long,
        now: Long,
        mainnet: Boolean,
    ): List<DelegateBlock> {
        val p = policy.clamped()
        val out = ArrayList<DelegateBlock>()
        if (!mainnet) out += DelegateBlock.NOT_MAINNET
        if (!policy.enabled) out += DelegateBlock.OFF
        if (policy.riskAcceptedAt <= 0L) out += DelegateBlock.RISK_NOT_ACCEPTED
        if (agent.isNullOrBlank()) out += DelegateBlock.NO_AGENT
        if (TOKENS.none { it.symbol == policy.token } || SwapTokens.bySymbol(p.output) == null) out += DelegateBlock.TOKEN_NOT_ALLOWED
        if (amountRaw <= 0L) out += DelegateBlock.ZERO_AMOUNT
        if (amountRaw > p.perActionRaw) out += DelegateBlock.OVER_ACTION_CAP
        if (spentToday(records, day) + amountRaw > p.perDayRaw) out += DelegateBlock.OVER_DAY_CAP
        if (policy.approvedAt > 0L && now > p.expiresAt()) out += DelegateBlock.EXPIRED
        if (chain == null || agent.isNullOrBlank() || chain.remainingFor(agent) <= 0L) out += DelegateBlock.NOT_APPROVED
        else {
            if (amountRaw > chain.remainingFor(agent)) out += DelegateBlock.OVER_ALLOWANCE
            if (amountRaw > chain.balanceRaw) out += DelegateBlock.OVER_BALANCE
            if (chain.agentLamports < MIN_AGENT_LAMPORTS) out += DelegateBlock.LOW_AGENT_SOL
        }
        return out.distinct()
    }

    /** "Add" keeps what is left on chain and adds to it; "set" replaces it. ApproveChecked always sets the total. */
    fun approveTotal(currentRemaining: Long, add: Long): Long = maxOf(0L, currentRemaining) + maxOf(0L, add)

    /** A varied amount for the daily auto action: 60–100% of the per-action cap, never above what is left. */
    fun autoAmount(policy: DelegatePolicy, remainingToday: Long, remainingAllowance: Long, rnd: kotlin.random.Random): Long {
        val p = policy.clamped()
        val cap = minOf(p.perActionRaw, remainingToday, remainingAllowance)
        if (cap < DelegateRules.minActionRaw(p.tokenInfo)) return 0L
        val step = p.tokenInfo.toRaw(0.01).coerceAtLeast(1L)
        val raw = (cap * (0.6 + 0.4 * rnd.nextDouble())).toLong() / step * step
        return raw.coerceIn(DelegateRules.minActionRaw(p.tokenInfo), cap)
    }

    // ---------------------------------------------------------------- transactions (pure, unit-tested)

    /** Signed by the USER in the wallet: ApproveChecked(total) on their own token account + optional SOL top-up to the agent. */
    fun approveTx(owner: PublicKey, token: SwapToken, agent: PublicKey, totalRaw: Long, topUpLamports: Long, blockhash: ByteArray): LegacyTx {
        require(totalRaw > 0L) { "allowance must be > 0" }
        val mint = PublicKey(token.mint)
        val ixs = arrayListOf(SplIx.approveChecked(SplIx.ata(owner, mint), mint, agent, owner, totalRaw, token.decimals))
        if (topUpLamports > 0L) ixs += SystemIx.transfer(owner, agent, topUpLamports)
        return LegacyTx.compile(owner, blockhash, ixs)
    }

    /** Signed by the USER in the wallet: removes the agent's allowance. */
    fun revokeTx(owner: PublicKey, token: SwapToken, blockhash: ByteArray): LegacyTx =
        LegacyTx.compile(owner, blockhash, listOf(SplIx.revoke(SplIx.ata(owner, PublicKey(token.mint)), owner)))

    /** Signed by the AGENT: pull [amount] from the user's account (as delegate) into the agent's own account; makes the user's output account if missing. */
    fun pullTx(agent: PublicKey, owner: PublicKey, token: SwapToken, amount: Long, blockhash: ByteArray, outputMintNeedingAta: String? = null): LegacyTx {
        val mint = PublicKey(token.mint)
        val ixs = arrayListOf(SplIx.createAtaIdempotent(agent, agent, mint))
        outputMintNeedingAta?.let { ixs += SplIx.createAtaIdempotent(agent, owner, PublicKey(it)) }
        ixs += SplIx.transferChecked(SplIx.ata(owner, mint), mint, SplIx.ata(agent, mint), agent, amount, token.decimals)
        return LegacyTx.compile(agent, blockhash, ixs)
    }

    /** Signed by the AGENT: send tokens it holds back to the user's account (after a failed swap). */
    fun returnTx(agent: PublicKey, owner: PublicKey, token: SwapToken, amount: Long, blockhash: ByteArray): LegacyTx {
        val mint = PublicKey(token.mint)
        return LegacyTx.compile(agent, blockhash, listOf(SplIx.transferChecked(SplIx.ata(agent, mint), mint, SplIx.ata(owner, mint), agent, amount, token.decimals)))
    }

    /**
     * Signed by the AGENT: everything back to the user. Each held token is sent to the user's account and the agent's
     * token account closed (rent to the user); then all agent SOL minus the fee goes to the user.
     */
    fun withdrawTx(agent: PublicKey, owner: PublicKey, agentLamports: Long, tokens: List<Pair<SwapToken, Long>>, blockhash: ByteArray): LegacyTx {
        val ixs = ArrayList<net.solardepin.solarchik.solana.Ix>()
        for ((t, amount) in tokens) {
            val mint = PublicKey(t.mint)
            if (amount > 0L) ixs += SplIx.transferChecked(SplIx.ata(agent, mint), mint, SplIx.ata(owner, mint), agent, amount, t.decimals)
            ixs += SplIx.closeAccount(SplIx.ata(agent, mint), owner, agent)
        }
        val send = agentLamports - FEE_LAMPORTS
        if (send > 0L) ixs += SystemIx.transfer(agent, owner, send)
        require(ixs.isNotEmpty()) { "nothing to withdraw" }
        return LegacyTx.compile(agent, blockhash, ixs)
    }

    /** The static account keys of a legacy or v0 transaction (base58). */
    fun staticKeys(raw: ByteArray): List<String> {
        fun cu16(at: Int): Pair<Int, Int> {
            var v = 0; var i = 0
            while (true) { val x = raw[at + i].toInt() and 0xff; v = v or ((x and 0x7f) shl (7 * i)); i++; if (x and 0x80 == 0 || i == 3) break }
            return v to i
        }
        val (nSig, l1) = cu16(0)
        var p = l1 + 64 * nSig
        if (raw[p].toInt() and 0x80 != 0) p++
        p += 3
        val (n, l2) = cu16(p)
        p += l2
        return (0 until n).map { Base58.encode(raw.copyOfRange(p + 32 * it, p + 32 * it + 32)) }
    }

    /** The agent's Jupiter tx: one signer (the agent), and the user's destination must be in it. Null = fine. */
    fun swapTxProblem(raw: ByteArray, agent: String, destination: String): String? {
        TxCheck.problem(raw, agent)?.let { return it }
        if (destination !in staticKeys(raw)) return "the swap output does not go to your wallet"
        return null
    }

    /** Parses getAccountInfo(jsonParsed) of the user's token account. */
    fun parseTokenAccount(ata: String, r: JsonElement, agentLamports: Long): DelegateChainState {
        val value = (r as? JsonObject)?.get("value") as? JsonObject
            ?: return DelegateChainState(ata, false, 0L, null, 0L, agentLamports)
        val info = ((value["data"] as? JsonObject)?.get("parsed") as? JsonObject)?.get("info") as? JsonObject
            ?: return DelegateChainState(ata, true, 0L, null, 0L, agentLamports)
        fun amt(o: JsonElement?): Long = ((o as? JsonObject)?.get("amount") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        return DelegateChainState(
            ata, true, amt(info["tokenAmount"]),
            (info["delegate"] as? JsonPrimitive)?.contentOrNull, amt(info["delegatedAmount"]), agentLamports,
        )
    }
}

/** Policy + ledger on this phone (prefs "solarchik.delegate"). */
class DelegateStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy(): DelegatePolicy = DelegatePolicy(
        enabled = p.getBoolean("enabled", false),
        riskAcceptedAt = p.getLong("riskAt", 0L),
        token = p.getString("token", "USDC").orEmpty(),
        output = p.getString("output", "SOL").orEmpty(),
        allowanceRaw = p.getLong("allowance", 5_000_000L),
        perActionRaw = p.getLong("perAction", 1_000_000L),
        perDayRaw = p.getLong("perDay", 2_000_000L),
        expiryDays = p.getInt("expiryDays", DelegatePolicy.DEFAULT_EXPIRY_DAYS),
        slippageBps = p.getInt("slip", SwapPolicy.DEFAULT_SLIPPAGE_BPS),
        approvedAt = p.getLong("approvedAt", 0L),
        approvedRaw = p.getLong("approvedRaw", 0L),
        autoDaily = p.getBoolean("auto", false),
    )

    fun setPolicy(v: DelegatePolicy) {
        val c = v.clamped()
        p.edit().putBoolean("enabled", c.enabled).putLong("riskAt", c.riskAcceptedAt).putString("token", c.token).putString("output", c.output)
            .putLong("allowance", c.allowanceRaw).putLong("perAction", c.perActionRaw).putLong("perDay", c.perDayRaw)
            .putInt("expiryDays", c.expiryDays).putInt("slip", c.slippageBps).putLong("approvedAt", c.approvedAt)
            .putLong("approvedRaw", c.approvedRaw).putBoolean("auto", c.autoDaily).apply()
    }

    fun records(): List<DelegateRecord> = runCatching {
        val a = JSONArray(p.getString("records", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            DelegateRecord(o.getLong("at"), o.getString("day"), o.getString("kind"), o.getLong("in"), o.optLong("out"),
                o.optString("sig"), o.getString("status"), o.optString("note"))
        }
    }.getOrDefault(emptyList())

    fun add(r: DelegateRecord) {
        val a = JSONArray()
        (records() + r).takeLast(80).forEach {
            a.put(JSONObject().put("at", it.at).put("day", it.day).put("kind", it.kind).put("in", it.amountRaw).put("out", it.outRaw)
                .put("sig", it.signature).put("status", it.status).put("note", it.note))
        }
        p.edit().putString("records", a.toString()).apply()
    }

    var autoDay: String
        get() = p.getString("autoDay", "").orEmpty()
        set(v) { p.edit().putString("autoDay", v).apply() }
    var autoAt: Long
        get() = p.getLong("autoAt", 0L)
        set(v) { p.edit().putLong("autoAt", v).apply() }
    var doneDay: String
        get() = p.getString("doneDay", "").orEmpty()
        set(v) { p.edit().putString("doneDay", v).apply() }
    var reminderDay: String
        get() = p.getString("reminderDay", "").orEmpty()
        set(v) { p.edit().putString("reminderDay", v).apply() }

    fun clear() = p.edit().clear().apply()

    companion object { const val PREFS = "solarchik.delegate" }
}

/**
 * Executes the delegated mode. Approve / revoke go through the wallet app (the user signs). Agent actions are
 * signed by [AgentKey] after [DelegateRules.check]; each one is re-checked against fresh chain state.
 */
class DelegateDesk(
    context: Context,
    private val jup: JupiterApi = JupiterApi(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> String = { LocalDate.now().toString() },
    private val agentKey: (Context) -> Keypair? = { AgentKey.keypair(it) },
) {
    private val app = context.applicationContext
    val store = DelegateStore(app)
    private val lock = Mutex()

    fun agentAddress(): String? = AgentKey.address(app).takeIf { it.isNotBlank() }

    suspend fun chainState(rpc: Rpc, owner: String): DelegateChainState {
        val t = store.policy().tokenInfo
        val ata = SplIx.ata(PublicKey(owner), PublicKey(t.mint)).toBase58()
        val agentLamports = agentAddress()?.let { runCatching { rpc.balanceLamports(it) }.getOrDefault(0L) } ?: 0L
        val r = rpc.call("getAccountInfo", buildJsonArray {
            add(JsonPrimitive(ata)); add(buildJsonObject { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
        })
        return DelegateRules.parseTokenAccount(ata, r, agentLamports)
    }

    /** User-signed ApproveChecked for [totalRaw] (set / raise / lower the allowance) plus an optional SOL top-up. */
    suspend fun approve(wallet: SolanaWallet, sender: ActivityResultSender, totalRaw: Long, topUpLamports: Long): Result<SentTx> {
        if (!wallet.mainnet) return Result.failure(DelegateException(listOf(DelegateBlock.NOT_MAINNET)))
        val pol = store.policy()
        if (!pol.live) return Result.failure(DelegateException(listOf(DelegateBlock.RISK_NOT_ACCEPTED)))
        val total = totalRaw.coerceIn(1L, DelegateRules.hardAllowanceRaw(pol.tokenInfo))
        val agent = PublicKey(AgentKey.create(app))
        val sent = wallet.signAndSendPrebuilt(sender) { owner ->
            DelegateRules.approveTx(PublicKey(owner), pol.tokenInfo, agent, total, topUpLamports, wallet.rpc.latestBlockhash()).serialize()
        }
        sent.onSuccess {
            store.setPolicy(store.policy().copy(approvedAt = clock(), approvedRaw = total, allowanceRaw = total))
            store.add(DelegateRecord(clock(), today(), DelegateRecord.APPROVE, total, topUpLamports, it.signature, DelegateRecord.SENT))
        }
        return sent
    }

    /** User-signed Revoke. Always allowed, even when the mode is off or expired. */
    suspend fun revoke(wallet: SolanaWallet, sender: ActivityResultSender): Result<SentTx> {
        val pol = store.policy()
        val sent = wallet.signAndSendPrebuilt(sender) { owner ->
            DelegateRules.revokeTx(PublicKey(owner), pol.tokenInfo, wallet.rpc.latestBlockhash()).serialize()
        }
        sent.onSuccess {
            store.setPolicy(store.policy().copy(approvedAt = 0L, approvedRaw = 0L, autoDaily = false))
            store.add(DelegateRecord(clock(), today(), DelegateRecord.REVOKE, 0L, 0L, it.signature, DelegateRecord.SENT))
        }
        return sent
    }

    private suspend fun sendAgent(rpc: Rpc, signed: ByteArray): String {
        val sig = rpc.sendTransaction(signed)
        repeat(40) { i ->
            when (runCatching { rpc.signatureStatus(sig) }.getOrNull()) {
                "confirmed", "finalized" -> return sig
                "failed" -> throw DelegateException(listOf(DelegateBlock.SEND_FAILED), "transaction failed on chain: $sig")
            }
            kotlinx.coroutines.delay(if (i < 10) 1000L else 2000L)
        }
        throw DelegateException(listOf(DelegateBlock.SEND_FAILED), "not confirmed in time: $sig")
    }

    /**
     * One delegated swap of [amountRaw] of the delegated token into the chosen output, paid to [owner]'s wallet.
     * No wallet prompt: signed by the agent key after every check passed on fresh chain state.
     */
    suspend fun runSwap(rpc: Rpc, owner: String, amountRaw: Long, mainnet: Boolean): Result<DelegateRecord> = lock.withLock {
        runCatching {
            val pol = store.policy().clamped()
            val kp = agentKey(app) ?: throw DelegateException(listOf(DelegateBlock.NO_AGENT))
            val agent = kp.publicKey.toBase58()
            val chain = chainState(rpc, owner)
            val blocks = DelegateRules.check(pol, agent, chain, store.records(), today(), amountRaw, clock(), mainnet)
            if (blocks.isNotEmpty()) throw DelegateException(blocks)
            val from = pol.tokenInfo
            val to = pol.outputInfo
            val req = SwapRequest(from, to, amountRaw, by = "delegate")
            val q: SwapQuote = jup.quote(from, to, amountRaw, pol.slippageBps)
            val qb = SwapGuard.checkQuote(SwapPolicy(enabled = true, riskAcceptedAt = 1L, maxSlippageBps = pol.slippageBps), req, q)
            if (qb.isNotEmpty()) throw DelegateException(listOf(DelegateBlock.QUOTE), qb.joinToString())
            val ownerKey = PublicKey(owner)
            val outIsSol = to.mint == SwapTokens.SOL.mint
            val destination = if (outIsSol) owner else SplIx.ata(ownerKey, PublicKey(to.mint)).toBase58()
            val needOutAta = !outIsSol && rpc.accountInfo(destination) == null
            // 1) pull into the agent's own token account (agent = approved delegate)
            val pull = DelegateRules.pullTx(kp.publicKey, ownerKey, from, amountRaw, rpc.latestBlockhash(), if (needOutAta) to.mint else null).partialSign(kp).serialize()
            val pullSig = sendAgent(rpc, pull)
            store.add(DelegateRecord(clock(), today(), DelegateRecord.PULL, amountRaw, 0L, pullSig, DelegateRecord.OK))
            // 2) swap from the agent's account. SPL output goes straight to the user's token account
            //    (destinationTokenAccount). Jupiter's v1 API refuses nativeDestinationAccount ("use v2", checked
            //    9 Oct 2026), so SOL output lands in the agent wallet and step 3 forwards exactly what arrived.
            try {
                suspend fun build(qq: SwapQuote) = jup.swapTx(qq, agent, destinationTokenAccount = if (outIsSol) null else destination)
                var b = build(q)
                if (b.tx.size > TxCheck.MAX_TX_BYTES) {
                    val small = jup.quote(from, to, amountRaw, pol.slippageBps, JupiterApi.SMALL_MAX_ACCOUNTS)
                    if (SwapGuard.checkQuote(SwapPolicy(enabled = true, riskAcceptedAt = 1L, maxSlippageBps = pol.slippageBps), req, small).isEmpty()) b = build(small)
                }
                (if (outIsSol) TxCheck.problem(b.tx, agent) else DelegateRules.swapTxProblem(b.tx, agent, destination))
                    ?.let { throw DelegateException(listOf(DelegateBlock.BAD_TX), it) }
                b.simulationError?.let { throw DelegateException(listOf(DelegateBlock.SIMULATION_FAILED), it) }
                val before = if (outIsSol) rpc.balanceLamports(agent) else 0L
                val sig = sendAgent(rpc, LocalKey.signSlot(b.tx, kp))
                var note = "${from.symbol}→${to.symbol}"
                if (outIsSol) {
                    // 3) forward the SOL that arrived to the user (on failure it stays withdrawable)
                    val got = rpc.balanceLamports(agent) - before
                    if (got > 0L) {
                        val fwd = runCatching { sendAgent(rpc, LegacyTx.compile(kp.publicKey, rpc.latestBlockhash(), listOf(SystemIx.transfer(kp.publicKey, ownerKey, got))).partialSign(kp).serialize()) }
                        store.add(DelegateRecord(clock(), today(), DelegateRecord.DELIVER, got, 0L, fwd.getOrDefault(""), if (fwd.isSuccess) DelegateRecord.OK else DelegateRecord.FAILED))
                        note += if (fwd.isSuccess) " · forwarded" else " · in agent wallet (withdraw)"
                    }
                }
                DelegateRecord(clock(), today(), DelegateRecord.SWAP, amountRaw, q.outAmount, sig, DelegateRecord.OK, note).also { store.add(it) }
            } catch (t: Throwable) {
                val back = runCatching { sendAgent(rpc, DelegateRules.returnTx(kp.publicKey, ownerKey, from, amountRaw, rpc.latestBlockhash()).partialSign(kp).serialize()) }
                store.add(DelegateRecord(clock(), today(), DelegateRecord.RETURN, amountRaw, 0L, back.getOrDefault(""), if (back.isSuccess) DelegateRecord.OK else DelegateRecord.FAILED, t.message.orEmpty().take(120)))
                throw t
            }
        }
    }

    /** Everything the agent holds goes back to [owner]: delegated-token leftovers, token account rent, SOL. */
    suspend fun withdraw(rpc: Rpc, owner: String): Result<String> = lock.withLock {
        runCatching {
            val kp = agentKey(app) ?: throw DelegateException(listOf(DelegateBlock.NO_AGENT))
            val agent = kp.publicKey
            val tokens = DelegateRules.TOKENS.mapNotNull { t ->
                val ata = SplIx.ata(agent, PublicKey(t.mint)).toBase58()
                val r = rpc.call("getAccountInfo", buildJsonArray { add(JsonPrimitive(ata)); add(buildJsonObject { put("encoding", "jsonParsed") }) })
                val st = DelegateRules.parseTokenAccount(ata, r, 0L)
                if (st.exists) t to st.balanceRaw else null
            }
            val lamports = rpc.balanceLamports(agent.toBase58())
            val sig = sendAgent(rpc, DelegateRules.withdrawTx(agent, PublicKey(owner), lamports, tokens, rpc.latestBlockhash()).partialSign(kp).serialize())
            store.add(DelegateRecord(clock(), today(), DelegateRecord.WITHDRAW, lamports, 0L, sig, DelegateRecord.OK))
            sig
        }
    }
}
