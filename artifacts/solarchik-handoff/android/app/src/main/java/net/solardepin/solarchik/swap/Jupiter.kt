package net.solardepin.solarchik.swap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

class JupiterException(message: String, val code: Int = 0) : Exception(message)

/** The unsigned transaction Jupiter built for one quote. */
data class SwapBuild(
    val tx: ByteArray,
    val priorityFeeLamports: Long,
    val lastValidBlockHeight: Long,
    val simulationError: String?,
)

/**
 * Jupiter Swap API v1 (Metis router) on the free lite-api host: GET /quote, POST /swap (base64 v0 tx).
 * Checked 9 Oct 2026: lite-api.jup.ag/swap/v1 needs no key. Jupiter's newer Swap v2 (/order, /build)
 * requires an API key from portal.jup.ag; the client is one class so a keyed host can replace it.
 */
open class JupiterApi(private val base: String = BASE) {
    private val json = Json { ignoreUnknownKeys = true }

    protected open fun get(url: String): String = http { Request.Builder().url(url).get().build() }
    protected open fun post(url: String, body: String): String =
        http { Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build() }

    /** The free host answers 429 / 503 in bursts: two retries (0.6 s, 1.5 s). Quotes and unsigned builds are safe to repeat. */
    private fun http(req: () -> Request): String {
        var last: JupiterException? = null
        for (wait in longArrayOf(0L, 600L, 1500L)) {
            if (wait > 0) Thread.sleep(wait)
            try {
                return client.newCall(req()).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw JupiterException("Jupiter HTTP ${res.code}: ${text.take(160)}", res.code)
                    text
                }
            } catch (e: JupiterException) {
                if (e.code != 429 && e.code !in 500..599) throw e
                last = e
            }
        }
        throw last!!
    }

    /**
     * [maxAccounts] keeps the route small enough for one transaction: on 9 Oct 2026 an unrestricted SOL→JUP route
     * came back as a 1393-byte v0 tx, above Solana's 1232-byte limit (the RPC refuses it).
     */
    open suspend fun quote(from: SwapToken, to: SwapToken, amountRaw: Long, slippageBps: Int, maxAccounts: Int = DEFAULT_MAX_ACCOUNTS): SwapQuote = withContext(Dispatchers.IO) {
        val url = "$base/quote".toHttpUrl().newBuilder()
            .addQueryParameter("inputMint", from.mint)
            .addQueryParameter("outputMint", to.mint)
            .addQueryParameter("amount", amountRaw.toString())
            .addQueryParameter("slippageBps", slippageBps.toString())
            .addQueryParameter("restrictIntermediateTokens", "true")
            .addQueryParameter("maxAccounts", maxAccounts.toString())
            .build().toString()
        parseQuote(get(url))
    }

    /**
     * [destinationTokenAccount] (1.1.0 delegated mode): the output goes to the user's own token account while
     * [owner] (the agent key) is the transfer authority and fee payer. [nativeDestination] is refused by the v1
     * API (HTTP 400 NOT_SUPPORTED "use v2", checked 9 Oct 2026); kept only for a keyed v2 host.
     */
    open suspend fun swapTx(quote: SwapQuote, owner: String, destinationTokenAccount: String? = null, nativeDestination: String? = null): SwapBuild = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("quoteResponse", json.parseToJsonElement(quote.raw))
            put("userPublicKey", owner)
            put("wrapAndUnwrapSol", true)
            destinationTokenAccount?.let { put("destinationTokenAccount", it) }
            nativeDestination?.let { put("nativeDestinationAccount", it) }
            put("dynamicComputeUnitLimit", true)
            put("prioritizationFeeLamports", buildJsonObject {
                put("priorityLevelWithMaxLamports", buildJsonObject {
                    put("maxLamports", SwapFees.MAX_PRIORITY_LAMPORTS)
                    put("priorityLevel", "medium")
                })
            })
        }.toString()
        parseSwap(post("$base/swap", body))
    }

    companion object {
        const val BASE = "https://lite-api.jup.ag/swap/v1"
        const val DEFAULT_MAX_ACCOUNTS = 40
        /** Second try when a build is still too large. */
        const val SMALL_MAX_ACCOUNTS = 24
        private val parser = Json { ignoreUnknownKeys = true }

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

        fun parseQuote(text: String): SwapQuote {
            val o = runCatching { parser.parseToJsonElement(text).jsonObject }.getOrElse { throw JupiterException("bad quote") }
            o.str("error")?.let { throw JupiterException(it) }
            val route = (o["routePlan"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("swapInfo") as? JsonObject }
            val fees = LinkedHashMap<String, Long>()
            route.forEach { s ->
                val mint = s.str("feeMint") ?: return@forEach
                val amt = s.str("feeAmount")?.toLongOrNull() ?: return@forEach
                fees[mint] = (fees[mint] ?: 0L) + amt
            }
            return SwapQuote(
                inputMint = o.str("inputMint") ?: throw JupiterException("quote without inputMint"),
                outputMint = o.str("outputMint") ?: throw JupiterException("quote without outputMint"),
                inAmount = o.str("inAmount")?.toLongOrNull() ?: throw JupiterException("quote without inAmount"),
                outAmount = o.str("outAmount")?.toLongOrNull() ?: throw JupiterException("quote without outAmount"),
                minOut = o.str("otherAmountThreshold")?.toLongOrNull() ?: throw JupiterException("quote without minimum"),
                priceImpactPct = (o.str("priceImpactPct")?.toDoubleOrNull() ?: Double.NaN) * 100.0,
                slippageBps = (o["slippageBps"] as? JsonPrimitive)?.longOrNull?.toInt() ?: -1,
                routeLabels = route.mapNotNull { it.str("label") }.distinct(),
                routeFees = fees,
                raw = text,
            )
        }

        fun parseSwap(text: String): SwapBuild {
            val o = runCatching { parser.parseToJsonElement(text).jsonObject }.getOrElse { throw JupiterException("bad swap answer") }
            o.str("error")?.let { throw JupiterException(it) }
            val b64 = o.str("swapTransaction") ?: throw JupiterException("no swap transaction")
            val sim = o["simulationError"]?.takeIf { it !is JsonNull }?.let { e ->
                (e as? JsonObject)?.let { it.str("error") ?: it.str("errorCode") } ?: e.toString()
            }
            return SwapBuild(
                tx = Base64.getDecoder().decode(b64),
                priorityFeeLamports = (o["prioritizationFeeLamports"] as? JsonPrimitive)?.longOrNull ?: 0L,
                lastValidBlockHeight = (o["lastValidBlockHeight"] as? JsonPrimitive)?.longOrNull ?: 0L,
                simulationError = sim,
            )
        }
    }
}
