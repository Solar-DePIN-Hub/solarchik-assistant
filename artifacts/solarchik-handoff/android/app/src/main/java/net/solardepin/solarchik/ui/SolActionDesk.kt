package net.solardepin.solarchik.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.FlowOutcome
import net.solardepin.solarchik.agents.MwaSigner
import net.solardepin.solarchik.agents.OwnedAgent
import net.solardepin.solarchik.agents.StrategyApi
import net.solardepin.solarchik.agents.StrategyCard
import net.solardepin.solarchik.agents.StrategyFlows
import net.solardepin.solarchik.agents.StrategyRules
import net.solardepin.solarchik.agents.engine.Track
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.sol.ActAgent
import net.solardepin.solarchik.sol.ActContext
import net.solardepin.solarchik.sol.ActListing
import net.solardepin.solarchik.sol.ActType
import net.solardepin.solarchik.sol.ActionPlan
import net.solardepin.solarchik.wallet.WalletError

/** Result line of a confirmed action; [link] is a devnet Explorer URL when a transaction landed. */
data class ActResult(val ok: Boolean, val text: String, val link: String = "")

/**
 * Sol's hands (0.21.7): builds what Sol may act on (my agents, the market) and runs a CONFIRMED
 * [ActionPlan] through the same devnet flows as the Agents tab: [StrategyFlows] (wallet proof + server
 * co-signed txs), [net.solardepin.solarchik.agents.Minter] and the on-phone desk.
 */
