package net.solardepin.solarchik

import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.agents.MintCollection
import net.solardepin.solarchik.agents.Minter
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.solana.CoreIx
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.wallet.Base58
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * 1.1.0 collection-mint rehearsal on DEVNET (-Prehearsal=<config.json>): builds the exact mint txs the app sends
 * on mainnet (collection + authority co-signer), signed by a devnet test payer. A Node driver then asks the
 * worker's co-sign logic for the authority signature, sends them to devnet and verifies them on chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevnetMintRehearsalIT {
    @Test fun buildCollectionMints() {
        val path = System.getProperty("solarchik.rehearsal").orEmpty()
        assumeTrue(path.isNotBlank())
        val cfg = JSONObject(File(path).readText())
        val secret = JSONArray(File(cfg.getString("payerPath")).readText())
        val payer = org.sol4k.Keypair.fromSecretKey(ByteArray(64) { secret.getInt(it).toByte() })
        val coll = MintCollection(org.sol4k.PublicKey(cfg.getString("collection")), org.sol4k.PublicKey(cfg.getString("authority")))
        val hash = Base58.decode(cfg.getString("blockhash"))
        val free = Catalog.skus.first { !it.paidOnly }
        val combo = Catalog.skus.first { it.paidOnly }
        val out = JSONObject().put("payer", payer.publicKey.toBase58())
        fun put(k: String, tx: LegacyTx, asset: org.sol4k.Keypair) {
            out.put(k, Base64.getEncoder().encodeToString(tx.partialSign(payer).serialize())).put(k + "Asset", asset.publicKey.toBase58())
        }
        var a = org.sol4k.Keypair.generate(); put("free", Minter.buildMintTx(payer.publicKey, hash, free, AgentTier.FREE, a, coll), a)
        a = org.sol4k.Keypair.generate(); put("pro", Minter.buildMintTx(payer.publicKey, hash, free, AgentTier.PRO, a, coll), a)
        a = org.sol4k.Keypair.generate(); put("combo", Minter.buildMintTx(payer.publicKey, hash, combo, AgentTier.PRO, a, coll), a)
        a = org.sol4k.Keypair.generate()
        put("comboUnpaid", LegacyTx.compile(payer.publicKey, hash, listOf(
            CoreIx.createV1(a.publicKey, payer.publicKey, combo.nameFor(AgentTier.PRO), SolarchikConfig.AGENT_URI, emptyList(), collection = coll.address, authority = coll.authority),
        )).partialSign(a), a)
        File(cfg.getString("out")).writeText(out.toString(2))
    }
}
