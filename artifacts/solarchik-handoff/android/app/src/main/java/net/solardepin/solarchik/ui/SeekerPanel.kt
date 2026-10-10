package net.solardepin.solarchik.ui

import android.view.Gravity
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.season.SeekerState
import net.solardepin.solarchik.season.SeekerStore
import net.solardepin.solarchik.season.SeekerVerify
import org.json.JSONObject

/**
 * 1.2.0 Settings → Verified Seeker. Not verified is a normal, complete state ("everything works without a Seeker");
 * verifying adds a badge and extra secretary minutes. The button signs in with the wallet (SIWS, no transaction).
 */
class SeekerPanel(private val host: MainActivity, private val onChange: () -> Unit) {
    private val ctx get() = host
    var busy = false
        private set
    /** Tests replace the network (POST path, body) -> (code, body). */
    @androidx.annotation.VisibleForTesting var post: ((String, String) -> Pair<Int, String>)? = null

    fun card(): View = Ui.column(ctx).apply {
        tag = "seeker-panel"
        val s = SeekerStore(ctx).state()
        val head = Ui.row(ctx, gap = 10).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.weight(Ui.text(ctx, ctx.getString(if (s.verified) R.string.sk_verified else R.string.sk_not_verified), 15f, Ui.TEXT, 800).apply { tag = "seeker-status" }))
        head.addView(Ui.pill(ctx, ctx.getString(if (s.verified) R.string.sk_badge else R.string.sk_optional), if (s.verified) Ui.GREEN else Ui.MUTED).apply { tag = "seeker-badge" })
        addView(head)
        val body = when {
            s.verified -> ctx.getString(R.string.sk_verified_body, s.bonusMin, SeekerVerify.short(s.mint))
            else -> ctx.getString(R.string.sk_body, s.bonusMin)
        }
        addView(Ui.top(Ui.muted(ctx, body, 12.5f).apply { setLineSpacing(0f, 1.25f); tag = "seeker-body" }, 6))
        when (s.last) {
            SeekerVerify.NO_SGT -> addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sk_no_sgt), 12.5f, Ui.AMBER, 700).apply { tag = "seeker-last" }, 6))
            SeekerVerify.MINT_USED -> addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.sk_mint_used), 12.5f, Ui.AMBER, 700).apply { tag = "seeker-last" }, 6))
        }
        if (!s.verified) addView(Ui.top(Ui.button(ctx, ctx.getString(if (busy) R.string.sk_checking else R.string.sk_verify), Ui.Btn.SECONDARY, R.drawable.ic_wallet) { verify() }
            .apply { tag = "seeker-verify"; isEnabled = !busy; textSize = 13.5f }, 10))
    }

    private fun http(path: String, body: String): Pair<Int, String> = post?.invoke(path, body) ?: net.solardepin.solarchik.season.SeasonRules.httpPost(ScreenApi.BASE + path, body)

    fun verify() {
        if (busy) return
        val w = host.wallet
        if (!w.connected) { host.toast(ctx.getString(R.string.sk_connect_first)); return }
        if (!w.mainnet) { host.toast(ctx.getString(R.string.sk_mainnet_only)); return }
        busy = true
        onChange()
        host.scope.launch {
            val userId = PlayerIds.get(ctx)
            val msg = runCatching {
                val (c1, b1) = withContext(Dispatchers.IO) { http("/seeker/challenge", SeekerVerify.challengeBody(userId)) }
                if (c1 != 200) error("challenge HTTP $c1")
                val payload = JSONObject(b1).getJSONObject("payload")
                val proof = w.signInWithSolana(payload).getOrElse { e -> busy = false; onChange(); host.toast(host.errorText(e)); return@launch }
                val (c2, b2) = withContext(Dispatchers.IO) { http("/seeker/verify", SeekerVerify.verifyBody(userId, payload.getString("nonce"), proof.publicKey, proof.signedMessage, proof.signature)) }
                val st = SeekerVerify.parse(b2) ?: error("verify HTTP $c2")
                SeekerStore(ctx).save(st)
                when {
                    st.verified -> ctx.getString(R.string.sk_done, st.bonusMin)
                    st.last == SeekerVerify.NO_SGT -> ctx.getString(R.string.sk_no_sgt)
                    st.last == SeekerVerify.MINT_USED -> ctx.getString(R.string.sk_mint_used)
                    else -> ctx.getString(R.string.sk_failed)
                }
            }.getOrElse { ctx.getString(R.string.sk_failed) }
            busy = false
            host.toast(msg)
            onChange()
        }
    }

    /** Status from the worker (e.g. verified on another install with the same account). */
    fun refresh() {
        if (android.os.Build.FINGERPRINT == "robolectric" && post == null) return
        host.scope.launch {
            val userId = PlayerIds.get(ctx)
            val st = withContext(Dispatchers.IO) { runCatching { net.solardepin.solarchik.season.SeasonRules.httpGet(ScreenApi.BASE + "/seeker/status?userId=" + userId) }.getOrNull() }
                ?.takeIf { it.first == 200 }?.let { SeekerVerify.parse(it.second) } ?: return@launch
            val before = SeekerStore(ctx).state()
            if (st.verified != before.verified || st.bonusMin != before.bonusMin) { SeekerStore(ctx).save(st.copy(last = before.last)); onChange() }
        }
    }

    @Suppress("unused") private fun unused(s: SeekerState) = s
}
