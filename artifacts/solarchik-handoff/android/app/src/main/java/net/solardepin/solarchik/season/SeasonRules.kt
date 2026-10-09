package net.solardepin.solarchik.season

import android.content.Context
import net.solardepin.solarchik.autopilot.AutoKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * 1.1.0 Season rules watcher (app side). The worker reads official Solana Mobile sources (blog via sitemap, docs;
 * X is not read) and keeps versioned "scoring signals", each with a source URL and a verbatim quote
 * (worker/season-rules.js). Here the Season Agent turns them into small, safe adjustments of its plan:
 *  - favoured / devalued action types change the autopilot's ORDER of suggestions (weights), never the caps;
 *  - "activity made to influence the score counts less" makes the autopilot suggest fewer actions a day;
 *  - featured dApps that are already in the plan's list come first.
 * Anything that would raise spending (a favoured swap) needs the user's approval. Action types the agent doesn't do
 * are only shown. Signals below [APPLY_CONFIDENCE] are shown but never applied. Nothing is invented here.
 */
data class RuleSignal(
    val kind: String, val action: String, val dapp: String, val start: String, val end: String,
    val text: String, val quote: String, val confidence: Double, val url: String, val title: String, val published: String,
    val textUk: String = "",
) {
    fun textFor(lang: String): String = if (lang == "uk" && textUk.isNotBlank()) textUk else text
    val key: String get() = "$kind|$action|${dapp.lowercase()}|${url.substringAfterLast('/')}"
}

data class RuleSource(val url: String, val title: String, val published: String, val summary: String, val relevant: Boolean, val summaryUk: String = "") {
    fun summaryFor(lang: String): String = if (lang == "uk" && summaryUk.isNotBlank()) summaryUk else summary
}

data class SeasonRulesDoc(
    val version: Int, val updatedAt: Long, val checkedAt: Long, val sources: List<RuleSource>, val signals: List<RuleSignal>,
    val skipped: List<String>, val throttled: Boolean = false,
) {
    /** The newest official source that said something about Seeker activity. */
    val latest: RuleSource? get() = sources.filter { it.relevant }.maxByOrNull { it.published } ?: sources.maxByOrNull { it.published }
}

data class RuleChange(val signal: RuleSignal, val type: String, val autoKind: AutoKind?, val dapp: String, val needsApproval: Boolean) {
    companion object {
        const val UP = "up"; const val DOWN = "down"; const val FEWER = "fewer"; const val FEATURED = "featured"
        const val CAMPAIGN = "campaign"; const val INFO = "info"; const val EVERYDAY = "everyday"
    }
    val applies: Boolean get() = type in setOf(UP, DOWN, FEWER, FEATURED, EVERYDAY)
}

/** What the planner uses. NONE = the plain 1.1.0 planner. */
data class RuleTuning(val weights: Map<AutoKind, Double> = emptyMap(), val featured: Set<String> = emptySet(), val maxPerDay: Int = 3) {
    val isNone: Boolean get() = weights.isEmpty() && featured.isEmpty() && maxPerDay >= 3
    companion object { val NONE = RuleTuning() }
}

object SeasonRules {
    const val APPLY_CONFIDENCE = 0.7
    const val UP_WEIGHT = 1.5
    const val DOWN_WEIGHT = 0.5

