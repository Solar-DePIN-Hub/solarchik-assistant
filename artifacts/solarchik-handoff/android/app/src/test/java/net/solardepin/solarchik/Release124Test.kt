package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.screen.CallActionSync
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.ui.CallActionCards
import net.solardepin.solarchik.circle.CircleRules
import net.solardepin.solarchik.wallet.MwaDirect
import net.solardepin.solarchik.wallet.WalletDiag
import net.solardepin.solarchik.wallet.WalletError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** 1.2.4: Ira's pay card really appears for an existing call, duplicate cards go, old-language card text is not shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release124Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val zone = ZoneId.of("Europe/Kiev")
    // the exact summary of Vadym's 13:32 call (live /inbox, 2026-10-10)
    private val summary = "says they paid for lunch yesterday, asks Vadim to send them 0.01 SOL, and reminds him about their meeting on Monday"
    private fun item(id: String, who: String, intent: String, number: String, minsAgo: Long) =
        CallItem("me", id, number, "$who: $intent. Callback $number.", now - minsAgo * 60_000L, CallInbox.DONE, "screen", who, intent, "", "call back at 3", number, "en", 65, 0.0, false)
    private val ira = item("rtc_u2_EXOf3p3o", "Ira", summary, "+380637443792", 60)
    private val ira2 = item("rtc_u7_EXNNxp3o", "Ira", "They paid for lunch yesterday and are requesting 0.01 SOL.", "+380637443792", 120)
    private val vadim = item("rtc_u1_EXMyTp3o", "Vadim", "wants to say hi to their friend", "+380638500117", 200)
    private val vadim2 = item("rtc_u2_EXEyYp3o", "Vadim", "Wanted to just say hi to the owner.", "+380638500117", 600)

    @Test fun version() {
        assertEquals("1.2.5", BuildConfig.VERSION_NAME)
        assertEquals(125, BuildConfig.VERSION_CODE)
    }

    @Test fun theExactSummaryMakesAPayCardEvenOffline() {
        val pay = CallActionRules.local(ira).single { it.payment }
        assertEquals(0.01, pay.amount, 1e-9)
        assertEquals("SOL", pay.token)
        assertEquals("Ira", pay.recipient)
        assertEquals("", pay.saidAddress)
    }

    @Test fun upgradeAddsTheMissingPayCardAndDropsDuplicateCallbacks() {
        val store = CallActionStore(app)
        app.getSharedPreferences(CallActionStore.PREFS, Context.MODE_PRIVATE).edit().putInt("rules", 2).commit()
        // as on the tablet after 1.2.3: call-back cards for every call, twice for some, no payment
        val old = listOf(ira, ira, ira2, vadim, vadim, vadim2).mapIndexed { i, c ->
            CallAction(c.key + "#" + i, c.key, CallAction.CALLBACK, number = c.caller, time = "15:00", text = "Call ${c.who} back")
        } + CallAction(ira.key + "#9", ira.key, CallAction.REMINDER, text = "Remind about the meeting on Monday", date = "2026-10-12")
        val raw = app.getSharedPreferences(CallActionStore.PREFS, Context.MODE_PRIVATE)
        // write without dedupe, as 1.2.3 stored them
        val arr = org.json.JSONArray(); old.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("callKey", it.callKey).put("type", it.type).put("number", it.number).put("time", it.time).put("text", it.text).put("date", it.date).put("status", it.status)) }
        raw.edit().putString("actions", arr.toString()).putStringSet("processed", listOf(ira, ira2, vadim, vadim2).map { it.key }.toSet()).commit()
        assertEquals(7, store.all().size)

        // the worker is unreachable: the local rules still find the payment in the exact summary
        val found = CallActionSync.run(app, listOf(ira, ira2, vadim, vadim2), "en", now, zone) { _, _ -> 503 to "" }
        assertTrue(found.toString(), found.any { it.payment && it.callKey == ira.key && it.amount == 0.01 })
        val all = CallActionStore(app).all()
        assertEquals(1, all.count { it.payment && it.callKey == ira.key })
        // one open call-back per number
        assertEquals(1, all.count { it.type == CallAction.CALLBACK && it.number == "+380637443792" })
        assertEquals(1, all.count { it.type == CallAction.CALLBACK && it.number == "+380638500117" })
        assertEquals(1, all.count { it.type == CallAction.REMINDER })
    }

    @Test fun anOpenPaymentIsShownBeforeCallbacksOnToday() {
        val store = CallActionStore(app)
        store.upgradeRules(CallActionSync.RULES)
        CallInbox.store(app, listOf(ira, vadim))
        store.add(listOf(CallAction(ira.key + "#p", ira.key, CallAction.PAYMENT, amount = 0.01, token = "SOL", recipient = "Ira")))
        store.add((1..5).map { CallAction(vadim.key + "#c$it", vadim.key + it, CallAction.CALLBACK, number = "+38050000000$it") })
        store.markProcessed(listOf(ira.key, vadim.key))
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        try {
            val a = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
            repeat(5) { org.robolectric.shadows.ShadowLooper.idleMainLooper() }
            val box = a.window.decorView.findViewWithTag<android.view.ViewGroup>("today-actions")
            val first = (box.getChildAt(1) as android.view.ViewGroup).getChildAt(0)
            assertEquals("ca-payment", first.tag)
        } finally { MainActivity.tickerEnabled = true }
    }

    @Test fun cardTextInAnotherLanguageIsNotShown() {
        assertEquals("", CallActionCards.inUi("Вадим Хотел передать привет Коллеге"))
        assertEquals("Remind about the meeting on Monday", CallActionCards.inUi("Remind about the meeting on Monday"))
        val a = CallAction("x", ira.key, CallAction.CALLBACK, quote = "Вадим: Хотів передати просто привіт власнику.")
        assertEquals(ira.intent, CallActionCards.quote(a, ira))
    }

    @Test fun logcatIsOneLinePerFailedDial() {
        val lines = listOf(
            "10-10 16:31:48.292 V/MobileWalletAdapterWebSocket(23395): connect",
            "10-10 16:31:48.497 W/LocalAssociationScenario(23395): Failed establishing a WebSocket connection",
            "10-10 16:31:48.497 W/LocalAssociationScenario(23395): com.neovisionaries.ws.client.WebSocketException: Failed to connect to '127.0.0.1:59349': failed to connect",
            "10-10 16:31:48.497 W/LocalAssociationScenario(23395): \tat com.neovisionaries.ws.client.SocketConnector.doConnect(SocketConnector.java:126)",
            "10-10 16:31:48.497 W/LocalAssociationScenario(23395): Caused by: java.net.SocketTimeoutException: failed to connect after 200ms",
            "10-10 16:31:48.497 W/LocalAssociationScenario(23395): \t... 5 more",
            "10-10 16:31:48.498 D/LocalAssociationScenario(23395): Connect attempt failed, retrying in 1000 ms",
            "10-10 16:31:49.505 W/LocalAssociationScenario(23395): com.neovisionaries.ws.client.WebSocketException: Failed to connect to '127.0.0.1:59349'",
            "10-10 16:31:49.505 W/LocalAssociationScenario(23395): Caused by: android.system.ErrnoException: connect failed: ECONNREFUSED (Connection refused)",
            "10-10 16:31:50.600 I/MobileWalletAdapterClient(23395): could not parse session properties, falling back on legacy session",
        )
        val out = WalletDiag.compactLogcat(lines)
        assertEquals(listOf(
            "16:31:48.497 ws dial failed: SocketTimeoutException",
            "16:31:49.505 ws dial failed: ECONNREFUSED",
            "16:31:50.600 MobileWalletAdapterClient: could not parse session properties, falling back on legacy session",
        ), out)
    }

    @Test fun aRefusedTokenWithAClosedSessionAsksForANewSession() {
        val e = WalletError(WalletError.Kind.FAILED, MwaDirect.NEW_SESSION + ": closed", authRejected = true)
        assertTrue(e.message!!.startsWith(MwaDirect.NEW_SESSION))
        assertTrue(e.authRejected)
    }

    // ---------------- Circle
    private val addr = "So11111111111111111111111111111111111111112"
    private fun iraPay() = CallAction(ira.key + "#p", ira.key, CallAction.PAYMENT, amount = 0.01, token = "SOL", recipient = "Ira")

    @Test fun circleMatchesByNormalizedPhoneThenName() {
        val list = listOf(net.solardepin.solarchik.circle.Contact("1", "Iryna K.", "063 744 37 92", addr), net.solardepin.solarchik.circle.Contact("2", "Petro", "", ""))
        assertEquals("1", net.solardepin.solarchik.circle.Circle.match(list, "Someone", "+380637443792")?.id)
        assertEquals("2", net.solardepin.solarchik.circle.Circle.match(list, " petro ", "")?.id)
        assertEquals("1", net.solardepin.solarchik.circle.Circle.match(list, "Iryna", "")?.id) // first word
        assertEquals(null, net.solardepin.solarchik.circle.Circle.match(list, "Olena", "+380501112233"))
        assertEquals(addr, net.solardepin.solarchik.circle.Circle.parseAddress("solana:$addr?amount=0.01"))
        assertEquals("", net.solardepin.solarchik.circle.Circle.parseAddress("not an address"))
    }

    @Test fun irasCallIsADebtAndSettledAfterAConfirmedSend() {
        CallInbox.store(app, listOf(ira))
        val c = net.solardepin.solarchik.circle.CircleStore(app).put(net.solardepin.solarchik.circle.Contact("", "Ira", "+380637443792", addr))
        CallActionStore(app).add(listOf(iraPay()))
        var d = net.solardepin.solarchik.circle.Circle.current(app).single()
        assertTrue(d.open)
        assertEquals("Ira", d.who)
        assertEquals(c.id, d.contact?.id)
        assertEquals(mapOf("ira" to mapOf("SOL" to 0.01)), net.solardepin.solarchik.circle.Circle.owedTo(listOf(d)))
        assertEquals(listOf("You owe Ira 0.01 SOL from today's call."), net.solardepin.solarchik.circle.Circle.briefLines(app, listOf(d), now, zone))
        assertEquals(listOf("You owe Ira 0.01 SOL from yesterday's call."), net.solardepin.solarchik.circle.Circle.briefLines(app, listOf(d), now + 86_400_000L, zone))
        CallActionStore(app).update(iraPay().id) { it.copy(status = CallAction.DONE, signature = "5".repeat(88)) }
        d = net.solardepin.solarchik.circle.Circle.current(app).single()
        assertTrue(d.settled)
        assertEquals("https://solscan.io/tx/" + "5".repeat(88), net.solardepin.solarchik.circle.Circle.solscan(d.action.signature))
        assertTrue(net.solardepin.solarchik.circle.Circle.briefLines(app, listOf(d), now, zone).isEmpty())
    }

    @Test fun solAnswersFromTheLedger() {
        CallInbox.store(app, listOf(ira))
        CallActionStore(app).add(listOf(iraPay()))
        val debts = net.solardepin.solarchik.circle.Circle.current(app)
        assertEquals(CircleRules.Intent.WhoOwe, CircleRules.intent("Who do I owe?", debts))
        assertEquals(CircleRules.Intent.WhoOwe, CircleRules.intent("кому я винен?", debts))
        assertEquals("Going by your calls, you owe: Ira 0.01 SOL.", CircleRules.whoOweText(app, debts))
        assertEquals(iraPay().id, (CircleRules.intent("Send Ira what I owe", debts) as CircleRules.Intent.Pay).debt?.action?.id)
        assertEquals(iraPay().id, (CircleRules.intent("pay Ira back", debts) as CircleRules.Intent.Pay).debt?.action?.id)
        assertEquals(iraPay().id, (CircleRules.intent("надішли Ірі борг", listOf(debts.single().copy(who = "Іра"))) as CircleRules.Intent.Pay).debt?.action?.id)
        val petro = CircleRules.intent("Send Petro what I owe", debts) as CircleRules.Intent.Pay
        assertEquals(null, petro.debt)
        assertEquals("Petro", petro.name)
        assertEquals(null, CircleRules.intent("what's the weather", debts))
        assertEquals("You don't owe anyone right now, going by your calls.", CircleRules.whoOweText(app, emptyList()))
    }

    private fun todayWith(contact: net.solardepin.solarchik.circle.Contact?): android.view.View {
        CallActionStore(app).upgradeRules(CallActionSync.RULES)
        CallInbox.store(app, listOf(ira))
        CallActionStore(app).add(listOf(iraPay()))
        CallActionStore(app).markProcessed(listOf(ira.key))
        contact?.let { net.solardepin.solarchik.circle.CircleStore(app).put(it) }
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        val a = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        repeat(5) { org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        return a.window.decorView
    }

    @Test fun payCardWithAContactSettlesWithOneConfirm() {
        try {
            val d = todayWith(net.solardepin.solarchik.circle.Contact("", "Ira", "0637443792", addr))
            val settle = d.findViewWithTag<android.widget.TextView>("circle-settle")
            assertTrue(settle.text.toString(), settle.text.toString().endsWith("Settle: send 0.01 SOL to Ira"))
            assertTrue(d.findViewWithTag<android.view.View>("circle-add-wallet") == null)
            settle.performClick(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dlg = CallActionCards.lastSheet!!
            assertTrue(dlg.isShowing)
            val msg = dlg.findViewById<android.widget.TextView>(android.R.id.message).text.toString()
            assertTrue(msg, msg.contains("0.01 SOL") && msg.contains(addr))
            dlg.dismiss()
        } finally { MainActivity.tickerEnabled = true }
    }

    @Test fun payCardWithoutAContactOffersAddWalletPrefilled() {
        try {
            val d = todayWith(null)
            val add = d.findViewWithTag<android.widget.TextView>("circle-add-wallet")
            assertTrue(add.text.toString(), add.text.toString().endsWith("Add Ira's wallet"))
            assertEquals("Ask Ira for wallet", d.findViewWithTag<android.widget.TextView>("circle-ask").text.toString())
            add.performClick(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val form = net.solardepin.solarchik.ui.CirclePanel.lastForm!!
            val root = form.window!!.decorView
            assertEquals("Ira", root.findViewWithTag<android.widget.EditText>("circle-f-name").text.toString())
            assertEquals("+380637443792", root.findViewWithTag<android.widget.EditText>("circle-f-phone").text.toString())
            assertEquals("", root.findViewWithTag<android.widget.EditText>("circle-f-address").text.toString()) // never from the call
            root.findViewWithTag<android.widget.EditText>("circle-f-address").setText("solana:$addr")
            form.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val saved = net.solardepin.solarchik.circle.CircleStore(app).all().single()
            assertEquals(addr, saved.address)
            // back on the card: the transfer confirm opens right away
            assertTrue(CallActionCards.lastSheet!!.isShowing)
            CallActionCards.lastSheet!!.dismiss()
        } finally { MainActivity.tickerEnabled = true }
    }

    @Test fun askTextHasNoAddressAndTheAmount() {
        val t = net.solardepin.solarchik.circle.Circle.askText(app, "Ira", net.solardepin.solarchik.circle.Debt(iraPay(), ira, null, "Ira"))
        assertTrue(t, t.contains("0.01 SOL") && t.contains("solana:YOUR_ADDRESS?amount=0.01"))
    }
}
