package net.solardepin.solarchik.screen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calls answered by the AI phone secretary (0.21.9), as the worker's GET /inbox returns them, plus what the
 * app keeps locally: the last list (shown offline), which calls were seen (unread badge) and which were
 * already announced with a notification. Several player ids can be watched: this phone's own id and ids
 * the player pasted ("show calls of another player ID", e.g. the demo line's owner account).
 */
data class CallItem(
    val owner: String,
    val callId: String,
    val caller: String,
    val text: String,
    val at: Long,
    val status: String,
    val source: String,
    val callerName: String,
    val intent: String,
    val urgency: String,
    val notes: String,
    val callback: String,
    val lang: String,
    val durationSec: Int?,
    val chargedUsd: Double?,
    val trial: Boolean,
    /** Why a missed call was not answered (worker: NEED_TOPUP, TRIAL_CALLER_CAP, TRIAL_DAILY_CAP). */
    val reason: String = "",
) {
    /** Stable key across ids and old voicemail lines without a call id. */
    val key: String get() = owner + "|" + callId.ifBlank { "vm:$at" }
    val answered: Boolean get() = status == CallInbox.DONE || status == CallInbox.PENDING
    val missed: Boolean get() = status == CallInbox.FAILED || status == CallInbox.NEED_TOPUP
    val blocked: Boolean get() = status == CallInbox.BLOCKED
    /** A finished note worth a notification (not the "note follows" placeholder). */
    val hasNote: Boolean get() = status == CallInbox.DONE || callId.isBlank()
    /** Number to call back: the secretary's callback, else the caller id. */
    val dialNumber: String get() = callback.ifBlank { caller }.takeIf { it.isNotBlank() && it != "unknown" }.orEmpty()
    val who: String get() = callerName.ifBlank { caller.takeIf { it.isNotBlank() && it != "unknown" }.orEmpty() }
}

data class TranscriptLine(val caller: Boolean, val text: String)

data class CallDetail(val item: CallItem, val lines: List<TranscriptLine>, val durationSec: Int?)

object CallInbox {
    const val DONE = "done"
    const val PENDING = "pending"
    const val FAILED = "failed"
    const val NEED_TOPUP = "need_topup"
    const val BLOCKED = "blocked"

    /** The demo secretary line (calls to it are routed to a player with POST /call-claim). */
    const val DEMO_LINE = "+380914810885"

    private const val PREF = "solarchik.calls"

    private fun canon(id: String) = id.replace(Regex("^(rtc|live)_"), "")

    /** Parses GET /inbox for [owner]. Drops empty lines and the failed live_ twin of an answered call. */
    fun parse(owner: String, code: Int, body: String): List<CallItem>? {
        if (code !in 200..299) return null
        val arr = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull() ?: return emptyList()
        val all = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { item(owner, it) } }
        return dedupe(all)
    }

    fun item(owner: String, o: JSONObject): CallItem? {
        val text = o.optString("text").trim()
        if (text.isEmpty()) return null
        val s = o.optJSONObject("summary") ?: JSONObject()
        return CallItem(
            owner = owner,
            callId = o.optString("callId").trim(),
            caller = o.optString("caller").trim(),
            text = text.take(600),
            at = o.optLong("at"),
            status = o.optString("status").trim().ifBlank { if (o.optString("callId").isNotBlank()) PENDING else DONE },
            source = o.optString("source").trim(),
            callerName = s.optString("caller_name").trim().take(60),
            intent = s.optString("intent").trim().take(200),
            urgency = s.optString("urgency").trim(),
            notes = s.optString("notes").trim().take(400),
            callback = s.optString("callback").trim().take(40),
            lang = o.optString("lang").trim(),
            durationSec = if (o.has("durationSec") && !o.isNull("durationSec")) o.optInt("durationSec").takeIf { it > 0 } else null,
            chargedUsd = if (o.has("chargedUsd")) o.optDouble("chargedUsd").takeIf { it.isFinite() } else null,
            trial = o.optBoolean("trial", false),
            reason = o.optString("reason").trim().take(40),
        )
    }

    fun dedupe(items: List<CallItem>): List<CallItem> {
        val good = items.filter { it.callId.isNotBlank() && it.status != FAILED }.map { it.owner + "|" + canon(it.callId) }.toSet()
        val kept = items.filter { !(it.callId.isNotBlank() && it.status == FAILED && (it.owner + "|" + canon(it.callId)) in good) }
            .distinctBy { it.key }
        // 1.2.6.1 (tablet: two identical Ira rows): the same call can come back under two ids this phone reads
        // (the user's own and the line owner's), or twice with the same note. One row per call.
        val seen = HashSet<String>()
        val out = ArrayList<CallItem>()
        for (c in kept.sortedByDescending { if (it.status == DONE) 1 else 0 }) {
            val byId = if (c.callId.isNotBlank()) "id:" + canon(c.callId) else null
            val byText = if (c.text.isNotBlank()) "tx:" + c.caller + "|" + c.text.trim() + "|" + (c.at / 300_000L) else null
            if ((byId != null && byId in seen) || (byText != null && byText in seen)) continue
            byId?.let { seen += it }; byText?.let { seen += it }
            out += c
        }
        val order = kept.withIndex().associate { it.value to it.index }
        return out.sortedBy { order[it] ?: 0 }
    }

    /** Merges the lists of several ids, newest first. */
    fun merge(lists: List<List<CallItem>>): List<CallItem> = dedupe(lists.flatten()).sortedByDescending { it.at }

    fun parseDetail(owner: String, code: Int, body: String): CallDetail? {
        if (code !in 200..299) return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val item = o.optJSONObject("item")?.let { item(owner, it) } ?: return null
        val arr = o.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until arr.length()).mapNotNull { i ->
            val l = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = l.optString("text").trim()
            if (t.isEmpty()) null else TranscriptLine(l.optString("who") == "caller", t.take(500))
        }
        val dur = if (o.has("durationSec") && !o.isNull("durationSec")) o.optInt("durationSec").takeIf { it > 0 } else item.durationSec
        return CallDetail(item, lines, dur)
    }

    /** Unread = answered/missed calls newer than the last time the Calls screen was open. */
    fun unread(items: List<CallItem>, seenAt: Long): Int = items.count { it.at > seenAt && !it.blocked && it.status != PENDING }

    /** Calls that deserve a notification now: finished, not announced yet, not older than [since]. */
    fun toAnnounce(items: List<CallItem>, announced: Set<String>, since: Long): List<CallItem> =
        items.filter { it.key !in announced && it.at > since && (it.hasNote || it.missed) }

    /** "1:05" style duration. */
    fun duration(sec: Int?): String = if (sec == null || sec <= 0) "" else String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60)

    // ---------------- local state ----------------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Player ids whose calls this phone shows: its own first, then linked ones. */
    fun ids(ctx: Context): List<String> = (listOf(PlayerIds.get(ctx)) + linked(ctx)).distinct()

    fun linked(ctx: Context): List<String> = prefs(ctx).getString("linked", "").orEmpty().split(',').map { it.trim() }.filter { valid(it) }

    fun valid(id: String): Boolean = id.length in 8..80 && id.none { it.isWhitespace() || it == ',' }

    fun link(ctx: Context, id: String): Boolean {
        val clean = id.trim()
        if (!valid(clean) || clean == PlayerIds.get(ctx)) return false
        prefs(ctx).edit().putString("linked", (linked(ctx) + clean).distinct().takeLast(3).joinToString(",")).apply()
        return true
    }

    fun unlink(ctx: Context, id: String) {
        prefs(ctx).edit().putString("linked", linked(ctx).filter { it != id }.joinToString(",")).apply()
    }

    fun cached(ctx: Context): List<CallItem> {
        val raw = prefs(ctx).getString("cache", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            dedupe((0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> item(o.optString("owner"), o) } })
        }.getOrDefault(emptyList())
    }

    fun store(ctx: Context, items: List<CallItem>) {
        val a = JSONArray()
        items.take(40).forEach { a.put(toJson(it)) }
        prefs(ctx).edit().putString("cache", a.toString()).putLong("fetchedAt", System.currentTimeMillis()).apply()
    }

    fun fetchedAt(ctx: Context): Long = prefs(ctx).getLong("fetchedAt", 0)

    fun toJson(it: CallItem): JSONObject = JSONObject()
        .put("owner", it.owner).put("callId", it.callId).put("caller", it.caller).put("text", it.text).put("at", it.at)
        .put("status", it.status).put("source", it.source).put("lang", it.lang).put("trial", it.trial).put("reason", it.reason)
        .apply { it.durationSec?.let { d -> put("durationSec", d) }; it.chargedUsd?.let { c -> put("chargedUsd", c) } }
        .put("summary", JSONObject().put("caller_name", it.callerName).put("intent", it.intent).put("urgency", it.urgency).put("notes", it.notes).put("callback", it.callback))

    fun seenAt(ctx: Context): Long = prefs(ctx).getLong("seenAt", 0)
    fun markSeen(ctx: Context, at: Long = System.currentTimeMillis()) { prefs(ctx).edit().putLong("seenAt", at).apply() }
    fun unreadCount(ctx: Context): Int = unread(cached(ctx), seenAt(ctx))

    fun announced(ctx: Context): Set<String> = prefs(ctx).getString("announced", "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
    fun addAnnounced(ctx: Context, keys: Collection<String>) {
        val all = (prefs(ctx).getString("announced", "").orEmpty().split('\n').filter { it.isNotBlank() } + keys).distinct().takeLast(200)
        prefs(ctx).edit().putString("announced", all.joinToString("\n")).apply()
    }

    /** First launch with 0.21.9: older calls are not announced (only those after the install/upgrade). */
    fun announceSince(ctx: Context): Long {
        val p = prefs(ctx)
        val v = p.getLong("announceSince", 0)
        if (v > 0) return v
        val now = System.currentTimeMillis() - 10 * 60_000L
        p.edit().putLong("announceSince", now).apply()
        return now
    }

    /** Numbers this phone blocked (mirrors the worker's list for the own id). */
    fun blockedLocal(ctx: Context): Set<String> = prefs(ctx).getStringSet("blocked", emptySet()).orEmpty()
    fun setBlockedLocal(ctx: Context, numbers: Collection<String>) { prefs(ctx).edit().putStringSet("blocked", numbers.toSet()).apply() }

    /** Fetches every watched id; returns the merged list (null when every request failed). */
    /** Tests (screen audits): the worker is not asked, the seeded list stays. */
    @Volatile internal var offlineForTest = false

    fun refresh(ctx: Context): List<CallItem>? {
        if (offlineForTest) return null
        val lists = ids(ctx).map { ScreenApi.calls(it) }
        if (lists.all { it == null }) return null
        val merged = merge(lists.filterNotNull())
        // keep a failed id's old lines instead of dropping them
        val failed = ids(ctx).filterIndexed { i, _ -> lists[i] == null }.toSet()
        val keep = cached(ctx).filter { it.owner in failed }
        val out = merge(listOf(merged, keep))
        store(ctx, out)
        return out
    }
}
