package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.CallText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 1.1.3: "remind her Monday" becomes a reminder card with a resolved local date (worker reply and the offline
 * rules, EN + UK), and the worker's daily AI call minutes cap shows an honest missed-call line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release113Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val kyiv = ZoneId.of("Europe/Kyiv")
    /** Fri 9 Oct 2026, 23:40 Kyiv: the 1.1.2 audit's sample call. */
    private val callAt = LocalDateTime.of(2026, 10, 9, 23, 40).atZone(kyiv).toInstant().toEpochMilli()
    private val olena = "Olena called. She asks you to send her 10 USDC for the logo design today and to call her back at 15:00 on +380671112233. Remind her about the meeting on Monday."

    private fun call(notes: String, at: Long = callAt, status: String = CallInbox.DONE, reason: String = "") =
        CallItem("me", "rtc_1", "+380671112233", "", at, status, "screen", "Olena", "", "medium", notes, "+380671112233", "en", 60, 0.0, false, reason)

    @Test fun requestCarriesTheCallDateTodayAndZone() {
        val now = LocalDateTime.of(2026, 10, 10, 8, 0).atZone(kyiv).toInstant().toEpochMilli()
        val o = JSONObject(CallActionRules.requestBody(listOf(call(olena)), "en", kyiv, now))
        assertEquals("Europe/Kyiv", o.getString("tz"))
        assertEquals("2026-10-10", o.getString("today"))
        val c = o.getJSONArray("calls").getJSONObject(0)
        assertEquals("2026-10-09", c.getString("date"))
        assertEquals("23:40", c.getString("at"))
    }

    @Test fun workerReminderWithADateIsParsedStoredAndFiresThatDay() {
        val c = call(olena)
        val reply = """{"ok":true,"processed":["${c.key}"],"actions":[
            {"callId":"${c.key}","type":"payment","amount":10,"token":"USDC","recipient":"Olena","address":"","number":"","when":"","day":"","date":"","text":"Send Olena 10 USDC","quote":""},
            {"callId":"${c.key}","type":"callback","amount":0,"token":"","recipient":"","address":"","number":"+380671112233","when":"15:00","day":"today","date":"2026-10-09","text":"Call Olena back at 15:00","quote":""},
            {"callId":"${c.key}","type":"reminder","amount":0,"token":"","recipient":"","address":"","number":"","when":"","day":"","date":"2026-10-12","text":"Remind Olena about the meeting on Monday","quote":"Remind her about the meeting on Monday"},
            {"callId":"${c.key}","type":"reminder","amount":0,"token":"","recipient":"","address":"","number":"","when":"","day":"","date":"2026-13-40","text":"bad date","quote":""}]}"""
        val (actions, processed) = CallActionRules.parse(reply, listOf(c))!!
        assertEquals(setOf(c.key), processed)
        assertEquals(listOf("payment", "callback", "reminder", "reminder"), actions.map { it.type })
        val rem = actions[2]
        assertEquals("2026-10-12", rem.date)
        assertEquals("an invalid date is dropped", "", actions[3].date)
        assertEquals("", actions[0].date)
        // Monday 12 Oct at 09:00 Kyiv (no time said), in the user's zone
        val now = LocalDateTime.of(2026, 10, 10, 8, 0).atZone(kyiv).toInstant().toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0).atZone(kyiv).toInstant().toEpochMilli(), CallActionRules.remindAt(rem, c.at, now, kyiv))
        // a said time on that day is used
        assertEquals(LocalDateTime.of(2026, 10, 12, 10, 30).atZone(kyiv).toInstant().toEpochMilli(), CallActionRules.remindAt(rem.copy(time = "10:30"), c.at, now, kyiv))
        // the old rules without a date are unchanged: callback at 15:00 on the call's day, rolled past now
        val cb = actions[1].copy(date = "")
        assertEquals(LocalDateTime.of(2026, 10, 10, 15, 0).atZone(kyiv).toInstant().toEpochMilli(), CallActionRules.remindAt(cb, c.at, now, kyiv))
        assertEquals(0L, CallActionRules.remindAt(rem.copy(date = ""), c.at, now, kyiv))
        // stored and read back with the date
        app.getSharedPreferences(CallActionStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        CallActionStore(app).add(actions)
        assertEquals("2026-10-12", CallActionStore(app).find(rem.id).date)
    }

    @Test fun offlineRulesFindRemindHerMondayNextToThePaymentAndTheCallback() {
        val zone = ZoneId.systemDefault()
        val at = LocalDateTime.of(2026, 10, 9, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val a = CallActionRules.local(call(olena, at))
        assertEquals(listOf(CallAction.PAYMENT, CallAction.CALLBACK, CallAction.REMINDER), a.map { it.type })
        assertEquals("2026-10-12", a[2].date)
        val uk = CallActionRules.local(call("Нагадай їй, будь ласка, у вівторок про оплату оренди.", at))
        assertEquals(listOf(CallAction.REMINDER), uk.map { it.type })
        assertEquals("2026-10-13", uk[0].date)
        assertEquals("2026-10-10", CallActionRules.local(call("Не забудь завтра надіслати чернетку.", at))[0].date)
        assertEquals("2026-10-11", CallActionRules.localDate("у неділю", at, zone))
        assertEquals("2026-10-12", CallActionRules.localDate("в понеділок", at, zone))
        assertEquals("2026-10-16", CallActionRules.localDate("on Friday", at, zone))
        assertEquals("", CallActionRules.localDate("sometime", at, zone))
        // "remind me" still works as before (a reminder card), without a day there is no date
        val me = CallActionRules.local(call("Please remind me to bring the documents.", at))
        assertEquals(listOf(CallAction.REMINDER), me.map { it.type })
        assertEquals("", me[0].date)
    }

    @Test fun minutesCapMissedLineIsHonestInEnglish() {
        val line = CallText.summary(app, call("", status = CallInbox.FAILED, reason = "CALL_MINUTES_GLOBAL"))
        assertTrue(line, line.contains("AI call minutes"))
        assertTrue(CallText.summary(app, call("", status = CallInbox.FAILED, reason = "CALL_MINUTES_ACCOUNT")).contains("Nothing was charged"))
    }

    @Test @Config(qualifiers = "uk")
    fun minutesCapMissedLineInUkrainian() {
        val line = CallText.summary(app, call("", status = CallInbox.FAILED, reason = "CALL_MINUTES_ACCOUNT"))
        assertTrue(line, line.contains("хвилини AI-дзвінків"))
    }
}