    fun parse(json: String): SeasonRulesDoc? = runCatching {
        val o = JSONObject(json)
        if (!o.optBoolean("ok", true)) return null
        val sources = o.optJSONArray("sources") ?: JSONArray()
        val sigs = o.optJSONArray("signals") ?: JSONArray()
        SeasonRulesDoc(
            o.optInt("version"), o.optLong("updatedAt"), o.optLong("checkedAt"),
            (0 until sources.length()).map { sources.getJSONObject(it) }.map {
                RuleSource(it.optString("url"), it.optString("title"), it.optString("published"), it.optString("summary"), it.optBoolean("relevant"), it.optString("summary_uk"))
            }.filter { official(it.url) },
            (0 until sigs.length()).map { sigs.getJSONObject(it) }.map {
                RuleSignal(it.optString("kind"), it.optString("action"), it.optString("dapp"), it.optString("start"), it.optString("end"),
                    it.optString("text"), it.optString("quote"), it.optDouble("confidence", 0.0), it.optString("url"), it.optString("title"), it.optString("published"), it.optString("text_uk"))
            }.filter { official(it.url) && it.quote.isNotBlank() },
            (o.optJSONArray("skipped") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("source") } },
            o.optBoolean("throttled"),
        )
    }.getOrNull()

    /** Only Solana Mobile's own sites are trusted as sources. */
    fun official(url: String): Boolean = Regex("^https://(solanamobile\\.com|docs\\.solanamobile\\.com)/").containsMatchIn(url)

    fun kindOf(action: String): AutoKind? = when (action) {
        "checkin" -> AutoKind.CHECKIN
        "swap" -> AutoKind.SWAP
        "dapp" -> AutoKind.DAPP
        "staking" -> AutoKind.STAKING
        else -> null
    }

    fun changes(doc: SeasonRulesDoc?, today: LocalDate = LocalDate.now()): List<RuleChange> {
        if (doc == null) return emptyList()
        val out = ArrayList<RuleChange>()
        for (s in doc.signals) {
            val sure = s.confidence >= APPLY_CONFIDENCE
            val k = kindOf(s.action)
            val c = when {
                // campaigns: only current ones (an end date not passed, or no end date and started in the last 7 days)
                s.kind == "campaign" -> if ((s.end.isNotBlank() && s.end < today.toString()) || (s.end.isBlank() && (s.start.isBlank() || s.start < today.minusDays(7).toString()))) null
                    else RuleChange(s, RuleChange.CAMPAIGN, k, "", false)
                !sure -> RuleChange(s, RuleChange.INFO, k, "", false)
                s.kind == "featured_dapp" -> {
                    val known = SeasonDapps.all.firstOrNull { d -> s.dapp.isNotBlank() && (d.name.equals(s.dapp, true) || d.short.equals(s.dapp, true) || s.dapp.contains(d.short, true)) }
                    if (known != null) RuleChange(s, RuleChange.FEATURED, AutoKind.DAPP, known.name, false) else RuleChange(s, RuleChange.INFO, null, s.dapp, false)
                }
                // "more weight to everyday wallet use": everyday actions (check-in, opening dApps) first, at most 2 a day
                (s.kind == "favored" || s.kind == "counts") && s.action in setOf("daily_use", "wallet_activity") -> RuleChange(s, RuleChange.EVERYDAY, null, "", false)
                (s.kind == "favored" || s.kind == "counts") && k != null -> RuleChange(s, RuleChange.UP, k, "", needsApproval = k == AutoKind.SWAP)
                s.kind == "devalued" && k != null -> RuleChange(s, RuleChange.DOWN, k, "", false)
                s.kind == "devalued" && s.action in setOf("wallet_activity", "daily_use", "other") -> RuleChange(s, RuleChange.FEWER, null, "", false)
                else -> RuleChange(s, RuleChange.INFO, k, s.dapp, false)
            }
            if (c != null && out.none { it.signal.key == c.signal.key }) out += c
        }
        return out
    }

    /** Applied tuning: approved-only for changes that need it; dismissed ones never. */
    fun tuning(changes: List<RuleChange>, approved: Set<String>, dismissed: Set<String>, enabled: Boolean = true): RuleTuning {
        if (!enabled) return RuleTuning.NONE
        val use = changes.filter { it.applies && it.signal.key !in dismissed && (!it.needsApproval || it.signal.key in approved) }
        val w = HashMap<AutoKind, Double>()
        for (c in use) when (c.type) {
            RuleChange.UP -> c.autoKind?.let { w[it] = maxOf(w[it] ?: 1.0, UP_WEIGHT) }
            RuleChange.DOWN -> c.autoKind?.let { w[it] = minOf(w[it] ?: 1.0, DOWN_WEIGHT) }
        }
        val featured = use.filter { it.type == RuleChange.FEATURED }.map { it.dapp }.toSet()
        if (use.any { it.type == RuleChange.EVERYDAY }) for (k in listOf(AutoKind.CHECKIN, AutoKind.DAPP)) w[k] = maxOf(w[k] ?: 1.0, UP_WEIGHT)
        val fewer = use.any { it.type == RuleChange.FEWER }
        val everyday = use.any { it.type == RuleChange.EVERYDAY }
        return RuleTuning(w, featured, if (fewer) 1 else if (everyday) 2 else 3)
    }

    fun pending(changes: List<RuleChange>, approved: Set<String>, dismissed: Set<String>): List<RuleChange> =
        changes.filter { it.needsApproval && it.signal.key !in approved && it.signal.key !in dismissed }

    fun httpGet(url: String): Pair<Int, String> = http(url, null)
    fun httpPost(url: String, body: String): Pair<Int, String> = http(url, body)
    private fun http(url: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 60_000
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        return try {
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
        } finally { c.disconnect() }
    }
}

