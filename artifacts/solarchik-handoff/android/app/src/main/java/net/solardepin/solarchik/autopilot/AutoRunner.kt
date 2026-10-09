package net.solardepin.solarchik.autopilot

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.delegate.DelegateDesk
import net.solardepin.solarchik.delegate.DelegateRules
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.notify.Notes
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.swap.JupiterApi
import net.solardepin.solarchik.swap.SwapGuard
import net.solardepin.solarchik.swap.SwapStore
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.ui.Fmt
import net.solardepin.solarchik.wallet.SolanaWallet
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Background side of the Season autopilot, the delegated daily action, the Saver and the Watcher. WorkManager wakes it about every
 * 15 minutes while either is on. Autopilot: plan the day, post due actions as notifications (the user taps, the
 * wallet signs). Delegated mode: the one small daily agent swap and the revoke reminder.
 */
object AutoRunner {
    const val WORK = "solarchik-season-auto"
    const val NOTE_BASE = 300

    fun sync(ctx: Context) {
        val on = AutopilotStore(ctx).policy().active || net.solardepin.solarchik.delegate.DelegateStore(ctx).policy().let { it.live && (it.autoDaily || it.approvedAt > 0L) } ||
            net.solardepin.solarchik.agents.SaverStore(ctx).policy().enabled || net.solardepin.solarchik.agents.WatcherStore(ctx).policy().enabled
        val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull() ?: return
        if (on) wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<AutoWorker>(15, TimeUnit.MINUTES).build())
        else wm.cancelUniqueWork(WORK)
    }

    /** Makes today's plan if there is none yet. Returns today's actions. */
    fun ensurePlan(ctx: Context, now: Long = System.currentTimeMillis(), rnd: Random = Random.Default, zone: ZoneId = ZoneId.systemDefault()): List<AutoAction> {
        val store = AutopilotStore(ctx)
        val pol = store.policy()
        val day = LocalDate.now(zone)
        val all = store.actions()
        val todays = all.filter { it.day == day.toString() }
        if (!pol.active || todays.isNotEmpty()) return todays
        val swaps = SwapStore(ctx)
        val sp = swaps.policy()
        val room = AutopilotPlanner.swapRoom(pol, swaps.records(), day.toString(), SwapGuard.remaining(sp, swaps.records(), day.toString()), sp.live && SolanaWallet(ctx).mainnet)
        val plan = AutopilotPlanner.plan(day, zone, pol, all, room, GameSave(ctx).signedToday(), now + 10 * 60_000L, rnd)
        store.save(all + plan)
        return plan
    }

    /** One background tick. [post] shows a notification (tests capture it). */
    suspend fun tick(
        ctx: Context,
        now: Long = System.currentTimeMillis(),
        rnd: Random = Random.Default,
        jup: JupiterApi = JupiterApi(),
        post: (Int, String, String, Intent) -> Boolean = { id, t, b, i -> notify(ctx, id, t, b, i) },
        delegateDesk: DelegateDesk? = null,
        rpc: Rpc? = null,
    ): List<String> {
        val out = ArrayList<String>()
        val store = AutopilotStore(ctx)
        val pol = store.policy()
        if (pol.active) {
            ensurePlan(ctx, now, rnd)
            val save = GameSave(ctx)
            store.actions().filter { it.kind == AutoKind.CHECKIN && it.day == LocalDate.now().toString() && it.status != AutoAction.DONE && save.signedToday() }
                .forEach { store.setStatus(it.id, AutoAction.DONE) }
            AutopilotPlanner.stale(store.actions(), now).forEach { store.setStatus(it.id, AutoAction.MISSED) }
            for (a in AutopilotPlanner.due(store.actions(), now)) {
                var quoted = 0L
                if (a.kind == AutoKind.SWAP) {
                    val to = SwapTokens.bySymbol(a.to) ?: continue
                    quoted = runCatching { jup.quote(SwapTokens.SOL, to, a.lamports, SwapStore(ctx).policy().maxSlippageBps).outAmount }.getOrDefault(0L)
                }
                val (title, body) = text(ctx, a, quoted)
                val open = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_AUTOPILOT, a.id)
                }
                if (post(NOTE_BASE + Math.floorMod(a.id.hashCode(), 50), title, body, open)) {
                    store.update(a.id) { it.copy(status = AutoAction.NOTIFIED, quotedOut = quoted) }
                    out += "notified:${a.id}"
                }
            }
        }
        out += delegateTick(ctx, now, rnd, post, delegateDesk, rpc)
        // 1.1.0 Saver (proposes saves) and Watcher (alerts only); neither ever signs.
        out += runCatching { net.solardepin.solarchik.agents.AgentTicks.saver(ctx, now, post, jup) }.getOrDefault(emptyList())
        out += runCatching { net.solardepin.solarchik.agents.AgentTicks.watcher(ctx, now, post, rpc = rpc) }.getOrDefault(emptyList())
        return out
    }

    private suspend fun delegateTick(ctx: Context, now: Long, rnd: Random, post: (Int, String, String, Intent) -> Boolean, desk0: DelegateDesk?, rpc0: Rpc?): List<String> {
        val desk = desk0 ?: DelegateDesk(ctx)
        val pol = desk.store.policy()
        val wallet = SolanaWallet(ctx)
        val owner = wallet.address
        if (!pol.live || pol.approvedAt <= 0L || owner.isBlank() || !wallet.mainnet || wallet.isLocal) return emptyList()
        val out = ArrayList<String>()
        val today = LocalDate.now().toString()
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_AUTOPILOT, "delegate")
        }
        if (now > pol.clamped().expiresAt() && desk.store.reminderDay != today) {
            desk.store.reminderDay = today
            if (post(NOTE_BASE + 60, ctx.getString(R.string.dlg_note_expired_title), ctx.getString(R.string.dlg_note_expired_body), open)) out += "reminder"
            return out
        }
        if (!pol.autoDaily || desk.store.doneDay == today) return out
        if (desk.store.autoDay != today) {
            val ap = AutopilotStore(ctx).policy()
            val d = LocalDate.now()
            val z = ZoneId.systemDefault()
            val from = maxOf(now + 5 * 60_000L, d.atTime(ap.wakeFrom, 0).atZone(z).toInstant().toEpochMilli())
            val end = d.atTime(minOf(ap.wakeTo, 23), 0).atZone(z).toInstant().toEpochMilli()
            desk.store.autoAt = if (end > from) AutopilotPlanner.times(from, end, 1, rnd).first() else Long.MAX_VALUE
            desk.store.autoDay = today
        }
        if (now < desk.store.autoAt) return out
        desk.store.doneDay = today
        val rpc = rpc0 ?: wallet.rpc
        val chain = runCatching { desk.chainState(rpc, owner) }.getOrNull() ?: return out
        val agent = desk.agentAddress().orEmpty()
        val amount = DelegateRules.autoAmount(pol, DelegateRules.remainingToday(pol, desk.store.records(), today), chain.remainingFor(agent), rnd)
        if (amount <= 0L) return out
        val r = desk.runSwap(rpc, owner, amount, wallet.mainnet)
        val t = pol.tokenInfo
        val body = r.fold(
            { ctx.getString(R.string.dlg_note_done_body, Fmt.sol(t.fromRaw(amount), 2) + " " + t.symbol, pol.outputInfo.symbol) },
            { ctx.getString(R.string.dlg_note_fail_body, it.message.orEmpty().take(100)) },
        )
        if (post(NOTE_BASE + 61, ctx.getString(R.string.dlg_note_title), body, open)) out += if (r.isSuccess) "delegate:ok" else "delegate:fail"
        return out
    }

    fun text(ctx: Context, a: AutoAction, quoted: Long): Pair<String, String> = when (a.kind) {
        AutoKind.SWAP -> {
            val to = SwapTokens.bySymbol(a.to)
            val sol = Fmt.sol(a.lamports / 1e9, 4)
            val q = if (quoted > 0 && to != null) " ≈ " + Fmt.sol(to.fromRaw(quoted), 4) + " " + to.symbol else " → " + a.to
            ctx.getString(R.string.ap_note_swap_title) to ctx.getString(R.string.ap_note_swap_body, "$sol SOL$q")
        }
        AutoKind.CHECKIN -> ctx.getString(R.string.ap_note_checkin_title) to ctx.getString(R.string.ap_note_checkin_body)
        AutoKind.DAPP -> ctx.getString(R.string.ap_note_dapp_title) to ctx.getString(R.string.ap_note_dapp_body, a.dapp)
        AutoKind.STAKING -> ctx.getString(R.string.ap_note_stake_title) to ctx.getString(R.string.ap_note_stake_body)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun notify(ctx: Context, id: Int, title: String, body: String, open: Intent): Boolean {
        if (!Notes.allowed(ctx)) return false
        Notes.createChannel(ctx)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, Notes.CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(0xFFF5C542.toInt())
            .build()
        return try { NotificationManagerCompat.from(ctx).notify(id, n); true } catch (_: SecurityException) { false }
    }
}

class AutoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { AutoRunner.tick(applicationContext) }
        return Result.success()
    }
}
