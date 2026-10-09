package net.solardepin.solarchik.ui.roof

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.SlicePrice
import net.solardepin.solarchik.agents.SlicePrices
import net.solardepin.solarchik.agents.SliceStocks
import net.solardepin.solarchik.agents.SliceStore
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.core.StreakRules
import net.solardepin.solarchik.ui.Fmt
import net.solardepin.solarchik.ui.Screen
import net.solardepin.solarchik.ui.SettingsScreen
import net.solardepin.solarchik.ui.Ui
import net.solardepin.solarchik.ui.YardScreen
import kotlin.math.max
import kotlin.math.min

/**
 * 0.22.0 home: the rooftop. Every old menu destination is an object on the roof (door → run, punch clock
 * → today's CLOCK IN, antenna → calls, panels → agents/wallet, ticker → Slice, toolbox → settings, Sol →
 * chat, record plate → garage) and a row in the ☰ list. The floating nav is hidden here; the other tabs
 * show it and its Home button returns to the roof.
 */
class RooftopScreen(host: MainActivity) : Screen(host) {
    private val save get() = host.save
    val tour = RoofTour(PrefsKv(host))
    private val prefs = host.getSharedPreferences("solarchik-roof", android.content.Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    lateinit var roof: RooftopView
        private set
    private lateinit var root: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var hud: LinearLayout
    private lateinit var hudRow2: LinearLayout
    private lateinit var streakChip: TextView
    private lateinit var timeChip: TextView
    private lateinit var bubble: LinearLayout
    private lateinit var bubbleText: TextView
    private lateinit var bubbleTail: TailView
    private lateinit var cta: LinearLayout
    private lateinit var ctaSub: TextView
    private var ctaPulse: android.animation.ObjectAnimator? = null
    private lateinit var hint: TextView
    private lateinit var earnChip: TextView
    private lateinit var callChip: TextView
    private val tags = ArrayList<Tag>()
    private val leads = ArrayList<View>()
    private var sheet: View? = null
    private var card: View? = null

    private var unread = 0
    private var earnedSol = 0.0
    private var longLabels = false
    private var shown = false

    private inner class Tag(
        val obj: RoofObject,
        val view: FrameLayout,
        val title: TextView,
        val sub: TextView,
        val badge: TextView,
        /** Anchor (frame units): x = centre (or left edge when [leftLand]/[leftPort]), y = top. */
        val land: PointF,
        val port: PointF,
        val leadFrom: Float? = null,
        val leftPort: Boolean = false,
        val alwaysSub: Boolean = false,
    )

    // ------------------------------------------------------------------ build

    override fun build(): View {
        root = FrameLayout(ctx).apply { setBackgroundColor(Ui.BG); clipChildren = false }
        roof = RooftopView(ctx).apply {
            onObject = { onRoofObject(it) }
            onCameraChanged = { this@RooftopScreen.overlay.post { layoutOverlay() } }
        }
        root.addView(roof, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(ctx).apply { clipChildren = false }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        buildTags()
        buildBubble()
        buildChips()
        buildCta()
        buildHud()
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) overlay.post { applyReserve(); layoutOverlay() } }
        return root
    }

    private fun glass(radius: Float): GradientDrawable = Ui.rounded(Color.argb(168, 10, 18, 30), radius, Color.argb(48, 255, 255, 255), dp(1))

