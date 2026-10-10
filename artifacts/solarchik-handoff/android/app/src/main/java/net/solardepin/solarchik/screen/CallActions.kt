package net.solardepin.solarchik.screen

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.solardepin.solarchik.delegate.SplIx
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.solana.SystemIx
import net.solardepin.solarchik.swap.SwapTokens
import org.json.JSONArray
import org.json.JSONObject
import org.sol4k.PublicKey
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 1.1.0 call -> action. When a secretary note holds a concrete request (pay someone, call back at a time,
 * remind me), it becomes an action card on Today and on the call screen:
 *  - payment: a prepared SOL or USDC transfer that the user confirms in the wallet. The recipient is NEVER filled
 *    in from the call: the user types or pastes it, and an address the caller said is only shown in full with a
 *    scam warning and must be confirmed by hand. Nothing signs automatically.
 *  - callback: one tap opens the dialer with the number (the user presses call) and an optional reminder.
 *  - reminder: a local notification at the time.
 * The worker (POST /call/actions, structured output) finds the requests; offline, a small local rule set does.
 */
data class CallAction(
    val id: String,
    val callKey: String,
    val type: String,
    val amount: Double = 0.0,
    val token: String = "",
    /** Who should get a payment, in the caller's words ("Olena"); never used as an address. */
    val recipient: String = "",
    /** An address the caller literally said (shown in full with a scam warning, never pre-filled). */
    val saidAddress: String = "",
    val number: String = "",
    val time: String = "",
    val day: String = "",
    val text: String = "",
    val quote: String = "",
    val source: String = SOURCE_WORKER,
    val status: String = OPEN,
    val signature: String = "",
    val remindAt: Long = 0L,
    /** 1.1.3: the action's local date (YYYY-MM-DD) when a day or date was said ("remind her Monday"), else "". */
    val date: String = "",
) {
    val payment: Boolean get() = type == PAYMENT

    companion object {
        const val PAYMENT = "payment"
        const val CALLBACK = "callback"
        const val REMINDER = "reminder"
        const val OPEN = "open"
        const val DONE = "done"
        const val DISMISSED = "dismissed"
        const val SOURCE_WORKER = "worker"
        const val SOURCE_LOCAL = "local"
    }
}

object CallActionRules {
    const val MAX_PER_SYNC = 6
    /** 1.1.3: a call can hold a payment, a callback and a reminder. */
    const val MAX_PER_CALL = 3
    /** A reminder with a day but no time fires at this local time. */
    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
    private val YMD = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    const val WINDOW_MS = 7L * 24 * 3600_000L
    /** Amounts above these get an extra "large amount" warning on the card. */
    const val LARGE_SOL = 0.5
    const val LARGE_USDC = 50.0
    /** 1.2.0: tokens a call payment card can send (SKR added: Pay 50 SKR to Ira). */
    val PAY_TOKENS = setOf("SOL", "USDC", "SKR")
    /** 1.2.0: SKR above this asks for the extra "large payment" confirmation. */
    const val LARGE_SKR = 500.0
    private val BASE58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    /** Pure: answered calls with a note, last 7 days, not yet looked at. */
    fun candidates(calls: List<CallItem>, processed: Set<String>, now: Long): List<CallItem> =
        calls.filter { !it.blocked && it.status == CallInbox.DONE && it.key !in processed && now - it.at in 0..WINDOW_MS && (it.intent + it.notes + it.text).isNotBlank() }
            .sortedByDescending { it.at }.take(MAX_PER_SYNC)

