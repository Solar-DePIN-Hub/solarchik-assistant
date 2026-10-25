package net.solardepin.solarchik.screen

import android.content.Context
import android.net.Uri
import android.os.Build
import net.solardepin.solarchik.wallet.Base58
import java.security.SecureRandom

/**
 * Call secretary settings and decisions.
 *
 * No READ_CONTACTS: Android only hands calls from numbers that are NOT in the user's contacts to a
 * CallScreeningService without that permission (CallScreeningService.onScreenCall docs), so
 * contacts always ring normally and the app never reads the phone book.
 */
object Secretary {
    enum class Mode { SILENCE, DECLINE }
    enum class Action { ALLOW, SILENCE, DECLINE }

    /** Android 10 (API 29) added ROLE_CALL_SCREENING and CallResponse.setSilenceCall. */
    const val MIN_SDK = Build.VERSION_CODES.Q

    const val PAY_WALLET = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"
    const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val TOPUP_USD = 5
    const val NOTE_USD = 0.20

    private const val PREF = "solarchik.secretary"

    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= MIN_SDK

    /** Only incoming calls, only with the secretary on, only where the platform supports silencing. */
    fun decide(incoming: Boolean, enabled: Boolean, mode: Mode, sdk: Int = Build.VERSION.SDK_INT): Action = when {
        !incoming || !enabled || !supported(sdk) -> Action.ALLOW
        mode == Mode.DECLINE -> Action.DECLINE
        else -> Action.SILENCE
    }

    fun mode(ctx: Context): Mode = runCatching { Mode.valueOf(prefs(ctx).getString("mode", null) ?: "") }.getOrDefault(Mode.SILENCE)
    fun setMode(ctx: Context, m: Mode) = prefs(ctx).edit().putString("mode", m.name).apply()

