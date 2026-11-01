package me.rerere.rikkihub.data.management.recovery

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared authenticated envelope; tests inject a key, production uses non-exportable Keystore AES. */
class AesGcmPayloadCipher(private val keyProvider: () -> SecretKey) : PayloadCipher {
    private val aad = "rikkihub.management.recycle.v1".toByteArray(Charsets.UTF_8)
    override val available: Boolean get() = runCatching { keyProvider(); true }.getOrDefault(false)

    override suspend fun encrypt(plain: ByteArray): ByteArray {
        require(plain.size <= ManagementRetentionPolicy.RECYCLE_MAX_BYTES)
        // The provider generates the IV: AndroidKeyStore keys require randomized
        // encryption and reject caller-provided IVs.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv != null && iv.size == 12) { "Unexpected GCM IV length" }
        return byteArrayOf(1) + iv + body
    }

    override suspend fun decrypt(cipher: ByteArray): ByteArray {
        require(cipher.size in 29..(ManagementRetentionPolicy.RECYCLE_MAX_BYTES + 29).toInt() && cipher[0] == 1.toByte())
        val engine = Cipher.getInstance("AES/GCM/NoPadding")
        engine.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, cipher.copyOfRange(1, 13)))
        engine.updateAAD(aad)
        return engine.doFinal(cipher, 13, cipher.size - 13)
    }

    companion object {
        /** Scoped to the host application's UID; no key bytes are saved or handed to workers. */
        fun android(alias: String = "rikkihub.management.recycle.v1"): AesGcmPayloadCipher {
            val lock = Any()
            return AesGcmPayloadCipher {
                synchronized(lock) {
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
                        generateKey()
                    }
                }
            }
        }
    }
}
