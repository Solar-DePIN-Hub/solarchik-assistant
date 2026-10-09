package net.solardepin.solarchik

import net.solardepin.solarchik.core.SolarchikConfig

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.agents.MintError
import net.solardepin.solarchik.agents.Minter
import net.solardepin.solarchik.agents.engine.Desk
import net.solardepin.solarchik.agents.engine.DeskWorker
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.game.RunActivity
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.ui.RunScreen
import net.solardepin.solarchik.ui.Screen
import net.solardepin.solarchik.ui.SettingsScreen
import net.solardepin.solarchik.ui.SolScreen
import net.solardepin.solarchik.ui.Ui
import net.solardepin.solarchik.ui.Ui.dp
import net.solardepin.solarchik.ui.YardScreen
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError

/** Single activity: five native tabs over one MWA sender. No WebView anywhere. */
class MainActivity : ComponentActivity() {
    enum class Tab(val label: Int, val icon: Int, val inNav: Boolean = true) {
        // 1.0.0 Solarchik Assistant nav: Today · Calls · [Sol] · Agents · More, Sol's mic raised in the middle.
        // Calls is a shortcut to CallsActivity, not a screen. The game (rooftop YARD, garage RUN, check-in SHIFT)
        // is reached from the small tiles on Today.
        TODAY(R.string.nav_today, R.drawable.ic_nav_yard),
        CALLS(R.string.nav_calls, R.drawable.ic_call),
        SOL(R.string.nav_talk, R.drawable.ic_mic),
        AGENTS(R.string.nav_agents, R.drawable.ic_nav_agents),
        SETTINGS(R.string.nav_settings, R.drawable.ic_nav_settings),
        YARD(R.string.nav_yard, R.drawable.ic_nav_yard, inNav = false),
        RUN(R.string.nav_run, R.drawable.ic_nav_run, inNav = false),
        SHIFT(R.string.nav_yard, R.drawable.ic_nav_yard, inNav = false),
        SEASON(R.string.season_title, R.drawable.ic_flame, inNav = false),
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var wallet: SolanaWallet
        private set
    lateinit var sender: ActivityResultSender
        private set
    lateinit var save: GameSave
        private set
    lateinit var store: AgentStore
        private set
    lateinit var minter: Minter
        private set
    lateinit var desk: Desk
        private set
    private var ticker: kotlinx.coroutines.Job? = null

    private val screens = LinkedHashMap<Tab, Screen>()
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var navPill: View
    private lateinit var navWrap: View
    private lateinit var playBtn: FrameLayout
    private var callsDot: View? = null
    private var onboarding: View? = null
    private val navCells = HashMap<Tab, View>()
    private lateinit var toastView: TextView
    private val navItems = HashMap<Tab, Pair<ImageView, TextView>>()
    private val main = Handler(Looper.getMainLooper())
    var current: Tab = Tab.TODAY
        private set
    var topInset = 0
        private set
    var bottomInset = 0
        private set

    private val runLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        renderAll()
        // "Sign today" on the run's CLOCK IN card: open the Yard and start the wallet flow there.
        if (res.data?.getBooleanExtra(RunActivity.EXTRA_SIGN, false) == true) {
            select(Tab.SHIFT)
            (screen(Tab.SHIFT) as? net.solardepin.solarchik.ui.YardScreen)?.signFromRun()
        }
    }