    /** Paid AI note per screened call ($0.20 from credit). Off until the player turns it on. */
    fun aiNotes(ctx: Context): Boolean = prefs(ctx).getBoolean("aiNotes", false)
    fun setAiNotes(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("aiNotes", on).apply()

    fun lastUsd(ctx: Context): Double? = prefs(ctx).getString("usd", null)?.toDoubleOrNull()
    fun setLastUsd(ctx: Context, usd: Double) = prefs(ctx).edit().putString("usd", usd.toString())
        .putBoolean("needTopup", usd < NOTE_USD && needTopup(ctx)).apply()

    /** The last balance read from the worker (Settings + Home show it before the next fetch lands). */
    fun lastBalance(ctx: Context): ScreenApi.Balance? {
        val p = prefs(ctx)
        val usd = p.getString("usd", null)?.toDoubleOrNull() ?: return null
        val trial = p.getString("trialUsd", null)?.toDoubleOrNull() ?: 0.0
        val paid = p.getString("paidUsd", null)?.toDoubleOrNull() ?: (usd - trial).coerceAtLeast(0.0)
        return ScreenApi.Balance(usd, paid, trial, trial > 0, p.getBoolean("owner", false))
    }
    fun setLastBalance(ctx: Context, b: ScreenApi.Balance) {
        prefs(ctx).edit().putString("paidUsd", b.paidUsd.toString()).putString("trialUsd", b.trialUsd.toString()).putBoolean("owner", b.owner).apply()
        setLastUsd(ctx, b.usd)
        // 0.22.0: the owner's ids are never blocked by credit (server-side exemption), so no top-up warning
        if (b.owner) prefs(ctx).edit().putBoolean("needTopup", false).apply()
    }

    /** Secretary voice language as last saved/read ("auto", "uk", "en"). */
    fun lang(ctx: Context): String = prefs(ctx).getString("lang", null)?.takeIf { it in ScreenApi.LANGS } ?: "auto"
    fun setLang(ctx: Context, lang: String) { if (lang in ScreenApi.LANGS) prefs(ctx).edit().putString("lang", lang).apply() }

    /** Wallet apps offered when no app handles solana: links (Play Store ids). */
    val WALLET_APPS = listOf("Phantom" to "app.phantom", "Solflare" to "com.solflare.mobile")

    fun needTopup(ctx: Context): Boolean = prefs(ctx).getBoolean("needTopup", false)
    fun setNeedTopup(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("needTopup", on).apply()

    fun pendingRef(ctx: Context): String? = prefs(ctx).getString("pendingRef", null)?.takeIf { it.isNotBlank() }
    fun setPendingRef(ctx: Context, ref: String?) = prefs(ctx).edit().putString("pendingRef", ref).apply()

    /** A fresh Solana Pay reference: 32 random bytes as a base58 public key. */
    fun newReference(rnd: SecureRandom = SecureRandom()): String = Base58.encode(ByteArray(32).also { rnd.nextBytes(it) })

    /** The worker matches the memo against userId.slice(0, 32). */
    fun memo(userId: String): String = userId.take(32)

    /** Solana Pay transfer request: [TOPUP_USD] USDC to the secretary treasury, tagged with reference and memo. */
    fun payUri(userId: String, reference: String, amount: Int = TOPUP_USD): String =
        "solana:$PAY_WALLET?amount=$amount&spl-token=$USDC_MINT&reference=$reference" +
            "&label=" + Uri.encode("Solarchik") + "&message=" + Uri.encode("Call secretary credit") +
            "&memo=" + Uri.encode(memo(userId))

    // ---- Carrier call forwarding (GSM MMI codes, dialed by the user from the system dialer) ----

    /** GSM conditional call forwarding. The carrier forwards; the app only pre-fills the dialer (ACTION_DIAL). */
    enum class Forward(val code: String) { NO_ANSWER("61"), BUSY("67"), UNREACHABLE("62") }

    /** Cancels all conditional forwarding (no answer + busy + unreachable) in one code. */
    const val FORWARD_ALL_OFF = "##004#"

    /**
     * The UA Sol secretary line (Zadarma, auto-renew on; paid until 21.11.2026 per the Zadarma API on 25.10.2026). Other countries: DEFAULT_LINES.
     * Same default as the web app (src/lib/game/save.ts secNumber). The player can change or clear it in Settings.
     */
    const val DEFAULT_FORWARD_NUMBER = "+380914810885"

    /** 1.2.15: one Sol line per country so forwarding stays a local/cheap call. The worker's GET /lines can add more. */
    data class Line(val country: String, val number: String)
    val DEFAULT_LINES = listOf(
        Line("UA", "+380914810885"), Line("US", "+18325009920"), Line("CA", "+15816899510"),
        Line("GB", "+442045771829"), Line("DK", "+4532330495"),
    )
    private val AMERICAS = setOf("US", "CA", "MX", "BR", "AR", "CL", "CO", "PE", "VE", "EC", "BO", "PY", "UY", "GT", "CR",
        "PA", "HN", "SV", "NI", "DO", "CU", "PR", "JM", "HT", "TT", "BS", "BB", "BZ", "GY", "SR")
    private val EUROPE = setOf("GB", "IE", "DK", "SE", "NO", "FI", "IS", "DE", "FR", "NL", "BE", "LU", "AT", "CH", "IT", "ES",
        "PT", "PL", "CZ", "SK", "HU", "RO", "BG", "GR", "HR", "SI", "RS", "BA", "ME", "MK", "AL", "EE", "LV", "LT", "MT",
        "CY", "LI", "MC", "SM", "AD", "VA")

    /** Exact country line, else US for the Americas, GB for Europe, UA otherwise (UA/MD/GE/… and the rest). */
    fun lineFor(country: String?, lines: List<Line> = DEFAULT_LINES): Line {
        val c = country.orEmpty().trim().uppercase()
        fun pick(cc: String) = lines.firstOrNull { it.country == cc }
        return pick(c) ?: when (c) {
            in AMERICAS -> pick("US")
            in EUROPE -> pick("GB")
            else -> null
        } ?: pick("UA") ?: lines.firstOrNull() ?: DEFAULT_LINES[0]
    }

    private val NANP = setOf("US", "CA", "PR", "DO", "JM", "TT", "BS", "BB")

    /** US/CA carriers (Verizon and other ex-CDMA networks) ignore GSM ** codes: *72 + number on, *73 off. */
    fun usesCdmaCodes(country: String): Boolean = country.uppercase() == "US" || country.uppercase() == "CA"
    fun cdmaForwardCode(number: String): String? = cleanNumber(number)?.let { "*72" + it.removePrefix("+") }
    fun cdmaNoAnswerCode(number: String): String? = cleanNumber(number)?.let { "*71" + it.removePrefix("+") }
    const val CDMA_OFF = "*73"

    /** Chosen SIM for forwarding (subscription id), -1 = default SIM. */
    fun forwardSim(ctx: Context): Int = prefs(ctx).getInt("fwdSim", -1)
    fun setForwardSim(ctx: Context, id: Int) = prefs(ctx).edit().putInt("fwdSim", id).apply()

    /** Emoji flag for an ISO country code ("UA" -> 🇺🇦). */
    fun flag(country: String): String =
        country.uppercase().takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            ?.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }?.joinToString("") ?: "🌐"

    /** SIM country, then network country, then the phone's locale. */
    fun detectCountry(ctx: Context): String {
        val base = runCatching { ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager }.getOrNull()
        val sim = forwardSim(ctx)
        val tm = if (sim >= 0 && Build.VERSION.SDK_INT >= 24) runCatching { base?.createForSubscriptionId(sim) }.getOrNull() ?: base else base
        return listOf(runCatching { tm?.simCountryIso }.getOrNull(), runCatching { tm?.networkCountryIso }.getOrNull(),
            java.util.Locale.getDefault().country).firstOrNull { !it.isNullOrBlank() && it.length == 2 }.orEmpty().uppercase()
    }

    /** Lines from the worker (cached JSON) when valid, else the built-in list. */
    fun lines(ctx: Context): List<Line> = parseLines(prefs(ctx).getString("lines", null)).ifEmpty { DEFAULT_LINES }
    fun saveLines(ctx: Context, json: String) { if (parseLines(json).isNotEmpty()) prefs(ctx).edit().putString("lines", json).apply() }
    fun parseLines(json: String?): List<Line> = runCatching {
        val a = org.json.JSONObject(json ?: return emptyList()).getJSONArray("lines")
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i); val c = o.optString("country").uppercase(); val n = cleanNumber(o.optString("number"))
            if (c.length == 2 && n != null) Line(c, n) else null
        }
    }.getOrDefault(emptyList())

    /** Automatic line for this phone. */
    fun autoLine(ctx: Context): Line = lineFor(detectCountry(ctx), lines(ctx))
    /** True when the player picked a line (or typed one) instead of automatic. */
    fun forwardChosen(ctx: Context): Boolean = prefs(ctx).contains("fwdNumber")
    fun clearForwardChoice(ctx: Context) { prefs(ctx).edit().remove("fwdNumber").apply() }

    /** Saved number; the automatic country line until the player picks another one; "" once the player cleared it. */
    fun forwardNumber(ctx: Context): String = prefs(ctx).getString("fwdNumber", null) ?: autoLine(ctx).number

    /** Saves a valid number (returns it) or leaves the old one and returns null. Blank clears it. */
    fun setForwardNumber(ctx: Context, raw: String): String? {
        if (raw.isBlank()) {
            prefs(ctx).edit().putString("fwdNumber", "").apply()
            return ""
        }
        val clean = cleanNumber(raw) ?: return null
        prefs(ctx).edit().putString("fwdNumber", clean).apply()
        return clean
    }

    /**
     * International format only, so forwarding works from any country: "+" (or "00") then 8..15 digits (E.164).
     * Spaces, dashes, dots and brackets are dropped. Anything else (letters, *, #, a second +) is rejected,
     * so a typed number can never change the MMI code itself.
     */
    fun cleanNumber(raw: String): String? {
        var t = raw.trim().filterNot { it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' || it == '\u00A0' }
        if (t.startsWith("00")) t = "+" + t.drop(2)
        if (!t.startsWith("+")) return null
        val digits = t.drop(1)
        if (digits.length !in 8..15 || !digits.all { it in '0'..'9' } || digits.startsWith("0")) return null
        return "+$digits"
    }

    fun forwardOnCode(kind: Forward, number: String): String? = cleanNumber(number)?.let { "**${kind.code}*$it#" }
    fun forwardOffCode(kind: Forward): String = "##${kind.code}#"

    /** 1.2.6: all conditional forwarding (no answer + busy + unreachable) in one code. */
    fun forwardAllCode(number: String): String? = cleanNumber(number)?.let { "**004*$it#" }

    /** 1.2.6: this phone's own number, verified by a call from it to the line (see ForwardPanel). */
    fun ownNumber(ctx: Context): String = prefs(ctx).getString("ownNumber", "").orEmpty()
    fun ownVerified(ctx: Context): Boolean = prefs(ctx).getBoolean("ownVerified", false)
    fun setOwn(ctx: Context, number: String, verified: Boolean) = prefs(ctx).edit().putString("ownNumber", number).putBoolean("ownVerified", verified).apply()

    /** Same hash as the worker's callerHash: SHA-256("solarchik-fwd:" + E.164), first 16 bytes, hex. */
    fun callerHash(e164: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(("solarchik-fwd:" + e164).toByteArray())
        return d.take(16).joinToString("") { "%02x".format(it) }
    }

    /** "+380 63 744 37 92" / "0637443792" -> "+380637443792" (Ukraine default for a leading 0), "" if not a number. */
    /** 1.2.15: national numbers are read in the SIM's country (US "2025550123" -> +12025550123); UA rules stay the fallback. */
    fun e164(raw: String, country: String = "UA"): String {
        val d = raw.filter { it.isDigit() }
        val c = country.trim().uppercase()
        if (!raw.trim().startsWith("+") && c.length == 2 && c != "UA") {
            runCatching { android.telephony.PhoneNumberUtils.formatNumberToE164(raw, c) }.getOrNull()
                ?.takeIf { it.startsWith("+") && it.length in 9..16 }?.let { return it }
            if (c in NANP && d.length == 10) return "+1$d"
            if (c in NANP && d.length == 11 && d.startsWith("1")) return "+$d"
        }
        return when {
            raw.trim().startsWith("+") && d.length in 8..15 -> "+$d"
            d.startsWith("380") && d.length == 12 -> "+$d"
            d.startsWith("0") && d.length == 10 -> "+38$d"
            else -> ""
        }
    }

    /** tel: URI for ACTION_DIAL; "#" must be escaped or the dialer drops everything after it. */
    fun dialUri(code: String): Uri = Uri.parse("tel:" + Uri.encode(code))

    /** True when the player picked Solarchik as the call screening app (API 29+ only). */
    fun holdsRole(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = ctx.getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