    fun requestBody(calls: List<CallItem>, lang: String, zone: ZoneId, now: Long = System.currentTimeMillis()): String = JSONObject()
        .put("lang", lang).put("app", "assistant")
        // 1.1.3: the phone's zone and date, so "Monday" / "tomorrow" resolve to the user's own calendar day.
        .put("tz", zone.id).put("today", Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString())
        .put("calls", JSONArray().apply {
            calls.forEach { c ->
                put(JSONObject().put("id", c.key).put("who", c.who).put("callback", c.callback.takeIf { it != "unknown" }.orEmpty())
                    .put("at", Instant.ofEpochMilli(c.at).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString())
                    .put("date", Instant.ofEpochMilli(c.at).atZone(zone).toLocalDate().toString())
                    .put("intent", c.intent).put("notes", c.notes).put("text", c.text))
            }
        }).toString()

    /** Pure: the worker's sanitized reply -> actions (unknown calls/types dropped again on this side). */
    fun parse(body: String, calls: List<CallItem>): Pair<List<CallAction>, Set<String>>? = runCatching {
        val o = JSONObject(body)
        if (!o.optBoolean("ok")) return null
        val keys = calls.map { it.key }.toSet()
        val arr = o.optJSONArray("actions") ?: JSONArray()
        val out = ArrayList<CallAction>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val key = a.optString("callId")
            val type = a.optString("type")
            if (key !in keys || type !in listOf(CallAction.PAYMENT, CallAction.CALLBACK, CallAction.REMINDER)) continue
            val addr = a.optString("address").takeIf { validAddress(it) }.orEmpty()
            out += CallAction(
                id = "$key#${out.count { it.callKey == key }}", callKey = key, type = type,
                amount = a.optDouble("amount", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
                token = a.optString("token").uppercase().takeIf { it == "SOL" || it == "USDC" || it == "SKR" }.orEmpty(),
                recipient = a.optString("recipient").take(80), saidAddress = addr,
                number = a.optString("number").filter { it.isDigit() || it == '+' }.take(16),
                time = a.optString("when").takeIf { Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(it) }.orEmpty(),
                day = a.optString("day").takeIf { it == "today" || it == "tomorrow" }.orEmpty(),
                text = a.optString("text").take(100), quote = a.optString("quote").take(200),
                date = if (type == CallAction.PAYMENT) "" else validDate(a.optString("date")),
            )
        }
        if (out.any { it.payment && it.amount <= 0.0 }) out.removeAll { it.payment && it.amount <= 0.0 }
        val processed = (o.optJSONArray("processed") ?: JSONArray()).let { p -> (0 until p.length()).map { p.getString(it) }.filter { it in keys }.toSet() }
        out to processed
    }.getOrNull()

    private val PAY_EN = Regex("(?i)\\b(?:send|pay|transfer|wire)\\b[^.?!]{0,40}?(\\d+(?:[.,]\\d+)?)\\s*(sol|usdc|skr|usd|dollars?|\\$)")
    private val PAY_UK = Regex("(?iu)(?:надішли|надіслати|скинь|скинути|переказати|перекажи|заплати|оплати)[^.?!]{0,40}?(\\d+(?:[.,]\\d+)?)\\s*(sol|usdc|skr|usd|долар\\w*|\\$)")
    private val CALL_EN = Regex("(?i)\\bcall (?:me |her |him |them |us )?back\\b(?:[^.?!]{0,30}?\\bat (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?)?")
    private val CALL_UK = Regex("(?iu)\\b(?:передзвони|передзвоніть|перетелефонуй)\\w*(?:[^.?!]{0,30}?\\bо (\\d{1,2})(?::(\\d{2}))?)?")
    private val REMIND_EN = Regex("(?i)\\b(?:remind (?:me|yourself|you|her|him|them)|don'?t forget)\\b[^.?!]{0,60}?(?:\\bat (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?)?")
    private val REMIND_UK = Regex("(?iu)(?:\\bнагада\\w*|\\bне забу\\w*)[^.?!]{0,60}?(?:\\bо (\\d{1,2})(?::(\\d{2}))?)?")
    private val DAY_WORDS = listOf(
        DayOfWeek.MONDAY to Regex("(?iu)\\bmonday\\b|понеділ"), DayOfWeek.TUESDAY to Regex("(?iu)\\btuesday\\b|вівтор"),
        DayOfWeek.WEDNESDAY to Regex("(?iu)\\bwednesday\\b|серед[уаи]\\b"), DayOfWeek.THURSDAY to Regex("(?iu)\\bthursday\\b|четвер"),
        DayOfWeek.FRIDAY to Regex("(?iu)\\bfriday\\b|п.ятниц"), DayOfWeek.SATURDAY to Regex("(?iu)\\bsaturday\\b|субот"),
        DayOfWeek.SUNDAY to Regex("(?iu)\\bsunday\\b|(?<!по)неділ"),
    )
    private val TOMORROW = Regex("(?iu)\\btomorrow\\b|завтра")

