package me.rerere.rikkihub.data.devin

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.data.oauth.AesGcmCredentialCipher
import me.rerere.rikkihub.data.oauth.readCredentialFile
import me.rerere.rikkihub.data.oauth.writeFileAtomically
import java.io.File

/** 账户状态的持久化; 生产实现是 [DevinCredentialStore] (Keystore 加密文件) */
internal interface DevinAccountStore {
    fun read(): DevinAccountState
    fun write(state: DevinAccountState)
}

internal class DevinCredentialStore(
    context: Context,
    private val json: Json,
) : DevinAccountStore {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val cipher = AesGcmCredentialCipher(KEY_ALIAS)

    override fun read(): DevinAccountState {
        val plaintext = readCredentialFile(file, cipher::decrypt) ?: return DevinAccountState()
        return runCatching {
            json.decodeFromString<DevinAccountState>(plaintext.decodeToString())
        }.getOrElse {
            DevinAccountState()
        }
    }

    override fun write(state: DevinAccountState) {
        val encrypted = cipher.encrypt(json.encodeToString(state).encodeToByteArray())
        writeFileAtomically(file, encrypted)
    }

    private companion object {
        const val FILE_NAME = "devin_accounts.enc"
        const val KEY_ALIAS = "rikkihub_devin_accounts"
    }
}
