package net.solardepin.solarchik

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import kotlinx.coroutines.launch
import net.solardepin.solarchik.wallet.MwaDirect
import net.solardepin.solarchik.wallet.MwaUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 1.2.2: the MWA association intent of both connect paths has exactly the spec shape. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release122Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val spec = Regex("^solana-wallet:/v1/associate/local\\?association=[A-Za-z0-9_-]{87}&port=(\\d{5})(&v=[A-Za-z0-9.]+)*$")

    @Test fun version() {
        assertEquals("1.2.8", BuildConfig.VERSION_NAME)
        assertTrue(BuildConfig.VERSION_CODE >= 122)
    }

    private fun assertSpec(u: Uri?, label: String) {
        val s = u.toString()
        println("$label association URI: $s")
        val m = spec.find(s)
        assertNotNull("$label: $s", m)
        assertTrue(s, m!!.groupValues[1].toInt() in 49152..65535)
        assertTrue(s, !s.contains("//") && !s.contains("localAssociationServer") && !s.contains("192.168"))
        assertEquals(MwaUri.problems(u).joinToString(), 0, MwaUri.problems(u).size)
    }

    @Test fun directPathUsesTheClientlibIntentWithTheSpecShape() {
        val sc = LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        val i = MwaDirect.associationIntent(sc, "app.phantom")
        assertEquals("android.intent.action.VIEW", i.action)
        assertTrue(i.categories.contains("android.intent.category.BROWSABLE"))
        assertEquals("app.phantom", i.`package`)
        assertSpec(i.data, "direct")
        assertEquals(sc.port.toString(), i.data!!.getQueryParameter("port"))
        // the app is the WebSocket client of the wallet's server on this device
        assertEquals("ws://127.0.0.1:" + sc.port + "/solana-wallet", MwaUri.wsTarget(sc))
        assertEquals("com.solana.mobilewalletadapter.v1", MwaUri.SUBPROTOCOL)
        assertTrue(MwaUri.summary(i.data).startsWith("spec OK solana-wallet:/v1/associate/local?association=…&port=…"))
        sc.close()
    }

    @Test fun libraryFallbackPathSendsTheSameShape() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val mwa = MobileWalletAdapter(ConnectionIdentity(Uri.parse("https://solardepin.net"), Uri.parse("favicon.ico"), "Solarchik"))
        val job = a.scope.launch { runCatching { mwa.connect(a.sender) } }
        var started: android.content.Intent? = null
        repeat(50) { if (started == null) { ShadowLooper.idleMainLooper(); Thread.sleep(20); started = shadowOf(a).nextStartedActivityForResult?.intent } }
        job.cancel()
        assertNotNull("clientlib-ktx never started the wallet intent", started)
        assertSpec(started!!.data, "clientlib-ktx")
    }

    @Test fun badUrisAreCalledOut() {
        val bad = Uri.parse("solana-wallet://v1/associate?localAssociationServer=ws%3A%2F%2F192.168.0.103%3A35125%2F&port=35125")
        val p = MwaUri.problems(bad)
        assertTrue(p.toString(), p.any { it.startsWith("has a host") } && p.any { it.startsWith("unknown params") } && p.any { it.startsWith("port=") })
        assertTrue(MwaUri.summary(bad).startsWith("NOT SPEC"))
    }
}
