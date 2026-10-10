package net.solardepin.solarchik.ui

import android.view.Gravity
import android.widget.LinearLayout
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.SaveAction
import net.solardepin.solarchik.agents.SaverPolicy
import net.solardepin.solarchik.agents.SaverRules
import net.solardepin.solarchik.agents.SaverStore
import net.solardepin.solarchik.autopilot.AutoRunner
import net.solardepin.solarchik.swap.SwapStore
import net.solardepin.solarchik.ui.Ui.dp
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.launch

/**
 * 1.1.0 Agents › Saver (OFF by default). Proposes small saves of SOL into USDC or SKR: on a schedule and as a
 * share of a SOL swap the user made. Each save opens the normal swap review below (quote, price impact, fees,
 * the Swaps daily cap and slippage) and only the wallet app can approve it.
 */
class SaverPanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    val store by lazy { SaverStore(host) }

    fun card(): LinearLayout = Ui.card(ctx, accent = Ui.GREEN, pad = 16).apply {
        tag = "sv-card"
        val pol = store.policy()
        addView(Ui.label(ctx, ctx.getString(R.string.sv_label), Ui.GREEN))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sv_body), 12.5f).apply { setLineSpacing(0f, 1.25f) }, 4))
        if (!pol.enabled) {
            addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.sv_turn_on), Ui.Btn.SECONDARY) { confirmOn() }.apply { tag = "sv-on" }, 12))
            return@apply
        }
        val swaps = SwapStore(host).policy()
        // 1.1.5: Saver on but no wallet yet: say so and offer the connect right here (a save is a real swap).
        if (host.wallet.mainnet && !host.wallet.connected) {
            addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sv_connect_first), 12.5f, Ui.AMBER, 600).apply { tag = "sv-connect-first"; setLineSpacing(0f, 1.2f) }, 8))
            addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.sv_connect), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { connect() }.apply { tag = "sv-connect" }, 8))
        }
        if (!host.wallet.mainnet) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.swp_devnet_mode), 12f, Ui.CYAN, 600), 8))
        else if (!swaps.live) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sv_needs_swaps), 12f, Ui.AMBER, 600).apply { tag = "sv-needs-swaps"; setLineSpacing(0f, 1.2f) }, 8))

        val open = store.open()
        if (open.isNotEmpty()) {
            addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sv_waiting)), 14))
            open.takeLast(3).reversed().forEach { addView(Ui.top(row(it, canOpen = true), 6)) }
        }

        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sv_target)), 14))
        val targets = SaverPolicy.TARGETS.map { it.symbol }
        addView(Ui.top(chips(targets, targets.indexOf(pol.target), "sv-target") { set(pol.copy(target = targets[it])) }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sv_amount)), 12))
        val amounts = listOf(2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L)
        addView(Ui.top(chips(amounts.map { Fmt.sol(it / 1e9, 3) }, amounts.indexOf(pol.amountLamports), "sv-amount") { set(pol.copy(amountLamports = amounts[it])) }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sv_every)), 12))
        val every = listOf(1, 7)
        addView(Ui.top(chips(listOf(ctx.getString(R.string.sv_daily), ctx.getString(R.string.sv_weekly)), every.indexOf(pol.everyDays), "sv-every") { set(pol.copy(everyDays = every[it])) }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sv_share)), 12))
        val shares = listOf(0, 5, 10, 25)
        addView(Ui.top(chips(shares.map { if (it == 0) ctx.getString(R.string.sv_share_off) else "$it%" }, shares.indexOf(pol.sharePct), "sv-share") { set(pol.copy(sharePct = shares[it])) }, 6))
        val next = SaverRules.nextDue(pol, store.lastScheduledAt, store.enabledAt.takeIf { it > 0 } ?: System.currentTimeMillis(), ZoneId.systemDefault())
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sv_now, Fmt.sol(pol.amountLamports / 1e9, 3), pol.target,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, net.solardepin.solarchik.core.AppLocale.ui()).format(Date(next))), 12f).apply { tag = "sv-now"; setLineSpacing(0f, 1.2f) }, 8))

        val btns = Ui.row(ctx, gap = 8)
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sv_save_now, Fmt.sol(pol.amountLamports / 1e9, 3), pol.target), Ui.Btn.PRIMARY) { saveNow() }.apply { tag = "sv-save-now"; textSize = 13f }))
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sv_turn_off), Ui.Btn.GHOST) { set(pol.copy(enabled = false)); AutoRunner.sync(host) }.apply { tag = "sv-off" }))
        addView(Ui.top(btns, 12))

        val hist = store.actions().filter { it.status == SaveAction.DONE || it.status == SaveAction.SKIPPED }.takeLast(6).reversed()
        if (hist.isNotEmpty()) {
            addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.ap_history)), 14))
            hist.forEach { addView(Ui.top(row(it, canOpen = false), 6)) }
        }
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sv_note), 11.5f).apply { setLineSpacing(0f, 1.2f) }, 10))
    }

    private fun row(a: SaveAction, canOpen: Boolean) = Ui.row(ctx, gap = 8).apply {
        tag = "sv-action-" + a.kind
        gravity = Gravity.CENTER_VERTICAL
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, net.solardepin.solarchik.core.AppLocale.ui()).format(Date(a.at))
        val kind = ctx.getString(if (a.kind == SaveAction.CHANGE) R.string.sv_kind_change else R.string.sv_kind_scheduled)
        val st = ctx.getString(when (a.status) { SaveAction.DONE -> R.string.ap_s_done; SaveAction.SKIPPED -> R.string.ap_s_skipped; else -> R.string.ap_s_notified })
        addView(Ui.weight(Ui.text(ctx, "$time · $kind · ${Fmt.sol(a.lamports / 1e9, 4)} SOL → ${a.target} · $st", 12.5f, if (a.status == SaveAction.DONE) Ui.GREEN else Ui.TEXT, 600)))
        if (canOpen) {
            addView(Ui.text(ctx, ctx.getString(R.string.sv_review), 12f, Ui.CYAN, 800).apply {
                setPadding(dp(6), dp(8), dp(6), dp(8)); tag = "sv-review"
                setOnClickListener { host.openAutopilot("saver:" + a.id) }
            })
            addView(Ui.text(ctx, "✕", 13f, Ui.MUTED, 800).apply {
                setPadding(dp(6), dp(8), dp(6), dp(8)); tag = "sv-skip"; contentDescription = ctx.getString(R.string.ap_s_skipped)
                setOnClickListener { store.setStatus(a.id, SaveAction.SKIPPED); onChange() }
            })
        }
    }

    /** A save right now: a proposal like any other, reviewed below and approved in the wallet. */
    internal fun saveNow() {
        val pol = store.policy()
        val a = SaveAction("save-m-${System.currentTimeMillis()}", System.currentTimeMillis(), SaveAction.SCHEDULED, pol.amountLamports, pol.target)
        store.add(a)
        host.openAutopilot("saver:" + a.id)
        onChange()
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

    private fun set(p: SaverPolicy) { store.setPolicy(p); onChange() }

    private fun confirmOn() {
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.aa_saver)
            .setMessage(R.string.sv_dialog)
            .setPositiveButton(R.string.sv_turn_on) { _, _ -> turnOn() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connect() {
        val started = host.current
        host.scope.launch {
            if (host.wallet.hasWalletApp()) host.wallet.connect(host.sender).onFailure { host.walletFailed(started, it) }
            else host.showInstallWallet()
            onChange()
        }
    }

    internal fun turnOn() {
        store.setPolicy(store.policy().copy(enabled = true))
        host.requestNotifications(fromUser = true)
        AutoRunner.sync(host)
        onChange()
    }
}
