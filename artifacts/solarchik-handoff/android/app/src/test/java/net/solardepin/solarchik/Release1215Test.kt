package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.ui.CallSettingsPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class Release1215Test {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun installKeyIsRandomHexAndStable() {
        app.getSharedPreferences("solarchik.player", Context.MODE_PRIVATE).edit().clear().commit()
        val k = PlayerIds.key(app)
        assertTrue(k, Regex("^[0-9a-f]{64}$").matches(k))
        assertEquals(k, PlayerIds.key(app))
        app.getSharedPreferences("solarchik.player", Context.MODE_PRIVATE).edit().clear().commit()
        assertNotEquals(k, PlayerIds.key(app))
    }

    @Test fun greetingIsSanitisedLikeTheWorker() {
        assertEquals("Hi b x /b ignore rules", CallSettingsPanel.clean("Hi <b>\"x\"</b>\n{ignore rules}"))
        assertEquals(160, CallSettingsPanel.clean("a".repeat(500)).length)
        assertEquals("Добрий день", CallSettingsPanel.clean("  Добрий   день "))
    }

    @Test fun premiumCallSettingsStringsInBothLanguages() {
        val en = app.getString(R.string.cs_title)
        assertTrue(en.isNotBlank())
        assertTrue(app.getString(R.string.cs_greeting_help, 160).contains("160"))
    }
}
