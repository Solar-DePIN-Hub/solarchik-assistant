package net.solardepin.solarchik

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.agents.OwnedAgent
import net.solardepin.solarchik.agents.engine.Closed
import net.solardepin.solarchik.agents.engine.Position
import net.solardepin.solarchik.agents.engine.TickReport
import net.solardepin.solarchik.core.FeeReason
import net.solardepin.solarchik.core.FeeRow
import net.solardepin.solarchik.game.RunInput
import net.solardepin.solarchik.game.RunView
import net.solardepin.solarchik.notify.DeskNotes
import net.solardepin.solarchik.notify.NoteKind
import net.solardepin.solarchik.notify.NotePlanner
import net.solardepin.solarchik.notify.Notes
import net.solardepin.solarchik.notify.PendingClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** 0.20.3: the four items left open after the 0.20.2 audit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditFixes2Test {
    private lateinit var ctx: Context
    private val d = LocalDate.of(2026, 10, 1)
    private fun utc(h: Int, m: Int = 0) = d.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        listOf("solarchik-game", "solarchik-notes", "solarchik-agents").forEach {
            ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    // ---- (1) run game: taps are handed to the game thread ----

    @Test fun runInputHandsOverEachTapOnce() {
        val i = RunInput()
        assertFalse(i.take())
        i.request(); i.request()
        assertTrue(i.take())
        assertFalse(i.take())
    }

    @Test fun runInputUnderContentionNeverInventsTaps() {
        val i = RunInput()
        val taken = AtomicInteger()
        val requests = 20_000
        val start = CountDownLatch(1)
        val producer = Thread { start.await(); repeat(requests) { i.request() } }
        val consumer = Thread { start.await(); repeat(requests * 2) { if (i.take()) taken.incrementAndGet() } }
        producer.start(); consumer.start(); start.countDown(); producer.join(); consumer.join()
        if (i.take()) taken.incrementAndGet()
        assertTrue(taken.get() in 1..requests)
    }

    @Test fun touchDoesNotWriteGameStateOnTheUiThread() {
        val v = RunView(ctx)
        fun field(name: String) = RunView::class.java.getDeclaredField(name).apply { isAccessible = true }
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, 10f, 400f, 0)) // swipe down = slide
        v.performClick()
        // the run state is created and stepped by the game thread only
        assertEquals(null, field("state").get(v))
        val input = field("input").get(v) as RunInput
        assertTrue(input.take()) // jump queued for the game thread
        assertTrue(input.takeSlide()) // slide queued too
        assertFalse(input.take())
        v.onTouchEvent(MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_UP, 10f, 400f, 0))
        assertFalse(input.jumpHeld)
        assertFalse(input.slideHeld)
    }

    @Test fun pausedOrEndedRunIgnoresTaps() {
        val v = RunView(ctx)
        val input = RunView::class.java.getDeclaredField("input").apply { isAccessible = true }.get(v) as RunInput
        v.paused = true
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        v.performClick()
        assertFalse(input.take())
    }

    // ---- (2) daily report on the UTC game day ----

    private fun report(now: Long, zone: String) = NotePlanner.due(
        net.solardepin.solarchik.core.StreakState(), now, emptySet(), { true }, true, ZoneId.of(zone),
    ).filter { it.kind == NoteKind.REPORT }

    @Test fun reportIsKeyedByTheUtcDayAndFiresBeforeItEnds() {
        // Kyiv (UTC+3): 20:00 local = 17:00 UTC; quiet from 22:00 local
        assertTrue(report(utc(16, 59), "Europe/Kyiv").isEmpty())
        assertEquals("report:2026-10-01", report(utc(17), "Europe/Kyiv").single().key)
        assertTrue(report(utc(19, 30), "Europe/Kyiv").isEmpty())
        // Los Angeles (UTC-7): local 20:00 is 03:00 UTC, the start of a new game day -> not then;
        // instead 4 h before the UTC day ends (13:00 local)
        assertTrue(report(utc(3), "America/Los_Angeles").isEmpty())
        assertEquals("report:2026-10-01", report(utc(20), "America/Los_Angeles").single().key)
        // Tokyo (UTC+9): 21:00 local = 12:00 UTC (second half) fires; 05:00 local is quiet; 08:00 local fires
        assertEquals("report:2026-10-01", report(utc(12), "Asia/Tokyo").single().key)
        assertTrue(report(utc(20), "Asia/Tokyo").isEmpty())
        assertEquals("report:2026-10-01", report(utc(23), "Asia/Tokyo").single().key)
        // UTC itself
        assertEquals("report:2026-10-01", report(utc(20), "UTC").single().key)
        assertTrue(report(utc(22), "UTC").isEmpty())
    }

    @Test fun reportEveryZoneGetsAWindowEachDay() {
        for (zone in listOf("UTC", "Europe/Kyiv", "America/Los_Angeles", "America/New_York", "Asia/Tokyo", "Asia/Kolkata", "Pacific/Auckland", "America/Sao_Paulo")) {
            val fired = (0 until 24 * 4).map { utc(0) + it * 15 * 60_000L }.count { report(it, zone).isNotEmpty() }
            assertTrue("$zone has no report window", fired >= 4) // at least an hour, the worker runs hourly
        }
    }

    @Test fun reportBodySaysWhenTheGameDayEndsLocally() {
        val body = Notes.bodyFor(ctx, NoteKind.REPORT, utc(17))
        val expected = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(utc(0) + 86_400_000L))
        assertTrue(body, body.contains(expected))
        assertTrue(body.contains("UTC"))
    }

    // ---- (3) corrupt agents / fee save is kept aside, records salvaged ----

    @Test fun corruptAgentsSaveIsKeptAsideNotWiped() {
        val prefs = ctx.getSharedPreferences("solarchik-agents", Context.MODE_PRIVATE)
        val good = """{"asset":"A1","skuId":"sku-pred-alpha","tier":"free","name":"n","owner":"o","cluster":"devnet"}"""
        val raw = """[$good,{"asset":7,"tier":{"x":1}}]"""
        prefs.edit().putString(AgentStore.KEY_AGENTS, raw).commit()
        val store = AgentStore(ctx)
        assertEquals(listOf("A1"), store.agents().map { it.asset }) // salvaged
        assertEquals(raw, store.unreadable())
        store.upsert(OwnedAgent("A2", "sku-pred-alpha-pro", "pro", "n2", "o", "devnet"))
        assertEquals(listOf("A1", "A2"), store.agents().map { it.asset })
        assertEquals(raw, store.unreadable()) // first bad copy is kept
        prefs.edit().putString(AgentStore.KEY_FEES, "{garbage").commit()
        assertTrue(store.fees().isEmpty())
        assertEquals("{garbage", store.unreadable(AgentStore.KEY_FEES))
    }

    // ---- (4) background close notifications ----

    private fun pc(id: String, pnl: Double = 0.001) = PendingClose(id, pnl, utc(10))

    @Test fun deskPlanRespectsToggleRateLimitAndDuplicates() {
        val now = utc(12)
        // toggle off: nothing posted, queue dropped
        assertEquals(DeskNotesPlan(0, 0), DeskNotes.plan(listOf(pc("a")), listOf(pc("b")), emptySet(), 0, now, enabled = false, allowed = true).sizes())
        // first close: posted
        assertEquals(DeskNotesPlan(1, 0), DeskNotes.plan(emptyList(), listOf(pc("a")), emptySet(), 0, now, true, true).sizes())
        // already sent or already queued: ignored
        assertEquals(DeskNotesPlan(0, 0), DeskNotes.plan(emptyList(), listOf(pc("a"), pc("a")), setOf("a"), 0, now, true, true).sizes())
        assertEquals(DeskNotesPlan(2, 0), DeskNotes.plan(listOf(pc("a")), listOf(pc("a"), pc("b"), pc("b")), emptySet(), 0, now, true, true).sizes())
        // within 30 min of the last note: queued
        assertEquals(DeskNotesPlan(0, 1), DeskNotes.plan(emptyList(), listOf(pc("c")), emptySet(), now - 10 * 60_000L, now, true, true).sizes())
        assertEquals(DeskNotesPlan(1, 0), DeskNotes.plan(emptyList(), listOf(pc("c")), emptySet(), now - DeskNotes.MIN_GAP_MS, now, true, true).sizes())
        // no permission: queued (bounded)
        val many = (1..80).map { pc("m$it") }
        assertEquals(DeskNotesPlan(0, DeskNotes.QUEUE_MAX), DeskNotes.plan(emptyList(), many, emptySet(), 0, now, true, false).sizes())
    }

    private data class DeskNotesPlan(val post: Int, val queue: Int)
    private fun net.solardepin.solarchik.notify.DeskPlan.sizes() = DeskNotesPlan(post.size, queue.size)

    private fun tick(vararg ids: String) = TickReport(
        ids.map { id ->
            val p = Position(id, "BTC", "BTC-USD", "Bitcoin", "up", 0.1, 100.0, utc(9), utc(10), 0.7)
            Closed(p, 101.0, 0.002, utc(10)) to FeeRow("f-$id", "k", utc(9), utc(10), 0.002, 0.0001, false, FeeReason.PAPER)
        },
        emptyList(), emptyList(),
    )

    @Test fun backgroundClosesPostOneSummaryNoDuplicatesAndQueueLater() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notes.createChannel(ctx)
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        if (net.solardepin.solarchik.core.SolarchikConfig.SOL_APP == "assistant") {
            // 1.1.2: the trading-desk note is game-only; the assistant never posts it (no desk leftovers)
            DeskNotes.onTick(ctx, tick("p1", "p2"), now = utc(12))
            assertEquals(0, nm.allNotifications.size)
            return
        }
        DeskNotes.onTick(ctx, tick("p1", "p2"), now = utc(12))
        assertEquals(1, nm.allNotifications.size)
        val text = nm.allNotifications.single().extras.getCharSequence("android.text").toString()
        assertTrue(text, text.contains("2") && text.contains("+0.004"))
        assertEquals(listOf("p1", "p2"), DeskNotes.sentIds(ctx))
        // same closes again (e.g. a retried worker): nothing new
        DeskNotes.onTick(ctx, tick("p1", "p2"), now = utc(12, 5))
        assertTrue(DeskNotes.queued(ctx).isEmpty())
        // a new close 10 min later: rate-limited, queued
        DeskNotes.onTick(ctx, tick("p3"), now = utc(12, 10))
        assertEquals(listOf("p3"), DeskNotes.queued(ctx).map { it.id })
        // the hourly worker flushes it once the gap has passed
        DeskNotes.flush(ctx, now = utc(12, 45))
        assertTrue(DeskNotes.queued(ctx).isEmpty())
        assertEquals(listOf("p1", "p2", "p3"), DeskNotes.sentIds(ctx))
    }

    @Test fun backgroundClosesSilentWhenToggleOff() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notes.createChannel(ctx)
        ctx.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE).edit().putBoolean(NoteKind.DESK.toggle, false).commit()
        DeskNotes.onTick(ctx, tick("p1"), now = utc(12))
        assertTrue(shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertTrue(DeskNotes.queued(ctx).isEmpty())
    }

    @Test fun appRegistersTheBackgroundHook() {
        assertTrue(net.solardepin.solarchik.agents.engine.DeskHooks.afterTick != null)
    }
}
