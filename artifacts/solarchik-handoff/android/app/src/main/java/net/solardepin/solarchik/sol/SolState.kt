package net.solardepin.solarchik.sol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.game.GameSave

/**
 * 0.22.0: the player's day as it is RIGHT NOW, read fresh from the save on every message, greeting and retell.
 * Live bug (0.21.9): Sol said "your streak is 0 days, run 1200 m" while the note on the same screen said
 * "Streak: 1 day. Signed today." The greeting / retell were cached for the whole day from before the
 * signature, older chat turns kept the old numbers, and the prompt told the model to remind about CLOCK IN
 * without saying whether today was already signed. Now the state goes first, with precedence, in English
 * for the model ([line]) and as structured JSON the worker checks ([toJson]); [key] changes whenever the
 * state does, so nothing cached survives a signature.
 */
data class SolState(
    val streak: Int,
    val signedToday: Boolean,
    val clockedToday: Boolean,
    val todayMeters: Int,
    /** When today's CLOCK IN was stamped (0 = not today). Chat turns before it talk about an older state. */
    val signedAt: Long = 0,
) {
    val key: String get() = "$streak-${if (signedToday) "s" else if (clockedToday) "c" else "n"}"

    fun line(goal: Int = SolarchikConfig.RUN_GOAL_M): String {
        val days = if (streak == 1) "1 day" else "$streak days"
        val today = when {
            signedToday -> "today's CLOCK IN is already signed. Do not ask the player to run $goal m or to sign today; the day is done (Ukrainian wording: «День уже підписано» / «Сьогодні вже зараховано», a sentence without «ти»)"
            clockedToday -> "today's run ($todayMeters m) unlocked CLOCK IN but it is not signed yet: the next step is to tap Sign today"
            else -> "today is not signed yet; today's best run is $todayMeters m of $goal m"
        }
        return "PLAYER STATE NOW (fresh from the phone, overrides anything said earlier): CLOCK IN streak $days; $today."
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("streak", streak)
        put("signedToday", signedToday)
        put("clockedToday", clockedToday)
        put("todayMeters", todayMeters)
    }

    companion object {
        fun of(save: GameSave): SolState {
            val today = save.today()
            val signed = save.signedToday()
            val at = if (signed) save.clockLog().filter { it.day == today }.maxOfOrNull { it.at } ?: 0L else 0L
            return SolState(save.liveStreak().streak, signed, save.checkInOpen(), save.todayDistance(), at)
        }

        /** History without assistant lines written before today's signature (they quote the old streak). */
        fun freshHistory(turns: List<ChatTurn>, state: SolState): List<ChatTurn> =
            if (state.signedAt <= 0) turns else turns.filter { it.role != "assistant" || it.at >= state.signedAt }
    }
}
