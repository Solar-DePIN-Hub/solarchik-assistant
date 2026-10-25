package net.solardepin.solarchik.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.circle.SolanaPay
import net.solardepin.solarchik.delegate.SplIx
import net.solardepin.solarchik.season.SeasonRules
import net.solardepin.solarchik.solana.Ix
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.solana.Meta
import net.solardepin.solarchik.swap.SwapTokens
import net.solardepin.solarchik.wallet.WalletError
import org.json.JSONObject
import org.sol4k.PublicKey

/**
 * 1.2.11 Sol Premium: 7.99 USDC for 30 days, a Solana Pay transfer (with a fresh reference key) to the treasury that the
 * user's wallet signs, then POST https://app.solardepin.net/premium/verify (checked on mainnet) until it is credited.
 * The Premium phone number is not automated yet, so an active Premium says "Your number is being set up".
 */
object Premium {
    const val BASE = "https://app.solardepin.net"
    const val TREASURY = "H7zKmmnMNfnsMtib6mopdT8XsPYAyPBFuaQeWhBYTpQg"
    const val PRICE_RAW = 7_990_000L // 7.99 USDC, 6 decimals
    private const val PREFS = "sol.premium"

    fun until(ctx: Context): Long = ctx.getSharedPreferences(PREFS, 0).getLong("until", 0L)
    fun active(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = until(ctx) * 1000 > now
    fun saveUntil(ctx: Context, until: Long) = ctx.getSharedPreferences(PREFS, 0).edit().putLong("until", until).apply()

    /** USDC transferChecked to the treasury with the reference as an extra read-only key (Solana Pay spec). */
    fun tx(owner: PublicKey, reference: String, blockhash: ByteArray): LegacyTx {
        val mint = PublicKey(SwapTokens.USDC.mint)
        val treasury = PublicKey(TREASURY)
        val t = SplIx.transferChecked(SplIx.ata(owner, mint), mint, SplIx.ata(treasury, mint), owner, PRICE_RAW, SwapTokens.USDC.decimals)
        val withRef = Ix(t.program, t.accounts + Meta(PublicKey(reference), signer = false, writable = false), t.data)
        return LegacyTx.compile(owner, blockhash, listOf(SplIx.createAtaIdempotent(owner, treasury, mint), withRef))
    }

    /** One verify call: until (unix s) when credited, 0 when not found yet, -1 when the payment is invalid. */
    fun verify(wallet: String, ref: String): Long {
        val (code, body) = SeasonRules.httpPost("$BASE/premium/verify", JSONObject().put("wallet", wallet).put("ref", ref).toString())
        val o = runCatching { JSONObject(body) }.getOrNull()
        return when {
            code == 200 && o?.optBoolean("premium") == true -> o.optLong("until")
            code == 402 && o?.optString("error") == "PAYMENT_INVALID" -> -1L
            else -> 0L
        }
    }

    fun status(wallet: String): Long? = runCatching {
        val (code, body) = SeasonRules.httpGet("$BASE/premium/status?wallet=$wallet")
        if (code != 200) null else JSONObject(body).let { if (it.optBoolean("premium")) it.optLong("until") else 0L }
    }.getOrNull()
}

class PremiumPanel(private val host: MainActivity) {
    private val ctx: Context get() = host
    val view: LinearLayout = Ui.column(ctx)
    private var busy = false

    fun render() {
        view.removeAllViews()
        if (Premium.active(ctx)) {
            val f = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, net.solardepin.solarchik.core.AppLocale.ui(ctx))
            view.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.pm_active, f.format(java.util.Date(Premium.until(ctx) * 1000))), 15f, Ui.GREEN, 800).apply { tag = "premium-active" }, 8))
            view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.pm_number_setup), 12.5f).apply { tag = "premium-number" }, 4))
            // 1.2.15: Premium-only call settings (voice, greeting, language)
            val cs = CallSettingsPanel(host); view.addView(cs.view); cs.load()
            return
        }
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.pm_body), 12.5f).apply { setLineSpacing(0f, 1.25f) }, 6))
        view.addView(Ui.top(Ui.button(ctx, ctx.getString(if (busy) R.string.pm_checking else R.string.pm_buy), Ui.Btn.PRIMARY) { buy() }.apply { tag = "premium-buy"; isEnabled = !busy }, 10))
    }

    fun refresh() {
        val w = host.wallet
        if (!w.connected || !w.mainnet) return
        host.scope.launch {
            val u = withContext(Dispatchers.IO) { Premium.status(w.address) } ?: return@launch
            if (u > 0) Premium.saveUntil(ctx, u)
            render()
        }
    }

    private fun buy() {
        val w = host.wallet
        if (busy) return
        if (!w.connected || !w.mainnet) { host.toast(ctx.getString(R.string.pm_need_wallet)); return }
        // 1.2.12: exact amount, token and recipient before the wallet opens
        android.app.AlertDialog.Builder(ctx).setTitle(R.string.pm_title)
            .setMessage(ctx.getString(R.string.pm_confirm, Premium.TREASURY))
            .setPositiveButton(R.string.pm_confirm_pay) { _, _ -> pay() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun pay() {
        val w = host.wallet
        if (busy) return
        busy = true; render()
        val ref = SolanaPay.newReference()
        host.scope.launch {
            try {
                val sent = w.signAndSend(host.sender, token = "USDC") { payer, blockhash -> Premium.tx(payer, ref, blockhash) }
                    .getOrElse { host.toast(WalletError.text(ctx, it)); return@launch }
                var until = 0L
                for (i in 0 until 20) { // ~60 s, every 3 s
                    until = withContext(Dispatchers.IO) { runCatching { Premium.verify(sent.address, ref) }.getOrDefault(0L) }
                    if (until != 0L) break
                    delay(3000)
                }
                when {
                    until > 0 -> { Premium.saveUntil(ctx, until); host.toast(ctx.getString(R.string.pm_number_setup)) }
                    until < 0 -> host.toast(ctx.getString(R.string.pm_invalid))
                    else -> host.toast(ctx.getString(R.string.pm_pending))
                }
            } finally { busy = false; render() }
        }
    }
}
