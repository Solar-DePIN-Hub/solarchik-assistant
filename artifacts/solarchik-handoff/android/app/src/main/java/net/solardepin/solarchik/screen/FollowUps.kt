package net.solardepin.solarchik.screen

import android.content.Context

/**
 * 1.0.0: the Today "Follow-ups" list, built from the secretary's call archive on this phone.
 * A call lands here when you set a reminder on it, or when the caller left a number to call back.
 * "Done" is remembered locally; nothing is sent anywhere.
 */
data class FollowUp(val item: CallItem, val kind: Kind, val at: Long) {
    enum class Kind { REMINDER, CALLBACK }
}

object FollowUps {
    /** Callback asks older than this drop off the list on their own. */
    const val WINDOW_MS = 7L * 24 * 3600_000L
    private const val PREF = "solarchik.followups"

    /** Pure: reminders first (soonest first), then callback asks (newest first). */
    fun build(calls: List<CallItem>, reminderAt: (String) -> Long, done: Set<String>, now: Long): List<FollowUp> {
        val out = ArrayList<FollowUp>()
        for (c in calls) {
            if (c.blocked || c.key in done) continue
            val r = reminderAt(c.key)
            when {
                r > now -> out += FollowUp(c, FollowUp.Kind.REMINDER, r)
                c.callback.isNotBlank() && c.callback != "unknown" && c.status == CallInbox.DONE && now - c.at in 0..WINDOW_MS ->
                    out += FollowUp(c, FollowUp.Kind.CALLBACK, c.at)
            }
        }
        val rem = out.filter { it.kind == FollowUp.Kind.REMINDER }.sortedBy { it.at }
        val cb = out.filter { it.kind == FollowUp.Kind.CALLBACK }.sortedByDescending { it.at }
        return rem + cb
    }

    fun list(ctx: Context, now: Long = System.currentTimeMillis()): List<FollowUp> =
        build(CallInbox.cached(ctx), { CallNotes.Reminders.at(ctx, it) }, done(ctx), now)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun done(ctx: Context): Set<String> = prefs(ctx).getStringSet("done", emptySet()).orEmpty()

    fun markDone(ctx: Context, f: FollowUp) {
        val keys = (done(ctx) + f.item.key).toList().takeLast(300).toSet()
        prefs(ctx).edit().putStringSet("done", keys).apply()
        if (f.kind == FollowUp.Kind.REMINDER) CallNotes.cancelReminder(ctx, f.item)
    }
}
