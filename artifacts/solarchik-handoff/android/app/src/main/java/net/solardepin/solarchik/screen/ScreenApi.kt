package net.solardepin.solarchik.screen

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Client for the solarchik-screen worker (balance, USDC top-up check, AI note, voicemail inbox). */
object ScreenApi {
    const val BASE = "https://solarchik-screen.davidbell1603.workers.dev"

    data class Screened(
        val reply: String,
        val summary: JSONObject,
        val chargedUsd: Double,
        val usd: Double,
        val needTopup: Boolean,
        val ok: Boolean,
    )

    sealed class Topup {
        data class Credited(val usd: Double, val added: Double) : Topup()
        /** The payment was already credited earlier (409): read the balance instead. */
        object AlreadyUsed : Topup()
        /** No transaction with this reference yet: the wallet may still be confirming. */
        object NotFound : Topup()
        data class Invalid(val detail: String) : Topup()
        data class Failed(val code: Int, val detail: String) : Topup()
    }

    data class Voicemail(val caller: String, val text: String, val at: Long)

    /** GET /balance: total credit, the paid part and the free trial part (0.21.8). */
    data class Balance(val usd: Double, val paidUsd: Double, val trialUsd: Double, val trial: Boolean, val owner: Boolean = false)

    /** Secretary voice languages the worker accepts (GET/POST /secretary-lang). */
    val LANGS = listOf("auto", "uk", "en")

    fun balanceInfo(userId: String): Balance? {
        val (code, body) = request("GET", "$BASE/balance?userId=${enc(userId)}", null)
        return parseBalanceInfo(code, body)
    }

    fun lang(userId: String): String? {
        val (code, body) = request("GET", "$BASE/secretary-lang?userId=${enc(userId)}", null)
        return parseLang(code, body)
    }

    fun setLang(userId: String, lang: String): String? {
        val (code, body) = request("POST", "$BASE/secretary-lang", JSONObject().put("userId", userId).put("lang", lang).toString())
        return parseLang(code, body)
    }

    fun parseBalanceInfo(code: Int, body: String): Balance? {
        if (code !in 200..299) return null
        val o = parse(body)
        val usd = o.optDouble("usd", Double.NaN)
        if (!usd.isFinite()) return null
        val trialUsd = o.optDouble("trialUsd", 0.0).takeIf { it.isFinite() } ?: 0.0
        val paid = o.optDouble("paidUsd", Double.NaN).takeIf { it.isFinite() } ?: (usd - trialUsd).coerceAtLeast(0.0)
        return Balance(usd, paid, trialUsd, o.optBoolean("trial", trialUsd > 0), o.optBoolean("owner", false))
    }

    fun parseLang(code: Int, body: String): String? {
        if (code !in 200..299) return null
        return parse(body).optString("lang").trim().lowercase().takeIf { it in LANGS }
    }

    fun balance(userId: String): Double? {
        val (code, body) = request("GET", "$BASE/balance?userId=${enc(userId)}", null)
        return parseBalance(code, body)
    }

    /** Ask the worker to verify an on-chain USDC payment by Solana Pay reference or by signature. */
    fun topup(userId: String, ref: String? = null, sig: String? = null): Topup {
        val payload = JSONObject().put("userId", userId)
        if (!ref.isNullOrBlank()) payload.put("ref", ref)
        if (!sig.isNullOrBlank()) payload.put("sig", sig)
        val (code, body) = request("POST", "$BASE/topup", payload.toString())
        return parseTopup(code, body)
    }

    fun screen(userId: String, text: String): Screened {
        val payload = JSONObject().put("userId", userId).put("text", text).toString()
        val (code, body) = request("POST", "$BASE/screen", payload)
        return parseScreen(code, body)
    }

    /** 1.1.7: the worker writes call notes in the app's UI language (translated on read, cached per call). */
    fun uiLang(): String = if (net.solardepin.solarchik.core.AppLocale.isUk(net.solardepin.solarchik.core.AppLocale.ui())) "uk" else "en"

    fun inbox(userId: String): List<Voicemail>? {
        val (code, body) = request("GET", "$BASE/inbox?userId=${enc(userId)}&lang=${uiLang()}", null)
        return parseInbox(code, body)
    }

    /** 0.21.9: the secretary's calls for [userId] (GET /inbox, with call ids, status, summary, duration). */
    fun calls(userId: String): List<CallItem>? {
        val (code, body) = request("GET", "$BASE/inbox?userId=${enc(userId)}&lang=${uiLang()}", null)
        return CallInbox.parse(userId, code, body)
    }

