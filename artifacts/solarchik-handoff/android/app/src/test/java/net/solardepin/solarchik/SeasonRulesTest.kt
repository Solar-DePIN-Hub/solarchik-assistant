package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.autopilot.AutoKind
import net.solardepin.solarchik.autopilot.AutopilotPlanner
import net.solardepin.solarchik.autopilot.AutopilotPolicy
import net.solardepin.solarchik.season.RuleChange
import net.solardepin.solarchik.season.RuleTuning
import net.solardepin.solarchik.season.SeasonRules
import net.solardepin.solarchik.season.SeasonRulesStore
import net.solardepin.solarchik.season.SeasonRulesSync
import net.solardepin.solarchik.ui.AgentsScreen
import org.json.JSONArray
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 1.1.0 Season rules watcher, app side. The fixture is the real reply of POST /season/rules/check on 9 Oct 2026
 * (worker f35c29ee, version 4, official solanamobile.com pages). Synthetic docs cover the approval rules.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class SeasonRulesTest {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.1.0").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val live = javaClass.classLoader!!.getResource("season/rules_live_2026-10-09.json")!!.readText()
    private val demoDay = LocalDate.of(2026, 10, 10)
    private val realCluster = System.getProperty("solarchik.cluster")
    private val summer = "https://solanamobile.com/blog/summer-wrapped.-what%E2%80%99s-next-on-your-seeker"

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        System.setProperty("solarchik.cluster", "mainnet")
        app.getSharedPreferences(SeasonRulesStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
    }

    private fun sig(kind: String, action: String, conf: Double = 0.9, dapp: String = "", url: String = summer, start: String = "", end: String = "") =
        JSONObject().put("kind", kind).put("action", action).put("dapp", dapp).put("start", start).put("end", end).put("text", "$kind $action")
            .put("quote", "a verbatim quote from the page").put("confidence", conf).put("url", url).put("title", "T").put("published", "2026-10-01")

    private fun doc(version: Int, vararg sigs: JSONObject) = JSONObject().put("ok", true).put("version", version).put("checkedAt", 1L)
        .put("sources", JSONArray().put(JSONObject().put("url", summer).put("title", "Summer Wrapped").put("published", "2026-09-29").put("summary", "S").put("relevant", true)))
        .put("signals", JSONArray().apply { sigs.forEach { put(it) } }).toString()

    @Test fun liveOfficialReplyIsParsedAndAdaptsOnlySafely() {
        val d = SeasonRules.parse(live)!!
        assertEquals(4, d.version)
        assertEquals(summer, d.latest!!.url)
        assertEquals("2026-09-29", d.latest!!.published)
        assertTrue(d.latest!!.summary.contains("everyday wallet use"))
        assertEquals(listOf("x.com/solanamobile"), d.skipped)
        assertTrue(d.signals.all { SeasonRules.official(it.url) && it.quote.isNotBlank() })
        val ch = SeasonRules.changes(d, demoDay)
        val every = ch.single { it.type == RuleChange.EVERYDAY }
        assertTrue(every.signal.quote.startsWith("The update gives more weight to everyday wallet use"))
        assertFalse("CLOCK IN ended on 8 Oct", ch.any { it.type == RuleChange.CAMPAIGN && it.signal.end == "2026-10-08" })
        assertTrue("MINE / Bakeland are not in the plan's dApp list: shown, not applied", ch.filter { it.signal.kind == "featured_dapp" }.none { it.applies })
        assertTrue("nothing here raises spending", SeasonRules.pending(ch, emptySet(), emptySet()).isEmpty())
        val t = SeasonRules.tuning(ch, emptySet(), emptySet())
        assertEquals(mapOf(AutoKind.CHECKIN to 1.5, AutoKind.DAPP to 1.5), t.weights)
        assertEquals(2, t.maxPerDay)
        assertEquals(RuleTuning.NONE, SeasonRules.tuning(ch, emptySet(), emptySet(), enabled = false))
    }

    @Test fun spendingChangesNeedApprovalUnsureOnesNeverApplyNothingUnofficial() {
        val d = SeasonRules.parse(doc(3,
            sig("favored", "swap"),
            sig("favored", "staking", conf = 0.5),
            sig("devalued", "staking"),
            sig("featured_dapp", "dapp", dapp = "Jupiter"),
            sig("favored", "nft"),
            sig("devalued", "wallet_activity"),
            sig("favored", "checkin", url = "https://example.com/blog/fake"),
            sig("campaign", "quest", start = "2026-10-01", end = "2026-10-31"),
        ))!!
        assertEquals("the non-official source is dropped", 7, d.signals.size)
        val ch = SeasonRules.changes(d, demoDay)
        val swap = ch.single { it.autoKind == AutoKind.SWAP }
        assertTrue(swap.needsApproval)
        assertEquals(RuleChange.INFO, ch.single { it.signal.action == "staking" && it.signal.confidence < 0.7 }.type)
        assertEquals(RuleChange.DOWN, ch.single { it.signal.kind == "devalued" && it.signal.action == "staking" }.type)
        assertEquals("Jupiter Mobile", ch.single { it.type == RuleChange.FEATURED }.dapp)
        assertEquals("a new action type is only shown", RuleChange.INFO, ch.single { it.signal.action == "nft" }.type)
        assertEquals(RuleChange.CAMPAIGN, ch.single { it.signal.kind == "campaign" }.type)
        val t0 = SeasonRules.tuning(ch, emptySet(), emptySet())
        assertNull("no swap boost without approval", t0.weights[AutoKind.SWAP])
        assertEquals(0.5, t0.weights[AutoKind.STAKING]!!, 0.0)
        assertEquals(setOf("Jupiter Mobile"), t0.featured)
        assertEquals("score-gaming counts less -> fewer actions", 1, t0.maxPerDay)
        assertEquals(listOf(swap), SeasonRules.pending(ch, emptySet(), emptySet()))
        assertEquals(1.5, SeasonRules.tuning(ch, setOf(swap.signal.key), emptySet()).weights[AutoKind.SWAP]!!, 0.0)
        assertNull(SeasonRules.tuning(ch, setOf(swap.signal.key), setOf(swap.signal.key)).weights[AutoKind.SWAP])
        assertTrue(SeasonRules.pending(ch, emptySet(), setOf(swap.signal.key)).isEmpty())
    }

    @Test fun plannerFollowsTheRulesButNeverItsCaps() {
        val zone = ZoneId.of("Europe/Kyiv")
        val pol = AutopilotPolicy(enabled = true, perDay = 3)
        val notBefore = demoDay.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        // NONE = exactly the plain planner
        for (seed in 0 until 20) assertEquals(
            AutopilotPlanner.plan(demoDay, zone, pol, emptyList(), 20_000_000, false, notBefore, Random(seed)),
            AutopilotPlanner.plan(demoDay, zone, pol, emptyList(), 20_000_000, false, notBefore, Random(seed), RuleTuning.NONE))
        val everyday = SeasonRules.tuning(SeasonRules.changes(SeasonRules.parse(live), demoDay), emptySet(), emptySet())
        for (seed in 0 until 40) {
            val p = AutopilotPlanner.plan(demoDay, zone, pol, emptyList(), 20_000_000, false, notBefore, Random(seed), everyday)
            assertTrue(p.size in 1..2)
            assertTrue("everyday actions first: ${p.map { it.kind }}", p.first().kind in setOf(AutoKind.CHECKIN, AutoKind.DAPP))
            assertTrue(p.filter { it.kind == AutoKind.SWAP }.all { it.lamports <= pol.swapMaxLamports })
        }
        val featured = RuleTuning(mapOf(AutoKind.DAPP to 1.5), setOf("Orb"), 3)
        for (seed in 0 until 20) AutopilotPlanner.plan(demoDay, zone, pol, emptyList(), 0, true, notBefore, Random(seed), featured)
            .filter { it.kind == AutoKind.DAPP }.forEach { assertEquals("Orb", it.dapp) }
    }

    @Test fun storeVersionsAndHourlyRefresh() {
        val st = SeasonRulesStore(app)
        assertTrue(st.enabled)
        assertTrue(st.accept(live))
        assertTrue(st.updated)
        assertFalse("an older version never replaces a newer one", st.accept(doc(1, sig("favored", "swap"))))
        assertEquals(4, st.doc()!!.version)
        st.seenVersion = 4
        assertFalse(st.updated)
        var gets = 0
        val get: (String) -> Pair<Int, String> = { u -> gets++; assertTrue(u.endsWith("/season/rules")); 200 to doc(6, sig("favored", "checkin")) }
        assertTrue(SeasonRulesSync.refresh(app, get, now = 10_000_000))
        assertFalse("at most hourly", SeasonRulesSync.refresh(app, get, now = 10_000_000 + 60_000))
        assertEquals(1, gets)
        assertEquals(6, st.doc()!!.version)
        assertTrue(st.updated)
        val r = SeasonRulesSync.checkNow(app, post = { u, _ -> assertTrue(u.endsWith("/season/rules/check")); 503 to "{}" })
        assertTrue(r.isFailure)
        assertEquals(6, st.doc()!!.version)
    }

    // ---------------- UI ----------------

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
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
    private fun shot(root: View, name: String, tag: String) {
        idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        findScroll(root)?.let { sc -> var y = 0; var c: View? = find(sc, tag); while (c != null && c !== sc) { y += c.top; c = c.parent as? View }; sc.scrollTo(0, (y - 120).coerceAtLeast(0)) }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seasonTab(): Pair<MainActivity, AgentsScreen> {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }
        a.select(MainActivity.Tab.AGENTS); idle()
        val ag = a.screen(MainActivity.Tab.AGENTS) as AgentsScreen
        ag.openSection(AgentsScreen.SEASON); idle()
        return a to ag
    }

    @Test fun seasonAgentShowsRulesUpdatedAndChecksOnDemand() {
        val (a, ag) = seasonTab()
        val d = a.window.decorView
        assertNotNull(find(d, "sr-none"))
        var posts = 0
        ag.rulesPanel.post = { u, _ -> posts++; assertTrue(u.endsWith("/season/rules/check")); 200 to live }
        (find(d, "sr-check") as View).performClick()
        repeat(40) { idle(); if (!ag.rulesPanel.checking) return@repeat }
        idle()
        assertEquals(1, posts)
        assertNotNull(find(d, "sr-updated"))
        assertEquals("Summer Wrapped. What’s Next on Your Seeker?", (find(d, "sr-source-title") as TextView).text.toString().substringBefore(" | "))
        assertTrue((find(d, "sr-source") as TextView).text.startsWith("2026-09-29 · solanamobile.com"))
        assertTrue(texts(d).any { it.startsWith("• Puts everyday actions first") })
        assertTrue(texts(d).any { it.startsWith("“The update gives more weight to everyday wallet use") })
        assertTrue((find(d, "sr-status") as TextView).text.contains("(X is not read)"))
        shot(d, "27_season_rules_updated", "sr-card")
        (find(d, "sr-seen") as View).performClick(); idle()
        assertNull(find(d, "sr-updated"))
        // a change that means more spending waits for the user's OK
        SeasonRulesStore(app).accept(doc(7, sig("favored", "swap")))
        ag.render(); idle()
        assertNotNull(find(d, "sr-pending-0"))
        assertNull(SeasonRulesStore(app).tuning().weights[AutoKind.SWAP])
        (find(d, "sr-approve-0") as View).performClick(); idle()
        assertEquals(1.5, SeasonRulesStore(app).tuning().weights[AutoKind.SWAP]!!, 0.0)
        assertNull(find(d, "sr-pending-0"))
    }

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun ukrainianRulesCard() {
        SeasonRulesStore(app).accept(live)
        val (a, _) = seasonTab()
        val d = a.window.decorView
        assertTrue(texts(d).any { it.equals("Офіційні правила сезону", ignoreCase = true) })
        assertTrue(texts(d).any { it == "Правила оновлено" })
        assertTrue(texts(d).any { it.startsWith("• Першими ставить буденні дії") })
        assertTrue(texts(d).any { it == "Перевірити оновлення правил" })
        shot(d, "28_uk_season_rules", "sr-card")
    }
}