    /** Pure: a weekday or "tomorrow" in [text] -> that local date after the call's day (same weekday = next week). */
    fun localDate(text: String, callAt: Long, zone: ZoneId): String {
        val base = Instant.ofEpochMilli(callAt).atZone(zone).toLocalDate()
        if (TOMORROW.containsMatchIn(text)) return base.plusDays(1).toString()
        val dow = DAY_WORDS.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return ""
        return base.with(TemporalAdjusters.next(dow)).toString()
    }

    fun validDate(s: String): String = s.trim().takeIf { YMD.matches(it) && runCatching { LocalDate.parse(it) }.isSuccess }.orEmpty()

    /** Offline fallback: a few plain rules over the note (EN/UK). Same safety: no address is ever taken over. */
    fun local(c: CallItem): List<CallAction> {
        val src = listOf(c.intent, c.notes, c.text).joinToString(". ")
        val out = ArrayList<CallAction>()
        (PAY_EN.find(src) ?: PAY_UK.find(src))?.let { m ->
            val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            val word = m.groupValues[2].lowercase()
            val token = when (word) { "sol" -> "SOL"; "skr" -> "SKR"; else -> "USDC" }
            val said = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}").find(src)?.value?.takeIf { validAddress(it) }.orEmpty()
            if (amount > 0) out += CallAction("${c.key}#0", c.key, CallAction.PAYMENT, amount = amount, token = token, recipient = c.who, saidAddress = said, quote = m.value.take(120), source = CallAction.SOURCE_LOCAL)
        }
        (CALL_EN.find(src) ?: CALL_UK.find(src))?.let { m ->
            val t = hhmm(m.groupValues.getOrNull(1), m.groupValues.getOrNull(2), m.groupValues.getOrNull(3))
            out += CallAction("${c.key}#${out.size}", c.key, CallAction.CALLBACK, number = c.dialNumber.filter { it.isDigit() || it == '+' }, time = t, quote = m.value.take(120), source = CallAction.SOURCE_LOCAL)
        }
        if (out.size < MAX_PER_CALL) (REMIND_EN.find(src) ?: REMIND_UK.find(src))?.let { m ->
            val t = hhmm(m.groupValues.getOrNull(1), m.groupValues.getOrNull(2), m.groupValues.getOrNull(3))
            // the rest of the sentence after "remind ..." holds its day ("... about the meeting on Monday")
            val sentence = src.substring(m.range.first).split(Regex("[.?!]")).first()
            out += CallAction("${c.key}#${out.size}", c.key, CallAction.REMINDER, time = t, text = c.intent.take(100),
                quote = m.value.take(120), source = CallAction.SOURCE_LOCAL, date = localDate(sentence, c.at, ZoneId.systemDefault()))
        }
        return out.take(MAX_PER_CALL)
    }

    /** "3", "", "pm" -> "15:00"; a bare 1..7 is read as afternoon (business calls), 8..11 as morning. */
    fun hhmm(h: String?, m: String?, ampm: String?): String {
        var hour = h?.toIntOrNull() ?: return ""
        val min = m?.toIntOrNull() ?: 0
        when (ampm?.lowercase()) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
            else -> if (hour in 1..7) hour += 12
        }
        if (hour !in 0..23 || min !in 0..59) return ""
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, min)
    }

    /**
     * Pure: when a callback/reminder should fire. 1.1.3: with a date ("Monday") on that day at [time] or
     * [DEFAULT_TIME]; if that moment has passed, the old rule (next day at the time) applies.
     * Without a date: at [time] on the call's day (+1 for "tomorrow"), never in the past.
     */
    fun remindAt(a: CallAction, callAt: Long, now: Long, zone: ZoneId): Long {
        if (a.date.isNotBlank() && a.type != CallAction.PAYMENT) {
            val d = runCatching { LocalDate.parse(a.date) }.getOrNull()
            val t = a.time.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: DEFAULT_TIME
            val at = d?.atTime(t)?.atZone(zone)?.toInstant()?.toEpochMilli()
            if (at != null && at > now) return at
        }
        if (a.time.isBlank()) return 0L
        val t = LocalTime.parse(a.time)
        var d = Instant.ofEpochMilli(callAt).atZone(zone).toLocalDate()
        if (a.day == "tomorrow") d = d.plusDays(1)
        var at = d.atTime(t).atZone(zone).toInstant().toEpochMilli()
        while (at <= now) { d = d.plusDays(1); at = d.atTime(t).atZone(zone).toInstant().toEpochMilli() }
        return at
    }

    fun validAddress(s: String): Boolean = BASE58.matches(s.trim()) && runCatching { PublicKey(s.trim()); true }.getOrDefault(false)

    /** Raw units for a payment (SOL 9 decimals, USDC 6, SKR 6 — checked on mainnet 2026-10-10); 0 when not a positive amount. */
    fun amountRaw(token: String, amount: Double): Long {
        val dec = if (token == "SOL") 9 else 6
        if (!amount.isFinite() || amount <= 0) return 0L
        return BigDecimal.valueOf(amount).movePointRight(dec).setScale(0, RoundingMode.DOWN).toLong()
    }

    fun large(token: String, amount: Double): Boolean = when (token) { "SOL" -> amount > LARGE_SOL; "SKR" -> amount > LARGE_SKR; else -> amount > LARGE_USDC }

    /**
     * The transfer the USER signs in the wallet: SOL System transfer, or USDC TransferChecked from the user's
     * USDC account to the recipient's (created idempotently, paid by the user, only if missing).
     */
    fun paymentTx(owner: PublicKey, recipient: PublicKey, token: String, raw: Long, blockhash: ByteArray): LegacyTx {
        require(raw > 0L) { "amount must be > 0" }
        require(owner != recipient) { "recipient is your own wallet" }
        return when (token) {
            "SOL" -> LegacyTx.compile(owner, blockhash, listOf(SystemIx.transfer(owner, recipient, raw)))
            "USDC", "SKR" -> {
                // 1.2.0: SKR is a classic SPL Token (Tokenkeg) mint with 6 decimals, the same path as USDC
                val t = if (token == "SKR") SwapTokens.SKR else SwapTokens.USDC
                val mint = PublicKey(t.mint)
                LegacyTx.compile(owner, blockhash, listOf(
                    SplIx.createAtaIdempotent(owner, recipient, mint),
                    SplIx.transferChecked(SplIx.ata(owner, mint), mint, SplIx.ata(recipient, mint), owner, raw, t.decimals),
                ))
            }
            else -> throw IllegalArgumentException("token")
        }
    }
}

class CallActionStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun processed(): Set<String> = p.getStringSet("processed", emptySet()).orEmpty()
    fun markProcessed(keys: Collection<String>) {
        if (keys.isEmpty()) return
        p.edit().putStringSet("processed", (processed() + keys).toList().takeLast(400).toSet()).apply()
    }

    fun all(): List<CallAction> = runCatching {
        val a = JSONArray(p.getString("actions", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CallAction(o.getString("id"), o.getString("callKey"), o.getString("type"), o.optDouble("amount", 0.0), o.optString("token"),
                o.optString("recipient"), o.optString("said"), o.optString("number"), o.optString("time"), o.optString("day"),
                o.optString("text"), o.optString("quote"), o.optString("source", CallAction.SOURCE_WORKER), o.optString("status", CallAction.OPEN),
                o.optString("sig"), o.optLong("remindAt"), o.optString("date"))
        }
    }.getOrDefault(emptyList())

    fun save(list: List<CallAction>) {
        val a = JSONArray()
        list.takeLast(120).forEach {
            a.put(JSONObject().put("id", it.id).put("callKey", it.callKey).put("type", it.type).put("amount", it.amount).put("token", it.token)
                .put("recipient", it.recipient).put("said", it.saidAddress).put("number", it.number).put("time", it.time).put("day", it.day)
                .put("text", it.text).put("quote", it.quote).put("source", it.source).put("status", it.status).put("sig", it.signature).put("remindAt", it.remindAt).put("date", it.date))
        }
        p.edit().putString("actions", a.toString()).apply()
    }

    fun add(list: List<CallAction>) { if (list.isNotEmpty()) save(all().filterNot { a -> list.any { it.id == a.id } } + list) }
    fun update(id: String, f: (CallAction) -> CallAction) = save(all().map { if (it.id == id) f(it) else it })
    fun open(): List<CallAction> = all().filter { it.status == CallAction.OPEN }
    fun forCall(key: String): List<CallAction> = all().filter { it.callKey == key }
    fun find(id: String): CallAction = all().first { it.id == id }

    companion object { const val PREFS = "solarchik.callactions" }
}

