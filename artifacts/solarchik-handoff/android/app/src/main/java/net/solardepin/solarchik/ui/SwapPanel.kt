package net.solardepin.solarchik.ui

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.swap.DcaAgent
import net.solardepin.solarchik.swap.PreparedSwap
import net.solardepin.solarchik.swap.SwapBlock
import net.solardepin.solarchik.swap.SwapDesk
import net.solardepin.solarchik.swap.SwapException
import net.solardepin.solarchik.swap.SwapGuard
import net.solardepin.solarchik.swap.SwapPolicy
import net.solardepin.solarchik.swap.SwapRecord
import net.solardepin.solarchik.swap.SwapRequest
import net.solardepin.solarchik.swap.SwapToken
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.ui.Ui.dp
import java.time.LocalDate
import java.util.Locale

/**
 * 1.1.0 Agents › Swaps: real Jupiter swaps on Solana mainnet. Off by default; the user opts in after a risk
 * note, sets a daily cap and max slippage, and confirms every swap in the wallet app. The DCA agent only
 * prepares a proposal; the review card shows the quote, price impact and fees before the wallet opens.
 */
class SwapPanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    val desk by lazy { SwapDesk(host) }
    private var from: SwapToken = SwapTokens.SOL
    private var to: SwapToken = SwapTokens.USDC
    private var amountText = "0.01"
    var prepared: PreparedSwap? = null
        internal set
    private var busy = false
    var lastError: String? = null
        internal set

    private val today: String get() = LocalDate.now().toString()

    fun render(box: LinearLayout) {
        box.removeAllViews()
        val w = host.wallet
        val policy = desk.store.policy()
        box.addView(Ui.card(ctx, accent = Ui.GOLD, pad = 16).apply {
            tag = "swap-head"
            addView(Ui.label(ctx, ctx.getString(R.string.swp_label), Ui.GOLD))
            addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.swp_title), 18f, Ui.TEXT, 800), 4))
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.swp_body), 12.5f).apply { setLineSpacing(0f, 1.25f) }, 6))
            addView(Ui.top(Ui.pill(ctx, ctx.getString(if (policy.live) R.string.swp_on else R.string.swp_off), if (policy.live) Ui.GREEN else Ui.MUTED, filled = policy.live).apply { tag = "swap-state" }, 10))
        })
        if (!w.mainnet) {
            box.addView(note(ctx.getString(R.string.swp_devnet_mode), Ui.CYAN, "swap-devnet"))
            return
        }
        if (!policy.live) {
            box.addView(riskCard())
            return
        }
        box.addView(limitsCard(policy))
        if (!w.connected) {
            box.addView(Ui.card(ctx, pad = 16).apply {
                addView(Ui.muted(ctx, ctx.getString(R.string.mn_connect_body)))
                addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.wallet_connect), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { connect() }.apply { tag = "swap-connect" }, 12))
            })
            return
        }
        desk.dcaProposal()?.let { box.addView(proposalCard(it)) }
        val p = prepared
        if (p != null) box.addView(reviewCard(p)) else box.addView(formCard())
        lastError?.let { box.addView(note(it, Ui.RED, "swap-error")) }
        box.addView(dcaCard())
        historyCard()?.let { box.addView(it) }
        box.addView(note(ctx.getString(R.string.swp_paper_note), Ui.MUTED, "swap-paper"))
    }

    private fun note(text: String, color: Int, tagName: String) = Ui.text(ctx, text, 12.5f, color, 600).apply {
        tag = tagName
        setLineSpacing(0f, 1.2f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Ui.rounded(Ui.withAlpha(color, 0x14), dp(14).toFloat(), Ui.withAlpha(color, 0x44), dp(1))
    }

    private fun riskCard() = Ui.card(ctx, accent = Ui.AMBER, pad = 16).apply {
        tag = "swap-risk"
        addView(Ui.label(ctx, ctx.getString(R.string.swp_risk_label), Ui.AMBER))
        addView(Ui.top(Ui.body(ctx, ctx.getString(R.string.swp_risk_body,
            Fmt.sol(SwapPolicy.DEFAULT_DAY_CAP_SOL), pct(SwapPolicy.DEFAULT_SLIPPAGE_BPS), SwapTokens.ALLOWLIST.joinToString(", ") { it.symbol })).apply { setLineSpacing(0f, 1.3f) }, 8))
        addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.swp_accept), Ui.Btn.SECONDARY) { confirmRisk() }.apply { tag = "swap-accept" }, 12))
    }

    private fun confirmRisk() {
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.swp_risk_label)
            .setMessage(R.string.swp_risk_dialog)
            .setPositiveButton(R.string.swp_accept_yes) { _, _ -> acceptRisk() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun acceptRisk() {
        desk.store.setPolicy(desk.store.policy().copy(enabled = true, riskAcceptedAt = System.currentTimeMillis()))
        onChange()
    }

    private fun pct(bps: Int): String = String.format(Locale.US, "%.1f%%", bps / 100.0)

    private fun limitsCard(policy: SwapPolicy) = Ui.card(ctx, pad = 16).apply {
        tag = "swap-limits"
        val spent = SwapGuard.spentToday(desk.store.records(), today)
        addView(Ui.label(ctx, ctx.getString(R.string.swp_limits)))
        addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.swp_spent, Fmt.sol(spent), Fmt.sol(policy.dayCapSol)), 15f, Ui.TEXT, 800).apply { tag = "swap-spent" }, 6))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.swp_cap), 12f), 10))
        val caps = listOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5)
        addView(Ui.top(chips(caps.map { Fmt.sol(it) }, caps.indexOfFirst { Math.abs(it - policy.dayCapSol) < 1e-9 }) { i ->
            desk.store.setPolicy(desk.store.policy().copy(dayCapSol = caps[i])); prepared = null; onChange()
        }.apply { tag = "swap-caps" }, 6))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.swp_slippage), 12f), 10))
        val slips = listOf(30, 50, 100, 300)
        addView(Ui.top(chips(slips.map { pct(it) }, slips.indexOf(policy.maxSlippageBps)) { i ->
            desk.store.setPolicy(desk.store.policy().copy(maxSlippageBps = slips[i])); prepared = null; onChange()
        }.apply { tag = "swap-slips" }, 6))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.swp_allow, SwapTokens.ALLOWLIST.joinToString(" · ") { it.symbol }, Fmt.sol(SwapPolicy.MAX_PRICE_IMPACT_PCT, 1)), 12f).apply { setLineSpacing(0f, 1.2f) }, 10))
        addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.swp_turn_off), Ui.Btn.GHOST) {
            desk.store.setPolicy(desk.store.policy().copy(enabled = false, riskAcceptedAt = 0L)); prepared = null; onChange()
        }.apply { tag = "swap-off" }, 10))
    }

    private fun chips(labels: List<String>, selected: Int, pick: (Int) -> Unit): LinearLayout {
        val row = Ui.row(ctx, gap = 6)
        labels.forEachIndexed { i, l ->
            row.addView(Ui.weight(Ui.button(ctx, l, if (i == selected) Ui.Btn.PRIMARY else Ui.Btn.GHOST) { pick(i) }.apply {
                textSize = 12f; setPadding(dp(4), dp(8), dp(4), dp(8)); maxLines = 1
            }))
        }
        return row
    }

    private fun formCard() = Ui.card(ctx, pad = 16).apply {
        tag = "swap-form"
        val list = SwapTokens.ALLOWLIST
        addView(Ui.label(ctx, ctx.getString(R.string.swp_from)))
        addView(Ui.top(chips(list.map { it.symbol }, list.indexOf(from)) { i -> from = list[i]; if (to == from) to = list.first { it != from }; onChange() }.apply { tag = "swap-from" }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.swp_to)), 10))
        addView(Ui.top(chips(list.map { it.symbol }, list.indexOf(to)) { i -> if (list[i] != from) { to = list[i]; onChange() } }.apply { tag = "swap-to" }, 6))
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.swp_amount, from.symbol)), 10))
        val input = EditText(ctx).apply {
            tag = "swap-amount"
            setText(amountText)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Ui.TEXT); textSize = 18f
            background = Ui.rounded(Ui.SURFACE, dp(14).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { amountText = s?.toString().orEmpty() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        addView(Ui.top(input, 6))
        val b = Ui.button(ctx, ctx.getString(if (busy) R.string.swp_quoting else R.string.swp_quote), Ui.Btn.PRIMARY) { quote() }.apply { tag = "swap-quote" }
        Ui.setEnabled(b, !busy)
        addView(Ui.top(b, 12))
    }

    /** Parses the amount field (comma or dot), 0 when unreadable. */
    internal fun amountRaw(): Long {
        val v = amountText.trim().replace(',', '.').toDoubleOrNull() ?: return 0L
        if (!v.isFinite() || v <= 0) return 0L
        return from.toRaw(v)
    }

    internal fun quote(req: SwapRequest = SwapRequest(from, to, amountRaw())) {
        if (busy) return
        busy = true
        lastError = null
        onChange()
        host.scope.launch {
            desk.prepare(host.wallet, req)
                .onSuccess { prepared = it }
                .onFailure { lastError = errorText(it) }
            busy = false
            onChange()
        }
    }

    fun errorText(t: Throwable): String = when (t) {
        is SwapException -> t.blocks.joinToString(" ") { blockText(it) } + (t.message?.takeIf { m -> t.blocks.any { it == SwapBlock.SIMULATION_FAILED || it == SwapBlock.BAD_TX } }?.let { " ($it)" } ?: "")
        is net.solardepin.solarchik.swap.JupiterException -> ctx.getString(R.string.swp_err_jupiter)
        else -> host.errorText(t)
    }

    private fun blockText(b: SwapBlock): String = ctx.getString(when (b) {
        SwapBlock.NOT_MAINNET -> R.string.swp_b_mainnet
        SwapBlock.OFF, SwapBlock.RISK_NOT_ACCEPTED -> R.string.swp_b_off
        SwapBlock.TOKEN_NOT_ALLOWED -> R.string.swp_b_token
        SwapBlock.SAME_TOKEN -> R.string.swp_b_same
        SwapBlock.ZERO_AMOUNT -> R.string.swp_b_zero
        SwapBlock.OVER_DAY_CAP -> R.string.swp_b_cap
        SwapBlock.SLIPPAGE_TOO_HIGH -> R.string.swp_b_slip
        SwapBlock.PRICE_IMPACT_TOO_HIGH -> R.string.swp_b_impact
        SwapBlock.QUOTE_MISMATCH -> R.string.swp_b_quote
        SwapBlock.SIMULATION_FAILED -> R.string.swp_b_sim
        SwapBlock.BAD_TX -> R.string.swp_b_tx
        SwapBlock.PRICE_MOVED -> R.string.swp_b_moved
    })

    private fun amt(t: SwapToken?, raw: Long): String = if (t == null) raw.toString() else Fmt.sol(t.fromRaw(raw), if (t.decimals > 6) 6 else 4) + " " + t.symbol

    private fun reviewCard(p: PreparedSwap) = Ui.card(ctx, accent = Ui.GOLD, pad = 16).apply {
        tag = "swap-review"
        val f = p.request.from
        val t = p.request.to
        addView(Ui.label(ctx, ctx.getString(R.string.swp_review), Ui.GOLD))
        if (p.request.by == "dca") addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.swp_by_dca), 12f), 4))
        addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.swp_pay_get, amt(f, p.quote.inAmount), amt(t, p.quote.outAmount)), 17f, Ui.TEXT, 800), 6))
        fun line(label: Int, value: String, tagName: String) = addView(Ui.top(Ui.row(ctx).apply {
            tag = tagName
            addView(Ui.weight(Ui.muted(ctx, ctx.getString(label), 12.5f)))
            addView(Ui.text(ctx, value, 12.5f, Ui.TEXT, 700).apply { gravity = Gravity.END })
        }, 6))
        line(R.string.swp_min_out, amt(t, p.quote.minOut) + " · " + pct(p.quote.slippageBps), "swap-min")
        line(R.string.swp_impact, String.format(Locale.US, "%.3f%%", p.quote.priceImpactPct), "swap-impact")
        line(R.string.swp_route, p.quote.routeLabels.joinToString(" → ").ifBlank { "Jupiter" }, "swap-route")
        line(R.string.swp_net_fee, Fmt.sol(p.fees.networkLamports / 1e9, 6) + " SOL", "swap-fee")
        if (p.fees.ataRentLamports > 0) line(R.string.swp_ata, Fmt.sol(p.fees.ataRentLamports / 1e9, 6) + " SOL", "swap-ata")
        val dexFees = p.quote.routeFees.entries.joinToString(", ") { (m, a) -> amt(SwapTokens.byMint(m), a) }
        if (dexFees.isNotBlank()) line(R.string.swp_dex_fee, dexFees, "swap-dexfee")
        line(R.string.swp_counts, Fmt.sol(p.solValue, 6) + " SOL", "swap-counts")
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.swp_wallet_note), 12f).apply { setLineSpacing(0f, 1.2f) }, 10))
        val go = Ui.button(ctx, ctx.getString(if (busy) R.string.swp_waiting else R.string.swp_confirm), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { execute(p) }.apply { tag = "swap-confirm" }
        Ui.setEnabled(go, !busy)
        addView(Ui.top(go, 12))
        addView(Ui.top(Ui.button(ctx, ctx.getString(android.R.string.cancel), Ui.Btn.GHOST) { prepared = null; lastError = null; onChange() }.apply { tag = "swap-cancel" }, 8))
    }

    private fun execute(p: PreparedSwap) {
        if (busy) return
        busy = true
        onChange()
        host.scope.launch {
            val r = desk.execute(host.wallet, host.sender, p)
            busy = false
            r.onSuccess { sent ->
                prepared = null
                lastError = null
                if (p.request.by == "dca") desk.dcaHandled()
                if (p.request.by == "autopilot") net.solardepin.solarchik.autopilot.AutopilotStore(host).setStatus(p.request.reason, net.solardepin.solarchik.autopilot.AutoAction.DONE)
                if (p.request.by == "saver") net.solardepin.solarchik.agents.SaverStore(host).setStatus(p.request.reason, net.solardepin.solarchik.agents.SaveAction.DONE)
                host.toast(ctx.getString(R.string.swp_sent, Fmt.short(sent.signature)))
                onChange()
                val status = desk.track(host.wallet.rpc, sent.signature)
                if (status == SwapRecord.STATUS_CONFIRMED) net.solardepin.solarchik.season.SeasonStore.markOnchain(host, "swap")
                // 1.1.0 Saver: a SOL swap the user made by hand may be offered as "save the change" (a proposal only).
                if (p.request.by.isBlank()) desk.store.records().firstOrNull { it.signature == sent.signature }?.let { net.solardepin.solarchik.agents.AgentTicks.proposeChange(host, it) }
                host.toast(ctx.getString(if (status == SwapRecord.STATUS_CONFIRMED) R.string.swp_confirmed else if (status == SwapRecord.STATUS_FAILED) R.string.swp_failed else R.string.swp_pending))
            }.onFailure { lastError = errorText(it) }
            onChange()
        }
    }

    private fun proposalCard(req: SwapRequest) = Ui.card(ctx, accent = Ui.PURPLE, pad = 16).apply {
        tag = "swap-proposal"
        addView(Ui.label(ctx, ctx.getString(R.string.swp_dca_label), Ui.PURPLE))
        addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.swp_dca_prop, amt(req.from, req.amountRaw), req.to.symbol), 15f, Ui.TEXT, 800), 4))
        val row = Ui.row(ctx, gap = 10)
        row.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.swp_dca_review), Ui.Btn.SECONDARY) { quote(req) }.apply { tag = "swap-proposal-review" }))
        row.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.swp_dca_skip), Ui.Btn.GHOST) { desk.dcaHandled(); onChange() }))
        addView(Ui.top(row, 10))
    }

    private fun dcaCard() = Ui.card(ctx, pad = 16).apply {
        tag = "swap-dca"
        val d = desk.store.dca()
        addView(Ui.label(ctx, ctx.getString(R.string.swp_dca_label), Ui.PURPLE))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.swp_dca_body), 12.5f).apply { setLineSpacing(0f, 1.2f) }, 4))
        addView(Ui.top(Ui.switchRow(ctx, ctx.getString(R.string.swp_dca_on), d.enabled) { _, on -> desk.store.setDca(desk.store.dca().copy(enabled = on)); onChange() }, 8))
        if (d.enabled) {
            val targets = listOf("USDC", "SKR", "JUP")
            addView(Ui.top(chips(targets, targets.indexOf(d.to)) { i -> desk.store.setDca(desk.store.dca().copy(to = targets[i])); onChange() }, 8))
            val amounts = listOf(0.005, 0.01, 0.02)
            addView(Ui.top(chips(amounts.map { Fmt.sol(it) + " SOL" }, amounts.indexOfFirst { Math.abs(it - d.amountSol) < 1e-9 }) { i ->
                desk.store.setDca(desk.store.dca().copy(amountSol = amounts[i])); onChange()
            }, 6))
        }
    }

    private fun historyCard(): LinearLayout? {
        val recs = desk.store.records().takeLast(5).reversed()
        if (recs.isEmpty()) return null
        return Ui.card(ctx, pad = 16).apply {
            tag = "swap-history"
            addView(Ui.label(ctx, ctx.getString(R.string.swp_history)))
            recs.forEach { r ->
                val f = SwapTokens.bySymbol(r.from)
                val t = SwapTokens.bySymbol(r.to)
                val row = Ui.row(ctx, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(Ui.weight(Ui.text(ctx, "${amt(f, r.inAmount)} → ${amt(t, r.outAmount)} · ${r.status}", 12.5f, if (r.status == SwapRecord.STATUS_FAILED) Ui.RED else Ui.TEXT, 600)))
                row.addView(Ui.text(ctx, "Solscan", 12f, Ui.CYAN, 800).apply { setOnClickListener { host.openUrl(SolarchikConfig.solscanTx(r.signature, "mainnet")) } })
                row.addView(Ui.text(ctx, "Orb", 12f, Ui.CYAN, 800).apply { setOnClickListener { host.openUrl(SolarchikConfig.orbTx(r.signature)) } })
                addView(Ui.top(row, 8))
            }
        }
    }

    private fun connect() {
        host.scope.launch {
            if (host.wallet.hasWalletApp()) host.wallet.connect(host.sender).onFailure { host.toast(host.errorText(it)) }
            else host.showInstallWallet()
            onChange()
        }
    }

    fun forTest(d: DcaAgent) = desk.store.setDca(d)
}
