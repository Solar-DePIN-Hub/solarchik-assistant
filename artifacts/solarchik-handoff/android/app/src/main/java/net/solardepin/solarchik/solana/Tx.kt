package net.solardepin.solarchik.solana

import org.sol4k.Keypair
import org.sol4k.PublicKey
import java.io.ByteArrayOutputStream

data class Meta(val key: PublicKey, val signer: Boolean, val writable: Boolean)

data class Ix(val program: PublicKey, val accounts: List<Meta>, val data: ByteArray)

/**
 * Legacy transaction built by hand so the signature slots are exact:
 * slot 0 is the MWA fee payer (left empty for the wallet), extra signers
 * (e.g. a fresh Core asset keypair) sign locally before the wallet sees it.
 */
class LegacyTx private constructor(val keys: List<Meta>, val message: ByteArray) {
    val signerCount: Int get() = keys.count { it.signer }
    private val sigs = Array(signerCount) { ByteArray(64) }

    fun signerIndex(key: PublicKey): Int = keys.indexOfFirst { it.signer && it.key == key }

    /** Adds a local signature (partial sign). The fee payer slot stays empty for the wallet. */
    fun partialSign(vararg keypairs: Keypair): LegacyTx {
        for (kp in keypairs) {
            val i = signerIndex(kp.publicKey)
            require(i >= 0) { "Not a signer: ${kp.publicKey.toBase58()}" }
            sigs[i] = kp.sign(message)
        }
        return this
    }

    fun signature(i: Int): ByteArray = sigs[i].copyOf()

    /** 1.1.0: a signature made elsewhere (the server's collection-authority co-sign), verified by the caller. */
    fun addSignature(key: PublicKey, signature: ByteArray): LegacyTx {
        val i = signerIndex(key)
        require(i >= 0) { "Not a signer: ${key.toBase58()}" }
        require(signature.size == 64) { "bad signature" }
        sigs[i] = signature.copyOf()
        return this
    }

    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        shortVec(out, sigs.size)
        sigs.forEach { out.write(it) }
        out.write(message)
        return out.toByteArray()
    }

    companion object {
        /** Solana packet limit for a serialized transaction. */
        const val MAX_SIZE = 1232

        fun compile(feePayer: PublicKey, recentBlockhash: ByteArray, ixs: List<Ix>): LegacyTx {
            require(recentBlockhash.size == 32) { "blockhash must be 32 bytes" }
            // Merge metas in first-appearance order; payer first; programs are readonly non-signers.
            val merged = LinkedHashMap<PublicKey, Meta>()
            merged[feePayer] = Meta(feePayer, signer = true, writable = true)
            fun add(m: Meta) {
                val prev = merged[m.key]
                merged[m.key] = if (prev == null) m else Meta(m.key, prev.signer || m.signer, prev.writable || m.writable)
            }
            for (ix in ixs) {
                ix.accounts.forEach(::add)
                add(Meta(ix.program, signer = false, writable = false))
            }
            val all = merged.values.toList()
            val payer = all.first()
            val rest = all.drop(1)
            val ordered = listOf(payer) +
                rest.filter { it.signer && it.writable } +
                rest.filter { it.signer && !it.writable } +
                rest.filter { !it.signer && it.writable } +
                rest.filter { !it.signer && !it.writable }
            val index = ordered.withIndex().associate { it.value.key to it.index }

            val msg = ByteArrayOutputStream()
            msg.write(ordered.count { it.signer })
            msg.write(ordered.count { it.signer && !it.writable })
            msg.write(ordered.count { !it.signer && !it.writable })
            shortVec(msg, ordered.size)
            ordered.forEach { msg.write(it.key.bytes()) }
            msg.write(recentBlockhash)
            shortVec(msg, ixs.size)
            for (ix in ixs) {
                msg.write(index.getValue(ix.program))
                shortVec(msg, ix.accounts.size)
                ix.accounts.forEach { msg.write(index.getValue(it.key)) }
                shortVec(msg, ix.data.size)
                msg.write(ix.data)
            }
            return LegacyTx(ordered, msg.toByteArray())
        }

        fun shortVec(out: ByteArrayOutputStream, n: Int) {
            var v = n
            while (true) {
                val elem = v and 0x7f
                v = v ushr 7
                if (v == 0) {
                    out.write(elem)
                    return
                }
                out.write(elem or 0x80)
            }
        }
    }
}

/** Little-endian Borsh writer: just what Metaplex Core and System need. */
class Borsh {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Long) = apply { for (i in 0 until 4) u8((v ushr (8 * i)).toInt()) }
    fun u64(v: Long) = apply { for (i in 0 until 8) u8((v ushr (8 * i)).toInt()) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun pubkey(k: PublicKey) = bytes(k.bytes())
    fun string(s: String) = apply {
        val b = s.encodeToByteArray()
        u32(b.size.toLong())
        out.write(b)
    }
    fun <T> vec(items: List<T>, each: Borsh.(T) -> Unit) = apply {
        u32(items.size.toLong())
        items.forEach { each(it) }
    }
    fun toByteArray(): ByteArray = out.toByteArray()
}

object SystemIx {
    val PROGRAM = PublicKey("11111111111111111111111111111111")

    /** SystemInstruction::Transfer = 2, then u64 lamports. */
    fun transfer(from: PublicKey, to: PublicKey, lamports: Long): Ix = Ix(
        PROGRAM,
        listOf(Meta(from, signer = true, writable = true), Meta(to, signer = false, writable = true)),
        Borsh().u32(2).u64(lamports).toByteArray(),
    )
}

object MemoIx {
    val PROGRAM = PublicKey("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")

    fun memo(signer: PublicKey, text: String): Ix =
        Ix(PROGRAM, listOf(Meta(signer, signer = true, writable = false)), text.encodeToByteArray())
}
