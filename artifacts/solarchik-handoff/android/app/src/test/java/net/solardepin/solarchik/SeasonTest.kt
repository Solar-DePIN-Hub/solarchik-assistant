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
import net.solardepin.solarchik.season.SeasonDapps
import net.solardepin.solarchik.season.SeasonItem
import net.solardepin.solarchik.season.SeasonStore
import net.solardepin.solarchik.season.Skr
import net.solardepin.solarchik.sol.AssistantRules
import net.solardepin.solarchik.ui.SeasonScreen
import net.solardepin.solarchik.ui.SolScreen
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
import java.io.File
import java.time.LocalDate

/**
 * 1.0.0 Seeker Season helper: the plan ticks only from real local state, suggestions link out, SKR is a
 * read-only mainnet balance (parsed from a real RPC answer), staking is a link, and Sol reads the plan.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class SeasonTest {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.0.0").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet", "solarchik.calls",
            "solarchik.calls.remind", "solarchik.followups", "solarchik.season", MainActivity.ASSISTANT_PREFS, "solarchik-game")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }

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
        scroll?.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ pure parts

    @Test fun skrBalanceFromARealMainnetAnswer() {
        // captured from api.mainnet-beta.solana.com (getTokenAccountsByOwner, SKR stake config owner, 9 Oct 2026)
        val json = javaClass.classLoader!!.getResource("season/skr-accounts.json")!!.readText()
        val v = Skr.parse(json).getOrThrow()
        assertEquals(5_052_599_612.393104, v, 0.001)
        // empty wallet = 0, RPC error = failure, garbage = failure
        assertEquals(0.0, Skr.parse("""{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":[]},"id":1}""").getOrThrow(), 0.0)
        assertTrue(Skr.parse("""{"jsonrpc":"2.0","error":{"code":429,"message":"Too many requests"},"id":1}""").isFailure)
        assertTrue(Skr.parse("<html>").isFailure)
        val body = JSONObject(Skr.body("C9pVXx7ieotYjaQr76gAgx7bmA67hZQivFTZ2UxGuWnz"))
        assertEquals("getTokenAccountsByOwner", body.getString("method"))
        assertEquals(Skr.MINT, body.getJSONArray("params").getJSONObject(1).getString("mint"))
        assertEquals("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", Skr.MINT)
        assertTrue(Skr.RPC.contains("mainnet"))
    }

    @Test fun streakAndDailySuggestion() {
        val d = LocalDate.of(2026, 10, 9)
        assertEquals(0, SeasonStore.streak(emptySet(), d))
        assertEquals(3, SeasonStore.streak(setOf("2026-10-07", "2026-10-08", "2026-10-09"), d))
        assertEquals("yesterday still counts until today is opened", 2, SeasonStore.streak(setOf("2026-10-07", "2026-10-08"), d))
        assertEquals(1, SeasonStore.streak(setOf("2026-10-05", "2026-10-09"), d))
        val week = (0L until SeasonDapps.all.size).map { SeasonDapps.forDay(d.plusDays(it)).name }.toSet()
        assertEquals("every suggestion comes up", SeasonDapps.all.map { it.name }.toSet(), week)
        assertEquals(listOf("dev.helius.orb", "ag.jup.jupiter.android", "com.loopscale.app", "com.tokenrun.app"), SeasonDapps.all.map { it.pkg })
        assertTrue(SeasonDapps.all.all { it.url.startsWith("https://") })
    }

    // ------------------------------------------------------------------ screens

    @Test fun planTicksOnlyFromRealState() {
        val a = launch()
        // opening the app is the only thing done so far
        var p = SeasonStore.plan(app, a.save.signedToday(), a.save.clockedToday())
        assertTrue(p.openedToday)
        assertFalse(p.done(SeasonItem.EXPLORE))
        assertFalse(p.done(SeasonItem.ONCHAIN))
        val d = a.window.decorView
        assertEquals("1 of 3 done today", (find(d, "today-season-sub") as TextView).text.toString())
        assertTrue((find(d, "today-season-daily_use") as TextView).text.startsWith("✓"))
        assertTrue((find(d, "today-season-explore") as TextView).text.startsWith("○"))

        find(d, "today-season")!!.performClick(); idle()
        assertEquals(MainActivity.Tab.SEASON, a.current)
        val s = a.screen(MainActivity.Tab.SEASON) as SeasonScreen
        assertEquals("done", find(s.view, "season-use-check")!!.contentDescription)
        assertEquals("not done", find(s.view, "season-explore-check")!!.contentDescription)
        assertEquals("not done", find(s.view, "season-chain-check")!!.contentDescription)
        assertEquals("1/3 today", (find(s.view, "season-progress") as TextView).text.toString())
        assertTrue(texts(s.view).any { it.contains("can't give you points") })
        shot(a.window.decorView, "05-season-en")

        // the suggestion links out (not installed here -> its website) and only then ticks
        val sugg = SeasonDapps.forDay(LocalDate.now())
        find(s.view, "season-explore-open")!!.performClick(); idle()
        val i = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(sugg.url, i.dataString)
        p = s.plan()
        assertEquals(sugg.name, p.explored)
        assertEquals("done", find(s.view, "season-explore-check")!!.contentDescription)
        assertEquals("2/3 today", (find(s.view, "season-progress") as TextView).text.toString())

        // the onchain item opens the check-in; nothing is signed from here
        assertFalse(a.save.signedToday())
        find(s.view, "season-chain-go")!!.performClick(); idle()
        assertEquals(MainActivity.Tab.SHIFT, a.current)
        assertFalse(a.save.signedToday())
        // back returns home
        a.select(MainActivity.Tab.SEASON); idle()
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(MainActivity.Tab.TODAY, a.current)
    }

    @Test fun skrCardStates() {
        val a = launch()
        a.select(MainActivity.Tab.SEASON); idle()
        val s = a.screen(MainActivity.Tab.SEASON) as SeasonScreen
        // no wallet: a connect button, no fake balance
        assertNotNull(find(s.view, "season-skr-connect"))
        assertNull(find(s.view, "season-skr-balance"))
        // staking is a link to the official page
        find(s.view, "season-skr-stake")!!.performClick()
        assertEquals("https://stake.solanamobile.com", shadowOf(a).nextStartedActivity.dataString)

        // with a wallet: RPC error -> "— SKR" + retry, then a balance
        LocalKey.create(app)
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit()
            .putString("kind", "local").putString("address", LocalKey.address(app)).commit()
        val b = launch()
        b.select(MainActivity.Tab.SEASON); idle()
        val s2 = b.screen(MainActivity.Tab.SEASON) as SeasonScreen
        s2.setSkrForTest(null, failed = true); idle()
        assertEquals("— SKR", (find(s2.view, "season-skr-balance") as TextView).text.toString())
        assertNotNull(find(s2.view, "season-skr-retry"))
        assertTrue(texts(s2.view).any { it.startsWith("Couldn't reach Solana mainnet") })
        s2.setSkrForTest(0.0); idle()
        assertEquals("0 SKR", (find(s2.view, "season-skr-balance") as TextView).text.toString())
        assertTrue((find(s2.view, "season-skr-balance") as TextView).text.toString().endsWith(" SKR"))
        assertNull(find(s2.view, "season-skr-retry"))
        assertTrue("the devnet key is called out", texts(s2.view).any { it.contains("built-in devnet key") })
        shot(b.window.decorView, "06-season-skr-en", "season-skr")
    }

    @Test fun solReadsThePlan() {
        assertEquals(AssistantRules.Kind.SEASON, AssistantRules.kind("What should I do for Seeker season today?"))
        assertEquals(AssistantRules.Kind.SEASON, AssistantRules.kind("Що робити для сезону Seeker?"))
        val a = launch()
        a.select(MainActivity.Tab.SOL); idle()
        (a.screen(MainActivity.Tab.SOL) as SolScreen).send("What should I do for Seeker season today?"); idle()
        val all = texts(a.window.decorView)
        val sugg = SeasonDapps.forDay(LocalDate.now()).name
        assertTrue(all.joinToString("\n"), all.any { it.startsWith("Seeker Season today: 1 of 3 done.") && it.contains("Try $sugg") && it.contains("I won't sign") })
    }

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi") fun seasonInUkrainian() {
        val a = launch()
        a.select(MainActivity.Tab.SEASON); idle()
        val s = a.screen(MainActivity.Tab.SEASON) as SeasonScreen
        assertEquals("1/3 сьогодні", (find(s.view, "season-progress") as TextView).text.toString())
        assertTrue(texts(s.view).any { it.contains("Керувати стейкінгом") })
    }
}
