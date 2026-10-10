package net.solardepin.solarchik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.BuildConfig
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.core.AppData
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.screen.CallReports
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.screen.Secretary
import net.solardepin.solarchik.ui.Ui.dp

private const val ASSISTANT = net.solardepin.solarchik.core.SolarchikConfig.SOL_APP == "assistant"

class SettingsScreen(host: MainActivity) : Screen(host) {
    private lateinit var walletBox: LinearLayout
    private lateinit var networkBody: TextView
    private var devnetSwitch: Switch? = null
    private var balance: Double? = null
    private var airdropping = false
    private lateinit var notesState: LinearLayout
    private lateinit var secretaryBox: LinearLayout
    private var secCredit: Double? = null
    private var secBal: ScreenApi.Balance? = null
    private var secLoading = false
    private var secLangBusy = false
    private var secBusy = false
    private var refreshAfterPay = false
    private lateinit var fwdInput: android.widget.EditText
    private lateinit var fwdStatus: TextView
    private val fwdOnButtons = mutableListOf<View>()

    override fun build(): View = page {
        addView(Ui.display(ctx, ctx.getString(R.string.settings_title), 26f))

        walletBox = Ui.card(ctx, accent = Ui.GOLD)
        addView(walletBox)

        addView(section(R.string.settings_network, R.drawable.ic_nav_sol, Ui.CYAN).apply {
            networkBody = Ui.muted(ctx)
            addView(Ui.top(networkBody, 10))
            // 1.1.0: the devnet switch is a hidden developer option (tap the version line 7 times).
            devBox = Ui.column(ctx)
            addView(devBox)
        })

        // 1.1.2 assistant: the game's fee tiers / fee-free windows are not part of the assistant
        if (!ASSISTANT) addView(section(R.string.settings_fees, R.drawable.ic_gift, Ui.GOLD).apply {
            addView(Ui.top(Ui.body(ctx, ctx.getString(
                R.string.settings_fees_body,
                Fmt.sol(SolarchikConfig.PRO_PRICE_SOL),
                Fmt.sol(SolarchikConfig.FREE_FEE_RATE * 100, 2),
                Fmt.sol(SolarchikConfig.ROYALTY_BPS / 100.0, 2),
                SolarchikConfig.STREAK_SHORT_DAYS,
                SolarchikConfig.WINDOW_SHORT_HOURS.toInt(),
                SolarchikConfig.STREAK_LONG_DAYS,
                SolarchikConfig.WINDOW_LONG_DAYS.toInt(),
            )).apply { setLineSpacing(0f, 1.3f) }, 10))
            addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.settings_treasury)), 12))
            addView(Ui.top(Ui.text(ctx, SolarchikConfig.TREASURY, 12f, Ui.CYAN, 700).apply {
                setOnClickListener { copy(SolarchikConfig.TREASURY) }
            }, 4))
        })

        addView(section(R.string.settings_notes, R.drawable.ic_timer, Ui.PURPLE).apply {
            notesState = Ui.column(ctx)
            addView(Ui.top(notesState, 8))
            listOf(
                "noteStreak" to R.string.note_streak_toggle,
            ).filter { (key, _) -> !net.solardepin.solarchik.game.GameSave.assistantOff(key) }.forEach { (key, label) ->
                addView(Ui.top(switchRow(ctx.getString(label), host.save.noteOn(key)) { _, on ->
                    host.save.setNote(key, on)
                    if (on) host.requestNotifications(fromUser = false)
                }, 8))
            }
        })

        addView(section(R.string.sec_title, R.drawable.ic_mic, Ui.PURPLE).apply {
            secretaryBox = Ui.column(ctx)
            addView(Ui.top(secretaryBox, 8))
            addView(Ui.top(buildForward(), 16))
        })

        addView(section(R.string.settings_run_sound, R.drawable.ic_run_volume, Ui.CYAN).apply {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.settings_run_sound_body)), 8))
            addView(Ui.top(volumeRow(R.string.settings_run_music, host.save.runMusicVol) { host.save.runMusicVol = it }, 12))
            addView(Ui.top(volumeRow(R.string.settings_run_sfx, host.save.runSfxVol) { host.save.runSfxVol = it }, 8))
        })

        addView(section(R.string.settings_sol_voice, R.drawable.ic_nav_sol, Ui.GOLD).apply {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.settings_sol_voice_body)).apply { setLineSpacing(0f, 1.25f) }, 8))
            voiceBox = Ui.column(ctx)
            addView(Ui.top(voiceBox, 10))
        })

        addView(section(R.string.settings_language, R.drawable.ic_nav_yard, Ui.GREEN).apply {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.settings_language_body)), 8))
            // In-app picker (0.21.7): stored choice, applied to UI, Sol chat/voice and agent texts.
            val pick = net.solardepin.solarchik.core.AppLocale.choice(ctx)
            val r = Ui.row(ctx, gap = 8)
            listOf(
                net.solardepin.solarchik.core.AppLocale.FOLLOW to ctx.getString(R.string.settings_language_phone),
                net.solardepin.solarchik.core.AppLocale.EN to "English",
                net.solardepin.solarchik.core.AppLocale.UK to "Українська",
            ).forEach { (code, label) ->
                r.addView(Ui.weight(Ui.button(ctx, label, if (code == pick) Ui.Btn.PRIMARY else Ui.Btn.GHOST) {
                    if (code != net.solardepin.solarchik.core.AppLocale.choice(ctx)) {
                        net.solardepin.solarchik.core.AppLocale.set(ctx, code)
                        host.recreate()
                    }
                }.apply { tag = "lang-" + code.ifEmpty { "phone" } }))
            }
            addView(Ui.top(r, 12))
        })

        addView(section(R.string.settings_privacy, R.drawable.ic_check, Ui.CYAN).apply {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.settings_privacy_body)).apply { setLineSpacing(0f, 1.3f) }, 8))
            val r = Ui.row(ctx, gap = 10)
            r.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.settings_privacy_open), Ui.Btn.GHOST) {
                host.openUrl(AppData.PRIVACY_URL)
            }))
            r.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.settings_delete_data), Ui.Btn.GHOST) { confirmWipe() }))
            addView(Ui.top(r, 12))
        })

        addView(section(R.string.settings_about, R.drawable.ic_launcher, Ui.GOLD, tint = false).apply {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.settings_about_body, BuildConfig.VERSION_NAME)).apply {
                tag = "settings-version"
                setOnClickListener { versionTap() }
            }, 8))
        })
    }

    /* ---------------- Sol's voice (0.21.8): OpenAI voice picker, preview, last engine + latency ---------------- */
    private lateinit var voiceBox: LinearLayout
    private var previewVoice: net.solardepin.solarchik.sol.SolVoice? = null

    internal fun renderVoice() {
        if (!this::voiceBox.isInitialized) return
        voiceBox.removeAllViews()
        val cur = net.solardepin.solarchik.sol.OpenAiVoice.voice(ctx)
        net.solardepin.solarchik.sol.OpenAiVoice.VOICES.chunked(2).forEach { pair ->
            val r = Ui.row(ctx, gap = 8)
            pair.forEach { v ->
                r.addView(Ui.weight(Ui.button(ctx, v.replaceFirstChar { it.uppercase() }, if (v == cur) Ui.Btn.SECONDARY else Ui.Btn.GHOST) {
                    net.solardepin.solarchik.sol.OpenAiVoice.setVoice(ctx, v)
                    renderVoice()
                    previewSolVoice()
                }.apply { tag = "voice-$v" }))
            }
            voiceBox.addView(Ui.top(r, 6))
        }
        voiceBox.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.settings_sol_voice_preview), Ui.Btn.GHOST, R.drawable.ic_nav_sol) { previewSolVoice() }.apply { tag = "voice-preview" }, 8))
        // 1.1.7: wipe the Sol chat (e.g. old Ukrainian messages before recording an English demo)
        voiceBox.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.settings_clear_chat), Ui.Btn.GHOST) {
            android.app.AlertDialog.Builder(ctx)
                .setMessage(R.string.settings_clear_chat_confirm)
                .setPositiveButton(R.string.settings_clear_chat) { _, _ ->
                    net.solardepin.solarchik.sol.SolChatStore(ctx).clear()
                    host.toast(ctx.getString(R.string.settings_clear_chat_done))
                    host.renderAll()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }.apply { tag = "clear-chat" }, 8))
        val src = net.solardepin.solarchik.sol.VoiceStats.source(ctx)
        val ms = net.solardepin.solarchik.sol.VoiceStats.ms(ctx)
        val engine = when {
            src.startsWith("openai") -> ctx.getString(R.string.voice_engine_openai)
            src == "gemini" -> ctx.getString(R.string.voice_engine_gemini)
            src == "system" -> ctx.getString(R.string.voice_engine_system)
            else -> ""
        }
        voiceBox.addView(Ui.top(Ui.muted(ctx, if (engine.isEmpty()) ctx.getString(R.string.voice_last_none) else ctx.getString(R.string.voice_last, engine, if (ms >= 0) "$ms" else "—"), 11f).apply { tag = "voice-last" }, 8))
        // 0.21.9: the last Sol turn timed stage by stage (voice gap diagnostics a judge can read)
        net.solardepin.solarchik.sol.SolLatency.stored(ctx)?.let { t ->
            val sec = net.solardepin.solarchik.sol.SolLatency::sec
            val line = if (t.voice) ctx.getString(R.string.voice_lat_voice, sec(t.stt), sec(t.request), sec(t.token), sec(t.audio))
            else ctx.getString(R.string.voice_lat_text, sec(t.request), sec(t.token), sec(t.audio))
            voiceBox.addView(Ui.top(Ui.muted(ctx, line, 11f).apply { tag = "voice-lat" }, 4))
        }
    }

    private fun previewSolVoice() {
        val v = previewVoice ?: net.solardepin.solarchik.sol.SolVoice(host).also { pv ->
            previewVoice = pv
            pv.onIdle = { n -> if (n == 0) host.toast(ctx.getString(R.string.sol_voice_failed)) }
        }
        v.speak(ctx.getString(R.string.settings_sol_voice_sample), host.lang)
        voiceBox.postDelayed({ renderVoice() }, 4000)
    }

    private fun section(title: Int, icon: Int, color: Int, tint: Boolean = true): LinearLayout = Ui.card(ctx).apply {
        val r = Ui.row(ctx, gap = 12)
        if (tint) r.addView(Ui.iconBadge(ctx, icon, color, 36))
        else r.addView(Ui.image(ctx, icon), LinearLayout.LayoutParams(dp(36), dp(36)))
        r.addView(Ui.weight(Ui.h2(ctx, ctx.getString(title))))
        addView(r)
    }

    /** A labelled 0..100 slider with its value on the right. */
    private fun volumeRow(label: Int, value: Int, set: (Int) -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = Ui.row(ctx)
        head.addView(Ui.weight(Ui.body(ctx, ctx.getString(label))))
        val num = Ui.text(ctx, String.format(java.util.Locale.ROOT, "%d", value), 14f, Ui.GOLD, 800)
        head.addView(num)
        box.addView(head)
        val bar = SeekBar(ctx).apply {
            max = 100
            progress = value
            contentDescription = ctx.getString(label)
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { num.text = String.format(java.util.Locale.ROOT, "%d", p); if (fromUser) set(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { set(sb.progress) }
            })
        }
        box.addView(Ui.top(bar, 4))
        return box
    }

    private fun switchRow(label: String, on: Boolean, cb: (CompoundButton, Boolean) -> Unit): View {
        val r = Ui.row(ctx)
        r.addView(Ui.weight(Ui.body(ctx, label)))
        val sw = Switch(ctx).apply {
            isChecked = on
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.GOLD, Ui.MUTED),
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.withAlpha(Ui.GOLD, 0x66), Ui.STROKE),
            )
            setOnCheckedChangeListener(cb)
        }
        r.addView(sw)
        return r
    }

    private lateinit var devBox: LinearLayout
    private var versionTaps = 0
    private var skr: Double? = null

    /** Seven taps on the version line unlock the developer devnet switch (testing only). */
    internal fun versionTap() {
        if (host.wallet.devUnlocked) return
        if (++versionTaps < 7) return
        host.wallet.devUnlocked = true
        host.toast(ctx.getString(R.string.mn_dev_unlocked))
        render()
    }

    private fun renderDev() {
        devBox.removeAllViews()
        val w = host.wallet
        if (!w.devUnlocked && !w.forceDevnet) return
        val sw = switchRow(ctx.getString(R.string.mn_dev_toggle), w.forceDevnet) { _, on ->
            w.forceDevnet = on
            balance = null
            skr = null
            host.renderAll()
        }.apply { tag = "settings-dev-devnet" }
        devBox.addView(Ui.top(sw, 10))
        devBox.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.mn_dev_note), 12f), 6))
    }

    override fun onShow() {
        render()
        refreshBalance()
        // 0.21.8: the live balance is read every time Settings opens (it showed a stale or empty value)
        if (Secretary.supported()) refreshSecretary()
    }

    private fun refreshBalance() {
        if (!host.wallet.connected) return
        host.scope.launch {
            host.wallet.balanceSol().onSuccess { balance = it; render() }
            // 1.1.0: real SKR next to SOL on mainnet (read-only token account read)
            if (host.wallet.mainnet && MainActivity.tickerEnabled) {
                val addr = host.wallet.address
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetch(addr) }
                    .onSuccess { skr = it; render() }
            }
        }
    }

    override fun onHide() {
        previewVoice?.stop()
    }

    override fun render() {
        if (!this::walletBox.isInitialized) return
        renderNotes()
        renderVoice()
        renderSecretary()
        renderForward()
        val w = host.wallet
        networkBody.text = ctx.getString(
            R.string.join_dot,
            ctx.getString(if (w.mainnet) R.string.network_mainnet else R.string.network_devnet),
            ctx.getString(if (w.mainnet) R.string.mn_network_mainnet else R.string.mn_network_devnet),
        )
        renderDev()

        walletBox.removeAllViews()
        val head = Ui.row(ctx, gap = 12)
        head.addView(Ui.iconBadge(ctx, R.drawable.ic_wallet, Ui.GOLD, 36))
        head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.settings_wallet))))
        head.addView(Ui.pill(ctx, w.clusterName, if (w.mainnet) Ui.GREEN else Ui.CYAN))
        walletBox.addView(head)
        if (!w.connected && w.mainnet) {
            // 1.1.0 mainnet: Mobile Wallet Adapter only (Seed Vault on Seeker, Phantom / Solflare elsewhere)
            val hasApp = w.hasWalletApp()
            walletBox.addView(Ui.top(Ui.muted(ctx, ctx.getString(if (hasApp) R.string.mn_connect_body else R.string.mn_no_app)).apply { setLineSpacing(0f, 1.25f) }, 10))
            walletBox.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.wallet_connect), Ui.Btn.PRIMARY, R.drawable.ic_wallet) { connect() }.apply { tag = "settings-connect" }, 14))
            if (!hasApp) walletBox.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.mn_get_wallet), Ui.Btn.SECONDARY, R.drawable.ic_open) { host.showInstallWallet() }, 10))
            return
        }
        if (!w.connected) {
            // 0.21.9: without a wallet app the built-in devnet wallet is the main path, not an error toast
            val hasApp = w.hasWalletApp()
            walletBox.addView(Ui.top(Ui.muted(ctx, ctx.getString(if (hasApp) R.string.lw_none_app else R.string.lw_none_builtin)), 10))
            val app = Ui.button(ctx, ctx.getString(R.string.wallet_connect), if (hasApp) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, R.drawable.ic_wallet) { connect() }
            val local = Ui.button(ctx, ctx.getString(R.string.lw_offer_use), if (hasApp) Ui.Btn.SECONDARY else Ui.Btn.PRIMARY, R.drawable.ic_gift) {
                host.scope.launch { host.setupBuiltInWallet(); refreshBalance() }
            }
            walletBox.addView(Ui.top(if (hasApp) app else local, 14))
            walletBox.addView(Ui.top(if (hasApp) local else app, 10))
            walletBox.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.lw_paper_note), 12f), 12))
            return
        }
        walletBox.addView(Ui.top(Ui.pill(ctx, ctx.getString(if (w.isLocal) R.string.lw_pill else R.string.lw_app_pill), if (w.isLocal) Ui.CYAN else Ui.GOLD, R.drawable.ic_wallet), 12))
        walletBox.addView(Ui.top(Ui.text(ctx, w.address, 13f, Ui.CYAN, 700).apply { setOnClickListener { copy(w.address) } }, 10))
        val balRow = Ui.row(ctx)
        balRow.addView(Ui.weight(Ui.label(ctx, ctx.getString(if (w.mainnet) R.string.settings_balance else R.string.lw_balance))))
        balRow.addView(Ui.text(ctx, balance?.let { ctx.getString(R.string.sol_unit, Fmt.sol(it)) } ?: "—", 22f, Ui.TEXT, 900).apply {
            setOnClickListener { refreshBalance() }
        })
        walletBox.addView(Ui.top(balRow, 12))
        if (w.mainnet) {
            val skrRow = Ui.row(ctx)
            skrRow.addView(Ui.weight(Ui.label(ctx, "SKR")))
            skrRow.addView(Ui.text(ctx, skr?.let { Fmt.sol(it, 2) + " SKR" } ?: "—", 18f, Ui.TEXT, 800).apply { tag = "settings-skr" })
            walletBox.addView(Ui.top(skrRow, 8))
            walletBox.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.mn_real_funds), 12f).apply { setLineSpacing(0f, 1.2f) }, 8))
        }
        if (w.isLocal) walletBox.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.lw_note), 12f).apply { setLineSpacing(0f, 1.2f) }, 8))
        if (!w.mainnet) walletBox.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.lw_paper_note), 12f), 8))
        if (!w.mainnet) {
            val label = when {
                airdropping -> ctx.getString(R.string.settings_airdrop_busy)
                w.isLocal -> ctx.getString(R.string.lw_get_sol)
                else -> ctx.getString(R.string.settings_airdrop, Fmt.sol(SolarchikConfig.AIRDROP_SOL))
            }
            val b = Ui.button(ctx, label, Ui.Btn.SECONDARY, R.drawable.ic_gift) { airdrop() }
            Ui.setEnabled(b, !airdropping)
            walletBox.addView(Ui.top(b, 14))
        }
        val actions = Ui.row(ctx, gap = 10)
        actions.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.open_explorer), Ui.Btn.GHOST) {
            host.openUrl(host.explorerAddress(w.address, w.clusterName))
        }))
        actions.addView(Ui.weight(Ui.button(ctx, ctx.getString(if (w.isLocal) R.string.lw_switch_app else R.string.settings_disconnect), Ui.Btn.GHOST) {
            w.forget()
            balance = null
            host.renderAll()
            if (w.hasWalletApp()) connect()
        }))
        walletBox.addView(Ui.top(actions, 10))
        if (w.mainnet) walletBox.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.mn_open_orb), Ui.Btn.GHOST, R.drawable.ic_open) {
            host.openUrl(SolarchikConfig.orbAccount(w.address))
        }.apply { tag = "settings-orb" }, 8))
    }

    private fun renderNotes() {
        notesState.removeAllViews()
        val ok = net.solardepin.solarchik.notify.Notes.allowed(ctx)
        notesState.addView(Ui.muted(ctx, ctx.getString(R.string.notes_how), 12f))
        if (ok) {
            notesState.addView(Ui.top(Ui.pill(ctx, ctx.getString(R.string.notes_allowed), Ui.GREEN, R.drawable.ic_check), 8))
        } else {
            notesState.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.notes_blocked), 13f, Ui.AMBER, 700), 8))
            notesState.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.notes_allow), Ui.Btn.SECONDARY, R.drawable.ic_timer) {
                host.requestNotifications(fromUser = true)
            }, 10))
        }
    }

    /** Home's Secretary card lands here: scroll the secretary section into view (0.21.7). */
    fun focusSecretary() {
        if (!this::secretaryBox.isInitialized) return
        secretaryBox.post { scrollToView(secretaryBox) }
    }

    // ---- Call secretary ----

    private fun renderSecretary() {
        val box = secretaryBox
        box.removeAllViews()
        // 0.21.9: the call archive first — Vadym looked here for his test call and found nothing
        val unread = net.solardepin.solarchik.screen.CallInbox.unreadCount(ctx)
        box.addView(Ui.button(ctx, if (unread > 0) ctx.getString(R.string.sec_calls_open_new, unread) else ctx.getString(R.string.sec_calls_open), Ui.Btn.PRIMARY, R.drawable.ic_call) {
            host.openCalls()
        }.apply { tag = "sec-calls" })
        box.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sec_calls_hint), 12f), 6))
        box.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sec_body)).apply { setLineSpacing(0f, 1.3f) }, 14))
        if (!Secretary.supported()) {
            box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sec_needs_android10), 13f, Ui.AMBER, 700), 10))
            return
        }
        val on = PlayerIds.screeningOn(ctx) && Secretary.holdsRole(ctx)
        box.addView(Ui.top(switchRow(ctx.getString(R.string.sec_toggle), on) { sw, want ->
            if (!want) {
                PlayerIds.setScreening(ctx, false)
                render()
            } else if (!(PlayerIds.screeningOn(ctx) && Secretary.holdsRole(ctx))) {
                sw.isChecked = false
                askToEnable()
            }
        }, 10))
        if (on) {
            box.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sec_mode)), 12))
            val modes = Ui.row(ctx, gap = 10)
            val cur = Secretary.mode(ctx)
            listOf(Secretary.Mode.SILENCE to R.string.sec_mode_silence, Secretary.Mode.DECLINE to R.string.sec_mode_decline).forEach { (m, label) ->
                modes.addView(Ui.weight(Ui.button(ctx, ctx.getString(label), if (m == cur) Ui.Btn.SECONDARY else Ui.Btn.GHOST) {
                    Secretary.setMode(ctx, m)
                    render()
                }))
            }
            box.addView(Ui.top(modes, 6))
            box.addView(Ui.top(Ui.muted(ctx, ctx.getString(if (cur == Secretary.Mode.SILENCE) R.string.sec_mode_silence_body else R.string.sec_mode_decline_body), 12f), 6))
        }

        box.addView(Ui.top(switchRow(ctx.getString(R.string.sec_ai_notes, Fmt.sol(Secretary.NOTE_USD, 2)), Secretary.aiNotes(ctx)) { _, want ->
            Secretary.setAiNotes(ctx, want)
        }, 12))

        // ---- credit (0.21.8): live /balance with paid + trial parts, loading feedback, tap to refresh ----
        val bal = secBal ?: Secretary.lastBalance(ctx)
        val creditRow = Ui.row(ctx, gap = 8).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        creditRow.addView(Ui.weight(Ui.label(ctx, ctx.getString(R.string.sec_credit))))
        if (bal != null && bal.owner) creditRow.addView(Ui.pill(ctx, ctx.getString(R.string.sec_owner), Ui.GREEN).apply { tag = "sec-owner" })
        else if (bal != null && bal.trial) creditRow.addView(Ui.pill(ctx, ctx.getString(R.string.sec_trial), Ui.CYAN))
        creditRow.addView(Ui.text(ctx, if (secLoading && bal == null) "…" else bal?.let { "$" + Fmt.sol(it.usd, 2) } ?: "—", 20f, Ui.TEXT, 900).apply {
            tag = "sec-credit"
            setOnClickListener { refreshSecretary() }
        })
        box.addView(Ui.top(creditRow, 12))
        if (bal != null && bal.owner) box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sec_owner_body), 12f, Ui.GREEN, 700), 2))
        else if (bal != null) box.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sec_credit_parts, "$" + Fmt.sol(bal.paidUsd, 2), "$" + Fmt.sol(bal.trialUsd, 2)), 12f), 2))
        val refresh = Ui.button(ctx, ctx.getString(if (secLoading) R.string.sec_refreshing else R.string.sec_refresh), Ui.Btn.GHOST) { refreshSecretary() }
        Ui.setEnabled(refresh, !secLoading)
        box.addView(Ui.top(refresh, 8))
        if (Secretary.needTopup(ctx)) {
            box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sec_need_topup), 13f, Ui.AMBER, 700), 6))
        }
        // ---- voice language of the phone secretary (worker /secretary-lang) ----
        box.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sec_lang)), 14))
        val langs = Ui.row(ctx, gap = 8)
        val curLang = Secretary.lang(ctx)
        listOf("auto" to R.string.sec_lang_auto, "uk" to R.string.sec_lang_uk, "en" to R.string.sec_lang_en).forEach { (code, label) ->
            val b = Ui.button(ctx, ctx.getString(label), if (code == curLang) Ui.Btn.SECONDARY else Ui.Btn.GHOST) { setSecLang(code) }
            Ui.setEnabled(b, !secLangBusy)
            langs.addView(Ui.weight(b))
        }
        box.addView(Ui.top(langs, 6))
        // ---- the full player id (the worker keys the credit and inbox by it) ----
        val pid = PlayerIds.get(ctx)
        box.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sec_player_id)), 14))
        // the id on its own full-width line (a weighted text next to the button collapsed to zero width)
        box.addView(Ui.top(Ui.text(ctx, pid, 12f, Ui.CYAN, 700).apply { setTextIsSelectable(true); tag = "sec-player-id" }, 6))
        box.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.copy), Ui.Btn.GHOST) { copy(pid) }.apply { tag = "sec-player-copy" }, 6))

        val pay = Ui.row(ctx, gap = 10)
        pay.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sec_topup, Secretary.TOPUP_USD), Ui.Btn.SECONDARY, R.drawable.ic_wallet) { startTopup() }))
        box.addView(Ui.top(pay, 12))
        val check = Ui.button(ctx, ctx.getString(if (secBusy) R.string.sec_checking else R.string.sec_check), Ui.Btn.GHOST) { checkPayment() }
        Ui.setEnabled(check, !secBusy && Secretary.pendingRef(ctx) != null)
        box.addView(Ui.top(check, 8))
        box.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sec_topup_how), 12f), 6))

        val reports = CallReports.list(ctx).take(8)
        box.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.sec_reports)), 14))
        if (reports.isEmpty()) box.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sec_reports_empty), 12f), 6))
        reports.forEach { r ->
            val head = ctx.getString(
                R.string.sec_report_head,
                Fmt.time(r.at),
                r.callerName.ifBlank { r.number },
                ctx.getString(if (r.action == "declined") R.string.sec_action_declined else R.string.sec_action_silenced),
            )
            box.addView(Ui.top(Ui.body(ctx, head), 8))
            val detail = when (r.status) {
                CallReports.STATUS_DONE -> listOf(r.reason, r.note).filter { it.isNotBlank() }.joinToString("\n")
                CallReports.STATUS_PENDING -> ctx.getString(R.string.sec_status_pending)
                CallReports.STATUS_NEED_TOPUP -> ctx.getString(R.string.sec_status_need_topup)
                CallReports.STATUS_FAILED -> ctx.getString(R.string.sec_status_failed)
                else -> ""
            }
            if (detail.isNotBlank()) box.addView(Ui.top(Ui.muted(ctx, detail, 12f), 2))
        }

    }

    /**
     * Carrier call forwarding to the Sol secretary number. Built once (so typing survives re-renders).
     * Each button only pre-fills the system dialer with a GSM MMI code (ACTION_DIAL, no CALL_PHONE);
     * the player presses call, and the carrier does the forwarding.
     */
    private fun buildForward(): View = Ui.column(ctx).apply {
        addView(Ui.label(ctx, ctx.getString(R.string.fwd_title)))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.fwd_body), 12f).apply { setLineSpacing(0f, 1.3f) }, 6))
        val numRow = Ui.row(ctx, gap = 10)
        fwdInput = android.widget.EditText(ctx).apply {
            hint = ctx.getString(R.string.fwd_number_hint)
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            typeface = Ui.tfMedium()
            background = Ui.rounded(Ui.SURFACE2, dp(14).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            isSingleLine = true
            contentDescription = ctx.getString(R.string.fwd_number_label)
            setText(Secretary.forwardNumber(ctx))
        }
        numRow.addView(Ui.weight(fwdInput))
        numRow.addView(Ui.button(ctx, ctx.getString(R.string.fwd_save), Ui.Btn.GHOST) { saveForwardNumber() })
        addView(Ui.top(numRow, 10))
        fwdStatus = Ui.muted(ctx, "", 12f)
        addView(Ui.top(fwdStatus, 6))

        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.fwd_on_label)), 12))
        fwdOnButtons.clear()
        listOf(
            Secretary.Forward.NO_ANSWER to R.string.fwd_no_answer,
            Secretary.Forward.BUSY to R.string.fwd_busy,
            Secretary.Forward.UNREACHABLE to R.string.fwd_unreachable,
        ).forEach { (kind, label) ->
            val b = Ui.button(ctx, ctx.getString(label, "**${kind.code}"), Ui.Btn.SECONDARY) {
                val code = Secretary.forwardOnCode(kind, Secretary.forwardNumber(ctx))
                if (code == null) host.toast(ctx.getString(R.string.fwd_need_number)) else dial(code)
            }
            fwdOnButtons += b
            addView(Ui.top(b, 8))
        }

        addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.fwd_off_label)), 14))
        val offRow = Ui.row(ctx, gap = 8)
        Secretary.Forward.values().forEach { kind ->
            val code = Secretary.forwardOffCode(kind)
            offRow.addView(Ui.weight(Ui.button(ctx, code, Ui.Btn.GHOST) { dial(code) }))
        }
        addView(Ui.top(offRow, 8))
        addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.fwd_off_all, Secretary.FORWARD_ALL_OFF), Ui.Btn.GHOST) {
            dial(Secretary.FORWARD_ALL_OFF)
        }, 8))
        addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.fwd_note), 12f).apply { setLineSpacing(0f, 1.3f) }, 10))
    }

    private fun renderForward() {
        if (!this::fwdStatus.isInitialized) return
        val num = Secretary.forwardNumber(ctx)
        fwdStatus.text = if (num.isEmpty()) ctx.getString(R.string.fwd_number_empty) else ctx.getString(R.string.fwd_number_set, num)
        fwdStatus.setTextColor(if (num.isEmpty()) Ui.AMBER else Ui.MUTED)
        fwdOnButtons.forEach { Ui.setEnabled(it, num.isNotEmpty()) }
    }

    private fun saveForwardNumber() {
        val saved = Secretary.setForwardNumber(ctx, fwdInput.text?.toString().orEmpty())
        if (saved == null) {
            host.toast(ctx.getString(R.string.fwd_number_bad))
        } else {
            fwdInput.setText(saved)
            host.toast(ctx.getString(if (saved.isEmpty()) R.string.fwd_number_cleared else R.string.fwd_number_saved))
        }
        renderForward()
    }

    /** Opens the dialer pre-filled; nothing is dialed until the player presses call. */
    private fun dial(code: String) {
        runCatching { host.startActivity(Intent(Intent.ACTION_DIAL, Secretary.dialUri(code))) }
            .onFailure { copy(code); host.toast(ctx.getString(R.string.fwd_no_dialer)) }
    }

    /** Rationale first, then the system role dialog. */
    private fun askToEnable() {
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.sec_rationale_title)
            .setMessage(R.string.sec_rationale_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sec_rationale_go) { _, _ ->
                host.requestScreeningRole { ok ->
                    PlayerIds.setScreening(ctx, ok)
                    if (!ok) host.toast(ctx.getString(R.string.sec_role_denied))
                    render()
                }
            }
            .show()
    }

    private fun startTopup() {
        val ref = Secretary.newReference()
        val uri = Secretary.payUri(PlayerIds.get(ctx), ref)
        Secretary.setPendingRef(ctx, ref)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        val handled = runCatching { intent.resolveActivity(host.packageManager) != null }.getOrDefault(false)
        if (!handled || runCatching { host.startActivity(intent) }.isFailure) showNoWallet(uri)
        render()
    }

    /** 0.21.8: no wallet app: offer Phantom / Solflare, the payment link and its QR (pay from another phone). */
    private fun showNoWallet(uri: String) {
        val col = Ui.column(ctx).apply { setPadding(dp(20), dp(8), dp(20), dp(4)) }
        col.addView(Ui.muted(ctx, ctx.getString(R.string.sec_no_wallet_body), 13f).apply { setLineSpacing(0f, 1.25f) })
        Secretary.WALLET_APPS.forEach { (name, pkg) ->
            col.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.sec_install_wallet, name), Ui.Btn.SECONDARY, R.drawable.ic_wallet) {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                runCatching { host.startActivity(market) }.onFailure { host.openUrl("https://play.google.com/store/apps/details?id=$pkg") }
            }, 10))
        }
        col.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.sec_copy_link), Ui.Btn.GHOST) { copy(uri) }, 10))
        Qr.bitmap(uri, dp(220))?.let { bmp ->
            col.addView(Ui.top(android.widget.ImageView(ctx).apply {
                setImageBitmap(bmp)
                contentDescription = ctx.getString(R.string.sec_qr)
                setBackgroundColor(android.graphics.Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }, 12), LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
            col.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sec_qr), 12f).apply { gravity = android.view.Gravity.CENTER }, 4))
        }
        col.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sec_then_check), 12f), 10))
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.sec_no_wallet_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sec_check) { _, _ -> checkPayment() }
            .show()
    }

    private fun setSecLang(lang: String) {
        if (secLangBusy || lang == Secretary.lang(ctx)) return
        secLangBusy = true
        val before = Secretary.lang(ctx)
        Secretary.setLang(ctx, lang)
        render()
        host.scope.launch {
            val saved = withContext(Dispatchers.IO) { ScreenApi.setLang(PlayerIds.get(ctx), lang) }
            if (saved == null) { Secretary.setLang(ctx, before); host.toast(ctx.getString(R.string.sec_offline)) }
            else { Secretary.setLang(ctx, saved); host.toast(ctx.getString(R.string.sec_lang_saved)) }
            secLangBusy = false
            render()
        }
    }

    private fun checkPayment() {
        val ref = Secretary.pendingRef(ctx) ?: return
        if (secBusy) return
        secBusy = true
        render()
        val userId = PlayerIds.get(ctx)
        host.scope.launch {
            val out = withContext(Dispatchers.IO) { ScreenApi.topup(userId, ref = ref) }
            when (out) {
                is ScreenApi.Topup.Credited -> {
                    Secretary.setPendingRef(ctx, null)
                    Secretary.setLastUsd(ctx, out.usd)
                    secCredit = out.usd
                    secBal = null
                    refreshAfterPay = true
                    host.toast(ctx.getString(R.string.sec_paid, "$" + Fmt.sol(out.added, 2)))
                }
                ScreenApi.Topup.AlreadyUsed -> {
                    Secretary.setPendingRef(ctx, null)
                    withContext(Dispatchers.IO) { ScreenApi.balanceInfo(userId) }?.let { secBal = it; secCredit = it.usd; Secretary.setLastBalance(ctx, it) }
                    host.toast(ctx.getString(R.string.sec_paid_already))
                }
                ScreenApi.Topup.NotFound -> host.toast(ctx.getString(R.string.sec_not_found))
                is ScreenApi.Topup.Invalid -> host.toast(ctx.getString(R.string.sec_invalid, out.detail))
                is ScreenApi.Topup.Failed -> host.toast(ctx.getString(R.string.sec_offline))
            }
            secBusy = false
            render()
            if (refreshAfterPay) { refreshAfterPay = false; refreshSecretary() }
        }
    }

    private fun refreshSecretary() {
        if (secLoading) return
        val userId = PlayerIds.get(ctx)
        secLoading = true
        render()
        host.scope.launch {
            val bal = withContext(Dispatchers.IO) { ScreenApi.balanceInfo(userId) }
            val lang = withContext(Dispatchers.IO) { ScreenApi.lang(userId) }
            withContext(Dispatchers.IO) { runCatching { net.solardepin.solarchik.screen.CallInbox.refresh(ctx) } }
            if (bal != null) { secBal = bal; secCredit = bal.usd; Secretary.setLastBalance(ctx, bal) }
            else host.toast(ctx.getString(R.string.sec_offline))
            if (lang != null && !secLangBusy) Secretary.setLang(ctx, lang)
            secLoading = false
            render()
        }
    }

    private fun connect() {
        val started = host.current
        host.scope.launch {
            host.wallet.connect(host.sender)
                .onSuccess { refreshBalance() }
                .onFailure { host.walletFailed(started, it) }
            host.renderAll()
        }
    }

    private fun airdrop() {
        if (airdropping || host.wallet.mainnet) return
        airdropping = true
        render()
        host.scope.launch {
            host.wallet.airdrop()
                .onSuccess {
                    host.toast(ctx.getString(if (it.isEmpty() && host.wallet.isLocal) R.string.lw_enough else R.string.settings_airdrop_ok))
                    delay(4000)
                    host.wallet.balanceSol().onSuccess { b -> balance = b }
                }
                .onFailure { host.toast(ctx.getString(R.string.settings_airdrop_fail)) }
            airdropping = false
            render()
        }
    }

    /** Publisher Policy: the player can delete everything the app stored about them. */
    private fun confirmWipe() {
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.settings_delete_title)
            .setMessage(R.string.settings_delete_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_delete_confirm) { _, _ ->
                host.wallet.forget()
                AppData.wipe(ctx)
                host.toast(ctx.getString(R.string.settings_delete_done))
                host.recreate()
            }
            .show()
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("address", text))
        host.toast(ctx.getString(R.string.copied))
    }
}
