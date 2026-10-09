package net.solardepin.solarchik.core

import net.solardepin.solarchik.R

/**
 * 1.1.0: the Solarchik Assistant shows exactly three agents. The game's strategy catalog ([Catalog.skus]:
 * Bitcoin Windows, Events Scout, Weather, Combo, Backpack desk, Slice) stays in the code for the shared paper
 * engine and old devnet NFTs, but is not shown in the assistant UI.
 *
 * Agent NFTs map onto these three as Free and Pro (Pro = 0.1 SOL to the treasury, checked by the worker by the
 * " Pro" name suffix). None of them is paid-only; the worker's Combo paid-only rule stays in place untouched.
 */
enum class AssistantAgent(val key: String, val titleRes: Int, val roleRes: Int, val accent: Int, val artRes: Int) {
    SEASON("season", R.string.aa_season, R.string.aa_season_role, 0xFF7AD1FF.toInt(), R.drawable.robot_frost),
    SAVER("saver", R.string.aa_saver, R.string.aa_saver_role, 0xFF5BD69A.toInt(), R.drawable.robot_midnight),
    WATCHER("watcher", R.string.aa_watcher, R.string.aa_watcher_role, 0xFFF5C542.toInt(), R.drawable.robot_sunflower);

    /** On-chain Core name (English, like every earlier Solarchik agent). */
    val nftName: String get() = when (this) {
        SEASON -> "Season Agent"
        SAVER -> "Saver"
        WATCHER -> "Watcher"
    }

    val sku: AgentSku get() = AssistantCatalog.skus[ordinal]

    companion object {
        fun of(key: String): AssistantAgent? = entries.firstOrNull { it.key == key }
    }
}

object AssistantCatalog {
    /** Mint SKUs (Free = id, Pro = id-pro), shared with [Catalog.offerFor] / [Catalog.fromName]. */
    val skus: List<AgentSku> = listOf(
        AgentSku("sku-season", "Season Agent", AgentClass.PREDICTION, "season", R.string.aa_season_role, R.drawable.robot_frost, 0xFF7AD1FF.toInt()),
        AgentSku("sku-saver", "Saver", AgentClass.DEX, "saver", R.string.aa_saver_role, R.drawable.robot_midnight, 0xFF5BD69A.toInt()),
        AgentSku("sku-watcher", "Watcher", AgentClass.PREDICTION, "watch", R.string.aa_watcher_role, R.drawable.robot_sunflower, 0xFFF5C542.toInt()),
    )

    fun agentOf(skuId: String): AssistantAgent? {
        val base = skuId.removeSuffix("-pro")
        return AssistantAgent.entries.firstOrNull { it.sku.id == base }
    }
}
