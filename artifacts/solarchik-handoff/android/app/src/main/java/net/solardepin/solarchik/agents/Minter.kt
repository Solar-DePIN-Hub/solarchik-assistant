package net.solardepin.solarchik.agents

import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import net.solardepin.solarchik.core.AgentSku
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.solana.CoreIx
import net.solardepin.solarchik.solana.Ix
import net.solardepin.solarchik.solana.LegacyTx
import net.solardepin.solarchik.solana.SystemIx
import net.solardepin.solarchik.wallet.Base58
import net.solardepin.solarchik.wallet.SentTx
import net.solardepin.solarchik.wallet.SolanaWallet
import net.solardepin.solarchik.wallet.WalletError
import org.sol4k.Keypair
import org.sol4k.PublicKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MintError(val kind: Kind, detail: String = "") : Exception(detail.ifBlank { kind.name }) {
    enum class Kind { FREE_USED, PRO_MAINNET_OFF, TOO_BIG, WALLET_CHANGED, PAID_ONLY, MAINNET_SOON }
}

/** Builds and sends Metaplex Core strategy NFT mints through MWA. */
class Minter(
    private val wallet: SolanaWallet,
    private val store: AgentStore,
    private val send: suspend (ActivityResultSender, suspend (PublicKey, ByteArray) -> LegacyTx) -> Result<SentTx> =
        { sender, build -> wallet.signAndSend(sender, build) },
    private val cluster: () -> String = { wallet.clusterName },
    private val clock: () -> Long = System::currentTimeMillis,
    /** The wallet's signature of [FreeAsset.message] (raw 64 bytes). */
    private val freeSeed: suspend (ActivityResultSender, String) -> Result<ByteArray> = { sender, addr ->
        wallet.signText(sender, FreeAsset.message(addr)).mapCatching { proof ->
            if (proof.address != addr) throw MintError(MintError.Kind.WALLET_CHANGED)
            Base58.decode(proof.signature)
        }
    },
    /**
     * 1.1.0 mainnet: the server's collection-authority signature for [tx] (null = not a mainnet collection mint).
     * The server refuses a Combo without payment, so a patched app still cannot mint a free Combo.
     */
    private val cosign: suspend (LegacyTx) -> ByteArray = { tx -> MintCosign.request(tx) },
    /** Owner of an existing Core asset at this address, "" for another program's account, null when absent. */
    private val coreOwner: suspend (String) -> String? = { address ->
        wallet.rpc.accountInfo(address)?.let { info ->
            if (info.owner == SolarchikConfig.MPL_CORE_PROGRAM) CoreIx.ownerOf(info.data)?.toBase58().orEmpty() else ""
        }
    },
) {

    fun canMint(sku: AgentSku, tier: String): MintError.Kind? {
        // Combo is paid only: never a free mint, whatever the UI picked.
        if (tier == AgentTier.FREE && sku.paidOnly) return MintError.Kind.PAID_ONLY
        // 1.1.0: mainnet mints wait for the mainnet collection (funded by Vadym); until then "coming soon", never a broken tx
        if (wallet.mainnet && !SolarchikConfig.MAINNET_MINT_READY) return MintError.Kind.MAINNET_SOON
        if (tier == AgentTier.PRO && wallet.mainnet && !SolarchikConfig.MAINNET_PAID_MINT) return MintError.Kind.PRO_MAINNET_OFF
        if (tier == AgentTier.FREE && wallet.connected && store.freeClaimed(wallet.address, wallet.clusterName)) return MintError.Kind.FREE_USED
        return null
    }

    suspend fun mint(sender: ActivityResultSender, sku: AgentSku, tier: String): Result<OwnedAgent> {
        canMint(sku, tier)?.let { return Result.failure(MintError(it)) }
        if (!wallet.connected) {
            wallet.connect(sender).onFailure { return Result.failure(it) }
            canMint(sku, tier)?.let { return Result.failure(MintError(it)) }
        }
        if (tier == AgentTier.FREE) return mintFree(sender, sku)
        return mintWith(sender, sku, tier, Keypair.generate())
    }

    /**
     * Free: the asset address is derived from the wallet's signature (see [FreeAsset]), so a second
     * Free mint from the same wallet fails on chain too: reinstall, second phone, any cluster.
     */
    internal suspend fun mintFree(sender: ActivityResultSender, sku: AgentSku): Result<OwnedAgent> {
        if (sku.paidOnly) return Result.failure(MintError(MintError.Kind.PAID_ONLY))
        val owner = wallet.address
        val sig = freeSeed(sender, owner).getOrElse { return Result.failure(it) }
        val asset = FreeAsset.keypair(owner, sig) ?: return Result.failure(MintError(MintError.Kind.WALLET_CHANGED))
        val assetId = asset.publicKey.toBase58()
        val existing = runCatching { coreOwner(assetId) }.getOrElse { return Result.failure(it) }
        if (existing != null) {
            if (existing == owner && store.agents().none { it.asset == assetId }) {
                // Minted before (other phone, reinstall): adopt it instead of minting again.
                store.upsert(
                    OwnedAgent(assetId, sku.skuId(AgentTier.FREE), AgentTier.FREE, sku.nameFor(AgentTier.FREE), owner, cluster(),
                        mintedAt = clock(), status = OwnedAgent.STATUS_VERIFIED),
                )
            }
            return Result.failure(MintError(MintError.Kind.FREE_USED))
        }
        return mintWith(sender, sku, AgentTier.FREE, asset, freeOwner = owner)
    }

    /**
     * The record is saved as pending before the wallet sees the tx: if the app is killed while the
     * wallet sends, the asset is still known, checked on chain later, and still counts as this
     * wallet's free mint. It is dropped only when nothing can have been sent.
     */
    internal suspend fun mintWith(
        sender: ActivityResultSender,
        sku: AgentSku,
        tier: String,
        asset: Keypair,
        freeOwner: String = "",
    ): Result<OwnedAgent> {
        if (tier == AgentTier.FREE && sku.paidOnly) return Result.failure(MintError(MintError.Kind.PAID_ONLY))
        val assetId = asset.publicKey.toBase58()
        var pending: OwnedAgent? = null
        val sent = send(sender) { payer, blockhash ->
            // The derived Free address belongs to the wallet that signed the seed; a switched account would mint someone else's.
            if (freeOwner.isNotBlank() && payer.toBase58() != freeOwner) throw MintError(MintError.Kind.WALLET_CHANGED)
            if (tier == AgentTier.FREE && store.freeClaimed(payer.toBase58(), cluster()) && store.agents().none { it.asset == assetId }) {
                throw MintError(MintError.Kind.FREE_USED)
            }
            val coll = if (wallet.mainnet) MintCollection.configured() else null
            val tx = buildMintTx(payer, blockhash, sku, tier, asset, coll)
            if (coll != null) tx.addSignature(coll.authority, cosign(tx))
            val rec = OwnedAgent(assetId, sku.skuId(tier), tier, sku.nameFor(tier), payer.toBase58(), cluster(), mintedAt = clock())
            store.upsert(rec)
            pending = rec
            tx
        }
        return sent.fold(
            onSuccess = { tx ->
                val rec = (pending ?: OwnedAgent(assetId, sku.skuId(tier), tier, sku.nameFor(tier), tx.address, tx.cluster, mintedAt = clock()))
                    .copy(owner = tx.address.ifBlank { pending?.owner.orEmpty() }, cluster = tx.cluster, sig = tx.signature)
                store.upsert(rec)
                Result.success(rec)
            },
            onFailure = { e ->
                if (pending != null && nothingSent(e)) store.remove(assetId)
                Result.failure(e)
            },
        )
    }

    /** Declines, a missing wallet and our own build errors never reach the chain. Timeouts might have. */
    private fun nothingSent(e: Throwable): Boolean = when (e) {
        is MintError -> true
        is WalletError -> e.kind == WalletError.Kind.DECLINED || e.kind == WalletError.Kind.NO_WALLET
        else -> false
    }

    /** Polls the chain until the asset account exists and belongs to the owner. */
    suspend fun verify(agent: OwnedAgent, attempts: Int = 10): OwnedAgent {
        var current = agent
        repeat(attempts) { i ->
            current = checkOnce(current)
            if (current.status != OwnedAgent.STATUS_PENDING) return current
            delay(if (i < 3) 1500L else 3000L)
        }
        return current
    }

    suspend fun checkOnce(agent: OwnedAgent): OwnedAgent {
        val rpc = wallet.rpc
        val info = runCatching { rpc.accountInfo(agent.asset) }.getOrElse { return agent }
        val now = System.currentTimeMillis()
        val next = if (info != null && info.owner == SolarchikConfig.MPL_CORE_PROGRAM &&
            CoreIx.ownerOf(info.data)?.toBase58() == agent.owner
        ) {
            agent.copy(status = OwnedAgent.STATUS_VERIFIED, checkedAt = now)
        } else {
            val failed = agent.sig.isNotBlank() && runCatching { rpc.signatureStatus(agent.sig) }.getOrNull() == "failed"
            val stale = now - agent.mintedAt > 3 * 60_000L
            if (failed || (info != null && agent.status == OwnedAgent.STATUS_VERIFIED) || (info == null && stale)) {
                agent.copy(status = OwnedAgent.STATUS_MISSING, checkedAt = now)
            } else agent.copy(checkedAt = now)
        }
        if (next != agent) store.upsert(next)
        return next
    }

    /** Re-checks local records and, when the RPC has DAS, picks up agents minted elsewhere (web). */
    suspend fun refresh(): List<OwnedAgent> {
        val owner = wallet.address
        if (owner.isBlank()) return emptyList()
        val cluster = wallet.clusterName
        runCatching { wallet.rpc.dasAssetsByOwner(owner) }.getOrNull()?.forEach { (id, name) ->
            val hit = Catalog.fromName(name) ?: return@forEach
            if (store.agents().none { it.asset == id }) {
                store.upsert(OwnedAgent(id, hit.first.skuId(hit.second), hit.second, name, owner, cluster, status = OwnedAgent.STATUS_VERIFIED))
            }
        }
        return store.agentsFor(owner, cluster).map { if (it.status == OwnedAgent.STATUS_MISSING) it else checkOnce(it) }
    }

    companion object {
        /**
         * PRO: SOL transfer to the treasury, then Core CreateV1 in the same tx.
         * FREE: CreateV1 only. Both carry the 5% Royalties plugin.
         * The asset keypair partially signs; the wallet signs the fee payer slot.
         */
        fun buildMintTx(payer: PublicKey, blockhash: ByteArray, sku: AgentSku, tier: String, asset: Keypair, collection: MintCollection? = null): LegacyTx {
            val ixs = ArrayList<Ix>()
            val (offerTier, price) = Catalog.offerFor(sku.skuId(tier))
            if (offerTier == AgentTier.PRO && price > 0) {
                ixs += SystemIx.transfer(payer, PublicKey(SolarchikConfig.TREASURY), SolarchikConfig.lamports(price))
            }
            val feeBps = Math.round(Catalog.feeRateFor(offerTier) * 10_000).toInt()
            ixs += CoreIx.createV1(
                asset = asset.publicKey,
                payer = payer,
                name = sku.nameFor(offerTier),
                uri = SolarchikConfig.AGENT_URI,
                plugins = CoreIx.agentPlugins(sku.skuId(offerTier), offerTier, sku.agentClass.id, sku.agentClass.role, feeBps, sku.lanes),
                collection = collection?.address,
                authority = collection?.authority,
            )
            val tx = LegacyTx.compile(payer, blockhash, ixs).partialSign(asset)
            if (tx.serialize().size > LegacyTx.MAX_SIZE) throw MintError(MintError.Kind.TOO_BIG)
            return tx
        }
    }
}

