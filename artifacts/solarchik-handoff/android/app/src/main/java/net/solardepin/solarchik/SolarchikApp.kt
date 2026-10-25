package net.solardepin.solarchik

import android.app.Application
import net.solardepin.solarchik.agents.engine.DeskHooks
import net.solardepin.solarchik.notify.DeskNotes
import net.solardepin.solarchik.notify.Notes

class SolarchikApp : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(net.solardepin.solarchik.core.AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // 1.2.15: per-install secret for the secretary worker (calls, transcripts, balance, settings)
        runCatching { net.solardepin.solarchik.screen.ScreenApi.installKey = net.solardepin.solarchik.screen.PlayerIds.key(this) }
        runCatching { net.solardepin.solarchik.core.CrashReport.install(this) }
        Notes.createChannel(this)
        runCatching { Notes.schedule(this) }
        // 0.21.9: secretary call notes (background poll every 15 min; the open app polls every 60 s)
        runCatching { net.solardepin.solarchik.screen.CallNotes.createChannel(this) }
        runCatching { net.solardepin.solarchik.screen.CallNotes.schedule(this) }
        // Background desk ticks (DeskWorker) announce closes; the open app shows them on screen.
        DeskHooks.afterTick = { ctx, report -> runCatching { DeskNotes.onTick(ctx, report) } }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // no run on screen and the system is short on memory: drop the preloaded run art
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) net.solardepin.solarchik.game.run.RunPreload.trim()
    }
}
