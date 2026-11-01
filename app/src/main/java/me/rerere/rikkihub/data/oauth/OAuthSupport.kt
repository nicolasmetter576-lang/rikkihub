package me.rerere.rikkihub.data.oauth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Shared helpers for the OAuth-backed providers (Codex / Devin):
 * AES-GCM-on-AndroidKeyStore credential file crypto, atomic file writes, the
 * loopback redirect page, PKCE session bookkeeping and the "wait until the
 * network is unblocked" suspension used before the token exchange.
 */
private const val TAG = "OAuthSupport"

internal class AesGcmCredentialCipher(private val keyAlias: String) {
    private val key: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size > IV_SIZE) { "Credential file is truncated" }
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}

internal fun writeFileAtomically(file: File, bytes: ByteArray) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(temporary).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    val parent = requireNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "Cannot create ${parent.path}" }
    Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}

/** Keeps the unreadable file as [file].corrupt for inspection and returns null. */
internal fun readCredentialFile(file: File, decrypt: (ByteArray) -> ByteArray): ByteArray? {
    if (!file.exists()) return null
    return runCatching { decrypt(file.readBytes()) }.getOrElse { error ->
        Log.e(TAG, "Credential file ${file.name} is unreadable; keeping it as .corrupt", error)
        runCatching {
            Files.move(
                file.toPath(),
                File(file.parentFile, "${file.name}.corrupt").toPath(),
                REPLACE_EXISTING,
            )
        }
        null
    }
}

internal data class OAuthPkceSession(
    val verifier: String,
    val redirectUri: String,
    val createdAt: Long = System.currentTimeMillis(),
)

internal const val OAUTH_SESSION_TTL_MS = 10 * 60 * 1000L

internal fun ConcurrentMap<String, OAuthPkceSession>.removeExpiredSessions(
    now: Long = System.currentTimeMillis(),
) {
    values.removeIf { now - it.createdAt > OAUTH_SESSION_TTL_MS }
}

/** What a loopback redirect means for the sign-in in progress. */
internal sealed interface OAuthCallbackDecision {
    /** Not one of ours (forged, already used, or superseded): leave the sign-in state alone. */
    data object Unknown : OAuthCallbackDecision

    data class Failed(val message: String) : OAuthCallbackDecision

    data class Exchange(val code: String, val session: OAuthPkceSession) : OAuthCallbackDecision
}

/**
 * Claims the session named by [state]. Only a redirect for a pending session may change the
 * sign-in status; any process on the device can hit the loopback port, and a reload of the
 * success page arrives after the session was consumed.
 */
internal fun ConcurrentMap<String, OAuthPkceSession>.claimCallback(
    state: String?,
    code: String?,
    error: String?,
    now: Long = System.currentTimeMillis(),
): OAuthCallbackDecision {
    val session = state?.let(::remove)
    removeExpiredSessions(now)
    return when {
        session == null -> OAuthCallbackDecision.Unknown
        now - session.createdAt > OAUTH_SESSION_TTL_MS ->
            OAuthCallbackDecision.Failed("OAuth sign-in session expired, please try again")
        !error.isNullOrBlank() -> OAuthCallbackDecision.Failed(error)
        code.isNullOrBlank() -> OAuthCallbackDecision.Failed("Missing authorization code")
        else -> OAuthCallbackDecision.Exchange(code, session)
    }
}

/** Answer for [OAuthCallbackDecision.Unknown]: no deep link back into the app. */
internal fun oauthUnknownCallbackPage(title: String): String = """
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title</title>
      </head>
      <body>
        <p>This sign-in link is invalid, expired or already used. You can close this page.</p>
      </body>
    </html>
""".trimIndent()

internal fun oauthCallbackPage(success: Boolean, deepLinkBase: String, title: String): String {
    val status = if (success) "success" else "error"
    val deepLink = "$deepLinkBase?status=${URLEncoder.encode(status, Charsets.UTF_8.name())}"
    return """
        <!doctype html>
        <html>
          <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="refresh" content="0; url=$deepLink">
            <title>$title</title>
          </head>
          <body>
            <p>${if (success) "Returning to Rikkihub..." else "Sign-in failed."}</p>
            <p><a href="$deepLink">Return to Rikkihub</a></p>
            <script>
              window.location.replace("$deepLink");
              setTimeout(function () { window.location.href = "$deepLink"; }, 500);
            </script>
          </body>
        </html>
    """.trimIndent()
}

internal fun randomUrlSafe(size: Int): String {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

internal suspend fun Context.awaitNetworkUnblocked() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
    suspendCancellableCoroutine { continuation ->
        val callback = AtomicReference<ConnectivityManager.NetworkCallback?>()
        continuation.invokeOnCancellation {
            runCatching {
                callback.get()?.let { connectivityManager.unregisterNetworkCallback(it) }
            }
        }
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!blocked && continuation.isActive) {
                    runCatching { connectivityManager.unregisterNetworkCallback(this) }
                    continuation.resume(Unit)
                }
            }
        }
        callback.set(networkCallback)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }
}
