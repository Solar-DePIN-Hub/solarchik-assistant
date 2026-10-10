package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.FollowUp
import net.solardepin.solarchik.screen.FollowUps
import net.solardepin.solarchik.sol.AssistantRules
import net.solardepin.solarchik.ui.CallsActivity
import net.solardepin.solarchik.ui.Onboarding
import net.solardepin.solarchik.ui.TodayScreen
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Calendar

/**
 * 1.0.0 Solarchik Assistant: Today home (secretary, follow-ups, agent wallet, check-in and Play tiles,
 * the mic and chips), the assistant nav, first-launch onboarding, the local assistant answers, and the
 * install identity. Renders go to `<shots>/1.0.0/` (README screens).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class TodayTest {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.0.0").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet", "solarchik.calls",
            "solarchik.calls.remind", "solarchik.followups", MainActivity.ASSISTANT_PREFS, "solarchik-game")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
    }

    // ------------------------------------------------------------------ fixtures

    private fun todayAt(minutesAgo: Int): Long {
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        return maxOf(start + 60_000L * (10 - minutesAgo / 60).coerceAtLeast(1), now - minutesAgo * 60_000L)
    }

    /** Sample calls in the worker's /inbox shape. Numbers are made up. */
    private fun sampleCalls(uk: Boolean): List<CallItem> {
        val a = JSONArray()
        fun call(id: String, caller: String, text: String, at: Long, status: String, name: String = "", intent: String = "", notes: String = "", callback: String = "", lang: String = "") {
            a.put(JSONObject().put("callId", id).put("caller", caller).put("text", text).put("at", at).put("status", status).put("lang", lang).put("durationSec", 64)
                .put("summary", JSONObject().put("caller_name", name).put("intent", intent).put("urgency", "normal").put("notes", notes).put("callback", callback)))
        }
        if (uk) {
            call("rtc_a1", "+380671112233", "Олена: перенести зустріч", todayAt(25), "done", "Олена", "Перенести п'ятничну зустріч на 15:00", "Просить підтвердити до вечора", "+380671112233", "uk")
            call("rtc_a2", "+380502223344", "Доставка", todayAt(140), "done", "Нова пошта", "Посилка буде завтра з 10 до 12", "", "", "uk")
        } else {
            call("rtc_a1", "+380671112233", "Olena: move the meeting", todayAt(25), "done", "Olena", "Wants to move Friday's meeting to 3 pm", "Asks you to confirm by tonight", "+380671112233", "en")
            call("rtc_a2", "+380502223344", "Delivery", todayAt(140), "done", "Courier", "Parcel arrives tomorrow between 10 and 12", "", "", "en")
        }
        call("live_a3", "+380443334455", "Missed call: the secretary could not pick up (refunded).", todayAt(200), "failed")
        call("rtc_a4", "+380931234567", "Blocked caller", todayAt(260), "blocked")
        return CallInbox.parse("owner-test", 200, JSONObject().put("items", a).toString())!!
    }

    private fun seed(uk: Boolean) {
        val items = sampleCalls(uk)
        CallInbox.store(app, items)
        CallInbox.markSeen(app, 0)
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun findAll(v: View, tag: String, out: MutableList<View> = mutableListOf()): List<View> {
        if (v.tag == tag) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) findAll(v.getChildAt(i), tag, out)
        return out
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun shot(root: View, name: String, scrollToTag: String? = null) {
        idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val scroll = findScroll(root)
        if (scroll != null && scrollToTag != null) {
            var y = 0; var cur: View? = find(scroll, scrollToTag)
            while (cur != null && cur !== scroll) { y += cur.top; cur = cur.parent as? View }
            scroll.scrollTo(0, (y - 160).coerceAtLeast(0))
        }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        scroll?.scrollTo(0, 0)
    }

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }

    // ------------------------------------------------------------------ identity

    @Test fun installsNextToTheGameAndStaysOnDevnet() {
        assertEquals("net.solardepin.solarchik.assistant", BuildConfig.APPLICATION_ID)
        assertEquals("1.2.2", BuildConfig.VERSION_NAME)
        // 1.1.0: the release defaults to mainnet; this suite runs in dev devnet mode (solarchik.cluster=devnet)
        assertFalse(BuildConfig.DEVNET_ONLY)
        assertEquals("Solarchik Assistant", app.getString(R.string.app_name))
        val w = SolanaWallet(app)
        assertFalse(w.mainnet)
        assertEquals("devnet", w.clusterName)
    }

    // ------------------------------------------------------------------ home

    @Test @Config(qualifiers = "en-w411dp-h914dp-xxhdpi") fun todayIsHomeWithAssistantNav() {
        seed(uk = false)
        val a = launch()
        assertEquals(MainActivity.Tab.TODAY, a.current)
        assertTrue(a.screen(MainActivity.Tab.TODAY) is TodayScreen)
        val d = a.window.decorView
        assertEquals(listOf("Today", "Calls", "Sol", "Agents", "More"),
            listOf("nav-today", "nav-calls", "nav-sol", "nav-agents", "nav-settings").map { find(d, it)!!.contentDescription.toString() })
        assertNull("the game has no nav slot", find(d, "nav-run"))
        listOf("today-greeting", "today-mic", "today-secretary", "today-todos", "today-wallet", "today-checkin", "today-play").forEach {
            assertNotNull("$it missing", find(d, it))
        }
        // secretary: 2 answered, 1 missed, 1 blocked today; latest two non-blocked calls with AI summaries
        val stats = texts(find(d, "today-sec-stats")!!)
        assertEquals(listOf("2", "Answered", "1", "Missed", "1", "Blocked"), stats)
        val latest = findAll(d, "today-call")
        assertEquals(2, latest.size)
        assertTrue(texts(latest[0]).any { it.contains("Friday's meeting") })
        // the summary line and Sol's line talk about today's calls
        assertTrue((find(d, "today-summary") as TextView).text.contains("calls today"))
        assertTrue((find(d, "today-sol-line") as TextView).text.contains("Olena"))
        // unread dot on the Calls nav item
        assertEquals(View.VISIBLE, find(d, "nav-calls-dot")!!.visibility)
        shot(d, "01-today-en")
    }

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi") fun todayInUkrainian() {
        seed(uk = true)
        val a = launch()
        val d = a.window.decorView
        assertEquals(listOf("Сьогодні", "Дзвінки", "Сол", "Агенти", "Ще"),
            listOf("nav-today", "nav-calls", "nav-sol", "nav-agents", "nav-settings").map { find(d, it)!!.contentDescription.toString() })
        assertTrue(texts(d).any { it.contains("Телефонний секретар") })
        assertTrue(texts(find(d, "today-sec-stats")!!).contains("Прийняті"))
        shot(d, "01-today-uk")
    }

    @Test fun followUpsComeFromCallbacksAndCanBeDone() {
        seed(uk = false)
        val a = launch()
        val d = a.window.decorView
        val rows = findAll(d, "today-todo")
        assertEquals("only Olena left a number to call back", 1, rows.size)
        assertTrue(texts(rows[0]).any { it.contains("Call back Olena") })
        find(rows[0], "today-todo-done")!!.performClick(); idle()
        assertTrue(findAll(a.window.decorView, "today-todo").isEmpty())
        assertTrue(texts(find(a.window.decorView, "today-todos")!!).any { it.startsWith("Nothing to follow up") })
        // persisted across launches
        assertTrue(FollowUps.list(app).isEmpty())
    }

    @Test fun cardsOpenTheRightPlaces() {
        seed(uk = false)
        val a = launch()
        val sa = shadowOf(a)
        // a call row opens that call's note
        findAll(a.window.decorView, "today-call").first().performClick()
        val i = sa.nextStartedActivity
        assertEquals(CallsActivity::class.java.name, i.component!!.className)
        assertTrue(i.getStringExtra(CallsActivity.EXTRA_KEY)!!.contains("rtc_a1"))
        // "Call secretary" arms the demo line from the Calls screen
        find(a.window.decorView, "today-sec-try")!!.performClick()
        assertTrue(sa.nextStartedActivity.getBooleanExtra(CallsActivity.EXTRA_TRY, false))
        // Calls in the nav is the inbox, not a screen
        find(a.window.decorView, "nav-calls")!!.performClick(); idle()
        assertEquals(CallsActivity::class.java.name, sa.nextStartedActivity.component!!.className)
        assertEquals(MainActivity.Tab.TODAY, a.current)
        // 1.1.4: Play starts the rooftop run straight away (no game hub); Today stays underneath
        find(a.window.decorView, "today-play")!!.performClick(); idle()
        assertEquals(net.solardepin.solarchik.game.RunActivity::class.java.name, sa.nextStartedActivity.component!!.className)
        assertEquals(MainActivity.Tab.TODAY, a.current)
        // check-in opens today's CLOCK IN card
        find(a.window.decorView, "today-checkin")!!.performClick(); idle()
        assertEquals(MainActivity.Tab.SHIFT, a.current)
        a.select(MainActivity.Tab.TODAY); idle()
        // wallet: set up from the card (no wallet app -> built-in devnet wallet is offered there)
        assertNotNull(find(a.window.decorView, "today-wallet-setup"))
        find(a.window.decorView, "today-settings")!!.performClick(); idle()
        assertEquals(MainActivity.Tab.SETTINGS, a.current)
    }

    @Test fun walletCardWithBuiltInWallet() {
        seed(uk = false)
        LocalKey.create(app)
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit()
            .putString("kind", "local").putString("address", LocalKey.address(app)).commit()
        val a = launch()
        val t = a.screen(MainActivity.Tab.TODAY) as TodayScreen
        t.setBalanceForTest(1.8425)
        t.render(); idle()
        val d = a.window.decorView
        assertEquals("1.8425 SOL", (find(d, "today-wallet-balance") as TextView).text.toString())
        assertTrue(texts(find(d, "today-wallet-next")!!).any { it.contains("Sol asks before every on-chain step") })
        assertNotNull(find(d, "today-wallet-agents"))
        find(d, "today-wallet-explorer")!!.performClick()
        val url = shadowOf(a).nextStartedActivity.dataString!!
        assertTrue(url, url.startsWith("https://explorer.solana.com/address/") && url.endsWith("?cluster=devnet"))
        shot(d, "02-today-wallet-en", "today-wallet")
    }

    @Test fun chipsAskSolAndAnswerFromThePhone() {
        seed(uk = false)
        val a = launch()
        find(a.window.decorView, "today-chip-calls")!!.performClick(); idle()
        assertEquals(MainActivity.Tab.SOL, a.current)
        val all = texts(a.window.decorView)
        assertTrue(all.any { it == "Did anyone call me today?" })
        assertTrue(all.joinToString("\n"), all.any { it.startsWith("You had 3 calls today.") && it.contains("Olena") })
        shot(a.window.decorView, "03-sol-calls-en", null)
        a.select(MainActivity.Tab.TODAY); idle()
        find(a.window.decorView, "today-chip-ask")!!.performClick(); idle()
        assertTrue(texts(a.window.decorView).any { it.startsWith("Three things: I answer your calls") })
    }

    @Test fun micListensOnTodayAndStops() {
        val a = launch()
        val t = a.screen(MainActivity.Tab.TODAY) as TodayScreen
        org.robolectric.Shadows.shadowOf(a.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        t.startListening(); idle()
        // Robolectric has no recognizer service: the app says so instead of pretending to listen
        if (t.isListening) {
            assertEquals(a.getString(R.string.today_listening), (find(a.window.decorView, "today-mic-hint") as TextView).text.toString())
            t.stopListening()
        }
        assertFalse(t.isListening)
        assertEquals(a.getString(R.string.today_mic_hint), (find(a.window.decorView, "today-mic-hint") as TextView).text.toString())
    }

    @Test @Config(qualifiers = "en-w411dp-h914dp-xxhdpi") fun callsInboxFromToday() {
        seed(uk = false)
        val c = Robolectric.buildActivity(CallsActivity::class.java).setup().visible().get()
        idle()
        assertTrue(texts(c.window.decorView).any { it.contains("Friday's meeting") })
        shot(c.window.decorView, "04-calls-en", "call-row")
    }

    // ------------------------------------------------------------------ onboarding

    @Test @Config(qualifiers = "en-w411dp-h914dp-xxhdpi") fun onboardingOnceThenToday() {
        MainActivity.onboardingEnabled = true
        val a = launch()
        assertTrue(a.onboardingShown)
        val d = a.window.decorView
        val o = find(d, "onboarding") as Onboarding
        assertEquals("Meet Sol, your pocket assistant", (find(o, "onb-title") as TextView).text.toString())
        shot(d, "00-onboarding-en")
        find(o, "onb-next")!!.performClick(); idle()
        assertTrue((find(o, "onb-title") as TextView).text.contains("answers the phone"))
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals("Back goes to the previous page", 0, o.index)
        find(o, "onb-next")!!.performClick(); find(o, "onb-next")!!.performClick(); idle()
        assertEquals("Get started", (find(o, "onb-next") as TextView).text.toString())
        find(o, "onb-next")!!.performClick(); idle()
        assertFalse(a.onboardingShown)
        assertEquals(MainActivity.Tab.TODAY, a.current)
        // never again
        val b = launch()
        assertFalse(b.onboardingShown)
    }

    @Test fun onboardingSkip() {
        MainActivity.onboardingEnabled = true
        val a = launch()
        find(a.window.decorView, "onb-skip")!!.performClick(); idle()
        assertFalse(a.onboardingShown)
        assertTrue(app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).getBoolean("onboarded", false))
    }

    @Test fun onboardingIsOffInOlderScreenTests() {
        // build.gradle passes -Dsolarchik.onboarding=0 so the older screen tests see the app, not page 1
        assertEquals("0", System.getProperty("solarchik.onboarding"))
        assertFalse(realOnboarding)
    }

    // ------------------------------------------------------------------ pure logic

    @Test fun assistantRulesRouteOnlyCallsAndSkills() {
        assertEquals(AssistantRules.Kind.CALLS, AssistantRules.kind("Did anyone call me today?"))
        assertEquals(AssistantRules.Kind.CALLS, AssistantRules.kind("Хтось мені дзвонив?"))
        assertEquals(AssistantRules.Kind.CALLS, AssistantRules.kind("any missed calls"))
        assertEquals(AssistantRules.Kind.SKILLS, AssistantRules.kind("What can you do for me?"))
        assertEquals(AssistantRules.Kind.SKILLS, AssistantRules.kind("Що ти вмієш?"))
        assertNull("agent commands go to the brain", AssistantRules.kind("call the agent and start Weather Station"))
        assertNull(AssistantRules.kind("start Weather Station"))
        assertNull(AssistantRules.kind("how is my streak"))
    }

    @Test fun assistantCallsLine() {
        val calls = sampleCalls(uk = false)
        val line = AssistantRules.callsLine(app, calls)
        assertTrue(line, line.startsWith("You had 3 calls today."))
        assertTrue(line, line.contains("Olena") && line.contains("Friday's meeting"))
        val old = calls.map { it.copy(at = it.at - 3 * 24 * 3600_000L) }
        assertTrue(AssistantRules.callsLine(app, old).startsWith("No calls today. Last one:"))
        assertTrue(AssistantRules.callsLine(app, emptyList()).contains(CallInbox.DEMO_LINE))
    }

    @Test fun followUpRules() {
        val now = System.currentTimeMillis()
        val calls = sampleCalls(uk = false)
        val olena = calls.first { it.callerName == "Olena" }
        val courier = calls.first { it.callerName == "Courier" }
        // callback ask -> follow-up; no number -> none; blocked / missed never
        val base = FollowUps.build(calls, { 0L }, emptySet(), now)
        assertEquals(listOf(olena.key), base.map { it.item.key })
        assertEquals(FollowUp.Kind.CALLBACK, base[0].kind)
        // a reminder on the courier call comes first
        val withReminder = FollowUps.build(calls, { if (it == courier.key) now + 3600_000L else 0L }, emptySet(), now)
        assertEquals(listOf(courier.key, olena.key), withReminder.map { it.item.key })
        assertEquals(FollowUp.Kind.REMINDER, withReminder[0].kind)
        // done and stale ones drop off
        assertTrue(FollowUps.build(calls, { 0L }, setOf(olena.key), now).isEmpty())
        assertTrue(FollowUps.build(calls, { 0L }, emptySet(), now + FollowUps.WINDOW_MS + 3600_000L).isEmpty())
    }

    @Test fun greetingByHour() {
        assertEquals(R.string.today_morning, TodayScreen.greetingFor(8))
        assertEquals(R.string.today_afternoon, TodayScreen.greetingFor(13))
        assertEquals(R.string.today_evening, TodayScreen.greetingFor(19))
        assertEquals(R.string.today_night, TodayScreen.greetingFor(2))
    }
}
