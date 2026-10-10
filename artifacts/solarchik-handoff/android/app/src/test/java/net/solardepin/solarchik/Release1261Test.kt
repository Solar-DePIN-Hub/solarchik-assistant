package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.FollowUps
import net.solardepin.solarchik.sol.ChatTurn
import net.solardepin.solarchik.ui.SolScreen
import net.solardepin.solarchik.wallet.Base58
import net.solardepin.solarchik.wallet.MwaDirect
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sol4k.PublicKey

/** 1.2.6.1: the tablet payment failure and the small bugs from the same recording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release1261Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val me = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
    private val ira = "HpEVVYWx2LiFANXAzfMy3yPTf61X1ZVDheYDYNmDJBwT"
    private val realCluster = System.getProperty("solarchik.cluster")

    /** Mainnet-like node: the blockhash answer carries its context slot; simulation answers as told. */
    class SlotChain(var simErr: String? = null) : net.solardepin.solarchik.solana.Rpc("http://stub.invalid") {
        val calls = ArrayList<String>()
        override suspend fun call(method: String, params: JsonArray): JsonElement {
            calls += method
            return when (method) {
                "getLatestBlockhash" -> buildJsonObject {
                    put("context", buildJsonObject { put("slot", 455_336_079L) })
                    put("value", buildJsonObject { put("blockhash", Base58.encode(ByteArray(32) { 7 })); put("lastValidBlockHeight", 9) })
                }
                "simulateTransaction" -> buildJsonObject {
                    put("value", buildJsonObject {
                        if (simErr == null) put("err", JsonNull) else put("err", buildJsonObject { put(simErr!!, buildJsonObject { put("account_index", 1) }) })
                        put("logs", buildJsonArray { add(JsonPrimitive("Program 11111111111111111111111111111111 invoke [1]")) })
                    })
                }
                "getAccountInfo" -> buildJsonObject { put("context", buildJsonObject { put("slot", 1) }); put("value", JsonNull) }
                else -> JsonNull
            }
        }
    }

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

    private fun wallet(chain: SlotChain): Pair<MainActivity, SolanaWallet> {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", me).putString("auth", "tok").commit()
        val w = SolanaWallet(app)
        w.rpcOverride = chain
        MwaDirect.FAKE_CLIENT = MobileWalletAdapterClient(1000)
        MwaDirect.transactOverride = { _ -> Result.success(MobileWalletAdapterClient.AuthorizationResult.create("tok", Base58.decode(me), "Phantom", null)) }
        return a to w
    }

    /** Root cause: Phantom ignores sign_and_send_transactions without min_context_slot (MWA issue #1146). */
    @Test fun signRequestCarriesMinContextSlotFromTheBlockhash() = runBlocking {
        val chain = SlotChain()
        val (a, w) = wallet(chain)
        w.signSeam = { ByteArray(64) { 7 } }
        val r = w.signAndSend(a.sender) { payer, bh -> CallActionRules.paymentTx(payer, PublicKey(ira), "SOL", CallActionRules.amountRaw("SOL", 0.01), bh) }
        assertTrue(r.exceptionOrNull()?.toString() ?: "", r.isSuccess)
        assertEquals(455_336_079, w.lastMinSlot)
        assertTrue(chain.calls.toString(), chain.calls.indexOf("simulateTransaction") > chain.calls.indexOf("getLatestBlockhash"))
    }

    /** A transfer the chain would refuse never opens the wallet; the user reads why. */
    @Test fun simulationRefusalStopsBeforeTheWallet() = runBlocking {
        val chain = SlotChain(simErr = "InsufficientFundsForRent")
        val (a, w) = wallet(chain)
        var opened = 0
        MwaDirect.transactOverride = { _ -> opened++; Result.success(MobileWalletAdapterClient.AuthorizationResult.create("tok", Base58.decode(me), "Phantom", null)) }
        val r = w.signAndSend(a.sender) { payer, bh -> CallActionRules.paymentTx(payer, PublicKey(ira), "SOL", CallActionRules.amountRaw("SOL", 0.0001), bh) }
        assertTrue(r.isFailure)
        assertEquals(0, opened)
        assertEquals(0, w.signRequests)
        val text = WalletError.text(a, r.exceptionOrNull())
        assertTrue(text, text.contains("new on Solana") && text.contains("0.001 SOL"))
        assertTrue(w.simulationText("{\"InstructionError\":[0,{\"Custom\":1}]}").startsWith("Not enough SOL"))
        assertTrue(w.simulationText("{\"InstructionError\":[1,\"InvalidAccountData\"]}").contains("none"))
    }

    private fun call(owner: String, id: String, text: String, at: Long, cb: String = "+380631112233") = CallItem(
        owner, id, "+380631112233", text, at, CallInbox.DONE, "sip", "Ira", "lunch", "", "", cb, "en", 65, null, true)

    @Test fun oneRowPerCallAndOneCallbackPerNumber() {
        val t = 1_760_000_000_000L
        val a = call("user", "rtc_1", "Ira asks you to send 0.01 SOL.", t)
        val b = call("owner", "rtc_1", "Ira asks you to send 0.01 SOL.", t)
        val c = call("owner", "rtc_2", "Ira asks you to send 0.01 SOL.", t + 20_000)
        val d = call("user", "rtc_3", "Ira again, about Monday.", t + 3_600_000)
        val list = CallInbox.merge(listOf(listOf(a, d), listOf(b, c)))
        assertEquals(list.map { it.callId }.toString(), 2, list.size)
        val f = FollowUps.build(list, { 0L }, emptySet(), t + 3_700_000)
        assertEquals(1, f.size)
        assertEquals("rtc_3", f[0].item.callId)
    }

    @Test fun englishChatHidesCyrillicAndTheStageBubbleNeverCutsAWord() {
        val turns = listOf(ChatTurn("assistant", "Ira, 10 жовт., 13:32: Вадим … Хотів передати просто привіт власнику", 1), ChatTurn("user", "who called?", 2))
        assertEquals(1, SolScreen.visibleTurns(turns, "en").size)
        assertEquals(2, SolScreen.visibleTurns(turns, "uk").size)
        val long = "You had 2 calls today. Ira asked you to send 0.01 SOL for lunch and to call back at three. She also reminded you about the meeting on Monday morning with the whole team at the office."
        val s = SolScreen.stageLine(long)
        assertTrue(s, s.length <= 150 && s.endsWith("."))
        assertFalse(s, s.contains("Mon…"))
    }

    @Test fun circleCopyAndVersion() {
        val body = app.getString(R.string.circle_body)
        assertTrue(body, body.contains("Only addresses you save yourself. Never one heard on a call."))
        assertFalse(body.contains("Addresses are only ones"))
    }
}
