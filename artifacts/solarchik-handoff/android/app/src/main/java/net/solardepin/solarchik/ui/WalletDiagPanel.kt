package net.solardepin.solarchik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.wallet.WalletDiag

/**
 * 1.2.0 Settings → Wallet diagnostics: pick the wallet app (Auto / Phantom / Solflare / …), run a logged
 * connect, and copy or share the step log (plus this app's own MWA logcat lines) so one run shows the failure.
 */
class WalletDiagPanel(private val host: MainActivity) {
    private val ctx: Context = host
    val view: LinearLayout = Ui.column(ctx).apply { tag = "wallet-diag" }
    private var busy = false

    fun render() {
        view.removeAllViews()
        val w = host.wallet
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.wd_body), 12.5f).apply { setLineSpacing(0f, 1.25f) }, 8))
        val installed = w.installedWallets()
        val pick = w.walletPackage.takeIf { it in installed }.orEmpty()
        view.addView(Ui.top(Ui.label(ctx, ctx.getString(R.string.wd_wallet_app), Ui.MUTED), 12))
        val chips = Ui.row(ctx, gap = 8)
        val options = listOf("" to ctx.getString(R.string.wd_auto)) + installed.toList()
        options.forEach { (pkg, name) ->
            chips.addView(Ui.weight(Ui.button(ctx, name, if (pkg == pick) Ui.Btn.PRIMARY else Ui.Btn.GHOST) {
                w.walletPackage = pkg
                WalletDiag.log("wallet app", if (pkg.isBlank()) "auto" else pkg)
                render()
            }.apply { tag = "wd-pick-" + pkg.ifBlank { "auto" }; textSize = 13f }))
        }
        view.addView(Ui.top(chips, 6))
        if (installed.isEmpty()) view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.wd_none_installed), 12f), 6))
        if ("com.solflare.mobile" !in installed) {
            view.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.wd_get_solflare), Ui.Btn.GHOST) {
                host.openUrl("https://play.google.com/store/apps/details?id=com.solflare.mobile")
            }.apply { tag = "wd-get-solflare"; textSize = 13f }, 8))
        }
        val actions = Ui.row(ctx, gap = 8)
        actions.addView(Ui.weight(Ui.button(ctx, ctx.getString(if (busy) R.string.wd_testing else R.string.wd_test), Ui.Btn.SECONDARY) { test() }.apply { tag = "wd-test"; textSize = 13f; isEnabled = !busy }))
        actions.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.wd_copy), Ui.Btn.GHOST) { copy() }.apply { tag = "wd-copy"; textSize = 13f }))
        actions.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.wd_share), Ui.Btn.GHOST) { share() }.apply { tag = "wd-share"; textSize = 13f }))
        actions.addView(Ui.weight(Ui.button(ctx, ctx.getString(R.string.wd_clear), Ui.Btn.GHOST) { WalletDiag.clear(); host.toast(ctx.getString(R.string.wd_cleared)); render() }.apply { tag = "wd-clear"; textSize = 13f }))
        view.addView(Ui.top(actions, 12))
        val lines = WalletDiag.text().trimEnd().lines().filter { it.isNotBlank() }
        val shown = if (lines.isEmpty()) ctx.getString(R.string.wd_empty) else lines.takeLast(40).joinToString("\n")
        view.addView(Ui.top(Ui.text(ctx, shown, 10.5f, Ui.MUTED, 500).apply {
            typeface = Typeface.MONOSPACE
            tag = "wd-log"
            setTextIsSelectable(true)
            background = Ui.rounded(Ui.withAlpha(android.graphics.Color.WHITE, 0x08), dp(10).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }, 10))
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun test() {
        if (busy) return
        val w = host.wallet
        busy = true
        WalletDiag.log("test", "started from Settings")
        render()
        host.scope.launch {
            val r = if (w.hasWalletApp() || w.installedWallets().isNotEmpty()) w.connect(host.sender) else Result.failure(net.solardepin.solarchik.wallet.WalletError(net.solardepin.solarchik.wallet.WalletError.Kind.NO_WALLET))
            r.onSuccess { WalletDiag.log("test result", "connected " + WalletDiag.shortAddr(it.address)); host.toast(ctx.getString(R.string.mn_wallet_connected, Fmt.short(it.address))) }
                .onFailure { WalletDiag.log("test result", "failed: " + WalletDiag.chain(it)); host.toast(host.errorText(it)) }
            busy = false
            host.renderAll()
        }
    }

    /** The step log, then this app's own MWA logcat lines. */
    private suspend fun fullText(): String {
        val cat = withContext(Dispatchers.IO) { WalletDiag.logcat() }
        return WalletDiag.header(ctx) + "\n\n" + WalletDiag.text().trimEnd() + (if (cat.isNotBlank()) "\n\n--- logcat ---\n$cat" else "")
    }

    private fun copy() {
        host.scope.launch {
            val t = fullText()
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Solarchik wallet diagnostics", t))
            host.toast(ctx.getString(R.string.wd_copied))
        }
    }

    private fun share() {
        host.scope.launch {
            val t = fullText()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Solarchik wallet diagnostics").putExtra(Intent.EXTRA_TEXT, t)
            runCatching { host.startActivity(Intent.createChooser(send, ctx.getString(R.string.wd_share))) }.onFailure { host.toast(ctx.getString(R.string.wd_share_failed)) }
        }
    }
}
