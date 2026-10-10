package net.solardepin.solarchik.circle

import android.content.Context
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.screen.CallItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.2.4 Circle: the people the user pays, kept on this phone only. A contact has a name, an optional phone and an
 * optional Solana address the USER typed or pasted (never one heard on a call). The debt ledger is not stored
 * separately: it is read from the call action cards ("asks you to send 0.01 SOL" = you owe that caller 0.01 SOL;
 * a card paid from the app = settled, with its transaction signature).
 */
data class Contact(val id: String, val name: String, val phone: String = "", val address: String = "")

/** One line of the ledger: a payment someone asked for on a call. */
data class Debt(
    val action: CallAction,
    val call: CallItem?,
    val contact: Contact?,
    /** Who asked: the contact's name, else the caller's name on the call, else the card's recipient. */
    val who: String,
) {
    val open: Boolean get() = action.status == CallAction.OPEN
    val settled: Boolean get() = action.status == CallAction.DONE && action.signature.isNotBlank()
    val amount: Double get() = action.amount
    val token: String get() = action.token
    val at: Long get() = call?.at ?: 0L
}

object Circle {
    /** Digits only, last 9 (so +380 63 744 37 92, 0637443792 and 380637443792 are the same number). */
    fun phoneKey(p: String): String = p.filter { it.isDigit() }.takeLast(9).takeIf { it.length >= 7 }.orEmpty()

    /** Letters and digits only, lower case ("Ira", " ira " and "IRA!" are the same name). */
    fun nameKey(n: String): String = n.lowercase().filter { it.isLetterOrDigit() }

    /** The contact for a caller: same phone first, else the same name (first word is enough: "Ira" = "Ira K."). */
    fun match(contacts: List<Contact>, name: String, phone: String): Contact? {
        val pk = phoneKey(phone)
        if (pk.isNotBlank()) contacts.firstOrNull { phoneKey(it.phone) == pk }?.let { return it }
        val nk = nameKey(name)
        if (nk.isBlank()) return null
        contacts.firstOrNull { nameKey(it.name) == nk }?.let { return it }
        val first = nameKey(name.trim().split(Regex("\\s+")).firstOrNull().orEmpty())
        return contacts.firstOrNull { c -> first.isNotBlank() && nameKey(c.name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()) == first }
    }

    /** Payment requests from calls, matched to contacts. Dismissed cards are not debts. Newest first. */
    fun debts(actions: List<CallAction>, calls: List<CallItem>, contacts: List<Contact>): List<Debt> =
        actions.filter { it.payment && it.amount > 0 && it.status != CallAction.DISMISSED }.map { a ->
            val call = calls.firstOrNull { it.key == a.callKey }
            val name = call?.who?.takeIf { it.isNotBlank() } ?: a.recipient
            val contact = if (a.callKey.startsWith(CONTACT_KEY)) contacts.firstOrNull { CONTACT_KEY + it.id == a.callKey }
                else match(contacts, name, call?.dialNumber.orEmpty())
            Debt(a, call, contact, contact?.name ?: name)
        }.sortedByDescending { it.at }

    /** "0.01 SOL, 5 SKR" owed to [who] (open debts only), summed per token. */
    fun owedTo(debts: List<Debt>): Map<String, Map<String, Double>> =
        debts.filter { it.open }.groupBy { nameKey(it.who) }.mapValues { (_, l) -> l.groupBy { it.token }.mapValues { e -> e.value.sumOf { it.amount } } }

    fun amount(v: Double): String = java.math.BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** The user pasted an address or a solana: link ("solana:ADDR?amount=0.01"): the address, or "" if not valid. */
    fun parseAddress(s: String): String {
        val t = s.trim().removePrefix("solana:").substringBefore('?').substringBefore('/').trim()
        return t.takeIf { CallActionRules.validAddress(it) }.orEmpty()
    }

    /** The message for "Ask Ira for wallet" (the user sends it from their own SMS / messenger). */
    fun askText(ctx: Context, who: String, d: Debt?): String = if (d != null)
        ctx.getString(net.solardepin.solarchik.R.string.circle_ask_text_amount, who, amount(d.amount), d.token)
    else ctx.getString(net.solardepin.solarchik.R.string.circle_ask_text, who)

    fun solscan(sig: String): String = "https://solscan.io/tx/$sig"

