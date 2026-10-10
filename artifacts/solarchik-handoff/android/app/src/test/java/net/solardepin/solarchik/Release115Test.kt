package net.solardepin.solarchik

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import net.solardepin.solarchik.sol.AssistantRules
import net.solardepin.solarchik.wallet.ICON_RELATIVE_URI
import net.solardepin.solarchik.wallet.IDENTITY_NAME
import net.solardepin.solarchik.wallet.IDENTITY_URI
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

/**
 * 1.1.5: the wallet connect (Phantom opened but never got an authorize request), the Today wallet card that read
 * "Create wallet", the Saver's connect hint and the shorter "what can you do" answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release115Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    private val realCluster = System.getProperty("solarchik.cluster")

    @org.junit.After fun restore() {
        MainActivity.tickerEnabled = true
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
    }

    @Before fun fresh() {
        // mainnet as in the release build (the suite default is dev devnet)
        System.setProperty("solarchik.cluster", "mainnet")
        MainActivity.tickerEnabled = false
        MainActivity.gameHub = false
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (k in 0 until v.childCount) find(v.getChildAt(k), tag)?.let { return it }
        return null
    }

    /** The real MWA clientlib validates the identity before it sends authorize; 1.1.4's absolute icon failed right there. */
    private fun authorizeError(icon: String): Throwable? = try {
        MobileWalletAdapterClient(1000).authorize(Uri.parse(IDENTITY_URI), Uri.parse(icon), IDENTITY_NAME, "solana:mainnet")
        null
    } catch (t: Throwable) { t }

    @Test fun walletIdentityPassesTheClientlibCheck() {
        val old = authorizeError("https://solardepin.net/favicon.ico")
        assertTrue("1.1.4's absolute icon is refused by the clientlib", old is IllegalArgumentException)
        val now = authorizeError(ICON_RELATIVE_URI)
        assertFalse("the 1.1.5 identity must pass the identity check: $now", now is IllegalArgumentException)
        assertTrue(Uri.parse(ICON_RELATIVE_URI).isRelative)
        assertTrue(Uri.parse(IDENTITY_URI).isAbsolute && Uri.parse(IDENTITY_URI).isHierarchical)
    }

    @Test fun todayWalletCardSaysConnectYourWallet() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }
        assertTrue(a.wallet.mainnet)
        val b = find(a.window.decorView, "today-wallet-setup") as TextView?
        assertNotNull(b)
        assertTrue(b!!.text.toString().contains(a.getString(R.string.mn_today_wallet_connect)))
        assertTrue(b.text.toString().contains("Phantom"))
        val card = find(a.window.decorView, "today-wallet") as ViewGroup
        assertTrue(texts(card).contains(a.getString(R.string.mn_today_wallet_title)))
    }

    @Test fun saverOnWithoutAWalletAsksToConnectFirst() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }
        assertTrue(a.wallet.mainnet)
        a.select(MainActivity.Tab.AGENTS); idle()
        val agents = a.screen(MainActivity.Tab.AGENTS) as net.solardepin.solarchik.ui.AgentsScreen
        val p = agents.saverPanel
        p.store.setPolicy(p.store.policy().copy(enabled = true))
        val card = p.card()
        assertNotNull(find(card, "sv-connect-first"))
        assertNotNull(find(card, "sv-connect"))
    }

    @Test fun whatCanYouDoIsShortAndNotTheSameTwice() {
        val first = AssistantRules.answer(app, "What can you do for me?", emptyList())!!
        val second = AssistantRules.answer(app, "What can you do for me?", emptyList())!!
        assertNotEquals(first, second)
        val long = app.getString(R.string.as_skills)
        assertNotEquals(long, first)
        assertTrue(first.length < long.length / 2 + 40)
        assertTrue(second.length < long.length / 2 + 40)
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
}
