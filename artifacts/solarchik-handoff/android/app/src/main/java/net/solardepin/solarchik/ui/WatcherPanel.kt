package net.solardepin.solarchik.ui

import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.AgentTicks
import net.solardepin.solarchik.agents.WatcherPolicy
import net.solardepin.solarchik.agents.WatcherRules
import net.solardepin.solarchik.agents.WatcherStore
import net.solardepin.solarchik.autopilot.AutoRunner
import net.solardepin.solarchik.ui.Ui.dp
import java.text.DateFormat
import java.util.Date

/**
 * 1.1.0 Agents › Watcher (OFF by default): price and wallet alerts through a notification and Sol. Read-only;
 * there is no trade, sign or send path here.
 */
class WatcherPanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    val store by lazy { WatcherStore(host) }
    /** Last prices read on this screen (USD), shown as a live row. */
    var prices: Map<String, Double> = emptyMap()
        internal set
    private var loading = false
    internal var fetchPrices: suspend () -> Map<String, Double> = { withContext(Dispatchers.IO) { WatcherRules.fetchPrices() } }

    fun card(): LinearLayout = Ui.card(ctx, accent = Ui.GOLD, pad = 16).apply {
        tag = "wt-card"
        val pol = store.policy()
        addView(Ui.label(ctx, ctx.getString(R.string.wt_label), Ui.GOLD))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.wt_body), 12.5f).apply { setLineSpacing(0f, 1.25f) }, 4))
        if (prices.isEmpty() && !loading) load()
        addView(Ui.top(Ui.text(ctx, if (prices.isEmpty()) ctx.getString(R.string.wt_prices_loading) else
            prices.entries.joinToString("  ·  ") { "${it.key} $" + net.solardepin.solarchik.agents.WatchAlert.fmt(it.value) }, 14f, Ui.TEXT, 800).apply { tag = "wt-prices" }, 10))
        if (!pol.enabled) {
            addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.wt_turn_on), Ui.Btn.SECONDARY) { turnOn() }.apply { tag = "wt-on" }, 12))
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.wt_never_trades), 11.5f), 8))
            return@apply
        }
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.wt_move)), 14))
        val pcts = listOf(3, 5, 10)
        addView(Ui.top(chips(pcts.map { "$it%" }, pcts.indexOf(pol.priceMovePct), "wt-pct") { set(pol.copy(priceMovePct = pcts[it])) }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.wt_wallet_move)), 12))
        val sols = listOf(0.005, 0.01, 0.1)
        addView(Ui.top(chips(sols.map { Fmt.sol(it, 3) + " SOL" }, sols.indexOf(pol.walletMoveSol), "wt-sol") { set(pol.copy(walletMoveSol = sols[it])) }, 6))
        addView(Ui.top(Ui.switchRow(ctx, ctx.getString(R.string.wt_watch_prices), pol.watchPrices) { _, on -> set(pol.copy(watchPrices = on)) }.apply { tag = "wt-sw-prices" }, 10))
        addView(Ui.top(Ui.switchRow(ctx, ctx.getString(R.string.wt_watch_wallet), pol.watchWallet) { _, on -> set(pol.copy(watchWallet = on)) }.apply { tag = "wt-sw-wallet" }, 4))

        val alerts = store.recent(hours = 72)
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.wt_alerts)), 14))
        if (alerts.isEmpty()) addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.wt_no_alerts), 12.5f).apply { tag = "wt-none" }, 6))
        alerts.take(6).forEach {
            val t = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, net.solardepin.solarchik.core.AppLocale.ui()).format(Date(it.at))
            addView(Ui.top(Ui.text(ctx, "$t · " + AgentTicks.text(ctx, it), 12.5f, Ui.TEXT, 600).apply { tag = "wt-alert" }, 6))
        }
        val btns = Ui.row(ctx, gap = 8)
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.wt_check_now), Ui.Btn.SECONDARY) { checkNow() }.apply { tag = "wt-check" }))
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sv_turn_off), Ui.Btn.GHOST) { set(pol.copy(enabled = false)); AutoRunner.sync(host) }.apply { tag = "wt-off" }))
        addView(Ui.top(btns, 12))
        // 1.1.5: "Check now" answers on the card itself (the toast alone was easy to miss)
        val result = when {
            checking -> ctx.getString(R.string.wt_checking)
            lastResultAt > 0L -> ctx.getString(R.string.wt_last_check, DateFormat.getTimeInstance(DateFormat.SHORT, net.solardepin.solarchik.core.AppLocale.ui()).format(Date(lastResultAt)),
                ctx.getString(if (lastAlert) R.string.wt_alert_short else R.string.wt_quiet_short))
            else -> ""
        }
        if (result.isNotEmpty()) addView(Ui.top(Ui.text(ctx, result, 12.5f, if (lastAlert && !checking) Ui.GOLD else Ui.GREEN, 700).apply { tag = "wt-result" }, 8))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.wt_never_trades), 11.5f).apply { setLineSpacing(0f, 1.2f) }, 10))
    }

    private var checking = false
    private var lastResultAt = 0L
    private var lastAlert = false

    private fun load() {
        loading = true
        host.scope.launch {
            runCatching { fetchPrices() }.onSuccess { if (it.isNotEmpty()) prices = it }
            loading = false
            onChange()
        }
    }

    internal fun checkNow() {
        if (checking) return
        checking = true
        onChange()
        host.scope.launch {
            try {
                store.lastCheckAt = 0L
                val r = AgentTicks.watcher(host, System.currentTimeMillis(), { id, t, b, i -> AutoRunner.notify(host, id, t, b, i) }, prices = fetchPrices)
                lastAlert = r.any { it.startsWith("watcher:price") || it.startsWith("watcher:wallet") }
                lastResultAt = System.currentTimeMillis()
                host.toast(ctx.getString(if (lastAlert) R.string.wt_checked_alert else R.string.wt_checked_quiet))
            } finally {
                checking = false
                onChange()
            }
        }
    }

    private fun chips(labels: List<String>, selected: Int, tagName: String, pick: (Int) -> Unit): LinearLayout {
        val row = Ui.row(ctx, gap = 6).apply { tag = tagName }
        labels.forEachIndexed { i, l ->
            row.addView(Ui.weight(Ui.button(ctx, l, if (i == selected) Ui.Btn.PRIMARY else Ui.Btn.GHOST) { pick(i) }.apply {
                textSize = 12f; setPadding(dp(4), dp(8), dp(4), dp(8)); maxLines = 1
            }))
        }
        return row
    }

    private fun set(p: WatcherPolicy) { store.setPolicy(p); onChange() }

    internal fun turnOn() {
        store.setPolicy(store.policy().copy(enabled = true))
        host.requestNotifications(fromUser = true)
        AutoRunner.sync(host)
        onChange()
    }
}
