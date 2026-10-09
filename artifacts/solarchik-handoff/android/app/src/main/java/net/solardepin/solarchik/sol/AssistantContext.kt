package net.solardepin.solarchik.sol

import net.solardepin.solarchik.screen.CallInbox
import net.solardepin.solarchik.screen.CallItem
import net.solardepin.solarchik.screen.FollowUp
import net.solardepin.solarchik.season.SeasonItem
import net.solardepin.solarchik.season.SeasonPlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 1.0.1: the short, factual "what's on the phone right now" block sent with every Sol chat message from the
 * assistant (worker `app: "assistant"` prompt). Built only from local state; nothing is invented, and empty
 * parts say so. Kept under [MAX] characters, most useful first (time, calls, follow-ups, wallet, Season).
 */
object AssistantContext {
    const val MAX = 900

    data class Wallet(val connected: Boolean, val builtIn: Boolean, val address: String, val mainnet: Boolean = false, val sol: Double? = null, val skr: Double? = null, val swapsOn: Boolean = false)

    fun build(
        calls: List<CallItem>,
        followUps: List<FollowUp>,
        wallet: Wallet,
        season: SeasonPlan,
        secretaryOn: Boolean,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        /** 1.1.0: Watcher alerts, open call actions and which agents are on (short plain lines). */
        extras: List<String> = emptyList(),
    ): String {
        val clock = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
        val day = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).apply { timeZone = zone }
        fun who(c: CallItem) = c.who.ifBlank { "unknown caller" }
        fun one(c: CallItem): String {
            val what = c.intent.ifBlank { c.text }.replace(Regex("\\s+"), " ").trim().take(90)
            val state = when {
                c.blocked -> "blocked"
                c.missed -> "missed"
                else -> "answered"
            }
            val cb = c.callback.takeIf { it.isNotBlank() && it != "unknown" }?.let { ", callback $it" }.orEmpty()
            return "${who(c)} ${clock.format(Date(c.at))} ($state)" + (if (what.isNotBlank() && !c.blocked) ": $what" else "") + cb
        }
        val today = AssistantRules.today(calls, now).filter { !it.blocked }
        val parts = ArrayList<String>()
        parts += "Now: ${day.format(Date(now))} (${zone.id})."
        parts += "Phone secretary: ${if (secretaryOn) "on" else "off"}."
        parts += when {
            today.isNotEmpty() -> "Calls today (${today.size}): " + today.take(3).joinToString("; ") { one(it) } + "."
            calls.any { !it.blocked } -> "No calls today. Last call: " + one(calls.first { !it.blocked }) + "."
            else -> "No calls yet."
        }
        parts += if (followUps.isEmpty()) "Follow-ups: none." else "Follow-ups: " + followUps.take(3).joinToString("; ") { f ->
            when (f.kind) {
                FollowUp.Kind.REMINDER -> "reminder to call ${who(f.item)} at ${clock.format(Date(f.at))}"
                FollowUp.Kind.CALLBACK -> "call back ${who(f.item)}"
            }
        } + "."
        val net = if (wallet.mainnet) "Solana mainnet, real funds" else "Solana devnet, developer test mode"
        parts += if (!wallet.connected) "Wallet: not connected ($net)."
        else "Wallet: ${if (wallet.builtIn) "built-in devnet key" else "wallet app"} on $net, ${wallet.address.take(4)}…${wallet.address.takeLast(4)}" +
            (wallet.sol?.let { ", ${java.math.BigDecimal(it).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} SOL" } ?: "") +
            (wallet.skr?.let { ", ${java.math.BigDecimal(it).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} SKR" } ?: "") +
            "; every transaction is approved by the user in the wallet app." +
            (if (wallet.mainnet) " Real Jupiter swaps: ${if (wallet.swapsOn) "on, with the user's daily cap" else "off (opt-in in Agents › Swaps)"}." else "")
        val todo = buildList {
            if (!season.done(SeasonItem.EXPLORE)) add("open ${season.suggestion.name}")
            if (!season.done(SeasonItem.ONCHAIN)) add(if (season.clockedToday) "sign today's check-in" else "do the daily check-in")
        }
        parts += "Seeker Season plan: ${season.doneCount}/${season.total} done" + (if (todo.isEmpty()) "." else "; left: " + todo.joinToString(", ") + ".") +
            " Streak ${season.streak} day(s)."
        parts += extras.filter { it.isNotBlank() }
        return parts.joinToString(" ").take(MAX)
    }
}