    private fun iconCircle(icon: Int, colors: IntArray, size: Int, tint: Int = Color.WHITE): FrameLayout = FrameLayout(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { shape = GradientDrawable.OVAL }
        addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(tint) }, FrameLayout.LayoutParams(size * 56 / 100, size * 56 / 100, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun buildTags() {
        fun tag(obj: RoofObject, icon: Int, colors: IntArray, title: Int, land: PointF, port: PointF, lead: Float? = null, leftPort: Boolean = false, alwaysSub: Boolean = false): Tag {
            val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glass(dp(22).toFloat())
                setPadding(dp(5), dp(5), dp(14), dp(5))
                elevation = dp(3).toFloat()
            }
            pill.addView(iconCircle(icon, colors, dp(26)))
            val col = Ui.column(ctx).apply { setPadding(dp(8), 0, 0, 0) }
            val t1 = Ui.text(ctx, ctx.getString(title), 13.5f, Color.WHITE, 800).apply { maxLines = 1 }
            val t2 = Ui.text(ctx, "", 11f, Color.argb(200, 232, 240, 247), 700).apply { maxLines = 1; visibility = View.GONE }
            col.addView(t1)
            col.addView(t2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
            pill.addView(col)
            frame.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            val badge = Ui.text(ctx, "", 11f, Color.WHITE, 800).apply {
                gravity = Gravity.CENTER
                background = Ui.rounded(Color.parseColor("#FF4D3D"), dp(10).toFloat(), Color.WHITE, dp(2))
                setPadding(dp(5), 0, dp(5), 0)
                minWidth = dp(20)
                elevation = dp(5).toFloat()
                visibility = View.GONE
                tag = "roof-calls-badge".takeIf { obj == RoofObject.ANTENNA }
            }
            frame.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)).apply { leftMargin = dp(20) })
            frame.isClickable = true
            pressable(frame)
            frame.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onRoofObject(obj) }
            frame.tag = "roof-tag-" + obj.name.lowercase()
            overlay.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return Tag(obj, frame, t1, t2, badge, land, port, lead, leftPort, alwaysSub).also { tags += it }
        }
        val blue = intArrayOf(Color.parseColor("#4C8DFF"), Color.parseColor("#2F5FE0"))
        tag(RoofObject.ANTENNA, R.drawable.ic_call, blue, R.string.roof_tag_calls, PointF(262f, 600f), PointF(282f, 640f), lead = 826f, leftPort = true)
        tag(RoofObject.PANELS, R.drawable.ic_nav_agents, intArrayOf(Color.parseColor("#FFC94A"), Color.parseColor("#F08A1E")), R.string.roof_tag_agents, PointF(665f, 716f), PointF(640f, 930f), lead = 866f)
        tag(RoofObject.TICKER, R.drawable.ic_slice, intArrayOf(Color.parseColor("#34D3B4"), Color.parseColor("#169C8C")), R.string.roof_tag_slice, PointF(1241f, 958f), PointF(1241f, 600f), lead = 870f)
        tag(RoofObject.TOOLBOX, R.drawable.ic_nav_settings, intArrayOf(Color.parseColor("#8A9AB0"), Color.parseColor("#55657C")), R.string.roof_tag_settings, PointF(432f, 1000f), PointF(385f, 1104f))
        tag(RoofObject.SOL, R.drawable.ic_nav_sol, intArrayOf(Color.parseColor("#FFB443"), Color.parseColor("#E9781C")), R.string.roof_tag_sol, PointF(960f, 1024f), PointF(960f, 1158f), alwaysSub = true)
        tag(RoofObject.CLOCK, R.drawable.ic_timer, intArrayOf(Color.parseColor("#FF5FA8"), Color.parseColor("#B8327A")), R.string.roof_tag_clock, PointF(RoofFrame.SIGN.centerX(), RoofFrame.SIGN.bottom + 6f), PointF(1490f, 936f), alwaysSub = true)
        for (i in 0 until 3) {
            val v = View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(30, 255, 255, 255)))
                visibility = View.GONE
            }
            overlay.addView(v, 0, FrameLayout.LayoutParams(dp(2), 10))
            leads += v
        }
    }

    /** Pressed feedback: the label dips a little under the finger, so it reads as a button. */
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    private fun buildBubble() {
        bubble = Ui.column(ctx).apply {
            background = Ui.rounded(Color.argb(242, 255, 255, 255), dp(18).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(12))
            elevation = dp(8).toFloat()
            tag = "roof-bubble"
            isClickable = true
            setOnClickListener { onRoofObject(RoofObject.SOL) }
        }
        bubble.addView(Ui.text(ctx, ctx.getString(R.string.roof_tag_sol).uppercase(), 10f, Color.parseColor("#D9891A"), 800).apply { letterSpacing = 0.14f })
        bubbleText = Ui.text(ctx, "", 14.5f, Color.parseColor("#132130"), 700).apply { setLineSpacing(0f, 1.18f) }
        bubble.addView(bubbleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        overlay.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bubbleTail = TailView(ctx).apply { elevation = dp(8).toFloat() }
        overlay.addView(bubbleTail, FrameLayout.LayoutParams(dp(18), dp(14)))
    }

    private fun buildChips() {
        earnChip = Ui.text(ctx, "", 13f, Ui.INK, 800).apply {
            background = Ui.gradient(intArrayOf(Color.parseColor("#FFE07A"), Color.parseColor("#F3A92C")), dp(16).toFloat(), GradientDrawable.Orientation.TOP_BOTTOM)
            setPadding(dp(10), dp(6), dp(12), dp(6))
            Ui.setIcon(this, R.drawable.ic_nav_agents, Ui.INK)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        overlay.addView(earnChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        callChip = Ui.text(ctx, ctx.getString(R.string.roof_call_chip), 12.5f, Color.WHITE, 800).apply {
            background = Ui.rounded(Color.argb(176, 10, 18, 30), dp(16).toFloat(), Color.argb(130, 255, 120, 100), dp(1))
            setPadding(dp(10), dp(6), dp(12), dp(6))
            Ui.setIcon(this, R.drawable.ic_call, Color.parseColor("#FF8A78"))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { onRoofObject(RoofObject.ANTENNA) }
        }
        overlay.addView(callChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildCta() {
        cta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.gradient(intArrayOf(Color.parseColor("#FFE07A"), Color.parseColor("#F8C23E"), Color.parseColor("#EE9A22")), dp(31).toFloat(), GradientDrawable.Orientation.TOP_BOTTOM)
            setPadding(dp(8), dp(8), dp(26), dp(8))
            elevation = dp(10).toFloat()
            isClickable = true
            foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(26).toFloat(), 0x33FFFFFF)
            tag = "roof-run"
            contentDescription = ctx.getString(R.string.roof_run)
            setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onRoofObject(RoofObject.DOOR) }
        }
        val play = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.INK) }
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_run_play); setColorFilter(Color.parseColor("#F8C23E")) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        cta.addView(play, LinearLayout.LayoutParams(dp(46), dp(46)))
        val ctaCol = Ui.column(ctx).apply { setPadding(dp(10), 0, 0, 0) }
        ctaCol.addView(Ui.text(ctx, ctx.getString(R.string.roof_run), 19f, Ui.INK, 900))
        ctaSub = Ui.text(ctx, "", 11.5f, Color.argb(200, 30, 24, 10), 700).apply { maxLines = 1; visibility = View.GONE }
        ctaCol.addView(ctaSub)
        cta.addView(ctaCol)
        if (android.os.Build.VERSION.SDK_INT >= 28) { cta.outlineSpotShadowColor = Color.parseColor("#FFC23E"); cta.outlineAmbientShadowColor = Color.parseColor("#FFC23E") }
        pressable(cta)
        ctaPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(cta,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.045f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.045f)).apply {
            duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        overlay.addView(cta, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hint = Ui.text(ctx, ctx.getString(R.string.roof_hint), 12f, Color.argb(220, 255, 255, 255), 700).apply {
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 1f, Color.argb(140, 0, 0, 0))
        }
        overlay.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun roundButton(icon: Int?, label: String?, cd: Int, tagName: String, onTap: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = glass(dp(21).toFloat())
        if (icon != null) addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        if (label != null) addView(Ui.text(ctx, label, 18f, Color.WHITE, 800).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        contentDescription = ctx.getString(cd)
        tag = tagName
        isClickable = true
        foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(21).toFloat(), 0x33FFFFFF)
        setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onTap() }
    }

    private fun chip(icon: Int, tint: Int): TextView = Ui.text(ctx, "", 13f, Color.WHITE, 700).apply {
        background = glass(dp(16).toFloat())
        setPadding(dp(9), dp(6), dp(12), dp(6))
        maxLines = 1
        Ui.setIcon(this, icon, tint)
    }

    private fun buildHud() {
        hud = Ui.column(ctx)
        val row1 = Ui.row(ctx, gap = dp(10)).apply { gravity = Gravity.CENTER_VERTICAL }
        row1.addView(Ui.display(ctx, ctx.getString(R.string.brand), 21f, Color.WHITE).apply { setShadowLayer(14f, 0f, 2f, Color.argb(70, 0, 0, 0)); maxLines = 1 })
        streakChip = chip(R.drawable.ic_flame, Color.parseColor("#FF9F3A")).apply { tag = "roof-streak" }
        timeChip = chip(R.drawable.ic_sun, Color.parseColor("#FFD45A"))
        row1.addView(streakChip)
        row1.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) // spacer (a MATCH_PARENT-high one stretched the HUD to the full screen)
        row1.addView(timeChip)
        row1.addView(roundButton(null, "?", R.string.roof_help_cd, "roof-help") { startTour(RoofTour.Variant.FULL) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        row1.addView(roundButton(R.drawable.ic_menu, null, R.string.roof_menu_cd, "roof-menu") { openSheet() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        hud.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hudRow2 = Ui.row(ctx, gap = dp(8))
        hud.addView(hudRow2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        overlay.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    /** Narrow screens (phones in portrait): streak + time move to a second HUD row. */
    private fun arrangeHud() {
        val narrow = root.width < dp(640)
        val row1 = hud.getChildAt(0) as LinearLayout
        fun move(v: View, to: LinearLayout, index: Int) {
            (v.parent as? ViewGroup)?.removeView(v)
            if (index < 0) to.addView(v) else to.addView(v, index)
        }
        if (narrow && streakChip.parent === row1) { move(streakChip, hudRow2, -1); move(timeChip, hudRow2, -1) }
        if (!narrow && streakChip.parent === hudRow2) { move(streakChip, row1, 1); move(timeChip, row1, 3) }
        hudRow2.visibility = if (narrow) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ layout

    override fun applyInsets() {
        if (!this::root.isInitialized) return
        hud.setPadding(dp(16), host.topInset + dp(12), dp(14), 0)
        applyReserve()
        overlay.post { layoutOverlay() }
    }

    private fun applyReserve() {
        if (!this::roof.isInitialized) return
        roof.bottomReservePx = (host.bottomInset + dp(104)).toFloat()
    }

    private fun layoutOverlay() {
        if (!this::roof.isInitialized || root.width == 0) return
        arrangeHud()
        val cam = roof.cam
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val edge = dp(10).toFloat()
        val bottomLimit = h - host.bottomInset - dp(6)
        val wide = !cam.portrait && root.width >= dp(1000)
        val long = wide && longLabels
        hud.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val hudBottom = host.topInset + dp(12) + hud.measuredHeight.toFloat()

        // CTA: on the step in front of the door (landscape) or centred at the bottom (portrait)
        cta.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        hint.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val ctaX: Float
        val ctaY: Float
        if (cam.portrait) {
            ctaX = (w - cta.measuredWidth) / 2f
            ctaY = bottomLimit - hint.measuredHeight - dp(10) - cta.measuredHeight
        } else {
            // one dominant PLAY button, bottom-right, like a game hub (the door behind it is the run's way in too)
            ctaX = w - dp(24) - cta.measuredWidth
            ctaY = bottomLimit - dp(14) - cta.measuredHeight
        }
        cta.translationX = ctaX; cta.translationY = ctaY
        hint.translationY = -(host.bottomInset + dp(6)).toFloat()
        hint.visibility = if (longLabels && card == null) View.VISIBLE else View.GONE

        // tags
        val placed = ArrayList<RectF>()
        placed += RectF(ctaX, ctaY, ctaX + cta.measuredWidth, ctaY + cta.measuredHeight)
        var leadI = 0
        leads.forEach { it.visibility = View.GONE }
        for (t in tags) {
            val showSub = t.alwaysSub || long || (t.obj == RoofObject.ANTENNA && unread > 0) || (t.obj == RoofObject.PANELS && roof.earned)
            t.sub.visibility = if (showSub && t.sub.text.isNotEmpty()) View.VISIBLE else View.GONE
            t.title.visibility = if (t.obj == RoofObject.CLOCK && !cam.portrait && t.sub.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (t.view.visibility == View.INVISIBLE) t.view.visibility = View.VISIBLE
            t.view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val mw = t.view.measuredWidth.toFloat()
            val mh = t.view.measuredHeight.toFloat()
            val a = if (cam.portrait) t.port else t.land
            val left0 = if (cam.portrait && t.leftPort) cam.x(a.x) else cam.x(a.x) - mw / 2f
            var x = left0.clampIn(edge, w - edge - mw)
            var y = cam.y(a.y).clampIn(hudBottom + dp(6), bottomLimit - mh)
            // nudge down past anything already placed (CTA, earlier tags)
            var r = RectF(x, y, x + mw, y + mh)
            var guard = 0
            while (placed.any { RectF.intersects(it, r) } && guard++ < 6) {
                val hit = placed.first { RectF.intersects(it, r) }
                y = hit.bottom + dp(4)
                if (y + mh > bottomLimit) { y = hit.top - mh - dp(4) }
                r = RectF(x, y, x + mw, y + mh)
            }
            // still on top of something (tight portrait bottom): slide sideways instead
            if (placed.any { RectF.intersects(it, r) }) {
                val hit = placed.first { RectF.intersects(it, r) }
                val y0 = r.top
                listOf(hit.right + dp(6), hit.left - mw - dp(6)).map { it.clampIn(edge, w - edge - mw) }
                    .map { RectF(it, y0, it + mw, y0 + mh) }
                    .firstOrNull { c -> placed.none { RectF.intersects(it, c) } }
                    ?.let { r = it; x = it.left }
            }
            // Sol himself is the big tap target: when his label has no room (portrait), leave it out
            if (t.obj == RoofObject.SOL && placed.any { RectF.intersects(it, r) }) {
                t.view.visibility = View.INVISIBLE
                continue
            }
            // last resort: icon only (the ☰ list and the tour still name it)
            if (placed.any { RectF.intersects(it, r) }) {
                t.sub.visibility = View.GONE
                t.title.visibility = View.GONE
                t.view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val iw = t.view.measuredWidth.toFloat()
                val ih = t.view.measuredHeight.toFloat()
                val hit = placed.first { RectF.intersects(it, r) }
                val y0 = r.top
                listOf(x, hit.left - iw - dp(6), hit.right + dp(6)).map { it.clampIn(edge, w - edge - iw) }
                    .map { RectF(it, y0, it + iw, y0 + ih) }
                    .let { c -> c.firstOrNull { k -> placed.none { RectF.intersects(it, k) } } ?: c.first() }
                    .let { r = it; x = it.left }
            }
            placed += r
            t.view.translationX = x
            t.view.translationY = y
            if (long && t.leadFrom != null && leadI < leads.size) {
                val lv = leads[leadI++]
                val y0 = cam.y(t.leadFrom)
                val y1 = y + dp(6)
                if (y1 - y0 > dp(8)) {
                    lv.layoutParams = (lv.layoutParams as FrameLayout.LayoutParams).apply { height = (y1 - y0).toInt() }
                    lv.translationX = cam.x(a.x) - dp(1)
                    lv.translationY = y0
                    lv.visibility = View.VISIBLE
                }
            }
        }

        // chips
        if (earnChip.visibility == View.VISIBLE) {
            earnChip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            earnChip.translationX = (cam.x(650f) - earnChip.measuredWidth / 2f).clampIn(edge, w - edge - earnChip.measuredWidth)
            earnChip.translationY = cam.y(712f) - earnChip.measuredHeight
        }
        if (callChip.visibility == View.VISIBLE) {
            callChip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            callChip.translationX = cam.x(292f)
            callChip.translationY = max(hudBottom + dp(4), cam.y(196f) - callChip.measuredHeight / 2f)
        }

        // Sol's bubble: left of his head (landscape) or above it (portrait)
        val maxBw = if (cam.portrait) min(dp(300), (w - 2 * edge).toInt()) else dp(280)
        bubble.measure(View.MeasureSpec.makeMeasureSpec(maxBw, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val bw = bubble.measuredWidth.toFloat()
        val bh = bubble.measuredHeight.toFloat()
        // pin the laid-out width to the measured one (WRAP_CONTENT let a long line run off the right edge)
        (bubble.layoutParams as FrameLayout.LayoutParams).let { if (it.width != bubble.measuredWidth) { it.width = bubble.measuredWidth; bubble.layoutParams = it } }
        val tail = bubbleTail
        val down = cam.portrait || bw > cam.x(822f) - edge
        if (tail.down != down) { tail.down = down; tail.invalidate() }
        if (down) {
            val bx = (cam.x(960f) - bw / 2f).clampIn(edge, max(edge, w - edge - bw))
            val by = max(hudBottom + dp(8), cam.y(556f) - bh - dp(12))
            bubble.translationX = bx; bubble.translationY = by
            tail.translationX = (cam.x(960f) - dp(9)).clampIn(bx + dp(16), max(bx + dp(16), bx + bw - dp(34))); tail.translationY = by + bh - 1
        } else {
            val right = cam.x(822f)
            val bx = (right - bw).coerceAtLeast(edge)
            val by = max(hudBottom + dp(8), cam.y(650f) - bh)
            bubble.translationX = bx; bubble.translationY = by
            tail.translationX = bx + bw - dp(4); tail.translationY = by + bh - dp(30)
        }
    }

    // ------------------------------------------------------------------ data

    override fun onShow() {
        shown = true
        roof.resetFly()
        overlay.alpha = 1f
        val offer = tour.onRoofVisit(isNewPlayer())
        longLabels = tour.longLabels()
        render()
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) ctaPulse?.start()
        refreshPrices()
        roof.start()
        // 0.22.3: the very first rooftop visit STARTS the tour (once, persisted by onRoofVisit); "Пропустити" on
        // every step, replay from "?". It waits until the activity is resumed (no permission dialog over it).
        if (offer && card == null) overlay.post { autoStartTour() }
        else if (tourSteps != null) showStep()
        debugHook?.invoke(this)
    }

    override fun onHide() {
        shown = false
        ctaPulse?.cancel(); cta.scaleX = 1f; cta.scaleY = 1f
        roof.stop()
        main.removeCallbacksAndMessages(null)
        voice?.stop()
        prefs.edit().putFloat("pnl_seen", host.desk.state().paperPnl.toFloat()).apply()
    }

    override fun onDestroy() {
        runCatching { voice?.shutdown() }
    }

    private fun isNewPlayer(): Boolean =
        save.bestDistance == 0 && save.liveStreak().streak == 0 && host.store.agents().isEmpty() && save.clockLog().isEmpty()

    override fun render() {
        if (!this::roof.isInitialized) return
        val lang = host.lang
        roof.lang = lang
        roof.mood = forcedMood ?: RoofSky.mood(System.currentTimeMillis())
        roof.bestMeters = save.bestDistance
        ctaSub.text = if (save.bestDistance > 0) ctx.getString(R.string.roof_menu_best, RoofText.meters(save.bestDistance, host.lang)) else ""
        ctaSub.visibility = if (save.bestDistance > 0) View.VISIBLE else View.GONE
        val st = save.liveStreak().streak
        roof.streak = st
        val punch = PunchState.of(save.clockedToday(), save.signedToday())
        roof.punch = punch
        renderCalls()
        // agents: paper P&L since the last visit lights the panels (labelled as paper)
        val pnl = host.desk.state().paperPnl
        if (!prefs.contains("pnl_seen")) prefs.edit().putFloat("pnl_seen", pnl.toFloat()).apply()
        earnedSol = pnl - prefs.getFloat("pnl_seen", pnl.toFloat())
        roof.earned = earnedSol >= 0.0005
        earnChip.visibility = if (roof.earned && tourSteps == null) View.VISIBLE else View.GONE
        earnChip.text = ctx.getString(R.string.roof_earned_chip, Fmt.sol(earnedSol, 3))
        // HUD
        streakChip.text = if (st > 0) ctx.resources.getQuantityString(R.plurals.roof_streak, st, st) else ctx.getString(R.string.roof_streak_zero)
        Ui.setIcon(streakChip, R.drawable.ic_flame, if (st > 0) Color.parseColor("#FF9F3A") else Color.argb(170, 232, 240, 247)) // the icon lives in the text span
        val net = ctx.getString(if (host.wallet.mainnet) R.string.network_mainnet else R.string.network_devnet)
        timeChip.text = ctx.getString(R.string.roof_time_net, Fmt.clock(System.currentTimeMillis()), net)
        Ui.setIcon(timeChip, if (roof.mood == RoofMood.NIGHT) R.drawable.ic_moon else R.drawable.ic_sun, if (roof.mood == RoofMood.NIGHT) Color.parseColor("#C9D6FF") else Color.parseColor("#FFD45A"))
        // tags
        val running = host.desk.state().runs.count { it.running }
        tag(RoofObject.PANELS).sub.text = if (roof.earned) "+" + Fmt.sol(earnedSol, 3) + " SOL" else if (running > 0) ctx.resources.getQuantityString(R.plurals.home_agents_running, running, running) else ctx.getString(R.string.roof_tag_agents_sub)
        tag(RoofObject.PANELS).sub.setTextColor(if (roof.earned) Color.parseColor("#FFD36A") else Color.argb(200, 232, 240, 247))
        tag(RoofObject.TICKER).sub.text = ctx.getString(R.string.roof_tag_slice_sub)
        tag(RoofObject.TOOLBOX).sub.text = ctx.getString(R.string.roof_tag_settings_sub)
        tag(RoofObject.SOL).sub.text = ctx.getString(R.string.roof_tag_sol_sub)
        val clock = tag(RoofObject.CLOCK)
        clock.sub.text = when (punch) {
            PunchState.NEED_RUN -> ctx.getString(R.string.roof_clock_need, SolarchikConfig.RUN_GOAL_M)
            PunchState.READY -> ctx.getString(R.string.roof_clock_ready)
            PunchState.DONE -> ctx.getString(R.string.roof_clock_done, st, Fmt.clock(StreakRules.nextDayStart(System.currentTimeMillis())))
        }
        clock.sub.setTextColor(when (punch) { PunchState.DONE -> Color.parseColor("#7DFFB8"); PunchState.READY -> Color.parseColor("#FFD36A"); else -> Color.parseColor("#FFB3D6") })
        renderTicker(lang)
        bubbleText.text = solLine(punch, st)
        val inTour = tourSteps != null || card != null
        bubble.visibility = if (inTour) View.GONE else View.VISIBLE
        bubbleTail.visibility = bubble.visibility
        overlay.post { layoutOverlay() }
    }

    private fun tag(o: RoofObject): Tag = tags.first { it.obj == o }

    fun renderCalls() {
        if (!this::roof.isInitialized) return
        val items = CallInbox.cached(ctx)
        unread = CallInbox.unread(items, CallInbox.seenAt(ctx))
        roof.callAlert = unread > 0
        val t = tag(RoofObject.ANTENNA)
        t.badge.visibility = if (unread > 0) View.VISIBLE else View.GONE
        t.badge.text = unread.toString()
        t.sub.text = if (unread > 0) ctx.resources.getQuantityString(R.plurals.roof_tag_calls_new, unread, unread) else ctx.getString(R.string.roof_tag_calls_sub)
        t.sub.setTextColor(if (unread > 0) Color.parseColor("#FF9A8A") else Color.argb(200, 232, 240, 247))
        t.view.contentDescription = ctx.getString(R.string.roof_tag_calls) + if (unread > 0) ", " + t.sub.text else ""
        callChip.visibility = if (unread > 0 && tourSteps == null && card == null) View.VISIBLE else View.GONE
        if (this::bubbleText.isInitialized && shown) {
            bubbleText.text = solLine(roof.punch, roof.streak)
            overlay.post { layoutOverlay() }
        }
    }

    /** Sol's line on the roof: a new call first, then today's CLOCK IN reminder, then agent earnings, then small talk. */
    private fun solLine(punch: PunchState, streak: Int): String = when (RoofLine.pick(unread, punch, roof.earned)) {
        RoofLine.Kind.CALL -> {
            val last = CallInbox.cached(ctx).firstOrNull()
            val who = last?.who.orEmpty()
            if (last != null && who.isNotBlank()) ctx.getString(R.string.roof_say_call, who, Fmt.clock(last.at)) else ctx.getString(R.string.roof_say_call_anon)
        }
        RoofLine.Kind.SIGN -> ctx.getString(R.string.roof_say_sign)
        RoofLine.Kind.RUN -> ctx.getString(R.string.roof_say_run, SolarchikConfig.RUN_GOAL_M)
        RoofLine.Kind.EARNED -> ctx.getString(R.string.roof_say_earned, Fmt.sol(earnedSol, 3))
        // after today's CLOCK IN: the streak line on odd visits, small talk about the sky on even ones
        RoofLine.Kind.DONE -> if (tour.visits % 2 == 1) ctx.getString(R.string.roof_say_done, streak) else ctx.getString(when (roof.mood) {
            RoofMood.DAY -> R.string.roof_say_day
            RoofMood.SUNSET -> R.string.roof_say_sunset
            RoofMood.NIGHT -> R.string.roof_say_night
        })
    }

    private fun renderTicker(lang: String) {
        val prices = cachedPrices
        val book = SliceStore(ctx).book()
        roof.tickerTitle = "SLICE"
        roof.tickerRight = ctx.getString(R.string.roof_ticker_paper, RoofText.usd(book.value(prices), lang))
        roof.tickerRows = TICKER_STOCKS.mapNotNull { t -> SliceStocks.all.firstOrNull { it.ticker == t } }.map { s ->
            val p = prices[s.mint]
            val ch = p?.change24h
            when {
                ch != null -> RooftopView.TickerRow(s.ticker, RoofText.pct(ch, lang), ch >= 0)
                p != null -> RooftopView.TickerRow(s.ticker, "$" + String.format(java.util.Locale.US, "%.0f", p.usd), null)
                else -> RooftopView.TickerRow(s.ticker, "—", null)
            }
        }
        roof.invalidate()
    }

    private fun refreshPrices() {
        if (!MainActivity.tickerEnabled) return
        if (System.currentTimeMillis() - pricesAt < 5 * 60_000L && cachedPrices.isNotEmpty()) return
        pricesAt = System.currentTimeMillis()
        host.scope.launch {
            val p = runCatching { withContext(Dispatchers.IO) { SlicePrices.fetch() } }.getOrDefault(emptyMap())
            if (p.isNotEmpty()) { cachedPrices = p; renderTicker(host.lang) }
        }
    }

    // ------------------------------------------------------------------ navigation

    private fun onRoofObject(o: RoofObject) {
        if (tourSteps != null || card != null) return
        when (o) {
            RoofObject.DOOR -> {
                overlay.animate().alpha(0f).setDuration(140).start()
                roof.flyIn { host.startRun() }
            }
            RoofObject.CLOCK -> openShift()
            RoofObject.PLATE -> host.select(MainActivity.Tab.RUN, animate = true)
            RoofObject.SOL -> host.select(MainActivity.Tab.SOL, animate = true)
            RoofObject.TICKER -> openAgents(AgentsScreen.WATCHER) // 1.1.0: prices live with the Watcher
            RoofObject.PANELS -> openAgents(0)
            RoofObject.ANTENNA -> host.openCalls()
            RoofObject.TOOLBOX -> host.select(MainActivity.Tab.SETTINGS, animate = true)
        }
    }

    /** Same as tapping [o] in the scene (tests). */
    @androidx.annotation.VisibleForTesting
    fun debugTap(o: RoofObject) = onRoofObject(o)

    private fun openShift() {
        host.select(MainActivity.Tab.SHIFT, animate = true)
        (host.screen(MainActivity.Tab.SHIFT) as? YardScreen)?.focusToday()
    }

    private fun openAgents(section: Int) {
        host.select(MainActivity.Tab.AGENTS, animate = true)
        (host.screen(MainActivity.Tab.AGENTS) as? AgentsScreen)?.openSection(section)
    }

    /** Back: closes the list or the tour first. */
    fun onBack(): Boolean {
        if (sheet != null) { closeSheet(); return true }
        if (card != null) { endTour(false); return true }
        return false
    }

    // ------------------------------------------------------------------ list menu

    fun openSheet() {
        if (sheet != null) return
        val portrait = roof.cam.portrait
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(120, 4, 10, 18))
            isClickable = true
            setOnClickListener { closeSheet() }
            tag = "roof-sheet"
        }
        val panel = Ui.column(ctx).apply {
            background = Ui.rounded(Color.argb(240, 9, 17, 28), dp(28).toFloat(), Color.argb(36, 255, 255, 255), dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(14))
            isClickable = true
            elevation = dp(20).toFloat()
        }
        val head = Ui.row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.weight(Ui.display(ctx, ctx.getString(R.string.roof_menu_title), 22f, Color.WHITE)))
        head.addView(roundButton(null, "✕", R.string.roof_close, "roof-sheet-close") { closeSheet() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(head)
        panel.addView(Ui.text(ctx, ctx.getString(R.string.roof_menu_sub), 12.5f, Color.argb(160, 220, 232, 242), 700), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(12) })
        val list = Ui.column(ctx, gap = dp(8))
        val punch = PunchState.of(save.clockedToday(), save.signedToday())
        val st = save.liveStreak().streak
        val calls = CallInbox.cached(ctx)
        fun row(icon: Int, colors: IntArray, title: String, sub: String, subColor: Int = Color.argb(170, 220, 232, 242), badge: Int = 0, primary: Boolean = false, tagName: String, go: () -> Unit) {
            val r = Ui.row(ctx, gap = dp(12)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = if (primary) Ui.rounded(Color.argb(46, 255, 205, 90), dp(18).toFloat(), Color.argb(96, 255, 205, 90), dp(1))
                else Ui.rounded(Color.argb(14, 255, 255, 255), dp(18).toFloat(), Color.argb(16, 255, 255, 255), dp(1))
                isClickable = true
                foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(18).toFloat(), 0x22FFFFFF)
                minimumHeight = dp(60)
                tag = tagName
                setOnClickListener { closeSheet(); go() }
            }
            val ic = FrameLayout(ctx).apply {
                background = Ui.gradient(colors, dp(13).toFloat())
                addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(if (primary) Ui.INK else Color.WHITE) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            r.addView(ic, LinearLayout.LayoutParams(dp(42), dp(42)))
            r.addView(Ui.weight(Ui.column(ctx).apply {
                addView(Ui.text(ctx, title, 15.5f, Color.WHITE, 800).apply { maxLines = 1 })
                addView(Ui.text(ctx, sub, 12f, subColor, 700).apply { maxLines = 2 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }))
            if (badge > 0) r.addView(Ui.text(ctx, badge.toString(), 12f, Color.WHITE, 800).apply {
                gravity = Gravity.CENTER; minWidth = dp(24)
                background = Ui.rounded(Color.parseColor("#FF4D3D"), dp(12).toFloat()); setPadding(dp(6), dp(3), dp(6), dp(3))
            })
            r.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(Color.argb(120, 255, 255, 255)) }, LinearLayout.LayoutParams(dp(18), dp(18)))
            list.addView(r)
        }
        val gold = intArrayOf(Color.parseColor("#FFD95A"), Color.parseColor("#EE9A22"))
        val pink = intArrayOf(Color.parseColor("#FF5FA8"), Color.parseColor("#B8327A"))
        val red = intArrayOf(Color.parseColor("#FF6A55"), Color.parseColor("#E0352A"))
        val blue = intArrayOf(Color.parseColor("#4C8DFF"), Color.parseColor("#2F5FE0"))
        val amber = intArrayOf(Color.parseColor("#FFB443"), Color.parseColor("#E9781C"))
        val agentC = intArrayOf(Color.parseColor("#FFC94A"), Color.parseColor("#F08A1E"))
        val teal = intArrayOf(Color.parseColor("#34D3B4"), Color.parseColor("#169C8C"))
        val grey = intArrayOf(Color.parseColor("#8A9AB0"), Color.parseColor("#55657C"))
        val violet = intArrayOf(Color.parseColor("#9D7BFF"), Color.parseColor("#5B3FD6"))
        row(R.drawable.ic_nav_run, gold, ctx.getString(R.string.roof_run), ctx.getString(R.string.roof_menu_best, RoofText.meters(save.bestDistance, host.lang)), primary = true, tagName = "roof-row-run") { host.startRun() }
        row(R.drawable.ic_timer, pink, ctx.getString(R.string.roof_menu_clock), tag(RoofObject.CLOCK).sub.text.toString(), subColor = if (punch == PunchState.DONE) Color.parseColor("#7DFFB8") else Color.parseColor("#FFB3D6"), tagName = "roof-row-clock") { openShift() }
        row(R.drawable.ic_call, red, ctx.getString(R.string.roof_menu_calls), when {
            unread > 0 -> ctx.resources.getQuantityString(R.plurals.roof_tag_calls_new, unread, unread)
            calls.isNotEmpty() -> ctx.getString(R.string.home_calls_last, calls.first().who.ifBlank { ctx.getString(R.string.calls_unknown) }, CallText.time(calls.first().at))
            else -> ctx.getString(R.string.roof_menu_calls_none)
        }, subColor = if (unread > 0) Color.parseColor("#FF9A8A") else Color.argb(170, 220, 232, 242), badge = unread, tagName = "roof-row-calls") { host.openCalls() }
        row(R.drawable.ic_call, blue, ctx.getString(R.string.roof_menu_secretary), secretaryState(), tagName = "roof-row-secretary") {
            host.select(MainActivity.Tab.SETTINGS, animate = true)
            (host.screen(MainActivity.Tab.SETTINGS) as? SettingsScreen)?.focusSecretary()
        }
        row(R.drawable.ic_nav_sol, amber, ctx.getString(R.string.roof_menu_sol), ctx.getString(R.string.roof_menu_sol_sub), tagName = "roof-row-sol") { host.select(MainActivity.Tab.SOL, animate = true) }
        row(R.drawable.ic_nav_agents, agentC, ctx.getString(R.string.roof_menu_agents), tag(RoofObject.PANELS).sub.text.toString().takeIf { roof.earned || host.desk.state().runs.any { it.running } } ?: ctx.getString(R.string.roof_menu_agents_sub), tagName = "roof-row-agents") { openAgents(0) }
        row(R.drawable.ic_wallet, violet, ctx.getString(R.string.roof_menu_strategies), ctx.getString(R.string.roof_menu_strategies_sub), tagName = "roof-row-strategies") { openAgents(1) }
        row(R.drawable.ic_slice, teal, ctx.getString(R.string.roof_menu_slice), ctx.getString(R.string.roof_menu_slice_sub, RoofText.usd(SliceStore(ctx).book().value(cachedPrices), host.lang)), tagName = "roof-row-slice") { openAgents(3) }
        row(R.drawable.ic_flame, intArrayOf(Color.parseColor("#FF9E6B"), Color.parseColor("#FF5E7E")), ctx.getString(R.string.roof_menu_garage), ctx.getString(R.string.roof_menu_garage_sub), tagName = "roof-row-garage") { host.select(MainActivity.Tab.RUN, animate = true) }
        row(R.drawable.ic_nav_settings, grey, ctx.getString(R.string.roof_menu_settings), ctx.getString(R.string.roof_menu_settings_sub), tagName = "roof-row-settings") { host.select(MainActivity.Tab.SETTINGS, animate = true) }
        row(R.drawable.ic_nav_sol, amber, ctx.getString(R.string.roof_menu_tour), ctx.getString(R.string.roof_menu_tour_sub), tagName = "roof-row-tour") { startTour(RoofTour.Variant.FULL) }
        row(R.drawable.ic_open, violet, ctx.getString(R.string.roof_menu_judges), ctx.getString(R.string.roof_menu_judges_sub), tagName = "roof-row-judges") { startTour(RoofTour.Variant.JUDGES) }
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
        panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val lp = if (portrait) FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (root.height * 0.82f).toInt(), Gravity.BOTTOM).apply {
            leftMargin = dp(8); rightMargin = dp(8); bottomMargin = host.bottomInset + dp(8)
        } else FrameLayout.LayoutParams(min(dp(440), root.width - dp(32)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = host.topInset + dp(14); bottomMargin = host.bottomInset + dp(14); rightMargin = dp(14)
        }
        scrim.addView(panel, lp)
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(160).start()
        panel.translationY = if (portrait) dp(40).toFloat() else 0f
        panel.translationX = if (portrait) 0f else dp(40).toFloat()
        panel.animate().translationX(0f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        sheet = scrim
    }

    private fun secretaryState(): String {
        val sec = net.solardepin.solarchik.screen.Secretary
        val sup = sec.supported()
        val on = sup && net.solardepin.solarchik.screen.PlayerIds.screeningOn(ctx) && sec.holdsRole(ctx)
        return ctx.getString(when { !sup -> R.string.home_sec_unsupported; on -> R.string.home_sec_on; else -> R.string.home_sec_off })
    }

    fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        s.animate().alpha(0f).setDuration(140).withEndAction { root.removeView(s) }.start()
    }

    // ------------------------------------------------------------------ tour

    private var tourSteps: List<TourStep>? = null
    private var tourVariant = RoofTour.Variant.FULL
    private var tourIndex = 0
    private var voice: net.solardepin.solarchik.sol.SolVoice? = null
    private var voiceOn = true

    private fun myProofs(): List<ProofLink> {
        val out = ArrayList<ProofLink>()
        save.clockLog().filter { it.kind == "tx" && it.sig.isNotBlank() && it.cluster == "devnet" }.maxByOrNull { it.at }?.let { out += ProofLink(R.string.judge_link_my_clock, it.sig, it.cluster) }
        host.store.agents().filter { it.sig.isNotBlank() && it.cluster == "devnet" }.lastOrNull()?.let { out += ProofLink(R.string.judge_link_my_mint, it.sig, it.cluster) }
        return out
    }

    fun startTour(v: RoofTour.Variant, at: Int = 0) {
        closeSheet()
        removeCard()
        tourVariant = v
        tourSteps = if (v == RoofTour.Variant.FULL) RoofTourSteps.full() else RoofTourSteps.judges(myProofs())
        tourIndex = at.coerceIn(0, tourSteps!!.size - 1)
        voiceOn = net.solardepin.solarchik.sol.SolChatStore(host).voiceOn && !forceMute
        render()
        setWorldUi(false)
        showStep()
    }

    private fun setWorldUi(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        tags.forEach { it.view.visibility = v }
        leads.forEach { if (!on) it.visibility = View.GONE }
        cta.visibility = v
        hint.visibility = if (on && longLabels) View.VISIBLE else View.GONE
        bubble.visibility = v; bubbleTail.visibility = v
        hud.visibility = v
        if (!on) { earnChip.visibility = View.GONE; callChip.visibility = View.GONE }
        if (on) { render(); renderCalls() }
    }

    private fun endTour(completed: Boolean) {
        val wasTour = tourSteps != null
        tourSteps = null
        main.removeCallbacksAndMessages(null)
        voice?.stop()
        roof.spotlight = null
        removeCard()
        tour.finish(completed)
        setWorldUi(true)
        if (wasTour && !completed) host.toast(ctx.getString(R.string.tour_replay_hint))
    }

    private fun removeCard() {
        card?.let { root.removeView(it) }
        card = null
    }

    private fun autoStartTour(tries: Int = 0) {
        if (!shown || card != null || tourSteps != null) return
        val resumed = host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (!resumed || root.width == 0) {
            if (tries < 40) overlay.postDelayed({ autoStartTour(tries + 1) }, 250)
            return
        }
        startTour(RoofTour.Variant.FULL)
    }

    @Suppress("unused") // 0.22.0 offer card, kept for a possible "ask first" variant
    private fun showOffer() {
        if (card != null || tourSteps != null) return
        setWorldUi(false)
        val c = cardShell(null)
        c.body.addView(Ui.text(ctx, ctx.getString(R.string.tour_offer_title), 19f, Color.WHITE, 800))
        c.body.addView(Ui.text(ctx, ctx.getString(R.string.tour_offer_body), 14.5f, Color.argb(225, 232, 240, 247), 500).apply { setLineSpacing(0f, 1.22f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val btns = Ui.row(ctx, gap = dp(8)).apply { gravity = Gravity.CENTER_VERTICAL }
        btns.addView(Ui.button(ctx, ctx.getString(R.string.tour_offer_go), Ui.Btn.PRIMARY) { removeCard(); startTour(RoofTour.Variant.FULL) }.apply { tag = "tour-offer-go" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Ui.button(ctx, ctx.getString(R.string.tour_offer_judges), Ui.Btn.SECONDARY) { removeCard(); startTour(RoofTour.Variant.JUDGES) }.apply { tag = "tour-offer-judges" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.body.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        c.body.addView(Ui.text(ctx, ctx.getString(R.string.tour_offer_later), 14f, Color.argb(190, 232, 240, 247), 700).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            tag = "tour-offer-later"
            setOnClickListener { removeCard(); roof.spotlight = null; tour.finish(false); setWorldUi(true); host.toast(ctx.getString(R.string.tour_replay_hint)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        roof.spotlight = RoofObject.SOL
        placeCard(c.view, RoofObject.SOL)
    }

    private class Shell(val view: LinearLayout, val body: LinearLayout)

    private fun cardShell(stepLabel: String?): Shell {
        val v = Ui.column(ctx).apply {
            background = Ui.rounded(Color.argb(244, 9, 17, 28), dp(24).toFloat(), Color.argb(70, 245, 197, 66), dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(16).toFloat()
            isClickable = true
            tag = "tour-card"
        }
        val head = Ui.row(ctx, gap = dp(10)).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.buddy_happy)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(40, 245, 197, 66)) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        head.addView(Ui.weight(Ui.text(ctx, (ctx.getString(R.string.roof_tag_sol) + (stepLabel?.let { " · $it" } ?: "")).uppercase(), 11f, Ui.GOLD, 800).apply { letterSpacing = 0.12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }))
        v.addView(head)
        val body = Ui.column(ctx)
        v.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        return Shell(v, body)
    }

    /** Portrait: the card lives in the big sky; landscape: the top corner away from the highlighted object. */
    private fun placeCard(v: View, target: RoofObject?) {
        removeCard()
        val cam = roof.cam
        val lp = if (cam.portrait) FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = host.topInset + dp(12)
        } else {
            val r = target?.let { roof.screenRect(it) }
            val right = r == null || r.centerX() < root.width / 2f
            FrameLayout.LayoutParams(min(dp(400), root.width / 2 - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or if (right) Gravity.END else Gravity.START).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = host.topInset + dp(14)
            }
        }
        root.addView(v, lp)
        v.alpha = 0f
        v.translationY = -dp(10).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(200).start()
        card = v
    }

    private fun showStep() {
        val steps = tourSteps ?: return
        val s = steps[tourIndex]
        val judges = tourVariant == RoofTour.Variant.JUDGES
        val c = cardShell(ctx.getString(R.string.tour_step, tourIndex + 1, steps.size))
        val head = c.view.getChildAt(0) as LinearLayout
        val vol = ImageView(ctx).apply {
            setImageResource(if (voiceOn) R.drawable.ic_run_volume else R.drawable.ic_run_volume_off)
            setColorFilter(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = ctx.getString(if (voiceOn) R.string.tour_voice_on else R.string.tour_voice_off)
            isClickable = true
            tag = "tour-voice"
            setOnClickListener {
                voiceOn = !voiceOn
                if (!voiceOn) voice?.stop()
                setImageResource(if (voiceOn) R.drawable.ic_run_volume else R.drawable.ic_run_volume_off)
                contentDescription = ctx.getString(if (voiceOn) R.string.tour_voice_on else R.string.tour_voice_off)
            }
        }
        head.addView(vol, LinearLayout.LayoutParams(dp(38), dp(38)))
        head.addView(Ui.text(ctx, ctx.getString(R.string.tour_skip), 13f, Color.argb(200, 232, 240, 247), 700).apply {
            setPadding(dp(8), dp(8), dp(4), dp(8)); isClickable = true; tag = "tour-skip"
            setOnClickListener { endTour(false) }
        })
        val title = ctx.getString(s.title)
        val body = if (s.bodyArg != null) ctx.getString(s.body, s.bodyArg) else ctx.getString(s.body)
        c.body.addView(Ui.text(ctx, title, 18f, Color.WHITE, 800))
        c.body.addView(Ui.text(ctx, body, 14.5f, Color.argb(228, 232, 240, 247), 500).apply { setLineSpacing(0f, 1.22f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        for (l in s.links) {
            c.body.addView(Ui.row(ctx, gap = dp(10)).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Color.argb(28, 122, 209, 255), dp(14).toFloat(), Color.argb(70, 122, 209, 255), dp(1))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isClickable = true
                foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(14).toFloat(), 0x227AD1FF)
                tag = "tour-link"
                contentDescription = ctx.getString(l.label) + ", " + l.url
                setOnClickListener { host.openUrl(l.url) }
                addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_open); setColorFilter(Ui.CYAN) }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(Ui.weight(Ui.column(ctx).apply {
                    addView(Ui.text(ctx, ctx.getString(l.label), 13.5f, Color.WHITE, 800).apply { maxLines = 1 })
                    addView(Ui.text(ctx, "explorer.solana.com · ${l.short} · devnet", 11.5f, Ui.CYAN, 700).apply { maxLines = 1 })
                }))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        // dots + progress (judges auto-play)
        val dots = Ui.row(ctx, gap = dp(5)).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in steps.indices) dots.addView(View(ctx).apply {
            background = Ui.rounded(if (i == tourIndex) Ui.GOLD else Color.argb(70, 255, 255, 255), dp(3).toFloat())
        }, LinearLayout.LayoutParams(if (i == tourIndex) dp(16) else dp(6), dp(6)))
        val foot = Ui.row(ctx, gap = dp(8)).apply { gravity = Gravity.CENTER_VERTICAL }
        foot.addView(Ui.weight(dots))
        if (tourIndex > 0) foot.addView(Ui.button(ctx, ctx.getString(R.string.tour_back), Ui.Btn.SECONDARY) { go(tourIndex - 1) }.apply { tag = "tour-back"; setPadding(dp(16), paddingTop, dp(16), paddingBottom) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val last = tourIndex == steps.size - 1
        foot.addView(Ui.button(ctx, ctx.getString(if (last) R.string.tour_done else R.string.tour_next), Ui.Btn.PRIMARY) { if (last) endTour(true) else go(tourIndex + 1) }.apply { tag = "tour-next"; setPadding(dp(18), paddingTop, dp(18), paddingBottom) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (judges && !last) {
            val bar = FrameLayout(ctx).apply { background = Ui.rounded(Color.argb(40, 255, 255, 255), dp(2).toFloat()) }
            val fillV = View(ctx).apply { background = Ui.rounded(Ui.GOLD, dp(2).toFloat()); pivotX = 0f; scaleX = 0f }
            bar.addView(fillV, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            c.body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
            fillV.animate().scaleX(1f).setDuration(s.ms).setInterpolator(android.view.animation.LinearInterpolator()).start()
            c.body.addView(Ui.text(ctx, ctx.getString(R.string.judge_auto, steps.sumOf { it.ms / 1000 }.toInt()), 11f, Color.argb(150, 232, 240, 247), 700), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            main.removeCallbacksAndMessages(null)
            if (autoPlay) main.postDelayed({ if (tourSteps === steps && tourIndex < steps.size - 1 && shown) go(tourIndex + 1) }, s.ms)
        }
        c.body.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        roof.spotlight = s.target
        placeCard(c.view, s.target)
        if (voiceOn) speak("$title. $body")
    }

    private fun go(i: Int) {
        val steps = tourSteps ?: return
        tourIndex = i.coerceIn(0, steps.size - 1)
        main.removeCallbacksAndMessages(null)
        showStep()
    }

    private fun speak(line: String) {
        if (!MainActivity.tickerEnabled) return
        val v = voice ?: net.solardepin.solarchik.sol.SolVoice(host).also { voice = it }
        runCatching { v.speak(line, host.lang) }
    }

    val tourStepIndex: Int get() = if (tourSteps == null) -1 else tourIndex
    val tourActive: Boolean get() = tourSteps != null
    val offerShown: Boolean get() = card != null && tourSteps == null

    companion object {
        private val TICKER_STOCKS = listOf("NVDAx", "TSLAx", "AAPLx")
        private var cachedPrices: Map<String, SlicePrice> = emptyMap()
        private var pricesAt = 0L
        /** Debug builds / tests: fixed sky, no auto-advance, no voice. */
        @JvmStatic var forcedMood: RoofMood? = null
        @JvmStatic var autoPlay = true
        @JvmStatic var forceMute = false
        @JvmStatic var debugHook: ((RooftopScreen) -> Unit)? = null
    }
}

/** Which line Sol says on the roof (pure, unit-tested). */
object RoofLine {
    enum class Kind { CALL, SIGN, RUN, EARNED, DONE }

    fun pick(unreadCalls: Int, punch: PunchState, earned: Boolean): Kind = when {
        unreadCalls > 0 -> Kind.CALL
        punch == PunchState.READY -> Kind.SIGN
        punch == PunchState.NEED_RUN -> Kind.RUN
        earned -> Kind.EARNED
        else -> Kind.DONE
    }
}

/** Speech-bubble tail: down (bubble above Sol) or a curl to the right (bubble left of his head). */
private class TailView(ctx: android.content.Context) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 255, 255, 255) }
    private val path = android.graphics.Path()
    var down = true

    override fun onDraw(c: android.graphics.Canvas) {
        path.reset()
        if (down) { path.moveTo(0f, 0f); path.lineTo(width.toFloat(), 0f); path.lineTo(width / 2f, height.toFloat()) }
        else { path.moveTo(0f, 0f); path.cubicTo(width * 0.25f, height * 0.35f, width * 0.55f, height * 0.7f, width.toFloat(), height * 0.88f); path.cubicTo(width * 0.6f, height * 0.9f, width * 0.2f, height * 0.8f, 0f, height * 0.6f) }
        path.close()
        c.drawPath(path, p)
    }
}

/** coerceIn that never throws when a tiny window makes the range empty (keeps the lower bound). */
private fun Float.clampIn(lo: Float, hi: Float): Float = if (hi < lo) lo else coerceIn(lo, hi)
