package net.solardepin.solarchik

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.game.RunActivity
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.CallNotes
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.ui.CallsActivity
import net.solardepin.solarchik.ui.roof.RoofObject
import net.solardepin.solarchik.ui.roof.RooftopScreen
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

/** 0.22.1 pre-release audit: every rooftop object, call actions, language switch, delete-my-data, run day line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk-w411dp-h914dp-xxhdpi")
class Audit0221Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        RooftopScreen.forceMute = true
        listOf("solarchik-roof", "solarchik-game", "solarchik-agents", "solarchik.calls", "solarchik.calls.remind", "solarchik-lang").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        net.solardepin.solarchik.ui.roof.RoofTour(net.solardepin.solarchik.ui.roof.PrefsKv(app)).finish(false) // no offer
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        SolanaWallet.walletAppCheck = realCheck
        RooftopScreen.forceMute = false
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v.visibility != View.VISIBLE) return out
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    // 1.0.0: Today is home; the rooftop is the game behind the Play tile
    private fun open(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle(); it.select(MainActivity.Tab.YARD); idle() }

    @Test fun everyRooftopObjectOpensItsPlace() {
        fun roof(a: MainActivity) = a.screen(MainActivity.Tab.YARD) as RooftopScreen
        val expect = mapOf(
            RoofObject.CLOCK to MainActivity.Tab.SHIFT,
            RoofObject.PLATE to MainActivity.Tab.RUN,
            RoofObject.SOL to MainActivity.Tab.SOL,
            RoofObject.TOOLBOX to MainActivity.Tab.SETTINGS,
            RoofObject.PANELS to MainActivity.Tab.AGENTS,
            RoofObject.TICKER to MainActivity.Tab.AGENTS,
        )
        for ((o, tab) in expect) {
            val a = open()
            roof(a).debugTap(o); idle()
            assertEquals("$o", tab, a.current)
            if (o == RoofObject.TICKER) assertEquals(AgentsScreen.WATCHER, (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).section)
            if (o == RoofObject.PANELS) assertEquals(0, (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).section)
        }
        val a = open()
        roof(a).debugTap(RoofObject.ANTENNA); idle()
        assertEquals(CallsActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        val b = open()
        roof(b).debugTap(RoofObject.DOOR)
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS); idle()
        assertEquals("the door starts the run", RunActivity::class.java.name, shadowOf(b).nextStartedActivity?.component?.className)
    }

    private fun item(owner: String) = CallItem(
        owner = owner, callId = "rtc_audit_1", caller = "+380631112233", text = "Просив передзвонити щодо доставки.", at = System.currentTimeMillis() - 60_000,
        status = CallInbox.DONE, source = "trial", callerName = "Олег", intent = "callback", urgency = "normal", notes = "", callback = "+380631112233",
        lang = "uk", durationSec = 74, chargedUsd = 0.0, trial = true,
    )

    @Test fun callDetailCallBackAndReminder() {
        // On a device androidx.startup initialises WorkManager; Robolectric doesn't run content providers.
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder()
                .build())
        }
        val me = PlayerIds.get(app)
        val it = item(me)
        CallInbox.store(app, listOf(it))
        val c = Robolectric.buildActivity(CallsActivity::class.java, Intent(app, CallsActivity::class.java).putExtra(CallsActivity.EXTRA_KEY, it.key)).setup().visible().get()
        idle()
        val d = c.window.decorView
        assertNotNull("detail opened from the notification key", find(d, "call-detail"))
        find(d, "call-back")!!.performClick(); idle()
        val dial = shadowOf(c).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dial?.action)
        assertEquals("tel:%2B380631112233", dial?.data.toString())
        find(d, "call-remind")!!.performClick(); idle()
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dlg)
        shadowOf(dlg).clickOnItem(1) // in 1 hour
        idle()
        val at = CallNotes.Reminders.at(app, it.key)
        assertTrue("reminder saved ~1 h ahead", at - System.currentTimeMillis() in 55 * 60_000L..61 * 60_000L)
        assertTrue(texts(d).any { t -> t.contains(net.solardepin.solarchik.screen.CallText.time(at)) })
    }

    @Test fun languageSwitchesBothWays() {
        val a = open()
        a.select(MainActivity.Tab.SETTINGS, animate = false); idle()
        find(a.window.decorView, "lang-en")!!.performClick(); idle()
        assertEquals("en", net.solardepin.solarchik.core.AppLocale.choice(app))
        val b = open()
        assertTrue(texts(b.window.decorView).any { it.contains("Rooftop run") })
        b.select(MainActivity.Tab.SETTINGS, animate = false); idle()
        find(b.window.decorView, "lang-uk")!!.performClick(); idle()
        assertEquals("uk", net.solardepin.solarchik.core.AppLocale.choice(app))
        net.solardepin.solarchik.core.AppLocale.set(app, "")
    }

    @Test fun deleteMyDataFromSettingsWipesTheGame() {
        val save = GameSave(app)
        save.recordRun(1300, 1500)
        save.stampClock("Addr111", "sig-test-not-a-real-tx", "devnet", "tx")
        val a = open()
        a.select(MainActivity.Tab.SETTINGS, animate = false); idle()
        val btn = generateSequence(listOf<View>(a.window.decorView)) { l -> l.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map(v::getChildAt) else emptyList() }.takeIf { it.isNotEmpty() } }
            .flatten().first { it is TextView && it.text.toString() == app.getString(R.string.settings_delete_data) }
        btn.performClick(); idle()
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(GameSave(app).signedToday())
        assertEquals(0, GameSave(app).bestDistance)
        assertEquals(0, GameSave(app).streak)
    }

    @Test fun clockInScreenSaysWhenTheNextDayStarts() {
        val a = open()
        a.select(MainActivity.Tab.SHIFT, animate = false); idle()
        val line = (find(a.window.decorView, "yard-day-reset") as TextView).text.toString()
        val next = net.solardepin.solarchik.core.StreakRules.nextDayStart(System.currentTimeMillis())
        assertEquals(app.getString(R.string.day_new_at, net.solardepin.solarchik.ui.Fmt.clock(next)), line)
        // UTC midnight in the phone's zone: Kyiv summer = 03:00
        val tz = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Kiev"))
        try {
            val ms = java.time.Instant.parse("2026-10-03T10:00:00Z").toEpochMilli()
            assertEquals("03:00", net.solardepin.solarchik.ui.Fmt.clock(net.solardepin.solarchik.core.StreakRules.nextDayStart(ms)))
        } finally { java.util.TimeZone.setDefault(tz) }
    }
}
