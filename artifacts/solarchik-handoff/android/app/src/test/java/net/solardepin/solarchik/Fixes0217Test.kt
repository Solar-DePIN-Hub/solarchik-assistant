package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.solardepin.solarchik.core.AppLocale
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.game.RunStartGate
import net.solardepin.solarchik.sol.NeuralVoice
import net.solardepin.solarchik.sol.SolRules
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.solana.RpcException
import net.solardepin.solarchik.ui.AgentNames
import net.solardepin.solarchik.ui.StrategyPanel
import net.solardepin.solarchik.wallet.WalletError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** 0.21.7 fixes: rules-only answers, language follow-the-phone, voice cache, run gate, RPC retries, wallet lines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fixes0217Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun solRulesAnswerOnlyExplicitRuleQuestions() {
        assertNotNull(SolRules.answer(app, "What is a fee-free window?"))
        assertNull(SolRules.answer(app, "I took a risk today and it felt great"))
        assertNull(SolRules.answer(app, "buy the Calm Hourly BTC NFT"))
    }

    @Test fun languageFollowsThePhoneUnlessChosen() {
        assertEquals("uk", AppLocale.resolve(AppLocale.FOLLOW, Locale("uk", "UA")))
        assertEquals("en", AppLocale.resolve(AppLocale.FOLLOW, Locale("ru", "RU")))
        assertEquals("en", AppLocale.resolve(AppLocale.FOLLOW, Locale.GERMANY))
        assertEquals("uk", AppLocale.resolve(AppLocale.UK, Locale.US))
        assertEquals("en", AppLocale.resolve(AppLocale.EN, Locale("uk", "UA")))
    }

    @Test fun neuralVoiceCachesClips() = runBlocking {
        var calls = 0
        val wav = "RIFF".toByteArray() + ByteArray(2000)
        NeuralVoice.fetcher = { _, _ -> calls++; wav }
        try {
            val a = NeuralVoice.clip(app, "Привіт, сонечко!", "uk")
            val b = NeuralVoice.clip(app, "Привіт, сонечко!", "uk")
            assertNotNull(a); assertEquals(a, b); assertEquals(1, calls)
            NeuralVoice.fetcher = { _, _ -> "nope".toByteArray() }
            assertNull("not a WAV: no clip, the system voice speaks", NeuralVoice.clip(app, "other line", "en"))
        } finally {
            NeuralVoice.fetcher = null
        }
    }

    @Test fun runStartsOnlyAfterWarmUp() {
        assertFalse(RunStartGate.shouldCreate(warmed = false, hasSetup = true, restartRequested = false, hasState = false))
        assertTrue(RunStartGate.shouldCreate(warmed = true, hasSetup = true, restartRequested = false, hasState = false))
        assertFalse(RunStartGate.shouldCreate(warmed = true, hasSetup = true, restartRequested = false, hasState = true))
        assertTrue(RunStartGate.shouldCreate(warmed = true, hasSetup = true, restartRequested = true, hasState = true))
    }

    @Test fun agentNamesInUkrainian() {
        assertEquals("Біткоїн-вікна", AgentNames.uk("Bitcoin Windows"))
        assertTrue(AgentNames.uk("Combo Prime Pro").endsWith("· Про"))
    }

    /** Scripted transport: answers in order; records the URL of every attempt. */
    private class ScriptRpc(url: String, vararg answers: Any) : Rpc(url) {
        val queue = ArrayDeque(answers.toList())
        val hits = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        override fun post(target: String, body: String): String {
            hits += target
            return when (val a = queue.removeFirst()) {
                is Int -> throw RpcException("HTTP $a", a)
                is java.io.IOException -> throw a
                else -> a as String
            }
        }
        override suspend fun pause(ms: Long) { waits += ms }
    }

    private val ok = """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},"value":42}}"""

    @Test fun rpcRetries429Then5xxThenSucceeds() = runBlocking {
        val r = ScriptRpc(SolarchikConfig.RPC_DEVNET, 429, 503, ok)
        assertEquals(42L, r.balanceLamports("x"))
        assertEquals(listOf(300L, 900L), r.waits)
        assertEquals(3, r.hits.count { it == SolarchikConfig.RPC_DEVNET })
    }

    @Test fun rpcFallsBackToTheProxyOnceAfterRetries() = runBlocking {
        val r = ScriptRpc(SolarchikConfig.RPC_DEVNET, 429, 429, 429, ok)
        assertEquals(42L, r.balanceLamports("x"))
        assertEquals(SolarchikConfig.RPC_DEVNET_FALLBACK, r.hits.last())
        assertEquals(4, r.hits.size)
    }

    @Test fun rpcDoesNotRetryAirdropsOrRealErrors() = runBlocking {
        val air = ScriptRpc(SolarchikConfig.RPC_DEVNET, 429)
        assertEquals(429, runCatching { air.requestAirdrop("x", 1) }.exceptionOrNull().let { (it as RpcException).code })
        assertEquals(1, air.hits.size)
        val bad = ScriptRpc(SolarchikConfig.RPC_DEVNET, 400)
        assertTrue(runCatching { bad.balanceLamports("x") }.isFailure)
        assertEquals(1, bad.hits.size)
        // 1.1.0: mainnet falls back to PublicNode (never to the devnet proxy)
        val main = ScriptRpc(SolarchikConfig.RPC_MAINNET, 429, 429, 429, ok)
        assertEquals(42L, main.balanceLamports("x"))
        assertEquals(SolarchikConfig.RPC_MAINNET_FALLBACK, main.hits.last())
        assertEquals(4, main.hits.size)
        val other = ScriptRpc("https://example.invalid/rpc", 429, 429, 429)
        assertTrue("an unknown node has no fallback", runCatching { other.balanceLamports("x") }.isFailure)
        assertEquals(3, other.hits.size)
    }

    @Test @Config(qualifiers = "uk")
    fun walletLinesAreLocalizedWithoutRawText() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Помилка гаманця: гаманець не надіслав підпис", WalletError.text(ctx, WalletError(WalletError.Kind.FAILED, "Wallet sent no signature")))
        assertEquals("Помилка гаманця: гаманець підключився без рахунку", WalletError.text(ctx, WalletError(WalletError.Kind.FAILED, "Wallet connected without account")))
        assertEquals("Помилка гаманця: гаманець не повернув транзакцію", WalletError.text(ctx, WalletError(WalletError.Kind.FAILED, "Wallet returned no transaction")))
        val raw = WalletError.text(ctx, WalletError(WalletError.Kind.FAILED, "JSON-RPC remote exception -32603 Internal error"))
        assertFalse(raw, raw.contains("JSON") || raw.contains("Internal"))
        assertEquals("Помилка гаманця: щось пішло не так", raw)
        assertFalse(WalletError.text(ctx, IllegalStateException("boom")).contains("boom"))
    }

    @Test fun faucetViaAirdropReadsWell() {
        val air = StrategyPanel.faucetText(app, buildJsonObject { put("ok", true); put("sig", "5abcdefghijkLMNOPQ"); put("via", "airdrop") })
        assertTrue(air, air.startsWith("Got devnet SOL from the public devnet faucet"))
        val own = StrategyPanel.faucetText(app, buildJsonObject { put("ok", true); put("sig", "5abcdefghijkLMNOPQ"); put("via", "faucet") })
        assertTrue(own, own.startsWith("Server faucet sent devnet SOL"))
        buildJsonArray { }
    }
}
