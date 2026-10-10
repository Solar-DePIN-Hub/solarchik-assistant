package net.solardepin.solarchik.wallet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import net.solardepin.solarchik.R

/**
 * 1.2.3: keeps the app's process running while the wallet is in front during a connect / sign.
 *
 * Android 15/16 (and Samsung) may freeze an app as soon as another app covers it; the MWA WebSocket client then
 * cannot dial the wallet's local server until the user comes back, and by then the wallet has given up (Vadym's
 * Android 16 log: first dial 16 s after the intent, right when Phantom returned RESULT_CANCELED). A short
 * foreground service (type shortService, API 34+, max ~3 min, no extra permission prompt) keeps the process out of
 * the freezer for that window. Started from the foreground just before the wallet intent; stopped when done.
 * Below API 34 it is not used. Failure to start it is logged and the connect goes on as before.
 */
class MwaKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 34) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wallet_keepalive_channel), NotificationManager.IMPORTANCE_LOW)) }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(getString(R.string.wallet_keepalive_title))
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            WalletDiag.log("keep-alive", "foreground service on (shortService)")
        } catch (t: Throwable) {
            WalletDiag.error("keep-alive failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        WalletDiag.log("keep-alive", "system timeout, stopping")
        stopSelf()
    }

    override fun onDestroy() {
        WalletDiag.log("keep-alive", "off")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "wallet-connect"
        private const val NOTIF_ID = 7301

        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT < 34) return
            runCatching { ctx.startForegroundService(Intent(ctx, MwaKeepAliveService::class.java)) }
                .onFailure { WalletDiag.error("keep-alive not started", it) }
        }

        fun stop(ctx: Context) {
            if (Build.VERSION.SDK_INT < 34) return
            runCatching { ctx.stopService(Intent(ctx, MwaKeepAliveService::class.java)) }
        }
    }
}

/**
 * 1.2.3: a background thread that ticks every 500 ms during the connect window. A gap much longer than the tick
 * means the process was frozen (not just in the background): that is logged with its length, so one tablet run
 * tells "frozen" apart from "the wallet never opened its server".
 */
class FreezeProbe {
    @Volatile private var running = true
    private var maxGap = 0L
    private val t = Thread {
        var last = SystemClock.elapsedRealtime()
        while (running) {
            try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { break }
            val now = SystemClock.elapsedRealtime()
            val gap = now - last - TICK_MS
            if (gap > maxGap) maxGap = gap
            if (gap > FROZEN_MS) WalletDiag.log("freeze", "app process stood still for " + gap + " ms")
            last = now
        }
    }.apply { isDaemon = true; name = "mwa-freeze-probe" }

    fun start(): FreezeProbe { t.start(); return this }
    fun stop(): Long { running = false; t.interrupt(); return maxGap }

    companion object {
        const val TICK_MS = 500L
        const val FROZEN_MS = 1500L
    }
}
