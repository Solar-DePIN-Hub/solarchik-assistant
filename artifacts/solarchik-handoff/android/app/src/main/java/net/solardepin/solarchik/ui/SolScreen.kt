package net.solardepin.solarchik.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.sol.ChatTurn
import net.solardepin.solarchik.sol.DailyReport
import net.solardepin.solarchik.sol.Report
import net.solardepin.solarchik.sol.SolChat
import net.solardepin.solarchik.sol.SolChatStore
import net.solardepin.solarchik.sol.SolEars
import net.solardepin.solarchik.sol.SolVoice
import net.solardepin.solarchik.ui.Ui.dp

/** Sol: daily note from real numbers, chat with the friend worker, voice in and out (EN/UK). */
class SolScreen(host: MainActivity) : Screen(host) {
    private lateinit var bubble: TextView
    private lateinit var report: LinearLayout
    private lateinit var chatList: LinearLayout
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var micBtn: TextView
    private val chat = SolChat()
    private val store = SolChatStore(host)
    private var voice: SolVoice? = null
    private var ears: SolEars? = null
    private var sending = false
    private var listening = false
    /** Sol's retelling of today's note, accepted only if it adds no number. */
    private var retold: Pair<String, String>? = null // note script (changes with the streak / signature) to text
    private var plainWhy: Int = 0
    private var retelling = false
    /** Sol's action desk (0.21.7): context, plans, confirmed execution. */
    internal var actions = net.solardepin.solarchik.ui.SolActionDesk(host)
    internal var brain = net.solardepin.solarchik.sol.SolBrain()
    /** The action waiting for the player's tap. Nothing runs until [confirm]. */
    var pending: net.solardepin.solarchik.sol.ActionPlan? = null
        private set
    private var executing = false

