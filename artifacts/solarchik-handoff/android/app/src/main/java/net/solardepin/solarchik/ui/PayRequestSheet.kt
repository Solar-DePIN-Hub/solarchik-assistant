package net.solardepin.solarchik.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.circle.Circle
import net.solardepin.solarchik.circle.Debt
import net.solardepin.solarchik.circle.PayRequest
import net.solardepin.solarchik.circle.PayRequestStore
import net.solardepin.solarchik.circle.PayWatch
import net.solardepin.solarchik.circle.SolanaPay
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.2.7 "Request": a Solana Pay transfer request for someone who owes the user. QR on screen + Share (SMS, any
 * messenger). The money goes from THEIR wallet to the user's connected wallet; the app never signs or holds anything.
 * While the sheet is open it checks the reference every few seconds; Paid ✓ only after the chain shows the transfer.
 */
object PayRequestSheet {
    @androidx.annotation.VisibleForTesting var last: Dialog? = null
    @androidx.annotation.VisibleForTesting var lastRequest: PayRequest? = null
    /** Off in unit tests (no network loop); the test calls [PayWatch.checkAll] itself. */
    @Volatile var pollEnabled = true
    const val POLL_MS = 4000L

    fun messageFor(ctx: android.content.Context, d: Debt): String =
        d.action.text.takeIf { d.call == null && it.isNotBlank() }?.take(60)
            ?: d.call?.let { ctx.getString(R.string.payreq_msg_call, CallText.time(it.at)) }
            ?: ctx.getString(R.string.payreq_msg_default)

