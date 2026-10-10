package net.solardepin.solarchik.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.circle.Circle
import net.solardepin.solarchik.circle.CircleStore
import net.solardepin.solarchik.circle.Contact
import net.solardepin.solarchik.circle.Debt
import net.solardepin.solarchik.circle.PayRequest
import net.solardepin.solarchik.circle.PayRequestStore
import net.solardepin.solarchik.circle.PayWatch
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallText

/**
 * 1.2.7 Circle tab (redesign 04): the one debt that matters as a hero with one yellow Settle, then people with
 * "You owe" (gold), "Owes you" (green, a note only) or "Ask for wallet". Addresses are only ones the user saved;
 * Settle opens the wallet approval (with the pre-simulation's plain-words refusals) and nothing moves without it.
 */
class CircleScreen(host: MainActivity) : Screen(host) {
    private lateinit var body: LinearLayout
    private lateinit var summary: android.widget.TextView

    override fun build(): View = page {
        tag = "circle"
        val head = Ui.row(ctx, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        val t = Ui.column(ctx)
        t.addView(Ui.display(ctx, ctx.getString(R.string.circle_title), 32f).apply { maxLines = 1 })
        summary = Ui.text(ctx, "", 15f, Kit.MUTED, 700).apply { tag = "circle-summary"; maxLines = 2 }
        t.addView(Ui.top(summary, 4))
        head.addView(Ui.weight(t))
        head.addView(FrameLayout(ctx).apply {
            tag = "circle-add"
            contentDescription = ctx.getString(R.string.circle_add)
            // one filled yellow per screen (spec): the Settle button owns it, so + is a dark round button with a gold sign
            background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Kit.S2); setStroke(dp(1), Ui.withAlpha(Ui.GOLD, 0x66)) }, dp(28).toFloat())
            isClickable = true
            setOnClickListener { CirclePanel.edit(host, Contact("", "")) { render() } }
            addView(Kit.icon(ctx, R.drawable.lc_plus, Ui.GOLD, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(head)
        body = Ui.column(ctx, gap = 14)
        addView(Ui.top(body, 8))
    }

    /** Open Solana Pay requests are checked (read-only) each time the Circle shows. */
    override fun onShow() {
        render()
        if (MainActivity.tickerEnabled && PayRequestStore(ctx).open().isNotEmpty()) host.scope.launch {
            if (PayWatch.checkAll(ctx).isNotEmpty()) host.renderAll()
        }
    }

    override fun render() {
        if (!this::body.isInitialized) return
        body.removeAllViews()
        val contacts = CircleStore(ctx).all()
        val debts = Circle.current(ctx)
        val owed = Circle.currentOwed(ctx)
        val open = debts.filter { it.open }
        val openOwed = owed.filter { it.open }
        // summary: "You owe 0.01 SOL · owed 2 USDC"
        val sb = SpannableStringBuilder()
        fun part(res: Int, value: String, color: Int) {
            if (sb.isNotEmpty()) sb.append(" · ")
            val full = ctx.getString(res, value); val i = full.indexOf(value)
            val s0 = sb.length; sb.append(full)
            if (i >= 0) sb.setSpan(ForegroundColorSpan(color), s0 + i, s0 + i + value.length, 0)
        }
        if (open.isNotEmpty()) part(R.string.circle_sum_owe, Circle.sumText(open), Ui.GOLD)
        if (openOwed.isNotEmpty()) part(R.string.circle_sum_owed, Circle.sumText(openOwed), Ui.GREEN)
        summary.text = if (sb.isEmpty()) ctx.getString(R.string.circle_tab_sub_none) else sb

        body.addView(hero(open.maxByOrNull { it.amount * (if (it.token == "SOL") 150 else 1) }, openOwed.firstOrNull()))
        body.addView(Ui.top(Kit.section(ctx, ctx.getString(R.string.circle_people)), 8))
        val list = Kit.list(ctx)
        val names = LinkedHashMap<String, Pair<Contact?, String>>()
        contacts.sortedBy { it.name.lowercase() }.forEach { names[Circle.nameKey(it.name)] = it to it.name }
        (open + openOwed).forEach { d -> val k = Circle.nameKey(d.who); if (k !in names && d.contact == null) names[k] = null to d.who }
        names.values.forEach { (c, name) -> Kit.addRow(list, personRow(c, name, open, openOwed)) }
        Kit.addRow(list, Kit.row(ctx, null, Ui.GOLD, ctx.getString(R.string.circle_add_person), null, lead = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1.5f), Ui.withAlpha(Ui.GOLD, 0x99), dp(4).toFloat(), dp(3).toFloat()) }
            addView(Kit.icon(ctx, R.drawable.lc_plus, Ui.GOLD, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }) { CirclePanel.edit(host, Contact("", "")) { render() } }.apply {
            tag = "circle-add-row"
            ((getChildAt(1) as LinearLayout).getChildAt(0) as android.widget.TextView).setTextColor(Ui.GOLD)
        })
        body.addView(list)
        val settled = debts.filter { it.settled }.take(5)
        val gotPaid = owed.filter { it.settled }.take(5)
        if (settled.isNotEmpty() || gotPaid.isNotEmpty()) {
            body.addView(Ui.top(Kit.section(ctx, ctx.getString(R.string.circle_settled_label)), 8))
            val l = Kit.list(ctx)
            settled.forEach { d ->
                Kit.addRow(l, Kit.row(ctx, R.drawable.lc_check, Ui.GREEN, ctx.getString(R.string.circle_settled_line, d.who, Circle.amount(d.amount), d.token),
                    (if (d.at > 0) CallText.time(d.at) + " · " else "") + "Solscan ↗") { host.openUrl(Circle.solscan(d.action.signature)) }.apply { tag = "circle-settled" })
            }
            gotPaid.forEach { d ->
                Kit.addRow(l, Kit.row(ctx, R.drawable.lc_check, Ui.GREEN, ctx.getString(R.string.payreq_settled_line, d.who, Circle.amount(d.amount), d.token),
                    (if (d.at > 0) CallText.time(d.at) + " · " else "") + "Solscan ↗") { host.openUrl(Circle.solscan(d.action.signature)) }.apply { tag = "circle-got-paid" })
            }
            body.addView(l)
        }
        body.addView(Ui.text(ctx, ctx.getString(R.string.circle_body), 14f, Kit.MUTED, 600).apply { setLineSpacing(0f, 1.25f); tag = "circle-note" })
    }

