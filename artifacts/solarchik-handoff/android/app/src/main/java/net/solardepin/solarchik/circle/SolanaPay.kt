package net.solardepin.solarchik.circle

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.wallet.Base58
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.SecureRandom

/**
 * 1.2.7 two-way Circle: a Solana Pay transfer request (https://docs.solanapay.com/spec) for someone who owes the user.
 *
 *   solana:<recipient>?amount=<n>&spl-token=<mint>&reference=<pubkey>&label=<label>&message=<message>&memo=<memo>
 *
 * The recipient is the user's own connected wallet. The payer's wallet builds and signs the transfer; the app never
 * holds a key and never moves money. Detection follows @solana/pay's findReference + validateTransfer: the oldest
 * signature that touches the reference, then the recipient's balance change must be at least the amount, in the
 * right mint, with no error.
 */
data class PayRequest(
    val id: String,
    /** The "Owes you" ledger line (CallAction OWED) it asks for. */
    val actionId: String,
    val who: String,
    val recipient: String,
    val amount: Double,
    val token: String,
    val reference: String,
    val message: String,
    val memo: String = "",
    val at: Long,
    val status: String = OPEN,
    val signature: String = "",
) {
    val url: String get() = SolanaPay.url(recipient, amount, token, reference, SolanaPay.LABEL, message, memo)

    companion object {
        const val OPEN = "open"
        const val PAID = "paid"
    }
}

object SolanaPay {
    const val LABEL = "Sol"
    const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val SKR = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"

    /** SOL has no spl-token (native); USDC and SKR are SPL mints with 6 decimals (checked on mainnet). */
    fun mint(token: String): String? = when (token.uppercase()) { "USDC" -> USDC; "SKR" -> SKR; else -> null }
    fun decimals(token: String): Int = if (mint(token) == null) 9 else 6

    /**
     * Spec "amount": a non-negative decimal, "0" before the point for values below 1, no scientific notation, no
     * more fraction digits than the token has (more is an invalid request); trailing zeros dropped.
     */
    fun amountText(amount: Double, token: String): String {
        require(amount > 0) { "amount must be positive" }
        val d = BigDecimal(amount.toString()).stripTrailingZeros()
        require(d.scale() <= decimals(token)) { "too many decimals for $token" }
        return if (d.scale() < 0) d.setScale(0).toPlainString() else d.toPlainString()
    }

