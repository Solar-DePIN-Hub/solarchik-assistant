package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.agents.OwnedAgent
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.screen.Secretary
import net.solardepin.solarchik.ui.AgentsScreen
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
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 0.21.8 screens (EN + UK): Settings secretary + Sol's voice, Home secretary card, Agents ownership + offer card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class Screens0218Test {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "0.21.8").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before fun off() { MainActivity.tickerEnabled = false }
    @After fun on() { MainActivity.tickerEnabled = true }

    private fun seed() {
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", ScreensTest.WALLET).commit()
        AgentStore(app).upsert(OwnedAgent("Fz6LxeUg5qjesYX3BdmtTwyyzBtMxk644XiTqU5W3w9w", "sku-pred-alpha", AgentTier.FREE, "Bitcoin Windows #11", ScreensTest.WALLET, "devnet", "sig", System.currentTimeMillis(), OwnedAgent.STATUS_VERIFIED))
        Secretary.setLastBalance(app, ScreenApi.Balance(usd = 1.42, paidUsd = 0.42, trialUsd = 1.0, trial = true))
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }

    private fun findTag(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTag(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun findText(v: View, text: String): View? {
        if (v is TextView && v.text.toString().equals(text, ignoreCase = true)) return v
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

    private fun shot(a: MainActivity, name: String, scrollToText: String? = null) {
        idle()
        val root: View = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val scroll = findScroll(root)
        if (scroll != null && scrollToText != null) {
            val t = findText(scroll, scrollToText)
            var y = 0; var cur: View? = t
            while (cur != null && cur !== scroll) { y += cur.top; cur = cur.parent as? View }
            scroll.scrollTo(0, (y - 120).coerceAtLeast(0))
        }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        scroll?.scrollTo(0, 0)
    }

    private fun shotView(v: View, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, 1000, v.measuredHeight)
        val bmp = Bitmap.createBitmap(1000, v.measuredHeight.coerceIn(1, 4000), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF07131C.toInt())
        v.draw(c)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun run(sfx: String) {
        seed()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        // Home: secretary card with credit
        a.select(MainActivity.Tab.SHIFT)
        shot(a, "01-home$sfx", a.getString(R.string.home_sec_title).takeIf { it.isNotBlank() })
        // Settings: secretary (balance, trial, language, player id) and Sol's voice
        a.select(MainActivity.Tab.SETTINGS)
        idle()
        val all = texts(a.window.decorView).joinToString("\n")
        assertTrue(all, all.contains(a.getString(R.string.settings_sol_voice)))
        assertNotNull(findTag(a.window.decorView, "voice-marin"))
        shot(a, "02-settings-secretary$sfx", a.getString(R.string.sec_title))
        shot(a, "03-settings-voice$sfx", a.getString(R.string.settings_sol_voice))
        findScroll(a.window.decorView)?.getChildAt(0)?.let { inner ->
            val y = (findText(inner, a.getString(R.string.sec_title))?.let { t -> var yy = 0; var c: View? = t; while (c != null && c !== inner) { yy += c.top; c = c.parent as? View }; yy } ?: 0)
            inner.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            inner.layout(0, 0, 1080, inner.measuredHeight)
            val h = 2600.coerceAtMost(inner.measuredHeight - y + 100)
            val bmp = Bitmap.createBitmap(1080, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(0xFF07131C.toInt()); c.translate(0f, -(y - 100).toFloat().coerceAtLeast(0f)); inner.draw(c)
            File(outDir, "02b-settings-secretary-full$sfx.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Agents: owned vs not owned; the offer card
        a.select(MainActivity.Tab.AGENTS)
        idle()
        val agentsText = texts(a.window.decorView).joinToString("\n")
        // 1.1.0: three assistant agents, each with its Free/Pro NFT
        for (n in listOf(R.string.aa_season, R.string.aa_saver, R.string.aa_watcher)) assertTrue(agentsText, agentsText.contains(a.getString(n)))
        assertNotNull(findTag(a.window.decorView, "agent-season"))
        shot(a, "04-agents$sfx", a.getString(R.string.aa_season))
    }

    @Test fun english() = run("-en")

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun ukrainian() = run("-uk")
}
