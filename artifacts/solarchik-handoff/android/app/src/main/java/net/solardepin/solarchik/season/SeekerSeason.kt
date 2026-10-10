package net.solardepin.solarchik.season

import android.content.Context
import net.solardepin.solarchik.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 1.0.0 Seeker Season helper. A daily plan that nudges natural use; it never signs, repeats or fakes anything.
 * Season 2 scores Seed Vault Wallet activity on mainnet (onchain activity, dApp exploration, daily use) and
 * since 20 Aug 2026 discounts repetitive, bot-like transactions. This app only:
 *  - remembers on this phone the days you opened it and the suggested dApps you opened from here,
 *  - suggests real dApp Store apps (links out),
 *  - offers the daily check-in, which you sign yourself in your wallet (1.1.0: a real mainnet memo tx),
 *  - 1.1.0: counts real mainnet actions only (a confirmed check-in memo tx or a confirmed Jupiter swap today),
 *    never claims points,
 *  - reads your SKR balance on mainnet (read-only).
 */
data class SeasonDapp(val name: String, val pkg: String, val url: String, val about: Int, val short: String = name)

object SeasonDapps {
    /** Checked against the dApp Store catalog (seekertracker.com/api/dappstore mirror, 9 Oct 2026). Suggestions only. */
    val all = listOf(
        SeasonDapp("Orb", "dev.helius.orb", "https://orbmarkets.io", R.string.season_dapp_orb),
        SeasonDapp("Jupiter Mobile", "ag.jup.jupiter.android", "https://jup.ag", R.string.season_dapp_jupiter, "Jupiter"),
        SeasonDapp("Loopscale", "com.loopscale.app", "https://loopscale.com", R.string.season_dapp_loopscale),
        SeasonDapp("TokenRun", "com.tokenrun.app", "https://geodnet.com", R.string.season_dapp_tokenrun),
    )

    /** One suggestion per day, rotating. */
    fun forDay(day: LocalDate): SeasonDapp = all[Math.floorMod(day.toEpochDay(), all.size.toLong()).toInt()]
}

enum class SeasonItem { DAILY_USE, EXPLORE, ONCHAIN }

data class SeasonPlan(
    val day: LocalDate,
    val openedToday: Boolean,
    val streak: Int,
    val suggestion: SeasonDapp,
    val explored: String?,
    val signedToday: Boolean,
    val clockedToday: Boolean,
    /** 1.1.0: today's real mainnet action ("check-in" or "swap"), null when none. */
    val onchain: String? = null,
    /** 1.1.0: on mainnet only a real mainnet action ticks the onchain item; dev devnet mode keeps the signed check-in. */
    val mainnet: Boolean = false,
) {
    fun done(item: SeasonItem): Boolean = when (item) {
        SeasonItem.DAILY_USE -> openedToday
        SeasonItem.EXPLORE -> explored != null
        SeasonItem.ONCHAIN -> if (mainnet) onchain != null else signedToday
    }

    val doneCount: Int get() = SeasonItem.entries.count { done(it) }
    val total: Int get() = SeasonItem.entries.size

    /** What Sol says when asked about the Season. Built only from this plan. */
    fun spoken(ctx: Context): String {
        val parts = ArrayList<String>()
        parts += ctx.getString(R.string.season_say_head, doneCount, total)
        if (!done(SeasonItem.EXPLORE)) parts += ctx.getString(R.string.season_say_explore, suggestion.name, ctx.getString(suggestion.about))
        else parts += ctx.getString(R.string.season_say_explored, explored ?: suggestion.name)
        parts += when {
            mainnet && onchain == "swap" -> ctx.getString(R.string.mn_season_say_swap)
            mainnet && onchain != null -> ctx.getString(R.string.season_say_signed)
            mainnet && signedToday -> ctx.getString(R.string.mn_season_say_not_chain)
            signedToday -> ctx.getString(R.string.season_say_signed)
            clockedToday -> ctx.getString(R.string.season_say_sign)
            else -> ctx.getString(R.string.season_say_run)
        }
        parts += ctx.getString(R.string.season_say_fair)
        return parts.joinToString(" ")
    }
}

/** Local memory for the plan (opened days, dApps opened from the plan). Nothing leaves the phone. */
object SeasonStore {
    private const val PREF = "solarchik.season"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun today(): LocalDate = LocalDate.now()

    fun markOpened(ctx: Context, day: LocalDate = today()) {
        val days = (openedDays(ctx) + day.toString()).sorted().takeLast(120).toSet()
        p(ctx).edit().putStringSet("opened", days).apply()
    }

    fun openedDays(ctx: Context): Set<String> = p(ctx).getStringSet("opened", emptySet()).orEmpty()

