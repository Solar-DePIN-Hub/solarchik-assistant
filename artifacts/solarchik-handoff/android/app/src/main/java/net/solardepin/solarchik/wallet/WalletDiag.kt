package net.solardepin.solarchik.wallet

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import net.solardepin.solarchik.BuildConfig

/**
 * 1.2.0: wallet diagnostics. Every Mobile Wallet Adapter step of a connect (wallet apps found, the association
 * intent, the activity result, WebSocket session, authorize) is written here with a timestamp, so one run on
 * the tablet shows where the connect stops. Settings → Wallet diagnostics shows, copies and shares the text.
 * Nothing secret is stored: the association key is cut, auth tokens and full addresses are never logged.
 */
object WalletDiag {
    private const val FILE = "wallet-diag.log"
    private const val MAX_LINES = 400
    private val lock = Any()
    @Volatile private var appCtx: Context? = null
    private val t0 = System.currentTimeMillis()

    /** Known MWA wallets (package → name). Queried by name in the manifest's <queries>. */
    val WALLETS = linkedMapOf(
        "app.phantom" to "Phantom",
        "com.solflare.mobile" to "Solflare",
        "com.solanamobile.seedvaultimpl" to "Seed Vault",
        "com.backpack.android" to "Backpack",
    )

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    private fun file(): java.io.File? = appCtx?.let { java.io.File(it.filesDir, FILE) }

    fun log(step: String, detail: String = "") {
        val line = stamp() + " " + step + (if (detail.isNotBlank()) ": " + detail.replace('\n', ' ').take(600) else "")
        runCatching { android.util.Log.i("WalletDiag", line) }
        val f = file() ?: return
        synchronized(lock) {
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > 120_000) {
                    val keep = f.readLines().takeLast(MAX_LINES)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    fun error(step: String, t: Throwable?) = log(step, chain(t))

    /** "IOException: x <- ConnectException: y" (the whole cause chain, one line). */
    fun chain(t: Throwable?): String {
        val parts = mutableListOf<String>()
        var c = t
        var n = 0
        while (c != null && n < 6) { parts += c.javaClass.simpleName + (c.message?.let { ": " + it.take(200) } ?: ""); c = c.cause; n++ }
        return parts.joinToString(" <- ").ifBlank { "no exception" }
    }

    fun text(): String = synchronized(lock) { runCatching { file()?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty() }

    fun clear() = synchronized(lock) { runCatching { file()?.delete() } }

    private fun stamp(): String = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    /** Device, app and wallet apps: the first lines of every test run. */
    fun header(ctx: Context): String {
        val pm = ctx.packageManager
        val sb = StringBuilder()
        sb.append("Solarchik ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
            .append(" · ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
        for ((pkg, name) in WALLETS) {
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            if (info != null) sb.append(" · ").append(name).append(" ").append(info.versionName ?: "?")
        }
        return sb.toString()
    }

    /** Activities that answer the association intent, with the launch mode (a singleTask/singleInstance wallet activity answers RESULT_CANCELED at once). */
    fun handlers(ctx: Context, intent: Intent): List<String> = runCatching {
        ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { r ->
            val a = r.activityInfo
            a.packageName + "/" + a.name.substringAfterLast('.') + " launchMode=" + launchMode(a.launchMode) +
                " affinity=" + (a.taskAffinity ?: "-") + " exported=" + a.exported
        }
    }.getOrDefault(emptyList())

    private fun launchMode(m: Int): String = when (m) {
        ActivityInfo.LAUNCH_MULTIPLE -> "standard"
        ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop"
        ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask"
        ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance"
        else -> m.toString()
    }

    /** The association URI with the key shortened (the key is single-use anyway; this keeps the log readable). */
    fun safeUri(u: Uri?): String {
        val s = u?.toString() ?: return "null"
        return s.replace(Regex("(association=)([^&]{8})[^&]*"), "$1$2…")
    }

    /** This app's own MWA log lines from logcat (an app may always read its own process's log). */
    fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=" + android.os.Process.myPid()).redirectErrorStream(true).start()
        val tags = listOf("LocalAssociationScenario", "MobileWalletAdapter", "WebSocket", "JsonRpc20", "SolanaWallet", "MwaDirect", "ActivityTaskManager")
        val all = compactLogcat(p.inputStream.bufferedReader().readLines().filter { l -> tags.any { l.contains(it) } })
        p.destroy()
        // 1.2.3: the first lines matter most (when did the first dial happen?); keep the head and the tail
        val out = if (all.size <= 200) all else all.take(80) + listOf("… " + (all.size - 200) + " lines skipped …") + all.takeLast(120)
        out.joinToString("\n")
    }.getOrDefault("")

    private val FRAME = Regex("^\\s*(at |Caused by:|\\.\\.\\. \\d+ more|Suppressed:)")
    private val STAMP = Regex("^\\d\\d-\\d\\d (\\d\\d:\\d\\d:\\d\\d\\.\\d{3}) ([VDIWEF])/([^(]+)\\(\\s*\\d+\\):\\s?(.*)$")
    private val NOISE = Regex("(?i)retrying in|connect attempt failed|^connect$|failed establishing a websocket connection")

    /**
     * 1.2.4: logcat in short: no stack frames, one line per failed WebSocket dial ("ws dial failed: ECONNREFUSED", each with its time),
     * and repeats of any other identical line folded into "(×N)".
     */
    fun compactLogcat(lines: List<String>): List<String> {
        val reason = Regex("ECONNREFUSED|SocketTimeoutException|ECONNRESET|ENETUNREACH|EACCES|EPERM")
        val out = ArrayList<String>()
        var last = ""
        var repeat = 0
        var pendTime = ""
        var pendReason: String? = null
        fun emit(time: String, body: String, fold: Boolean = true) {
            if (fold && body == last) { repeat++; return }
            if (repeat > 1) out[out.size - 1] = out.last() + " (×$repeat)"
            out += (if (time.isNotBlank()) "$time " else "") + body
            last = body
            repeat = 1
        }
        fun flushDial() { pendReason?.let { emit(pendTime, "ws dial failed" + (if (it.isNotBlank()) ": $it" else ""), fold = false) }; pendReason = null }
        for (raw in lines) {
            val m = STAMP.find(raw)
            val msg = (m?.groupValues?.get(4) ?: raw).trim()
            val time = m?.groupValues?.get(1).orEmpty()
            if (FRAME.containsMatchIn(msg)) {
                if (pendReason == "") reason.find(msg)?.let { pendReason = it.value }
                continue
            }
            if (msg.contains("Failed to connect", true) || reason.containsMatchIn(msg)) {
                flushDial()
                pendTime = time
                pendReason = reason.find(msg)?.value.orEmpty()
                continue
            }
            if (NOISE.containsMatchIn(msg)) continue
            flushDial()
            val tag = m?.groupValues?.get(3)?.trim().orEmpty()
            emit(time, (if (tag.isNotBlank()) "$tag: " else "") + msg.take(160))
        }
        flushDial()
        if (repeat > 1) out[out.size - 1] = out.last() + " (×$repeat)"
        return out
    }

    fun shortAddr(a: String): String = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a

    fun sinceStart(): Long = System.currentTimeMillis() - t0
}
