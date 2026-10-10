package net.solardepin.solarchik.game

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.solardepin.solarchik.R
import net.solardepin.solarchik.game.run.DayMod
import net.solardepin.solarchik.game.run.Ev
import net.solardepin.solarchik.game.run.Phase
import net.solardepin.solarchik.game.run.RunAudio
import net.solardepin.solarchik.game.run.RunSim
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError

/**
 * Full-screen immersive roof run (landscape, like the web runner). Hosts the RunView surface and
 * the RunOverlay HUD. Crossing GameSave.GOAL_M opens today's CLOCK IN at once without stopping
 * the run (banner + HUD Sign badge; signing uses the shared [ClockIn] flow). The last heart
 * records the run, pays its suns, daily quests and milestones, and reveals the rewards.
 */
class RunActivity : ComponentActivity(), RunView.Listener, RunOverlay.Actions {
    private lateinit var save: GameSave
    private var hintSeen = false
    private lateinit var game: RunView
    private lateinit var overlay: RunOverlay
    private lateinit var audio: RunAudio
    private lateinit var radio: RunRadio
    private lateinit var garage: RunGarage
    private lateinit var quests: RunQuests
    private lateinit var wallet: SolanaWallet
    private lateinit var sender: ActivityResultSender
    private val scope = MainScope()
    private var hud: RunHud? = null
    private var paused = false
    private var ended = false
    private var signing = false
    private var signError = ""
    private val announced = HashSet<String>()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // the run was paused before the dialog; the player resumes, then talks
        if (!ok) overlay.setCaption(getString(R.string.run_mic_denied))
    }

    /** App language (phone language, or the player's pick in Settings). */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(net.solardepin.solarchik.core.AppLocale.wrap(newBase))
    }

    /** 1.2.10: a rotation must not switch the UI to the phone's language (see AppLocale.pin). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        net.solardepin.solarchik.core.AppLocale.pin(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        save = GameSave(this)
        garage = RunGarage(this)
        quests = RunQuests(this, garage)
        wallet = SolanaWallet(this)
        sender = ActivityResultSender(this)
        audio = RunAudio(this).also { it.prepare { audioReady = true; maybeGo() } }
        game = RunView(this, this).also {
            it.audio = audio
            it.prime(garage.robot, garage.skin)
            it.onReady = { artReady = true; maybeGo() }
        }
        overlay = RunOverlay(this, this)
        radio = RunRadio(this, scope, lang(), audio, onCaption = { overlay.setCaption(it) }, onListening = { overlay.setListening(it) })
        overlay.buddy.setImageBitmap(runCatching { assets.open("sprites/pet/buddy-talk-3.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull())
        overlay.setMuted(audio.musicMuted)
        overlay.onSunTarget = { x, y -> game.sunTargetX = x; game.sunTargetY = y }
        overlay.setMicAvailable(android.speech.SpeechRecognizer.isRecognitionAvailable(this))

        val root = FrameLayout(this)
        root.addView(game, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            overlay.setSafeInsets(c.left, c.top, c.right, c.bottom)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ended) yard() else pauseToggle()
            }
        })
        // 0.21.7: no countdown until the art is warm and the music is prepared (loading cover meanwhile).
        overlay.setLoading(true)
        game.postDelayed({ audioReady = true; maybeGo() }, AUDIO_WAIT_MS) // never wait on audio forever
    }

    private var artReady = false
    private var audioReady = false
    private var begun = false

    /** Start the first run once both the art and the audio are ready. */
    private fun maybeGo() {
        if (begun || !artReady || !audioReady || isFinishing) return
        begun = true
        overlay.setLoading(false)
        startRun()
    }

    private fun lang() = if (resources.configuration.locales[0].language == "uk") "uk" else "en"

    private fun setup(): RunSetup {
        val today = save.today()
        return RunSetup(
            seed = RunSim.daySeed(today),
            mod = DayMod.of(save.dayMod()),
            offerBonus = save.offerBonus(),
            goalMeters = GameSave.GOAL_M,
            // native daily care: a live CLOCK IN streak starts the run with a shield (web pet care)
            careBoost = save.streak > 0,
            skin = garage.skin,
            robot = garage.robot,
            tutorial = !save.runTutorialDone,
        )
    }

    private fun startRun() {
        ended = false
        paused = false
        hud = null
        signError = ""
        announced.clear()
        overlay.setPaused(false)
        refreshClock()
        overlay.setCaption("")
        game.start(setup())
        audio.play("start")
        audio.startMusic()
        radio.live = true
        radio.paused = false
        radio.begin()
    }

    // ---- RunView.Listener (UI thread) ----

    override fun onHud(hud: RunHud) {
        this.hud = hud
        // the tutorial hint is a once-per-phone thing: once it has shown and gone, it stays gone
        if (hud.hint) hintSeen = true else if (hintSeen && hud.phase == Phase.RUNNING && !save.runTutorialDone) save.runTutorialDone = true
        overlay.bind(hud)
        radio.onHud(hud)
        if (hud.phase == Phase.RUNNING || hud.phase == Phase.COUNTDOWN) audio.setTrack(hud.music)
        if (hud.phase == Phase.RUNNING) {
            for (q in quests.liveDone(stats(hud))) {
                if (!announced.add(q.id)) continue
                overlay.questDone(getString(R.string.quest_done_toast, getString(GearNames.quest(q.id))) + "  " + getString(R.string.run_reward_plus, q.reward))
            }
        }
    }

    private fun stats(h: RunHud) = RunStats(h.meters, h.suns, h.maxCombo, h.stomps, h.grinds, h.unders)

    override fun onEvents(events: List<Ev>, hud: RunHud) {
        radio.push(events, hud)
        if (Ev.MILESTONE in events) overlay.milestone((hud.meters / RunSim.MILESTONE_M) * RunSim.MILESTONE_M)
        if (Ev.CLOCK in events) {
            // CLOCK IN unlocked mid-run: save it now, celebrate, keep running
            save.unlockClock(hud.meters, hud.score)
            refreshClock()
            overlay.celebrateClock()
        }
        // web buzz(): jump 18 ms, hurt 40 ms, clock 55 ms; native adds the boss beats. performHapticFeedback
        // without FLAG_IGNORE_GLOBAL_SETTING follows the phone's touch-vibration setting.
        when {
            Ev.CLOCK in events || Ev.HURT in events || Ev.DOWNED in events || Ev.BOSS in events ->
                game.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Ev.BEAM in events || Ev.SHATTER in events || Ev.OVERHEAT in events -> game.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Ev.JUMP in events -> game.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onResult(result: RunResult) {
        if (ended) return
        ended = true
        if (hintSeen) save.runTutorialDone = true
        val h = result.hud
        val best = save.bestDistance
        save.recordRun(h.meters, h.score)
        garage.addSuns(h.suns)
        val rewards = quests.commit(stats(h))
        setResult(RESULT_OK)
        radio.live = false
        audio.stopMusic()
        overlay.bind(h)
        overlay.showRewards(h.suns, rewards.map { GearNames.reward(this, it) to it.suns }, h.meters > best && best > 0)
        refreshClock()
    }

    private fun refreshClock() {
        overlay.setClock(
            RunOverlay.ClockUi(
                open = save.clockedToday() || save.signedToday() && hud?.clockOpen == true,
                signed = save.signedToday(),
                busy = signing,
                wallet = wallet.connected,
                dayLine = ClockIn.dayLine(this, save),
                proofLine = ClockIn.proofLine(this, save),
                error = signError,
            ),
        )
    }

    // ---- RunOverlay.Actions ----

    override fun pauseToggle() = setPaused(!paused)

    private fun setPaused(p: Boolean) {
        if (ended && p) return
        if (!p && signing) return // the wallet is open; resume after it answers
        paused = p
        game.paused = p
        game.releaseAll()
        overlay.setPaused(p)
        radio.paused = p
        if (p) audio.pauseMusic() else audio.startMusic()
    }

    override fun resume() = setPaused(false)

    override fun yard() {
        audio.stopMusic()
        finish()
    }

    override fun again() {
        radio.stopListen()
        startRun()
    }

    override fun sign() {
        // web onClock -> doSign: the same CLOCK IN flow the Yard uses, right here.
        if (!RunOverlay.runSigning) return // 1.2.10: no signing in the run
        if (signing || !ClockIn.ready(save)) return
        if (!wallet.connected) { yardSign(); return }
        signing = true
        signError = ""
        refreshClock()
        scope.launch {
            val result = try { ClockIn.sign(wallet, sender, save) } finally { signing = false }
            result.onSuccess {
                quests.milestones().takeIf { it.isNotEmpty() }?.let { ms ->
                    overlay.questDone(ms.joinToString("  ") { GearNames.reward(this@RunActivity, it) + " +" + it.suns })
                }
                game.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }.onFailure { signError = WalletError.text(this@RunActivity, it) }
            refreshClock()
        }
    }

    override fun signBadge() {
        // the badge pauses the run only now, for the wallet; Resume continues the same run
        setPaused(true)
        sign()
    }

    override fun share() = ClockIn.share(this, save)

    override fun yardSign() {
        // No wallet connected here: the Yard connects and signs (its CLOCK IN flow).
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SIGN, true))
        audio.stopMusic()
        finish()
    }

    override fun musicToggle() {
        val next = !audio.musicMuted
        audio.musicMuted = next
        overlay.setMuted(next)
        if (!next && !paused && !ended) audio.startMusic()
    }

    override fun mic() {
        if (radio.listening) {
            radio.stopListen()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) radio.startListen()
        else {
            // 0.21.7: the system permission dialog never pops over a live run: pause first, then ask.
            setPaused(true)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun slideDown() = game.slideDown()
    override fun slideUp() = game.slideUp()

    // ---- keyboard (web: Space/Up/W/K jump, Down/S/J slide, Esc/P pause) ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_P -> { if (event.repeatCount == 0 && !ended) pauseToggle(); return true }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_NUMPAD_0 -> {
                if (event.repeatCount == 0) game.jumpDown()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_J -> {
                if (event.repeatCount == 0) game.slideDown()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_NUMPAD_0 -> { game.jumpUp(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_J -> { game.slideUp(); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        // web: the tab going hidden pauses the run
        if (!ended && hud != null) setPaused(true)
        audio.pauseMusic()
    }

    override fun onResume() {
        super.onResume()
        // 0.22.0: the day may have been signed elsewhere (Yard, wallet app, another screen) while away: re-read it
        refreshClock()
        if (!paused && !ended) audio.startMusic()
    }

    override fun onDestroy() {
        radio.shutdown()
        audio.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SIGN = "solarchik.run.sign"
        const val AUDIO_WAIT_MS = 4000L
    }
}
