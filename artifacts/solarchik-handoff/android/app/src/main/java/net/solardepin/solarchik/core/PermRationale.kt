package net.solardepin.solarchik.core

import android.Manifest
import android.content.Context
import net.solardepin.solarchik.R

/** 1.2.12: an in-app explanation before every runtime permission dialog (dApp Store review). Cancel = denied. */
object PermRationale {
    @androidx.annotation.VisibleForTesting var skipForTest = false

    fun text(perm: String): Int = when (perm) {
        Manifest.permission.RECORD_AUDIO -> R.string.pr_mic
        Manifest.permission.POST_NOTIFICATIONS -> R.string.pr_notes
        else -> R.string.pr_generic
    }

    fun then(ctx: Context, perm: String, onCancel: () -> Unit = {}, go: () -> Unit) {
        if (skipForTest) { go(); return }
        runCatching {
            android.app.AlertDialog.Builder(ctx).setTitle(R.string.pr_title).setMessage(text(perm))
                .setPositiveButton(R.string.pr_continue) { _, _ -> go() }
                .setNegativeButton(R.string.pr_not_now) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }.show()
        }.onFailure { go() }
    }
}
