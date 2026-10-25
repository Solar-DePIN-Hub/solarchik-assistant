package net.solardepin.solarchik.screen

import android.content.Context
import java.util.UUID

object PlayerIds {
    private const val PREF = "solarchik.player"
    private const val KEY = "userId"
    private const val SCREEN = "screeningOn"
    private const val KEY_SECRET = "installKey"

    fun get(ctx: Context): String {
        val p = prefs(ctx)
        val had = p.getString(KEY, null).orEmpty()
        if (had.isNotBlank()) return had
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY, id).apply()
        return id
    }

    /**
     * 1.2.15 per-install secret (64 hex chars, SecureRandom), sent as X-Sol-Key to the secretary worker. The worker
     * binds the first key it sees to this user id; after that only this install can read its calls and settings.
     */
    fun key(ctx: Context): String {
        val p = prefs(ctx)
        val had = p.getString(KEY_SECRET, null).orEmpty()
        if (had.length == 64) return had
        val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val k = b.joinToString("") { "%02x".format(it) }
        p.edit().putString(KEY_SECRET, k).commit()
        return k
    }

    fun bind(ctx: Context, id: String) {
        val clean = id.trim()
        if (clean.length < 8 || clean.length > 80 || clean.any { it.isWhitespace() }) return
        prefs(ctx).edit().putString(KEY, clean).apply()
    }

    fun screeningOn(ctx: Context): Boolean = prefs(ctx).getBoolean(SCREEN, false)

    fun setScreening(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(SCREEN, on).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
