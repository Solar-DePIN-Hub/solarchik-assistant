package net.solardepin.solarchik.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.circle.Circle
import net.solardepin.solarchik.screen.CallActionRules
import org.sol4k.PublicKey
import net.solardepin.solarchik.stack.Habits

/**
 * 1.2.7 "Save USDC" habit: an SPL TransferChecked of N USDC from the user's wallet to the user's OWN savings address
 * (the savings account is created idempotently if missing), signed in the wallet in one MWA session. Before the
 * wallet opens: the USDC balance is read; no USDC account / not enough USDC = the card skips itself for today (the
 * clock-in never needs money). The pre-simulation in [net.solardepin.solarchik.wallet.SolanaWallet.signAndSend]
 * catches the rest (low SOL for the fee, rent for a new account) with plain words. Done only after Solana confirms.
 */
object SaveUsdc {
    const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val DECIMALS = 6

    /** Pure: the transfer the user signs (6 decimals, ATA created only if the savings address has none). */
    fun tx(owner: PublicKey, to: PublicKey, amount: Double, blockhash: ByteArray) =
        CallActionRules.paymentTx(owner, to, "USDC", CallActionRules.amountRaw("USDC", amount), blockhash)

    /** Pure: what to do with a read balance: null = go on, else the skip message. */
    fun skipReason(ctx: android.content.Context, balance: Double?, accounts: Int, amount: Double): String? = when {
        balance == null -> null // could not read: the simulation decides
        accounts == 0 -> ctx.getString(R.string.save_skip_no_account)
        balance + 1e-9 < amount -> ctx.getString(R.string.save_skip_low, Circle.amount(balance), Circle.amount(amount))
        else -> null
    }

    @androidx.annotation.VisibleForTesting
    var lastConfirm: android.app.AlertDialog? = null

    fun start(host: MainActivity, done: (Boolean) -> Unit) {
        val w = host.wallet
        if (!w.connected) { host.toast(host.getString(R.string.save_skip_no_wallet)); (host.screen(MainActivity.Tab.TODAY) as? TodayScreen)?.setupWallet(); return }
        val amount = Habits.saveAmount(host)
        val to = Habits.saveTo(host)
        if (!CallActionRules.validAddress(to) || to == w.address) { HabitsSheet.setupSave(host) { done(false) }; return }
        host.scope.launch {
            val bal = withContext(Dispatchers.IO) { net.solardepin.solarchik.season.Skr.fetchMint(w.address, MINT) }.getOrNull()
            skipReason(host, bal?.first, bal?.second ?: 1, amount)?.let { why ->
                Habits.markSkipped(host, Habits.SAVE)
                CallActionCards.status(host, host.getString(R.string.habit_save_usdc_n, Circle.amount(amount)), why)
                done(true)
                return@launch
            }
            val dlg = android.app.AlertDialog.Builder(host).setTitle(host.getString(R.string.save_confirm_title, Circle.amount(amount)))
                .setMessage(host.getString(R.string.save_confirm_body, Fmt.short(to)))
                .setPositiveButton(R.string.save_confirm_go) { _, _ -> send(host, amount, to, done) }
                .setNegativeButton(android.R.string.cancel, null).create()
            dlg.show()
            lastConfirm = dlg
        }
    }

    private fun send(host: MainActivity, amount: Double, to: String, done: (Boolean) -> Unit) {
        val what = host.getString(R.string.pay_what, Circle.amount(amount), "USDC", Fmt.short(to))
        val waiting = CallActionCards.status(host, host.getString(R.string.pay_waiting_title), host.getString(R.string.pay_waiting, what))
        waiting.setCanceledOnTouchOutside(false)
        host.scope.launch {
            val r = runCatching { host.wallet.signAndSend(host.sender, token = "USDC") { payer, blockhash -> tx(payer, PublicKey(to), amount, blockhash) } }
                .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e else Result.failure(e) }
            r.onSuccess { sent ->
                Habits.setPendingSig(host, sent.signature, amount)
                CallActionCards.status(host, host.getString(R.string.pay_sent_title), host.getString(R.string.pay_sent, what), sent.signature)
                val at = System.currentTimeMillis()
                val ok = withContext(Dispatchers.IO) { runCatching { host.wallet.waitConfirmed(sent.signature) }.getOrDefault(false) }
                if (ok) {
                    val left = CallActionCards.MIN_SENT_MS - (System.currentTimeMillis() - at)
                    if (left > 0) kotlinx.coroutines.delay(left)
                    Habits.addSaved(host, amount, sent.signature)
                    CallActionCards.status(host, host.getString(R.string.pay_settled_title), host.getString(R.string.save_done, Circle.amount(amount), Circle.amount(Habits.saved(host))), sent.signature)
                    done(true)
                } else {
                    CallActionCards.status(host, host.getString(R.string.pay_sent_title), host.getString(R.string.save_unconfirmed), sent.signature)
                    done(false)
                }
            }.onFailure {
                CallActionCards.status(host, host.getString(R.string.pay_failed_title), host.getString(R.string.pay_failed, host.errorText(it)))
                done(false)
            }
        }
    }

    /** A save that was sent but not confirmed: checked again (once per Today opening); Done only when confirmed. */
    fun recheckPending(host: MainActivity, changed: () -> Unit) {
        val sig = Habits.pendingSig(host)
        if (sig.isBlank() || !MainActivity.tickerEnabled) return
        host.scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { host.wallet.waitConfirmed(sig) }.getOrDefault(false) }
            if (ok && Habits.pendingSig(host) == sig) { Habits.addSaved(host, java.math.BigDecimal(Habits.pendingAmount(host).toString()).setScale(6, java.math.RoundingMode.HALF_UP).toDouble(), sig); changed() }
        }
    }
}
