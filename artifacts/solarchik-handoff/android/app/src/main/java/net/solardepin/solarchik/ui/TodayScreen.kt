package net.solardepin.solarchik.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.FollowUps
import net.solardepin.solarchik.stack.Habits
import net.solardepin.solarchik.stack.MorningStack
import net.solardepin.solarchik.stack.StackItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 1.2.7 Today = the Morning stack, full screen (redesign 01–03). One card at a time: call-backs and reminders,
 * payments, habits, then the Season task. Swipe right = do it / done, left = later; every swipe also has a button.
 * A payment swipe only opens the wallet approval. When the stack is clear after you worked it today: Clocked in,
 * with the streak and the run. Clocking in never costs money (the Solana signature is an optional extra).
 */
class TodayScreen(host: MainActivity) : Screen(host) {
    private lateinit var root: FrameLayout
    private lateinit var dateLabel: TextView
    private lateinit var greeting: TextView
    private lateinit var streakPill: TextView
    private lateinit var progressRow: LinearLayout
    private lateinit var segments: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var deckBox: FrameLayout
    private lateinit var hints: LinearLayout
    private lateinit var hintMid: TextView
    private lateinit var playTile: View
    private lateinit var playSub: TextView
    private lateinit var tintL: View
    private lateinit var tintR: View
    private lateinit var colView: LinearLayout
    private var deck: DeckView? = null
    private var shown: List<StackItem> = emptyList()

    private var voice: net.solardepin.solarchik.sol.SolVoice? = null
    internal var briefingBusy = false
    internal var briefingPlaying = false
    /** Tests: the worker call (POST) and the voice are scripted. */
    internal var briefingPost: (String, String) -> Pair<Int, String> = { url, body -> (postOverride ?: defaultPost)(url, body) }
    internal var speak: (String, String) -> Boolean = { text, lang -> (voice ?: net.solardepin.solarchik.sol.SolVoice(host).also { v ->
        voice = v
        v.onIdle = { n -> briefingPlaying = false; if (n == 0) host.toast(ctx.getString(R.string.sol_voice_failed)); host.renderAll() }
    }).speak(text, lang) }
    private var actionsSynced = 0L

