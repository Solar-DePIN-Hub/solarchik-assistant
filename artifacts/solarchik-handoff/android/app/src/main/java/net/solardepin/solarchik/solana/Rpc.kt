package net.solardepin.solarchik.solana

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.solardepin.solarchik.wallet.Base58
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

class RpcException(message: String, val code: Int = 0) : Exception(message)

/** One row of getSignaturesForAddress: [memo] is the RPC's "[len] text" memo summary. */
data class SigInfo(val signature: String, val ok: Boolean, val memo: String)

/** Minimal JSON-RPC client for the few calls the app needs. Open so tests can script answers. */
open class Rpc(val url: String) {
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun call(method: String, params: JsonArray): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString()
        // 0.21.7: the public devnet node answers 429 in bursts. Retry 429/5xx twice (300/900 ms), then once
        // through the market's /solana-rpc proxy (it retries and falls back to a second node). Airdrops are
        // never retried: a faucet 429 is a real "no", and a repeat could double-spend the faucet's quota.
        if (method == "requestAirdrop") return@withContext parse(post(url, body))
        var last: Throwable? = null
        for (wait in RETRY_MS) {
            if (wait > 0) pause(wait)
            try {
                return@withContext parse(post(url, body))
            } catch (e: RpcException) {
                if (!retryable(e.code)) throw e
                last = e
            }
        }
        val fb = fallbackFor(url) ?: throw last!!
        try {
            parse(post(fb, body))
        } catch (e: java.io.IOException) {
            throw last ?: e
        }
    }

    /** One HTTP round trip; HTTP failures become [RpcException] with the status as code. Test seam. */
    protected open fun post(target: String, body: String): String {
        val req = Request.Builder().url(target)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 429) throw RpcException("rate limited", 429)
            if (!res.isSuccessful) throw RpcException("HTTP ${res.code}", res.code)
            text
        }
    }

    protected open suspend fun pause(ms: Long) = kotlinx.coroutines.delay(ms)

    private fun parse(text: String): JsonElement {
        val obj = runCatching { json.parseToJsonElement(text) as JsonObject }
            .getOrElse { throw RpcException("bad RPC response") }
        obj["error"]?.takeIf { it !is JsonNull }?.let {
            val e = it.jsonObject
            throw RpcException(e["message"]?.jsonPrimitive?.contentOrNull ?: "rpc error", e["code"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0)
        }
        return obj["result"] ?: JsonNull
    }

    suspend fun latestBlockhash(): ByteArray {
        val r = call("getLatestBlockhash", buildJsonArray { add(buildJsonObject { put("commitment", "confirmed") }) })
        // 1.2.6.1: the context slot rides along as min_context_slot (Phantom drops sign_and_send without it, MWA #1146)
        lastBlockhashSlot = runCatching { (((r as? JsonObject)?.get("context") as? JsonObject)?.get("slot") as? JsonPrimitive)?.longOrNull }.getOrNull() ?: 0L
        return parseBlockhash(r)
    }

    /**
     * 1.2.6.1: simulateTransaction (sigVerify off, the blockhash as built). Null when the chain would accept it;
     * the error (with the last log lines) when it would not. Throws when the node can't be asked.
     */
    open suspend fun simulate(tx: ByteArray): String? {
        val r = call("simulateTransaction", buildJsonArray {
            add(JsonPrimitive(java.util.Base64.getEncoder().encodeToString(tx)))
            add(buildJsonObject { put("encoding", "base64"); put("sigVerify", false); put("replaceRecentBlockhash", true); put("commitment", "confirmed") })
        })
        val v = (r as? JsonObject)?.get("value") as? JsonObject ?: throw RpcException("no simulation")
        val err = v["err"]
        if (err == null || err is kotlinx.serialization.json.JsonNull) return null
        val logs = (v["logs"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return err.toString() + " | " + logs.takeLast(3).joinToString(" / ")
    }

    /** The context slot of the last [latestBlockhash] answer (0 if the node didn't say). */
    @Volatile var lastBlockhashSlot: Long = 0L

    suspend fun balanceLamports(address: String): Long {
        val r = call("getBalance", buildJsonArray { add(JsonPrimitive(address)); add(buildJsonObject { put("commitment", "confirmed") }) })
        return parseBalance(r)
    }

    /** Recent signatures of [address] with their memo text (newest first). */
    open suspend fun memoSignatures(address: String, limit: Int): List<SigInfo> {
        val r = call("getSignaturesForAddress", buildJsonArray { add(JsonPrimitive(address)); add(buildJsonObject { put("limit", limit); put("commitment", "confirmed") }) })
        return parseSignatures(r)
    }

    suspend fun requestAirdrop(address: String, lamports: Long): String {
        val r = call("requestAirdrop", buildJsonArray { add(JsonPrimitive(address)); add(JsonPrimitive(lamports)) })
        return r.jsonPrimitive.content
    }

    /** Sends a fully signed transaction: tests, devnet tools, and the MWA sign-only fallback. */
    suspend fun sendTransaction(signed: ByteArray): String {
        val r = call(
            "sendTransaction",
            buildJsonArray {
                add(JsonPrimitive(Base64.getEncoder().encodeToString(signed)))
                add(buildJsonObject { put("encoding", "base64"); put("preflightCommitment", "confirmed") })
            },
        )
        return r.jsonPrimitive.content
    }

    data class AccountInfo(val owner: String, val lamports: Long, val data: ByteArray)

    suspend fun accountInfo(address: String): AccountInfo? {
        val r = call(
            "getAccountInfo",
            buildJsonArray {
                add(JsonPrimitive(address))
                add(buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed") })
            },
        )
        val v = r.jsonObject["value"]
        if (v == null || v is JsonNull) return null
        val o = v.jsonObject
        val data = o["data"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content.orEmpty()
        return AccountInfo(
            owner = o["owner"]?.jsonPrimitive?.content.orEmpty(),
            lamports = o["lamports"]?.jsonPrimitive?.longOrNull ?: 0L,
            data = runCatching { Base64.getDecoder().decode(data) }.getOrDefault(ByteArray(0)),
        )
    }

    /** "confirmed" / "finalized" / "processed", "failed", or null when unknown yet. */
    suspend fun signatureStatus(sig: String): String? {
        val r = call(
            "getSignatureStatuses",
            buildJsonArray { add(buildJsonArray { add(JsonPrimitive(sig)) }); add(buildJsonObject { put("searchTransactionHistory", true) }) },
        )
        val row = r.jsonObject["value"]?.jsonArray?.firstOrNull()
        if (row == null || row is JsonNull) return null
        val o = row.jsonObject
        if (o["err"] != null && o["err"] !is JsonNull) return "failed"
        return o["confirmationStatus"]?.jsonPrimitive?.contentOrNull
    }

    /** DAS getAssetsByOwner. Most public RPCs do not have it; callers fall back to local records. */
    suspend fun dasAssetsByOwner(owner: String): List<Pair<String, String>> {
        val r = call(
            "getAssetsByOwner",
            JsonArray(listOf(buildJsonObject { put("ownerAddress", owner); put("page", 1); put("limit", 100) })),
        )
        val items = (r as? JsonObject)?.get("items")?.jsonArray ?: return emptyList()
        return items.mapNotNull {
            val o = it.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["content"]?.jsonObject?.get("metadata")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            id to name
        }
    }

    companion object {
        /** First try, then the two retries after 300 and 900 ms. */
        val RETRY_MS = longArrayOf(0, 300, 900)

        fun retryable(code: Int): Boolean = code == 429 || code in 500..599

        /** Devnet: the market proxy. Mainnet (1.1.0): PublicNode's free endpoint. Others: none. */
        fun fallbackFor(url: String): String? = when (url) {
            net.solardepin.solarchik.core.SolarchikConfig.RPC_DEVNET -> net.solardepin.solarchik.core.SolarchikConfig.RPC_DEVNET_FALLBACK
            net.solardepin.solarchik.core.SolarchikConfig.RPC_MAINNET -> net.solardepin.solarchik.core.SolarchikConfig.RPC_MAINNET_FALLBACK
            else -> null
        }

        /** Throws [RpcException] (never NPE) when the node answers without a usable blockhash. */
        fun parseBlockhash(r: JsonElement): ByteArray {
            val hash = ((r as? JsonObject)?.get("value") as? JsonObject)?.get("blockhash")?.let { it as? JsonPrimitive }?.contentOrNull
                ?: throw RpcException("no blockhash in response")
            return runCatching { Base58.decode(hash) }.getOrNull()?.takeIf { it.size == 32 } ?: throw RpcException("bad blockhash")
        }

        fun parseBalance(r: JsonElement): Long =
            ((r as? JsonObject)?.get("value") as? JsonPrimitive)?.longOrNull ?: throw RpcException("no balance in response")

        fun parseSignatures(r: JsonElement): List<SigInfo> = (r as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sig = (o["signature"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val err = o["err"]
            SigInfo(sig, err == null || err is JsonNull, (o["memo"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
