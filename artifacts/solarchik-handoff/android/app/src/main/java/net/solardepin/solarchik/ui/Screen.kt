package net.solardepin.solarchik.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.ui.Ui.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One tab. Builds its view once; [render] refreshes the data in place. */
abstract class Screen(val host: MainActivity) {
    val ctx: Context get() = host
    protected lateinit var column: LinearLayout
    private var scroll: ScrollView? = null

    val view: View by lazy { build() }

    protected fun dp(v: Number): Int = ctx.dp(v)

    protected abstract fun build(): View

    /** Standard scrolling page with insets-aware padding. */
    protected fun page(fill: LinearLayout.() -> Unit): View {
        column = Ui.column(ctx, gap = 14)
        column.fill()
        val sv = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(column)
        }
        scroll = sv
        applyInsets()
        return sv
    }

    /** Scroll the page so [v] sits near the top (below the status bar). */
    fun scrollToView(v: View) {
        val sv = scroll ?: return
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== sv) { y += cur.top; cur = cur.parent as? View }
        sv.smoothScrollTo(0, (y - host.topInset - ctx.dp(80)).coerceAtLeast(0))
    }

    open fun applyInsets() {
        if (!this::column.isInitialized) return
        val side = ctx.dp(18)
        column.setPadding(side, host.topInset + ctx.dp(14), side, host.bottomInset + ctx.dp(112))
    }

    open fun render() {}
    open fun onShow() { render() }
    open fun onHide() {}
    /** The activity is going away: release services (TTS, recognizer). */
    open fun onDestroy() {}
}

object Fmt {
    fun sol(v: Double, max: Int = 4): String {
        if (!v.isFinite()) return "0"
        val bd = BigDecimal(v).setScale(max, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = bd.toPlainString()
        return if (s == "-0") "0" else s
    }

    /** Whole numbers shown alone in a TextView (score, streak). */
    fun count(n: Int): String = String.format(Locale.getDefault(), "%d", n)

    fun signedSol(v: Double, max: Int = 4): String = (if (v > 0) "+" else "") + sol(v, max)

    fun short(addr: String): String = if (addr.length < 12) addr else addr.take(4) + "…" + addr.takeLast(4)

    /** 47:12:03 under a day, "6d 23h" above. */
    fun countdown(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (d > 0) String.format(Locale.US, "%dd %02dh %02dm", d, h, m)
        else String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun clock(ms: Long): String = if (ms <= 0) "—" else SimpleDateFormat("HH:mm", net.solardepin.solarchik.core.AppLocale.ui()).format(Date(ms))

    fun time(ms: Long): String = if (ms <= 0) "—" else SimpleDateFormat(if (net.solardepin.solarchik.core.AppLocale.isUk(net.solardepin.solarchik.core.AppLocale.ui())) "d MMM, HH:mm" else "MMM d, HH:mm", net.solardepin.solarchik.core.AppLocale.ui()).format(Date(ms))

    fun pct(rate: Double): String = BigDecimal(rate * 100).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"
}
