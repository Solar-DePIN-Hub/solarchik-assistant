package net.solardepin.solarchik

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.ui.roof.PunchState
import net.solardepin.solarchik.ui.roof.RoofCamera
import net.solardepin.solarchik.ui.roof.RoofFrame
import net.solardepin.solarchik.ui.roof.RoofHit
import net.solardepin.solarchik.ui.roof.RoofKv
import net.solardepin.solarchik.ui.roof.RoofLine
import net.solardepin.solarchik.ui.roof.RoofObject
import net.solardepin.solarchik.ui.roof.RoofTour
import net.solardepin.solarchik.ui.roof.RoofTourSteps
import net.solardepin.solarchik.ui.roof.RooftopScreen
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
import org.robolectric.shadows.ShadowLooper

/** 0.22.0: rooftop home, CLOCK IN punch clock and the guided tour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class Roof0220Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck

    private class MapKv : RoofKv {
        val m = HashMap<String, Any>()
        override fun bool(key: String) = m[key] as? Boolean ?: false
        override fun put(key: String, v: Boolean) { m[key] = v }
        override fun int(key: String) = m[key] as? Int ?: 0
        override fun putInt(key: String, v: Int) { m[key] = v }
    }

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        RooftopScreen.forceMute = true
        listOf("solarchik-roof", "solarchik-game", "solarchik-agents", "solarchik.calls").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        SolanaWallet.walletAppCheck = realCheck
        RooftopScreen.forceMute = false
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun visible(v: View?): Boolean {
        var c: View? = v ?: return false
        while (c != null) { if (c.visibility != View.VISIBLE) return false; c = c.parent as? View }
        return true
    }

    // 1.0.0: Today is home; the rooftop is the game behind the Play tile
    private fun open(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle(); it.select(MainActivity.Tab.YARD); idle() }

    // ---- tour state ----

    @Test fun tourIsOfferedOnceToANewPlayer() {
        val kv = MapKv()
        val t = RoofTour(kv)
        assertTrue("first visit offers the tour", t.onRoofVisit(isNewPlayer = true))
        assertFalse("second visit does not", t.onRoofVisit(isNewPlayer = true))
        assertFalse(RoofTour(kv).onRoofVisit(isNewPlayer = true))
        assertEquals(3, t.visits)
    }

    @Test fun existingPlayersAreNotInterrupted() {
        val kv = MapKv()
        assertFalse(RoofTour(kv).onRoofVisit(isNewPlayer = false))
        assertTrue(RoofTour(kv).offered)
        assertFalse(RoofTour(kv).onRoofVisit(isNewPlayer = true))
    }

    @Test fun longLabelsOnlyOnTheFirstVisits() {
        val t = RoofTour(MapKv())
        repeat(3) { t.onRoofVisit(true); assertTrue(t.longLabels()) }
        t.onRoofVisit(true)
        assertFalse(t.longLabels())
    }

    @Test fun skippingMarksOfferedButNotDone() {
        val kv = MapKv()
        RoofTour(kv).finish(completedAll = false)
        assertTrue(RoofTour(kv).offered)
        assertFalse(RoofTour(kv).completed)
        RoofTour(kv).finish(completedAll = true)
        assertTrue(RoofTour(kv).completed)
    }

    @Test fun firstLaunchOfferThenReplayFromTheRoof() {
        val a = open()
        assertEquals(MainActivity.Tab.YARD, a.current)
        assertTrue(a.screen(MainActivity.Tab.YARD) is RooftopScreen)
        val d = a.window.decorView
        assertTrue("nav is hidden on the roof", !visible(find(d, "nav-wrap")))
        // 0.22.3: the first launch STARTS the tour, with a visible skip
        assertTrue("new player's tour starts by itself", visible(find(d, "tour-card")))
        assertTrue("skip is visible", visible(find(d, "tour-skip")))
        assertNull("no offer card any more", find(d, "tour-offer-later"))
        find(d, "tour-skip")!!.performClick(); idle()
        assertNull(find(d, "tour-card"))

        // a second launch does not offer it again
        val b = open()
        val d2 = b.window.decorView
        assertNull(find(d2, "tour-offer-later"))
        assertNull(find(d2, "tour-card"))
        // replay from "?"
        find(d2, "roof-help")!!.performClick(); idle()
        assertTrue(visible(find(d2, "tour-card")))
        repeat(RoofTourSteps.full().size) { find(d2, "tour-next")!!.performClick(); idle() }
        assertNull("the last Next closes the tour", find(d2, "tour-card"))
        assertTrue(RoofTour(net.solardepin.solarchik.ui.roof.PrefsKv(app)).completed)
        // replay again, the judges' variant from the list
        find(d2, "roof-menu")!!.performClick(); idle()
        find(d2, "roof-row-judges")!!.performClick(); idle()
        assertTrue(visible(find(d2, "tour-card")))
        find(d2, "tour-skip")!!.performClick(); idle()
        assertNull(find(d2, "tour-card"))
    }

    @Test fun everyMenuDestinationIsReachableFromTheRoof() {
        val a = open()
        find(a.window.decorView, "tour-skip")?.performClick(); idle()
        for ((row, tab) in listOf("roof-row-clock" to MainActivity.Tab.SHIFT, "roof-row-sol" to MainActivity.Tab.SOL,
            "roof-row-agents" to MainActivity.Tab.AGENTS, "roof-row-strategies" to MainActivity.Tab.AGENTS,
            "roof-row-slice" to MainActivity.Tab.AGENTS, "roof-row-garage" to MainActivity.Tab.RUN,
            "roof-row-settings" to MainActivity.Tab.SETTINGS, "roof-row-secretary" to MainActivity.Tab.SETTINGS)) {
            a.select(MainActivity.Tab.YARD); idle()
            find(a.window.decorView, "roof-menu")!!.performClick(); idle()
            assertNotNull(row, find(a.window.decorView, row))
            find(a.window.decorView, row)!!.performClick(); idle()
            assertEquals(row, tab, a.current)
            assertTrue("nav is back off the roof", visible(find(a.window.decorView, "nav-wrap")))
        }
        assertNotNull(find(a.window.decorView, "roof-row-calls") ?: run { a.select(MainActivity.Tab.YARD); idle(); find(a.window.decorView, "roof-menu")!!.performClick(); idle(); find(a.window.decorView, "roof-row-calls") })
        assertNotNull(find(a.window.decorView, "roof-row-run"))
    }

    @Test fun callsBadgeShowsUnreadCallsOnTheRoof() {
        CallInbox.link(app, Screens0219Test.OWNER)
        CallInbox.store(app, CallInbox.parse(Screens0219Test.OWNER, 200, Screens0219Test.FIXTURE)!!)
        CallInbox.markSeen(app, 0)
        val a = open()
        find(a.window.decorView, "tour-skip")?.performClick(); idle()
        val badge = find(a.window.decorView, "roof-calls-badge") as android.widget.TextView
        assertTrue(visible(badge))
        assertEquals(CallInbox.unreadCount(app).toString(), badge.text.toString())
    }

    // ---- punch clock ----

    @Test fun punchClockGlowsUntilTodayIsSigned() {
        assertEquals(PunchState.NEED_RUN, PunchState.of(clockedToday = false, signedToday = false))
        assertTrue(PunchState.NEED_RUN.glowing)
        assertEquals(PunchState.READY, PunchState.of(clockedToday = true, signedToday = false))
        assertTrue(PunchState.READY.glowing)
        assertEquals(PunchState.DONE, PunchState.of(clockedToday = true, signedToday = true))
        assertFalse("calm after CLOCK IN", PunchState.DONE.glowing)
    }

    @Test fun punchClockOpensTheDailyCheckIn() {
        val a = open()
        find(a.window.decorView, "tour-skip")?.performClick(); idle()
        val roof = a.screen(MainActivity.Tab.YARD) as RooftopScreen
        roof.debugTap(RoofObject.CLOCK); idle()
        assertEquals(MainActivity.Tab.SHIFT, a.current)
        // 1.0.0: the check-in screen keeps today's CLOCK IN card (the secretary card moved to Today)
        assertNotNull(find(a.window.decorView, "yard-day-reset"))
    }

    @Test fun solRemindsUntilCheckedIn() {
        assertEquals(RoofLine.Kind.RUN, RoofLine.pick(0, PunchState.NEED_RUN, earned = true))
        assertEquals(RoofLine.Kind.SIGN, RoofLine.pick(0, PunchState.READY, earned = false))
        assertEquals(RoofLine.Kind.CALL, RoofLine.pick(2, PunchState.READY, earned = false))
        assertEquals(RoofLine.Kind.EARNED, RoofLine.pick(0, PunchState.DONE, earned = true))
        assertEquals(RoofLine.Kind.DONE, RoofLine.pick(0, PunchState.DONE, earned = false))
    }

    // ---- judges' tour: real devnet proofs only ----

    @Test fun judgesTourIsSixtySecondsWithRealDevnetLinks() {
        val steps = RoofTourSteps.judges(emptyList())
        assertEquals(60_000L, steps.sumOf { it.ms })
        val links = steps.flatMap { it.links }.distinctBy { it.sig }
        assertEquals(3, links.size)
        val b58 = Regex("^[1-9A-HJ-NP-Za-km-z]{86,88}$")
        for (l in links) {
            assertTrue(l.sig, b58.matches(l.sig))
            assertEquals("https://explorer.solana.com/tx/${l.sig}?cluster=devnet", l.url)
        }
        assertTrue(links.any { it.sig.startsWith("598nnYPz") } && links.any { it.sig.startsWith("b2PztsNS") })
        assertEquals(7, RoofTourSteps.full().size)
        assertEquals(listOf(RoofObject.SOL, RoofObject.DOOR, RoofObject.CLOCK, RoofObject.ANTENNA, RoofObject.PANELS, RoofObject.TICKER, RoofObject.TOOLBOX), RoofTourSteps.full().map { it.target })
    }

    // ---- camera / hit targets ----

    @Test fun cameraFitsPortraitAndLandscape() {
        val p = RoofCamera.fit(1080, 2400, 300f)
        assertTrue(p.portrait)
        // 0.22.1 cover: the rooftop is big (scale >= 1.3x the old key-range fit), Sol near the middle, the clock on screen
        assertTrue(p.s >= 1.3f * 1080f / (RoofFrame.KEY_X1 - RoofFrame.KEY_X0))
        val solX = p.x(RoofFrame.SOL_CX) / 1080f
        assertTrue("Sol at $solX", solX in 0.34f..0.55f)
        assertTrue("clock visible", p.x(RoofFrame.CLOCK.right) <= 1080f && p.x(RoofFrame.CLOCK.left) >= 0f)
        assertTrue("rooftop starts in the top quarter", p.y(0f) <= 2400f * 0.36f)
        assertTrue("floor above the run button", p.y(RoofFrame.H) <= 2400f - 299f)
        val l = RoofCamera.fit(2400, 1080, 0f)
        assertFalse(l.portrait)
        assertEquals(0f, l.y(0f), 0.5f)
        assertEquals(1080f, l.y(RoofFrame.H), 0.5f)
        val c = RoofFrame.CLOCK
        assertEquals(RoofObject.CLOCK, RoofHit.at(p, p.x(c.centerX()), p.y(c.centerY()), 126f))
        val door = RoofFrame.DOOR
        assertEquals(RoofObject.DOOR, RoofHit.at(l, l.x(door.centerX()), l.y(door.bottom - 20f), 126f))
    }
}