/** 1.1.0: the mainnet Solarchik Agents collection (from BuildConfig once created). */
data class MintCollection(val address: PublicKey, val authority: PublicKey) {
    companion object {
        fun configured(): MintCollection? {
            if (!SolarchikConfig.MAINNET_MINT_READY) return null
            val c = SolarchikConfig.MAINNET_COLLECTION
            val a = SolarchikConfig.MAINNET_COLLECTION_AUTHORITY
            if (c.isBlank() || a.isBlank()) return null
            return MintCollection(PublicKey(c), PublicKey(a))
        }
    }
}

/** POST the unsigned mint to the worker; it answers the collection authority's signature after its checks. */
object MintCosign {
    suspend fun request(tx: LegacyTx): ByteArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = org.json.JSONObject().put("tx", java.util.Base64.getEncoder().encodeToString(tx.serialize())).toString()
        val req = okhttp3.Request.Builder().url(SolarchikConfig.MINT_COSIGN_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        net.solardepin.solarchik.solana.Rpc.client.newCall(req).execute().use { res ->
            val o = org.json.JSONObject(res.body?.string().orEmpty().ifBlank { "{}" })
            if (!res.isSuccessful) throw MintError(if (o.optString("error") == "COMBO_PAID_ONLY") MintError.Kind.PAID_ONLY else MintError.Kind.MAINNET_SOON, o.optString("error"))
            val sig = Base58.decode(o.getString("signature"))
            // never trust blindly: the signature must verify against the authority and this exact message
            val auth = PublicKey(o.getString("authority"))
            check(auth.verify(sig, tx.message)) { "bad co-signature" }
            sig
        }
    }
}
