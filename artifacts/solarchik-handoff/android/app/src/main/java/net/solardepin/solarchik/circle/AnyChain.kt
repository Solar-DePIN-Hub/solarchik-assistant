package net.solardepin.solarchik.circle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.solana.Rpc
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 1.2.15 "Pay from any chain" (Aurora Intents, powered by NEAR Intents).
 *
 * For a USDC or SOL request the app also creates a pay page on the sol-anychain worker. A friend without a Solana
 * wallet opens it, picks the network and asset they hold (USDC on Base, USDT on Tron, ETH, BTC, NEAR…), and gets a
 * one-time deposit address with an EXACT_OUTPUT quote: the user receives exactly the requested amount on Solana, and
 * anything that goes wrong is refunded to the friend's own address. The app never trusts the server's "paid": it reads
 * the Solana transaction the intent settled with and checks the user's wallet gained the amount ([SolanaPay.validate]).
 */
object AnyChain {
    val TOKENS = setOf("USDC", "SOL")
    @Volatile var enabled = true
    /** Tests: (method, path, body) -> (code, json). */
    @androidx.annotation.VisibleForTesting var http: ((String, String, String?) -> Pair<Int, String>)? = null

    /** Off in Robolectric unless a test scripts [http] (unit tests never create real pages). */
    fun active() = enabled && (http != null || android.os.Build.FINGERPRINT != "robolectric")
    fun supports(r: PayRequest) = active() && r.token.uppercase() in TOKENS

    private fun call(method: String, path: String, body: String?): Pair<Int, String> {
        http?.let { return it(method, path, body) }
        val b = okhttp3.Request.Builder().url(SolarchikConfig.ANYCHAIN_URL + path)
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        return Rpc.client.newCall(b.build()).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    /** Creates the page for [r] once (stored on the request); returns the request as stored, or [r] if offline. */
    suspend fun ensure(ctx: Context, r: PayRequest, from: String = "", timeoutMs: Long = 5000): PayRequest {
        if (!supports(r) || r.anyUrl.isNotBlank() || r.status != PayRequest.OPEN) return r
        val made = withTimeoutOrNull(timeoutMs) { runCatching { create(r, from) }.getOrNull() } ?: return r
        val store = PayRequestStore(ctx)
        store.update(r.id) { it.copy(anyId = made.first, anyUrl = made.second) }
        return store.byId(r.id) ?: r.copy(anyId = made.first, anyUrl = made.second)
    }

    fun body(r: PayRequest, from: String): String = JSONObject()
        .put("to", r.recipient).put("amount", SolanaPay.amountText(r.amount, r.token)).put("token", r.token.uppercase())
        .put("who", r.who.take(40)).put("from", from.take(40)).put("note", r.message.take(80)).put("reference", r.reference).toString()

    suspend fun create(r: PayRequest, from: String = ""): Pair<String, String> = withContext(Dispatchers.IO) {
        val (code, text) = call("POST", "/anychain/request", body(r, from))
        check(code == 200) { "anychain $code" }
        val o = JSONObject(text)
        val id = o.getString("id"); val url = o.getString("url")
        // only our own pages, never a URL the server could swap for something else
        check(Regex("[a-z0-9]{10}").matches(id) && url == SolarchikConfig.ANYCHAIN_URL + "/r/" + id) { "unexpected page url" }
        id to url
    }

    /** The settlement the worker reports: Solana tx signatures once the intent reached SUCCESS, else empty. */
    suspend fun settlement(anyId: String): List<String> = withContext(Dispatchers.IO) {
        val (code, text) = call("GET", "/anychain/request/$anyId", null)
        if (code != 200) return@withContext emptyList()
        val o = JSONObject(text)
        if (o.optString("status") != "paid") return@withContext emptyList()
        val a = o.optJSONObject("paid")?.optJSONArray("solanaTxs") ?: return@withContext emptyList()
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    /** Paid only if one of the reported Solana transactions really paid the user (read-only RPC). */
    suspend fun check(rpc: Rpc, r: PayRequest): SolanaPay.Check {
        if (r.anyId.isBlank()) return SolanaPay.Check.NotYet
        val sigs = runCatching { settlement(r.anyId) }.getOrDefault(emptyList())
        var mismatch: SolanaPay.Check? = null
        for (sig in sigs.take(4)) {
            val tx = rpc.call("getTransaction", buildJsonArray {
                add(JsonPrimitive(sig))
                add(buildJsonObject { put("encoding", "jsonParsed"); put("commitment", "confirmed"); put("maxSupportedTransactionVersion", 0) })
            })
            if (tx is JsonNull) continue
            val why = SolanaPay.validate(tx, r, requireReference = false) ?: return SolanaPay.Check.Paid(sig)
            mismatch = SolanaPay.Check.Mismatch(sig, "any-chain: $why")
        }
        return mismatch ?: SolanaPay.Check.NotYet
    }
}
