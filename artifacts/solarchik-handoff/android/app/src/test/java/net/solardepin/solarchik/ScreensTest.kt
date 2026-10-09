package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.agents.OwnedAgent
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.FeeLedger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Opens every tab with realistic data and renders it to PNG.
 * Set -Dsolarchik.shots=/path to choose the output dir (default build/screens).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreensTest {
    // 1.1.4: these tests drive the old game hub screens, which the assistant no longer opens.
    @org.junit.Before fun gameHubOn() { MainActivity.gameHub = true }
    @org.junit.After fun gameHubOff() { MainActivity.gameHub = false }

    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens")

    private fun seed(streakDays: Int, activeWindow: Boolean) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val game = ctx.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE)
        game.edit().clear().commit()
        val today = LocalDate.now(ZoneOffset.UTC)
        val days = (streakDays - 1 downTo 1).map { today.minusDays(it.toLong()).toString() }
        val now = System.currentTimeMillis()
        val window = if (activeWindow) {
            """[{"id":"h48-1","kind":"h48","milestone":7,"status":"active","grantedAt":$now,"startedAt":${now - 5 * 3600_000L},"endsAt":${now + 43 * 3600_000L - 754_000L}}]"""
        } else "[]"
        game.edit()
            .putInt("streak", streakDays - 1)
            .putString("signedDay", today.minusDays(1).toString())
            .putInt("seven", (streakDays - 1) % 7)
            .putInt("thirty", streakDays - 1)
            .putString("clockDays", days.joinToString(",", "[", "]") { "\"$it\"" })
            .putString("feeWindows", window)
            .putString("runDay", today.toString())
            .putInt("lastDistance", 1200).putInt("lastScore", 1460)
            .putInt("bestDistance", 1200).putInt("bestScore", 1720)
            .putString("lastClockDay", today.toString())
            .commit()
        val wallet = ctx.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE)
        wallet.edit().clear().putString("address", WALLET).commit()
        ctx.getSharedPreferences("solarchik-agents", Context.MODE_PRIVATE).edit().clear().commit()
        val store = AgentStore(ctx)
        store.upsert(OwnedAgent("Fz6LxeUg5qjesYX3BdmtTwyyzBtMxk644XiTqU5W3w9w", "sku-pred-alpha", AgentTier.FREE, "Bitcoin Windows #11", WALLET, "devnet", "sig", now - 86_400_000L, OwnedAgent.STATUS_VERIFIED))
        store.upsert(OwnedAgent("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", "sku-combo-prime-pro", AgentTier.PRO, "Combo Prime Pro", WALLET, "devnet", "sig2", now - 3_600_000L, OwnedAgent.STATUS_VERIFIED))
        val h = 3_600_000L
        store.addFee(FeeLedger.planFee("p1", "Bitcoin Windows #11", AgentTier.FREE, now - 30 * h, now - 29 * h, 0.0124, false, false))
        store.addFee(FeeLedger.planFee("p2", "Bitcoin Windows #11", AgentTier.FREE, now - 20 * h, now - 19 * h, -0.006, false, false))
        store.addFee(FeeLedger.planFee("p3", "Combo Prime Pro", AgentTier.PRO, now - 6 * h, now - 4 * h, 0.031, false, false))
        store.addFee(FeeLedger.planFee("p4", "Bitcoin Windows #11", AgentTier.FREE, now - 4 * h, now - 2 * h, 0.018, false, true))
    }

    private fun shot(a: MainActivity, name: String, height: Int = 2400) {
        ShadowLooper.idleMainLooper()
        val root: View = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // full-length page too
        val content = (a.window.decorView.findViewById<View>(android.R.id.content) as android.view.ViewGroup)
        val scroll = findScroll(content) ?: return
        val inner = scroll.getChildAt(0)
        inner.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val full = Bitmap.createBitmap(1080, inner.measuredHeight.coerceAtMost(9000), Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        c.drawColor(0xFF07131C.toInt())
        inner.layout(0, 0, 1080, inner.measuredHeight)
        inner.draw(c)
        File(outDir, "$name-full.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findScroll(v: View): android.widget.ScrollView? {
        if (v is android.widget.ScrollView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun rendersEveryTab() {
        seed(streakDays = 5, activeWindow = false)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        shot(a, "01-yard")
        a.select(MainActivity.Tab.RUN); shot(a, "02-run")
        a.select(MainActivity.Tab.AGENTS); shot(a, "03-agents")
        a.select(MainActivity.Tab.SOL); shot(a, "05-sol")
        a.select(MainActivity.Tab.SETTINGS); shot(a, "06-settings")
        assertEquals(MainActivity.Tab.SETTINGS, a.current)
    }

    /** Lobby with a lived-in garage: balance, owned gear, quests half done, then the skins shelf. */
    @Test fun rendersRunShop() {
        seed(streakDays = 5, activeWindow = false)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val day = net.solardepin.solarchik.core.StreakRules.dayKey(System.currentTimeMillis())
        ctx.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit()
            .putInt("suns", 860).putInt("totalSuns", 640)
            .putString("unlockedRobots", "stock,sunflower,hetman").putString("robot", "hetman")
            .putString("unlockedSkins", "flag,gold,cherry,frost").putString("skin", "frost")
            .putString("questDay", day).putString("questDone", "suns").putBoolean("questChest", false)
            .putInt("quest_suns", 25).putInt("quest_combo", 5).putInt("quest_clock", 1200)
            .putInt("quest_b_stomp", 3).putInt("quest_b_grind", 2).putInt("quest_b_under", 1).putInt("quest_b_suns", 41)
            .commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        a.select(MainActivity.Tab.RUN); shot(a, "10-run-shop")
        val skins = findText(a.window.decorView, a.getString(R.string.shop_roofs))
        requireNotNull(skins).performClick()
        shot(a, "11-run-shop-skins")
    }

    private fun findText(v: View, text: String): View? {
        if (v is android.widget.TextView && v.text.toString() == text) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test fun rendersActiveWindowAndProTier() {
        seed(streakDays = 8, activeWindow = true)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        shot(a, "07-yard-window-active")
        a.select(MainActivity.Tab.AGENTS)
        shot(a, "04-agents-ledger")
    }

    @Test @Config(qualifiers = "uk-w411dp-h914dp-xxhdpi")
    fun rendersUkrainian() {
        seed(streakDays = 3, activeWindow = false)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        shot(a, "08-yard-uk")
        a.select(MainActivity.Tab.AGENTS); shot(a, "09-agents-uk")
    }

    companion object {
        const val WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    }
}
