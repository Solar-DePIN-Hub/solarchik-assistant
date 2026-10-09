package net.solardepin.solarchik.delegate

import android.content.Context
import android.util.Base64
import org.sol4k.Keypair
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 1.1.0 experimental delegated mode: the app's own agent key. Generated on the phone; the Ed25519 secret is
 * stored only sealed with AES-256-GCM under a non-exportable Android Keystore key (Keystore has no Solana
 * Ed25519 signing, so the secret is decrypted in memory for the moment of signing). It holds only the small SOL
 * top-up for fees; the user's tokens stay in the user's own account, which the agent may draw from up to the
 * approved allowance.
 */
object AgentKey {
    private const val PREF = "solarchik-agent-key"
    private const val ALIAS = "solarchik-agent-key-v1"

    interface Box {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    @Volatile var box: Box = KeystoreBox

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun exists(ctx: Context): Boolean = prefs(ctx).getString("sealed", null) != null && address(ctx).isNotBlank()

    fun address(ctx: Context): String = prefs(ctx).getString("address", "").orEmpty()

    fun create(ctx: Context): String {
        if (exists(ctx)) return address(ctx)
        val kp = Keypair.generate()
        prefs(ctx).edit()
            .putString("sealed", Base64.encodeToString(box.seal(kp.secret), Base64.NO_WRAP))
            .putString("address", kp.publicKey.toBase58())
            .putLong("createdAt", System.currentTimeMillis())
            .apply()
        return kp.publicKey.toBase58()
    }

    fun keypair(ctx: Context): Keypair? {
        val raw = prefs(ctx).getString("sealed", null) ?: return null
        return runCatching {
            Keypair.fromSecretKey(box.open(Base64.decode(raw, Base64.NO_WRAP))).takeIf { it.publicKey.toBase58() == address(ctx) }
        }.getOrNull()
    }

    /** Deletes the key. Callers withdraw its SOL and tokens and revoke first; the UI enforces that order. */
    fun delete(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching { (box as? KeystoreBox)?.deleteKey() }
    }

    object KeystoreBox : Box {
        private fun key(): SecretKey {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        override fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            return c.iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            return c.doFinal(sealed, 12, sealed.size - 12)
        }

        fun deleteKey() {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }
}
