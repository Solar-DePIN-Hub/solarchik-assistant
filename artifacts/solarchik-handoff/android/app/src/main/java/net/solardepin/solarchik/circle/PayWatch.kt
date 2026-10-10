package net.solardepin.solarchik.circle

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.notify.Notes
import net.solardepin.solarchik.solana.Rpc
import java.util.concurrent.TimeUnit

/**
 * 1.2.7: watches open Solana Pay requests (read-only RPC: getSignaturesForAddress(reference) + getTransaction).
 * Runs on resume, while the request sheet is open, and from a 15-minute WorkManager job while anything is open.
 */
object PayWatch {
    const val WORK = "solarchik-payreq"
    /** Requests older than this stop being polled (they stay in the list, Request again makes a fresh one). */
    const val MAX_AGE_MS = 30L * 24 * 3600_000L
    @Volatile var rpcForTest: Rpc? = null
    @Volatile var lastMismatch: String = ""

    private fun rpc(): Rpc = rpcForTest ?: Rpc(SolarchikConfig.RPC_MAINNET)

    /** Checks every open request once; returns the ones that turned Paid (already stored, notified if [notify]). */
    suspend fun checkAll(ctx: Context, notify: Boolean = true, now: Long = System.currentTimeMillis()): List<PayRequest> {
        val store = PayRequestStore(ctx)
        val paid = mutableListOf<PayRequest>()
        for (r in store.open().filter { now - it.at < MAX_AGE_MS }) {
            when (val c = runCatching { SolanaPay.check(rpc(), r) }.getOrElse { SolanaPay.Check.NotYet }) {
                is SolanaPay.Check.Paid -> {
                    store.markPaid(ctx, r.id, c.signature)
                    paid += r.copy(status = PayRequest.PAID, signature = c.signature)
                    if (notify) notifyPaid(ctx, r)
                }
                is SolanaPay.Check.Mismatch -> lastMismatch = "${r.id}: ${c.why}"
                else -> Unit
            }
        }
        if (store.open().none { now - it.at < MAX_AGE_MS }) runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }
        return paid
    }

    fun schedule(ctx: Context) = runCatching {
        val req = PeriodicWorkRequestBuilder<PayWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun notifyPaid(ctx: Context, r: PayRequest) {
        runCatching { Notes.createChannel(ctx) }
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.CIRCLE.name)
        }
        val pi = PendingIntent.getActivity(ctx, 7300, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = ctx.getString(R.string.payreq_paid_note, r.who, Circle.amount(r.amount), r.token)
        val n = NotificationCompat.Builder(ctx, Notes.CHANNEL).setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(ctx.getString(R.string.payreq_paid_title)).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).setColor(0xFFF5C542.toInt()).build()
        try { NotificationManagerCompat.from(ctx).notify(7300 + (r.id.hashCode() and 0xff), n) } catch (_: SecurityException) {}
    }
}

class PayWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { PayWatch.checkAll(applicationContext); return Result.success() }
}
