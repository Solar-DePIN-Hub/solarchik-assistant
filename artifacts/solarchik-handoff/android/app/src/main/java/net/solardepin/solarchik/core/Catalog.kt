package net.solardepin.solarchik.core

import net.solardepin.solarchik.R

/** Wire names match web NftTier. */
object AgentTier {
    const val FREE = "free"
    const val PRO = "pro"
}

/** Web NftClassId: 1 prediction, 2 dex/arb, 3 combo. */
enum class AgentClass(val id: Int, val role: String, val titleRes: Int, val shortRes: Int, val blurbRes: Int) {
    PREDICTION(1, "pred", R.string.class_pred_title, R.string.class_pred_short, R.string.class_pred_blurb),
    DEX(2, "dex", R.string.class_dex_title, R.string.class_dex_short, R.string.class_dex_blurb),
    COMBO(3, "combo", R.string.class_combo_title, R.string.class_combo_short, R.string.class_combo_blurb);

    companion object {
        fun of(id: Int): AgentClass = entries.firstOrNull { it.id == id } ?: PREDICTION
    }
}

/**
 * One base strategy. Each base comes as FREE (`id`) and PRO (`id-pro`), like web catalog.ts withTiers,
 * except a [paidOnly] base (Combo), which is sold only as PRO: no free mint exists for it.
 */
data class AgentSku(
    val id: String,
    val name: String,
    val agentClass: AgentClass,
    /** Lanes as web: c = crypto 15m, e = events, w = weather; dex for arb. */
    val lanes: String,
    val blurbRes: Int,
    val artRes: Int,
    val accent: Int,
    val paidOnly: Boolean = false,
) {
    /** The tier actually sold for a picked tier: a paid-only base is always PRO. */
    fun tierFor(picked: String): String = if (paidOnly) AgentTier.PRO else picked
    fun skuId(tier: String): String = if (tier == AgentTier.PRO) "$id-pro" else id
    fun nameFor(tier: String): String =
        (if (tier == AgentTier.PRO) "$name Pro" else name).take(SolarchikConfig.CORE_NAME_MAX)
    fun priceSol(tier: String): Double = Catalog.offerFor(skuId(tier)).second
}

object Catalog {
    val skus: List<AgentSku> = listOf(
        AgentSku("sku-pred-alpha", "Bitcoin Windows #11", AgentClass.PREDICTION, "c", R.string.sku_alpha_blurb, R.drawable.robot_ember, 0xFFFF9F43.toInt()),
        AgentSku("sku-pred-events", "Events Scout #04", AgentClass.PREDICTION, "e", R.string.sku_events_blurb, R.drawable.robot_prism, 0xFFA78BFA.toInt()),
        AgentSku("sku-pred-weather", "Weather Station", AgentClass.PREDICTION, "w", R.string.sku_weather_blurb, R.drawable.robot_frost, 0xFF7AD1FF.toInt()),
        AgentSku("sku-combo-prime", "Combo Prime", AgentClass.COMBO, "cew", R.string.sku_combo_blurb, R.drawable.robot_sunflower, 0xFFF5C542.toInt(), paidOnly = true),
        AgentSku("sku-dex-arb", "Backpack SOL Desk", AgentClass.DEX, "dex", R.string.sku_arb_blurb, R.drawable.robot_midnight, 0xFF5BD69A.toInt()),
    )

    /** 1.1.0: game skus plus the three assistant agents (Season Agent, Saver, Watcher). */
    private val allSkus: List<AgentSku> get() = skus + AssistantCatalog.skus

    private val proIds: Set<String> get() = allSkus.map { "${it.id}-pro" }.toSet()

    /** Port of web offerFor: (tier, price). */
    /** Free ids that are never sold (web PAID_ONLY_BASE_SKUS). */
    val paidOnlyFreeIds: Set<String> = skus.filter { it.paidOnly }.map { it.id }.toSet()

    fun offerFor(id: String): Pair<String, Double> =
        if (id in proIds) AgentTier.PRO to SolarchikConfig.PRO_PRICE_SOL else AgentTier.FREE to 0.0

    fun baseOf(skuId: String): AgentSku? = allSkus.firstOrNull { it.id == skuId.removeSuffix("-pro") }

    /**
     * Names already minted on-chain before a rename. "Titan × Backpack" (until 0.20.4) promised an arbitrage, but the
     * native agent forecasts SOL/USDC direction from Backpack prices, so it is "Backpack SOL Desk" now (same sku id).
     */
    private val legacyNames = mapOf("Titan × Backpack" to "sku-dex-arb")

    /** Recovers sku + tier from an on-chain Core name ("Combo Prime Pro"). */
    fun fromName(name: String): Pair<AgentSku, String>? {
        val clean = name.trim()
        for ((old, id) in legacyNames) {
            val sku = skus.first { it.id == id }
            if (clean == "$old Pro") return sku to AgentTier.PRO
            if (clean == old) return sku to AgentTier.FREE
        }
        for (sku in allSkus) {
            if (clean == sku.nameFor(AgentTier.PRO)) return sku to AgentTier.PRO
            if (clean == sku.name) return sku to AgentTier.FREE
        }
        return null
    }

    fun feeRateFor(tier: String): Double = if (tier == AgentTier.FREE) SolarchikConfig.FREE_FEE_RATE else 0.0
}
