package net.solardepin.solarchik.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import net.solardepin.solarchik.R

/** Dark solar design system: colors, type, cards, buttons, pills, progress. */
object Ui {
    val BG = Color.parseColor("#07131C")
    val SURFACE = Color.parseColor("#0E2232")
    val SURFACE2 = Color.parseColor("#14304A")
    val STROKE = Color.parseColor("#1E3D57")
    val GOLD = Color.parseColor("#F5C542")
    val AMBER = Color.parseColor("#F39A2B")
    val CYAN = Color.parseColor("#7AD1FF")
    val TEXT = Color.parseColor("#EEF4F9")
    val MUTED = Color.parseColor("#8DA4B7")
    val GREEN = Color.parseColor("#5BD69A")
    val RED = Color.parseColor("#FF7A7A")
    val PURPLE = Color.parseColor("#A78BFA")
    val INK = Color.parseColor("#1A1204")

    private var medium: Typeface? = null
    private var bold: Typeface? = null
    private var extra: Typeface? = null
    private var display: Typeface? = null

    fun init(ctx: Context) {
        if (medium != null) return
        medium = font(ctx, R.font.manrope_medium)
        bold = font(ctx, R.font.manrope_bold)
        extra = font(ctx, R.font.manrope_extrabold)
        display = font(ctx, R.font.unbounded_bold)
    }

    private fun font(ctx: Context, id: Int): Typeface? = runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull()

    fun tfMedium(): Typeface = medium ?: Typeface.DEFAULT
    fun tfBold(): Typeface = bold ?: Typeface.DEFAULT_BOLD
    fun tfExtra(): Typeface = extra ?: Typeface.DEFAULT_BOLD
    fun tfDisplay(): Typeface = display ?: Typeface.DEFAULT_BOLD

    fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    fun View.dp(v: Number): Int = context.dp(v)

    fun rounded(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun gradient(colors: IntArray, radiusPx: Float, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR): GradientDrawable =
        GradientDrawable(orientation, colors).apply { cornerRadius = radiusPx }

    fun ripple(content: Drawable, radiusPx: Float, color: Int = 0x33FFFFFF): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(color), content, rounded(Color.WHITE, radiusPx))

