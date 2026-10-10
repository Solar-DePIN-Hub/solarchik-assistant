package net.solardepin.solarchik.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.solardepin.solarchik.R
import net.solardepin.solarchik.notify.Notes
import net.solardepin.solarchik.ui.CallsActivity
import java.util.concurrent.TimeUnit

/**
 * New call notes as system notifications (0.21.9). How fresh they are, honestly:
 *  - app open: polled on resume and every 60 s ([MainActivity]); the Calls screen polls every 15 s;
 *  - right after "Call the secretary" in the app: polled every 10 s for 4 minutes while the app is open;
 *  - app in the background: WorkManager every 15 minutes (Android's floor; Doze can delay it further).
 * There is no push server.
 */
object CallNotes {
    const val CHANNEL = "solarchik-calls"
    private const val POLL = "solarchik-calls-poll"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.calls_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<CallPollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(POLL, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Fetches every watched id and announces finished notes not announced yet. Blocking (call off the main thread). */
    fun check(ctx: Context): List<CallItem> {
        val items = CallInbox.refresh(ctx) ?: return emptyList()
        val due = CallInbox.toAnnounce(items, CallInbox.announced(ctx), CallInbox.announceSince(ctx))
        if (due.isEmpty()) return emptyList()
        due.take(3).forEach { post(ctx, it) }
        CallInbox.addAnnounced(ctx, due.map { it.key })
        return due
    }

    fun title(ctx: Context, item: CallItem): String = when {
        item.missed -> ctx.getString(R.string.calls_note_missed_title, item.who.ifBlank { ctx.getString(R.string.calls_unknown) })
        else -> ctx.getString(R.string.calls_note_title, item.who.ifBlank { ctx.getString(R.string.calls_unknown) })
    }

    fun body(ctx: Context, item: CallItem): String = CallText.summary(ctx, item)

    private fun openIntent(ctx: Context, item: CallItem, req: Int): PendingIntent {
        val open = Intent(ctx, CallsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallsActivity.EXTRA_KEY, item.key)
        }
        return PendingIntent.getActivity(ctx, req, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun post(ctx: Context, item: CallItem, reminder: Boolean = false): Boolean {
        if (!Notes.allowed(ctx)) return false
        createChannel(ctx)
        val id = 4000 + (item.key.hashCode() and 0x3FFF) + if (reminder) 20000 else 0
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(if (reminder) ctx.getString(R.string.calls_remind_title, item.who.ifBlank { ctx.getString(R.string.calls_unknown) }) else title(ctx, item))
            .setContentText(body(ctx, item))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(ctx, item)))
            .setContentIntent(openIntent(ctx, item, id))
            .setAutoCancel(true)
            .setCategory(if (reminder) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF7A4DFF.toInt())
        if (item.dialNumber.isNotBlank()) {
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(item.dialNumber))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.addAction(R.drawable.ic_call, ctx.getString(R.string.calls_call_back), PendingIntent.getActivity(ctx, id + 1, dial, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** "Remind me": a local notification at [atMillis] (WorkManager one-off; survives reboots). */
    fun remind(ctx: Context, item: CallItem, atMillis: Long) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<CallReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("item" to CallInbox.toJson(item).toString()))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("call-remind-" + item.key.hashCode(), ExistingWorkPolicy.REPLACE, req)
        Reminders.set(ctx, item.key, atMillis)
    }

    fun cancelReminder(ctx: Context, item: CallItem) {
        WorkManager.getInstance(ctx).cancelUniqueWork("call-remind-" + item.key.hashCode())
        Reminders.set(ctx, item.key, 0)
    }

    /** When each call's reminder fires (shown on the call screen). */
    object Reminders {
        private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("solarchik.calls.remind", Context.MODE_PRIVATE)
        fun at(ctx: Context, key: String): Long = p(ctx).getLong(key, 0).takeIf { it > System.currentTimeMillis() } ?: 0
        fun set(ctx: Context, key: String, at: Long) { p(ctx).edit().apply { if (at > 0) putLong(key, at) else remove(key) }.apply() }
    }
}

class CallPollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { CallNotes.check(applicationContext) }
        return Result.success()
    }
}

class CallReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val raw = inputData.getString("item") ?: return Result.success()
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return Result.success()
        val item = CallInbox.item(o.optString("owner"), o) ?: return Result.success()
        CallNotes.post(applicationContext, item, reminder = true)
        CallNotes.Reminders.set(applicationContext, item.key, 0)
        return Result.success()
    }
}

/** Display texts for a call, shared by the list, the detail and the notifications. */
object CallText {
    val KYIV: java.util.TimeZone = java.util.TimeZone.getTimeZone("Europe/Kyiv").takeIf { it.id != "GMT" } ?: java.util.TimeZone.getTimeZone("Europe/Kiev")

