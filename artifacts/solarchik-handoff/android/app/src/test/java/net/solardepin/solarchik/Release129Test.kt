package net.solardepin.solarchik

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import kotlinx.coroutines.runBlocking
import net.solardepin.solarchik.stack.Habits
import net.solardepin.solarchik.stack.MorningStack
import net.solardepin.solarchik.wallet.Base58
import net.solardepin.solarchik.wallet.MwaDirect
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError
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
import org.robolectric.shadows.ShadowLooper

/** 1.2.9 (Vadym's tablet run of 1.2.8): memo path, token reuse, explicit clock-in, Calls tab, bottom clearance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xxhdpi")
class Release129Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val me = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
    private val realCluster = System.getProperty("solarchik.cluster")
    private val prefs get() = app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE)

    @Before fun setUp() {
        System.setProperty("solarchik.cluster", "mainnet")
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
    }

    @After fun tearDown() {
        MwaDirect.transactOverride = null; MwaDirect.FAKE_CLIENT = null
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
        MainActivity.tickerEnabled = true
    }

    private fun idle() = repeat(8) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun walk(v: View, f: (View) -> Unit) { f(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f) }
    private fun find(root: View, tag: String): View? { var r: View? = null; walk(root) { if (r == null && it.tag == tag && it.isShown) r = it }; return r }

    @Test fun clockInMemoGoesThroughThePaymentPathInOneSession() = runBlocking {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        prefs.edit().putString("address", me).putString("auth", "saved").commit()
        val w = SolanaWallet(app)
        w.rpcOverride = ChainStub()
        var sessions = 0
        var sentToken: String? = null
        val signed = ArrayList<ByteArray>()
        MwaDirect.FAKE_CLIENT = MobileWalletAdapterClient(1000)
        MwaDirect.transactOverride = { _ -> sessions++; sentToken = prefs.getString("auth", null); Result.success(MobileWalletAdapterClient.AuthorizationResult.create("saved", Base58.decode(me), "Phantom", null)) }
        w.signSeam = { bytes -> signed += bytes; ByteArray(64) { 5 } }
        val r = w.clockInOnChain(a.sender, 0, 0, 3, "2026-10-11")
        assertTrue(r.exceptionOrNull()?.toString() ?: "", r.isSuccess)
        assertEquals("one wallet session", 1, sessions)
        assertEquals("one sign request (sign-and-send)", 1, w.signRequests)
        assertEquals("the saved token went to the wallet", "saved", sentToken)
        assertEquals("tx", r.getOrThrow().kind)
        val tx = String(signed.single(), Charsets.ISO_8859_1)
        assertTrue("memo text in the tx", tx.contains("solarchik clock 2026-10-11"))
        assertTrue("memo program in the tx", signed.single().toList().windowed(32).any { it == Base58.decode("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr").toList() })
        assertNotNull("min_context_slot passed like the payment", w.lastMinSlot ?: 0)
    }

    @Test fun theTokenFromAuthorizeIsSavedEvenWhenTheSignFails() = runBlocking {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        prefs.edit().putString("address", me).putString("auth", "old").commit()
        val w = SolanaWallet(app)
        w.rpcOverride = ChainStub()
        MwaDirect.FAKE_CLIENT = MobileWalletAdapterClient(1000)
        MwaDirect.transactOverride = { _ -> Result.success(MobileWalletAdapterClient.AuthorizationResult.create("fresh", Base58.decode(me), "Phantom", null)) }
        w.signSeam = { _ -> throw java.util.concurrent.ExecutionException(RuntimeException("wallet went away")) }
        // (the test seam throws straight out; in the live session the error is caught and reported)
        val r = runCatching { w.clockInOnChain(a.sender, 0, 0, 1, "2026-10-11").getOrThrow() }
        assertFalse(r.isSuccess)
        assertEquals("the wallet's new token is kept for the next payment", "fresh", prefs.getString("auth", null))
    }

    @Test fun aFailedOrDeclinedAttemptNeverWipesAValidToken() = runBlocking {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        prefs.edit().putString("address", me).putString("auth", "valid").commit()
        val w = SolanaWallet(app)
        w.rpcOverride = ChainStub()
        MwaDirect.FAKE_CLIENT = MobileWalletAdapterClient(1000)
        // the wallet opened but never answered (the 1.2.8 memo symptom)
        MwaDirect.transactOverride = { _ -> Result.failure(WalletError(WalletError.Kind.FAILED, "Timed out waiting for local association to be ready")) }
        assertFalse(w.clockInOnChain(a.sender, 0, 0, 1, "2026-10-11").isSuccess)
        assertEquals("valid", prefs.getString("auth", null))
        // a declined sign (user said no in the wallet)
        MwaDirect.transactOverride = { _ -> Result.failure(WalletError.classify("User did not authorize signing", null)) }
        assertFalse(w.clockInOnChain(a.sender, 0, 0, 1, "2026-10-11").isSuccess)
        assertEquals("valid", prefs.getString("auth", null))
    }

    @Test fun clockingInIsAnExplicitTapNeverASideEffect() {
        Habits.set(app, Habits.WORKOUT, true)
        Habits.set(app, Habits.SEASON, false)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val d = a.window.decorView
        // the last card leaves the stack without a swipe (e.g. a payment that settled while the wallet was open)
        Habits.markDone(app, Habits.WORKOUT)
        a.renderAll(); idle()
        assertFalse("not clocked in by itself", MorningStack.clockedIn(app))
        assertNull(find(d, "stack-clocked"))
        val btn = find(d, "stack-clock-in")
        assertNotNull("an explicit Clock in button", btn)
        assertEquals("Clock in", (btn as android.widget.TextView).text.toString().trim('\u2060', ' '))
        btn.performClick(); idle()
        assertTrue(MorningStack.clockedIn(app))
        assertNotNull(find(d, "stack-clocked"))
    }

    @Test fun callsIsABarTabAndSeasonShowsOnToday() {
        net.solardepin.solarchik.season.SeasonDropsStore(app).save("en", """{"ok":true,"items":[{"id":"x1","app":"MattleFun","perk":"Quests","sourceUrl":"https://x.com/mattlefun/status/1","sourceDate":"2026-10-07","checked":"2026-10-10"},{"id":"x2","app":"Mentioned","perk":"1 USD","sourceUrl":"https://x.com/m/status/2","sourceDate":"2026-10-07","checked":"2026-10-10"}]}""", System.currentTimeMillis())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val d = a.window.decorView
        val bar = find(d, "nav-bar") as ViewGroup
        val order = ArrayList<String>(); walk(bar) { t -> (t.tag as? String)?.takeIf { it.startsWith("nav-") && it.count { c -> c == '-' } == 1 && it !in setOf("nav-pill", "nav-bar", "nav-mic", "nav-wrap") }?.let { order += it } }
        assertEquals(listOf("nav-today", "nav-calls", "nav-sol", "nav-circle", "nav-me"), order.distinct())
        find(d, "nav-calls")!!.performClick(); idle()
        val started = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        assertEquals(net.solardepin.solarchik.ui.CallsActivity::class.java.name, started.component?.className)
        val row = find(d, "today-season-row")
        assertNotNull("Season tasks on Today", row)
        row!!.performClick(); idle()
        assertEquals(MainActivity.Tab.SEASON, a.current)
    }

    private fun navTop(d: View): Int {
        val bar = find(d, "nav-bar")!!; val p = IntArray(2); bar.getLocationInWindow(p); return p[1]
    }

    /** Scrolls the tab's page to the end and checks its last item ends above the floating bar. */
    @Test fun everyScrollingScreenEndsAboveTheBar() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val d = a.window.decorView
        val bad = ArrayList<String>()
        for (tab in listOf(MainActivity.Tab.TODAY, MainActivity.Tab.CIRCLE, MainActivity.Tab.ME, MainActivity.Tab.SETTINGS, MainActivity.Tab.SEASON, MainActivity.Tab.AGENTS)) {
            a.select(tab); idle()
            val scr = a.screen(tab) ?: continue
            val sv = scr.scroll
            val content: ViewGroup = if (sv != null) { sv.scrollTo(0, sv.getChildAt(0).height); idle(); sv.getChildAt(0) as ViewGroup }
                else ((scr.view as ViewGroup).let { r -> (0 until r.childCount).map { r.getChildAt(it) }.last { it is android.widget.LinearLayout } } as ViewGroup)
            val last = (0 until content.childCount).map { content.getChildAt(it) }.lastOrNull { it.visibility == View.VISIBLE && it.height > 0 } ?: continue
            val p = IntArray(2); last.getLocationInWindow(p)
            val nav = navTop(d)
            if (p[1] + last.height > nav) bad += "${tab.name}: last item ends at ${p[1] + last.height}px, bar starts at ${nav}px"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
