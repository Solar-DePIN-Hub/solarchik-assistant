package net.solardepin.solarchik.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import net.solardepin.solarchik.R
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.0.0 first launch: three pages (Sol, the phone secretary, the agent wallet), Skip on every page,
 * dots, Next / Get started. Shown once over the app; [onDone] persists it.
 */
@SuppressLint("ViewConstructor")
class Onboarding(ctx: Context, private val onDone: () -> Unit) : FrameLayout(ctx) {
    private data class Page(val title: Int, val body: Int, val icon: Int, val color: Int, val robot: Boolean)

    private val pages = listOf(
        Page(R.string.onb_1_title, R.string.onb_1_body, R.drawable.ic_mic, Ui.CYAN, robot = true),
        Page(R.string.onb_2_title, R.string.onb_2_body, R.drawable.ic_call, Ui.PURPLE, robot = false),
        Page(R.string.onb_3_title, R.string.onb_3_body, R.drawable.ic_wallet, Ui.GOLD, robot = false),
    )
    var index = 0
        private set
    private val art = FrameLayout(ctx)
    private val title: TextView = Ui.display(ctx, "", 25f).apply { gravity = Gravity.CENTER; tag = "onb-title" }
    private val body: TextView = Ui.text(ctx, "", 15.5f, Ui.MUTED, 500).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f); tag = "onb-body" }
    private val dots = Ui.row(ctx, gap = 8).apply { gravity = Gravity.CENTER }
    private val next: TextView
    private val skip: TextView
    private val column = Ui.column(ctx)

    init {
        tag = "onboarding"
        isClickable = true // swallow touches meant for the app underneath
        elevation = dp(40).toFloat()
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#14254F"), Ui.BG, Ui.BG))
        skip = Ui.text(ctx, ctx.getString(R.string.onb_skip), 14f, Ui.MUTED, 800).apply {
            tag = "onb-skip"
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            setOnClickListener { onDone() }
        }
        column.gravity = Gravity.CENTER_HORIZONTAL
        val top = FrameLayout(ctx)
        top.addView(skip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
        column.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(art, LinearLayout.LayoutParams(dp(220), dp(220)))
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        column.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        next = Ui.button(ctx, "", Ui.Btn.PRIMARY) { advance() }.apply { tag = "onb-next" }
        column.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyInsets(0, 0)
        show(0, animate = false)
    }

    fun applyInsets(top: Int, bottom: Int) {
        val side = dp(28)
        column.setPadding(side, top + dp(8), side, bottom + dp(24))
    }

    fun advance() {
        if (index >= pages.size - 1) onDone() else show(index + 1, animate = true)
    }

    /** System Back: previous page; on the first page it just stays (Skip is right there). */
    fun back() {
        if (index > 0) show(index - 1, animate = true)
    }

    private fun show(i: Int, animate: Boolean) {
        index = i
        val p = pages[i]
        art.removeAllViews()
        val halo = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.withAlpha(p.color, 0x40), Ui.withAlpha(p.color, 0x0A))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), Ui.withAlpha(p.color, 0x55))
            }
        }
        art.addView(halo, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (p.robot) {
            art.addView(Ui.image(context, R.drawable.buddy_happy), LayoutParams(dp(140), dp(186), Gravity.CENTER))
        } else {
            val core = FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.withAlpha(p.color, 0x33)); setStroke(dp(2), Ui.withAlpha(p.color, 0x99)) }
                addView(ImageView(context).apply { setImageResource(p.icon); setColorFilter(p.color) }, LayoutParams(dp(64), dp(64), Gravity.CENTER))
            }
            art.addView(core, LayoutParams(dp(132), dp(132), Gravity.CENTER))
        }
        title.text = context.getString(p.title)
        body.text = context.getString(p.body)
        next.text = context.getString(if (i == pages.size - 1) R.string.onb_start else R.string.onb_next)
        skip.visibility = if (i == pages.size - 1) View.INVISIBLE else View.VISIBLE
        dots.removeAllViews()
        pages.indices.forEach { d ->
            dots.addView(View(context).apply {
                background = Ui.rounded(if (d == i) Ui.GOLD else Ui.withAlpha(Color.WHITE, 0x33), dp(4).toFloat())
            }, LinearLayout.LayoutParams(dp(if (d == i) 22 else 8), dp(8)))
        }
        if (animate) {
            listOf(art, title, body).forEach { v ->
                v.alpha = 0f; v.translationX = dp(24).toFloat()
                v.animate().alpha(1f).translationX(0f).setDuration(240).start()
            }
        }
    }
}
