package net.solardepin.solarchik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.solardepin.solarchik.agents.MintError
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.season.SeasonItem
import net.solardepin.solarchik.season.SeasonStore
import net.solardepin.solarchik.swap.JupiterApi
import net.solardepin.solarchik.swap.SwapBlock
import net.solardepin.solarchik.swap.SwapBuild
import net.solardepin.solarchik.swap.SwapDesk
import net.solardepin.solarchik.swap.SwapException
import net.solardepin.solarchik.swap.SwapGuard
import net.solardepin.solarchik.swap.SwapPolicy
import net.solardepin.solarchik.swap.SwapQuote
import net.solardepin.solarchik.swap.SwapRecord
import net.solardepin.solarchik.swap.SwapRequest
import net.solardepin.solarchik.swap.SwapStore
import net.solardepin.solarchik.swap.SwapToken
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.swap.DcaAgent
import net.solardepin.solarchik.swap.TxCheck
import net.solardepin.solarchik.ui.AgentsScreen
import net.solardepin.solarchik.ui.SettingsScreen
import net.solardepin.solarchik.ui.TodayScreen
import net.solardepin.solarchik.wallet.Base58
import net.solardepin.solarchik.wallet.LocalKey
import net.solardepin.solarchik.wallet.MemoTx
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate

