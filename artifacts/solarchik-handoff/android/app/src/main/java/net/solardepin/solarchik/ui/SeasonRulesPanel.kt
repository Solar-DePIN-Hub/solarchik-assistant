package net.solardepin.solarchik.ui

import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.autopilot.AutoKind
import net.solardepin.solarchik.season.RuleChange
import net.solardepin.solarchik.season.SeasonRules
import net.solardepin.solarchik.season.SeasonRulesStore
import net.solardepin.solarchik.season.SeasonRulesSync
import java.text.DateFormat
import java.util.Date

/**
 * 1.1.0 Agents › Season Agent › official rules. Shows the newest official Solana Mobile source the worker read,
 * its short summary, what the Season Agent changed because of it (with the quote), and the changes that wait for
 * the user's approval. "Check for rule updates" asks the worker to read the sources now.
 */
class SeasonRulesPanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    val store by lazy { SeasonRulesStore(host) }
    var checking = false
        private set
    private var loaded = false
    // unit tests never reach the live worker unless they inject these
    private val offline = android.os.Build.FINGERPRINT == "robolectric"
    internal var get: (String) -> Pair<Int, String> = if (offline) { _ -> 0 to "" } else SeasonRules::httpGet
    internal var post: (String, String) -> Pair<Int, String> = if (offline) { _, _ -> 0 to "" } else SeasonRules::httpPost

    fun card(): LinearLayout = Ui.card(ctx, accent = Ui.GREEN, pad = 16).apply {
        tag = "sr-card"
        if (!loaded) { loaded = true; refresh() }
        val doc = store.doc()
        val head = Ui.row(ctx, gap = 8).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(Ui.weight(Ui.label(ctx, ctx.getString(R.string.sr_label), Ui.GREEN)))
        if (store.updated) head.addView(Ui.pill(ctx, ctx.getString(R.string.sr_updated), Ui.GOLD).apply { tag = "sr-updated" })
        addView(head)
        val latest = doc?.latest
        if (doc == null || latest == null) {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sr_none), 12.5f).apply { tag = "sr-none"; setLineSpacing(0f, 1.2f) }, 6))
        } else {
            addView(Ui.top(Ui.text(ctx, latest.title.substringBefore(" | ").ifBlank { latest.url }, 15f, Ui.TEXT, 800).apply { tag = "sr-source-title" }, 6))
            addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sr_source_line, latest.published.ifBlank { "—" }, host_(latest.url)), 11.5f, Ui.CYAN, 700).apply {
                tag = "sr-source"; setOnClickListener { host.openUrl(latest.url) }
            }, 2))
            if (latest.summaryFor(host.lang).isNotBlank()) addView(Ui.top(Ui.muted(ctx, latest.summaryFor(host.lang), 12.5f).apply { tag = "sr-summary"; setLineSpacing(0f, 1.25f) }, 6))
            val changes = store.changes()
            val approved = store.approved; val dismissed = store.dismissed
            val applied = changes.filter { it.applies && it.signal.key !in dismissed && (!it.needsApproval || it.signal.key in approved) }
            val pending = SeasonRules.pending(changes, approved, dismissed)
            val other = changes.filter { !it.applies }.take(3)
            if (applied.isNotEmpty()) {
                addView(Ui.top(Ui.label(ctx, ctx.getString(if (store.enabled) R.string.sr_applied else R.string.sr_would_apply)), 12))
                applied.forEachIndexed { i, c -> addView(Ui.top(changeRow(c, "sr-applied-$i"), 6)) }
            }
            if (pending.isNotEmpty()) {
                addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sr_pending), Ui.GOLD), 12))
                pending.forEachIndexed { i, c ->
                    addView(Ui.top(changeRow(c, "sr-pending-$i"), 6))
                    val r = Ui.row(ctx, gap = 8)
                    r.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sr_approve), Ui.Btn.SECONDARY) { store.approved = store.approved + c.signal.key; onChange() }.apply { tag = "sr-approve-$i" }))
                    r.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sr_ignore), Ui.Btn.GHOST) { store.dismissed = store.dismissed + c.signal.key; onChange() }.apply { tag = "sr-ignore-$i" }))
                    addView(Ui.top(r, 6))
                }
            }
            if (other.isNotEmpty()) {
                addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sr_noted)), 12))
                other.forEachIndexed { i, c -> addView(Ui.top(changeRow(c, "sr-noted-$i"), 6)) }
            }
            if (applied.isEmpty() && pending.isEmpty()) addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sr_no_change), 12f).apply { tag = "sr-no-change" }, 10))
            val t = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.checkedAt))
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sr_status, doc.version, t), 11f).apply { tag = "sr-status" }, 10))
        }
        addView(Ui.top(Ui.switchRow(ctx, ctx.getString(R.string.sr_adapt), store.enabled) { _, on -> store.enabled = on; onChange() }.apply { tag = "sr-switch" }, 10))
        val btns = Ui.row(ctx, gap = 8)
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(if (checking) R.string.sr_checking else R.string.sr_check), Ui.Btn.SECONDARY) { checkNow() }.apply { tag = "sr-check"; Ui.setEnabled(this, !checking) }))
        if (store.updated) btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sr_seen), Ui.Btn.GHOST) { store.seenVersion = doc?.version ?: 0; onChange() }.apply { tag = "sr-seen" }))
        addView(Ui.top(btns, 10))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sr_honest), 11f).apply { setLineSpacing(0f, 1.2f); tag = "sr-honest" }, 8))
    }

    private fun host_(url: String) = url.substringAfter("://").substringBefore("/")

    private fun changeRow(c: RuleChange, tag: String): View = Ui.column(ctx).apply {
        this.tag = tag
        addView(Ui.text(ctx, "• " + line(c), 12.5f, Ui.TEXT, 700).apply { setLineSpacing(0f, 1.2f) })
        addView(Ui.muted(ctx, "“" + c.signal.quote + "”", 11.5f).apply { setLineSpacing(0f, 1.2f); setOnClickListener { host.openUrl(c.signal.url) } })
    }

    fun line(c: RuleChange): String {
        val kind = c.autoKind?.let { kindName(it) }.orEmpty()
        return when (c.type) {
            RuleChange.UP -> ctx.getString(if (c.needsApproval) R.string.sr_up_swap else R.string.sr_up, kind)
            RuleChange.DOWN -> ctx.getString(R.string.sr_down, kind)
            RuleChange.FEWER -> ctx.getString(R.string.sr_fewer)
            RuleChange.EVERYDAY -> ctx.getString(R.string.sr_everyday)
            RuleChange.FEATURED -> ctx.getString(R.string.sr_featured, c.dapp)
            RuleChange.CAMPAIGN -> ctx.getString(R.string.sr_campaign, c.signal.textFor(host.lang), listOf(c.signal.start, c.signal.end).filter { it.isNotBlank() }.joinToString(" – ").ifBlank { "—" })
            else -> c.signal.textFor(host.lang)
        }
    }

    private fun kindName(k: AutoKind) = ctx.getString(when (k) {
        AutoKind.CHECKIN -> R.string.sr_k_checkin
        AutoKind.SWAP -> R.string.sr_k_swap
        AutoKind.DAPP -> R.string.sr_k_dapp
        AutoKind.STAKING -> R.string.sr_k_staking
    })

    private fun refresh() {
        host.scope.launch {
            val up = withContext(Dispatchers.IO) { runCatching { SeasonRulesSync.refresh(host, get) }.getOrDefault(false) }
            if (up) onChange()
        }
    }

    internal fun checkNow() {
        if (checking) return
        checking = true
        onChange()
        host.scope.launch {
            val r = withContext(Dispatchers.IO) { SeasonRulesSync.checkNow(host, post) }
            checking = false
            r.onSuccess { d ->
                val src = d.latest
                host.toast(if (d.throttled) ctx.getString(R.string.sr_checked_recent) else ctx.getString(R.string.sr_checked, src?.title?.substringBefore(" | ") ?: "—"))
            }.onFailure { host.toast(ctx.getString(R.string.sr_check_failed)) }
            onChange()
        }
    }
}
