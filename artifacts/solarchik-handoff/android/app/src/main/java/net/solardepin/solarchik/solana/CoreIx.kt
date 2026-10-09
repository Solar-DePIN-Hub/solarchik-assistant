package net.solardepin.solarchik.solana

import net.solardepin.solarchik.core.SolarchikConfig
import org.sol4k.PublicKey

/**
 * Metaplex Core CreateV1, hand-encoded (no Kotlin SDK exists).
 * Layout matches @metaplex-foundation/mpl-core createV1:
 *   u8 discriminator (0) | u8 DataState (0 = AccountState) | string name | string uri |
 *   Option<Vec<PluginAuthorityPair>> plugins   (each pair: Plugin enum + Option<Authority>)
 * Accounts: asset(w,s) collection? authority? payer(w,s) owner? updateAuthority? system logWrapper?
 * Missing optional accounts are passed as the Core program id (Shank "optional = program id").
 */
object CoreIx {
    val PROGRAM = PublicKey(SolarchikConfig.MPL_CORE_PROGRAM)

    data class Creator(val address: PublicKey, val percentage: Int)

    sealed class Plugin {
        /** Plugin enum index 0. */
        data class Royalties(val basisPoints: Int, val creators: List<Creator>) : Plugin()
        /** Plugin enum index 6. */
        data class Attributes(val list: List<Pair<String, String>>) : Plugin()
    }

    fun createV1Data(name: String, uri: String, plugins: List<Plugin>): ByteArray {
        require(name.encodeToByteArray().size <= SolarchikConfig.CORE_NAME_MAX) { "name too long" }
        val w = Borsh().u8(0).u8(0).string(name).string(uri)
        w.u8(1) // Some(plugins)
        w.vec(plugins) { p ->
            when (p) {
                is Plugin.Royalties -> {
                    u8(0)
                    u16(p.basisPoints)
                    vec(p.creators) { c -> pubkey(c.address); u8(c.percentage) }
                    u8(0) // RuleSet::None
                }
                is Plugin.Attributes -> {
                    u8(6)
                    vec(p.list) { (k, v) -> string(k); string(v) }
                }
            }
            u8(0) // authority: None -> plugin default
        }
        // CreateV1 has no external plugin adapter field (that is CreateV2).
        return w.toByteArray()
    }

    fun createV1(
        asset: PublicKey,
        payer: PublicKey,
        name: String,
        uri: String,
        plugins: List<Plugin>,
        owner: PublicKey? = null,
        /** 1.1.0 mainnet: the Solarchik collection and its update authority (co-signed by the server). */
        collection: PublicKey? = null,
        authority: PublicKey? = null,
    ): Ix {
        val none = Meta(PROGRAM, signer = false, writable = false)
        return Ix(
            PROGRAM,
            listOf(
                Meta(asset, signer = true, writable = true),
                collection?.let { Meta(it, signer = false, writable = true) } ?: none, // collection
                authority?.let { Meta(it, signer = true, writable = false) } ?: none, // authority (payer signs when none)
                Meta(payer, signer = true, writable = true),
                owner?.let { Meta(it, signer = false, writable = false) } ?: none,
                none, // update authority = payer
                Meta(SystemIx.PROGRAM, signer = false, writable = false),
                none, // log wrapper
            ),
            createV1Data(name, uri, plugins),
        )
    }

    /** Strategy NFT plugins: 5% royalty to treasury + compact attributes (sku, tier, class, role, fee, lanes). */
    fun agentPlugins(skuId: String, tier: String, classId: Int, role: String, feeBps: Int, lanes: String): List<Plugin> = listOf(
        Plugin.Royalties(SolarchikConfig.ROYALTY_BPS, listOf(Creator(PublicKey(SolarchikConfig.TREASURY), 100))),
        Plugin.Attributes(
            listOf(
                "sku" to skuId,
                "tier" to tier,
                "class" to classId.toString(),
                "role" to role,
                "fee" to feeBps.toString(),
                "ln" to lanes,
                "track" to "live",
            ),
        ),
    )

    /** AssetV1 account: key u8 (1) then owner pubkey. */
    fun ownerOf(data: ByteArray): PublicKey? {
        if (data.size < 33 || data[0].toInt() != 1) return null
        return PublicKey(data.copyOfRange(1, 33))
    }

    /** AssetV1: key | owner | UpdateAuthority(u8 tag + 32 when Address/Collection) | name | uri. */
    fun nameOf(data: ByteArray): String? = runCatching {
        var o = 33
        val tag = data[o].toInt()
        o += 1
        if (tag == 1 || tag == 2) o += 32
        val len = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or
            ((data[o + 2].toInt() and 0xff) shl 16) or ((data[o + 3].toInt() and 0xff) shl 24)
        o += 4
        if (len < 0 || len > 64 || o + len > data.size) return null
        String(data, o, len, Charsets.UTF_8)
    }.getOrNull()
}
