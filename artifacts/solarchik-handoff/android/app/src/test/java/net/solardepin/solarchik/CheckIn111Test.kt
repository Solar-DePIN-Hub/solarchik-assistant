package net.solardepin.solarchik

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.game.ClockIn
import net.solardepin.solarchik.game.GameSave
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 1.1.1: in the assistant the daily check-in is one tap (no run in Play needed); streak logic unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class CheckIn111Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() { MainActivity.tickerEnabled = false; MainActivity.onboardingEnabled = false }
    @After fun tearDown() { MainActivity.tickerEnabled = true }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    @Test fun checkInIsOpenWithoutARun() {
        assertFalse(SolarchikConfig.CHECKIN_NEEDS_RUN)
        val save = GameSave(app)
        assertTrue(save.todayDistance() == 0)
        assertFalse("the game's run gate itself is unchanged", save.clockedToday())
        assertTrue(save.checkInOpen())
        assertTrue(ClockIn.ready(save))
    }

    @Test fun todayAndSeasonOfferTheOneTapCheckIn() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        repeat(5) { ShadowLooper.idleMainLooper() }
        // 1.2.7: no check-in tile on Today; the optional "Sign on Solana" chip shows once the stack is clear
        val t = texts(a.window.decorView)
        assertFalse(t.any { it.startsWith("Run 1200 m") })
        a.select(MainActivity.Tab.SEASON); repeat(5) { ShadowLooper.idleMainLooper() }
        val s = texts(a.window.decorView)
        assertTrue(s.filter { it.contains("check", true) }.toString(), s.any { it.startsWith("One tap: sign today's check-in in your wallet") })
        assertTrue(s.any { it.trim().trim('\u2060').trim() == "Check in" })
        assertFalse(s.any { it.contains("1200 m run") })
    }
}
