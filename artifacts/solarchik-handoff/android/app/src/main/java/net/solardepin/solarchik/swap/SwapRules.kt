package net.solardepin.solarchik.swap

import net.solardepin.solarchik.wallet.Base58
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 1.1.0 real swaps on Solana mainnet through Jupiter. This file is pure rules (no I/O) so every limit is
 * unit-tested: the token allowlist, the user's daily spend cap, max slippage, max price impact, and the
 * check that a Jupiter transaction is paid and signed only by the user's own wallet.
 *
 * Nothing here signs. Every swap is prepared by the app or an agent and confirmed by the user in the wallet app.
 */
data class SwapToken(val symbol: String, val mint: String, val decimals: Int) {
    fun toRaw(amount: Double): Long = BigDecimal.valueOf(amount).movePointRight(decimals).setScale(0, RoundingMode.DOWN).toLong()
    fun fromRaw(raw: Long): Double = BigDecimal.valueOf(raw).movePointLeft(decimals).toDouble()
}

object SwapTokens {
    /** Native SOL as Jupiter names it (wrapped SOL mint; Jupiter wraps and unwraps in the same tx). */
    val SOL = SwapToken("SOL", "So11111111111111111111111111111111111111112", 9)
    val USDC = SwapToken("USDC", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 6)
    val SKR = SwapToken("SKR", "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", 6)
    val JUP = SwapToken("JUP", "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", 6)

    /** The only tokens real swaps may touch (input and output). */
    val ALLOWLIST = listOf(SOL, USDC, SKR, JUP)

    fun bySymbol(s: String): SwapToken? = ALLOWLIST.firstOrNull { it.symbol.equals(s.trim(), ignoreCase = true) }
    fun byMint(m: String): SwapToken? = ALLOWLIST.firstOrNull { it.mint == m }
}

/** The user's own limits. Real swaps are OFF until the user reads the risk note and opts in. */
data class SwapPolicy(
    val enabled: Boolean = false,
    val riskAcceptedAt: Long = 0L,
    val dayCapSol: Double = DEFAULT_DAY_CAP_SOL,
    val maxSlippageBps: Int = DEFAULT_SLIPPAGE_BPS,
) {
    val live: Boolean get() = enabled && riskAcceptedAt > 0L

    fun clamped(): SwapPolicy = copy(
        dayCapSol = dayCapSol.coerceIn(MIN_DAY_CAP_SOL, HARD_DAY_CAP_SOL),
        maxSlippageBps = maxSlippageBps.coerceIn(MIN_SLIPPAGE_BPS, HARD_SLIPPAGE_BPS),
    )

    companion object {
        /** Default daily spend cap for real swaps (SOL-equivalent of what leaves the wallet). */
        const val DEFAULT_DAY_CAP_SOL = 0.05
        const val MIN_DAY_CAP_SOL = 0.001
        /** The user can raise the cap up to this, never above. */
        const val HARD_DAY_CAP_SOL = 0.5
        const val DEFAULT_SLIPPAGE_BPS = 50
        const val MIN_SLIPPAGE_BPS = 10
        /** 3% is the ceiling the user can choose. */
        const val HARD_SLIPPAGE_BPS = 300
        /** A route that moves the price more than this is refused (thin liquidity). */
        const val MAX_PRICE_IMPACT_PCT = 1.0
        /** Re-quote when the prepared tx is older than this (Jupiter txs carry a recent blockhash). */
        const val FRESH_MS = 40_000L
        /** On a re-quote, the new minimum output may be at most this much worse than what the user saw. */
        const val REQUOTE_TOLERANCE_BPS = 50
    }
}

/** One real swap the wallet signed. [solValue] is what counted against the day cap. */
data class SwapRecord(
    val at: Long,
    val day: String,
    val signature: String,
    val from: String,
    val to: String,
    val inAmount: Long,
    val outAmount: Long,
    val solValue: Double,
    val status: String = STATUS_SENT,
    val by: String = "",
) {
    companion object {
        const val STATUS_SENT = "sent"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_FAILED = "failed"
    }
}

/** A swap the user or an agent wants. [by] names the agent ("" = the user). */
data class SwapRequest(val from: SwapToken, val to: SwapToken, val amountRaw: Long, val by: String = "", val reason: String = "")

/** The fields of a Jupiter quote the app shows and checks. [raw] is passed back to /swap unchanged. */
data class SwapQuote(
    val inputMint: String,
    val outputMint: String,
    val inAmount: Long,
    val outAmount: Long,
    val minOut: Long,
    val priceImpactPct: Double,
    val slippageBps: Int,
    val routeLabels: List<String>,
    val routeFees: Map<String, Long>,
    val raw: String,
)

enum class SwapBlock {
    NOT_MAINNET, OFF, RISK_NOT_ACCEPTED, TOKEN_NOT_ALLOWED, SAME_TOKEN, ZERO_AMOUNT, OVER_DAY_CAP,
    SLIPPAGE_TOO_HIGH, PRICE_IMPACT_TOO_HIGH, QUOTE_MISMATCH, SIMULATION_FAILED, BAD_TX, PRICE_MOVED,
}

object SwapGuard {
    fun spentToday(records: List<SwapRecord>, day: String): Double =
        records.filter { it.day == day && it.status != SwapRecord.STATUS_FAILED }.sumOf { it.solValue }

    fun remaining(policy: SwapPolicy, records: List<SwapRecord>, day: String): Double =
        maxOf(0.0, policy.clamped().dayCapSol - spentToday(records, day))

