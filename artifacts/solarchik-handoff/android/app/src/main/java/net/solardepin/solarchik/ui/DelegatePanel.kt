package net.solardepin.solarchik.ui

import android.view.Gravity
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.delegate.AgentKey
import net.solardepin.solarchik.delegate.DelegateBlock
import net.solardepin.solarchik.delegate.DelegateChainState
import net.solardepin.solarchik.delegate.DelegateDesk
import net.solardepin.solarchik.delegate.DelegateException
import net.solardepin.solarchik.delegate.DelegatePolicy
import net.solardepin.solarchik.delegate.DelegateRecord
import net.solardepin.solarchik.delegate.DelegateRules
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.ui.Ui.dp
import java.text.DateFormat
import java.util.Date

/**
 * 1.1.0 Agents › Swaps › EXPERIMENTAL delegated limit (OFF by default). One wallet-signed Approve lets the app's
 * agent key spend up to an allowance of one token for small swaps without a prompt. Red warning, editable caps,
 * the remaining allowance read from chain, and Revoke + Withdraw always visible.
 */
class DelegatePanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    val desk by lazy { DelegateDesk(host) }
    var chain: DelegateChainState? = null
        internal set
    private var chainAt = 0L
    private var loading = false
    private var busy = false
    var lastError: String? = null
        internal set
    private var topUpLamports = DelegateRules.DEFAULT_TOPUP_LAMPORTS

    fun render(box: LinearLayout) {
        val w = host.wallet
        if (!w.mainnet) return
        val pol = desk.store.policy().clamped()
        val agent = desk.agentAddress()
        box.addView(Ui.card(ctx, accent = Ui.RED, pad = 16).apply {
            tag = "dlg-head"
            addView(Ui.label(ctx, ctx.getString(R.string.dlg_label), Ui.RED))
            addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.dlg_title), 17f, Ui.TEXT, 800), 4))
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.dlg_body), 12.5f).apply { setLineSpacing(0f, 1.25f) }, 6))
        })
        if (!pol.live) {
            box.addView(warning())
            box.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.dlg_enable), Ui.Btn.SECONDARY) { confirmEnable() }.apply { tag = "dlg-enable" }, 4))
            if (pol.approvedAt > 0L || (agent != null && (chain?.remainingFor(agent) ?: 0L) > 0L)) box.addView(safetyRow(pol))
            return
        }
        if (!w.connected) {
            box.addView(Ui.muted(ctx, ctx.getString(R.string.mn_connect_body), 12.5f))
            return
        }
        refresh()
        box.addView(warning())
        box.addView(statusCard(pol, agent))
        box.addView(allowanceCard(pol, agent))
        box.addView(capsCard(pol))
        box.addView(actionCard(pol, agent))
        lastError?.let { box.addView(Ui.text(ctx, it, 12.5f, Ui.RED, 600).apply { tag = "dlg-error"; setLineSpacing(0f, 1.2f) }) }
        box.addView(safetyRow(pol))
        historyCard()?.let { box.addView(it) }
    }

    private fun warning() = Ui.text(ctx, ctx.getString(R.string.dlg_warning), 12.5f, Ui.RED, 700).apply {
        tag = "dlg-warning"
        setLineSpacing(0f, 1.25f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Ui.rounded(Ui.withAlpha(Ui.RED, 0x18), dp(14).toFloat(), Ui.withAlpha(Ui.RED, 0x66), dp(1))
    }

    private fun confirmEnable() {
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.dlg_label)
            .setMessage(R.string.dlg_warning)
            .setPositiveButton(R.string.dlg_enable_yes) { _, _ -> enableForTest() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun enableForTest() {
        desk.store.setPolicy(desk.store.policy().copy(enabled = true, riskAcceptedAt = System.currentTimeMillis()))
        AgentKey.create(host)
        runCatching { net.solardepin.solarchik.autopilot.AutoRunner.sync(host) }
        onChange()
    }

    private fun amt(raw: Long, pol: DelegatePolicy = desk.store.policy()): String = Fmt.sol(pol.tokenInfo.fromRaw(raw), 2) + " " + pol.tokenInfo.symbol

    private fun statusCard(pol: DelegatePolicy, agent: String?) = Ui.card(ctx, pad = 16).apply {
        tag = "dlg-status"
        addView(Ui.label(ctx, ctx.getString(R.string.dlg_status)))
        val c = chain
        val remaining = if (c != null && agent != null) c.remainingFor(agent) else null
        addView(Ui.top(Ui.text(ctx, when {
            remaining == null && loading -> ctx.getString(R.string.dlg_loading)
            remaining == null -> ctx.getString(R.string.dlg_unknown)
            remaining <= 0L -> ctx.getString(R.string.dlg_not_approved)
            else -> ctx.getString(R.string.dlg_remaining, amt(remaining, pol))
        }, 15f, Ui.TEXT, 800).apply { tag = "dlg-remaining" }, 6))
        val today = java.time.LocalDate.now().toString()
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.dlg_today, amt(DelegateRules.spentToday(desk.store.records(), today), pol), amt(pol.perDayRaw, pol)), 12.5f).apply { tag = "dlg-today" }, 4))
        if (c != null) addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.dlg_agent_sol, Fmt.sol(c.agentLamports / 1e9, 4), Fmt.short(agent.orEmpty())), 12.5f).apply { tag = "dlg-agent" }, 4))
        if (pol.approvedAt > 0L) addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.dlg_expires, DateFormat.getDateInstance(DateFormat.MEDIUM, net.solardepin.solarchik.core.AppLocale.ui()).format(Date(pol.expiresAt()))), 12.5f).apply { tag = "dlg-expiry" }, 4))
        val row = Ui.row(ctx, gap = 8)
        row.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.dlg_refresh), Ui.Btn.GHOST) { chainAt = 0L; refresh(force = true) }.apply { tag = "dlg-refresh" }))
        if (agent != null) row.addView(Ui.weight(Ui.button(ctx, "Solscan", Ui.Btn.GHOST) { host.openUrl(SolarchikConfig.solscanAccount(agent, "mainnet")) }))
        addView(Ui.top(row, 10))
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

    private fun steps(pol: DelegatePolicy): List<Double> = when (pol.token) {
        "USDC" -> listOf(1.0, 5.0, 10.0, 25.0, 50.0)
        "JUP" -> listOf(1.0, 5.0, 10.0, 50.0, 100.0)
        else -> listOf(100.0, 500.0, 1000.0, 5000.0, 10000.0)
    }

    private fun allowanceCard(pol: DelegatePolicy, agent: String?) = Ui.card(ctx, pad = 16).apply {
        tag = "dlg-allowance"
        val approved = pol.approvedAt > 0L
        addView(Ui.label(ctx, ctx.getString(R.string.dlg_token)))
        val toks = DelegateRules.TOKENS.map { it.symbol }
        addView(Ui.top(chips(toks, toks.indexOf(pol.token), "dlg-tokens") { i ->
            if (approved) host.toast(ctx.getString(R.string.dlg_token_locked)) else { desk.store.setPolicy(desk.store.policy().copy(token = toks[i])); chain = null; chainAt = 0L; onChange() }
        }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.dlg_allowance)), 10))
        val st = steps(pol)
        addView(Ui.top(chips(st.map { Fmt.sol(it, 0) }, st.indexOfFirst { pol.tokenInfo.toRaw(it) == pol.allowanceRaw }, "dlg-amounts") { i ->
            desk.store.setPolicy(desk.store.policy().copy(allowanceRaw = pol.tokenInfo.toRaw(st[i]))); onChange()
        }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.dlg_topup)), 10))
        val tops = listOf(0L, 10_000_000L, 20_000_000L)
        addView(Ui.top(chips(tops.map { if (it == 0L) "0" else Fmt.sol(it / 1e9, 2) + " SOL" }, tops.indexOf(topUpLamports), "dlg-topups") { i -> topUpLamports = tops[i]; onChange() }, 6))
        val set = Ui.button(ctx, ctx.getString(if (approved) R.string.dlg_change else R.string.dlg_approve, amt(pol.allowanceRaw, pol)), Ui.Btn.PRIMARY, R.drawable.ic_wallet) {
            approve(pol.allowanceRaw)
        }.apply { tag = "dlg-approve" }
        Ui.setEnabled(set, !busy)
        addView(Ui.top(set, 12))
        if (approved && agent != null) {
            val left = chain?.remainingFor(agent)
            val add = Ui.button(ctx, ctx.getString(R.string.dlg_add, amt(pol.allowanceRaw, pol)), Ui.Btn.SECONDARY) {
                if (left == null) host.toast(ctx.getString(R.string.dlg_unknown)) else approve(DelegateRules.approveTotal(left, pol.allowanceRaw))
            }.apply { tag = "dlg-add" }
            Ui.setEnabled(add, !busy && left != null)
            addView(Ui.top(add, 8))
        }
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.dlg_approve_note), 12f).apply { setLineSpacing(0f, 1.2f) }, 8))
    }

    private fun capsCard(pol: DelegatePolicy) = Ui.card(ctx, pad = 16).apply {
        tag = "dlg-caps"
        val t = pol.tokenInfo
        val st = steps(pol).map { it / 5 }
        addView(Ui.label(ctx, ctx.getString(R.string.dlg_per_action)))
        addView(Ui.top(chips(st.map { Fmt.sol(it, if (it < 1) 1 else 0) }, st.indexOfFirst { t.toRaw(it) == pol.perActionRaw }, "dlg-per-action") { i ->
            desk.store.setPolicy(desk.store.policy().copy(perActionRaw = t.toRaw(st[i]))); onChange()
        }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.dlg_per_day)), 10))
        val days = steps(pol).map { it * 2 / 5 }
        addView(Ui.top(chips(days.map { Fmt.sol(it, if (it < 1) 1 else 0) }, days.indexOfFirst { t.toRaw(it) == pol.perDayRaw }, "dlg-per-day") { i ->
            desk.store.setPolicy(desk.store.policy().copy(perDayRaw = t.toRaw(days[i]))); onChange()
        }, 6))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.dlg_caps_now, amt(pol.perActionRaw, pol), amt(pol.perDayRaw, pol)), 12f).apply { tag = "dlg-caps-now" }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.dlg_output)), 10))
        val outs = SwapTokens.ALLOWLIST.filter { it.mint != t.mint }.map { it.symbol }
        addView(Ui.top(chips(outs, outs.indexOf(pol.output), "dlg-output") { i -> desk.store.setPolicy(desk.store.policy().copy(output = outs[i])); onChange() }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.dlg_expiry)), 10))
        val ex = listOf(1, 3, 7, 14, 30)
        addView(Ui.top(chips(ex.map { ctx.getString(R.string.dlg_days, it) }, ex.indexOf(pol.expiryDays), "dlg-expiry-days") { i ->
            desk.store.setPolicy(desk.store.policy().copy(expiryDays = ex[i])); onChange()
        }, 6))
    }

    private fun actionCard(pol: DelegatePolicy, agent: String?) = Ui.card(ctx, accent = Ui.PURPLE, pad = 16).apply {
        tag = "dlg-actions"
        addView(Ui.label(ctx, ctx.getString(R.string.dlg_actions), Ui.PURPLE))
        addView(Ui.top(Ui.switchRow(ctx, ctx.getString(R.string.dlg_auto), pol.autoDaily) { _, on ->
            desk.store.setPolicy(desk.store.policy().copy(autoDaily = on)); runCatching { net.solardepin.solarchik.autopilot.AutoRunner.sync(host) }; onChange()
        }.apply { tag = "dlg-auto" }, 6))
        val run = Ui.button(ctx, ctx.getString(R.string.dlg_run, amt(pol.perActionRaw, pol), pol.outputInfo.symbol), Ui.Btn.SECONDARY) { runNow(pol) }.apply { tag = "dlg-run" }
        Ui.setEnabled(run, !busy && pol.approvedAt > 0L)
        addView(Ui.top(run, 10))
        addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.dlg_season_note), 12f, Ui.AMBER, 600).apply { tag = "dlg-season"; setLineSpacing(0f, 1.2f) }, 10))
    }

    /** Revoke + withdraw: always visible once the mode was ever used. */
    private fun safetyRow(pol: DelegatePolicy) = Ui.card(ctx, accent = Ui.RED, pad = 16).apply {
        tag = "dlg-safety"
        val revoke = Ui.button(ctx, ctx.getString(R.string.dlg_revoke), Ui.Btn.PRIMARY) { revoke() }.apply { tag = "dlg-revoke" }
        Ui.setEnabled(revoke, !busy)
        addView(Ui.top(revoke, 0))
        val wd = Ui.button(ctx, ctx.getString(R.string.dlg_withdraw), Ui.Btn.GHOST) { withdraw() }.apply { tag = "dlg-withdraw" }
        Ui.setEnabled(wd, !busy && desk.agentAddress() != null)
        addView(Ui.top(wd, 8))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.dlg_withdraw_note), 12f).apply { setLineSpacing(0f, 1.2f) }, 6))
        if (pol.live) addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.dlg_off), Ui.Btn.GHOST) {
            desk.store.setPolicy(desk.store.policy().copy(enabled = false, riskAcceptedAt = 0L, autoDaily = false))
            host.toast(ctx.getString(R.string.dlg_off_note)); onChange()
        }.apply { tag = "dlg-off" }, 4))
    }

    private fun historyCard(): LinearLayout? {
        val recs = desk.store.records().takeLast(6).reversed()
        if (recs.isEmpty()) return null
        return Ui.card(ctx, pad = 16).apply {
            tag = "dlg-history"
            addView(Ui.label(ctx, ctx.getString(R.string.dlg_history)))
            recs.forEach { r ->
                val row = Ui.row(ctx, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(Ui.weight(Ui.text(ctx, "${r.kind} · ${if (r.kind == DelegateRecord.WITHDRAW) Fmt.sol(r.amountRaw / 1e9, 4) + " SOL" else amt(r.amountRaw)} · ${r.status}", 12.5f, if (r.status == DelegateRecord.FAILED) Ui.RED else Ui.TEXT, 600)))
                if (r.signature.isNotBlank()) row.addView(Ui.text(ctx, "Solscan", 12f, Ui.CYAN, 800).apply { setOnClickListener { host.openUrl(SolarchikConfig.solscanTx(r.signature, "mainnet")) } })
                addView(Ui.top(row, 8))
            }
        }
    }

    fun refresh(force: Boolean = false) {
        val w = host.wallet
        if (!w.connected || !MainActivity.tickerEnabled || loading) return
        if (!force && chainAt != 0L && System.currentTimeMillis() - chainAt < 30_000L) return
        loading = true
        chainAt = System.currentTimeMillis()
        host.scope.launch {
            runCatching { desk.chainState(w.rpc, w.address) }.onSuccess { chain = it }.onFailure { lastError = ctx.getString(R.string.dlg_rpc_error) }
            loading = false
            onChange()
        }
    }

    fun errorText(t: Throwable): String = when (t) {
        is DelegateException -> t.blocks.joinToString(" ") { blockText(it) } + (t.message?.takeIf { t.blocks.any { b -> b == DelegateBlock.BAD_TX || b == DelegateBlock.SIMULATION_FAILED || b == DelegateBlock.SEND_FAILED || b == DelegateBlock.QUOTE } }?.let { " ($it)" } ?: "")
        else -> host.errorText(t)
    }

    private fun blockText(b: DelegateBlock): String = ctx.getString(when (b) {
        DelegateBlock.NOT_MAINNET -> R.string.dlg_b_mainnet
        DelegateBlock.OFF, DelegateBlock.RISK_NOT_ACCEPTED -> R.string.dlg_b_off
        DelegateBlock.NO_AGENT -> R.string.dlg_b_agent
        DelegateBlock.NOT_APPROVED -> R.string.dlg_b_approved
        DelegateBlock.EXPIRED -> R.string.dlg_b_expired
        DelegateBlock.ZERO_AMOUNT -> R.string.dlg_b_zero
        DelegateBlock.OVER_ACTION_CAP -> R.string.dlg_b_action
        DelegateBlock.OVER_DAY_CAP -> R.string.dlg_b_day
        DelegateBlock.OVER_ALLOWANCE -> R.string.dlg_b_allowance
        DelegateBlock.OVER_BALANCE -> R.string.dlg_b_balance
        DelegateBlock.LOW_AGENT_SOL -> R.string.dlg_b_sol
        DelegateBlock.TOKEN_NOT_ALLOWED -> R.string.dlg_b_token
        DelegateBlock.QUOTE -> R.string.dlg_b_quote
        DelegateBlock.BAD_TX, DelegateBlock.SIMULATION_FAILED, DelegateBlock.SEND_FAILED -> R.string.dlg_b_tx
    })

    private fun approve(total: Long) {
        if (busy) return
        busy = true; lastError = null; onChange()
        host.scope.launch {
            desk.approve(host.wallet, host.sender, total, topUpLamports)
                .onSuccess { host.toast(ctx.getString(R.string.dlg_sent, Fmt.short(it.signature))); chainAt = 0L }
                .onFailure { lastError = errorText(it) }
            busy = false
            runCatching { net.solardepin.solarchik.autopilot.AutoRunner.sync(host) }
            onChange()
            kotlinx.coroutines.delay(4000); refresh(force = true)
        }
    }

    private fun revoke() {
        if (busy) return
        busy = true; lastError = null; onChange()
        host.scope.launch {
            desk.revoke(host.wallet, host.sender)
                .onSuccess { host.toast(ctx.getString(R.string.dlg_revoked, Fmt.short(it.signature))) }
                .onFailure { lastError = errorText(it) }
            busy = false; onChange()
            kotlinx.coroutines.delay(4000); refresh(force = true)
        }
    }

    private fun withdraw() {
        if (busy) return
        busy = true; lastError = null; onChange()
        host.scope.launch {
            desk.withdraw(host.wallet.rpc, host.wallet.address)
                .onSuccess { host.toast(ctx.getString(R.string.dlg_withdrawn, Fmt.short(it))) }
                .onFailure { lastError = errorText(it) }
            busy = false; onChange(); refresh(force = true)
        }
    }

    private fun runNow(pol: DelegatePolicy) {
        if (busy) return
        busy = true; lastError = null; onChange()
        host.scope.launch {
            val today = java.time.LocalDate.now().toString()
            val left = DelegateRules.remainingToday(pol, desk.store.records(), today)
            desk.runSwap(host.wallet.rpc, host.wallet.address, minOf(pol.perActionRaw, left.takeIf { it > 0 } ?: pol.perActionRaw), host.wallet.mainnet)
                .onSuccess { host.toast(ctx.getString(R.string.dlg_done, Fmt.short(it.signature))) }
                .onFailure { lastError = errorText(it) }
            busy = false; onChange(); refresh(force = true)
        }
    }
}