    /** encodeURIComponent: everything but A-Z a-z 0-9 - _ . ! ~ * ' ( ) as UTF-8 %XX (space is %20, as in the spec examples). */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) sb.append(ch) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun validAddress(a: String): Boolean = runCatching { Base58.decode(a).size == 32 }.getOrDefault(false)

    /** The transfer request URL, parameters in the spec's order; empty optional fields are left out. */
    fun url(recipient: String, amount: Double, token: String, reference: String, label: String, message: String, memo: String = ""): String {
        require(validAddress(recipient)) { "bad recipient" }
        val q = mutableListOf("amount=" + amountText(amount, token))
        mint(token)?.let { q += "spl-token=$it" }
        if (reference.isNotBlank()) { require(validAddress(reference)); q += "reference=$reference" }
        if (label.isNotBlank()) q += "label=" + encode(label)
        if (message.isNotBlank()) q += "message=" + encode(message)
        if (memo.isNotBlank()) q += "memo=" + encode(memo)
        return "solana:$recipient?" + q.joinToString("&")
    }

    /** A fresh reference: 32 random bytes as a base58 public key (like Keypair.generate().publicKey; nobody holds a key for it). */
    fun newReference(rnd: SecureRandom = SecureRandom()): String = Base58.encode(ByteArray(32).also { rnd.nextBytes(it) })

    /** The share-sheet text: one short English line plus the link. */
    fun shareText(r: PayRequest): String =
        "Hi ${r.who}! Here's the Solana Pay link for the ${Circle.amount(r.amount)} ${r.token}" +
            (if (r.message.isNotBlank()) " (${r.message})" else "") + ". It opens in Phantom or any Solana wallet: ${r.url}"

    // ------------------------------------------------------------------ detection

    sealed class Check {
        object NotYet : Check()
        data class Paid(val signature: String) : Check()
        data class Mismatch(val signature: String, val why: String) : Check()
    }

    /** getSignaturesForAddress(reference): the OLDEST signature (findReference semantics), or null. */
    fun oldestSignature(result: JsonElement): String? {
        val arr = result as? JsonArray ?: return null
        return arr.mapNotNull { ((it as? JsonObject)?.get("signature") as? JsonPrimitive)?.contentOrNull }.lastOrNull()
    }

    private fun keyOf(e: JsonElement): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonObject -> (e["pubkey"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    /**
     * validateTransfer: [tx] is getTransaction(sig, jsonParsed, maxSupportedTransactionVersion 0). Null = valid;
     * else why not. The reference must be one of the transaction's keys, the transaction must not have failed,
     * and the recipient (SOL) or the recipient's token account in the right mint (SPL) must have gained >= amount.
     */
    fun validate(tx: JsonElement, r: PayRequest): String? {
        val o = tx as? JsonObject ?: return "not found"
        val meta = o["meta"] as? JsonObject ?: return "missing meta"
        meta["err"]?.let { if (it !is JsonNull) return "transaction failed" }
        val msg = ((o["transaction"] as? JsonObject)?.get("message") as? JsonObject) ?: return "missing message"
        val keys = (msg["accountKeys"] as? JsonArray)?.mapNotNull { keyOf(it) }.orEmpty().toMutableList()
        (meta["loadedAddresses"] as? JsonObject)?.let { la ->
            listOf("writable", "readonly").forEach { k -> (la[k] as? JsonArray)?.mapNotNullTo(keys) { keyOf(it) } }
        }
        if (r.reference !in keys) return "reference not found"
        val want = BigDecimal(amountText(r.amount, r.token)).movePointRight(decimals(r.token))
        val mint = mint(r.token)
        if (mint == null) {
            val i = keys.indexOf(r.recipient)
            if (i < 0) return "recipient not found"
            fun bal(k: String): Long? = ((meta[k] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.longOrNull
            val pre = bal("preBalances") ?: return "missing balances"
            val post = bal("postBalances") ?: return "missing balances"
            return if (BigDecimal.valueOf(post - pre) >= want) null else "amount not transferred"
        }
        fun tokenRows(k: String) = (meta[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { (it["mint"] as? JsonPrimitive)?.contentOrNull == mint && (it["owner"] as? JsonPrimitive)?.contentOrNull == r.recipient }
        fun raw(row: JsonObject?) = ((row?.get("uiTokenAmount") as? JsonObject)?.get("amount") as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val post = tokenRows("postTokenBalances")
        if (post.isEmpty()) return "recipient not found"
        val pre = tokenRows("preTokenBalances")
        val gained = post.maxOf { p ->
            val idx = (p["accountIndex"] as? JsonPrimitive)?.longOrNull
            raw(p) - raw(pre.firstOrNull { (it["accountIndex"] as? JsonPrimitive)?.longOrNull == idx })
        }
        return if (gained >= want) null else "amount not transferred"
    }

    /** One check of one request against the chain (read-only). */
    suspend fun check(rpc: Rpc, r: PayRequest): Check {
        val sigs = rpc.call("getSignaturesForAddress", buildJsonArray { add(JsonPrimitive(r.reference)); add(buildJsonObject { put("limit", 10); put("commitment", "confirmed") }) })
        val sig = oldestSignature(sigs) ?: return Check.NotYet
        val tx = rpc.call("getTransaction", buildJsonArray {
            add(JsonPrimitive(sig))
            add(buildJsonObject { put("encoding", "jsonParsed"); put("commitment", "confirmed"); put("maxSupportedTransactionVersion", 0) })
        })
        if (tx is JsonNull) return Check.NotYet
        val why = validate(tx, r) ?: return Check.Paid(sig)
        return Check.Mismatch(sig, why)
    }
}

/** Local requests (no server, no keys): one per "Owes you" line, newest kept. */
class PayRequestStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<PayRequest> = runCatching {
        val a = JSONArray(p.getString("list", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PayRequest(o.getString("id"), o.getString("action"), o.optString("who"), o.getString("to"), o.getDouble("amount"), o.getString("token"),
                o.getString("ref"), o.optString("msg"), o.optString("memo"), o.getLong("at"), o.optString("status", PayRequest.OPEN), o.optString("sig"))
        }
    }.getOrDefault(emptyList())

    fun save(list: List<PayRequest>) {
        val a = JSONArray()
        list.takeLast(50).forEach { r ->
            a.put(JSONObject().put("id", r.id).put("action", r.actionId).put("who", r.who).put("to", r.recipient).put("amount", r.amount).put("token", r.token)
                .put("ref", r.reference).put("msg", r.message).put("memo", r.memo).put("at", r.at).put("status", r.status).put("sig", r.signature))
        }
        p.edit().putString("list", a.toString()).apply()
    }

    fun forAction(actionId: String): PayRequest? = all().lastOrNull { it.actionId == actionId }
    fun open(): List<PayRequest> = all().filter { it.status == PayRequest.OPEN }

    /** The request for this ledger line: the open one if it's still for the same amount and wallet, else a fresh one. */
    fun requestFor(a: CallAction, who: String, recipient: String, message: String, now: Long = System.currentTimeMillis()): PayRequest {
        forAction(a.id)?.takeIf { it.status == PayRequest.OPEN && it.recipient == recipient && it.amount == a.amount && it.token == a.token }?.let { return it }
        val r = PayRequest("pr" + now.toString(36), a.id, who, recipient, a.amount, a.token, SolanaPay.newReference(), message, "", now)
        save(all().filterNot { it.actionId == a.id && it.status == PayRequest.OPEN } + r)
        return r
    }

    fun markPaid(ctx: Context, id: String, sig: String) {
        val r = all().firstOrNull { it.id == id } ?: return
        save(all().map { if (it.id == id) it.copy(status = PayRequest.PAID, signature = sig) else it })
        runCatching { CallActionStore(ctx).update(r.actionId) { it.copy(status = CallAction.DONE, signature = sig) } }
    }

    companion object { const val PREFS = "solarchik.payreq" }
}
