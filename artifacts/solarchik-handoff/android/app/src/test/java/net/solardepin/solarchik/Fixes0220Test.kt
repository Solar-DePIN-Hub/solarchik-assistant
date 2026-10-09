package net.solardepin.solarchik

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import net.solardepin.solarchik.game.RunOverlay
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.sol.ActAgent
import net.solardepin.solarchik.sol.ActContext
import net.solardepin.solarchik.sol.ChatTurn
import net.solardepin.solarchik.sol.SolActions
import net.solardepin.solarchik.sol.SolBrain
import net.solardepin.solarchik.sol.SolGreeting
import net.solardepin.solarchik.sol.SolRules
import net.solardepin.solarchik.sol.SolState
import net.solardepin.solarchik.ui.AgentNames
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.ui.StrategyWords
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 0.22.0 owner fixes (device video of 0.21.9): stale streak, duplicate agents, plain strategy card, Slice, calls, run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk-w411dp-h914dp-xxhdpi")
class Fixes0220Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        SolanaWallet.walletAppCheck = realCheck
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v.visibility != View.VISIBLE) return out
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun allTexts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) allTexts(v.getChildAt(i), out)
        return out
    }

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    // ---- 2: Sol always uses the fresh streak ----

    private val signed = SolState(streak = 1, signedToday = true, clockedToday = true, todayMeters = 1340, signedAt = 5_000)

    @Test fun afterSigningSolSaysTheStreakAndDoesNotAskToRun() {
        val line = signed.line()
        assertTrue(line, line.startsWith("PLAYER STATE NOW"))
        assertTrue(line, line.contains("streak 1 day") && line.contains("already signed") && line.contains("Do not ask the player to run 1200 m"))
        val rule = SolRules.answer(app, "Яка в мене серія днів?", signed)!!
        assertTrue(rule, rule.contains("1 день") && rule.contains("вже підписано"))
        assertFalse(rule, rule.contains("Пробіжи"))
        // not signed yet: the reminder with the run is still right
        val before = SolRules.answer(app, "Яка в мене серія днів?", signed.copy(streak = 0, signedToday = false, clockedToday = false, todayMeters = 300, signedAt = 0))!!
        assertTrue(before, before.contains("0 днів") && before.contains("Пробіжи 1200"))
    }

    @Test fun everyMessageCarriesTheFreshStateFirstAndDropsPreSignatureTalk() {
        val body = SolBrain().body("Що робити?", "uk", "yard", ActContext(emptyList(), emptyList(), false), emptyList(), "Серія: 1 день. Сьогодні підписано.", signed)
        val o = Json.parseToJsonElement(body).jsonObject
        assertTrue(o["context"].toString().contains("PLAYER STATE NOW"))
        assertTrue(o["context"].toString().indexOf("PLAYER STATE NOW") < o["context"].toString().indexOf("Серія"))
        assertEquals("true", (o["state"]!!.jsonObject["signedToday"] as JsonPrimitive).content)
        val turns = listOf(
            ChatTurn("assistant", "Твоя серія зараз 0 днів… треба пробігти 1200 м", 1_000),
            ChatTurn("user", "ок", 2_000),
            ChatTurn("assistant", "День уже підписано", 6_000),
        )
        assertEquals(listOf("ок", "День уже підписано"), SolState.freshHistory(turns, signed).map { it.text })
        assertEquals(3, SolState.freshHistory(turns, signed.copy(signedAt = 0, signedToday = false)).size)
    }

    @Test fun aGreetingCachedBeforeTheSignatureIsNotShownAfterIt() {
        val p = app.getSharedPreferences("solarchik-sol", Context.MODE_PRIVATE)
        val before = signed.copy(streak = 0, signedToday = false, clockedToday = false)
        p.edit().putString("greet.uk.2026-10-03.${before.key}", "Серія 0, пробіжи 1200 м").commit()
        assertNotNull(SolGreeting.cached(app, "2026-10-03", "uk", before.key))
        assertNull(SolGreeting.cached(app, "2026-10-03", "uk", signed.key))
    }

    // ---- 3: one NFT, one name, current language ----

    @Test fun theSameStrategyIsNotListedTwiceInTwoLanguages() {
        val nft = ActAgent(id = "AssetWeather111", name = "Weather Station", skuId = "sku-pred-weather", track = "devnet")
        val paper = listOf(
            ActAgent(id = "paper:sku-pred-weather", name = "Weather Station", skuId = "sku-pred-weather"),
            ActAgent(id = "paper:sku-pred-alpha", name = "Bitcoin Windows #11", skuId = "sku-pred-alpha"),
        )
        val merged = SolActions.mergeAgents(listOf(nft, nft), paper, "практика") { AgentNames.uk(it) }
        // one NFT once (no EN + UK twin), its practice run clearly marked, every name in the app language
        assertEquals(listOf("Метеостанція", "Метеостанція · практика", "Біткоїн-вікна #11"), merged.map { it.name })
        assertEquals("AssetWeather111", merged[0].id)
        assertEquals(merged.size, merged.map { it.name }.distinct().size)
        assertTrue(merged.none { it.name.contains("Weather") || it.name.contains("Bitcoin") })
    }

    // ---- 4: a strategy proposal in plain words ----

    @Test fun strategyChangeIsDescribedWithoutJargon() {
        val cur = buildJsonObject { put("lanes", buildJsonArray { add(JsonPrimitive("crypto")) }); put("windows", buildJsonArray { add(JsonPrimitive(15)) }); put("risk", "calm"); put("stakeSol", 0.02); put("stopPct", 50); put("takePct", 100) }
        val lines = StrategyWords.describe(app, cur)
        assertTrue(lines.joinToString("\n"), lines[0].contains("вгору чи вниз") && lines[0].contains("15 хв"))
        assertTrue(lines[1].contains("спокійно"))
        assertTrue(lines[2].contains("0,02") || lines[2].contains("0.02"))
        assertEquals("15 хв і 1 год", StrategyWords.windows(app, listOf(60, 15)))
        val changes = listOf(Triple("windows", "15", "15/60"), Triple("risk", "calm", "balanced"))
        val say = StrategyWords.say(app, "Біткоїн-вікна #11", changes)
        assertTrue(say, say.contains("пропоную ставити на ринки по 15 хв і 1 год замість 15 хв") && say.contains("«Підтвердити»"))
        assertFalse(say, say.substringAfter("пропоную").contains("вікн"))
        assertTrue(StrategyWords.say(app, "X", listOf(Triple("risk", "calm", "balanced"))).contains("торгувати зважено, а не спокійно"))
        assertEquals("Сміливість: спокійно → зважено", StrategyWords.change(app, "risk", "calm", "balanced"))
    }

    // ---- 5: Slice says what it is and what to do ----

    @Test fun sliceExplainsItselfAndEveryRowHasABuyButton() {
        // 1.1.0: Slice is no longer shown in the assistant (three agents only); its copy stays for the game
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        a.select(MainActivity.Tab.AGENTS, animate = false)
        (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).openSection(3)
        ShadowLooper.idleMainLooper()
        assertNull(find(a.window.decorView, "slice-how"))
        assertTrue(a.getString(R.string.slice_how_title).isNotBlank())
    }

    // ---- 1 (app side): honest reasons for a missed call ----

    @Test fun missedCallSaysWhyItWasMissed() {
        val o = org.json.JSONObject().put("callId", "rtc_x").put("caller", "+380638500117").put("text", "Missed").put("at", 1L).put("status", "need_topup").put("reason", "TRIAL_CALLER_CAP")
        val item = CallInbox.item("u", o)!!
        assertEquals("TRIAL_CALLER_CAP", item.reason)
        assertTrue(net.solardepin.solarchik.screen.CallText.summary(app, item).contains("цей номер"))
        assertEquals("TRIAL_CALLER_CAP", CallInbox.item("u", CallInbox.toJson(item))!!.reason)
        assertTrue(net.solardepin.solarchik.screen.CallText.summary(app, item.copy(reason = "")).contains("закінчився кредит"))
    }

    // ---- 7: the run never offers to sign a day that is already signed ----

    private fun overlay(): RunOverlay = RunOverlay(app, object : RunOverlay.Actions {
        override fun pauseToggle() {}; override fun resume() {}; override fun yard() {}; override fun again() {}
        override fun sign() {}; override fun signBadge() {}; override fun share() {}; override fun yardSign() {}
        override fun musicToggle() {}; override fun mic() {}; override fun slideDown() {}; override fun slideUp() {}
    }).also { it.animations = false }

    @Test fun signedTodayThe1200mBannerAndTheEndCardOfferNoSignature() {
        val signBtn = app.getString(R.string.run_sign_btn)
        val o = overlay()
        o.setClock(RunOverlay.ClockUi(open = true, signed = true, wallet = true, dayLine = "CLOCK IN 1340 м · серія 1"))
        o.celebrateClock()
        val banner = allTexts(find(o, "clock-banner")!!).joinToString(" ")
        assertTrue(banner, banner.contains("Сьогодні вже підписано"))
        assertFalse(banner, banner.contains(app.getString(R.string.run_clock_unlocked)) || banner.contains("Підпиши"))
        o.setPaused(true)
        assertFalse(texts(o).contains(signBtn))
        // not signed yet: the offer is still there
        val n = overlay()
        n.setClock(RunOverlay.ClockUi(open = true, signed = false, wallet = true))
        n.celebrateClock()
        assertTrue(allTexts(find(n, "clock-banner")!!).contains(app.getString(R.string.run_clock_unlocked)))
        n.setPaused(true)
        assertTrue(texts(n).contains(signBtn))
    }

    private fun signToday(): net.solardepin.solarchik.game.GameSave {
        val save = net.solardepin.solarchik.game.GameSave(app)
        save.recordRun(1340, 1500)
        save.stampClock("Addr111", "sig-test-not-a-real-tx", "devnet", "memo")
        assertTrue(save.signedToday())
        return save
    }

    private fun runHud(phase: net.solardepin.solarchik.game.run.Phase, meters: Int) = net.solardepin.solarchik.game.RunHud(
        hearts = if (phase == net.solardepin.solarchik.game.run.Phase.DEAD) 0 else 3, shield = 0, score = meters, meters = meters, combo = 0,
        phase = phase, countdown = 0.0, death = net.solardepin.solarchik.game.run.DeathKind.HIT, suns = 3, maxCombo = 1, bonus = false,
        bonusLeft = 0.0, grind = false, didBonus = false, chapter = net.solardepin.solarchik.game.run.ChapterId.STORM, announce = "",
        announceOn = false, clockOpen = true,
    )

    private fun noSignOffer(where: String, all: List<String>) {
        for (id in listOf(R.string.run_sign_btn, R.string.run_sign_now, R.string.run_connect_yard, R.string.run_clock_unlocked, R.string.run_clock_keep)) {
            assertFalse("$where offers to sign again: ${app.getString(id)} in $all", all.contains(app.getString(id)))
        }
    }

    @Test fun runActivityAlreadySignedTodayNeverOffersCLOCKINAgainAt1200mOrAtTheEnd() {
        app.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().clear().commit()
        signToday()
        val a = org.robolectric.Robolectric.buildActivity(net.solardepin.solarchik.game.RunActivity::class.java).setup().get()
        val root = a.window.decorView
        a.onHud(runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1199))
        a.onEvents(listOf(net.solardepin.solarchik.game.run.Ev.CLOCK), runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1200))
        val banner = allTexts(find(root, "clock-banner")!!)
        assertTrue("$banner", banner.any { it.contains("Сьогодні вже підписано") })
        noSignOffer("1200 m banner", banner)
        noSignOffer("live HUD", texts(root))
        a.onResult(net.solardepin.solarchik.game.RunResult(runHud(net.solardepin.solarchik.game.run.Phase.DEAD, 1610)))
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        val end = texts(root)
        noSignOffer("end-of-run card", end)
        assertTrue("the end card says the day is signed: $end", end.contains(app.getString(R.string.run_signed_title)))
        // play again: still no offer
        a.again()
        a.onEvents(listOf(net.solardepin.solarchik.game.run.Ev.CLOCK), runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1200))
        noSignOffer("second run banner", allTexts(find(root, "clock-banner")!!))
    }

    @Test fun runActivityReadsTheSignatureFreshWhenTheDayIsSignedMidRunElsewhere() {
        app.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().clear().commit()
        val a = org.robolectric.Robolectric.buildActivity(net.solardepin.solarchik.game.RunActivity::class.java).setup().get()
        val root = a.window.decorView
        a.onHud(runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 400))
        signToday() // e.g. signed in the Yard / on another screen while this run was open
        a.onEvents(listOf(net.solardepin.solarchik.game.run.Ev.CLOCK), runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1200))
        noSignOffer("1200 m banner", allTexts(find(root, "clock-banner")!!))
        a.onResult(net.solardepin.solarchik.game.RunResult(runHud(net.solardepin.solarchik.game.run.Phase.DEAD, 1300)))
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        noSignOffer("end-of-run card", texts(root))
    }

    @Test fun runActivityUnsignedStillOffersTheSignatureAtTheEnd() {
        app.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().clear().commit()
        val a = org.robolectric.Robolectric.buildActivity(net.solardepin.solarchik.game.RunActivity::class.java).setup().get()
        val root = a.window.decorView
        a.onHud(runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1199))
        a.onEvents(listOf(net.solardepin.solarchik.game.run.Ev.CLOCK), runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1200))
        assertTrue(allTexts(find(root, "clock-banner")!!).contains(app.getString(R.string.run_clock_unlocked)))
        a.onResult(net.solardepin.solarchik.game.RunResult(runHud(net.solardepin.solarchik.game.run.Phase.DEAD, 1300)))
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        val end = texts(root)
        assertTrue("$end", end.contains(app.getString(R.string.run_sign_now)))
    }
}
