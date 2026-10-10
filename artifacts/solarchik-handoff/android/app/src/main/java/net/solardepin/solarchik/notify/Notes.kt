package net.solardepin.solarchik.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.engine.DeskStore
import net.solardepin.solarchik.core.StreakRules
import net.solardepin.solarchik.game.GameSave
import java.util.concurrent.TimeUnit

/** Local reminders. No server, no push: WorkManager checks once an hour and posts what is due. */
object Notes {
    const val CHANNEL = "solarchik"
    private const val SENT = "notes.sent"
    private const val SENT_LIST = "notes.sent.v2"
    private const val SENT_KEEP = 120
    private const val WORK = "solarchik-notes"

    fun allowed(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= 33

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.note_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<NoteWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Was there anything worth a report today: a run, a signed day, or a closed position. */
    fun activityToday(ctx: Context, save: GameSave): Boolean {
        val today = save.today()
        if (save.todayDistance() > 0 || save.signedToday()) return true
        return DeskStore(ctx).read().log.any { StreakRules.dayKey(it.at) == today }
    }

    /**
     * Sent keys in the order they were sent. (The 0.20.1 string set had no order, so trimming it
     * could drop today's key and repeat a note an hour later.)
     */
    fun sentKeys(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences("solarchik-notes", Context.MODE_PRIVATE)
        val list = prefs.getString(SENT_LIST, null)?.let { raw ->
            runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
        }
        return list ?: prefs.getStringSet(SENT, emptySet()).orEmpty().sorted()
    }

    /** Posts every due note once. Returns what was posted (for tests and logs). */
    fun check(ctx: Context, save: GameSave = GameSave(ctx)): List<DueNote> {
        val prefs = ctx.getSharedPreferences("solarchik-notes", Context.MODE_PRIVATE)
        val sent = sentKeys(ctx)
        val due = NotePlanner.due(save.streakState(), save.now(), sent.toSet(), { save.noteOn(it.toggle) }, activityToday(ctx, save))
        if (due.isEmpty() || !allowed(ctx)) return emptyList()
        val posted = due.filter { post(ctx, it.kind, bodyFor(ctx, it.kind, save.now())) }
        val keep = (sent + posted.map { it.key }).distinct().takeLast(SENT_KEEP)
        prefs.edit().putString(SENT_LIST, org.json.JSONArray(keep).toString()).remove(SENT).apply()
        return posted
    }

    // Permission is checked right here (allowed()), and a revoke race is caught below.
    @android.annotation.SuppressLint("MissingPermission")
    /** The report says when the UTC game day ends on this phone's clock, so its timing is clear. */
    fun bodyFor(ctx: Context, kind: NoteKind, now: Long): String = when (kind) {
        NoteKind.STREAK -> ctx.getString(R.string.note_streak_body)
        NoteKind.REWARD -> ctx.getString(R.string.note_reward_body)
        NoteKind.WINDOW -> ctx.getString(R.string.note_window_body)
        NoteKind.REPORT -> ctx.getString(
            R.string.note_report_body,
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, net.solardepin.solarchik.core.AppLocale.ui()).format(java.util.Date(NotePlanner.reportDayEnd(now))),
        )
        NoteKind.DESK -> ""
    }

    internal fun post(ctx: Context, kind: NoteKind, text: String): Boolean {
        if (!allowed(ctx)) return false
        val (title, tab) = when (kind) {
            NoteKind.STREAK -> R.string.note_streak_title to MainActivity.Tab.RUN
            NoteKind.REWARD -> R.string.note_reward_title to MainActivity.Tab.SHIFT
            NoteKind.WINDOW -> R.string.note_window_title to MainActivity.Tab.SHIFT
            NoteKind.REPORT -> R.string.note_report_title to MainActivity.Tab.SOL
            NoteKind.DESK -> R.string.note_desk_title to MainActivity.Tab.AGENTS
        }
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TAB, tab.name)
        }
        val pi = PendingIntent.getActivity(ctx, kind.ordinal, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(ctx.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(0xFFF5C542.toInt())
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(100 + kind.ordinal, n)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}

/** 1.2.5: the morning stack note, once a day from 08:00, only when cards from calls are waiting. */
object StackNote {
    @android.annotation.SuppressLint("MissingPermission")
    fun check(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val n = net.solardepin.solarchik.stack.MorningStack.notificationDue(ctx, now)
        if (n <= 0 || !Notes.allowed(ctx)) return false
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.TODAY.name)
        }
        val pi = PendingIntent.getActivity(ctx, 140, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = ctx.resources.getQuantityString(R.plurals.stack_note_body, n, n)
        val note = NotificationCompat.Builder(ctx, Notes.CHANNEL).setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(ctx.getString(R.string.stack_note_title)).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).setColor(0xFFF5C542.toInt()).build()
        return try {
            NotificationManagerCompat.from(ctx).notify(140, note)
            net.solardepin.solarchik.stack.MorningStack.markNotified(ctx, now)
            true
        } catch (_: SecurityException) { false }
    }
}

class NoteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { Notes.check(applicationContext) }
        runCatching { StackNote.check(applicationContext) }
        runCatching { DeskNotes.flush(applicationContext) }
        return Result.success()
    }
}
