package net.solardepin.solarchik.agents

import android.content.Context
import net.solardepin.solarchik.swap.SwapGuard
import net.solardepin.solarchik.swap.SwapPolicy
import net.solardepin.solarchik.swap.SwapRecord
import net.solardepin.solarchik.swap.SwapRequest
import net.solardepin.solarchik.swap.SwapToken
import net.solardepin.solarchik.swap.SwapTokens
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 1.1.0 Saver agent (OFF by default): moves small amounts of SOL into USDC or SKR with real Jupiter swaps.
 * Two ways, both only ever PROPOSE: a scheduled small save (daily or weekly, notification -> review -> wallet)
 * and "save the change": after a SOL swap the user made, a share of it is offered as a save. Every save is a
 * normal Agents › Swaps request (by = "saver"): the swap opt-in, the daily cap, slippage and price-impact rules
 * and the wallet confirmation all apply. The Saver never signs anything.
 */
data class SaverPolicy(
    val enabled: Boolean = false,
    val target: String = "USDC",
    val amountLamports: Long = DEFAULT_AMOUNT,
    val everyDays: Int = 7,
    val sharePct: Int = 10,
    val hour: Int = 19,
) {
    val targetToken: SwapToken get() = SwapTokens.bySymbol(target)?.takeIf { it in TARGETS } ?: SwapTokens.USDC

    fun clamped(): SaverPolicy = copy(
        target = targetToken.symbol,
        amountLamports = amountLamports.coerceIn(MIN_LAMPORTS, MAX_LAMPORTS),
        everyDays = if (everyDays in listOf(1, 7)) everyDays else 7,
        sharePct = sharePct.coerceIn(0, MAX_SHARE_PCT),
        hour = hour.coerceIn(7, 22),
    )

    companion object {
        val TARGETS = listOf(SwapTokens.USDC, SwapTokens.SKR)
        const val DEFAULT_AMOUNT = 5_000_000L // 0.005 SOL
        const val MIN_LAMPORTS = 1_000_000L // 0.001 SOL (smallest swap worth the fee)
        const val MAX_LAMPORTS = 20_000_000L // 0.02 SOL a save, always also inside the swap day cap
        const val MAX_SHARE_PCT = 25
    }
}

/** One proposed save; [status]: proposed -> notified -> done / skipped. */
data class SaveAction(val id: String, val at: Long, val kind: String, val lamports: Long, val target: String, val status: String = PROPOSED) {
    companion object {
        const val SCHEDULED = "scheduled"
        const val CHANGE = "change"
        const val PROPOSED = "proposed"
        const val NOTIFIED = "notified"
        const val DONE = "done"
        const val SKIPPED = "skipped"
    }
}

object SaverRules {
    /** Pure: when the next scheduled save is due (local [hour] on the day after the last one plus [everyDays]). */
    fun nextDue(pol: SaverPolicy, lastScheduledAt: Long, enabledAt: Long, zone: ZoneId): Long {
        val p = pol.clamped()
        val base = if (lastScheduledAt > 0) Instant.ofEpochMilli(lastScheduledAt).atZone(zone).toLocalDate().plusDays(p.everyDays.toLong())
        else Instant.ofEpochMilli(enabledAt).atZone(zone).toLocalDate().let { d ->
            // first save: today if the hour is still ahead, else tomorrow
            if (Instant.ofEpochMilli(enabledAt).atZone(zone).hour < p.hour) d else d.plusDays(1)
        }
        return base.atTime(p.hour, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** Pure: the save for a SOL swap of [swapLamports] the user made ("save the change"); 0 when too small / off. */
    fun changeFor(pol: SaverPolicy, swapLamports: Long): Long {
        val p = pol.clamped()
        if (!p.enabled || p.sharePct <= 0 || swapLamports <= 0) return 0L
        val share = swapLamports * p.sharePct / 100
        if (share < SaverPolicy.MIN_LAMPORTS) return 0L
        return minOf(share, p.amountLamports)
    }

    /** Pure: what a save may actually be, inside the swap day cap left today. 0 = nothing (cap used / swaps off). */
    fun fit(lamports: Long, swap: SwapPolicy, records: List<SwapRecord>, day: String): Long {
        if (!swap.live) return 0L
        val room = (SwapGuard.remaining(swap, records, day) * 1e9).toLong()
        val v = minOf(lamports, room)
        return if (v < SaverPolicy.MIN_LAMPORTS) 0L else v
    }

    fun request(a: SaveAction): SwapRequest =
        SwapRequest(SwapTokens.SOL, SwapTokens.bySymbol(a.target) ?: SwapTokens.USDC, a.lamports, by = "saver", reason = a.id)

    /** Only SOL swaps the user made by hand count for "save the change" (never a save, autopilot or agent swap). */
    fun countsForChange(r: SwapRecord): Boolean = r.by.isBlank() && r.from == "SOL" && r.status != SwapRecord.STATUS_FAILED
}

class SaverStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy(): SaverPolicy = SaverPolicy(
        p.getBoolean("enabled", false), p.getString("target", "USDC").orEmpty(), p.getLong("amount", SaverPolicy.DEFAULT_AMOUNT),
        p.getInt("every", 7), p.getInt("share", 10), p.getInt("hour", 19),
    ).clamped()

    fun setPolicy(v: SaverPolicy) {
        val c = v.clamped()
        val e = p.edit().putBoolean("enabled", c.enabled).putString("target", c.target).putLong("amount", c.amountLamports)
            .putInt("every", c.everyDays).putInt("share", c.sharePct).putInt("hour", c.hour)
        if (c.enabled && p.getLong("enabledAt", 0L) == 0L) e.putLong("enabledAt", System.currentTimeMillis())
        if (!c.enabled) e.putLong("enabledAt", 0L)
        e.apply()
    }

    var enabledAt: Long
        get() = p.getLong("enabledAt", 0L)
        set(v) { p.edit().putLong("enabledAt", v).apply() }
    var lastScheduledAt: Long
        get() = p.getLong("lastScheduled", 0L)
        set(v) { p.edit().putLong("lastScheduled", v).apply() }
    /** Last user swap signature already offered as "save the change". */
    var changeSeen: String
        get() = p.getString("changeSeen", "").orEmpty()
        set(v) { p.edit().putString("changeSeen", v).apply() }

    fun actions(): List<SaveAction> = runCatching {
        val a = JSONArray(p.getString("actions", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SaveAction(o.getString("id"), o.getLong("at"), o.getString("kind"), o.getLong("lamports"), o.getString("target"), o.optString("status", SaveAction.PROPOSED))
        }
    }.getOrDefault(emptyList())

    fun save(list: List<SaveAction>) {
        val a = JSONArray()
        list.sortedBy { it.at }.takeLast(60).forEach {
            a.put(JSONObject().put("id", it.id).put("at", it.at).put("kind", it.kind).put("lamports", it.lamports).put("target", it.target).put("status", it.status))
        }
        p.edit().putString("actions", a.toString()).apply()
    }

    fun add(a: SaveAction) = save(actions().filterNot { it.id == a.id } + a)
    fun setStatus(id: String, status: String) = save(actions().map { if (it.id == id) it.copy(status = status) else it })
    fun find(id: String): SaveAction? = actions().firstOrNull { it.id == id }
    fun open(): List<SaveAction> = actions().filter { it.status == SaveAction.PROPOSED || it.status == SaveAction.NOTIFIED }

    companion object {
        const val PREFS = "solarchik.saver"
        fun dayOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
        fun today(zone: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zone).toString()
    }
}
