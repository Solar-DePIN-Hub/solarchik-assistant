package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.circle.CircleStore
import net.solardepin.solarchik.circle.Contact
import net.solardepin.solarchik.stack.MorningStack as MS
import java.time.LocalDate
import java.time.ZoneId
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallActionSync
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.ui.CallActionCards
import net.solardepin.solarchik.ui.CallsActivity
import net.solardepin.solarchik.ui.CirclePanel
import net.solardepin.solarchik.ui.Onboarding
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 1.2.5 first-run audit, English, as a judge installing it fresh: onboarding (3 pages), Today after Ira's call
 * (pay card + Circle), Calls, More → Circle, Add wallet, Settle confirm. Screenshots go to build/screens/1.2.5;
 * every screen must have no Cyrillic, no text off screen, no single-line label cut, no duplicated card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class Release125Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val cyr = Regex("[а-яіїєґё]", RegexOption.IGNORE_CASE)
    private val dir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.2.6").apply { mkdirs() }
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled
    private val addr = "HpEVVYWx2LiFANXAzfMy3yPTf61X1ZVDheYDYNmDJBwT"
    private val now = System.currentTimeMillis()
    private val ira = CallItem("me", "rtc_u2_EXOf3p3o", "+380637443792", "Ira says they paid for lunch yesterday and asks Vadim to send them 0.01 SOL.",
        now - 40 * 60_000L, CallInbox.DONE, "screen", "Ira", "Says they paid for lunch yesterday, asks Vadim to send them 0.01 SOL", "", "call back at 3", "+380637443792", "en", 65, 0.0, false)
    private val problems = mutableListOf<String>()

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.gameHub = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet", "solarchik.calls", CallActionStore.PREFS,
            "solarchik.calls.remind", "solarchik.followups", MainActivity.ASSISTANT_PREFS, "solarchik-game", CircleStore.PREFS, MS.PREFS)
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(3) }
    private fun walk(v: View, f: (View) -> Unit) { f(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f) }
    private fun shown(v: View): Boolean { var c: View? = v; while (c != null) { if (c.visibility != View.VISIBLE) return false; c = c.parent as? View }; return true }
    private fun texts(root: View): List<String> { val o = mutableListOf<String>(); walk(root) { if (it is TextView && shown(it)) o += it.text.toString() }; return o }
    private fun find(root: View, tag: String): View? { var r: View? = null; walk(root) { if (r == null && it.tag == tag && shown(it)) r = it }; return r }
    private fun count(root: View, tag: String): Int { var n = 0; walk(root) { if (it.tag == tag && shown(it)) n++ }; return n }

    private fun shot(root: View, file: String) {
        idle()
        val w = 1233; val h = 2742
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        var scroll: ScrollView? = null
        walk(root) { if (scroll == null && it is ScrollView && shown(it)) scroll = it }
        val s = scroll
        val content = s?.getChildAt(0)
        val full = if (s != null && content != null) maxOf(h, h - s.height + content.height) else h
        val bmp = Bitmap.createBitmap(w, full.coerceAtMost(16000), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (s != null && content != null && full > h) {
            root.draw(c)
            var y = 0; var cur: View? = s
            while (cur != null && cur !== root) { y += cur.top; cur = cur.parent as? View }
            c.save(); c.translate(s.left.toFloat(), y.toFloat()); c.clipRect(0, 0, s.width, content.height)
            c.drawColor(0xFF07131C.toInt()); content.draw(c); c.restore()
        } else root.draw(c)
        File(dir, "$file.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun audit(root: View, screen: String) {
        shot(root, screen)
        texts(root).filter { cyr.containsMatchIn(it.replace("Українська", "")) }.forEach { problems += "$screen: Cyrillic '$it'" }
        val rw = root.width
        walk(root) { v ->
            if (v !is TextView || !shown(v) || v.text.isNullOrBlank() || v.width == 0) return@walk
            val loc = IntArray(2); v.getLocationInWindow(loc)
            if (loc[0] < -2 || loc[0] + v.width > rw + 2) problems += "$screen: off-screen '${v.text.take(40)}'"
            val l = v.layout ?: return@walk
            if ((0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } && v.maxLines <= 1) problems += "$screen: cut '${v.text.take(60)}'"
        }
        File(dir, "$screen.txt").writeText(texts(root).filter { it.isNotBlank() }.joinToString("\n"))
    }

    private fun seedIra() {
        CallActionStore(app).upgradeRules(CallActionSync.RULES)
        CallInbox.store(app, listOf(ira))
        CallInbox.markSeen(app, 0)
        CallActionStore(app).add(listOf(
            CallAction(ira.key + "#0", ira.key, CallAction.PAYMENT, amount = 0.01, token = "SOL", recipient = "Ira"),
            CallAction(ira.key + "#1", ira.key, CallAction.CALLBACK, number = ira.caller, time = "15:00", text = "Call Ira back at 3 PM"),
        ))
        CallActionStore(app).markProcessed(listOf(ira.key))
    }

    @Test fun firstRunStoryInEnglish() {
        // 1) onboarding, 3 pages
        MainActivity.onboardingEnabled = true
        val ob = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val o = find(ob.window.decorView, "onboarding") as Onboarding
        for (i in 0..2) {
            audit(o, "0${i + 1}-onboarding-$i")
            if (i < 2) { find(o, "onb-next")!!.performClick(); idle() }
        }
        find(o, "onb-next")!!.performClick(); idle() // Not now
        assertEquals(MainActivity.Tab.TODAY, ob.current)
        MainActivity.onboardingEnabled = false

        // 2) Today after Ira's call: cards first, then Circle, no game tile, one card per kind
        seedIra()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        a.screen(MainActivity.Tab.TODAY)?.onShow(); idle()
        val today = a.window.decorView
        audit(today, "04-today-after-call")
        // 1.2.7: one swipe card at a time (call-backs first), the Play tile under the stack, no wallet/briefing blocks
        assertNotNull(find(today, "today-deck"))
        assertNotNull(find(today, "today-play"))
        assertEquals("one call-back card", 1, count(today, "ca-callback"))
        assertTrue(texts(today).toString(), texts(today).contains("1 of 2"))
        listOf("today-actions", "today-circle", "today-wallet", "today-briefing").forEach { assertNull("$it moved off Today", find(today, it)) }
        assertTrue("no Follow-ups box repeating the call-back card", texts(today).none { it == "Call back Ira" })

        // 4) the Circle tab (the Calls screens above may have refreshed the cache from the worker)
        seedIra()
        a.select(MainActivity.Tab.CIRCLE); idle()
        audit(a.window.decorView, "07-circle")
        assertTrue(texts(a.window.decorView).toString(), texts(a.window.decorView).any { it == "Ira · 0.01 SOL" })

        // 5) Add Ira's wallet (prefilled), then the Settle confirm
        find(a.window.decorView, "circle-add-wallet")!!.performClick(); idle()
        val form = CirclePanel.lastForm!!
        assertEquals("+380637443792", form.window!!.decorView.findViewWithTag<android.widget.EditText>("circle-f-phone").text.toString())
        audit(form.window!!.decorView, "08-add-wallet-form")
        form.window!!.decorView.findViewWithTag<android.widget.EditText>("circle-f-address").setText(addr)
        form.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val confirm = CallActionCards.lastSheet!!
        audit(confirm.window!!.decorView, "09-settle-confirm")
        assertTrue(texts(confirm.window!!.decorView).any { it.startsWith("Send 0.01 SOL to Ira?") })
        confirm.dismiss(); idle()
        a.screen(MainActivity.Tab.CIRCLE)?.onShow(); idle()
        audit(a.window.decorView, "10-circle-with-contact")
        assertEquals("Settle 0.01 SOL", (find(a.window.decorView, "circle-settle") as TextView).text.toString().trim('\u2060', ' '))

        // 6) settled (as after a confirmed transfer): Solscan line, gone from "You owe"
        CallActionStore(app).update(ira.key + "#0") { it.copy(status = CallAction.DONE, signature = "5".repeat(88)) }
        a.screen(MainActivity.Tab.CIRCLE)?.onShow(); idle()
        audit(a.window.decorView, "11-circle-settled")
        assertTrue(texts(a.window.decorView).any { it == "Paid Ira 0.01 SOL" })
        assertTrue(texts(a.window.decorView).any { it.endsWith("Solscan ↗") })
        assertNull(find(a.window.decorView, "circle-settle"))

        // 7) the stack: Later on what's left; with nothing left, Today says Clocked in (no money needed)
        a.select(MainActivity.Tab.TODAY); idle()
        audit(a.window.decorView, "12-today-stack-callback")
        repeat(5) { find(a.window.decorView, "stack-later")?.performClick(); idle() }
        audit(a.window.decorView, "13-today-clocked-in")
        assertTrue(find(a.window.decorView, "stack-clocked") != null)
        assertEquals("1 day in a row. Your stack is clear.", (find(a.window.decorView, "stack-streak") as TextView).text.toString())
        assertTrue(texts(a.window.decorView).any { it.startsWith("Your streak is saved either way.") })

        // 3) Calls list and the call
        val calls = Robolectric.buildActivity(CallsActivity::class.java).create().start().resume().visible().get(); idle()
        audit(calls.window.decorView, "05-calls")
        seedIra()
        val det = Robolectric.buildActivity(CallsActivity::class.java, android.content.Intent(app, CallsActivity::class.java).putExtra(CallsActivity.EXTRA_KEY, ira.key)).create().start().resume().visible().get(); idle()
        audit(det.window.decorView, "06-call-detail")

        File(dir, "problems.txt").writeText(problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private val zone = ZoneId.of("Europe/Kiev")
    private fun at(day: String, h: Int) = LocalDate.parse(day).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun laterSnoozesToTomorrowMorningAndDoneClocksIn() {
        seedIra()
        val t = at("2026-10-11", 9)
        assertEquals("1.2.7: call-backs, then payments", listOf(CallAction.CALLBACK, CallAction.PAYMENT), MS.items(app, t).map { it.type })
        val until = MS.snooze(app, ira.key + "#0", t, zone)
        assertEquals(at("2026-10-12", 6), until)
        assertEquals(listOf(CallAction.CALLBACK), MS.items(app, t).map { it.type })
        assertEquals(false, MS.clockedIn(app, t, zone))
        MS.done(app, ira.key + "#1", t, zone)
        assertTrue(MS.items(app, t).isEmpty())
        assertTrue(MS.clockedIn(app, t, zone))
        // the snoozed payment is back the next morning, and the clock-in is not repeated for nothing
        assertEquals(listOf(CallAction.PAYMENT), MS.items(app, at("2026-10-12", 7)).map { it.type })
        assertEquals(false, MS.clockedIn(app, at("2026-10-12", 7), zone))
    }

    @Test fun nothingHandledIsNotAClockIn() {
        val t = at("2026-10-11", 9)
        assertTrue(MS.items(app, t).isEmpty())
        assertEquals(false, MS.settle(app, t, zone))
    }

    @Test fun streakCountsConsecutiveDays() {
        val today = LocalDate.parse("2026-10-11")
        assertEquals(0, MS.streak(emptySet(), today))
        assertEquals(3, MS.streak(setOf("2026-10-09", "2026-10-10", "2026-10-11"), today))
        assertEquals("today not done yet: yesterday's run still counts", 2, MS.streak(setOf("2026-10-09", "2026-10-10"), today))
        assertEquals(1, MS.streak(setOf("2026-10-07", "2026-10-11"), today))
        assertEquals(7, MS.streak((5..11).map { "2026-10-%02d".format(it) }.toSet(), today))
    }

    @Test fun voiceAnswers() {
        assertEquals(MS.Answer.DONE, MS.answer("Done"))
        assertEquals(MS.Answer.LATER, MS.answer("later please"))
        assertEquals(MS.Answer.DO, MS.answer("pay"))
        assertEquals(MS.Answer.LATER, MS.answer("пізніше"))
        assertEquals(MS.Answer.DONE, MS.answer("готово"))
        assertEquals(null, MS.answer("what's the weather"))
    }

    @Test fun morningNoteOnlyFrom8WithCardsOncePerDayAndToggleable() {
        assertEquals(0, MS.notificationDue(app, at("2026-10-11", 9), zone)) // nothing waiting
        seedIra()
        assertEquals(0, MS.notificationDue(app, at("2026-10-11", 7), zone))
        assertEquals(2, MS.notificationDue(app, at("2026-10-11", 8), zone))
        MS.markNotified(app, at("2026-10-11", 8), zone)
        assertEquals(0, MS.notificationDue(app, at("2026-10-11", 12), zone))
        MS.setNotify(app, false)
        assertEquals(0, MS.notificationDue(app, at("2026-10-12", 9), zone))
    }
}