    // ---------- text ----------
    fun text(ctx: Context, value: CharSequence = "", size: Float = 14f, color: Int = TEXT, weight: Int = 500): TextView =
        TextView(ctx).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = when {
                weight >= 900 -> tfDisplay()
                weight >= 800 -> tfExtra()
                weight >= 700 -> tfBold()
                else -> tfMedium()
            }
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
        }

    fun display(ctx: Context, value: CharSequence, size: Float = 24f, color: Int = TEXT) = text(ctx, value, size, color, 900)
    fun h2(ctx: Context, value: CharSequence) = text(ctx, value, 18f, TEXT, 800)
    fun body(ctx: Context, value: CharSequence = "") = text(ctx, value, 14f, TEXT, 500)
    fun muted(ctx: Context, value: CharSequence = "", size: Float = 13f) = text(ctx, value, size, MUTED, 500)
    fun label(ctx: Context, value: CharSequence, color: Int = MUTED) = text(ctx, value.toString().uppercase(), 11f, color, 800).apply {
        letterSpacing = 0.12f
    }

    // ---------- containers ----------
    fun column(ctx: Context, gap: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, ctx.dp(gap)); setColor(Color.TRANSPARENT) }
        }
    }

    fun row(ctx: Context, gap: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(ctx.dp(gap), 1); setColor(Color.TRANSPARENT) }
        }
    }

    fun card(ctx: Context, accent: Int? = null, pad: Int = 18): LinearLayout = column(ctx).apply {
        background = if (accent != null) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(blend(SURFACE, accent, 0.16f), SURFACE)).apply {
                cornerRadius = ctx.dp(22).toFloat()
                setStroke(ctx.dp(1), blend(STROKE, accent, 0.35f))
            }
        } else rounded(SURFACE, ctx.dp(22).toFloat(), STROKE, ctx.dp(1))
        val p = ctx.dp(pad)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun spacer(ctx: Context, h: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, ctx.dp(h))
    }

    fun weight(v: View, w: Float = 1f): View {
        v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        return v
    }

    fun matchWidth(v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0): View {
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = top }
        return v
    }

    fun top(v: View, marginDp: Int): View {
        val lp = (v.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = v.dp(marginDp)
        v.layoutParams = lp
        return v
    }

    // ---------- buttons ----------
    enum class Btn { PRIMARY, SECONDARY, GHOST, SUCCESS }

    fun button(ctx: Context, value: CharSequence, style: Btn = Btn.PRIMARY, icon: Int? = null, onClick: (() -> Unit)? = null): TextView =
        text(ctx, value, 16f, if (style == Btn.PRIMARY) INK else TEXT, 800).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(54)
            val h = ctx.dp(20)
            setPadding(h, ctx.dp(12), h, ctx.dp(12))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            styleButton(this, style)
            icon?.let { setIcon(this, it, if (style == Btn.PRIMARY) INK else GOLD) }
            setAutoSizeTextTypeUniformWithConfiguration(12, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            onClick?.let { cb -> setOnClickListener { if (isEnabled) { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); cb() } } }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    fun styleButton(tv: TextView, style: Btn) {
        val r = tv.dp(18).toFloat()
        val base = when (style) {
            Btn.PRIMARY -> gradient(intArrayOf(GOLD, AMBER), r, GradientDrawable.Orientation.LEFT_RIGHT)
            Btn.SECONDARY -> rounded(SURFACE2, r, blend(STROKE, GOLD, 0.35f), tv.dp(1))
            Btn.GHOST -> rounded(Color.TRANSPARENT, r, STROKE, tv.dp(1))
            Btn.SUCCESS -> rounded(blend(SURFACE, GREEN, 0.18f), r, blend(STROKE, GREEN, 0.5f), tv.dp(1))
        }
        tv.background = ripple(base, r)
        tv.setTextColor(
            when (style) {
                Btn.PRIMARY -> INK
                Btn.SUCCESS -> GREEN
                Btn.SECONDARY -> GOLD
                Btn.GHOST -> TEXT
            },
        )
    }

    /** Icon glued to the start of the text, so it stays centered with it in wide buttons. */
    fun setIcon(tv: TextView, res: Int, tint: Int) {
        val d = tv.context.getDrawable(res)?.mutate() ?: return
        d.setTint(tint)
        val s = tv.dp(20)
        d.setBounds(0, 0, s, s)
        tv.setCompoundDrawables(null, null, null, null)
        val plain = tv.text.toString()
        val sb = android.text.SpannableStringBuilder("\u2060 ").append(plain)
        sb.setSpan(CenteredIconSpan(d, tv.dp(8)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = sb
    }

    fun setEnabled(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.45f
    }

    // ---------- pills / badges ----------
    fun pill(ctx: Context, value: CharSequence, color: Int = GOLD, icon: Int? = null, filled: Boolean = false): TextView =
        text(ctx, value, 12f, if (filled) INK else color, 800).apply {
            val h = ctx.dp(10)
            setPadding(h, ctx.dp(5), h, ctx.dp(5))
            gravity = Gravity.CENTER_VERTICAL
            background = if (filled) rounded(color, ctx.dp(99).toFloat())
            else rounded(withAlpha(color, 0x22), ctx.dp(99).toFloat(), withAlpha(color, 0x66), ctx.dp(1))
            icon?.let {
                val d = ctx.getDrawable(it)?.mutate()
                d?.setTint(if (filled) INK else color)
                val s = ctx.dp(14)
                d?.setBounds(0, 0, s, s)
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = ctx.dp(5)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    fun image(ctx: Context, res: Int, scale: ImageView.ScaleType = ImageView.ScaleType.FIT_CENTER): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            scaleType = scale
            adjustViewBounds = true
        }

    fun iconBadge(ctx: Context, res: Int, color: Int, sizeDp: Int = 40): FrameLayout = FrameLayout(ctx).apply {
        background = rounded(withAlpha(color, 0x26), ctx.dp(sizeDp / 2.6f).toFloat())
        val iv = ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(color)
        }
        val s = ctx.dp(sizeDp * 0.52f)
        addView(iv, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)).apply {
            topMargin = ctx.dp(12); bottomMargin = ctx.dp(12)
        }
    }

    // ---------- color helpers ----------
    /** Accessibility: tappable chips get at least a 44dp touch height. */
    fun <T : TextView> tappable(v: T): T = v.apply {
        minHeight = context.dp(TAP_MIN_DP)
        minimumHeight = context.dp(TAP_MIN_DP)
    }

    const val TAP_MIN_DP = 44

    fun switchRow(ctx: Context, label: String, on: Boolean, cb: (android.widget.CompoundButton, Boolean) -> Unit): LinearLayout {
        val r = row(ctx)
        r.addView(weight(body(ctx, label)))
        val sw = android.widget.Switch(ctx).apply {
            contentDescription = label
            isChecked = on
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(GOLD, MUTED),
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(withAlpha(GOLD, 0x66), STROKE),
            )
            setOnCheckedChangeListener(cb)
        }
        r.addView(sw)
        r.minimumHeight = ctx.dp(48)
        r.isClickable = true
        r.setOnClickListener { sw.toggle() }
        return r
    }

    /** Two-or-more option segmented control. [onPick] gets the index. */
    fun segmented(ctx: Context, labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val seg = row(ctx).apply {
            background = rounded(SURFACE, ctx.dp(18).toFloat(), STROKE, ctx.dp(1))
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val tv = text(ctx, label, 14f, if (on) INK else MUTED, 800).apply {
                gravity = android.view.Gravity.CENTER
                isClickable = true
                background = if (on) gradient(intArrayOf(GOLD, AMBER), ctx.dp(14).toFloat(), GradientDrawable.Orientation.LEFT_RIGHT) else null
                setOnClickListener { if (!on) { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onPick(i) } }
                // 1.1.0: five tabs (Agents gained "Swaps") must not break a word over two lines
                if (labels.size >= 5) {
                    maxLines = 1
                    setPadding(ctx.dp(2), 0, ctx.dp(2), 0)
                    setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
                }
            }
            seg.addView(tv, LinearLayout.LayoutParams(0, ctx.dp(TAP_MIN_DP), 1f))
        }
        return seg
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}

/** Draws a drawable vertically centered on the text line. */
class CenteredIconSpan(private val d: Drawable, private val gap: Int) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let {
            val pfm = paint.fontMetricsInt
            it.ascent = pfm.ascent; it.descent = pfm.descent; it.top = pfm.top; it.bottom = pfm.bottom
        }
        return d.bounds.width() + gap / 2
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val fm = paint.fontMetricsInt
        val center = y + (fm.descent + fm.ascent) / 2f
        canvas.save()
        canvas.translate(x, center - d.bounds.height() / 2f)
        d.draw(canvas)
        canvas.restore()
    }
}

