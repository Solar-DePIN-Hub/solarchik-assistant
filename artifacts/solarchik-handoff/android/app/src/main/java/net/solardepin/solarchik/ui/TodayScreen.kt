package net.solardepin.solarchik.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.BuildConfig
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.screen.FollowUp
import net.solardepin.solarchik.screen.FollowUps
import net.solardepin.solarchik.sol.AssistantRules
import net.solardepin.solarchik.sol.SolEars
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 1.0.0 home: "Today". Sol with a big hold-to-talk mic, the phone secretary, follow-ups from calls,
 * the agent wallet on Solana devnet, and two small habit tiles (daily check-in, the rooftop run).
 * Every card opens an existing screen; nothing here is a mock.
 */
class TodayScreen(host: MainActivity) : Screen(host) {
    private lateinit var dateLabel: TextView
    private lateinit var greeting: TextView
    private lateinit var summary: TextView
    private lateinit var solLine: TextView
    private lateinit var micHint: TextView
    private lateinit var mic: FrameLayout
    private lateinit var micRing: View
    private lateinit var secPill: TextView
    private lateinit var statAnswered: TextView
    private lateinit var statMissed: TextView
    private lateinit var statBlocked: TextView
    private lateinit var latestLabel: TextView
    private lateinit var latestBox: LinearLayout
    private lateinit var todoCount: TextView
    private lateinit var todoBox: LinearLayout
    private lateinit var walletBody: LinearLayout
    private lateinit var checkStreak: TextView
    private lateinit var checkState: TextView
    private lateinit var playSub: TextView
    private lateinit var seasonSub: TextView
    private lateinit var seasonChecks: LinearLayout

    private var ears: SolEars? = null
    private var listening = false
    private var downAt = 0L
    private var stopOnUp = false
    private var skipClick = false
    private var ringAnim: ValueAnimator? = null
    private var balance: Double? = null
    private var balanceAt = 0L
    private var balanceFailed = false
    private lateinit var briefingBody: LinearLayout
    private lateinit var actionsBox: LinearLayout
    private lateinit var actionsWrap: View
    private var voice: net.solardepin.solarchik.sol.SolVoice? = null
    internal var briefingBusy = false
    internal var briefingPlaying = false
    /** Tests: the worker call (POST) and the voice are scripted. */
    internal var briefingPost: (String, String) -> Pair<Int, String> = { url, body -> (postOverride ?: defaultPost)(url, body) }
    internal var speak: (String, String) -> Boolean = { text, lang -> (voice ?: net.solardepin.solarchik.sol.SolVoice(host).also { voice = it }).speak(text, lang) }
    private var actionsSynced = 0L

