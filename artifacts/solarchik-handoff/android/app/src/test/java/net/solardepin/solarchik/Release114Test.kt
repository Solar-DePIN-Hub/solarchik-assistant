package net.solardepin.solarchik

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.game.RunActivity
import net.solardepin.solarchik.ui.YardScreen
import net.solardepin.solarchik.ui.roof.RooftopScreen
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

/**
 * 1.1.4: Play in the assistant starts the rooftop run directly. The old game hub (the night rooftop yard with the
 * CLOCK IN building, and the run garage) can't be reached from any path, and a finished run returns to Today.
 * The check-in still opens its own card without a run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release114Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before fun fresh() {
        MainActivity.gameHub = false
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(i: Intent? = null): MainActivity {
        val c = if (i == null) Robolectric.buildActivity(MainActivity::class.java) else Robolectric.buildActivity(MainActivity::class.java, i)
        return c.setup().visible().get().also { idle() }
    }

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (k in 0 until v.childCount) find(v.getChildAt(k), tag)?.let { return it }
        return null
    }

    private fun assertRunStarted(a: MainActivity) {
        val next = shadowOf(a).nextStartedActivity
        assertNotNull("the run was not started", next)
        assertEquals(RunActivity::class.java.name, next.component!!.className)
    }

    @Test fun playTileStartsTheRunAndNeverShowsTheYard() {
        val a = launch()
        assertEquals(MainActivity.Tab.TODAY, a.current)
        find(a.window.decorView, "today-play")!!.performClick(); idle()
        assertRunStarted(a)
        assertEquals(MainActivity.Tab.TODAY, a.current)
        assertNull("the rooftop yard was built", a.screen(MainActivity.Tab.YARD))
        assertFalse(a.screen(MainActivity.Tab.TODAY) == null)
    }

    @Test fun everyGameHubTabStartsTheRunInstead() {
        val a = launch()
        for (tab in listOf(MainActivity.Tab.YARD, MainActivity.Tab.RUN)) {
            a.select(tab, animate = true); idle()
            assertRunStarted(a)
            assertEquals(MainActivity.Tab.TODAY, a.current)
        }
        assertNull(a.screen(MainActivity.Tab.YARD))
        assertNull(a.screen(MainActivity.Tab.RUN))
        assertTrue(a.screen(MainActivity.Tab.YARD) !is RooftopScreen)
    }

    @Test fun finishedOrAbandonedRunReturnsToToday() {
        val a = launch()
        a.select(MainActivity.Tab.AGENTS); idle()
        find(a.window.decorView, "nav-today")!!.performClick(); idle()
        find(a.window.decorView, "today-play")!!.performClick(); idle()
        val run = shadowOf(a).nextStartedActivity
        // the player is somewhere else when the run ends (e.g. the run was started from another screen)
        a.select(MainActivity.Tab.AGENTS); idle()
        shadowOf(a).receiveResult(run, Activity.RESULT_OK, null); idle()
        assertEquals(MainActivity.Tab.TODAY, a.current)
        a.select(MainActivity.Tab.SETTINGS); idle()
        a.playGame(); idle()
        shadowOf(a).receiveResult(shadowOf(a).nextStartedActivity, Activity.RESULT_CANCELED, null); idle()
        assertEquals(MainActivity.Tab.TODAY, a.current)
    }

    @Test fun signTodayFromTheRunStillOpensTheCheckIn() {
        val a = launch()
        a.playGame(); idle()
        val run = shadowOf(a).nextStartedActivity
        shadowOf(a).receiveResult(run, Activity.RESULT_OK, Intent().putExtra(RunActivity.EXTRA_SIGN, true)); idle()
        assertEquals(MainActivity.Tab.SHIFT, a.current)
        assertTrue(a.screen(MainActivity.Tab.SHIFT) is YardScreen)
    }

    @Test fun checkInTileOpensTheCheckInWithoutARun() {
        val a = launch()
        find(a.window.decorView, "today-checkin")!!.performClick(); idle()
        assertEquals(MainActivity.Tab.SHIFT, a.current)
        val next = shadowOf(a).nextStartedActivity
        assertTrue("check-in must not start the run", next == null || next.component?.className != RunActivity::class.java.name)
        assertNull(a.screen(MainActivity.Tab.YARD))
    }

    @Test fun restoredOrNotificationGameTabsOpenToday() {
        assertEquals(MainActivity.Tab.TODAY, MainActivity.startTab("YARD", null))
        assertEquals(MainActivity.Tab.TODAY, MainActivity.startTab(null, "RUN"))
        assertEquals(MainActivity.Tab.SHIFT, MainActivity.startTab(null, "SHIFT"))
        assertEquals(MainActivity.Tab.AGENTS, MainActivity.startTab("AGENTS", "YARD"))
        // a streak notification (it targets the old run garage) opens Today and doesn't start a run on its own
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.RUN.name)).setup().visible()
        idle()
        val a = c.get()
        assertEquals(MainActivity.Tab.TODAY, a.current)
        val started = shadowOf(a).nextStartedActivity
        assertTrue(started == null || started.component?.className != RunActivity::class.java.name)
        c.newIntent(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.YARD.name)); idle()
        assertEquals(MainActivity.Tab.TODAY, a.current)
        assertNull(a.screen(MainActivity.Tab.YARD))
    }

    @Test fun versionIs114() {
        assertEquals("1.1.5", BuildConfig.VERSION_NAME) // 1.1.5 keeps every 1.1.4 rule
        assertEquals(115, BuildConfig.VERSION_CODE)
    }
}
