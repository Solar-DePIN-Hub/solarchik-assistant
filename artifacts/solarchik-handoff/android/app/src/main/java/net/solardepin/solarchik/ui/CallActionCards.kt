package net.solardepin.solarchik.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.screen.ActionReminders
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.ui.Ui.dp
import org.sol4k.PublicKey
import java.time.ZoneId

/**
 * 1.1.0 call -> action cards (Today and the call screen). Nothing here signs on its own:
 *  - payment: the user types or pastes the recipient (an address the caller said is shown in full with a scam
 *    warning and is used only after "Use this address" + the "I checked" box), then the wallet app approves;
 *  - callback: the dialer opens with the number (the user presses call), optional local reminder;
 *  - reminder: a local notification at the time.
 */
object CallActionCards {
    fun title(ctx: Context, a: CallAction, who: String): String = net.solardepin.solarchik.screen.Phones.show(ctx, rawTitle(ctx, a, who))

    private fun rawTitle(ctx: Context, a: CallAction, who: String): String = when (a.type) {
        CallAction.PAYMENT -> ctx.getString(R.string.ca_pay_title, amount(a), a.token.ifBlank { "?" }, a.recipient.ifBlank { who.ifBlank { ctx.getString(R.string.calls_unknown) } })
        CallAction.CALLBACK -> if (a.time.isNotBlank()) ctx.getString(R.string.ca_cb_title_at, who.ifBlank { a.number }, a.time) else ctx.getString(R.string.ca_cb_title, who.ifBlank { a.number })
        else -> inUi(a.text).ifBlank { ctx.getString(R.string.ca_rem_title) } + (if (a.time.isNotBlank() && !a.text.contains(a.time)) " · ${a.time}" else "")
    }

    private val CYR = Regex("[\\u0400-\\u04FF]")

    /** 1.2.4: card text saved in another language (a Ukrainian quote in the English UI) is not shown as is. */
    fun inUi(text: String): String {
        if (text.isBlank()) return text
        val en = net.solardepin.solarchik.screen.ScreenApi.uiLang() == "en"
        return if ((en && CYR.containsMatchIn(text)) || (!en && RU.containsMatchIn(text))) "" else text
    }
    private val RU = Regex("[ыэъёЫЭЪЁ]|\\b(Хотел|хотел|привет|пожалуйста|перезвон)")

    /** The caller's words for the card: the saved quote, or the call's (translated) note when the quote is in another language. */
    fun quote(a: CallAction, call: net.solardepin.solarchik.screen.CallItem?): String =
        inUi(a.quote).ifBlank { if (a.quote.isBlank()) "" else inUi(call?.intent.orEmpty()) }

    fun amount(a: CallAction): String = java.math.BigDecimal.valueOf(a.amount).stripTrailingZeros().toPlainString()

