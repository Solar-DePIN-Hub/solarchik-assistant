package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.season.Skr
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.swap.JupiterApi
import net.solardepin.solarchik.swap.SwapDesk
import net.solardepin.solarchik.swap.SwapGuard
import net.solardepin.solarchik.swap.SwapPolicy
import net.solardepin.solarchik.swap.SwapRequest
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.swap.TxCheck
import net.solardepin.solarchik.wallet.Base58
import net.solardepin.solarchik.wallet.MemoTx
import net.solardepin.solarchik.wallet.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * 1.1.0 live, READ-ONLY checks against Solana mainnet and Jupiter (run with -PmainnetLive=1). Nothing is ever
 * signed or sent: balances are reads, the check-in memo and the Jupiter swap are only *simulated* by the RPC
 * (sigVerify=false), exactly as built by the app for a real funded wallet (the public treasury address).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainnetLiveIT {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val treasury = SolarchikConfig.TREASURY
    private val skrHolder = "C9pVXx7ieotYjaQr76gAgx7bmA67hZQivFTZ2UxGuWnz"
    private val realCluster = System.getProperty("solarchik.cluster")

    @Before fun gate() {
        assumeTrue(System.getProperty("solarchik.mainnetLive") == "1")
        System.setProperty("solarchik.cluster", "mainnet")
        app.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().clear().putString("address", treasury).putString("auth", "t").commit()
        app.getSharedPreferences("solarchik.swap", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restore() {
        if (realCluster == null) System.clearProperty("solarchik.cluster") else System.setProperty("solarchik.cluster", realCluster)
    }

    private suspend fun simulate(rpc: Rpc, raw: ByteArray): JsonObject {
        val r = rpc.call("simulateTransaction", buildJsonArray {
            add(JsonPrimitive(Base64.getEncoder().encodeToString(raw)))
            add(buildJsonObject { put("encoding", "base64"); put("sigVerify", false); put("replaceRecentBlockhash", true); put("commitment", "confirmed") })
        })
        return r.jsonObject["value"]!!.jsonObject
    }

    /**
     * A wallet that really holds [mint] right now, found read-only from the mint's latest transactions (fixed
     * addresses go stale: the SKR holder used before had moved its SKR by 9 Oct). Must be a normal wallet with
     * its tokens in the associated token account and SOL for a fee.
     */
    private suspend fun findHolder(rpc: Rpc, mint: String, minRaw: Long): String {
        val sigs = rpc.call("getSignaturesForAddress", buildJsonArray { add(JsonPrimitive(mint)); add(buildJsonObject { put("limit", 20) }) }).jsonArray
        for (s in sigs) {
            if (s.jsonObject["err"].toString() != "null") continue
            Thread.sleep(300)
            val tx = runCatching { rpc.call("getTransaction", buildJsonArray { add(s.jsonObject["signature"]!!); add(buildJsonObject { put("encoding", "jsonParsed"); put("maxSupportedTransactionVersion", 0) }) }) }.getOrNull() as? JsonObject ?: continue
            val keys = tx["transaction"]!!.jsonObject["message"]!!.jsonObject["accountKeys"]!!.jsonArray.map { it.jsonObject["pubkey"]!!.jsonPrimitive.content }
            for (b in tx["meta"]!!.jsonObject["postTokenBalances"]?.jsonArray.orEmpty()) {
                val o = b.jsonObject
                if (o["mint"]!!.jsonPrimitive.content != mint) continue
                val amount = o["uiTokenAmount"]!!.jsonObject["amount"]!!.jsonPrimitive.content.toLong()
                val holder = o["owner"]?.jsonPrimitive?.content ?: continue
                if (amount < minRaw) continue
                val idx = o["accountIndex"]!!.jsonPrimitive.content.toInt()
                if (keys[idx] != net.solardepin.solarchik.delegate.SplIx.ata(org.sol4k.PublicKey(holder), org.sol4k.PublicKey(mint)).toBase58()) continue
                val info = rpc.accountInfo(holder) ?: continue
                if (info.owner == "11111111111111111111111111111111" && info.lamports > 10_000_000L) return holder
            }
        }
        error("no holder of $mint found in recent transactions")
    }

    @Test fun balancesSolAndSkr() = runBlocking {
        val w = SolanaWallet(app)
        assertTrue(w.mainnet)
        val sol = w.balanceSol().getOrThrow()
        println("LIVE mainnet SOL $treasury = $sol (${w.rpcUrl})")
        assertTrue(sol >= 0.0)
        val holder = findHolder(Rpc(SolarchikConfig.RPC_MAINNET), SwapTokens.SKR.mint, 1_000_000L)
        val skr = Skr.fetch(holder).getOrThrow()
        println("LIVE mainnet SKR $holder = $skr")
        assertTrue(skr > 0.0)
        assertEquals(0.0, Skr.fetch("11111111111111111111111111111112").getOrElse { 0.0 }, 0.0)
    }

    @Test fun checkInMemoSimulatesOnMainnet() = runBlocking {
        val rpc = Rpc(SolarchikConfig.RPC_MAINNET)
        val memo = MemoTx.clockMemo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), 1200, 1)
        val raw = MemoTx.build(Base58.decode(treasury), rpc.latestBlockhash(), memo)
        val v = simulate(rpc, raw)
        println("LIVE memo simulate err=${v["err"]} logs=${v["logs"]}")
        assertTrue("memo tx simulates without error: ${v["err"]}", v["err"].toString() == "null")
        assertTrue(v["logs"]!!.jsonArray.any { it.toString().contains("Memo") })
    }

    @Test fun jupiterQuoteAndUnsignedSwapBuildForAllowlist() = runBlocking {
        val jup = JupiterApi()
        val rpc = Rpc(SolarchikConfig.RPC_MAINNET)
        for (to in listOf(SwapTokens.USDC, SwapTokens.SKR, SwapTokens.JUP)) {
            val req = SwapRequest(SwapTokens.SOL, to, 1_000_000)
            val q = jup.quote(req.from, to, req.amountRaw, 50)
            val policy = SwapPolicy(true, 1L)
            assertTrue("quote within limits for ${to.symbol}: ${SwapGuard.checkQuote(policy, req, q)}", SwapGuard.checkQuote(policy, req, q).isEmpty())
            var b = jup.swapTx(q, treasury)
            if (b.tx.size > TxCheck.MAX_TX_BYTES) {
                println("LIVE ${to.symbol}: ${b.tx.size}-byte tx is too large -> re-quote with maxAccounts=${JupiterApi.SMALL_MAX_ACCOUNTS}")
                b = jup.swapTx(jup.quote(req.from, to, req.amountRaw, 50, JupiterApi.SMALL_MAX_ACCOUNTS), treasury)
            }
            assertNull(TxCheck.problem(b.tx, treasury))
            println("LIVE jupiter 0.001 SOL -> ${to.fromRaw(q.outAmount)} ${to.symbol} (min ${to.fromRaw(q.minOut)}), impact ${"%.4f".format(q.priceImpactPct)}%, route ${q.routeLabels}, prio ${b.priorityFeeLamports} lamports, jupiterSim=${b.simulationError}, tx ${b.tx.size} bytes v0=${TxCheck.header(b.tx).versioned}")
            val v = simulate(rpc, b.tx)
            println("LIVE rpc simulate ${to.symbol}: err=${v["err"]} units=${v["unitsConsumed"]}")
            Thread.sleep(400)
        }
    }

    @Test fun swapDeskPreparesARealReviewButNeverSigns() = runBlocking {
        val desk = SwapDesk(app)
        desk.store.setPolicy(SwapPolicy(true, 1L))
        val w = SolanaWallet(app)
        val p = desk.prepare(w, SwapRequest(SwapTokens.SOL, SwapTokens.USDC, 1_000_000)).getOrThrow()
        println("LIVE review: pay ${p.quote.inAmount} lamports, get ${SwapTokens.USDC.fromRaw(p.quote.outAmount)} USDC (min ${SwapTokens.USDC.fromRaw(p.quote.minOut)}), impact ${p.quote.priceImpactPct}%, fees ${p.fees}, counts ${p.solValue} SOL")
        assertEquals(0.001, p.solValue, 1e-12)
        assertTrue(desk.store.records().isEmpty())
    }

    // ------------------------------------------------------------------ 1.1.0 delegated limit (read-only)

    @Test fun delegatedApproveRevokeSimulateAndAgentSwapBuilds() = runBlocking {
        val rpc = Rpc(SolarchikConfig.RPC_MAINNET)
        val usdc = SwapTokens.USDC
        // a real USDC wallet (read-only; nothing is signed or sent for it)
        val holderAddr = findHolder(rpc, usdc.mint, 1_000_000L)
        val user = org.sol4k.PublicKey(holderAddr)
        val r = rpc.call("getTokenAccountsByOwner", buildJsonArray {
            add(JsonPrimitive(holderAddr)); add(buildJsonObject { put("mint", usdc.mint) }); add(buildJsonObject { put("encoding", "jsonParsed") })
        })
        val real = r.jsonObject["value"]!!.jsonArray.map { it.jsonObject["pubkey"]!!.jsonPrimitive.content }
        val ata = net.solardepin.solarchik.delegate.SplIx.ata(user, org.sol4k.PublicKey(usdc.mint)).toBase58()
        println("LIVE USDC holder $holderAddr accounts $real, derived ATA $ata")
        assertTrue(ata in real)
        val agent = org.sol4k.Keypair.generate()
        // the exact Approve (5 USDC + 0.01 SOL top-up) and Revoke the wallet would sign: simulated only
        val approve = net.solardepin.solarchik.delegate.DelegateRules.approveTx(user, usdc, agent.publicKey, 5_000_000, 10_000_000, rpc.latestBlockhash()).serialize()
        val va = simulate(rpc, approve)
        println("LIVE approve simulate err=${va["err"]} logs=${va["logs"]}")
        assertEquals("null", va["err"].toString())
        assertTrue(va["logs"]!!.jsonArray.any { it.toString().contains("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success") })
        val revoke = net.solardepin.solarchik.delegate.DelegateRules.revokeTx(user, usdc, rpc.latestBlockhash()).serialize()
        val vr = simulate(rpc, revoke)
        println("LIVE revoke simulate err=${vr["err"]} logs=${vr["logs"]}")
        assertEquals("null", vr["err"].toString())
        // on-chain allowance read (balance, current delegate if any)
        val st = net.solardepin.solarchik.delegate.DelegateDesk(app).chainState(rpc, holderAddr)
        println("LIVE chain state $st")
        assertTrue(st.exists)
        // Jupiter builds the agent's swap: agent pays and signs, output goes to the user (SOL natively / JUP account)
        val jup = JupiterApi()
        val q = jup.quote(usdc, SwapTokens.SOL, 100_000, 50)
        val b = jup.swapTx(q, agent.publicKey.toBase58())
        val problem = net.solardepin.solarchik.swap.TxCheck.problem(b.tx, agent.publicKey.toBase58())
        println("LIVE agent swap 0.1 USDC -> ${SwapTokens.SOL.fromRaw(q.outAmount)} SOL into the agent (${b.tx.size} bytes, then forwarded): problem=$problem jupiterSim=${b.simulationError}")
        assertNull(problem)
        val native = runCatching { jup.swapTx(q, agent.publicKey.toBase58(), nativeDestination = holderAddr) }
        println("LIVE nativeDestinationAccount on v1: ${native.exceptionOrNull()?.message ?: "accepted"}")
        Thread.sleep(500)
        val q2 = jup.quote(usdc, SwapTokens.JUP, 100_000, 50)
        val dest = net.solardepin.solarchik.delegate.SplIx.ata(user, org.sol4k.PublicKey(SwapTokens.JUP.mint)).toBase58()
        val b2 = runCatching { jup.swapTx(q2, agent.publicKey.toBase58(), destinationTokenAccount = dest) }
        println("LIVE agent swap 0.1 USDC -> JUP to $dest: ${b2.map { "problem=" + net.solardepin.solarchik.delegate.DelegateRules.swapTxProblem(it.tx, agent.publicKey.toBase58(), dest) + " sim=" + it.simulationError }.getOrElse { "error " + it.message }}")
        assertNull(net.solardepin.solarchik.delegate.DelegateRules.swapTxProblem(b2.getOrThrow().tx, agent.publicKey.toBase58(), dest))
    }

    // ------------------------------------------------------------------ 1.1.0 call -> action payments (read-only)

    @Test fun callPaymentTransfersSimulateOnMainnet() = runBlocking {
        val rpc = Rpc(SolarchikConfig.RPC_MAINNET)
        val to = org.sol4k.Keypair.generate().publicKey // a fresh recipient the user typed
        // SOL: 0.001 SOL from the treasury (a real funded wallet), exactly the tx the wallet app would be asked to sign
        val sol = net.solardepin.solarchik.screen.CallActionRules.paymentTx(org.sol4k.PublicKey(treasury), to, "SOL", 1_000_000, rpc.latestBlockhash()).serialize()
        val vs = simulate(rpc, sol)
        println("LIVE call payment SOL simulate err=${vs["err"]} logs=${vs["logs"]}")
        assertEquals("null", vs["err"].toString())
        Thread.sleep(400)
        // USDC: 0.1 USDC from a real USDC wallet, recipient ATA created idempotently
        val holder = findHolder(rpc, SwapTokens.USDC.mint, 1_000_000L)
        val usdc = net.solardepin.solarchik.screen.CallActionRules.paymentTx(org.sol4k.PublicKey(holder), to, "USDC", 100_000, rpc.latestBlockhash()).serialize()
        val vu = simulate(rpc, usdc)
        println("LIVE call payment USDC from $holder simulate err=${vu["err"]} logs=${vu["logs"]}")
        assertEquals("null", vu["err"].toString())
        val logs = vu["logs"]!!.jsonArray.map { it.toString() }
        assertTrue(logs.any { it.contains("CreateIdempotent") })
        // the transferChecked is the last top-level Tokenkeg instruction (mainnet Tokenkeg no longer logs its name)
        assertTrue(logs.last().contains("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success"))
    }
}
