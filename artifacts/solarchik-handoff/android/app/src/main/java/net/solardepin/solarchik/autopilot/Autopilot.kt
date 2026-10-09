package net.solardepin.solarchik.autopilot

import android.content.Context
import net.solardepin.solarchik.season.SeasonDapps
import net.solardepin.solarchik.swap.SwapRecord
import net.solardepin.solarchik.swap.SwapTokens
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 1.1.0 Season autopilot (OFF by default). Each day it plans 1–2 varied actions at random times inside the user's
 * waking hours: a small Jupiter swap (allowlist, inside the swap cap and the autopilot caps), the daily check-in
 * memo, opening a suggested dApp, or the SKR staking page. When one is due it fetches a fresh read-only quote and
 * posts a notification. Tapping it opens the review; the WALLET signs. The autopilot never signs anything itself.
 */
enum class AutoKind { CHECKIN, SWAP, DAPP, STAKING }

data class AutoAction(
    val id: String,
    val day: String,
    val at: Long,
    val kind: AutoKind,
    /** SWAP: output symbol and SOL input in lamports. */
    val to: String = "",
    val lamports: Long = 0L,
    /** DAPP: the suggested dApp's name. */
    val dapp: String = "",
    val status: String = PLANNED,
    /** SWAP: the quoted output (raw) when the notification went out. */
    val quotedOut: Long = 0L,
) {
    companion object {
        const val PLANNED = "planned"
        const val NOTIFIED = "notified"
        const val DONE = "done"
        const val SKIPPED = "skipped"
        const val MISSED = "missed"
    }
}

data class AutopilotPolicy(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val wakeFrom: Int = 9,
    val wakeTo: Int = 22,
    /** Daily cap on the NUMBER of actions (1–2 by default, at most 3). */
    val perDay: Int = 2,
    /** Per-action max for an autopilot swap (SOL, lamports) and the daily autopilot swap cap. */
    val swapMaxLamports: Long = 10_000_000L,
    val swapDayCapLamports: Long = 20_000_000L,
) {
    val active: Boolean get() = enabled && !paused

    fun clamped(): AutopilotPolicy {
        val from = wakeFrom.coerceIn(5, 21)
        val to = wakeTo.coerceIn(from + 3, 24)
        val day = swapDayCapLamports.coerceIn(MIN_SWAP_LAMPORTS, HARD_SWAP_DAY_LAMPORTS)
        return copy(wakeFrom = from, wakeTo = to, perDay = perDay.coerceIn(1, 3), swapDayCapLamports = day,
            swapMaxLamports = swapMaxLamports.coerceIn(MIN_SWAP_LAMPORTS, day))
    }

    companion object {
        const val MIN_SWAP_LAMPORTS = 1_000_000L
        const val HARD_SWAP_DAY_LAMPORTS = 100_000_000L
        /** At least this long between two actions on the same day. */
        const val MIN_GAP_MS = 90 * 60_000L
    }
}

object AutopilotPlanner {
    val SWAP_TARGETS = listOf("USDC", "JUP", "SKR")

