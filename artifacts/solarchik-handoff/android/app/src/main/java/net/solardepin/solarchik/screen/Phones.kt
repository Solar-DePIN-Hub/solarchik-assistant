package net.solardepin.solarchik.screen

import android.content.Context
import net.solardepin.solarchik.circle.Circle
import net.solardepin.solarchik.circle.CircleStore

/**
 * 1.2.6: phone numbers are masked on screen by default ("+380 •• ••• •• 17"). Shown in full: numbers of the
 * user's own Circle contacts, this phone's verified number and the secretary line; or everything when the user
 * turns on "Show full phone numbers" in Settings. Dialing always uses the real number.
 */
object Phones {
    private val RX = Regex("\\+?\\d[\\d ()\\-]{6,16}\\d")
    private const val PREF = "phones"

    fun revealAll(ctx: Context): Boolean = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("reveal", false)
    fun setRevealAll(ctx: Context, on: Boolean) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("reveal", on).apply()

    /** "+380638500117" -> "+380 •• ••• •• 17"; "0638500117" -> "0•• ••• •• 17". Short digit runs stay as they are. */
    fun mask(n: String): String {
        val d = n.filter { it.isDigit() }
        if (d.length < 9) return n
        val plus = n.trim().startsWith("+")
        val cc = if (plus) d.take((d.length - 9).coerceIn(1, 3)) else ""
        return (if (plus) "+$cc " else "") + "•• ••• •• " + d.takeLast(2)
    }

    private fun known(ctx: Context): Set<String> =
        (CircleStore(ctx).all().map { Circle.phoneKey(it.phone) } +
            Circle.phoneKey(Secretary.ownNumber(ctx)) + Circle.phoneKey(Secretary.forwardNumber(ctx)) + Circle.phoneKey(Secretary.DEFAULT_FORWARD_NUMBER))
            .filter { it.isNotBlank() }.toSet()

    /** Masks every phone number in a display text, except the user's own contacts' numbers. */
    fun show(ctx: Context, text: String): String {
        if (text.isBlank() || revealAll(ctx) || !RX.containsMatchIn(text)) return text
        val mine = known(ctx)
        return RX.replace(text) { m ->
            val d = m.value.filter { it.isDigit() }
            if (d.length < 9 || Circle.phoneKey(m.value) in mine) m.value else mask(m.value)
        }
    }
}
