package net.solardepin.solarchik.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.MintError
import net.solardepin.solarchik.agents.OwnedAgent
import net.solardepin.solarchik.agents.engine.AgentRun
import net.solardepin.solarchik.agents.engine.DeskError
import net.solardepin.solarchik.agents.engine.DeskState
import net.solardepin.solarchik.agents.engine.RiskCaps
import net.solardepin.solarchik.agents.engine.Track
import net.solardepin.solarchik.agents.engine.UserCaps
import net.solardepin.solarchik.core.AgentSku
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.AssistantAgent
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.core.FeeLedger
import net.solardepin.solarchik.core.FeeReason
import net.solardepin.solarchik.core.FeeRow
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.ui.Ui.dp

class AgentsScreen(host: MainActivity) : Screen(host) {
    private var tier = AgentTier.FREE
    private var busySku: String? = null
    private var balance: Double? = null
    private var balanceKey = ""
    private var refreshed = false

    /** Balance belongs to one wallet on one cluster; drop it when either changes. */
    private fun walletKey() = host.wallet.let { if (it.connected) it.address + "@" + it.clusterName else "" }

    private fun syncWalletKey() {
        val k = walletKey()
        if (k != balanceKey) {
            balanceKey = k
            balance = null
            refreshed = false
        }
    }

    private fun setBalance(key: String, v: Double) {
        if (key == walletKey()) balance = v
    }
    /**
     * 1.1.0: the assistant shows three agents: [SEASON] (Season plan, autopilot, delegated limit), [SAVER] (small
     * saves into USDC/SKR through real, confirmed Jupiter swaps) and [WATCHER] (price and wallet alerts, never
     * trades). The game's desk, strategy catalog, NFT market and Slice stay in the code (shared engine) but are
     * not shown here.
     */
    var section = SEASON
        private set

    /** Home cards and notifications open an agent directly. */
    fun openSection(i: Int) {
        section = i.coerceIn(SEASON, WATCHER)
        render()
    }

    private var track = Track.PAPER
    private var paying = false

    private lateinit var sectionBox: LinearLayout
    private lateinit var deskBox: LinearLayout
    private lateinit var shopBox: LinearLayout
    private lateinit var marketBox: LinearLayout
    private lateinit var sliceBox: LinearLayout
    private val strategyPanel by lazy { StrategyPanel(host) { render() } }
    private val slicePanel by lazy { SlicePanel(host) { render() } }
    internal val swapPanel by lazy { SwapPanel(host) { render() } }
    internal val delegatePanel by lazy { DelegatePanel(host) { render() } }
    internal val autopilotPanel by lazy { AutopilotPanel(host) { render() } }
    internal val rulesPanel by lazy { SeasonRulesPanel(host) { render() } }
    internal val saverPanel by lazy { SaverPanel(host) { render() } }
    internal val watcherPanel by lazy { WatcherPanel(host) { render() } }
    private lateinit var swapBox: LinearLayout
    private lateinit var agentBox: LinearLayout

    private lateinit var walletPill: TextView
    private lateinit var clusterLabel: TextView
    private lateinit var segFree: TextView
    private lateinit var segPro: TextView
    private lateinit var tierLine: TextView
    private lateinit var catalogBox: LinearLayout
    private lateinit var mineBox: LinearLayout
    private lateinit var ledgerBox: LinearLayout

