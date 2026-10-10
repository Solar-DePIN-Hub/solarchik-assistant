package net.solardepin.solarchik.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.sol.SolEars
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.2.7 Sol voice (redesign 07): the centre mic dims the screen and slides up a sheet with the mascot, "Listening…",
 * the live transcript, a 34-bar waveform driven by the mic level and an intent preview. What was said goes to the
 * same Sol pipeline as the chat (answers, actions you confirm); "Type" opens the chat keyboard. × or swipe down closes.
 */
object VoiceSheet {
    private var overlay: FrameLayout? = null
    private var ears: SolEars? = null

    @androidx.annotation.VisibleForTesting
    val open: Boolean get() = overlay != null

    /** Pure: the one-line preview of what a sentence will do. */
    fun intentFor(ctx: android.content.Context, said: String): String {
        val s = said.lowercase()
        return when {
            Regex("who do i owe|what do i owe|кому я винен|скільки я винен").containsMatchIn(s) -> ctx.getString(R.string.voice_who_owe)
            Regex("\\b(send|pay|settle)\\b|надішли|відправ|переказ|заплати").containsMatchIn(s) -> ctx.getString(R.string.voice_pays)
            Regex("\\b(remind|call)\\b|нагадай|подзвони").containsMatchIn(s) -> ctx.getString(R.string.voice_adds, said.trim().replaceFirstChar { it.uppercase() }.removePrefix("Remind me to ").removePrefix("remind me to ").replaceFirstChar { it.uppercase() }.take(40))
            else -> ctx.getString(R.string.voice_asks)
        }
    }

    fun dismissIfOpen(host: MainActivity? = null): Boolean {
        val o = overlay ?: return false
        // a stale sheet from another (finished or recreated) activity is forgotten, not "closed"
        if (o.parent == null || !o.isAttachedToWindow || (host != null && o.context !== host)) { overlay = null; return false }
        close(); return true
    }

