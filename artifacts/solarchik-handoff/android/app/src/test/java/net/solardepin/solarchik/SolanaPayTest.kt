package net.solardepin.solarchik

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import net.solardepin.solarchik.circle.PayRequest
import net.solardepin.solarchik.circle.PayRequestStore
import net.solardepin.solarchik.circle.PayWatch
import net.solardepin.solarchik.circle.SolanaPay
import net.solardepin.solarchik.screen.CallAction
import net.solardepin.solarchik.screen.CallActionStore
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.stack.MorningStack
import net.solardepin.solarchik.ui.PayRequestSheet
import net.solardepin.solarchik.wallet.Base58
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 1.2.7 two-way Circle: Solana Pay transfer requests (https://docs.solanapay.com/spec) and their on-chain check. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class SolanaPayTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val owner = "8J3hQ1JZq8CkQ6CwRNrsdc9UHS1R1JZmE7vUfnTqC7ic"
    private val payer = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val mv = "mvines9iiHiQTysrwkJjGf2gb9Ex9jXJX8ns3qwf2kN"
    private val dir = File("/workspace/deliverables/redesign/compare-1.2.8/build").apply { mkdirs() }

    private val realCluster = System.getProperty("solarchik.cluster")
    private val realOnboarding = MainActivity.onboardingEnabled
    private val realTicker = MainActivity.tickerEnabled
    @org.junit.Before fun setUp() { System.setProperty("solarchik.cluster", "mainnet") } // as in the release build

    @After fun tearDown() { if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster); PayWatch.rpcForTest = null; PayRequestSheet.pollEnabled = true; MainActivity.tickerEnabled = realTicker; MainActivity.onboardingEnabled = realOnboarding }

    // ------------------------------------------------------------------ URL (spec examples)

    @Test fun urlMatchesTheSpecExamples() {
        // "solana:mvines9iiHiQTysrwkJjGf2gb9Ex9jXJX8ns3qwf2kN?amount=1&label=Michael&message=Thanks%20for%20all%20the%20fish&memo=OrderId12345"
        assertEquals("solana:$mv?amount=1&label=Michael&message=Thanks%20for%20all%20the%20fish&memo=OrderId12345",
            SolanaPay.url(mv, 1.0, "SOL", "", "Michael", "Thanks for all the fish", "OrderId12345"))
        // "solana:mvines9iiHiQTysrwkJjGf2gb9Ex9jXJX8ns3qwf2kN?amount=0.01&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        assertEquals("solana:$mv?amount=0.01&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", SolanaPay.url(mv, 0.01, "USDC", "", "", ""))
        // with a reference, in the spec's parameter order
        val ref = SolanaPay.newReference()
        assertEquals("solana:$owner?amount=0.5&spl-token=${SolanaPay.SKR}&reference=$ref&label=Sol&message=Deposit",
            SolanaPay.url(owner, 0.5, "SKR", ref, "Sol", "Deposit"))
    }

    @Test fun amountsAndEncoding() {
        assertEquals("0.000000001", SolanaPay.amountText(0.000000001, "SOL"))
        assertEquals("0.0001", SolanaPay.amountText(1e-4, "SOL"))
        assertEquals("100", SolanaPay.amountText(100.0, "USDC"))
        assertEquals("1.5", SolanaPay.amountText(1.50, "USDC"))
        assertTrue("more decimals than the mint has is invalid", runCatching { SolanaPay.amountText(0.0000001, "USDC") }.isFailure)
        assertTrue(runCatching { SolanaPay.amountText(0.0, "SOL") }.isFailure)
        assertEquals("Lunch%20%26%20coffee%3F%20%E2%80%94%20%D0%BE%D0%B1%D1%96%D0%B4", SolanaPay.encode("Lunch & coffee? — обід"))
        assertEquals("it's-ok_(1)!~*.", SolanaPay.encode("it's-ok_(1)!~*."))
        assertTrue(runCatching { SolanaPay.url("not-an-address", 1.0, "SOL", "", "", "") }.isFailure)
    }

    @Test fun referencesAreFreshValidPubkeys() {
        val a = SolanaPay.newReference(); val b = SolanaPay.newReference()
        assertNotEquals(a, b)
        assertEquals(32, Base58.decode(a).size)
    }

    // ------------------------------------------------------------------ validateTransfer

    private fun req(token: String, amount: Double, ref: String = REF) =
        PayRequest("p1", "a1", "Andrii", owner, amount, token, ref, "Deposit", "", 0L)

    private fun solTx(to: String = owner, pre: Long = 1_000_000_000, post: Long = 1_010_000_000, err: String = "null", withRef: Boolean = true): JsonElement = Json.parseToJsonElement("""
        {"slot":1,"meta":{"err":$err,"fee":5000,"preBalances":[500000000,$pre,1],"postBalances":[489995000,$post,1],"preTokenBalances":[],"postTokenBalances":[]},
         "transaction":{"message":{"accountKeys":[{"pubkey":"$payer","signer":true,"writable":true},{"pubkey":"$to","signer":false,"writable":true},
           {"pubkey":"${if (withRef) REF else "11111111111111111111111111111111"}","signer":false,"writable":false}]}}}""")

    private fun usdcTx(mint: String = SolanaPay.USDC, owner2: String = owner, pre: String = "2000000", post: String = "12000000"): JsonElement = Json.parseToJsonElement("""
        {"meta":{"err":null,"preBalances":[1,2,3,4],"postBalances":[1,2,3,4],
          "preTokenBalances":[{"accountIndex":2,"mint":"$mint","owner":"$owner2","uiTokenAmount":{"amount":"$pre","decimals":6}}],
          "postTokenBalances":[{"accountIndex":1,"mint":"$mint","owner":"$payer","uiTokenAmount":{"amount":"0","decimals":6}},{"accountIndex":2,"mint":"$mint","owner":"$owner2","uiTokenAmount":{"amount":"$post","decimals":6}}]},
         "transaction":{"message":{"accountKeys":["$payer","AtaPayer1111111111111111111111111111111111","AtaOwner1111111111111111111111111111111111","$REF"]}}}""")

    @Test fun validateTransferSemantics() {
        assertNull(SolanaPay.validate(solTx(), req("SOL", 0.01)))
        assertNull("more than asked is fine (as in @solana/pay)", SolanaPay.validate(solTx(post = 1_020_000_000), req("SOL", 0.01)))
        assertEquals("amount not transferred", SolanaPay.validate(solTx(post = 1_009_999_999), req("SOL", 0.01)))
        assertEquals("recipient not found", SolanaPay.validate(solTx(to = "So11111111111111111111111111111111111111112"), req("SOL", 0.01)))
        assertEquals("transaction failed", SolanaPay.validate(solTx(err = """{"InstructionError":[0,"Custom"]}"""), req("SOL", 0.01)))
        assertEquals("reference not found", SolanaPay.validate(solTx(withRef = false), req("SOL", 0.01)))
        // SPL: the owner's token account in the right mint gained >= amount (a brand-new account has no pre row)
        assertNull(SolanaPay.validate(usdcTx(), req("USDC", 10.0)))
        assertEquals("a balance that went down is no payment", "amount not transferred", SolanaPay.validate(usdcTx(pre = "999999999"), req("USDC", 10.0)))
        assertEquals("amount not transferred", SolanaPay.validate(usdcTx(post = "11999999"), req("USDC", 10.0)))
        assertEquals("recipient not found", SolanaPay.validate(usdcTx(mint = SolanaPay.SKR), req("USDC", 10.0)))
        assertEquals("recipient not found", SolanaPay.validate(usdcTx(owner2 = payer), req("USDC", 10.0)))
    }

    @Test fun findReferenceTakesTheOldestSignature() {
        val sigs = Json.parseToJsonElement("""[{"signature":"new"},{"signature":"mid"},{"signature":"old"}]""")
        assertEquals("old", SolanaPay.oldestSignature(sigs))
        assertNull(SolanaPay.oldestSignature(Json.parseToJsonElement("[]")))
    }

    private class FakeRpc(val sigs: String, val tx: JsonElement?) : Rpc("https://example.invalid") {
        val methods = mutableListOf<String>()
        override suspend fun call(method: String, params: JsonArray): JsonElement {
            methods += method
            return when (method) {
                "getSignaturesForAddress" -> Json.parseToJsonElement(sigs)
                "getTransaction" -> tx ?: kotlinx.serialization.json.JsonNull
                else -> error(method)
            }
        }
    }

    // ------------------------------------------------------------------ the app flow

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun walk(v: View, f: (View) -> Unit) { f(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f) }
    private fun find(root: View, tag: String): View? { var r: View? = null; walk(root) { if (r == null && it.tag == tag && it.visibility == View.VISIBLE) r = it }; return r }
    private fun texts(root: View): List<String> { val o = mutableListOf<String>(); walk(root) { if (it is TextView) o += it.text.toString() }; return o }
    private fun shot(root: View, name: String) {
        idle()
        val w = 1233; val h = 2742
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { root.draw(this) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** A bottom sheet's content at phone width on the dimmed app colour (the dialog window itself doesn't draw in Robolectric). */
    private fun sheetShot(content: View, name: String) {
        idle()
        val w = 1233
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, w, content.measuredHeight)
        val bmp = Bitmap.createBitmap(w, content.measuredHeight + 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFF03080F.toInt()); translate(0f, 200f); content.draw(this) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    @Test fun owesYouRequestShareDetectAndPaid() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        PayRequestSheet.pollEnabled = false
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", owner).putString("auth", "t").commit()
        // a manual "Owes you" line (as from "Andrii owes me…"); a call-made one works the same
        CallActionStore(app).add(listOf(CallAction("manual:x#0", "manual:x", CallAction.OWED, amount = 0.01, token = "SOL", recipient = "Andrii", text = "Deposit", source = CallAction.SOURCE_LOCAL)))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()

        // the Morning stack: "Andrii owes you 0.01 SOL" with Request
        val d0 = a.window.decorView
        assertNotNull(find(d0, "ca-owed"))
        assertTrue(texts(d0).toString(), texts(d0).contains("Andrii owes you 0.01 SOL"))
        assertEquals("Request 0.01 SOL", (find(d0, "stack-do") as TextView).text.toString().trim('\u2060', ' '))
        shot(d0, "21-today-owed-card")

        // Circle: Owes you hero with Request
        a.select(MainActivity.Tab.CIRCLE); idle()
        val c = a.window.decorView
        assertTrue(texts(c).toString(), texts(c).any { it.startsWith("Owes you") || it.equals("OWES YOU") })
        find(c, "circle-request")!!.performClick(); idle()
        assertNotNull("toast: " + org.robolectric.shadows.ShadowToast.getTextOfLatestToast() + " wallet=" + a.wallet.connected + "/" + a.wallet.mainnet, PayRequestSheet.lastRequest)
        val sheet = PayRequestSheet.last!!
        val r = PayRequestSheet.lastRequest!!
        val sv = sheet.window!!.decorView
        assertEquals("Ask Andrii for 0.01 SOL", (find(sv, "payreq-title") as TextView).text.toString())
        assertNotNull((find(sv, "payreq-qr") as ImageView).drawable)
        assertEquals("solana:$owner?amount=0.01&reference=${r.reference}&label=Sol&message=Deposit", r.url)
        sheetShot(find(sv, "payreq-sheet")!!, "22-circle-request-sheet")
        File(dir, "solana-pay-url.txt").writeText(r.url + "\n" + SolanaPay.url(owner, 10.0, "USDC", r.reference, "Sol", "From our call, Oct 10, 1:32 PM") + "\n")

        // Share: the chooser carries the short English line with the link
        find(sv, "payreq-share")!!.performClick(); idle()
        val chooser = generateSequence { shadowOf(a).nextStartedActivity }.first { it.action == Intent.ACTION_CHOOSER }
        val inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        val text = inner.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text, text.startsWith("Hi Andrii! Here's the Solana Pay link for the 0.01 SOL (Deposit)."))
        assertTrue(text.endsWith(r.url))

        // the solana: link as a wallet sees it: a VIEW intent with the solana scheme (Phantom handles it); our own app never claims it
        val uri = Uri.parse(r.url)
        assertEquals("solana", uri.scheme)
        assertEquals(owner, uri.schemeSpecificPart.substringBefore('?'))
        val view = Intent(Intent.ACTION_VIEW, uri)
        assertTrue("Solarchik must not intercept solana: links", app.packageManager.queryIntentActivities(view, 0).none { it.activityInfo.packageName == app.packageName })

        // the stack no longer shows the card once a request is out
        sheet.dismiss(); idle()
        assertTrue(MorningStack.items(app).none { it.id == "manual:x#0" })

        // not paid yet -> still open
        PayWatch.rpcForTest = FakeRpc("[]", null)
        assertTrue(runBlocking { PayWatch.checkAll(app, notify = false) }.isEmpty())
        assertEquals(PayRequest.OPEN, PayRequestStore(app).forAction("manual:x#0")!!.status)
        // a transfer that's 1 lamport short is not a payment
        PayWatch.rpcForTest = FakeRpc("""[{"signature":"short"}]""", Json.parseToJsonElement(solTx(post = 1_009_999_999).toString().replace(REF, r.reference)))
        assertTrue(runBlocking { PayWatch.checkAll(app, notify = false) }.isEmpty())
        // the real one: Paid ✓, the ledger line is done with its signature (Solscan), and the Circle shows it
        val fake = FakeRpc("""[{"signature":"5sig"}]""", Json.parseToJsonElement(solTx().toString().replace(REF, r.reference)))
        PayWatch.rpcForTest = fake
        val paid = runBlocking { PayWatch.checkAll(app, notify = false) }
        assertEquals(listOf("getSignaturesForAddress", "getTransaction"), fake.methods)
        assertEquals("5sig", paid.single().signature)
        assertEquals(PayRequest.PAID, PayRequestStore(app).forAction("manual:x#0")!!.status)
        val line = CallActionStore(app).find("manual:x#0")
        assertEquals(CallAction.DONE, line.status); assertEquals("5sig", line.signature)
        a.screen(MainActivity.Tab.CIRCLE)?.render(); idle()
        assertTrue(texts(a.window.decorView).toString(), texts(a.window.decorView).contains("Andrii paid you 0.01 SOL"))
        assertNull(find(a.window.decorView, "circle-request"))
        shot(a.window.decorView, "23-circle-got-paid")
    }

    @Test fun requestNeedsAMainnetWallet() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        CallActionStore(app).add(listOf(CallAction("manual:y#0", "manual:y", CallAction.OWED, amount = 2.0, token = "USDC", recipient = "Olena", source = CallAction.SOURCE_LOCAL)))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        a.select(MainActivity.Tab.CIRCLE); idle()
        find(a.window.decorView, "circle-request")!!.performClick(); idle()
        assertTrue(PayRequestStore(app).all().isEmpty())
        assertTrue("asks to connect a wallet first", PayRequestSheet.last is android.app.AlertDialog)
    }

    companion object { const val REF = "Ref1KsBeUZ8s4yLzTskRuCZTT6EuQXdRK7FjhbVqL2E" }
}
