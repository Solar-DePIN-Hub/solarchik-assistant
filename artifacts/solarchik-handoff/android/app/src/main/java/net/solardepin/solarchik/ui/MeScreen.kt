package net.solardepin.solarchik.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.stack.Habits
import net.solardepin.solarchik.stack.MorningStack

/**
 * 1.2.7 Me (redesign 05): the wallet hero (mainnet, SOL / USDC / SKR read live, Send, Explorer), your daily habits,
 * the phone secretary, the morning stack time, and one level down everything else: Settings (language, network,
 * forwarding, wallet diagnostics), calls history, Sol chat, the Season plan, agents and autopilot.
 */
class MeScreen(host: MainActivity) : Screen(host) {
    private lateinit var walletBox: LinearLayout
    private lateinit var habitsRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private var sol: Double? = null
    private var usdc: Double? = null
    private var skr: Double? = null
    private var addr = ""
    private var readAt = 0L
    private var failed = false

    override fun build(): View = page {
        tag = "me"
        val head = Ui.row(ctx, gap = 14).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Kit.S2) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(Ui.image(ctx, R.drawable.buddy_happy, android.widget.ImageView.ScaleType.CENTER_CROP), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(dp(60), dp(60)))
        head.addView(Ui.weight(Ui.display(ctx, ctx.getString(R.string.me_title), 32f).apply { maxLines = 1 }))
        head.addView(FrameLayout(ctx).apply {
            tag = "me-bell"
            contentDescription = ctx.getString(R.string.me_notes)
            background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Kit.S1); setStroke(dp(1), Kit.HAIR) }, dp(26).toFloat())
            isClickable = true
            setOnClickListener { host.requestNotifications(fromUser = true) }
            addView(Kit.icon(ctx, R.drawable.lc_bell, Ui.TEXT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(head)
        walletBox = Ui.column(ctx).apply {
            tag = "me-wallet"
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Kit.S3, Kit.S1)).apply { cornerRadius = dp(30).toFloat(); setStroke(dp(1), Ui.withAlpha(Color.WHITE, 0x1C)) }
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        addView(Ui.top(walletBox, 6))
        val hh = Ui.row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        hh.addView(Ui.weight(Kit.section(ctx, ctx.getString(R.string.me_habits))))
        hh.addView(Ui.text(ctx, ctx.getString(R.string.me_edit), 16f, Ui.GOLD, 800).apply {
            tag = "me-habits-edit"; minHeight = dp(44); gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(2), 0)
            isClickable = true; setOnClickListener { HabitsSheet.show(host) { host.renderAll() } }
        })
        addView(Ui.top(hh, 8))
        habitsRow = Ui.row(ctx, gap = 10)
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(habitsRow); tag = "me-habits" })
        listBox = Ui.column(ctx, gap = 14)
        addView(Ui.top(listBox, 10))
    }

    // ------------------------------------------------------------------ wallet

    private fun renderWallet() {
        walletBox.removeAllViews()
        val w = host.wallet
        val top = Ui.row(ctx, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
        val netName = if (w.mainnet) ctx.getString(R.string.network_mainnet) else ctx.getString(R.string.today_devnet)
        top.addView(Kit.chip(ctx, "●  $netName", if (w.mainnet) Ui.GREEN else Ui.CYAN, size = 15f).apply { tag = "today-cluster"; setPadding(dp(14), dp(8), dp(14), dp(8)) })
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (w.connected) top.addView(Ui.text(ctx, walletName() + " · " + Fmt.short(w.address), 15f, Kit.MUTED, 600).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; tag = "me-wallet-addr" })
        walletBox.addView(top)
        if (!w.connected) {
            walletBox.addView(Ui.top(Ui.text(ctx, ctx.getString(if (w.mainnet) R.string.me_wallet_none else R.string.today_wallet_none), 16f, Ui.TEXT, 600).apply { setLineSpacing(0f, 1.25f) }, 16))
            walletBox.addView(Ui.top(Kit.primary(ctx, ctx.getString(R.string.me_connect), R.drawable.lc_wallet) {
                (host.screen(MainActivity.Tab.TODAY) as? TodayScreen)?.setupWallet() ?: run { host.select(MainActivity.Tab.TODAY); (host.screen(MainActivity.Tab.TODAY) as? TodayScreen)?.setupWallet() }
            }.apply { tag = "today-wallet-setup" }, 16))
            return
        }
        if (addr != w.address) { sol = null; usdc = null; skr = null; failed = false }
        val s = sol ?: host.walletSol.takeIf { host.walletSolAddr == w.address }
        val tiles = Ui.row(ctx, gap = 10)
        fun tile(sym: String, letter: String, c1: Int, c2: Int, v: Double?, digits: Int, tagName: String) {
            val t = Ui.column(ctx).apply {
                background = Ui.rounded(Ui.withAlpha(Ui.BG, 0x55), dp(22).toFloat(), Kit.HAIR, dp(1))
                setPadding(dp(14), dp(14), dp(10), dp(14))
            }
            val r = Ui.row(ctx, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(Ui.text(ctx, letter, 14f, Color.WHITE, 900).apply { gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { shape = GradientDrawable.OVAL } }, LinearLayout.LayoutParams(dp(30), dp(30)))
            r.addView(Ui.text(ctx, sym, 15f, Ui.TEXT, 800).apply { maxLines = 1 })
            t.addView(r)
            t.addView(Ui.top(Ui.display(ctx, when { v != null -> num(v, digits); failed -> "—"; else -> "…" }, 20f).apply {
                tag = tagName; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(13, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, 10).apply { layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { height = dp(28) } })
            tiles.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        tile("SOL", "S", Color.parseColor("#9945FF"), Color.parseColor("#14F195"), s, 4, "today-wallet-balance")
        if (w.mainnet) {
            tile("USDC", "$", Color.parseColor("#2775CA"), Color.parseColor("#3D8FE0"), usdc, 2, "me-usdc")
            tile("SKR", "K", Color.parseColor("#F5A742"), Color.parseColor("#F5C542"), skr ?: host.walletSkr.takeIf { host.walletSolAddr == w.address }, 2, "today-wallet-skr")
        }
        walletBox.addView(Ui.top(tiles, 16))
        val btns = Ui.row(ctx, gap = 10)
        btns.addView(Kit.ghost(ctx, ctx.getString(R.string.me_send), R.drawable.lc_send) { send() }.apply { tag = "me-send" }, LinearLayout.LayoutParams(0, dp(52), 1f))
        btns.addView(Kit.ghost(ctx, ctx.getString(R.string.me_explorer), R.drawable.lc_ext) { host.openUrl(host.explorerAddress(w.address, w.clusterName)) }.apply { tag = "today-wallet-explorer" }, LinearLayout.LayoutParams(0, dp(52), 1f))
        walletBox.addView(Ui.top(btns, 14))
        if (net.solardepin.solarchik.season.SeekerStore(ctx).state().verified) walletBox.addView(Ui.top(Kit.chip(ctx, ctx.getString(R.string.sk_badge) + " Seeker", Ui.GREEN).apply { tag = "today-seeker-badge" }, 10))
    }

    private fun num(v: Double, digits: Int): String {
        val nf = java.text.NumberFormat.getNumberInstance(net.solardepin.solarchik.core.AppLocale.ui())
        nf.maximumFractionDigits = digits
        nf.minimumFractionDigits = if (digits == 2 && v < 1000 && v != Math.floor(v)) 2 else 0
        return nf.format(v)
    }

    private fun walletName(): String = when (host.wallet.walletPackage) {
        "app.phantom" -> "Phantom"; "com.solflare.mobile" -> "Solflare"; "" -> if (host.wallet.isLocal) ctx.getString(R.string.today_wallet_builtin) else ctx.getString(R.string.today_wallet_app)
        else -> if (host.wallet.walletPackage.contains("seedvault", true)) "Seed Vault" else ctx.getString(R.string.today_wallet_app)
    }

    /** Send: to someone in your Circle with a saved address (the same one-approval transfer). */
    private fun send() {
        val people = net.solardepin.solarchik.circle.CircleStore(ctx).all().filter { it.address.isNotBlank() }.sortedBy { it.name.lowercase() }
        if (people.isEmpty()) { host.toast(ctx.getString(R.string.me_send_none)); host.select(MainActivity.Tab.CIRCLE, animate = true); return }
        android.app.AlertDialog.Builder(ctx).setTitle(R.string.me_send_pick)
            .setItems(people.map { it.name + " · " + Fmt.short(it.address) }.toTypedArray()) { _, i -> CirclePanel.sendTo(host, people[i]) { host.renderAll() } }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Live balances (SOL, and on mainnet USDC + SKR), at most every 30 s unless [force]. */
    fun refresh(force: Boolean = false) {
        val w = host.wallet
        if (!w.connected || !MainActivity.tickerEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (!force && addr == w.address && readAt != 0L && now - readAt < 30_000) return
        readAt = now
        val a = w.address
        host.scope.launch {
            var r = withContext(Dispatchers.IO) { w.balanceSol() }
            if (r.isFailure) { kotlinx.coroutines.delay(1500); r = withContext(Dispatchers.IO) { w.balanceSol() } }
            if (a != w.address) return@launch
            addr = a
            r.onSuccess { sol = it; failed = false; host.walletSol = it; host.walletSolAddr = a }.onFailure { failed = sol == null }
            if (w.mainnet) {
                withContext(Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetchMint(a, SaveUsdc.MINT) }.onSuccess { usdc = it.first }
                withContext(Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetch(a) }.onSuccess { skr = it; host.walletSkr = it }
            }
            if (this@MeScreen::walletBox.isInitialized) renderWallet()
        }
    }

    /** Tests: known balances without the network. */
    internal fun setBalanceForTest(s: Double?, skrBalance: Double? = null, usdcBalance: Double? = null) { sol = s; skr = skrBalance; usdc = usdcBalance; addr = host.wallet.address; failed = false }

    // ------------------------------------------------------------------ habits + list

    private fun renderHabits() {
        habitsRow.removeAllViews()
        val on = Habits.enabled(ctx)
        if (on.isEmpty()) { habitsRow.addView(Kit.chip(ctx, ctx.getString(R.string.me_habits_none), Kit.MUTED, fill = false, size = 15f)); return }
        on.forEach { id ->
            val icon = when (id) { Habits.SAVE -> R.drawable.lc_coins; Habits.SEASON -> R.drawable.lc_spark; Habits.WALLET -> R.drawable.lc_wallet; Habits.CALL -> R.drawable.lc_heart; Habits.WORKOUT -> R.drawable.lc_dumbbell; else -> R.drawable.lc_check }
            val color = when (id) { Habits.SAVE -> Ui.GREEN; Habits.SEASON -> Ui.PURPLE; Habits.WALLET -> Ui.GOLD; Habits.CALL -> Color.parseColor("#FF8FB1"); else -> Ui.CYAN }
            habitsRow.addView(Kit.chip(ctx, Habits.title(ctx, id), Ui.TEXT, icon, fill = false, size = 15f).apply {
                tag = "me-habit-$id"
                setPadding(dp(14), dp(11), dp(16), dp(11))
                compoundDrawables[0]?.setTint(color)
                isClickable = true; setOnClickListener { HabitsSheet.show(host) { host.renderAll() } }
            })
        }
        if (Habits.saved(ctx) > 0) habitsRow.addView(Kit.chip(ctx, ctx.getString(R.string.me_saved, net.solardepin.solarchik.circle.Circle.amount(Habits.saved(ctx))), Ui.GREEN, R.drawable.lc_coins, size = 15f).apply { tag = "me-saved"; setPadding(dp(14), dp(11), dp(16), dp(11)) })
    }

    private fun renderList() {
        listBox.removeAllViews()
        val main = Kit.list(ctx)
        val sec = net.solardepin.solarchik.screen.Secretary
        val secOn = sec.supported() && net.solardepin.solarchik.screen.PlayerIds.screeningOn(ctx) && sec.holdsRole(ctx)
        val today = net.solardepin.solarchik.sol.AssistantRules.today(net.solardepin.solarchik.screen.CallInbox.cached(ctx), System.currentTimeMillis()).count { !it.blocked }
        Kit.addRow(main, Kit.row(ctx, R.drawable.lc_headset, Ui.CYAN, ctx.getString(R.string.me_sec),
            if (secOn) ctx.resources.getQuantityString(R.plurals.me_sec_on, today, today) else ctx.getString(R.string.me_sec_off)) {
            if (secOn) host.openCalls() else { host.select(MainActivity.Tab.SETTINGS, animate = true); (host.screen(MainActivity.Tab.SETTINGS) as? SettingsScreen)?.focusSecretary() }
        }.apply { tag = "today-secretary" })
        Kit.addRow(main, Kit.row(ctx, R.drawable.lc_bell, Ui.GOLD, ctx.getString(R.string.me_stack_at), null,
            trailing = Kit.toggle(ctx, MorningStack.notifyOn(ctx)) { on -> MorningStack.setNotify(ctx, on); if (on) host.requestNotifications(fromUser = true) }.apply { tag = "me-stack-notify" }).apply { tag = "me-stack" })
        val t = host.screen(MainActivity.Tab.TODAY) as? TodayScreen
        val pol = net.solardepin.solarchik.sol.BriefingStore(ctx).policy()
        Kit.addRow(main, Kit.row(ctx, R.drawable.lc_vol, Ui.PURPLE, ctx.getString(R.string.me_briefing),
            if (pol.enabled) ctx.getString(R.string.br_at, pol.label) else ctx.getString(R.string.br_off),
            trailing = Kit.chip(ctx, ctx.getString(when { t?.briefingBusy == true -> R.string.br_preparing; t?.briefingPlaying == true -> R.string.br_stop; else -> R.string.br_play }), Ui.GOLD, R.drawable.lc_play).apply {
                tag = "today-briefing-play"; minHeight = dp(40)
                isClickable = true
                setOnClickListener {
                    val ts = (host.screen(MainActivity.Tab.TODAY) ?: run { host.select(MainActivity.Tab.TODAY); host.select(MainActivity.Tab.ME); host.screen(MainActivity.Tab.TODAY) }) as? TodayScreen
                    if (ts?.briefingPlaying == true) ts.stopBriefing() else ts?.playBriefing()
                }
            }) { pickBriefingTime() }.apply { tag = "today-briefing" })
        Kit.addRow(main, Kit.row(ctx, R.drawable.lc_more, Ui.TEXT, ctx.getString(R.string.me_settings), ctx.getString(R.string.me_settings_sub)) { host.select(MainActivity.Tab.SETTINGS, animate = true) }.apply { tag = "today-settings" })
        listBox.addView(main)

        listBox.addView(Ui.top(Kit.section(ctx, ctx.getString(R.string.me_more_title)), 8))
        val more = Kit.list(ctx)
        Kit.addRow(more, Kit.row(ctx, R.drawable.lc_phone, Ui.CYAN, ctx.getString(R.string.me_calls), null) { host.openCalls() }.apply { tag = "me-calls" })
        Kit.addRow(more, Kit.row(ctx, R.drawable.lc_mic, Ui.GOLD, ctx.getString(R.string.me_sol_chat), null) { host.select(MainActivity.Tab.SOL, animate = true) }.apply { tag = "me-sol" })
        Kit.addRow(more, Kit.row(ctx, R.drawable.lc_spark, Ui.PURPLE, ctx.getString(R.string.me_season), null) { host.select(MainActivity.Tab.SEASON, animate = true) }.apply { tag = "today-season" })
        Kit.addRow(more, Kit.row(ctx, R.drawable.lc_shield, Ui.GREEN, ctx.getString(R.string.me_agents), null) { host.select(MainActivity.Tab.AGENTS, animate = true) }.apply { tag = "today-wallet-agents" })
        listBox.addView(more)
        listBox.addView(Ui.text(ctx, ctx.getString(R.string.today_footer, net.solardepin.solarchik.BuildConfig.VERSION_NAME, host.wallet.clusterName), 14f, Ui.withAlpha(Kit.MUTED, 0xAA), 600).apply { gravity = Gravity.CENTER; tag = "me-version" })
    }

    private fun pickBriefingTime() {
        val st = net.solardepin.solarchik.sol.BriefingStore(ctx)
        val pol = st.policy()
        android.app.TimePickerDialog(ctx, { _, h, m ->
            st.setPolicy(pol.copy(enabled = true, hour = h, minute = m))
            net.solardepin.solarchik.sol.Briefing.schedule(ctx)
            render()
        }, pol.hour, pol.minute, true).show()
    }

    override fun render() {
        if (!this::walletBox.isInitialized) return
        renderWallet()
        renderHabits()
        renderList()
    }

    override fun onShow() {
        render()
        refresh()
    }
}