    /** One call with its transcript (GET /call). */
    fun call(userId: String, callId: String): CallDetail? {
        val (code, body) = request("GET", "$BASE/call?userId=${enc(userId)}&callId=${enc(callId)}&lang=${uiLang()}", null)
        return CallInbox.parseDetail(userId, code, body)
    }

    /** 1.2.6: start the SMS-free check: the user then calls the line from this number within 5 minutes. */
    fun verifyStart(userId: String, number: String): JSONObject? {
        val (code, body) = request("POST", "$BASE/phone/verify", JSONObject().put("userId", userId).put("number", number).toString())
        return if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null
    }

    /** {number, verified, line} for this user. */
    fun phoneStatus(userId: String): JSONObject? {
        val (code, body) = request("GET", "$BASE/phone/status?userId=${enc(userId)}", null)
        return if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null
    }

    /** The phone just screened a caller: tell the line who may be forwarded in a moment (a hash, never the number). */
    fun forwardExpect(userId: String, hash: String): Boolean =
        request("POST", "$BASE/forward/expect", JSONObject().put("userId", userId).put("h", hash).toString()).first == 200

    /** Block or unblock a caller for this player (POST /block): the secretary rejects blocked numbers, never charged. */
    fun block(userId: String, number: String, blocked: Boolean): List<String>? {
        val (code, body) = request("POST", "$BASE/block", JSONObject().put("userId", userId).put("number", number).put("blocked", blocked).toString())
        return parseNumbers(code, body)
    }

    fun blocked(userId: String): List<String>? {
        val (code, body) = request("GET", "$BASE/block?userId=${enc(userId)}", null)
        return parseNumbers(code, body)
    }

    fun parseNumbers(code: Int, body: String): List<String>? {
        if (code !in 200..299) return null
        val a = parse(body).optJSONArray("numbers") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    /** POST /call-claim: the next call to the demo line (3 min) lands in this player's Calls. Returns the armed seconds. */
    fun claim(userId: String): Int? {
        val (code, body) = request("POST", "$BASE/call-claim", JSONObject().put("userId", userId).toString())
        if (code !in 200..299) return null
        return parse(body).optInt("armedSec", 0).takeIf { it > 0 }
    }

    fun parseBalance(code: Int, body: String): Double? {
        if (code !in 200..299) return null
        val v = parse(body).optDouble("usd", Double.NaN)
        return v.takeIf { it.isFinite() }
    }

    fun parseTopup(code: Int, body: String): Topup {
        val json = parse(body)
        val err = json.optString("error")
        return when {
            code in 200..299 && json.has("usd") -> Topup.Credited(json.optDouble("usd", 0.0), json.optDouble("added", 0.0))
            code == 409 || err == "ALREADY_USED" -> Topup.AlreadyUsed
            code == 402 && err == "PAYMENT_NOT_FOUND" -> Topup.NotFound
            code == 402 -> Topup.Invalid(json.optString("detail").ifBlank { err.ifBlank { "payment_invalid" } })
            else -> Topup.Failed(code, json.optString("detail").ifBlank { err.ifBlank { "http_$code" } })
        }
    }

    fun parseScreen(code: Int, body: String): Screened {
        val json = parse(body)
        val need = code == 402 || json.optString("error") == "NEED_TOPUP"
        val summary = json.optJSONObject("summary") ?: JSONObject()
        if (summary.length() == 0 && json.optString("summary").isNotBlank()) summary.put("notes", json.optString("summary"))
        return Screened(
            reply = json.optString("reply"),
            summary = summary,
            chargedUsd = json.optDouble("chargedUsd", 0.0),
            usd = json.optDouble("usd", 0.0),
            needTopup = need,
            ok = code in 200..299 && !need,
        )
    }

    fun parseInbox(code: Int, body: String): List<Voicemail>? {
        if (code !in 200..299) return null
        val items = parse(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").trim()
            if (text.isEmpty()) null else Voicemail(o.optString("caller").trim(), text.take(600), o.optLong("at"))
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun parse(body: String): JSONObject = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse { JSONObject() }

    /** Tests only: answer a request without the network (method, url, body) -> (code, body); null = go online. */
    @Volatile internal var requestForTest: ((String, String, String?) -> Pair<Int, String>?)? = null

    private fun request(method: String, url: String, body: String?): Pair<Int, String> {
        requestForTest?.invoke(method, url, body)?.let { return it }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to stream?.bufferedReader()?.readText().orEmpty()
        } catch (e: Throwable) {
            0 to JSONObject().put("error", "offline").put("detail", e.message ?: "offline").toString()
        } finally {
            c.disconnect()
        }
    }
}
