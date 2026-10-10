package net.solardepin.solarchik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.R
import net.solardepin.solarchik.notify.Notes
import net.solardepin.solarchik.screen.CallDetail
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.CallNotes
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.ui.Ui.dp

/**
 * Calls (0.21.9): every call the AI phone secretary answered for this player, newest first, with the note,
 * the transcript and the post-call actions (call back, block, remind me). Opened from Home, from Settings ›
 * Secretary and from a call notification. Kept as a plain activity so it ports to the next main menu as is.
 */
class CallsActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView
    private var items: List<CallItem> = emptyList()
    private var loading = false
    private var offline = false
    private var open: CallItem? = null
    private var detail: CallDetail? = null
    private var detailLoading = false
    private var blocked: Set<String> = emptySet()
    private var poll: Job? = null
    private var claimUntil = 0L
    private var topInset = 0
    private var bottomInset = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(net.solardepin.solarchik.core.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        items = CallInbox.cached(this)
        blocked = CallInbox.blockedLocal(this)
        claimUntil = savedInstanceState?.getLong("claimUntil") ?: 0L
        setContentView(build())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (open != null) { open = null; detail = null; render(); scroll.scrollTo(0, 0) } else finish()
            }
        })
        openFromIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("claimUntil", claimUntil)
    }

    private fun openFromIntent(i: Intent?) {
        // 1.0.0: "Call secretary" on Today arms the demo line and opens the dialer straight away
        if (i?.getBooleanExtra(EXTRA_TRY, false) == true) {
            i.removeExtra(EXTRA_TRY)
            callSecretary()
        }
        val key = i?.getStringExtra(EXTRA_KEY) ?: return
        items.firstOrNull { it.key == key }?.let { openCall(it) } ?: run { pendingKey = key }
    }

    private var pendingKey: String? = null

    override fun onResume() {
        super.onResume()
        refresh()
        // While the screen is open: every 15 s; right after "Call the secretary": every 10 s for 4 minutes.
        poll?.cancel()
        poll = scope.launch {
            while (isActive) {
                delay(if (System.currentTimeMillis() < claimUntil + 60_000) 10_000 else 15_000)
                refresh(quiet = true)
            }
        }
    }

    override fun onPause() {
        poll?.cancel()
        poll = null
        CallInbox.markSeen(this)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        column = Ui.column(this, gap = 12)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(column)
        }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 1.2.3: an opaque strip under the status bar, so the cards don't scroll (and smear) under the clock and icons
        val scrim = View(this).apply { setBackgroundColor(Ui.BG); tag = "calls-status-scrim"; isClickable = false }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        val navScrim = View(this).apply { setBackgroundColor(Ui.BG); tag = "calls-nav-scrim"; isClickable = false }
        root.addView(navScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
            scrim.layoutParams = (scrim.layoutParams as FrameLayout.LayoutParams).apply { height = bars.top }
            navScrim.layoutParams = (navScrim.layoutParams as FrameLayout.LayoutParams).apply { height = bars.bottom }
            pad()
            insets
        }
        pad()
        return root
    }

    private fun pad() {
        column.setPadding(dp(18), topInset + dp(10), dp(18), bottomInset + dp(28))
    }

    private fun header(title: String, back: Boolean) {
        val row = Ui.row(this, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(Ui.text(this, "‹", 30f, Ui.TEXT, 800).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48); minHeight = dp(48)
            contentDescription = getString(R.string.calls_back)
            tag = "calls-back"
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        titleView = Ui.display(this, title, 26f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        row.addView(Ui.weight(titleView))
        if (!back) {
            val r = Ui.text(this, getString(if (loading) R.string.calls_refreshing else R.string.calls_refresh), 13f, Ui.CYAN, 800).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(10), 0, dp(4), 0)
                tag = "calls-refresh"
                setOnClickListener { refresh() }
            }
            row.addView(r)
        }
        column.addView(row)
    }

    private fun render() {
        column.removeAllViews()
        val o = open
        if (o != null) renderDetail(o) else renderList()
    }

    // ---------------- list ----------------

    private fun renderList() {
        header(getString(R.string.calls_title), back = false)
        column.addView(Ui.muted(this, getString(R.string.calls_sub), 13f).apply { setLineSpacing(0f, 1.25f) })

        if (!Notes.allowed(this)) {
            column.addView(Ui.card(this, accent = Ui.AMBER, pad = 14).apply {
                addView(Ui.text(this@CallsActivity, getString(R.string.calls_notes_off), 13f, Ui.AMBER, 700))
                addView(Ui.top(Ui.button(this@CallsActivity, getString(R.string.notes_allow), Ui.Btn.SECONDARY, R.drawable.ic_timer) { askNotifications() }, 10))
            })
        }

        // Call the secretary: arms the demo line for this player, then opens the dialer
        column.addView(Ui.card(this, accent = Ui.PURPLE, pad = 16).apply {
            tag = "calls-try"
            val head = Ui.row(this@CallsActivity, gap = 12).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(Ui.iconBadge(this@CallsActivity, R.drawable.ic_call, Ui.PURPLE, 36))
            head.addView(Ui.weight(Ui.column(this@CallsActivity).apply {
                addView(Ui.h2(this@CallsActivity, getString(R.string.calls_try_title)))
                addView(Ui.top(Ui.text(this@CallsActivity, CallInbox.DEMO_LINE, 14f, Ui.CYAN, 800), 2))
            }))
            addView(head)
            val armed = System.currentTimeMillis() < claimUntil
            addView(Ui.top(Ui.muted(this@CallsActivity, getString(if (armed) R.string.calls_try_armed else R.string.calls_try_body), 12f).apply { setLineSpacing(0f, 1.25f) }, 10))
            addView(Ui.top(Ui.button(this@CallsActivity, getString(R.string.calls_try_btn), Ui.Btn.PRIMARY, R.drawable.ic_call) { callSecretary() }.apply { tag = "calls-try-btn" }, 12))
        })

        val unread = CallInbox.seenAt(this)
        when {
            items.isEmpty() && loading -> column.addView(Ui.muted(this, getString(R.string.calls_loading), 13f))
            items.isEmpty() -> column.addView(Ui.card(this, pad = 16).apply {
                addView(Ui.text(this@CallsActivity, getString(if (offline) R.string.calls_offline else R.string.calls_empty), 14f, Ui.TEXT, 700))
                addView(Ui.top(Ui.muted(this@CallsActivity, getString(R.string.calls_empty_body), 12f).apply { setLineSpacing(0f, 1.25f) }, 6))
            })
            else -> {
                column.addView(Ui.top(Ui.label(this, getString(R.string.calls_list_label)), 6))
                if (offline) column.addView(Ui.text(this, getString(R.string.calls_offline_cached), 12f, Ui.AMBER, 700))
                items.forEach { column.addView(row(it, it.at > unread && !it.blocked)) }
            }
        }

        // Whose calls: this phone's player id, plus linked ids (e.g. the demo line owner's account)
        column.addView(Ui.top(Ui.card(this, pad = 14).apply {
            addView(Ui.label(this@CallsActivity, getString(R.string.calls_ids_label)))
            addView(Ui.top(Ui.muted(this@CallsActivity, getString(R.string.calls_ids_body), 12f).apply { setLineSpacing(0f, 1.25f) }, 6))
            val own = PlayerIds.get(this@CallsActivity)
            addView(Ui.top(idRow(own, mine = true), 8))
            CallInbox.linked(this@CallsActivity).forEach { addView(Ui.top(idRow(it, mine = false), 6)) }
            addView(Ui.top(Ui.button(this@CallsActivity, getString(R.string.calls_link_btn), Ui.Btn.GHOST) { askLink() }.apply { tag = "calls-link" }, 10))
        }, 8))
        // 0.22.0: credit / voice / language are one tap away from here (the Home card now opens this list)
        column.addView(Ui.top(Ui.button(this, getString(R.string.calls_sec_settings), Ui.Btn.SECONDARY, R.drawable.ic_nav_settings) {
            startActivity(android.content.Intent(this, net.solardepin.solarchik.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(net.solardepin.solarchik.MainActivity.EXTRA_TAB, net.solardepin.solarchik.MainActivity.Tab.SETTINGS.name)
                .putExtra(net.solardepin.solarchik.MainActivity.EXTRA_FOCUS, "secretary"))
            finish()
        }.apply { tag = "calls-sec-settings" }, 4))
    }

    private fun idRow(id: String, mine: Boolean): View = Ui.row(this, gap = 8).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.weight(Ui.text(this@CallsActivity, (if (mine) getString(R.string.calls_id_mine) else getString(R.string.calls_id_linked)) + " · " + Fmt.short(id), 12f, if (mine) Ui.CYAN else Ui.TEXT, 700)))
        if (!mine) addView(Ui.text(this@CallsActivity, getString(R.string.calls_unlink), 12f, Ui.RED, 800).apply {
            minHeight = dp(40); gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { CallInbox.unlink(this@CallsActivity, id); items = items.filter { it.owner != id }; CallInbox.store(this@CallsActivity, items); render() }
        })
    }

    private fun statusColor(it: CallItem): Int = when {
        it.blocked -> Ui.RED
        it.missed -> Ui.AMBER
        it.status == CallInbox.PENDING -> Ui.CYAN
        else -> Ui.GREEN
    }

    private fun row(it: CallItem, isNew: Boolean): View = Ui.card(this, pad = 14).apply {
        tag = "call-row"
        isClickable = true
        foreground = Ui.ripple(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), dp(22).toFloat(), 0x22F5C542)
        setOnClickListener { _ -> openCall(it) }
        val top = Ui.row(this@CallsActivity, gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }
        if (isNew) top.addView(View(this@CallsActivity).apply { background = Ui.rounded(Ui.GOLD, dp(5).toFloat()); contentDescription = getString(R.string.calls_new) }, LinearLayout.LayoutParams(dp(10), dp(10)))
        top.addView(Ui.weight(Ui.text(this@CallsActivity, CallText.who(this@CallsActivity, it).ifBlank { getString(R.string.calls_unknown) }, 16f, Ui.TEXT, 800).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }))
        top.addView(Ui.text(this@CallsActivity, CallText.time(it.at), 12f, Ui.MUTED, 700))
        addView(top)
        val pills = Ui.row(this@CallsActivity, gap = 6).apply { gravity = Gravity.CENTER_VERTICAL }
        pills.addView(Ui.pill(this@CallsActivity, CallText.status(this@CallsActivity, it), statusColor(it)))
        CallText.language(this@CallsActivity, it).takeIf { l -> l.isNotBlank() }?.let { l -> pills.addView(Ui.pill(this@CallsActivity, l, Ui.CYAN)) }
        CallInbox.duration(it.durationSec).takeIf { d -> d.isNotBlank() }?.let { d -> pills.addView(Ui.pill(this@CallsActivity, d, Ui.MUTED)) }
        if (it.callerName.isNotBlank() && it.caller.isNotBlank() && it.caller != "unknown") pills.addView(Ui.text(this@CallsActivity, net.solardepin.solarchik.screen.Phones.show(this@CallsActivity, it.caller), 12f, Ui.MUTED, 700))
        addView(Ui.top(pills, 8))
        addView(Ui.top(Ui.text(this@CallsActivity, CallText.summary(this@CallsActivity, it), 14f, Ui.TEXT, 600).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }, 8))
    }

    // ---------------- detail ----------------

    private fun openCall(it: CallItem) {
        open = it
        detail = null
        render()
        scroll.scrollTo(0, 0)
        if (it.callId.isBlank()) return
        detailLoading = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { ScreenApi.call(it.owner, it.callId) }
            detailLoading = false
            if (open?.key == it.key) { detail = d; render() }
        }
    }

    private fun renderDetail(base: CallItem) {
        val d = detail
        val it = d?.item?.copy(owner = base.owner) ?: base
        header(CallText.who(this, it).ifBlank { getString(R.string.calls_unknown) }, back = true)
        val card = Ui.card(this, accent = statusColor(it), pad = 16).apply { tag = "call-detail" }
        val pills = Ui.row(this, gap = 6).apply { gravity = Gravity.CENTER_VERTICAL }
        pills.addView(Ui.pill(this, CallText.status(this, it), statusColor(it)))
        CallText.language(this, it, d?.lines?.joinToString(" ") { l -> l.text }.orEmpty()).takeIf { l -> l.isNotBlank() }?.let { l -> pills.addView(Ui.pill(this, l, Ui.CYAN)) }
        card.addView(pills)
        fun fact(label: Int, value: String) {
            if (value.isBlank()) return
            val r = Ui.row(this, gap = 10)
            r.addView(Ui.text(this, getString(label), 12f, Ui.MUTED, 700), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.addView(Ui.weight(Ui.text(this, value, 14f, Ui.TEXT, 700).apply { setTextIsSelectable(true) }))
            card.addView(Ui.top(r, 8))
        }
        fact(R.string.calls_f_time, CallText.time(it.at) + " · " + getString(R.string.calls_kyiv))
        fact(R.string.calls_f_number, net.solardepin.solarchik.screen.Phones.show(this, it.caller.takeIf { c -> c != "unknown" }.orEmpty()).ifBlank { getString(R.string.calls_hidden) })
        if (it.callback.isNotBlank() && it.callback != it.caller) fact(R.string.calls_f_callback, net.solardepin.solarchik.screen.Phones.show(this, it.callback))
        fact(R.string.calls_f_name, it.callerName)
        fact(R.string.calls_f_duration, CallInbox.duration(d?.durationSec ?: it.durationSec))
        fact(R.string.calls_f_urgency, when (it.urgency) { "high" -> getString(R.string.calls_urg_high); "medium" -> getString(R.string.calls_urg_medium); "low" -> getString(R.string.calls_urg_low); else -> "" })
        fact(R.string.calls_f_cost, when {
            it.chargedUsd == null -> ""
            it.chargedUsd <= 0.0 -> getString(R.string.calls_cost_free)
            else -> "$" + Fmt.sol(it.chargedUsd, 2) + if (it.trial) " · " + getString(R.string.sec_trial) else ""
        })
        column.addView(card)

        column.addView(Ui.card(this, pad = 16).apply {
            addView(Ui.label(this@CallsActivity, getString(R.string.calls_note_label), Ui.GOLD))
            addView(Ui.top(Ui.text(this@CallsActivity, CallText.summary(this@CallsActivity, it), 16f, Ui.TEXT, 700).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.2f); tag = "call-note" }, 6))
        })

        // 1.1.0: requests found in this call (payment / callback / reminder); acted on from Today, never automatically
        CallActionCards.rows(this, it.key, it.who)?.let { v -> column.addView(v) }

        // post-call actions
        val actions = Ui.card(this, pad = 14).apply { tag = "call-actions" }
        actions.addView(Ui.label(this, getString(R.string.calls_actions_label)))
        val num = it.dialNumber
        val isBlocked = num.isNotBlank() && blocked.contains(num)
        val r1 = Ui.row(this, gap = 8)
        val back = Ui.button(this, getString(R.string.calls_call_back), Ui.Btn.PRIMARY, R.drawable.ic_call) { dial(num) }.apply { tag = "call-back" }
        Ui.setEnabled(back, num.isNotBlank())
        actions.addView(Ui.top(back, 10))
        val remindAt = CallNotes.Reminders.at(this, it.key)
        r1.addView(Ui.weight(Ui.button(this, getString(if (remindAt > 0) R.string.calls_remind_change else R.string.calls_remind), Ui.Btn.SECONDARY, R.drawable.ic_timer) { askRemind(it) }.apply { tag = "call-remind" }))
        actions.addView(Ui.top(r1, 8))
        if (remindAt > 0) {
            actions.addView(Ui.top(Ui.row(this, gap = 8).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.weight(Ui.text(this@CallsActivity, getString(R.string.calls_remind_set, CallText.time(remindAt)), 12f, Ui.GOLD, 700)))
                addView(Ui.text(this@CallsActivity, getString(R.string.calls_remind_cancel), 12f, Ui.MUTED, 800).apply {
                    minHeight = dp(40); gravity = Gravity.CENTER
                    setOnClickListener { _ -> CallNotes.cancelReminder(this@CallsActivity, it); render() }
                })
            }, 6))
        }
        val blk = Ui.button(this, getString(if (isBlocked) R.string.calls_unblock else R.string.calls_block), Ui.Btn.GHOST) { askBlock(it, !isBlocked) }.apply { tag = "call-block" }
        Ui.setEnabled(blk, num.isNotBlank())
        actions.addView(Ui.top(blk, 8))
        actions.addView(Ui.top(Ui.muted(this, getString(if (isBlocked) R.string.calls_blocked_body else R.string.calls_block_body), 11f).apply { setLineSpacing(0f, 1.2f) }, 6))
        column.addView(actions)

        // transcript
        column.addView(Ui.card(this, pad = 14).apply {
            tag = "call-transcript"
            addView(Ui.label(this@CallsActivity, getString(R.string.calls_transcript_label)))
            val lines = d?.lines.orEmpty()
            when {
                it.callId.isBlank() -> addView(Ui.top(Ui.muted(this@CallsActivity, getString(R.string.calls_transcript_none), 12f), 8))
                detailLoading && d == null -> addView(Ui.top(Ui.muted(this@CallsActivity, getString(R.string.calls_loading), 12f), 8))
                d == null -> addView(Ui.top(Ui.text(this@CallsActivity, getString(R.string.calls_transcript_offline), 12f, Ui.AMBER, 700), 8))
                lines.isEmpty() -> addView(Ui.top(Ui.muted(this@CallsActivity, getString(R.string.calls_transcript_none), 12f), 8))
                else -> {
                    addView(Ui.top(Ui.muted(this@CallsActivity, getString(R.string.calls_transcript_hint), 11f), 4))
                    lines.forEach { l ->
                        val wrap = LinearLayout(this@CallsActivity).apply { gravity = if (l.caller) Gravity.START else Gravity.END }
                        val col = Ui.column(this@CallsActivity)
                        col.addView(Ui.text(this@CallsActivity, getString(if (l.caller) R.string.calls_who_caller else R.string.calls_who_secretary), 10f, if (l.caller) Ui.CYAN else Ui.PURPLE, 800))
                        col.addView(Ui.top(Ui.text(this@CallsActivity, net.solardepin.solarchik.screen.Phones.show(this@CallsActivity, l.text), 14f, Ui.TEXT, 600).apply {
                            background = Ui.rounded(if (l.caller) Ui.SURFACE2 else Ui.withAlpha(Ui.PURPLE, 0x30), dp(14).toFloat(), Ui.STROKE, dp(1))
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            setTextIsSelectable(true)
                        }, 2))
                        wrap.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            if (l.caller) rightMargin = dp(40) else leftMargin = dp(40)
                        })
                        addView(Ui.top(wrap, 8))
                    }
                }
            }
        })
    }

    // ---------------- actions ----------------

    private fun refresh(quiet: Boolean = false) {
        if (loading) return
        loading = true
        if (!quiet) render()
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val list = CallInbox.refresh(this@CallsActivity)
                // a note that arrived while this screen is open is announced too (the list shows it as new)
                if (list != null) runCatching { CallNotes.check(this@CallsActivity) }
                ScreenApi.blocked(PlayerIds.get(this@CallsActivity))?.let { CallInbox.setBlockedLocal(this@CallsActivity, it) }
                list
            }
            loading = false
            offline = fresh == null
            if (fresh != null) items = CallInbox.cached(this@CallsActivity)
            blocked = CallInbox.blockedLocal(this@CallsActivity)
            pendingKey?.let { k -> items.firstOrNull { it.key == k }?.let { pendingKey = null; openCall(it); return@launch } }
            // keep an open call in sync (its note may have arrived)
            open?.let { o -> items.firstOrNull { it.key == o.key }?.let { n -> if (n != o) { open = n; if (n.status != o.status) openCall(n) } } }
            if (!quiet || open == null) render()
        }
    }

    private fun callSecretary() {
        scope.launch {
            val armed = withContext(Dispatchers.IO) { ScreenApi.claim(PlayerIds.get(this@CallsActivity)) }
            if (armed != null) {
                claimUntil = System.currentTimeMillis() + armed * 1000L
                toast(getString(R.string.calls_try_ok))
            } else {
                toast(getString(R.string.calls_try_offline))
            }
            render()
            dial(CallInbox.DEMO_LINE)
        }
    }

    private fun dial(number: String) {
        if (number.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) }
            .onFailure {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("number", number))
                toast(getString(R.string.calls_no_dialer))
            }
    }

    private fun askBlock(item: CallItem, block: Boolean) {
        val num = item.dialNumber
        if (num.isBlank()) return
        val go = {
            scope.launch {
                val list = withContext(Dispatchers.IO) { ScreenApi.block(item.owner, num, block) }
                if (list == null) toast(getString(R.string.sec_offline))
                else {
                    if (item.owner == PlayerIds.get(this@CallsActivity)) CallInbox.setBlockedLocal(this@CallsActivity, list)
                    blocked = if (block) blocked + num else blocked - num
                    if (item.owner == PlayerIds.get(this@CallsActivity)) blocked = list.toSet()
                    toast(getString(if (block) R.string.calls_block_ok else R.string.calls_unblock_ok, num))
                }
                render()
            }
        }
        if (!block) { go(); return }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.calls_block_title, num))
            .setMessage(R.string.calls_block_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.calls_block) { _, _ -> go() }
            .show()
    }

    private fun askRemind(item: CallItem) {
        val now = System.currentTimeMillis()
        val tomorrow9 = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val options = listOf(
            getString(R.string.calls_remind_15) to now + 15 * 60_000L,
            getString(R.string.calls_remind_60) to now + 60 * 60_000L,
            getString(R.string.calls_remind_180) to now + 180 * 60_000L,
            getString(R.string.calls_remind_tomorrow) to tomorrow9,
        )
        val labels = (options.map { it.first } + getString(R.string.calls_remind_pick)).toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.calls_remind_title_pick)
            .setItems(labels) { _, which ->
                if (which < options.size) setReminder(item, options[which].second)
                else {
                    val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1) }
                    android.app.TimePickerDialog(this, { _, h, m ->
                        val at = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m); set(java.util.Calendar.SECOND, 0)
                            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }.timeInMillis
                        setReminder(item, at)
                    }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
                }
            }
            .show()
    }

    private fun setReminder(item: CallItem, at: Long) {
        CallNotes.remind(this, item, at)
        if (!Notes.allowed(this)) askNotifications()
        toast(getString(R.string.calls_remind_set, CallText.time(at)))
        render()
    }

    private fun askLink() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.calls_link_hint)
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            isSingleLine = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
            tag = "calls-link-input"
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.calls_link_title)
            .setMessage(R.string.calls_link_body)
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.calls_link_ok) { _, _ ->
                if (CallInbox.link(this, input.text?.toString().orEmpty())) refresh() else toast(getString(R.string.calls_link_bad))
            }
            .show()
    }

    private val notePermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { render() }

    private fun askNotifications() {
        if (Notes.allowed(this)) return
        if (Notes.needsRuntimePermission() && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notePermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
        }
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_KEY = "net.solardepin.solarchik.CALL_KEY"

        const val EXTRA_TRY = "net.solardepin.solarchik.CALL_TRY"

        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, CallsActivity::class.java))

        /** 1.0.0 Today: open one call's note and transcript. */
        fun openCall(ctx: Context, key: String) = ctx.startActivity(Intent(ctx, CallsActivity::class.java).putExtra(EXTRA_KEY, key))

        /** 1.0.0 Today: arm the demo line for this phone and open the dialer. */
        fun trySecretary(ctx: Context) = ctx.startActivity(Intent(ctx, CallsActivity::class.java).putExtra(EXTRA_TRY, true))
    }
}
