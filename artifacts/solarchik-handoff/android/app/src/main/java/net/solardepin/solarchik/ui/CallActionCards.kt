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
    fun title(ctx: Context, a: CallAction, who: String): String = when (a.type) {
        CallAction.PAYMENT -> ctx.getString(R.string.ca_pay_title, amount(a), a.token.ifBlank { "?" }, a.recipient.ifBlank { who.ifBlank { ctx.getString(R.string.calls_unknown) } })
        CallAction.CALLBACK -> if (a.time.isNotBlank()) ctx.getString(R.string.ca_cb_title_at, who.ifBlank { a.number }, a.time) else ctx.getString(R.string.ca_cb_title, who.ifBlank { a.number })
        else -> a.text.ifBlank { ctx.getString(R.string.ca_rem_title) } + (if (a.time.isNotBlank() && !a.text.contains(a.time)) " · ${a.time}" else "")
    }

    fun amount(a: CallAction): String = java.math.BigDecimal(a.amount).stripTrailingZeros().toPlainString()

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
        if (a.quote.isNotBlank()) addView(Ui.top(Ui.muted(ctx, "“" + a.quote + "”", 12f), 4))
        if (a.remindAt > System.currentTimeMillis()) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.ca_reminder_set, CallText.time(a.remindAt)), 12f, Ui.GOLD, 700).apply { tag = "ca-reminder-at" }, 4))
        // 1.1.0 polish: the main action gets its own full-width row so labels never truncate (EN and UK)
        var main: View? = null
        val btns = Ui.row(ctx, gap = 8)
        when (a.type) {
            CallAction.PAYMENT -> {
                addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.ca_pay_warning), 12f, Ui.RED, 700).apply { tag = "ca-pay-warning"; setLineSpacing(0f, 1.2f) }, 8))
                main = Ui.button(ctx, ctx.getString(R.string.ca_pay_prepare), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { paySheet(host, a, onChange) }.apply { tag = "ca-pay" }
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
                addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.ca_said_use), Ui.Btn.GHOST) { input.setText(a.saidAddress) }.apply { tag = "ca-sheet-use-said" }, 6))
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

    private fun send(host: MainActivity, a: CallAction, recipient: String, onChange: () -> Unit) {
        host.scope.launch {
            val raw = CallActionRules.amountRaw(a.token, a.amount)
            val r = host.wallet.signAndSend(host.sender) { payer, blockhash -> CallActionRules.paymentTx(payer, PublicKey(recipient), a.token, raw, blockhash) }
            r.onSuccess { sent ->
                CallActionStore(host).update(a.id) { it.copy(status = CallAction.DONE, signature = sent.signature) }
                host.toast(host.getString(R.string.ca_sent, Fmt.short(sent.signature)))
            }.onFailure { host.toast(host.errorText(it)) }
            onChange()
        }
    }

    /** Compact rows for the call screen: what was found, and a way to act on it from Today. */
    fun rows(ctx: Context, key: String, who: String): View? {
        val list = CallActionStore(ctx).forCall(key).filter { it.status == CallAction.OPEN }
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