    private val notePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        renderAll()
        if (it) runCatching { net.solardepin.solarchik.notify.Notes.check(this) }
    }

    private var permCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permCallback?.invoke(ok)
        permCallback = null
    }

    private var roleCallback: ((Boolean) -> Unit)? = null
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        roleCallback?.invoke(res.resultCode == RESULT_OK || net.solardepin.solarchik.screen.Secretary.holdsRole(this))
        roleCallback = null
    }

    /** Android 10+: asks the system to make Solarchik the call screening app (the role dialog). */
    fun requestScreeningRole(cb: (Boolean) -> Unit) {
        val sec = net.solardepin.solarchik.screen.Secretary
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return cb(false)
        if (sec.holdsRole(this)) return cb(true)
        val rm = getSystemService(android.app.role.RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING)) return cb(false)
        roleCallback = cb
        runCatching { roleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)) }
            .onFailure { roleCallback = null; cb(false) }
    }

    /** Runtime permission with a callback (microphone for Sol). */
    fun withPermission(perm: String, cb: (Boolean) -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cb(true)
            return
        }
        permCallback = cb
        permLauncher.launch(perm)
    }

    /** "uk" or "en" from the app locale. */
    val lang: String get() = if (resources.configuration.locales[0].language == "uk") "uk" else "en"

    /**
     * Android 13+: asks for POST_NOTIFICATIONS. If the system will not show the dialog any more
     * (denied twice), opens the app's notification settings instead.
     */
    fun requestNotifications(fromUser: Boolean) {
        val notes = net.solardepin.solarchik.notify.Notes
        if (notes.allowed(this)) return
        val prefs = getSharedPreferences("solarchik-notes", MODE_PRIVATE)
        val asked = prefs.getBoolean("asked", false)
        if (!fromUser && asked) return
        prefs.edit().putBoolean("asked", true).apply()
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (notes.needsRuntimePermission() && (!asked || shouldShowRequestPermissionRationale(perm))) {
            notePermission.launch(perm)
        } else if (fromUser) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
    }

    /** App language (phone language, or the player's pick in Settings). */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(net.solardepin.solarchik.core.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)
        wallet = SolanaWallet(this)
        // 0.21.9: no wallet app (judges' tablets) -> offer the built-in devnet wallet instead of failing
        wallet.offerBuiltIn = { offerBuiltInWallet() }
        save = GameSave(this)
        store = AgentStore(this)
        minter = Minter(wallet, store)
        desk = Desk(this)
        Ui.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildRoot())
        // 0.21.7: decode and scale the run art in the background while the player is in the app,
        // so the run opens straight into the countdown instead of a black surface.
        runCatching { val g = net.solardepin.solarchik.game.RunGarage(this); net.solardepin.solarchik.game.run.RunPreload.start(this, g.robot, g.skin) }
        select(startTab(savedInstanceState?.getString("tab"), intent?.getStringExtra(EXTRA_TAB)))
        // 1.0.0: Back walks home to Today (the roof closes its list / tour first) before leaving the app.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val roof = screens[Tab.YARD] as? net.solardepin.solarchik.ui.roof.RooftopScreen
                when {
                    onboarding != null -> (onboarding as? net.solardepin.solarchik.ui.Onboarding)?.back()
                    current == Tab.YARD && roof?.onBack() == true -> Unit
                    current != Tab.TODAY -> select(Tab.TODAY, animate = true)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        if (savedInstanceState == null) maybeOnboard()
        if (BuildConfig.DEBUG) debugRoof(intent)
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_AUTOPILOT)?.let { id -> window.decorView.post { openAutopilot(id) } }
        if (savedInstanceState == null) handleAssistantExtras(intent)
        runCatching { net.solardepin.solarchik.autopilot.AutoRunner.sync(this) }
        runCatching { net.solardepin.solarchik.sol.Briefing.schedule(this) }
    }

    /** 1.1.0: briefing and call-action notifications land on Today. */
    private fun handleAssistantExtras(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(EXTRA_BRIEFING, false)) {
            i.removeExtra(EXTRA_BRIEFING)
            select(Tab.TODAY)
            window.decorView.post { (screen(Tab.TODAY) as? net.solardepin.solarchik.ui.TodayScreen)?.playBriefing() }
        }
        i.getStringExtra(EXTRA_CALL_ACTION)?.let { id ->
            i.removeExtra(EXTRA_CALL_ACTION)
            select(Tab.TODAY)
            window.decorView.post { (screen(Tab.TODAY) as? net.solardepin.solarchik.ui.TodayScreen)?.focusAction(id) }
        }
    }

    /**
     * 1.1.0: the user tapped an autopilot notification. Opens the matching review; nothing is signed here. A swap
     * goes to Agents › Swaps with the quote and fees, and only the wallet app can approve it.
     */
    fun openAutopilot(id: String) {
        val ap = net.solardepin.solarchik.autopilot.AutopilotStore(this)
        val agentsTab = { sec: Int -> select(Tab.AGENTS, animate = true); (screen(Tab.AGENTS) as? net.solardepin.solarchik.ui.AgentsScreen)?.also { it.openSection(sec) } }
        if (id == "delegate") { agentsTab(net.solardepin.solarchik.ui.AgentsScreen.SEASON); return }
        if (id == "watcher") { agentsTab(net.solardepin.solarchik.ui.AgentsScreen.WATCHER); return }
        if (id.startsWith("saver:")) {
            // 1.1.0 Saver: the proposed save opens as a normal swap review; only the wallet app can approve it.
            val agents = agentsTab(net.solardepin.solarchik.ui.AgentsScreen.SAVER) ?: return
            val s = net.solardepin.solarchik.agents.SaverStore(this)
            val a = s.find(id.removePrefix("saver:")) ?: return
            if (a.status == net.solardepin.solarchik.agents.SaveAction.DONE || a.status == net.solardepin.solarchik.agents.SaveAction.SKIPPED || !s.policy().enabled) return
            if (wallet.connected) agents.swapPanel.quote(net.solardepin.solarchik.agents.SaverRules.request(a))
            return
        }
        val a = ap.find(id) ?: return
        if (!ap.policy().active || a.status == net.solardepin.solarchik.autopilot.AutoAction.DONE) { select(Tab.SEASON, animate = true); return }
        when (a.kind) {
            net.solardepin.solarchik.autopilot.AutoKind.SWAP -> {
                val to = net.solardepin.solarchik.swap.SwapTokens.bySymbol(a.to) ?: return
                select(Tab.AGENTS, animate = true)
                val agents = screen(Tab.AGENTS) as? net.solardepin.solarchik.ui.AgentsScreen ?: return
                agents.openSection(net.solardepin.solarchik.ui.AgentsScreen.SAVER)
                if (wallet.connected) agents.swapPanel.quote(net.solardepin.solarchik.swap.SwapRequest(net.solardepin.solarchik.swap.SwapTokens.SOL, to, a.lamports, by = "autopilot", reason = a.id))
            }
            net.solardepin.solarchik.autopilot.AutoKind.CHECKIN -> {
                select(Tab.SHIFT, animate = true)
                val shift = screen(Tab.SHIFT) as? net.solardepin.solarchik.ui.YardScreen ?: return
                if (save.checkInOpen() && !save.signedToday()) shift.signFromRun() else shift.focusToday()
            }
            net.solardepin.solarchik.autopilot.AutoKind.DAPP -> {
                val d = net.solardepin.solarchik.season.SeasonDapps.all.firstOrNull { it.name == a.dapp } ?: net.solardepin.solarchik.season.SeasonDapps.all.first()
                select(Tab.SEASON, animate = true)
                (screen(Tab.SEASON) as? net.solardepin.solarchik.ui.SeasonScreen)?.openDapp(d)
                ap.setStatus(a.id, net.solardepin.solarchik.autopilot.AutoAction.DONE)
            }
            net.solardepin.solarchik.autopilot.AutoKind.STAKING -> {
                openUrl(net.solardepin.solarchik.season.Skr.STAKE_URL)
                if (net.solardepin.solarchik.season.SeasonStore.explored(this) == null) net.solardepin.solarchik.season.SeasonStore.markExploredName(this, "SKR staking")
                ap.setStatus(a.id, net.solardepin.solarchik.autopilot.AutoAction.DONE)
                select(Tab.SEASON, animate = true)
            }
        }
    }

    /** Debug builds only (screenshots): fixed sky / tour step from adb extras. */
    private fun debugRoof(i: Intent?) {
        val roof = net.solardepin.solarchik.ui.roof.RooftopScreen
        i?.getStringExtra("roof_scene")?.let { s -> roof.forcedMood = net.solardepin.solarchik.ui.roof.RoofMood.entries.firstOrNull { it.name.equals(s, true) } }
        if (i?.hasExtra("roof_autoplay") == true) roof.autoPlay = i.getBooleanExtra("roof_autoplay", true)
        if (i?.hasExtra("roof_mute") == true) roof.forceMute = i.getBooleanExtra("roof_mute", false)
        val tour = i?.getStringExtra("roof_tour")
        val step = i?.getIntExtra("roof_step", 0) ?: 0
        val menu = i?.getBooleanExtra("roof_menu", false) == true
        (screens[Tab.YARD] as? net.solardepin.solarchik.ui.roof.RooftopScreen)?.let { r ->
            r.view.post {
                when (tour) {
                    "full" -> r.startTour(net.solardepin.solarchik.ui.roof.RoofTour.Variant.FULL, step)
                    "judges" -> r.startTour(net.solardepin.solarchik.ui.roof.RoofTour.Variant.JUDGES, step)
                }
                if (menu) r.openSheet()
                r.render()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }?.let { select(it) }
        if (intent.getStringExtra(EXTRA_FOCUS) == "secretary") (screen(Tab.SETTINGS) as? net.solardepin.solarchik.ui.SettingsScreen)?.focusSecretary()
        intent.getStringExtra(EXTRA_AUTOPILOT)?.let { openAutopilot(it) }
        handleAssistantExtras(intent)
        if (BuildConfig.DEBUG) debugRoof(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", current.name)
    }

    override fun onResume() {
        super.onResume()
        // Seeker Season plan: remember (on this phone only) that the app was opened today
        net.solardepin.solarchik.season.SeasonStore.markOpened(this)
        screens[current]?.onShow()
        startTicker()
        pollCalls()
    }

    private var callsPolling = false

    /** 0.21.9: new secretary notes -> notification + Home badge (on resume and every 60 s while open). */
    fun pollCalls() {
        if (!tickerEnabled || callsPolling) return
        callsPolling = true
        scope.launch {
            runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { net.solardepin.solarchik.screen.CallNotes.check(this@MainActivity) } }
            callsPolling = false
            (screens[Tab.SHIFT] as? YardScreen)?.renderCalls()
            (screens[Tab.TODAY] as? net.solardepin.solarchik.ui.TodayScreen)?.render()
            renderCallsDot()
            (screens[Tab.YARD] as? net.solardepin.solarchik.ui.roof.RooftopScreen)?.renderCalls()
            if (current == Tab.SETTINGS) screens[current]?.render()
        }
    }

    fun openCalls() = net.solardepin.solarchik.ui.CallsActivity.open(this)

    private fun renderCallsDot() {
        val dot = callsDot ?: return
        dot.visibility = if (runCatching { net.solardepin.solarchik.screen.CallInbox.unreadCount(this) }.getOrDefault(0) > 0) View.VISIBLE else View.GONE
    }

    /** 1.0.0: three short pages on the very first launch, framed as the assistant. Skip any time. */
    private fun maybeOnboard() {
        if (!onboardingEnabled) return
        val prefs = getSharedPreferences(ASSISTANT_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("onboarded", false)) return
        showOnboarding()
    }

    fun showOnboarding() {
        if (onboarding != null) return
        val root = content.parent as? FrameLayout ?: return
        val v = net.solardepin.solarchik.ui.Onboarding(this) {
            getSharedPreferences(ASSISTANT_PREFS, MODE_PRIVATE).edit().putBoolean("onboarded", true).apply()
            val o = onboarding
            onboarding = null
            o?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction { root.removeView(o) }?.start()
            select(Tab.TODAY)
        }
        onboarding = v
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.applyInsets(topInset, bottomInset)
    }

    val onboardingShown: Boolean get() = onboarding != null

    /**
     * 0.21.9: asked by [SolanaWallet] when a mint / buy / CLOCK IN needs a wallet and no MWA wallet app is
     * installed. Accept = create the Keystore-sealed devnet key and fund it, then the action continues.
     */
    private suspend fun offerBuiltInWallet(): Boolean {
        if (isFinishing || isDestroyed) return false
        val yes = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            var done = false
            fun finish(v: Boolean) { if (!done) { done = true; if (cont.isActive) cont.resumeWith(Result.success(v)) } }
            val d = android.app.AlertDialog.Builder(this)
                .setTitle(R.string.lw_offer_title)
                .setMessage(R.string.lw_offer_body)
                .setPositiveButton(R.string.lw_offer_use) { _, _ -> finish(true) }
                .setNeutralButton(R.string.lw_offer_install) { _, _ ->
                    val pkg = "app.phantom"
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
                        .onFailure { openUrl("https://play.google.com/store/apps/details?id=$pkg") }
                    finish(false)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(false) }
                .setOnCancelListener { finish(false) }
                .show()
            cont.invokeOnCancellation { runCatching { d.dismiss() } }
        }
        if (!yes) return false
        setupBuiltInWallet()
        return true
    }

    @Volatile private var funding = false

    /** Creates (or reuses) the built-in devnet wallet and fills it from the devnet faucet. */
    suspend fun setupBuiltInWallet(): Result<String> {
        // 1.1.0: mainnet has no built-in hot wallet. Without a wallet app the user is pointed to one.
        if (wallet.mainnet) {
            showInstallWallet()
            return Result.failure(net.solardepin.solarchik.wallet.WalletError(net.solardepin.solarchik.wallet.WalletError.Kind.NO_WALLET))
        }
        wallet.useBuiltIn()
        renderAll()
        if (funding) return Result.success("")
        funding = true
        toast(getString(R.string.lw_funding))
        val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
            net.solardepin.solarchik.wallet.LocalFunding.fund(wallet, SolanaWallet.LOCAL_MIN_LAMPORTS)
        }
        funding = false
        val bal = wallet.balanceSol().getOrNull()
        r.onSuccess { toast(getString(R.string.lw_ready, net.solardepin.solarchik.ui.Fmt.sol(bal ?: 0.0))) }
            .onFailure { toast(getString(R.string.lw_ready_unfunded, it.message ?: "?")) }
        renderAll()
        return r
    }

    override fun onPause() {
        screens[current]?.onHide()
        ticker?.cancel()
        ticker = null
        super.onPause()
    }

    /** While the app is open the desk ticks every 30 s; the worker covers the background. */
    private fun startTicker() {
        ticker?.cancel()
        if (!tickerEnabled) return
        ticker = scope.launch {
            var n = 0
            while (true) {
                if (n++ % 2 == 1) pollCalls()
                if (desk.state().anyRunning) {
                    val report = runCatching { desk.tick() }.getOrNull()
                    if (report != null) {
                        if (current == Tab.AGENTS || current == Tab.YARD || current == Tab.SHIFT || current == Tab.TODAY) screens[current]?.render()
                    }
                } else if (current == Tab.AGENTS) {
                    screens[current]?.render()
                }
                delay(TICK_MS)
            }
        }
    }

    /** Start or stop the background worker to match the desk. */
    fun deskChanged() {
        if (!tickerEnabled) return
        DeskWorker.sync(this, desk.state().anyRunning)
        if (desk.state().anyRunning) requestNotifications(fromUser = false)
        scope.launch {
            // Foreground ticks show closes on screen; only DeskWorker ticks raise notifications.
            runCatching { desk.tick() }
            screens[current]?.render()
        }
    }

    override fun onDestroy() {
        screens.values.forEach { runCatching { it.onDestroy() } }
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Floating glass bar (0.21.7): rounded, translucent, a gold pill that slides to the picked tab,
        // Play raised in the middle as the sun button.
        val bar = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Ui.withAlpha(Ui.blend(Ui.blend(Ui.BG, Ui.SURFACE2, 0.9f), Ui.GOLD, 0.05f), 0xFA), Ui.withAlpha(Ui.blend(Ui.BG, Ui.SURFACE, 0.9f), 0xFA)),
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Ui.withAlpha(Ui.GOLD, 0x3A))
            }
            elevation = dp(18).toFloat()
            clipChildren = false
            clipToPadding = false
            tag = "nav-bar"
        }
        navPill = View(this).apply {
            background = Ui.rounded(Ui.withAlpha(Ui.GOLD, 0x24), dp(22).toFloat(), Ui.withAlpha(Ui.GOLD, 0x55), dp(1))
            tag = "nav-pill"
        }
        bar.addView(navPill, FrameLayout.LayoutParams(0, dp(54), Gravity.CENTER_VERTICAL))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), 0, dp(6), 0)
        }
        for (tab in Tab.entries.filter { it.inNav }) nav.addView(if (tab == CENTER) playItem() else navItem(tab), LinearLayout.LayoutParams(0, dp(66), 1f))
        bar.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        val navWrap = FrameLayout(this).apply {
            tag = "nav-wrap"
            clipChildren = false
            clipToPadding = false
            addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)).apply {
                leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(10); topMargin = dp(24)
            })
        }
        this.navWrap = navWrap
        root.clipChildren = false
        root.addView(navWrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        nav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> movePill(current, animate = false) }

        toastView = Ui.text(this, "", 14f, Ui.TEXT, 700).apply {
            background = Ui.rounded(Ui.SURFACE2, dp(16).toFloat(), Ui.withAlpha(Ui.GOLD, 0x66), dp(1))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(16).toFloat()
            visibility = View.GONE
        }
        root.addView(
            toastView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(104)
            },
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
            navWrap.setPadding(0, 0, 0, bars.bottom)
            (toastView.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(104) + bars.bottom
            screens.values.forEach { it.applyInsets() }
            (onboarding as? net.solardepin.solarchik.ui.Onboarding)?.applyInsets(bars.top, bars.bottom)
            insets
        }
        return root
    }

    private fun tick(v: View) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun navItem(tab: Tab): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(22).toFloat(), 0x22F5C542)
            setOnClickListener {
                tick(it)
                if (tab == Tab.CALLS) openCalls() else select(tab, animate = true)
            }
            contentDescription = getString(tab.label)
            tag = "nav-" + tab.name.lowercase()
        }
        val icon = ImageView(this).apply { setImageResource(tab.icon); setColorFilter(Ui.MUTED) }
        val label = Ui.text(this, getString(tab.label), 11f, Ui.MUTED, 800).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        if (tab == Tab.CALLS) {
            // unread secretary notes: a small gold dot on the Calls icon
            val box = FrameLayout(this)
            box.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26)))
            val dot = View(this).apply {
                background = Ui.rounded(Ui.GOLD, dp(5).toFloat(), Ui.BG, dp(2))
                tag = "nav-calls-dot"
                visibility = View.GONE
            }
            box.addView(dot, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.END))
            callsDot = dot
            col.addView(box, LinearLayout.LayoutParams(dp(28), dp(26)))
        } else col.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
        col.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        navItems[tab] = icon to label
        navCells[tab] = col
        return col
    }

    /** 1.0.0: Sol's mic is the raised gold button in the middle of the bar. */
    private fun playItem(): View {
        val tab = CENTER
        val cell = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            contentDescription = getString(tab.label)
            tag = "nav-" + tab.name.lowercase()
        }
        playBtn = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#FFE07A"), Ui.GOLD, Color.parseColor("#F29A2E")),
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(3), Ui.BG)
            }
            elevation = dp(14).toFloat()
            isClickable = true
            foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), dp(32).toFloat(), 0x33FFFFFF)
            setOnClickListener {
                tick(it)
                select(tab, animate = true)
            }
        }
        val icon = ImageView(this).apply { setImageResource(tab.icon); setColorFilter(Ui.INK) }
        playBtn.addView(icon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        cell.addView(playBtn, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = -dp(22) })
        val label = Ui.text(this, getString(tab.label), 11f, Ui.GOLD, 900).apply { gravity = Gravity.CENTER; maxLines = 1 }
        cell.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(8) })
        navItems[tab] = icon to label
        navCells[tab] = cell
        return cell
    }

    /** The gold pill slides under the picked tab; on Play it hides and the sun button glows instead. */
    private fun movePill(tab: Tab, animate: Boolean) {
        if (!this::navPill.isInitialized) return
        val cell = navCells[if (tab.inNav) tab else Tab.TODAY] ?: return
        if (cell.width == 0) return
        val w = cell.width - dp(10)
        if (navPill.layoutParams.width != w) {
            navPill.layoutParams = (navPill.layoutParams as FrameLayout.LayoutParams).apply { width = w }
        }
        val x = (nav.left + cell.left + dp(5)).toFloat()
        val show = tab != CENTER
        if (animate) {
            navPill.animate().translationX(x).alpha(if (show) 1f else 0f).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
        } else {
            navPill.translationX = x
            navPill.alpha = if (show) 1f else 0f
        }
    }

    private fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.82f; v.scaleY = 0.82f
        v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    fun select(tab: Tab, animate: Boolean = false) {
        if (tab == Tab.CALLS) { openCalls(); return }
        val changed = current != tab
        val from = current
        if (screens.containsKey(current) && changed) screens[current]?.onHide()
        current = tab
        val screen = screens.getOrPut(tab) { create(tab) }
        content.removeAllViews()
        content.addView(screen.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.applyInsets()
        // 0.22.0: the rooftop is full-bleed; every other screen keeps the floating nav.
        if (this::navWrap.isInitialized) navWrap.visibility = if (tab == Tab.YARD) View.GONE else View.VISIBLE
        if (animate && changed) {
            // Slide from the side of the tab we came from, with a soft fade.
            val dir = if (tab == Tab.TODAY) -1 else if (from == Tab.TODAY) 1 else if (tab.ordinal > from.ordinal) 1 else -1
            screen.view.alpha = 0f
            screen.view.translationX = dp(28).toFloat() * dir
            screen.view.animate().alpha(1f).translationX(0f).setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        } else {
            screen.view.alpha = 1f
            screen.view.translationX = 0f
            screen.view.translationY = 0f
        }
        for ((t, pair) in navItems) {
            val on = t == tab || (!tab.inNav && t == Tab.TODAY)
            if (t == CENTER) {
                pair.second.setTextColor(if (on) Ui.GOLD else Ui.withAlpha(Ui.GOLD, 0xB0))
                continue
            }
            pair.first.setColorFilter(if (on) Ui.GOLD else Ui.MUTED)
            pair.second.setTextColor(if (on) Ui.GOLD else Ui.MUTED)
        }
        if (this::playBtn.isInitialized) {
            playBtn.animate().scaleX(if (tab == CENTER) 1.08f else 1f).scaleY(if (tab == CENTER) 1.08f else 1f).setDuration(200).start()
        }
        movePill(tab, animate && changed)
        if (animate && changed) navItems[tab]?.first?.let { bounce(if (tab == CENTER && this::playBtn.isInitialized) playBtn else it) }
        screen.onShow()
        renderCallsDot()
    }

    private fun create(tab: Tab): Screen = when (tab) {
        Tab.TODAY, Tab.CALLS -> net.solardepin.solarchik.ui.TodayScreen(this)
        Tab.YARD -> net.solardepin.solarchik.ui.roof.RooftopScreen(this)
        Tab.SHIFT -> YardScreen(this)
        Tab.RUN -> RunScreen(this)
        Tab.AGENTS -> AgentsScreen(this)
        Tab.SOL -> SolScreen(this)
        Tab.SETTINGS -> SettingsScreen(this)
        Tab.SEASON -> net.solardepin.solarchik.ui.SeasonScreen(this)
    }

    fun screen(tab: Tab): Screen? = screens[tab]

    fun renderAll() {
        screens.values.forEach { it.render() }
    }

    fun startRun() {
        runLauncher.launch(Intent(this, RunActivity::class.java))
    }

    fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun explorerTx(sig: String, cluster: String): String = net.solardepin.solarchik.game.ClockIn.explorerTx(sig, cluster)

    /** 1.1.0: mainnet accounts open on Solscan (Orb is the second link in Settings); devnet keeps Solana Explorer. */
    fun explorerAddress(addr: String, cluster: String): String =
        if (cluster == "devnet") "https://explorer.solana.com/address/$addr?cluster=devnet" else SolarchikConfig.solscanAccount(addr, cluster)

    /** 1.1.0: last balances read on Today (for Sol's context); null until read. */
    @Volatile var walletSol: Double? = null
    @Volatile var walletSkr: Double? = null

    /** 1.1.0: on mainnet, while NFT mints are "coming soon", paper agents run without owning an NFT. */
    val paperOpen: Boolean get() = wallet.mainnet && !SolarchikConfig.MAINNET_MINT_READY

    /** 1.1.0 mainnet: no wallet app installed. Seed Vault ships on Seeker; elsewhere Phantom or Solflare. */
    fun showInstallWallet() {
        if (isFinishing || isDestroyed) return
        fun store(pkg: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
                .onFailure { openUrl("https://play.google.com/store/apps/details?id=$pkg") }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.mn_install_title)
            .setMessage(R.string.mn_install_body)
            .setPositiveButton("Phantom") { _, _ -> store("app.phantom") }
            .setNeutralButton("Solflare") { _, _ -> store("com.solflare.mobile") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun toast(message: CharSequence) {
        toastView.text = message
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.translationY = dp(12).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setDuration(180).start()
        main.removeCallbacksAndMessages(TOAST)
        main.postAtTime({
            toastView.animate().alpha(0f).setDuration(220).withEndAction { toastView.visibility = View.GONE }.start()
        }, TOAST, android.os.SystemClock.uptimeMillis() + 3600)
    }

    fun errorText(t: Throwable?): String = when (t) {
        is MintError -> when (t.kind) {
            MintError.Kind.FREE_USED -> getString(R.string.mint_err_free)
            MintError.Kind.PRO_MAINNET_OFF -> getString(R.string.mint_err_pro_off)
            MintError.Kind.TOO_BIG -> getString(R.string.mint_err_big)
            MintError.Kind.WALLET_CHANGED -> getString(R.string.mint_err_wallet_changed)
            MintError.Kind.PAID_ONLY -> getString(R.string.mint_err_paid_only)
            MintError.Kind.MAINNET_SOON -> getString(R.string.mn_mint_soon)
        }
        else -> WalletError.text(this, t)
    }

    companion object {
        /**
         * Tab to open. A recreated activity (rotation on some OEMs, locale change, process death)
         * returns to the tab the player was on; a notification's tab applies to a fresh start.
         */
        /** The tab restored after rotation / process death wins over the launch intent; unknown names fall through. */
        fun startTab(saved: String?, fromIntent: String?): Tab =
            listOfNotNull(saved, fromIntent).firstNotNullOfOrNull { name -> Tab.entries.firstOrNull { it.name == name && it != Tab.CALLS } } ?: Tab.TODAY

        /** The raised button in the middle of the bar. */
        val CENTER = Tab.SOL
        const val ASSISTANT_PREFS = "solarchik.assistant"

        /** First-launch onboarding; unit tests switch it off with -Dsolarchik.onboarding=0 unless they test it. */
        @JvmStatic var onboardingEnabled = System.getProperty("solarchik.onboarding") != "0"

        private val TOAST = Any()
        private const val TICK_MS = 30_000L
        const val EXTRA_TAB = "net.solardepin.solarchik.TAB"
        /** 0.22.0: "secretary" scrolls Settings to the secretary section (from the Calls list). */
        const val EXTRA_FOCUS = "net.solardepin.solarchik.FOCUS"
        /** 1.1.0: a Season autopilot notification ("<action id>" or "delegate"). */
        const val EXTRA_AUTOPILOT = "net.solardepin.solarchik.AUTOPILOT"
        /** 1.1.0: the morning briefing notification (Today plays it). */
        const val EXTRA_BRIEFING = "net.solardepin.solarchik.BRIEFING"
        /** 1.1.0: a call-action reminder (Today shows its card). */
        const val EXTRA_CALL_ACTION = "net.solardepin.solarchik.CALL_ACTION"
        /** Screenshot tests switch the live desk loop off so renders are deterministic. */
        @JvmStatic var tickerEnabled = true
    }
}