    override fun build(): View = page {
        addView(header())
        addView(solCard())
        addView(briefingCard())
        addView(secretaryCard())
        addView(actionsCard())
        addView(todoCard())
        addView(seasonCard())
        addView(walletCard())
        addView(habitRow())
        addView(Ui.text(ctx, ctx.getString(R.string.today_footer, BuildConfig.VERSION_NAME, host.wallet.clusterName), 11f, Ui.withAlpha(Ui.MUTED, 0xAA), 600).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        })
    }

    // ------------------------------------------------------------------ header

    private fun header(): View = Ui.column(ctx).apply {
        tag = "today-header"
        val top = Ui.row(ctx, gap = 10).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = Ui.column(ctx)
        dateLabel = Ui.label(ctx, "", Ui.GOLD)
        titles.addView(dateLabel)
        greeting = Ui.display(ctx, "", 26f).apply {
            maxLines = 1
            tag = "today-greeting"
            setAutoSizeTextTypeUniformWithConfiguration(18, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        titles.addView(greeting, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(6) })
        top.addView(Ui.weight(titles))
        top.addView(roundIcon(R.drawable.ic_nav_settings, ctx.getString(R.string.today_settings)) { host.select(MainActivity.Tab.SETTINGS, animate = true) }.apply { tag = "today-settings" })
        addView(top)
        summary = Ui.muted(ctx, "", 14f).apply { tag = "today-summary" }
        addView(Ui.top(summary, 6))
    }

    private fun roundIcon(res: Int, desc: String, onTap: () -> Unit): View = FrameLayout(ctx).apply {
        background = Ui.ripple(Ui.rounded(Ui.SURFACE, dp(22).toFloat(), Ui.STROKE, dp(1)), dp(22).toFloat())
        contentDescription = desc
        isClickable = true
        setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(Ui.TEXT) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    // ------------------------------------------------------------------ Sol

    private fun solCard(): View = Ui.column(ctx).apply {
        tag = "today-sol"
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#1D2F6E"), Color.parseColor("#123B52"), Color.parseColor("#0C2A3A"))).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), Ui.withAlpha(Ui.CYAN, 0x44))
        }
        setPadding(dp(18), dp(18), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val top = Ui.row(ctx, gap = 12).apply { gravity = Gravity.TOP }
        val col = Ui.column(ctx)
        val who = Ui.row(ctx, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
        who.addView(View(ctx).apply { background = Ui.rounded(Ui.GREEN, dp(4).toFloat()) }, LinearLayout.LayoutParams(dp(8), dp(8)))
        who.addView(Ui.text(ctx, ctx.getString(R.string.today_sol_name), 15f, Color.WHITE, 800))
        who.addView(Ui.text(ctx, ctx.getString(R.string.today_sol_role), 12f, Ui.withAlpha(Color.WHITE, 0xAA), 600))
        col.addView(who)
        solLine = Ui.text(ctx, "", 16f, Color.WHITE, 700).apply {
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
            tag = "today-sol-line"
        }
        col.addView(Ui.top(solLine, 10))
        top.addView(Ui.weight(col))
        top.addView(Ui.image(ctx, R.drawable.buddy_happy), LinearLayout.LayoutParams(dp(78), dp(104)))
        addView(top)

        // the big mic: hold to talk, release to send; a short tap is hands-free (ends on silence)
        val micWrap = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        micRing = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.withAlpha(Ui.GOLD, 0x22)); setStroke(dp(2), Ui.withAlpha(Ui.GOLD, 0x66)) }
            alpha = 0f
        }
        micWrap.addView(micRing, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        mic = FrameLayout(ctx).apply {
            tag = "today-mic"
            contentDescription = ctx.getString(R.string.today_mic_desc)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FFE07A"), Ui.GOLD, Color.parseColor("#F29A2E"))).apply { shape = GradientDrawable.OVAL }
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = true
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_mic); setColorFilter(Ui.INK) }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
        }
        attachMic(mic)
        micWrap.addView(mic, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        // keyboard | MIC | chat history, like the call screen of a phone
        val micRow = Ui.row(ctx).apply { gravity = Gravity.CENTER }
        micRow.addView(sideButton(R.drawable.ic_keyboard, ctx.getString(R.string.today_chip_type), "today-chip-type") {
            host.select(MainActivity.Tab.SOL, animate = true)
            (host.screen(MainActivity.Tab.SOL) as? SolScreen)?.focusInput()
        })
        micRow.addView(micWrap, LinearLayout.LayoutParams(dp(140), dp(124)))
        micRow.addView(sideButton(R.drawable.ic_chat, ctx.getString(R.string.today_chip_chat), "today-chip-chat") {
            host.select(MainActivity.Tab.SOL, animate = true)
        })
        addView(micRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(124)).apply { topMargin = dp(8) })
        micHint = Ui.text(ctx, ctx.getString(R.string.today_mic_hint), 12.5f, Ui.withAlpha(Color.WHITE, 0xCC), 700).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            tag = "today-mic-hint"
        }
        addView(micHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        val chips = Ui.row(ctx, gap = 8)
        chips.addView(chip(ctx.getString(R.string.today_chip_calls), "today-chip-calls") { askSol(ctx.getString(R.string.today_chip_calls_msg), voice = false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chips.addView(chip(ctx.getString(R.string.today_chip_ask), "today-chip-ask") { askSol(ctx.getString(R.string.today_chip_ask_msg), voice = false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    private fun sideButton(icon: Int, desc: String, tagName: String, onTap: () -> Unit): View = FrameLayout(ctx).apply {
        tag = tagName
        contentDescription = desc
        isClickable = true
        background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.withAlpha(Color.WHITE, 0x14)); setStroke(dp(1), Ui.withAlpha(Color.WHITE, 0x33)) }, dp(26).toFloat())
        setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
        addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }

    private fun chip(text: String, tagName: String, onTap: () -> Unit): TextView = Ui.text(ctx, text, 13f, Color.WHITE, 700).apply {
        tag = tagName
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(11, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        gravity = Gravity.CENTER
        minHeight = dp(Ui.TAP_MIN_DP)
        setPadding(dp(14), 0, dp(14), 0)
        background = Ui.ripple(Ui.rounded(Ui.withAlpha(Color.WHITE, 0x14), dp(22).toFloat(), Ui.withAlpha(Color.WHITE, 0x33), dp(1)), dp(22).toFloat())
        isClickable = true
        setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachMic(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    view.isPressed = true
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    stopOnUp = listening
                    if (!listening) startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    val held = SystemClock.uptimeMillis() - downAt > HOLD_MS
                    when {
                        stopOnUp -> stopListening()
                        held && listening -> ears?.finishNow() // nothing heard yet: stay hands-free until silence
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP) { skipClick = true; view.performClick() }
                    true
                }
                else -> true
            }
        }
        // TalkBack / switch access: a click toggles hands-free listening
        v.setOnClickListener {
            if (skipClick) { skipClick = false; return@setOnClickListener }
            if (listening) stopListening() else startListening()
        }
    }

    /** Public for tests: same as pressing the mic. */
    fun startListening() {
        if (listening) return
        host.withPermission(android.Manifest.permission.RECORD_AUDIO) { ok ->
            if (!ok) { host.toast(ctx.getString(R.string.chat_mic_denied)); return@withPermission }
            val e = ears ?: SolEars(host).also { ears = it }
            if (!e.available()) { host.toast(ctx.getString(R.string.chat_mic_off)); return@withPermission }
            if (MainActivity.tickerEnabled) net.solardepin.solarchik.sol.SolLatency.prewarmWorker()
            listening = true
            renderMic()
            e.listen(host.lang, onPartial = { micHint.text = "“$it”" }) { said ->
                listening = false
                renderMic()
                if (!said.isNullOrBlank()) askSol(said, voice = true)
            }
        }
    }

    fun stopListening() {
        ears?.stop()
        listening = false
        renderMic()
    }

    val isListening: Boolean get() = listening

    /** A question from Today goes to the Sol chat, where answers and confirmation cards live. */
    fun askSol(text: String, voice: Boolean) {
        host.select(MainActivity.Tab.SOL, animate = true)
        (host.screen(MainActivity.Tab.SOL) as? SolScreen)?.askFromToday(text, voice)
    }

    private fun renderMic() {
        if (!this::micHint.isInitialized) return
        micHint.text = ctx.getString(if (listening) R.string.today_listening else R.string.today_mic_hint)
        micHint.setTextColor(if (listening) Ui.GOLD else Ui.withAlpha(Color.WHITE, 0xCC))
        ringAnim?.cancel()
        ringAnim = null
        if (listening && ValueAnimator.areAnimatorsEnabled()) {
            ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    micRing.alpha = 1f - f
                    micRing.scaleX = 0.8f + 0.35f * f
                    micRing.scaleY = 0.8f + 0.35f * f
                }
                start()
            }
        } else {
            micRing.alpha = if (listening) 1f else 0f
            micRing.scaleX = 1f; micRing.scaleY = 1f
        }
    }

    // ------------------------------------------------------------------ secretary

    private fun secretaryCard(): View = Ui.card(ctx, accent = Ui.PURPLE).apply {
        tag = "today-secretary"
        val head = Ui.row(ctx, gap = 12).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { host.openCalls() }
        }
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_call, Ui.PURPLE, 40))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.today_sec_title)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }))
        secPill = Ui.tappable(Ui.pill(ctx, "", Ui.GREEN)).apply { tag = "today-sec-state" }
        head.addView(secPill)
        addView(head)

        val stats = Ui.row(ctx, gap = 8).apply { tag = "today-sec-stats" }
        fun stat(label: Int, color: Int): TextView {
            val box = Ui.column(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = Ui.rounded(Ui.withAlpha(Color.WHITE, 0x0A), dp(16).toFloat(), Ui.withAlpha(Color.WHITE, 0x14), dp(1))
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            val n = Ui.display(ctx, "0", 22f, color)
            box.addView(n)
            box.addView(Ui.top(Ui.text(ctx, ctx.getString(label), 11f, Ui.MUTED, 700).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, 4))
            stats.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return n
        }
        statAnswered = stat(R.string.today_sec_handled, Ui.GREEN)
        statMissed = stat(R.string.today_sec_missed, Ui.AMBER)
        statBlocked = stat(R.string.today_sec_blocked, Ui.RED)
        addView(Ui.top(stats, 14))

        latestLabel = Ui.label(ctx, "")
        addView(Ui.top(latestLabel, 16))
        latestBox = Ui.column(ctx, gap = 8).apply { tag = "today-sec-latest" }
        addView(Ui.top(latestBox, 8))

        val buttons = Ui.row(ctx, gap = 10)
        buttons.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.today_sec_inbox), Ui.Btn.SECONDARY) { host.openCalls() }.apply { tag = "today-sec-inbox" }))
        buttons.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.today_sec_try), Ui.Btn.PRIMARY, R.drawable.ic_call) {
            CallsActivity.trySecretary(host)
        }.apply { tag = "today-sec-try" }))
        addView(Ui.top(buttons, 14))
    }

    private fun callRow(c: CallItem): View = Ui.column(ctx).apply {
        tag = "today-call"
        contentDescription = ctx.getString(R.string.today_call_open)
        background = Ui.ripple(Ui.rounded(Ui.withAlpha(Color.WHITE, 0x08), dp(16).toFloat(), Ui.withAlpha(Color.WHITE, 0x12), dp(1)), dp(16).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        setOnClickListener { CallsActivity.openCall(host, c.key) }
        val top = Ui.row(ctx, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
        val dot = when {
            c.blocked -> Ui.RED
            c.missed -> Ui.AMBER
            else -> Ui.GREEN
        }
        top.addView(View(ctx).apply { background = Ui.rounded(dot, dp(4).toFloat()) }, LinearLayout.LayoutParams(dp(8), dp(8)))
        top.addView(Ui.weight(Ui.text(ctx, c.who.ifBlank { ctx.getString(R.string.calls_unknown) }, 15f, Ui.TEXT, 800).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }))
        top.addView(Ui.text(ctx, CallText.time(c.at), 12f, Ui.MUTED, 700))
        addView(top)
        addView(Ui.top(Ui.text(ctx, CallText.summary(ctx, c), 13.5f, Ui.withAlpha(Ui.TEXT, 0xDD), 500).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f)
        }, 6))
    }

    // ------------------------------------------------------------------ 1.1.0 morning briefing

    private fun briefingCard(): View = Ui.card(ctx, accent = Ui.GOLD, pad = 16).apply {
        tag = "today-briefing"
        briefingBody = Ui.column(ctx)
        addView(briefingBody)
    }

    private fun renderBriefing() {
        val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
        val pol = st.policy()
        briefingBody.removeAllViews()
        val head = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_timer, Ui.GOLD, 40))
        head.addView(Ui.weight(Ui.column(ctx).apply {
            addView(Ui.h2(ctx, ctx.getString(R.string.br_title)))
            addView(Ui.text(ctx, if (pol.enabled) ctx.getString(R.string.br_at, pol.label) else ctx.getString(R.string.br_off), 12.5f, if (pol.enabled) Ui.GOLD else Ui.MUTED, 700).apply {
                tag = "today-briefing-time"
                isClickable = true
                setOnClickListener { pickBriefingTime() }
            })
        }))
        head.addView(android.widget.Switch(ctx).apply {
            tag = "today-briefing-switch"
            isChecked = pol.enabled
            contentDescription = ctx.getString(R.string.br_title)
            setOnCheckedChangeListener { _, on -> st.setPolicy(pol.copy(enabled = on)); net.solardepin.solarchik.sol.Briefing.schedule(ctx); if (on) host.requestNotifications(fromUser = true); render() }
        })
        briefingBody.addView(head)
        val last = st.lastText
        if (last.isNotBlank()) briefingBody.addView(Ui.top(Ui.muted(ctx, last, 13f).apply { tag = "today-briefing-text"; setLineSpacing(0f, 1.25f); maxLines = if (briefingPlaying) 30 else 3; ellipsize = TextUtils.TruncateAt.END }, 10))
        val label = when {
            briefingBusy -> ctx.getString(R.string.br_preparing)
            briefingPlaying -> ctx.getString(R.string.br_stop)
            else -> ctx.getString(R.string.br_play)
        }
        val b = Ui.button(ctx, label, Ui.Btn.PRIMARY) { if (briefingPlaying) stopBriefing() else playBriefing() }.apply { tag = "today-briefing-play" }
        Ui.setEnabled(b, !briefingBusy)
        briefingBody.addView(Ui.top(b, 12))
    }

    private fun pickBriefingTime() {
        val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
        val pol = st.policy()
        android.app.TimePickerDialog(ctx, { _, h, m ->
            st.setPolicy(pol.copy(enabled = true, hour = h, minute = m))
            net.solardepin.solarchik.sol.Briefing.schedule(ctx)
            render()
        }, pol.hour, pol.minute, true).show()
    }

    /** Builds the facts from this phone, gets Sol's text (worker; local template offline) and speaks it. */
    fun playBriefing() {
        if (briefingBusy) return
        briefingBusy = true
        render()
        host.scope.launch {
            val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
            val w = host.wallet
            val now = System.currentTimeMillis()
            var sol: Double? = balance
            var skrNow: Double? = skr
            if (w.connected) {
                w.balanceSol().onSuccess { sol = it; balance = it; host.walletSol = it }
                if (w.mainnet) withContext(Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetch(w.address) }.onSuccess { skrNow = it; skr = it; host.walletSkr = it }
            }
            val zone = java.time.ZoneId.systemDefault()
            val facts = net.solardepin.solarchik.sol.Briefing.facts(
                CallInbox.cached(ctx), FollowUps.list(ctx, now),
                net.solardepin.solarchik.screen.CallActionStore(ctx).open().map { net.solardepin.solarchik.sol.AssistantExtras.actionLine(it) + " (needs your confirmation)" },
                net.solardepin.solarchik.agents.WatcherStore(ctx).recent(now),
                net.solardepin.solarchik.sol.AssistantContext.Wallet(w.connected, w.isLocal, if (w.connected) w.address else "", w.mainnet, sol.takeIf { w.connected }, skrNow.takeIf { w.connected && w.mainnet }),
                st.snap(), net.solardepin.solarchik.season.SeasonStore.planFor(ctx, host.save, w.mainnet), now, zone,
            )
            val text = withContext(Dispatchers.IO) { net.solardepin.solarchik.sol.Briefing.fetch(facts, host.lang, briefingPost) }
                ?: net.solardepin.solarchik.sol.Briefing.localText(facts, ctx)
            st.lastText = text
            st.playedAt = now
            st.pendingDay = ""
            if (w.connected && sol != null) st.setSnap(net.solardepin.solarchik.sol.WalletSnap(now, sol, skrNow))
            briefingBusy = false
            briefingPlaying = speak(text, host.lang)
            if (!briefingPlaying) host.toast(ctx.getString(R.string.chat_tts_missing))
            render()
        }
    }

    private fun stopBriefing() {
        voice?.stop()
        briefingPlaying = false
        render()
    }

    // ------------------------------------------------------------------ 1.1.0 actions from calls

    private fun actionsCard(): View = Ui.column(ctx, gap = 10).apply {
        tag = "today-actions"
        actionsWrap = this
        addView(Ui.label(ctx, ctx.getString(R.string.ca_title)))
        actionsBox = Ui.column(ctx, gap = 10)
        addView(actionsBox)
    }

    private fun renderActions() {
        val open = net.solardepin.solarchik.screen.CallActionStore(ctx).open().takeLast(4).reversed()
        actionsWrap.visibility = if (open.isEmpty()) View.GONE else View.VISIBLE
        actionsBox.removeAllViews()
        open.forEach { actionsBox.addView(CallActionCards.card(host, it) { render() }) }
    }

    /** Looks at new answered calls for requests (worker; local rules offline). At most once a minute. */
    internal fun syncActions(post: (String, String) -> Pair<Int, String> = briefingPost) {
        val now = System.currentTimeMillis()
        if (now - actionsSynced < 60_000L) return
        actionsSynced = now
        val calls = CallInbox.cached(ctx)
        host.scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { net.solardepin.solarchik.screen.CallActionSync.run(ctx, calls, host.lang, post = post) }.getOrDefault(emptyList()) }
            if (found.isNotEmpty()) render()
        }
    }

    /** A reminder notification was tapped: show its card. */
    fun focusAction(id: String) {
        render()
        actionsWrap.post { actionsWrap.parent?.requestChildFocus(actionsWrap, actionsWrap); actionsWrap.requestRectangleOnScreen(android.graphics.Rect(0, 0, actionsWrap.width, actionsWrap.height), false) }
    }

    // ------------------------------------------------------------------ follow-ups

    private fun todoCard(): View = Ui.card(ctx).apply {
        tag = "today-todos"
        val head = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_timer, Ui.AMBER, 40))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.today_todo_title))))
        todoCount = Ui.pill(ctx, "0", Ui.AMBER)
        head.addView(todoCount)
        addView(head)
        todoBox = Ui.column(ctx, gap = 4)
        addView(Ui.top(todoBox, 12))
    }

    private fun todoRow(f: FollowUp): View = Ui.row(ctx, gap = 12).apply {
        tag = "today-todo"
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        val check = FrameLayout(ctx).apply {
            tag = "today-todo-done"
            contentDescription = ctx.getString(R.string.today_todo_done)
            isClickable = true
            background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Ui.withAlpha(Ui.AMBER, 0xAA)) }, dp(14).toFloat())
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                FollowUps.markDone(ctx, f)
                host.toast(ctx.getString(R.string.today_todo_done_toast))
                render()
            }
        }
        addView(FrameLayout(ctx).apply {
            addView(check, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val who = f.item.who.ifBlank { f.item.callback.ifBlank { ctx.getString(R.string.calls_unknown) } }
        val col = Ui.column(ctx).apply {
            isClickable = true
            setOnClickListener { CallsActivity.openCall(host, f.item.key) }
        }
        col.addView(Ui.text(ctx, ctx.getString(R.string.today_todo_callback, who), 15f, Ui.TEXT, 800).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val sub = when (f.kind) {
            FollowUp.Kind.REMINDER -> ctx.getString(R.string.today_todo_reminder, CallText.time(f.at))
            FollowUp.Kind.CALLBACK -> f.item.intent.ifBlank { CallText.summary(ctx, f.item) } + " · " + CallText.time(f.item.at)
        }
        col.addView(Ui.top(Ui.text(ctx, sub, 12.5f, if (f.kind == FollowUp.Kind.REMINDER) Ui.GOLD else Ui.MUTED, 600).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, 3))
        addView(Ui.weight(col))
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(Ui.MUTED) }, LinearLayout.LayoutParams(dp(18), dp(18)))
    }

    // ------------------------------------------------------------------ wallet

    private fun walletCard(): View = Ui.card(ctx, accent = Ui.GOLD).apply {
        tag = "today-wallet"
        val head = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_wallet, Ui.GOLD, 40))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(if (host.wallet.mainnet) R.string.mn_today_wallet_title else R.string.today_wallet_title))))
        head.addView(Ui.pill(ctx, ctx.getString(if (host.wallet.mainnet) R.string.network_mainnet else R.string.today_devnet), if (host.wallet.mainnet) Ui.GREEN else Ui.CYAN).apply { tag = "today-cluster" })
        addView(head)
        walletBody = Ui.column(ctx)
        addView(Ui.top(walletBody, 12))
    }

    private fun renderWallet() {
        walletBody.removeAllViews()
        val w = host.wallet
        if (!w.connected) {
            walletBody.addView(Ui.muted(ctx, ctx.getString(if (w.mainnet) R.string.mn_today_wallet_none else R.string.today_wallet_none), 13.5f).apply { setLineSpacing(0f, 1.25f) })
            // 1.1.5: on mainnet this is the user's own wallet app (MWA connect), never "create a wallet"
            walletBody.addView(Ui.top(Ui.button(ctx, ctx.getString(if (w.mainnet) R.string.mn_today_wallet_connect else R.string.today_wallet_setup), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { setupWallet() }.apply { tag = "today-wallet-setup" }, 14))
            return
        }
        val bal = balance
        walletBody.addView(Ui.display(ctx, when {
            bal != null -> Fmt.sol(bal) + " SOL"
            balanceFailed -> "— SOL"
            else -> "… SOL"
        }, 28f, Ui.TEXT).apply { tag = "today-wallet-balance" })
        val kind = ctx.getString(if (w.isLocal) R.string.today_wallet_builtin else R.string.today_wallet_app)
        val sub = when {
            bal == null && balanceFailed -> ctx.getString(R.string.today_wallet_balance_off)
            bal == null -> ctx.getString(R.string.today_wallet_balance_loading)
            else -> kind + " · " + Fmt.short(w.address)
        }
        walletBody.addView(Ui.top(Ui.muted(ctx, sub, 12.5f), 4))
        if (w.mainnet) walletBody.addView(Ui.top(Ui.text(ctx, skr?.let { Fmt.sol(it, 2) + " SKR" } ?: "… SKR", 16f, Ui.TEXT, 800).apply { tag = "today-wallet-skr" }, 6))

        val desk = host.desk.state()
        val last = desk.log.lastOrNull() ?: desk.runs.mapNotNull { it.last }.maxByOrNull { it.at }
        walletBody.addView(Ui.top(infoRow(R.string.today_wallet_did, last?.let { DeskText.event(ctx, it) + " · " + Fmt.clock(it.at) } ?: ctx.getString(R.string.today_wallet_did_none), "today-wallet-did"), 14))
        val pending = (host.screen(MainActivity.Tab.SOL) as? SolScreen)?.pendingTitle()
        walletBody.addView(Ui.top(infoRow(R.string.today_wallet_next, pending?.let { ctx.getString(R.string.today_wallet_next_pending, it) } ?: ctx.getString(R.string.today_wallet_next_none), "today-wallet-next", pending != null), 8))

        val buttons = Ui.row(ctx, gap = 10)
        buttons.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.today_wallet_agents), Ui.Btn.SECONDARY, R.drawable.ic_nav_agents) {
            host.select(MainActivity.Tab.AGENTS, animate = true)
        }.apply { tag = "today-wallet-agents" }))
        buttons.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.today_wallet_explorer), Ui.Btn.GHOST, R.drawable.ic_open) {
            host.openUrl(host.explorerAddress(w.address, w.clusterName))
        }.apply { tag = "today-wallet-explorer" }))
        walletBody.addView(Ui.top(buttons, 14))
    }

    private fun infoRow(label: Int, value: String, tagName: String, highlight: Boolean = false): View = Ui.column(ctx).apply {
        tag = tagName
        background = Ui.rounded(Ui.withAlpha(if (highlight) Ui.GOLD else Color.WHITE, if (highlight) 0x18 else 0x08), dp(14).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(Ui.label(ctx, ctx.getString(label), if (highlight) Ui.GOLD else Ui.MUTED))
        addView(Ui.top(Ui.text(ctx, value, 13.5f, Ui.TEXT, 600).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f) }, 4))
    }

    private fun setupWallet() {
        val w = host.wallet
        val started = host.current
        host.scope.launch {
            if (w.hasWalletApp()) {
                w.connect(host.sender)
                    .onSuccess { if (w.connected) host.toast(ctx.getString(R.string.mn_wallet_connected, Fmt.short(w.address))) }
                    .onFailure { host.walletFailed(started, it) }
            } else {
                host.setupBuiltInWallet()
            }
            balanceAt = 0L
            refreshBalance()
            host.renderAll()
        }
    }

    private fun refreshBalance(force: Boolean = false) {
        val w = host.wallet
        if (!w.connected || !MainActivity.tickerEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (!force && balanceAt != 0L && now - balanceAt < 30_000) return
        balanceAt = now
        host.scope.launch {
            val r = withContext(Dispatchers.IO) { w.balanceSol() }
            r.onSuccess { balance = it; balanceFailed = false; host.walletSol = it }.onFailure { balanceFailed = balance == null }
            // 1.1.0: real SKR on mainnet next to SOL (read-only)
            if (w.mainnet) withContext(Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetch(w.address) }.onSuccess { skr = it; host.walletSkr = it }
            if (this@TodayScreen::walletBody.isInitialized) renderWallet()
        }
    }

    /** Tests: a known balance without the network. */
    internal fun setBalanceForTest(sol: Double?, skrBalance: Double? = null) { balance = sol; balanceFailed = false; skr = skrBalance }
    private var skr: Double? = null

    // ------------------------------------------------------------------ Seeker Season

    private fun seasonCard(): View = Ui.card(ctx, accent = Ui.CYAN, pad = 16).apply {
        tag = "today-season"
        isClickable = true
        contentDescription = ctx.getString(R.string.season_card_open)
        foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(22).toFloat(), 0x22FFFFFF)
        setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); host.select(MainActivity.Tab.SEASON, animate = true) }
        val head = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_check, Ui.CYAN, 40))
        val col = Ui.column(ctx)
        col.addView(Ui.text(ctx, ctx.getString(R.string.season_title), 16f, Ui.TEXT, 800))
        seasonSub = Ui.text(ctx, "", 12.5f, Ui.MUTED, 600).apply { tag = "today-season-sub"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(Ui.top(seasonSub, 2))
        head.addView(Ui.weight(col))
        head.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(Ui.MUTED) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(head)
        seasonChecks = Ui.row(ctx, gap = 8)
        addView(Ui.top(seasonChecks, 12))
    }

    private fun renderSeason() {
        val p = net.solardepin.solarchik.season.SeasonStore.planFor(ctx, host.save, host.wallet.mainnet)
        seasonSub.text = ctx.getString(R.string.season_card_sub, p.doneCount, p.total)
        seasonChecks.removeAllViews()
        listOf(
            net.solardepin.solarchik.season.SeasonItem.DAILY_USE to R.string.season_chip_use,
            net.solardepin.solarchik.season.SeasonItem.EXPLORE to R.string.season_chip_explore,
            net.solardepin.solarchik.season.SeasonItem.ONCHAIN to R.string.season_chip_chain,
        ).forEach { (item, label) ->
            val done = p.done(item)
            val c = if (done) Ui.GREEN else Ui.MUTED
            seasonChecks.addView(Ui.text(ctx, (if (done) "✓ " else "○ ") + ctx.getString(label), 11.5f, c, 700).apply {
                tag = "today-season-" + item.name.lowercase()
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = Ui.rounded(Ui.withAlpha(c, 0x16), dp(12).toFloat())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    // ------------------------------------------------------------------ habits

    private fun habitRow(): View = Ui.row(ctx, gap = 10).apply {
        gravity = Gravity.TOP
        val check = habitTile("today-checkin", R.drawable.ic_flame, Ui.AMBER, R.string.today_checkin_title) { openCheckIn() }
        checkStreak = check.second
        checkState = check.third
        addView(check.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val play = habitTile("today-play", R.drawable.ic_nav_run, Ui.GREEN, R.string.today_play_title) { host.playGame() }
        playSub = play.third
        play.second.visibility = View.GONE
        addView(play.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun habitTile(tagName: String, icon: Int, color: Int, title: Int, onTap: () -> Unit): Triple<View, TextView, TextView> {
        val tile = Ui.card(ctx, accent = color, pad = 16).apply {
            tag = tagName
            isClickable = true
            foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(22).toFloat(), 0x22FFFFFF)
            setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onTap() }
        }
        tile.addView(Ui.iconBadge(ctx, icon, color, 36))
        tile.addView(Ui.top(Ui.text(ctx, ctx.getString(title), 15f, Ui.TEXT, 800).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, 10))
        val big = Ui.text(ctx, "", 13f, color, 800).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        tile.addView(Ui.top(big, 3))
        val small = Ui.text(ctx, "", 12f, Ui.MUTED, 600).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        tile.addView(Ui.top(small, 3))
        return Triple(tile, big, small)
    }

    private fun openCheckIn() {
        val save = host.save
        host.select(MainActivity.Tab.SHIFT, animate = true)
        val shift = host.screen(MainActivity.Tab.SHIFT) as? YardScreen ?: return
        if (save.checkInOpen() && !save.signedToday()) shift.signFromRun() else shift.focusToday()
    }

    // ------------------------------------------------------------------ render

    override fun onShow() {
        render()
        refreshBalance()
        syncActions()
        // 1.1.0: a briefing posted this morning and not heard yet plays once when Today opens.
        val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
        if (st.policy().enabled && st.pendingDay == net.solardepin.solarchik.sol.Briefing.today()) playBriefing()
    }

    override fun onHide() {
        if (listening) stopListening()
        ringAnim?.cancel()
    }

    override fun onDestroy() {
        ears?.stop()
        ears = null
        voice?.shutdown()
        voice = null
    }

    override fun render() {
        if (!this::greeting.isInitialized) return
        val now = System.currentTimeMillis()
        val loc = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        dateLabel.text = SimpleDateFormat("EEEE, d MMMM", loc).format(Date(now))
        greeting.text = ctx.getString(greetingFor(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)))

        val calls = CallInbox.cached(ctx)
        val today = AssistantRules.today(calls, now)
        val todos = FollowUps.list(ctx, now)
        val parts = ArrayList<String>()
        val real = today.count { !it.blocked }
        if (real > 0) parts += ctx.resources.getQuantityString(R.plurals.today_summary_calls, real, real)
        if (todos.isNotEmpty()) parts += ctx.resources.getQuantityString(R.plurals.today_summary_followups, todos.size, todos.size)
        summary.text = if (parts.isEmpty()) ctx.getString(R.string.today_summary_quiet) else parts.joinToString(" · ")

        solLine.text = solLineFor(today, todos)
        renderMic()
        renderSecretary(calls, today)
        renderBriefing()
        renderActions()
        renderTodos(todos)
        renderSeason()
        renderWallet()
        renderHabits()
    }

    private fun solLineFor(today: List<CallItem>, todos: List<FollowUp>): String {
        val seen = CallInbox.seenAt(ctx)
        val fresh = today.firstOrNull { !it.blocked && it.at > seen && it.status != CallInbox.PENDING }
        return when {
            fresh != null -> ctx.getString(R.string.today_line_call, fresh.who.ifBlank { ctx.getString(R.string.calls_unknown) }, kyivClock(fresh.at), CallText.summary(ctx, fresh).trim())
            todos.isNotEmpty() -> ctx.resources.getQuantityString(R.plurals.today_line_todo, todos.size, todos.size)
            host.save.signedToday() -> ctx.getString(R.string.today_line_signed)
            else -> ctx.getString(R.string.today_line_idle)
        }
    }

    private fun renderSecretary(calls: List<CallItem>, today: List<CallItem>) {
        val sec = net.solardepin.solarchik.screen.Secretary
        val sup = sec.supported()
        val on = sup && net.solardepin.solarchik.screen.PlayerIds.screeningOn(ctx) && sec.holdsRole(ctx)
        secPill.text = ctx.getString(if (on) R.string.today_sec_on else R.string.today_sec_setup)
        val c = if (on) Ui.GREEN else Ui.AMBER
        secPill.setTextColor(c)
        secPill.background = Ui.rounded(Ui.withAlpha(c, 0x22), dp(99).toFloat(), Ui.withAlpha(c, 0x66), dp(1))
        secPill.setOnClickListener {
            if (on) host.openCalls()
            else {
                host.select(MainActivity.Tab.SETTINGS, animate = true)
                (host.screen(MainActivity.Tab.SETTINGS) as? SettingsScreen)?.focusSecretary()
            }
        }
        statAnswered.text = Fmt.count(today.count { it.answered })
        statMissed.text = Fmt.count(today.count { it.missed })
        statBlocked.text = Fmt.count(today.count { it.blocked })

        val show = (if (today.isNotEmpty()) today else calls).filter { !it.blocked }.take(2)
        latestLabel.text = ctx.getString(if (today.isNotEmpty()) R.string.today_sec_latest_today else R.string.today_sec_latest).uppercase()
        latestLabel.visibility = if (show.isEmpty()) View.GONE else View.VISIBLE
        latestBox.removeAllViews()
        if (show.isEmpty()) latestBox.addView(Ui.muted(ctx, ctx.getString(R.string.today_sec_empty), 13f).apply { setLineSpacing(0f, 1.25f) })
        else show.forEach { latestBox.addView(callRow(it)) }
    }

    private fun renderTodos(todos: List<FollowUp>) {
        todoCount.text = Fmt.count(todos.size)
        todoCount.visibility = if (todos.isEmpty()) View.GONE else View.VISIBLE
        todoBox.removeAllViews()
        if (todos.isEmpty()) {
            todoBox.addView(Ui.muted(ctx, ctx.getString(R.string.today_todo_empty), 13f).apply { setLineSpacing(0f, 1.25f) })
            return
        }
        todos.take(MAX_TODOS).forEach { todoBox.addView(todoRow(it)) }
        if (todos.size > MAX_TODOS) todoBox.addView(Ui.text(ctx, ctx.getString(R.string.today_todo_more, todos.size - MAX_TODOS), 13f, Ui.CYAN, 800).apply {
            tag = "today-todo-more"
            minHeight = dp(Ui.TAP_MIN_DP)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { host.openCalls() }
        })
    }

    private fun renderHabits() {
        val save = host.save
        val st = save.liveStreak().streak
        checkStreak.text = if (st <= 0) ctx.getString(R.string.today_checkin_none) else ctx.resources.getQuantityString(R.plurals.today_checkin_streak, st, st)
        checkState.text = when {
            save.signedToday() -> ctx.getString(R.string.today_checkin_signed)
            save.checkInOpen() -> ctx.getString(R.string.today_checkin_sign)
            else -> ctx.getString(R.string.today_checkin_run, GameSave.GOAL_M)
        }
        checkState.setTextColor(when {
            save.signedToday() -> Ui.GREEN
            save.checkInOpen() -> Ui.GOLD
            else -> Ui.MUTED
        })
        playSub.text = if (save.bestDistance <= 0) ctx.getString(R.string.today_play_new) else ctx.getString(R.string.today_play_sub, save.bestDistance)
    }

    /** HH:mm in the secretary's time zone, like every other call time in the app. */
    private fun kyivClock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = CallText.KYIV }.format(Date(ms))

    companion object {
        /** Tests: scripted worker replies for the briefing and call actions. */
        @androidx.annotation.VisibleForTesting
        var postOverride: ((String, String) -> Pair<Int, String>)? = null
        /** Unit tests never reach the live worker unless they script it (the local fallbacks run instead). */
        private val defaultPost: (String, String) -> Pair<Int, String> =
            if (android.os.Build.FINGERPRINT == "robolectric") { _, _ -> 0 to "" } else net.solardepin.solarchik.sol.Briefing::httpPost
        const val HOLD_MS = 350L
        const val MAX_TODOS = 3

        fun greetingFor(hour: Int): Int = when (hour) {
            in 5..11 -> R.string.today_morning
            in 12..16 -> R.string.today_afternoon
            in 17..22 -> R.string.today_evening
            else -> R.string.today_night
        }
    }
}
