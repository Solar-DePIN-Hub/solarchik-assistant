package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import kotlinx.coroutines.runBlocking
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.wallet.Base58
import org.sol4k.PublicKey
import net.solardepin.solarchik.wallet.MwaDirect
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.2.6 tablet bug: Circle "Send" showed Phantom's Connect prompt, then Phantom went home and nothing was signed.
 * Now the payment is built (fresh blockhash) before the wallet opens, and authorize + signAndSendTransactions run
 * in ONE wallet session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaySessionTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val me = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
    private val ira = "HpEVVYWx2LiFANXAzfMy3yPTf61X1ZVDheYDYNmDJBwT"
    private val realCluster = System.getProperty("solarchik.cluster")

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

    @Test fun buildFirstThenAuthorizeAndSignInTheSameSession() = runBlocking {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", me).putString("auth", "old-token").commit()
        val w = SolanaWallet(app)
        assertTrue(w.mainnet)
        w.rpcOverride = ChainStub()
        val events = ArrayList<String>()
        var sessions = 0
        MwaDirect.FAKE_CLIENT = MobileWalletAdapterClient(1000)
        MwaDirect.transactOverride = { _ ->
            sessions++; events += "session $sessions: authorize"
            Result.success(MobileWalletAdapterClient.AuthorizationResult.create("tok", Base58.decode(me), "Phantom", null))
        }
        w.signSeam = { bytes -> events += "session $sessions: sign ${bytes.size} bytes"; ByteArray(64) { 7 } }
        val r = w.signAndSend(a.sender) { payer, bh ->
            events += "build for ${payer.toBase58().take(4)}"
            CallActionRules.paymentTx(payer, PublicKey(ira), "SOL", CallActionRules.amountRaw("SOL", 0.0001), bh)
        }
        assertTrue(r.exceptionOrNull()?.toString() ?: "", r.isSuccess)
        assertEquals(1, sessions)
        assertEquals(1, w.signRequests)
        assertEquals(listOf("build for 8J3h", "session 1: authorize", "session 1: sign " + events[2].substringAfter("sign ").substringBefore(" bytes") + " bytes"), events)
        assertEquals(Base58.encode(ByteArray(64) { 7 }), r.getOrThrow().signature)
    }
}
