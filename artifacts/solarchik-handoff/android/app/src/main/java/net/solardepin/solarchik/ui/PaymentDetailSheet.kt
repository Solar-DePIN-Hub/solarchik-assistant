package net.solardepin.solarchik.ui

import android.view.Gravity
import android.widget.LinearLayout
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.circle.Circle
import net.solardepin.solarchik.circle.Debt
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.2.10 (tablet: tapping a settled payment in Circle jumped straight to Chrome): an in-app payment detail first
 * (amount, to/from, time, status, short signature); Solscan only behind "View on Solscan" (a Custom Tab).
 */
object PaymentDetailSheet {
    @androidx.annotation.VisibleForTesting var last: android.app.Dialog? = null

    fun show(host: MainActivity, d: Debt, incoming: Boolean) {
        val ctx = host
        val box = Ui.column(ctx).apply {
            tag = "pay-detail"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Kit.S2); val c = ctx.dp(32).toFloat(); cornerRadii = floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f)
            }
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(24) + host.bottomInset)
        }
        box.addView(Ui.text(ctx, ctx.getString(if (incoming) R.string.pd_kicker_in else R.string.pd_kicker_out).uppercase(), 14f, Ui.GREEN, 800).apply { letterSpacing = 0.06f })
        box.addView(Ui.top(Ui.display(ctx, Circle.amount(d.amount) + " " + d.token, 34f).apply { tag = "pay-detail-amount" }, 4))
        val list = Kit.list(ctx)
        fun line(label: Int, value: String, tagName: String) {
            val r = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14)); tag = tagName }
            r.addView(Ui.text(ctx, ctx.getString(label), 14f, Kit.MUTED, 700))
            r.addView(Ui.weight(Ui.text(ctx, value, 15f, Ui.TEXT, 700).apply { gravity = Gravity.END; maxLines = 2 }))
            Kit.addRow(list, r)
        }
        val addr = if (incoming) "" else d.contact?.address.orEmpty()
        line(if (incoming) R.string.pd_from else R.string.pd_to, d.who + if (addr.isNotBlank()) " · " + Fmt.short(addr) else "", "pay-detail-who")
        if (d.at > 0) line(R.string.pd_time, CallText.time(d.at), "pay-detail-time")
        line(R.string.pd_status, ctx.getString(R.string.pd_confirmed), "pay-detail-status")
        line(R.string.pd_sig, Fmt.short(d.action.signature), "pay-detail-sig")
        box.addView(Ui.top(list, 16), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        var dlg: android.app.Dialog? = null
        box.addView(Ui.top(Kit.primary(ctx, ctx.getString(R.string.pd_solscan), R.drawable.lc_ext) {
            host.openCustomTab(Circle.solscan(d.action.signature))
        }.apply { tag = "pay-detail-solscan" }, 18), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.top(Kit.ghost(ctx, ctx.getString(R.string.pd_copy)) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Solana signature", d.action.signature))
            if (android.os.Build.VERSION.SDK_INT < 33) host.toast(ctx.getString(R.string.payreq_copied))
        }.apply { tag = "pay-detail-copy" }, 10), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dlg = HabitsSheet.sheet(host, box).also { it.show(); last = it }
    }
}
