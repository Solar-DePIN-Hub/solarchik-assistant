package net.solardepin.solarchik.ui

import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.season.SeasonDrop
import net.solardepin.solarchik.season.SeasonDropsStore
import net.solardepin.solarchik.season.SeasonDropsSync

/**
 * 1.2.0 "Today's Season tasks": Seeker Season partner drops with the perk as announced, the source (blog / docs,
 * or a curated @solanamobile post on X with the date it was checked) and a button to open it. No points promises.
 */
class SeasonDropsPanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    private var loading = false
    // unit tests never reach the live worker unless they inject [get]
    private val offline = android.os.Build.FINGERPRINT == "robolectric"
    @androidx.annotation.VisibleForTesting var get: ((String) -> Pair<Int, String>)? = null

    fun refresh(force: Boolean = false) {
        if (loading || (offline && get == null)) return
        loading = true
        val lang = host.lang
        host.scope.launch {
            val g = get ?: net.solardepin.solarchik.season.SeasonRules::httpGet
            val up = withContext(Dispatchers.IO) { runCatching { SeasonDropsSync.refresh(host, lang, g, force = force) }.getOrDefault(false) }
            loading = false
            if (up) onChange()
        }
    }

    fun card(): View = Ui.card(ctx, accent = Ui.CYAN).apply {
        tag = "season-drops"
        val doc = SeasonDropsStore(ctx).doc(host.lang)
        val head = Ui.row(ctx, gap = 12).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_check, Ui.CYAN, 40))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.sd_title))))
        doc?.items?.size?.takeIf { it > 0 }?.let { head.addView(Ui.pill(ctx, it.toString(), Ui.CYAN).apply { tag = "season-drops-count" }) }
        addView(head)
        val items = doc?.items.orEmpty()
        if (items.isEmpty()) {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(if (loading) R.string.sd_loading else R.string.sd_empty), 13f).apply { tag = "season-drops-empty" }, 10))
        } else items.take(6).forEachIndexed { i, d -> addView(Ui.top(row(d, i), if (i == 0) 12 else 8)) }
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sd_sources), 11.5f).apply { tag = "season-drops-sources"; setLineSpacing(0f, 1.2f) }, 12))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sd_no_points), 11.5f).apply { setLineSpacing(0f, 1.2f) }, 4))
    }

    private fun row(d: SeasonDrop, i: Int): View = Ui.column(ctx).apply {
        tag = "season-drop-$i"
        background = Ui.rounded(Ui.withAlpha(android.graphics.Color.WHITE, 0x08), 14f * ctx.resources.displayMetrics.density)
        val p = (12 * ctx.resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        addView(Ui.text(ctx, d.app, 15f, Ui.TEXT, 800))
        addView(Ui.top(Ui.text(ctx, d.perk, 13f, Ui.withAlpha(Ui.TEXT, 0xDD), 500).apply { setLineSpacing(0f, 1.2f) }, 4))
        if (d.deadline.isNotBlank()) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sd_until, day(d.deadline)), 12f, Ui.AMBER, 700), 4))
        val src = if (d.curated) ctx.getString(R.string.sd_src_curated, day(d.sourceDate), day(d.checked)) else ctx.getString(R.string.sd_src_official, day(d.sourceDate))
        addView(Ui.top(Ui.muted(ctx, src, 11.5f), 4))
        addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.sd_open), Ui.Btn.GHOST, R.drawable.ic_open) { host.openUrl(d.sourceUrl) }.apply { tag = "season-drop-open-$i"; textSize = 13f }, 8))
    }

    /** "2026-10-07" in the app's UI language: "Oct 7" / "7 жовт.". */
    private fun day(iso: String): String = runCatching {
        val loc = net.solardepin.solarchik.core.AppLocale.ui(ctx)
        java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern(if (loc.language == "uk") "d MMM" else "MMM d", loc))
    }.getOrDefault(iso)
}
