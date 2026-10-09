package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.ui.CallsActivity
import org.junit.After
import org.junit.Assert.assertFalse
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

/** 1.1.2 audit: game leftovers gone from the assistant's Settings, chat and Calls, in both languages. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class Audit112Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.1.2").apply { mkdirs() }

    @Before fun setUp() { MainActivity.tickerEnabled = false; MainActivity.onboardingEnabled = false }
    @After fun tearDown() { MainActivity.tickerEnabled = true }

    private fun idle() = repeat(5) { ShadowLooper.idleMainLooper() }
    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }
    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
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
            scroll.scrollTo(0, (y - 900).coerceAtLeast(0))
        }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun gameOnlyRemindersAreOffInTheAssistant() {
        val save = GameSave(app)
        listOf("noteReward", "noteWindow", "noteReport", "noteDesk").forEach { assertFalse(it, save.noteOn(it)) }
        assertTrue(save.noteOn("noteStreak"))
    }

    private fun settingsChecks(lang: String) {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        a.select(MainActivity.Tab.SETTINGS); idle()
        val t = texts(a.window.decorView)
        val banned = if (lang == "en") listOf("Fees & tiers", "Reward ready", "Window ending", "Desk closes", "Daily note", "Player ID", "fee-free")
            else listOf("Комісії", "Вікно", "Деск", "ID гравця")
        val hits = t.filter { s -> banned.any { s.contains(it, ignoreCase = true) } }
        assertTrue("$lang settings leftovers: $hits", hits.isEmpty())
        assertTrue(t.toString(), t.any { it == if (lang == "en") "Check-in reminder" else "Нагадування про відмітку" })
        shot(a.window.decorView, "${lang}_settings_reminders", "sec-player-id")
        val c = Robolectric.buildActivity(CallsActivity::class.java).setup().visible().get(); idle()
        val ct = texts(c.window.decorView)
        assertFalse(ct.toString(), ct.any { it.contains("player", true) || it.contains("гравц", true) })
        shot(c.window.decorView, "${lang}_calls")
    }

    @Test fun englishSettingsAndCallsHaveNoGameLeftovers() = settingsChecks("en")

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun ukrainianSettingsAndCallsHaveNoGameLeftovers() {
        net.solardepin.solarchik.core.AppLocale.set(app, net.solardepin.solarchik.core.AppLocale.UK)
        try { settingsChecks("uk") } finally { app.getSharedPreferences("solarchik-lang", Context.MODE_PRIVATE).edit().clear().commit() }
    }
}
