package net.solardepin.solarchik.ui

import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.screen.Secretary
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.2.6 "Forward missed calls to Sol". Step 1: verify this phone's number without SMS (the user calls the
 * secretary line from it; the line recognises the caller ID, links the number and hangs up, never answered).
 * Step 2: the carrier's own GSM forwarding codes, pre-filled in the dialer (the user presses call).
 */
class ForwardPanel(private val host: MainActivity) {
    private val ctx: Context = host
    val view: LinearLayout = Ui.column(ctx).apply { tag = "forward" }
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var callLine: TextView
    private var poll: Job? = null
    @Volatile private var line = Secretary.DEFAULT_FORWARD_NUMBER

    init { build(); refresh() }

    private fun build() {
        view.addView(Ui.text(ctx, ctx.getString(R.string.fw_title), 17f, Ui.TEXT, 800).apply { tag = "fw-title" })
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.fw_body), 12.5f).apply { setLineSpacing(0f, 1.3f) }, 6))

        view.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.fw_step1), Ui.GOLD), 14))
        input = EditText(ctx).apply {
            tag = "fw-number"
            hint = ctx.getString(R.string.fw_number_hint)
            setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 15f
            background = Ui.rounded(Ui.SURFACE2, dp(14).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            isSingleLine = true
            setText(Secretary.ownNumber(ctx))
        }
        view.addView(Ui.top(input, 8))
        view.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.fw_verify), Ui.Btn.SECONDARY) { verify() }.apply { tag = "fw-verify" }, 8))
        callLine = Ui.button(ctx, "", Ui.Btn.PRIMARY, R.drawable.ic_call) { dial(line) }.apply { tag = "fw-call-line"; visibility = android.view.View.GONE; maxLines = 2 }
        view.addView(Ui.top(callLine, 8))
        status = Ui.text(ctx, "", 12.5f, Ui.MUTED, 700).apply { tag = "fw-status"; setLineSpacing(0f, 1.25f) }
        view.addView(Ui.top(status, 8))

        view.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.fw_step2), Ui.GOLD), 16))
        view.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.fw_all), Ui.Btn.PRIMARY) {
            Secretary.forwardAllCode(line)?.let { dial(it) }
        }.apply { tag = "fw-all"; maxLines = 2 }, 8))
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.fw_one_case), 12f), 10))
        val row = Ui.row(ctx, gap = 8)
        listOf(Secretary.Forward.NO_ANSWER to R.string.fw_no_answer, Secretary.Forward.BUSY to R.string.fw_busy, Secretary.Forward.UNREACHABLE to R.string.fw_unreachable).forEach { (k, label) ->
            row.addView(Ui.weight(Ui.button(ctx, ctx.getString(label), Ui.Btn.GHOST) { Secretary.forwardOnCode(k, line)?.let { dial(it) } }.apply { tag = "fw-" + k.code; textSize = 13f; maxLines = 2 }))
        }
        view.addView(Ui.top(row, 6))
        view.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.fw_off, Secretary.FORWARD_ALL_OFF), Ui.Btn.GHOST) { dial(Secretary.FORWARD_ALL_OFF) }.apply { tag = "fw-off"; textSize = 14f }, 8))
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.fw_carrier), 11.5f).apply { setLineSpacing(0f, 1.25f) }, 10))
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.fw_how), 11.5f).apply { tag = "fw-how"; setLineSpacing(0f, 1.25f) }, 8))
    }

    private fun render() {
        val n = Secretary.ownNumber(ctx)
        status.text = when {
            Secretary.ownVerified(ctx) && n.isNotBlank() -> ctx.getString(R.string.fw_verified, n)
            poll?.isActive == true -> ctx.getString(R.string.fw_waiting)
            else -> ctx.getString(R.string.fw_not_verified)
        }
        status.setTextColor(if (Secretary.ownVerified(ctx)) Ui.GREEN else Ui.MUTED)
        callLine.text = ctx.getString(R.string.fw_call_line, line)
    }

    /** Reads the server's view (verified or not) once; the panel works offline from the last known state. */
    fun refresh() {
        render()
        if (!MainActivity.tickerEnabled) return
        host.scope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { ScreenApi.phoneStatus(PlayerIds.get(ctx)) }.getOrNull() } ?: return@launch
            st.optString("line").takeIf { it.isNotBlank() }?.let { line = it }
            val v = st.optBoolean("verified")
            if (v) Secretary.setOwn(ctx, st.optString("number"), true)
            else if (Secretary.ownVerified(ctx)) Secretary.setOwn(ctx, Secretary.ownNumber(ctx), false)
            render()
        }
    }

    private fun verify() {
        val n = Secretary.e164(input.text?.toString().orEmpty())
        if (n.isBlank()) { host.toast(ctx.getString(R.string.fw_bad_number)); return }
        Secretary.setOwn(ctx, n, false)
        input.setText(n)
        host.scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ScreenApi.verifyStart(PlayerIds.get(ctx), n) }.getOrNull() }
            if (r == null) { host.toast(ctx.getString(R.string.fw_offline)); return@launch }
            r.optString("line").takeIf { it.isNotBlank() }?.let { line = it }
            callLine.visibility = android.view.View.VISIBLE
            startPoll()
            render()
        }
    }

    private fun startPoll() {
        poll?.cancel()
        poll = host.scope.launch {
            val until = System.currentTimeMillis() + 5 * 60_000L
            while (System.currentTimeMillis() < until) {
                delay(3000)
                val st = withContext(Dispatchers.IO) { runCatching { ScreenApi.phoneStatus(PlayerIds.get(ctx)) }.getOrNull() }
                if (st?.optBoolean("verified") == true) {
                    Secretary.setOwn(ctx, st.optString("number"), true)
                    callLine.visibility = android.view.View.GONE
                    host.toast(ctx.getString(R.string.fw_verified_toast))
                    break
                }
            }
            poll = null
            render()
        }
    }

    private fun dial(code: String) {
        runCatching { host.startActivity(Intent(Intent.ACTION_DIAL, Secretary.dialUri(code))) }
            .onFailure { host.toast(ctx.getString(R.string.fwd_no_dialer)) }
    }
}
