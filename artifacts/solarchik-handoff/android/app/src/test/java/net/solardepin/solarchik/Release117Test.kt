package net.solardepin.solarchik

import net.solardepin.solarchik.screen.CallText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** 1.1.7: dates in the app's UI language (not the phone's), and times Sol can say out loud. */
class Release117Test {
    // 2026-10-10 13:32 Kyiv (UTC+3)
    private val at = java.time.ZonedDateTime.of(2026, 10, 10, 13, 32, 0, 0, java.time.ZoneId.of("Europe/Kyiv")).toInstant().toEpochMilli()

    @Test fun englishUiShowsEnglishDates() {
        val s = CallText.time(at, Locale.US)
        assertEquals("Oct 10, 1:32 PM", s)
        assertFalse(Regex("[а-яіїєґ]", RegexOption.IGNORE_CASE).containsMatchIn(s))
    }

    @Test fun ukrainianUiKeepsUkrainianDates() {
        assertTrue(CallText.time(at, Locale("uk", "UA")).startsWith("10 "))
        assertTrue(CallText.time(at, Locale("uk", "UA")).endsWith("13:32"))
    }

    @Test fun solSaysTimesInWords() {
        assertEquals("today at 1:32 PM", CallText.spoken(at, at + 3_600_000, Locale.US))
        assertEquals("yesterday at 1:32 PM", CallText.spoken(at, at + 86_400_000, Locale.US))
        assertEquals("сьогодні о 13:32", CallText.spoken(at, at + 60_000, Locale("uk", "UA")))
        assertTrue(CallText.spoken(at, at + 5 * 86_400_000L, Locale.US).startsWith("on October 10"))
    }
}
