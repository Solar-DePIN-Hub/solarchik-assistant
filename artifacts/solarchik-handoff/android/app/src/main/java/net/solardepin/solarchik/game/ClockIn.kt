package net.solardepin.solarchik.game

import android.app.Activity
import androidx.core.app.ShareCompat
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import net.solardepin.solarchik.R
import net.solardepin.solarchik.core.FeeWindow
import net.solardepin.solarchik.ui.Fmt
import net.solardepin.solarchik.wallet.SolanaWallet

/**
 * The one CLOCK IN signing flow (web doSign), shared by the Yard and the run's HUD badge /
 * result card: capture the day that was run, sign the memo with the MWA wallet, stamp the save.
 */
object ClockIn {
    /** True when today's CLOCK IN is unlocked by a run and not signed yet. */
    fun ready(save: GameSave): Boolean = save.clockedToday() && !save.signedToday()

    /**
     * Signs today's CLOCK IN. Success carries the fee-free window it granted (if any).
     * Must be called on the main thread (the wallet opens through [sender]).
     */
    suspend fun sign(wallet: SolanaWallet, sender: ActivityResultSender, save: GameSave): Result<FeeWindow?> {
        if (!ready(save)) return Result.failure(IllegalStateException("CLOCK IN is not open"))
        // Captured before the wallet opens: the proof belongs to the day that was run.
        val day = save.today()
        val meters = save.todayDistance()
        val proof = wallet.clockInOnChain(sender, meters, save.todayScore(), save.nextStreak(), day)
        return proof.map { save.stampClock(it.address, it.signature, it.cluster, it.kind, day, meters).firstOrNull() }
    }

    fun explorerTx(sig: String, cluster: String): String =
        if (cluster == "devnet") "https://explorer.solana.com/tx/$sig?cluster=devnet" else net.solardepin.solarchik.core.SolarchikConfig.solscanTx(sig, cluster)

    /** The day card's first line (web DayCard: "{m} m · streak {n}"). */
    fun dayLine(a: Activity, save: GameSave): String = a.getString(R.string.share_text, save.todayDistance(), save.streak)

    /** The day card's proof line (cluster + short signature). */
    fun proofLine(a: Activity, save: GameSave): String {
        if (save.clockSig.isBlank()) return ""
        val short = Fmt.short(save.clockSig)
        return a.getString(if (save.clockKind == "tx") R.string.yard_proof_tx else R.string.yard_proof_msg, "${save.clockCluster} $short")
    }

    /** Share today's signed day (web DayCard share; text + explorer link for a transaction). */
    fun share(a: Activity, save: GameSave) {
        if (!save.signedToday() || save.clockSig.isBlank()) return
        val text = a.getString(R.string.share_text, save.todayDistance(), save.streak) + "\n${Fmt.short(save.clockSig)} · ${save.clockCluster}"
        val body = if (save.clockKind == "tx") "$text\n${explorerTx(save.clockSig, save.clockCluster)}" else text
        ShareCompat.IntentBuilder(a)
            .setType("text/plain")
            .setText(body)
            .setChooserTitle(a.getString(R.string.share_title))
            .startChooser()
    }
}
