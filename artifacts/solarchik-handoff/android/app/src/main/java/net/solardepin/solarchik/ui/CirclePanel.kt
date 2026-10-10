package net.solardepin.solarchik.ui

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.circle.Circle
import net.solardepin.solarchik.circle.CircleStore
import net.solardepin.solarchik.circle.Contact
import net.solardepin.solarchik.circle.Debt
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.2.4 Settings → Circle: the people you pay, with what calls say you owe them. Each open debt can be settled
 * (one wallet approval), or, without an address, the person can be asked for one. Contacts can be added, edited,
 * deleted and paid directly. Everything stays on this phone; nothing is sent without the wallet's approval.
 */
class CirclePanel(private val host: MainActivity) {
    private val ctx: Context = host
    val view: LinearLayout = Ui.column(ctx).apply { tag = "circle" }

    fun render() {
        view.removeAllViews()
        val contacts = CircleStore(ctx).all()
        val debts = Circle.debts(CallActionStore(ctx).all(), CallInbox.cached(ctx), contacts)
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.circle_body), 12.5f).apply { setLineSpacing(0f, 1.25f) }, 8))

        val open = debts.filter { it.open }
        view.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.circle_owe_label), Ui.GOLD), 14))
        if (open.isEmpty()) view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.circle_owe_none), 12.5f).apply { tag = "circle-owe-none" }, 6))
        open.forEach { d -> view.addView(Ui.top(debtRow(d), 8)) }

        val settled = debts.filter { it.settled }.take(5)
        if (settled.isNotEmpty()) {
            view.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.circle_settled_label)), 14))
            settled.forEach { d ->
                view.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.circle_settled_line, d.who, Circle.amount(d.amount), d.token) +
                    (if (d.at > 0) " · " + CallText.time(d.at) else "") + " · Solscan ↗", 12.5f, Ui.GREEN, 700).apply {
                    tag = "circle-settled"
                    minHeight = dp(40); gravity = android.view.Gravity.CENTER_VERTICAL
                    setOnClickListener { host.openUrl(Circle.solscan(d.action.signature)) }
                }, 4))
            }
        }

        view.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.circle_people_label)), 16))
        if (contacts.isEmpty()) view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.circle_people_none), 12.5f), 6))
        contacts.sortedBy { it.name.lowercase() }.forEach { c -> view.addView(Ui.top(contactRow(c), 8)) }
        view.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.circle_add), Ui.Btn.SECONDARY) { edit(host, Contact("", "")) { render() } }.apply { tag = "circle-add" }, 12))
    }

    private fun debtRow(d: Debt): View = Ui.card(ctx, accent = Ui.GOLD, pad = 12).apply {
        tag = "circle-debt"
        addView(Ui.text(ctx, ctx.getString(R.string.circle_you_owe, d.who, Circle.amount(d.amount), d.token), 15f, Ui.TEXT, 800))
        val src = d.call?.let { ctx.getString(R.string.circle_from_call, CallText.time(it.at)) } ?: ctx.getString(R.string.circle_from_you)
        addView(Ui.top(Ui.text(ctx, src, 12f, Ui.CYAN, 700).apply {
            tag = "circle-debt-call"
            if (d.call != null) { minHeight = dp(36); gravity = android.view.Gravity.CENTER_VERTICAL; setOnClickListener { CallsActivity.open(ctx) } }
        }, 2))
        addView(Ui.top(actions(host, d) { render() }, 8))
    }

    private fun contactRow(c: Contact): View = Ui.card(ctx, pad = 12).apply {
        tag = "circle-contact"
        addView(Ui.text(ctx, c.name, 15f, Ui.TEXT, 800))
        val sub = listOfNotNull(c.phone.takeIf { it.isNotBlank() }, c.address.takeIf { it.isNotBlank() }?.let { Fmt.short(it) } ?: ctx.getString(R.string.circle_no_wallet)).joinToString(" · ")
        addView(Ui.top(Ui.muted(ctx, sub, 12f), 2))
        val r = Ui.row(ctx, gap = 8)
        r.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.circle_send), Ui.Btn.PRIMARY) {
            if (c.address.isBlank()) edit(host, c) { render() } else sendTo(host, c) { render() }
        }.apply { tag = "circle-send"; textSize = 14f }))
        r.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.circle_edit), Ui.Btn.GHOST) { edit(host, c) { render() } }.apply { tag = "circle-edit"; textSize = 14f }))
        r.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.circle_delete), Ui.Btn.GHOST) {
            android.app.AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.circle_delete_q, c.name))
                .setPositiveButton(R.string.circle_delete) { _, _ -> CircleStore(ctx).delete(c.id); render() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }.apply { tag = "circle-delete"; textSize = 14f }))
        addView(Ui.top(r, 8))
    }

    companion object {
        /** Buttons for one debt: Settle (address known) or Add wallet + Ask for wallet. */
        fun actions(host: MainActivity, d: Debt, onChange: () -> Unit): View {
            val ctx: Context = host
            val col = Ui.column(ctx, gap = 8)
            val c = d.contact
            if (c != null && c.address.isNotBlank()) {
                col.addView(Ui.button(ctx, ctx.getString(R.string.circle_settle, Circle.amount(d.amount), d.token, c.name), Ui.Btn.PRIMARY, R.drawable.ic_wallet) {
                    CallActionCards.payContact(host, d.action, c, onChange)
                }.apply { tag = "circle-settle"; maxLines = 2 })
            } else {
                col.addView(Ui.button(ctx, ctx.getString(R.string.circle_add_wallet, d.who), Ui.Btn.PRIMARY, R.drawable.ic_wallet) {
                    edit(host, c ?: Contact("", d.who, d.call?.dialNumber.orEmpty())) { saved ->
                        onChange()
                        if (saved.address.isNotBlank()) CallActionCards.payContact(host, d.action, saved, onChange)
                    }
                }.apply { tag = "circle-add-wallet"; maxLines = 2 })
                col.addView(Ui.button(ctx, ctx.getString(R.string.circle_ask, d.who), Ui.Btn.GHOST) { ask(host, d.who, d) }.apply { tag = "circle-ask"; maxLines = 2 })
            }
            return col
        }

        /** Share sheet with a short message asking for a Solana address (the user picks SMS or a messenger). */
        fun ask(host: MainActivity, who: String, d: Debt?) {
            val text = Circle.askText(host, who, d)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            d?.call?.dialNumber?.takeIf { it.isNotBlank() }?.let { send.putExtra("address", it) } // SMS apps prefill the number
            runCatching { host.startActivity(Intent.createChooser(send, host.getString(R.string.circle_ask, who))) }.onFailure { host.toast(text) }
        }

        @androidx.annotation.VisibleForTesting
        var lastForm: android.app.AlertDialog? = null

        /** Add / edit form: name (required), phone, Solana address (pasted; a solana: link works too). */
        fun edit(host: MainActivity, c: Contact, onSaved: (Contact) -> Unit) {
            val ctx: Context = host
            val box = Ui.column(ctx, gap = 6).apply { setPadding(dp(20), dp(8), dp(20), 0) }
            fun field(tag: String, hint: Int, value: String, type: Int) = EditText(ctx).apply {
                this.tag = tag; this.hint = ctx.getString(hint); setText(value); inputType = type; textSize = 14f
            }
            val name = field("circle-f-name", R.string.circle_f_name, c.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            val phone = field("circle-f-phone", R.string.circle_f_phone, c.phone, InputType.TYPE_CLASS_PHONE)
            val addr = field("circle-f-address", R.string.circle_f_address, c.address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            box.addView(name); box.addView(phone); box.addView(addr)
            box.addView(Ui.button(ctx, ctx.getString(R.string.circle_paste), Ui.Btn.GHOST) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                if (t.isBlank()) host.toast(ctx.getString(R.string.circle_paste_empty)) else addr.setText(t.trim())
            }.apply { tag = "circle-f-paste"; textSize = 14f })
            box.addView(Ui.muted(ctx, ctx.getString(R.string.circle_f_note), 11.5f).apply { setLineSpacing(0f, 1.2f) })
            val dlg = android.app.AlertDialog.Builder(ctx)
                .setTitle(if (c.id.isBlank()) R.string.circle_add else R.string.circle_edit_title)
                .setView(android.widget.ScrollView(ctx).apply { addView(box) })
                .setPositiveButton(R.string.circle_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dlg.setOnShowListener {
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val n = name.text.toString().trim()
                    val rawAddr = addr.text.toString().trim()
                    val a = Circle.parseAddress(rawAddr)
                    when {
                        n.isBlank() -> host.toast(ctx.getString(R.string.circle_err_name))
                        rawAddr.isNotBlank() && a.isBlank() -> host.toast(ctx.getString(R.string.ca_err_address))
                        host.wallet.connected && a == host.wallet.address -> host.toast(ctx.getString(R.string.ca_err_self))
                        else -> {
                            val saved = CircleStore(ctx).put(c.copy(name = n, phone = phone.text.toString().trim(), address = a))
                            dlg.dismiss()
                            onSaved(saved)
                        }
                    }
                }
            }
            dlg.show()
            lastForm = dlg
        }

        /** "Send" from a contact row: amount and token, then the same one-approval transfer. */
        fun sendTo(host: MainActivity, c: Contact, onChange: () -> Unit) {
            val ctx: Context = host
            val box = Ui.column(ctx, gap = 8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
            val amt = EditText(ctx).apply { tag = "circle-s-amount"; hint = ctx.getString(R.string.circle_s_amount); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            box.addView(amt)
            var token = "SOL"
            val chips = Ui.row(ctx, gap = 8)
            val tokens = listOf("SOL", "SKR", "USDC")
            fun paint() { for (i in 0 until chips.childCount) (chips.getChildAt(i) as android.widget.TextView).let { Ui.styleButton(it, if (tokens[i] == token) Ui.Btn.PRIMARY else Ui.Btn.GHOST) } }
            tokens.forEach { t -> chips.addView(Ui.weight(Ui.button(ctx, t, Ui.Btn.GHOST) { token = t; paint() }.apply { tag = "circle-s-$t"; textSize = 14f })) }
            paint()
            box.addView(chips)
            box.addView(Ui.muted(ctx, ctx.getString(R.string.circle_s_to, c.name, Fmt.short(c.address)), 12f))
            val dlg = android.app.AlertDialog.Builder(ctx).setTitle(ctx.getString(R.string.circle_send_title, c.name))
                .setView(box).setPositiveButton(R.string.circle_next, null).setNegativeButton(android.R.string.cancel, null).create()
            dlg.setOnShowListener {
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val v = amt.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                    if (v <= 0) { host.toast(ctx.getString(R.string.ca_err_amount)); return@setOnClickListener }
                    dlg.dismiss()
                    val a = CallAction(Circle.CONTACT_KEY + c.id + "#" + System.currentTimeMillis(), Circle.CONTACT_KEY + c.id, CallAction.PAYMENT, amount = v, token = token, recipient = c.name)
                    CallActionCards.payContact(host, a, c, onChange)
                }
            }
            dlg.show()
            lastForm = dlg
        }
    }
}
