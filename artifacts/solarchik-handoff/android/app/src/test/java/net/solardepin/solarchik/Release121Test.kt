package net.solardepin.solarchik

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.season.SeasonDropsStore
import net.solardepin.solarchik.season.SeekerStore
import net.solardepin.solarchik.ui.CallsActivity
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * 1.2.1 full-app audit: every assistant screen rendered in EN and UK (PNG in build/screens/1.2.1),
 * EN shows no Cyrillic, UK text stays inside the screen, every clickable has a handler, and every
 * handler on Today / Sol / Agents / Settings / Season / Calls does something visible when tapped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class Release121Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val cyr = Regex("[а-яіїєґё]", RegexOption.IGNORE_CASE)
    private val dir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.2.1").apply { mkdirs() }
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled
    private val tabs = listOf(MainActivity.Tab.TODAY, MainActivity.Tab.SOL, MainActivity.Tab.AGENTS, MainActivity.Tab.SETTINGS, MainActivity.Tab.SEASON)

    private val drops = """{"ok":true,"x":false,"sourcesText":"%s","curatedChecked":"2026-10-10","checkedAt":1,"items":[
        {"id":"a","app":"MattleFun","perk":"%s","deadline":"","sourceUrl":"https://x.com/mattlefun/status/2107820234271044066","sourceDate":"2026-10-07","origin":"curated","checked":"2026-10-10"},
        {"id":"b","app":"Mentioned","perk":"%s","deadline":"","sourceUrl":"https://x.com/solanamobile/status/1","sourceDate":"2026-10-07","origin":"curated","checked":"2026-10-10"},
        {"id":"c","app":"TapTapTap","perk":"%s","deadline":"","sourceUrl":"https://x.com/solanamobile/status/2","sourceDate":"2026-09-29","origin":"curated","checked":"2026-10-10"},
        {"id":"d","app":"DiversiFi","perk":"%s","deadline":"","sourceUrl":"https://x.com/solanamobile/status/3","sourceDate":"2026-10-05","origin":"curated","checked":"2026-10-10"}]}"""

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.gameHub = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet", "solarchik.calls",
            "solarchik.calls.remind", "solarchik.followups", MainActivity.ASSISTANT_PREFS, "solarchik-game", SeasonDropsStore.PREFS, SeekerStore.PREFS)
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().putBoolean("onboarded", true).commit()
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(3) }

    private fun seed(uk: Boolean) {
        val a = JSONArray()
        val now = System.currentTimeMillis()
        fun call(id: String, caller: String, text: String, at: Long, status: String, name: String, intent: String, notes: String, cb: String) {
            a.put(JSONObject().put("callId", id).put("caller", caller).put("text", text).put("at", at).put("status", status).put("lang", if (uk) "uk" else "en").put("durationSec", 64)
                .put("summary", JSONObject().put("caller_name", name).put("intent", intent).put("urgency", "normal").put("notes", notes).put("callback", cb)))
        }
        if (uk) call("rtc_s1", "+380671112233", "Андрій: просить 50 SKR за квитки на концерт", now - 20 * 60_000L, "done", "Андрій", "Просить надіслати 50 SKR за квитки на концерт", "Просить передзвонити ввечері", "+380671112233")
        else call("rtc_s1", "+380671112233", "Andrii: asks for 50 SKR for the concert tickets", now - 20 * 60_000L, "done", "Andrii", "Asks you to send 50 SKR for the concert tickets", "Wants a call back tonight", "+380671112233")
        CallInbox.store(app, CallInbox.parse("owner-test", 200, JSONObject().put("items", a).toString())!!)
        CallInbox.markSeen(app, 0)
        val (src, p1, p2, p3, p4) = if (uk) listOf("Читає офіційний блог і документацію Solana Mobile. Підтримка X вбудована і вмикається з платним API.",
            "Святкова подія Turn One Up, понад ${'$'}30K нагород.", "Кожен власник Seeker отримує ${'$'}1 на старт.", "Бонусні спроби для власників Seeker.", "Знижка на комісію для Seeker.")
        else listOf("Reads the official Solana Mobile blog and docs. X support is built in and turns on with the paid API.",
            "Turn One Up birthday event, ${'$'}30K+ in total rewards.", "Every Seeker owner gets ${'$'}1 to start.", "Bonus tries for Seeker owners.", "Lower fees for Seeker owners.")
        SeasonDropsStore(app).save(if (uk) "uk" else "en", drops.format(src, p1, p2, p3, p4), System.currentTimeMillis())
    }

    // ---------------------------------------------------------------- tree helpers

    private fun walk(v: View, f: (View) -> Unit) { f(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f) }
    private fun shown(v: View): Boolean { var c: View? = v; while (c != null) { if (c.visibility != View.VISIBLE) return false; c = c.parent as? View }; return true }
    private fun texts(root: View): List<String> { val o = mutableListOf<String>(); walk(root) { if (it is TextView && shown(it)) o += it.text.toString() }; return o }
    private fun findScroll(v: View): ScrollView? { var s: ScrollView? = null; walk(v) { if (s == null && it is ScrollView && shown(it)) s = it }; return s }
    private fun clickables(root: View): List<View> { val o = mutableListOf<View>(); walk(root) { if (it.isClickable && shown(it)) o += it }; return o }
    private fun name(v: View): String {
        val own = (v.tag as? String) ?: (v as? TextView)?.text?.toString()?.take(40)?.ifBlank { null } ?: v.contentDescription?.toString()
        if (own != null) return own
        val inner = mutableListOf<String>(); walk(v) { if (it is TextView && it.text.isNotBlank()) inner += it.text.toString().take(30) }
        var p = v.parent as? View; var ptag: String? = null
        while (p != null && ptag == null) { ptag = p.tag as? String; p = p.parent as? View }
        return v.javaClass.simpleName + "[" + inner.take(2).joinToString("/") + "] in " + ptag
    }
    /** Visible state of the tree: texts, checked/selected/enabled, backgrounds and alpha, so restyled buttons and toggles count as an effect. */
    private fun state(root: View): String {
        val sb = StringBuilder()
        walk(root) { v ->
            if (!shown(v)) return@walk
            if (v is TextView) sb.append(v.text).append('|')
            if (v is android.widget.CompoundButton) sb.append(if (v.isChecked) "C" else "c")
            if (v.isFocused) sb.append('F')
            sb.append(if (v.isEnabled) 'E' else 'e').append(System.identityHashCode(v.background)).append(v.alpha).append(';')
        }
        return sb.toString()
    }

    /** Lays the window out at the phone's size and draws the whole scroll content (not just the viewport). */
    private fun shot(root: View, file: String) {
        idle()
        val w = 1233; val h = 2742
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val scroll = findScroll(root)
        val content = scroll?.getChildAt(0)
        val full = if (content != null) maxOf(h, h - scroll.height + content.height) else h
        val bmp = Bitmap.createBitmap(w, full.coerceAtMost(16000), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (scroll != null && content != null && full > h) {
            root.draw(c) // header and nav as on screen
            var y = 0; var cur: View? = scroll
            while (cur != null && cur !== root) { y += cur.top; cur = cur.parent as? View }
            c.save(); c.translate(scroll.left.toFloat(), y.toFloat()); c.clipRect(0, 0, scroll.width, content.height)
            c.drawColor(0xFF07131C.toInt()); content.draw(c); c.restore()
        } else root.draw(c)
        File(dir, "$file.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** Text that sticks out of the screen, or a label cut mid-word in one line (ellipsized buttons/titles). */
    private fun layoutProblems(root: View, screen: String): List<String> {
        val out = mutableListOf<String>()
        val rw = root.width
        walk(root) { v ->
            if (v !is TextView || !shown(v) || v.text.isNullOrBlank() || v.width == 0) return@walk
            val loc = IntArray(2); v.getLocationInWindow(loc)
            if (loc[0] < -2 || loc[0] + v.width > rw + 2) out += "$screen: off-screen x=${loc[0]} w=${v.width} '${v.text.take(40)}'"
            val l = v.layout ?: return@walk
            val cut = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
            if (cut && v.maxLines <= 1) out += "$screen: ellipsized '${v.text.take(60)}'"
        }
        return out
    }

    private fun open(tab: MainActivity.Tab): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle(); it.select(tab); idle(); it.screen(tab)?.onShow(); idle() }

    private fun auditScreens(uk: Boolean) {
        seed(uk)
        val key = CallInbox.cached(app).first().key
        val sfx = if (uk) "uk" else "en"
        val problems = mutableListOf<String>()
        val noHandler = mutableListOf<String>()
        val report = StringBuilder()
        fun check(root: View, screen: String) {
            shot(root, "$screen-$sfx")
            val t = texts(root)
            if (!uk) t.filter { cyr.containsMatchIn(it.replace("Українська", "")) }.forEach { problems += "$screen: Cyrillic in EN '$it'" }
            if (uk) problems += layoutProblems(root, screen)
            // a text field squeezed to nothing by its neighbour (1.2.1: the forwarding number field next to "Save")
            walk(root) { if (it is android.widget.EditText && shown(it) && it.width < 100) problems += "$screen: text field ${it.width}px wide '${it.hint}'" }
            // switches (checked listener), text fields and selectable text are clickable by nature; the onboarding layer swallows touches
            clickables(root).filter { !it.hasOnClickListeners() && it !is ScrollView && it !is android.widget.CompoundButton && it !is android.widget.EditText &&
                !((it as? TextView)?.isTextSelectable ?: false) && it.tag != "onboarding" }.forEach { noHandler += "$screen: ${name(it)}" }
            report.append("== $screen ($sfx): ${t.size} texts, ${clickables(root).size} clickables\n")
        }
        for (tab in tabs) { val a = open(tab); check(a.window.decorView, tab.name.lowercase()) }
        val calls = Robolectric.buildActivity(CallsActivity::class.java).create().start().resume().visible().get(); idle()
        check(calls.window.decorView, "calls")
        seed(uk) // the list screen may have refreshed the cache from the worker
        val det = Robolectric.buildActivity(CallsActivity::class.java, android.content.Intent(app, CallsActivity::class.java).putExtra(CallsActivity.EXTRA_KEY, key)).create().start().resume().visible().get(); idle()
        check(det.window.decorView, "call-detail")
        // onboarding
        app.getSharedPreferences(MainActivity.ASSISTANT_PREFS, Context.MODE_PRIVATE).edit().putBoolean("onboarded", false).commit()
        MainActivity.onboardingEnabled = true
        val ob = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        check(ob.window.decorView, "onboarding")
        MainActivity.onboardingEnabled = false
        report.append("\nproblems:\n").append(problems.joinToString("\n")).append("\n\nclickable without handler:\n").append(noHandler.joinToString("\n"))
        File(dir, "audit-$sfx.txt").writeText(report.toString())
        println(report)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue(noHandler.joinToString("\n"), noHandler.isEmpty())
    }

    @Test fun version() {
        assertEquals("1.2.2", BuildConfig.VERSION_NAME)
        assertEquals(122, BuildConfig.VERSION_CODE)
    }

    @Test fun everyScreenInEnglish() = auditScreens(uk = false)

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun everyScreenInUkrainian() = auditScreens(uk = true)

    @Test fun todayShowsTheSeasonPartnerPerks() {
        seed(false)
        val a = open(MainActivity.Tab.TODAY)
        val line = a.window.decorView.findViewWithTag<TextView>("today-season-tasks")
        assertTrue(line != null && shown(line))
        assertEquals("Partner perks today: MattleFun, Mentioned, TapTapTap +1", line!!.text.toString())
        line.performClick() // the line sits on the Season card: a tap opens the Season screen
        (line.parent as View).performClick(); idle()
        assertTrue(texts(a.window.decorView).any { it == "Today's Season tasks" })
    }

    @Test fun sendWithAnEmptyBoxFocusesTheField() {
        val a = open(MainActivity.Tab.SOL)
        val send = a.window.decorView.findViewWithTag<View>("sol-send")!!
        var field: android.widget.EditText? = null
        walk(a.window.decorView) { if (it is android.widget.EditText && shown(it)) field = it }
        field!!.clearFocus()
        send.performClick(); idle()
        assertTrue(field!!.hasFocus())
    }

    @Test fun longSentencesAreSplitForTheVoice() {
        val long = "Good morning, " + (1..40).joinToString(", ") { "call number $it from a friend" } + ". Bye."
        val lines = net.solardepin.solarchik.sol.SolVoice.sentences(long)
        assertTrue(lines.all { it.length <= 400 })
        assertEquals(long.replace(Regex("\\s+"), " "), lines.joinToString(" "))
    }

    @Test fun noSeasonDropsHidesTheLine() {
        val a = open(MainActivity.Tab.TODAY)
        val line = a.window.decorView.findViewWithTag<TextView>("today-season-tasks")
        assertTrue(line == null || !shown(line))
    }

    /**
     * Handlers whose effect is not on screen in a unit test (reviewed by hand): network calls whose answer
     * restyles the screen later, audio, the hidden version-tap counter, and the tab you are already on.
     */
    private val quiet = mapOf(
        "nav-" to "the tab you are on: tapping it again stays there",
        "voice-preview" to "plays Sol's sample voice (audio; failure shows a toast)",
        "sec-credit" to "reloads the secretary credit from the worker",
        "calls-refresh" to "reloads the call list from the worker",
        "calls-try-btn" to "reserves the line on the worker, then opens the dialer",
        "settings-version" to "counts taps for the hidden diagnostics switch",
        "settings-balance" to "re-reads the balance over RPC (no wallet in the test)",
        "Season Agent" to "the agent tab already shown",
        "Auto" to "the secretary language already picked (another one is saved on the worker and restyles)",
        "lang-phone" to "the app language already picked (another one recreates the screen)",
    )

    /** Taps every handler on a screen (fresh screen each time) and records what visibly happened. */
    private fun tapAll(screen: String, build: () -> Activity): List<String> {
        val silent = mutableListOf<String>()
        val n = clickables(build().window.decorView).size
        val clip = app.getSystemService(ClipboardManager::class.java)
        for (i in 0 until n) {
            val a = build()
            val root = a.window.decorView
            val list = clickables(root)
            if (i >= list.size) break
            val v = list[i]
            if (!v.hasOnClickListeners() || !v.isEnabled) continue // a disabled button (e.g. "Refreshing…") ignores taps by design
            val label = name(v)
            val before = state(root) + clickables(root).size
            val toasts = ShadowToast.shownToastCount()
            val dlg = ShadowDialog.getLatestDialog()
            val clipBefore = clip.primaryClip?.getItemAt(0)?.text?.toString()
            shadowOf(a).clearNextStartedActivities()
            try { v.performClick() } catch (e: Throwable) { silent += "$screen: '$label' CRASH ${e.javaClass.simpleName}: ${e.message}"; continue }
            ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS); idle()
            val after = state(a.window.decorView) + clickables(a.window.decorView).size
            val effect = after != before || ShadowToast.shownToastCount() != toasts || ShadowDialog.getLatestDialog() !== dlg ||
                shadowOf(a).nextStartedActivity != null || shadowOf(app as android.app.Application).nextStartedActivity != null ||
                shadowOf(a).lastRequestedPermission != null || clip.primaryClip?.getItemAt(0)?.text?.toString() != clipBefore || a.isFinishing || a.isDestroyed || a.isChangingConfigurations
            if (!effect && quiet.none { label.startsWith(it.key) }) silent += "$screen: '$label' did nothing visible"
            ShadowDialog.getLatestDialog()?.dismiss()
        }
        return silent
    }

    @Test fun everyButtonDoesSomething() {
        seed(false)
        val out = mutableListOf<String>()
        for (tab in tabs) out += tapAll(tab.name.lowercase()) { open(tab) }
        out += tapAll("calls") { Robolectric.buildActivity(CallsActivity::class.java).create().start().resume().visible().get().also { idle() } }
        File(dir, "taps-en.txt").writeText(out.joinToString("\n"))
        println("TAPS:\n" + out.joinToString("\n"))
        assertTrue(out.joinToString("\n"), out.isEmpty())
    }
}