    /** Before any network call: is this swap allowed at all? [solValue] = SOL-equivalent of the input. */
    fun precheck(
        policy: SwapPolicy,
        records: List<SwapRecord>,
        day: String,
        req: SwapRequest,
        solValue: Double,
        mainnet: Boolean,
    ): List<SwapBlock> {
        val out = ArrayList<SwapBlock>()
        if (!mainnet) out += SwapBlock.NOT_MAINNET
        if (!policy.enabled) out += SwapBlock.OFF
        if (policy.riskAcceptedAt <= 0L) out += SwapBlock.RISK_NOT_ACCEPTED
        if (SwapTokens.byMint(req.from.mint) == null || SwapTokens.byMint(req.to.mint) == null) out += SwapBlock.TOKEN_NOT_ALLOWED
        if (req.from.mint == req.to.mint) out += SwapBlock.SAME_TOKEN
        if (req.amountRaw <= 0L) out += SwapBlock.ZERO_AMOUNT
        // a tiny epsilon so 0.05 of a 0.05 cap is allowed despite double rounding
        if (solValue > remaining(policy, records, day) + 1e-12) out += SwapBlock.OVER_DAY_CAP
        return out
    }

    /** After the quote: the quote must be for exactly this request and inside the user's limits. */
    fun checkQuote(policy: SwapPolicy, req: SwapRequest, q: SwapQuote): List<SwapBlock> {
        val out = ArrayList<SwapBlock>()
        if (q.inputMint != req.from.mint || q.outputMint != req.to.mint || q.inAmount != req.amountRaw) out += SwapBlock.QUOTE_MISMATCH
        if (q.slippageBps > policy.clamped().maxSlippageBps) out += SwapBlock.SLIPPAGE_TOO_HIGH
        if (q.priceImpactPct.isNaN() || q.priceImpactPct > SwapPolicy.MAX_PRICE_IMPACT_PCT) out += SwapBlock.PRICE_IMPACT_TOO_HIGH
        if (q.outAmount <= 0L || q.minOut <= 0L || q.minOut > q.outAmount) out += SwapBlock.QUOTE_MISMATCH
        return out.distinct()
    }

    /** A fresh re-quote may not be worse than what the user reviewed by more than the tolerance. */
    fun requoteOk(seenMinOut: Long, freshMinOut: Long): Boolean =
        freshMinOut.toDouble() >= seenMinOut.toDouble() * (1.0 - SwapPolicy.REQUOTE_TOLERANCE_BPS / 10_000.0)
}

/**
 * Minimal reader of a serialized Solana transaction (legacy or v0): the signer list. A Jupiter swap tx must
 * need exactly one signature, and that signer (the fee payer) must be the user's wallet.
 */
object TxCheck {
    /** Solana's packet limit for a serialized transaction. */
    const val MAX_TX_BYTES = 1232

    data class Header(val versioned: Boolean, val signatures: Int, val requiredSigners: Int, val feePayer: String)

    private fun compactU16(b: ByteArray, at: Int): Pair<Int, Int> {
        var v = 0
        var i = 0
        while (true) {
            val x = b[at + i].toInt() and 0xff
            v = v or ((x and 0x7f) shl (7 * i))
            i++
            if (x and 0x80 == 0 || i == 3) break
        }
        return v to i
    }

    fun header(raw: ByteArray): Header {
        require(raw.size > 100) { "tx too short" }
        val (nSig, l1) = compactU16(raw, 0)
        var p = l1 + 64 * nSig
        require(p < raw.size) { "tx truncated" }
        val versioned = raw[p].toInt() and 0x80 != 0
        if (versioned) {
            require(raw[p].toInt() and 0x7f == 0) { "unknown tx version" }
            p++
        }
        val required = raw[p].toInt() and 0xff
        p += 3
        val (nKeys, l2) = compactU16(raw, p)
        p += l2
        require(nKeys >= 1 && p + 32 <= raw.size) { "no accounts" }
        return Header(versioned, nSig, required, Base58.encode(raw.copyOfRange(p, p + 32)))
    }

    /** Null when fine, else why the tx is refused before the wallet ever sees it. */
    fun problem(raw: ByteArray, owner: String): String? {
        if (raw.size > MAX_TX_BYTES) return "transaction too large (${raw.size} bytes)"
        val h = runCatching { header(raw) }.getOrElse { return "unreadable transaction: ${it.message}" }
        if (h.requiredSigners != 1 || h.signatures != 1) return "transaction needs ${h.requiredSigners} signers"
        if (h.feePayer != owner) return "fee payer is not your wallet"
        return null
    }
}

/** What the confirmation card shows before the wallet opens. All lamports are SOL lamports. */
data class SwapFees(
    val baseFeeLamports: Long,
    val priorityFeeLamports: Long,
    /** One-time rent for a new token account when the user has none for the output token (refundable on close). */
    val ataRentLamports: Long,
) {
    val networkLamports: Long get() = baseFeeLamports + priorityFeeLamports
    val totalLamports: Long get() = networkLamports + ataRentLamports

    companion object {
        const val BASE_FEE_LAMPORTS = 5_000L
        /** Rent-exempt minimum of an SPL token account (165 bytes). */
        const val ATA_RENT_LAMPORTS = 2_039_280L
        /** The app caps Jupiter's priority fee here (0.0001 SOL). */
        const val MAX_PRIORITY_LAMPORTS = 100_000L
    }
}