    /** Consecutive opened days ending today (or yesterday, if today is not opened yet). */
    fun streak(days: Set<String>, today: LocalDate): Int {
        var d = if (today.toString() in days) today else today.minusDays(1)
        var n = 0
        while (d.toString() in days) { n++; d = d.minusDays(1) }
        return n
    }

    fun markExplored(ctx: Context, app: SeasonDapp, day: LocalDate = today()) {
        p(ctx).edit().putString("explored:$day", app.name).apply()
    }

    fun explored(ctx: Context, day: LocalDate = today()): String? = p(ctx).getString("explored:$day", null)

    /** 1.1.0: the SKR staking page opened from the plan counts as today's explore item (a link, nothing signed). */
    fun markExploredName(ctx: Context, name: String, day: LocalDate = today()) {
        p(ctx).edit().putString("explored:$day", name).apply()
    }

    /** 1.1.0: a confirmed real mainnet action today ("check-in" or "swap"). The first one of the day is kept. */
    fun markOnchain(ctx: Context, kind: String, day: LocalDate = today()) {
        if (onchainMarked(ctx, day) == null) p(ctx).edit().putString("onchain:$day", kind).apply()
    }

    fun onchainMarked(ctx: Context, day: LocalDate = today()): String? = p(ctx).getString("onchain:$day", null)

    /**
     * The plan for the current cluster. On mainnet the onchain item needs a real mainnet tx today: a check-in memo
     * transaction (not a detached message signature) or a confirmed swap.
     */
    fun planFor(ctx: Context, save: net.solardepin.solarchik.game.GameSave, mainnet: Boolean, day: LocalDate = today()): SeasonPlan {
        val signed = save.signedToday()
        val checkin = signed && save.clockCluster == "mainnet" && save.clockKind == "tx"
        val onchain = onchainMarked(ctx, day) ?: if (checkin) "check-in" else null
        return plan(ctx, signed, save.checkInOpen(), day).copy(onchain = onchain, mainnet = mainnet)
    }

    fun plan(ctx: Context, signedToday: Boolean, clockedToday: Boolean, day: LocalDate = today()): SeasonPlan {
        val days = openedDays(ctx)
        return SeasonPlan(day, day.toString() in days, streak(days, day), SeasonDapps.forDay(day), explored(ctx, day), signedToday, clockedToday)
    }
}

/** SKR on mainnet, read-only: the sum of the owner's SKR token accounts (getTokenAccountsByOwner). */
object Skr {
    const val MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
    const val RPC = "https://api.mainnet-beta.solana.com"
    /** 1.1.0: second public mainnet node when the first is rate limited. */
    const val RPC_FALLBACK = "https://solana-rpc.publicnode.com"
    /** 1.2.0: PublicNode now refuses getTokenAccountsByOwner without a key ("Indexed requests require a personal token"); this one answers it. */
    const val RPC_FALLBACK2 = "https://public.rpc.solanavibestation.com"
    const val STAKE_URL = "https://stake.solanamobile.com"

    class RpcError(message: String) : Exception(message)

    fun body(owner: String): String = JSONObject()
        .put("jsonrpc", "2.0").put("id", 1).put("method", "getTokenAccountsByOwner")
        .put("params", JSONArray().put(owner).put(JSONObject().put("mint", MINT)).put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
        .toString()

    /** Pure: parse the RPC answer into whole SKR (exact decimals, no float rounding before the end). */
    fun parse(json: String): Result<Double> = runCatching {
        val o = JSONObject(json)
        o.optJSONObject("error")?.let { throw RpcError(it.optString("message", "rpc error")) }
        val arr = o.getJSONObject("result").getJSONArray("value")
        var sum = BigDecimal.ZERO
        for (i in 0 until arr.length()) {
            val info = arr.getJSONObject(i).getJSONObject("account").getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
            if (info.optString("mint") != MINT) continue
            val amt = info.getJSONObject("tokenAmount")
            sum += BigDecimal(amt.getString("amount")).movePointLeft(amt.getInt("decimals"))
        }
        sum.toDouble()
    }

    private val client by lazy { OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build() }

    /** Blocking; call from IO. 1.1.0: the public node first, PublicNode when it fails (429 / 5xx / network). */
    fun fetch(owner: String): Result<Double> = fetch(owner, RPC)
        .recoverCatching { fetch(owner, RPC_FALLBACK2).getOrThrow() }
        .recoverCatching { fetch(owner, RPC_FALLBACK).getOrThrow() }

    fun fetch(owner: String, rpc: String): Result<Double> = runCatching {
        val req = Request.Builder().url(rpc).post(body(owner).toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful && text.isBlank()) throw RpcError("HTTP ${res.code}")
            parse(text).getOrThrow()
        }
    }
}
