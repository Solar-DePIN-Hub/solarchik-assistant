package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.game.Interp
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallText
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.sol.SolLatency
import net.solardepin.solarchik.sol.SolVoice
import net.solardepin.solarchik.sol.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class Fixes0219Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    // ---- item 1: calls ----

    /** Shape of the real GET /inbox for the owner id (ayTaC80A call, live_ ghost twin, old voicemail line). */
    private val inbox = """{"items":[
      {"callId":"rtc_u1_ayTaC80A","caller":"+380638500117","text":"Вадим просить передати привіт.","at":1790979027789,"status":"done","source":"tool",
       "summary":{"caller_name":"Вадим","intent":"Передати привіт","urgency":"low","notes":"","callback":"+380638500117"},"lang":"uk","durationSec":74,"chargedUsd":0},
      {"callId":"live_u1_ayTaC80A","caller":"+380638500117","text":"Call failed","at":1790979027000,"status":"failed"},
      {"callId":"","caller":"+380501112233","text":"Old voicemail line","at":1790000000000},
      {"callId":"x","caller":"+1","text":"","at":1}
    ]}"""

    @Test fun inboxDropsGhostTwinAndEmptyLines() {
        val items = CallInbox.parse("owner", 200, inbox)!!
        assertEquals(2, items.size)
        val call = items.first { it.callId == "rtc_u1_ayTaC80A" }
        assertEquals("Вадим", call.who)
        assertEquals(74, call.durationSec)
        assertEquals("uk", call.lang)
        assertTrue(call.answered)
        assertEquals("+380638500117", call.dialNumber)
        assertEquals("1:14", CallInbox.duration(call.durationSec))
        assertNull(CallInbox.parse("owner", 500, inbox))
        assertEquals(emptyList<Any>(), CallInbox.parse("owner", 200, "not json"))
    }

    @Test fun unreadAndAnnounceRules() {
        val items = CallInbox.parse("owner", 200, inbox)!!
        assertEquals(2, CallInbox.unread(items, 0))
        assertEquals(0, CallInbox.unread(items, 1790979027789))
        val keys = items.map { it.key }.toSet()
        assertTrue(CallInbox.toAnnounce(items, keys, 0).isEmpty())
        // only calls after the install/upgrade cut are announced
        assertEquals(listOf("rtc_u1_ayTaC80A"), CallInbox.toAnnounce(items, emptySet(), 1790900000000).map { it.callId })
    }

    @Test fun mergeAcrossIdsIsNewestFirstAndDistinct() {
        val a = CallInbox.parse("a", 200, inbox)!!
        val b = CallInbox.parse("b", 200, inbox)!!
        val m = CallInbox.merge(listOf(a, a, b))
        assertEquals(2, m.size) // 1.2.6.1: one row per call even when two ids list it (tablet showed Ira twice)
        assertTrue(m.zipWithNext().all { (x, y) -> x.at >= y.at })
    }

    @Test fun detailParsesTranscriptLines() {
        val body = """{"ok":true,"item":{"callId":"rtc_u1_ayTaC80A","caller":"+380638500117","text":"Вадим просить передати привіт.","at":1790979027789,"status":"done"},
          "lines":[{"who":"sol","text":"Добрий день, це секретар."},{"who":"caller","text":"Передайте привіт."},{"who":"caller","text":" "}],"durationSec":74}"""
        val d = CallInbox.parseDetail("owner", 200, body)!!
        assertEquals(2, d.lines.size)
        assertFalse(d.lines[0].caller)
        assertTrue(d.lines[1].caller)
        assertEquals(74, d.durationSec)
        assertNull(CallInbox.parseDetail("owner", 404, body))
    }

    @Test fun blockListParses() {
        assertEquals(listOf("+380501112233"), ScreenApi.parseNumbers(200, """{"ok":true,"numbers":["+380501112233",""]}"""))
        assertNull(ScreenApi.parseNumbers(400, "{}"))
    }

    @Test fun callTimeIsKyivLocal() {
        // 1790979027789 = 2026-10-02 22:10:27 UTC = 3 Oct 01:10 in Kyiv (UTC+3)
        assertEquals("Oct 3, 1:10 AM", CallText.time(1790979027789, Locale.ENGLISH))
        assertEquals("3 жовт., 01:10", CallText.time(1790979027789, Locale("uk", "UA")))
    }

    @Test fun linkedIdsAreValidatedAndCapped() {
        assertFalse(CallInbox.link(ctx, "short"))
        assertTrue(CallInbox.link(ctx, "d61556d7-b92a-4a54-aa84-95897565439d"))
        CallInbox.link(ctx, "id-two-123456"); CallInbox.link(ctx, "id-three-123456"); CallInbox.link(ctx, "id-four-123456")
        assertEquals(3, CallInbox.linked(ctx).size)
        assertFalse("oldest dropped", "d61556d7-b92a-4a54-aa84-95897565439d" in CallInbox.linked(ctx))
    }

    // ---- item 4: spoken text ----

    @Test fun speechTextReadsNamesNaturally() {
        assertEquals("Bitcoin вікна номер 11", SpeechText.speakable("Bitcoin-вікна #11", "uk"))
        assertEquals("Agent number 3 is running.", SpeechText.speakable("**Agent** #3 is running ✅.", "en"))
        assertEquals("Метеостанція номер 2: 25°", SpeechText.speakable("Метеостанція №2: 25°", "uk"))
        assertEquals("Відкрий Explorer", SpeechText.speakable("Відкрий Explorer https://explorer.solana.com/tx/abc", "uk"))
        assertEquals("ціна 0,1 SOL", SpeechText.speakable("ціна 0,1 SOL", "uk"))
    }

    // ---- item 5: voice pipeline ----

    @Test fun firstClauseStartsSpeechEarlyButNeverSplitsDecimals() {
        assertEquals(0, SolVoice.firstClauseEnd("Привіт, як ти?"))
        val t = "Сьогодні твій агент закрив дві угоди, обидві в плюс"
        val cut = SolVoice.firstClauseEnd(t)
        assertTrue(cut > 0)
        assertEquals("Сьогодні твій агент закрив дві угоди, ", t.substring(0, cut))
        assertEquals(0, SolVoice.firstClauseEnd("Метеостанція коштує 0,1 SOL і працює на devnet"))
    }

    @Test fun latencyStagesAreMeasuredFromTheLastWord() {
        var now = 1000L
        SolLatency.clock = { now }
        SolLatency.speechEnded(900)
        now = 1100; SolLatency.endOfSpeech()
        now = 1250; SolLatency.sttFinal()
        SolLatency.sent(voice = true)
        now = 1260; SolLatency.request(5)
        now = 1900; SolLatency.firstToken()
        now = 2400; SolLatency.firstAudio(ctx)
        val t = SolLatency.stored(ctx)!!
        assertTrue(t.voice)
        assertEquals(200, t.eos); assertEquals(350, t.stt); assertEquals(360, t.request); assertEquals(1000, t.token); assertEquals(1500, t.audio)
        // a typed question starts its own turn
        now = 50_000; SolLatency.sent(voice = false)
        assertFalse(SolLatency.current!!.voice)
        SolLatency.clock = { android.os.SystemClock.elapsedRealtime() }
    }

    // ---- item 2: one robot, drawn between sim steps ----

    @Test fun renderInterpolationIsBoundedAndSkipsWarps() {
        assertEquals(0.5, Interp.alpha(1.0 / 120, 1.0 / 60), 1e-9)
        assertEquals(1.0, Interp.alpha(1.0, 1.0 / 60), 1e-9)
        assertEquals(15.0, Interp.lerp(10.0, 20.0, 0.5), 1e-9)
        assertTrue(Interp.continuous(100.0, 106.0, 200.0, 190.0))
        assertFalse("restart / warp is not smeared", Interp.continuous(5000.0, 120.0, 200.0, 200.0))
    }

    // ---- copy: both languages, informal Ukrainian ----

    private fun names(path: String): Set<String> =
        Regex("<(?:string|plurals) name=\"([^\"]+)\"").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    @Test fun newScreensAreTranslated() {
        val res = listOf("src/main/res", "app/src/main/res").first { File(it).isDirectory }
        for (f in listOf("strings_calls.xml", "strings_wallet.xml")) {
            val en = names("$res/values/$f")
            val uk = names("$res/values-uk/$f")
            assertTrue(f, en.isNotEmpty())
            assertEquals("$f: keys missing in one language", en, uk)
        }
    }

    @Test fun ukrainianCopyIsInformal() {
        val res = listOf("src/main/res", "app/src/main/res").first { File(it).isDirectory }
        val formal = Regex("(?iu)\\b(ваш|ваша|ваше|ваші|вашого|вашій|ваших|вас|вам|ви)\\b|\\b(натисніть|спробуйте|введіть|зачекайте|оберіть|відкрийте|встановіть|запитайте|створіть|купіть)\\b")
        val bad = File("$res/values-uk").listFiles()!!.filter { it.name.endsWith(".xml") }.flatMap { f ->
            Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())
                .filter { formal.containsMatchIn(it.groupValues[2]) }.map { "${f.name}:${it.groupValues[1]}" }.toList()
        }
        assertTrue("formal Ukrainian: $bad", bad.isEmpty())
        val uk = File("$res/values-uk/strings.xml").readText()
        assertFalse(uk.contains("підписати гаманець"))
        assertNotNull(Regex("name=\"report_unsigned\">[^<]*%1\\\$d м").find(uk))
    }
}