/** Rounded progress bar with a solar gradient fill. Built in code only, never inflated from XML. */
@android.annotation.SuppressLint("ViewConstructor")
class SolarProgress(ctx: Context, private val from: Int = Ui.GOLD, private val to: Int = Ui.AMBER) : View(ctx) {
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.withAlpha(Color.WHITE, 0x14) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shader: Shader? = null
    private var shaderW = -1f

    init {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (8 * ctx.resources.displayMetrics.density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction <= 0f) return
        val w = maxOf(height.toFloat(), width * fraction)
        if (w != shaderW) {
            shaderW = w
            shader = LinearGradient(0f, 0f, w, 0f, from, to, Shader.TileMode.CLAMP)
        }
        fill.shader = shader
        rect.set(0f, 0f, w, height.toFloat())
        canvas.drawRoundRect(rect, r, r, fill)
    }
}

/** Seven day dots, Monday first, like the web weekStamps. */
class WeekStrip(ctx: Context) : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL
    }

    fun bind(labels: List<String>, done: List<Boolean>, todayIndex: Int) {
        removeAllViews()
        for (i in 0 until 7) {
            val col = Ui.column(context).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val dot = TextView(context).apply {
                gravity = Gravity.CENTER
                val s = dp(36)
                layoutParams = LayoutParams(s, s)
                val isDone = done.getOrElse(i) { false }
                background = when {
                    isDone -> Ui.gradient(intArrayOf(Ui.GOLD, Ui.AMBER), dp(18).toFloat())
                    i == todayIndex -> Ui.rounded(Color.TRANSPARENT, dp(18).toFloat(), Ui.GOLD, dp(2))
                    else -> Ui.rounded(Ui.withAlpha(Color.WHITE, 0x10), dp(18).toFloat())
                }
                if (isDone) {
                    val d = context.getDrawable(R.drawable.ic_check)?.mutate()
                    d?.setTint(Ui.INK)
                    val s2 = dp(18)
                    d?.setBounds(0, 0, s2, s2)
                    setCompoundDrawables(null, d, null, null)
                    setPadding(0, dp(9), 0, 0)
                }
            }
            val lbl = Ui.text(context, labels.getOrElse(i) { "" }, 11f, if (i == todayIndex) Ui.GOLD else Ui.MUTED, 800).apply {
                gravity = Gravity.CENTER
            }
            col.addView(dot)
            col.addView(lbl, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