class SeasonRulesStore(ctx: Context) {
    private val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var json: String
        get() = p.getString("doc", "").orEmpty()
        set(v) { p.edit().putString("doc", v).apply() }
    /** "Adapt automatically" (on by default: only safe changes apply by themselves). */
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply() }
    var seenVersion: Int
        get() = p.getInt("seen", 0)
        set(v) { p.edit().putInt("seen", v).apply() }
    var fetchedAt: Long
        get() = p.getLong("fetchedAt", 0L)
        set(v) { p.edit().putLong("fetchedAt", v).apply() }
    var approved: Set<String>
        get() = p.getStringSet("approved", emptySet()).orEmpty()
        set(v) { p.edit().putStringSet("approved", v).apply() }
    var dismissed: Set<String>
        get() = p.getStringSet("dismissed", emptySet()).orEmpty()
        set(v) { p.edit().putStringSet("dismissed", v).apply() }

    fun doc(): SeasonRulesDoc? = json.takeIf { it.isNotBlank() }?.let { SeasonRules.parse(it) }
    fun changes(): List<RuleChange> = SeasonRules.changes(doc())
    fun tuning(): RuleTuning = SeasonRules.tuning(changes(), approved, dismissed, enabled)
    val updated: Boolean get() = (doc()?.version ?: 0) > seenVersion && (doc()?.signals?.isNotEmpty() == true)

    /** Stores a newer (or equal) version from the worker. Returns true when the version went up. */
    fun accept(body: String): Boolean {
        val d = SeasonRules.parse(body) ?: return false
        val old = doc()?.version ?: 0
        if (d.version < old) return false
        json = body
        return d.version > old
    }

    companion object { const val PREFS = "solarchik.seasonrules" }
}

/** Network: GET the current rules (at most hourly) or ask the worker to check the official sources now. */
object SeasonRulesSync {
    const val EVERY_MS = 60 * 60_000L
    fun refresh(ctx: Context, get: (String) -> Pair<Int, String> = SeasonRules::httpGet, now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean {
        val st = SeasonRulesStore(ctx)
        if (!force && now - st.fetchedAt < EVERY_MS) return false
        val (code, body) = runCatching { get(net.solardepin.solarchik.screen.ScreenApi.BASE + "/season/rules") }.getOrElse { 0 to "" }
        if (code != 200) return false
        st.fetchedAt = now
        return st.accept(body)
    }

    /** "Check for rule updates": the worker reads the official sources now (throttled server-side to every 5 min). */
    fun checkNow(ctx: Context, post: (String, String) -> Pair<Int, String> = SeasonRules::httpPost, now: Long = System.currentTimeMillis()): Result<SeasonRulesDoc> {
        val (code, body) = runCatching { post(net.solardepin.solarchik.screen.ScreenApi.BASE + "/season/rules/check", "{}") }.getOrElse { return Result.failure(it) }
        if (code != 200) return Result.failure(IllegalStateException("HTTP $code"))
        val st = SeasonRulesStore(ctx)
        st.accept(body)
        st.fetchedAt = now
        return SeasonRules.parse(body)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("bad reply"))
    }
}
