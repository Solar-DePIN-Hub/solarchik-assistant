package net.solardepin.solarchik

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.wallet.WalletError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.1.6: Phantom opened but never started the MWA session. The clientlib's association failures are
 * reported as "the wallet never showed the request" (not "something went wrong", not "cancelled"),
 * and a late failure never pops up on a tab the user has moved to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release116Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before fun fresh() {
        MainActivity.tickerEnabled = false
        MainActivity.gameHub = false
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
    }

    @org.junit.After fun restore() { MainActivity.tickerEnabled = true }

    @Test fun versionIs117() = assertEquals("1.2.6.1", BuildConfig.VERSION_NAME)

    @Test fun associationFailuresAreNotDeclinesAndSayTheWalletNeverAsked() {
        val assoc = app.getString(R.string.err_d_assoc)
        for (m in listOf(
            "Timed out waiting for local association to be ready",
            "Failed establishing local association with wallet",
            "Local association was cancelled before connected",
        )) {
            val e = WalletError.classify(m, null)
            assertEquals(m, WalletError.Kind.FAILED, e.kind)
            assertFalse(m, e.authRejected)
            assertTrue(m, WalletError.text(app, e).contains(assoc))
        }
        // a real decline is still a decline
        assertEquals(WalletError.Kind.DECLINED, WalletError.classify("User did not authorize signing", null).kind)
    }

    private fun texts(v: View, out: MutableList<String>) {
        if (v is TextView && v.visibility == View.VISIBLE) out += v.text.toString()
        if (v is ViewGroup) for (k in 0 until v.childCount) texts(v.getChildAt(k), out)
    }

    @Test fun lateWalletFailureStaysOffOtherTabs() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val err = WalletError.classify("Timed out waiting for local association to be ready", null)
        val msg = WalletError.connectText(a, err) // 1.2.5: connect failures in plain words
        assertTrue(msg, msg.startsWith("The wallet opened but didn't start the connection"))
        val other = if (a.current == MainActivity.Tab.AGENTS) MainActivity.Tab.TODAY else MainActivity.Tab.AGENTS
        a.walletFailed(other, err)
        shadowOf(Looper.getMainLooper()).idle()
        val shown = mutableListOf<String>(); texts(a.window.decorView, shown)
        assertFalse(shown.any { it == msg })
        a.walletFailed(a.current, err)
        shadowOf(Looper.getMainLooper()).idle()
        val now = mutableListOf<String>(); texts(a.window.decorView, now)
        assertTrue(now.any { it == msg })
    }
}
