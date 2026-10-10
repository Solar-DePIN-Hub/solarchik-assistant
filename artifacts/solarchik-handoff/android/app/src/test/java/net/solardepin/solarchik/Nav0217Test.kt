package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.agents.SliceBook
import net.solardepin.solarchik.agents.SlicePrice
import net.solardepin.solarchik.agents.SlicePrices
import net.solardepin.solarchik.agents.SliceStocks
import net.solardepin.solarchik.ui.AgentsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 0.21.7 #13/#16 floating nav + home cards, #15 Slice restored. Renders every tab EN/UK to PNG. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class Nav0217Test {
    // 1.1.4: these tests drive the old game hub screens, which the assistant no longer opens.
    @org.junit.Before fun gameHubOn() { MainActivity.gameHub = true }
    @org.junit.After fun gameHubOff() { MainActivity.gameHub = false }

    private val dir = File(System.getProperty("solarchik.shots") ?: "build/screens", "nav-0217")

    private fun open(): MainActivity {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().clear().putString("address", ScreensTest.WALLET).commit()
        MainActivity.tickerEnabled = false
        return Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
    }

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView && v.visibility == View.VISIBLE) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) if (v.getChildAt(i).visibility == View.VISIBLE) texts(v.getChildAt(i), out)
        return out
    }

    private fun shot(a: MainActivity, name: String) {
        repeat(5) { ShadowLooper.idleMainLooper() }
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun floatingNavHomeCardsAndEveryTab() {
        val a = open()
        val d = a.window.decorView
        // 1.0.0: Today is home; Sol's mic is the raised centre button; the game sits behind a small tile
        assertEquals(MainActivity.Tab.TODAY, a.current)
        assertNotNull(find(d, "nav-bar"))
        // 1.2.7: Today · Circle · [mic] · Me, a dot under the active tab
        assertEquals(listOf("Today", "Circle", "Me"), listOf("nav-today", "nav-circle", "nav-me").map { find(d, it)!!.contentDescription.toString() })
        assertNotNull(find(d, "nav-mic"))
        assertNotNull("1.2.9: Calls is a bar tab", find(d, "nav-calls"))
        assertEquals(View.VISIBLE, find(d, "nav-dot-today")!!.visibility)
        shot(a, "en-1-home")
        a.select(MainActivity.Tab.ME, animate = false)
        assertNotNull("Secretary is on Me", find(d, "today-secretary"))
        assertTrue(texts(find(d, "today-secretary")!!).contains("Phone secretary"))
        assertEquals(View.VISIBLE, find(d, "nav-dot-me")!!.visibility)
        a.select(MainActivity.Tab.AGENTS, animate = false); shot(a, "en-2-agents")
        assertEquals("Agents sits under Me", View.VISIBLE, find(d, "nav-dot-me")!!.visibility)
        a.select(MainActivity.Tab.RUN, animate = false); shot(a, "en-3-play")
        a.select(MainActivity.Tab.SOL, animate = false); shot(a, "en-4-sol")
        a.select(MainActivity.Tab.SETTINGS, animate = false); shot(a, "en-5-more")
        a.select(MainActivity.Tab.CIRCLE, animate = false); shot(a, "en-6-circle")
        assertEquals(View.VISIBLE, find(d, "nav-dot-circle")!!.visibility)
    }

    @Test fun homeSecretaryCardOpensTheCallsListDirectly() {
        // 0.22.0 (owner): no intermediate Settings screen on the way to the calls
        val a = open()
        a.select(MainActivity.Tab.ME, animate = false)
        find(a.window.decorView, "me-calls")!!.performClick()
        ShadowLooper.idleMainLooper()
        val next = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        assertEquals(net.solardepin.solarchik.ui.CallsActivity::class.java.name, next?.component?.className)
    }

    @Test fun sliceIsHiddenFromTheAssistantAgents() {
        val a = open()
        // 1.1.0: the assistant shows three agents; Slice (paper stocks) stays in code for the game but is not shown
        a.select(MainActivity.Tab.AGENTS, animate = false)
        (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).openSection(3)
        ShadowLooper.idleMainLooper()
        assertEquals(MainActivity.Tab.AGENTS, a.current)
        assertEquals(AgentsScreen.WATCHER, (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).section)
        val all = texts(a.window.decorView).joinToString("\n")
        assertFalse(all, all.contains("Paper portfolio · simulated, no real trades") || all.contains("Open Slice site"))
        shot(a, "en-6-agents-watcher")
    }

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun ukrainianNavAndTabs() {
        val a = open()
        val d = a.window.decorView
        assertEquals(listOf("Сьогодні", "Коло", "Я"), listOf("nav-today", "nav-circle", "nav-me").map { find(d, it)!!.contentDescription.toString() })
        shot(a, "uk-1-home")
        a.select(MainActivity.Tab.AGENTS, animate = false); shot(a, "uk-2-agents")
        a.select(MainActivity.Tab.RUN, animate = false); shot(a, "uk-3-play")
        a.select(MainActivity.Tab.SOL, animate = false); shot(a, "uk-4-sol")
        a.select(MainActivity.Tab.SETTINGS, animate = false); shot(a, "uk-5-more")
        (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).let { a.select(MainActivity.Tab.AGENTS); it.openSection(AgentsScreen.SAVER) }
        assertTrue(texts(d).joinToString("\n").contains("Скарбничка"))
        assertFalse(texts(d).joinToString("\n").contains("Паперовий портфель"))
        shot(a, "uk-6-saver")
    }

    @Test fun slicePaperBookAndPriceParsing() {
        val p = SlicePrices.parse("""{"XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp":{"usdPrice":250.5,"priceChange24h":-1.2},"bad":{"usdPrice":0}}""")
        assertEquals(SlicePrice(250.5, -1.2), p["XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp"])
        assertNull(p["bad"])
        assertEquals(10, SliceStocks.all.size)
        val m = SliceStocks.all[0].mint
        val b = SliceBook().buy(m, 50.0, 250.0)!!
        assertEquals(950.0, b.cash, 1e-9)
        assertEquals(1010.0, b.value(mapOf(m to SlicePrice(300.0, null))), 1e-9)
        assertNull("no cash beyond $1000", SliceBook(cash = 10.0).buy(m, 50.0, 1.0))
        assertEquals(1010.0, b.sellAll(m, 300.0)!!.cash, 1e-9)
    }
}
