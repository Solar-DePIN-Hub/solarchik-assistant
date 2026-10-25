package net.solardepin.solarchik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.screen.Secretary
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

    @Test fun forwardLineFollowsTheCountry() {
        assertEquals("+380914810885", Secretary.lineFor("ua").number)
        assertEquals("+18325009920", Secretary.lineFor("US").number)
        assertEquals("+15816899510", Secretary.lineFor("ca").number)
        assertEquals("+442045771829", Secretary.lineFor("GB").number)
        assertEquals("+4532330495", Secretary.lineFor("DK").number)
        assertEquals("+18325009920", Secretary.lineFor("MX").number) // Americas -> US
        assertEquals("+442045771829", Secretary.lineFor("DE").number) // Europe -> GB
        assertEquals("+380914810885", Secretary.lineFor("JP").number) // elsewhere -> UA
        assertEquals("+380914810885", Secretary.lineFor("").number)
        assertEquals("🇺🇦", Secretary.flag("ua"))
    }

    @Test fun mmiCodesUseTheChosenLine() {
        app.getSharedPreferences("solarchik.secretary", Context.MODE_PRIVATE).edit().clear().commit()
        Secretary.clearForwardChoice(app)
        assertEquals(Secretary.autoLine(app).number, Secretary.forwardNumber(app))
        Secretary.setForwardNumber(app, "+4532330495")
        assertEquals("**004*+4532330495#", Secretary.forwardAllCode(Secretary.forwardNumber(app)))
        assertEquals("**61*+4532330495#", Secretary.forwardOnCode(Secretary.Forward.NO_ANSWER, Secretary.forwardNumber(app)))
        Secretary.clearForwardChoice(app)
        assertEquals(Secretary.autoLine(app).number, Secretary.forwardNumber(app))
    }

    @Test fun workerLinesOverrideTheBuiltInList() {
        val j = """{"lines":[{"country":"DE","number":"+4930123456"},{"country":"x","number":"bad"}]}"""
        val l = Secretary.parseLines(j)
        assertEquals(listOf(Secretary.Line("DE", "+4930123456")), l)
        assertEquals("+4930123456", Secretary.lineFor("de", l).number)
        assertTrue(Secretary.parseLines("nope").isEmpty())
    }

    @Test fun e164UsesTheSimCountry() {
        assertEquals("+12025550123", Secretary.e164("(202) 555-0123", "US"))
        assertEquals("+380441234567", Secretary.e164("044 123 4567", "UA"))
        assertEquals("+380441234567", Secretary.e164("0441234567"))
        assertEquals("+4532330495", Secretary.e164("+45 32 33 04 95", "US"))
    }

    @Test fun cdmaCodesForUsAndCanada() {
        assertTrue(Secretary.usesCdmaCodes("us")); assertTrue(Secretary.usesCdmaCodes("CA")); assertTrue(!Secretary.usesCdmaCodes("UA"))
        assertEquals("*7218325009920", Secretary.cdmaForwardCode("+18325009920"))
        assertEquals("*7118325009920", Secretary.cdmaNoAnswerCode("+18325009920"))
        assertEquals("*73", Secretary.CDMA_OFF)
    }

    @Test fun banterComesFromResourcesInBothLanguages() {
        net.solardepin.solarchik.game.run.RunBanter.load(app, "en")
        net.solardepin.solarchik.game.run.RunBanter.load(app, "uk")
        val en = net.solardepin.solarchik.game.run.RunBanter.pool(net.solardepin.solarchik.game.run.BanterKind.GO, "en", null)
        val uk = net.solardepin.solarchik.game.run.RunBanter.pool(net.solardepin.solarchik.game.run.BanterKind.GO, "uk", null)
        assertTrue(en.first().startsWith("Let's go"))
        assertTrue(uk.first().startsWith("Поїхали"))
        assertEquals("12 м, комбо 3, сонечок 4, сердець 2", net.solardepin.solarchik.game.run.RunBanter.context(12, net.solardepin.solarchik.game.run.ChapterId.NIGHT, 3, 4, 2, "uk"))
    }

    @Test fun limitMessagesSayUtcReset() {
        assertTrue(net.solardepin.solarchik.screen.CallText.resetNote(app).contains("00:00 UTC"))
    }
}
