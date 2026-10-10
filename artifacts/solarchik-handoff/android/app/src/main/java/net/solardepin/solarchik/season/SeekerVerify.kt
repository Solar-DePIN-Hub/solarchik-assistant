package net.solardepin.solarchik.season

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * 1.2.0 Verified Seeker (Seeker Genesis Token), app side. The worker issues a Sign-In-With-Solana payload, the
 * wallet signs it through MWA, the worker checks the signature and looks for an SGT in that wallet (Token-2022,
 * non-zero balance, SGT metadata + group). One SGT mint gives the perk to one account only. The perk: a Verified
 * badge and extra secretary minutes a day. Nothing else in the app depends on it; without a Seeker everything works.
 */
data class SeekerState(val verified: Boolean, val mint: String = "", val address: String = "", val at: Long = 0L, val bonusMin: Int = 10, val last: String = "")

object SeekerVerify {
    const val NO_SGT = "no_sgt"
    const val MINT_USED = "mint_used"

    fun challengeBody(userId: String): String = JSONObject().put("userId", userId).toString()

    fun verifyBody(userId: String, nonce: String, pk: ByteArray, msg: ByteArray, sig: ByteArray): String = JSONObject()
        .put("userId", userId).put("nonce", nonce)
        .put("address", b64(pk)).put("signedMessage", b64(msg)).put("signature", b64(sig))
        .toString()

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    /** Pure: the worker's /seeker/verify or /seeker/status answer. */
    fun parse(body: String): SeekerState? = runCatching {
        val o = JSONObject(body)
        SeekerState(o.optBoolean("verified"), o.optString("mint"), o.optString("address"), o.optLong("at"), o.optInt("bonusMin", 10),
            o.optString("reason").ifBlank { o.optString("error") })
    }.getOrNull()

    fun short(mint: String): String = if (mint.length > 10) mint.take(4) + "…" + mint.takeLast(4) else mint
}

class SeekerStore(ctx: Context) {
    private val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun state(): SeekerState = SeekerState(p.getBoolean("verified", false), p.getString("mint", "").orEmpty(), p.getString("address", "").orEmpty(),
        p.getLong("at", 0L), p.getInt("bonus", 10), p.getString("last", "").orEmpty())
    fun save(s: SeekerState) {
        val e = p.edit().putString("last", s.last).putInt("bonus", s.bonusMin)
        // a failed try never removes a verification the worker already stored
        if (s.verified) e.putBoolean("verified", true).putString("mint", s.mint).putString("address", s.address).putLong("at", s.at)
        e.apply()
    }
    companion object { const val PREFS = "solarchik.seeker" }
}
