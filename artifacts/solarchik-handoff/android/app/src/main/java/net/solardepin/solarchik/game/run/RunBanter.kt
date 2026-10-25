package net.solardepin.solarchik.game.run

import kotlin.random.Random

/** Port of the web runBanter.ts (EN/UK packs): fallback lines when the AI friend is offline. */
enum class BanterKind { GO, CHAPTER, COMBO, GOLD, HURT, DEAD, BONUS, GRIND, SHIELD, BOSS }

/** Scripted captions (string resources), web scriptedBanter keys. */
enum class ScriptLine { CLOCK_READY, FIRST_ROOF, LAST_HEART }

object RunBanter {
    private data class Pack(val kinds: Map<BanterKind, List<String>>, val chapters: Map<ChapterId, String>, val ctx: String)
    private val packs = java.util.concurrent.ConcurrentHashMap<String, Pack>()

    /** Lines live in res (strings_banter.xml, en + uk); loads the pack for [lang] once. */
    fun load(context: android.content.Context, lang: String) {
        val l = if (lang == "uk") "uk" else "en"
        if (packs.containsKey(l)) return
        val conf = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale(l)) }
        val r = context.createConfigurationContext(conf).resources
        fun id(n: String, type: String) = r.getIdentifier(n, type, context.packageName)
        val kinds = BanterKind.values().associateWith { k -> id("banter_" + k.name.lowercase(), "array").takeIf { it != 0 }?.let { r.getStringArray(it).toList() }.orEmpty() }
        val ch = ChapterId.values().mapNotNull { c -> id("banter_ch_" + c.name.lowercase(), "string").takeIf { it != 0 }?.let { c to r.getString(it) } }.toMap()
        packs[l] = Pack(kinds, ch, r.getString(id("banter_context", "string")))
    }

    private var lastLine = ""

    fun pool(kind: BanterKind, lang: String, chapter: ChapterId?): List<String> {
        val p = packs[if (lang == "uk") "uk" else "en"] ?: return emptyList()
        if (kind == BanterKind.CHAPTER && chapter != null) p.chapters[chapter]?.let { return listOf(it) }
        return p.kinds[kind].orEmpty()
    }

    fun pick(kind: BanterKind, lang: String, chapter: ChapterId?, rnd: Random = Random.Default): String {
        val all = pool(kind, lang, chapter)
        val list = all.filter { it.isNotEmpty() && it != lastLine }.ifEmpty { all }
        val line = if (list.isEmpty()) "" else list[rnd.nextInt(list.size)]
        lastLine = line
        return line
    }

    /** web scriptedBanter(state, events). [lastHeartSaid] is the run's one-shot flag. */
    fun scripted(meters: Double, death: DeathKind, hearts: Int, events: List<Ev>, goal: Int, lastHeartSaid: Boolean): ScriptLine? {
        if (Ev.CLOCK in events) return ScriptLine.CLOCK_READY
        if (Ev.DEAD in events) {
            if (death == DeathKind.FALL && meters < 200) return ScriptLine.FIRST_ROOF
            if (meters >= goal) return ScriptLine.CLOCK_READY
        }
        if (Ev.HURT in events && hearts == 1 && !lastHeartSaid) return ScriptLine.LAST_HEART
        return null
    }

    fun eventToBanter(ev: Ev): BanterKind? = if (ev == Ev.DEAD) BanterKind.DEAD else null

    fun context(meters: Int, chapter: ChapterId, combo: Int, suns: Int, hearts: Int, lang: String = "en"): String =
        (packs[if (lang == "uk") "uk" else "en"]?.ctx ?: "%1\$d m, combo %2\$d, suns %3\$d, hearts %4\$d").format(meters, combo, suns, hearts)

    fun periodicKind(bonus: Boolean, combo: Int, hearts: Int): BanterKind = when {
        bonus -> BanterKind.BONUS
        combo >= 4 -> BanterKind.COMBO
        hearts <= 1 -> BanterKind.HURT
        else -> BanterKind.CHAPTER
    }
}
