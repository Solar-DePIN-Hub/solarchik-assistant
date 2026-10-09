package net.solardepin.solarchik.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.MintError
import net.solardepin.solarchik.core.AgentSku
import net.solardepin.solarchik.core.AgentTier

/**
 * 0.21.8: the one confirmation card for "you don't own this agent yet" — used by the Agents tab
 * (as a dialog) and by Sol's chat (inline, after "start agent X"). Nothing runs before a tap.
 */
object OfferCard {
    data class Option(val label: String, val primary: Boolean, val enabled: Boolean, val tag: String, val go: () -> Unit)

    fun view(ctx: Context, title: String, body: String, note: String?, options: List<Option>, onCancel: (() -> Unit)?): LinearLayout =
        Ui.card(ctx, accent = Ui.GOLD, pad = 14).apply {
            tag = "offer-card"
            addView(Ui.label(ctx, ctx.getString(R.string.desk_get_agent), Ui.GOLD))
            addView(Ui.top(Ui.text(ctx, title, 15f, Ui.TEXT, 800), 4))
            addView(Ui.top(Ui.muted(ctx, body, 12f).apply { setLineSpacing(0f, 1.2f) }, 4))
            options.forEach { o ->
                val b = Ui.button(ctx, o.label, if (o.primary) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, R.drawable.ic_bolt_small) { o.go() }.apply { tag = o.tag }
                Ui.setEnabled(b, o.enabled)
                addView(Ui.top(b, 8))
            }
            if (note != null) addView(Ui.top(Ui.muted(ctx, note, 11f), 6))
            if (onCancel != null) addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.sol_act_cancel), Ui.Btn.GHOST) { onCancel() }.apply { tag = "offer-cancel" }, 8))
        }

    /** Mint free (when allowed; never for paid-only Combo) and Buy Pro for [sku]; [pick] gets the tier. */
    fun acquireOptions(host: MainActivity, sku: AgentSku, busy: Boolean, pick: (String) -> Unit): List<Option> {
        val ctx: Context = host
        val out = mutableListOf<Option>()
        if (!sku.paidOnly) {
            val block = host.minter.canMint(sku, AgentTier.FREE)
            out += Option(
                when (block) {
                    MintError.Kind.FREE_USED -> ctx.getString(R.string.mint_free_used)
                    MintError.Kind.MAINNET_SOON -> ctx.getString(R.string.mn_mint_soon)
                    else -> ctx.getString(R.string.offer_mint_free)
                },
                primary = false, enabled = !busy && block == null, tag = "offer-mint-free",
            ) { pick(AgentTier.FREE) }
        }
        val proBlock = host.minter.canMint(sku, AgentTier.PRO)
        out += Option(
            when (proBlock) {
                MintError.Kind.PRO_MAINNET_OFF -> ctx.getString(R.string.mint_pro_off)
                MintError.Kind.MAINNET_SOON -> ctx.getString(R.string.mn_mint_soon)
                else -> ctx.getString(R.string.offer_buy_pro, Fmt.sol(sku.priceSol(AgentTier.PRO)))
            },
            primary = true, enabled = !busy && proBlock == null, tag = "offer-buy-pro",
        ) { pick(AgentTier.PRO) }
        return out
    }
}
