package net.solardepin.solarchik

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.delegate.SplIx
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.season.SeasonDrops
import net.solardepin.solarchik.season.SeasonDropsStore
import net.solardepin.solarchik.season.SeekerState
import net.solardepin.solarchik.season.SeekerStore
import net.solardepin.solarchik.season.SeekerVerify
import net.solardepin.solarchik.sol.AssistantRules
import net.solardepin.solarchik.swap.SwapTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.sol4k.PublicKey

/** 1.2.0: SKR payments and balance answers, Season partner drops, Verified Seeker, wallet diagnostics; EN/UK screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release120Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val owner = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
    private val friend = "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"
    private val cyr = Regex("[а-яіїєґё]", RegexOption.IGNORE_CASE)

    private val dropsJson = """{"ok":true,"x":false,"sourcesText":"Reads the official Solana Mobile blog and docs. X support is built in and turns on with the paid API.","curatedChecked":"2026-10-10","checkedAt":1,
      "items":[
        {"id":"a","app":"MattleFun","perk":"Turn One Up birthday event, ${'$'}30K+ in total rewards.","deadline":"","sourceUrl":"https://x.com/mattlefun/status/2107820234271044066","sourceDate":"2026-10-07","origin":"curated","checked":"2026-10-10"},
        {"id":"b","app":"Kintara","perk":"Claim an exclusive Seeker hoodie.","deadline":"","sourceUrl":"https://solanamobile.com/blog/summer-wrapped","sourceDate":"2026-09-22","origin":"blog"},
        {"id":"c","app":"Scam","perk":"Free SOL","deadline":"","sourceUrl":"https://evil.example/x","sourceDate":"2026-10-08","origin":"blog"},
        {"id":"d","app":"Old","perk":"Expired","deadline":"2020-01-01","sourceUrl":"https://solanamobile.com/blog/old","sourceDate":"2020-01-01","origin":"blog"}]}"""

    @Before fun fresh() {
        MainActivity.tickerEnabled = false
        MainActivity.gameHub = false
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        app.getSharedPreferences(SeasonDropsStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(SeekerStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @org.junit.After fun restore() { MainActivity.tickerEnabled = true }

    @Test fun version() {
        assertEquals("1.2.2", BuildConfig.VERSION_NAME)
        assertEquals(122, BuildConfig.VERSION_CODE)
    }

    @Test fun skrPaymentIsAnSplTransferWithSixDecimalsAndCreatesTheRecipientAccount() {
        val me = PublicKey(owner); val to = PublicKey(friend)
        assertEquals(50_000_000L, CallActionRules.amountRaw("SKR", 50.0))
        val tx = CallActionRules.paymentTx(me, to, "SKR", CallActionRules.amountRaw("SKR", 50.0), ByteArray(32) { 7 })
        val keys = tx.keys.map { it.key.toBase58() }
        val mint = PublicKey(SwapTokens.SKR.mint)
        assertEquals("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", SwapTokens.SKR.mint)
        assertEquals(6, SwapTokens.SKR.decimals)
        assertTrue(keys.contains(mint.toBase58()))
        assertTrue("from my SKR account", keys.contains(SplIx.ata(me, mint).toBase58()))
        assertTrue("to the recipient's SKR account", keys.contains(SplIx.ata(to, mint).toBase58()))
        assertTrue(keys.contains(SplIx.ATA_PROGRAM.toBase58()))
        assertEquals("only I sign", 1, tx.keys.count { it.signer })
        assertTrue(CallActionRules.large("SKR", 600.0)); assertFalse(CallActionRules.large("SKR", 50.0))
        assertTrue("SKR" in CallActionRules.PAY_TOKENS)
    }

    @Test fun solAnswersSkrBalanceWithoutYieldNumbers() {
        assertEquals(AssistantRules.Kind.SKR, AssistantRules.kind("What is my SKR balance?"))
        assertEquals(AssistantRules.Kind.SKR, AssistantRules.kind("how much \$SKR do I have"))
        assertEquals(AssistantRules.Kind.SKR, AssistantRules.kind("Скільки в мене SKR?"))
        assertNotEquals(AssistantRules.Kind.SKR, AssistantRules.kind("buy 10 SKR"))
        assertNotEquals(AssistantRules.Kind.SKR, AssistantRules.kind("what is my streak"))
        val line = AssistantRules.skrLine(app, AssistantRules.SkrState(true, true, 1234.5678))
        assertTrue(line, line.contains("1234.57 SKR"))
        assertFalse(line, Regex("%|APY|APR|yield", RegexOption.IGNORE_CASE).containsMatchIn(line))
        assertFalse(cyr.containsMatchIn(line))
        assertTrue(AssistantRules.skrLine(app, AssistantRules.SkrState(false, false, null)).contains("Connect a wallet"))
    }

    @Test fun seasonDropsKeepOnlyOfficialCurrentItems() {
        val d = SeasonDrops.parse(dropsJson, "2026-10-10")!!
        assertEquals(listOf("MattleFun", "Kintara"), d.items.map { it.app })
        assertTrue(d.items[0].curated)
        assertFalse(d.x)
        assertEquals(listOf("MattleFun: Turn One Up birthday event, \$30K+ in total rewards"), SeasonDrops.lines(d, 1))
    }

    private fun texts(v: View, out: MutableList<String>) {
        if (v is TextView && v.visibility == View.VISIBLE && v.isShown) out += v.text.toString()
        if (v is ViewGroup) for (k in 0 until v.childCount) texts(v.getChildAt(k), out)
    }
    private fun all(v: View, out: MutableList<String>) {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (k in 0 until v.childCount) all(v.getChildAt(k), out)
    }
    private fun find(v: View, tag: String): View? = v.findViewWithTag(tag)

    @Test fun seasonScreenShowsTodaysTasksWithSourcesInEnglish() {
        SeasonDropsStore(app).save("en", dropsJson, System.currentTimeMillis())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(MainActivity.Tab.SEASON)
        shadowOf(Looper.getMainLooper()).idle()
        val root = a.window.decorView
        val card = find(root, "season-drops")
        assertNotNull(card)
        val t = mutableListOf<String>(); all(card!!, t)
        assertTrue(t.joinToString("|"), t.any { it == "Today's Season tasks" })
        assertTrue(t.any { it == "MattleFun" })
        assertTrue(t.any { it.startsWith("Source: @solanamobile on X") && it.contains("curated") })
        assertTrue(t.any { it == "Reads the official Solana Mobile blog and docs. X support is built in and turns on with the paid API." })
        assertFalse("no Cyrillic in EN", t.any { cyr.containsMatchIn(it) })
        assertNotNull(find(card, "season-drop-open-0"))
        assertNotNull("SKR stake link stays", find(root, "season-skr"))
    }

    @Test @Config(qualifiers = "uk")
    fun seasonTasksInUkrainian() {
        SeasonDropsStore(app).save("uk", dropsJson.replace("Turn One Up birthday event", "Святкова подія Turn One Up"), System.currentTimeMillis())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(MainActivity.Tab.SEASON)
        shadowOf(Looper.getMainLooper()).idle()
        val t = mutableListOf<String>(); all(find(a.window.decorView, "season-drops")!!, t)
        assertTrue(t.joinToString("|"), t.any { it == "Завдання Сезону на сьогодні" })
        assertTrue(t.any { it.contains("платним API") })
    }

    @Test fun notVerifiedSeekerIsANormalStateAndVerifyAsksForAWalletFirst() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(MainActivity.Tab.SETTINGS)
        shadowOf(Looper.getMainLooper()).idle()
        val root = a.window.decorView
        val status = find(root, "seeker-status") as TextView
        assertEquals("Not verified", status.text.toString())
        val body = (find(root, "seeker-body") as TextView).text.toString()
        assertTrue(body, body.contains("works on any Android phone"))
        assertTrue(body.contains("+10 secretary minutes"))
        val btn = find(root, "seeker-verify")!!
        if (!a.wallet.connected) {
            btn.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val shown = mutableListOf<String>(); texts(root, shown)
            assertTrue(shown.joinToString("|"), shown.any { it == app.getString(R.string.sk_connect_first) })
        }
        // wallet diagnostics are there too, every button has a handler
        for (tag in listOf("wd-test", "wd-copy", "wd-share", "wd-clear")) assertTrue(tag, find(root, tag)!!.hasOnClickListeners())
    }

    @Test fun verifiedSeekerShowsBadgeAndBonus() {
        SeekerStore(app).save(SeekerState(true, "5mXbkqKz883aufhAsx3p5Z1NcvD2ppZbdTTznM6oUKLj", owner, 1L, 10))
        val parsed = SeekerVerify.parse("""{"ok":true,"verified":false,"reason":"no_sgt","bonusMin":10}""")!!
        assertEquals(SeekerVerify.NO_SGT, parsed.last)
        SeekerStore(app).save(parsed) // a later failed try keeps the verification
        assertTrue(SeekerStore(app).state().verified)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(MainActivity.Tab.SETTINGS)
        shadowOf(Looper.getMainLooper()).idle()
        val root = a.window.decorView
        assertEquals("Verified Seeker", (find(root, "seeker-status") as TextView).text.toString())
        assertTrue((find(root, "seeker-body") as TextView).text.toString().contains("5mXb…UKLj"))
        assertEquals(null, find(root, "seeker-verify"))
    }

    @Test fun englishScreensHaveNoCyrillic() {
        SeasonDropsStore(app).save("en", dropsJson, System.currentTimeMillis())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        for (tab in listOf(MainActivity.Tab.TODAY, MainActivity.Tab.AGENTS, MainActivity.Tab.SETTINGS, MainActivity.Tab.SEASON)) {
            a.select(tab)
            shadowOf(Looper.getMainLooper()).idle()
            val t = mutableListOf<String>(); texts(a.window.decorView, t)
            val bad = t.filter { cyr.containsMatchIn(it.replace("Українська", "")) }
            assertTrue("$tab: $bad", bad.isEmpty())
        }
    }
}