    override fun build(): View {
        root = FrameLayout(ctx).apply { tag = "today" }
        tintL = View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.withAlpha(Color.parseColor("#7E93A6"), 0x55), Color.TRANSPARENT)); alpha = 0f }
        tintR = View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(Ui.withAlpha(Ui.GREEN, 0x55), Color.TRANSPARENT)); alpha = 0f }
        root.addView(tintL, FrameLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        root.addView(tintR, FrameLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        colView = Ui.column(ctx).apply { clipChildren = false; clipToPadding = false }
        root.addView(colView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        colView.addView(header())
        colView.addView(progress(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(16) })
        deckBox = FrameLayout(ctx).apply { tag = "today-deck"; clipChildren = false; clipToPadding = false }
        colView.addView(deckBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        colView.addView(hintsRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(2) })
        colView.addView(playTile().also { playTile = it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(8) })
        applyInsets()
        return root
    }

    override fun applyInsets() {
        if (!this::colView.isInitialized) return
        colView.setPadding(dp(18), host.topInset + dp(12), dp(18), host.bottomInset + dp(108))
    }

    // ------------------------------------------------------------------ header + progress

    private fun header(): View = Ui.row(ctx, gap = 12).apply {
        tag = "today-header"
        gravity = Gravity.CENTER_VERTICAL
        addView(FrameLayout(ctx).apply {
            background = Ui.rounded(Kit.S1, dp(16).toFloat(), Kit.HAIR, dp(1))
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_launcher) }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles = Ui.column(ctx)
        dateLabel = Ui.text(ctx, "", 15f, Kit.MUTED, 600).apply { maxLines = 1 }
        titles.addView(dateLabel)
        greeting = Ui.display(ctx, "", 26f).apply {
            maxLines = 1; tag = "today-greeting"
            setAutoSizeTextTypeUniformWithConfiguration(18, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        titles.addView(greeting, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(2) })
        addView(Ui.weight(titles))
        streakPill = Kit.chip(ctx, "0", Ui.GOLD, R.drawable.lc_flame, size = 17f).apply {
            tag = "today-streak"
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = Ui.rounded(Ui.withAlpha(Ui.GOLD, 0x1C), dp(18).toFloat(), Ui.withAlpha(Ui.GOLD, 0x55), dp(1))
        }
        addView(streakPill)
    }

    private fun progress(): View = Ui.row(ctx, gap = 12).apply {
        progressRow = this
        gravity = Gravity.CENTER_VERTICAL
        segments = Ui.row(ctx, gap = 6)
        addView(segments, LinearLayout.LayoutParams(0, dp(6), 1f))
        progressText = Ui.text(ctx, "", 15f, Ui.TEXT, 800).apply { tag = "stack-progress"; maxLines = 1 }
        addView(progressText)
        addView(Kit.chip(ctx, ctx.getString(R.string.stack_read), Ui.TEXT, R.drawable.lc_vol, fill = false, size = 15f).apply {
            tag = "stack-voice"
            minHeight = dp(44)
            setPadding(dp(14), 0, dp(16), 0)
            background = Ui.ripple(Ui.rounded(Kit.S1, dp(22).toFloat(), Kit.HAIR, dp(1)), dp(22).toFloat())
            isClickable = true
            setOnClickListener { Kit.haptic(it, "tick"); readStack() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
    }

    private fun renderSegments(done: Int, total: Int) {
        segments.removeAllViews()
        val n = total.coerceAtMost(8)
        for (i in 0 until n) {
            val c = if (i < done) Ui.GOLD else if (i == done) Ui.withAlpha(Ui.GOLD, 0xAA) else Ui.withAlpha(Color.WHITE, 0x1E)
            segments.addView(View(ctx).apply { background = Ui.rounded(c, dp(3).toFloat()) }, LinearLayout.LayoutParams(0, dp(5), 1f))
        }
    }

    private fun hintsRow(): View = Ui.row(ctx).apply {
        hints = this
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.text(ctx, "← " + ctx.getString(R.string.stack_later), 15f, Kit.MUTED, 800).apply {
            tag = "stack-later"; minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(10), 0)
            isClickable = true; setOnClickListener { deck?.commit(-1) ?: Unit }
        })
        hintMid = Ui.text(ctx, ctx.getString(R.string.stack_hint_short), 15f, Kit.MUTED, 700).apply { tag = "stack-hint"; gravity = Gravity.CENTER; maxLines = 1 }
        addView(Ui.weight(hintMid))
        addView(Ui.text(ctx, ctx.getString(R.string.stack_hint_done) + " →", 15f, Ui.GREEN, 800).apply {
            tag = "stack-done"; minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(4), 0)
            isClickable = true; setOnClickListener { deck?.commit(1) ?: Unit }
        })
    }

    private fun playTile(): View = Ui.row(ctx, gap = 14).apply {
        tag = "today-play"
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.ripple(Ui.rounded(Kit.S1, dp(24).toFloat(), Kit.HAIR, dp(1)), dp(24).toFloat())
        setPadding(dp(12), dp(8), dp(10), dp(8))
        isClickable = true
        setOnClickListener { Kit.haptic(it, "tick"); host.playGame() }
        addView(Ui.image(ctx, R.drawable.run_bot_stock), LinearLayout.LayoutParams(dp(56), dp(60)))
        val col = Ui.column(ctx)
        col.addView(Ui.text(ctx, ctx.getString(R.string.play_title), 17f, Ui.TEXT, 800).apply { maxLines = 1 })
        playSub = Ui.text(ctx, "", 14f, Kit.MUTED, 600).apply { maxLines = 1 }
        col.addView(Ui.top(playSub, 2))
        addView(Ui.weight(col))
        addView(Kit.chip(ctx, ctx.getString(R.string.play_btn), Ui.GOLD, R.drawable.lc_play, size = 17f).apply {
            tag = "today-play-btn"
            setPadding(dp(18), dp(12), dp(20), dp(12))
            background = Ui.ripple(Ui.rounded(Ui.withAlpha(Ui.GOLD, 0x1E), dp(24).toFloat()), dp(24).toFloat())
            isClickable = true
            setOnClickListener { Kit.haptic(it, "tick"); host.playGame() }
        })
    }

    // ------------------------------------------------------------------ render

    override fun render() {
        if (!this::greeting.isInitialized) return
        val now = System.currentTimeMillis()
        val loc = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        dateLabel.text = SimpleDateFormat("EEEE, MMM d", loc).format(Date(now)).let { if (net.solardepin.solarchik.core.AppLocale.isUk(loc)) SimpleDateFormat("EEEE, d MMMM", loc).format(Date(now)) else it }
        greeting.text = ctx.getString(greetingFor(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)))
        val streak = MorningStack.streak(ctx, now)
        streakPill.text = Fmt.count(streak)
        streakPill.contentDescription = ctx.getString(R.string.streak_desc, streak)
        val best = host.save.bestDistance
        playSub.text = if (best > 0) ctx.getString(R.string.play_best, best) else ctx.getString(R.string.play_first)

        val items = MorningStack.deck(ctx, now)
        shown = items
        deckBox.removeAllViews()
        deck = null
        if (items.isEmpty()) {
            val clocked = MorningStack.settle(ctx, now)
            val st = MorningStack.streak(ctx, now) // after settle: today's clock-in counts
            streakPill.text = Fmt.count(st)
            streakPill.contentDescription = ctx.getString(R.string.streak_desc, st)
            progressRow.visibility = View.GONE
            hints.visibility = View.GONE
            playTile.visibility = if (clocked) View.GONE else View.VISIBLE
            deckBox.addView(if (clocked) clockedView(st) else emptyView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        progressRow.visibility = View.VISIBLE
        hints.visibility = View.VISIBLE
        playTile.visibility = View.VISIBLE
        val handled = handledToday(now)
        val total = handled + items.size
        renderSegments(handled, total)
        progressText.text = ctx.getString(R.string.stack_progress, handled + 1, total)
        val d = DeckView(ctx)
        val views = items.take(3).mapIndexed { i, it -> cardFor(it, i == 0).also { v -> if (i > 0) peekTags(v) } }
        d.setCards(views)
        val topItem = items.first()
        d.rightSpringsBack = topItem is StackItem.Call && (topItem.a.type == CallAction.PAYMENT || topItem.a.type == CallAction.OWED)
        d.stampRight = (views.first() as? ViewGroup)?.findViewWithTag("stamp-right")
        d.stampLeft = (views.first() as? ViewGroup)?.findViewWithTag("stamp-left")
        d.onProgress = { p ->
            tintR.alpha = p.coerceIn(0f, 1f); tintL.alpha = (-p).coerceIn(0f, 1f)
            hintMid.text = when {
                p >= 1f -> ctx.getString(if (d.rightSpringsBack) R.string.stack_release_settle else R.string.stack_release_done)
                p <= -1f -> ctx.getString(R.string.stack_release_later)
                else -> ctx.getString(R.string.stack_hint_short)
            }
        }
        d.onCommit = { dir -> tintR.alpha = 0f; tintL.alpha = 0f; if (dir > 0) doIt(topItem) else later(topItem) }
        deck = d
        deckBox.addView(d, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Cards handled today (for "2 of 5"): kept per day on this phone. */
    private fun handledToday(now: Long): Int {
        val p = ctx.getSharedPreferences(MorningStack.PREFS, android.content.Context.MODE_PRIVATE)
        return if (p.getString("hday", "") == MorningStack.day(now).toString()) p.getInt("hn", 0) else 0
    }

    private fun countHandled() {
        val now = System.currentTimeMillis()
        val p = ctx.getSharedPreferences(MorningStack.PREFS, android.content.Context.MODE_PRIVATE)
        p.edit().putString("hday", MorningStack.day(now).toString()).putInt("hn", handledToday(now) + 1).apply()
    }

    // ------------------------------------------------------------------ cards

    private fun shell(tagName: String, accent: Int, interactive: Boolean, fill: LinearLayout.() -> Unit): View {
        val f = FrameLayout(ctx).apply {
            tag = tagName
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.blend(Kit.S3, accent, 0.10f), Kit.S1)).apply {
                cornerRadius = dp(34).toFloat(); setStroke(dp(1), Ui.withAlpha(Color.WHITE, 0x1C))
            }
            elevation = dp(14).toFloat()
            clipToPadding = false
        }
        val c = Ui.column(ctx).apply { setPadding(dp(22), dp(22), dp(22), dp(22)) }
        c.fill()
        f.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (interactive) {
            fun stamp(text: String, color: Int, right: Boolean) = Ui.display(ctx, text, 24f, color).apply {
                tag = if (right) "stamp-right" else "stamp-left"
                alpha = 0f
                rotation = if (right) -12f else 12f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = Ui.rounded(Ui.withAlpha(Ui.BG, 0xCC), dp(10).toFloat(), color, dp(3))
            }
            f.addView(stamp(ctx.getString(if (tagName == "ca-pay") R.string.stamp_settle else R.string.stamp_done), if (tagName == "ca-pay") Ui.GOLD else Ui.GREEN, true),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(22); topMargin = dp(54) })
            f.addView(stamp(ctx.getString(R.string.stamp_later), Color.parseColor("#B8C6D2"), false),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { rightMargin = dp(22); topMargin = dp(54) })
        }
        return f
    }

    private fun LinearLayout.kind(icon: Int, label: String, color: Int, source: String) {
        val r = Ui.row(ctx, gap = 10).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(Kit.icon(ctx, icon, color, 22))
        r.addView(Ui.text(ctx, label.uppercase(), 15f, color, 800).apply { letterSpacing = 0.06f; maxLines = 1; tag = "stack-kind" })
        r.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) // a bare spacer: wrap height would take the whole card
        r.addView(Ui.text(ctx, source, 14f, Kit.MUTED, 600).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(r)
    }

    private fun LinearLayout.title(text: String, tagName: String = "stack-title") {
        addView(Ui.top(Ui.display(ctx, text, 31f).apply {
            tag = tagName
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
            letterSpacing = -0.02f
            setAutoSizeTextTypeUniformWithConfiguration(20, 31, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, 18).apply { layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { height = dp(118) } })
    }

    private fun LinearLayout.quote(who: String, text: String) {
        if (text.isBlank()) return
        addView(Ui.top(Ui.column(ctx).apply {
            background = Ui.rounded(Ui.withAlpha(Ui.BG, 0x66), dp(20).toFloat(), Kit.HAIR, dp(1))
            setPadding(dp(18), dp(14), dp(18), dp(16))
            addView(Ui.text(ctx, ctx.getString(R.string.card_said, who), 14f, Kit.MUTED, 600))
            addView(Ui.top(Ui.text(ctx, "“" + text.trim().trim('"', '“', '”') + "”", 17f, Ui.TEXT, 600).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f) }, 6))
        }, 14))
    }

    private fun LinearLayout.body(text: String, color: Int = Kit.MUTED) {
        if (text.isBlank()) return
        addView(Ui.top(Ui.text(ctx, text, 16f, color, 600).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f) }, 12))
    }

    private fun LinearLayout.bottom(button: View, note: String? = null) {
        addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        if (!note.isNullOrBlank()) addView(Ui.text(ctx, note, 14f, Kit.MUTED, 600).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, 0, 0, dp(10)) })
        addView(button)
    }

    /** Peeking cards are inert: their tags get a "-peek" suffix so only the top card answers to stack-do etc. */
    private fun peekTags(v: View) {
        (v.tag as? String)?.let { v.tag = "$it-peek" }
        if (v is ViewGroup) for (i in 0 until v.childCount) peekTags(v.getChildAt(i))
    }

    private fun cardFor(item: StackItem, top: Boolean): View = when (item) {
        is StackItem.Call -> callCard(item.a, top)
        is StackItem.Habit -> habitCard(item.id, top)
        is StackItem.Season -> seasonCard(item.d, top)
    }

    private fun callCard(a: CallAction, top: Boolean): View {
        val call = CallInbox.cached(ctx).firstOrNull { it.key == a.callKey }
        val who = call?.who?.takeIf { it.isNotBlank() } ?: a.recipient.ifBlank { ctx.getString(R.string.calls_unknown) }
        val src = call?.let { ctx.getString(R.string.src_call, Fmt.clock(it.at)) } ?: ""
        val quote = CallActionCards.inUi(a.quote).takeIf { it.length > 8 }.orEmpty()
        return when (a.type) {
            CallAction.PAYMENT -> {
                val c = net.solardepin.solarchik.circle.Circle.match(net.solardepin.solarchik.circle.CircleStore(ctx).all(), who, call?.dialNumber.orEmpty())
                shell("ca-pay", Ui.GOLD, top) {
                    kind(R.drawable.lc_users, ctx.getString(R.string.kind_circle), Ui.GOLD, src)
                    title(ctx.getString(R.string.card_pay_title, c?.name ?: who, CallActionCards.amount(a), a.token))
                    quote(who, quote)
                    body(if (c != null && c.address.isNotBlank()) ctx.getString(R.string.card_pay_to, c.name, Fmt.short(c.address)) else ctx.getString(R.string.card_pay_nowallet, who))
                    bottom(Kit.primary(ctx, if (c != null && c.address.isNotBlank()) ctx.getString(R.string.card_settle, CallActionCards.amount(a), a.token) else ctx.getString(R.string.circle_add_wallet, who), R.drawable.lc_wallet) { stackDo(a) }.apply { tag = "stack-do" },
                        ctx.getString(R.string.card_pay_safe))
                }
            }
            CallAction.OWED -> shell("ca-owed", Ui.GREEN, top) {
                kind(R.drawable.lc_users, ctx.getString(R.string.kind_circle), Ui.GREEN, src)
                title(ctx.getString(R.string.card_owed_title, who, CallActionCards.amount(a), a.token))
                quote(who, quote)
                body(ctx.getString(R.string.card_owed_body))
                bottom(Kit.primary(ctx, ctx.getString(R.string.payreq_btn, CallActionCards.amount(a), a.token), R.drawable.lc_wallet) { stackDo(a) }.apply { tag = "stack-do" },
                    ctx.getString(R.string.payreq_safe_short))
            }
            CallAction.CALLBACK -> shell("ca-callback", Ui.CYAN, top) {
                kind(R.drawable.lc_phone, ctx.getString(R.string.kind_callback), Ui.CYAN, src)
                title(CallActionCards.title(ctx, a, who))
                inChip(a, call?.at ?: 0L)?.let { addView(Ui.top(Kit.chip(ctx, it, Ui.CYAN, R.drawable.lc_clock), 12)) }
                quote(who, quote)
                bottom(Kit.primary(ctx, ctx.getString(R.string.card_call, who), R.drawable.lc_phone) { stackDo(a) }.apply { tag = "stack-do" })
            }
            else -> shell("ca-reminder", Ui.AMBER, top) {
                kind(R.drawable.lc_clock, ctx.getString(R.string.kind_reminder), Ui.AMBER, src)
                title(CallActionCards.title(ctx, a, who))
                quote(who, quote)
                bottom(Kit.primary(ctx, ctx.getString(R.string.card_remind), R.drawable.lc_bell) { stackDo(a) }.apply { tag = "stack-do" })
            }
        }
    }

    /** "in 6 h 48 min" until a call-back time that is still ahead today. */
    private fun inChip(a: CallAction, callAt: Long): String? {
        if (a.time.isBlank() || callAt <= 0) return null
        val now = System.currentTimeMillis()
        val at = net.solardepin.solarchik.screen.CallActionRules.remindAt(a, callAt, now, java.time.ZoneId.systemDefault())
        val left = at - now
        if (left <= 0 || left > 24 * 3600_000L) return null
        val h = left / 3600_000L; val m = (left % 3600_000L) / 60_000L
        val s = if (net.solardepin.solarchik.core.AppLocale.lang(ctx) == "uk") (if (h > 0) "$h год $m хв" else "$m хв") else (if (h > 0) "$h h $m min" else "$m min")
        return ctx.getString(R.string.card_in, s)
    }

    private fun habitCard(id: String, top: Boolean): View {
        val icon = when (id) { Habits.SAVE -> R.drawable.lc_coins; Habits.WALLET -> R.drawable.lc_wallet; Habits.CALL -> R.drawable.lc_heart; Habits.WORKOUT -> R.drawable.lc_dumbbell; else -> R.drawable.lc_check }
        return shell("stack-habit", Ui.GREEN, top) {
            kind(icon, ctx.getString(R.string.kind_habit), Ui.GREEN, ctx.getString(R.string.src_habit))
            title(Habits.title(ctx, id), "stack-habit-title")
            when (id) {
                Habits.SAVE -> {
                    body(ctx.getString(R.string.habit_save_body, Fmt.short(Habits.saveTo(ctx))))
                    val total = Habits.saved(ctx)
                    addView(Ui.top(Kit.chip(ctx, if (total > 0) ctx.getString(R.string.habit_saved_total, net.solardepin.solarchik.circle.Circle.amount(total)) else ctx.getString(R.string.habit_saved_none), Ui.GREEN, R.drawable.lc_coins).apply { tag = "habit-saved-total" }, 12))
                    bottom(Kit.primary(ctx, ctx.getString(R.string.habit_save_btn, net.solardepin.solarchik.circle.Circle.amount(Habits.saveAmount(ctx))), R.drawable.lc_wallet) { habitDo(id) }.apply { tag = "stack-do" },
                        ctx.getString(R.string.habit_save_skip_note))
                }
                Habits.WALLET -> { body(ctx.getString(R.string.habit_wallet_body)); bottom(Kit.primary(ctx, ctx.getString(R.string.habit_wallet_btn), R.drawable.lc_wallet) { habitDo(id) }.apply { tag = "stack-do" }) }
                Habits.CALL -> {
                    val c = Habits.callContact(ctx)
                    body(ctx.getString(if (c != null) R.string.habit_call_body else R.string.habit_call_pick))
                    bottom(Kit.primary(ctx, if (c != null) ctx.getString(R.string.card_call, c.name) else ctx.getString(R.string.habit_pick_btn), R.drawable.lc_phone) { habitDo(id) }.apply { tag = "stack-do" })
                }
                Habits.WORKOUT -> { body(ctx.getString(R.string.habit_workout_body)); bottom(Kit.primary(ctx, ctx.getString(R.string.card_done), R.drawable.lc_check) { habitDo(id) }.apply { tag = "stack-do" }) }
                else -> { body(ctx.getString(R.string.habit_custom_body)); bottom(Kit.primary(ctx, ctx.getString(R.string.card_done), R.drawable.lc_check) { habitDo(id) }.apply { tag = "stack-do" }) }
            }
        }
    }

    private fun seasonCard(d: net.solardepin.solarchik.season.SeasonDrop, top: Boolean): View = shell("stack-season", Ui.PURPLE, top) {
        kind(R.drawable.lc_spark, ctx.getString(R.string.kind_season), Ui.PURPLE, ctx.getString(R.string.src_season))
        title(d.app, "stack-season-app")
        body(d.perk, Ui.TEXT)
        if (d.deadline.isNotBlank()) addView(Ui.top(Kit.chip(ctx, ctx.getString(R.string.card_until, d.deadline), Ui.AMBER, R.drawable.lc_clock), 12))
        addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        addView(Ui.text(ctx, ctx.getString(R.string.card_season_note), 14f, Kit.MUTED, 600).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, 0, 0, dp(10)) })
        val r = Ui.row(ctx, gap = 10)
        r.addView(Kit.ghost(ctx, ctx.getString(R.string.card_did)) { seasonDid(d) }.apply { tag = "stack-season-done" }, LinearLayout.LayoutParams(0, dp(56), 0.8f))
        r.addView(Kit.primary(ctx, ctx.getString(R.string.card_open), R.drawable.lc_ext) { seasonOpen(d) }.apply { tag = "stack-season-open" }, LinearLayout.LayoutParams(0, dp(56), 1.2f))
        addView(r)
    }

    // ------------------------------------------------------------------ do it / later

    private fun doIt(item: StackItem) {
        when (item) {
            is StackItem.Call -> stackDo(item.a)
            is StackItem.Habit -> habitDo(item.id)
            is StackItem.Season -> seasonOpen(item.d)
        }
    }

    private fun later(item: StackItem) {
        when (item) {
            is StackItem.Call -> stackLater(item.a)
            is StackItem.Habit -> habitLater(item.id)
            is StackItem.Season -> seasonLater(item.d)
        }
    }

    /** Do it: the dialer for a call-back, the prefilled wallet confirm for a payment, the reminder for a reminder. */
    internal fun stackDo(a: CallAction) {
        when (a.type) {
            CallAction.PAYMENT -> {
                val call = CallInbox.cached(ctx).firstOrNull { it.key == a.callKey }
                val who = call?.who?.takeIf { it.isNotBlank() } ?: a.recipient
                val c = net.solardepin.solarchik.circle.Circle.match(net.solardepin.solarchik.circle.CircleStore(ctx).all(), who, call?.dialNumber.orEmpty())
                val after = { MorningStack.touched(ctx); if (statusOf(a.id) != CallAction.OPEN) countHandled(); host.renderAll() }
                if (c != null && c.address.isNotBlank()) CallActionCards.payContact(host, a, c, after)
                else CirclePanel.edit(host, c ?: net.solardepin.solarchik.circle.Contact("", who, call?.dialNumber.orEmpty()), ctx.getString(R.string.circle_add_wallet, who)) { saved ->
                    host.renderAll()
                    if (saved.address.isNotBlank()) CallActionCards.payContact(host, a, saved, after)
                }
                render()
            }
            CallAction.CALLBACK -> { if (a.number.isNotBlank()) CallActionCards.dial(ctx, a); MorningStack.done(ctx, a.id); countHandled(); render() }
            CallAction.OWED -> {
                // opens the Solana Pay request (QR + share); once a request is out, the card leaves the stack
                val d = net.solardepin.solarchik.circle.Circle.currentOwed(ctx).firstOrNull { it.action.id == a.id } ?: return
                PayRequestSheet.show(host, d) {
                    if (net.solardepin.solarchik.circle.PayRequestStore(ctx).forAction(a.id) != null && !countedOwed.contains(a.id)) { countedOwed += a.id; MorningStack.touched(ctx); countHandled() }
                    host.renderAll()
                }
                render()
            }
            CallAction.REMINDER -> {
                CallActionCards.remind(host, a, CallInbox.cached(ctx).firstOrNull { it.key == a.callKey }?.at ?: System.currentTimeMillis())
                MorningStack.touched(ctx); countHandled(); render()
            }
            else -> { MorningStack.done(ctx, a.id); countHandled(); render() }
        }
    }

    private val countedOwed = mutableSetOf<String>()

    private fun statusOf(id: String): String = runCatching { net.solardepin.solarchik.screen.CallActionStore(ctx).find(id).status }.getOrDefault(CallAction.OPEN)

    internal fun stackLater(a: CallAction) {
        MorningStack.snooze(ctx, a.id)
        countHandled()
        host.toast(ctx.getString(R.string.stack_snoozed))
        render()
    }

    internal fun habitLater(id: String) {
        MorningStack.snooze(ctx, Habits.key(id))
        countHandled()
        host.toast(ctx.getString(R.string.stack_snoozed))
        render()
    }

    internal fun habitDo(id: String) {
        when (id) {
            Habits.SAVE -> SaveUsdc.start(host) { ok -> if (ok) countHandled(); host.renderAll() }
            Habits.WALLET -> checkWallet()
            Habits.CALL -> {
                val c = Habits.callContact(ctx)
                if (c == null) { HabitsSheet.pickContact(host) { render() }; return }
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + c.phone))) }
                Habits.markDone(ctx, id); countHandled(); render()
            }
            else -> { Habits.markDone(ctx, id); countHandled(); host.toast(ctx.getString(R.string.today_todo_done_toast)); render() }
        }
    }

    /** "Check wallet": one sentence about the overnight change (read-only). */
    private fun checkWallet() {
        val w = host.wallet
        if (!w.connected) { setupWallet(); return }
        host.scope.launch {
            val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
            val before = st.snap()?.takeIf { MorningStack.day(it.at) != MorningStack.day(System.currentTimeMillis()) }?.sol
            val now = withContext(Dispatchers.IO) { w.balanceSol() }.getOrNull()
            now?.let { host.walletSol = it; host.walletSolAddr = w.address; st.setSnap(net.solardepin.solarchik.sol.WalletSnap(System.currentTimeMillis(), it, host.walletSkr)) }
            val line = Habits.walletLine(ctx, before, now)
            host.toast(line)
            speak(line, host.lang)
            if (now != null) { Habits.markDone(ctx, Habits.WALLET); countHandled() }
            render()
        }
    }

    /** Opens the official link only (assist-only); on return, Today asks whether it is done. */
    internal var pendingSeason: net.solardepin.solarchik.season.SeasonDrop? = null
    internal fun seasonOpen(d: net.solardepin.solarchik.season.SeasonDrop) {
        pendingSeason = d
        host.openUrl(d.sourceUrl)
        render()
    }

    internal fun seasonDid(d: net.solardepin.solarchik.season.SeasonDrop) {
        pendingSeason = null
        MorningStack.seasonDone(ctx, d.id)
        countHandled()
        render()
    }

    internal fun seasonLater(d: net.solardepin.solarchik.season.SeasonDrop) {
        MorningStack.snooze(ctx, "season:" + d.id)
        MorningStack.seasonHandledOne(ctx)
        MorningStack.touched(ctx)
        countHandled()
        host.toast(ctx.getString(R.string.stack_snoozed))
        render()
    }

    /** Back from the link: "Done with MattleFun?" (nothing is marked done without the user saying so). */
    private fun askSeasonDone() {
        val d = pendingSeason ?: return
        pendingSeason = null
        android.app.AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.stack_season_ask, d.app))
            .setPositiveButton(R.string.stack_season_did) { _, _ -> seasonDid(d) }
            .setNegativeButton(R.string.stack_season_not_yet, null).show()
    }

    // ------------------------------------------------------------------ clocked in / empty

    private fun clockedView(streak: Int): View {
        val f = FrameLayout(ctx).apply { tag = "stack-clocked"; clipChildren = false }
        if (!Kit.reducedMotion(ctx)) f.addView(ConfettiView(ctx), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val c = Ui.column(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        val mascot = FrameLayout(ctx).apply { clipChildren = false }
        val img = Ui.image(ctx, R.drawable.buddy_happy)
        mascot.addView(img, FrameLayout.LayoutParams(dp(210), dp(230), Gravity.CENTER))
        mascot.addView(Kit.chip(ctx, Fmt.count(streak), Ui.INK, R.drawable.lc_flame, size = 22f).apply {
            setPadding(dp(14), dp(8), dp(16), dp(8))
            background = Ui.rounded(Ui.GOLD, dp(24).toFloat(), Ui.BG, dp(4))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(36); bottomMargin = dp(6) })
        c.addView(mascot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        c.addView(Ui.display(ctx, ctx.getString(R.string.clock_title), 34f).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        c.addView(Ui.text(ctx, ctx.resources.getQuantityString(R.plurals.clock_sub, streak, streak), 16f, Ui.withAlpha(Ui.TEXT, 0xDD), 600).apply { tag = "stack-streak"; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        c.addView(weekDots(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        c.addView(Kit.primary(ctx, ctx.getString(R.string.clock_play), R.drawable.lc_play) { host.playGame() }.apply { tag = "clock-play" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(22) })
        if (host.save.signedToday()) {
            c.addView(Ui.text(ctx, ctx.getString(R.string.clock_signed), 15f, Ui.GREEN, 800).apply { tag = "stack-signed"; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(12) })
        } else {
            val sb = android.text.SpannableStringBuilder(ctx.getString(R.string.clock_sign_chip) + "  ")
            val s0 = sb.length
            sb.append(ctx.getString(R.string.clock_optional))
            sb.setSpan(android.text.style.ForegroundColorSpan(Ui.GOLD), s0, sb.length, 0)
            c.addView(Ui.text(ctx, sb, 15f, Ui.TEXT, 700).apply {
                tag = "stack-sign"
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(18), 0, dp(18), 0)
                background = Ui.ripple(Kit.dashed(ctx, Ui.withAlpha(Ui.GOLD, 0x88), 22, Ui.withAlpha(Ui.GOLD, 0x0C)), dp(22).toFloat())
                ctx.getDrawable(R.drawable.lc_pen)?.mutate()?.let { d -> d.setTint(Ui.GOLD); val s = dp(18); d.setBounds(0, 0, s, s); setCompoundDrawables(d, null, null, null); compoundDrawablePadding = dp(8) }
                isClickable = true
                setOnClickListener { openCheckIn() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(14) })
            c.addView(Ui.text(ctx, ctx.getString(R.string.clock_sign_note), 14f, Kit.MUTED, 600).apply { gravity = Gravity.CENTER; tag = "stack-local-only" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        f.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // spec §6: mascot springs in, one strong haptic
        if (!Kit.reducedMotion(ctx)) { img.scaleX = 0.6f; img.scaleY = 0.6f; img.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start() }
        if (!celebrated) { celebrated = true; f.post { celebrate(f) } }
        return f
    }

    private var celebrated = false

    private fun celebrate(v: View) {
        val vib = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator else null
        if (vib != null && android.os.Build.VERSION.SDK_INT >= 31 && vib.areAllPrimitivesSupported(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, android.os.VibrationEffect.Composition.PRIMITIVE_THUD)) {
            runCatching { vib.vibrate(android.os.VibrationEffect.startComposition()
                .addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f)
                .addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 80)
                .addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 80).compose()) }
        } else v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** The last 7 days ending today: gold check = clocked in; today ringed. */
    private fun weekDots(): View = Ui.row(ctx).apply {
        gravity = Gravity.CENTER
        tag = "stack-week"
        val days = MorningStack.days(ctx)
        val today = MorningStack.day(System.currentTimeMillis())
        val loc = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            val on = d.toString() in days
            val col = Ui.column(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val dot = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) Ui.withAlpha(Ui.GOLD, 0xE6) else Ui.withAlpha(Color.WHITE, 0x12))
                    if (i == 0) setStroke(dp(3), Ui.BG)
                }
                if (on) addView(ImageView(ctx).apply { setImageResource(R.drawable.lc_check); setColorFilter(Ui.INK) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }
            val ring = FrameLayout(ctx).apply {
                if (i == 0) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Ui.GOLD) }
                addView(dot, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
            }
            col.addView(ring, LinearLayout.LayoutParams(dp(46), dp(46)))
            col.addView(Ui.text(ctx, d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, loc), 14f, Kit.MUTED, 800).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun emptyView(): View = Ui.column(ctx).apply {
        tag = "stack-empty"
        gravity = Gravity.CENTER
        background = Ui.rounded(Kit.S1, dp(34).toFloat(), Kit.HAIR, dp(1))
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(Ui.image(ctx, R.drawable.buddy_happy), LinearLayout.LayoutParams(dp(140), dp(150)))
        addView(Ui.top(Ui.display(ctx, ctx.getString(R.string.clock_empty_title), 26f).apply { gravity = Gravity.CENTER }, 12).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) } })
        addView(Ui.text(ctx, ctx.getString(R.string.clock_empty_sub), 16f, Kit.MUTED, 600).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        addView(Kit.ghost(ctx, ctx.getString(R.string.clock_empty_habits), R.drawable.lc_plus, Ui.GOLD) { HabitsSheet.show(host) { host.renderAll() } }.apply { tag = "stack-pick-habits" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
    }

    private fun openCheckIn() {
        val save = host.save
        host.select(MainActivity.Tab.SHIFT, animate = true)
        val shift = host.screen(MainActivity.Tab.SHIFT) as? YardScreen ?: return
        if (save.checkInOpen() && !save.signedToday()) shift.signFromRun() else shift.focusToday()
    }

    // ------------------------------------------------------------------ the stack, hands-free

    private var stackVoice: net.solardepin.solarchik.sol.SolVoice? = null
    private var stackEars: net.solardepin.solarchik.sol.SolEars? = null
    private var stackMisses = 0

    /** Sol reads the top card and asks "done, later or do it?"; the answer acts and the next card follows. */
    private fun readStack() {
        host.withPermission(android.Manifest.permission.RECORD_AUDIO) { ok ->
            stackMisses = 0
            readTop(listen = ok)
        }
    }

    private fun readTop(listen: Boolean) {
        val item = MorningStack.deck(ctx).firstOrNull()
        val v = stackVoice ?: net.solardepin.solarchik.sol.SolVoice(host).also { stackVoice = it }
        val text = when (item) {
            null -> { v.onIdle = null; v.speak(ctx.getString(R.string.stack_spoken_zero), host.lang); return }
            is StackItem.Season -> ctx.getString(R.string.stack_spoken_season, item.d.app, item.d.perk)
            is StackItem.Habit -> Habits.title(ctx, item.id) + ". " + ctx.getString(R.string.stack_spoken_ask)
            is StackItem.Call -> {
                val who = CallInbox.cached(ctx).firstOrNull { it.key == item.a.callKey }?.who.orEmpty()
                CallActionCards.title(ctx, item.a, who) + ". " + ctx.getString(if (item.a.type == CallAction.PAYMENT) R.string.stack_spoken_ask_pay else R.string.stack_spoken_ask)
            }
        }
        v.onIdle = { _ -> if (listen) hear(item) }
        if (!v.speak(text, host.lang)) host.toast(ctx.getString(R.string.chat_tts_missing))
    }

    private fun hear(item: StackItem) {
        val ears = stackEars ?: net.solardepin.solarchik.sol.SolEars(host).also { stackEars = it }
        if (!ears.available()) return
        ears.listen(host.lang, onPartial = {}) { heard ->
            when (MorningStack.answer(heard)) {
                MorningStack.Answer.LATER -> { later(item); readTop(true) }
                MorningStack.Answer.DONE -> {
                    when (item) {
                        is StackItem.Call -> { MorningStack.done(ctx, item.a.id); countHandled(); render() }
                        is StackItem.Habit -> if (item.id == Habits.SAVE) { habitDo(item.id); return@listen } else { Habits.markDone(ctx, item.id); countHandled(); render() }
                        is StackItem.Season -> seasonDid(item.d)
                    }
                    readTop(true)
                }
                // a call, a payment or a link leaves the app: stop here
                MorningStack.Answer.DO -> { doIt(item); if (item is StackItem.Call && item.a.type == CallAction.REMINDER) readTop(true) }
                null -> if (++stackMisses < 2) readTop(true) else host.toast(ctx.getString(R.string.stack_not_heard))
            }
        }
    }

    // ------------------------------------------------------------------ briefing (Me › Morning briefing, notification)

    /** Builds the facts from this phone, gets Sol's text (worker; local template offline) and speaks it. */
    fun playBriefing() {
        if (briefingBusy) return
        briefingBusy = true
        host.renderAll()
        host.scope.launch {
            val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
            val w = host.wallet
            val now = System.currentTimeMillis()
            var sol: Double? = host.walletSol.takeIf { host.walletSolAddr == w.address }
            var skrNow: Double? = host.walletSkr
            if (w.connected) {
                w.balanceSol().onSuccess { sol = it; host.walletSol = it; host.walletSolAddr = w.address }
                if (w.mainnet && MainActivity.tickerEnabled) withContext(Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetch(w.address) }.onSuccess { skrNow = it; host.walletSkr = it }
            }
            if (MainActivity.tickerEnabled) withContext(Dispatchers.IO) { runCatching { net.solardepin.solarchik.season.SeasonDropsSync.refresh(ctx, host.lang) } }
            val zone = java.time.ZoneId.systemDefault()
            val facts = net.solardepin.solarchik.sol.Briefing.facts(
                CallInbox.cached(ctx), FollowUps.list(ctx, now),
                net.solardepin.solarchik.screen.CallActionStore(ctx).open().map { net.solardepin.solarchik.sol.AssistantExtras.actionLine(it) + " (needs your confirmation)" },
                net.solardepin.solarchik.agents.WatcherStore(ctx).recent(now),
                net.solardepin.solarchik.sol.AssistantContext.Wallet(w.connected, w.isLocal, if (w.connected) w.address else "", w.mainnet, sol.takeIf { w.connected }, skrNow.takeIf { w.connected && w.mainnet }),
                st.snap(), net.solardepin.solarchik.season.SeasonStore.planFor(ctx, host.save, w.mainnet), now, zone,
                seasonTasks = net.solardepin.solarchik.season.SeasonDrops.lines(net.solardepin.solarchik.season.SeasonDropsStore(ctx).doc(host.lang)),
            )
            val base = withContext(Dispatchers.IO) { net.solardepin.solarchik.sol.Briefing.fetch(facts, host.lang, briefingPost) }
                ?: net.solardepin.solarchik.sol.Briefing.localText(facts, ctx)
            val owe = net.solardepin.solarchik.circle.Circle.briefLines(ctx, net.solardepin.solarchik.circle.Circle.current(ctx), now, zone)
            val text = (listOf(base) + owe).joinToString(" ")
            st.lastText = text
            st.playedAt = now
            st.pendingDay = ""
            if (w.connected && sol != null) st.setSnap(net.solardepin.solarchik.sol.WalletSnap(now, sol, skrNow))
            briefingBusy = false
            briefingPlaying = speak(text, host.lang)
            if (!briefingPlaying) host.toast(ctx.getString(R.string.chat_tts_missing))
            host.renderAll()
        }
    }

    fun stopBriefing() {
        voice?.stop()
        briefingPlaying = false
        host.renderAll()
    }

    /** Looks at new answered calls for requests (worker; local rules offline). At most once a minute. */
    internal fun syncActions(post: (String, String) -> Pair<Int, String> = briefingPost) {
        val now = System.currentTimeMillis()
        if (now - actionsSynced < 60_000L) return
        actionsSynced = now
        val calls = CallInbox.cached(ctx)
        host.scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { net.solardepin.solarchik.screen.CallActionSync.run(ctx, calls, host.lang, post = post) }.getOrDefault(emptyList()) }
            if (found.isNotEmpty()) host.renderAll()
        }
    }

    /** A reminder notification was tapped: its card is the stack on Today. */
    fun focusAction(id: String) { render() }

    /** Connect the wallet app (MWA); on devnet without one, the built-in wallet. */
    internal fun setupWallet() {
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
            host.renderAll()
            (host.screen(MainActivity.Tab.ME) as? MeScreen)?.refresh(force = true)
        }
    }

    override fun onShow() {
        render()
        askSeasonDone()
        syncActions()
        SaveUsdc.recheckPending(host) { host.renderAll() }
        if (MainActivity.tickerEnabled) host.scope.launch {
            val lang = host.lang
            val fresh = withContext(Dispatchers.IO) { runCatching { net.solardepin.solarchik.season.SeasonDropsSync.refresh(ctx, lang) }.getOrDefault(false) }
            if (fresh) render()
        }
        // 1.1.0: a briefing posted this morning and not heard yet plays once when Today opens.
        val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
        if (st.policy().enabled && st.pendingDay == net.solardepin.solarchik.sol.Briefing.today()) playBriefing()
    }

    override fun onDestroy() {
        voice?.shutdown(); voice = null
        stackVoice?.shutdown(); stackVoice = null
        stackEars?.stop()
    }

    companion object {
        /** Tests: scripted worker replies for the briefing and call actions. */
        @androidx.annotation.VisibleForTesting
        var postOverride: ((String, String) -> Pair<Int, String>)? = null
        /** Unit tests never reach the live worker unless they script it (the local fallbacks run instead). */
        private val defaultPost: (String, String) -> Pair<Int, String> =
            if (android.os.Build.FINGERPRINT == "robolectric") { _, _ -> 0 to "" } else net.solardepin.solarchik.sol.Briefing::httpPost
        const val HOLD_MS = 350L

        fun greetingFor(hour: Int): Int = when (hour) {
            in 5..11 -> R.string.today_morning
            in 12..16 -> R.string.today_afternoon
            in 17..22 -> R.string.today_evening
            else -> R.string.today_night
        }
    }

    /** Tests: wallet balances without the network (shown on Me since 1.2.7). */
    internal fun setBalanceForTest(sol: Double?, skr: Double? = null) {
        host.walletSol = sol; host.walletSkr = skr; host.walletSolAddr = host.wallet.address
        (host.screen(MainActivity.Tab.ME) as? MeScreen)?.setBalanceForTest(sol, skr)
    }
}

/** 1.2.7 Clocked in: 80 confetti pieces falling for 1.6 s, fading out over the last 0.4 s (spec §6). */
class ConfettiView(ctx: android.content.Context) : View(ctx) {
    private val rnd = java.util.Random(7)
    private val colors = intArrayOf(Ui.GOLD, Ui.CYAN, Ui.GREEN, Ui.PURPLE, Color.parseColor("#FF8A7A"), Color.WHITE)
    private data class P(val x: Float, val y: Float, val vx: Float, val vy: Float, val w: Float, val h: Float, val rot: Float, val c: Int)
    private val ps = List(80) { P(rnd.nextFloat(), rnd.nextFloat() * 0.75f - 0.1f, rnd.nextFloat() * 0.2f - 0.1f, rnd.nextFloat() * 0.25f, 6f + rnd.nextFloat() * 8f, 4f + rnd.nextFloat() * 6f, rnd.nextFloat() * 360f, colors[rnd.nextInt(colors.size)]) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (android.os.Build.FINGERPRINT != "robolectric") anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val alpha = if (t < 0.75f) 1f else (1f - (t - 0.75f) / 0.25f)
        if (alpha <= 0f) return
        for (p in ps) {
            val x = (p.x + p.vx * t) * width
            val y = (p.y + p.vy * t + 0.55f * t * t) * height * 0.8f
            paint.color = Ui.withAlpha(p.c, (0xDD * alpha).toInt())
            canvas.save(); canvas.rotate(p.rot + t * 180f, x, y)
            canvas.drawRoundRect(x, y, x + p.w * d, y + p.h * d, 2 * d, 2 * d, paint)
            canvas.restore()
        }
    }

}
