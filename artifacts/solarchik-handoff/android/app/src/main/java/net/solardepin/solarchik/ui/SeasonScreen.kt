package net.solardepin.solarchik.ui

import android.graphics.Color
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.season.SeasonDapp
import net.solardepin.solarchik.season.SeasonDapps
import net.solardepin.solarchik.season.SeasonItem
import net.solardepin.solarchik.season.SeasonPlan
import net.solardepin.solarchik.season.SeasonStore
import net.solardepin.solarchik.season.Skr

/**
 * 1.0.0 Seeker Season helper: a daily plan whose items tick only from real state on this phone
 * (opened today, a suggested dApp opened from here today, today's check-in signed), plus the SKR
 * balance read from mainnet. 1.1.0: the optional autopilot (OFF by default) only plans and notifies; the wallet signs.
 */
class SeasonScreen(host: MainActivity) : Screen(host) {
    private lateinit var progress: TextView
    private lateinit var planBox: LinearLayout
    private lateinit var skrBody: LinearLayout

    private var skr: Double? = null
    private var skrFor: String? = null
    private var skrAt = 0L
    private var skrFailed = false
    private var skrLoading = false
    internal val autopilot by lazy { AutopilotPanel(host) { render() } }
    private lateinit var autoBox: LinearLayout
    internal val drops by lazy { SeasonDropsPanel(host) { render() } }
    private lateinit var dropsBox: LinearLayout

