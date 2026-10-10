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
 * 1.2.6 first-run audit (clean demo data: a random never-used address, masked unknown numbers, real Season drops)
 * 1.2.5 first-run audit, English, as a judge installing it fresh: onboarding (3 pages), Today after Ira's call
 * (pay card + Circle), Calls, More → Circle, Add wallet, Settle confirm. Screenshots go to build/screens/1.2.5;
 * every screen must have no Cyrillic, no text off screen, no single-line label cut, no duplicated card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class Release126Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val cyr = Regex("[а-яіїєґё]", RegexOption.IGNORE_CASE)
    private val dir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.2.6").apply { mkdirs() }
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled
    private val addr = "HpEVVYWx2LiFANXAzfMy3yPTf61X1ZVDheYDYNmDJBwT"
    private val now = System.currentTimeMillis()
    private val ira = CallItem("me", "rtc_u2_EXOf3p3o", "+380637443792", "Ira says they paid for lunch yesterday and asks Vadim to send them 0.01 SOL.",
        now - 40 * 60_000L, CallInbox.DONE, "screen", "Ira", "Says they paid for lunch yesterday, asks Vadim to send them 0.01 SOL", "", "call back at 3", "+380637443792", "en", 65, 0.0, false)
    private val stranger = CallItem("me", "rtc_u2_strangr1", "+380501112233", "Asks whether the flat is still for rent. Callback: +380501112233.",
        now - 90 * 60_000L, CallInbox.DONE, "screen", "", "Asks whether the flat is still for rent", "", "", "+380501112233", "en", 30, 0.0, false)
    private val problems = mutableListOf<String>()

    private val realCluster = System.getProperty("solarchik.cluster")

    @Before fun setUp() {
        // 1.2.6: the audit shows what a real install shows: Solana mainnet (the unit suite's default is dev devnet)
        System.setProperty("solarchik.cluster", "mainnet")
        CallInbox.offlineForTest = true
        MainActivity.tickerEnabled = false
        MainActivity.gameHub = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet", "solarchik.calls", CallActionStore.PREFS,
            "solarchik.calls.remind", "solarchik.followups", MainActivity.ASSISTANT_PREFS, "solarchik-game", CircleStore.PREFS, MS.PREFS, net.solardepin.solarchik.season.SeasonDropsStore.PREFS, "solarchik.secretary", "phones")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun tearDown() {
        CallInbox.offlineForTest = false
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
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
        CallInbox.store(app, listOf(ira, stranger))
        CallInbox.markSeen(app, 0)
        CallActionStore(app).add(listOf(
            CallAction(ira.key + "#0", ira.key, CallAction.PAYMENT, amount = 0.01, token = "SOL", recipient = "Ira"),
            CallAction(ira.key + "#1", ira.key, CallAction.CALLBACK, number = ira.caller, time = "15:00", text = "Call Ira back at 3 PM"),
        ))
        CallActionStore(app).markProcessed(listOf(ira.key, stranger.key))
    }

    private fun seedSeason() {
        val json = javaClass.getResourceAsStream("/season/drops-en.json")!!.bufferedReader().readText()
        net.solardepin.solarchik.season.SeasonDropsStore(app).save("en", json, System.currentTimeMillis())
    }

    @Test fun firstRunStoryInEnglish() {
        dir.listFiles()?.forEach { it.delete() } // no screens left from an older run
        // 1) onboarding, 3 pages
        MainActivity.onboardingEnabled = true
        val ob = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        val o = find(ob.window.decorView, "onboarding") as Onboarding
        for (i in 0..2) {
            audit(o, "0${i + 1}-onboarding-$i")
            if (i < 2) { find(o, "onb-next")!!.performClick(); idle() }
        }
        find(o, "onb-next")!!.performClick(); idle()
        assertEquals(MainActivity.Tab.TODAY, ob.current)
        MainActivity.onboardingEnabled = false

        // 2) Today after the calls: the stack has Ira's 2 cards, then 3 Season tasks; the greeting fits the hour
        seedIra(); seedSeason()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        a.screen(MainActivity.Tab.TODAY)?.onShow(); idle()
        val today = a.window.decorView
        audit(today, "04-today-after-call")
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        assertEquals(app.getString(net.solardepin.solarchik.ui.TodayScreen.greetingFor(hour)), (find(today, "today-greeting") as TextView).text.toString())
        assertTrue(texts(today).toString(), texts(today).contains("MORNING STACK · 1 OF 5"))
        assertEquals("one pay card", 1, count(today, "ca-pay"))
        assertTrue(texts(today).any { it.startsWith("Pay 0.01 SOL") })

        // 3) More: Circle, then forwarding setup (verify + carrier codes)
        seedIra()
        a.select(MainActivity.Tab.SETTINGS); idle()
        audit(a.window.decorView, "07-more-with-circle-and-forwarding")
        assertTrue(texts(a.window.decorView).any { it.startsWith("You owe Ira 0.01 SOL") })
        assertTrue(texts(a.window.decorView).contains("Forward missed calls to Sol"))
        assertTrue(texts(a.window.decorView).any { it.startsWith("Not verified yet") })

        // 4) Add Ira's wallet (prefilled), then the Settle confirm
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
        CallActionStore(app).update(ira.key + "#0") { it.copy(status = CallAction.DONE, signature = "5".repeat(88)) }
        a.screen(MainActivity.Tab.SETTINGS)?.onShow(); idle()
        audit(a.window.decorView, "10-more-circle-settled")
        assertTrue(texts(a.window.decorView).any { it.startsWith("Paid Ira 0.01 SOL") && it.endsWith("Solscan ↗") })

        // 5) the stack: the call-back goes to tomorrow; then the Season tasks, one by one
        a.select(MainActivity.Tab.TODAY); idle()
        audit(a.window.decorView, "11-today-stack-callback")
        assertTrue(texts(a.window.decorView).contains("MORNING STACK · 1 OF 4"))
        find(a.window.decorView, "stack-later")!!.performClick(); idle()
        audit(a.window.decorView, "12-today-stack-season-task")
        assertTrue(find(a.window.decorView, "stack-season") != null)
        assertEquals("MattleFun", (find(a.window.decorView, "stack-season-app") as TextView).text.toString())
        // swipe right / Open: only opens the official link; back in the app, "Done with MattleFun?"
        find(a.window.decorView, "stack-season-open")!!.performClick(); idle()
        val opened = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        assertEquals("https://x.com/mattlefun/status/2107820234271044066", opened.dataString)
        a.screen(MainActivity.Tab.TODAY)?.onShow(); idle()
        val ask = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog() as android.app.AlertDialog
        audit(ask.window!!.decorView, "13-season-done-ask")
        ask.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals("Mentioned", (find(a.window.decorView, "stack-season-app") as TextView).text.toString())
        find(a.window.decorView, "stack-later")!!.performClick(); idle() // later
        find(a.window.decorView, "stack-season-done")!!.performClick(); idle() // I did it
        audit(a.window.decorView, "14-today-clocked-in")
        assertTrue(find(a.window.decorView, "stack-clocked") != null)

        // 6) Calls: Ira is in the Circle now (full number); the stranger's number is masked everywhere
        seedIra()
        val calls = Robolectric.buildActivity(CallsActivity::class.java).create().start().resume().visible().get(); idle()
        audit(calls.window.decorView, "05-calls")
        val ct = texts(calls.window.decorView)
        assertTrue(ct.toString(), ct.any { it.contains("+380 •• ••• •• 33") })
        assertTrue(ct.toString(), ct.none { it.contains("501112233") })
        seedIra()
        val det = Robolectric.buildActivity(CallsActivity::class.java, android.content.Intent(app, CallsActivity::class.java).putExtra(CallsActivity.EXTRA_KEY, stranger.key)).create().start().resume().visible().get(); idle()
        audit(det.window.decorView, "06-call-detail-stranger")
        assertTrue(texts(det.window.decorView).none { it.contains("501112233") })

        // real defaults: mainnet, no wallet connected, so no balances at all; nowhere a placeholder address
        assertTrue(BuildConfig.DEVNET_ONLY.not())
        assertTrue(a.wallet.mainnet)
        dir.listFiles()!!.filter { it.name.endsWith(".txt") }.forEach { f ->
            val t = f.readText()
            assertTrue(f.name + " says devnet", !t.contains("devnet", ignoreCase = true))
            assertTrue(f.name + " shows a seeded balance", !t.contains("1234.5"))
        }
        // nowhere a placeholder address
        dir.listFiles()!!.filter { it.name.endsWith(".txt") }.forEach { f -> assertTrue(f.name, !f.readText().contains("So11")) }
        File(dir, "problems.txt").writeText(problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun masking() {
        assertEquals("+380 •• ••• •• 17", net.solardepin.solarchik.screen.Phones.mask("+380638500117"))
        assertEquals("Call +380 •• ••• •• 17 back", net.solardepin.solarchik.screen.Phones.show(app, "Call +380638500117 back"))
        CircleStore(app).put(Contact("", "Vadim", "+380 63 850 01 17"))
        assertEquals("a Circle contact's number stays", "Call +380638500117 back", net.solardepin.solarchik.screen.Phones.show(app, "Call +380638500117 back"))
        assertEquals("the secretary line stays", "Line +380914810885", net.solardepin.solarchik.screen.Phones.show(app, "Line +380914810885"))
        assertEquals("amounts are not numbers to mask", "Send 0.01 SOL, 50 SKR", net.solardepin.solarchik.screen.Phones.show(app, "Send 0.01 SOL, 50 SKR"))
        net.solardepin.solarchik.screen.Phones.setRevealAll(app, true)
        assertEquals("Call +380501112233", net.solardepin.solarchik.screen.Phones.show(app, "Call +380501112233"))
    }

    @Test fun seasonTasksFollowTheCallCardsAndClockIn() {
        seedSeason()
        val t = System.currentTimeMillis()
        val apps = MS.seasonItems(app, t, "en").map { it.app }
        assertEquals(listOf("MattleFun", "Mentioned", "DiversiFi"), apps)
        MS.touched(app, t)
        assertEquals(false, MS.settle(app, t))
        val first = MS.seasonItems(app, t, "en")
        MS.seasonDone(app, first[0].id, t)
        assertEquals("the next drop moves up but the day keeps 3 at most", listOf("Mentioned", "DiversiFi"), MS.seasonItems(app, t, "en").map { it.app })
        MS.snooze(app, "season:" + first[1].id, t); MS.seasonHandledOne(app, t)
        MS.seasonDone(app, first[2].id, t)
        assertTrue(MS.seasonItems(app, t, "en").isEmpty())
        assertTrue(MS.settle(app, t))
        assertEquals(MS.Answer.DO, MS.answer("open it"))
    }

    @Test fun greetingFollowsTheHour() {
        val ctx = app
        assertEquals("Good morning", ctx.getString(net.solardepin.solarchik.ui.TodayScreen.greetingFor(8)))
        assertEquals("Good afternoon", ctx.getString(net.solardepin.solarchik.ui.TodayScreen.greetingFor(13)))
        assertEquals("Good evening", ctx.getString(net.solardepin.solarchik.ui.TodayScreen.greetingFor(19)))
        val f = org.json.JSONObject().put("now", "Sat 10 Oct 2026, 19:03")
        assertTrue(net.solardepin.solarchik.sol.Briefing.localText(f, ctx).startsWith("Good evening, here is your briefing."))
    }
}
