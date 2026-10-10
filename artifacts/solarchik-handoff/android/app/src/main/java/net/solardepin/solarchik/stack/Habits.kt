package net.solardepin.solarchik.stack

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * 1.2.7 daily habits: each one switched on adds one card a day to the Morning stack. Five presets plus your own.
 * Rules: "Save USDC" is opt-in (off by default), its amount is yours (at least [MIN_SAVE]) and it goes to a second
 * address of YOURS (the app never holds funds). A habit card you skip, snooze, or that auto-skips (not enough USDC)
 * never blocks the clock-in: clocking in never costs money.
 */
object Habits {
    const val SAVE = "save_usdc"
    const val SEASON = "season"
    const val WALLET = "check_wallet"
    const val CALL = "call_close"
    const val WORKOUT = "workout"
    val PRESETS = listOf(SAVE, SEASON, WALLET, CALL, WORKOUT)
    const val MIN_SAVE = 0.1
    const val DEFAULT_SAVE = 1.0
    const val PREFS = "solarchik.habits"

    data class Custom(val id: String, val title: String)

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today(now: Long, zone: ZoneId = ZoneId.systemDefault()) = MorningStack.day(now, zone).toString()

    /** Only the Season task is on for a fresh install (it was already in the 1.2.6 stack); Save USDC never is. */
    fun defaultOn(id: String): Boolean = id == SEASON

    fun on(ctx: Context, id: String): Boolean = if (id.startsWith("c")) customs(ctx).any { it.id == id } else p(ctx).getBoolean("on:$id", defaultOn(id))

    fun set(ctx: Context, id: String, on: Boolean) { p(ctx).edit().putBoolean("on:$id", on).apply() }

    /** Habits switched on (presets in their order, then custom ones). */
    fun enabled(ctx: Context): List<String> = PRESETS.filter { on(ctx, it) } + customs(ctx).map { it.id }

    // ------------------------------------------------------------------ custom habits

    fun customs(ctx: Context): List<Custom> = runCatching {
        val a = JSONArray(p(ctx).getString("custom", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Custom(o.getString("id"), o.optString("title")) } }
    }.getOrDefault(emptyList())

    fun addCustom(ctx: Context, title: String): Custom? {
        val t = title.trim().take(40)
        if (t.isBlank()) return null
        val c = Custom("c" + System.currentTimeMillis().toString(36), t)
        val a = JSONArray(); (customs(ctx) + c).forEach { a.put(JSONObject().put("id", it.id).put("title", it.title)) }
        p(ctx).edit().putString("custom", a.toString()).apply()
        return c
    }