    /** Morning briefing lines: "You owe Ira 0.01 SOL from yesterday's call." (open debts from calls, at most 2). */
    fun briefLines(ctx: Context, debts: List<Debt>, now: Long, zone: java.time.ZoneId): List<String> =
        debts.filter { it.open && it.call != null }.take(2).map { d ->
            val day = java.time.Instant.ofEpochMilli(d.at).atZone(zone).toLocalDate()
            val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val whenRes = when (day) {
                today -> net.solardepin.solarchik.R.string.circle_brief_today
                today.minusDays(1) -> net.solardepin.solarchik.R.string.circle_brief_yesterday
                else -> net.solardepin.solarchik.R.string.circle_brief_earlier
            }
            ctx.getString(net.solardepin.solarchik.R.string.circle_brief_line, d.who, amount(d.amount), d.token, ctx.getString(whenRes))
        }

    /** Everything you owe, in one phrase per person: "Ira 0.01 SOL; Petro 5 SKR". */
    fun owedPhrase(debts: List<Debt>): String =
        debts.filter { it.open }.groupBy { it.who }.entries.joinToString("; ") { (who, l) ->
            who + " " + l.groupBy { it.token }.entries.joinToString(" + ") { (t, x) -> amount(x.sumOf { it.amount }) + " " + t }
        }

    /** 1.2.7 "Owes you": callers who said they will send YOU money (a note only; it never creates a transfer). */
    fun owed(actions: List<CallAction>, calls: List<CallItem>, contacts: List<Contact>): List<Debt> =
        actions.filter { it.type == CallAction.OWED && it.amount > 0 && it.status != CallAction.DISMISSED }.map { a ->
            val call = calls.firstOrNull { it.key == a.callKey }
            val name = call?.who?.takeIf { it.isNotBlank() } ?: a.recipient
            val contact = match(contacts, name, call?.dialNumber.orEmpty())
            Debt(a, call, contact, contact?.name ?: name)
        }.sortedByDescending { it.at }

    fun currentOwed(ctx: Context): List<Debt> = owed(net.solardepin.solarchik.screen.CallActionStore(ctx).all(), net.solardepin.solarchik.screen.CallInbox.cached(ctx), CircleStore(ctx).all())

    /** "0.01 SOL + 5 SKR" for open lines. */
    fun sumText(debts: List<Debt>): String = debts.filter { it.open }.groupBy { it.token }.entries.joinToString(" + ") { (t, x) -> amount(x.sumOf { it.amount }) + " " + t }

    /** Debts for the current phone data. */
    fun current(ctx: Context): List<Debt> = debts(net.solardepin.solarchik.screen.CallActionStore(ctx).all(), net.solardepin.solarchik.screen.CallInbox.cached(ctx), CircleStore(ctx).all())

    const val CONTACT_KEY = "contact:"
}

/** Local store of the Circle (SharedPreferences, wiped by "Delete my data"). */
class CircleStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Contact> = runCatching {
        val a = JSONArray(p.getString("contacts", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Contact(it.getString("id"), it.optString("name"), it.optString("phone"), it.optString("address")) } }
    }.getOrDefault(emptyList())

    fun save(list: List<Contact>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone).put("address", it.address)) }
        p.edit().putString("contacts", a.toString()).apply()
    }

    /** Adds or replaces by id; returns the saved contact. */
    fun put(c: Contact): Contact {
        val fixed = c.copy(id = c.id.ifBlank { "c" + System.currentTimeMillis().toString(36) + (0..999).random() }, name = c.name.trim(), phone = c.phone.trim(), address = c.address.trim())
        save(all().filterNot { it.id == fixed.id } + fixed)
        return fixed
    }

    fun delete(id: String) = save(all().filterNot { it.id == id })
    fun find(id: String): Contact? = all().firstOrNull { it.id == id }

    companion object { const val PREFS = "solarchik.circle" }
}

/** 1.2.4: tells the worker a Circle payment to a caller was confirmed on chain (it checks the signature itself). */
object Settled {
    fun report(ctx: Context, number: String, amount: String, token: String, signature: String, owner: String? = null): Boolean = runCatching {
        val userId = owner?.takeIf { it.isNotBlank() } ?: net.solardepin.solarchik.screen.PlayerIds.get(ctx)
        val body = JSONObject().put("userId", userId).put("number", number).put("amount", amount.toDouble()).put("token", token).put("signature", signature).toString()
        net.solardepin.solarchik.sol.Briefing.httpPost(net.solardepin.solarchik.screen.ScreenApi.BASE + "/circle/settled", body).first == 200
    }.getOrDefault(false)
}
