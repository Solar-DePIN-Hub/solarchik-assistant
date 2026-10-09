package net.solardepin.solarchik.sol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.solana.Rpc
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 0.21.8: ONE path for everything the player says to Sol (chat tab and in-run voice).
 *
 *  1. worker /sol/chat (OpenAI, streamed NDJSON: `{"d":…}` text deltas, then `{"done":true, reply, action}`)
 *     answers small talk AND proposes one structured action, first words in ~0.6 s;
 *  2. the market sol-act route (Gemini) when the worker is down;
 *  3. the phone's own parser ([SolActions.parseLocal]) plus a labelled offline line.
 *
 * The friend worker is no longer on this path: it told players they "can only watch" their agents.
 * Nothing here executes an action; the caller shows the confirmation card.
 */
class SolBrain(
    private val url: String = SolarchikConfig.SOL_BRAIN_URL,
    private val market: SolActClient = SolActClient(),
    /** POSTs [body] to [url] and feeds every response line to the callback; false when it failed. Test seam. */
    private val stream: suspend (String, String, (String) -> Unit) -> Boolean = ::httpLines,
) {
    enum class Source { WORKER, MARKET, OFFLINE }

    data class Reply(
        val reply: String,
        val action: SolAction?,
        val source: Source,
        val model: String = "",
        val ttftMs: Long? = null,
        val ms: Long = 0,
    ) {
        val offline: Boolean get() = source == Source.OFFLINE
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun body(message: String, language: String, scene: String, ctx: ActContext, history: List<ChatTurn>, context: String = "", state: SolState? = null): String = buildJsonObject {
        put("message", message.take(600))
        put("language", if (language == "uk") "uk" else "en")
        put("scene", scene)
        put("stream", true)
        put("app", net.solardepin.solarchik.core.SolarchikConfig.SOL_APP)
        // 0.22.0: the fresh player state goes first (never cut by the length cap) and as JSON for the worker
        val full = listOfNotNull(state?.line(), context.takeIf { it.isNotBlank() }).joinToString("\n")
        if (full.isNotBlank()) put("context", full.take(if (scene == "run") 800 else 1200))
        state?.let { put("state", it.toJson()) }
        val c = ctx.toJson()
        c["agents"]?.let { put("agents", it) }
        c["market"]?.let { put("market", it) }
        put("canMintFree", ctx.canMintFree)
        putJsonArray("history") {
            history.filter { !it.fallback && !it.local }.takeLast(6).forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.text.take(400)) }) }
        }
    }.toString()

    /**
     * Asks Sol. [onDelta] gets the reply text as it streams (the whole text so far), so the UI and the
     * voice can start on the first sentence. Never throws except for cancellation.
     */
    suspend fun ask(
        message: String, language: String, scene: String, ctx: ActContext, history: List<ChatTurn>,
        context: String = "", state: SolState? = null, onDelta: (String) -> Unit = {},
    ): Reply {
        val t0 = System.currentTimeMillis()
        var text = StringBuilder()
        var done: JsonObject? = null
        var first: Long? = null
        // 0.22.3: one quick retry when the first call fails before any text (cold connection / worker isolate)
        var attempt = 0
        var ok: Boolean
        while (true) {
        text = StringBuilder(); done = null; first = null
        ok = try {
            stream(url, body(message, language, scene, ctx, history, context, state)) { line ->
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@stream
                val d = (o["d"] as? JsonPrimitive)?.content
                if (d != null) {
                    if (first == null) first = System.currentTimeMillis() - t0
                    text.append(d)
                    onDelta(text.toString())
                }
                if ((o["done"] as? JsonPrimitive)?.content == "true") done = o
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            false
        }
        val good = ok && (done?.get("ok") as? JsonPrimitive)?.content == "true"
        if (good || text.isNotEmpty() || attempt >= 1 || System.currentTimeMillis() - t0 > 9_000) break
        attempt++
        }
        val fin = done
        if (ok && fin != null && (fin["ok"] as? JsonPrimitive)?.content == "true") {
            val reply = (fin["reply"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { text.toString().trim() }
            val action = SolActions.normalize(fin["action"] as? JsonObject, ctx)
            if (reply.isNotBlank() || action != null) {
                return Reply(SolRules.tidy(reply), action, Source.WORKER, (fin["model"] as? JsonPrimitive)?.content.orEmpty(), first, System.currentTimeMillis() - t0)
            }
        }
        // In a run the market route is wrong: it is the agent/strategy desk (it answered game questions about agents)
        if (scene == "run") return Reply("", SolActions.parseLocal(message, ctx), Source.OFFLINE, "", null, System.currentTimeMillis() - t0)
        // 2. market sol-act (it falls back to the phone parser itself when unreachable)
        val m = try { market.ask(message, language, ctx, history) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { SolActReply("", SolActions.parseLocal(message, ctx), offline = true) }
        if (!m.offline && (m.reply.isNotBlank() || m.action != null)) {
            val r = m.reply.takeIf { SolChat.fitsLanguage(it, if (language == "uk") "uk" else "en") }.orEmpty()
            return Reply(SolRules.tidy(r), m.action, Source.MARKET, m.model, null, System.currentTimeMillis() - t0)
        }
        // 3. offline: the phone parser still understands commands; everything else gets a labelled line
        return Reply("", m.action ?: SolActions.parseLocal(message, ctx), Source.OFFLINE, "", null, System.currentTimeMillis() - t0)
    }

    companion object {
        /** A [stream] that always fails (tests, or no network). */
        val NO_WORKER: suspend (String, String, (String) -> Unit) -> Boolean = { _, _, _ -> false }

        private val client by lazy { Rpc.client.newBuilder().callTimeout(14, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build() }

        suspend fun httpLines(url: String, body: String, onLine: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Accept", "application/x-ndjson, application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext false
                val src = res.body?.source() ?: return@withContext false
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isNotBlank()) withContext(Dispatchers.Main.immediate) { onLine(line) }
                }
                true
            }
        }
    }
}