    fun removeCustom(ctx: Context, id: String) {
        val a = JSONArray(); customs(ctx).filterNot { it.id == id }.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title)) }
        p(ctx).edit().putString("custom", a.toString()).apply()
    }

    fun title(ctx: Context, id: String): String {
        val r = ctx.resources
        return when (id) {
            SAVE -> r.getString(net.solardepin.solarchik.R.string.habit_save_usdc_n, net.solardepin.solarchik.circle.Circle.amount(saveAmount(ctx)))
            SEASON -> r.getString(net.solardepin.solarchik.R.string.habit_season)
            WALLET -> r.getString(net.solardepin.solarchik.R.string.habit_wallet)
            CALL -> callContact(ctx)?.let { r.getString(net.solardepin.solarchik.R.string.habit_call_who, it.name) } ?: r.getString(net.solardepin.solarchik.R.string.habit_call)
            WORKOUT -> r.getString(net.solardepin.solarchik.R.string.habit_workout)
            else -> customs(ctx).firstOrNull { it.id == id }?.title ?: id
        }
    }

    // ------------------------------------------------------------------ Save USDC settings

    fun saveAmount(ctx: Context): Double = p(ctx).getFloat("save_amount", DEFAULT_SAVE.toFloat()).toDouble().let { java.math.BigDecimal.valueOf(it).setScale(6, java.math.RoundingMode.HALF_UP).toDouble() }

    /** Pure: the amount the user typed, or null when it is not a number of at least [MIN_SAVE]. */
    fun parseAmount(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= MIN_SAVE && it <= 10_000 }

    fun setSaveAmount(ctx: Context, v: Double) { p(ctx).edit().putFloat("save_amount", v.coerceAtLeast(MIN_SAVE).toFloat()).apply() }
    fun saveTo(ctx: Context): String = p(ctx).getString("save_to", "").orEmpty()
    fun setSaveTo(ctx: Context, addr: String) { p(ctx).edit().putString("save_to", addr.trim()).apply() }

    /** Save USDC can be on only with your own second address (never the wallet itself). */
    fun saveReady(ctx: Context): Boolean = saveTo(ctx).isNotBlank()

    // ------------------------------------------------------------------ "Call someone close"

    fun callContactId(ctx: Context): String = p(ctx).getString("call_contact", "").orEmpty()
    fun setCallContact(ctx: Context, id: String) { p(ctx).edit().putString("call_contact", id).apply() }
    fun callContact(ctx: Context): net.solardepin.solarchik.circle.Contact? =
        callContactId(ctx).takeIf { it.isNotBlank() }?.let { net.solardepin.solarchik.circle.CircleStore(ctx).find(it) }?.takeIf { it.phone.isNotBlank() }

    // ------------------------------------------------------------------ the day

    fun doneToday(ctx: Context, id: String, now: Long = System.currentTimeMillis()): Boolean = p(ctx).getString("done:$id", "") == today(now)
    fun skippedToday(ctx: Context, id: String, now: Long = System.currentTimeMillis()): Boolean = p(ctx).getString("skip:$id", "") == today(now)

    fun markDone(ctx: Context, id: String, now: Long = System.currentTimeMillis()) {
        p(ctx).edit().putString("done:$id", today(now)).apply()
        MorningStack.touched(ctx, now)
    }

    /** Auto-skip (e.g. not enough USDC): gone for today, and it never blocks the clock-in. */
    fun markSkipped(ctx: Context, id: String, now: Long = System.currentTimeMillis()) {
        p(ctx).edit().putString("skip:$id", today(now)).apply()
        MorningStack.touched(ctx, now)
    }

    /** Habit cards waiting today (the Season task is the Season card, not a habit card). */
    fun pending(ctx: Context, now: Long = System.currentTimeMillis()): List<String> = enabled(ctx).filter { id ->
        id != SEASON && !doneToday(ctx, id, now) && !skippedToday(ctx, id, now) && MorningStack.snoozedUntil(ctx, key(id)) <= now &&
            (id != SAVE || saveReady(ctx))
    }

    fun key(id: String) = "habit:$id"

    // ------------------------------------------------------------------ savings ledger (confirmed transfers only)

    data class Saved(val at: Long, val amount: Double, val signature: String)

    fun savedLog(ctx: Context): List<Saved> = runCatching {
        val a = JSONArray(p(ctx).getString("saved", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Saved(o.getLong("at"), o.getDouble("amount"), o.getString("sig")) } }
    }.getOrDefault(emptyList())

    fun saved(ctx: Context): Double = savedLog(ctx).fold(java.math.BigDecimal.ZERO) { s, x -> s + java.math.BigDecimal.valueOf(x.amount) }.toDouble()

    /** Called only after Solana confirmed the transfer; the card is Done then, not before. */
    fun addSaved(ctx: Context, amount: Double, signature: String, now: Long = System.currentTimeMillis()) {
        if (savedLog(ctx).any { it.signature == signature }) return
        val a = JSONArray(); (savedLog(ctx) + Saved(now, amount, signature)).takeLast(400).forEach { a.put(JSONObject().put("at", it.at).put("amount", it.amount).put("sig", it.signature)) }
        p(ctx).edit().putString("saved", a.toString()).remove("pending_sig").apply()
        markDone(ctx, SAVE, now)
    }

    /** A save that was sent but not confirmed yet (checked again next time Today opens). */
    fun pendingSig(ctx: Context): String = p(ctx).getString("pending_sig", "").orEmpty()
    fun pendingAmount(ctx: Context): Double = p(ctx).getFloat("pending_amount", saveAmount(ctx).toFloat()).toDouble()
    fun setPendingSig(ctx: Context, sig: String, amount: Double) { p(ctx).edit().putString("pending_sig", sig).putFloat("pending_amount", amount.toFloat()).apply() }

    /** "Check wallet": the overnight change in one sentence, from the last snapshot (read-only; null when unknown). */
    fun walletLine(ctx: Context, before: Double?, now: Double?): String {
        val r = ctx.resources
        if (now == null) return r.getString(net.solardepin.solarchik.R.string.habit_wallet_unknown)
        if (before == null) return r.getString(net.solardepin.solarchik.R.string.habit_wallet_first, net.solardepin.solarchik.ui.Fmt.sol(now))
        val d = java.math.BigDecimal.valueOf(now).subtract(java.math.BigDecimal.valueOf(before)).setScale(4, java.math.RoundingMode.HALF_UP)
        return when {
            d.signum() == 0 -> r.getString(net.solardepin.solarchik.R.string.habit_wallet_same, net.solardepin.solarchik.ui.Fmt.sol(now))
            d.signum() > 0 -> r.getString(net.solardepin.solarchik.R.string.habit_wallet_up, d.stripTrailingZeros().toPlainString(), net.solardepin.solarchik.ui.Fmt.sol(now))
            else -> r.getString(net.solardepin.solarchik.R.string.habit_wallet_down, d.negate().stripTrailingZeros().toPlainString(), net.solardepin.solarchik.ui.Fmt.sol(now))
        }
    }
}

/** 1.2.7: one card of the Morning stack (calls, habits, Season). */
sealed class StackItem {
    abstract val key: String
    data class Call(val a: net.solardepin.solarchik.screen.CallAction) : StackItem() { override val key get() = a.id }
    data class Habit(val id: String) : StackItem() { override val key get() = Habits.key(id) }
    data class Season(val d: net.solardepin.solarchik.season.SeasonDrop) : StackItem() { override val key get() = "season:" + d.id }
}