    override fun build(): View = page {
        addView(Ui.display(ctx, ctx.getString(R.string.sol_title), 26f))
        addView(Ui.muted(ctx, ctx.getString(R.string.sol_sub), 14f))

        val stage = FrameLayout(ctx).apply {
            background = Ui.gradient(intArrayOf(Ui.blend(Ui.SURFACE2, Ui.CYAN, 0.18f), Ui.SURFACE), dp(26).toFloat(), android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250))
        }
        stage.addView(Ui.image(ctx, R.drawable.buddy_happy), FrameLayout.LayoutParams(dp(150), dp(200), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(10); rightMargin = dp(10)
        })
        bubble = Ui.text(ctx, "", 14f, Ui.TEXT, 700).apply {
            background = Ui.rounded(Ui.withAlpha(Ui.BG, 0xD8), dp(18).toFloat(), Ui.withAlpha(Ui.CYAN, 0x66), dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 7
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        stage.addView(bubble, FrameLayout.LayoutParams(dp(205), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(16); leftMargin = dp(14)
        })
        addView(stage)

        addView(Ui.card(ctx, accent = Ui.GOLD).apply {
            val head = Ui.row(ctx, gap = 12)
            head.addView(Ui.iconBadge(ctx, R.drawable.ic_nav_sol, Ui.GOLD, 36))
            head.addView(Ui.weight(Ui.h2(ctx, ctx.getString(R.string.chat_title))))
            head.addView(Ui.text(ctx, ctx.getString(R.string.chat_clear), 12f, Ui.MUTED, 700).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                gravity = Gravity.CENTER
                minHeight = dp(48)
                minWidth = dp(48)
                setOnClickListener { store.clear(); render() }
            })
            addView(head)
            chatList = Ui.column(ctx, gap = 8)
            addView(Ui.top(chatList, 12))
            status = Ui.muted(ctx, "", 12f).apply { visibility = View.GONE }
            addView(Ui.top(status, 8))
            val row = Ui.row(ctx, gap = 8)
            input = EditText(ctx).apply {
                hint = ctx.getString(R.string.chat_hint)
                setHintTextColor(Ui.MUTED)
                setTextColor(Ui.TEXT)
                textSize = 15f
                typeface = Ui.tfMedium()
                background = Ui.rounded(Ui.SURFACE2, dp(16).toFloat(), Ui.STROKE, dp(1))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_SEND
                maxLines = 3
                setOnEditorActionListener { _, id, ev ->
                    if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(text.toString()); true } else false
                }
            }
            row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
            micBtn = round(Ui.CYAN) { toggleMic() }.apply { contentDescription = ctx.getString(R.string.chat_mic) }
            micBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
            micBtn.setPadding(dp(12), 0, 0, 0)
            row.addView(micBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(round(Ui.GOLD) {
                // 1.2.1: an empty box is not a silent no-op: the field gets focus and the keyboard opens
                if (input.text.isNullOrBlank()) {
                    input.requestFocus()
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } else send(input.text.toString())
            }.apply {
                tag = "sol-send"
                contentDescription = ctx.getString(R.string.chat_send)
                background = Ui.rounded(Ui.GOLD, dp(16).toFloat())
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_send, 0, 0, 0)
                setPadding(dp(13), 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(Ui.top(row, 12))
            addView(Ui.top(Ui.switchRow(ctx, ctx.getString(R.string.chat_voice), store.voiceOn) { _, on ->
                store.voiceOn = on
                if (!on) voice?.stop()
            }, 10))
        })

        // 1.0.0: the conversation comes first; today's note follows it
        report = Ui.card(ctx, accent = Ui.CYAN)
        addView(report)

    }

    /** 1.0.0 Today wallet card: the action waiting for the player's Confirm, if any. */
    fun pendingTitle(): String? = pending?.let { planTitle(it) }

    /** 1.0.0: a question asked on Today lands here; bring the chat into view. */
    fun askFromToday(text: String, voice: Boolean) {
        send(text, voice)
        if (this::chatList.isInitialized) chatList.post { scrollToView(chatList) }
    }

    /** 1.0.0 Today chips: "Type instead" focuses the box and opens the keyboard. */
    fun focusInput() {
        if (!this::input.isInitialized) return
        input.requestFocus()
        input.post {
            scrollToView(input)
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun round(color: Int, onClick: () -> Unit): TextView = Ui.text(ctx, "", 18f, Ui.TEXT, 800).apply {
        gravity = Gravity.CENTER
        background = Ui.rounded(Ui.withAlpha(color, 0x22), dp(16).toFloat(), Ui.withAlpha(color, 0x66), dp(1))
        isClickable = true
        setOnClickListener { onClick() }
    }

    override fun onShow() {
        render()
        if (MainActivity.tickerEnabled) {
            // 1.1.0 assistant: no game-note retelling (it talked about runs and CLOCK IN)
            // 0.21.9: warm the agent/market context and the worker connection before the first question
            actions.prewarm()
            net.solardepin.solarchik.sol.SolLatency.prewarmWorker()
        }
        takeHandoff()
    }

    /** 0.21.8: an action asked for by voice during a run gets its confirmation card here, re-planned fresh. */
    internal fun takeHandoff() {
        if (sending || executing || pending != null) return
        val h = net.solardepin.solarchik.sol.SolHandoff.take() ?: return
        host.scope.launch {
            val c = actions.context()
            val action = h.action.takeIf { a -> a.agent == null || c.agent(a.agent) != null } ?: return@launch
            store.add(ChatTurn("user", h.said, h.at))
            val plan = net.solardepin.solarchik.sol.SolActions.plan(action, c)
            val why = blockedText(plan)
            if (why != null) say(why, speakIt = false)
            else {
                pending = plan
                say(if (plan.acquire) ctx.getString(R.string.sol_act_ready_acquire, plan.agent?.name.orEmpty()) else ctx.getString(R.string.sol_act_ready, planTitle(plan)), speakIt = false)
            }
            render()
        }
    }

    override fun onHide() {
        ears?.stop()
        // a stopped recognizer sends no callback: clear the "listening" line ourselves
        if (listening && this::status.isInitialized && !sending) status.visibility = View.GONE
        listening = false
        voice?.stop()
    }

    override fun onDestroy() {
        ears?.stop()
        ears = null
        voice?.shutdown()
        voice = null
    }

    private fun currentReport(): Report {
        val save = host.save
        return DailyReport.build(ctx, save.today(), save.liveStreak().streak, save.signedToday(), save.todayDistance(), save.feeProgress(), host.store.fees(), host.desk.state())
    }

    override fun render() {
        if (!this::report.isInitialized) return
        val today = host.save.today()
        val rep = currentReport()
        report.removeAllViews()
        // 1.1.0 assistant: the rooftop game's daily note (runs, CLOCK IN, fee windows) is not shown here.
        report.visibility = View.GONE
        val head = Ui.row(ctx)
        head.addView(Ui.weight(Ui.label(ctx, ctx.getString(R.string.report_title), Ui.CYAN)))
        report.addView(head)
        rep.lines.forEach { report.addView(Ui.top(Ui.body(ctx, "• $it"), 8)) }
        val told = retold?.takeIf { it.first == rep.script }?.second
        if (told != null) {
            report.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.report_retold), Ui.GOLD), 12))
            report.addView(Ui.top(Ui.text(ctx, told, 14f, Ui.TEXT, 600), 4))
        } else if (plainWhy != 0) {
            report.addView(Ui.top(Ui.muted(ctx, ctx.getString(plainWhy), 12f), 12))
        }
        report.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.report_listen), Ui.Btn.SECONDARY, R.drawable.ic_nav_sol) {
            speak(told ?: rep.script)
        }, 12))

        val turns = store.turns()
        chatList.removeAllViews()
        if (turns.isEmpty()) chatList.addView(Ui.muted(ctx, ctx.getString(R.string.chat_empty), 12f))
        turns.takeLast(8).forEach { chatList.addView(bubbleView(it)) }
        pending?.let { chatList.addView(actionCard(it)) }
        bubble.text = turns.lastOrNull { it.role == "assistant" && !it.fallback }?.text ?: ctx.getString(R.string.today_line_idle)
        micBtn.alpha = if (listening) 1f else 0.9f
        micBtn.background = Ui.rounded(if (listening) Ui.withAlpha(Ui.RED, 0x55) else Ui.withAlpha(Ui.CYAN, 0x22), dp(16).toFloat(), Ui.withAlpha(if (listening) Ui.RED else Ui.CYAN, 0x88), dp(1))
    }

    private fun bubbleView(t: ChatTurn): View {
        val mine = t.role == "user"
        val wrap = LinearLayout(ctx).apply { gravity = if (mine) Gravity.END else Gravity.START }
        val col = Ui.column(ctx)
        val tv = Ui.text(ctx, t.text, 14f, if (mine) Ui.INK else Ui.TEXT, 600).apply {
            background = if (mine) Ui.rounded(Ui.GOLD, dp(16).toFloat()) else Ui.rounded(Ui.SURFACE2, dp(16).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        col.addView(tv)
        if (t.fallback) col.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.chat_fallback), 10f, Ui.MUTED, 700), 2))
        // 1.1.2: no "game rules" tag in the assistant (local answers are the assistant's own)
        if (t.link.isNotBlank()) col.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sol_act_explorer), 12f, Ui.CYAN, 800).apply {
            isClickable = true
            setPadding(0, dp(6), 0, dp(6))
            tag = "sol-act-link"
            setOnClickListener { host.openUrl(t.link) }
        }, 2))
        wrap.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (mine) leftMargin = dp(48) else rightMargin = dp(48)
        })
        return wrap
    }

    fun send(raw: String, voice: Boolean = false) {
        val msg = raw.trim()
        if (msg.isEmpty() || sending) return
        net.solardepin.solarchik.sol.SolLatency.sent(voice)
        val history = store.turns()
        store.add(ChatTurn("user", msg, System.currentTimeMillis()))
        input.setText("")
        // 1.2.0: "what is my SKR balance": read it now when the Today card has not yet (read-only mainnet RPC)
        val w = host.wallet
        if (net.solardepin.solarchik.sol.AssistantRules.kind(msg) == net.solardepin.solarchik.sol.AssistantRules.Kind.SKR && w.connected && w.mainnet && host.walletSkr == null && MainActivity.tickerEnabled) {
            sending = true
            render()
            host.scope.launch {
                withContext(Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetch(w.address) }.onSuccess { host.walletSkr = it }
                sending = false
                val rule = net.solardepin.solarchik.sol.AssistantRules.skrLine(ctx, net.solardepin.solarchik.sol.AssistantRules.SkrState(true, true, host.walletSkr))
                store.add(ChatTurn("assistant", rule, System.currentTimeMillis(), local = true))
                render()
                speak(rule, auto = true)
            }
            return
        }
        // 1.2.4 Circle: "who do I owe?" / "send Ira what I owe" from the ledger on this phone; a payment opens its card
        val debts = net.solardepin.solarchik.circle.Circle.current(ctx)
        net.solardepin.solarchik.circle.CircleRules.intent(msg, debts)?.let { it ->
            val reply = when (it) {
                is net.solardepin.solarchik.circle.CircleRules.Intent.WhoOwe -> net.solardepin.solarchik.circle.CircleRules.whoOweText(ctx, debts)
                is net.solardepin.solarchik.circle.CircleRules.Intent.Pay -> {
                    val d = it.debt
                    if (d == null) {
                        if (it.name.isBlank()) net.solardepin.solarchik.circle.CircleRules.whoOweText(ctx, debts) else ctx.getString(R.string.circle_sol_nothing_for, it.name)
                    } else {
                        val c = d.contact
                        if (c != null && c.address.isNotBlank()) CallActionCards.payContact(host, d.action, c) { render() }
                        else CirclePanel.edit(host, c ?: net.solardepin.solarchik.circle.Contact("", d.who, d.call?.dialNumber.orEmpty())) { saved ->
                            if (saved.address.isNotBlank()) CallActionCards.payContact(host, d.action, saved) { render() }
                        }
                        ctx.getString(R.string.circle_sol_open, d.who)
                    }
                }
            }
            store.add(ChatTurn("assistant", reply, System.currentTimeMillis(), local = true))
            render()
            speak(reply, auto = true)
            return
        }
        // 1.0.0: calls / "what can you do" are answered from this phone (call archive), instantly and offline
        (net.solardepin.solarchik.sol.AssistantRules.answer(ctx, msg, net.solardepin.solarchik.screen.CallInbox.cached(ctx),
                season = { net.solardepin.solarchik.season.SeasonStore.planFor(ctx, host.save, host.wallet.mainnet) },
                skr = { net.solardepin.solarchik.sol.AssistantRules.SkrState(w.connected, w.mainnet, host.walletSkr.takeIf { w.connected && w.mainnet }) })
            ?: net.solardepin.solarchik.sol.SolRules.answer(ctx, msg, net.solardepin.solarchik.sol.SolState.of(host.save)))?.let { rule ->
            store.add(ChatTurn("assistant", rule, System.currentTimeMillis(), local = true))
            render()
            speak(rule, auto = true)
            return
        }
        act(msg, history)
    }

    /** 1.0.1: calls, follow-ups, wallet and the Season plan for the assistant prompt, then the agent desk report. */
    internal fun assistantContext(now: Long = System.currentTimeMillis()): String {
        val sec = net.solardepin.solarchik.screen.Secretary
        val secOn = runCatching { sec.supported() && net.solardepin.solarchik.screen.PlayerIds.screeningOn(ctx) && sec.holdsRole(ctx) }.getOrDefault(false)
        val w = host.wallet
        val line = net.solardepin.solarchik.sol.AssistantContext.build(
            net.solardepin.solarchik.screen.CallInbox.cached(ctx),
            net.solardepin.solarchik.screen.FollowUps.list(ctx, now),
            net.solardepin.solarchik.sol.AssistantContext.Wallet(w.connected, w.isLocal, if (w.connected) w.address else "", w.mainnet,
                host.walletSol.takeIf { w.connected }, host.walletSkr.takeIf { w.connected && w.mainnet },
                runCatching { net.solardepin.solarchik.swap.SwapStore(ctx).policy().live }.getOrDefault(false)),
            net.solardepin.solarchik.season.SeasonStore.planFor(ctx, host.save, host.wallet.mainnet),
            secOn, now,
            extras = net.solardepin.solarchik.sol.AssistantExtras.lines(ctx, now),
        )
        val report = currentReport().script.take(250)
        return if (report.isBlank()) line else "$line Agent desk: $report"
    }

    /* ---------------- one brain (0.21.8) ---------------- */

    private fun say(text: String, link: String = "", speakIt: Boolean = true) {
        store.add(ChatTurn("assistant", text, System.currentTimeMillis(), link = link, action = true))
        if (speakIt) speak(text, auto = true)
    }

    /** Last brain answer (tests, diagnostics): source, model, time to first token. */
    internal var lastReply: net.solardepin.solarchik.sol.SolBrain.Reply? = null
        private set

    /**
     * 0.21.8: everything except the instant rules goes through [brain] — worker (OpenAI, streamed), then the
     * market model, then the phone parser. Text streams into the status line and the voice starts on the
     * first finished sentence. An action becomes a confirmation card; nothing executes without the tap.
     */
    private fun act(msg: String, history: List<ChatTurn>) {
        sending = true
        pending = null
        status.text = "…"
        status.visibility = View.VISIBLE
        render()
        val v = if (store.voiceOn) (voice ?: SolVoice(host).also { voice = it }) else null
        v?.stop()
        v?.beginStream()
        var streamed = ""
        host.scope.launch {
            try {
                val c = actions.context()
                net.solardepin.solarchik.sol.SolLatency.request(actions.lastBuildMs)
                // 0.22.0: fresh state on every message; chat lines from before today's signature are not resent
                val st = net.solardepin.solarchik.sol.SolState.of(host.save)
                val r = brain.ask(msg, host.lang, "yard", c, net.solardepin.solarchik.sol.SolState.freshHistory(history, st), assistantContext(), st) { soFar ->
                    if (streamed.isEmpty()) net.solardepin.solarchik.sol.SolLatency.firstToken()
                    streamed = soFar
                    status.text = soFar
                    v?.feed(soFar, host.lang, final = false)
                }
                lastReply = r
                val action = r.action
                // finish the streamed voice with the final text (or speak a different final line)
                fun answer(text: String, link: String = "") {
                    store.add(ChatTurn("assistant", text, System.currentTimeMillis(), link = link, action = true))
                    if (v == null) return
                    if (streamed.isNotBlank() && text.startsWith(streamed.trim().take(24))) v.feed(text, host.lang, final = true)
                    else if (streamed.isBlank()) v.speak(text, host.lang)
                    else v.enqueue(text, host.lang)
                }
                when {
                    action == null -> {
                        if (r.offline || r.reply.isBlank()) {
                            if (r.offline) store.add(ChatTurn("assistant", SolChat.offlineLine(host.lang), System.currentTimeMillis(), fallback = true))
                            else answer(ctx.getString(R.string.sol_act_unclear))
                        } else answer(r.reply)
                    }
                    !action.type.needsConfirm -> {
                        val st = c.agent(action.agent)?.let { actions.status(it) } ?: ctx.getString(R.string.sol_act_unclear)
                        if (v != null && streamed.isNotBlank()) v.feed(streamed, host.lang, final = true)
                        store.add(ChatTurn("assistant", st, System.currentTimeMillis(), action = true))
                        v?.enqueue(st, host.lang)
                    }
                    else -> {
                        val plan = net.solardepin.solarchik.sol.SolActions.plan(action, c)
                        val why = blockedText(plan)
                        if (why != null) {
                            if (v != null && streamed.isNotBlank()) v.feed(streamed, host.lang, final = true)
                            store.add(ChatTurn("assistant", why, System.currentTimeMillis(), action = true))
                            v?.enqueue(why, host.lang)
                        } else {
                            pending = plan
                            // 0.22.0: a strategy change is always explained by the phone in plain words
                            val strategyLine = if (action.type == net.solardepin.solarchik.sol.ActType.SET_STRATEGY) StrategyWords.say(ctx, plan.agent?.name.orEmpty(), plan.changes) else null
                            answer(strategyLine ?: r.reply.takeIf { it.isNotBlank() && !plan.acquire && r.source == net.solardepin.solarchik.sol.SolBrain.Source.WORKER }
                                ?: if (plan.acquire) ctx.getString(R.string.sol_act_ready_acquire, plan.agent?.name.orEmpty()) else ctx.getString(R.string.sol_act_ready, planTitle(plan)))
                        }
                    }
                }
            } finally {
                sending = false
                status.visibility = View.GONE
                if (v == null) net.solardepin.solarchik.sol.SolLatency.done(host)
                render()
            }
        }
    }

    private fun blockedText(p: net.solardepin.solarchik.sol.ActionPlan): String? = when (p.blocked) {
        null -> null
        "gone" -> ctx.getString(R.string.sol_act_blocked_gone)
        "free_used" -> ctx.getString(R.string.sol_act_blocked_free_used)
        "already_running" -> ctx.getString(R.string.sol_act_blocked_running, p.agent?.name.orEmpty())
        "already_stopped" -> ctx.getString(R.string.sol_act_blocked_stopped, p.agent?.name.orEmpty())
        "listed" -> ctx.getString(R.string.sol_act_blocked_listed, p.agent?.name.orEmpty())
        "same" -> ctx.getString(R.string.sol_act_blocked_same)
        else -> ctx.getString(R.string.sol_act_blocked_no_nft)
    }

    private fun planTitle(p: net.solardepin.solarchik.sol.ActionPlan): String = when (p.action.type) {
        net.solardepin.solarchik.sol.ActType.BUY_STRATEGY -> ctx.getString(R.string.sol_act_title_buy, p.listing?.name.orEmpty())
        net.solardepin.solarchik.sol.ActType.SET_STRATEGY -> ctx.getString(R.string.sol_act_title_strategy, p.agent?.name.orEmpty())
        net.solardepin.solarchik.sol.ActType.MINT_FREE -> ctx.getString(R.string.sol_act_title_mint, AgentNames.display(ctx, net.solardepin.solarchik.core.Catalog.skus.first { !it.paidOnly }.name))
        net.solardepin.solarchik.sol.ActType.START_AGENT -> ctx.getString(R.string.sol_act_title_start, p.agent?.name.orEmpty())
        net.solardepin.solarchik.sol.ActType.STOP_AGENT -> ctx.getString(R.string.sol_act_title_stop, p.agent?.name.orEmpty())
        net.solardepin.solarchik.sol.ActType.AGENT_STATUS -> p.agent?.name.orEmpty()
    }

    private fun changeLabel(k: String): String = ctx.getString(
        when (k) {
            "risk" -> R.string.sol_chg_risk
            "windows" -> R.string.sol_chg_windows
            "stakeSol" -> R.string.sol_chg_stake
            "askLo" -> R.string.sol_chg_asklo
            "askHi" -> R.string.sol_chg_askhi
            "edgeBps" -> R.string.sol_chg_edge
            "stopPct" -> R.string.sol_chg_stop
            "takePct" -> R.string.sol_chg_take
            else -> R.string.sol_chg_rules
        },
    )

    /** The confirmation card: what changes, the price in SOL, the 240 h lock note, Confirm / Cancel. */
    private fun actionCard(p: net.solardepin.solarchik.sol.ActionPlan): View = Ui.card(ctx, accent = Ui.GOLD, pad = 14).apply {
        tag = "sol-act-card"
        addView(Ui.label(ctx, ctx.getString(R.string.sol_act_card), Ui.GOLD))
        addView(Ui.top(Ui.text(ctx, planTitle(p), 15f, Ui.TEXT, 800), 4))
        val sku = p.agent?.let { net.solardepin.solarchik.core.Catalog.baseOf(it.skuId) }
        if (p.acquire && sku != null) {
            // 0.21.8: "start X" for an agent I don't own — the same offer as the Agents tab
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.offer_body), 12f), 4))
            OfferCard.acquireOptions(host, sku, executing) { tier -> confirmAcquire(tier) }.forEach { o ->
                val b = Ui.button(ctx, o.label, if (o.primary) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, R.drawable.ic_bolt_small) { o.go() }.apply { tag = o.tag }
                Ui.setEnabled(b, o.enabled)
                addView(Ui.top(b, 8))
            }
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sol_act_wallet), 11f), 6))
            addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.sol_act_cancel), Ui.Btn.GHOST) { cancelPending() }.apply { tag = "sol-act-cancel" }, 8))
            return@apply
        }
        p.listing?.takeIf { p.action.type == net.solardepin.solarchik.sol.ActType.SET_STRATEGY }?.let {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sol_act_template, it.name), 12f), 4))
        }
        if (p.action.type == net.solardepin.solarchik.sol.ActType.SET_STRATEGY && p.nextSpec != null) {
            // 0.22.0: now vs after in plain words, then exactly what changes (no "windows 15/60" jargon)
            fun block(title: Int, spec: kotlinx.serialization.json.JsonObject?, tagName: String, accent: Int) = Ui.column(ctx).apply {
                tag = tagName
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.rounded(Ui.withAlpha(accent, 0x16), dp(14).toFloat(), Ui.withAlpha(accent, 0x55), dp(1))
                addView(Ui.label(ctx, ctx.getString(title), accent))
                StrategyWords.describe(ctx, spec).forEach { addView(Ui.top(Ui.text(ctx, it, 13f, Ui.TEXT, 600).apply { setLineSpacing(0f, 1.2f) }, 4)) }
            }
            addView(Ui.top(block(R.string.strat_now, p.agent?.spec, "sol-act-strategy-now", Ui.MUTED), 8))
            addView(Ui.top(block(R.string.strat_after, p.nextSpec, "sol-act-strategy-after", Ui.GOLD), 8))
            addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.strat_changes), Ui.CYAN), 10))
            p.changes.forEach { (k, from, to) -> addView(Ui.top(Ui.body(ctx, "• " + StrategyWords.change(ctx, k, from, to)), 4)) }
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.strat_guard), 12f).apply { setLineSpacing(0f, 1.2f) }, 6))
        } else p.changes.forEach { (k, from, to) ->
            val f = if (k == "risk") actions.riskLabel(from) else from
            val t = if (k == "risk") actions.riskLabel(to) else to
            addView(Ui.top(Ui.body(ctx, "• ${changeLabel(k)}: $f → $t"), 4))
        }
        val price = when (p.action.type) {
            net.solardepin.solarchik.sol.ActType.BUY_STRATEGY -> ctx.getString(R.string.sol_act_price, Fmt.sol((p.priceLamports ?: 0) / 1e9))
            net.solardepin.solarchik.sol.ActType.SET_STRATEGY -> ctx.getString(R.string.sol_act_price_strategy)
            net.solardepin.solarchik.sol.ActType.MINT_FREE -> ctx.getString(R.string.sol_act_price_free)
            else -> ctx.getString(R.string.sol_act_local)
        }
        addView(Ui.top(Ui.text(ctx, price, 13f, Ui.GOLD, 800).apply { tag = "sol-act-price" }, 8))
        if (p.locksSale) addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sol_act_lock, net.solardepin.solarchik.agents.StrategyRules.SALE_LOCK_HOURS), 12f, Ui.AMBER, 700).apply { tag = "sol-act-lock" }, 4))
        if (p.action.type in setOf(net.solardepin.solarchik.sol.ActType.BUY_STRATEGY, net.solardepin.solarchik.sol.ActType.SET_STRATEGY, net.solardepin.solarchik.sol.ActType.MINT_FREE)) {
            addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.sol_act_wallet), 11f), 4))
        }
        val row = Ui.row(ctx, gap = 8)
        row.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.sol_act_cancel), Ui.Btn.GHOST) { cancelPending() }.apply { tag = "sol-act-cancel" }))
        val ok = Ui.button(ctx, ctx.getString(R.string.sol_act_confirm)) { confirm() }.apply { tag = "sol-act-confirm" }
        Ui.setEnabled(ok, !executing)
        row.addView(Ui.weight(ok))
        addView(Ui.top(row, 10))
    }

    fun cancelPending() {
        if (pending == null || executing) return
        pending = null
        say(ctx.getString(R.string.sol_act_cancelled), speakIt = false)
        render()
    }

    /** Mint free / Buy Pro on the offer card, then start (still only on the player's tap). */
    fun confirmAcquire(tier: String) {
        val p = pending ?: return
        if (executing || !p.acquire) return
        runConfirmed { actions.acquireAndStart(p, tier) }
    }

    /** The ONLY path that executes an action: the player's tap on Confirm. */
    fun confirm() {
        val p = pending ?: return
        if (p.acquire) return
        runConfirmed { actions.execute(p) }
    }

    private fun runConfirmed(work: suspend () -> net.solardepin.solarchik.ui.ActResult) {
        if (executing) return
        executing = true
        status.text = ctx.getString(R.string.sol_act_working)
        status.visibility = View.VISIBLE
        render()
        host.scope.launch {
            val r = try {
                work()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                net.solardepin.solarchik.ui.ActResult(false, ctx.getString(R.string.sol_act_failed, host.errorText(t)))
            }
            executing = false
            pending = null
            status.visibility = View.GONE
            say(r.text, r.link)
            host.renderAll()
            render()
        }
    }

    /** Once per day: ask Sol to retell the note; keep it only if every number is from the note. */
    private fun retellOnce() {
        val today = host.save.today()
        val script = currentReport().script
        if (retelling || retold?.first == script) return
        retelling = true
        val rep = currentReport()
        host.scope.launch {
            val r = try {
                chat.ask(rep.script, host.lang, store.playerId(), store.conversationId(today), emptyList(), "yard", rep.script)
            } finally {
                retelling = false
            }
            plainWhy = when {
                r.offline || r.fallback -> R.string.report_plain_offline
                !SolChat.onlyKnownNumbers(r.text, rep.script) -> R.string.report_plain
                else -> 0
            }
            if (plainWhy == 0) retold = rep.script to r.text
            render()
        }
    }

    private fun speak(text: String, auto: Boolean = false) {
        if (auto && !store.voiceOn) return
        val v = voice ?: SolVoice(host).also { voice = it }
        if (!v.speak(text, host.lang)) host.toast(ctx.getString(R.string.chat_tts_missing))
    }

    private fun toggleMic() {
        if (listening) {
            ears?.stop(); listening = false; status.visibility = View.GONE; render(); return
        }
        host.withPermission(android.Manifest.permission.RECORD_AUDIO) { ok ->
            if (!ok) { host.toast(ctx.getString(R.string.chat_mic_denied)); return@withPermission }
            val e = ears ?: SolEars(host).also { ears = it }
            if (!e.available()) { host.toast(ctx.getString(R.string.chat_mic_off)); return@withPermission }
            voice?.stop()
            if (MainActivity.tickerEnabled) { actions.prewarm(); net.solardepin.solarchik.sol.SolLatency.prewarmWorker() }
            listening = true
            status.text = ctx.getString(R.string.chat_listening)
            status.visibility = View.VISIBLE
            render()
            e.listen(host.lang, onPartial = { status.text = it }) { said ->
                listening = false
                status.visibility = View.GONE
                render()
                if (!said.isNullOrBlank()) send(said, voice = true)
            }
        }
    }

    companion object {
        private val TIPS = intArrayOf(R.string.sol_tip_1, R.string.sol_tip_2, R.string.sol_tip_3, R.string.sol_tip_4, R.string.sol_tip_5)

        /** One stable tip per UTC day. */
        fun tipOfDay(ctx: Context, day: String): String {
            val h = day.fold(7) { acc, c -> acc * 31 + c.code } and 0x7fffffff
            return ctx.getString(TIPS[h % TIPS.size])
        }
    }
}
