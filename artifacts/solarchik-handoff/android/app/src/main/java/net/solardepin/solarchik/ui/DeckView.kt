package net.solardepin.solarchik.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import net.solardepin.solarchik.ui.Ui.dp
import kotlin.math.abs

/**
 * 1.2.7 Tinder-style deck (spec §6): only the top card moves. It follows the finger, rotates up to 12° around
 * its bottom centre, a stamp fades in, and the next card scales up behind it. Past 35 % of the width (or a fast
 * fling) it commits: fly-out for Done / Later, or, for a payment ([rightSpringsBack]), back to centre first and
 * then [onCommit] opens the wallet approval: a swipe never sends money.
 */
@SuppressLint("ViewConstructor")
class DeckView(ctx: Context) : FrameLayout(ctx) {
    var onCommit: ((dir: Int) -> Unit)? = null
    /** Signed drag progress, 1 = the commit threshold (for the edge tint and the hint line). */
    var onProgress: ((p: Float) -> Unit)? = null
    var rightSpringsBack = false
    var stampRight: TextView? = null
    var stampLeft: TextView? = null
    private var cards: List<View> = emptyList()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var x0 = 0f
    private var y0 = 0f
    private var dragging = false
    private var armed = false
    private var vt: VelocityTracker? = null
    private val reduced = Kit.reducedMotion(ctx)

    init { clipChildren = false; clipToPadding = false }

    /** [views] top first (at most 3). The peeking ones are scaled .94 / .88, 14 / 26 dp lower, and dimmed. */
    fun setCards(views: List<View>) {
        removeAllViews()
        cards = views.take(3)
        for (i in cards.indices.reversed()) {
            val v = cards[i]
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { bottomMargin = dp(26) })
            rest(v, i)
            if (i > 0) { v.isEnabled = false; v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        }
    }

    private fun rest(v: View, i: Int) {
        val s = when (i) { 0 -> 1f; 1 -> 0.94f; else -> 0.88f }
        v.scaleX = s; v.scaleY = s
        v.translationY = when (i) { 0 -> 0f; 1 -> dp(14).toFloat() * 2.2f; else -> dp(26).toFloat() * 2.2f }
        v.translationX = 0f; v.rotation = 0f
        v.alpha = when (i) { 0 -> 1f; 1 -> 0.55f; else -> 0.3f }
    }

    private fun top(): View? = cards.firstOrNull()

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - x0; val dy = e.y - y0
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = top() ?: return false
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - x0
                if (!dragging && abs(dx) > slop) dragging = true
                if (dragging) drag(t, dx)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = (vt?.xVelocity ?: 0f) / resources.displayMetrics.density
                vt?.recycle(); vt = null
                if (dragging) release(t, t.translationX, vx)
                dragging = false
                return true
            }
        }
        return false
    }

    private fun threshold() = width.coerceAtLeast(1) * 0.35f

    internal fun drag(t: View, dx: Float) {
        t.translationX = dx
        t.pivotX = t.width / 2f; t.pivotY = t.height.toFloat()
        if (!reduced) t.rotation = (dx / width.coerceAtLeast(1) * 12f).coerceIn(-12f, 12f)
        val p = (abs(dx) / threshold()).coerceAtMost(1f)
        stampRight?.alpha = if (dx > 0) p else 0f
        stampLeft?.alpha = if (dx < 0) p else 0f
        cards.getOrNull(1)?.let { n -> val s = 0.94f + 0.06f * p; n.scaleX = s; n.scaleY = s; n.translationY = dp(14) * 2.2f * (1 - p); n.alpha = 0.55f + 0.45f * p }
        val over = abs(dx) >= threshold()
        if (over != armed) { armed = over; Kit.haptic(this, if (over) "threshold" else "unthreshold") }
        onProgress?.invoke(if (dx >= 0) abs(dx) / threshold() else -abs(dx) / threshold())
    }

    private fun release(t: View, dx: Float, vx: Float) {
        armed = false
        val dir = when {
            dx > threshold() || (vx > 1000 && dx > slop) -> 1
            dx < -threshold() || (vx < -1000 && dx < -slop) -> -1
            else -> 0
        }
        onProgress?.invoke(0f)
        if (dir == 0) {
            t.animate().translationX(0f).rotation(0f).setDuration(300).setInterpolator(OvershootInterpolator(0.8f)).start()
            stampRight?.animate()?.alpha(0f)?.setDuration(150)?.start(); stampLeft?.animate()?.alpha(0f)?.setDuration(150)?.start()
            cards.getOrNull(1)?.let { n -> n.animate().scaleX(0.94f).scaleY(0.94f).translationY(dp(14) * 2.2f).alpha(0.55f).setDuration(250).start() }
            return
        }
        commit(dir)
    }

    /** Buttons and voice use the same motion as a swipe. */
    fun commit(dir: Int) {
        val t = top() ?: run { onCommit?.invoke(dir); return }
        Kit.haptic(this, if (dir < 0) "tick" else "confirm")
        // no animator time (animations off; unit tests): act at once
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || android.os.Build.FINGERPRINT == "robolectric") { rest(t, 0); onCommit?.invoke(dir); return }
        if (dir > 0 && rightSpringsBack) {
            t.animate().translationX(0f).rotation(0f).setDuration(200).withEndAction { stampRight?.alpha = 0f; onCommit?.invoke(dir) }.start()
            return
        }
        stampRight?.alpha = if (dir > 0) 1f else 0f
        stampLeft?.alpha = if (dir < 0) 1f else 0f
        t.animate().translationX(dir * width.coerceAtLeast(dp(300)) * 1.5f).rotation(if (reduced) 0f else dir * 12f).alpha(if (reduced) 0f else 1f)
            .setDuration(220).setInterpolator(AccelerateInterpolator()).withEndAction { onCommit?.invoke(dir) }.start()
        cards.getOrNull(1)?.animate()?.scaleX(1f)?.scaleY(1f)?.translationY(0f)?.alpha(1f)?.setDuration(220)?.start()
    }
}
