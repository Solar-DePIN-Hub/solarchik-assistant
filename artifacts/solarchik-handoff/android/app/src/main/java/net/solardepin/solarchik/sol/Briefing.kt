package net.solardepin.solarchik.sol

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.WatchAlert
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.FollowUp
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.season.SeasonItem
import net.solardepin.solarchik.season.SeasonPlan
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 1.1.0 morning voice briefing. At a user-set time (default 08:30) a notification; tapping it, opening Today
 * while it is pending, or "Play briefing" on Today plays Sol's spoken summary: calls since yesterday (who wants
 * what, callbacks), follow-ups due, actions from calls waiting for confirmation, the real wallet change since the
 * last briefing, Watcher alerts and the Season plan. The facts come only from this phone; the worker (assistant
 * persona) turns them into speech text, with a local template when offline. Nothing is signed or sent.
 */
data class BriefingPolicy(val enabled: Boolean = true, val hour: Int = 8, val minute: Int = 30) {
    fun clamped() = copy(hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59))
    val label: String get() = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
}

data class WalletSnap(val at: Long, val sol: Double?, val skr: Double?)

class BriefingStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy() = BriefingPolicy(p.getBoolean("enabled", true), p.getInt("hour", 8), p.getInt("minute", 30)).clamped()
    fun setPolicy(v: BriefingPolicy) { val c = v.clamped(); p.edit().putBoolean("enabled", c.enabled).putInt("hour", c.hour).putInt("minute", c.minute).apply() }

    /** A posted briefing not played yet: opening Today plays it once. */
    var pendingDay: String
        get() = p.getString("pending", "").orEmpty()
        set(v) { p.edit().putString("pending", v).apply() }
    var postedDay: String
        get() = p.getString("posted", "").orEmpty()
        set(v) { p.edit().putString("posted", v).apply() }
    var playedAt: Long
        get() = p.getLong("playedAt", 0L)
        set(v) { p.edit().putLong("playedAt", v).apply() }
    var lastText: String
        get() = p.getString("text", "").orEmpty()
        set(v) { p.edit().putString("text", v).apply() }

    /** Balances at the last played briefing: the wallet change is measured against these. */
    fun snap(): WalletSnap? = p.getLong("snapAt", 0L).takeIf { it > 0 }?.let {
        WalletSnap(it, p.getString("snapSol", null)?.toDoubleOrNull(), p.getString("snapSkr", null)?.toDoubleOrNull())
    }
    fun setSnap(s: WalletSnap) { p.edit().putLong("snapAt", s.at).putString("snapSol", s.sol?.toString()).putString("snapSkr", s.skr?.toString()).apply() }

    companion object { const val PREFS = "solarchik.briefing" }
}

object Briefing {
    const val WORK = "solarchik-briefing"
    const val NOTE_ID = 410

    /** Pure: the next [pol] time strictly after [now]. */
    fun nextAt(pol: BriefingPolicy, now: Long, zone: ZoneId): Long {
        val c = pol.clamped()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val t = today.atTime(c.hour, c.minute).atZone(zone).toInstant().toEpochMilli()
        return if (t > now) t else today.plusDays(1).atTime(c.hour, c.minute).atZone(zone).toInstant().toEpochMilli()
    }

