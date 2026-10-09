package net.solardepin.solarchik.agents.engine

import android.content.Context
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.agents.OwnedAgent
import net.solardepin.solarchik.core.FeeLedger
import net.solardepin.solarchik.core.FeeReason
import net.solardepin.solarchik.core.FeeRow
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.solana.MemoIx
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.solana.SystemIx
import net.solardepin.solarchik.wallet.SentTx
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError
import java.security.MessageDigest
import org.sol4k.PublicKey
import java.util.UUID

class DeskError(val kind: Kind) : Exception(kind.name) {
    enum class Kind { DEVNET_ONLY, NOTHING_OWED, NOT_OWNED, PAYMENT_PENDING }
}

data class TickReport(val closed: List<Pair<Closed, FeeRow>>, val opened: List<Position>, val events: List<DeskEvent>)

/**
 * Runs the strategies on this phone: reads live markets, calls [AgentEngine], books every
 * close through [FeeLedger.planFee] with the fee-free window rule, and pays owed fees on devnet.
 */
class Desk(
    context: Context,
    private val feed: MarketFeed = HttpMarketFeed(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val rpc: Rpc = Rpc(SolarchikConfig.RPC_DEVNET),
    /**
     * 1.1.0: on mainnet, while NFT mints are "coming soon", paper runs open without an owned agent
     * (paper is simulated money; there is nothing to mint yet).
     */
    private val paperOpen: () -> Boolean = { net.solardepin.solarchik.wallet.SolanaWallet(context).mainnet && !SolarchikConfig.MAINNET_MINT_READY },
) {
    private val app = context.applicationContext
    val store = DeskStore(app)
    val agents = AgentStore(app)
    private val save get() = GameSave(app, clock)

    fun state(): DeskState = store.read()

    suspend fun start(key: String, skuId: String, tier: String, name: String, track: String): Result<DeskState> = withContext(Dispatchers.IO) { lock.withLock {
        if (track == Track.DEVNET) {
            val owned = agents.agents().firstOrNull { it.asset == key && it.cluster == "devnet" && it.status != OwnedAgent.STATUS_MISSING }
                ?: return@withLock Result.failure(DeskError(DeskError.Kind.NOT_OWNED))
            if (owned.tier != tier) return@withLock Result.failure(DeskError(DeskError.Kind.NOT_OWNED))
        } else if (!paperOpen() && !net.solardepin.solarchik.agents.Ownership.ownsSku(agents.agents(), skuId)) {
            // 0.21.8: paper runs need an owned agent as well (mint free or buy Pro first)
            return@withLock Result.failure(DeskError(DeskError.Kind.NOT_OWNED))
        }
        val s = store.read()
        val now = clock()
        val prev = s.run(key)
        val run = (prev ?: AgentRun(key, skuId, tier, name, track)).copy(
            running = true, track = track, startedAt = now, anchors = if (prev?.track == track) prev.anchors else emptyMap(),
        )
        val next = s.copy(runs = s.runs.filter { it.key != key } + run)
        store.write(next)
        Result.success(next)
    } }

    /** Stops new entries. An open position still settles at its window, so PnL is never left hanging. */
    suspend fun stop(key: String): DeskState = withContext(Dispatchers.IO) { lock.withLock {
        val s = store.read()
        val next = s.copy(runs = s.runs.map { if (it.key == key) it.copy(running = false) else it })
        store.write(next)
        next
    } }

    suspend fun setCaps(caps: UserCaps): UserCaps = withContext(Dispatchers.IO) { lock.withLock {
        val s = store.read()
        val c = RiskCaps.clamp(caps)
        store.write(s.copy(caps = c))
        c
    } }

    fun freeFor(s: DeskState, track: String): Double {
        val locked = s.runs.filter { it.track == track }.sumOf { it.open?.stake ?: 0.0 }
        val purse = if (track == Track.PAPER) Strategies.PAPER_PURSE + s.paperPnl else s.devnetBalance
        return maxOf(0.0, purse - locked)
    }

    suspend fun tick(): TickReport = withContext(Dispatchers.IO) { lock.withLock {
        var s = store.read()
        val now = clock()
        val active = s.runs.filter { it.running || it.open != null }
        if (active.isEmpty()) return@withLock TickReport(emptyList(), emptyList(), emptyList())

        // Devnet sizing reads the real devnet balance of the NFT owner (at most every 5 minutes).
        if (active.any { it.track == Track.DEVNET } && (now - s.balanceAt > BALANCE_EVERY_MS || now < s.balanceAt)) {
            val owner = agents.agents().firstOrNull { a -> active.any { it.key == a.asset } }?.owner
            if (owner != null) runCatching { rpc.balanceLamports(owner) / 1e9 }.onSuccess { s = s.copy(devnetBalance = it, balanceAt = now) }
        }

        val cache = HashMap<String, Quote?>()
        suspend fun q(src: Source, key: String? = null): Quote? = cache.getOrPut("${src.name}|${key.orEmpty()}") { feed.quote(src, key) }

        val closed = ArrayList<Pair<Closed, FeeRow>>()
        val opened = ArrayList<Position>()
        val events = ArrayList<DeskEvent>()
        val runs = s.runs.toMutableList()
        val save = this@Desk.save
        for (i in runs.indices) {
            val run = runs[i]
            if (!run.running && run.open == null) continue
            val open = run.open
            val settle = if (open != null && now >= open.closeAt) {
                // A position from an older build may name a source this build lacks: no quote, so it
                // closes flat after the no-data grace instead of failing every tick.
                Source.entries.firstOrNull { it.name == open.source }?.let { src -> q(src, if (src == Source.EVENTS) open.key else null) }
            } else null
            val quotes = if (open == null) Strategies.sources(run.skuId).mapNotNull { src -> q(src)?.let { src to it } }.toMap() else emptyMap()
            val out = AgentEngine.tick(run, now, quotes, settle, s.day(run.track), s.caps, freeFor(s, run.track)) {
                "${run.track.take(1)}-" + UUID.randomUUID().toString().take(13)
            }
            runs[i] = out.run
            s = s.copy(runs = runs.toList(), days = s.days + (run.track to out.day))
            events += out.events
            out.opened?.let { opened += it }
            out.closed?.let { c ->
                val row = FeeLedger.planFee(
                    id = c.position.id,
                    agent = run.name,
                    tier = run.tier,
                    openedAt = c.position.openedAt,
                    closedAt = c.closedAt,
                    pnl = c.pnl,
                    paper = run.track == Track.PAPER,
                    covered = save.feeWindowCovers(c.position.openedAt),
                )
                agents.addFee(row)
                closed += c to row
                if (run.track == Track.PAPER) s = s.copy(paperPnl = AgentEngine.roundLamports(s.paperPnl + c.pnl))
            }
        }
        s = s.copy(log = (events.reversed() + s.log).take(DeskStore.LOG_KEEP), lastTickAt = now)
        store.write(s)
        TickReport(closed, opened, events)
    } }

    /** Owed fees (FREE tier, profit, outside a window) in one devnet transfer to the treasury, with a memo. */
    fun owedRows(): List<FeeRow> = agents.fees().filter { it.reason == FeeReason.UNSENT }

    /**
     * Pays every owed row in one devnet tx. The memo carries a ref of the row ids, and the attempt
     * is saved first: if the app dies while the wallet sends, [reconcile] finds the tx on chain and
     * marks the rows charged instead of letting the player pay twice.
     */
    suspend fun payFees(wallet: SolanaWallet, sender: ActivityResultSender): Result<SentTx> {
        if (wallet.mainnet) return Result.failure(DeskError(DeskError.Kind.DEVNET_ONLY))
        val prepared = withContext(Dispatchers.IO) {
            lock.withLock {
                reconcileLocked()?.let { return@withLock Result.success(it) }
                if (store.read().pendingPay != null) return@withLock Result.failure(DeskError(DeskError.Kind.PAYMENT_PENDING))
                val rows = owedRows()
                val lamports = rows.sumOf { SolarchikConfig.lamports(it.fee) }
                if (rows.isEmpty() || lamports <= 0) return@withLock Result.failure(DeskError(DeskError.Kind.NOTHING_OWED))
                val ids = rows.map { it.id }
                val pending = PendingPay(ids, payRef(ids), wallet.address, lamports, clock())
                store.write(store.read().copy(pendingPay = pending))
                Result.success(pending)
            }
        }
        val pending = prepared.getOrElse { return Result.failure(it) }
        if (pending !is PendingPay) return Result.success(pending as SentTx)
        val sent = wallet.signAndSend(sender) { payer, blockhash -> feeTx(payer, blockhash, pending.lamports, pending.ids.size, pending.ref) }
        withContext(Dispatchers.IO) {
            lock.withLock {
                val s = store.read()
                sent.onSuccess { tx ->
                    markPaid(pending.ids.toSet(), tx.signature)
                    store.write(s.copy(pendingPay = null))
                }.onFailure { e ->
                    // Declined or no wallet: nothing left the phone. Anything else may have landed.
                    val final = e is WalletError && (e.kind == WalletError.Kind.DECLINED || e.kind == WalletError.Kind.NO_WALLET)
                    if (final) store.write(s.copy(pendingPay = null))
                }
            }
        }
        return sent
    }

    /** Looks for an unresolved payment on chain. Returns the tx when it landed; clears stale attempts. */
    suspend fun reconcile(): SentTx? = withContext(Dispatchers.IO) { lock.withLock { reconcileLocked() } }

    private suspend fun reconcileLocked(): SentTx? {
        val p = store.read().pendingPay ?: return null
        val sigs = if (p.owner.isBlank()) emptyList() else runCatching { rpc.memoSignatures(p.owner, 30) }.getOrNull()
        val age = clock() - p.at
        if (sigs == null) {
            // Could not look (offline / RPC down): keep blocking a second payment, but not forever.
            if (age > PENDING_GIVE_UP_MS || age < 0) store.write(store.read().copy(pendingPay = null))
            return null
        }
        val hit = sigs.firstOrNull { it.ok && it.memo.contains("ref=${p.ref}") }
        if (hit != null) {
            markPaid(p.ids.toSet(), hit.signature)
            store.write(store.read().copy(pendingPay = null))
            return SentTx(p.owner, hit.signature, "devnet")
        }
        // Not on chain: a blockhash lives ~1-2 minutes, so after that the attempt can no longer land.
        if (age > PENDING_EXPIRES_MS || age < 0) store.write(store.read().copy(pendingPay = null))
        return null
    }

    private fun markPaid(ids: Set<String>, sig: String) {
        agents.updateFees { if (it.id in ids) FeeLedger.markCharged(it, sig) else it }
    }

    companion object {
        /** One desk per process: the open app and the background worker never tick at the same time. */
        val lock = Mutex()
        const val BALANCE_EVERY_MS = 5 * 60_000L
        /** After this an unconfirmed payment attempt is treated as dropped (blockhash expired). */
        const val PENDING_EXPIRES_MS = 3 * 60_000L
        /** Without any successful chain lookup, give up on the attempt after a day. */
        const val PENDING_GIVE_UP_MS = 24 * 3600_000L

        /** Short, stable reference for a set of fee rows (goes into the memo). */
        fun payRef(ids: List<String>): String {
            val d = MessageDigest.getInstance("SHA-256").digest(ids.sorted().joinToString(",").toByteArray())
            return d.take(5).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
        }

        fun feeTx(payer: PublicKey, blockhash: ByteArray, lamports: Long, count: Int, ref: String = ""): LegacyTx = LegacyTx.compile(
            payer,
            blockhash,
            listOf(
                SystemIx.transfer(payer, PublicKey(SolarchikConfig.TREASURY), lamports),
                MemoIx.memo(payer, "solarchik fees n=$count lamports=$lamports" + if (ref.isNotBlank()) " ref=$ref" else ""),
            ),
        )
    }
}