object CallActionSync {
    /**
     * Looks at new answered calls once: the worker first, the local rules when it is unreachable.
     * Returns the new actions. [post] is the HTTP call (tests script it).
     */
    fun run(ctx: Context, calls: List<CallItem>, lang: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
            post: (String, String) -> Pair<Int, String> = net.solardepin.solarchik.sol.Briefing::httpPost): List<CallAction> {
        val store = CallActionStore(ctx)
        val todo = CallActionRules.candidates(calls, store.processed(), now)
        if (todo.isEmpty()) return emptyList()
        val reply = runCatching { post(ScreenApi.BASE + "/call/actions", CallActionRules.requestBody(todo, lang, zone)) }.getOrNull()
        val parsed = reply?.takeIf { it.first == 200 }?.let { CallActionRules.parse(it.second, todo) }
        val found = parsed?.first ?: todo.flatMap { CallActionRules.local(it) }
        val done = parsed?.second?.takeIf { it.isNotEmpty() } ?: todo.map { it.key }.toSet()
        store.add(found)
        store.markProcessed(done)
        return found
    }
}

/** Local reminder for an action ("remind me", "call back at 3"): a one-off notification at the time. */
object ActionReminders {
    fun schedule(ctx: Context, a: CallAction, title: String, body: String, at: Long, now: Long = System.currentTimeMillis()) {
        val req = OneTimeWorkRequestBuilder<ActionReminderWorker>()
            .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to a.id, "title" to title, "body" to body, "number" to a.number))
            .build()
        runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork("call-action-" + a.id.hashCode(), ExistingWorkPolicy.REPLACE, req) }
        CallActionStore(ctx).update(a.id) { it.copy(remindAt = at) }
    }
}

class ActionReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id").orEmpty()
        val open = android.content.Intent(applicationContext, net.solardepin.solarchik.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(net.solardepin.solarchik.MainActivity.EXTRA_CALL_ACTION, id)
        }
        net.solardepin.solarchik.autopilot.AutoRunner.notify(applicationContext, 500 + Math.floorMod(id.hashCode(), 80),
            inputData.getString("title").orEmpty(), inputData.getString("body").orEmpty(), open)
        return Result.success()
    }
}
