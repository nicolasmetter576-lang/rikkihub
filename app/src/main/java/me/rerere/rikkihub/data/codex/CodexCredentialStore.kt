package me.rerere.rikkihub.data.codex

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.data.oauth.AesGcmCredentialCipher
import me.rerere.rikkihub.data.oauth.readCredentialFile
import me.rerere.rikkihub.data.oauth.writeFileAtomically
import java.io.File

internal class CodexCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val cipher = AesGcmCredentialCipher(KEY_ALIAS)

    fun read(): CodexAccountState {
        val plaintext = readCredentialFile(file, cipher::decrypt) ?: return CodexAccountState()
        return runCatching {
            json.decodeFromString<CodexAccountState>(plaintext.decodeToString())
        }.getOrElse {
            CodexAccountState()
        }
    }

    fun write(state: CodexAccountState) {
        val encrypted = cipher.encrypt(json.encodeToString(state).encodeToByteArray())
        writeFileAtomically(file, encrypted)
    }

    private companion object {
        const val FILE_NAME = "codex_accounts.enc"
        const val KEY_ALIAS = "rikkihub_codex_accounts"
    }
}

@Serializable
internal data class CodexAccountState(
    val accounts: List<CodexAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)
