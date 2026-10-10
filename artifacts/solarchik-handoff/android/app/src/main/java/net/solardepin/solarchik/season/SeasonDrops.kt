package net.solardepin.solarchik.season

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.2.0 Season agent v2: Seeker Season partner drops ("today's Season tasks") from the worker's GET /season/drops.
 * The worker reads the official Solana Mobile blog and docs; drops announced on X are a hand-checked, dated list
 * (origin "curated"); X itself is read only when the paid X API is switched on (x = true). Nothing is invented here:
 * an item without an official source link is dropped, and a deadline in the past hides the item.
 */
data class SeasonDrop(
    val id: String, val app: String, val perk: String, val deadline: String, val sourceUrl: String, val sourceDate: String,
    val origin: String, val via: String = "", val checked: String = "",
) {
    val curated: Boolean get() = origin == "curated"
}

data class SeasonDropsDoc(val items: List<SeasonDrop>, val x: Boolean, val sourcesText: String, val curatedChecked: String, val checkedAt: Long)

object SeasonDrops {
    /** Hosts a drop's source may be on: Solana Mobile's own sites and posts on X (official or reposted partner posts). */
    fun trusted(url: String): Boolean = Regex("^https://(([a-z0-9-]+\\.)?solanamobile\\.com|x\\.com)/").containsMatchIn(url)

    fun parse(json: String, today: String = java.time.LocalDate.now().toString()): SeasonDropsDoc? = runCatching {
        val o = JSONObject(json)
        if (!o.optBoolean("ok", true)) return null
        val a = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until a.length()).map { a.getJSONObject(it) }.map {
            SeasonDrop(it.optString("id"), it.optString("app"), it.optString("perk"), it.optString("deadline"), it.optString("sourceUrl"),
                it.optString("sourceDate"), it.optString("origin"), it.optString("via"), it.optString("checked"))
        }.filter { it.app.isNotBlank() && it.perk.isNotBlank() && trusted(it.sourceUrl) && (it.deadline.isBlank() || it.deadline >= today) }
        SeasonDropsDoc(items, o.optBoolean("x"), o.optString("sourcesText"), o.optString("curatedChecked"), o.optLong("checkedAt"))
    }.getOrNull()

    /** "MattleFun: Turn One Up birthday event…" lines for the briefing and Sol (at most [n]). */
    fun lines(doc: SeasonDropsDoc?, n: Int = 3): List<String> = doc?.items.orEmpty().take(n).map { it.app + ": " + it.perk.trimEnd('.') }
}

class SeasonDropsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun json(lang: String): String = p.getString("doc_$lang", "").orEmpty()
    fun fetchedAt(lang: String): Long = p.getLong("at_$lang", 0L)
    fun save(lang: String, body: String, now: Long) { p.edit().putString("doc_$lang", body).putLong("at_$lang", now).apply() }
    fun doc(lang: String): SeasonDropsDoc? = json(lang).takeIf { it.isNotBlank() }?.let { SeasonDrops.parse(it) }

    companion object { const val PREFS = "solarchik.seasondrops" }
}

object SeasonDropsSync {
    const val EVERY_MS = 60 * 60_000L

    /** GET /season/drops?lang= at most hourly (per language). Returns true when a new answer was stored. */
    fun refresh(ctx: Context, lang: String, get: (String) -> Pair<Int, String> = SeasonRules::httpGet, now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean {
        val st = SeasonDropsStore(ctx)
        if (!force && now - st.fetchedAt(lang) < EVERY_MS && st.json(lang).isNotBlank()) return false
        val (code, body) = runCatching { get(net.solardepin.solarchik.screen.ScreenApi.BASE + "/season/drops?lang=" + lang) }.getOrElse { 0 to "" }
        if (code != 200 || SeasonDrops.parse(body) == null) return false
        st.save(lang, body, now)
        return true
    }
}
