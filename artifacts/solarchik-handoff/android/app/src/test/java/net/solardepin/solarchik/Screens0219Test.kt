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
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.ui.CallsActivity
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 0.21.9 screens (EN + UK): Home with the Calls card, the Calls list + call detail (with -Plive=1 the REAL
 * worker inbox of the owner id d61556d7… incl. the ayTaC80A call and its transcript; otherwise a fixture),
 * Settings wallet card (no wallet app / built-in devnet wallet), the built-in wallet offer, voice timing line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class Screens0219Test {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "0.21.9").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val live = !System.getProperty("solarchik.live").isNullOrBlank()
    private val realCheck = SolanaWallet.walletAppCheck

    companion object {
        const val OWNER = "d61556d7-b92a-4a54-aa84-95897565439d"
        /** The built-in wallet used for the real devnet mint + Pro buy (LocalWalletDevnetIT). */
        const val LOCAL_WALLET = "C9pVXx7ieotYjaQr76gAgx7bmA67hZQivFTZ2UxGuWnz"
        val FIXTURE = """{"items":[
          {"callId":"rtc_u0_EUfjtp3oN92tzumvMe0LZ6AuayTaC80A","caller":"+380638500117","text":"Вадим: Передати привіт Callback +380638500117.","at":1790979027789,"status":"done","source":"tool",
           "summary":{"caller_name":"Вадим","intent":"Передати привіт","urgency":"low","notes":"","callback":"+380638500117"}},
          {"callId":"live_u1_EUeemp3oNN2yN0RIpGe4JPjl5akbqjb3","caller":"+380638500117","text":"Missed call: the secretary could not pick up (refunded).","at":1790974834883,"status":"failed"}]}"""
    }

    @Before fun off() {
        MainActivity.tickerEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
    }
    @After fun on() {
        MainActivity.tickerEnabled = true
        SolanaWallet.walletAppCheck = realCheck
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }

    private fun findTag(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTag(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun findText(v: View, text: String): View? {
        if (v is TextView && v.text.toString().contains(text, ignoreCase = true)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun shotRoot(root: View, name: String, scrollToText: String? = null) {
        idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val scroll = findScroll(root)
        if (scroll != null && scrollToText != null) {
            val t = findText(scroll, scrollToText)
            var y = 0; var cur: View? = t
            while (cur != null && cur !== scroll) { y += cur.top; cur = cur.parent as? View }
            scroll.scrollTo(0, (y - 140).coerceAtLeast(0))
        }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        scroll?.scrollTo(0, 0)
    }

    private fun shotView(v: View, name: String, width: Int = 1000) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, width, v.measuredHeight)
        val bmp = Bitmap.createBitmap(width, v.measuredHeight.coerceIn(1, 4000), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF07131C.toInt())
        v.draw(c)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seedCalls() {
        app.getSharedPreferences("solarchik.calls", Context.MODE_PRIVATE).edit().clear().commit()
        val items = if (live) requireNotNull(ScreenApi.calls(OWNER)) { "worker /inbox unreachable" } else CallInbox.parse(OWNER, 200, FIXTURE)!!
        assertTrue("ayTaC80A call missing from the inbox", items.any { it.callId.endsWith("ayTaC80A") })
        CallInbox.link(app, OWNER)
        CallInbox.store(app, items)
        CallInbox.markSeen(app, 0)
    }

    private fun run(sfx: String) {
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        seedCalls()
        app.getSharedPreferences("solarchik-voice", Context.MODE_PRIVATE).edit()
            .putString("source", "openai:marin").putLong("ms", 640).putString("lat", "1,420,430,1180,1760,12,300").commit()

        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        // 1.0.0 Home is Today: the secretary card with today's / latest calls, unread dot on the Calls nav item
        // 1.2.7: the phone secretary row is on Me, the unread dot sits on the Me tab
        a.select(MainActivity.Tab.ME)
        idle()
        assertNotNull(findTag(a.window.decorView, "today-secretary"))
        assertTrue(findTag(a.window.decorView, "nav-calls-dot")!!.visibility == View.VISIBLE)
        shotRoot(a.window.decorView, "01-home-calls$sfx", a.getString(R.string.me_sec))

        // Settings: secretary section opens Calls; wallet card without a wallet app; voice timing line
        a.select(MainActivity.Tab.SETTINGS)
        idle()
        shotRoot(a.window.decorView, "02-settings-wallet-none$sfx", a.getString(R.string.settings_wallet))
        shotRoot(a.window.decorView, "03-settings-secretary-calls$sfx", a.getString(R.string.sec_title))
        shotRoot(a.window.decorView, "04-settings-voice-timing$sfx", a.getString(R.string.settings_sol_voice))

        // the built-in wallet offer (what a tablet without Phantom/Solflare sees on Mint / Buy Pro)
        a.select(MainActivity.Tab.AGENTS)
        idle()
        shotRoot(a.window.decorView, "05-agents-paper-vs-devnet$sfx")
        findTag(a.window.decorView, "desk-get-agent")?.performClick()
        idle()
        val offer = (a.screen(MainActivity.Tab.AGENTS) as net.solardepin.solarchik.ui.AgentsScreen).offerDialog
        val mint = offer?.window?.decorView?.let { findTag(it, "offer-mint-free") }
        if (mint != null) {
            mint.performClick()
            repeat(30) { idle() }
            val dlg = ShadowDialog.getLatestDialog()
            assertNotNull("no built-in wallet offer", dlg)
            val txt = texts(dlg.window!!.decorView).joinToString("\n")
            assertTrue(txt, txt.contains(a.getString(R.string.lw_offer_use)))
            shotView(dlg.window!!.decorView, "06-builtin-wallet-offer$sfx", 1000)
            (dlg as? android.app.AlertDialog)?.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.performClick()
            idle()
            offer.dismiss()
        }

        // Settings with the built-in wallet in use (the real devnet wallet from LocalWalletDevnetIT when live)
        app.getSharedPreferences("solarchik-local-wallet", Context.MODE_PRIVATE).edit()
            .putString("sealed", "AAAA").putString("address", LOCAL_WALLET).putLong("createdAt", 1).commit()
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("kind", SolanaWallet.KIND_LOCAL).putString("address", LOCAL_WALLET).commit()
        a.select(MainActivity.Tab.SETTINGS)
        a.screen(MainActivity.Tab.SETTINGS)?.onShow()
        if (live) repeat(40) { idle(); Thread.sleep(50) }
        idle()
        val st = texts(a.window.decorView).joinToString("\n")
        assertTrue(st, st.contains(a.getString(R.string.lw_pill)))
        shotRoot(a.window.decorView, "07-settings-wallet-builtin$sfx", a.getString(R.string.settings_wallet))
        a.select(MainActivity.Tab.AGENTS)
        idle()
        shotRoot(a.window.decorView, "08-agents-builtin$sfx")

        // Calls list + detail (transcript)
        val calls = Robolectric.buildActivity(CallsActivity::class.java).create().start().visible().get()
        idle()
        shotRoot(calls.window.decorView, "09-calls-list$sfx", a.getString(R.string.calls_list_label))
        shotRoot(calls.window.decorView, "09b-calls-list-top$sfx")
        val key = CallInbox.cached(app).first { it.callId.endsWith("ayTaC80A") }.key
        val det = Robolectric.buildActivity(CallsActivity::class.java, Intent(app, CallsActivity::class.java).putExtra(CallsActivity.EXTRA_KEY, key)).create().start().visible().get()
        if (live) {
            var waited = 0
            // 1.2.7: the transcript is collapsed under the note; it loads, then opens on tap
            while (waited < 100 && findTag(det.window.decorView, "call-transcript-toggle") == null) { idle(); Thread.sleep(100); waited++ }
            assertNotNull("transcript did not load from the worker", findTag(det.window.decorView, "call-transcript-toggle"))
        }
        idle()
        shotRoot(det.window.decorView, "10-call-detail$sfx")
        findTag(det.window.decorView, "call-transcript-toggle")?.performClick(); idle()
        if (live) assertNotNull(findText(det.window.decorView, a.getString(R.string.calls_transcript_hint)))
        shotRoot(det.window.decorView, "11-call-detail-transcript$sfx", a.getString(R.string.calls_transcript_label))
        PlayerIds.get(app)
        AgentStore(app)
    }

    @Test fun english() = run("-en")

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun ukrainian() = run("-uk")
}
