package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.solardepin.solarchik.agents.StrategyApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Hackathon (judges) build facts: launcher name, the server the app talks to, every route it calls exists there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JudgesBuildTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Every /api/native route the app posts to (StrategyPanel). */
    private val routes = listOf(
        "market-list", "strategy-info", "strategy-validate", "strategy-prepare", "strategy-confirm",
        "market-prepare-list", "market-confirm-list", "market-unlist", "market-prepare-buy", "market-confirm-buy", "faucet-drip",
    )

    @Test fun launcherIsSolarchikAssistant() {
        assertEquals("Solarchik Assistant", ctx.getString(R.string.app_name))
    }

    @Test @Config(qualifiers = "uk") fun launcherIsSolarchikAssistantUk() {
        assertEquals("Соларчик Асистент", ctx.getString(R.string.app_name))
    }

    @Test fun serverIsTheJudgesDeployment() {
        assertEquals("https://solarchik-market.vercel.app", StrategyApi.BASE)
        val src = File("src/main/java/net/solardepin/solarchik").walkTopDown().filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse("old server without /api/native", "solarchik-super-app" in src)
        // 0.21.7: buy / strategy change moved into StrategyFlows (shared with Sol's confirmed actions).
        val panel = File("src/main/java/net/solardepin/solarchik/ui/StrategyPanel.kt").readText() +
            File("src/main/java/net/solardepin/solarchik/agents/StrategyFlows.kt").readText()
        val called = Regex("""(?:post|confirm|api)\("([a-z-]+)"""").findAll(panel).map { it.groupValues[1] }.toSet()
        assertEquals(routes.toSet(), called)
    }

    @Test fun serverTextsFollowPhoneLanguage() {
        assertEquals("uk", StrategyApi.lang(java.util.Locale("uk", "UA")))
        assertEquals("en", StrategyApi.lang(java.util.Locale.US))
        assertEquals("en", StrategyApi.lang(java.util.Locale.GERMANY))
        val b = StrategyApi.withLang(JsonObject(emptyMap()), java.util.Locale.US)
        assertEquals("en", (b["lang"] as JsonPrimitive).content)
    }

    @Test @Config(qualifiers = "uk") fun ukClassTitlesAreUkrainian() {
        assertEquals("Агент прогнозів", ctx.getString(R.string.class_pred_title))
        assertEquals("Комбо-агент", ctx.getString(R.string.class_combo_title))
    }

    /** Opt-in (-PliveAgents=1): each route answers JSON on the live server (a refusal for an empty body is fine; 404 is not). */
    @Test fun liveServerHasEveryRoute() = runBlocking {
        assumeTrue(System.getProperty("solarchik.liveAgents") == "1")
        for (r in routes) {
            val body = StrategyApi.post(r, JsonObject(emptyMap()))
            val ok = (body["ok"] as? JsonPrimitive)?.booleanOrNull
            assertNotNull("$r: no ok field in $body", ok)
            if (r == "market-list") assertTrue("market-list must list", ok == true)
            else assertTrue("$r: $body", ok == true || (body["reason"] != null && !StrategyApi.reason(body).startsWith("server ")))
            if (StrategyApi.lang() == "en") assertFalse("$r: Ukrainian reason for an English phone: $body", Regex("[А-Яа-яІіЇїЄєҐґ]").containsMatchIn(StrategyApi.reason(body)))
        }
    }
}