    override fun build(): View = page {
        val top = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(FrameLayout(ctx).apply {
            tag = "season-back"
            contentDescription = ctx.getString(R.string.nav_today)
            background = Ui.rounded(Ui.withAlpha(Color.WHITE, 0x10), dp(22).toFloat())
            isClickable = true
            minimumHeight = dp(Ui.TAP_MIN_DP); minimumWidth = dp(Ui.TAP_MIN_DP)
            setOnClickListener { host.select(MainActivity.Tab.TODAY, animate = true) }
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(Ui.TEXT); rotation = 180f }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(Ui.weight(Ui.display(ctx, ctx.getString(R.string.season_title), 26f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }))
        addView(top)
        addView(Ui.muted(ctx, ctx.getString(R.string.season_sub), 13.5f).apply { setLineSpacing(0f, 1.25f) })

        addView(Ui.card(ctx, accent = Ui.GOLD).apply {
            tag = "season-plan"
            val head = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(Ui.iconBadge(ctx, R.drawable.ic_check, Ui.GOLD, 40))
            head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.season_plan)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }))
            progress = Ui.pill(ctx, "", Ui.GOLD).apply { tag = "season-progress" }
            head.addView(progress)
            addView(head)
            planBox = Ui.column(ctx, gap = 12)
            addView(Ui.top(planBox, 14))
        })

        dropsBox = Ui.column(ctx)
        addView(dropsBox)

        autoBox = Ui.column(ctx)
        addView(autoBox)

        addView(Ui.card(ctx, accent = Ui.PURPLE).apply {
            tag = "season-skr"
            val head = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(Ui.iconBadge(ctx, R.drawable.ic_wallet, Ui.PURPLE, 40))
            head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.season_skr))))
            head.addView(Ui.pill(ctx, ctx.getString(R.string.season_skr_mainnet), Ui.PURPLE))
            addView(head)
            skrBody = Ui.column(ctx)
            addView(Ui.top(skrBody, 12))
        })

        addView(Ui.card(ctx).apply {
            tag = "season-fair"
            addView(Ui.label(ctx, ctx.getString(R.string.season_fair_title), Ui.CYAN))
            addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.season_fair_body), 13.5f, Ui.withAlpha(Ui.TEXT, 0xDD), 500).apply { setLineSpacing(0f, 1.25f) }, 6))
        })
    }

    fun plan(): SeasonPlan = SeasonStore.planFor(ctx, host.save, host.wallet.mainnet)

    override fun onShow() {
        render()
        refreshSkr()
        drops.refresh()
    }

    override fun render() {
        if (!this::planBox.isInitialized) return
        val p = plan()
        progress.text = ctx.getString(R.string.season_progress, p.doneCount, p.total)
        planBox.removeAllViews()
        planBox.addView(useItem(p))
        planBox.addView(exploreItem(p))
        planBox.addView(chainItem(p))
        dropsBox.removeAllViews()
        dropsBox.addView(drops.card())
        autoBox.removeAllViews()
        autoBox.addView(autopilot.card())
        renderSkr()
    }

    // ------------------------------------------------------------------ plan items

    private fun item(tagName: String, done: Boolean, title: Int, body: String, fill: LinearLayout.() -> Unit = {}): View = Ui.row(ctx, gap = 12).apply {
        tag = tagName
        gravity = Gravity.TOP
        background = Ui.rounded(Ui.withAlpha(if (done) Ui.GREEN else Color.WHITE, if (done) 0x14 else 0x08), dp(16).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(FrameLayout(ctx).apply {
            tag = "$tagName-check"
            contentDescription = if (done) "done" else "not done"
            background = if (done) Ui.rounded(Ui.GREEN, dp(13).toFloat()) else Ui.rounded(Color.TRANSPARENT, dp(13).toFloat(), Ui.withAlpha(Ui.MUTED, 0xAA), dp(2))
            if (done) addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_check); setColorFilter(Ui.INK) }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        val col = Ui.column(ctx)
        col.addView(Ui.text(ctx, ctx.getString(title), 15f, Ui.TEXT, 800))
        col.addView(Ui.top(Ui.text(ctx, body, 13f, if (done) Ui.GREEN else Ui.MUTED, 600).apply { setLineSpacing(0f, 1.2f) }, 3))
        col.fill()
        addView(Ui.weight(col))
    }

    private fun useItem(p: SeasonPlan): View {
        val streak = ctx.resources.getQuantityString(R.plurals.season_streak, p.streak, p.streak)
        return item("season-use", p.done(SeasonItem.DAILY_USE), R.string.season_use_title,
            if (p.openedToday) ctx.getString(R.string.season_use_done, streak) else ctx.getString(R.string.season_use_todo))
    }

    private fun exploreItem(p: SeasonPlan): View {
        val done = p.done(SeasonItem.EXPLORE)
        val body = if (done) ctx.getString(R.string.season_explore_done, p.explored)
        else ctx.getString(R.string.season_explore_todo, p.suggestion.name, ctx.getString(p.suggestion.about))
        return item("season-explore", done, R.string.season_explore_title, body) {
            if (!done) addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.season_explore_open, p.suggestion.name), Ui.Btn.PRIMARY, R.drawable.ic_open) { openDapp(p.suggestion) }.apply { tag = "season-explore-open" }, 10))
            addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.season_explore_more)), 12))
            val others = SeasonDapps.all.filter { it != p.suggestion }
            val chips = Ui.row(ctx, gap = 8)
            others.forEach { d ->
                chips.addView(Ui.text(ctx, d.short, 12.5f, Ui.CYAN, 800).apply {
                    contentDescription = d.name
                    tag = "season-dapp-" + d.pkg
                    gravity = Gravity.CENTER
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    minHeight = dp(Ui.TAP_MIN_DP)
                    setPadding(dp(4), 0, dp(4), 0)
                    setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    background = Ui.rounded(Ui.withAlpha(Ui.CYAN, 0x14), dp(14).toFloat(), Ui.withAlpha(Ui.CYAN, 0x44), dp(1))
                    isClickable = true
                    setOnClickListener { openDapp(d) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(Ui.top(chips, 6))
            addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.season_explore_note), 11.5f, Ui.MUTED, 500).apply { setLineSpacing(0f, 1.2f) }, 8))
        }
    }

    private fun chainItem(p: SeasonPlan): View {
        val save = host.save
        val body = when {
            p.mainnet && p.onchain == "swap" -> ctx.getString(R.string.mn_season_swap_done)
            p.mainnet && p.onchain != null -> ctx.getString(R.string.mn_season_checkin_done)
            p.mainnet && p.signedToday -> ctx.getString(R.string.mn_season_not_chain)
            p.signedToday -> ctx.getString(R.string.season_chain_signed)
            p.clockedToday -> ctx.getString(R.string.season_chain_sign)
            else -> ctx.getString(R.string.season_chain_run, GameSave.GOAL_M)
        }
        return item("season-chain", p.done(SeasonItem.ONCHAIN), R.string.season_chain_title, body) {
            if (!p.signedToday) addView(Ui.top(Ui.button(ctx, ctx.getString(if (p.clockedToday) R.string.season_chain_btn_sign else R.string.season_chain_btn_run),
                if (p.clockedToday) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, R.drawable.ic_flame) {
                host.select(MainActivity.Tab.SHIFT, animate = true)
                val shift = host.screen(MainActivity.Tab.SHIFT) as? YardScreen
                if (save.checkInOpen() && !save.signedToday()) shift?.signFromRun() else shift?.focusToday()
            }.apply { tag = "season-chain-go" }, 10))
            if (p.mainnet && !p.done(SeasonItem.ONCHAIN)) addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.mn_season_swap_btn), Ui.Btn.GHOST, R.drawable.ic_open) {
                host.select(MainActivity.Tab.AGENTS, animate = true)
                (host.screen(MainActivity.Tab.AGENTS) as? AgentsScreen)?.openSection(AgentsScreen.SAVER)
            }.apply { tag = "season-chain-swap" }, 8))
            addView(Ui.top(Ui.text(ctx, ctx.getString(if (p.mainnet) R.string.mn_season_chain_note else R.string.season_chain_note), 11.5f, Ui.MUTED, 500).apply { setLineSpacing(0f, 1.2f) }, 8))
        }
    }

    /** Opens the app if installed, else its website. Ticks "explore" for today: the user really opened it from here. */
    fun openDapp(d: SeasonDapp) {
        val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(d.pkg) }.getOrNull()
        if (launch != null) runCatching { host.startActivity(launch) }.onFailure { host.openUrl(d.url) }
        else host.openUrl(d.url)
        SeasonStore.markExplored(ctx, d)
        render()
        (host.screen(MainActivity.Tab.TODAY) as? TodayScreen)?.render()
    }

    // ------------------------------------------------------------------ SKR

    private fun renderSkr() {
        skrBody.removeAllViews()
        val w = host.wallet
        if (!w.connected) {
            skrBody.addView(Ui.muted(ctx, ctx.getString(R.string.season_skr_none), 13.5f).apply { setLineSpacing(0f, 1.25f) })
            skrBody.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.season_skr_connect), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { connect() }.apply { tag = "season-skr-connect" }, 14))
        } else {
            val have = skr.takeIf { skrFor == w.address }
            skrBody.addView(Ui.display(ctx, when {
                have != null -> Fmt.sol(have, 2) + " SKR"
                skrFailed -> "— SKR"
                else -> "… SKR"
            }, 28f, Ui.TEXT).apply { tag = "season-skr-balance" })
            skrBody.addView(Ui.top(Ui.muted(ctx, when {
                have == null && skrFailed -> ctx.getString(R.string.season_skr_error)
                have == null -> ctx.getString(R.string.season_skr_loading)
                else -> ctx.getString(R.string.season_skr_addr, Fmt.short(w.address))
            }, 12.5f).apply { tag = "season-skr-status" }, 4))
            if (have == null && skrFailed) skrBody.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.season_skr_retry), Ui.Btn.SECONDARY) { refreshSkr(force = true) }.apply { tag = "season-skr-retry" }, 10))
            if (w.isLocal) skrBody.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.season_skr_local), 12.5f, Ui.AMBER, 600).apply { setLineSpacing(0f, 1.2f) }, 10))
        }
        skrBody.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.season_skr_stake_note), 12.5f, Ui.MUTED, 500).apply { setLineSpacing(0f, 1.2f) }, 14))
        skrBody.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.season_skr_stake), Ui.Btn.GHOST, R.drawable.ic_open) {
            host.openUrl(Skr.STAKE_URL)
            if (SeasonStore.explored(ctx) == null) SeasonStore.markExploredName(ctx, "SKR staking")
            render()
        }.apply { tag = "season-skr-stake" }, 8))
    }

    private fun connect() {
        val w = host.wallet
        val started = host.current
        host.scope.launch {
            if (w.hasWalletApp()) w.connect(host.sender).onFailure { host.walletFailed(started, it) }
            else host.setupBuiltInWallet()
            skrAt = 0L
            refreshSkr()
            host.renderAll()
        }
    }

    /** Read-only mainnet balance; throttled to once a minute per address. Skipped in tests (no network). */
    fun refreshSkr(force: Boolean = false) {
        val w = host.wallet
        if (!w.connected || !MainActivity.tickerEnabled || skrLoading) return
        val addr = w.address
        val now = SystemClock.elapsedRealtime()
        if (!force && skrFor == addr && skrAt != 0L && now - skrAt < 60_000) return
        skrAt = now
        skrLoading = true
        skrFailed = false
        if (this::skrBody.isInitialized) renderSkr()
        host.scope.launch {
            val r = withContext(Dispatchers.IO) { Skr.fetch(addr) }
            skrLoading = false
            r.onSuccess { skr = it; skrFor = addr; skrFailed = false }.onFailure { skrFailed = true; if (skrFor != addr) skr = null }
            if (this@SeasonScreen::skrBody.isInitialized) renderSkr()
        }
    }

    /** Tests: a known SKR result without the network (null + failed = RPC error). */
    internal fun setSkrForTest(value: Double?, failed: Boolean = false) {
        skr = value; skrFor = if (value != null) host.wallet.address else null; skrFailed = failed
        if (this::skrBody.isInitialized) renderSkr()
    }
}
