package me.rerere.rikkihub.data.creator.vault

import android.content.Context
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault 回收 payload 的加密 key（noBackup 文件，不进备份/模型/审计）。
 *
 * 模式：32B SecureRandom AES key
 * 懒创建在 `noBackupFilesDir/creator_vault/recycle.key`；key 丢 → 旧回收
 * payload 不可解（接受：回收材料缺失提前标「不可完整撤销」）。
 *
 * JVM 单测可注入 `keyDir`；key 文件本身也属 [CreatorBackupScope] 排除面。
 */
class CreatorVaultKeyStore(
    private val keyFile: File,
) {
    constructor(context: Context) : this(
        File(CreatorDatabaseFactory.vaultDir(context), "recycle.key")
    )

    companion object {
        private const val KEY_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }

    private val secretKey: SecretKey by lazy {
        if (keyFile.exists() && keyFile.length() == KEY_BYTES.toLong()) {
            SecretKeySpec(keyFile.readBytes(), "AES")
        } else {
            keyFile.parentFile?.mkdirs()
            val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val tmp = File(keyFile.parentFile, "${keyFile.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(keyFile)) {
                if (!keyFile.exists()) throw IllegalStateException("recycle key install failed")
                tmp.delete()
            }
            SecretKeySpec(keyFile.readBytes(), "AES")
        }
    }

    /** AES-GCM 加密：输出 = iv || ciphertext||tag。 */
    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= GCM_IV_BYTES) return null
        return runCatching {
            val iv = sealed.copyOfRange(0, GCM_IV_BYTES)
            val body = sealed.copyOfRange(GCM_IV_BYTES, sealed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(body)
        }.getOrNull()
    }
}