/**
 * 1.1.0 Solana mainnet: cluster config + hidden dev devnet toggle, MWA only on mainnet (no hot wallet, no
 * faucet), check-in memo build, Jupiter swap rules (allowlist, daily cap, slippage, impact, signer check),
 * the swap desk with a scripted Jupiter, mainnet mint "coming soon" (combo stays paid-only), Season counting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h914dp-xxhdpi")
class MainnetTest {
    private val outDir = File(System.getProperty("solarchik.shots") ?: "build/screens", "1.1.0").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val realCheck = SolanaWallet.walletAppCheck
    private val realOnboarding = MainActivity.onboardingEnabled
    private val realCluster = System.getProperty("solarchik.cluster")
    private val owner = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"

    @Before fun setUp() {
        MainActivity.tickerEnabled = false
        MainActivity.onboardingEnabled = false
        SolanaWallet.walletAppCheck = { true }
        LocalKey.box = TestBox()
        // mainnet as in the release build (the suite default is dev devnet)
        System.setProperty("solarchik.cluster", "mainnet")
        listOf("solarchik-agents", "solarchik-desk", "solarchik-sol", "seeker-wallet", "solarchik-local-wallet", "solarchik.calls",
            "solarchik.followups", "solarchik.season", "solarchik.swap", MainActivity.ASSISTANT_PREFS, "solarchik-game")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun tearDown() {
        MainActivity.tickerEnabled = true
        MainActivity.onboardingEnabled = realOnboarding
        SolanaWallet.walletAppCheck = realCheck
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
    }

    private fun idle() = repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { idle() }
    private fun connectAs(addr: String) = app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("address", addr).putString("auth", "t").commit()

    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun shot(root: View, name: String, scrollToTag: String? = null) {
        idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val scroll = findScroll(root)
        if (scroll != null && scrollToTag != null) {
            var y = 0; var cur: View? = find(scroll, scrollToTag)
            while (cur != null && cur !== scroll) { y += cur.top; cur = cur.parent as? View }
            scroll.scrollTo(0, (y - 160).coerceAtLeast(0))
        }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun res(name: String): String = javaClass.classLoader!!.getResource("mainnet/$name")!!.readText()

    // ------------------------------------------------------------------ cluster + wallet

    @Test fun releaseDefaultsToMainnetWithHiddenDevnetToggle() {
        assertFalse("1.1.0 is not devnet-only", BuildConfig.DEVNET_ONLY)
        assertFalse("mainnet mints wait for the collection", BuildConfig.MAINNET_MINT_READY)
        val w = SolanaWallet(app)
        assertTrue(w.mainnet)
        assertEquals("mainnet", w.clusterName)
        assertEquals(SolarchikConfig.RPC_MAINNET, w.rpcUrl)
        assertEquals("https://solscan.io/tx/SIG", w.txUrl("SIG"))
        assertEquals("https://solscan.io/account/$owner", w.accountUrl(owner))
        w.forceDevnet = true
        assertFalse(w.mainnet)
        assertEquals(SolarchikConfig.RPC_DEVNET, w.rpcUrl)
        assertEquals("https://solscan.io/tx/SIG?cluster=devnet", w.txUrl("SIG"))
        w.forceDevnet = false
        assertTrue(SolanaWallet(app).mainnet)
        assertEquals(SolarchikConfig.RPC_MAINNET_FALLBACK, net.solardepin.solarchik.solana.Rpc.fallbackFor(SolarchikConfig.RPC_MAINNET))
    }

    @Test fun mainnetHasNoHotWalletAndNoFaucet() = runBlocking {
        val w = SolanaWallet(app)
        LocalKey.create(app)
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("kind", SolanaWallet.KIND_LOCAL).putString("address", LocalKey.address(app)).commit()
        assertFalse("a saved built-in key is ignored on mainnet", w.isLocal)
        assertTrue(runCatching { w.useBuiltIn() }.isFailure)
        assertTrue("faucet refused on mainnet", w.airdrop().isFailure)
        var offered = false
        w.offerBuiltIn = { offered = true; true }
        assertTrue(w.ensureLocalFunds(1_000_000L))
        assertFalse("the built-in wallet is never offered on mainnet", offered)
        // dev devnet mode brings the built-in key back
        w.forceDevnet = true
        assertTrue(w.isLocal)
    }

    @Test fun checkInMemoIsOneSignerMemoTxForTheWallet() {
        val memo = MemoTx.clockMemo("2026-10-09", 1234, 3)
        assertTrue(memo, memo.startsWith("solarchik clock 2026-10-09 1234m s3 "))
        assertTrue(memo.endsWith(" " + GameSave.dayModOf("2026-10-09")))
        val payer = Base58.decode(owner)
        val raw = MemoTx.build(payer, ByteArray(32) { 7 }, memo)
        val h = TxCheck.header(raw)
        assertFalse("legacy memo tx", h.versioned)
        assertEquals(1, h.requiredSigners)
        assertEquals(owner, h.feePayer)
        assertNull(TxCheck.problem(raw, owner))
        assertTrue(String(raw, Charsets.ISO_8859_1).contains(memo))
        assertTrue("memo program in the account list", raw.toList().windowed(32).any { it.toByteArray().contentEquals(Base58.decode(SolarchikConfig.MEMO_PROGRAM)) })
        assertTrue("well under the 1232-byte limit", raw.size < 300)
    }

    // ------------------------------------------------------------------ swap rules

    private val live = SwapPolicy(enabled = true, riskAcceptedAt = 1L)
    private fun req(from: SwapToken = SwapTokens.SOL, to: SwapToken = SwapTokens.USDC, sol: Double = 0.01) = SwapRequest(from, to, from.toRaw(sol))

    @Test fun swapsAreOffUntilOptInAndOnlyOnMainnet() {
        assertFalse(SwapPolicy().live)
        assertEquals(0.05, SwapPolicy().dayCapSol, 0.0)
        val off = SwapGuard.precheck(SwapPolicy(), emptyList(), "d", req(), 0.01, mainnet = true)
        assertTrue(off.containsAll(listOf(SwapBlock.OFF, SwapBlock.RISK_NOT_ACCEPTED)))
        assertEquals(listOf(SwapBlock.NOT_MAINNET), SwapGuard.precheck(live, emptyList(), "d", req(), 0.01, mainnet = false))
        assertTrue(SwapGuard.precheck(live, emptyList(), "d", req(), 0.01, mainnet = true).isEmpty())
    }

    @Test fun allowlistOnlySolUsdcSkrJup() {
        assertEquals(listOf("SOL", "USDC", "SKR", "JUP"), SwapTokens.ALLOWLIST.map { it.symbol })
        val bonk = SwapToken("BONK", "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", 5)
        assertTrue(SwapBlock.TOKEN_NOT_ALLOWED in SwapGuard.precheck(live, emptyList(), "d", SwapRequest(SwapTokens.SOL, bonk, 1000), 0.001, true))
        assertTrue(SwapBlock.SAME_TOKEN in SwapGuard.precheck(live, emptyList(), "d", req(to = SwapTokens.SOL), 0.01, true))
        assertTrue(SwapBlock.ZERO_AMOUNT in SwapGuard.precheck(live, emptyList(), "d", SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 0), 0.0, true))
        assertEquals(1_000_000L, SwapTokens.USDC.toRaw(1.0))
        assertEquals(10_000_000L, SwapTokens.SOL.toRaw(0.01))
        assertEquals(0.123456, SwapTokens.SKR.fromRaw(123_456), 0.0)
    }

    @Test fun dailyCapCountsSentAndConfirmedButNotFailed() {
        fun rec(v: Double, status: String = SwapRecord.STATUS_CONFIRMED, day: String = "2026-10-09") = SwapRecord(0, day, "s$v$status", "SOL", "USDC", 1, 1, v, status)
        val recs = listOf(rec(0.02), rec(0.02, SwapRecord.STATUS_SENT), rec(0.04, SwapRecord.STATUS_FAILED), rec(0.05, day = "2026-10-08"))
        assertEquals(0.04, SwapGuard.spentToday(recs, "2026-10-09"), 1e-12)
        assertEquals(0.01, SwapGuard.remaining(live, recs, "2026-10-09"), 1e-12)
        assertTrue(SwapGuard.precheck(live, recs, "2026-10-09", req(sol = 0.01), 0.01, true).isEmpty())
        assertEquals(listOf(SwapBlock.OVER_DAY_CAP), SwapGuard.precheck(live, recs, "2026-10-09", req(sol = 0.011), 0.011, true))
        // a new day starts from zero
        assertTrue(SwapGuard.precheck(live, recs, "2026-10-10", req(sol = 0.05), 0.05, true).isEmpty())
        // the user can't raise the cap above the hard ceiling, nor slippage above 3%
        val wild = SwapPolicy(true, 1L, dayCapSol = 50.0, maxSlippageBps = 5000).clamped()
        assertEquals(SwapPolicy.HARD_DAY_CAP_SOL, wild.dayCapSol, 0.0)
        assertEquals(SwapPolicy.HARD_SLIPPAGE_BPS, wild.maxSlippageBps)
    }

    @Test fun quoteChecksSlippageImpactAndMatch() {
        val q = JupiterApi.parseQuote(res("quote_sol_usdc.json"))
        val r = SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 1_000_000)
        assertEquals(SwapTokens.SOL.mint, q.inputMint)
        assertEquals(1_000_000L, q.inAmount)
        assertTrue(q.outAmount > 0 && q.minOut in 1..q.outAmount)
        assertEquals(50, q.slippageBps)
        assertTrue(q.routeLabels.isNotEmpty())
        assertTrue(SwapGuard.checkQuote(live, r, q).isEmpty())
        assertTrue(SwapBlock.SLIPPAGE_TOO_HIGH in SwapGuard.checkQuote(live.copy(maxSlippageBps = 30), r, q))
        assertTrue(SwapBlock.PRICE_IMPACT_TOO_HIGH in SwapGuard.checkQuote(live, r, q.copy(priceImpactPct = 1.5)))
        assertTrue(SwapBlock.QUOTE_MISMATCH in SwapGuard.checkQuote(live, r.copy(amountRaw = 2_000_000), q))
        assertTrue(SwapBlock.QUOTE_MISMATCH in SwapGuard.checkQuote(live, r, q.copy(outputMint = SwapTokens.JUP.mint)))
        assertTrue(SwapGuard.requoteOk(1000, 996))
        assertFalse(SwapGuard.requoteOk(1000, 990))
    }

    @Test fun jupiterSwapTxIsSignedOnlyByTheUser() {
        val b = JupiterApi.parseSwap(res("swap_sol_usdc.json"))
        val h = TxCheck.header(b.tx)
        assertTrue("Jupiter builds a v0 transaction", h.versioned)
        assertEquals(1, h.requiredSigners)
        assertEquals(owner, h.feePayer)
        assertNull(TxCheck.problem(b.tx, owner))
        assertEquals("fee payer is not your wallet", TxCheck.problem(b.tx, "So11111111111111111111111111111111111111112"))
        assertNull(b.simulationError)
        assertTrue(b.priorityFeeLamports in 0..100_000)
        assertNotNull(TxCheck.problem(ByteArray(40), owner))
    }

    /** A scripted Jupiter: the captured live answers, no network. */
    private inner class FakeJup(val quote: String = res("quote_sol_usdc.json"), val swap: String = res("swap_sol_usdc.json")) : JupiterApi() {
        var quotes = 0
        var swaps = 0
        override suspend fun quote(from: SwapToken, to: SwapToken, amountRaw: Long, slippageBps: Int, maxAccounts: Int): SwapQuote {
            quotes++
            return parseQuote(quote).copy(inputMint = from.mint, outputMint = to.mint, inAmount = amountRaw, slippageBps = slippageBps)
        }
        override suspend fun swapTx(quote: SwapQuote, owner: String, destinationTokenAccount: String?, nativeDestination: String?): SwapBuild { swaps++; return parseSwap(swap) }
    }

    private class NoTokenRpc : net.solardepin.solarchik.solana.Rpc("test") {
        override suspend fun call(method: String, params: kotlinx.serialization.json.JsonArray): kotlinx.serialization.json.JsonElement =
            kotlinx.serialization.json.Json.parseToJsonElement("""{"context":{"slot":1},"value":[]}""")
    }

    @Test fun swapDeskPreparesReviewWithinLimits() = runBlocking {
        connectAs(owner)
        val w = SolanaWallet(app)
        val jup = FakeJup()
        val desk = SwapDesk(app, jup, today = { "2026-10-09" })
        // off by default: nothing reaches Jupiter
        val off = desk.prepare(w, SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 1_000_000), NoTokenRpc())
        assertTrue((off.exceptionOrNull() as SwapException).blocks.contains(SwapBlock.OFF))
        assertEquals(0, jup.quotes)
        desk.store.setPolicy(live)
        val p = desk.prepare(w, SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 1_000_000), NoTokenRpc()).getOrThrow()
        assertEquals(0.001, p.solValue, 1e-12)
        assertEquals(owner, p.owner)
        assertEquals(SwapPolicy.DEFAULT_SLIPPAGE_BPS, p.quote.slippageBps)
        assertEquals(5_000L, p.fees.baseFeeLamports)
        assertEquals("no USDC account yet: one-time rent shown", 2_039_280L, p.fees.ataRentLamports)
        // over the cap: refused before any quote
        val before = jup.quotes
        val big = desk.prepare(w, SwapRequest(SwapTokens.SOL, SwapTokens.USDC, SwapTokens.SOL.toRaw(0.06)), NoTokenRpc())
        assertTrue((big.exceptionOrNull() as SwapException).blocks.contains(SwapBlock.OVER_DAY_CAP))
        assertEquals(before, jup.quotes)
        // a tx paid by someone else never reaches the wallet
        connectAs("So11111111111111111111111111111111111111112")
        val other = desk.prepare(SolanaWallet(app), SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 1_000_000), NoTokenRpc())
        assertTrue((other.exceptionOrNull() as SwapException).blocks.contains(SwapBlock.BAD_TX))
        // a failed Jupiter simulation is refused
        connectAs(owner)
        val simFail = res("swap_sol_usdc.json").replace("\"simulationError\":null", "\"simulationError\":{\"errorCode\":\"INSUFFICIENT_FUNDS\",\"error\":\"insufficient lamports\"}")
        val sf = SwapDesk(app, FakeJup(swap = simFail), today = { "2026-10-09" }).prepare(SolanaWallet(app), SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 1_000_000), NoTokenRpc())
        assertTrue((sf.exceptionOrNull() as SwapException).blocks.contains(SwapBlock.SIMULATION_FAILED))
    }

    @Test fun dcaAgentProposesOncePerDayWithinCap() {
        var day = "2026-10-09"
        val desk = SwapDesk(app, FakeJup(), today = { day })
        assertNull("off by default", desk.dcaProposal())
        desk.store.setDca(DcaAgent(enabled = true, to = "SKR", amountSol = 0.02))
        assertNull("real swaps still off", desk.dcaProposal())
        desk.store.setPolicy(live)
        val p = desk.dcaProposal()!!
        assertEquals(SwapTokens.SKR, p.to)
        assertEquals(SwapTokens.SOL.toRaw(0.02), p.amountRaw)
        assertEquals("dca", p.by)
        desk.store.add(SwapRecord(0, day, "x", "SOL", "USDC", 1, 1, 0.04))
        assertEquals("trimmed to what is left under the cap", SwapTokens.SOL.toRaw(0.01), desk.dcaProposal()!!.amountRaw)
        desk.dcaHandled()
        assertNull("one proposal per day", desk.dcaProposal())
        day = "2026-10-10"
        assertNotNull(desk.dcaProposal())
        desk.store.setDca(DcaAgent(enabled = true, to = "BONK"))
        assertNull("never outside the allowlist", desk.dcaProposal())
    }

    // ------------------------------------------------------------------ mint + season

    @Test fun mainnetMintIsComingSoonAndComboStaysPaidOnly() {
        connectAs(owner)
        val a = launch()
        val combo = Catalog.skus.first { it.paidOnly }
        val free = Catalog.skus.first { !it.paidOnly }
        assertEquals(MintError.Kind.PAID_ONLY, a.minter.canMint(combo, AgentTier.FREE))
        assertEquals(MintError.Kind.MAINNET_SOON, a.minter.canMint(free, AgentTier.FREE))
        assertEquals(MintError.Kind.MAINNET_SOON, a.minter.canMint(free, AgentTier.PRO))
        assertTrue("paper agents run without an NFT meanwhile", a.paperOpen)
        a.select(MainActivity.Tab.AGENTS)
        (a.screen(MainActivity.Tab.AGENTS) as AgentsScreen).openSection(AgentsScreen.SEASON); idle()
        val all = texts(a.window.decorView)
        assertTrue(all.filter { it.contains("mint", true) }.toString(), all.any { it.trim().endsWith("Mainnet mint: coming soon") })
        shot(a.window.decorView, "06-mint-coming-soon", "mint-soon")
    }

    @Test fun seasonCountsOnlyRealMainnetActions() {
        val save = GameSave(app)
        val day = LocalDate.now()
        assertFalse(SeasonStore.planFor(app, save, mainnet = true, day = day).done(SeasonItem.ONCHAIN))
        save.recordRun(1300, 10)
        save.stampClock(owner, "sig", "mainnet", "message", save.today(), 1300)
        val sigOnly = SeasonStore.planFor(app, save, mainnet = true, day = day)
        assertTrue(sigOnly.signedToday)
        assertFalse("a detached signature is not a mainnet tx", sigOnly.done(SeasonItem.ONCHAIN))
        SeasonStore.markOnchain(app, "swap", day)
        val swapped = SeasonStore.planFor(app, save, mainnet = true, day = day)
        assertTrue(swapped.done(SeasonItem.ONCHAIN))
        assertEquals("swap", swapped.onchain)
        assertTrue("dev devnet mode keeps the old rule", SeasonStore.planFor(app, save, mainnet = false, day = day).done(SeasonItem.ONCHAIN))
    }

    @Test fun mainnetCheckInTxTicksSeason() {
        val save = GameSave(app)
        save.recordRun(1300, 10)
        save.stampClock(owner, "5".repeat(88), "mainnet", "tx", save.today(), 1300)
        val p = SeasonStore.planFor(app, save, mainnet = true, day = LocalDate.now())
        assertTrue(p.done(SeasonItem.ONCHAIN))
        assertEquals("check-in", p.onchain)
        assertEquals("https://solscan.io/tx/abc", net.solardepin.solarchik.game.ClockIn.explorerTx("abc", "mainnet"))
    }

    @Test fun collectionMintTxsForTheWorkerChecks() {
        assertNull("no collection until Vadym creates it", net.solardepin.solarchik.agents.MintCollection.configured())
        val payer = org.sol4k.Keypair.fromSecretKey(ByteArray(32) { (it + 1).toByte() })
        val authority = org.sol4k.Keypair.fromSecretKey(ByteArray(32) { (it + 50).toByte() })
        val collection = org.sol4k.PublicKey("CoLLzsNbB5uAUt7obSmZHXcfdwTL9vTXJE8mYosq5XmK")
        val coll = net.solardepin.solarchik.agents.MintCollection(collection, authority.publicKey)
        val hash = ByteArray(32) { 9 }
        val free = Catalog.skus.first { !it.paidOnly }
        val combo = Catalog.skus.first { it.paidOnly }
        fun asset(n: Int) = org.sol4k.Keypair.fromSecretKey(ByteArray(32) { (it + n).toByte() })
        val txFree = net.solardepin.solarchik.agents.Minter.buildMintTx(payer.publicKey, hash, free, AgentTier.FREE, asset(100), coll)
        val txPro = net.solardepin.solarchik.agents.Minter.buildMintTx(payer.publicKey, hash, free, AgentTier.PRO, asset(110), coll)
        val txCombo = net.solardepin.solarchik.agents.Minter.buildMintTx(payer.publicKey, hash, combo, AgentTier.PRO, asset(120), coll)
        // a patched client: Combo name, no payment
        val forged = net.solardepin.solarchik.solana.LegacyTx.compile(payer.publicKey, hash, listOf(
            net.solardepin.solarchik.solana.CoreIx.createV1(asset(130).publicKey, payer.publicKey, combo.nameFor(AgentTier.PRO), SolarchikConfig.AGENT_URI, emptyList(), collection = collection, authority = authority.publicKey),
        )).partialSign(asset(130))
        for (t in listOf(txFree, txPro, txCombo)) {
            assertEquals("payer, asset, authority sign", 3, t.signerCount)
            val sig = authority.sign(t.message)
            t.addSignature(authority.publicKey, sig)
            assertTrue(authority.publicKey.verify(t.signature(t.signerIndex(authority.publicKey)), t.message))
        }
        assertTrue(combo.nameFor(AgentTier.PRO).contains("Combo"))
        // fixtures for worker/agent-mint.test.mjs (the JS side parses exactly what the app builds)
        val out = File(outDir.parentFile, "mint-fixtures.json")
        val enc = java.util.Base64.getEncoder()
        out.writeText(org.json.JSONObject()
            .put("payer", payer.publicKey.toBase58()).put("authority", authority.publicKey.toBase58()).put("collection", collection.toBase58())
            .put("free", enc.encodeToString(net.solardepin.solarchik.agents.Minter.buildMintTx(payer.publicKey, hash, free, AgentTier.FREE, asset(100), coll).serialize()))
            .put("pro", enc.encodeToString(net.solardepin.solarchik.agents.Minter.buildMintTx(payer.publicKey, hash, free, AgentTier.PRO, asset(110), coll).serialize()))
            .put("combo", enc.encodeToString(net.solardepin.solarchik.agents.Minter.buildMintTx(payer.publicKey, hash, combo, AgentTier.PRO, asset(120), coll).serialize()))
            .put("comboUnpaid", enc.encodeToString(forged.serialize()))
            .put("freeName", free.nameFor(AgentTier.FREE)).put("proName", free.nameFor(AgentTier.PRO)).put("comboName", combo.nameFor(AgentTier.PRO))
            .toString(2))
    }

    // ------------------------------------------------------------------ UI

    @Test fun settingsMainnetIsWalletAppOnlyAndDevToggleIsHidden() {
        SolanaWallet.walletAppCheck = { false }
        val a = launch()
        a.select(MainActivity.Tab.SETTINGS); idle()
        val d = a.window.decorView
        val all = texts(d)
        assertTrue(all.any { it.startsWith("No Solana wallet app found") })
        assertFalse("no built-in wallet offer on mainnet", all.any { it.contains("built-in devnet wallet", ignoreCase = true) })
        assertNull(find(d, "settings-dev-devnet"))
        shot(d, "07-settings-mainnet")
        val s = a.screen(MainActivity.Tab.SETTINGS) as SettingsScreen
        repeat(7) { s.versionTap() }
        idle()
        assertNotNull(find(d, "settings-dev-devnet"))
    }

    @Test fun todayShowsMainnetSolAndSkr() {
        connectAs(owner)
        val a = launch()
        val t = a.screen(MainActivity.Tab.TODAY) as TodayScreen
        t.setBalanceForTest(0.0096, 1234.5)
        // 1.2.7: balances live on Me (SOL, USDC, SKR tiles)
        a.select(MainActivity.Tab.ME); idle()
        val d = a.window.decorView
        assertEquals("0.0096", (find(d, "today-wallet-balance") as TextView).text.toString())
        assertEquals("1,234.5", (find(d, "today-wallet-skr") as TextView).text.toString())
        assertNotNull(find(d, "me-usdc"))
        assertTrue((find(d, "today-cluster") as TextView).text.toString().contains("Mainnet"))
        find(d, "today-wallet-explorer")!!.performClick()
        assertEquals("https://solscan.io/account/$owner", shadowOf(a).nextStartedActivity.dataString)
        shot(d, "08-me-mainnet", "me-wallet")
    }

    @Test fun swapsTabRiskOptInThenLimitsAndReview() {
        connectAs(owner)
        val a = launch()
        a.select(MainActivity.Tab.AGENTS)
        val ag = a.screen(MainActivity.Tab.AGENTS) as AgentsScreen
        ag.openSection(AgentsScreen.SAVER); idle()
        val d = a.window.decorView
        assertNotNull("risk note first", find(d, "swap-risk"))
        assertNull(find(d, "swap-form"))
        shot(d, "09-swaps-risk", "swap-head")
        ag.swapPanel.acceptRisk(); idle()
        assertNotNull(find(d, "swap-limits"))
        assertNotNull(find(d, "swap-form"))
        assertEquals("Spent today: 0 of 0.05 SOL", (find(d, "swap-spent") as TextView).text.toString())
        assertTrue(SwapStore(app).policy().live)
        shot(d, "10-swaps-limits", "swap-limits")
        // a prepared review shows quote, impact and fees before the wallet
        val p = runBlocking {
            SwapDesk(app, FakeJup(), today = { LocalDate.now().toString() }).prepare(SolanaWallet(app), SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 1_000_000), NoTokenRpc()).getOrThrow()
        }
        ag.swapPanel.prepared = p
        ag.render(); idle()
        for (tag in listOf("swap-review", "swap-min", "swap-impact", "swap-route", "swap-fee", "swap-ata", "swap-counts", "swap-confirm")) assertNotNull(tag, find(d, tag))
        shot(d, "11-swaps-review", "swap-review")
    }
}
