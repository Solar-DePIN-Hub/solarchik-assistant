package net.solardepin.solarchik.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.2.7 redesign kit ("one thing at a time"): the tokens and small pieces of /deliverables/redesign/spec.md §4–5,
 * in plain Views. Yellow means "the one thing to tap": [primary] is the only filled yellow control on a screen.
 */
object Kit {
    val S1 = Color.parseColor("#0E2232")
    val S2 = Color.parseColor("#14293B")
    val S3 = Color.parseColor("#1A3247")
    val HAIR = Ui.withAlpha(Color.WHITE, 0x14)
    val MUTED = Color.parseColor("#93A9BB")
    val RED = Color.parseColor("#FF8A7A")

    /** The one yellow pill button (56 dp, 17 sp extra bold, gold glow). */
    fun primary(ctx: Context, text: CharSequence, icon: Int? = null, onTap: () -> Unit): TextView = Ui.text(ctx, text, 17f, Ui.INK, 800).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(13, 17, 1, TypedValue.COMPLEX_UNIT_SP)
        minHeight = ctx.dp(56)
        setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        background = Ui.ripple(Ui.rounded(Ui.GOLD, ctx.dp(28).toFloat()), ctx.dp(28).toFloat(), 0x33000000)
        elevation = ctx.dp(8).toFloat()
        if (Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Ui.GOLD; outlineAmbientShadowColor = Ui.GOLD }
        isClickable = true
        isFocusable = true
        icon?.let { Ui.setIcon(this, it, Ui.INK) }
        setOnClickListener { if (isEnabled) { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() } }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(56))
    }

    /** Secondary: a dark pill with a hairline (never yellow-filled). */
    fun ghost(ctx: Context, text: CharSequence, icon: Int? = null, color: Int = Ui.TEXT, onTap: () -> Unit): TextView = Ui.text(ctx, text, 16f, color, 800).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(12, 16, 1, TypedValue.COMPLEX_UNIT_SP)
        minHeight = ctx.dp(52)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = Ui.ripple(Ui.rounded(Ui.withAlpha(Color.WHITE, 0x0C), ctx.dp(26).toFloat(), HAIR, ctx.dp(1)), ctx.dp(26).toFloat())
        isClickable = true
        isFocusable = true
        icon?.let { Ui.setIcon(this, it, color) }
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(52))
    }

    /** A small chip (18 dp radius); [fill] tints it. */
    fun chip(ctx: Context, text: CharSequence, color: Int, icon: Int? = null, fill: Boolean = true, size: Float = 14f): TextView = Ui.text(ctx, text, size, color, 800).apply {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(12), ctx.dp(7))
        background = if (fill) Ui.rounded(Ui.withAlpha(color, 0x24), ctx.dp(18).toFloat()) else Ui.rounded(Ui.withAlpha(Color.WHITE, 0x0C), ctx.dp(18).toFloat(), HAIR, ctx.dp(1))
        icon?.let { res ->
            ctx.getDrawable(res)?.mutate()?.let { d -> d.setTint(color); val s = ctx.dp(16); d.setBounds(0, 0, s, s); setCompoundDrawables(d, null, null, null); compoundDrawablePadding = ctx.dp(6) }
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int = 22): ImageView = ImageView(ctx).apply {
        setImageResource(res); setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    /** Icon tile (13 dp radius) used in lists. */
    fun tile(ctx: Context, res: Int, color: Int, sizeDp: Int = 44): FrameLayout = FrameLayout(ctx).apply {
        background = Ui.rounded(Ui.withAlpha(color, 0x22), ctx.dp(13).toFloat())
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(color) }, FrameLayout.LayoutParams(ctx.dp(sizeDp * 0.5f), ctx.dp(sizeDp * 0.5f), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    /** Round avatar with the first letter (colour from the name, so Ira stays pink). */
    fun avatar(ctx: Context, name: String, sizeDp: Int = 48): TextView {
        val pals = listOf(intArrayOf(0xFFFF9A9E.toInt(), 0xFFF7707D.toInt()), intArrayOf(0xFF8EE6B5.toInt(), 0xFF3FBF7F.toInt()),
            intArrayOf(0xFF9DB8FF.toInt(), 0xFF7C8CF8.toInt()), intArrayOf(0xFFFFD58A.toInt(), 0xFFF5A742.toInt()), intArrayOf(0xFF8FE3F5.toInt(), 0xFF46B6E0.toInt()))
        val key = net.solardepin.solarchik.circle.Circle.nameKey(name)
        val p = pals[Math.floorMod(key.hashCode(), pals.size)].let { if (key.startsWith("ira")) pals[0] else if (key.startsWith("mom")) pals[1] else if (key.startsWith("andri")) pals[2] else it }
        return Ui.text(ctx, name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?", sizeDp * 0.4f, Ui.INK, 900).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, p).apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        }
    }

    /** The grouped list surface (24 dp radius, hairline) with hairlines between rows. */
    fun list(ctx: Context): LinearLayout = Ui.column(ctx).apply {
        background = Ui.rounded(S1, ctx.dp(24).toFloat(), HAIR, ctx.dp(1))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun addRow(list: LinearLayout, row: View) {
        if (list.childCount > 0) list.addView(View(list.context).apply { setBackgroundColor(HAIR) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        list.addView(row)
    }

    /** One list row: tile, title, optional subtitle, and a trailing view (chevron by default). */
    fun row(ctx: Context, iconRes: Int?, color: Int, title: CharSequence, sub: CharSequence?, trailing: View? = null, lead: View? = null, onTap: (() -> Unit)? = null): LinearLayout = Ui.row(ctx, gap = 14).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(68)
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
        (lead ?: iconRes?.let { tile(ctx, it, color) })?.let { addView(it) }
        val col = Ui.column(ctx)
        col.addView(Ui.text(ctx, title, 16f, Ui.TEXT, 700).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (!sub.isNullOrBlank()) col.addView(Ui.top(Ui.text(ctx, sub, 14f, MUTED, 600).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, 2))
        addView(Ui.weight(col))
        val t = trailing ?: if (onTap != null) icon(ctx, net.solardepin.solarchik.R.drawable.lc_chev, MUTED, 20) else null
        t?.let { addView(it) }
        if (onTap != null) {
            isClickable = true
            background = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), ctx.dp(24).toFloat(), 0x1AFFFFFF)
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onTap() }
        }
    }

    /** Gold switch (thumb dark on gold track when on, like the mockups). */
    fun toggle(ctx: Context, on: Boolean, onChange: (Boolean) -> Unit): android.widget.Switch = android.widget.Switch(ctx).apply {
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Color.parseColor("#1A1204"), Color.parseColor("#B8C6D2")))
        trackTintList = ColorStateList(states, intArrayOf(Ui.GOLD, Color.parseColor("#2A4459")))
        setOnCheckedChangeListener { v, b -> v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onChange(b) }
    }

    fun dashed(ctx: Context, color: Int, radiusDp: Int, fill: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = ctx.dp(radiusDp).toFloat()
        setStroke(ctx.dp(1.5f), color, ctx.dp(5).toFloat(), ctx.dp(4).toFloat())
    }

    /** Section label ("DAILY HABITS"), 14 sp. */
    fun section(ctx: Context, text: CharSequence, color: Int = MUTED): TextView = Ui.text(ctx, text.toString().uppercase(), 14f, color, 800).apply { letterSpacing = 0.08f; maxLines = 1 }

    /** Haptic with the newer constants where the OS has them (spec §6). */
    fun haptic(v: View, kind: String) {
        val c = when (kind) {
            "threshold" -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
            "unthreshold" -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK
            "confirm" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            "tap" -> HapticFeedbackConstants.KEYBOARD_TAP
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        v.performHapticFeedback(c)
    }

    /** "Ends in 67" (spec §8): the full number only for your own contacts or with Settings › Show phone numbers. */
    fun phoneEnds(ctx: Context, phone: String): String {
        val d = phone.filter { it.isDigit() }
        if (d.length < 4) return ""
        return ctx.getString(net.solardepin.solarchik.R.string.phone_ends, d.takeLast(2))
    }

    /** Reduced motion (animator scale 0): no confetti or rotation; haptics stay. */
    fun reducedMotion(ctx: Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
}
