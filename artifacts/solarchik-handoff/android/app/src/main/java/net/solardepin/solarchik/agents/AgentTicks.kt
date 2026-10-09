package net.solardepin.solarchik.agents

import android.content.Context
import android.content.Intent
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.swap.JupiterApi
import net.solardepin.solarchik.swap.SwapRecord
import net.solardepin.solarchik.swap.SwapStore
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.ui.Fmt
import net.solardepin.solarchik.wallet.SolanaWallet
import java.time.ZoneId

/** Background steps of the Saver and the Watcher (called from the 15-minute AutoRunner tick). Neither signs. */
object AgentTicks {
    const val NOTE_SAVER = 360
    const val NOTE_WATCHER = 370

    private fun open(ctx: Context, extra: String) = Intent(ctx, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_AUTOPILOT, extra)
    }

    /** "Save the change": the newest SOL swap the user made by hand, offered once as a small save. */
    fun proposeChange(ctx: Context, r: SwapRecord, now: Long = System.currentTimeMillis()): SaveAction? {
        val store = SaverStore(ctx)
        val pol = store.policy()
        if (!pol.enabled || !SaverRules.countsForChange(r) || store.changeSeen == r.signature) return null
        store.changeSeen = r.signature
        val swaps = SwapStore(ctx)
        val lamports = SaverRules.fit(SaverRules.changeFor(pol, r.inAmount), swaps.policy(), swaps.records(), SaverStore.today())
        if (lamports <= 0L) return null
        val a = SaveAction("save-c-$now", now, SaveAction.CHANGE, lamports, pol.target)
        store.add(a)
        return a
    }

    suspend fun saver(ctx: Context, now: Long, post: (Int, String, String, Intent) -> Boolean, jup: JupiterApi, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val store = SaverStore(ctx)
        val pol = store.policy()
        val wallet = SolanaWallet(ctx)
        if (!pol.enabled || !wallet.mainnet || !wallet.connected || wallet.isLocal) return emptyList()
        val out = ArrayList<String>()
        val swaps = SwapStore(ctx)
        val sp = swaps.policy()
        val newest = swaps.records().filter { SaverRules.countsForChange(it) && now - it.at in 0..24 * 3600_000L }.maxByOrNull { it.at }
        val proposals = ArrayList<SaveAction>()
        newest?.let { proposeChange(ctx, it, now)?.let(proposals::add) }
        if (now >= SaverRules.nextDue(pol, store.lastScheduledAt, store.enabledAt.takeIf { it > 0 } ?: now, zone)) {
            store.lastScheduledAt = now
            val lamports = SaverRules.fit(pol.amountLamports, sp, swaps.records(), SaverStore.dayOf(now, zone))
            if (lamports > 0L) SaveAction("save-s-$now", now, SaveAction.SCHEDULED, lamports, pol.target).also { store.add(it); proposals += it }
            else out += "saver:skip-cap"
        }
        for (a in proposals) {
            val to = SwapTokens.bySymbol(a.target) ?: continue
            val q = runCatching { jup.quote(SwapTokens.SOL, to, a.lamports, sp.clamped().maxSlippageBps).outAmount }.getOrDefault(0L)
            val what = Fmt.sol(a.lamports / 1e9, 4) + " SOL → " + (if (q > 0) "≈ " + Fmt.sol(to.fromRaw(q), 4) + " " else "") + to.symbol
            val body = ctx.getString(if (a.kind == SaveAction.CHANGE) R.string.sv_note_change else R.string.sv_note_body, what)
            if (post(NOTE_SAVER, ctx.getString(R.string.sv_note_title), body, open(ctx, "saver:" + a.id))) {
                store.setStatus(a.id, SaveAction.NOTIFIED)
                out += "saver:${a.kind}"
            }
        }
        return out
    }

    /** Read-only checks; [prices] and [balances] are injectable for tests. */
    suspend fun watcher(
        ctx: Context,
        now: Long,
        post: (Int, String, String, Intent) -> Boolean,
        prices: suspend () -> Map<String, Double> = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WatcherRules.fetchPrices() } },
        balances: (suspend () -> Map<String, Double>)? = null,
        rpc: Rpc? = null,
    ): List<String> {
        val store = WatcherStore(ctx)
        val pol = store.policy()
        if (!pol.enabled || now - store.lastCheckAt < 14 * 60_000L) return emptyList()
        store.lastCheckAt = now
        val p = if (pol.watchPrices) runCatching { prices() }.getOrDefault(emptyMap()) else emptyMap()
        val w = if (pol.watchWallet) runCatching { (balances ?: { walletBalances(ctx, rpc) })() }.getOrDefault(emptyMap()) else emptyMap()
        val (alerts, base) = WatcherRules.check(pol, store.baselines(), p, w, store.alerts(), now)
        store.setBaselines(base)
        if (alerts.isEmpty()) return listOf("watcher:quiet")
        store.addAlerts(alerts)
        val body = alerts.joinToString("\n") { text(ctx, it) }
        post(NOTE_WATCHER, ctx.getString(R.string.wt_note_title), body, open(ctx, "watcher"))
        return alerts.map { "watcher:${it.kind}:${it.symbol}" }
    }

    fun text(ctx: Context, a: WatchAlert): String = when (a.kind) {
        WatchAlert.PRICE -> ctx.getString(if (a.pct >= 0) R.string.wt_price_up else R.string.wt_price_down, a.symbol, "%.1f".format(kotlin.math.abs(a.pct)), WatchAlert.fmt(a.to))
        else -> ctx.getString(if (a.delta >= 0) R.string.wt_wallet_in else R.string.wt_wallet_out, WatchAlert.fmt(kotlin.math.abs(a.delta)) + " " + a.symbol, WatchAlert.fmt(a.to) + " " + a.symbol)
    }

    private suspend fun walletBalances(ctx: Context, rpc0: Rpc?): Map<String, Double> {
        val wallet = SolanaWallet(ctx)
        if (!wallet.connected || !wallet.mainnet) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        runCatching { (rpc0 ?: wallet.rpc).balanceLamports(wallet.address) / 1e9 }.onSuccess { out["SOL"] = it }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetch(wallet.address) }.onSuccess { out["SKR"] = it }
        return out
    }
}
