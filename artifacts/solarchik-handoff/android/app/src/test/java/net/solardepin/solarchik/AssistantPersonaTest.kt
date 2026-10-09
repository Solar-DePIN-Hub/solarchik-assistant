package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.season.SeasonStore
import net.solardepin.solarchik.sol.ActContext
import net.solardepin.solarchik.sol.AssistantContext
import net.solardepin.solarchik.sol.SolBrain
import net.solardepin.solarchik.ui.SolScreen
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate
import java.util.TimeZone

/**
 * 1.0.1: every Sol chat message from Solarchik Assistant carries `app: "assistant"` (the worker then uses the
 * pocket-assistant prompt) and a factual context block built from the phone: calls, follow-ups, wallet, Season.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class AssistantPersonaTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet", "solarchik.calls",
            "solarchik.calls.remind", "solarchik.followups", "solarchik.season", MainActivity.ASSISTANT_PREFS, "solarchik-game")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
    }

    private fun calls(now: Long) = CallInbox.parse("owner-test", 200, JSONObject().put("items", JSONArray()
        .put(JSONObject().put("callId", "rtc_p1").put("caller", "+380671112233").put("text", "Olena: move the meeting").put("at", now - 25 * 60_000L).put("status", "done").put("lang", "en")
            .put("summary", JSONObject().put("caller_name", "Olena").put("intent", "Wants to move Friday's meeting to 3 pm").put("urgency", "normal").put("notes", "").put("callback", "+380671112233")))
        .put(JSONObject().put("callId", "rtc_p2").put("caller", "+380931234567").put("text", "Blocked caller").put("at", now - 60 * 60_000L).put("status", "blocked")
            .put("summary", JSONObject().put("caller_name", "").put("intent", "").put("urgency", "normal").put("notes", "").put("callback", "")))
    ).toString())!!

    @Test fun everyChatMessageSaysItIsTheAssistant() {
        val body = SolBrain().body("what can you do?", "en", "yard", ActContext(emptyList(), emptyList(), false), emptyList(), "Calls today (1): Olena.")
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("assistant", (o["app"] as JsonPrimitive).content)
        assertEquals("yard", (o["scene"] as JsonPrimitive).content)
        assertTrue(o["context"].toString().contains("Olena"))
        // the run keeps its own scene (the worker gives runs the game prompt)
        val run = Json.parseToJsonElement(SolBrain().body("how far?", "en", "run", ActContext(emptyList(), emptyList(), false), emptyList())).jsonObject
        assertEquals("run", (run["scene"] as JsonPrimitive).content)
    }

    @Test fun contextIsFactualAndShort() {
        val now = System.currentTimeMillis()
        val plan = SeasonStore.plan(app, signedToday = false, clockedToday = false, day = LocalDate.now())
        val s = AssistantContext.build(calls(now), emptyList(), AssistantContext.Wallet(false, false, ""), plan, secretaryOn = true, now = now, zone = TimeZone.getTimeZone("Europe/Kyiv"))
        assertTrue(s, s.startsWith("Now: "))
        assertTrue(s, s.contains("(Europe/Kyiv)"))
        assertTrue(s, s.contains("Phone secretary: on."))
        assertTrue(s, s.contains("Calls today (1): Olena") && s.contains("Wants to move Friday's meeting") && s.contains("callback +380671112233"))
        assertFalse("blocked callers are left out", s.contains("+380931234567"))
        assertTrue(s, s.contains("Follow-ups: none."))
        assertTrue(s, s.contains("Agent wallet: not connected (Solana devnet)."))
        assertTrue(s, s.contains("Seeker Season plan: 0/3 done; left: open ${plan.suggestion.name}, do the daily check-in."))
        assertTrue(s.length <= AssistantContext.MAX)
        val empty = AssistantContext.build(emptyList(), emptyList(), AssistantContext.Wallet(false, false, ""), plan, false, now)
        assertTrue(empty, empty.contains("No calls yet."))
    }

    @Test fun solScreenSendsThePhoneState() {
        val now = System.currentTimeMillis()
        CallInbox.store(app, calls(now))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        repeat(5) { ShadowLooper.idleMainLooper() }
        a.select(MainActivity.Tab.SOL)
        val s = a.screen(MainActivity.Tab.SOL) as SolScreen
        val ctx = s.assistantContext(now)
        assertTrue(ctx, ctx.contains("Calls today (1): Olena"))
        assertTrue(ctx, ctx.contains("Follow-ups: call back Olena."))
        assertTrue(ctx, ctx.contains("Seeker Season plan: 1/3 done"))
    }
}