class SolActionDesk(
    private val host: MainActivity,
    private val api: suspend (String, JsonObject) -> JsonObject = { r, b -> StrategyApi.post(r, b) },
) {
    private val ctx get() = host
    /**
     * 0.21.9: the network half of the context (strategy cards, market listings). Building it used to run
     * before every Sol request, sequentially, and a cold Vercel start made it 2+ s on its own (a big part of
     * the 4–6 s voice gap). Now it is fetched in parallel, capped, prewarmed when the Sol tab opens or the mic
     * starts, and a stale copy (≤ [STALE_MS]) answers at once while a fresh one loads in the background.
     * The local half (desk run state, owned records) is rebuilt on every call, so it is never stale.
     */
    private data class NetPart(val at: Long, val complete: Boolean, val cards: Map<String, StrategyCard?>, val market: List<ActListing>)
    @Volatile private var remote: NetPart? = null
    @Volatile private var refreshing = false

    /** Last context build: how long the network part took and whether a cached copy was used (latency log). */
    @Volatile var lastBuildMs: Long = -1
        private set

    fun invalidate() { remote = remote?.copy(at = 0) }

    private fun devnetAssets(): List<OwnedAgent> {
        val w = host.wallet
        if (!w.connected || w.clusterName != "devnet") return emptyList()
        return host.store.agentsFor(w.address, "devnet").filter { it.status != OwnedAgent.STATUS_MISSING }
    }

    private suspend fun fetchNet(capMs: Long): NetPart = kotlinx.coroutines.coroutineScope {
        val w = host.wallet
        val assets = devnetAssets()
        val cards = assets.map { a ->
            a.asset to async {
                kotlinx.coroutines.withTimeoutOrNull(capMs) {
                    runCatching { api("strategy-info", buildJsonObject { put("asset", a.asset) }) }.getOrNull()
                        ?.takeIf { StrategyApi.ok(it) }?.let { StrategyCard.parse(it) }
                }
            }
        }
        val market = async {
            kotlinx.coroutines.withTimeoutOrNull(capMs) {
                runCatching { StrategyCard.parseMarket(api("market-list", JsonObject(emptyMap()))) }.getOrNull()
            }
        }
        val cardMap = cards.associate { (id, d) -> id to d.await() }
        val m = market.await()
        val listings = m.orEmpty().filter { it.priceLamports != null && !(w.connected && it.owner == w.address) }
            .map { ActListing(it.asset, it.name, it.priceLamports ?: 0, it.spec) }
        NetPart(System.currentTimeMillis(), m != null && cardMap.values.none { it == null }, cardMap, listings)
    }

    private fun refreshLater() {
        if (refreshing) return
        refreshing = true
        host.scope.launch {
            try { remote = fetchNet(REFRESH_CAP_MS) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) {} finally { refreshing = false }
        }
    }

    /** Warm the network part ahead of a request (Sol tab opened, mic started). */
    fun prewarm() {
        val n = remote
        if (n == null || !n.complete || System.currentTimeMillis() - n.at >= CACHE_MS) refreshLater()
    }

    private suspend fun netPart(): NetPart {
        val n = remote
        val now = System.currentTimeMillis()
        val missing = n != null && devnetAssets().any { it.asset !in n.cards }
        if (n != null && n.complete && !missing && now - n.at < CACHE_MS) return n
        if (n != null && now - n.at < STALE_MS) { refreshLater(); return n }
        return fetchNet(FIRST_CAP_MS).also { remote = it; if (!it.complete) refreshLater() }
    }

    suspend fun context(): ActContext {
        val t0 = System.currentTimeMillis()
        val n = netPart()
        lastBuildMs = System.currentTimeMillis() - t0
        val w = host.wallet
        val desk = host.desk.state()
        val devnet = mutableListOf<ActAgent>()
        for (a in devnetAssets()) {
            val card = n.cards[a.asset]
            val run = desk.run(a.asset)
            devnet += ActAgent(
                id = a.asset, name = card?.name?.ifBlank { null } ?: a.name, running = run?.running == true,
                strategyNft = card?.hasChain == true && card.spec != null, spec = card?.spec, listed = card?.listed == true,
                unlockSec = card?.unlockSec ?: 0, trades = run?.let { it.wins + it.losses } ?: card?.perf?.trades,
                pnlSol = run?.pnl ?: card?.perf?.realizedSol, skuId = a.skuId, tier = a.tier, track = Track.DEVNET,
                aprSince = card?.perf?.aprSince,
            )
        }
        val records = host.store.agents()
        val paper = mutableListOf<ActAgent>()
        for (sku in Catalog.skus) {
            val key = "paper:${sku.id}"
            val run = desk.run(key)
            // 0.21.8: a paper agent is only "mine" when I own its sku (free or Pro NFT); Sol offers the rest
            val ownedRec = records.filter { it.status != OwnedAgent.STATUS_MISSING && Catalog.baseOf(it.skuId)?.id == sku.id }
            val tier = if (ownedRec.any { it.tier == AgentTier.PRO }) AgentTier.PRO else sku.tierFor(AgentTier.FREE)
            paper += ActAgent(
                id = key, name = sku.name, running = run?.running == true, trades = run?.let { it.wins + it.losses },
                pnlSol = run?.pnl, skuId = sku.id, tier = tier, track = Track.PAPER,
                owned = host.paperOpen || net.solardepin.solarchik.agents.Ownership.ownsSku(records, sku.id),
            )
        }
        val agents = net.solardepin.solarchik.sol.SolActions.mergeAgents(devnet, paper, ctx.getString(R.string.sol_agent_practice)) { if (host.lang == "uk") AgentNames.uk(it) else it }
        val mine = (devnet + paper).map { it.id }.toSet()
        val market = n.market.filter { it.id !in mine }
        val freeSku = Catalog.skus.firstOrNull { !it.paidOnly }
        val canMint = freeSku != null && !w.mainnet && host.minter.canMint(freeSku, AgentTier.FREE) == null
        return ActContext(agents, market, canMint)
    }

    fun riskLabel(r: String): String = when (r) {
        "calm" -> ctx.getString(R.string.sol_risk_calm)
        "balanced" -> ctx.getString(R.string.sol_risk_balanced)
        "risky" -> ctx.getString(R.string.sol_risk_risky)
        else -> r
    }

    /** "how is my agent doing": answered at once from the desk and the on-chain results. */
    fun status(a: ActAgent): String {
        val state = ctx.getString(if (a.running) R.string.sol_status_running else R.string.sol_status_stopped)
        var s = ctx.getString(R.string.sol_status_line, a.name, state, a.trades ?: 0, Fmt.signedSol(a.pnlSol ?: 0.0, 6))
        if (a.strategyNft) {
            val apr = a.aprSince?.let { "${Fmt.sol(it, 1)}%" } ?: "—"
            s += " " + ctx.getString(R.string.sol_status_strategy, riskLabel(a.risk), a.windows.joinToString("/").ifBlank { "—" }, apr)
            StrategyRules.lockParts(StrategyRules.lockLeftMs(a.unlockSec, System.currentTimeMillis()))?.let { (d, h) ->
                s += " " + ctx.getString(R.string.sm_lock_left, d, h)
            }
        }
        return s
    }

    private fun failText(o: FlowOutcome): String = ctx.getString(R.string.sol_act_failed, o.error?.let { WalletError.text(ctx, it) } ?: o.reason)

    /** Runs a plan the player CONFIRMED. Never called without the tap (see SolScreen.confirm). */
    suspend fun execute(plan: ActionPlan): ActResult {
        val w = host.wallet
        val a = plan.agent
        val result = when (plan.action.type) {
            ActType.BUY_STRATEGY -> {
                val l = plan.listing ?: return ActResult(false, ctx.getString(R.string.sol_act_blocked_gone))
                if (!w.connected || w.clusterName != "devnet") return ActResult(false, ctx.getString(R.string.sm_need_devnet))
                val o = StrategyFlows(MwaSigner(w, host.sender)).buy(w.address, l.id, l.priceLamports)
                if (o.ok) {
                    StrategyPanel.rememberBought(host, l.id, l.name, w.address, o.sig)
                    ActResult(true, ctx.getString(R.string.sol_act_bought, l.name, Fmt.sol(l.priceSol)), StrategyRules.explorerTx(o.sig))
                } else ActResult(false, failText(o), o.sig.takeIf { it.isNotBlank() }?.let(StrategyRules::explorerTx).orEmpty())
            }
            ActType.SET_STRATEGY -> {
                val next = plan.nextSpec
                if (a == null || next == null) return ActResult(false, ctx.getString(R.string.sol_act_blocked_no_nft))
                if (!w.connected || w.clusterName != "devnet") return ActResult(false, ctx.getString(R.string.sm_need_devnet))
                val o = StrategyFlows(MwaSigner(w, host.sender)).changeStrategy(w.address, a.id, next)
                if (o.ok) ActResult(true, ctx.getString(R.string.sol_act_strategy_done, a.name, o.version, StrategyRules.SALE_LOCK_HOURS), StrategyRules.explorerTx(o.sig))
                else ActResult(false, failText(o), o.sig.takeIf { it.isNotBlank() }?.let(StrategyRules::explorerTx).orEmpty())
            }
            ActType.MINT_FREE -> {
                val sku = Catalog.skus.first { !it.paidOnly }
                host.minter.mint(host.sender, sku, AgentTier.FREE).fold(
                    onSuccess = { rec -> ActResult(true, ctx.getString(R.string.sol_act_minted, AgentNames.display(ctx, rec.name)), rec.sig.takeIf { it.isNotBlank() }?.let { host.explorerTx(it, rec.cluster) }.orEmpty()) },
                    onFailure = { ActResult(false, ctx.getString(R.string.sol_act_failed, host.errorText(it))) },
                )
            }
            ActType.START_AGENT -> {
                if (a == null) return ActResult(false, ctx.getString(R.string.sol_act_unclear))
                host.desk.start(a.id, a.skuId, a.tier, a.name, a.track).fold(
                    onSuccess = {
                        host.deskChanged()
                        ActResult(true, ctx.getString(R.string.sol_act_started, a.name, ctx.getString(if (a.track == Track.PAPER) R.string.track_paper else R.string.track_devnet)))
                    },
                    onFailure = { ActResult(false, ctx.getString(R.string.sol_act_failed, if (it is net.solardepin.solarchik.agents.engine.DeskError) ctx.getString(R.string.desk_not_owned) else host.errorText(it))) },
                )
            }
            ActType.STOP_AGENT -> {
                if (a == null) return ActResult(false, ctx.getString(R.string.sol_act_unclear))
                host.desk.stop(a.id)
                host.deskChanged()
                ActResult(true, ctx.getString(R.string.sol_act_stopped, a.name))
            }
            ActType.AGENT_STATUS -> ActResult(true, a?.let(::status) ?: ctx.getString(R.string.sol_act_unclear))
        }
        invalidate()
        return result
    }

    /**
     * 0.21.8: "start X" for an agent I don't own — runs only after the player picked Mint free / Buy Pro on
     * the confirmation card: mint (wallet approval), then start the paper run with the minted tier.
     */
    suspend fun acquireAndStart(plan: ActionPlan, tier: String): ActResult {
        val a = plan.agent ?: return ActResult(false, ctx.getString(R.string.sol_act_unclear))
        val sku = Catalog.baseOf(a.skuId) ?: return ActResult(false, ctx.getString(R.string.sol_act_unclear))
        val minted = host.minter.mint(host.sender, sku, tier).getOrElse {
            return ActResult(false, ctx.getString(R.string.sol_act_failed, host.errorText(it)))
        }
        val link = minted.sig.takeIf { it.isNotBlank() }?.let { host.explorerTx(it, minted.cluster) }.orEmpty()
        invalidate()
        return host.desk.start(a.id, sku.id, tier, sku.nameFor(tier), Track.PAPER).fold(
            onSuccess = {
                host.deskChanged()
                ActResult(true, ctx.getString(R.string.sol_act_minted, AgentNames.display(ctx, minted.name)) + " " +
                    ctx.getString(R.string.sol_act_started, a.name, ctx.getString(R.string.track_paper)), link)
            },
            onFailure = { ActResult(false, ctx.getString(R.string.sol_act_failed, host.errorText(it)), link) },
        )
    }

    companion object {
        private const val CACHE_MS = 45_000L
        /** A cached network part this old still answers at once (refreshed in the background). */
        private const val STALE_MS = 15 * 60_000L
        /** No cache yet: wait at most this long for cards and market before asking Sol without them. */
        private const val FIRST_CAP_MS = 1_200L
        private const val REFRESH_CAP_MS = 8_000L
    }
}
