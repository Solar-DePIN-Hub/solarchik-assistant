package net.solardepin.solarchik

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import net.solardepin.solarchik.autopilot.AutoAction
import net.solardepin.solarchik.autopilot.AutoKind
import net.solardepin.solarchik.autopilot.AutoRunner
import net.solardepin.solarchik.autopilot.AutopilotPlanner
import net.solardepin.solarchik.autopilot.AutopilotPolicy
import net.solardepin.solarchik.autopilot.AutopilotStore
import net.solardepin.solarchik.delegate.AgentKey
import net.solardepin.solarchik.delegate.DelegateBlock
import net.solardepin.solarchik.delegate.DelegateChainState
import net.solardepin.solarchik.delegate.DelegateDesk
import net.solardepin.solarchik.delegate.DelegateException
import net.solardepin.solarchik.delegate.DelegatePolicy
import net.solardepin.solarchik.delegate.DelegateRecord
import net.solardepin.solarchik.delegate.DelegateRules
import net.solardepin.solarchik.delegate.SplIx
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.solana.SystemIx
import net.solardepin.solarchik.swap.JupiterApi
import net.solardepin.solarchik.swap.SwapBuild
import net.solardepin.solarchik.swap.SwapPolicy
import net.solardepin.solarchik.swap.SwapQuote
import net.solardepin.solarchik.swap.SwapRecord
import net.solardepin.solarchik.swap.SwapStore
import net.solardepin.solarchik.swap.SwapToken
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.ui.SeasonScreen
import net.solardepin.solarchik.wallet.Base58
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.sol4k.Keypair
import org.sol4k.PublicKey
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

class AgentTestBox : AgentKey.Box {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun seal(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.let { it.iv + it.doFinal(plain) }
    override fun open(sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12))) }.doFinal(sealed.copyOfRange(12, sealed.size))
}

