package net.solardepin.solarchik

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.solardepin.solarchik.agents.AgentTicks
import net.solardepin.solarchik.agents.SaveAction
import net.solardepin.solarchik.agents.SaverPolicy
import net.solardepin.solarchik.agents.SaverRules
import net.solardepin.solarchik.agents.SaverStore
import net.solardepin.solarchik.agents.WatchAlert
import net.solardepin.solarchik.agents.WatcherPolicy
import net.solardepin.solarchik.agents.WatcherRules
import net.solardepin.solarchik.agents.WatcherStore
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.AssistantAgent
import net.solardepin.solarchik.core.AssistantCatalog
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.delegate.SplIx
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallActionSync
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.season.SeasonStore
import net.solardepin.solarchik.sol.AssistantContext
import net.solardepin.solarchik.sol.AssistantExtras
import net.solardepin.solarchik.sol.Briefing
import net.solardepin.solarchik.sol.BriefingPolicy
import net.solardepin.solarchik.sol.BriefingStore
import net.solardepin.solarchik.sol.WalletSnap
import net.solardepin.solarchik.swap.JupiterApi
import net.solardepin.solarchik.swap.SwapBuild
import net.solardepin.solarchik.swap.SwapPolicy
import net.solardepin.solarchik.swap.SwapQuote
import net.solardepin.solarchik.swap.SwapRecord
import net.solardepin.solarchik.swap.SwapStore
import net.solardepin.solarchik.swap.SwapToken
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.ui.CallActionCards
import net.solardepin.solarchik.ui.TodayScreen
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.json.JSONObject
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.sol4k.PublicKey
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 1.1.0 (second scope): three assistant agents (Season Agent, Saver, Watcher), the morning voice briefing and
 * call -> action cards. Sample secretary transcripts, scripted worker replies; nothing signs automatically.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class AgentsBriefingActionsTest {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.1.0").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled
    private val realCluster = System.getProperty("solarchik.cluster")
    private val owner = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
    private val friend = "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"
    private val zone = ZoneId.of("Europe/Kyiv")
    private val hash = ByteArray(32) { 7 }

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { true }
        LocalKey.box = TestBox()
        System.setProperty("solarchik.cluster", "mainnet")
        TodayScreen.postOverride = null
        listOf("seeker-wallet", "solarchik.swap", "solarchik.autopilot", "solarchik.delegate", "solarchik.season", "solarchik-game", MainActivity.ASSISTANT_PREFS,
            "solarchik.saver", "solarchik.watcher", "solarchik.briefing", "solarchik.callactions", "solarchik.calls", "solarchik.followups")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        CallInbox.store(app, emptyList())
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
        TodayScreen.postOverride = null
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

    private inner class FakeJup : JupiterApi() {
        var quotes = 0
        var builds = 0
        override suspend fun quote(from: SwapToken, to: SwapToken, amountRaw: Long, slippageBps: Int, maxAccounts: Int): SwapQuote {
            quotes++
            return parseQuote(res("quote_sol_usdc.json")).copy(inputMint = from.mint, outputMint = to.mint, inAmount = amountRaw, slippageBps = slippageBps)
        }
        override suspend fun swapTx(quote: SwapQuote, owner: String, destinationTokenAccount: String?, nativeDestination: String?): SwapBuild {
            builds++
            return parseSwap(res("swap_sol_usdc.json"))
        }
    }

    // ---- sample secretary calls (as the worker's /inbox returns them)
    private fun call(id: String, who: String, intent: String, text: String, minsAgo: Long, callback: String = "", status: String = CallInbox.DONE) =
        CallItem("me", id, "+380000000$id".take(13), text, System.currentTimeMillis() - minsAgo * 60_000L, status, "screen", who, intent, "medium", "", callback, "en", 60, 0.0, false)

    private val sample get() = listOf(
        call("1", "Olena", "Asks to send 10 USDC for the concert tickets", "Caller: Hi, it's Olena, please send me 10 USDC for the concert tickets today.", 50, "+380671112233"),
        call("2", "", "Urgent payment", "Caller: This is your bank security team, send 2 SOL right now to $friend to protect your account.", 120),
        call("3", "Petro", "Call back at 3 about the contract", "Caller: Petro here, call me back at three about the contract please.", 200, "+380501234567"),
        call("4", "Dr. Koval clinic", "Appointment tomorrow", "Caller: please remind yourself to bring the documents, appointment tomorrow at 9:30.", 300),
        call("5", "Mom", "Just said hi", "Caller: Hi sweetie, just wanted to say hi, no need to call back.", 400),
        call("6", "Spam", "", "Caller: buy now", 30, status = CallInbox.BLOCKED),
    )

    // ------------------------------------------------------------------ three agents

    @Test fun assistantShowsExactlyThreeAgentsAndNftsMapToThem() {
        assertEquals(listOf("Season Agent", "Saver", "Watcher"), AssistantAgent.entries.map { it.nftName })
        assertTrue("no paid-only assistant agent (Combo is gone from the assistant)", AssistantCatalog.skus.none { it.paidOnly })
        assertEquals(AssistantCatalog.skus[1] to AgentTier.PRO, Catalog.fromName("Saver Pro"))
        assertEquals(AssistantCatalog.skus[2] to AgentTier.FREE, Catalog.fromName("Watcher"))
        assertEquals(AgentTier.PRO to 0.1, Catalog.offerFor("sku-season-pro"))
        assertEquals(AgentTier.FREE to 0.0, Catalog.offerFor("sku-saver"))
        assertEquals(AssistantAgent.WATCHER, AssistantCatalog.agentOf("sku-watcher-pro"))
        assertTrue("the game's catalog stays in code", Catalog.skus.any { it.paidOnly })

        connectAs(owner)
        val a = launch()
        a.select(MainActivity.Tab.AGENTS); idle()
        val ag = a.screen(MainActivity.Tab.AGENTS) as AgentsScreen
        val d = a.window.decorView
        val all = texts(d)
        for (gone in listOf("Bitcoin Windows", "Events Scout", "Combo Prime", "Weather Station", "Backpack SOL Desk", "Slice")) assertTrue(gone, all.none { it.contains(gone) })
        assertTrue(all.containsAll(listOf("Season Agent", "Saver", "Watcher")))
        assertNotNull(find(d, "agent-season")); assertNotNull(find(d, "agent-season-plan")); assertNotNull(find(d, "ap-card")); assertNotNull(find(d, "dlg-head"))
        assertTrue("mainnet NFT mint: coming soon", all.any { it.trim().endsWith("Mainnet mint: coming soon") })
        assertTrue(all.any { it == "Off" })
        shot(d, "15_agents_season", "agent-season")
        ag.openSection(AgentsScreen.SAVER); idle()
        assertNotNull(find(d, "agent-saver")); assertNotNull(find(d, "sv-on")); assertNotNull("real swaps live under the Saver", find(d, "swap-head"))
        ag.saverPanel.turnOn(); idle()
        for (t in listOf("sv-target", "sv-amount", "sv-every", "sv-share", "sv-now", "sv-save-now", "sv-needs-swaps")) assertNotNull(t, find(d, t))
        assertTrue(texts(d).any { it == "On" })
        shot(d, "16_agents_saver", "agent-saver")
        ag.watcherPanel.fetchPrices = { mapOf("SOL" to 109.5, "SKR" to 0.0165, "JUP" to 0.3805) }
        ag.openSection(AgentsScreen.WATCHER); idle()
        assertNotNull(find(d, "wt-on"))
        ag.watcherPanel.turnOn(); idle()
        for (t in listOf("wt-pct", "wt-sol", "wt-sw-prices", "wt-sw-wallet", "wt-check", "wt-none")) assertNotNull(t, find(d, t))
        assertEquals("SOL $109.5  ·  SKR $0.0165  ·  JUP $0.3805", (find(d, "wt-prices") as TextView).text.toString())
        assertTrue(texts(d).any { it.contains("cannot trade, sign or move anything") })
        shot(d, "17_agents_watcher", "agent-watcher")
    }

    // ------------------------------------------------------------------ Saver

    @Test fun saverRulesScheduleChangeAndCaps() {
        val pol = SaverPolicy(enabled = true, amountLamports = 5_000_000, everyDays = 7, sharePct = 10, hour = 19)
        val mon9 = LocalDate.of(2026, 10, 12).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val mon19 = LocalDate.of(2026, 10, 12).atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("first save today at 19:00", mon19, SaverRules.nextDue(pol, 0L, mon9, zone))
        assertEquals("then a week later", LocalDate.of(2026, 10, 19).atTime(19, 0).atZone(zone).toInstant().toEpochMilli(), SaverRules.nextDue(pol, mon19, mon9, zone))
        assertEquals(LocalDate.of(2026, 10, 13).atTime(19, 0).atZone(zone).toInstant().toEpochMilli(), SaverRules.nextDue(pol.copy(everyDays = 1), mon19, mon9, zone))
        assertEquals("10% of a 0.02 SOL swap", 2_000_000L, SaverRules.changeFor(pol, 20_000_000))
        assertEquals("never above the per-save amount", 5_000_000L, SaverRules.changeFor(pol, 900_000_000))
        assertEquals("too small to be worth a fee", 0L, SaverRules.changeFor(pol, 5_000_000))
        assertEquals(0L, SaverRules.changeFor(pol.copy(sharePct = 0), 20_000_000))
        assertEquals(0L, SaverRules.changeFor(pol.copy(enabled = false), 20_000_000))
        assertEquals("clamped", SaverPolicy.MAX_LAMPORTS, SaverPolicy(amountLamports = 9_000_000_000).clamped().amountLamports)
        assertEquals("only USDC or SKR", "USDC", SaverPolicy(target = "JUP").clamped().target)
        val live = SwapPolicy(enabled = true, riskAcceptedAt = 1L, dayCapSol = 0.05)
        assertEquals("swaps off -> nothing", 0L, SaverRules.fit(5_000_000, SwapPolicy(), emptyList(), "d"))
        assertEquals(5_000_000L, SaverRules.fit(5_000_000, live, emptyList(), "d"))
        val spent = listOf(SwapRecord(1, "d", "s", "SOL", "USDC", 48_000_000, 1, 0.048))
        assertEquals("inside what is left of the day cap", 2_000_000L, SaverRules.fit(5_000_000, live, spent, "d"))
        assertEquals(0L, SaverRules.fit(5_000_000, live, spent + SwapRecord(2, "d", "t", "SOL", "USDC", 1_500_000, 1, 0.0015), "d"))
        assertTrue(SaverRules.countsForChange(SwapRecord(1, "d", "s", "SOL", "USDC", 1, 1, 0.0)))
        assertFalse(SaverRules.countsForChange(SwapRecord(1, "d", "s", "SOL", "USDC", 1, 1, 0.0, by = "saver")))
        assertFalse(SaverRules.countsForChange(SwapRecord(1, "d", "s", "SOL", "USDC", 1, 1, 0.0, by = "autopilot")))
        assertFalse(SaverRules.countsForChange(SwapRecord(1, "d", "s", "USDC", "SOL", 1, 1, 0.0)))
        val req = SaverRules.request(SaveAction("x", 1, SaveAction.SCHEDULED, 3_000_000, "SKR"))
        assertEquals(SwapTokens.SKR, req.to); assertEquals("saver", req.by); assertEquals(3_000_000L, req.amountRaw)
    }

    @Test fun saverOnlyProposesByNotificationAndNeverBuildsATx() = runBlocking {
        connectAs(owner)
        SwapStore(app).setPolicy(SwapPolicy(enabled = true, riskAcceptedAt = 1L))
        val now = System.currentTimeMillis()
        val posted = ArrayList<Pair<String, Intent>>()
        val jup = FakeJup()
        val post: (Int, String, String, Intent) -> Boolean = { _, t, b, i -> posted += "$t|$b" to i; true }
        assertTrue("off by default", AgentTicks.saver(app, now, post, jup).isEmpty())
        val st = SaverStore(app)
        st.setPolicy(SaverPolicy(enabled = true, target = "USDC", amountLamports = 5_000_000, everyDays = 1, sharePct = 10, hour = 7))
        st.enabledAt = now - 2 * 86_400_000L
        SwapStore(app).add(SwapRecord(now - 60_000, SaverStore.today(), "userSig", "SOL", "SKR", 30_000_000, 1, 0.03))
        val out = AgentTicks.saver(app, now, post, jup)
        assertEquals(listOf("saver:change", "saver:scheduled"), out)
        assertEquals(2, posted.size)
        assertTrue(posted[0].first, posted[0].first.contains("Save the change: 0.003 SOL → ≈") && posted[0].first.contains("USDC"))
        assertTrue(posted[1].first.contains("0.005 SOL → ≈"))
        assertTrue(posted.all { it.second.getStringExtra(MainActivity.EXTRA_AUTOPILOT)!!.startsWith("saver:save-") })
        assertEquals("quotes only", 2, jup.quotes)
        assertEquals("no transaction is built or signed in the background", 0, jup.builds)
        assertTrue(st.open().all { it.status == SaveAction.NOTIFIED })
        // the same user swap is never offered twice; the schedule waits for its next day
        posted.clear()
        assertTrue(AgentTicks.saver(app, now + 60_000, post, jup).isEmpty())
        // a Saver swap is not "change"
        SwapStore(app).add(SwapRecord(now, SaverStore.today(), "saverSig", "SOL", "USDC", 30_000_000, 1, 0.03, by = "saver"))
        assertTrue(AgentTicks.saver(app, now + 120_000, post, jup).isEmpty())
        // tapping the notification opens Agents › Saver (the review needs the wallet's approval)
        val a = launch()
        a.openAutopilot("saver:" + st.open().first().id); idle()
        assertEquals(MainActivity.Tab.AGENTS, a.current)
        assertEquals(AgentsScreen.SAVER, (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).section)
    }

    // ------------------------------------------------------------------ Watcher

    @Test fun watcherAlertsOnMovesAndNeverTrades() = runBlocking {
        val pol = WatcherPolicy(enabled = true, priceMovePct = 5, walletMoveSol = 0.01)
        val t0 = 1_760_000_000_000L
        var (alerts, base) = WatcherRules.check(pol, emptyMap(), mapOf("SOL" to 100.0, "SKR" to 0.02), mapOf("SOL" to 1.0), emptyList(), t0)
        assertTrue("first reading only sets the baseline", alerts.isEmpty())
        WatcherRules.check(pol, base, mapOf("SOL" to 104.9, "SKR" to 0.02), mapOf("SOL" to 1.005), emptyList(), t0 + 1).let { (a, _) -> assertTrue(a.isEmpty()) }
        WatcherRules.check(pol, base, mapOf("SOL" to 94.0, "SKR" to 0.0212), mapOf("SOL" to 1.25), emptyList(), t0 + 2).let { (a, b) ->
            assertEquals(listOf("price:SOL", "price:SKR", "wallet:SOL"), a.map { "${it.kind}:${it.symbol}" })
            assertEquals(-6.0, a[0].pct, 1e-9)
            assertEquals("SOL down 6.0% to $94", a[0].line())
            assertEquals("wallet SOL +0.25 (now 1.25)", a[2].line())
            alerts = a; base = b
        }
        assertEquals(94.0, base["price:SOL"]!!, 0.0)
        // quiet window: another 6% within 2 h does not alert for the same token
        WatcherRules.check(pol, base, mapOf("SOL" to 88.0), emptyMap(), alerts, t0 + 3).let { (a, _) -> assertTrue(a.isEmpty()) }
        // daily budget
        val many = (0 until WatcherPolicy.MAX_ALERTS_A_DAY).map { WatchAlert(t0 - 1000L * it, WatchAlert.PRICE, "JUP", 1.0, 2.0) }
        WatcherRules.check(pol, mapOf("price:SKR" to 0.02), mapOf("SKR" to 0.03), emptyMap(), many, t0 + 3).let { (a, _) -> assertTrue(a.isEmpty()) }
        // Jupiter Price API v3 reply (shape read live on 9 Oct 2026)
        val live = """{"JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN":{"usdPrice":0.3804688321433463,"decimals":6,"priceChange24h":12.3},"SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3":{"usdPrice":0.016513254140036963,"decimals":6},"So11111111111111111111111111111111111111112":{"usdPrice":109.49868479610063,"decimals":9}}"""
        assertEquals(listOf("SOL", "SKR", "JUP"), WatcherRules.parsePrices(live).keys.toList())
        assertTrue(WatcherRules.priceUrl().startsWith("https://lite-api.jup.ag/price/v3?ids=So111"))

        // background tick: off by default; on -> notification + Sol's context; no swap ever recorded
        val posted = ArrayList<String>()
        val post: (Int, String, String, Intent) -> Boolean = { _, t, b, _ -> posted += "$t|$b"; true }
        assertTrue(AgentTicks.watcher(app, t0, post, prices = { mapOf("SOL" to 100.0) }, balances = { emptyMap() }).isEmpty())
        WatcherStore(app).setPolicy(pol)
        assertEquals(listOf("watcher:quiet"), AgentTicks.watcher(app, t0, post, prices = { mapOf("SOL" to 100.0) }, balances = { mapOf("SOL" to 0.5) }))
        val now = System.currentTimeMillis()
        val out = AgentTicks.watcher(app, now, post, prices = { mapOf("SOL" to 91.0) }, balances = { mapOf("SOL" to 0.5) })
        assertEquals(listOf("watcher:price:SOL"), out)
        assertEquals("Watcher|SOL is down 9.0% to $91", posted.single())
        assertTrue(AssistantExtras.lines(app, now).any { it == "Watcher alerts (24h): SOL down 9.0% to $91." })
        assertTrue("the Watcher never trades", SwapStore(app).records().isEmpty())
        assertTrue("throttled to ~15 min", AgentTicks.watcher(app, now + 60_000, post, prices = { mapOf("SOL" to 50.0) }).isEmpty())
    }

    // ------------------------------------------------------------------ morning briefing

    @Test fun briefingFactsTimingAndLocalText() {
        val now = LocalDate.of(2026, 10, 10).atTime(8, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 11).atTime(8, 30).atZone(zone).toInstant().toEpochMilli(), Briefing.nextAt(BriefingPolicy(), now, zone))
        assertEquals(LocalDate.of(2026, 10, 10).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), Briefing.nextAt(BriefingPolicy(hour = 9, minute = 0), now, zone))
        assertTrue("on by default at 08:30", BriefingStore(app).policy().let { it.enabled && it.label == "08:30" })
        val calls = sample.mapIndexed { i, it -> it.copy(at = now - (i + 1) * 3_600_000L) } +  // 1.1.2: indexOf on a fresh `sample` was clock-dependent (flaky)
            call("old", "Ivan", "two days ago", "x", 0).copy(at = now - 3 * 86_400_000L)
        val plan = SeasonStore.planFor(app, net.solardepin.solarchik.game.GameSave(app), true)
        val f = Briefing.facts(calls, emptyList(), listOf("pay 10 USDC to Olena (needs your confirmation)"), listOf(WatchAlert(now, WatchAlert.PRICE, "SOL", 100.0, 94.0)),
            AssistantContext.Wallet(true, false, owner, true, 0.4213, 120.5), WalletSnap(now - 86_400_000L, 0.4318, 113.85), plan, now, zone)
        val c = f.getJSONArray("calls")
        assertEquals("yesterday + overnight only, never blocked", 5, c.length())
        assertEquals("Mom", c.getJSONObject(0).getString("who"))
        assertEquals("Olena", c.getJSONObject(4).getString("who"))
        assertEquals("+380671112233", c.getJSONObject(4).getString("callback"))
        val w = f.getJSONObject("wallet")
        assertEquals("mainnet", w.getString("network"))
        assertEquals(-0.0105, w.getDouble("solDelta"), 1e-9)
        assertEquals(6.65, w.getDouble("skrDelta"), 1e-9)
        assertEquals("SOL down 6.0% to $94", f.getJSONArray("alerts").getString(0))
        val text = Briefing.localText(f, app)
        assertTrue(text, text.startsWith("Good morning, here is your briefing. 5 calls since yesterday. Olena at") || text.contains("5 calls since yesterday."))
        assertTrue(text, text.contains("Your wallet is down 0.0105 SOL, now 0.4213 SOL."))
        assertTrue(text, text.contains("One action from a call waits for your confirmation."))
        assertTrue(text, text.contains("Petro at 05:30: Call back at 3 about the contract. Call back on +380501234567."))
        assertTrue(text, text.contains("Watcher: SOL down 6.0% to \$94."))
        assertFalse(text, text.contains("Spam"))
    }

    @Test fun briefingNotificationAndTodayPlaysWorkerText() {
        // no wallet here (unit tests make no RPC calls); the wallet delta is covered by briefingFactsTimingAndLocalText
        CallInbox.store(app, sample)
        val sent = ArrayList<Pair<String, String>>()
        TodayScreen.postOverride = { url, body ->
            sent += url to body
            if (url.endsWith("/sol/briefing")) 200 to """{"ok":true,"text":"Good morning. Olena wants 10 USDC for the tickets, and Petro asks for a call back at 15:00.","persona":"assistant"}"""
            else 0 to ""
        }
        val a = launch()
        val today = a.screen(MainActivity.Tab.TODAY) as TodayScreen
        val spoken = ArrayList<String>()
        today.speak = { t, _ -> spoken += t; true }
        today.setBalanceForTest(0.4213, 120.5)
        a.select(MainActivity.Tab.SETTINGS); idle()
        // the 08:30 job fires once a day; the notification opens Today
        val posted = ArrayList<Intent>()
        assertTrue(Briefing.fire(app, post = { id, _, _, i -> assertEquals(Briefing.NOTE_ID, id); posted += i; true }))
        assertFalse("once a day", Briefing.fire(app, post = { _, _, _, i -> posted += i; true }))
        assertTrue(posted.single().getBooleanExtra(MainActivity.EXTRA_BRIEFING, false))
        assertEquals(Briefing.today(), BriefingStore(app).pendingDay)
        a.select(MainActivity.Tab.TODAY); idle()
        repeat(60) { idle(); if (spoken.isNotEmpty()) return@repeat }
        assertEquals("opening Today plays the pending briefing once", 1, spoken.size)
        assertTrue(spoken[0].startsWith("Good morning. Olena wants 10 USDC"))
        val req = JSONObject(sent.first { it.first.endsWith("/sol/briefing") }.second)
        assertEquals("assistant", req.getString("app"))
        assertTrue(req.getJSONObject("facts").getJSONArray("calls").length() >= 4)
        assertEquals("", BriefingStore(app).pendingDay)
        val d = a.window.decorView
        assertTrue((find(d, "today-briefing-text") as TextView).text.startsWith("Good morning. Olena"))
        assertEquals("Every day at 08:30 · tap to change", (find(d, "today-briefing-time") as TextView).text.toString())
        shot(d, "18_today_briefing", "today-briefing")
        // "Play briefing" again; offline -> the local template from the same facts
        TodayScreen.postOverride = { _, _ -> 503 to "{}" }
        today.briefingPost = { u, b -> TodayScreen.postOverride!!(u, b) }
        today.playBriefing(); repeat(60) { idle() }
        assertEquals(2, spoken.size)
        assertTrue(spoken[1], spoken[1].startsWith("Good morning, here is your briefing."))
    }

    // ------------------------------------------------------------------ call -> action

    @Test fun localRulesFindRequestsInSampleTranscripts() {
        val byId = sample.associateBy { it.callId }
        val pay = CallActionRules.local(byId["1"]!!)
        assertEquals(1, pay.size)
        assertEquals(CallAction.PAYMENT, pay[0].type); assertEquals(10.0, pay[0].amount, 0.0); assertEquals("USDC", pay[0].token)
        assertEquals("Olena", pay[0].recipient); assertEquals("", pay[0].saidAddress)
        val scam = CallActionRules.local(byId["2"]!!).single()
        assertEquals("SOL", scam.token); assertEquals(2.0, scam.amount, 0.0)
        assertEquals("the spoken address is kept only to show it with a warning", friend, scam.saidAddress)
        val cb = CallActionRules.local(byId["3"]!!).single()
        assertEquals(CallAction.CALLBACK, cb.type); assertEquals("15:00", cb.time); assertEquals("+380501234567", cb.number)
        val rem = CallActionRules.local(byId["4"]!!).single()
        assertEquals(CallAction.REMINDER, rem.type)
        assertTrue("'no need to call back' is not a request? (local rules are simple)", CallActionRules.local(byId["5"]!!).all { it.type == CallAction.CALLBACK })
        val uk = call("7", "Андрій", "Просить переказати 0.5 SOL", "Абонент: скинь мені, будь ласка, 0,5 SOL за оренду, і передзвони о 6.", 10)
        val ukA = CallActionRules.local(uk)
        assertEquals(listOf(CallAction.PAYMENT, CallAction.CALLBACK), ukA.map { it.type })
        assertEquals(0.5, ukA[0].amount, 0.0); assertEquals("18:00", ukA[1].time)
        assertEquals("15:00", CallActionRules.hhmm("3", null, null)); assertEquals("09:30", CallActionRules.hhmm("9", "30", null)); assertEquals("21:00", CallActionRules.hhmm("9", null, "pm"))
        // remindAt: on the call's day, "tomorrow" +1, never in the past
        val callAt = LocalDate.of(2026, 10, 9).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val noon = callAt + 60_000
        assertEquals(LocalDate.of(2026, 10, 9).atTime(15, 0).atZone(zone).toInstant().toEpochMilli(), CallActionRules.remindAt(cb, callAt, noon, zone))
        assertEquals(LocalDate.of(2026, 10, 10).atTime(9, 30).atZone(zone).toInstant().toEpochMilli(), CallActionRules.remindAt(rem.copy(time = "09:30", day = "tomorrow", date = ""), callAt, noon, zone))
        assertEquals(LocalDate.of(2026, 10, 10).atTime(9, 30).atZone(zone).toInstant().toEpochMilli(), CallActionRules.remindAt(rem.copy(time = "09:30", date = ""), callAt, noon, zone))
    }

    @Test fun workerReplyIsParsedAndOfflineFallsBackToLocalRules() {
        val calls = sample
        val keys = calls.map { it.key }
        // shape of the live reply (POST /call/actions, 9 Oct 2026, gpt-4.1-mini structured output)
        val body = JSONObject().put("ok", true).put("processed", org.json.JSONArray(keys.take(5)))
            .put("actions", org.json.JSONArray()
                .put(JSONObject().put("callId", keys[0]).put("type", "payment").put("amount", 10).put("token", "USDC").put("recipient", "Olena").put("address", "").put("number", "").put("when", "").put("day", "").put("text", "Send Olena 10 USDC for the concert tickets").put("quote", "send me 10 USDC"))
                .put(JSONObject().put("callId", keys[1]).put("type", "payment").put("amount", 2).put("token", "SOL").put("recipient", "").put("address", friend).put("number", "").put("when", "").put("day", "").put("text", "Send 2 SOL").put("quote", "send 2 SOL right now"))
                .put(JSONObject().put("callId", keys[2]).put("type", "callback").put("amount", 0).put("token", "").put("recipient", "").put("address", "").put("number", "+380501234567").put("when", "15:00").put("day", "today").put("text", "Call Petro back at 15:00").put("quote", "call me back at three"))
                .put(JSONObject().put("callId", "nope").put("type", "callback").put("text", "x"))
                .put(JSONObject().put("callId", keys[3]).put("type", "transfer_everything").put("text", "x")))
            .toString()
        val (acts, processed) = CallActionRules.parse(body, calls)!!
        assertEquals(listOf("payment", "payment", "callback"), acts.map { it.type })
        assertEquals(friend, acts[1].saidAddress)
        assertEquals("today", acts[2].day)
        assertEquals(5, processed.size)
        // request body: only answered calls with a note, never blocked; the model sees the call, not the wallet
        val req = JSONObject(CallActionRules.requestBody(CallActionRules.candidates(calls, emptySet(), System.currentTimeMillis()), "en", zone))
        assertEquals(5, req.getJSONArray("calls").length())
        assertFalse(req.toString().contains(owner))

        var posts = 0
        val found = CallActionSync.run(app, calls, "en", post = { url, _ -> posts++; assertTrue(url.endsWith("/call/actions")); 200 to body })
        assertEquals(3, found.size)
        assertEquals(0, CallActionSync.run(app, calls, "en", post = { _, _ -> posts++; 200 to body }).size)
        assertEquals("each call is looked at once", 1, posts)
        // offline: local rules, and nothing is sent anywhere
        app.getSharedPreferences(CallActionStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val offline = CallActionSync.run(app, calls, "en", post = { _, _ -> throw java.io.IOException("offline") })
        assertEquals(listOf(CallAction.SOURCE_LOCAL), offline.map { it.source }.distinct())
        assertTrue(offline.any { it.payment && it.token == "USDC" })
    }

    @Test fun paymentTxIsAPlainTransferTheUserSigns() {
        val me = PublicKey(owner); val to = PublicKey(friend)
        val sol = CallActionRules.paymentTx(me, to, "SOL", CallActionRules.amountRaw("SOL", 0.5), hash)
        val solKeys = sol.keys.map { it.key.toBase58() }
        assertEquals(owner, solKeys[0])
        assertTrue(solKeys.contains("11111111111111111111111111111111"))
        assertTrue(solKeys.contains(friend))
        assertEquals(500_000_000L, CallActionRules.amountRaw("SOL", 0.5))
        val usdc = CallActionRules.paymentTx(me, to, "USDC", CallActionRules.amountRaw("USDC", 10.0), hash)
        val keys = usdc.keys.map { it.key.toBase58() }
        val mint = PublicKey(SwapTokens.USDC.mint)
        assertTrue("from my USDC account", keys.contains(SplIx.ata(me, mint).toBase58()))
        assertTrue("to the recipient's USDC account", keys.contains(SplIx.ata(to, mint).toBase58()))
        assertTrue(keys.contains(SplIx.TOKEN_PROGRAM.toBase58())); assertTrue(keys.contains(SplIx.ATA_PROGRAM.toBase58()))
        assertEquals("only I sign", 1, usdc.keys.count { it.signer })
        assertEquals(10_000_000L, CallActionRules.amountRaw("USDC", 10.0))
        assertTrue(runCatching { CallActionRules.paymentTx(me, me, "SOL", 1, hash) }.isFailure)
        assertTrue("1.2.0: SKR is a payment token", runCatching { CallActionRules.paymentTx(me, to, "SKR", 1, hash) }.isSuccess)
        assertTrue(runCatching { CallActionRules.paymentTx(me, to, "BONK", 1, hash) }.isFailure)
        assertTrue(CallActionRules.validAddress(friend)); assertFalse(CallActionRules.validAddress("Olena")); assertFalse(CallActionRules.validAddress("0x12ab"))
        assertTrue(CallActionRules.large("SOL", 2.0)); assertFalse(CallActionRules.large("USDC", 10.0))
    }

    @Test fun todayShowsActionCardsPaymentNeedsTheUsersRecipient() {
        connectAs(owner)
        CallInbox.store(app, sample)
        val keys = sample.map { it.key }
        CallActionStore(app).add(listOf(
            CallAction(keys[1] + "#0", keys[1], CallAction.PAYMENT, amount = 2.0, token = "SOL", saidAddress = friend, quote = "send 2 SOL right now to protect your account"),
            CallAction(keys[2] + "#0", keys[2], CallAction.CALLBACK, number = "+380501234567", time = "15:00", day = "today", quote = "call me back at three"),
            CallAction(keys[0] + "#0", keys[0], CallAction.PAYMENT, amount = 10.0, token = "USDC", recipient = "Olena", quote = "send me 10 USDC"),
        ))
        CallActionStore(app).upgradeRules(net.solardepin.solarchik.screen.CallActionSync.RULES) // already seen by the current rules
        CallActionStore(app).markProcessed(keys)
        val a = launch()
        val d = a.window.decorView
        assertNotNull(find(d, "today-actions"))
        val all = texts(d)
        assertTrue(all.toString(), all.any { it == "Pay 10 USDC · asked by Olena" })
        assertTrue(all.any { it.startsWith("Payment requests by phone are a common scam") })
        assertTrue(all.contains("MORNING STACK · 1 OF 3"))
        shot(d, "19_today_call_actions", "today-actions")
        // 1.2.5 morning stack: one card at a time; Later sends both payments to tomorrow
        (find(d, "stack-later") as View).performClick(); idle()
        (find(d, "stack-later") as View).performClick(); idle()
        assertTrue(texts(d).any { it == "Call Petro back at 15:00" })
        // callback: the dialer opens with the number; the user presses call
        (find(d, "ca-dial") as View).performClick(); idle()
        val dial = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:+380501234567", dial.dataString)
        (find(d, "ca-remind") as View).performClick(); idle()
        assertTrue(CallActionStore(app).find(keys[2] + "#0").remindAt > System.currentTimeMillis())
        // payment: the address the caller said is shown in full with a warning but NOT filled in
        val scam = CallActionStore(app).all().first { it.id == keys[1] + "#0" }
        CallActionCards.paySheet(a, scam) {}; idle()
        val sheet = CallActionCards.lastSheet!!
        val root = sheet.window!!.decorView
        val input = find(root, "ca-sheet-recipient") as EditText
        assertEquals("", input.text.toString())
        assertEquals(friend, (find(root, "ca-sheet-said-addr") as TextView).text.toString())
        assertTrue(texts(root).any { it.startsWith("The caller said this address.") })
        assertEquals("Enter a valid Solana address.", CallActionCards.paymentProblem(a, scam, "", true))
        assertEquals("Tick the box after checking the address.", CallActionCards.paymentProblem(a, scam, friend, false))
        assertEquals("That is your own wallet.", CallActionCards.paymentProblem(a, scam, owner, true))
        assertNull(CallActionCards.paymentProblem(a, scam, friend, true))
        // confirm without a recipient: refused before any wallet call; the sheet stays open
        sheet.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(sheet.isShowing)
        assertEquals(CallAction.OPEN, CallActionStore(app).find(scam.id).status)
        // 1.2.4: no one-tap "use this address" for an address heard on a call
        assertNull(find(root, "ca-sheet-use-said"))
        assertEquals("", input.text.toString())
        shot(root, "20_payment_sheet_scam_warning")
        sheet.dismiss()
        // Sol knows about the waiting actions
        assertTrue(AssistantExtras.lines(app).any { it.startsWith("Actions from calls waiting for the user's confirmation: pay 2 SOL") })
        // dismiss
        (find(d, "ca-dismiss") as View).performClick(); idle()
        assertEquals(2, CallActionStore(app).open().size)
    }

    // ------------------------------------------------------------------ Ukrainian (secondary language) is complete

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun ukrainianAgentsBriefingAndActions() {
        connectAs(owner)
        CallInbox.store(app, sample)
        val keys = sample.map { it.key }
        CallActionStore(app).add(listOf(
            CallAction(keys[0] + "#0", keys[0], CallAction.PAYMENT, amount = 10.0, token = "USDC", recipient = "Олена", quote = "скинь мені 10 USDC"),
            CallAction(keys[2] + "#0", keys[2], CallAction.CALLBACK, number = "+380501234567", time = "15:00", day = "today", text = "Передзвонити Петру о 15:00", quote = "передзвони о третій"),
        ))
        CallActionStore(app).upgradeRules(net.solardepin.solarchik.screen.CallActionSync.RULES) // already seen by the current rules
        CallActionStore(app).markProcessed(keys)
        val a = launch()
        val d = a.window.decorView
        val latinWords = Regex("\\b(the|and|your|you|with|call|back|wallet|briefing|Pay|Off|On)\\b")
        fun noEnglish(where: String) {
            // the sample call notes themselves are English data (what the caller said), not UI copy
            val data = sample.flatMap { listOf(it.intent, it.text) }.filter { it.isNotBlank() }
            val bad = texts(d).filter { t -> data.none { t.contains(it) } && latinWords.containsMatchIn(t) && !Regex("[а-яіїєґ]", RegexOption.IGNORE_CASE).containsMatchIn(t) }
            assertTrue("$where: $bad", bad.isEmpty())
        }
        assertTrue(texts(d).any { it == "Ранкове зведення" })
        assertTrue(texts(d).any { it.startsWith("Оплатити 10 USDC") })
        noEnglish("today")
        shot(d, "21_uk_today_actions", "today-actions")
        shot(d, "22_uk_today_briefing", "today-briefing")
        a.select(MainActivity.Tab.AGENTS); idle()
        val ag = a.screen(MainActivity.Tab.AGENTS) as AgentsScreen
        assertTrue(texts(d).containsAll(listOf("Агент сезону", "Скарбничка", "Вартовий")))
        noEnglish("agents/season")
        shot(d, "23_uk_agents_season", "agent-season")
        ag.openSection(AgentsScreen.SAVER); idle(); ag.saverPanel.turnOn(); idle()
        noEnglish("agents/saver")
        shot(d, "24_uk_agents_saver", "agent-saver")
        ag.watcherPanel.fetchPrices = { mapOf("SOL" to 109.5, "SKR" to 0.0165, "JUP" to 0.3805) }
        ag.openSection(AgentsScreen.WATCHER); idle(); ag.watcherPanel.turnOn(); idle()
        noEnglish("agents/watcher")
        shot(d, "25_uk_agents_watcher", "agent-watcher")
        val pay = CallActionStore(app).all().first { it.payment }
        CallActionCards.paySheet(a, pay.copy(saidAddress = friend)) {}; idle()
        val root = CallActionCards.lastSheet!!.window!!.decorView
        assertTrue(texts(root).toString(), texts(root).none { latinWords.containsMatchIn(it) && !Regex("[а-яіїєґ]", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        shot(root, "26_uk_payment_sheet")
        CallActionCards.lastSheet!!.dismiss()
    }

    @Test fun englishIsTheDefaultLanguageEvenOnAUkrainianPhone() {
        val was = System.getProperty("solarchik.langDefault")
        try {
            System.clearProperty("solarchik.langDefault")
            app.getSharedPreferences("solarchik-lang", Context.MODE_PRIVATE).edit().clear().commit()
            assertEquals(net.solardepin.solarchik.core.AppLocale.EN, net.solardepin.solarchik.core.AppLocale.choice(app))
            assertEquals("en", net.solardepin.solarchik.core.AppLocale.resolve(net.solardepin.solarchik.core.AppLocale.choice(app), java.util.Locale("uk", "UA")))
            net.solardepin.solarchik.core.AppLocale.set(app, net.solardepin.solarchik.core.AppLocale.UK)
            assertEquals("uk", net.solardepin.solarchik.core.AppLocale.lang(app))
            net.solardepin.solarchik.core.AppLocale.set(app, net.solardepin.solarchik.core.AppLocale.FOLLOW)
            assertEquals("the Phone choice is remembered", net.solardepin.solarchik.core.AppLocale.FOLLOW, net.solardepin.solarchik.core.AppLocale.choice(app))
        } finally {
            if (was == null) System.clearProperty("solarchik.langDefault") else System.setProperty("solarchik.langDefault", was)
            app.getSharedPreferences("solarchik-lang", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
