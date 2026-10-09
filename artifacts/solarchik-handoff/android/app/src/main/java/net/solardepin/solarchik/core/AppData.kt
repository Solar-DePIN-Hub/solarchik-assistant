package net.solardepin.solarchik.core

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager

/**
 * Everything this app keeps about the player lives on the phone, in these preference files.
 * Settings > Privacy & data > "Delete my data" wipes all of them (dApp Store Publisher Policy:
 * users must be able to delete their data). Nothing is held on a server under the player's name;
 * on-chain records (CLOCK IN memos, minted NFTs, fee transfers) are public and cannot be erased.
 */
object AppData {
    /** Every SharedPreferences file the app writes. Keep in sync when a new store is added. */
    val PREFS = listOf(
        "solarchik-game",
        "solarchik.swap",
        "solarchik-lang",
        "solarchik-slice",
        "solarchik-agents",
        "solarchik-desk",
        "solarchik-notes",
        "solarchik-sol",
        "solarchik-voice",
        "solarchik.desk",
        "solarchik.player",
        "solarchik.secretary",
        "seeker-wallet",
        // 0.21.9
        "solarchik.calls",
        // 0.22.0 rooftop (tour seen / last visit)
        "solarchik-roof",
        "solarchik.calls.remind",
        "solarchik-local-wallet",
        // 1.0.0 assistant: onboarding seen, wallet balance cache, follow-ups done
        "solarchik.assistant",
        "solarchik.followups", "solarchik.season",
        // 1.1.0 Season autopilot, experimental delegated limit (+ its sealed agent key)
        "solarchik.autopilot", "solarchik.delegate", "solarchik-agent-key",
        "solarchik.saver", "solarchik.watcher", "solarchik.briefing", "solarchik.callactions",
    )

    /** Background jobs that would otherwise keep ticking with old state. */
    val WORKS = listOf("solarchik-desk", "solarchik-notes", "solarchik-calls-poll", "solarchik-season-auto", "solarchik-briefing")

    const val PRIVACY_URL = "https://github.com/Solar-DePIN-Hub/solarchik-assistant/blob/main/PRIVACY.md"

    // commit(), not apply(): the activity is recreated right after, so the wipe must be on disk first.
    @SuppressLint("ApplySharedPref")
    fun wipe(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { WORKS.forEach { WorkManager.getInstance(app).cancelUniqueWork(it) } }
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll() }
        PREFS.forEach { name ->
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            app.deleteSharedPreferences(name)
        }
        // Sol's cached voice clips (texts Sol said to this player)
        runCatching { java.io.File(app.cacheDir, "sol-voice").deleteRecursively() }
        runCatching { java.io.File(app.cacheDir, "sol-voice-oa").deleteRecursively() }
        net.solardepin.solarchik.sol.SolHandoff.take()
        // the built-in devnet wallet's Keystore key goes too (its devnet SOL is test money)
        runCatching { net.solardepin.solarchik.wallet.LocalKey.delete(app) }
        // 1.1.0: the delegated-mode agent key (the UI asks to withdraw its SOL first)
        runCatching { net.solardepin.solarchik.delegate.AgentKey.delete(app) }
    }
}
