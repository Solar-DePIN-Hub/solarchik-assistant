package net.solardepin.solarchik

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.game.RunActivity
import net.solardepin.solarchik.game.RunHud
import net.solardepin.solarchik.game.RunResult
import net.solardepin.solarchik.game.run.ChapterId
import net.solardepin.solarchik.game.run.DeathKind
import net.solardepin.solarchik.game.run.Ev
import net.solardepin.solarchik.game.run.Phase
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.ui.roof.RooftopScreen
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 0.22.1 review screenshots (Robolectric native graphics, the test screen's own size) into build/screens/0.22.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h914dp-xxhdpi")
class Shots0221Test {
    // 1.1.4: these tests drive the old game hub screens, which the assistant no longer opens.
    @org.junit.Before fun gameHubOn() { MainActivity.gameHub = true }
    @org.junit.After fun gameHubOff() { MainActivity.gameHub = false }

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck
    private val dir = File(System.getProperty("solarchik.shots") ?: "build/screens", "0.22.3")

    private val realTz = java.util.TimeZone.getDefault()

    @Before fun setUp() {
        // The owner's phone is in Kyiv: the "new day" line then reads 03:00 (UTC midnight), as on the device.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Kiev"))
        MainActivity.tickerEnabled = false
        SolanaWallet.walletAppCheck = { false }
        LocalKey.box = TestBox()
        RooftopScreen.forceMute = true
        listOf("solarchik-roof", "solarchik-game", "solarchik-agents", "solarchik.calls").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun tearDown() {
        java.util.TimeZone.setDefault(realTz)
        MainActivity.tickerEnabled = true
        SolanaWallet.walletAppCheck = realCheck
        RooftopScreen.forceMute = false
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun shot(a: Activity, name: String) {
        idle()
        val root = a.window.decorView
        // the screen's own size (411x914 dp @ xxhdpi): forcing another size would misplace the scene overlay
        val w = root.width.takeIf { it > 0 } ?: 1233
        val h = root.height.takeIf { it > 0 } ?: 2742
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Let transient toasts run out before the next shot. */
    private fun settle() { ShadowLooper.idleMainLooper(8, java.util.concurrent.TimeUnit.SECONDS); idle() }

    // 1.0.0: Today is home; the rooftop is the game behind the Play tile
    private fun open(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle(); it.select(MainActivity.Tab.YARD); idle() }

    private fun click(a: Activity, tag: String) { requireNotNull(find(a.window.decorView, tag)) { tag }.performClick(); idle() }

    @Test fun rooftopTourMenuAndClock() {
        val a = open()
        shot(a, "01-roof-tour-auto-start-uk")
        click(a, "tour-skip"); settle()
        shot(a, "02-roof-uk")
        click(a, "roof-help")
        click(a, "tour-next"); click(a, "tour-next")
        shot(a, "03-roof-tour-clock-step-uk")
        click(a, "tour-skip"); settle()
        click(a, "roof-menu")
        shot(a, "04-roof-menu-uk")
        click(a, "roof-row-judges")
        shot(a, "05a-roof-judges-first-uk")
        var guard = 0
        while (find(a.window.decorView, "tour-link") == null && guard++ < 8) {
            val next = find(a.window.decorView, "tour-next") ?: break
            next.performClick(); idle()
        }
        shot(a, "05-roof-judges-step-uk")
        find(a.window.decorView, "tour-skip")?.performClick(); idle()
        // CLOCK IN glowing (run done, not signed) vs signed
        val save = GameSave(app)
        save.recordRun(1340, 1500)
        val b = open()
        shot(b, "06-roof-clock-ready-uk")
        save.stampClock("Addr111", "sig-test-not-a-real-tx", "devnet", "memo")
        val c = open()
        shot(c, "07-roof-clock-signed-uk")
        c.select(MainActivity.Tab.SHIFT, animate = false)
        shot(c, "07b-clock-in-screen-signed-uk")
    }

    @Test @Config(qualifiers = "en-w411dp-h914dp-xxhdpi")
    fun rooftopEnglish() {
        val a = open()
        click(a, "tour-skip"); settle()
        shot(a, "08-roof-en")
        click(a, "roof-help"); click(a, "tour-next"); click(a, "tour-next")
        shot(a, "08b-roof-tour-clock-step-en")
        click(a, "tour-skip"); settle()
        click(a, "roof-menu")
        shot(a, "08c-roof-menu-en")
    }

    private fun tabletSet(prefix: String) {
        val a = open()
        click(a, "tour-skip"); settle()
        shot(a, "$prefix-roof")
        click(a, "roof-help"); click(a, "tour-next"); click(a, "tour-next")
        shot(a, "$prefix-tour-clock-step")
        click(a, "tour-skip"); settle()
        click(a, "roof-menu")
        shot(a, "$prefix-menu")
        click(a, "roof-row-judges")
        var guard = 0
        while (find(a.window.decorView, "tour-link") == null && guard++ < 8) {
            val next = find(a.window.decorView, "tour-next") ?: break
            next.performClick(); idle()
        }
        shot(a, "$prefix-judges-step")
    }

    @Test @Config(qualifiers = "uk-w1280dp-h800dp-land-hdpi")
    fun tabletLandscapeUk() = tabletSet("20-tablet-land-uk")

    @Test @Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
    fun tabletLandscapeEn() = tabletSet("21-tablet-land-en")

    @Test @Config(qualifiers = "uk-w800dp-h1280dp-port-hdpi")
    fun tabletPortraitUk() = tabletSet("22-tablet-port-uk")

    @Test fun sliceAndCalls() {
        val a = open()
        a.select(MainActivity.Tab.AGENTS, animate = false)
        (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).openSection(3)
        shot(a, "09-slice-uk")
        val calls = Robolectric.buildActivity(net.solardepin.solarchik.ui.CallsActivity::class.java).setup().visible().get()
        assertNotNull(find(calls.window.decorView, "calls-sec-settings"))
        shot(calls, "10-calls-uk")
    }

    private fun hud(phase: Phase, m: Int) = RunHud(
        hearts = if (phase == Phase.DEAD) 0 else 3, shield = 0, score = m, meters = m, combo = 0, phase = phase, countdown = 0.0,
        death = DeathKind.HIT, suns = 12, maxCombo = 3, bonus = false, bonusLeft = 0.0, grind = false, didBonus = false,
        chapter = ChapterId.STORM, announce = "", announceOn = false, clockOpen = true,
    )

    private fun drawView(v: View, name: String) {
        // the run is landscape-only
        v.measure(View.MeasureSpec.makeMeasureSpec(2742, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 2742, 1233)
        val bmp = Bitmap.createBitmap(2742, 1233, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF1B2B3A.toInt()) // stands in for the game canvas behind the HUD
        v.draw(c)
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The run HUD/cards over a plain backdrop (the game canvas needs the real GL-free loop, not drawn here). */
    @Test @Config(qualifiers = "uk-w914dp-h411dp-land-xxhdpi")
    fun runAlreadySigned() {
        val o = net.solardepin.solarchik.game.RunOverlay(app, object : net.solardepin.solarchik.game.RunOverlay.Actions {
            override fun pauseToggle() {}; override fun resume() {}; override fun yard() {}; override fun again() {}
            override fun sign() {}; override fun signBadge() {}; override fun share() {}; override fun yardSign() {}
            override fun musicToggle() {}; override fun mic() {}; override fun slideDown() {}; override fun slideUp() {}
        }).also { it.animations = false }
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        host.setContentView(o)
        idle()
        o.setClock(net.solardepin.solarchik.game.RunOverlay.ClockUi(open = true, signed = true, wallet = true, dayLine = "CLOCK IN · 1340 м · серія 1"))
        o.bind(hud(Phase.RUNNING, 1200))
        o.celebrateClock()
        drawView(o, "11-run-1200m-already-signed-uk")
        o.bind(hud(Phase.DEAD, 1610))
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        idle()
        drawView(o, "12-run-end-already-signed-uk")
    }
}
