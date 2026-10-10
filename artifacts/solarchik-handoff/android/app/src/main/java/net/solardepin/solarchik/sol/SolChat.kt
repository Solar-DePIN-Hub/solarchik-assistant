package net.solardepin.solarchik.sol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.solana.Rpc
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

@Serializable
data class ChatTurn(
    /** "user" or "assistant" (wire names of the friend worker). */
    val role: String,
    val text: String,
    val at: Long,
    /** The worker answered with its canned fallback, or the phone was offline. */
    val fallback: Boolean = false,
    /** Answered on the phone from the game rules, not by the worker. */
    val local: Boolean = false,
    /** Devnet Explorer link of a transaction Sol ran after the player's confirm tap (0.21.7). */
    val link: String = "",
    /** A line from Sol's action mode (confirm card, result). */
    val action: Boolean = false,
)

data class SolReply(val text: String, val fallback: Boolean, val offline: Boolean, val model: String = "")

/**
 * Sol's live AI. Primary: solarchik-market /api/native/sol-chat (Gemini, short warm replies strictly
 * in the app language, ~1 s). Secondary: the AI friend worker (worker/solarchik-ai-friend.js). A reply
 * that is not in the app language (Ukrainian with stray English words, or Cyrillic for English) is
 * dropped and the next endpoint answers. Only when every endpoint fails does the caller get the
 * offline line (marked offline + fallback so the UI labels it). No keys on the phone.
 */
class SolChat(
    private val urls: List<String> = listOf(SolarchikConfig.SOL_CHAT_URL, SolarchikConfig.FRIEND_CHAT_URL),
    private val post: suspend (String, String) -> String? = ::httpPost,
) {
    constructor(url: String, post: suspend (String, String) -> String? = ::httpPost) : this(listOf(url), post)

    suspend fun ask(message: String, language: String, playerId: String, conversationId: String, history: List<ChatTurn>, scene: String = "yard", context: String = ""): SolReply {
        val lang = if (language == "uk") "uk" else "en"
        val turns = buildJsonArray {
            history.filter { !it.fallback && !it.local }.takeLast(6).forEach { t ->
                add(buildJsonObject { put("role", t.role); put("content", t.text.take(400)) })
            }
        }
        var answered = false
        for (url in urls) {
            val friend = url == SolarchikConfig.FRIEND_CHAT_URL || url.endsWith("/v1/chat")
            val body = buildJsonObject {
                put("message", message.take(2000))
                put("language", lang)
                put("scene", scene)
                // 1.2.9: the assistant persona (no game wording) for every chat from this app, not only SolBrain
                if (scene != "run") put("app", net.solardepin.solarchik.core.SolarchikConfig.SOL_APP)
                if (context.isNotBlank()) put("context", context.take(400))
                put("history", turns)
                if (friend) {
                    put("playerId", playerId)
                    put("conversationId", conversationId)
                    put("name", "Sol")
                }
            }.toString()
            val text = try {
                post(url, body)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c // the screen went away: no reply to store
            } catch (_: Throwable) {
                null
            } ?: continue
            answered = true
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val reply = o["reply"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val fb = o["fallback"]?.jsonPrimitive?.booleanOrNull == true
            if (reply.isBlank() || fb || !fitsLanguage(reply, lang)) continue
            return SolReply(SolRules.tidy(reply), false, false, o["model"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        return SolReply(offlineLine(lang), fallback = true, offline = !answered)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun offlineLine(language: String): String =
            if (language == "uk") "Я зараз не дістаю до сонячної вежі. Спробуй ще раз за хвилину." else "I can't reach the sun tower right now. Try again in a minute."

        /** Latin words a Ukrainian reply may contain (names of the game, chain and tiers). */
        private val LATIN_OK = setOf("sol", "solana", "solarchik", "devnet", "mainnet", "nft", "nfts", "pro", "usdc", "btc", "ok", "ai", "seeker", "phantom", "solflare", "slice")
        private val LATIN_WORD = Regex("\\b[A-Za-z]{2,}\\b")

        /** A uk reply must be Cyrillic (no Russian-only letters, no stray English); an en reply has no Cyrillic. */
        fun fitsLanguage(reply: String, lang: String): Boolean {
            val letters = reply.count { it.isLetter() }
            if (letters == 0) return false
            val cyr = reply.count { it in '\u0400'..'\u04FF' }
            if (lang != "uk") return cyr == 0
            if (cyr < letters * 0.7) return false
            if (reply.any { it in "ыэъёЫЭЪЁ" }) return false
            return LATIN_WORD.findAll(reply).all { it.value.lowercase() in LATIN_OK }
        }

        private val clientFor = mutableMapOf<String, okhttp3.OkHttpClient>()

        private fun client(url: String): okhttp3.OkHttpClient = synchronized(clientFor) {
            // The market route answers in ~1 s and gives up itself after 9 s; the friend worker after 14 s.
            val secs = if (url.endsWith("/v1/chat")) 15L else 11L
            clientFor.getOrPut(url) { Rpc.client.newBuilder().callTimeout(secs, java.util.concurrent.TimeUnit.SECONDS).build() }
        }

        internal suspend fun httpPost(url: String, body: String): String? = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Origin", "https://appassets.androidplatform.net")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client(url).newCall(req).execute().use { res -> if (res.isSuccessful) res.body?.string() else null }
        }

        private val NUM = Regex("\\d+(?:\\.\\d+)?")

        fun nums(text: String): List<String> = NUM.findAll(text).map { it.value }.toList()

        /** Web DailyReport onlyKnownNumbers: every number in the reply must appear in the note. */
        fun onlyKnownNumbers(reply: String, source: String): Boolean {
            val allow = nums(source).toSet()
            return nums(reply).all { it in allow }
        }
    }
}

/** Chat history on the phone (last 40 turns) and a conversation id per UTC day. */
class SolChatStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("solarchik-sol", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun turns(): List<ChatTurn> = prefs.getString("turns", null)?.let {
        runCatching { json.decodeFromString(ListSerializer(ChatTurn.serializer()), it) }.getOrNull()
    } ?: emptyList()

    fun add(turn: ChatTurn) {
        val all = (turns() + turn).takeLast(40)
        prefs.edit().putString("turns", json.encodeToString(ListSerializer(ChatTurn.serializer()), all)).apply()
    }

    fun clear() = prefs.edit().remove("turns").apply()

    fun playerId(): String = PlayerIds.get(app)

    /** One conversation per UTC day; older day ids are dropped so prefs do not grow forever. */
    fun conversationId(day: String): String {
        val key = "conv.$day"
        prefs.getString(key, null)?.let { return it }
        val id = "sol-" + UUID.randomUUID().toString().take(12)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("conv.") && it != key }.forEach { edit.remove(it) }
        edit.putString(key, id).apply()
        return id
    }

    var voiceOn: Boolean
        get() = prefs.getBoolean("voice", true)
        set(v) = prefs.edit().putBoolean("voice", v).apply()
}
