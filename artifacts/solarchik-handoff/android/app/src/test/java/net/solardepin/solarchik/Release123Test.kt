package net.solardepin.solarchik

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallActionSync
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.wallet.FreezeProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** 1.2.3: re-checked calls get the payment card the old rules missed; wallet connect keep-alive is declared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release123Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val ira = CallItem("me", "rtc_ira", "+380637443792", "Ira says they paid for lunch yesterday, asks Vadim to send them 0.01 SOL, and reminds him about their meeting on Monday. Callback +380637443792. call back at 3",
        now - 3_600_000L, CallInbox.DONE, "screen", "Ira", "says they paid for lunch yesterday, asks Vadim to send them 0.01 SOL, and reminds him about their meeting on Monday", "", "call back at 3", "+380637443792", "en", 65, 0.0, false)

    // the live worker's reply for this exact call (POST /call/actions, 2026-10-10)
    private fun reply(key: String) = """{"ok":true,"actions":[
        {"callId":"$key","type":"payment","amount":0.01,"token":"SOL","recipient":"Ira","text":"Send Ira 0.01 SOL for lunch"},
        {"callId":"$key","type":"callback","number":"+380637443792","when":"15:00","text":"Call Ira back at 15:00"},
        {"callId":"$key","type":"reminder","text":"Remind Vadim about the meeting on Monday","date":"2026-10-12"}],"processed":["$key"]}"""

    @Test fun aCallSeenByTheOldRulesGetsItsMissingPaymentCardOnce() {
        val store = CallActionStore(app)
        // as on Vadym's tablet: processed by 1.2.2 with a callback + reminder, no payment
        store.add(listOf(
            CallAction(ira.key + "#0", ira.key, CallAction.CALLBACK, number = "+380637443792", time = "15:00", text = "Call Ira back at 15:00"),
            CallAction(ira.key + "#1", ira.key, CallAction.REMINDER, text = "Remind about the meeting on Monday", status = CallAction.DONE),
        ))
        store.markProcessed(listOf(ira.key))
        app.getSharedPreferences(CallActionStore.PREFS, Context.MODE_PRIVATE).edit().putInt("rules", 1).apply()

        val found = CallActionSync.run(app, listOf(ira), "en", now, ZoneId.of("Europe/Kiev")) { _, _ -> 200 to reply(ira.key) }
        assertEquals(listOf(CallAction.PAYMENT), found.map { it.type })
        val all = CallActionStore(app).forCall(ira.key)
        assertEquals(3, all.size) // the old callback + reminder stay, nothing duplicated
        val pay = all.single { it.payment }
        assertEquals(0.01, pay.amount, 1e-9)
        assertEquals("SOL", pay.token)
        assertEquals("", pay.saidAddress) // the address is entered by the user
        assertEquals(CallAction.DONE, all.single { it.type == CallAction.REMINDER }.status) // a finished card is not reopened

        // once only
        assertTrue(CallActionSync.run(app, listOf(ira), "en", now, ZoneId.of("Europe/Kiev")) { _, _ -> 200 to reply(ira.key) }.isEmpty())
        assertEquals(3, CallActionStore(app).forCall(ira.key).size)
    }

    @Test fun aFreshCallGetsAllThreeCards() {
        val found = CallActionSync.run(app, listOf(ira), "en", now, ZoneId.of("Europe/Kiev")) { _, _ -> 200 to reply(ira.key) }
        assertEquals(listOf(CallAction.PAYMENT, CallAction.CALLBACK, CallAction.REMINDER), found.map { it.type })
    }

    @Test fun keepAliveServiceIsDeclaredAsAShortForegroundService() {
        val pm = app.packageManager
        val si = pm.getPackageInfo(app.packageName, PackageManager.GET_SERVICES).services!!.first { it.name.endsWith("MwaKeepAliveService") }
        assertTrue(!si.exported)
        val perms = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(perms.contains("android.permission.FOREGROUND_SERVICE"))
    }

    @Test fun freezeProbeMeasuresGaps() {
        val p = FreezeProbe().start()
        Thread.sleep(700)
        assertTrue(p.stop() < FreezeProbe.FROZEN_MS)
    }

    @Test fun callDetailHasOpaqueStripsUnderTheSystemBars() {
        val c = org.robolectric.Robolectric.buildActivity(net.solardepin.solarchik.ui.CallsActivity::class.java).setup().get()
        val root = c.window.decorView
        assertTrue(root.findViewWithTag<View>("calls-status-scrim") != null)
        assertTrue(root.findViewWithTag<View>("calls-nav-scrim") != null)
    }
}
