package net.solardepin.solarchik.circle

import android.content.Context
import net.solardepin.solarchik.R

/**
 * 1.2.4: Sol answers "who do I owe?" and "send Ira what I owe" from the Circle ledger on this phone (not the
 * model), and for a payment opens the prefilled card. Sending still needs the user's approval in the wallet.
 */
object CircleRules {
    private val whoOwe = Regex("(?i)\\b(who|what|how much)\\b[^?.!]{0,20}\\b(do|did)?\\s*i\\s+owe\\b|\\bmy debts?\\b|кому\\s+я\\s+(винен|винна|заборгував)|скільки\\s+я\\s+(винен|винна)|мої\\s+борги")
    private val payOwe = Regex("(?i)\\b(send|pay|give|settle)\\b.{0,40}\\b(what i owe|i owe|back|up|debt)\\b|\\bpay\\s+\\S+\\s+back\\b|\\bsettle (up )?with\\b|(надішли|віддай|поверни|переказ\\w*|заплати)\\b.{0,40}(борг|що я винен|що винен|винна)")

    sealed class Intent {
        object WhoOwe : Intent()
        data class Pay(val debt: Debt?, val name: String) : Intent()
    }

    /** First letters of a name, so "Ірі" / "Іри" find "Іра" and "Ira's" finds "Ira". */
    private fun stem(w: String): String = Circle.nameKey(w).let { if (it.length > 3) it.take(it.length - 1).take(4) else it.take(2) }

    fun intent(message: String, debts: List<Debt>): Intent? {
        val m = message.trim()
        if (m.isEmpty() || m.length > 140) return null
        if (payOwe.containsMatchIn(m)) {
            val raw = m.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 2 }
            val words = raw.map { stem(it.removeSuffix("'s")) }
            val open = debts.filter { it.open }
            val hit = open.firstOrNull { d -> stem(d.who.split(' ').first()).let { s -> s.length >= 2 && words.any { w -> w.length >= 2 && w == s || (s.length > 2 && w.length > 2 && (w.startsWith(s) || s.startsWith(w))) } } }
            if (hit != null) return Intent.Pay(hit, hit.who)
            // a name was said ("send Petro what I owe") that owes nothing: say so; no name: the only open debt
            val said = raw.drop(1).firstOrNull { it.first().isUpperCase() && it.lowercase() != "i" && it != "SOL" && it != "SKR" && it != "USDC" }
            return Intent.Pay(if (said == null) open.singleOrNull() else null, said.orEmpty())
        }
        if (whoOwe.containsMatchIn(m)) return Intent.WhoOwe
        return null
    }

    fun whoOweText(ctx: Context, debts: List<Debt>): String {
        val p = Circle.owedPhrase(debts)
        return if (p.isBlank()) ctx.getString(R.string.circle_sol_none) else ctx.getString(R.string.circle_sol_owe, p)
    }
}
