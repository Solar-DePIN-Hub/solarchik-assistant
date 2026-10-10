package net.solardepin.solarchik

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallActionSync
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.stack.Habits
import net.solardepin.solarchik.ui.CallsActivity
import net.solardepin.solarchik.ui.HabitsSheet
import net.solardepin.solarchik.ui.MeScreen
import net.solardepin.solarchik.ui.TodayScreen
import net.solardepin.solarchik.ui.VoiceSheet
import org.junit.After
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
 * 1.2.7: the eight redesign screens rendered from the real build at the mockups' size (1080×2340, 411 dp at
 * xxhdpi), English, real defaults (mainnet, no fake balances beyond the seeded test wallet below). Written to
 * /workspace/deliverables/redesign/compare-1.2.9/build/ as 01…08 next to the mockups' names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xxhdpi")
class Redesign127ShotsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(System.getProperty("solarchik.compare") ?: "/workspace/deliverables/redesign/compare-1.2.9/build").apply { mkdirs() }
    private val now = System.currentTimeMillis()
    private val owner = "8J3hQ1JZq8CkQ6CwRNrsdc9UHS1R1JZmE7vUfnTqC7ic"
    private val realCluster = System.getProperty("solarchik.cluster")
    private val realOnboarding = MainActivity.onboardingEnabled
    private val realTicker = MainActivity.tickerEnabled
    private val cyr = Regex("[\\u0400-\\u04FF]")
    private val problems = mutableListOf<String>()

    private fun item(id: String, who: String, intent: String, number: String, minsAgo: Long) =
        CallItem("me", id, number, "$who: $intent. Callback $number.", now - minsAgo * 60_000L, CallInbox.DONE, "screen", who, intent, "", "call back at 3", number, "en", 65, 0.0, false)
    private fun item(id: String, who: String, intent: String, number: String, minsAgo: Long, notes: String) =
        CallItem("me", id, number, "$who: $intent", now - minsAgo * 60_000L, CallInbox.DONE, "screen", who, intent, "", notes, number, "en", 65, 0.0, false)
    private val ira = item("rtc_u2_EXOf3p3o", "Ira", "Ira paid for lunch yesterday and asks you to send her 0.01 SOL. She'd like a call back at 15:00.", "+380637443792", 60, "")
    private val andrii = item("rtc_u3_EXAndr3o", "Andrii", "Andrii will send the 0.5 SOL deposit tomorrow.", "+380501112233", 90, "")

    @Before fun setUp() {
        System.setProperty("solarchik.cluster", "mainnet")
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
    }
    @After fun tearDown() {
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
        MainActivity.tickerEnabled = realTicker; MainActivity.onboardingEnabled = realOnboarding
        net.solardepin.solarchik.screen.ScreenApi.requestForTest = null
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun walk(v: View, f: (View) -> Unit) { f(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f) }
    private fun find(root: View, tag: String): View? { var r: View? = null; walk(root) { if (r == null && it.tag == tag && it.visibility == View.VISIBLE) r = it }; return r }
    private fun texts(root: View): List<String> { val o = mutableListOf<String>(); walk(root) { if (it is TextView && it.visibility == View.VISIBLE) o += it.text.toString() }; return o }

    private fun shot(root: View, name: String, w: Int = 1080, h: Int = 2340) {
        idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { root.draw(this) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
        texts(root).filter { cyr.containsMatchIn(it) }.forEach { problems += "$name: Cyrillic '$it'" }
        File(dir, "$name.txt").writeText(texts(root).filter { it.isNotBlank() }.joinToString("\n"))
    }

    /** A dialog / overlay on top of the app: the app window first, then the sheet's content at the bottom. */
    private fun sheetShot(app0: View, content: View, name: String, dim: Boolean = true) {
        idle()
        val w = 1080; val h = 2340
        app0.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); app0.layout(0, 0, w, h)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        content.layout(0, 0, w, content.measuredHeight)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            app0.draw(this)
            if (dim) drawColor(0x99000000.toInt())
            save(); translate(0f, (h - content.measuredHeight).toFloat()); content.draw(this); restore()
        }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
        texts(content).filter { cyr.containsMatchIn(it) }.forEach { problems += "$name: Cyrillic '$it'" }
        File(dir, "$name.txt").writeText(texts(content).filter { it.isNotBlank() }.joinToString("\n"))
    }

    /** 1.2.8: a row the mockup shows at rest must end above the floating nav (not under it). */
    private fun aboveNav(root: View, tag: String, name: String) {
        val v = find(root, tag) ?: run { problems += "$name: no $tag"; return }
        val nav = find(root, "nav-wrap") ?: find(root, "nav-bar") ?: run { problems += "$name: no nav"; return }
        val a = IntArray(2); val b = IntArray(2); v.getLocationInWindow(a); nav.getLocationInWindow(b)
        val navTop = b[1] + (nav as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.minOfOrNull { it.top } ?: 0 }!!
        if (a[1] + v.height > navTop) problems += "$name: $tag ends at ${a[1] + v.height}px, nav starts at ${navTop}px"
    }

    private fun seed() {
        CallActionStore(app).upgradeRules(CallActionSync.RULES)
        CallInbox.store(app, listOf(ira, andrii))
        CallInbox.markSeen(app, 0)
        CallActionStore(app).add(listOf(
            CallAction(ira.key + "#0", ira.key, CallAction.CALLBACK, number = ira.caller, time = "15:00", text = "Call Ira back at 15:00", quote = "call me back at three"),
            CallAction(ira.key + "#1", ira.key, CallAction.PAYMENT, amount = 0.01, token = "SOL", recipient = "Ira", quote = "send me 0.01 SOL for lunch"),
            CallAction(andrii.key + "#0", andrii.key, CallAction.OWED, amount = 0.5, token = "SOL", recipient = "Andrii", quote = "I'll send you the 0.5 SOL deposit tomorrow"),
        ))
        CallActionStore(app).markProcessed(listOf(ira.key, andrii.key))
        Habits.set(app, Habits.WORKOUT, true)
        Habits.set(app, Habits.WALLET, true)
        // 1.2.9: two official Season partner tasks (the header pill on Today)
        net.solardepin.solarchik.season.SeasonDropsStore(app).save("en", """{"ok":true,"items":[{"id":"x1","app":"MattleFun","perk":"Turn One Up birthday event: quests and rewards","sourceUrl":"https://x.com/mattlefun/status/2107820234271044066","sourceDate":"2026-10-07","checked":"2026-10-10"},{"id":"x2","app":"Mentioned","perk":"Live on the Seeker dApp Store","sourceUrl":"https://x.com/MentionedMa/status/1","sourceDate":"2026-10-07","checked":"2026-10-10"}]}""", now)
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", owner).putString("auth", "t").putString("walletPkg", "app.phantom").commit()
    }

    @Test fun theEightScreens() {
        seed()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val d = a.window.decorView
        (a.screen(MainActivity.Tab.TODAY) as TodayScreen).setBalanceForTest(0.0096, 0.0)
        a.screen(MainActivity.Tab.TODAY)?.render(); idle()

        // 01 Today: the stack (call-back on top)
        shot(d, "01-today-stack")
        // 02 mid-swipe right: the top card tilted with its stamp showing (what the finger does, frozen)
        val deck = (find(d, "today-deck") as ViewGroup).getChildAt(0) as ViewGroup
        val top = deck.getChildAt(deck.childCount - 1)
        top.translationX = 300f; top.rotation = 9f
        walk(top) { if (it.tag == "stamp-right") it.alpha = 1f }
        shot(d, "02-today-swipe")
        top.translationX = 0f; top.rotation = 0f
        walk(top) { if (it.tag == "stamp-right") it.alpha = 0f }

        // 04 Circle (owe Ira, Andrii owes you)
        a.select(MainActivity.Tab.CIRCLE); idle()
        shot(d, "04-circle")
        aboveNav(d, "circle-add-row", "04-circle")
        // 1.2.9 (c): scrolled to the end, the Circle explanation paragraph ends above the bar
        run {
            var sv: ScrollView? = null; walk(d) { if (sv == null && it is ScrollView && it.isShown) sv = it }
            // lay out at the shot size first (the Robolectric window is taller), then scroll to the very end
            d.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)); d.layout(0, 0, 1080, 2340)
            sv?.let { it.scrollTo(0, it.getChildAt(0).height - it.height) }
            shot(d, "04b-circle-scrolled-end"); sv?.let { it.scrollTo(0, it.getChildAt(0).height - it.height) }; aboveNav(d, "circle-note", "04b-circle-scrolled-end")
            // the render at the very end: Robolectric's ScrollView.draw ignores scrollY, so draw the column shifted, then the bar on top
            sv?.let { v ->
                val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply {
                    drawColor(net.solardepin.solarchik.ui.Ui.BG)
                    val l = IntArray(2); v.getLocationInWindow(l)
                    save(); translate(l[0].toFloat(), (l[1] - v.scrollY).toFloat()); v.getChildAt(0).draw(this); restore()
                    find(d, "nav-wrap")?.let { n -> val m = IntArray(2); n.getLocationInWindow(m); save(); translate(m[0].toFloat(), m[1].toFloat()); n.draw(this); restore() }
                }
                File(dir, "04b-circle-scrolled-end.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
            }
            sv?.scrollTo(0, 0); idle()
        }
        // 05 Me (wallet tiles on mainnet, habits, secretary)
        a.select(MainActivity.Tab.ME); idle()
        (a.screen(MainActivity.Tab.ME) as MeScreen).setBalanceForTest(0.0096, 0.0, 0.0)
        a.screen(MainActivity.Tab.ME)?.render(); idle()
        shot(d, "05-me")
        aboveNav(d, "today-settings", "05-me")
        if (find(d, "me-wallet-addr").let { (it as? TextView)?.text?.startsWith("Phantom") } != true) problems += "05-me: wallet name is not Phantom"
        // 06 habits picker
        HabitsSheet.show(a) {}; idle()
        val hs = HabitsSheet.last!!
        sheetShot(d, hs.window!!.decorView.findViewWithTag("habits-sheet"), "06-habits-picker")
        hs.dismiss(); idle()
        // 07 Sol voice overlay with a heard phrase and its intent preview
        a.select(MainActivity.Tab.TODAY); idle()
        VoiceSheet.show(a, startListening = false); idle()
        VoiceSheet.fakeHeard("Who do I owe?"); idle()
        shot(d, "07-sol-voice")
        VoiceSheet.dismissIfOpen(a); idle()

        // 03 clocked in: clear the stack with Later/Done (no money needed)
        // 1.2.9: Later → the choice; "Tomorrow" moves each card out of today
        val today = a.screen(MainActivity.Tab.TODAY) as TodayScreen
        repeat(12) {
            find(a.window.decorView, "stack-later")?.let { b -> b.performClick(); idle(); today.lastLater?.let { dl -> dl.listView.performItemClick(null, 1, 1L); idle() } }
        }
        // 1.2.9: clocking in is the explicit "Clock in" tap
        shot(d, "03a-stack-clear-clock-in")
        find(a.window.decorView, "stack-clock-in")!!.performClick(); idle()
        shot(d, "03-clocked-in")
        assertTrue(find(d, "stack-clocked") != null)

        // 08 call detail (Ira), transcript collapsed, sticky Call Ira back; the conversation as the server keeps it
        val lines = listOf(
            "caller" to "Hi, it's Ira. Is Vadym there?", "secretary" to "He can't pick up right now. I can take a message.",
            "caller" to "I paid for our lunch yesterday, could he send me 0.01 SOL?", "secretary" to "Sure, I'll pass that on. Anything else?",
            "caller" to "Yes, please ask him to call me back at three.", "secretary" to "Got it: a call back at 15:00.",
            "caller" to "Thanks, bye!",
        )
        net.solardepin.solarchik.screen.ScreenApi.requestForTest = { _, url, _ ->
            if (!url.contains("/call?")) null else 200 to org.json.JSONObject()
                .put("item", org.json.JSONObject().put("callId", ira.callId).put("caller", ira.caller).put("text", ira.text).put("at", ira.at).put("status", "done").put("source", "screen").put("lang", "en").put("durationSec", 65).put("chargedUsd", 0.0)
                    .put("summary", org.json.JSONObject().put("caller_name", "Ira").put("intent", ira.intent).put("callback", ira.callback)))
                .put("lines", org.json.JSONArray().apply { lines.forEach { (w, t) -> put(org.json.JSONObject().put("who", w).put("text", t)) } })
                .put("durationSec", 65).toString()
        }
        val det = Robolectric.buildActivity(CallsActivity::class.java, Intent(app, CallsActivity::class.java).putExtra(CallsActivity.EXTRA_KEY, ira.key)).create().start().resume().visible().get(); idle()
        shot(det.window.decorView, "08-call-detail")
        // 1.2.9 (b): the payment row's subtitle fits one line
        run {
            val want = app.getString(R.string.ca_sub_pay); var tv: TextView? = null
            walk(det.window.decorView) { if (tv == null && it is TextView && it.isShown && it.text.toString() == want) tv = it }
            val t = tv
            if (t == null) problems += "08-call-detail: no '$want' row"
            else if (t.lineCount > 1 || (t.layout?.getEllipsisCount(0) ?: 0) > 0) problems += "08-call-detail: '$want' wraps or is cut"
        }
        // the note comes before the facts (mockup 08)
        val dv = det.window.decorView
        val note = find(dv, "call-note"); val facts = find(dv, "call-detail")
        if (note == null) problems += "08-call-detail: no note"
        else if (facts != null) { val n = IntArray(2); val f = IntArray(2); note.getLocationInWindow(n); facts.getLocationInWindow(f); if (n[1] > f[1]) problems += "08-call-detail: facts above the note" }

        net.solardepin.solarchik.screen.ScreenApi.requestForTest = null
        File(dir, "problems.txt").writeText(problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** 1.2.9: the five onboarding cards and the Later choice. */
    @Test fun onboardingAndLaterChoice() {
        seed()
        MainActivity.onboardingEnabled = true
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val d = a.window.decorView
        val o = find(d, "onboarding") as net.solardepin.solarchik.ui.Onboarding
        for (i in 0 until 5) { shot(d, "30-onboarding-${i + 1}"); o.advanceBySwipe(); idle() }
        find(o, "onb-skip")?.performClick() ?: find(o, "onb-next")?.performClick(); idle()
        if (a.onboardingShown) problems += "onboarding did not close"
        a.select(MainActivity.Tab.TODAY); idle()
        find(d, "stack-later")!!.performClick(); idle()
        val dlg = (a.screen(MainActivity.Tab.TODAY) as TodayScreen).lastLater!!
        sheetShot(d, dlg.window!!.decorView, "27-later-choice")
        dlg.dismiss(); idle()
        File(dir, "problems-onboarding.txt").writeText(problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test @Config(qualifiers = "uk-w411dp-h891dp-xxhdpi") fun onboardingInUkrainian() {
        MainActivity.onboardingEnabled = true
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val d = a.window.decorView
        val o = find(d, "onboarding") as net.solardepin.solarchik.ui.Onboarding
        for (i in 0 until 5) { shot(d, "31-onboarding-uk-${i + 1}"); o.advanceBySwipe(); idle() }
        // Cyrillic is expected here; English leaking in is not
        val en = Regex("\\b(the|your|and|Next|Skip|Later)\\b")
        (0 until 5).forEach { i -> File(dir, "31-onboarding-uk-${i + 1}.txt").readLines().filter { en.containsMatchIn(it) }.forEach { assertTrue("uk page ${i + 1}: English '$it'", false) } }
    }
}
