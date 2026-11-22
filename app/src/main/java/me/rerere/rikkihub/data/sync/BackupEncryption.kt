package me.rerere.rikkihub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.oauth.AesGcmCredentialCipher
import me.rerere.rikkihub.data.oauth.readCredentialFile
import me.rerere.rikkihub.data.oauth.writeFileAtomically
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

class BackupDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnsupportedBackupEncryptionVersionException(val version: Int) :
    Exception("Unsupported backup encryption version: $version")

/**
 * 流式分块认证加密容器（.rkbackup）：
 *
 * Header (41B, big-endian):
 * - magic (8B): "RKHVENC\0"
 * - version (1B): 1 = PBKDF2WithHmacSHA256 (600,000 次, 32B key); 预留 2 给未来 Argon2id
 * - salt (16B): 随机盐
 * - noncePrefix (8B): 随机 nonce 前缀
 * - plaintextLength (8B uint64): 明文总长 (上限 16 GiB)
 *
 * Payload: 1 MiB 明文分块，每块独立 AES-256-GCM (128-bit tag)，
 * nonce = noncePrefix(8B) || uint32_be(chunkIndex)，
 * AAD = header(41B) || uint32_be(chunkIndex)。
 */
object BackupEncryption {
    const val EXTENSION = "rkbackup"
    val MAGIC: ByteArray = byteArrayOf(
        'R'.code.toByte(),
        'K'.code.toByte(),
        'H'.code.toByte(),
        'V'.code.toByte(),
        'E'.code.toByte(),
        'N'.code.toByte(),
        'C'.code.toByte(),
        0,
    )
    const val HEADER_LENGTH = 41
    const val VERSION_PBKDF2_SHA256: Byte = 1
    const val PBKDF2_ITERATIONS = 600_000
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 8
    const val TAG_BITS = 128
    const val TAG_BYTES = 16
    const val CHUNK_SIZE = 1024 * 1024
    const val MAX_PLAINTEXT_BYTES = 16L * 1024L * 1024L * 1024L

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        return runCatching {
            val prefix = ByteArray(MAGIC.size)
            FileInputStream(file).use { input ->
                readFully(input, prefix, MAGIC.size) == MAGIC.size && prefix.contentEquals(MAGIC)
            }
        }.getOrDefault(false)
    }

    suspend fun encryptFile(source: File, destination: File, password: String) = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "Backup password must not be empty" }
        require(source.isFile) { "Source backup file does not exist: ${source.path}" }
        require(source.canonicalPath != destination.canonicalPath) {
            "Source and destination must be distinct files"
        }

        val plaintextLength = source.length()
        require(plaintextLength in 0L..MAX_PLAINTEXT_BYTES) {
            "Backup file exceeds maximum supported encryption size"
        }

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)

        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            put(VERSION_PBKDF2_SHA256)
            put(salt)
            put(noncePrefix)
            putLong(plaintextLength)
        }.array()

        val passwordChars = password.toCharArray()
        var keyBytes: ByteArray? = null
        var completed = false
        try {
            currentCoroutineContext().ensureActive()
            keyBytes = deriveKey(passwordChars, salt, VERSION_PBKDF2_SHA256.toInt())
            val secretKey = SecretKeySpec(keyBytes, "AES")

            currentCoroutineContext().ensureActive()
            destination.parentFile?.let { parent ->
                check(parent.isDirectory || parent.mkdirs()) { "Cannot create directory: ${parent.path}" }
            }

            val chunkCount = max(1L, (plaintextLength + CHUNK_SIZE - 1L) / CHUNK_SIZE)
            val readBuffer = ByteArray(CHUNK_SIZE)
            val nonce = ByteArray(NONCE_PREFIX_BYTES + 4)
            val aad = ByteArray(HEADER_LENGTH + 4)
            System.arraycopy(noncePrefix, 0, nonce, 0, NONCE_PREFIX_BYTES)
            System.arraycopy(header, 0, aad, 0, HEADER_LENGTH)

            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    output.write(header)
                    var remaining = plaintextLength
                    for (chunkIndex in 0 until chunkCount) {
                        currentCoroutineContext().ensureActive()
                        val plainCount = min(CHUNK_SIZE.toLong(), remaining).toInt()
                        if (plainCount > 0) {
                            val read = readFully(input, readBuffer, plainCount)
                            check(read == plainCount) { "Unexpected EOF while encrypting backup" }
                        }
                        putIntBigEndian(nonce, NONCE_PREFIX_BYTES, chunkIndex.toInt())
                        putIntBigEndian(aad, HEADER_LENGTH, chunkIndex.toInt())

                        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
                        cipher.updateAAD(aad)
                        val encryptedChunk = cipher.doFinal(readBuffer, 0, plainCount)
                        output.write(encryptedChunk)
                        remaining -= plainCount
                    }
                    check(input.read() == -1) { "Source file grew during encryption" }
                    output.fd.sync()
                }
            }
            completed = true
        } finally {
            passwordChars.fill('\u0000')
            keyBytes?.fill(0)
            if (!completed) {
                destination.delete()
            }
        }
    }

    suspend fun decryptFile(source: File, destination: File, password: String) = withContext(Dispatchers.IO) {
        if (password.isEmpty()) throw BackupDecryptionException("Backup password is empty")
        require(source.isFile) { "Encrypted backup file does not exist: ${source.path}" }
        require(source.canonicalPath != destination.canonicalPath) {
            "Source and destination must be distinct files"
        }

        val totalLength = source.length()
        require(totalLength >= HEADER_LENGTH) { "Invalid encrypted backup header" }

        val passwordChars = password.toCharArray()
        var keyBytes: ByteArray? = null
        var completed = false
        try {
            FileInputStream(source).use { input ->
                val header = ByteArray(HEADER_LENGTH)
                require(readFully(input, header, HEADER_LENGTH) == HEADER_LENGTH) {
                    "Truncated encrypted backup header"
                }
                val magicSlice = header.copyOfRange(0, MAGIC.size)
                require(magicSlice.contentEquals(MAGIC)) { "Not an encrypted rikkihub backup" }

                val version = header[8].toInt() and 0xFF
                if (version != VERSION_PBKDF2_SHA256.toInt()) {
                    throw UnsupportedBackupEncryptionVersionException(version)
                }

                val salt = header.copyOfRange(9, 25)
                val noncePrefix = header.copyOfRange(25, 33)
                val plaintextLength = ByteBuffer.wrap(header, 33, 8).order(ByteOrder.BIG_ENDIAN).long
                require(plaintextLength in 0L..MAX_PLAINTEXT_BYTES) {
                    "Invalid encrypted backup length: $plaintextLength"
                }

                val chunkCount = max(1L, (plaintextLength + CHUNK_SIZE - 1L) / CHUNK_SIZE)
                val expectedLength = HEADER_LENGTH.toLong() + plaintextLength + chunkCount * TAG_BYTES.toLong()
                require(totalLength == expectedLength) {
                    "Encrypted backup size mismatch (expected $expectedLength, actual $totalLength)"
                }

                currentCoroutineContext().ensureActive()
                keyBytes = deriveKey(passwordChars, salt, version)
                val secretKey = SecretKeySpec(keyBytes, "AES")

                destination.parentFile?.let { parent ->
                    check(parent.isDirectory || parent.mkdirs()) { "Cannot create directory: ${parent.path}" }
                }

                val encryptedBuffer = ByteArray(CHUNK_SIZE + TAG_BYTES)
                val nonce = ByteArray(NONCE_PREFIX_BYTES + 4)
                val aad = ByteArray(HEADER_LENGTH + 4)
                System.arraycopy(noncePrefix, 0, nonce, 0, NONCE_PREFIX_BYTES)
                System.arraycopy(header, 0, aad, 0, HEADER_LENGTH)

                FileOutputStream(destination).use { output ->
                    var remaining = plaintextLength
                    for (chunkIndex in 0 until chunkCount) {
                        currentCoroutineContext().ensureActive()
                        val plainCount = min(CHUNK_SIZE.toLong(), remaining).toInt()
                        val cipherCount = plainCount + TAG_BYTES
                        val read = readFully(input, encryptedBuffer, cipherCount)
                        require(read == cipherCount) { "Unexpected EOF while decrypting backup" }

                        putIntBigEndian(nonce, NONCE_PREFIX_BYTES, chunkIndex.toInt())
                        putIntBigEndian(aad, HEADER_LENGTH, chunkIndex.toInt())

                        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
                        cipher.updateAAD(aad)
                        val plainChunk = try {
                            cipher.doFinal(encryptedBuffer, 0, cipherCount)
                        } catch (e: AEADBadTagException) {
                            throw BackupDecryptionException("Incorrect password or corrupted backup", e)
                        }
                        output.write(plainChunk)
                        remaining -= plainCount
                    }
                    require(input.read() == -1) { "Unexpected trailing bytes in encrypted backup" }
                    output.fd.sync()
                }
            }
            completed = true
        } finally {
            passwordChars.fill('\u0000')
            keyBytes?.fill(0)
            if (!completed) {
                destination.delete()
            }
        }
    }

    suspend fun createPasswordVerifier(password: String): String = withContext(Dispatchers.Default) {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val chars = password.toCharArray()
        var derived: ByteArray? = null
        try {
            derived = deriveKey(chars, salt, VERSION_PBKDF2_SHA256.toInt())
            val encoder = Base64.getEncoder()
            "v1:${encoder.encodeToString(salt)}:${encoder.encodeToString(derived)}"
        } finally {
            chars.fill('\u0000')
            derived?.fill(0)
        }
    }

    suspend fun verifyPassword(password: String, verifier: String): Boolean = withContext(Dispatchers.Default) {
        if (password.isEmpty()) return@withContext false
        val parts = verifier.split(':')
        if (parts.size != 3 || parts[0] != "v1") return@withContext false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return@withContext false
        val expected = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return@withContext false
        val chars = password.toCharArray()
        var actual: ByteArray? = null
        try {
            actual = deriveKey(chars, salt, VERSION_PBKDF2_SHA256.toInt())
            MessageDigest.isEqual(actual, expected)
        } finally {
            chars.fill('\u0000')
            actual?.fill(0)
        }
    }

    private fun deriveKey(passwordChars: CharArray, salt: ByteArray, version: Int): ByteArray {
        require(version == VERSION_PBKDF2_SHA256.toInt()) { "Unsupported KDF version: $version" }
        val spec = PBEKeySpec(passwordChars, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun readFully(input: FileInputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val count = input.read(buffer, total, length - total)
            if (count <= 0) break
            total += count
        }
        return total
    }

    private fun putIntBigEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}

/**
 * 将用户记住的备份密码用 AndroidKeyStore AES-256-GCM 密文存在 noBackupFilesDir 中。
 * 绝不进 settings.json、备份包或系统云备份。
 */
class BackupPasswordStore(context: Context) {
    private val file = File(BackupDeviceStateStore.stateDir(context), FILE_NAME)
    private val cipher = AesGcmCredentialCipher(KEY_ALIAS)

    fun save(password: String) {
        require(password.isNotEmpty()) { "Backup password must not be empty" }
        val bytes = password.toByteArray(Charsets.UTF_8)
        try {
            writeFileAtomically(file, cipher.encrypt(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    fun load(): String? {
        val plaintext = readCredentialFile(file, cipher::decrypt) ?: return null
        return try {
            String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    fun hasStoredPassword(): Boolean = file.isFile && file.length() > 0L

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.corrupt").delete()
    }

    private companion object {
        const val KEY_ALIAS = "rikkihub_backup_encryption_key_v1"
        const val FILE_NAME = "backup_pwd.enc"
    }
}