/**
 * 1.1.0 Season autopilot (planner variety, caps, notifications that only open a review) and the experimental
 * delegated limit (Approve/Revoke/pull/withdraw tx bytes, every limit, the agent swap flow with a scripted chain).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class AutoDelegateTest {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.1.0").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled
    private val realCluster = System.getProperty("solarchik.cluster")
    private val owner = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
    private val zone = ZoneId.of("Europe/Kyiv")
    private val hash = ByteArray(32) { 7 }

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { true }
        LocalKey.box = TestBox()
        AgentKey.box = AgentTestBox()
        System.setProperty("solarchik.cluster", "mainnet")
        listOf("seeker-wallet", "solarchik.swap", "solarchik.autopilot", "solarchik.delegate", "solarchik-agent-key", "solarchik.season", "solarchik-game", MainActivity.ASSISTANT_PREFS)
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }
    private fun connectAs(addr: String) = app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", addr).putString("auth", "t").commit()
    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }
    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun shot(root: View, name: String, scrollToTag: String? = null) {
        idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val scroll = findScroll(root)
        if (scroll != null && scrollToTag != null) {
            var y = 0; var cur: View? = find(scroll, scrollToTag)
            while (cur != null && cur !== scroll) { y += cur.top; cur = cur.parent as? View }
            scroll.scrollTo(0, (y - 160).coerceAtLeast(0))
        }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun res(name: String): String = javaClass.classLoader!!.getResource("mainnet/$name")!!.readText()

    // ------------------------------------------------------------------ autopilot planner

    @Test fun autopilotIsOffByDefaultAndPlansNothing() {
        val store = AutopilotStore(app)
        assertFalse(store.policy().enabled)
        assertTrue(AutoRunner.ensurePlan(app).isEmpty())
        assertTrue(store.actions().isEmpty())
    }

    @Test fun planIsInsideWakingHoursVariedAndCapped() {
        val pol = AutopilotPolicy(enabled = true, wakeFrom = 9, wakeTo = 22, perDay = 2, swapMaxLamports = 10_000_000L, swapDayCapLamports = 15_000_000L)
        var history = emptyList<AutoAction>()
        val start = LocalDate.of(2026, 10, 10)
        val rnd = Random(42)
        var swaps = 0
        for (d in 0 until 60) {
            val day = start.plusDays(d.toLong())
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val plan = AutopilotPlanner.plan(day, zone, pol, history, swapRoomLamports = 15_000_000L, checkedInToday = d % 3 == 0, notBefore = dayStart, rnd = rnd)
            assertTrue("1–2 actions", plan.size in 1..2)
            assertEquals("distinct kinds in a day", plan.size, plan.map { it.kind }.toSet().size)
            for (a in plan) {
                val h = java.time.Instant.ofEpochMilli(a.at).atZone(zone).hour
                assertTrue("waking hours: $h", h in 9..21)
                if (a.kind == AutoKind.SWAP) {
                    swaps++
                    assertTrue(a.lamports in AutopilotPolicy.MIN_SWAP_LAMPORTS..10_000_000L)
                    assertTrue(a.to in AutopilotPlanner.SWAP_TARGETS)
                    val recent = history.filter { it.kind == AutoKind.SWAP && it.day >= day.minusDays(14).toString() }
                    assertFalse("no identical swap within 14 days", recent.any { it.to == a.to && it.lamports == a.lamports })
                }
                if (d % 3 == 0) assertTrue("no check-in when already signed", a.kind != AutoKind.CHECKIN)
            }
            if (plan.size == 2) assertTrue("≥ 90 min apart", plan[1].at - plan[0].at >= AutopilotPolicy.MIN_GAP_MS)
            assertTrue("swap total ≤ room", plan.filter { it.kind == AutoKind.SWAP }.sumOf { it.lamports } <= 15_000_000L)
            history = history + plan
        }
        assertTrue("swaps do happen", swaps > 5)
        // the four kinds all appear over time
        assertEquals(AutoKind.entries.toSet(), history.map { it.kind }.toSet())
        // no swap without room; later than notBefore
        val day = start.plusDays(100)
        val late = day.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val p2 = AutopilotPlanner.plan(day, zone, pol.copy(perDay = 3), emptyList(), 0L, false, late, Random(1))
        assertTrue(p2.none { it.kind == AutoKind.SWAP })
        assertTrue(p2.all { it.at >= late })
        // too late in the day: nothing
        assertTrue(AutopilotPlanner.plan(day, zone, pol, emptyList(), 0L, false, day.atTime(21, 50).atZone(zone).toInstant().toEpochMilli(), Random(1)).isEmpty())
    }

    @Test fun autopilotCapsAreEditableAndClamped() {
        val c = AutopilotPolicy(enabled = true, perDay = 9, swapMaxLamports = 999_000_000L, swapDayCapLamports = 999_000_000L, wakeFrom = 2, wakeTo = 3).clamped()
        assertEquals(3, c.perDay)
        assertEquals(AutopilotPolicy.HARD_SWAP_DAY_LAMPORTS, c.swapDayCapLamports)
        assertTrue(c.swapMaxLamports <= c.swapDayCapLamports)
        assertEquals(5, c.wakeFrom); assertEquals(8, c.wakeTo)
        val store = AutopilotStore(app)
        store.setPolicy(AutopilotPolicy(enabled = true, perDay = 1, swapMaxLamports = 2_000_000L, swapDayCapLamports = 5_000_000L))
        assertEquals(1, store.policy().perDay)
        assertEquals(2_000_000L, store.policy().swapMaxLamports)
        // the room is the smaller of the autopilot cap (minus autopilot swaps today) and the swap cap room
        val recs = listOf(SwapRecord(0, "2026-10-10", "s", "SOL", "USDC", 3_000_000, 1, 0.003, SwapRecord.STATUS_CONFIRMED, "autopilot"),
            SwapRecord(0, "2026-10-10", "t", "SOL", "USDC", 9_000_000, 1, 0.009, SwapRecord.STATUS_FAILED, "autopilot"))
        assertEquals(2_000_000L, AutopilotPlanner.swapRoom(store.policy(), recs, "2026-10-10", 0.05, true))
        assertEquals(1_000_000L, AutopilotPlanner.swapRoom(store.policy(), recs, "2026-10-10", 0.001, true))
        assertEquals(0L, AutopilotPlanner.swapRoom(store.policy(), recs, "2026-10-10", 0.05, false))
    }

    private inner class FakeJup(val swapTx: (SwapQuote, String, String?, String?) -> SwapBuild = { _, _, _, _ -> parseSwap(res("swap_sol_usdc.json")) }) : JupiterApi() {
        var quotes = 0
        val builds = ArrayList<Triple<String, String?, String?>>()
        override suspend fun quote(from: SwapToken, to: SwapToken, amountRaw: Long, slippageBps: Int, maxAccounts: Int): SwapQuote {
            quotes++
            return parseQuote(res("quote_sol_usdc.json")).copy(inputMint = from.mint, outputMint = to.mint, inAmount = amountRaw, slippageBps = slippageBps)
        }
        override suspend fun swapTx(quote: SwapQuote, owner: String, destinationTokenAccount: String?, nativeDestination: String?): SwapBuild {
            builds += Triple(owner, destinationTokenAccount, nativeDestination)
            return swapTx.invoke(quote, owner, destinationTokenAccount, nativeDestination)
        }
    }

    @Test fun dueActionBecomesANotificationThatOnlyOpensTheReview() = runBlocking {
        val store = AutopilotStore(app)
        store.setPolicy(AutopilotPolicy(enabled = true))
        val now = System.currentTimeMillis()
        val day = LocalDate.now().toString()
        store.save(listOf(
            AutoAction("$day-0-swap", day, now - 60_000, AutoKind.SWAP, to = "USDC", lamports = 3_000_000),
            AutoAction("$day-1-dapp", day, now + 3_600_000, AutoKind.DAPP, dapp = "Orb"),
            AutoAction("old", LocalDate.now().minusDays(1).toString(), now - 30 * 3_600_000L, AutoKind.STAKING),
        ))
        val posted = ArrayList<Pair<String, Intent>>()
        val jup = FakeJup()
        val out = AutoRunner.tick(app, now, Random(3), jup, post = { _, t, b, i -> posted += "$t|$b" to i; true })
        assertEquals(listOf("notified:$day-0-swap"), out)
        assertEquals(1, posted.size)
        assertTrue("quote prepared in advance: ${posted[0].first}", posted[0].first.contains("0.003 SOL ≈ 0.") && posted[0].first.contains("USDC"))
        assertEquals("$day-0-swap", posted[0].second.getStringExtra(MainActivity.EXTRA_AUTOPILOT))
        assertEquals(1, jup.quotes)
        assertEquals("no transaction is ever built or signed in the background", 0, jup.builds.size)
        assertEquals(AutoAction.NOTIFIED, store.find("$day-0-swap")!!.status)
        assertTrue(store.find("$day-0-swap")!!.quotedOut > 0)
        assertEquals(AutoAction.PLANNED, store.find("$day-1-dapp")!!.status)
        assertEquals(AutoAction.MISSED, store.find("old")!!.status)
        // paused: nothing posted
        store.save(store.actions().map { if (it.id.endsWith("dapp")) it.copy(at = now - 1000) else it })
        store.setPolicy(store.policy().copy(paused = true))
        posted.clear()
        AutoRunner.tick(app, now, Random(3), jup, post = { _, t, b, i -> posted += "$t|$b" to i; true })
        assertTrue(posted.isEmpty())
    }

    @Test fun tappingADappActionOpensItAndMarksDone() {
        connectAs(owner)
        val store = AutopilotStore(app)
        store.setPolicy(AutopilotPolicy(enabled = true))
        val day = LocalDate.now().toString()
        store.save(listOf(AutoAction("$day-0-dapp", day, System.currentTimeMillis(), AutoKind.DAPP, dapp = "Loopscale", status = AutoAction.NOTIFIED)))
        val a = launch()
        a.openAutopilot("$day-0-dapp"); idle()
        assertEquals(AutoAction.DONE, store.find("$day-0-dapp")!!.status)
        assertEquals("Loopscale", net.solardepin.solarchik.season.SeasonStore.explored(app))
    }

    @Test fun seasonAutopilotCardOnPauseStop() {
        connectAs(owner)
        val a = launch()
        a.select(MainActivity.Tab.SEASON); idle()
        val d = a.window.decorView
        assertNotNull(find(d, "ap-card"))
        assertNotNull("off by default", find(d, "ap-on"))
        assertTrue(texts(d).any { it.contains("nothing here guarantees points") })
        val season = a.screen(MainActivity.Tab.SEASON) as SeasonScreen
        season.autopilot.turnOn(); season.render(); idle()
        assertNotNull(find(d, "ap-state"))
        assertNotNull(find(d, "ap-per-day")); assertNotNull(find(d, "ap-swap-max")); assertNotNull(find(d, "ap-swap-day"))
        assertTrue(texts(d).any { it.startsWith("Up to 2 actions a day") })
        shot(d, "12_season_autopilot", "ap-card")
        (find(d, "ap-pause") as View).performClick(); idle()
        assertTrue(AutopilotStore(app).policy().paused)
        assertTrue(texts(d).any { it == "Paused" })
        season.autopilot.stop(); idle()
        assertFalse(AutopilotStore(app).policy().enabled)
        assertTrue(AutopilotStore(app).actions().none { it.status == AutoAction.PLANNED || it.status == AutoAction.NOTIFIED })
    }

    // ------------------------------------------------------------------ delegated limit: transactions

    private fun ixs(tx: LegacyTx): List<Triple<String, List<String>, ByteArray>> {
        // re-parse the legacy message: header(3) keys blockhash ixs
        val m = tx.message
        var p = 3
        val nKeys = m[p].toInt(); p++
        val keys = (0 until nKeys).map { Base58.encode(m.copyOfRange(p + 32 * it, p + 32 * it + 32)) }
        p += 32 * nKeys + 32
        val n = m[p].toInt(); p++
        return (0 until n).map {
            val prog = keys[m[p].toInt()]; p++
            val na = m[p].toInt(); p++
            val accts = (0 until na).map { keys[m[p + it].toInt()] }; p += na
            val nd = m[p].toInt(); p++
            val data = m.copyOfRange(p, p + nd); p += nd
            Triple(prog, accts, data)
        }
    }

    private fun u64(b: ByteArray, at: Int): Long = (0 until 8).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xff) shl (8 * i)) }

    @Test fun approveRevokePullWithdrawTxBytes() {
        val user = PublicKey(owner)
        val agent = Keypair.fromSecretKey(ByteArray(32) { (it + 3).toByte() })
        val usdc = SwapTokens.USDC
        val userAta = SplIx.ata(user, PublicKey(usdc.mint)).toBase58()
        val agentAta = SplIx.ata(agent.publicKey, PublicKey(usdc.mint)).toBase58()

        val approve = DelegateRules.approveTx(user, usdc, agent.publicKey, 5_000_000L, 10_000_000L, hash)
        assertEquals("only the user signs the Approve", 1, approve.signerCount)
        val ai = ixs(approve)
        assertEquals(SplIx.TOKEN_PROGRAM.toBase58(), ai[0].first)
        assertEquals(listOf(userAta, usdc.mint, agent.publicKey.toBase58(), owner), ai[0].second)
        assertEquals(13, ai[0].third[0].toInt())
        assertEquals(5_000_000L, u64(ai[0].third, 1))
        assertEquals(6, ai[0].third[9].toInt())
        assertEquals("top-up to the agent", SystemIx.PROGRAM.toBase58(), ai[1].first)
        assertEquals(listOf(owner, agent.publicKey.toBase58()), ai[1].second)
        assertEquals(10_000_000L, u64(ai[1].third, 4))
        assertEquals(1, ixs(DelegateRules.approveTx(user, usdc, agent.publicKey, 1L, 0L, hash)).size)

        val revoke = ixs(DelegateRules.revokeTx(user, usdc, hash))
        assertEquals(1, revoke.size)
        assertEquals(listOf(userAta, owner), revoke[0].second)
        assertEquals(listOf<Byte>(5), revoke[0].third.toList())

        val pull = DelegateRules.pullTx(agent.publicKey, user, usdc, 1_000_000L, hash)
        assertEquals("the agent alone signs (as the approved delegate)", 1, pull.signerCount)
        val pi = ixs(pull)
        assertEquals(SplIx.ATA_PROGRAM.toBase58(), pi[0].first)
        assertEquals(agentAta, pi[0].second[1])
        assertEquals(12, pi[1].third[0].toInt())
        assertEquals(listOf(userAta, usdc.mint, agentAta, agent.publicKey.toBase58()), pi[1].second)
        assertEquals(1_000_000L, u64(pi[1].third, 1))
        // with the user's output account created by the agent
        assertEquals(3, ixs(DelegateRules.pullTx(agent.publicKey, user, usdc, 1_000_000L, hash, SwapTokens.JUP.mint)).size)

        val back = ixs(DelegateRules.returnTx(agent.publicKey, user, usdc, 1_000_000L, hash))
        assertEquals(listOf(agentAta, usdc.mint, userAta, agent.publicKey.toBase58()), back[0].second)

        val wd = DelegateRules.withdrawTx(agent.publicKey, user, 12_000_000L, listOf(usdc to 250_000L), hash)
        val wi = ixs(wd)
        assertEquals(3, wi.size)
        assertEquals(12, wi[0].third[0].toInt()); assertEquals(userAta, wi[0].second[2])
        assertEquals(9, wi[1].third[0].toInt()); assertEquals("rent back to the user", owner, wi[1].second[1])
        assertEquals(owner, wi[2].second[1]); assertEquals(12_000_000L - 5_000L, u64(wi[2].third, 4))
        // signed bytes verify
        val signed = pull.partialSign(agent)
        assertTrue(agent.publicKey.verify(signed.signature(0), signed.message))
    }

    @Test fun delegateLimitsAndClamps() {
        val usdc = SwapTokens.USDC
        val agent = "Agent1111111111111111111111111111111111111"
        val now = 1_760_000_000_000L
        val pol = DelegatePolicy(enabled = true, riskAcceptedAt = 1, allowanceRaw = 5_000_000, perActionRaw = 1_000_000, perDayRaw = 2_000_000, approvedAt = now - 86_400_000L, approvedRaw = 5_000_000)
        val chain = DelegateChainState("ata", true, 20_000_000, agent, 4_000_000, 8_000_000)
        val day = "2026-10-09"
        assertTrue(DelegateRules.check(pol, agent, chain, emptyList(), day, 1_000_000, now, true).isEmpty())
        fun b(p: DelegatePolicy = pol, c: DelegateChainState? = chain, recs: List<DelegateRecord> = emptyList(), amt: Long = 1_000_000, at: Long = now, mainnet: Boolean = true, ag: String? = agent) =
            DelegateRules.check(p, ag, c, recs, day, amt, at, mainnet)
        assertTrue(b(p = DelegatePolicy()).containsAll(listOf(DelegateBlock.OFF, DelegateBlock.RISK_NOT_ACCEPTED)))
        assertTrue(b(mainnet = false).contains(DelegateBlock.NOT_MAINNET))
        assertTrue(b(ag = null).contains(DelegateBlock.NO_AGENT))
        assertTrue(b(amt = 1_000_001).contains(DelegateBlock.OVER_ACTION_CAP))
        val spent = listOf(DelegateRecord(0, day, DelegateRecord.SWAP, 1_500_000, 1, "s", DelegateRecord.OK), DelegateRecord(0, day, DelegateRecord.SWAP, 900_000, 1, "f", DelegateRecord.FAILED))
        assertEquals(1_500_000L, DelegateRules.spentToday(spent, day))
        assertTrue(b(recs = spent).contains(DelegateBlock.OVER_DAY_CAP))
        assertTrue(b(recs = spent, amt = 500_000).isEmpty())
        assertTrue(b(c = chain.copy(delegatedRaw = 600_000)).contains(DelegateBlock.OVER_ALLOWANCE))
        assertTrue(b(c = chain.copy(delegate = "Someone")).contains(DelegateBlock.NOT_APPROVED))
        assertTrue(b(c = null).contains(DelegateBlock.NOT_APPROVED))
        assertTrue(b(c = chain.copy(balanceRaw = 10)).contains(DelegateBlock.OVER_BALANCE))
        assertTrue(b(c = chain.copy(agentLamports = 1_000_000)).contains(DelegateBlock.LOW_AGENT_SOL))
        assertTrue(b(at = now + 7 * 86_400_000L).contains(DelegateBlock.EXPIRED))
        assertTrue(b(amt = 0).contains(DelegateBlock.ZERO_AMOUNT))
        // clamps: action ≤ day ≤ allowance ≤ hard cap
        val c = DelegatePolicy(allowanceRaw = usdc.toRaw(9_999.0), perDayRaw = usdc.toRaw(9_999.0), perActionRaw = usdc.toRaw(9_999.0), expiryDays = 400).clamped()
        assertEquals(usdc.toRaw(500.0), c.allowanceRaw)
        assertEquals(c.allowanceRaw, c.perDayRaw)
        assertEquals(c.perDayRaw, c.perActionRaw)
        assertEquals(30, c.expiryDays)
        assertEquals("output never the same token", "SOL", DelegatePolicy(output = "USDC").clamped().output)
        // change / add: Approve always sets the total
        assertEquals(9_000_000L, DelegateRules.approveTotal(4_000_000L, 5_000_000L))
        assertEquals(5_000_000L, DelegateRules.approveTotal(-3L, 5_000_000L))
        // the varied daily amount: 60–100% of the per-action cap, in 0.01 steps, never above what is left
        val r = Random(9)
        repeat(50) {
            val amt = DelegateRules.autoAmount(pol, 2_000_000, 4_000_000, r)
            assertTrue(amt in 600_000L..1_000_000L)
            assertEquals(0L, amt % 10_000L)
        }
        assertTrue(DelegateRules.autoAmount(pol, 300_000, 4_000_000, r) in 180_000L..300_000L)
        assertEquals(0L, DelegateRules.autoAmount(pol, 50_000, 4_000_000, r))
    }

    @Test fun parsesTheOnChainAllowance() {
        val json = Json.parseToJsonElement("""{"context":{"slot":1},"value":{"lamports":2039280,"owner":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","data":{"program":"spl-token","parsed":{"type":"account","info":{"mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v","owner":"$owner","state":"initialized","tokenAmount":{"amount":"12345678","decimals":6},"delegate":"AgentX","delegatedAmount":{"amount":"4000000","decimals":6}}}}}}""")
        val s = DelegateRules.parseTokenAccount("ata", json, 9)
        assertEquals(12_345_678L, s.balanceRaw)
        assertEquals("AgentX", s.delegate)
        assertEquals(4_000_000L, s.remainingFor("AgentX"))
        assertEquals(0L, s.remainingFor("Other"))
        assertFalse(DelegateRules.parseTokenAccount("ata", Json.parseToJsonElement("""{"value":null}"""), 0).exists)
    }

    @Test fun agentSwapTxMustPayFromAgentAndPayOutToTheUser() {
        val jupTx = JupiterApi.parseSwap(res("swap_sol_usdc.json")).tx // fee payer = treasury
        val keys = DelegateRules.staticKeys(jupTx)
        assertEquals(owner, keys[0])
        assertNull(DelegateRules.swapTxProblem(jupTx, owner, keys[1]))
        assertNotNull(DelegateRules.swapTxProblem(jupTx, owner, "11111111111111111111111111111112"))
        assertNotNull(DelegateRules.swapTxProblem(jupTx, "So11111111111111111111111111111111111111112", keys[1]))
    }

    /** Scripted mainnet node for the agent flow: allowance, balances, blockhash, sends, confirmed statuses. */
    private class ChainRpc(val agent: String, var delegated: Long = 4_000_000L, var agentLamports: Long = 8_000_000L) : Rpc("test") {
        val sent = ArrayList<ByteArray>()
        override suspend fun call(method: String, params: JsonArray): JsonElement = Json.parseToJsonElement(when (method) {
            "getBalance" -> """{"context":{"slot":1},"value":${agentLamports + if (sent.size >= 2) 500_000L else 0L}}"""
            "getAccountInfo" -> {
                val addr = params[0].jsonPrimitive.content
                if (addr == SplIx.ata(PublicKey("8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"), PublicKey(SwapTokens.USDC.mint)).toBase58())
                    """{"value":{"data":{"parsed":{"info":{"tokenAmount":{"amount":"50000000"},"delegate":"$agent","delegatedAmount":{"amount":"$delegated"}}}}}}"""
                else """{"value":null}"""
            }
            "getLatestBlockhash" -> """{"context":{"slot":1},"value":{"blockhash":"${Base58.encode(ByteArray(32) { 5 })}","lastValidBlockHeight":9}}"""
            "sendTransaction" -> { sent += java.util.Base64.getDecoder().decode(params[0].jsonPrimitive.content); "\"sig${sent.size}\"" }
            "getSignatureStatuses" -> """{"value":[{"err":null,"confirmationStatus":"confirmed"}]}"""
            else -> "null"
        })
    }

    @Test fun agentSwapRunsTwoStepsWithinLimitsAndReturnsFundsOnFailure() = runBlocking {
        val agentAddr = AgentKey.create(app)
        val kp = AgentKey.keypair(app)!!
        val desk = DelegateDesk(app, today = { LocalDate.now().toString() })
        val rpc = ChainRpc(agentAddr)
        // off: nothing happens
        assertTrue((desk.runSwap(rpc, owner, 1_000_000, true).exceptionOrNull() as DelegateException).blocks.contains(DelegateBlock.OFF))
        desk.store.setPolicy(DelegatePolicy(enabled = true, riskAcceptedAt = 1, approvedAt = System.currentTimeMillis(), approvedRaw = 5_000_000))
        // a good Jupiter tx: paid and signed by the agent, output to the user's wallet (SOL)
        val good = FakeJup { _, payer, _, _ -> SwapBuild(LegacyTx.compile(PublicKey(payer), hash, listOf(SystemIx.transfer(PublicKey(payer), PublicKey(owner), 1))).serialize(), 1, 1, null) }
        val ok = DelegateDesk(app, good).runSwap(rpc, owner, 1_000_000, true).getOrThrow()
        assertEquals(DelegateRecord.SWAP, ok.kind)
        assertEquals("pull, swap, forward the SOL that arrived", 3, rpc.sent.size)
        assertEquals("pull tx: the agent signed", agentAddr, Base58.encode(rpc.sent[0].copyOfRange(1 + 64 + 4, 1 + 64 + 4 + 32)))
        assertTrue(kp.publicKey.verify(rpc.sent[0].copyOfRange(1, 65), rpc.sent[0].copyOfRange(65, rpc.sent[0].size)))
        assertEquals("v1 has no native destination: SOL output to the agent", Triple(agentAddr, null, null), good.builds.single())
        val fwd = desk.store.records().first { it.kind == DelegateRecord.DELIVER }
        assertEquals(500_000L, fwd.amountRaw)
        assertTrue(ok.note.contains("forwarded"))
        // SPL output goes straight to the user's token account
        desk.store.setPolicy(desk.store.policy().copy(output = "JUP"))
        val jupOut = FakeJup { _, payer, dest, _ -> SwapBuild(LegacyTx.compile(PublicKey(payer), hash, listOf(SystemIx.transfer(PublicKey(payer), PublicKey(dest!!), 1))).serialize(), 1, 1, null) }
        DelegateDesk(app, jupOut).runSwap(rpc, owner, 1_000_000, true).getOrThrow()
        assertEquals(SplIx.ata(PublicKey(owner), PublicKey(SwapTokens.JUP.mint)).toBase58(), jupOut.builds.single().second)
        assertEquals(5, rpc.sent.size)
        // the daily cap counts both: 1.0 + 1.0 = 2.0, a third is refused before anything is signed
        val over = DelegateDesk(app, good).runSwap(rpc, owner, 500_000, true)
        assertTrue((over.exceptionOrNull() as DelegateException).blocks.contains(DelegateBlock.OVER_DAY_CAP))
        assertEquals(5, rpc.sent.size)
        // a bad Jupiter tx (someone else pays): swap refused, the pulled tokens go straight back
        desk.store.setPolicy(desk.store.policy().copy(perDayRaw = 5_000_000, output = "SOL"))
        val bad = FakeJup()
        val r = DelegateDesk(app, bad).runSwap(rpc, owner, 1_000_000, true)
        assertTrue((r.exceptionOrNull() as DelegateException).blocks.contains(DelegateBlock.BAD_TX))
        assertEquals("pull + return", 7, rpc.sent.size)
        assertEquals(DelegateRecord.RETURN, desk.store.records().last().kind)
        assertEquals(DelegateRecord.OK, desk.store.records().last().status)
        // allowance on chain smaller than the amount: refused before anything is signed
        rpc.delegated = 100_000
        val small = DelegateDesk(app, good).runSwap(rpc, owner, 1_000_000, true)
        assertTrue((small.exceptionOrNull() as DelegateException).blocks.contains(DelegateBlock.OVER_ALLOWANCE))
        assertEquals(7, rpc.sent.size)
        // withdraw: everything back to the user
        rpc.delegated = 4_000_000
        val sig = DelegateDesk(app, good).withdraw(rpc, owner).getOrThrow()
        assertEquals("sig8", sig)
    }

    @Test fun delegatedPanelWarnsShowsCapsAndRevoke() {
        connectAs(owner)
        val a = launch()
        a.select(MainActivity.Tab.AGENTS); idle()
        val agents = a.screen(MainActivity.Tab.AGENTS) as AgentsScreen
        agents.openSection(AgentsScreen.SEASON); idle()
        val d = a.window.decorView
        assertNotNull(find(d, "dlg-head"))
        assertNotNull(find(d, "dlg-warning"))
        assertNotNull("off by default", find(d, "dlg-enable"))
        assertTrue(texts(d).any { it.contains("Seeker Season may NOT count them") })
        agents.delegatePanel.enableForTest(); idle()
        assertTrue(AgentKey.exists(app))
        for (t in listOf("dlg-status", "dlg-remaining", "dlg-approve", "dlg-amounts", "dlg-topups", "dlg-per-action", "dlg-per-day", "dlg-expiry-days", "dlg-output", "dlg-auto", "dlg-run", "dlg-revoke", "dlg-withdraw", "dlg-season"))
            assertNotNull(t, find(d, t))
        assertTrue(texts(d).any { it.trim().endsWith("Approve 5 USDC in wallet") })
        // edit caps in the UI
        ((find(d, "dlg-per-action") as ViewGroup).getChildAt(0)).performClick(); idle()
        assertEquals(200_000L, DelegateDesk(app).store.policy().perActionRaw)
        ((find(d, "dlg-per-day") as ViewGroup).getChildAt(2)).performClick(); idle()
        assertEquals(4_000_000L, DelegateDesk(app).store.policy().perDayRaw)
        assertTrue(texts(d).any { it == "Now: ≤ 0.2 USDC per action, ≤ 4 USDC a day." })
        // after an approval: change / add, expiry, chain remaining
        DelegateDesk(app).store.setPolicy(DelegateDesk(app).store.policy().copy(approvedAt = System.currentTimeMillis(), approvedRaw = 5_000_000))
        agents.delegatePanel.chain = DelegateChainState("ata", true, 30_000_000, AgentKey.address(app), 3_500_000, 9_000_000)
        agents.render(); idle()
        assertTrue(texts(d).any { it == "Remaining allowance: 3.5 USDC" })
        assertNotNull(find(d, "dlg-add"))
        assertTrue(texts(d).any { it.trim().endsWith("Set allowance to 5 USDC in wallet") })
        assertNotNull(find(d, "dlg-expiry"))
        shot(d, "13_delegated_limit", "dlg-head")
        shot(d, "14_delegated_caps", "dlg-caps")
        // turning off keeps Revoke visible (the allowance stays on chain until revoked)
        (find(d, "dlg-off") as View).performClick(); idle()
        assertNotNull(find(d, "dlg-revoke"))
    }

    @Test fun oversizeJupiterTxIsRefusedAndReQuotedSmaller() = runBlocking {
        val tx = JupiterApi.parseSwap(res("swap_sol_usdc.json")).tx
        assertNull(net.solardepin.solarchik.swap.TxCheck.problem(tx, owner))
        val big = tx + ByteArray(1300 - tx.size)
        assertTrue(net.solardepin.solarchik.swap.TxCheck.problem(big, owner)!!.contains("too large"))
        // the swap desk asks Jupiter for a smaller route when the first build does not fit
        connectAs(owner)
        val maxes = ArrayList<Int>()
        val jup = object : JupiterApi() {
            override suspend fun quote(from: SwapToken, to: SwapToken, amountRaw: Long, slippageBps: Int, maxAccounts: Int): SwapQuote {
                maxes += maxAccounts
                return parseQuote(res("quote_sol_usdc.json")).copy(inputMint = from.mint, outputMint = to.mint, inAmount = amountRaw, slippageBps = slippageBps)
            }
            override suspend fun swapTx(quote: SwapQuote, owner: String, destinationTokenAccount: String?, nativeDestination: String?): SwapBuild =
                if (maxes.last() == JupiterApi.DEFAULT_MAX_ACCOUNTS) SwapBuild(big, 1, 1, null) else parseSwap(res("swap_sol_usdc.json"))
        }
        val desk = net.solardepin.solarchik.swap.SwapDesk(app, jup)
        desk.store.setPolicy(SwapPolicy(true, 1L))
        val rpc = object : Rpc("test") {
            override suspend fun call(method: String, params: JsonArray): JsonElement = Json.parseToJsonElement("""{"context":{"slot":1},"value":[]}""")
        }
        val p = desk.prepare(SolanaWallet(app), net.solardepin.solarchik.swap.SwapRequest(SwapTokens.SOL, SwapTokens.JUP, 1_000_000), rpc).getOrThrow()
        assertEquals(listOf(JupiterApi.DEFAULT_MAX_ACCOUNTS, JupiterApi.SMALL_MAX_ACCOUNTS), maxes)
        assertTrue(p.build.tx.size <= net.solardepin.solarchik.swap.TxCheck.MAX_TX_BYTES)
    }
}
