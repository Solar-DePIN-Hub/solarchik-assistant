package net.solardepin.solarchik.game

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import net.solardepin.solarchik.R
import net.solardepin.solarchik.game.run.ChapterId
import net.solardepin.solarchik.game.run.DeathKind
import net.solardepin.solarchik.game.run.Phase
import net.solardepin.solarchik.game.run.RunSim
import kotlin.math.ceil

/**
 * The DOM layer of the web runner, as Android views over the RunView surface: HUD (pause,
 * hearts, score, distance, time of day), hint line, countdown / chapter banner, Sol caption,
 * mic, music toggle, slide button, and the pause / result cards. CLOCK IN at the goal does not
 * stop the run: a celebratory banner, then a persistent Sign badge in the HUD; the pause and
 * result cards carry the sign section and the day card. Sizes of RoofRun.tsx are kept in dp;
 * chips and buttons are drawn as chunky game controls. UI thread only.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class RunOverlay(private val ctx: Context, private val actions: Actions) : FrameLayout(ctx) {

    interface Actions {
        fun pauseToggle()
        fun resume()
        fun yard()
        fun again()
        /** Sign today's CLOCK IN (card button or the HUD badge; the badge pauses first). */
        fun sign()
        fun signBadge()
        fun share()
        /** No wallet connected: go to the Yard to connect and sign there. */
        fun yardSign()
        fun musicToggle()
        fun mic()
        fun slideDown()
        fun slideUp()
    }

    companion object {
        /**
         * 1.2.10 (tablet: the old Solarchik game-over screen with its "Sign today" button popped up after a run):
         * no signing in the run at all. The result is a clean card (score, Play again, Back to Today); signing the
         * day stays optional on Today after the stack. Old run tests flip this back on to keep covering the flow.
         */
        @Volatile var runSigning = false
        const val BANNER_MS = 2800L
        const val CARD_W = 404
        /** Sol's caption chip width (dp). */
        const val CAPTION_W = 230
        /** 0.21.8: the caption chip's minimum width (dp). */
        const val CAPTION_MIN_W = 120

        /** One short horizontal line: newlines/tabs and runs of spaces collapse, at most 140 chars. */
        fun cleanCaption(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(140)
        /** Death beat before the result card (ms): the fall reads, then the card. */
        const val DEATH_BEAT_MS = 1100L
        const val CARD_W_WIDE = 720
        const val SIGNED_BADGE_MS = 4000L
        val BG = Color.parseColor("#0B1620")
        val ELEVATED = Color.parseColor("#183041")
        val FG = Color.parseColor("#E8F0F5")
        val PRIMARY = Color.parseColor("#E8B931")
        val PRIMARY_FG = Color.parseColor("#0B1620")
        val MUTED = Color.parseColor("#8AA0AE")
        val OK = Color.parseColor("#6FBF4A")
        val DANGER = Color.parseColor("#E0564A")

        fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

        fun chapterRes(id: ChapterId) = when (id) {
            ChapterId.SUNRISE -> R.string.ch_sunrise
            ChapterId.VILLAGE -> R.string.ch_village
            ChapterId.STORM -> R.string.ch_storm
            ChapterId.NIGHT -> R.string.ch_night
            ChapterId.SERPENT -> R.string.ch_serpent
        }

        /** Chapter banner (web shows the sim's English banner; localized here). */
        fun bannerRes(id: ChapterId) = when (id) {
            ChapterId.SUNRISE -> R.string.ch_banner_sunrise
            ChapterId.VILLAGE -> R.string.ch_banner_village
            ChapterId.STORM -> R.string.ch_banner_storm
            ChapterId.NIGHT -> R.string.ch_banner_night
            ChapterId.SERPENT -> R.string.ch_banner_serpent
        }
    }

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = (v * dm.density).toInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private fun sunIcon(tint: Int, sizeDp: Int): android.graphics.drawable.Drawable? =
        ResourcesCompat.getDrawable(resources, R.drawable.ic_sun, null)?.mutate()?.apply { setTint(tint); setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }
    /** Fredoka has no Cyrillic: Ukrainian banners, HUD and cards use the rounded Nunito ExtraBold. */
    private val display: Typeface? = runCatching {
        ResourcesCompat.getFont(ctx, if (ctx.resources.configuration.locales[0].language == "uk") R.font.nunito_extrabold else R.font.fredoka_semibold)
    }.getOrNull()
    private val body: Typeface? = runCatching { ResourcesCompat.getFont(ctx, R.font.nunito_bold) }.getOrNull()

    private fun box(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius * dm.density }

    /** A painted nine-patch whose own padding is ignored (views keep their dp padding). */
    private fun np(id: Int): android.graphics.drawable.Drawable =
        LayerDrawable(arrayOf(ResourcesCompat.getDrawable(resources, id, null)!!)).apply { setPadding(0, 0, 0, 0) }

    /** HUD chip: painted dark glass with a light rim ([small] for the tiny chapter tag). */
    private fun chip(small: Boolean = false) = np(if (small) R.drawable.run_chip_s else R.drawable.run_chip)

    /** Painted game button: a glossy face over a darker edge that sinks when pressed. */
    private fun painted(normal: Int, pressed: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), np(pressed))
        addState(intArrayOf(), np(normal))
    }

    private fun goldButton() = painted(R.drawable.run_btn_gold, R.drawable.run_btn_gold_p)
    private fun darkButton() = painted(R.drawable.run_btn_dark, R.drawable.run_btn_dark_p)
    private fun greenButton() = painted(R.drawable.run_btn_green, R.drawable.run_btn_green_p)

    /** Card motion and reward count-ups (off for reduced motion and in screenshot tests). */
    var animations = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

    private fun popIn(v: View, delay: Long = 0) {
        if (!animations) { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; return }
        v.alpha = 0f; v.scaleX = 0.82f; v.scaleY = 0.82f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    /** Banners sweep in: from wide and transparent, settling with an overshoot. */
    private fun bannerIn(v: View) {
        if (!animations) { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; return }
        v.alpha = 0f; v.scaleX = 1.35f; v.scaleY = 1.35f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun bump(v: View, to: Float = 1.25f) {
        if (!animations) return
        ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, to, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, to, 1f))
            .apply { duration = 260; interpolator = OvershootInterpolator(3f) }.start()
    }

    private fun label(text: String = "", size: Float, color: Int = FG, face: Typeface? = display, track: Float = 0f) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face ?: Typeface.DEFAULT_BOLD
        letterSpacing = track
        includeFontPadding = false
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun iconButton(res: Int, bg: Drawable, sizeDp: Int, iconDp: Int, tint: Int, desc: String, onTap: () -> Unit) = FrameLayout(ctx).apply {
        background = bg
        contentDescription = desc
        isClickable = true
        isFocusable = true
        addView(icon(res, tint, iconDp), LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        setOnClickListener { onTap() }
        layoutParams = LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // ---- HUD ----
    private val hud = FrameLayout(ctx)
    private val pauseBtn: FrameLayout
    private val pauseIcon: ImageView
    private val hearts = LinearLayout(ctx)
    private val heartViews = ArrayList<ImageView>()
    private val shieldView: ImageView
    private val scoreV: TextView
    private val sunsV: TextView
    private val distV: TextView
    private val chapterV: TextView
    private val grindV: TextView
    private val flightV: TextView
    private val hintV: TextView
    private val gardenV: TextView
    private val countdownV: TextView
    private val announceV: TextView
    private val milestoneV: TextView
    private val sunsChip: LinearLayout
    private val sunsIcon: ImageView
    private val locMe = IntArray(2)
    private val locIcon = IntArray(2)
    /** Score shown in the chip; rolls up to the real score. */
    private var shownScore = 0
    private var scoreAnim: ValueAnimator? = null
    /** UI: where the suns chip sits (fractions of this view), for the fly-to-HUD coins. */
    var onSunTarget: ((Double, Double) -> Unit)? = null
    private val slideBtn: LinearLayout
    private val musicBtn: FrameLayout
    private val musicIcon: ImageView
    private val micBtn: FrameLayout
    private val captionRow: LinearLayout
    private val captionV: TextView
    val buddy: ImageView

    // ---- CLOCK IN reward moment (non-blocking) ----
    private val clockBadge: TextView
    private val clockBanner: LinearLayout
    private lateinit var clockBannerTitle: TextView
    private lateinit var clockBannerSub: TextView
    private val questToast: TextView

    // ---- cards ----
    private val pauseLayer = FrameLayout(ctx)
    private val pauseCard: LinearLayout
    private val pauseStats: TextView
    private val pauseSign: SignSection
    private val deadLayer = FrameLayout(ctx)
    /** Death beat layer (dim + word) between the last hit and the result card. */
    private val deathDim = FrameLayout(ctx)
    private val deathWord: TextView
    private var deadSince = 0L
    /** Tests set 0 to see the card at once. */
    var deathBeatMs = DEATH_BEAT_MS
    /** Opaque "getting the roofs ready" cover while RunView warms its assets. */
    private val loadingLayer = FrameLayout(ctx)
    private var loading = false
    private val deadCard: LinearLayout
    private val deadTitle: TextView
    private val deadReached: TextView
    private val deadRibbon: TextView
    private val deadStats: TextView
    private val deadScore: TextView
    private val deadSign: SignSection
    private val rewardsBox: LinearLayout
    private val rewardsSuns: TextView
    private val rewardsRows: LinearLayout

    private var last: RunHud? = null
    private var paused = false
    private var listening = false
    private var clock = ClockUi()
    private var bannerUntil = 0L
    private var signedSeenAt = 0L

    /** What the run knows about today's CLOCK IN (from the save and the signing flow). */
    data class ClockUi(
        val open: Boolean = false,
        val signed: Boolean = false,
        val busy: Boolean = false,
        val wallet: Boolean = true,
        val dayLine: String = "",
        val proofLine: String = "",
        val error: String = "",
    )

    /** The sign block shared by the pause and result cards: sign button or the web day card. */
    private inner class SignSection {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(alpha(PRIMARY, 0.22f), alpha(PRIMARY, 0.08f))).apply {
                cornerRadius = dp(14).toFloat(); setStroke(dp(1.5f), alpha(PRIMARY, 0.55f))
            }
            setPadding(dp(14), dp(12), dp(14), dp(14))
            visibility = GONE
        }
        val kicker = label(ctx.getString(R.string.run_goal_label, GameSave.GOAL_M), 12f, PRIMARY, track = 0.16f)
        val title = label("", 20f)
        val sub = label("", 13f, MUTED, body)
        val btn = button(ctx.getString(R.string.run_sign_btn), true, 56, 22f, null) { onSignTap() }
        val day = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = GONE }
        val dayTitle = label("", 18f).apply { gravity = Gravity.CENTER }
        val dayLine = label("", 14f, FG, body).apply { gravity = Gravity.CENTER }
        val proof = label("", 12f, MUTED, body).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val share = button(ctx.getString(R.string.run_share), true, 44, 15f, R.drawable.ic_share) { actions.share() }
        val err = label("", 12f, DANGER, body).apply { gravity = Gravity.CENTER; visibility = GONE }

        init {
            root.addView(kicker)
            root.addView(title, lp(top = 2))
            root.addView(sub, lp(top = 4))
            root.addView(btn, lp(top = 12, h = 60))
            root.addView(err, lp(top = 6))
            day.addView(dayTitle)
            day.addView(dayLine, lp(top = 4))
            day.addView(proof, lp(top = 4))
            day.addView(share, lp(top = 10, h = 48))
            root.addView(day, lp(top = 4))
        }

        private fun onSignTap() {
            if (!clock.wallet) actions.yardSign() else actions.sign()
        }

        fun bind(c: ClockUi, pausedCard: Boolean) {
            root.visibility = if (c.open || c.signed) VISIBLE else GONE
            if (root.visibility == GONE) return
            val signed = c.signed
            kicker.visibility = if (signed) GONE else VISIBLE
            title.visibility = if (signed) GONE else VISIBLE
            sub.visibility = if (signed) GONE else VISIBLE
            btn.visibility = if (signed) GONE else VISIBLE
            day.visibility = if (signed) VISIBLE else GONE
            title.text = ctx.getString(if (pausedCard) R.string.run_clock_title else R.string.run_sign_now)
            sub.text = when {
                !c.wallet -> ctx.getString(R.string.run_no_wallet)
                pausedCard -> ctx.getString(R.string.run_sign_pause_sub)
                else -> ctx.getString(R.string.run_clock_keep)
            }
            btn.text = ctx.getString(
                when {
                    c.busy -> R.string.run_signing
                    !c.wallet -> R.string.run_connect_yard
                    else -> R.string.run_sign_btn
                },
            )
            btn.textSize = if (c.wallet) 22f else 16f
            btn.isEnabled = !c.busy
            btn.alpha = if (c.busy) 0.6f else 1f
            err.text = c.error
            err.visibility = if (c.error.isNotEmpty() && !signed) VISIBLE else GONE
            dayTitle.text = ctx.getString(R.string.run_signed_title)
            dayLine.text = c.dayLine
            proof.text = c.proofLine
            proof.visibility = if (c.proofLine.isEmpty()) GONE else VISIBLE
        }
    }

    init {
        clipChildren = false
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // top-left: pause · hearts · score · suns
        val tl = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        pauseBtn = iconButton(R.drawable.ic_run_pause, darkButton(), 44, 20, FG, ctx.getString(R.string.run_pause)) { actions.pauseToggle() }
        pauseIcon = pauseBtn.getChildAt(0) as ImageView
        (pauseIcon.layoutParams as LayoutParams).bottomMargin = dp(4)
        tl.addView(pauseBtn, LinearLayout.LayoutParams(dp(44), dp(46)).apply { marginEnd = dp(8) })
        hearts.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = chip()
            setPadding(dp(10), dp(6), dp(10), dp(6))
            clipChildren = false
        }
        for (i in 0 until RunSim.HEARTS) {
            val hv = icon(R.drawable.ic_run_heart, PRIMARY, 22)
            heartViews += hv
            hearts.addView(hv, LinearLayout.LayoutParams(dp(22), dp(22)).apply { if (i > 0) marginStart = dp(4) })
        }
        shieldView = icon(R.drawable.ic_run_shield, OK, 22)
        hearts.addView(shieldView, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(4) })
        tl.addView(hearts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
        scoreV = label("0", 19f).apply {
            background = chip()
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER
            minWidth = dp(36)
        }
        tl.addView(scoreV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
        // the same dark glass as every chip; the gold lives in the sun icon and the digits
        sunsChip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = chip()
            setPadding(dp(9), 0, dp(12), 0)
        }
        sunsIcon = icon(R.drawable.ic_sun, PRIMARY, 20)
        sunsChip.addView(sunsIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(5) })
        sunsV = label("0", 19f, PRIMARY).apply { gravity = Gravity.CENTER_VERTICAL }
        sunsChip.addView(sunsV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tl.addView(sunsChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        hud.addView(tl, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(12); topMargin = dp(12) })

        // top-right: distance · time of day · grind / flight
        val tr = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        distV = label("0m", 19f).apply { background = chip(); setPadding(dp(12), dp(6), dp(12), dp(6)) }
        tr.addView(distV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        chapterV = label("", 11f, PRIMARY, track = 0.025f).apply { background = chip(small = true); setPadding(dp(8), dp(3), dp(8), dp(3)) }
        tr.addView(chapterV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        grindV = label(ctx.getString(R.string.run_grind), 13f, OK, track = 0.025f).apply { background = chip(small = true); setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = GONE }
        tr.addView(grindV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        flightV = label("", 13f, PRIMARY, track = 0.025f).apply { background = chip(small = true); setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = GONE }
        tr.addView(flightV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        hud.addView(tr, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { rightMargin = dp(12); topMargin = dp(12) })

        // top-centre: the CLOCK IN Sign badge (persistent once unlocked) + quest toasts under it
        clockBadge = label(ctx.getString(R.string.run_sign_badge), 16f, PRIMARY_FG).apply {
            background = goldButton()
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(16), dp(4))
            minHeight = dp(44)
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(R.string.run_sign_badge_a11y)
            visibility = GONE
            setOnClickListener { if (!clock.signed && !clock.busy) actions.signBadge() }
        }
        hud.addView(clockBadge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(46), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        // 0.21.7: a small one-line toast at the right edge under the slide button, never mid-screen
        questToast = label("", 12f, FG).apply {
            background = chip()
            setPadding(dp(10), dp(5), dp(10), dp(5))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(260)
            visibility = GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        hud.addView(questToast, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = dp(84); rightMargin = dp(12) })

        // bottom hint / garden banner
        hintV = label(ctx.getString(R.string.run_hint), 14f, alpha(FG, 0.8f), track = 0.025f).apply { gravity = Gravity.CENTER; setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), alpha(Color.BLACK, 0.45f)) }
        hud.addView(hintV, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        gardenV = label(ctx.getString(R.string.run_garden), 16f, PRIMARY_FG).apply {
            background = box(PRIMARY, 10f); setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.CENTER; visibility = GONE
        }
        hud.addView(gardenV, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })

        // countdown + chapter banner (positioned by percentage in onLayout)
        countdownV = label("", 64f, PRIMARY).apply { gravity = Gravity.CENTER; setShadowLayer(dp(4).toFloat(), 0f, dp(3).toFloat(), Color.parseColor("#8A143A8C")); visibility = GONE }
        hud.addView(countdownV, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        announceV = label("", 38f, PRIMARY, track = 0.025f).apply { gravity = Gravity.CENTER; setShadowLayer(dp(4).toFloat(), 0f, dp(3).toFloat(), Color.parseColor("#8A143A8C")); visibility = GONE }
        hud.addView(announceV, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // CLOCK IN unlocked banner (brief; the run keeps going under it).
        // 0.21.8: a small one-line horizontal chip at the top that auto-hides (on the SM-X210 tablet the big
        // two-line banner showed up as vertical text at the left edge); its lines can never wrap per letter.
        clockBanner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
            background = chip()
            setPadding(dp(14), dp(6), dp(14), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            isClickable = false
            tag = "clock-banner"
        }
        clockBannerTitle = label(ctx.getString(R.string.run_clock_unlocked), 17f, PRIMARY, track = 0.02f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setHorizontallyScrolling(false); tag = "clock-banner-title"
        }
        clockBanner.addView(clockBannerTitle)
        clockBannerSub = label(ctx.getString(R.string.run_clock_keep), 13f, FG, body).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; tag = "clock-banner-sub"
        }
        clockBanner.addView(clockBannerSub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        hud.addView(clockBanner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(108) })

        // distance milestone: a glass chip that drops in under the top bar, then lifts away
        milestoneV = label("", 18f, PRIMARY, track = 0.03f).apply {
            background = chip()
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(6), dp(18), dp(6))
            visibility = GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        hud.addView(milestoneV, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(64) })

        // Sol caption + mic + music
        // 0.21.7: Sol's line is a small chip in the sky under the hearts (top-left, next to Sol's face),
        // at most two short lines, auto-hidden by RunRadio. It used to sit low-left over the hero's path.
        captionRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
        buddy = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        captionRow.addView(buddy, LinearLayout.LayoutParams(dp(22), dp(24)).apply { marginEnd = dp(5) })
        captionV = label("", 11f, FG, body).apply {
            // 0.21.8: never narrower than ~12 letters (a collapsed chip wrapped the line one letter per row)
            background = chip(); setPadding(dp(8), dp(4), dp(8), dp(4)); maxWidth = dp(CAPTION_W); minWidth = dp(CAPTION_MIN_W)
            setLineSpacing(0f, 1.1f); ellipsize = TextUtils.TruncateAt.END; maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        captionRow.addView(captionV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        hud.addView(captionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(12); topMargin = dp(62) })

        micBtn = iconButton(R.drawable.ic_run_mic, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PRIMARY); setStroke(dp(3), Color.parseColor("#A8650E")) }, 56, 24, PRIMARY_FG, ctx.getString(R.string.run_talk)) { actions.mic() }
        hud.addView(micBtn, LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(12); bottomMargin = dp(67) })
        musicBtn = iconButton(R.drawable.ic_run_volume, darkButton(), 44, 20, FG, ctx.getString(R.string.run_mute)) { actions.musicToggle() }
        musicIcon = musicBtn.getChildAt(0) as ImageView
        (musicIcon.layoutParams as LayoutParams).bottomMargin = dp(4)
        hud.addView(musicBtn, LayoutParams(dp(44), dp(46), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(12); bottomMargin = dp(16) })

        // slide button (hold)
        slideBtn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = darkButton()
            minimumWidth = dp(64)
            setPadding(dp(14), 0, dp(14), dp(4))
            contentDescription = ctx.getString(R.string.run_slide)
            isClickable = true
            addView(icon(R.drawable.ic_run_slide, FG, 20))
            addView(label(ctx.getString(R.string.run_slide), 15f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; actions.slideDown() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; actions.slideUp() }
                }
                true
            }
            // TalkBack / switch access click: one slide press (touch uses the hold above)
            setOnClickListener { actions.slideDown(); actions.slideUp() }
        }
        hud.addView(slideBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(64), Gravity.END or Gravity.TOP).apply { rightMargin = dp(12) })

        // ---- pause card (also the sign sheet when the badge is tapped) ----
        pauseLayer.visibility = GONE
        pauseLayer.isClickable = true
        pauseLayer.setBackgroundColor(alpha(Color.BLACK, 0.35f))
        pauseCard = card()
        val pauseCols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val pauseLeft = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pauseLeft.addView(label(ctx.getString(R.string.run_paused), 14f, PRIMARY, track = 0.16f))
        pauseLeft.addView(label(ctx.getString(R.string.run_breath), 30f), lp(top = 4))
        pauseStats = label("", 14f, MUTED, body)
        pauseLeft.addView(pauseStats, lp(top = 8))
        pauseLeft.addView(button(ctx.getString(R.string.run_resume), true, 52, 18f, R.drawable.ic_run_play) { actions.resume() }, lp(top = 20, h = 56))
        pauseLeft.addView(button(ctx.getString(R.string.run_yard), false, 48, 15f, R.drawable.ic_run_home) { actions.yard() }, lp(top = 8, h = 50))
        pauseCols.addView(pauseLeft, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pauseSign = SignSection()
        if (runSigning) pauseCols.addView(pauseSign.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(20) })
        pauseCard.addView(pauseCols)
        pauseLayer.addView(scroller(pauseCard), LayoutParams(dp(CARD_W), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(pauseLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- death beat ----
        deathDim.visibility = GONE
        deathDim.setBackgroundColor(alpha(Color.BLACK, 0.38f))
        deathWord = label("", 40f, FG).apply { setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), alpha(Color.BLACK, 0.7f)) }
        deathDim.addView(deathWord, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(deathDim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- result card (dead): stats, CLOCK IN sign / day card, rewards reveal ----
        deadLayer.visibility = GONE
        deadLayer.isClickable = true
        deadLayer.setBackgroundColor(alpha(Color.BLACK, 0.5f))
        deadCard = card()
        val deadCols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val deadLeft = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val headRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headRow.addView(label(ctx.getString(if (runSigning) R.string.run_daily else R.string.run_result_kicker), 14f, PRIMARY, track = 0.16f).apply { tag = "run-result-kicker" }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        deadRibbon = label("", 12f, BG).apply { background = box(OK, 99f); setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = GONE }
        headRow.addView(deadRibbon)
        deadLeft.addView(headRow)
        deadTitle = label("", 30f)
        deadLeft.addView(deadTitle, lp(top = 4))
        // 1.2.10: the score, big (the new result card)
        deadScore = label("", 46f, PRIMARY).apply { tag = "run-result-score"; visibility = if (runSigning) GONE else VISIBLE }
        deadLeft.addView(deadScore, lp(top = 2))
        deadReached = label("", 14f, PRIMARY)
        deadLeft.addView(deadReached, lp(top = 4))
        deadStats = label("", 14f, MUTED, body)
        deadLeft.addView(deadStats, lp(top = 8))
        rewardsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(alpha(Color.WHITE, 0.05f)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), alpha(Color.WHITE, 0.1f)) }
            setPadding(dp(14), dp(10), dp(14), dp(12))
            visibility = GONE
        }
        val rewardsHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rewardsHead.addView(label(ctx.getString(R.string.run_rewards), 12f, MUTED, track = 0.16f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rewardsSuns = label("", 20f, PRIMARY).apply {
            setCompoundDrawablesRelative(sunIcon(PRIMARY, 20), null, null, null)
            compoundDrawablePadding = dp(4)
            gravity = Gravity.CENTER_VERTICAL
        }
        rewardsHead.addView(rewardsSuns)
        rewardsBox.addView(rewardsHead)
        rewardsRows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rewardsBox.addView(rewardsRows, lp(top = 4))
        deadLeft.addView(rewardsBox, lp(top = 12))
        // one row so both stay above the fold on a short landscape screen
        val deadButtons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        deadButtons.addView(button(ctx.getString(if (runSigning) R.string.run_again_full else R.string.run_play_again), true, 60, 22f, null) { actions.again() }.apply { tag = "run-again" },
            LinearLayout.LayoutParams(0, dp(64), if (runSigning) 1.7f else 1f))
        deadButtons.addView(button(ctx.getString(if (runSigning) R.string.run_yard else R.string.run_back_today), false, 60, 15f, R.drawable.ic_run_home) { actions.yard() }.apply { tag = "run-back-today"; maxLines = 1 },
            LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(10) })
        deadLeft.addView(deadButtons, lp(top = 14, h = 64))
        deadCols.addView(deadLeft, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        deadSign = SignSection()
        if (runSigning) deadCols.addView(deadSign.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(20) })
        deadCard.addView(deadCols)
        deadLayer.addView(scroller(deadCard), LayoutParams(dp(CARD_W), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(deadLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- loading cover (first frames are warmed behind it; the countdown starts after) ----
        loadingLayer.setBackgroundColor(BG)
        loadingLayer.isClickable = true
        loadingLayer.visibility = GONE
        val loadCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        loadCol.addView(android.widget.ProgressBar(ctx).apply { indeterminateTintList = ColorStateList.valueOf(PRIMARY) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        loadCol.addView(label(ctx.getString(R.string.run_loading), 16f, FG), lp(top = 14))
        loadingLayer.addView(loadCol, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(loadingLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun scroller(card: View) = android.widget.ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(0, dp(16), 0, dp(16))
        addView(card)
    }

    private fun lp(top: Int = 0, h: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (h > 0) dp(h) else LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#16303F"), BG)).apply {
            cornerRadius = dp(20).toFloat(); setStroke(dp(2), alpha(PRIMARY, 0.35f))
        }
        elevation = dp(12).toFloat()
        setPadding(dp(24), dp(22), dp(24), dp(22))
    }

    private fun button(text: String, primary: Boolean, hDp: Int, size: Float, iconRes: Int?, onTap: () -> Unit): TextView = label(text, size, if (primary) PRIMARY_FG else FG, if (primary) display else body).apply {
        gravity = Gravity.CENTER
        background = if (primary) goldButton() else darkButton()
        minHeight = dp(hDp)
        isClickable = true
        isFocusable = true
        if (iconRes != null) {
            val d = ResourcesCompat.getDrawable(resources, iconRes, null)?.mutate()
            d?.setTint(if (primary) PRIMARY_FG else FG)
            d?.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        setPadding(dp(12), 0, dp(12), dp(4)) // the face sits above the 4 dp edge
        if (iconRes != null) {
            // keep icon + label centred together
            post {
                val w = paint.measureText(this.text.toString()) + dp(24)
                val pad = ((width - w) / 2).toInt().coerceAtLeast(dp(12))
                setPadding(pad, 0, pad, dp(4))
            }
        }
        setOnClickListener { onTap() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val h = b - t
        // web: countdown top 28%, banner top 26%, slide button centred on 48%
        countdownV.translationY = h * 0.28f - countdownV.top
        announceV.translationY = h * 0.26f - announceV.top
        slideBtn.translationY = h * 0.48f - slideBtn.height / 2f - slideBtn.top
        val w = r - l
        if (w > 0 && h > 0) {
            val me = locMe; val it = locIcon
            getLocationInWindow(me); sunsIcon.getLocationInWindow(it)
            // aim at the sun icon on the chip's left
            val cx = it[0] - me[0] + sunsIcon.width / 2
            val cy = it[1] - me[1] + sunsIcon.height / 2
            onSunTarget?.invoke(cx.toDouble() / w, cy.toDouble() / h)
        }
    }

    /** A distance milestone: the chip drops in with a little overshoot, holds, then lifts and fades. */
    fun milestone(meters: Int) {
        milestoneV.text = ctx.getString(R.string.run_pop_milestone, meters)
        milestoneV.animate().cancel()
        milestoneV.visibility = VISIBLE
        if (!animations) { milestoneV.alpha = 1f; milestoneV.translationY = 0f; postDelayed({ milestoneV.visibility = GONE }, 1400); return }
        milestoneV.alpha = 0f; milestoneV.translationY = -dp(24).toFloat(); milestoneV.scaleX = 0.9f; milestoneV.scaleY = 0.9f
        milestoneV.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(OvershootInterpolator(2f)).withEndAction {
            milestoneV.animate().alpha(0f).translationY(-dp(14).toFloat()).setStartDelay(1100).setDuration(380).setInterpolator(DecelerateInterpolator())
                .withEndAction { milestoneV.visibility = GONE }.start()
        }.start()
    }

    /** Safe-area insets (display cutout) for the HUD, like env(safe-area-inset-*). */
    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        hud.setPadding(left, top, right, bottom)
    }

    fun setMuted(muted: Boolean) {
        musicIcon.setImageResource(if (muted) R.drawable.ic_run_volume_off else R.drawable.ic_run_volume)
        musicBtn.contentDescription = ctx.getString(if (muted) R.string.run_unmute else R.string.run_mute)
    }

    fun setCaption(text: String) {
        captionV.text = cleanCaption(text)
        captionRow.visibility = if (text.isEmpty() || last?.phase == Phase.DEAD) GONE else VISIBLE
    }

    fun setLoading(on: Boolean) {
        loading = on
        if (!on && animations && loadingLayer.visibility == VISIBLE) {
            loadingLayer.animate().alpha(0f).setDuration(220).withEndAction { loadingLayer.alpha = 1f; refreshVisibility() }.start()
            loading = false
            return
        }
        refreshVisibility()
    }

    val isLoading: Boolean get() = loading

    fun setListening(on: Boolean) {
        listening = on
        (micBtn.background as? GradientDrawable)?.setColor(if (on) DANGER else PRIMARY)
        micBtn.contentDescription = ctx.getString(if (on) R.string.run_talk_stop else R.string.run_talk)
        (micBtn.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (on) FG else PRIMARY_FG)
    }

    fun setMicAvailable(on: Boolean) { micBtn.tag = on; refreshVisibility() }

    fun setPaused(p: Boolean) {
        val was = paused
        paused = p
        pauseIcon.setImageResource(if (p) R.drawable.ic_run_play else R.drawable.ic_run_pause)
        pauseBtn.contentDescription = ctx.getString(if (p) R.string.run_resume else R.string.run_pause)
        last?.let { pauseStats.text = ctx.getString(R.string.run_stats, it.meters, it.score, it.suns) }
        pauseSign.bind(clock, pausedCard = true)
        refreshVisibility()
        if (p && !was) popIn(pauseCard)
    }

    /** The CLOCK IN moment at the goal: banner for a few seconds, then the badge stays. */
    fun celebrateClock(now: Long = android.os.SystemClock.uptimeMillis()) {
        // 0.22.0: today already signed (the player keeps running for fun / a record): no "sign the day" offer
        if (clock.signed) {
            clockBannerTitle.text = ctx.getString(R.string.run_clock_done_title, GameSave.GOAL_M)
            // 0.22.1: the CLOCK IN day is the UTC day; say when the next one opens, in the phone's own time
            clockBannerSub.text = ctx.getString(R.string.run_clock_done_sub, net.solardepin.solarchik.ui.Fmt.clock(net.solardepin.solarchik.core.StreakRules.nextDayStart(System.currentTimeMillis())))
            clockBannerSub.visibility = VISIBLE
        } else {
            clockBannerTitle.text = ctx.getString(R.string.run_clock_unlocked)
            clockBannerSub.text = ctx.getString(R.string.run_clock_keep)
            clockBannerSub.visibility = VISIBLE
        }
        bannerUntil = now + BANNER_MS
        refreshVisibility()
        popIn(clockBanner)
        bump(clockBadge, 1.4f)
        postDelayed({ refreshVisibility() }, BANNER_MS + 30)
    }

    /** A daily quest finished mid-run. */
    fun questDone(text: String) {
        questToast.text = text
        questToast.visibility = VISIBLE
        popIn(questToast)
        bump(sunsChip)
        val shown = text
        postDelayed({ if (questToast.text == shown) questToast.visibility = GONE }, 2600)
    }

    fun setClock(c: ClockUi) {
        val wasSigned = clock.signed
        val wasBusy = clock.busy
        clock = c
        if (c.signed && !wasSigned && wasBusy) signedSeenAt = android.os.SystemClock.uptimeMillis()
        clockBadge.text = ctx.getString(
            when {
                c.signed -> R.string.run_signed_badge
                c.busy -> R.string.run_signing
                else -> R.string.run_sign_badge
            },
        )
        clockBadge.background = if (c.signed) greenButton() else goldButton()
        clockBadge.setCompoundDrawablesRelative(
            if (c.signed) ResourcesCompat.getDrawable(resources, R.drawable.ic_check, null)?.mutate()?.apply { setTint(PRIMARY_FG); setBounds(0, 0, dp(18), dp(18)) }
            else sunIcon(PRIMARY_FG, 18),
            null, null, null,
        )
        clockBadge.compoundDrawablePadding = dp(6)
        pauseSign.bind(c, pausedCard = true)
        deadSign.bind(c, pausedCard = false)
        val wide = runSigning && (c.open || c.signed)
        for (layer in arrayOf(pauseLayer, deadLayer)) {
            val sv = layer.getChildAt(0)
            val lpw = sv.layoutParams as LayoutParams
            val target = dp(if (wide) CARD_W_WIDE else CARD_W)
            if (lpw.width != target) { lpw.width = target; sv.layoutParams = lpw }
        }
        if (c.signed && !wasSigned && wasBusy) postDelayed({ refreshVisibility() }, SIGNED_BADGE_MS + 30)
        refreshVisibility()
    }

    /** Result card rewards: suns from the run (+ quest / milestone payouts), revealed one by one. */
    fun showRewards(runSuns: Int, rows: List<Pair<String, Int>>, newBest: Boolean) {
        val total = runSuns + rows.sumOf { it.second }
        rewardsBox.visibility = VISIBLE
        rewardsRows.removeAllViews()
        val all = (if (newBest) listOf(ctx.getString(R.string.run_new_best) to 0) else emptyList()) + rows
        all.forEachIndexed { i, (text, n) ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val check = icon(if (n == 0) R.drawable.ic_flame else R.drawable.ic_check, if (n == 0) Color.parseColor("#FF9A2A") else OK, 16)
            row.addView(check, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
            row.addView(label(text, 14f, FG, body).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (n > 0) row.addView(label(ctx.getString(R.string.run_reward_plus, n), 15f, PRIMARY))
            rewardsRows.addView(row, lp(top = 8))
            popIn(row, 350L + i * 160L)
        }
        if (animations && total > 0) {
            ValueAnimator.ofInt(0, total).apply {
                duration = 700
                interpolator = DecelerateInterpolator()
                addUpdateListener { rewardsSuns.text = ctx.getString(R.string.run_reward_plus, it.animatedValue as Int) }
                start()
            }
        } else {
            rewardsSuns.text = ctx.getString(R.string.run_reward_plus, total)
        }
        rewardsSuns.contentDescription = ctx.getString(R.string.run_suns_earned, total)
    }

    private fun refreshVisibility() {
        val h = last
        val dead = h?.phase == Phase.DEAD
        val live = h != null && (h.phase == Phase.RUNNING || h.phase == Phase.COUNTDOWN)
        val now = android.os.SystemClock.uptimeMillis()
        val banner = live && !dead && now < bannerUntil
        pauseBtn.visibility = if (dead) GONE else VISIBLE
        pauseLayer.visibility = if (paused && !dead) VISIBLE else GONE
        slideBtn.visibility = if (!dead && !paused && h?.bonus != true) VISIBLE else GONE
        micBtn.visibility = if (live && !paused && micBtn.tag != false) VISIBLE else GONE
        val wantHint = h?.hint == true && !paused
        if (wantHint) {
            hintV.animate().cancel()
            hintV.alpha = 1f
            hintV.visibility = VISIBLE
        } else if (hintV.visibility == VISIBLE && hintV.alpha == 1f && animations && !paused && h?.phase == Phase.RUNNING) {
            // fade out instead of popping off
            hintV.animate().alpha(0f).setDuration(500).withEndAction { hintV.visibility = GONE }.start()
        } else if (!animations || paused || h?.phase != Phase.RUNNING) {
            hintV.animate().cancel()
            hintV.visibility = GONE
        }
        gardenV.visibility = if (h?.bonus == true) VISIBLE else GONE
        countdownV.visibility = if (h?.phase == Phase.COUNTDOWN && !paused) VISIBLE else GONE
        val wasAnnounce = announceV.visibility == VISIBLE
        announceV.visibility = if (h != null && h.announceOn && h.phase == Phase.RUNNING && h.announce.isNotEmpty() && !banner) VISIBLE else GONE
        if (announceV.visibility == VISIBLE && !wasAnnounce) bannerIn(announceV)
        clockBanner.visibility = if (runSigning && banner && !paused) VISIBLE else GONE
        val signedFresh = clock.signed && now - signedSeenAt < SIGNED_BADGE_MS
        clockBadge.visibility = if (runSigning && live && !paused && clock.open && (!clock.signed || signedFresh)) VISIBLE else GONE
        val cardDue = dead && android.os.SystemClock.uptimeMillis() - deadSince >= deathBeatMs
        deadLayer.visibility = if (cardDue) VISIBLE else GONE
        deathDim.visibility = if (dead && !cardDue) VISIBLE else GONE
        if (dead) { questToast.visibility = GONE; captionRow.visibility = GONE }
        if (!live || paused) questToast.visibility = GONE
        loadingLayer.visibility = if (loading) VISIBLE else GONE
    }

    /** Score chip: rolls up to [score] (web: plain tabular digits); jumps straight there on a new run or without animations. */
    private fun setScore(score: Int, prev: RunHud?) {
        val fresh = prev == null || score < shownScore || prev.phase == Phase.COUNTDOWN
        if (!animations || fresh) {
            scoreAnim?.cancel()
            shownScore = score
            scoreV.text = String.format(java.util.Locale.ROOT, "%d", score)
            return
        }
        if (score == shownScore) return
        scoreAnim?.cancel()
        val from = shownScore
        scoreAnim = ValueAnimator.ofInt(from, score).apply {
            duration = if (score - from > 200) 420 else 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { shownScore = it.animatedValue as Int; scoreV.text = String.format(java.util.Locale.ROOT, "%d", shownScore) }
            start()
        }
        if (score - from >= 100) bump(scoreV, 1.12f)
    }

    fun bind(h: RunHud) {
        val prev = last
        last = h
        for (i in heartViews.indices) {
            val on = i < h.hearts
            heartViews[i].setImageResource(if (on) R.drawable.ic_run_heart else R.drawable.ic_run_heart_empty)
            heartViews[i].imageTintList = ColorStateList.valueOf(if (on) PRIMARY else alpha(FG, 0.25f))
        }
        if (prev != null && h.hearts < prev.hearts && h.hearts in 0 until heartViews.size) bump(heartViews[h.hearts], 1.7f)
        shieldView.visibility = if (h.shield > 0) VISIBLE else GONE
        if (prev != null && h.shield > prev.shield) bump(shieldView, 1.6f)
        hearts.contentDescription = ctx.getString(if (h.shield > 0) R.string.run_hearts_shield_a11y else R.string.run_hearts_a11y, h.hearts)
        setScore(h.score, prev)
        scoreV.contentDescription = ctx.getString(R.string.run_score_a11y, h.score)
        sunsV.text = String.format(java.util.Locale.ROOT, "%d", h.suns)
        sunsV.contentDescription = ctx.getString(R.string.run_suns_earned, h.suns)
        // the chip pops when the flying sun lands in it (~0.5 s after the pickup)
        if (prev != null && h.suns > prev.suns) { if (animations) postDelayed({ bump(sunsChip, 1.16f) }, 480) else bump(sunsChip, 1.16f) }
        distV.text = ctx.getString(R.string.run_meters, h.meters)
        chapterV.text = ctx.getString(chapterRes(h.chapter))
        grindV.visibility = if (h.grind && !h.bonus) VISIBLE else GONE
        flightV.visibility = if (h.bonus) VISIBLE else GONE
        if (h.bonus) flightV.text = ctx.getString(R.string.run_flight, ceil(h.bonusLeft).toInt())
        countdownV.text = if (h.countdown > 0.28) ceil(h.countdown).toInt().toString() else ctx.getString(R.string.run_go)
        announceV.text = if (h.announce.isEmpty()) "" else if (h.announce == RunSim.BOSS_BANNER) ctx.getString(R.string.run_boss_banner) else ctx.getString(bannerRes(h.chapter))
        if (h.phase == Phase.DEAD) {
            deadTitle.text = ctx.getString(if (h.death == DeathKind.HIT) R.string.run_hit else R.string.run_fell)
            deadReached.text = ctx.getString(R.string.run_reached, ctx.getString(chapterRes(h.chapter)))
            val heat = ctx.getString(R.string.run_stats_heat, h.meters, h.score, h.suns, h.maxCombo)
            deadStats.text = if (h.didBonus) ctx.getString(R.string.run_stats_garden, heat) else heat
            deadRibbon.text = ctx.getString(R.string.run_clocked_ribbon, GameSave.GOAL_M)
            deadRibbon.visibility = if (runSigning && h.clockOpen) VISIBLE else GONE
            deadScore.text = ctx.getString(R.string.run_result_score, h.score)
            if (prev?.phase != Phase.DEAD) {
                rewardsBox.visibility = GONE
                deadSince = android.os.SystemClock.uptimeMillis()
                // death beat: dim + the fall/hit word first, the card after DEATH_BEAT_MS
                deathWord.text = deadTitle.text
                deathDim.alpha = 0f
                if (animations) deathDim.animate().alpha(1f).setDuration(320).start() else deathDim.alpha = 1f
                popIn(deathWord)
                postDelayed({ refreshVisibility(); popIn(deadCard) }, deathBeatMs)
            }
        }
        refreshVisibility()
    }
}
