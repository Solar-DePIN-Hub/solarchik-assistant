package net.solardepin.solarchik

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.AppData
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.screen.CallReports
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.screen.Secretary
import net.solardepin.solarchik.wallet.Base58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.robolectric.annotation.Config
import java.io.File

/** 0.20.5: call secretary without READ_CONTACTS, USDC top-up by Solana Pay reference, local call reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretaryTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        AppData.PREFS.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun noContactsOrTestPermissionsInTheApp() {
        val perms = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        assertFalse("android.permission.READ_CONTACTS" in perms)
        assertFalse("android.permission.REORDER_TASKS" in perms)
        // Ours: INTERNET, POST_NOTIFICATIONS, RECORD_AUDIO. The other four are WorkManager's (normal permissions).
        assertEquals(
            setOf("INTERNET", "POST_NOTIFICATIONS", "RECORD_AUDIO", "WAKE_LOCK", "ACCESS_NETWORK_STATE", "RECEIVE_BOOT_COMPLETED", "FOREGROUND_SERVICE")
                .map { "android.permission.$it" }.toSet(),
            perms.filter { it.startsWith("android.permission.") }.toSet(),
        )
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.telecom.CallScreeningService"))
        assertTrue(manifest.contains("android.permission.BIND_SCREENING_SERVICE"))
        assertFalse(File("src/main/java").walkTopDown().filter { it.extension == "kt" }.any { it.readText().contains("ContactsContract") })
    }

    @Test fun decisionNeedsIncomingEnabledAndAndroid10() {
        assertEquals(Secretary.Action.ALLOW, Secretary.decide(true, false, Secretary.Mode.SILENCE, 34))
        assertEquals(Secretary.Action.ALLOW, Secretary.decide(false, true, Secretary.Mode.SILENCE, 34))
        assertEquals(Secretary.Action.ALLOW, Secretary.decide(true, true, Secretary.Mode.DECLINE, 28))
        assertEquals(Secretary.Action.SILENCE, Secretary.decide(true, true, Secretary.Mode.SILENCE, 34))
        assertEquals(Secretary.Action.DECLINE, Secretary.decide(true, true, Secretary.Mode.DECLINE, 29))
        assertFalse(Secretary.supported(28))
        assertTrue(Secretary.supported(29))
    }

    @Test fun settingsDefaultToSilenceAndNoPaidNotes() {
        assertEquals(Secretary.Mode.SILENCE, Secretary.mode(ctx))
        assertFalse(Secretary.aiNotes(ctx))
        Secretary.setMode(ctx, Secretary.Mode.DECLINE)
        assertEquals(Secretary.Mode.DECLINE, Secretary.mode(ctx))
        ctx.getSharedPreferences("solarchik.secretary", Context.MODE_PRIVATE).edit().putString("mode", "junk").commit()
        assertEquals(Secretary.Mode.SILENCE, Secretary.mode(ctx))
        Secretary.setNeedTopup(ctx, true)
        Secretary.setLastUsd(ctx, 0.1)
        assertTrue("still below one note", Secretary.needTopup(ctx))
        Secretary.setLastUsd(ctx, 5.0)
        assertFalse(Secretary.needTopup(ctx))
        AppData.wipe(ctx)
        assertNull(Secretary.lastUsd(ctx))
    }

    @Test fun solanaPayUriMatchesTheWorkerContract() {
        val userId = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val ref = Secretary.newReference()
        assertEquals(32, Base58.decode(ref).size)
        val uri = Uri.parse(Secretary.payUri(userId, ref))
        assertEquals("solana", uri.scheme)
        assertEquals(Secretary.PAY_WALLET, uri.schemeSpecificPart.substringBefore('?'))
        val q = Uri.parse("x://y?" + uri.schemeSpecificPart.substringAfter('?'))
        assertEquals("5", q.getQueryParameter("amount"))
        assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", q.getQueryParameter("spl-token"))
        assertEquals(ref, q.getQueryParameter("reference"))
        assertEquals(userId.take(32), q.getQueryParameter("memo"))
        Secretary.setPendingRef(ctx, ref)
        assertEquals(ref, Secretary.pendingRef(ctx))
        Secretary.setPendingRef(ctx, null)
        assertNull(Secretary.pendingRef(ctx))
    }

    @Test fun topupResponsesMapToOutcomes() {
        assertEquals(ScreenApi.Topup.Credited(9.6, 5.0), ScreenApi.parseTopup(200, """{"userId":"u","usd":9.6,"added":5,"sig":"s"}"""))
        assertEquals(ScreenApi.Topup.AlreadyUsed, ScreenApi.parseTopup(409, """{"error":"ALREADY_USED"}"""))
        assertEquals(ScreenApi.Topup.NotFound, ScreenApi.parseTopup(402, """{"error":"PAYMENT_NOT_FOUND","detail":"no transaction with this reference yet"}"""))
        assertEquals(ScreenApi.Topup.Invalid("no_usdc_to_treasury"), ScreenApi.parseTopup(402, """{"error":"PAYMENT_INVALID","detail":"no_usdc_to_treasury"}"""))
        assertEquals(ScreenApi.Topup.Invalid("PAYMENT_REQUIRED"), ScreenApi.parseTopup(402, """{"error":"PAYMENT_REQUIRED"}"""))
        assertTrue(ScreenApi.parseTopup(503, """{"error":"RPC_UNAVAILABLE"}""") is ScreenApi.Topup.Failed)
        assertTrue(ScreenApi.parseTopup(0, "") is ScreenApi.Topup.Failed)
        assertTrue("200 without usd is not a credit", ScreenApi.parseTopup(200, "{}") is ScreenApi.Topup.Failed)
    }

    @Test fun screenBalanceAndInboxParsing() {
        val need = ScreenApi.parseScreen(402, """{"error":"NEED_TOPUP","usd":0}""")
        assertTrue(need.needTopup)
        assertFalse(need.ok)
        val ok = ScreenApi.parseScreen(200, """{"reply":"Who is calling?","summary":{"caller_name":"Bob","notes":"sales"},"chargedUsd":0.2,"usd":4.6}""")
        assertTrue(ok.ok)
        assertEquals("Bob", ok.summary.optString("caller_name"))
        assertEquals(4.6, ok.usd, 1e-9)
        assertFalse(ScreenApi.parseScreen(500, """{"error":"API_FAIL_REFUNDED"}""").ok)
        assertEquals(4.6, ScreenApi.parseBalance(200, """{"usd":4.6,"sessionUsd":0.2}""")!!, 1e-9)
        assertNull(ScreenApi.parseBalance(0, ""))
        val inbox = ScreenApi.parseInbox(200, """{"userId":"u","items":[{"caller":"+1555","text":"Call me back","at":5},{"caller":"x","text":"  "},7]}""")!!
        assertEquals(listOf(ScreenApi.Voicemail("+1555", "Call me back", 5)), inbox)
        assertEquals(emptyList<ScreenApi.Voicemail>(), ScreenApi.parseInbox(200, "{}"))
        assertNull(ScreenApi.parseInbox(500, ""))
    }

    @Test fun callReportsKeepNewestFirstAndUpdateInPlace() {
        repeat(CallReports.MAX + 5) { i ->
            CallReports.add(ctx, CallReports.Report("c$i", i.toLong(), "+1$i", "silenced", CallReports.STATUS_PENDING))
        }
        val all = CallReports.list(ctx)
        assertEquals(CallReports.MAX, all.size)
        assertEquals("c${CallReports.MAX + 4}", all.first().id)
        CallReports.update(ctx, "c10") { it.copy(status = CallReports.STATUS_DONE, note = "spam likely") }
        val r = CallReports.list(ctx).first { it.id == "c10" }
        assertEquals(CallReports.STATUS_DONE, r.status)
        assertEquals("spam likely", r.note)
        // Rows from 0.20.4 (summary object, needTopup flag) still read.
        ctx.getSharedPreferences("solarchik.desk", Context.MODE_PRIVATE).edit()
            .putString("reports", """[{"id":"sec-1","at":1,"user":"+380","summary":{"caller_name":"Ann","notes":"hi"},"needTopup":true},{"nope":1}]""").commit()
        val old = CallReports.list(ctx).single()
        assertEquals("+380", old.number)
        assertEquals("Ann", old.callerName)
        assertEquals(CallReports.STATUS_NEED_TOPUP, old.status)
    }

    @Test fun legacyArbNameStillResolves() {
        val (sku, tier) = Catalog.fromName("Titan × Backpack Pro")!!
        assertEquals("sku-dex-arb", sku.id)
        assertEquals(AgentTier.PRO, tier)
        assertEquals("sku-dex-arb", Catalog.fromName("Titan × Backpack")!!.first.id)
        assertEquals("Backpack SOL Desk", Catalog.baseOf("sku-dex-arb")!!.name)
    }

    // ---- 0.20.6: carrier call forwarding to the Sol secretary ----

    @Test fun forwardNumberDefaultsToTheSecretaryAndIsValidated() {
        assertEquals("+380914810885", Secretary.forwardNumber(ctx))
        assertEquals(Secretary.DEFAULT_FORWARD_NUMBER, Secretary.cleanNumber(Secretary.DEFAULT_FORWARD_NUMBER))
        assertEquals("+380441234567", Secretary.cleanNumber(" +380 (44) 123-45-67 "))
        assertEquals("+12025550123", Secretary.cleanNumber("0012025550123"))
        listOf("", "0441234567", "+0441234567", "+38044", "+1234567890123456", "+38044*123#", "+380+441234567", "tel:+380441234567", "+38o441234567")
            .forEach { assertNull(it, Secretary.cleanNumber(it)) }
        assertNull(Secretary.setForwardNumber(ctx, "12345"))
        assertEquals("invalid input keeps the old number", "+380914810885", Secretary.forwardNumber(ctx))
        assertEquals("+380441234567", Secretary.setForwardNumber(ctx, "+380 44 123 45 67"))
        assertEquals("+380441234567", Secretary.forwardNumber(ctx))
        assertEquals("", Secretary.setForwardNumber(ctx, "  "))
        assertEquals("cleared stays cleared", "", Secretary.forwardNumber(ctx))
        Secretary.setForwardNumber(ctx, "+380441234567")
        AppData.wipe(ctx)
        assertEquals("wipe restores the default", "+380914810885", Secretary.forwardNumber(ctx))
    }

    @Test fun gsmForwardingCodes() {
        val n = "+380441234567"
        assertEquals("**61*+380441234567#", Secretary.forwardOnCode(Secretary.Forward.NO_ANSWER, n))
        assertEquals("**67*+380441234567#", Secretary.forwardOnCode(Secretary.Forward.BUSY, n))
        assertEquals("**62*+380441234567#", Secretary.forwardOnCode(Secretary.Forward.UNREACHABLE, n))
        assertNull(Secretary.forwardOnCode(Secretary.Forward.BUSY, ""))
        assertEquals(listOf("##61#", "##67#", "##62#"), Secretary.Forward.values().map { Secretary.forwardOffCode(it) })
        assertEquals("##004#", Secretary.FORWARD_ALL_OFF)
        val uri = Secretary.dialUri("**61*+380441234567#")
        assertEquals("tel", uri.scheme)
        assertTrue("# must be escaped", uri.toString().endsWith("%23"))
        assertEquals("**61*+380441234567#", uri.schemeSpecificPart)
    }

    @Test fun noCallPhonePermission() {
        val perms = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        assertFalse("android.permission.CALL_PHONE" in perms)
    }

    @Test fun settingsForwardPanelVerifyThenCarrierCodesInTheDialerOnly() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        a.select(MainActivity.Tab.SETTINGS)
        val root = a.window.decorView
        assertTrue(texts(root).contains("Forward missed calls to Sol"))
        val tagged = { t: String -> all(root).first { it.tag == t } }
        tagged("fw-all").performClick()
        var dial = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("**004*+380914810885#", dial.data!!.schemeSpecificPart)
        tagged("fw-61").performClick()
        assertEquals("**61*+380914810885#", shadowOf(a).nextStartedActivity.data!!.schemeSpecificPart)
        tagged("fw-off").performClick()
        assertEquals("##004#", shadowOf(a).nextStartedActivity.data!!.schemeSpecificPart)
        // a number that is not a phone number is refused before anything goes to the server
        (tagged("fw-number") as EditText).setText("hello")
        tagged("fw-verify").performClick()
        assertEquals("", Secretary.ownNumber(ctx))
        assertTrue(texts(root).any { it.startsWith("Not verified yet") })
    }

    @Test fun callerHashMatchesTheWorker() {
        // worker: callerHash("+380501112233") (solarchik-screen.js, 1.2.6)
        assertEquals("ec40efff9208c9a5d9f5ab956a6a017c", Secretary.callerHash("+380501112233"))
        assertEquals("+380637443792", Secretary.e164("063 744 37 92"))
        assertEquals("+380637443792", Secretary.e164("+380 63 744-37-92"))
        assertEquals("", Secretary.e164("12"))
    }

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts(v: View): List<String> = all(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun find(v: View, text: String): View? = all(v).firstOrNull { it is TextView && it !is EditText && it.text.toString() == text }
}
