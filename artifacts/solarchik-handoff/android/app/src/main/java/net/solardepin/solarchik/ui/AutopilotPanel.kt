package net.solardepin.solarchik.ui

import android.view.Gravity
import android.widget.LinearLayout
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.autopilot.AutoAction
import net.solardepin.solarchik.autopilot.AutoKind
import net.solardepin.solarchik.autopilot.AutoRunner
import net.solardepin.solarchik.autopilot.AutopilotPolicy
import net.solardepin.solarchik.autopilot.AutopilotStore
import net.solardepin.solarchik.swap.SwapStore
import net.solardepin.solarchik.ui.Ui.dp
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/**
 * 1.1.0 Season › Autopilot (OFF by default): 1–2 varied real actions a day as notifications; each one opens the
 * review and the wallet app signs. Editable waking hours, actions per day, per-swap max and daily swap cap;
 * Pause / Stop; today's plan and history.
 */
class AutopilotPanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    val store by lazy { AutopilotStore(host) }

    fun card(): LinearLayout = Ui.card(ctx, accent = Ui.CYAN, pad = 20).apply {
        tag = "ap-card"
        val pol = store.policy()
        addView(Ui.label(ctx, ctx.getString(R.string.ap_label), Ui.CYAN))
        addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.ap_title), 18f, Ui.TEXT, 800), 6))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.ap_body), 14f).apply { setLineSpacing(0f, 1.35f) }, 10))
        if (!pol.enabled) {
            addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.ap_turn_on), Ui.Btn.SECONDARY) { confirmOn() }.apply { tag = "ap-on"; minHeight = dp(52); setPadding(dp(16), dp(12), dp(16), dp(12)) }, 16))
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.ap_points_note), 13f).apply { setLineSpacing(0f, 1.3f) }, 14))
            return@apply
        }
        addView(Ui.top(Ui.pill(ctx, ctx.getString(if (pol.paused) R.string.ap_paused else R.string.ap_running), if (pol.paused) Ui.AMBER else Ui.GREEN, filled = !pol.paused).apply { tag = "ap-state" }, 10))
        if (!SwapStore(host).policy().live) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.ap_no_swaps), 13.5f, Ui.AMBER, 600).apply { tag = "ap-no-swaps"; setLineSpacing(0f, 1.3f) }, 10))

        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.ap_today)), 14))
        val today = LocalDate.now().toString()
        val todays = store.actions().filter { it.day == today }
        if (todays.isEmpty()) addView(Ui.top(Ui.muted(ctx, ctx.getString(if (pol.paused) R.string.ap_none_paused else R.string.ap_none), 13.5f).apply { setLineSpacing(0f, 1.3f) }, 8))
        todays.forEach { addView(Ui.top(row(it, open = !pol.paused && it.status != AutoAction.DONE), 8)) }

        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.ap_hours)), 14))
        val froms = listOf(7, 8, 9, 10)
        addView(Ui.top(chips(froms.map { "$it:00" }, froms.indexOf(pol.wakeFrom), "ap-from") { i -> set(pol.copy(wakeFrom = froms[i])) }, 6))
        val tos = listOf(20, 21, 22, 23)
        addView(Ui.top(chips(tos.map { "–$it:00" }, tos.indexOf(pol.wakeTo), "ap-to") { i -> set(pol.copy(wakeTo = tos[i])) }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.ap_per_day)), 12))
        val n = listOf(1, 2, 3)
        addView(Ui.top(chips(n.map { it.toString() }, n.indexOf(pol.perDay), "ap-per-day") { i -> set(pol.copy(perDay = n[i])) }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.ap_swap_max)), 12))
        val maxes = listOf(2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L)
        addView(Ui.top(chips(maxes.map { Fmt.sol(it / 1e9, 3) }, maxes.indexOf(pol.swapMaxLamports), "ap-swap-max") { i -> set(pol.copy(swapMaxLamports = maxes[i], swapDayCapLamports = maxOf(pol.swapDayCapLamports, maxes[i]))) }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.ap_swap_day)), 12))
        val days = listOf(5_000_000L, 10_000_000L, 20_000_000L, 50_000_000L)
        addView(Ui.top(chips(days.map { Fmt.sol(it / 1e9, 3) }, days.indexOf(pol.swapDayCapLamports), "ap-swap-day") { i -> set(pol.copy(swapDayCapLamports = days[i])) }, 6))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.ap_caps_now, pol.perDay, Fmt.sol(pol.swapMaxLamports / 1e9, 3), Fmt.sol(pol.swapDayCapLamports / 1e9, 3)), 13f).apply { tag = "ap-caps-now"; setLineSpacing(0f, 1.3f) }, 10))

        val btns = Ui.row(ctx, gap = 10)
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(if (pol.paused) R.string.ap_resume else R.string.ap_pause), Ui.Btn.SECONDARY) {
            store.setPolicy(store.policy().copy(paused = !pol.paused)); AutoRunner.sync(host); if (pol.paused) AutoRunner.ensurePlan(host); onChange()
        }.apply { tag = "ap-pause"; minHeight = dp(52) }))
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.ap_stop), Ui.Btn.GHOST) { stop() }.apply { tag = "ap-stop"; minHeight = dp(52) }))
        addView(Ui.top(btns, 18))

        val hist = store.actions().filter { it.day != today }.takeLast(8).reversed()
        if (hist.isNotEmpty()) {
            addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.ap_history)), 14))
            hist.forEach { addView(Ui.top(row(it, open = false), 6)) }
        }
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.ap_points_note), 13f).apply { setLineSpacing(0f, 1.3f) }, 16))
    }

    private fun row(a: AutoAction, open: Boolean) = Ui.row(ctx, gap = 8).apply {
        tag = "ap-action-${a.kind.name.lowercase()}"
        gravity = Gravity.CENTER_VERTICAL
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, net.solardepin.solarchik.core.AppLocale.ui()).format(Date(a.at))
        val what = when (a.kind) {
            AutoKind.SWAP -> ctx.getString(R.string.ap_kind_swap, Fmt.sol(a.lamports / 1e9, 4), a.to)
            AutoKind.CHECKIN -> ctx.getString(R.string.ap_kind_checkin)
            AutoKind.DAPP -> ctx.getString(R.string.ap_kind_dapp, a.dapp)
            AutoKind.STAKING -> ctx.getString(R.string.ap_kind_stake)
        }
        val prefix = if (a.day == LocalDate.now().toString()) time else a.day.substring(5) + " " + time
        addView(Ui.weight(Ui.text(ctx, "$prefix · $what · ${statusText(a.status)}", 13.5f, if (a.status == AutoAction.DONE) Ui.GREEN else Ui.TEXT, 600).apply { setLineSpacing(0f, 1.15f) }))
        if (open) addView(Ui.text(ctx, ctx.getString(R.string.ap_open), 12f, Ui.CYAN, 800).apply {
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { host.openAutopilot(a.id) }
        })
    }

    private fun statusText(s: String): String = ctx.getString(when (s) {
        AutoAction.DONE -> R.string.ap_s_done
        AutoAction.NOTIFIED -> R.string.ap_s_notified
        AutoAction.SKIPPED -> R.string.ap_s_skipped
        AutoAction.MISSED -> R.string.ap_s_missed
        else -> R.string.ap_s_planned
    })

    private fun chips(labels: List<String>, selected: Int, tagName: String, pick: (Int) -> Unit): LinearLayout {
        val row = Ui.row(ctx, gap = 8).apply { tag = tagName }
        labels.forEachIndexed { i, l ->
            row.addView(Ui.weight(Ui.button(ctx, l, if (i == selected) Ui.Btn.PRIMARY else Ui.Btn.GHOST) { pick(i) }.apply {
                textSize = 13f; setPadding(dp(6), dp(10), dp(6), dp(10)); minHeight = dp(44); maxLines = 1
            }))
        }
        return row
    }

    private fun set(p: AutopilotPolicy) { store.setPolicy(p); onChange() }

    private fun confirmOn() {
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.ap_label)
            .setMessage(R.string.ap_dialog)
            .setPositiveButton(R.string.ap_turn_on) { _, _ -> turnOn() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun turnOn() {
        store.setPolicy(store.policy().copy(enabled = true, paused = false))
        host.requestNotifications(fromUser = true)
        AutoRunner.sync(host)
        AutoRunner.ensurePlan(host)
        onChange()
    }

    /** Stop: off, and today's open actions are skipped (history stays). */
    internal fun stop() {
        store.setPolicy(store.policy().copy(enabled = false, paused = false))
        store.save(store.actions().map { if (it.status == AutoAction.PLANNED || it.status == AutoAction.NOTIFIED) it.copy(status = AutoAction.SKIPPED) else it })
        AutoRunner.sync(host)
        onChange()
    }
}
