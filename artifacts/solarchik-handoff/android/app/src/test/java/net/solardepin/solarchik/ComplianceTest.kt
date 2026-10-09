package net.solardepin.solarchik

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import net.solardepin.solarchik.core.AppData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * CLOCK IN / Solana dApp Store checks that can be made without a device:
 * permissions are only the ones the app uses, MWA is reachable, the player can wipe their data,
 * and the 512 px store icon is rendered from the same launcher drawable the APK ships.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ComplianceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun requested(): Set<String> =
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()

    /** Each dangerous permission and why (also in PRIVACY.md and docs/DAPP_STORE.md). */
    private val dangerousAllowed = mapOf(
        "android.permission.RECORD_AUDIO" to "Sol voice input (SpeechRecognizer), asked on first mic tap",
        "android.permission.POST_NOTIFICATIONS" to "streak / reward / desk notes, asked from Settings",
    )

    @Test fun onlyUsedPermissions() {
        val perms = requested()
        listOf(
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.REORDER_TASKS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.REQUEST_INSTALL_PACKAGES",
        ).forEach { assertFalse("$it must not be requested", it in perms) }
        val dangerous = perms.filter {
            runCatching { ctx.packageManager.getPermissionInfo(it, 0).protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
        }
        dangerous.forEach { assertTrue("undocumented dangerous permission $it", it in dangerousAllowed) }
        assertTrue("android.permission.INTERNET" in perms)
    }

    @Test fun releaseFacts() {
        assertEquals("net.solardepin.solarchik.assistant", BuildConfig.APPLICATION_ID)
        assertFalse("paid mainnet mint must stay off", BuildConfig.MAINNET_PAID_MINT)
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        assertEquals(35, info.targetSdkVersion)
        assertTrue(info.minSdkVersion >= 26)
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun mobileWalletAdapterIsWired() {
        // MWA clientlib-ktx is on the classpath and the manifest can see MWA wallets (<queries>).
        Class.forName("com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter")
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("solana.mobile.intent.action.WALLET_ASSOCIATE"))
        assertFalse("no WebView anywhere", File("src/main/java").walkTopDown().filter { it.extension == "kt" }.any { it.readText().contains("android.webkit.WebView") })
    }

    @Test fun deleteMyDataWipesEveryStore() {
        AppData.PREFS.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().putString("x", "1").putInt("streak", 9).commit() }
        AppData.wipe(ctx)
        AppData.PREFS.forEach { assertTrue("$it not wiped", ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty()) }
        // every getSharedPreferences("…") name in the sources is covered by the wipe list
        val names = Regex("getSharedPreferences\\(\"([^\"]+)\"|PREFS? = \"([^\"]+)\"")
        val used = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> names.findAll(f.readText()).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } } }
            .toSet()
        assertTrue("missing from AppData.PREFS: ${used - AppData.PREFS.toSet()}", AppData.PREFS.containsAll(used))
    }

    @Test fun storeIcon512FromLauncherDrawable() {
        val d = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher)!!
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 512, 512)
        d.draw(Canvas(bmp))
        assertEquals(512, bmp.width)
        assertEquals(512, bmp.height)
        // not blank: the centre pixel is painted
        assertTrue(android.graphics.Color.alpha(bmp.getPixel(256, 256)) > 0)
        val out = File(System.getProperty("solarchik.shots") ?: "build/screens").parentFile!!.resolve("store")
        out.mkdirs()
        File(out, "icon-512.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
