package net.solardepin.solarchik.sol

import android.content.Context
import net.solardepin.solarchik.R
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallText

/**
 * 1.0.0 assistant answers that come from the phone itself, not the model: "did anyone call?" is read from
 * the secretary's call archive on this phone, and "what can you do?" is a fixed honest list. Both are
 * instant and work offline. Anything about agents or strategies goes on to the brain as before.
 */
object AssistantRules {
    private val callWords = Regex("\\b(calls?|called|calling|phoned?|missed|voicemail|secretary)\\b|дзвон|дзвін|телефонув|секретар|пропущен", RegexOption.IGNORE_CASE)
    private val agentWords = Regex("agent|strateg|агент|стратег|mint|buy|купи", RegexOption.IGNORE_CASE)
    private val skills = Regex("what (can|do) you do|what are you|help me|що ти (вмієш|можеш)|хто ти|чим ти можеш", RegexOption.IGNORE_CASE)

    private val seasonWords = Regex("seeker|season|сезон|сікер", RegexOption.IGNORE_CASE)

    enum class Kind { CALLS, SKILLS, SEASON }

    fun kind(message: String): Kind? {
        val m = message.trim()
        if (m.isEmpty() || m.length > 120) return null
        if (seasonWords.containsMatchIn(m)) return Kind.SEASON
        if (agentWords.containsMatchIn(m)) return null
        return when {
            skills.containsMatchIn(m) -> Kind.SKILLS
            callWords.containsMatchIn(m) -> Kind.CALLS
            else -> null
        }
    }

    fun answer(
        ctx: Context, message: String, calls: List<CallItem>, now: Long = System.currentTimeMillis(),
        season: (() -> net.solardepin.solarchik.season.SeasonPlan)? = null,
    ): String? = when (kind(message)) {
        Kind.SEASON -> season?.invoke()?.spoken(ctx)
        Kind.SKILLS -> ctx.getString(R.string.as_skills)
        Kind.CALLS -> callsLine(ctx, calls, now)
        null -> null
    }

    /** Calls that started on the phone's current local day. */
    fun today(calls: List<CallItem>, now: Long = System.currentTimeMillis()): List<CallItem> {
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return calls.filter { it.at in start..now + 60_000 }
    }

    fun callsLine(ctx: Context, calls: List<CallItem>, now: Long = System.currentTimeMillis()): String {
        val real = calls.filter { !it.blocked }
        val day = today(real, now)
        fun one(c: CallItem): String {
            val who = c.who.ifBlank { ctx.getString(R.string.calls_unknown) }
            return ctx.getString(R.string.as_call_one, who, CallText.time(c.at), CallText.summary(ctx, c).trim().take(140))
        }
        return when {
            day.isNotEmpty() -> ctx.resources.getQuantityString(R.plurals.as_calls_today, day.size, day.size) + " " +
                day.take(3).joinToString("") { "\n• " + one(it).trimEnd('.') + "." }
            real.isNotEmpty() -> ctx.getString(R.string.as_calls_none_today) + " " + ctx.getString(R.string.as_calls_last) + " " + one(real.first()).trimEnd('.') + "."
            else -> ctx.getString(R.string.as_calls_never, CallInbox.DEMO_LINE)
        }
    }
}