    /** "3 Oct, 01:10" in Kyiv time (the secretary line is Ukrainian; the screen says so). */
    fun time(ms: Long, locale: java.util.Locale = net.solardepin.solarchik.core.AppLocale.ui()): String {
        if (ms <= 0) return "—"
        // 1.1.7: English "Oct 10, 1:32 PM", Ukrainian "10 жовт., 13:32", always in the app's UI language
        val f = if (net.solardepin.solarchik.core.AppLocale.isUk(locale)) java.text.SimpleDateFormat("d MMM, HH:mm", locale)
            else java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
        f.timeZone = KYIV
        return f.format(java.util.Date(ms))
    }

    /**
     * 1.1.7: a time Sol can say out loud: "today at 1:32 PM", "yesterday at 9:05 AM", "on October 8 at 6:00 PM"
     * (Ukrainian: "сьогодні о 13:32", "учора о 09:05", "8 жовтня о 18:00"). Kyiv time, like the call list.
     */
    fun spoken(ms: Long, now: Long = System.currentTimeMillis(), locale: java.util.Locale = net.solardepin.solarchik.core.AppLocale.ui()): String {
        if (ms <= 0) return ""
        val uk = net.solardepin.solarchik.core.AppLocale.isUk(locale)
        val zone = KYIV.toZoneId()
        val d = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = if (uk) java.time.format.DateTimeFormatter.ofPattern("HH:mm", locale).format(d)
            else java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(d)
        val day = when (d.toLocalDate()) {
            today -> if (uk) "сьогодні" else "today"
            today.minusDays(1) -> if (uk) "учора" else "yesterday"
            else -> if (uk) java.time.format.DateTimeFormatter.ofPattern("d MMMM", locale).format(d)
                else "on " + java.time.format.DateTimeFormatter.ofPattern("MMMM d", java.util.Locale.US).format(d)
        }
        return if (uk) "$day о $clock" else "$day at $clock"
    }

    fun summary(ctx: Context, item: CallItem): String = Phones.show(ctx, rawSummary(ctx, item))

    /** The caller as shown: their name, else their number (masked unless they are in the user's Circle). */
    fun who(ctx: Context, item: CallItem): String = Phones.show(ctx, item.who)

    private fun rawSummary(ctx: Context, item: CallItem): String = when {
        item.blocked -> ctx.getString(R.string.calls_blocked_line)
        // 1.1.3: the worker's daily AI call minutes cap (the caller heard a short goodbye, nothing was charged)
        item.reason == "CALL_MINUTES_GLOBAL" || item.reason == "CALL_MINUTES_ACCOUNT" -> ctx.getString(R.string.calls_missed_minutes_cap)
        item.status == CallInbox.NEED_TOPUP && item.reason == "TRIAL_CALLER_CAP" -> ctx.getString(R.string.calls_missed_caller_cap)
        item.status == CallInbox.NEED_TOPUP && item.reason == "TRIAL_DAILY_CAP" -> ctx.getString(R.string.calls_missed_daily_cap)
        item.status == CallInbox.NEED_TOPUP -> ctx.getString(R.string.calls_missed_topup)
        item.missed -> ctx.getString(R.string.calls_missed_line)
        item.status == CallInbox.PENDING -> ctx.getString(R.string.calls_pending_line)
        item.intent.isNotBlank() -> item.intent + if (item.notes.isNotBlank()) " · " + item.notes else ""
        item.text == AUTO_EMPTY -> ctx.getString(R.string.calls_no_details)
        else -> item.text.removePrefix(AUTO_PREFIX).trim()
    }

    /** The worker's English fallbacks, shown translated. */
    const val AUTO_EMPTY = "Call answered; the caller left no details."
    const val AUTO_PREFIX = "Call answered (from the call transcript):"

    fun status(ctx: Context, item: CallItem): String = ctx.getString(
        when {
            item.blocked -> R.string.calls_status_blocked
            item.missed -> R.string.calls_status_missed
            item.status == CallInbox.PENDING -> R.string.calls_status_pending
            else -> R.string.calls_status_answered
        },
    )

    /** Language of the call: the secretary setting saved with the call, else guessed from the words. */
    fun language(ctx: Context, item: CallItem, words: String = ""): String {
        if (!item.answered) return ""
        val code = when {
            item.lang == "uk" || item.lang == "en" -> item.lang
            Regex("[А-Яа-яІіЇїЄєҐґ]").containsMatchIn(item.text + " " + item.intent + " " + words) -> "uk"
            (item.text + words).any { it.isLetter() } && item.lang != "auto" -> "en"
            else -> ""
        }
        return when (code) {
            "uk" -> ctx.getString(R.string.sec_lang_uk)
            "en" -> ctx.getString(R.string.sec_lang_en)
            else -> ""
        }
    }
}
