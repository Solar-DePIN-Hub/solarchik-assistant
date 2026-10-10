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

    /** 1.2.9: today's log for the Clocked-in summary ("d|label" done, "l|label" snoozed), this phone only. */
    const val LOG_DONE = 'd'
    const val LOG_TOMORROW = 't'
    const val LOG_DISMISSED = 'x'

    fun log(ctx: Context, done: Boolean, label: String, now: Long = System.currentTimeMillis()) = log(ctx, if (done) LOG_DONE else LOG_TOMORROW, label, now)

    fun log(ctx: Context, kind: Char, label: String, now: Long = System.currentTimeMillis()) {
        if (label.isBlank()) return
        val d = day(now).toString()
        val cur = if (p(ctx).getString("logday", "") == d) p(ctx).getString("log", "").orEmpty() else ""
        val line = "$kind|" + label.replace('\n', ' ').take(60)
        val lines = cur.split('\n').filter { it.isNotBlank() && it.substring(2) != line.substring(2) } + line
        p(ctx).edit().putString("logday", d).putString("log", lines.takeLast(20).joinToString("\n")).apply()
    }

    /** Labels logged today by kind ([LOG_DONE], [LOG_TOMORROW], [LOG_DISMISSED]). */
    fun todayLog(ctx: Context, now: Long = System.currentTimeMillis()): Map<Char, List<String>> {
        if (p(ctx).getString("logday", "") != day(now).toString()) return emptyMap()
        val lines = p(ctx).getString("log", "").orEmpty().split('\n').filter { it.length > 2 }
        return lines.groupBy({ it[0] }, { it.substring(2) })
    }

    /** 1.2.9 "Later today": the card goes to the end of today's stack (it comes back after the others). */
    fun laterToday(ctx: Context, key: String, now: Long = System.currentTimeMillis()) {
        val d = day(now).toString()
        val cur = if (p(ctx).getString("backday", "") == d) p(ctx).getString("back", "").orEmpty().split(',').filter { it.isNotBlank() } else emptyList()
        p(ctx).edit().putString("backday", d).putString("back", (cur - key + key).joinToString(",")).apply()
    }

    private fun backOrder(ctx: Context, now: Long): List<String> =
        if (p(ctx).getString("backday", "") == day(now).toString()) p(ctx).getString("back", "").orEmpty().split(',').filter { it.isNotBlank() } else emptyList()

    fun snoozedUntil(ctx: Context, id: String): Long = p(ctx).getLong("snooze:$id", 0L)

    /**
     * Open call cards not snoozed and not already waiting on a reminder. 1.2.7 order: people first (call-backs,
     * reminders), then payments, then "Owes you" lines with no request out yet ("Andrii owes you 0.01 SOL: Request").
     */
    fun items(ctx: Context, now: Long = System.currentTimeMillis()): List<CallAction> {
        val asked = runCatching { net.solardepin.solarchik.circle.PayRequestStore(ctx).all().map { it.actionId }.toSet() }.getOrDefault(emptySet())
        val open = CallActionStore(ctx).open().filter {
            snoozedUntil(ctx, it.id) <= now && !(it.type == CallAction.REMINDER && it.remindAt > now) && !(it.type == CallAction.OWED && (it.id in asked || it.amount <= 0))
        }.reversed()
        return open.sortedBy { when (it.type) { CallAction.CALLBACK -> 0; CallAction.REMINDER -> 1; CallAction.PAYMENT -> 2; CallAction.OWED -> 3; else -> 4 } }
    }

    /** 1.2.7: the whole deck in order: call-backs and reminders, payments, habits, then the Season task. */
    fun deck(ctx: Context, now: Long = System.currentTimeMillis()): List<StackItem> =
        (items(ctx, now).map { StackItem.Call(it) } + Habits.pending(ctx, now).map { StackItem.Habit(it) } + seasonItems(ctx, now).map { StackItem.Season(it) })
            .let { all -> val back = backOrder(ctx, now); all.sortedBy { back.indexOf(it.key) } } // "Later today" cards last

    /**
     * 1.2.6: after the call cards, today's Season tasks (official partner drops from the Season agent). Opening one
     * only opens the official link (assist-only); it counts as done when the user says so. Done is kept per drop.
     */
    fun seasonItems(ctx: Context, now: Long = System.currentTimeMillis(), lang: String = net.solardepin.solarchik.core.AppLocale.lang(ctx)): List<net.solardepin.solarchik.season.SeasonDrop> =
        if (!Habits.on(ctx, Habits.SEASON)) emptyList() else net.solardepin.solarchik.season.SeasonDropsStore(ctx).doc(lang)?.items.orEmpty()
            .filter { it.sourceUrl.isNotBlank() && !p(ctx).getBoolean("sdone:" + it.id, false) && snoozedUntil(ctx, "season:" + it.id) <= now }
            .distinctBy { it.app }.take((SEASON_PER_DAY - seasonHandled(ctx, now)).coerceAtLeast(0))

    /** 1.2.7: the "Today's Season task" habit is ONE Season card a day (done or later), so the stack stays short. */
    const val SEASON_PER_DAY = 1
    private fun seasonHandled(ctx: Context, now: Long): Int {
        val d = day(now, ZoneId.systemDefault()).toString()
        return if (p(ctx).getString("sday", "") == d) p(ctx).getInt("sn", 0) else 0
    }
    fun seasonHandledOne(ctx: Context, now: Long = System.currentTimeMillis()) {
        val d = day(now, ZoneId.systemDefault()).toString()
        p(ctx).edit().putString("sday", d).putInt("sn", seasonHandled(ctx, now) + 1).apply()
    }

    fun seasonDone(ctx: Context, id: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        p(ctx).edit().putBoolean("sdone:$id", true).apply()
        seasonHandledOne(ctx, now)
        touched(ctx, now, zone)
    }

    /** Everything waiting: call cards, habits, then the Season task. */
    fun pendingCount(ctx: Context, now: Long = System.currentTimeMillis()): Int = items(ctx, now).size + Habits.pending(ctx, now).size + seasonItems(ctx, now).size

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
        // 1.2.9 (tablet: "Clocked in!" appeared by itself after a payment card left the stack): touching the
        // stack no longer clocks in; the user taps "Clock in" (see [readyToClock] / [settle]).
        p(ctx).edit().putString("touched", day(now, zone).toString()).apply()
    }

    /** The stack is clear today and something was handled: Today shows the explicit "Clock in" button. */
    fun readyToClock(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
        !clockedIn(ctx, now, zone) && p(ctx).getString("touched", "") == day(now, zone).toString() && pendingCount(ctx, now) == 0

    /** Stack empty and worked today: clock in for today (once). Returns true when clocked in today. */
    fun settle(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val today = day(now, zone).toString()
        if (clockedIn(ctx, now, zone)) return true
        if (p(ctx).getString("touched", "") != today || pendingCount(ctx, now) > 0) return false
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
        return pendingCount(ctx, now)
    }

    fun markNotified(ctx: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) =
        p(ctx).edit().putString("notified", day(now, zone).toString()).apply()

    /** Voice answer to a read-aloud card: "done", "later" or "pay" (EN/UK); null when not understood. */
    enum class Answer { DONE, LATER, DO }

    fun answer(heard: String?): Answer? {
        val m = heard.orEmpty().lowercase()
        return when {
            Regex("\\b(later|tomorrow|skip|snooze|not now)\\b|пізніше|потім|завтра|пропусти").containsMatchIn(m) -> Answer.LATER
            Regex("\\b(pay|send|call|dial|remind|open|do it)\\b|відкрий|заплати|оплати|надішли|подзвони|набери|нагадай|зроби").containsMatchIn(m) -> Answer.DO
            Regex("\\b(done|did it|finished|ok|okay|yes)\\b|готово|зроблено|так|вже").containsMatchIn(m) -> Answer.DONE
            else -> null
        }
    }
}
