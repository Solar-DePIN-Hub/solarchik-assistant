package net.solardepin.solarchik

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.game.RunActivity
import net.solardepin.solarchik.game.RunOverlay
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.ui.CallsActivity
import net.solardepin.solarchik.ui.PaymentDetailSheet
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
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 1.2.10 (Vadym's tablet run of 1.2.9). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xxhdpi")
class Release1210Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(System.getProperty("solarchik.compare") ?: "/workspace/deliverables/redesign/compare-1.2.10/build").apply { mkdirs() }
    private val realCluster = System.getProperty("solarchik.cluster")

    @Before fun setUp() {
        System.setProperty("solarchik.cluster", "mainnet")
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
    }
    @After fun tearDown() {
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
        MainActivity.tickerEnabled = true
        net.solardepin.solarchik.screen.ScreenApi.requestForTest = null
        net.solardepin.solarchik.core.AppLocale.set(app, net.solardepin.solarchik.core.AppLocale.EN)
    }

    private fun idle() = repeat(8) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun walk(v: View, f: (View) -> Unit) { f(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f) }
    private fun find(root: View, tag: String): View? { var r: View? = null; walk(root) { if (r == null && it.tag == tag && it.isShown) r = it }; return r }
    private fun texts(root: View): List<String> { val o = mutableListOf<String>(); walk(root) { if (it is TextView && it.isShown) o += it.text.toString() }; return o }
    private fun shot(root: View, name: String, w: Int = 1080, h: Int = 2340) {
        idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Canvas(bmp).apply { root.draw(this) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
        File(dir, "$name.txt").writeText(texts(root).filter { it.isNotBlank() }.joinToString("\n"))
    }
    private val cyr = Regex("[\\u0400-\\u04FF]")

    private fun runHud(phase: net.solardepin.solarchik.game.run.Phase, meters: Int) = net.solardepin.solarchik.game.RunHud(
        hearts = if (phase == net.solardepin.solarchik.game.run.Phase.DEAD) 0 else 3, shield = 0, score = meters, meters = meters, combo = 0,
        phase = phase, countdown = 0.0, death = net.solardepin.solarchik.game.run.DeathKind.HIT, suns = 3, maxCombo = 1, bonus = false,
        bonusLeft = 0.0, grind = false, didBonus = false, chapter = net.solardepin.solarchik.game.run.ChapterId.STORM, announce = "",
        announceOn = false, clockOpen = true,
    )

    /** 1) the old game-over card with "Sign today" is gone: score, Play again, Back to Today, nothing to sign. */
    @Test @Config(qualifiers = "en-w891dp-h411dp-xxhdpi") fun runResultIsTheNewCardWithoutSigning() {
        assertFalse(RunOverlay.runSigning)
        app.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().clear().commit()
        val a = Robolectric.buildActivity(RunActivity::class.java).setup().get()
        val root = a.window.decorView
        a.onHud(runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1199))
        a.onEvents(listOf(net.solardepin.solarchik.game.run.Ev.CLOCK), runHud(net.solardepin.solarchik.game.run.Phase.RUNNING, 1200))
        assertNull("no Sign badge / banner in the run", find(root, "clock-banner"))
        a.onResult(net.solardepin.solarchik.game.RunResult(runHud(net.solardepin.solarchik.game.run.Phase.DEAD, 1300)))
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        val end = texts(root)
        assertTrue("$end", end.none { it == app.getString(R.string.run_sign_now) || it == app.getString(R.string.run_sign_btn) || it == app.getString(R.string.run_signing) })
        assertEquals("Play again", (find(root, "run-again") as TextView).text.toString())
        assertEquals("Back to Today", (find(root, "run-back-today") as TextView).text.toString())
        assertEquals("1300 points", (find(root, "run-result-score") as TextView).text.toString())
        // the loading cover waits for the game's first frames (none under Robolectric): hide it for the render
        a.onLoadingDoneForTest()
        shot(root, "01-run-result", 2673, 1233)
        a.sign() // nothing to sign in the run
        assertNull(shadowOf(a).nextStartedActivity)
        find(root, "run-back-today")!!.performClick()
        assertTrue(a.isFinishing)
    }

    /** 2) a rotation (the activities handle it themselves) must not switch an English UI to the phone's Ukrainian. */
    @Test @Config(qualifiers = "uk-w411dp-h891dp-xxhdpi") fun rotationKeepsTheAppLanguageOnTheSeasonScreen() {
        net.solardepin.solarchik.core.AppLocale.set(app, net.solardepin.solarchik.core.AppLocale.EN)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        // what Android does on rotation with configChanges: the activity resources get the phone's (uk) locale back
        val uk = android.content.res.Configuration(a.resources.configuration).apply { setLocales(android.os.LocaleList(java.util.Locale("uk", "UA"))); orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        @Suppress("DEPRECATION") a.resources.updateConfiguration(uk, a.resources.displayMetrics)
        a.onConfigurationChanged(uk)
        assertEquals("en", a.resources.configuration.locales[0].language)
        a.select(MainActivity.Tab.SEASON); idle()
        val t = texts(a.window.decorView).filter { cyr.containsMatchIn(it) }
        assertTrue("Ukrainian on the English Season screen: $t", t.isEmpty())
        shot(a.window.decorView, "02-season-en")
        val ap = find(a.window.decorView, "ap-card")
        if (ap != null) { (find(a.window.decorView, "ap-on"))?.let { assertTrue("Autopilot button tall enough", it.height >= (44 * a.resources.displayMetrics.density).toInt()) } }
    }

    /** 3) call detail: a visible Back pill, and the conversation loads on a retry instead of "offline". */
    @Test fun callDetailRetriesAndHasAVisibleBack() {
        val ira = net.solardepin.solarchik.screen.CallItem("owner-1", "rtc_u2_EXOf3p3oNExpy0LDYjrfS88wGP9fOXvu", "+380637443792", "Ira says they paid for lunch yesterday.", System.currentTimeMillis() - 60_000, CallInbox.DONE, "tool", "Ira", "asks for 0.01 SOL", "", "", "+380637443792", "en", 65, 0.0, false)
        CallInbox.store(app, listOf(ira))
        var calls = 0
        net.solardepin.solarchik.screen.ScreenApi.requestForTest = { _, url, _ ->
            if (url.contains("/call?")) {
                calls++
                if (calls == 1) 0 to "{\"error\":\"offline\"}"
                else 200 to JSONObject().put("item", JSONObject().put("callId", ira.callId).put("caller", ira.caller).put("text", ira.text).put("at", ira.at).put("status", "done")
                    .put("summary", JSONObject().put("caller_name", "Ira"))).put("lines", org.json.JSONArray().put(JSONObject().put("who", "caller").put("text", "Hi, it's Ira."))).put("durationSec", 65).toString()
            } else 200 to "{\"items\":[]}"
        }
        val d = Robolectric.buildActivity(CallsActivity::class.java, Intent(app, CallsActivity::class.java).putExtra(CallsActivity.EXTRA_KEY, ira.key)).setup().visible().get()
        ShadowLooper.idleMainLooper(6, java.util.concurrent.TimeUnit.SECONDS); idle()
        val root = d.window.decorView
        val back = find(root, "calls-back") as TextView
        assertEquals("Back", back.text.toString())
        assertTrue("retried after the first failure", calls >= 2)
        assertTrue(texts(root).none { it == app.getString(R.string.calls_transcript_offline) || it == app.getString(R.string.calls_transcript_failed) })
        shot(root, "03-call-detail-back")
    }

    /** 4) a settled payment opens an in-app detail first; Solscan only behind its button (a Custom Tab). */
    @Test fun settledPaymentOpensADetailSheetThenSolscan() {
        val owner = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", owner).putString("auth", "t").putString("walletPkg", "app.phantom").commit()
        val ira = net.solardepin.solarchik.screen.CallItem("owner-1", "rtc_ira", "+380637443792", "Ira: send me 0.01 SOL for lunch.", System.currentTimeMillis() - 3_600_000, CallInbox.DONE, "tool", "Ira", "asks for 0.01 SOL", "", "", "+380637443792", "en", 65, 0.0, false)
        CallInbox.store(app, listOf(ira))
        net.solardepin.solarchik.circle.CircleStore(app).put(net.solardepin.solarchik.circle.Contact("", "Ira", "+380637443792", "GxkJ7mUu1wQyDUWvW5XyMTnAWWBCKqMQnmBzX6rZrRsj"))
        val sig = "4tFqLh9z" + "5".repeat(80)
        CallActionStore(app).add(listOf(CallAction(ira.key + "#p", ira.key, CallAction.PAYMENT, amount = 0.01, token = "SOL", recipient = "Ira", status = CallAction.DONE, signature = sig)))
        CallActionStore(app).markProcessed(listOf(ira.key))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        a.select(MainActivity.Tab.CIRCLE); idle()
        find(a.window.decorView, "circle-settled")!!.performClick(); idle()
        assertNull("no browser before the sheet", shadowOf(a).nextStartedActivity)
        val sheet = PaymentDetailSheet.last!!.window!!.decorView
        assertEquals("0.01 SOL", (find(sheet, "pay-detail-amount") as TextView).text.toString())
        assertTrue(texts(sheet).toString(), texts(sheet).any { it.startsWith("Ira") })
        assertTrue(texts(sheet).any { it == "4tFq…5555" })
        run {
            val w = 1080; sheet.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST)); sheet.layout(0, 0, w, sheet.measuredHeight)
            val bmp = Bitmap.createBitmap(w, sheet.measuredHeight, Bitmap.Config.ARGB_8888); Canvas(bmp).apply { drawColor(0xFF07131C.toInt()); sheet.draw(this) }
            File(dir, "04-payment-detail.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
            File(dir, "04-payment-detail.txt").writeText(texts(sheet).joinToString("\n"))
        }
        find(sheet, "pay-detail-solscan")!!.performClick(); idle()
        val tab = shadowOf(a).nextStartedActivity!!
        assertEquals(Intent.ACTION_VIEW, tab.action)
        assertTrue(tab.dataString!!.startsWith("https://solscan.io/tx/$sig"))
        assertTrue("a Custom Tab", tab.extras!!.containsKey("android.support.customtabs.extra.SESSION"))
    }

    /** 5) the owner's name is spelled Vadym everywhere a note is shown or read out. */
    @Test fun notesSpellTheOwnerVadym() {
        val o = JSONObject().put("callId", "x").put("text", "Ira asks Vadim to send them 0.01 SOL.").put("at", 1L).put("status", "done")
            .put("summary", JSONObject().put("caller_name", "Ira").put("intent", "Asks Vadim to call back"))
        val it = CallInbox.item("u", o)!!
        assertEquals("Ira asks Vadym to send them 0.01 SOL.", it.text)
        assertEquals("Asks Vadym to call back", it.intent)
        assertEquals("Vadimir stays", CallInbox.fixNames("Vadimir stays"))
    }
}
