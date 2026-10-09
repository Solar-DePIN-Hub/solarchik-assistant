package net.solardepin.solarchik

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.agents.MintError
import net.solardepin.solarchik.agents.Minter
import net.solardepin.solarchik.agents.OwnedAgent
import net.solardepin.solarchik.agents.engine.GammaPick
import net.solardepin.solarchik.agents.engine.Strategies
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.game.GameSave
import net.solardepin.solarchik.notify.Notes
import net.solardepin.solarchik.sol.SolChat
import net.solardepin.solarchik.sol.SolChatStore
import net.solardepin.solarchik.solana.Rpc
import net.solardepin.solarchik.solana.RpcException
import net.solardepin.solarchik.wallet.SentTx
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.StickyBlockhash
import net.solardepin.solarchik.wallet.WalletError
import net.solardepin.solarchik.screen.Secretary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.sol4k.Keypair
import java.time.LocalDate
import java.time.ZoneOffset

/** One test (or more) per bug fixed in the 0.20.2 audit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditFixesTest {
    private lateinit var ctx: Context
    private val day = 86_400_000L
    private val now = LocalDate.of(2026, 10, 1).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        listOf("solarchik-game", "solarchik-notes", "solarchik-desk", "solarchik-agents", "solarchik-sol", "seeker-wallet").forEach {
            ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    // ---- Events pick: prefer markets that resolve within days and actually trade ----

    private fun m(id: String, yes: Double, endDays: Double?, vol24: Double = 0.0, dayMove: Double = 0.0, hourMove: Double = 0.0, q: String = "Will $id happen?") =
        GammaPick.Market(id, q, yes, 5e6, endDays?.let { now + (it * day).toLong() }, true, vol24, dayMove, hourMove)

    @Test fun gammaPrefersSoonLiquidMovingMarkets() {
        val ms = listOf(
            m("far-huge", 0.80, 200.0, vol24 = 5e6, dayMove = 0.05),
            m("month", 0.75, 20.0, vol24 = 2e6, dayMove = 0.02),
            m("soon-still", 0.72, 3.0, vol24 = 9e5, dayMove = 0.0),
            m("soon-moving", 0.70, 5.0, vol24 = 1e5, dayMove = 0.03),
            m("soon-thin", 0.75, 2.0, vol24 = 5_000.0, dayMove = 0.1),
        )
        assertEquals("soon-moving", GammaPick.pick(ms, now)!!.id)
        // without a moving one, the most traded soon market
        assertEquals("soon-still", GammaPick.pick(ms.filter { it.id != "soon-moving" }, now)!!.id)
        // nothing within 7 days -> within 30 days
        assertEquals("month", GammaPick.pick(ms.filter { !it.id.startsWith("soon") }, now)!!.id)
        // nothing liquid at all -> the base rule (strongest favourite)
        assertEquals("far-huge", GammaPick.pick(listOf(m("a", 0.7, 300.0), m("far-huge", 0.8, 300.0), m("b", 0.66, null)), now)!!.id)
    }

    @Test fun gammaHourMoveCountsAndEndingTooSoonIsSkipped() {
        val ms = listOf(
            m("ends-in-1h", 0.7, 1.0 / 24, vol24 = 9e6, dayMove = 0.2),
            m("hour-mover", 0.7, 4.0, vol24 = 3e4, hourMove = 0.006),
            m("still", 0.7, 4.0, vol24 = 8e5),
        )
        assertEquals("hour-mover", GammaPick.pick(ms, now)!!.id)
    }

    @Test fun gammaKeepsTheHonestFloorAndCeiling() {
        val ms = listOf(
            m("coinflip", 0.60, 2.0, vol24 = 9e6, dayMove = 0.1),
            m("decided", 0.97, 2.0, vol24 = 9e6, dayMove = 0.1),
        )
        assertNull(GammaPick.pick(ms, now))
        val atFloor = m("floor", Strategies.MIN_CONFIDENCE, 2.0, vol24 = 9e4, dayMove = 0.02)
        assertEquals("floor", GammaPick.pick(ms + atFloor, now)!!.id)
        // "no" favourites count too (yes = 0.3 -> favourite 0.7)
        assertEquals("no-fav", GammaPick.pick(listOf(m("no-fav", 0.3, 2.0, vol24 = 9e4)), now)!!.id)
    }

    @Test fun gammaSkipsSportsAndBitcoinVariants() {
        val qs = listOf(
            "Counter-Strike: NAVI vs FaZe (BO3)", "Map Handicap: G2 (-1.5)", "Spread: Lakers (-4.5)", "Celtics O/U 220.5",
            "LoL: T1 vs Gen.G", "Will Arsenal win on 2026-10-04?", "Will Chelsea vs. Spurs end in a draw?", "Bitcoin above 120k on Oct 3?",
            "Will BTC hit 150k?", "Valorant Champions winner",
        )
        val ms = qs.mapIndexed { i, q -> m("s$i", 0.7, 2.0, vol24 = 9e6, dayMove = 0.1, q = q) } + m("ok", 0.7, 2.0, vol24 = 2e4, dayMove = 0.01, q = "Will the Fed cut rates in October?")
        assertEquals("ok", GammaPick.pick(ms, now)!!.id)
        // the word guard does not eat ordinary words
        assertEquals("fc-ish", GammaPick.pick(listOf(m("fc-ish", 0.7, 2.0, vol24 = 2e4, q = "Will FCC approve the merger?")), now)!!.id)
    }

    @Test fun gammaParsesVolumeAndMovesAndRejectsBadPrices() {
        val o = Json.parseToJsonElement(
            """{"id":"42","question":"Will X?","outcomePrices":"[\"0.71\",\"0.29\"]","volume":"123456.7","volume24hr":45678.9,
               "oneDayPriceChange":-0.034,"oneHourPriceChange":"0.006","endDate":"2026-10-03T00:00:00Z","active":true,"closed":false}""",
        ).jsonObject
        val mk = GammaPick.parse(o)!!
        assertEquals(0.71, mk.yes, 1e-12)
        assertEquals(45678.9, mk.volume24h, 1e-9)
        assertEquals(0.034, mk.dayMove, 1e-12)
        assertEquals(0.006, mk.hourMove, 1e-12)
        assertTrue(GammaPick.moving(mk))
        val bad = Json.parseToJsonElement("""{"id":"1","question":"q","outcomePrices":"[\"1.7\",\"0\"]"}""").jsonObject
        assertNull(GammaPick.parse(bad))
        val garbage = Json.parseToJsonElement("""{"id":"1","question":"q","outcomePrices":"[\"0.7\"]","volume24hr":{"x":1},"oneDayPriceChange":"n/a"}""").jsonObject
        assertEquals(0.0, GammaPick.parse(garbage)!!.volume24h, 0.0)
    }

    // ---- RPC parsing never NPEs ----

    @Test fun rpcParsersThrowTypedErrors() {
        try { Rpc.parseBlockhash(Json.parseToJsonElement("""{"value":null}""")); fail() } catch (e: RpcException) { }
        try { Rpc.parseBlockhash(Json.parseToJsonElement("""{"value":{"blockhash":"notbase58!!"}}""")); fail() } catch (e: RpcException) { }
        try { Rpc.parseBalance(Json.parseToJsonElement("null")); fail() } catch (e: RpcException) { }
        assertEquals(32, Rpc.parseBlockhash(Json.parseToJsonElement("""{"value":{"blockhash":"11111111111111111111111111111111"}}""")).size)
        assertEquals(5L, Rpc.parseBalance(Json.parseToJsonElement("""{"value":5}""")))
        val sigs = Rpc.parseSignatures(
            Json.parseToJsonElement("""[{"signature":"a","err":null,"memo":"[5] hi"},{"signature":"b","err":{"InstructionError":[0,"x"]},"memo":null},{"nosig":1},7]"""),
        )
        assertEquals(listOf("a" to true, "b" to false), sigs.map { it.signature to it.ok })
        assertEquals("[5] hi", sigs[0].memo)
        assertTrue(Rpc.parseSignatures(Json.parseToJsonElement("""{"oops":1}""")).isEmpty())
    }

    // ---- MWA error classification ----

    private fun remote(code: Int, msg: String) = JsonRpc20Client.JsonRpc20RemoteException(code, msg, null)

    @Test fun walletDeclinesAreNeverRetried() {
        val a = WalletError.classify("User did not authorize signing", null)
        assertEquals(WalletError.Kind.DECLINED, a.kind)
        assertFalse(a.signOnlyMayHelp)
        val b = WalletError.classify("sign failed", remote(-3, "not signed"))
        assertEquals(WalletError.Kind.DECLINED, b.kind)
        val c = WalletError.classify("auth", remote(-1, "authorization failed"))
        assertEquals(WalletError.Kind.DECLINED, c.kind)
        assertTrue(c.authRejected)
        assertTrue(WalletError.classify("auth token invalid", null).authRejected)
        assertEquals(WalletError.Kind.NO_WALLET, WalletError.classify("No compatible wallet found", null).kind)
    }

    @Test fun onlyWalletSideSendFailuresTrySignOnly() {
        val w = SolanaWallet(ctx)
        val notSubmitted = WalletError.classify("x", remote(-4, "not all transactions were submitted"))
        assertTrue(w.shouldTrySignOnly(notSubmitted))
        assertTrue(w.shouldTrySignOnly(WalletError.classify("x", remote(-32603, "internal"))))
        val timeout = WalletError.classify("Timed out waiting for wallet", null)
        assertEquals(WalletError.Kind.FAILED, timeout.kind)
        assertFalse(w.shouldTrySignOnly(timeout))
        assertFalse(w.shouldTrySignOnly(WalletError.classify("x", java.io.IOException("broken pipe"))))
        assertFalse(w.shouldTrySignOnly(WalletError.classify("User declined", null)))
        assertFalse(w.shouldTrySignOnly(RuntimeException("x")))
    }

    @Test fun stickyBlockhashIsFetchedOnce() = runBlocking {
        var n = 0
        val s = StickyBlockhash { n++; ByteArray(32) { n.toByte() } }
        val a = s.get(); val b = s.get()
        assertEquals(1, n)
        assertTrue(a.contentEquals(b))
    }

    @Test fun walletAddressMigratesFrom01951Save() {
        val addr = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
        ctx.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("auth", "tok").commit()
        ctx.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().putString("clockAddress", addr).commit()
        val w = SolanaWallet(ctx)
        assertEquals(addr, w.address)
        assertTrue(w.connected)
    }

    @Test fun walletMigrationIgnoresJunkAndNeedsAToken() {
        ctx.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().putString("clockAddress", "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU").commit()
        assertFalse(SolanaWallet(ctx).connected) // no auth token: never "connected" from game data alone
        ctx.getSharedPreferences("seeker-wallet", Context.MODE_PRIVATE).edit().putString("auth", "tok").commit()
        ctx.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().putString("clockAddress", "not-an-address").commit()
        assertFalse(SolanaWallet(ctx).connected)
    }

    // ---- Mint: pending record survives a kill mid-send ----

    private val payer = Keypair.generate()
    private val sku = Catalog.skus.first()

    private fun sender(): ActivityResultSender =
        Robolectric.buildActivity(MainActivity::class.java).setup().get().sender

    private fun minter(store: AgentStore, result: (AgentStore) -> Result<SentTx>, calls: Int = 1) = Minter(
        SolanaWallet(ctx), store,
        send = { _, build ->
            // calls = 2: the sign-only fallback builds again. Build errors come back as a failure, like SolanaWallet.
            runCatching { repeat(calls) { build(payer.publicKey, ByteArray(32) { 3 }) } }
                .fold(onSuccess = { result(store) }, onFailure = { Result.failure(it) })
        },
        cluster = { "devnet" },
        clock = { now },
    )

    @Test fun mintRecordIsSavedBeforeTheWalletSends() = runBlocking {
        val store = AgentStore(ctx)
        var seenDuringSend: List<OwnedAgent> = emptyList()
        val asset = Keypair.generate()
        val res = minter(store, { s -> seenDuringSend = s.agents(); Result.success(SentTx(payer.publicKey.toBase58(), "9".repeat(88), "devnet")) })
            .mintWith(sender(), sku, AgentTier.FREE, asset)
        assertTrue(res.isSuccess)
        assertEquals(listOf(asset.publicKey.toBase58()), seenDuringSend.map { it.asset })
        val rec = store.agents().single()
        assertEquals("9".repeat(88), rec.sig)
        assertEquals(OwnedAgent.STATUS_PENDING, rec.status)
    }

    @Test fun declinedMintLeavesNoRecordButTimeoutKeepsIt() = runBlocking {
        val store = AgentStore(ctx)
        val s = sender()
        val declined = minter(store, { Result.failure(WalletError(WalletError.Kind.DECLINED)) }).mintWith(s, sku, AgentTier.FREE, Keypair.generate())
        assertTrue(declined.isFailure)
        assertTrue(store.agents().isEmpty())
        assertFalse(store.freeClaimed(payer.publicKey.toBase58(), "devnet"))
        val timedOut = minter(store, { Result.failure(WalletError(WalletError.Kind.FAILED, "timed out")) }).mintWith(s, sku, AgentTier.FREE, Keypair.generate())
        assertTrue(timedOut.isFailure)
        assertEquals(1, store.agents().size) // might be on chain: keep it so verify() can find out
        assertTrue(store.freeClaimed(payer.publicKey.toBase58(), "devnet"))
    }

    @Test fun signOnlyRebuildDoesNotTripTheFreeCheckOnItsOwnAsset() = runBlocking {
        val store = AgentStore(ctx)
        val asset = Keypair.generate()
        val res = minter(store, { Result.success(SentTx(payer.publicKey.toBase58(), "8".repeat(88), "devnet")) }, calls = 2)
            .mintWith(sender(), sku, AgentTier.FREE, asset)
        assertTrue(res.exceptionOrNull()?.toString(), res.isSuccess)
        assertEquals(1, store.agents().size)
        // a second, different free mint is still refused at build time
        val again = minter(store, { Result.success(SentTx(payer.publicKey.toBase58(), "7".repeat(88), "devnet")) })
            .mintWith(sender(), sku, AgentTier.FREE, Keypair.generate())
        assertTrue((again.exceptionOrNull() as? MintError)?.kind == MintError.Kind.FREE_USED)
    }

    // ---- CLOCK IN across midnight ----

    @Test fun clockInSignedAfterMidnightStampsTheDayThatWasRun() {
        var t = LocalDate.of(2026, 10, 1).atTime(23, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        val save = GameSave(ctx) { t }
        save.recordRun(1200, 1300)
        val runDay = save.today()
        val meters = save.lastDistance
        t += 2 * 60_000 // wallet prompt answered at 00:01 UTC
        save.stampClock("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", "sig", "devnet", "memo", day = runDay, meters = meters)
        assertEquals("2026-10-01", save.signedDay)
        assertEquals(1, save.streak) // still alive on 10-02: yesterday was signed
        assertEquals(1200, save.clockLog().single { it.day == "2026-10-01" }.meters)
    }

    // ---- Notes: ordered keys, no repeats ----

    @Test fun noteKeysKeepOrderSoTodayIsNeverTrimmed() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notes.createChannel(ctx)
        val t = LocalDate.of(2026, 10, 1).atTime(22, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        ctx.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit()
            .putInt("streak", 4).putString("signedDay", "2026-09-30").putInt("seven", 4).putInt("thirty", 4).commit()
        // a 0.20.1 save: an unordered set with many old keys
        ctx.getSharedPreferences("solarchik-notes", Context.MODE_PRIVATE).edit()
            .putStringSet("notes.sent", (1..150).map { "old-%03d".format(java.util.Locale.ROOT, it) }.toSet()).commit()
        val save = GameSave(ctx) { t }
        val first = Notes.check(ctx, save)
        assertEquals(1, first.size)
        val keys = Notes.sentKeys(ctx)
        assertEquals(first.single().key, keys.last())
        assertTrue(keys.size <= 120)
        assertFalse(ctx.getSharedPreferences("solarchik-notes", Context.MODE_PRIVATE).contains("notes.sent"))
        repeat(3) { assertTrue(Notes.check(ctx, save).isEmpty()) }
    }

    // ---- Sol chat ----

    @Test fun solChatRethrowsCancellation() = runBlocking {
        val chat = SolChat(url = "http://x.invalid", post = { _, _ -> throw CancellationException("left the screen") })
        try {
            chat.ask("hi", "en", "p", "c", emptyList())
            fail("cancellation swallowed")
        } catch (e: CancellationException) { }
    }

    @Test fun conversationIdsDoNotPileUp() {
        val store = SolChatStore(ctx)
        val a = store.conversationId("2026-09-30")
        assertEquals(a, store.conversationId("2026-09-30"))
        val b = store.conversationId("2026-10-01")
        assertTrue(a != b)
        val keys = ctx.getSharedPreferences("solarchik-sol", Context.MODE_PRIVATE).all.keys.filter { it.startsWith("conv.") }
        assertEquals(listOf("conv.2026-10-01"), keys)
    }

    // ---- Activity tab restore ----

    @Test fun restoredTabWinsOverIntentAndJunkFallsThrough() {
        assertEquals(MainActivity.Tab.SOL, MainActivity.startTab("SOL", "AGENTS"))
        assertEquals(MainActivity.Tab.AGENTS, MainActivity.startTab(null, "AGENTS"))
        assertEquals(MainActivity.Tab.AGENTS, MainActivity.startTab("GONE", "AGENTS"))
        assertEquals(MainActivity.Tab.TODAY, MainActivity.startTab(null, "nope"))
        // 1.0.0: Calls is a shortcut to the inbox, never a restored screen
        assertEquals(MainActivity.Tab.TODAY, MainActivity.startTab("CALLS", null))
    }

    // ---- Call screening ----

    @Test fun callScreeningOnlyActsOnIncomingCallsWithTheSecretaryOn() {
        assertEquals(Secretary.Action.ALLOW, Secretary.decide(incoming = false, enabled = true, mode = Secretary.Mode.DECLINE, sdk = 34))
        assertEquals(Secretary.Action.ALLOW, Secretary.decide(incoming = true, enabled = false, mode = Secretary.Mode.DECLINE, sdk = 34))
        assertEquals(Secretary.Action.ALLOW, Secretary.decide(incoming = true, enabled = true, mode = Secretary.Mode.SILENCE, sdk = 28))
        assertEquals(Secretary.Action.SILENCE, Secretary.decide(incoming = true, enabled = true, mode = Secretary.Mode.SILENCE, sdk = 29))
        assertEquals(Secretary.Action.DECLINE, Secretary.decide(incoming = true, enabled = true, mode = Secretary.Mode.DECLINE, sdk = 34))
    }

    // ---- Locale: plurals and formatted strings ----

    @Test @Config(qualifiers = "uk") fun ukrainianStreakPlurals() {
        val r = ctx.resources
        assertEquals("Серія: 1 день.", r.getQuantityString(R.plurals.report_streak_days, 1, 1))
        assertEquals("Серія: 3 дні.", r.getQuantityString(R.plurals.report_streak_days, 3, 3))
        assertEquals("Серія: 5 днів.", r.getQuantityString(R.plurals.report_streak_days, 5, 5))
        assertEquals("Серія: 21 день.", r.getQuantityString(R.plurals.report_streak_days, 21, 21))
        assertTrue(ctx.getString(R.string.fees_pending).isNotBlank())
        assertEquals("Зменшити: Ліміт", ctx.getString(R.string.risk_less, "Ліміт"))
    }

    @Test fun englishStreakPluralsAndShareText() {
        val r = ctx.resources
        assertEquals("Streak: 1 day.", r.getQuantityString(R.plurals.report_streak_days, 1, 1))
        assertEquals("Streak: 2 days.", r.getQuantityString(R.plurals.report_streak_days, 2, 2))
        assertEquals("CLOCK IN 1200m · streak 3", ctx.getString(R.string.share_text, 1200, 3))
        assertEquals("METAPLEX CORE · DEVNET", ctx.getString(R.string.agents_cluster, "DEVNET"))
    }
}
