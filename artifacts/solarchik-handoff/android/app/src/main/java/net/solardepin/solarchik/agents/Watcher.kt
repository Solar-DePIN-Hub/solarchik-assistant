package net.solardepin.solarchik.agents

import android.content.Context
import net.solardepin.solarchik.swap.SwapTokens
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * 1.1.0 Watcher agent (OFF by default): watches SOL, SKR and JUP prices (Jupiter Price API, read-only) and the
 * user's wallet (SOL and SKR balances over public mainnet RPC, read-only) and alerts through a notification and
 * Sol (the alerts go into Sol's context and the morning briefing). It has no transaction code at all: it never
 * trades, signs or moves anything.
 */
data class WatcherPolicy(
    val enabled: Boolean = false,
    val priceMovePct: Int = 5,
    val walletMoveSol: Double = 0.01,
    val walletMoveSkr: Double = 10.0,
    val watchPrices: Boolean = true,
    val watchWallet: Boolean = true,
) {
    fun clamped(): WatcherPolicy = copy(
        priceMovePct = priceMovePct.coerceIn(2, 25),
        walletMoveSol = walletMoveSol.coerceIn(0.001, 100.0),
        walletMoveSkr = walletMoveSkr.coerceIn(1.0, 1_000_000.0),
    )

    companion object {
        val TOKENS = listOf(SwapTokens.SOL, SwapTokens.SKR, SwapTokens.JUP)
        const val MAX_ALERTS_A_DAY = 6
        /** The same thing (one token's price, or the wallet) alerts at most once in this window. */
        const val QUIET_MS = 2 * 3600_000L
    }
}

data class WatchAlert(val at: Long, val kind: String, val symbol: String, val from: Double, val to: Double) {
    val pct: Double get() = if (from == 0.0) 0.0 else (to - from) / from * 100.0
    val delta: Double get() = to - from

    /** Plain English line for Sol's context and the briefing facts (the UI formats its own localized text). */
    fun line(): String = when (kind) {
        PRICE -> "$symbol ${if (pct >= 0) "up" else "down"} ${"%.1f".format(java.util.Locale.US, abs(pct))}% to $${fmt(to)}"
        else -> "wallet $symbol ${if (delta >= 0) "+" else "-"}${fmt(abs(delta))} (now ${fmt(to)})"
    }

    companion object {
        const val PRICE = "price"
        const val WALLET = "wallet"
        fun fmt(v: Double): String = java.math.BigDecimal(v).round(java.math.MathContext(4)).stripTrailingZeros().toPlainString()
    }
}

object WatcherRules {
    /**
     * Pure: compares fresh readings with the baselines. A price alert fires when a token moved at least
     * [WatcherPolicy.priceMovePct] since its baseline; a wallet alert when SOL or SKR changed by at least the set
     * amount. Each alert moves that baseline to the new value. Returns (alerts, new baselines).
     */
    fun check(
        pol: WatcherPolicy,
        baselines: Map<String, Double>,
        prices: Map<String, Double>,
        wallet: Map<String, Double>,
        history: List<WatchAlert>,
        now: Long,
    ): Pair<List<WatchAlert>, Map<String, Double>> {
        val p = pol.clamped()
        val base = baselines.toMutableMap()
        val out = ArrayList<WatchAlert>()
        val dayStart = now - 24 * 3600_000L
        var budget = WatcherPolicy.MAX_ALERTS_A_DAY - history.count { it.at > dayStart }
        fun quiet(key: String) = history.any { "${it.kind}:${it.symbol}" == key && now - it.at < WatcherPolicy.QUIET_MS }
        if (p.watchPrices) for ((sym, price) in prices) {
            if (price <= 0.0 || !price.isFinite()) continue
            val key = "${WatchAlert.PRICE}:$sym"
            val b = base[key]
            if (b == null || b <= 0.0) { base[key] = price; continue }
            val pct = abs(price - b) / b * 100.0
            if (pct >= p.priceMovePct && !quiet(key) && budget > 0) {
                out += WatchAlert(now, WatchAlert.PRICE, sym, b, price)
                base[key] = price
                budget--
            }
        }
        if (p.watchWallet) for ((sym, bal) in wallet) {
            if (bal < 0.0 || !bal.isFinite()) continue
            val key = "${WatchAlert.WALLET}:$sym"
            val b = base[key]
            if (b == null) { base[key] = bal; continue }
            val min = if (sym == "SOL") p.walletMoveSol else p.walletMoveSkr
            if (abs(bal - b) >= min && budget > 0) {
                out += WatchAlert(now, WatchAlert.WALLET, sym, b, bal)
                budget--
            }
            base[key] = bal
        }
        return out to base
    }

    /** Jupiter Price API v3 (no key, read-only). */
    const val PRICE_URL = "https://lite-api.jup.ag/price/v3?ids="

    fun priceUrl(): String = PRICE_URL + WatcherPolicy.TOKENS.joinToString(",") { it.mint }

    /** Pure: {"<mint>":{"usdPrice":..}} -> symbol -> USD. */
    fun parsePrices(json: String): Map<String, Double> {
        val o = JSONObject(json)
        val out = LinkedHashMap<String, Double>()
        for (t in WatcherPolicy.TOKENS) {
            val v = o.optJSONObject(t.mint)?.optDouble("usdPrice", Double.NaN) ?: Double.NaN
            if (v.isFinite() && v > 0) out[t.symbol] = v
        }
        return out
    }

    fun fetchPrices(get: (String) -> String = ::httpGet): Map<String, Double> = parsePrices(get(priceUrl()))

    fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}

class WatcherStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy(): WatcherPolicy = WatcherPolicy(
        p.getBoolean("enabled", false), p.getInt("pct", 5), p.getFloat("sol", 0.01f).toDouble(), p.getFloat("skr", 10f).toDouble(),
        p.getBoolean("prices", true), p.getBoolean("wallet", true),
    ).clamped()

    fun setPolicy(v: WatcherPolicy) {
        val c = v.clamped()
        p.edit().putBoolean("enabled", c.enabled).putInt("pct", c.priceMovePct).putFloat("sol", c.walletMoveSol.toFloat())
            .putFloat("skr", c.walletMoveSkr.toFloat()).putBoolean("prices", c.watchPrices).putBoolean("wallet", c.watchWallet).apply()
        if (!c.enabled) p.edit().remove("base").apply()
    }

    fun baselines(): Map<String, Double> = runCatching {
        val o = JSONObject(p.getString("base", "{}"))
        o.keys().asSequence().associateWith { o.getDouble(it) }
    }.getOrDefault(emptyMap())

    fun setBaselines(m: Map<String, Double>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString("base", o.toString()).apply()
    }

    fun alerts(): List<WatchAlert> = runCatching {
        val a = JSONArray(p.getString("alerts", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            WatchAlert(o.getLong("at"), o.getString("kind"), o.getString("sym"), o.getDouble("from"), o.getDouble("to"))
        }
    }.getOrDefault(emptyList())

    fun addAlerts(list: List<WatchAlert>) {
        if (list.isEmpty()) return
        val a = JSONArray()
        (alerts() + list).takeLast(40).forEach { a.put(JSONObject().put("at", it.at).put("kind", it.kind).put("sym", it.symbol).put("from", it.from).put("to", it.to)) }
        p.edit().putString("alerts", a.toString()).apply()
    }

    /** Alerts of the last [hours] hours, newest first (Sol's context, the briefing). */
    fun recent(now: Long = System.currentTimeMillis(), hours: Int = 24): List<WatchAlert> =
        alerts().filter { now - it.at in 0..hours * 3600_000L }.sortedByDescending { it.at }

    var lastCheckAt: Long
        get() = p.getLong("lastCheck", 0L)
        set(v) { p.edit().putLong("lastCheck", v).apply() }

    companion object { const val PREFS = "solarchik.watcher" }
}
