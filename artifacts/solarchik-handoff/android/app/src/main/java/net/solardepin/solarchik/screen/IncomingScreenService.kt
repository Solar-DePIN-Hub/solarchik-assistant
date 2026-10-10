package net.solardepin.solarchik.screen

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Call secretary. Android only binds this for calls from numbers outside the user's contacts
 * (the app has no READ_CONTACTS), so contacts always ring. The decision is local and instant;
 * the optional AI note runs afterwards and never delays the call.
 */
class IncomingScreenService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        // getCallDirection() exists on API 29+; before that the service only sees incoming calls
        // (and the role itself needs API 29, see Secretary.MIN_SDK).
        val incoming = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            details.callDirection == Call.Details.DIRECTION_INCOMING
        val action = Secretary.decide(incoming, PlayerIds.screeningOn(this), Secretary.mode(this))
        respondToCall(details, response(action))
        // 1.2.6: if this call goes on to the secretary (declined = busy forward, or not answered), the line knows it
        // is ours: a hash of the caller's number, kept 3 minutes, only for a verified phone. Never blocks the call.
        if (incoming && Secretary.ownVerified(this)) {
            val n = Secretary.e164(details.handle?.schemeSpecificPart.orEmpty())
            if (n.isNotBlank()) { val app = applicationContext; Thread { runCatching { ScreenApi.forwardExpect(PlayerIds.get(app), Secretary.callerHash(n)) } }.start() }
        }
        if (action == Secretary.Action.ALLOW) return

        val number = details.handle?.schemeSpecificPart.orEmpty().ifBlank { "unknown" }
        val id = "call-" + System.currentTimeMillis().toString(36)
        val wantNote = Secretary.aiNotes(this)
        CallReports.add(
            this,
            CallReports.Report(
                id = id,
                at = System.currentTimeMillis(),
                number = number,
                action = if (action == Secretary.Action.DECLINE) "declined" else "silenced",
                status = if (wantNote) CallReports.STATUS_PENDING else CallReports.STATUS_LOGGED,
            ),
        )
        if (!wantNote) return
        val app = applicationContext
        Thread {
            runCatching { note(app, id, number) }
                .onFailure {
                    Log.w("SolarchikScreen", "note failed", it)
                    CallReports.update(app, id) { r -> r.copy(status = CallReports.STATUS_FAILED) }
                }
        }.start()
    }

    private fun response(action: Secretary.Action): CallResponse = when (action) {
        Secretary.Action.ALLOW -> CallResponse.Builder().build()
        // The call still reaches the call log and the in-call UI, just without ringing.
        Secretary.Action.SILENCE -> CallResponse.Builder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setSilenceCall(true)
        }.build()
        // Rejected like a busy line; the call log keeps it and the missed-call notification shows.
        Secretary.Action.DECLINE -> CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
    }

    companion object {
        /** The paid AI note: what the number likely is and what to do. Only runs with credit. */
        fun note(ctx: android.content.Context, id: String, number: String) {
            val text = "Incoming call from $number, not in the player's contacts. " +
                "Give a short caller note: likely caller type, spam risk, and whether to call back."
            val out = ScreenApi.screen(PlayerIds.get(ctx), text)
            if (out.needTopup) {
                Secretary.setNeedTopup(ctx, true)
                CallReports.update(ctx, id) { it.copy(status = CallReports.STATUS_NEED_TOPUP) }
                return
            }
            if (!out.ok) {
                CallReports.update(ctx, id) { it.copy(status = CallReports.STATUS_FAILED) }
                return
            }
            Secretary.setLastUsd(ctx, out.usd)
            val s = out.summary
            CallReports.update(ctx, id) {
                it.copy(
                    status = CallReports.STATUS_DONE,
                    note = s.optString("notes").ifBlank { out.reply }.take(400),
                    callerName = s.optString("caller_name").take(60),
                    reason = listOf(s.optString("intent"), s.optString("spam_risk").takeIf { r -> r.isNotBlank() }?.let { r -> "spam: $r" })
                        .filter { r -> !r.isNullOrBlank() }.joinToString(" · ").take(120),
                    chargedUsd = out.chargedUsd,
                )
            }
        }
    }
}
