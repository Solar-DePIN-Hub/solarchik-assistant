package net.solardepin.solarchik.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 1.2.12: lightweight crash reporting without PII. On an uncaught exception we save the exception CLASS names and stack
 * frames (never messages, which can hold addresses or numbers) plus app version, Android SDK and device model to a file;
 * the next start POSTs it to the worker's /crash in the background and deletes it. Then the default handler runs.
 */
object CrashReport {
    private const val FILE = "pending-crash.json"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(app.filesDir, FILE).writeText(build(e).toString()) }
            prev?.uncaughtException(t, e)
        }
        Thread { runCatching { sendPending(app) } }.apply { isDaemon = true }.start()
    }

    fun build(e: Throwable): JSONObject {
        val sb = StringBuilder()
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth < 4) {
            sb.append(if (depth == 0) "" else "Caused by: ").append(c.javaClass.name).append('\n')
            c.stackTrace.take(25).forEach { sb.append("  at ").append(it.toString()).append('\n') }
            c = c.cause; depth++
        }
        return JSONObject().put("type", e.javaClass.name).put("stack", sb.toString().take(6000))
            .put("v", net.solardepin.solarchik.BuildConfig.VERSION_NAME).put("sdk", android.os.Build.VERSION.SDK_INT.toString())
            .put("model", android.os.Build.MODEL.orEmpty())
    }

    private fun sendPending(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        val (code, _) = net.solardepin.solarchik.season.SeasonRules.httpPost(net.solardepin.solarchik.screen.ScreenApi.BASE + "/crash", f.readText())
        if (code in 200..499) f.delete()
    }
}
