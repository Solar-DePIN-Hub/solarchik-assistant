package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import net.solardepin.solarchik.circle.AnyChain
import net.solardepin.solarchik.circle.PayRequest
import net.solardepin.solarchik.circle.PayRequestStore
import net.solardepin.solarchik.circle.PayWatch
import net.solardepin.solarchik.circle.SolanaPay
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.solana.Rpc
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.2.15 "Pay from any chain" (Aurora Intents via worker/sol-anychain.js): link creation, share text, verified settlement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnyChainTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val owner = "8J3hQ1JZq8CkQ6CwRNrsdc9UHS1R1JZmE7vUfnTqC7ic"
    private val solver = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val ref = SolanaPay.newReference()
    private val calls = mutableListOf<Triple<String, String, String?>>()
    private var paidTxs = "[]"
    private var status = "open"

    @Before fun setUp() {
        app.getSharedPreferences(PayRequestStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        AnyChain.http = { m, p, b ->
            calls += Triple(m, p, b)
            when {
                m == "POST" && p == "/anychain/request" -> 200 to """{"id":"abcde12345","url":"${SolarchikConfig.ANYCHAIN_URL}/r/abcde12345"}"""
                m == "GET" && p == "/anychain/request/abcde12345" -> 200 to """{"id":"abcde12345","status":"$status","paid":{"via":"aurora-intents","solanaTxs":$paidTxs}}"""
                else -> 404 to "{}"
            }
        }
    }
    @After fun tearDown() { AnyChain.http = null; PayWatch.rpcForTest = null }

    private fun request(token: String = "USDC", amount: Double = 5.0) =
        PayRequest("pr1", "a1", "Ira", owner, amount, token, ref, "Lunch", "", System.currentTimeMillis()).also { PayRequestStore(app).save(listOf(it)) }

    /** A settlement transfer sent by the intents solver: no Solana Pay reference among the keys. */
    private fun settleTx(post: String = "7000000"): JsonElement = Json.parseToJsonElement("""
        {"meta":{"err":null,"preBalances":[1,2,3],"postBalances":[1,2,3],
          "preTokenBalances":[{"accountIndex":2,"mint":"${SolanaPay.USDC}","owner":"$owner","uiTokenAmount":{"amount":"2000000","decimals":6}}],
          "postTokenBalances":[{"accountIndex":2,"mint":"${SolanaPay.USDC}","owner":"$owner","uiTokenAmount":{"amount":"$post","decimals":6}}]},
         "transaction":{"message":{"accountKeys":["$solver","AtaSolver111111111111111111111111111111111","AtaOwner1111111111111111111111111111111111"]}}}""")

    private class Chain(val txs: Map<String, JsonElement>) : Rpc("https://example.invalid") {
        val asked = mutableListOf<String>()
        override suspend fun call(method: String, params: JsonArray): JsonElement = when (method) {
            "getSignaturesForAddress" -> Json.parseToJsonElement("[]") // nothing ever touched the reference
            "getTransaction" -> { val s = (params[0] as JsonPrimitive).content; asked += s; txs[s] ?: JsonNull }
            else -> error(method)
        }
    }

    @Test fun createsThePageOnceAndPutsItInTheShareText() = runBlocking {
        val r = request()
        assertTrue(AnyChain.supports(r))
        assertFalse("SKR has no any-chain route", AnyChain.supports(r.copy(token = "SKR")))
        val made = AnyChain.ensure(app, r, from = "Vadym")
        assertEquals("abcde12345", made.anyId)
        assertEquals("${SolarchikConfig.ANYCHAIN_URL}/r/abcde12345", made.anyUrl)
        assertEquals("persisted", made.anyUrl, PayRequestStore(app).byId("pr1")!!.anyUrl)
        val sent = JSONObject(calls.single().third!!)
        assertEquals(owner, sent.getString("to")); assertEquals("5", sent.getString("amount")); assertEquals("USDC", sent.getString("token"))
        assertEquals(ref, sent.getString("reference")); assertEquals("Ira", sent.getString("who")); assertEquals("Vadym", sent.getString("from"))
        AnyChain.ensure(app, made)
        assertEquals("no second page for the same request", 1, calls.size)
        val text = SolanaPay.shareText(made)
        assertTrue(text, text.startsWith("Hi Ira, here's my payment link for 5 USDC: solana:$owner?"))
        assertTrue(text, text.endsWith("No Solana wallet? Pay from any network (USDC on Base, USDT on Tron, ETH, BTC…): ${made.anyUrl}"))
        assertFalse("no line without a page", SolanaPay.shareText(r).contains("any network"))
    }

    @Test fun aServerAnsweringSomeOtherUrlIsIgnored() = runBlocking {
        AnyChain.http = { _, _, _ -> 200 to """{"id":"abcde12345","url":"https://evil.example/r/abcde12345"}""" }
        assertEquals("", AnyChain.ensure(app, request()).anyUrl)
        AnyChain.http = { _, _, _ -> 503 to """{"error":"AURORA_NOT_CONFIGURED"}""" }
        assertEquals("offline/unconfigured: the Solana Pay link still works alone", "", AnyChain.ensure(app, request()).anyUrl)
    }

    @Test fun paidOnlyAfterTheReportedSolanaTransactionReallyPaidTheUser() = runBlocking {
        val r = AnyChain.ensure(app, request())
        PayWatch.rpcForTest = Chain(mapOf("S1" to settleTx()))
        assertTrue("not settled yet", PayWatch.checkAll(app, notify = false).isEmpty())

        // the worker claims paid, but the transaction it names paid less than asked: not paid
        status = "paid"; paidTxs = """["Slow"]"""
        PayWatch.rpcForTest = Chain(mapOf("Slow" to settleTx(post = "6999999")))
        assertTrue(PayWatch.checkAll(app, notify = false).isEmpty())
        assertTrue(PayWatch.lastMismatch, PayWatch.lastMismatch.contains("any-chain: amount not transferred"))

        // a transaction the chain doesn't know: not paid
        paidTxs = """["Ghost"]"""
        PayWatch.rpcForTest = Chain(emptyMap())
        assertTrue(PayWatch.checkAll(app, notify = false).isEmpty())

        paidTxs = """["S1"]"""
        val chain = Chain(mapOf("S1" to settleTx()))
        PayWatch.rpcForTest = chain
        val paid = PayWatch.checkAll(app, notify = false)
        assertEquals(listOf("pr1"), paid.map { it.id })
        assertEquals("S1", PayRequestStore(app).byId(r.id)!!.signature)
        assertEquals(PayRequest.PAID, PayRequestStore(app).byId(r.id)!!.status)
        assertEquals(listOf("S1"), chain.asked)
    }

    @Test fun theSolanaPayPathStillNeedsTheReference() {
        val r = request()
        assertEquals("reference not found", SolanaPay.validate(settleTx(), r))
        assertEquals(null, SolanaPay.validate(settleTx(), r, requireReference = false))
    }
}