    fun show(host: MainActivity, pushToTalk: Boolean = false, startListening: Boolean = MainActivity.tickerEnabled) {
        val root = host.window.decorView.findViewWithTag<FrameLayout>("app-root") ?: return
        if (overlay != null && overlay?.parent === root) return
        overlay = null
        val ctx = host
        val o = FrameLayout(ctx).apply { tag = "voice-sheet"; isClickable = true; setOnClickListener { close() } }
        val scrim = View(ctx).apply { setBackgroundColor(Color.argb(0x99, 3, 8, 15)) }
        o.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val sheet = Ui.column(ctx).apply {
            isClickable = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Kit.S3, Kit.S2)).apply { cornerRadius = ctx.dp(32).toFloat(); setStroke(ctx.dp(1), Ui.withAlpha(Color.WHITE, 0x1A)) }
            setPadding(ctx.dp(22), ctx.dp(14), ctx.dp(22), ctx.dp(22))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val top = FrameLayout(ctx)
        top.addView(View(ctx).apply { background = Ui.rounded(Ui.withAlpha(Color.WHITE, 0x33), ctx.dp(3).toFloat()) }, FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(5), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        top.addView(FrameLayout(ctx).apply {
            tag = "voice-close"; contentDescription = ctx.getString(R.string.voice_close)
            background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Kit.S1) }, ctx.dp(22).toFloat())
            isClickable = true; setOnClickListener { close() }
            addView(Kit.icon(ctx, R.drawable.lc_x, Ui.TEXT, 20), FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
        }, FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.END or Gravity.TOP))
        sheet.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(44)))
        val mascot = FrameLayout(ctx)
        val rings = Rings(ctx)
        mascot.addView(rings, FrameLayout.LayoutParams(ctx.dp(150), ctx.dp(150), Gravity.CENTER))
        mascot.addView(FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.withAlpha(Ui.GOLD, 0x22)) }
            addView(Ui.image(ctx, R.drawable.buddy_happy, android.widget.ImageView.ScaleType.FIT_CENTER), FrameLayout.LayoutParams(ctx.dp(84), ctx.dp(84), Gravity.CENTER))
        }, FrameLayout.LayoutParams(ctx.dp(96), ctx.dp(96), Gravity.CENTER))
        sheet.addView(mascot, LinearLayout.LayoutParams(ctx.dp(150), ctx.dp(150)))
        val label = Ui.text(ctx, ctx.getString(R.string.voice_listening).uppercase(), 14f, Ui.GOLD, 800).apply { letterSpacing = 0.14f; tag = "voice-label" }
        sheet.addView(label)
        val transcript = Ui.display(ctx, ctx.getString(R.string.voice_hint), 24f).apply {
            tag = "voice-transcript"; gravity = Gravity.CENTER; setTextColor(Ui.withAlpha(Ui.TEXT, 0x99)); maxLines = 4
        }
        sheet.addView(Ui.top(transcript, 12), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })
        val wave = Wave(ctx).apply { tag = "voice-wave" }
        sheet.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(56)).apply { topMargin = ctx.dp(16) })
        val intentRow = Ui.row(ctx, gap = 12).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Kit.S1, ctx.dp(22).toFloat(), Kit.HAIR, ctx.dp(1))
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(10), ctx.dp(10))
            tag = "voice-intent"
        }
        intentRow.addView(Kit.tile(ctx, R.drawable.lc_spark, Ui.GOLD, 40))
        val intentText = Ui.text(ctx, ctx.getString(R.string.voice_asks), 16f, Ui.TEXT, 800).apply { maxLines = 2; tag = "voice-intent-text" }
        intentRow.addView(Ui.weight(intentText))
        intentRow.addView(Kit.chip(ctx, ctx.getString(R.string.voice_type), Ui.TEXT, R.drawable.lc_kbd, fill = false, size = 15f).apply {
            tag = "voice-type"; minHeight = ctx.dp(44); gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { close(); host.select(MainActivity.Tab.SOL, animate = true); (host.screen(MainActivity.Tab.SOL) as? SolScreen)?.focusInput() }
        })
        sheet.addView(intentRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(16) })
        o.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = ctx.dp(12); rightMargin = ctx.dp(12); bottomMargin = ctx.dp(108) + host.bottomInset
        })
        // swipe down to close
        var y0 = 0f
        sheet.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { v.translationY = (e.rawY - y0).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (v.translationY > v.height / 4f) close() else v.animate().translationY(0f).setDuration(160).start(); true }
                else -> false
            }
        }
        val navIdx = (0 until root.childCount).firstOrNull { root.getChildAt(it).tag == "nav-wrap" } ?: root.childCount
        root.addView(o, navIdx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = o
        if (android.os.Build.VERSION.SDK_INT >= 31 && !Kit.reducedMotion(ctx)) runCatching {
            root.getChildAt(0).setRenderEffect(android.graphics.RenderEffect.createBlurEffect(24f, 24f, android.graphics.Shader.TileMode.CLAMP))
        }
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(200).start()
        sheet.translationY = ctx.dp(420).toFloat()
        sheet.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start()
        micStop(host, true)
        if (!startListening) return
        host.withPermission(android.Manifest.permission.RECORD_AUDIO) { ok ->
            if (!ok) { host.toast(ctx.getString(R.string.chat_mic_denied)); close(); return@withPermission }
            val e = SolEars(host).also { ears = it }
            if (!e.available()) { host.toast(ctx.getString(R.string.chat_mic_off)); close(); return@withPermission }
            if (MainActivity.tickerEnabled) net.solardepin.solarchik.sol.SolLatency.prewarmWorker()
            e.onLevel = { db -> wave.level(((db + 2f) / 12f).coerceIn(0f, 1f)) }
            e.listen(host.lang, onPartial = { p ->
                transcript.text = p; transcript.setTextColor(Ui.TEXT)
                intentText.text = intentFor(ctx, p)
            }) { said ->
                ears = null
                if (said.isNullOrBlank()) { close(); return@listen }
                transcript.text = said; transcript.setTextColor(Ui.TEXT)
                label.text = ctx.getString(R.string.voice_heard).uppercase()
                intentText.text = intentFor(ctx, said)
                Kit.haptic(intentRow, "confirm")
                o.postDelayed({
                    close()
                    host.select(MainActivity.Tab.SOL, animate = true)
                    (host.screen(MainActivity.Tab.SOL) as? SolScreen)?.askFromToday(said, voice = true)
                }, 650)
            }
        }
    }

    /** Tests: show what the sheet looks like mid-sentence. */
    @androidx.annotation.VisibleForTesting
    fun fakeHeard(text: String) {
        val o = overlay ?: return
        (o.findViewWithTag<TextView>("voice-transcript"))?.apply { this.text = text; setTextColor(Ui.TEXT) }
        (o.findViewWithTag<TextView>("voice-intent-text"))?.text = intentFor(o.context, text)
        (o.findViewWithTag<Wave>("voice-wave"))?.demo()
    }

    fun close() {
        val o = overlay ?: return
        overlay = null
        ears?.stop(); ears = null
        val root = o.parent as? ViewGroup
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { root?.getChildAt(0)?.setRenderEffect(null) }
        (o.context as? MainActivity)?.let { micStop(it, false) }
        root?.removeView(o)
    }

    /** In the bar the mic becomes a stop square while listening. */
    private fun micStop(host: MainActivity, on: Boolean) {
        val btn = host.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<FrameLayout>("nav-mic") ?: return
        val icon = btn.getChildAt(0) as? android.widget.ImageView ?: return
        if (on) icon.setImageDrawable(GradientDrawable().apply { setColor(Ui.INK); cornerRadius = host.dp(5).toFloat(); setSize(host.dp(22), host.dp(22)) })
        else icon.setImageResource(R.drawable.lc_mic)
        icon.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    }

    /** 34 bars, each easing toward the latest mic level. */
    class Wave(ctx: android.content.Context) : View(ctx) {
        private val bars = FloatArray(34) { 0.12f }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GOLD; strokeCap = Paint.Cap.ROUND }
        private var t = 0
        fun level(v: Float) {
            t++
            for (i in bars.indices) {
                val shape = 0.55f + 0.45f * kotlin.math.sin((i + t) * 0.55f)
                val target = (0.12f + v * shape * (1f - kotlin.math.abs(i - 16.5f) / 26f)).coerceIn(0.08f, 1f)
                bars[i] += (target - bars[i]) * 0.5f
            }
            invalidate()
        }
        fun demo() { for (i in bars.indices) bars[i] = (0.2f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.7f)) * (1f - kotlin.math.abs(i - 16.5f) / 22f)).coerceIn(0.1f, 1f); invalidate() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val step = w / bars.size
            paint.strokeWidth = step * 0.5f
            for (i in bars.indices) {
                val x = step * (i + 0.5f); val bh = h * bars[i] / 2f
                paint.alpha = (140 + 115 * bars[i]).toInt().coerceAtMost(255)
                c.drawLine(x, h / 2 - bh, x, h / 2 + bh, paint)
            }
        }
    }

    /** Soft gold pulse rings behind the mascot (1200 ms loop; still with reduced motion). */
    class Rings(ctx: android.content.Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.GOLD; strokeWidth = ctx.dp(2).toFloat() }
        private var phase = 0.3f
        private var anim: ValueAnimator? = null
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Kit.reducedMotion(context) || android.os.Build.FINGERPRINT == "robolectric") return
            anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1200; repeatCount = ValueAnimator.INFINITE; addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start() }
        }
        override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val r0 = width / 2f
            for (k in 0..1) {
                val p = (phase + k * 0.5f) % 1f
                paint.alpha = ((1f - p) * 110).toInt()
                c.drawCircle(width / 2f, height / 2f, r0 * (0.62f + 0.38f * p), paint)
            }
        }
    }
}