    /** Calls since yesterday 00:00 (yesterday's and overnight calls), never blocked ones. */
    fun since(now: Long, zone: ZoneId): Long = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Pure: the facts the worker may speak about, all from local state. */
    fun facts(
        calls: List<CallItem>,
        followUps: List<FollowUp>,
        actions: List<String>,
        alerts: List<WatchAlert>,
        wallet: AssistantContext.Wallet,
        snap: WalletSnap?,
        season: SeasonPlan,
        now: Long,
        zone: ZoneId,
    ): JSONObject {
        val tz = TimeZone.getTimeZone(zone)
        val clock = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
        val day = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).apply { timeZone = tz }
        val from = since(now, zone)
        val recent = calls.filter { !it.blocked && it.at in from..now }.sortedBy { it.at }.takeLast(6)
        val o = JSONObject().put("now", day.format(Date(now)))
        o.put("calls", JSONArray().apply {
            recent.forEach { c ->
                put(JSONObject().put("who", c.who.ifBlank { "unknown caller" }).put("time", clock.format(Date(c.at)))
                    .put("state", if (c.missed) "missed" else "answered")
                    .put("want", c.intent.ifBlank { c.text }.replace(Regex("\\s+"), " ").trim().take(140))
                    .put("callback", c.callback.takeIf { it.isNotBlank() && it != "unknown" }.orEmpty()))
            }
        })
        o.put("followUps", JSONArray().apply {
            followUps.take(5).forEach { f ->
                val who = f.item.who.ifBlank { "unknown caller" }
                put(if (f.kind == FollowUp.Kind.REMINDER) "reminder: call $who at ${clock.format(Date(f.at))}" else "call back $who" + (f.item.callback.takeIf { it.isNotBlank() && it != "unknown" }?.let { " at $it" } ?: ""))
            }
        })
        o.put("actions", JSONArray(actions.take(4)))
        o.put("alerts", JSONArray(alerts.take(4).map { it.line() }))
        val w = JSONObject().put("connected", wallet.connected).put("network", if (wallet.mainnet) "mainnet" else "devnet")
        if (wallet.connected) {
            wallet.sol?.let { w.put("sol", round(it, 4)) }
            wallet.skr?.let { w.put("skr", round(it, 2)) }
            if (snap != null) {
                if (wallet.sol != null && snap.sol != null) w.put("solDelta", round(wallet.sol - snap.sol, 4))
                if (wallet.skr != null && snap.skr != null) w.put("skrDelta", round(wallet.skr - snap.skr, 2))
                w.put("since", day.format(Date(snap.at)))
            }
        }
        o.put("wallet", w)
        val left = buildList {
            if (!season.done(SeasonItem.EXPLORE)) add("open ${season.suggestion.name}")
            if (!season.done(SeasonItem.ONCHAIN)) add(if (season.clockedToday) "sign today's check-in" else "do the daily check-in")
        }
        o.put("season", JSONObject().put("done", season.doneCount).put("total", season.total).put("left", JSONArray(left)).put("streak", season.streak))
        return o
    }

    private fun round(v: Double, n: Int): Double = java.math.BigDecimal(v).setScale(n, java.math.RoundingMode.HALF_UP).toDouble()

    /** Offline fallback from the same facts (also what tests read). */
    fun localText(f: JSONObject, ctx: Context): String {
        val parts = ArrayList<String>()
        parts += ctx.getString(R.string.br_hello)
        val calls = f.optJSONArray("calls") ?: JSONArray()
        if (calls.length() == 0) parts += ctx.getString(R.string.br_no_calls)
        else {
            parts += ctx.resources.getQuantityString(R.plurals.br_calls, calls.length(), calls.length())
            for (i in 0 until minOf(3, calls.length())) {
                val c = calls.getJSONObject(i)
                val want = c.optString("want").trimEnd('.')
                parts += ctx.getString(R.string.br_call, c.optString("who"), c.optString("time"), want) +
                    (c.optString("callback").takeIf { it.isNotBlank() }?.let { " " + ctx.getString(R.string.br_callback, it) } ?: "")
            }
        }
        val fu = f.optJSONArray("followUps")?.length() ?: 0
        if (fu > 0) parts += ctx.resources.getQuantityString(R.plurals.br_followups, fu, fu)
        val acts = f.optJSONArray("actions")?.length() ?: 0
        if (acts > 0) parts += ctx.resources.getQuantityString(R.plurals.br_actions, acts, acts)
        val w = f.optJSONObject("wallet")
        if (w != null && w.optBoolean("connected")) {
            val d = w.optDouble("solDelta", Double.NaN)
            parts += when {
                d.isNaN() -> ctx.getString(R.string.br_wallet_first, num(w.optDouble("sol", 0.0)))
                abs(d) < 0.00005 -> ctx.getString(R.string.br_wallet_same, num(w.optDouble("sol", 0.0)))
                d > 0 -> ctx.getString(R.string.br_wallet_up, num(d), num(w.optDouble("sol", 0.0)))
                else -> ctx.getString(R.string.br_wallet_down, num(-d), num(w.optDouble("sol", 0.0)))
            }
        }
        val al = f.optJSONArray("alerts")
        if (al != null && al.length() > 0) parts += ctx.getString(R.string.br_alerts, al.getString(0))
        val s = f.optJSONObject("season")
        if (s != null) {
            val left = s.optJSONArray("left")?.length() ?: 0
            parts += if (left == 0) ctx.getString(R.string.br_season_done) else ctx.getString(R.string.br_season_left, s.optInt("done"), s.optInt("total"))
        }
        parts += ctx.getString(R.string.br_bye)
        return parts.joinToString(" ")
    }

    private fun num(v: Double): String = java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** POST /sol/briefing; null when the worker is unavailable (the caller falls back to [localText]). */
    fun fetch(facts: JSONObject, lang: String, post: (String, String) -> Pair<Int, String> = ::httpPost): String? = runCatching {
        val (code, body) = post(ScreenApi.BASE + "/sol/briefing", JSONObject().put("lang", lang).put("app", "assistant").put("facts", facts).toString())
        if (code != 200) return null
        JSONObject(body).takeIf { it.optBoolean("ok") }?.optString("text")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun httpPost(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Content-Type", "application/json")
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally { c.disconnect() }
    }

    /** (Re)schedules the one-off work for the next briefing time; cancels it when off. */
    fun schedule(ctx: Context, now: Long = System.currentTimeMillis()) {
        val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull() ?: return
        val pol = BriefingStore(ctx).policy()
        if (!pol.enabled) { wm.cancelUniqueWork(WORK); return }
        val delay = (nextAt(pol, now, ZoneId.systemDefault()) - now).coerceAtLeast(60_000L)
        wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BriefingWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
    }

    /** The scheduled moment: post the notification once a day and mark the briefing pending. */
    fun fire(ctx: Context, now: Long = System.currentTimeMillis(), post: (Int, String, String, Intent) -> Boolean = { id, t, b, i -> net.solardepin.solarchik.autopilot.AutoRunner.notify(ctx, id, t, b, i) }): Boolean {
        val st = BriefingStore(ctx)
        if (!st.policy().enabled) return false
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (st.postedDay == day) return false
        st.postedDay = day
        st.pendingDay = day
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_BRIEFING, true)
        }
        return post(NOTE_ID, ctx.getString(R.string.br_note_title), ctx.getString(R.string.br_note_body), open)
    }

    fun today(zone: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zone).toString()
}

class BriefingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { Briefing.fire(applicationContext) }
        Briefing.schedule(applicationContext)
        return Result.success()
    }
}

/** 1.1.0: what Sol's chat context and the briefing add about the three agents and call actions (plain English). */
object AssistantExtras {
    fun lines(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val out = ArrayList<String>()
        val on = buildList {
            if (net.solardepin.solarchik.autopilot.AutopilotStore(ctx).policy().enabled) add("Season autopilot")
            if (net.solardepin.solarchik.delegate.DelegateStore(ctx).policy().enabled) add("delegated limit (experimental)")
            if (net.solardepin.solarchik.agents.SaverStore(ctx).policy().enabled) add("Saver")
            if (net.solardepin.solarchik.agents.WatcherStore(ctx).policy().enabled) add("Watcher")
        }
        out += "Agents on: " + (if (on.isEmpty()) "none." else on.joinToString(", ") + ".")
        val alerts = net.solardepin.solarchik.agents.WatcherStore(ctx).recent(now).take(3)
        if (alerts.isNotEmpty()) out += "Watcher alerts (24h): " + alerts.joinToString("; ") { it.line() } + "."
        val acts = net.solardepin.solarchik.screen.CallActionStore(ctx).open().take(3)
        if (acts.isNotEmpty()) out += "Actions from calls waiting for the user's confirmation: " + acts.joinToString("; ") { actionLine(it) } + "."
        return out
    }

    fun actionLine(a: net.solardepin.solarchik.screen.CallAction): String = when (a.type) {
        net.solardepin.solarchik.screen.CallAction.PAYMENT -> "pay ${java.math.BigDecimal(a.amount).stripTrailingZeros().toPlainString()} ${a.token.ifBlank { "?" }}" + (a.recipient.takeIf { it.isNotBlank() }?.let { " to $it" } ?: "")
        net.solardepin.solarchik.screen.CallAction.CALLBACK -> "call back" + (a.number.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + (a.time.takeIf { it.isNotBlank() }?.let { " at $it" } ?: "")
        else -> "reminder" + (a.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + (a.time.takeIf { it.isNotBlank() }?.let { " at $it" } ?: "")
    }
}
