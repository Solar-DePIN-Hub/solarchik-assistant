package net.solardepin.solarchik.game

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.solardepin.solarchik.core.FeeProgress
import net.solardepin.solarchik.core.FeeWindow
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.core.StreakRules
import net.solardepin.solarchik.core.StreakState
import java.time.LocalDate
import java.time.ZoneOffset

/** One signed day, kept for the yard history (native extra; web keeps only clockDays). */
@Serializable
data class ClockEntry(
    val day: String,
    val sig: String,
    val kind: String,
    val cluster: String,
    val meters: Int,
    val at: Long,
)

/**
 * Local save. Storage keys for the streak and fee windows match the Grok export
 * (streak, signedDay, seven, thirty, clockDays, feeWindows) so both builds read the same prefs.
 */
class GameSave(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val prefs = context.applicationContext.getSharedPreferences("solarchik-game", Context.MODE_PRIVATE)

    var lastDistance: Int
        get() = prefs.getInt("lastDistance", 0)
        set(value) { prefs.edit().putInt("lastDistance", value).apply() }

    var bestDistance: Int
        get() = prefs.getInt("bestDistance", 0)
        set(value) { prefs.edit().putInt("bestDistance", value).apply() }

    var lastScore: Int
        get() = prefs.getInt("lastScore", 0)
        set(value) { prefs.edit().putInt("lastScore", value).apply() }

    /** The first-run tutorial hints were shown once; later runs go without them. */
    var runTutorialDone: Boolean
        get() = prefs.getBoolean("runTutorialDone", false)
        set(value) { prefs.edit().putBoolean("runTutorialDone", value).apply() }

    /** Roof-run music volume, 0..100 (RunAudio reads the same key). */
    var runMusicVol: Int
        get() = prefs.getInt("runMusicVol", 70).coerceIn(0, 100)
        set(value) { prefs.edit().putInt("runMusicVol", value.coerceIn(0, 100)).apply() }

    /** Roof-run effects volume, 0..100; 0 silences the effects. */
    var runSfxVol: Int
        get() = prefs.getInt("runSfxVol", 90).coerceIn(0, 100)
        set(value) { prefs.edit().putInt("runSfxVol", value.coerceIn(0, 100)).apply() }

    var bestScore: Int
        get() = prefs.getInt("bestScore", 0)
        set(value) { prefs.edit().putInt("bestScore", value).apply() }

    /** Live streak (0 once a UTC day was missed). Only [stampClock] raises it. */
    val streak: Int
        get() = liveStreak().streak

    var lastClockDay: String
        get() = prefs.getString("lastClockDay", "").orEmpty()
        set(value) { prefs.edit().putString("lastClockDay", value).apply() }

    var signedDay: String
        get() = prefs.getString("signedDay", "").orEmpty()
        set(value) { prefs.edit().putString("signedDay", value).apply() }

    var clockSig: String
        get() = prefs.getString("clockSig", "").orEmpty()
        set(value) { prefs.edit().putString("clockSig", value).apply() }

    var clockKind: String
        get() = prefs.getString("clockKind", "").orEmpty()
        set(value) { prefs.edit().putString("clockKind", value).apply() }

    var clockCluster: String
        get() = prefs.getString("clockCluster", "").orEmpty()
        set(value) { prefs.edit().putString("clockCluster", value).apply() }

    var clockAddress: String
        get() = prefs.getString("clockAddress", "").orEmpty()
        set(value) { prefs.edit().putString("clockAddress", value).apply() }

    fun now(): Long = clock()
    fun today(): String = StreakRules.dayKey(clock())

    fun dayMod(): String = Companion.dayModOf(today())

    fun clockedToday(): Boolean = lastClockDay == today() && lastDistance >= GOAL_M

    /** 1.1.1: today's check-in can be signed (the assistant needs no run; see SolarchikConfig.CHECKIN_NEEDS_RUN). */
    fun checkInOpen(): Boolean = !net.solardepin.solarchik.core.SolarchikConfig.CHECKIN_NEEDS_RUN || clockedToday()

    /** Best distance run today (0 after a new UTC day until the next run). */
    fun todayDistance(): Int = if (prefs.getString("runDay", "") == today()) lastDistance else 0
    fun todayScore(): Int = if (prefs.getString("runDay", "") == today()) lastScore else 0
    fun signedToday(): Boolean = signedDay == today()

    fun recordRun(meters: Int, score: Int) {
        val today = today()
        runs += 1
        if (prefs.getString("runDay", "") != today) {
            lastDistance = 0
            lastScore = 0
            prefs.edit().putString("runDay", today).apply()
        }
        if (meters >= lastDistance) {
            lastDistance = meters
            lastScore = score
        }
        if (meters > bestDistance) bestDistance = meters
        if (score > bestScore) bestScore = score
        // The run only unlocks today's CLOCK IN. The streak moves on the signed CLOCK IN.
        if (meters < GOAL_M || lastClockDay == today) return
        lastClockDay = today
    }

    /**
     * The run crossed [GOAL_M]: today's CLOCK IN opens right away (the run keeps going, so the
     * unlock must not wait for the last heart). The final distance is still recorded by [recordRun].
     */
    fun unlockClock(meters: Int, score: Int) {
        val today = today()
        if (prefs.getString("runDay", "") != today) {
            lastDistance = 0
            lastScore = 0
            prefs.edit().putString("runDay", today).apply()
        }
        if (meters >= lastDistance) {
            lastDistance = meters
            lastScore = score
        }
        if (meters > bestDistance) bestDistance = meters
        if (meters >= GOAL_M) lastClockDay = today
    }

    // ---- Streak + fee-free windows (rules in core/StreakRules.kt, same as web save.ts) ----

    fun streakState(): StreakState {
        val streakNow = prefs.getInt("streak", 0)
        val signed = signedDay
        val daysRaw = prefs.getString("clockDays", null)
        val days = daysRaw?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?.filter { DAY.matches(it) }?.distinct()?.take(SolarchikConfig.CLOCK_DAYS_KEEP)
            ?: StreakRules.seedDays(signed, streakNow)
        return StreakState(
            streak = streakNow,
            signedDay = signed,
            seven = if (prefs.contains("seven")) prefs.getInt("seven", 0) else streakNow % SolarchikConfig.STREAK_SHORT_DAYS,
            thirty = if (prefs.contains("thirty")) prefs.getInt("thirty", 0) else streakNow,
            clockDays = days,
            feeWindows = readWindows(),
        )
    }

    /** Normalized for today: a broken streak reads as 0. */
    fun liveStreak(): StreakState {
        val raw = streakState()
        val norm = StreakRules.normalize(raw, today())
        if (norm != raw) writeStreak(norm)
        return norm
    }

    private fun writeStreak(s: StreakState) {
        prefs.edit()
            .putInt("streak", s.streak)
            .putString("signedDay", s.signedDay)
            .putInt("seven", s.seven)
            .putInt("thirty", s.thirty)
            .putString("clockDays", json.encodeToString(ListSerializer(String.serializer()), s.clockDays))
            .putString("feeWindows", json.encodeToString(ListSerializer(FeeWindow.serializer()), s.feeWindows))
            .apply()
    }

    private fun readWindows(): List<FeeWindow> {
        val raw = prefs.getString("feeWindows", null) ?: return emptyList()
        return runCatching { net.solardepin.solarchik.core.FeeWindows.read(json.parseToJsonElement(raw)) }.getOrDefault(emptyList())
    }

    /** Streak the memo will carry if today gets signed now. */
    fun nextStreak(): Int {
        val s = liveStreak()
        if (s.signedDay == today()) return s.streak
        return if (s.signedDay == StreakRules.prevDay(today())) s.streak + 1 else 1
    }

    /**
     * Returns the windows granted by this stamp (empty when none). [day] is the UTC day the run and
     * the signature belong to: a wallet prompt opened before midnight and signed after it still
     * stamps the day that was run, not the new one.
     */
    fun stampClock(address: String, signature: String, cluster: String, kind: String, day: String = today(), meters: Int = lastDistance): List<FeeWindow> {
        val before = StreakRules.normalize(streakState(), day)
        val after = StreakRules.stamp(before, day, now())
        writeStreak(after)
        clockAddress = address
        clockSig = signature
        clockCluster = cluster
        clockKind = kind
        addLog(ClockEntry(day, signature.take(100), kind, cluster, meters, now()))
        return after.feeWindows.filter { w -> before.feeWindows.none { it.id == w.id } }
    }

    fun activateWindow(): Boolean {
        val before = liveStreak()
        val after = StreakRules.activate(before, now())
        writeStreak(after)
        return after.feeWindows.any { it.status == FeeWindow.ACTIVE } && before.feeWindows != after.feeWindows
    }

    fun feeProgress(): FeeProgress = StreakRules.progress(liveStreak(), now())

    fun feeWindowCovers(openedAt: Long): Boolean = StreakRules.covers(liveStreak(), openedAt)

    fun clockLog(): List<ClockEntry> {
        val raw = prefs.getString("clockLog", null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(ClockEntry.serializer()), raw) }.getOrDefault(emptyList())
    }

    private fun addLog(entry: ClockEntry) {
        val all = clockLog().filter { it.day != entry.day } + entry
        prefs.edit().putString("clockLog", json.encodeToString(ListSerializer(ClockEntry.serializer()), all.takeLast(400))).apply()
    }

    // ---- Notification switches (keys shared with the Grok export DayAlerts) ----
    fun noteOn(key: String): Boolean = !assistantOff(key) && prefs.getBoolean(key, true)
    fun setNote(key: String, on: Boolean) { prefs.edit().putBoolean(key, on).apply() }

    var runs: Int
        get() = prefs.getInt("runs", 0)
        set(value) { prefs.edit().putInt("runs", value).apply() }

    /** web: offerBonus = (runs + 1) % 15 === 0 */
    fun offerBonus(): Boolean = (runs + 1) % 15 == 0

    companion object {
        /** 1.1.2: game-only reminders (fee-free windows, the game's daily note, the trading desk) are off in the assistant. */
        private val GAME_ONLY_NOTES = setOf("noteReward", "noteWindow", "noteReport", "noteDesk")
        fun assistantOff(key: String): Boolean = net.solardepin.solarchik.core.SolarchikConfig.SOL_APP == "assistant" && key in GAME_ONLY_NOTES

        const val GOAL_M = SolarchikConfig.RUN_GOAL_M
        private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun dayModOf(day: String): String {
            val mods = arrayOf("calm", "wind", "gold", "drones", "wire")
            val h = dayHash(day)
            return mods[(h % mods.size).toInt()]
        }

        private fun dayHash(key: String): Long {
            var h = 2166136261L
            for (ch in key) {
                h = h xor ch.code.toLong()
                h = (h * 16777619L) and 0xFFFFFFFFL
            }
            return h
        }
    }
}