    private fun hero(owe: Debt?, owed: Debt?): View {
        val d = owe ?: owed
        val color = if (owe != null) Ui.GOLD else Ui.GREEN
        return Ui.column(ctx).apply {
            tag = "circle-hero"
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.blend(Kit.S2, color, if (d != null) 0.22f else 0.05f), Kit.S1)).apply {
                cornerRadius = dp(30).toFloat(); setStroke(dp(1), Ui.withAlpha(color, if (d != null) 0x55 else 0x22))
            }
            setPadding(dp(20), dp(22), dp(20), dp(20))
            if (d == null) {
                addView(Ui.text(ctx, ctx.getString(R.string.circle_tab_sub_none), 18f, Ui.TEXT, 800))
                addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.circle_hero_empty), 15f, Kit.MUTED, 600).apply { setLineSpacing(0f, 1.25f) }, 8))
                return@apply
            }
            val r = Ui.row(ctx, gap = 14).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(Kit.avatar(ctx, d.who, 60))
            val c = Ui.column(ctx)
            c.addView(Ui.text(ctx, ctx.getString(if (owe != null) R.string.circle_hero_owe else R.string.circle_hero_owed).uppercase(), 14f, color, 800).apply { letterSpacing = 0.06f })
            c.addView(Ui.top(Ui.display(ctx, ctx.getString(R.string.circle_hero_line, d.who, Circle.amount(d.amount), d.token), 24f).apply {
                maxLines = 1; tag = "circle-hero-line"
                setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, 2).apply { layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { height = dp(32) } })
            r.addView(Ui.weight(c))
            addView(r)
            val reason = d.call?.let { ctx.getString(R.string.circle_hero_reason_call, d.who, CallText.time(it.at)) } ?: ctx.getString(R.string.circle_hero_reason_you)
            val quote = CallActionCards.inUi(d.action.quote).takeIf { it.length > 8 }
            // 1.2.8: quote and source on one line (mockup: "For lunch, from her call yesterday")
            addView(Ui.top(Ui.text(ctx, listOfNotNull(quote?.let { "“$it”" }, reason).joinToString(" · "), 15f, Ui.withAlpha(Ui.TEXT, 0xCC), 600).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f) }, 12))
            if (owe != null) {
                val ct = owe.contact
                if (ct != null && ct.address.isNotBlank()) {
                    addView(Ui.top(Kit.primary(ctx, ctx.getString(R.string.card_settle, Circle.amount(owe.amount), owe.token), R.drawable.lc_wallet) {
                        CallActionCards.payContact(host, owe.action, ct) { host.renderAll() }
                    }.apply { tag = "circle-settle" }, 16))
                } else {
                    addView(Ui.top(Kit.primary(ctx, ctx.getString(R.string.circle_add_wallet, owe.who), R.drawable.lc_wallet) {
                        CirclePanel.edit(host, ct ?: Contact("", owe.who, owe.call?.dialNumber.orEmpty()), ctx.getString(R.string.circle_add_wallet, owe.who)) { saved ->
                            host.renderAll()
                            if (saved.address.isNotBlank()) CallActionCards.payContact(host, owe.action, saved) { host.renderAll() }
                        }
                    }.apply { tag = "circle-add-wallet" }, 16))
                    // 1.2.8: a text link, not a second big button (mockup has one CTA)
                    addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.circle_ask, owe.who), 15f, Ui.CYAN, 800).apply {
                        tag = "circle-ask"; gravity = Gravity.CENTER; minHeight = dp(44); isClickable = true
                        setOnClickListener { CirclePanel.ask(host, owe.who, owe) }
                    }, 4))
                }
                if (ct != null && ct.address.isNotBlank()) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.card_pay_safe), 14f, Kit.MUTED, 600).apply { gravity = Gravity.CENTER }, 10))
            } else {
                // 1.2.7 two-way Circle: a Solana Pay request (QR + share link) to the user's own wallet
                val pending = PayRequestStore(ctx).forAction(d.action.id)?.takeIf { it.status == PayRequest.OPEN }
                if (pending != null) addView(Ui.top(Kit.chip(ctx, ctx.getString(R.string.payreq_pending, CallText.time(pending.at)), Ui.GREEN, R.drawable.lc_clock).apply { tag = "circle-request-pending" }, 14))
                addView(Ui.top(Kit.primary(ctx, ctx.getString(if (pending != null) R.string.payreq_show else R.string.payreq_btn, Circle.amount(d.amount), d.token), R.drawable.lc_wallet) {
                    PayRequestSheet.show(host, d) { host.renderAll() }
                }.apply { tag = "circle-request" }, 16))
                addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.circle_owed_yes), 15f, Ui.GREEN, 800).apply {
                    tag = "circle-owed-received"; gravity = Gravity.CENTER; minHeight = dp(44); isClickable = true
                    setOnClickListener { received(d) }
                }, 4))
            }
        }
    }

    private fun received(d: Debt) {
        android.app.AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.circle_owed_q, d.who, Circle.amount(d.amount), d.token))
            .setPositiveButton(R.string.circle_owed_yes) { _, _ -> CallActionStore(ctx).update(d.action.id) { it.copy(status = CallAction.DONE) }; host.renderAll() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun personRow(c: Contact?, name: String, owe: List<Debt>, owed: List<Debt>): View {
        val key = Circle.nameKey(name)
        val mine = owe.filter { (c != null && it.contact?.id == c.id) || Circle.nameKey(it.who) == key }
        val theirs = owed.filter { (c != null && it.contact?.id == c.id) || Circle.nameKey(it.who) == key }
        val sub = when {
            // 1.2.8: one short line next to a chip ("No wallet yet" wrapped under "Owes you 0.5 SOL")
            c == null || c.address.isBlank() -> ctx.getString(R.string.circle_no_wallet_short)
            c.phone.isNotBlank() -> Kit.phoneEnds(ctx, c.phone)
            else -> Fmt.short(c.address)
        }
        val chip = when {
            mine.isNotEmpty() -> Kit.chip(ctx, ctx.getString(R.string.circle_chip_owe, Circle.sumText(mine), "").trim(), Ui.GOLD)
            theirs.isNotEmpty() -> Kit.chip(ctx, ctx.getString(R.string.circle_chip_owed, Circle.sumText(theirs), "").trim(), Ui.GREEN)
            c == null || c.address.isBlank() -> Kit.chip(ctx, ctx.getString(R.string.circle_chip_ask), Ui.TEXT).apply { tag = "circle-ask-chip"; background = Ui.rounded(Ui.withAlpha(Color.WHITE, 0x10), dp(18).toFloat()) }
            else -> null
        }
        return Kit.row(ctx, null, Ui.TEXT, name, sub, trailing = chip ?: Kit.icon(ctx, R.drawable.lc_chev, Kit.MUTED, 20), lead = Kit.avatar(ctx, name, 52), subLines = 1) {
            person(c, name, mine, theirs)
        }.apply { tag = "circle-contact" }
    }

    /** One person: Settle / Send / Ask for wallet / Edit / Delete. */
    private fun person(c: Contact?, name: String, mine: List<Debt>, theirs: List<Debt>) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        mine.firstOrNull()?.let { d ->
            if (c != null && c.address.isNotBlank()) opts += ctx.getString(R.string.card_settle, Circle.amount(d.amount), d.token) to { CallActionCards.payContact(host, d.action, c) { host.renderAll() } }
        }
        theirs.firstOrNull()?.let { d ->
            opts += ctx.getString(R.string.payreq_btn, Circle.amount(d.amount), d.token) to { PayRequestSheet.show(host, d) { host.renderAll() } }
            opts += ctx.getString(R.string.circle_owed_yes) to { received(d) }
        }
        opts += ctx.getString(R.string.owed_add, name) to { PayRequestSheet.addOwed(host, c?.name ?: name) { host.renderAll() } }
        if (c != null && c.address.isNotBlank()) opts += ctx.getString(R.string.circle_send) to { CirclePanel.sendTo(host, c) { host.renderAll() } }
        if (c == null || c.address.isBlank()) {
            opts += ctx.getString(R.string.circle_add_wallet, name) to { CirclePanel.edit(host, c ?: Contact("", name, mine.firstOrNull()?.call?.dialNumber.orEmpty()), ctx.getString(R.string.circle_add_wallet, name)) { render() } }
            opts += ctx.getString(R.string.circle_ask, name) to { CirclePanel.ask(host, name, mine.firstOrNull()) }
        }
        if (c != null) {
            opts += ctx.getString(R.string.circle_edit) to { CirclePanel.edit(host, c) { render() } }
            opts += ctx.getString(R.string.circle_delete) to {
                android.app.AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.circle_delete_q, c.name))
                    .setPositiveButton(R.string.circle_delete) { _, _ -> CircleStore(ctx).delete(c.id); render() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        val dlg = android.app.AlertDialog.Builder(ctx).setTitle(name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .setNegativeButton(android.R.string.cancel, null).create()
        dlg.show()
        lastPerson = dlg
    }

    @androidx.annotation.VisibleForTesting
    var lastPerson: android.app.AlertDialog? = null
}
