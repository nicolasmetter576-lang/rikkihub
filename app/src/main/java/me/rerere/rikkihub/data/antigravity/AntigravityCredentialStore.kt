package me.rerere.rikkihub.data.antigravity

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.data.oauth.AesGcmCredentialCipher
import me.rerere.rikkihub.data.oauth.readCredentialFile
import me.rerere.rikkihub.data.oauth.writeFileAtomically
import java.io.File

/** 凭据在 noBackupFilesDir 的加密文件里，不进备份/同步（同 Codex/Devin）。 */
internal class AntigravityCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val cipher = AesGcmCredentialCipher(KEY_ALIAS)

    fun read(): AntigravityAccountState {
        val plaintext = readCredentialFile(file, cipher::decrypt) ?: return AntigravityAccountState()
        return runCatching {
            json.decodeFromString<AntigravityAccountState>(plaintext.decodeToString())
        }.getOrElse {
            AntigravityAccountState()
        }
    }

    fun write(state: AntigravityAccountState) {
        val encrypted = cipher.encrypt(json.encodeToString(state).encodeToByteArray())
        writeFileAtomically(file, encrypted)
    }

    private companion object {
        const val FILE_NAME = "antigravity_accounts.enc"
        const val KEY_ALIAS = "rikkihub_antigravity_accounts"
    }
}