    fun show(host: MainActivity, d: Debt, onChange: () -> Unit = {}) {
        val ctx = host
        val w = host.wallet
        if (!w.connected || !w.mainnet) {
            android.app.AlertDialog.Builder(ctx).setMessage(R.string.payreq_need_wallet)
                .setPositiveButton(R.string.me_connect) { _, _ -> (host.screen(MainActivity.Tab.TODAY) as? TodayScreen)?.setupWallet() }
                .setNegativeButton(android.R.string.cancel, null).show().also { last = it }
            return
        }
        val r = runCatching { PayRequestStore(ctx).requestFor(d.action, d.who, w.address, messageFor(ctx, d)) }.getOrElse {
            host.toast(ctx.getString(R.string.payreq_bad)); return
        }
        lastRequest = r
        PayWatch.schedule(ctx)
        val box = Ui.column(ctx).apply {
            tag = "payreq-sheet"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Kit.S2); val c = ctx.dp(32).toFloat(); cornerRadii = floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f)
            }
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(24) + host.bottomInset)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(Ui.text(ctx, ctx.getString(R.string.payreq_kicker).uppercase(), 14f, Ui.GREEN, 800).apply { letterSpacing = 0.06f; gravity = Gravity.CENTER })
        box.addView(Ui.top(Ui.display(ctx, ctx.getString(R.string.payreq_title, d.who, Circle.amount(r.amount), r.token), 24f).apply { gravity = Gravity.CENTER; tag = "payreq-title" }, 4))
        val qrSize = ctx.dp(232)
        box.addView(Ui.top(android.widget.FrameLayout(ctx).apply {
            background = Ui.rounded(Color.WHITE, ctx.dp(22).toFloat())
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            addView(ImageView(ctx).apply {
                tag = "payreq-qr"; contentDescription = ctx.getString(R.string.payreq_qr_desc)
                Qr.bitmap(r.url, 512)?.let { setImageBitmap(it) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, android.widget.FrameLayout.LayoutParams(qrSize, qrSize))
        }, 16), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.payreq_to, Fmt.short(r.recipient)) + (if (r.message.isNotBlank()) " · " + r.message else ""), 14f, Kit.MUTED, 600).apply {
            gravity = Gravity.CENTER; maxLines = 2; tag = "payreq-to"
        }, 12))
        val status = Ui.text(ctx, ctx.getString(R.string.payreq_waiting), 15f, Ui.TEXT, 700).apply { gravity = Gravity.CENTER; tag = "payreq-status" }
        box.addView(Ui.top(status, 12))
        box.addView(Ui.top(Kit.primary(ctx, ctx.getString(R.string.payreq_share), R.drawable.lc_ext) { share(host, r, d) }.apply { tag = "payreq-share" }, 16),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.top(Kit.ghost(ctx, ctx.getString(R.string.payreq_copy)) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Solana Pay", r.url))
            if (android.os.Build.VERSION.SDK_INT < 33) host.toast(ctx.getString(R.string.payreq_copied))
        }.apply { tag = "payreq-copy" }, 10), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.payreq_safe), 13f, Kit.MUTED, 600).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, 12))

        val dlg = HabitsSheet.sheet(host, box)
        var job: Job? = null
        fun paid(sig: String) {
            status.text = ctx.getString(R.string.payreq_paid_line, Circle.amount(r.amount), r.token)
            status.setTextColor(Ui.GREEN)
            status.tag = "payreq-paid"
            status.isClickable = true
            status.setOnClickListener { host.openUrl(Circle.solscan(sig)) }
            Kit.haptic(status, "confirm")
            onChange()
        }
        if (pollEnabled) job = host.scope.launch {
            while (true) {
                delay(POLL_MS)
                val done = PayWatch.checkAll(ctx, notify = false).firstOrNull { it.id == r.id }
                if (done != null) { paid(done.signature); break }
            }
        }
        dlg.setOnDismissListener { job?.cancel(); onChange() }
        dlg.show()
        last = dlg
    }

    fun share(host: MainActivity, r: PayRequest, d: Debt) {
        val text = SolanaPay.shareText(r)
        // EXTRA_TEXT for chat apps; sms_body prefills the SMS app (the person's number when the call left one)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra("sms_body", text)
        d.call?.dialNumber?.takeIf { it.isNotBlank() }?.let { send.putExtra("address", it) }
        runCatching { host.startActivity(Intent.createChooser(send, host.getString(R.string.payreq_share))) }.onFailure { host.toast(text) }
    }

    /** "They owe me…": a manual Owes-you line (amount, token, what for). Nothing is sent or requested yet. */
    fun addOwed(host: MainActivity, who: String, request: Boolean = false, onSaved: () -> Unit) {
        val ctx = host
        val box = Ui.column(ctx, gap = 6).apply { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0) }
        // 1.2.9 Me → Receive → Request amount: who pays is optional
        val name = if (who.isBlank()) EditText(ctx).apply { tag = "owed-f-who"; hint = ctx.getString(R.string.recv_f_who); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; textSize = 14f } else null
        name?.let { box.addView(it) }
        val amount = EditText(ctx).apply { tag = "owed-f-amount"; hint = ctx.getString(R.string.owed_f_amount); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; textSize = 14f }
        val tokens = listOf("SOL", "USDC", "SKR")
        var token = "SOL"
        val pick = Ui.row(ctx, gap = 8)
        fun paint() { for (i in 0 until pick.childCount) (pick.getChildAt(i) as TextView).alpha = if (tokens[i] == token) 1f else 0.45f }
        tokens.forEach { t -> pick.addView(Kit.chip(ctx, t, Ui.GREEN).apply { tag = "owed-f-$t"; isClickable = true; setOnClickListener { token = t; paint() } }) }
        paint()
        val what = EditText(ctx).apply { tag = "owed-f-what"; hint = ctx.getString(R.string.owed_f_what); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; textSize = 14f }
        box.addView(amount); box.addView(pick); box.addView(what)
        val dlg = android.app.AlertDialog.Builder(ctx).setTitle(if (who.isBlank()) ctx.getString(R.string.recv_f_title) else if (request) ctx.getString(R.string.recv_f_title_who, who) else ctx.getString(R.string.owed_f_title, who)).setView(box)
            .setPositiveButton(if (request) R.string.recv_f_go else R.string.owed_f_save, null).setNegativeButton(android.R.string.cancel, null).create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = amount.text.toString().replace(',', '.').toDoubleOrNull()
                val ok = v != null && v > 0 && runCatching { SolanaPay.amountText(v, token) }.isSuccess
                if (!ok) { amount.error = ctx.getString(R.string.owed_f_bad); return@setOnClickListener }
                val key = "manual:" + System.currentTimeMillis().toString(36)
                val payer = who.ifBlank { name?.text?.toString()?.trim().orEmpty().take(40).ifBlank { SolanaPay.SOMEONE } }
                CallActionStore(ctx).add(listOf(CallAction("$key#0", key, CallAction.OWED, amount = v!!, token = token, recipient = payer,
                    text = what.text.toString().trim().take(60), source = CallAction.SOURCE_LOCAL)))
                dlg.dismiss(); onSaved()
                if (request) {
                    // straight to the request: QR on screen + the share sheet with the SMS text prefilled
                    val d = net.solardepin.solarchik.circle.Circle.currentOwed(ctx).firstOrNull { it.action.id == "$key#0" } ?: return@setOnClickListener
                    show(host, d, onSaved)
                    lastRequest?.takeIf { it.actionId == d.action.id }?.let { share(host, it, d) }
                }
            }
        }
        dlg.show()
        lastOwedForm = dlg
    }

    /** 1.2.9 Me → Receive: your address as a QR (any wallet can scan it) + "Request amount" (a Solana Pay link). */
    fun receive(host: MainActivity) {
        val ctx = host
        val w = host.wallet
        if (!w.connected || !w.mainnet) {
            android.app.AlertDialog.Builder(ctx).setMessage(R.string.payreq_need_wallet)
                .setPositiveButton(R.string.me_connect) { _, _ -> (host.screen(MainActivity.Tab.TODAY) as? TodayScreen)?.setupWallet() }
                .setNegativeButton(android.R.string.cancel, null).show().also { last = it }
            return
        }
        val box = Ui.column(ctx).apply {
            tag = "receive-sheet"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Kit.S2); val c = ctx.dp(32).toFloat(); cornerRadii = floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f)
            }
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(24) + host.bottomInset)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(Ui.text(ctx, ctx.getString(R.string.recv_kicker).uppercase(), 14f, Ui.GREEN, 800).apply { letterSpacing = 0.06f; gravity = Gravity.CENTER })
        box.addView(Ui.top(Ui.display(ctx, ctx.getString(R.string.recv_title), 24f).apply { gravity = Gravity.CENTER; tag = "receive-title" }, 4))
        val qrSize = ctx.dp(208)
        box.addView(Ui.top(android.widget.FrameLayout(ctx).apply {
            background = Ui.rounded(Color.WHITE, ctx.dp(22).toFloat())
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            addView(ImageView(ctx).apply {
                tag = "receive-qr"; contentDescription = ctx.getString(R.string.recv_qr_desc)
                Qr.bitmap(w.address, 512)?.let { setImageBitmap(it) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, android.widget.FrameLayout.LayoutParams(qrSize, qrSize))
        }, 16), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(Ui.top(Ui.text(ctx, w.address, 13f, Ui.TEXT, 600).apply { gravity = Gravity.CENTER; tag = "receive-address"; setTextIsSelectable(true) }, 12),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        var dlg: android.app.Dialog? = null
        box.addView(Ui.top(Kit.primary(ctx, ctx.getString(R.string.recv_request), R.drawable.lc_coins) {
            dlg?.dismiss(); addOwed(host, "", request = true) { host.renderAll() }
        }.apply { tag = "receive-request" }, 16), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.top(Kit.ghost(ctx, ctx.getString(R.string.recv_copy)) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Solana address", w.address))
            if (android.os.Build.VERSION.SDK_INT < 33) host.toast(ctx.getString(R.string.payreq_copied))
        }.apply { tag = "receive-copy" }, 10), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.recv_note), 13f, Kit.MUTED, 600).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, 12))
        dlg = HabitsSheet.sheet(host, box).also { it.show(); lastReceive = it }
    }

    @androidx.annotation.VisibleForTesting var lastReceive: android.app.Dialog? = null

    @androidx.annotation.VisibleForTesting var lastOwedForm: android.app.AlertDialog? = null
}
