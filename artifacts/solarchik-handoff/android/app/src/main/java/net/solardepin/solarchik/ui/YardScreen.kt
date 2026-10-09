package net.solardepin.solarchik.ui

import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.core.FeeLedger
import net.solardepin.solarchik.core.FeeProgress
import net.solardepin.solarchik.core.FeeWindow
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.core.StreakRules
import net.solardepin.solarchik.game.ClockIn
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.ui.Ui.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class YardScreen(host: MainActivity) : Screen(host) {
    private val save get() = host.save
    private var signing = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderClocks()
            ticker.postDelayed(this, 1000)
        }
    }

    private lateinit var streakNum: TextView
    private lateinit var netPill: TextView
    private lateinit var modPill: TextView
    private lateinit var resetIn: TextView
    private lateinit var runBar: SolarProgress
    private lateinit var runLine: TextView
    private lateinit var bestLine: TextView
    private lateinit var status: TextView
    private lateinit var dayReset: TextView
    private lateinit var action: TextView
    private lateinit var proofRow: LinearLayout
    private lateinit var proof: TextView
    private lateinit var week: WeekStrip
    private lateinit var weekSub: TextView
    private lateinit var feeBody: LinearLayout
    private lateinit var crewLine: TextView
    private lateinit var solLine: TextView

    override fun build(): View = page {
        addView(hero())
        // 1.0.0: secretary / calls / agents cards live on Today now; this screen is the daily check-in
        addView(todayCard().also { todayView = it })
        addView(feeCard())
        addView(weekCard())
        addView(crewCard())
        addView(solCard())
    }

    private fun hero(): View {
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(248))
            background = Ui.rounded(Ui.SURFACE, dp(26).toFloat())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.dp(26).toFloat())
                }
            }
            clipToOutline = true
        }
        val art = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            runCatching { ctx.assets.open("yard-bg.jpg").use { setImageBitmap(BitmapFactory.decodeStream(it)) } }
        }
        frame.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Ui.withAlpha(Ui.BG, 0x99), Ui.withAlpha(Ui.BG, 0x10), Ui.withAlpha(Ui.BG, 0xF2)),
            )
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val topRow = Ui.row(ctx).apply { setPadding(dp(18), dp(16), dp(16), 0); gravity = Gravity.TOP }
        val titles = Ui.column(ctx).apply {
            addView(Ui.display(ctx, ctx.getString(R.string.brand), 26f, Ui.GOLD))
            addView(Ui.text(ctx, ctx.getString(R.string.yard_tagline), 12f, Ui.TEXT, 700).apply {
                setShadowLayer(8f, 0f, 1f, Ui.BG)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        topRow.addView(Ui.weight(titles))
        netPill = Ui.pill(ctx, "", Ui.CYAN, filled = false)
        topRow.addView(netPill)
        frame.addView(topRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = Ui.row(ctx).apply { setPadding(dp(18), 0, dp(16), dp(16)); gravity = Gravity.BOTTOM }
        val flame = ImageView(ctx).apply { setImageResource(R.drawable.ic_flame); setColorFilter(Ui.AMBER) }
        bottom.addView(flame, LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(6) })
        val streakCol = Ui.column(ctx).apply { setPadding(dp(6), 0, 0, 0) }
        streakNum = Ui.display(ctx, "0", 40f, Ui.TEXT).apply { setShadowLayer(10f, 0f, 2f, Ui.BG) }
        streakCol.addView(streakNum)
        streakCol.addView(Ui.label(ctx, ctx.getString(R.string.yard_streak_label), Ui.GOLD))
        bottom.addView(Ui.weight(streakCol))
        modPill = Ui.pill(ctx, "", Ui.GOLD, icon = R.drawable.ic_nav_sol)
        bottom.addView(modPill)
        frame.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return frame
    }

    // ---------------- Home cards (0.21.7): Secretary first, then Agents · Slice · Streak ----------------

    private lateinit var secState: TextView
    private lateinit var callsState: TextView
    private lateinit var callsBadge: TextView

    /** Calls card: unread badge and the last call (from the local cache; MainActivity refreshes it). */
    fun renderCalls() {
        if (!this::callsState.isInitialized) return
        val items = net.solardepin.solarchik.screen.CallInbox.cached(ctx)
        val unread = net.solardepin.solarchik.screen.CallInbox.unread(items, net.solardepin.solarchik.screen.CallInbox.seenAt(ctx))
        val last = items.firstOrNull()
        callsState.text = if (last == null) ctx.getString(R.string.home_calls_empty)
        else ctx.getString(R.string.home_calls_last, last.who.ifBlank { ctx.getString(R.string.calls_unknown) }, net.solardepin.solarchik.screen.CallText.time(last.at))
        callsBadge.visibility = if (unread > 0) View.VISIBLE else View.GONE
        callsBadge.text = ctx.resources.getQuantityString(R.plurals.home_calls_new, unread, unread)
        callsBadge.contentDescription = callsBadge.text
    }
    private lateinit var agentsState: TextView
    private lateinit var sliceState: TextView
    private lateinit var streakState: TextView

    private fun bold(colors: IntArray, tag: String, onTap: () -> Unit): LinearLayout = Ui.column(ctx).apply {
        background = Ui.gradient(colors, dp(22).toFloat())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        elevation = dp(6).toFloat()
        isClickable = true
        foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), dp(22).toFloat(), 0x33FFFFFF)
        this.tag = tag
        setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onTap()
        }
    }

    private fun homeCards(): View = Ui.column(ctx, gap = 10).apply {
        // 0.22.0 (owner: an extra screen before the calls): the secretary card opens the Calls list directly
        val sec = bold(intArrayOf(android.graphics.Color.parseColor("#2C6BFF"), android.graphics.Color.parseColor("#7A4DFF")), "home-secretary") { host.openCalls() }
        val top = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.iconBadge(ctx, R.drawable.ic_call, android.graphics.Color.WHITE, 40))
        top.addView(Ui.weight(Ui.column(ctx).apply {
            addView(Ui.text(ctx, ctx.getString(R.string.home_sec_title), 18f, android.graphics.Color.WHITE, 900))
            secState = Ui.text(ctx, "", 12f, Ui.withAlpha(android.graphics.Color.WHITE, 0xDD), 700)
            addView(Ui.top(secState, 2))
        }))
        top.addView(Ui.text(ctx, "›", 26f, android.graphics.Color.WHITE, 900))
        sec.addView(top)
        addView(sec)
        // 0.21.9: Calls — the secretary's call archive, with an unread badge (one tap from Home)
        val calls = bold(intArrayOf(android.graphics.Color.parseColor("#13B4A6"), android.graphics.Color.parseColor("#2C7BFF")), "home-calls") { host.openCalls() }
        val ctop = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        ctop.addView(Ui.iconBadge(ctx, R.drawable.ic_call, android.graphics.Color.WHITE, 40))
        ctop.addView(Ui.weight(Ui.column(ctx).apply {
            addView(Ui.text(ctx, ctx.getString(R.string.home_calls_title), 18f, android.graphics.Color.WHITE, 900))
            callsState = Ui.text(ctx, "", 12f, Ui.withAlpha(android.graphics.Color.WHITE, 0xDD), 700).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            addView(Ui.top(callsState, 2))
        }))
        callsBadge = Ui.text(ctx, "", 13f, Ui.INK, 900).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.GOLD, dp(14).toFloat())
            setPadding(dp(9), dp(3), dp(9), dp(3))
            minWidth = dp(28)
            tag = "home-calls-badge"
            visibility = View.GONE
        }
        ctop.addView(callsBadge)
        ctop.addView(Ui.text(ctx, "›", 26f, android.graphics.Color.WHITE, 900))
        calls.addView(ctop)
        addView(calls)
        val row = Ui.row(ctx, gap = 10)
        fun tile(colors: IntArray, tag: String, icon: Int, title: Int, onTap: () -> Unit): TextView {
            val t = bold(colors, tag, onTap)
            t.addView(Ui.image(ctx, icon).apply { setColorFilter(Ui.INK) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            t.addView(Ui.top(Ui.text(ctx, ctx.getString(title), 14f, Ui.INK, 900).apply { maxLines = 1 }, 8))
            val state = Ui.text(ctx, "", 11f, Ui.withAlpha(Ui.INK, 0xCC), 800).apply { maxLines = 2 }
            t.addView(Ui.top(state, 2))
            row.addView(t, LinearLayout.LayoutParams(0, dp(108), 1f))
            return state
        }
        agentsState = tile(intArrayOf(android.graphics.Color.parseColor("#FFD86B"), android.graphics.Color.parseColor("#F5A524")), "home-agents", R.drawable.ic_nav_agents, R.string.home_agents_title) {
            host.select(MainActivity.Tab.AGENTS, animate = true)
            (host.screen(MainActivity.Tab.AGENTS) as? AgentsScreen)?.openSection(0)
        }
        sliceState = tile(intArrayOf(android.graphics.Color.parseColor("#7CF0D0"), android.graphics.Color.parseColor("#2FB8C9")), "home-slice", R.drawable.ic_slice, R.string.home_slice_title) {
            host.select(MainActivity.Tab.AGENTS, animate = true)
            (host.screen(MainActivity.Tab.AGENTS) as? AgentsScreen)?.openSection(3)
        }
        streakState = tile(intArrayOf(android.graphics.Color.parseColor("#FF9E6B"), android.graphics.Color.parseColor("#FF5E7E")), "home-streak", R.drawable.ic_flame, R.string.home_streak_title) {
            host.select(MainActivity.Tab.RUN, animate = true)
        }
        addView(row)
    }

    private fun renderHome() {
        if (!this::secState.isInitialized) return
        renderCalls()
        val sup = net.solardepin.solarchik.screen.Secretary.supported()
        val on = sup && net.solardepin.solarchik.screen.PlayerIds.screeningOn(ctx) && net.solardepin.solarchik.screen.Secretary.holdsRole(ctx)
        val state = ctx.getString(when { !sup -> R.string.home_sec_unsupported; on -> R.string.home_sec_on; else -> R.string.home_sec_off })
        // 0.21.8: the same live credit as Settings (read on every Home visit)
        val bal = if (sup) net.solardepin.solarchik.screen.Secretary.lastBalance(ctx) else null
        secState.text = if (bal == null) state else ctx.getString(
            R.string.home_sec_with_credit, state, "$" + Fmt.sol(bal.usd, 2) + if (bal.trial) " · " + ctx.getString(R.string.sec_trial) else "",
        )
        val running = host.desk.state().runs.count { it.running }
        agentsState.text = if (running > 0) ctx.resources.getQuantityString(R.plurals.home_agents_running, running, running) else ctx.getString(R.string.home_agents_none)
        val book = net.solardepin.solarchik.agents.SliceStore(ctx).book()
        sliceState.text = ctx.getString(R.string.home_slice_state, "$" + String.format(java.util.Locale.US, "%,.0f", book.value(emptyMap())))
        val st = save.liveStreak().streak
        streakState.text = ctx.resources.getQuantityString(R.plurals.home_streak_days, st, st)
    }

    private fun todayCard(): View = Ui.card(ctx).apply {
        val head = Ui.row(ctx)
        head.addView(Ui.weight(Ui.label(ctx, ctx.getString(R.string.yard_today_title), Ui.GOLD)))
        resetIn = Ui.muted(ctx, "", 12f)
        head.addView(resetIn)
        addView(head)
        val line = Ui.row(ctx)
        runLine = Ui.text(ctx, "", 22f, Ui.TEXT, 900).apply {
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(14, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        line.addView(runLine, LinearLayout.LayoutParams(0, dp(30), 1f))
        bestLine = Ui.muted(ctx).apply { setPadding(dp(10), 0, 0, 0) }
        line.addView(bestLine)
        addView(Ui.top(line, 12))
        runBar = SolarProgress(ctx)
        addView(Ui.top(runBar, 10))
        status = Ui.body(ctx).apply { setTextColor(Ui.MUTED) }
        addView(Ui.top(status, 12))
        dayReset = Ui.text(ctx, "", 12.5f, Ui.MUTED, 600).apply { tag = "yard-day-reset" }
        addView(Ui.top(dayReset, 6))
        action = Ui.button(ctx, "", Ui.Btn.PRIMARY) { onAction() }
        addView(Ui.top(action, 14))
        proofRow = Ui.row(ctx, gap = 10)
        proof = Ui.text(ctx, "", 13f, Ui.CYAN, 700).apply {
            setOnClickListener { openProof() }
            Ui.setIcon(this, R.drawable.ic_open, Ui.CYAN)
        }
        proofRow.addView(Ui.weight(proof))
        proofRow.addView(Ui.tappable(Ui.pill(ctx, ctx.getString(R.string.share), Ui.GOLD, icon = R.drawable.ic_share)).apply {
            setOnClickListener { shareDay() }
        })
        addView(Ui.top(proofRow, 12))
    }

    private fun feeCard(): View = Ui.card(ctx, accent = Ui.GOLD).apply {
        val head = Ui.row(ctx, gap = 10)
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_gift, Ui.GOLD, 36))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.yard_fee_title))))
        addView(head)
        feeBody = Ui.column(ctx, gap = 10)
        addView(Ui.top(feeBody, 14))
    }

    private fun weekCard(): View = Ui.card(ctx).apply {
        val head = Ui.row(ctx)
        head.addView(Ui.weight(Ui.label(ctx, ctx.getString(R.string.yard_week_title))))
        weekSub = Ui.muted(ctx, "", 12f)
        head.addView(weekSub)
        addView(head)
        week = WeekStrip(ctx)
        addView(Ui.top(week, 14))
    }

    private fun crewCard(): View = Ui.card(ctx).apply {
        val row = Ui.row(ctx, gap = 12)
        row.addView(Ui.image(ctx, R.drawable.robot_sunflower), LinearLayout.LayoutParams(dp(54), dp(64)))
        val col = Ui.column(ctx)
        col.addView(Ui.h2(ctx, ctx.getString(R.string.yard_crew_title)))
        crewLine = Ui.muted(ctx)
        col.addView(Ui.top(crewLine, 4))
        row.addView(Ui.weight(col))
        addView(row)
        isClickable = true
        setOnClickListener { host.select(MainActivity.Tab.AGENTS) }
    }

    private fun solCard(): View = Ui.card(ctx, accent = Ui.CYAN).apply {
        val row = Ui.row(ctx, gap = 12)
        row.addView(Ui.image(ctx, R.drawable.buddy_happy), LinearLayout.LayoutParams(dp(56), dp(68)))
        val col = Ui.column(ctx)
        col.addView(Ui.label(ctx, ctx.getString(R.string.yard_sol_title), Ui.CYAN))
        solLine = Ui.body(ctx)
        col.addView(Ui.top(solLine, 6))
        row.addView(Ui.weight(col))
        addView(row)
        isClickable = true
        setOnClickListener { host.select(MainActivity.Tab.SOL) }
    }

    private var secFetchAt = 0L

    override fun onShow() {
        render()
        refreshSecCredit()
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, 1000)
    }

    override fun onHide() {
        ticker.removeCallbacks(tick)
    }

    /** Live secretary credit for the Home card (at most every 20 s). */
    private fun refreshSecCredit() {
        if (!net.solardepin.solarchik.screen.Secretary.supported()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - secFetchAt < 20_000) return
        secFetchAt = now
        val id = net.solardepin.solarchik.screen.PlayerIds.get(ctx)
        host.scope.launch {
            val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { net.solardepin.solarchik.screen.ScreenApi.balanceInfo(id) }
            if (b != null) { net.solardepin.solarchik.screen.Secretary.setLastBalance(ctx, b); renderHome() }
        }
    }

    override fun render() {
        if (!this::status.isInitialized) return
        renderHome()
        val goal = GameSave.GOAL_M
        renderedDay = save.today()
        val state = save.liveStreak()
        streakNum.text = Fmt.count(state.streak)
        netPill.text = ctx.getString(if (host.wallet.mainnet) R.string.network_mainnet else R.string.network_devnet)
        modPill.text = modName(save.dayMod())

        val dist = save.todayDistance()
        val clocked = save.clockedToday()
        val signed = save.signedToday()
        runLine.text = ctx.getString(R.string.yard_run_progress, dist.coerceAtMost(goal), goal)
        bestLine.text = ctx.getString(R.string.yard_best, save.bestDistance)
        runBar.fraction = dist / goal.toFloat()
        status.text = when {
            signed && save.clockKind == "message" -> ctx.getString(R.string.yard_status_msg, save.clockCluster.ifBlank { "devnet" })
            signed -> ctx.getString(R.string.yard_status_tx, save.clockCluster.ifBlank { "devnet" }) // never "sign the day" once it is signed
            clocked -> ctx.getString(R.string.yard_status_ready)
            dist in 1..399 -> ctx.getString(R.string.yard_status_first_roof)
            else -> ctx.getString(R.string.yard_status_need_run, goal)
        }
        dayReset.text = ctx.getString(R.string.day_new_at, Fmt.clock(net.solardepin.solarchik.core.StreakRules.nextDayStart(System.currentTimeMillis())))
        when {
            signing -> {
                action.text = ctx.getString(R.string.yard_signing)
                Ui.styleButton(action, Ui.Btn.SECONDARY)
                action.setCompoundDrawables(null, null, null, null)
                Ui.setEnabled(action, false)
            }
            signed -> {
                action.text = ctx.getString(R.string.yard_signed)
                Ui.styleButton(action, Ui.Btn.SUCCESS)
                Ui.setIcon(action, R.drawable.ic_check, Ui.GREEN)
                Ui.setEnabled(action, true)
                action.alpha = 1f
            }
            clocked -> {
                action.text = ctx.getString(R.string.yard_clock_in)
                Ui.styleButton(action, Ui.Btn.PRIMARY)
                Ui.setIcon(action, R.drawable.ic_wallet, Ui.INK)
                Ui.setEnabled(action, true)
            }
            else -> {
                action.text = ctx.getString(R.string.yard_go_run, goal)
                Ui.styleButton(action, Ui.Btn.PRIMARY)
                Ui.setIcon(action, R.drawable.ic_nav_run, Ui.INK)
                Ui.setEnabled(action, true)
            }
        }
        proofRow.visibility = if (signed && save.clockSig.isNotBlank()) View.VISIBLE else View.GONE
        proof.text = when {
            save.clockSig.isBlank() -> ""
            save.clockKind == "message" -> ctx.getString(R.string.yard_proof_msg, Fmt.short(save.clockSig))
            else -> ctx.getString(R.string.yard_proof_tx, Fmt.short(save.clockSig))
        }

        renderWeek(state.clockDays)
        weekSub.text = ctx.getString(R.string.yard_week_sub, state.streak)
        renderFee()
        renderCrew()
        renderSolLine(state.streak)
        renderClocks()
    }

    /** Live AI greeting once a day (cached); the tip of the day only until it lands / offline. */
    private fun renderSolLine(streak: Int) {
        val day = save.today()
        val lang = host.lang
        val st = net.solardepin.solarchik.sol.SolState.of(save)
        val greet = net.solardepin.solarchik.sol.SolGreeting.cached(ctx, day, lang, st.key)
        solLine.text = greet ?: SolScreen.tipOfDay(ctx, day)
        if (greet != null) return
        host.scope.launch {
            val cue = if (st.signedToday) ctx.getString(R.string.sol_greet_cue_signed, streak) else ctx.getString(R.string.sol_greet_cue, streak)
            val g = net.solardepin.solarchik.sol.SolGreeting.fetch(ctx, day, lang, cue, st)
            if (g != null) solLine.text = g
        }
    }

    private fun renderWeek(days: List<String>) {
        val today = LocalDate.parse(save.today())
        val monday = today.with(DayOfWeek.MONDAY)
        val set = days.toSet()
        val labels = ctx.getString(R.string.weekday_short).split(",")
        val done = (0 until 7).map { set.contains(monday.plusDays(it.toLong()).toString()) }
        week.bind(labels, done, (today.dayOfWeek.value - 1))
    }

    private var feeActiveText: TextView? = null
    private var feeActiveBar: SolarProgress? = null

    private fun renderFee() {
        feeBody.removeAllViews()
        feeActiveText = null
        feeActiveBar = null
        when (val p = save.feeProgress()) {
            is FeeProgress.Active -> {
                feeBody.addView(Ui.row(ctx, gap = 8).apply {
                    addView(Ui.pill(ctx, ctx.getString(R.string.yard_fee_active), Ui.GREEN, icon = R.drawable.ic_timer))
                })
                val big = Ui.display(ctx, Fmt.countdown(p.leftMs), 34f, Ui.GOLD)
                feeActiveText = big
                feeBody.addView(big)
                val bar = SolarProgress(ctx, Ui.GREEN, Ui.CYAN)
                bar.fraction = p.leftMs / p.window.durationMs.toFloat()
                feeActiveBar = bar
                feeBody.addView(bar)
                feeBody.addView(Ui.muted(ctx, ctx.getString(R.string.yard_fee_active_sub)))
            }
            is FeeProgress.Ready -> {
                val kind = ctx.getString(if (p.window.kind == FeeWindow.KIND_LONG) R.string.window_7d else R.string.window_48h)
                feeBody.addView(Ui.text(ctx, ctx.getString(R.string.yard_fee_ready, kind), 20f, Ui.GOLD, 900))
                feeBody.addView(Ui.muted(ctx, if (p.count > 1) ctx.getString(R.string.yard_fee_ready_more, p.count) else ctx.getString(R.string.yard_fee_ready_one)))
                feeBody.addView(Ui.button(ctx, ctx.getString(R.string.yard_fee_activate), Ui.Btn.PRIMARY, R.drawable.ic_bolt_small) {
                    if (save.activateWindow()) host.toast(ctx.getString(R.string.yard_activated))
                    render()
                })
            }
            is FeeProgress.Wait -> {
                val short = SolarchikConfig.STREAK_SHORT_DAYS
                feeBody.addView(progressRow(ctx.getString(R.string.yard_fee_wait_48, p.days48), "${p.seven}/$short", p.seven / short.toFloat(), Ui.GOLD, Ui.AMBER))
                feeBody.addView(progressRow(ctx.getString(R.string.yard_fee_wait_7d, p.days30), "${p.thirty}/${p.next30}",
                    (p.thirty - (p.next30 - SolarchikConfig.STREAK_LONG_DAYS)) / SolarchikConfig.STREAK_LONG_DAYS.toFloat(), Ui.CYAN, Ui.PURPLE))
                feeBody.addView(Ui.muted(ctx, ctx.getString(R.string.yard_fee_rules), 12f))
            }
        }
    }

    private fun progressRow(title: String, count: String, frac: Float, a: Int, b: Int): View = Ui.column(ctx).apply {
        val r = Ui.row(ctx)
        r.addView(Ui.weight(Ui.text(ctx, title, 14f, Ui.TEXT, 700)))
        r.addView(Ui.text(ctx, count, 13f, a, 800))
        addView(r)
        addView(Ui.top(SolarProgress(ctx, a, b).apply { fraction = frac }, 8))
    }

    private fun renderCrew() {
        val w = host.wallet
        val agents = if (w.connected) host.store.agentsFor(w.address, w.clusterName).filter { it.status != "missing" } else emptyList()
        val sum = FeeLedger.summarize(host.store.fees())
        val desk = host.desk.state()
        val running = desk.runs.count { it.running }
        val holding = desk.runs.count { it.open != null }
        val base = if (agents.isEmpty() && sum.positions == 0) ctx.getString(R.string.yard_crew_empty)
        else ctx.getString(R.string.yard_crew_line, agents.size, Fmt.signedSol(sum.pnl, 6), Fmt.sol(sum.feesWaived, 6))
        crewLine.text = if (running + holding > 0) base + "\n" + ctx.getString(R.string.yard_crew_desk, running, holding) else base
    }

    private var renderedDay = ""

    private fun renderClocks() {
        if (!this::resetIn.isInitialized) return
        // A new UTC day while the yard is open: streak, run and CLOCK IN state all change.
        if (renderedDay.isNotEmpty() && renderedDay != save.today()) { render(); return }
        val now = save.now()
        val end = LocalDate.parse(StreakRules.dayKey(now)).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        resetIn.text = ctx.getString(R.string.yard_reset_in, Fmt.countdown(end - now).let { if (it.length > 5) it.substring(0, 5) else it })
        val p = save.feeProgress()
        if (p is FeeProgress.Active) {
            feeActiveText?.text = Fmt.countdown(p.leftMs)
            feeActiveBar?.fraction = p.leftMs / p.window.durationMs.toFloat()
        } else if (feeActiveText != null) {
            renderFee()
        }
    }

    private fun onAction() {
        when {
            signing -> Unit
            save.signedToday() -> Unit
            save.clockedToday() -> clockIn()
            else -> host.startRun()
        }
    }

    private var todayView: View? = null

    /** 0.22.0: the rooftop punch clock opens this screen on today's CLOCK IN card. */
    fun focusToday() {
        val v = todayView ?: return
        v.post { scrollToView(v) }
    }

    /** The run's CLOCK IN card asked to sign right away (web onClock). */
    fun signFromRun() = clockIn()

    private fun clockIn() {
        if (!ClockIn.ready(save) || signing) return
        signing = true
        render()
        host.scope.launch {
            val result = ClockIn.sign(host.wallet, host.sender, save)
            signing = false
            result.onSuccess { granted ->
                granted?.let { w ->
                    host.toast(ctx.getString(R.string.yard_granted, ctx.getString(if (w.kind == FeeWindow.KIND_LONG) R.string.window_7d else R.string.window_48h)))
                }
            }.onFailure {
                host.toast(host.errorText(it))
            }
            host.renderAll()
        }
    }

    private fun shareDay() = ClockIn.share(host, save)

    private fun openProof() {
        if (save.clockKind != "tx" || save.clockSig.isBlank()) return
        host.openUrl(host.explorerTx(save.clockSig, save.clockCluster))
    }

    private fun modName(mod: String): String = ctx.getString(
        when (mod) {
            "wind" -> R.string.mod_wind
            "gold" -> R.string.mod_gold
            "drones" -> R.string.mod_drones
            "wire" -> R.string.mod_wire
            else -> R.string.mod_calm
        },
    ).substringBefore(":")

    companion object {
        fun modLong(ctx: android.content.Context, mod: String): String = ctx.getString(
            when (mod) {
                "wind" -> R.string.mod_wind
                "gold" -> R.string.mod_gold
                "drones" -> R.string.mod_drones
                "wire" -> R.string.mod_wire
                else -> R.string.mod_calm
            },
        )
    }
}
