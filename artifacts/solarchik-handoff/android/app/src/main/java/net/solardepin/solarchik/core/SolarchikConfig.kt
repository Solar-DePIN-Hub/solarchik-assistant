package net.solardepin.solarchik.core

import net.solardepin.solarchik.BuildConfig

/**
 * Every product number in one place. Mirrors the web rules in
 * src/lib/agents/fees.config.ts, user-limits.ts, src/lib/game/save.ts and src/lib/game/pay.ts.
 * Change a number here and the whole APK follows.
 */
object SolarchikConfig {
    // --- Strategy NFT tiers (fees.config.ts) ---
    /** PRO mint price, paid to [TREASURY]. */
    const val PRO_PRICE_SOL = 0.1
    /** FREE tier performance fee on realized profit only. PRO pays 0. */
    const val FREE_FEE_RATE = 0.05
    /** Metaplex Core Royalties plugin on both tiers. */
    const val ROYALTY_BPS = 500
    /** FREE mints allowed per wallet. */
    const val FREE_PER_WALLET = 1
    /** Paid PRO mint on mainnet (Seeker). Off until launch; devnet PRO always works. */
    val MAINNET_PAID_MINT: Boolean = BuildConfig.MAINNET_PAID_MINT

    // --- Treasury (pay.ts PAY_WALLET). Public receive address only. ---
    const val TREASURY = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic"

    // --- Streak + fee-free windows (save.ts) ---
    /** Signed days toward one 48h window. Counter restarts after the reward. */
    const val STREAK_SHORT_DAYS = 7
    const val WINDOW_SHORT_HOURS = 48L
    /** Signed days toward one 7-day window at 30/60/90… Counter does not restart. */
    const val STREAK_LONG_DAYS = 30
    const val WINDOW_LONG_DAYS = 7L
    const val WINDOW_SHORT_MS = WINDOW_SHORT_HOURS * 60 * 60 * 1000
    const val WINDOW_LONG_MS = WINDOW_LONG_DAYS * 24 * 60 * 60 * 1000
    /** Kept UTC days of signed history (web keeps 120). */
    const val CLOCK_DAYS_KEEP = 120

    // --- Run ---
    /** Meters that unlock today's signed CLOCK IN. */
    const val RUN_GOAL_M = 1200

    // --- Agent risk caps (user-limits.ts). User caps can only go down from these. ---
    const val HARD_MAX_TRADE_SOL = 0.02
    const val HARD_DAY_CAP_SOL = 0.3
    const val HARD_MAX_LOSSES = 2
    const val HARD_DAY_LOSS_SOL = 0.3

    // --- Chain ---
    const val MPL_CORE_PROGRAM = "CoREENxT6tW1HoK8ypY1SxRMZTcVPm7R94rH4PZNhX7d"
    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    const val MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
    const val AGENT_URI = "urn:solarchik:agent"
    const val CORE_NAME_MAX = 32
    const val RPC_DEVNET = "https://api.devnet.solana.com"
    /** Server proxy for devnet JSON-RPC: retries 429/5xx and falls back to a second node (0.21.7). */
    const val RPC_DEVNET_FALLBACK = "https://solarchik-market.vercel.app/solana-rpc"
    const val RPC_MAINNET = "https://api.mainnet-beta.solana.com"
    /**
     * 1.1.0: no paid RPC key exists for the app, so mainnet uses the public node with retries and falls
     * back to PublicNode's free endpoint. Both are rate limited; heavy use can still see 429s.
     */
    const val RPC_MAINNET_FALLBACK = "https://solana-rpc.publicnode.com"
    /** 1.1.0: Metaplex Core mints on mainnet need the mainnet collection (created by Vadym's funded key). */
    val MAINNET_MINT_READY: Boolean = BuildConfig.MAINNET_MINT_READY
    val MAINNET_COLLECTION: String = BuildConfig.MAINNET_COLLECTION
    val MAINNET_COLLECTION_AUTHORITY: String = BuildConfig.MAINNET_COLLECTION_AUTHORITY
    /** The worker checks a mainnet mint (tier from the asset name, Pro/Combo payment, Combo paid-only) before co-signing. */
    const val MINT_COSIGN_URL = "https://solarchik-screen.davidbell1603.workers.dev/agent/mint-cosign"
    const val AIRDROP_SOL = 1.0
    const val LAMPORTS_PER_SOL = 1_000_000_000L

    // --- Sol (AI friend worker) ---
    const val FRIEND_CHAT_URL = "https://friend.solardepin.net/v1/chat"
    /** Primary Sol chat (Gemini, solarchik-market server; same host as StrategyMarket). */
    const val SOL_CHAT_URL = "https://solarchik-market.vercel.app/api/native/sol-chat"
    /** Sol's neural voice (Gemini TTS, WAV). The system TTS is only the offline fallback. */
    const val SOL_VOICE_URL = "https://solarchik-market.vercel.app/api/native/sol-voice"
    /** Sol "do things" mode: model → one structured action; the app confirms and executes (0.21.7). */
    const val SOL_ACT_URL = "https://solarchik-market.vercel.app/api/native/sol-act"

    /**
     * 0.21.8: Sol's brain (OpenAI gpt-4.1-mini on the solarchik-screen worker, NDJSON stream, one
     * propose_action tool). Primary for every Sol message; the market sol-act route is the fallback.
     */
    const val SOL_BRAIN_URL = "https://solarchik-screen.davidbell1603.workers.dev/sol/chat"
    /** 0.21.8: Sol's voice (OpenAI gpt-4o-mini-tts, 24 kHz PCM streamed; edge-cached per line). */
    /** 1.0.1: tells the worker this is Solarchik Assistant, so /sol/chat uses the pocket-assistant prompt (runs keep the game prompt). */
    const val SOL_APP = "assistant"
    const val SOL_TTS_URL = "https://solarchik-screen.davidbell1603.workers.dev/sol/tts"

    // --- Explorers (1.1.0: mainnet links go to Solscan, Orb as the second link) ---
    fun solscanTx(sig: String, cluster: String): String =
        if (cluster == "devnet") "https://solscan.io/tx/$sig?cluster=devnet" else "https://solscan.io/tx/$sig"
    fun solscanAccount(addr: String, cluster: String): String =
        if (cluster == "devnet") "https://solscan.io/account/$addr?cluster=devnet" else "https://solscan.io/account/$addr"
    fun orbTx(sig: String): String = "https://orbmarkets.io/tx/$sig"
    fun orbAccount(addr: String): String = "https://orbmarkets.io/address/$addr"

    fun lamports(sol: Double): Long = Math.round(sol * LAMPORTS_PER_SOL)
}