    override fun build(): View = page {
        val head = Ui.row(ctx)
        clusterLabel = Ui.label(ctx, "", Ui.CYAN)
        head.addView(Ui.weight(clusterLabel))
        walletPill = Ui.tappable(Ui.pill(ctx, "", Ui.GOLD, icon = R.drawable.ic_wallet)).apply { setOnClickListener { onWalletPill() } }
        head.addView(walletPill)
        addView(head)
        addView(Ui.display(ctx, ctx.getString(R.string.agents_title), 24f))
        addView(Ui.muted(ctx, ctx.getString(R.string.agents_sub), 14f))
        sectionBox = Ui.column(ctx)
        addView(sectionBox)
        deskBox = Ui.column(ctx, gap = 14)
        addView(deskBox)
        shopBox = Ui.column(ctx, gap = 14)
        addView(shopBox)
        marketBox = Ui.column(ctx, gap = 14)
        addView(marketBox)
        sliceBox = Ui.column(ctx, gap = 12)
        addView(sliceBox)
        swapBox = Ui.column(ctx, gap = 12)
        addView(swapBox)
        agentBox = Ui.column(ctx, gap = 12).apply { tag = "agents-box" }
        addView(agentBox)
        shopBox.apply {

        // Tier switch
        val seg = Ui.row(ctx).apply {
            background = Ui.rounded(Ui.SURFACE, dp(18).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segFree = segment(ctx.getString(R.string.tier_free)) { tier = AgentTier.FREE; tierPicked = true; render() }
        segPro = segment(ctx.getString(R.string.tier_pro)) { tier = AgentTier.PRO; tierPicked = true; render() }
        seg.addView(segFree, LinearLayout.LayoutParams(0, dp(44), 1f))
        seg.addView(segPro, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(seg)
        tierLine = Ui.text(ctx, "", 13f, Ui.TEXT, 700)
        addView(Ui.column(ctx).apply {
            addView(tierLine)
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.tier_royalty), 12f), 4))
        })

        catalogBox = Ui.column(ctx, gap = 14)
        addView(catalogBox)

        addView(Ui.top(Ui.h2(ctx, ctx.getString(R.string.agents_mine)), 10))
        mineBox = Ui.column(ctx, gap = 10)
        addView(mineBox)

        }
        ledgerBox = Ui.column(ctx, gap = 10)
    }

    private fun segment(label: String, onClick: () -> Unit): TextView = Ui.text(ctx, label, 15f, Ui.MUTED, 800).apply {
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    private var tierPicked = false

    override fun onShow() {
        if (!tierPicked) {
            val w = host.wallet
            if (w.connected && host.store.freeClaimed(w.address, w.clusterName)) tier = AgentTier.PRO
        }
        syncWalletKey()
        render()
        if (!refreshed && host.wallet.connected) {
            refreshed = true
            refreshChain()
        }
        if (host.desk.state().pendingPay != null) {
            host.scope.launch {
                runCatching { host.desk.reconcile() }
                render()
            }
        }
    }

    private fun refreshChain() {
        val key = walletKey()
        host.scope.launch {
            host.wallet.balanceSol().onSuccess { setBalance(key, it) }
            runCatching { host.minter.refresh() }
            render()
        }
    }

    override fun render() {
        if (!this::ledgerBox.isInitialized) return
        val w = host.wallet
        syncWalletKey()
        clusterLabel.text = ctx.getString(R.string.agents_cluster, w.clusterName.uppercase())
        walletPill.text = if (w.connected) {
            Fmt.short(w.address) + (balance?.let { " · " + Fmt.sol(it, 3) } ?: "")
        } else ctx.getString(R.string.wallet_connect)

        val pro = tier == AgentTier.PRO
        styleSeg(segFree, !pro)
        styleSeg(segPro, pro)
        tierLine.text = if (pro) ctx.getString(R.string.tier_pro_line, Fmt.sol(SolarchikConfig.PRO_PRICE_SOL)) else ctx.getString(R.string.tier_free_line)
        tierLine.setTextColor(if (pro) Ui.GOLD else Ui.TEXT)

        sectionBox.removeAllViews()
        sectionBox.addView(Ui.segmented(ctx, AssistantAgent.entries.map { ctx.getString(it.titleRes) }, section) {
            section = it
            render()
        }.apply { tag = "agents-tabs" })
        // The game's desk / catalog / market / Slice are not part of the assistant UI (1.1.0).
        for (b in listOf(deskBox, shopBox, marketBox, sliceBox)) b.visibility = View.GONE
        swapBox.visibility = View.GONE
        agentBox.removeAllViews()
        val agent = AssistantAgent.entries[section]
        agentBox.addView(agentCard(agent))
        when (agent) {
            AssistantAgent.SEASON -> {
                agentBox.addView(seasonPlanCard())
                agentBox.addView(rulesPanel.card())
                agentBox.addView(autopilotPanel.card())
                delegatePanel.render(agentBox)
            }
            AssistantAgent.SAVER -> {
                agentBox.addView(saverPanel.card())
                // SwapPanel clears its box: give it its own.
                agentBox.addView(Ui.column(ctx, gap = 12).also { swapPanel.render(it) }.apply { tag = "agent-swaps" })
            }
            AssistantAgent.WATCHER -> agentBox.addView(watcherPanel.card())
        }
    }

    /** One of the three agents: what it does, whether it is on, and its NFT (Free / Pro) mint. */
    private fun agentCard(agent: AssistantAgent): View = Ui.card(ctx, accent = agent.accent).apply {
        tag = "agent-" + agent.key
        val row = Ui.row(ctx, gap = 14).apply { gravity = Gravity.TOP }
        val artBox = FrameLayout(ctx).apply { background = Ui.rounded(Ui.withAlpha(agent.accent, 0x1E), dp(18).toFloat()) }
        artBox.addView(Ui.image(ctx, agent.artRes), FrameLayout.LayoutParams(dp(58), dp(80), Gravity.CENTER))
        row.addView(artBox, LinearLayout.LayoutParams(dp(74), dp(94)))
        val col = Ui.column(ctx)
        val on = when (agent) {
            AssistantAgent.SEASON -> autopilotPanel.store.policy().enabled || delegatePanel.desk.store.policy().enabled
            AssistantAgent.SAVER -> saverPanel.store.policy().enabled
            AssistantAgent.WATCHER -> watcherPanel.store.policy().enabled
        }
        col.addView(Ui.pill(ctx, ctx.getString(if (on) R.string.aa_on else R.string.aa_off), if (on) Ui.GREEN else Ui.MUTED, filled = on).apply { tag = "agent-state" })
        col.addView(Ui.top(Ui.text(ctx, ctx.getString(agent.titleRes), 18f, Ui.TEXT, 800), 8))
        col.addView(Ui.top(Ui.muted(ctx, ctx.getString(agent.roleRes), 13f).apply { setLineSpacing(0f, 1.2f) }, 4))
        row.addView(Ui.weight(col))
        addView(row)
        // Agent NFT: Free or Pro (0.1 SOL to the treasury); "coming soon" until the mainnet collection exists.
        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.aa_nft)), 14))
        val sku = agent.sku
        val btns = Ui.row(ctx, gap = 8)
        val soon = host.minter.canMint(sku, AgentTier.FREE) == MintError.Kind.MAINNET_SOON
        if (soon) btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.mn_mint_soon), Ui.Btn.SECONDARY) {}.apply { tag = "agent-mint-soon"; Ui.setEnabled(this, false) }))
        else for (t in listOf(AgentTier.FREE, AgentTier.PRO)) {
            val block = host.minter.canMint(sku, t)
            val busy = busySku == sku.skuId(t)
            val label = when {
                busy -> ctx.getString(R.string.mint_busy)
                t == AgentTier.PRO -> ctx.getString(R.string.aa_nft_pro, Fmt.sol(SolarchikConfig.PRO_PRICE_SOL))
                else -> ctx.getString(R.string.aa_nft_free)
            }
            val b = Ui.button(ctx, label, if (t == AgentTier.PRO) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY) { tier = t; tierPicked = true; mint(sku) }.apply { tag = "agent-mint-$t"; textSize = 13f }
            Ui.setEnabled(b, block == null && busySku == null)
            btns.addView(Ui.weight(b))
        }
        addView(Ui.top(btns, 8))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(if (soon) R.string.mn_mint_soon_body else R.string.aa_nft_note), 11.5f).apply { tag = if (soon) "mint-soon" else "agent-nft-note"; setLineSpacing(0f, 1.2f) }, 6))
    }

    /** Season Agent: today's Seeker Season plan in one line, with a way into the full plan. */
    private fun seasonPlanCard(): View = Ui.card(ctx, accent = Ui.CYAN, pad = 16).apply {
        tag = "agent-season-plan"
        val plan = net.solardepin.solarchik.season.SeasonStore.plan(host, host.save.signedToday(), host.save.clockedToday())
        addView(Ui.label(ctx, ctx.getString(R.string.aa_plan_label), Ui.CYAN))
        addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.aa_plan_line, plan.doneCount, plan.total, plan.streak), 15f, Ui.TEXT, 800), 4))
        addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.aa_plan_open), Ui.Btn.SECONDARY) { host.select(MainActivity.Tab.SEASON, animate = true) }.apply { tag = "agent-season-open" }, 10))
    }

    // ---------------- Desk ----------------

    private fun renderDesk() {
        val st = host.desk.state()
        deskBox.removeAllViews()
        deskBox.addView(deskHero(st))
        deskBox.addView(Ui.segmented(ctx, listOf(ctx.getString(R.string.track_paper), ctx.getString(R.string.track_devnet)), if (track == Track.PAPER) 0 else 1) {
            track = if (it == 0) Track.PAPER else Track.DEVNET
            render()
        })
        deskBox.addView(Ui.muted(ctx, ctx.getString(if (track == Track.PAPER) R.string.track_paper_hint else R.string.track_devnet_hint), 12f))
        runnable(st).forEach { deskBox.addView(runCard(it, st)) }
        if (track == Track.DEVNET && runnable(st).isEmpty()) {
            deskBox.addView(emptyCard(ctx.getString(if (host.wallet.connected) R.string.desk_devnet_empty else R.string.desk_devnet_connect)))
        }
        deskBox.addView(riskPanel(st.caps))
        deskBox.addView(feesCard())
        deskBox.addView(Ui.top(Ui.h2(ctx, ctx.getString(R.string.desk_log)), 6))
        if (st.log.isEmpty()) deskBox.addView(emptyCard(ctx.getString(R.string.desk_log_empty)))
        else deskBox.addView(Ui.card(ctx, pad = 14).apply {
            st.log.take(12).forEachIndexed { i, e ->
                if (i > 0) addView(Ui.top(Ui.divider(ctx), 8))
                val line = Ui.row(ctx, gap = 10).apply { gravity = Gravity.TOP }
                line.addView(Ui.text(ctx, Fmt.clock(e.at), 11f, Ui.MUTED, 700))
                val color = when (e.kind) {
                    "open" -> Ui.GOLD
                    "close" -> if (e.pnl > 0) Ui.GREEN else if (e.pnl < 0) Ui.RED else Ui.TEXT
                    "block" -> Ui.AMBER
                    else -> Ui.TEXT
                }
                line.addView(Ui.weight(Ui.text(ctx, DeskText.event(ctx, e), 12f, color, 600)))
                addView(Ui.top(line, if (i == 0) 0 else 8))
            }
        })
        deskBox.addView(Ui.top(Ui.h2(ctx, ctx.getString(R.string.ledger_title)), 6))
        deskBox.addView(ledgerBox)
        renderLedger()
        deskBox.addView(Ui.muted(ctx, ctx.getString(R.string.desk_bg_note), 11f))
    }

    private fun deskHero(st: DeskState): View = Ui.card(ctx, accent = Ui.GOLD).apply {
        val running = st.runs.count { it.running }
        val head = Ui.row(ctx, gap = 10)
        head.addView(Ui.weight(Ui.text(ctx, ctx.getString(R.string.desk_title), 18f, Ui.TEXT, 900)))
        head.addView(Ui.pill(ctx, if (running > 0) ctx.getString(R.string.desk_running, running) else ctx.getString(R.string.desk_idle), if (running > 0) Ui.GREEN else Ui.MUTED, filled = running > 0))
        addView(head)
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.desk_sub), 12f), 6))
        val day = st.day(track).rolled(net.solardepin.solarchik.core.StreakRules.dayKey(System.currentTimeMillis()))
        val purse = if (track == Track.PAPER) ctx.getString(R.string.desk_purse_paper, Fmt.sol(host.desk.freeFor(st, Track.PAPER) + st.runs.filter { it.track == Track.PAPER }.sumOf { it.open?.stake ?: 0.0 }, 4))
        else ctx.getString(R.string.desk_purse_devnet, Fmt.sol(balance ?: st.devnetBalance, 4))
        addView(Ui.top(Ui.text(ctx, purse, 20f, Ui.GOLD, 900), 12))
        val pnl = st.runs.filter { it.track == track }.sumOf { it.pnl }
        addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.desk_pnl, Fmt.signedSol(pnl, 6)), 13f, if (pnl > 0) Ui.GREEN else if (pnl < 0) Ui.RED else Ui.TEXT, 700), 4))
        val caps = st.caps
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.desk_today, Fmt.sol(day.spent), Fmt.sol(caps.dayCapSol), day.lossStreak, caps.maxLosses), 12f), 4))
        val bar = SolarProgress(ctx)
        bar.fraction = (day.spent / caps.dayCapSol).toFloat()
        addView(Ui.top(bar, 10))
    }

    /**
     * Paper: every catalog strategy is listed, but 0.21.8 only STARTS owned ones (any cluster); the rest
     * show a "Get this agent" card (mint free / buy Pro). Devnet: owned devnet NFTs only.
     */
    private fun runnable(st: DeskState): List<AgentRun> {
        val w = host.wallet
        if (track == Track.PAPER) {
            return Catalog.skus.map { sku ->
                val key = "paper:${sku.id}"
                st.run(key) ?: AgentRun(key, sku.id, sku.tierFor(AgentTier.FREE), sku.name, Track.PAPER)
            }
        }
        if (!w.connected) return emptyList()
        return host.store.agentsFor(w.address, "devnet").filter { it.status != OwnedAgent.STATUS_MISSING }.map { a ->
            st.run(a.asset)?.takeIf { it.track == Track.DEVNET } ?: AgentRun(a.asset, a.skuId, a.tier, a.name, Track.DEVNET)
        }
    }

    private fun runCard(r: AgentRun, st: DeskState): View {
        val sku = Catalog.baseOf(r.skuId)
        val accent = sku?.accent ?: Ui.GOLD
        return Ui.card(ctx, accent = if (r.running) accent else null, pad = 14).apply {
            val row = Ui.row(ctx, gap = 12).apply { gravity = Gravity.TOP }
            val art = FrameLayout(ctx).apply { background = Ui.rounded(Ui.withAlpha(accent, 0x1E), dp(14).toFloat()) }
            art.addView(Ui.image(ctx, sku?.artRes ?: R.drawable.robot_sunflower), FrameLayout.LayoutParams(dp(40), dp(54), Gravity.CENTER))
            row.addView(art, LinearLayout.LayoutParams(dp(54), dp(66)))
            val col = Ui.column(ctx)
            val pills = Ui.row(ctx, gap = 6)
            val (stLabel, stColor) = when {
                r.open != null -> ctx.getString(R.string.desk_status_holding) to Ui.GOLD
                r.running -> ctx.getString(R.string.desk_status_running) to Ui.GREEN
                else -> ctx.getString(R.string.desk_status_stopped) to Ui.MUTED
            }
            pills.addView(Ui.pill(ctx, stLabel, stColor, filled = r.running || r.open != null))
            val ownedPaper = r.track != Track.PAPER || host.paperOpen || net.solardepin.solarchik.agents.Ownership.ownsSku(host.store.agents(), r.skuId)
            if (r.track == Track.PAPER && r.key.startsWith("paper:")) pills.addView(Ui.pill(ctx, ctx.getString(if (ownedPaper) R.string.desk_owned else R.string.desk_not_owned_pill), if (ownedPaper) Ui.GREEN else Ui.MUTED))
            val tierPill = Ui.pill(ctx, ctx.getString(if (r.tier == AgentTier.PRO) R.string.tier_pro else R.string.tier_free), if (r.tier == AgentTier.PRO) Ui.GOLD else Ui.CYAN)
            // three pills do not fit the narrow column on a phone ("Безкошт|овний" broke mid-word): tier goes on its own line
            col.addView(pills)
            if (pills.childCount >= 2) col.addView(Ui.top(tierPill.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) }, 6)) else pills.addView(tierPill)
            col.addView(Ui.top(Ui.text(ctx, AgentNames.display(ctx, r.name), 15f, Ui.TEXT, 800), 6))
            sku?.let { col.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.mint_lanes, lanes(it.lanes)), 11f, accent, 700), 2)) }
            r.open?.let { p ->
                val left = (p.closeAt - System.currentTimeMillis()).coerceAtLeast(0)
                col.addView(Ui.top(Ui.text(ctx, DeskText.side(ctx, p.side) + " · " + p.label, 12f, Ui.TEXT, 700), 6))
                col.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.desk_open_line, DeskText.conf(p.confidence), Fmt.sol(p.stake), Fmt.countdown(left)), 11f), 2))
            }
            if (r.open == null) r.last?.let { col.addView(Ui.top(Ui.muted(ctx, DeskText.event(ctx, it), 11f), 6)) }
            if (r.jobs > 0 || r.wins + r.losses > 0) {
                col.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.desk_stats, r.jobs, r.wins, r.losses, Fmt.signedSol(r.pnl, 6)), 11f, if (r.pnl > 0) Ui.GREEN else if (r.pnl < 0) Ui.RED else Ui.MUTED, 700), 4))
            }
            row.addView(Ui.weight(col))
            addView(row)
            val btn = if (r.running) Ui.button(ctx, ctx.getString(R.string.desk_stop), Ui.Btn.GHOST) { stopRun(r) }
            else if (!ownedPaper) Ui.button(ctx, ctx.getString(R.string.desk_get_agent), Ui.Btn.SECONDARY, R.drawable.ic_bolt_small) { offerAgent(r) }.apply { tag = "desk-get-agent" }
            else Ui.button(ctx, ctx.getString(R.string.desk_start), Ui.Btn.SECONDARY, R.drawable.ic_bolt_small) { startRun(r) }
            addView(Ui.top(btn, 12))
        }
    }

    private fun startRun(r: AgentRun) {
        host.scope.launch {
            host.desk.start(r.key, r.skuId, r.tier, r.name, r.track)
                .onSuccess {
                    host.toast(ctx.getString(R.string.desk_started, AgentNames.display(ctx, r.name), ctx.getString(if (r.track == Track.PAPER) R.string.track_paper else R.string.track_devnet)))
                    host.deskChanged()
                }
                .onFailure { host.toast(if (it is DeskError) ctx.getString(R.string.desk_not_owned) else host.errorText(it)) }
            render()
        }
    }

    /** 0.21.8: the shared confirmation card as a dialog: mint free / buy Pro, then the paper run starts. */
    internal var offerDialog: android.app.AlertDialog? = null
        private set

    private fun offerAgent(r: AgentRun) {
        val sku = Catalog.baseOf(r.skuId) ?: return
        var dlg: android.app.AlertDialog? = null
        val card = OfferCard.view(
            ctx,
            ctx.getString(R.string.offer_title, AgentNames.display(ctx, sku.name)),
            ctx.getString(R.string.offer_body),
            ctx.getString(R.string.sol_act_wallet),
            OfferCard.acquireOptions(host, sku, busySku != null) { tier -> dlg?.dismiss(); acquireAndStart(sku, tier) },
        ) { dlg?.dismiss() }
        dlg = android.app.AlertDialog.Builder(host)
            .setView(android.widget.ScrollView(ctx).apply { setPadding(dp(8), dp(8), dp(8), dp(8)); addView(card) })
            .create()
        offerDialog = dlg
        dlg.show()
    }

    private fun acquireAndStart(sku: net.solardepin.solarchik.core.AgentSku, tier: String) {
        if (busySku != null) return
        busySku = sku.skuId(tier)
        render()
        host.scope.launch {
            val res = host.minter.mint(host.sender, sku, tier)
            busySku = null
            res.onSuccess { rec ->
                host.toast(ctx.getString(R.string.mint_ok, rec.name))
                host.desk.start("paper:${sku.id}", sku.id, tier, sku.nameFor(tier), Track.PAPER)
                    .onSuccess { host.toast(ctx.getString(R.string.desk_started, AgentNames.display(ctx, sku.nameFor(tier)), ctx.getString(R.string.track_paper))); host.deskChanged() }
            }.onFailure { host.toast(host.errorText(it)) }
            host.renderAll()
        }
    }

    private fun stopRun(r: AgentRun) {
        host.scope.launch {
            host.desk.stop(r.key)
            host.toast(ctx.getString(R.string.desk_stopped, AgentNames.display(ctx, r.name)))
            host.deskChanged()
            render()
        }
    }

    private fun riskPanel(caps: UserCaps): View = Ui.card(ctx, accent = Ui.CYAN).apply {
        val head = Ui.row(ctx, gap = 12)
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_timer, Ui.CYAN, 36))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.risk_title))))
        addView(head)
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.risk_sub), 12f), 6))
        addView(Ui.top(stepper(R.string.risk_trade, caps.maxTradeSol, TRADE_STEPS, SolarchikConfig.HARD_MAX_TRADE_SOL) { setCaps(caps.copy(maxTradeSol = it)) }, 12))
        addView(Ui.top(stepper(R.string.risk_day, caps.dayCapSol, DAY_STEPS, SolarchikConfig.HARD_DAY_CAP_SOL) { setCaps(caps.copy(dayCapSol = it)) }, 10))
        addView(Ui.top(stepper(R.string.risk_losses, caps.maxLosses.toDouble(), LOSS_STEPS, SolarchikConfig.HARD_MAX_LOSSES.toDouble(), whole = true) { setCaps(caps.copy(maxLosses = it.toInt())) }, 10))
        addView(Ui.top(stepper(R.string.risk_day_loss, caps.dayLossSol, DAY_STEPS, SolarchikConfig.HARD_DAY_LOSS_SOL) { setCaps(caps.copy(dayLossSol = it)) }, 10))
        addView(Ui.top(Ui.switchRow(ctx, ctx.getString(R.string.risk_pause), caps.paused) { _, on -> setCaps(caps.copy(paused = on)) }, 12))
    }

    private fun stepper(label: Int, value: Double, steps: List<Double>, hard: Double, whole: Boolean = false, onSet: (Double) -> Unit): View {
        val r = Ui.row(ctx, gap = 8)
        val col = Ui.column(ctx)
        col.addView(Ui.text(ctx, ctx.getString(label), 13f, Ui.TEXT, 700))
        val hardTxt = if (whole) hard.toInt().toString() else Fmt.sol(hard) + " SOL"
        col.addView(Ui.muted(ctx, ctx.getString(R.string.risk_hard, hardTxt), 11f))
        r.addView(Ui.weight(col))
        val idx = steps.indexOfFirst { it >= value - 1e-9 }.let { if (it < 0) steps.lastIndex else it }
        val name = ctx.getString(label)
        fun mini(txt: String, desc: Int, enabled: Boolean, go: () -> Unit) = Ui.text(ctx, txt, 18f, Ui.TEXT, 900).apply {
            gravity = Gravity.CENTER
            contentDescription = ctx.getString(desc, name)
            isEnabled = enabled
            background = Ui.rounded(Ui.SURFACE2, dp(12).toFloat(), Ui.STROKE, dp(1))
            isClickable = enabled
            alpha = if (enabled) 1f else 0.35f
            setOnClickListener { if (enabled) go() }
        }
        r.addView(mini("−", R.string.risk_less, idx > 0) { onSet(steps[idx - 1]) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        r.addView(Ui.text(ctx, if (whole) value.toInt().toString() else Fmt.sol(value), 15f, Ui.GOLD, 900).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(64), dp(48)))
        r.addView(mini("+", R.string.risk_more, idx < steps.lastIndex) { onSet(steps[idx + 1]) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return r
    }

    private fun setCaps(c: UserCaps) {
        host.scope.launch {
            host.desk.setCaps(c)
            render()
        }
    }

    private fun feesCard(): View = Ui.card(ctx, accent = Ui.AMBER).apply {
        val head = Ui.row(ctx, gap = 12)
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_gift, Ui.AMBER, 36))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.fees_title))))
        addView(head)
        val owed = host.desk.owedRows()
        val total = owed.sumOf { it.fee }
        if (owed.isEmpty()) {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.fees_none), 12f), 8))
            return@apply
        }
        addView(Ui.top(Ui.body(ctx, ctx.getString(R.string.fees_owed, Fmt.sol(total, 9), owed.size)), 8))
        val b = Ui.button(ctx, if (paying) ctx.getString(R.string.mint_busy) else ctx.getString(R.string.fees_pay, Fmt.sol(total, 9)), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { payFees() }
        Ui.setEnabled(b, !paying)
        addView(Ui.top(b, 12))
    }

    private fun payFees() {
        if (paying) return
        if (host.wallet.mainnet) { host.toast(ctx.getString(R.string.fees_devnet_only)); return }
        paying = true
        render()
        host.scope.launch {
            host.desk.payFees(host.wallet, host.sender)
                .onSuccess { host.toast(ctx.getString(R.string.fees_paid)) }
                .onFailure {
                    host.toast(
                        when ((it as? DeskError)?.kind) {
                            DeskError.Kind.DEVNET_ONLY -> ctx.getString(R.string.fees_devnet_only)
                            DeskError.Kind.NOTHING_OWED -> ctx.getString(R.string.fees_nothing)
                            DeskError.Kind.PAYMENT_PENDING -> ctx.getString(R.string.fees_pending)
                            else -> host.errorText(it)
                        },
                    )
                }
            paying = false
            render()
        }
    }

    private fun styleSeg(tv: TextView, on: Boolean) {
        tv.background = if (on) Ui.gradient(intArrayOf(Ui.GOLD, Ui.AMBER), tv.dp(14).toFloat(), android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT) else null
        tv.setTextColor(if (on) Ui.INK else Ui.MUTED)
    }

    private fun skuCard(sku: AgentSku): View = Ui.card(ctx, accent = sku.accent).apply {
        // Combo is paid only: its card is always Pro, even with the Free tab picked.
        val tier = sku.tierFor(this@AgentsScreen.tier)
        val row = Ui.row(ctx, gap = 14).apply { gravity = Gravity.TOP }
        val artBox = FrameLayout(ctx).apply {
            background = Ui.rounded(Ui.withAlpha(sku.accent, 0x1E), dp(18).toFloat())
        }
        artBox.addView(Ui.image(ctx, sku.artRes), FrameLayout.LayoutParams(dp(70), dp(96), Gravity.CENTER))
        row.addView(artBox, LinearLayout.LayoutParams(dp(88), dp(112)))
        val col = Ui.column(ctx)
        val pills = Ui.row(ctx, gap = 6)
        pills.addView(Ui.pill(ctx, ctx.getString(sku.agentClass.shortRes), sku.accent))
        pills.addView(Ui.pill(ctx, ctx.getString(if (tier == AgentTier.PRO) R.string.tier_pro else R.string.tier_free), if (tier == AgentTier.PRO) Ui.GOLD else Ui.CYAN, filled = tier == AgentTier.PRO))
        if (sku.paidOnly) pills.addView(Ui.pill(ctx, ctx.getString(R.string.tier_paid_only), Ui.GOLD))
        col.addView(pills)
        col.addView(Ui.top(Ui.text(ctx, AgentNames.display(ctx, sku.nameFor(tier)), 17f, Ui.TEXT, 800), 8))
        col.addView(Ui.top(Ui.muted(ctx, ctx.getString(sku.blurbRes)), 4))
        col.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.mint_lanes, lanes(sku.lanes)), 12f, sku.accent, 700), 6))
        row.addView(Ui.weight(col))
        addView(row)

        val block = host.minter.canMint(sku, tier)
        val busy = busySku == sku.skuId(tier)
        val label = when {
            busy -> ctx.getString(R.string.mint_busy)
            block == MintError.Kind.FREE_USED -> ctx.getString(R.string.mint_free_used)
            block == MintError.Kind.PRO_MAINNET_OFF -> ctx.getString(R.string.mint_pro_off)
            block == MintError.Kind.PAID_ONLY -> ctx.getString(R.string.mint_err_paid_only)
            block == MintError.Kind.MAINNET_SOON -> ctx.getString(R.string.mn_mint_soon)
            tier == AgentTier.PRO -> ctx.getString(R.string.mint_pro, Fmt.sol(sku.priceSol(tier)))
            else -> ctx.getString(R.string.mint_free)
        }
        val btn = Ui.button(ctx, label, if (tier == AgentTier.PRO) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, R.drawable.ic_bolt_small) { mint(sku) }
        Ui.setEnabled(btn, block == null && busySku == null)
        addView(Ui.top(btn, 14))
        if (block == MintError.Kind.MAINNET_SOON) addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.mn_mint_soon_body), 11.5f).apply { tag = "mint-soon" }, 6))
    }

    private fun lanes(code: String): String {
        if (code == "dex") return ctx.getString(R.string.lane_dex)
        return code.mapNotNull {
            when (it) {
                'c' -> ctx.getString(R.string.lane_c)
                'e' -> ctx.getString(R.string.lane_e)
                'w' -> ctx.getString(R.string.lane_w)
                else -> null
            }
        }.joinToString(" · ")
    }

    private fun mint(sku: AgentSku) {
        if (busySku != null) return
        val chosen = sku.tierFor(tier)
        busySku = sku.skuId(chosen)
        render()
        host.scope.launch {
            val res = host.minter.mint(host.sender, sku, chosen)
            busySku = null
            res.onSuccess { rec ->
                host.toast(ctx.getString(R.string.mint_ok, rec.name))
                render()
                val checked = host.minter.verify(rec)
                if (checked.status == OwnedAgent.STATUS_VERIFIED) host.toast(ctx.getString(R.string.mint_verified, rec.name))
                val key = walletKey()
                host.wallet.balanceSol().onSuccess { setBalance(key, it) }
            }.onFailure { host.toast(host.errorText(it)) }
            host.renderAll()
        }
    }

    private fun onWalletPill() {
        if (host.wallet.connected) {
            refreshChain()
            return
        }
        host.scope.launch {
            host.wallet.connect(host.sender)
                .onSuccess { refreshChain() }
                .onFailure { host.toast(host.errorText(it)) }
            host.renderAll()
        }
    }

    private fun renderMine() {
        mineBox.removeAllViews()
        val w = host.wallet
        if (!w.connected) {
            mineBox.addView(emptyCard(ctx.getString(R.string.agents_connect_hint)))
            return
        }
        val mine = host.store.agentsFor(w.address, w.clusterName).sortedByDescending { it.mintedAt }
        if (mine.isEmpty()) {
            mineBox.addView(emptyCard(ctx.getString(R.string.agents_mine_empty, w.clusterName)))
            return
        }
        mine.forEach { mineBox.addView(ownedCard(it)) }
    }

    private fun ownedCard(a: OwnedAgent): View = Ui.card(ctx, pad = 14).apply {
        val sku = Catalog.baseOf(a.skuId)
        val row = Ui.row(ctx, gap = 12)
        row.addView(Ui.image(ctx, sku?.artRes ?: R.drawable.robot_sunflower), LinearLayout.LayoutParams(dp(44), dp(56)))
        val col = Ui.column(ctx)
        col.addView(Ui.text(ctx, AgentNames.display(ctx, a.name), 15f, Ui.TEXT, 800))
        val fee = Catalog.feeRateFor(a.tier)
        col.addView(Ui.top(Ui.muted(ctx, Fmt.short(a.asset) + " · " + ctx.getString(R.string.agent_fee_line, Fmt.pct(fee)), 12f), 3))
        row.addView(Ui.weight(col))
        val (txt, color) = when (a.status) {
            OwnedAgent.STATUS_VERIFIED -> ctx.getString(R.string.agent_verified) to Ui.GREEN
            OwnedAgent.STATUS_MISSING -> ctx.getString(R.string.agent_missing) to Ui.RED
            else -> ctx.getString(R.string.agent_pending) to Ui.GOLD
        }
        row.addView(Ui.pill(ctx, txt, color))
        addView(row)
        isClickable = true
        setOnClickListener { host.openUrl(host.explorerAddress(a.asset, a.cluster)) }
    }

    private fun renderLedger() {
        ledgerBox.removeAllViews()
        val rows = host.store.fees()
        if (rows.isEmpty()) {
            ledgerBox.addView(emptyCard(ctx.getString(R.string.ledger_empty)))
            return
        }
        val s = FeeLedger.summarize(rows)
        ledgerBox.addView(Ui.card(ctx, accent = Ui.GREEN, pad = 14).apply {
            addView(Ui.text(ctx, ctx.getString(R.string.ledger_summary, s.positions, Fmt.signedSol(s.pnl, 6)), 15f, Ui.TEXT, 800))
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.ledger_fees, Fmt.sol(s.feesCharged), Fmt.sol(s.feesOwed), Fmt.sol(s.feesWaived)), 12f), 4))
        })
        rows.take(30).forEach { ledgerBox.addView(ledgerRow(it)) }
    }

    private fun ledgerRow(r: FeeRow): View = Ui.card(ctx, pad = 14).apply {
        val top = Ui.row(ctx)
        top.addView(Ui.weight(Ui.text(ctx, r.agent, 14f, Ui.TEXT, 800)))
        top.addView(Ui.text(ctx, Fmt.signedSol(r.pnl, 6) + " SOL", 14f, if (r.pnl > 0) Ui.GREEN else if (r.pnl < 0) Ui.RED else Ui.MUTED, 800))
        addView(top)
        val bottom = Ui.row(ctx, gap = 8)
        val (label, color) = reason(r.reason)
        bottom.addView(Ui.pill(ctx, label, color))
        if (r.fee > 0) bottom.addView(Ui.text(ctx, (if (r.owes) "−" else "") + Fmt.sol(r.fee, 6) + " SOL", 12f, if (r.owes) Ui.AMBER else Ui.MUTED, 700))
        addView(Ui.top(bottom, 8))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.ledger_row_time, Fmt.time(r.openedAt), Fmt.time(r.closedAt)), 11f), 6))
    }

    private fun reason(code: String): Pair<String, Int> = when (code) {
        FeeReason.PRO -> ctx.getString(R.string.reason_pro) to Ui.GOLD
        FeeReason.WINDOW -> ctx.getString(R.string.reason_window) to Ui.GREEN
        FeeReason.LOSS -> ctx.getString(R.string.reason_loss) to Ui.MUTED
        FeeReason.PAPER -> ctx.getString(R.string.reason_paper) to Ui.CYAN
        FeeReason.CHARGED -> ctx.getString(R.string.reason_charged) to Ui.AMBER
        else -> ctx.getString(R.string.reason_unsent) to Ui.AMBER
    }

    private fun emptyCard(text: String): View = Ui.card(ctx, pad = 16).apply {
        addView(Ui.muted(ctx, text, 13f))
    }

    companion object {
        /** 1.1.0 assistant agent tabs. */
        const val SEASON = 0
        const val SAVER = 1
        const val WATCHER = 2
        private val TRADE_STEPS = listOf(0.002, 0.005, 0.01, 0.015, 0.02)
        private val DAY_STEPS = listOf(0.02, 0.05, 0.1, 0.2, 0.3)
        private val LOSS_STEPS = listOf(1.0, 2.0)
    }
}