    /** Full card with buttons (Today). */
    fun card(host: MainActivity, a: CallAction, onChange: () -> Unit): LinearLayout = Ui.card(host, accent = if (a.payment) Ui.RED else Ui.PURPLE, pad = 14).apply {
        tag = "ca-" + a.type
        val ctx = host
        val call = CallInbox.cached(ctx).firstOrNull { it.key == a.callKey }
        val who = call?.who.orEmpty()
        val head = Ui.row(ctx, gap = 8).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(Ui.pill(ctx, ctx.getString(when (a.type) { CallAction.PAYMENT -> R.string.ca_kind_pay; CallAction.CALLBACK -> R.string.ca_kind_cb; else -> R.string.ca_kind_rem }), if (a.payment) Ui.RED else Ui.PURPLE))
        head.addView(Ui.weight(Ui.text(ctx, call?.let { CallText.time(it.at) }.orEmpty(), 11.5f, Ui.MUTED, 600)))
        head.addView(Ui.text(ctx, "✕", 14f, Ui.MUTED, 800).apply {
            tag = "ca-dismiss"; contentDescription = ctx.getString(R.string.ca_dismiss)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { CallActionStore(ctx).update(a.id) { it.copy(status = CallAction.DISMISSED) }; onChange() }
        })
        addView(head)
        addView(Ui.top(Ui.text(ctx, title(ctx, a, who), 15f, Ui.TEXT, 800).apply { tag = "ca-title" }, 6))
        quote(a, call).takeIf { it.isNotBlank() }?.let { q -> addView(Ui.top(Ui.muted(ctx, "“" + net.solardepin.solarchik.screen.Phones.show(ctx, q) + "”", 12f).apply { tag = "ca-quote" }, 4)) }
        if (a.remindAt > System.currentTimeMillis()) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.ca_reminder_set, CallText.time(a.remindAt)), 12f, Ui.GOLD, 700).apply { tag = "ca-reminder-at" }, 4))
        // 1.1.0 polish: the main action gets its own full-width row so labels never truncate (EN and UK)
        var main: View? = null
        val btns = Ui.row(ctx, gap = 8)
        when (a.type) {
            CallAction.PAYMENT -> {
                addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.ca_pay_warning), 12f, Ui.RED, 700).apply { tag = "ca-pay-warning"; setLineSpacing(0f, 1.2f) }, 8))
                // 1.2.4 Circle: the recipient comes from the user's own contacts (matched by phone, then name), never from the call
                val contacts = net.solardepin.solarchik.circle.CircleStore(ctx).all()
                val c = net.solardepin.solarchik.circle.Circle.match(contacts, who.ifBlank { a.recipient }, call?.dialNumber.orEmpty())
                val d = net.solardepin.solarchik.circle.Debt(a, call, c, c?.name ?: who.ifBlank { a.recipient })
                if (c != null && c.address.isNotBlank()) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.circle_card_to, c.name, Fmt.short(c.address)), 12.5f, Ui.GREEN, 700).apply { tag = "ca-pay-contact" }, 6))
                addView(Ui.top(CirclePanel.actions(host, d, onChange), 10))
                btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.ca_pay_manual), Ui.Btn.GHOST) { paySheet(host, a, onChange) }.apply { tag = "ca-pay" }))
            }
            CallAction.CALLBACK -> {
                if (a.number.isNotBlank()) main = Ui.button(ctx, ctx.getString(R.string.ca_dial) + " " + a.number, Ui.Btn.PRIMARY) { dial(ctx, a) }.apply { tag = "ca-dial" }
                btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.ca_remind), Ui.Btn.SECONDARY) { remind(host, a, call?.at ?: System.currentTimeMillis()); onChange() }.apply { tag = "ca-remind" }))
            }
            else -> btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.ca_remind), Ui.Btn.SECONDARY) { remind(host, a, call?.at ?: System.currentTimeMillis()); onChange() }.apply { tag = "ca-remind" }))
        }
        btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.ca_done), Ui.Btn.GHOST) { CallActionStore(ctx).update(a.id) { it.copy(status = CallAction.DONE) }; onChange() }.apply { tag = "ca-done" }))
        main?.let { addView(Ui.top(it, 10)) }
        addView(Ui.top(btns, if (main != null) 8 else 10))
    }

    /** Opens the dialer with the number filled in; the user presses call. */
    fun dial(ctx: Context, a: CallAction) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.number))) }
    }

    /** At the said time (or in an hour when no time was said). */
    fun remind(host: MainActivity, a: CallAction, callAt: Long, now: Long = System.currentTimeMillis()) {
        val at = CallActionRules.remindAt(a, callAt, now, ZoneId.systemDefault()).takeIf { it > 0 } ?: (now + 3600_000L)
        val who = CallInbox.cached(host).firstOrNull { it.key == a.callKey }?.who.orEmpty()
        host.requestNotifications(fromUser = true)
        ActionReminders.schedule(host, a, host.getString(R.string.ca_note_title), title(host, a, who), at, now)
        host.toast(host.getString(R.string.ca_reminder_set, CallText.time(at)))
    }

    /**
     * Payment sheet: amount and token from the call (read-only), the recipient typed or pasted by the user. The
     * address the caller said is never pre-filled: it is shown in full with a scam warning and used only on tap.
     */
    fun paySheet(host: MainActivity, a: CallAction, onChange: () -> Unit) {
        val ctx = host
        val box = Ui.column(ctx, gap = 8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(Ui.text(ctx, ctx.getString(R.string.ca_sheet_amount, amount(a), a.token), 18f, Ui.TEXT, 800).apply { tag = "ca-sheet-amount" })
        if (CallActionRules.large(a.token, a.amount)) box.addView(Ui.text(ctx, ctx.getString(R.string.ca_large), 12.5f, Ui.RED, 700))
        if (!host.wallet.mainnet && a.token != "SOL") box.addView(Ui.text(ctx, ctx.getString(R.string.ca_token_mainnet, a.token), 12.5f, Ui.AMBER, 700))
        val input = EditText(ctx).apply {
            tag = "ca-sheet-recipient"
            hint = ctx.getString(R.string.ca_sheet_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 13f
            setText("")
        }
        box.addView(input)
        if (a.saidAddress.isNotBlank()) {
            box.addView(Ui.card(ctx, accent = Ui.RED, pad = 12).apply {
                tag = "ca-sheet-said"
                addView(Ui.text(ctx, ctx.getString(R.string.ca_said_warning), 12.5f, Ui.RED, 800).apply { setLineSpacing(0f, 1.2f) })
                addView(Ui.top(Ui.text(ctx, a.saidAddress, 13f, Ui.TEXT, 700).apply { tag = "ca-sheet-said-addr"; setTextIsSelectable(true) }, 6))
                // 1.2.4: no "use this address" button: an address heard on a call is never taken over (type it yourself after checking)
            })
        }
        val check = CheckBox(ctx).apply { tag = "ca-sheet-check"; text = ctx.getString(R.string.ca_sheet_check); setTextColor(Ui.TEXT) }
        box.addView(check)
        box.addView(Ui.muted(ctx, ctx.getString(R.string.ca_sheet_note), 11.5f).apply { setLineSpacing(0f, 1.2f) })
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.ca_sheet_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.ca_sheet_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0E1C27.toInt()); cornerRadius = 22f * ctx.resources.displayMetrics.density })
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val problem = paymentProblem(host, a, input.text.toString(), check.isChecked)
                if (problem != null) { host.toast(problem); return@setOnClickListener }
                dlg.dismiss()
                send(host, a, input.text.toString().trim(), onChange)
            }
        }
        dlg.show()
        lastSheet = dlg
    }

    @androidx.annotation.VisibleForTesting
    var lastSheet: android.app.AlertDialog? = null

    /** Null when the payment may go to the wallet for approval. */
    fun paymentProblem(host: MainActivity, a: CallAction, recipient: String, checked: Boolean): String? {
        val r = recipient.trim()
        return when {
            a.token !in CallActionRules.PAY_TOKENS -> host.getString(R.string.ca_err_token)
            !host.wallet.mainnet && a.token != "SOL" -> host.getString(R.string.ca_token_mainnet, a.token)
            CallActionRules.amountRaw(a.token, a.amount) <= 0L -> host.getString(R.string.ca_err_amount)
            !CallActionRules.validAddress(r) -> host.getString(R.string.ca_err_address)
            host.wallet.connected && r == host.wallet.address -> host.getString(R.string.ca_err_self)
            !checked -> host.getString(R.string.ca_err_check)
            else -> null
        }
    }

    /**
     * 1.2.4 Circle: pay a saved contact. One confirm here (who, how much, the saved address), then the wallet's own
     * approval. No checkbox: the address is one the user saved themselves.
     */
    fun payContact(host: MainActivity, a: CallAction, c: net.solardepin.solarchik.circle.Contact, onChange: () -> Unit) {
        val ctx = host
        val problem = paymentProblem(host, a, c.address, true)
        if (problem != null) { host.toast(problem); return }
        val msg = ctx.getString(R.string.circle_confirm, net.solardepin.solarchik.circle.Circle.amount(a.amount), a.token, c.name, c.address)
        val dlg = android.app.AlertDialog.Builder(ctx).setTitle(R.string.ca_sheet_title).setMessage(msg)
            .setPositiveButton(R.string.circle_confirm_send) { _, _ -> send(host, a, c.address, onChange, c) }
            .setNegativeButton(android.R.string.cancel, null).create()
        dlg.show()
        lastSheet = dlg
    }

    /**
     * 1.2.6: the payment's own status sheet, so nothing fails silently: "Approve in your wallet" while the wallet is
     * open, then either a clear error, or "Sent" -> "Settled" with the Solscan link once the chain confirms it.
     */
    @androidx.annotation.VisibleForTesting
    var lastStatus: android.app.AlertDialog? = null

    private fun status(host: MainActivity, title: String, msg: String, sig: String? = null): android.app.AlertDialog {
        lastStatus?.let { runCatching { it.dismiss() } }
        val b = android.app.AlertDialog.Builder(host).setTitle(title).setMessage(msg).setPositiveButton(android.R.string.ok, null)
        if (sig != null) b.setNeutralButton(R.string.pay_solscan) { _, _ -> host.openUrl(host.wallet.txUrl(sig)) }
        val d = b.create()
        d.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0E1C27.toInt()); cornerRadius = 22f * host.resources.displayMetrics.density })
        if (!host.isFinishing) d.show()
        lastStatus = d
        return d
    }

    private fun send(host: MainActivity, a: CallAction, recipient: String, onChange: () -> Unit, contact: net.solardepin.solarchik.circle.Contact? = null) {
        val what = host.getString(R.string.pay_what, net.solardepin.solarchik.circle.Circle.amount(a.amount), a.token, contact?.name ?: Fmt.short(recipient))
        status(host, host.getString(R.string.pay_waiting_title), host.getString(R.string.pay_waiting, what))
        host.scope.launch {
            val raw = CallActionRules.amountRaw(a.token, a.amount)
            val r = runCatching { host.wallet.signAndSend(host.sender) { payer, blockhash -> CallActionRules.paymentTx(payer, PublicKey(recipient), a.token, raw, blockhash) } }
                .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e else Result.failure(e) }
            r.onSuccess { sent ->
                val store = CallActionStore(host)
                if (store.all().none { it.id == a.id }) store.add(listOf(a.copy(status = CallAction.DONE, signature = sent.signature))) // sent from Circle
                else store.update(a.id) { it.copy(status = CallAction.DONE, signature = sent.signature) }
                status(host, host.getString(R.string.pay_sent_title), host.getString(R.string.pay_sent, what), sent.signature)
                onChange()
                // the secretary may tell this caller next time that it was sent (number, amount, token, signature only)
                val call = CallInbox.cached(host).firstOrNull { it.key == a.callKey }
                val number = contact?.phone?.takeIf { it.isNotBlank() } ?: call?.dialNumber.orEmpty()
                val ok = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { host.wallet.waitConfirmed(sent.signature) }.getOrDefault(false) }
                if (ok) {
                    status(host, host.getString(R.string.pay_settled_title), host.getString(R.string.pay_settled, what), sent.signature)
                    if (number.isNotBlank()) withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { net.solardepin.solarchik.circle.Settled.report(host, number, net.solardepin.solarchik.circle.Circle.amount(a.amount), a.token, sent.signature, call?.owner) } }
                } else {
                    net.solardepin.solarchik.wallet.WalletDiag.log("circle", "payment " + Fmt.short(sent.signature) + " not confirmed yet; the secretary is not told")
                    status(host, host.getString(R.string.pay_sent_title), host.getString(R.string.pay_unconfirmed, what), sent.signature)
                }
                onChange()
            }.onFailure {
                status(host, host.getString(R.string.pay_failed_title), host.getString(R.string.pay_failed, host.errorText(it)))
                onChange()
            }
        }
    }

    /** Compact rows for the call screen: what was found, and a way to act on it from Today. */
    fun rows(ctx: Context, key: String, who: String): View? {
        // 1.2.5: call-backs are already "What next → Call back" right below; list only what that does not cover
        val list = CallActionStore(ctx).forCall(key).filter { it.status == CallAction.OPEN && it.type != CallAction.CALLBACK }
        if (list.isEmpty()) return null
        return Ui.card(ctx, accent = Ui.PURPLE, pad = 14).apply {
            tag = "call-found-actions"
            addView(Ui.label(ctx, ctx.getString(R.string.ca_title)))
            list.forEach { a ->
                addView(Ui.top(Ui.text(ctx, title(ctx, a, who), 14f, Ui.TEXT, 700), 6))
                val btns = Ui.row(ctx, gap = 8)
                if (a.type == CallAction.CALLBACK && a.number.isNotBlank()) btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.ca_dial), Ui.Btn.SECONDARY) { dial(ctx, a) }))
                btns.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.ca_open_today), Ui.Btn.GHOST) {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_CALL_ACTION, a.id))
                }.apply { tag = "call-action-open" }))
                addView(Ui.top(btns, 6))
            }
        }
    }
}
