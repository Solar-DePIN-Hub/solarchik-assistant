package net.solardepin.solarchik.stack

import android.content.Context
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 1.2.5 Morning stack (CLOCK IN for calls): the open cards from calls (pay / call back / remind), one at a
 * time. Do it, or Later (snoozed to tomorrow 06:00). When the stack is empty after you worked it today, you are
 * clocked in for the day; consecutive days make the streak. All of it lives on this phone: the streak is a local
 * habit counter, not an on-chain record (the optional CLOCK IN signature is the on-chain one).
 */
object MorningStack {
    const val PREFS = "solarchik.stack"
    const val BONUS_DAYS = 7

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun day(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    fun snoozedUntil(ctx: Context, id: String): Long = p(ctx).getLong("snooze:$id", 0L)

    /** Open cards not snoozed and not already waiting on a reminder; payments first, then call-backs, then the rest. */
    fun items(ctx: Context, now: Long = System.currentTimeMillis()): List<CallAction> {
        val open = CallActionStore(ctx).open().filter { snoozedUntil(ctx, it.id) <= now && !(it.type == CallAction.REMINDER && it.remindAt > now) }.reversed()
        return open.sortedBy { when (it.type) { CallAction.PAYMENT -> 0; CallAction.CALLBACK -> 1; else -> 2 } }
    }

    /** Later: back tomorrow morning (06:00 local), so the next morning's stack has it. */
    fun snooze(ctx: Context, id: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val until = day(now, zone).plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        p(ctx).edit().putLong("snooze:$id", until).apply()
        touched(ctx, now, zone)
        return until
    }

    /** Done (the user handled it: dialled, paid, or just says it's done). */
    fun done(ctx: Context, id: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        CallActionStore(ctx).update(id) { if (it.status == CallAction.OPEN) it.copy(status = CallAction.DONE) else it }
        touched(ctx, now, zone)
    }

    /** Something in the stack was handled today (counts toward clocking in once the stack is empty). */
    fun touched(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        p(ctx).edit().putString("touched", day(now, zone).toString()).apply()
        settle(ctx, now, zone)
    }

    /** Stack empty and worked today: clock in for today (once). Returns true when clocked in today. */
    fun settle(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val today = day(now, zone).toString()
        if (clockedIn(ctx, now, zone)) return true
        if (p(ctx).getString("touched", "") != today || items(ctx, now).isNotEmpty()) return false
        val days = (days(ctx) + today).sorted().takeLast(60)
        p(ctx).edit().putString("days", days.joinToString(",")).apply()
        return true
    }

    fun days(ctx: Context): Set<String> = p(ctx).getString("days", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    fun clockedIn(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = day(now, zone).toString() in days(ctx)

    /** Consecutive clocked-in days ending today (or yesterday, if today is not done yet). */
    fun streak(days: Set<String>, today: LocalDate): Int {
        var d = if (today.toString() in days) today else today.minusDays(1)
        var n = 0
        while (d.toString() in days) { n++; d = d.minusDays(1) }
        return n
    }

    fun streak(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = streak(days(ctx), day(now, zone))

    // ------------------------------------------------------------- morning notification (08:00, only with cards)

    fun notifyOn(ctx: Context): Boolean = p(ctx).getBoolean("notify", true)
    fun setNotify(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("notify", on).apply()

    /** Due once a day from 08:00 local, only when there are cards waiting. */
    fun notificationDue(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int {
        if (!notifyOn(ctx)) return 0
        val t = Instant.ofEpochMilli(now).atZone(zone)
        if (t.hour < 8) return 0
        if (p(ctx).getString("notified", "") == t.toLocalDate().toString()) return 0
        return items(ctx, now).size
    }

    fun markNotified(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) =
        p(ctx).edit().putString("notified", day(now, zone).toString()).apply()

    /** Voice answer to a read-aloud card: "done", "later" or "pay" (EN/UK); null when not understood. */
    enum class Answer { DONE, LATER, DO }

    fun answer(heard: String?): Answer? {
        val m = heard.orEmpty().lowercase()
        return when {
            Regex("\\b(later|tomorrow|skip|snooze|not now)\\b|пізніше|потім|завтра|пропусти").containsMatchIn(m) -> Answer.LATER
            Regex("\\b(pay|send|call|dial|remind|do it)\\b|заплати|оплати|надішли|подзвони|набери|нагадай|зроби").containsMatchIn(m) -> Answer.DO
            Regex("\\b(done|did it|finished|ok|okay|yes)\\b|готово|зроблено|так|вже").containsMatchIn(m) -> Answer.DONE
            else -> null
        }
    }
}
