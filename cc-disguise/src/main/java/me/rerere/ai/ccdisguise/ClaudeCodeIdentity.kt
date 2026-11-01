package me.rerere.ai.ccdisguise

import android.content.Context
import java.io.File
import kotlin.uuid.Uuid

class ClaudeCodeIdentity(private val context: Context? = null) {
    @Volatile
    private var cached: String? = null

    fun userId(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val id = loadOrCreate(identityFile())
            cached = id
            return id
        }
    }

    private fun identityFile(): File? {
        val dir = context?.filesDir ?: return null
        return File(File(dir, DIR_NAME), FILE_NAME)
    }

    /**
     * Sync the compatibility file with the given [userId].
     * Called after config init or ID regeneration so that the legacy file stays current.
     */
    fun syncFile(userId: String) {
        val file = identityFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(userId)
        cached = userId
    }

    companion object {
        const val DIR_NAME = "cc-disguise"
        const val FILE_NAME = "user-id"

        fun generate(): String {
            return "user_${Uuid.random().toHexString()}${Uuid.random().toHexString()}_account__session_${Uuid.random()}"
        }

        fun isValid(value: String): Boolean {
            val rest = value.removePrefix("user_")
            if (rest === value || rest.length < 64) return false
            val hex = rest.substring(0, 64)
            return hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } &&
                rest.substring(64).startsWith("_account__session_")
        }

        fun loadOrCreate(file: File?): String {
            if (file != null) {
                if (file.isFile) {
                    val existing = file.readText().trim()
                    if (isValid(existing)) return existing
                }
                val created = generate()
                file.parentFile?.mkdirs()
                file.writeText(created)
                return created
            }
            return generate()
        }

        /**
         * Reads the legacy file-based user ID without creating a new one.
         * Returns null if the file does not exist or contains an invalid value.
         */
        fun readLegacyFile(file: File?): String? {
            if (file == null || !file.isFile) return null
            val existing = file.readText().trim()
            return if (isValid(existing)) existing else null
        }
    }
}