    /**
     * Today's plan. [history] = earlier actions (for variety: no identical swap within 14 days, no kind repeated as the
     * first action two days running, staking at most every 3 days, a dApp not suggested in the last 3 autopilot dApps).
     * [swapRoomLamports] = what is left today under BOTH the swap cap and the autopilot swap cap (0 = no swap).
     */
    fun plan(
        day: LocalDate,
        zone: ZoneId,
        policy: AutopilotPolicy,
        history: List<AutoAction>,
        swapRoomLamports: Long,
        checkedInToday: Boolean,
        notBefore: Long,
        rnd: Random,
    ): List<AutoAction> {
        val p = policy.clamped()
        val dayStr = day.toString()
        val start = day.atTime(p.wakeFrom, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(minOf(p.wakeTo, 23), if (p.wakeTo >= 24) 59 else 0).atZone(zone).toInstant().toEpochMilli()
        val from = maxOf(start, notBefore)
        if (end - from < 30 * 60_000L) return emptyList()

        val recent = history.filter { it.day < dayStr }.sortedBy { it.at }
        val lastFirst = recent.lastOrNull()?.let { last -> recent.filter { it.day == last.day }.minByOrNull { it.at }?.kind }
        val stakingRecently = recent.any { it.kind == AutoKind.STAKING && it.day >= day.minusDays(3).toString() }
        val candidates = ArrayList<AutoKind>()
        if (!checkedInToday) candidates += AutoKind.CHECKIN
        if (swapRoomLamports >= AutopilotPolicy.MIN_SWAP_LAMPORTS) candidates += AutoKind.SWAP
        candidates += AutoKind.DAPP
        if (!stakingRecently) candidates += AutoKind.STAKING
        candidates.shuffle(rnd)
        // variety: yesterday's first kind goes to the back
        lastFirst?.let { k -> if (candidates.size > 1 && candidates.first() == k) { candidates.remove(k); candidates.add(k) } }

        val n = minOf(1 + rnd.nextInt(p.perDay), candidates.size)
        val kinds = candidates.take(n)
        val times = times(from, end, n, rnd)
        var room = swapRoomLamports
        return kinds.mapIndexed { i, k ->
            val at = times[i]
            val id = "$dayStr-$i-${k.name.lowercase()}"
            when (k) {
                AutoKind.SWAP -> {
                    val (to, lamports) = swapFor(p, minOf(room, p.swapMaxLamports), recent, rnd)
                    room -= lamports
                    AutoAction(id, dayStr, at, k, to = to, lamports = lamports)
                }
                AutoKind.DAPP -> {
                    val used = recent.filter { it.kind == AutoKind.DAPP }.takeLast(3).map { it.dapp }.toSet()
                    val pick = SeasonDapps.all.filter { it.name !in used }.ifEmpty { SeasonDapps.all }.let { it[rnd.nextInt(it.size)] }
                    AutoAction(id, dayStr, at, k, dapp = pick.name)
                }
                else -> AutoAction(id, dayStr, at, k)
            }
        }.filter { it.kind != AutoKind.SWAP || it.lamports >= AutopilotPolicy.MIN_SWAP_LAMPORTS }
    }

    /** n random times in [from, end) at least MIN_GAP apart (closer if the window is short), sorted. */
    fun times(from: Long, end: Long, n: Int, rnd: Random): List<Long> {
        if (n <= 0) return emptyList()
        val span = end - from
        val gap = minOf(AutopilotPolicy.MIN_GAP_MS, span / (n + 1))
        val free = span - gap * (n - 1)
        val picks = List(n) { (rnd.nextDouble() * free).toLong() }.sorted()
        return picks.mapIndexed { i, x -> from + x + gap * i }
    }

    /** A varied small swap: random target and amount (30–100% of the room, 0.0001 SOL steps), never identical to one in the last 14 days. */
    fun swapFor(p: AutopilotPolicy, room: Long, recent: List<AutoAction>, rnd: Random): Pair<String, Long> {
        if (room < AutopilotPolicy.MIN_SWAP_LAMPORTS) return "USDC" to 0L
        val seen = recent.filter { it.kind == AutoKind.SWAP }.takeLast(28).map { it.to to it.lamports }.toSet()
        val lastTo = recent.lastOrNull { it.kind == AutoKind.SWAP }?.to
        val targets = SWAP_TARGETS.shuffled(rnd).sortedBy { if (it == lastTo) 1 else 0 }
        val step = 100_000L
        repeat(20) { attempt ->
            val to = targets[attempt % targets.size]
            val raw = (room * (0.3 + 0.7 * rnd.nextDouble())).toLong() / step * step
            val amt = raw.coerceIn(AutopilotPolicy.MIN_SWAP_LAMPORTS, room)
            if ((to to amt) !in seen) return to to amt
        }
        return targets.first() to room
    }

    /** What is left today for an autopilot swap: min(autopilot day cap − autopilot swaps today, swap-cap room). */
    fun swapRoom(p: AutopilotPolicy, records: List<SwapRecord>, day: String, swapCapRoomSol: Double, swapsLive: Boolean): Long {
        if (!swapsLive) return 0L
        val spent = records.filter { it.day == day && it.by == "autopilot" && it.status != SwapRecord.STATUS_FAILED }.sumOf { Math.round(it.solValue * 1e9) }
        return maxOf(0L, minOf(p.clamped().swapDayCapLamports - spent, Math.round(swapCapRoomSol * 1e9)))
    }

    /** Due now: planned, time reached, not older than 3 h (older ones become MISSED rather than nagging late). */
    fun due(actions: List<AutoAction>, now: Long): List<AutoAction> =
        actions.filter { it.status == AutoAction.PLANNED && it.at <= now && now - it.at < 3 * 3_600_000L }

    fun stale(actions: List<AutoAction>, now: Long): List<AutoAction> =
        actions.filter { (it.status == AutoAction.PLANNED || it.status == AutoAction.NOTIFIED) && now - it.at >= 6 * 3_600_000L }
}

/** Policy + actions on this phone (prefs "solarchik.autopilot"). */
class AutopilotStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy(): AutopilotPolicy = AutopilotPolicy(
        p.getBoolean("enabled", false), p.getBoolean("paused", false), p.getInt("wakeFrom", 9), p.getInt("wakeTo", 22),
        p.getInt("perDay", 2), p.getLong("swapMax", 10_000_000L), p.getLong("swapDay", 20_000_000L),
    ).clamped()

    fun setPolicy(v: AutopilotPolicy) {
        val c = v.clamped()
        p.edit().putBoolean("enabled", c.enabled).putBoolean("paused", c.paused).putInt("wakeFrom", c.wakeFrom).putInt("wakeTo", c.wakeTo)
            .putInt("perDay", c.perDay).putLong("swapMax", c.swapMaxLamports).putLong("swapDay", c.swapDayCapLamports).apply()
    }

    fun actions(): List<AutoAction> = runCatching {
        val a = JSONArray(p.getString("actions", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            AutoAction(o.getString("id"), o.getString("day"), o.getLong("at"), AutoKind.valueOf(o.getString("kind")),
                o.optString("to"), o.optLong("lamports"), o.optString("dapp"), o.optString("status", AutoAction.PLANNED), o.optLong("quoted"))
        }
    }.getOrDefault(emptyList())

    fun save(list: List<AutoAction>) {
        val a = JSONArray()
        list.sortedBy { it.at }.takeLast(80).forEach {
            a.put(JSONObject().put("id", it.id).put("day", it.day).put("at", it.at).put("kind", it.kind.name).put("to", it.to)
                .put("lamports", it.lamports).put("dapp", it.dapp).put("status", it.status).put("quoted", it.quotedOut))
        }
        p.edit().putString("actions", a.toString()).apply()
    }

    fun find(id: String): AutoAction? = actions().firstOrNull { it.id == id }

    fun update(id: String, f: (AutoAction) -> AutoAction) = save(actions().map { if (it.id == id) f(it) else it })

    fun setStatus(id: String, status: String) = update(id) { it.copy(status = status) }

    fun clear() = p.edit().clear().apply()

    companion object { const val PREFS = "solarchik.autopilot" }
}
