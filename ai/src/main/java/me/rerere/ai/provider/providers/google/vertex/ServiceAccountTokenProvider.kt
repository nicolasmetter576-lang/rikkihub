package me.rerere.ai.provider.providers.google.vertex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.util.ServiceAccountAuthException
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 使用服务账号（email + private key PEM）换取 Google OAuth2 Access Token。
 * 构造时传入 OkHttpClient；调用时传 email、私钥 PEM 与 scopes。
 */
class ServiceAccountTokenProvider(
    http: OkHttpClient
) {
    private val http = http.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    // Token cache to avoid frequent token requests
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    @Serializable
    private data class CachedToken(
        val token: String,
        val expiresAt: Long // Unix timestamp in seconds
    )

    /**
     * Generate cache key based on service account email, private key fingerprint, and scopes
     */
    private fun generateCacheKey(serviceAccountEmail: String, privateKeyPem: String, scopes: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(privateKeyPem.toByteArray(Charsets.UTF_8))
        val keyFingerprint = digest.take(8).joinToString("") { "%02x".format(it) }
        return "$serviceAccountEmail:$keyFingerprint:${scopes.sorted().joinToString(",")}"
    }

    /**
     * Check if cached token is still valid (not expired with 5 minutes buffer)
     */
    private fun isCachedTokenValid(cachedToken: CachedToken): Boolean {
        val now = Instant.now().epochSecond
        val bufferSeconds = 300 // 5 minutes buffer before actual expiration
        return cachedToken.expiresAt > (now + bufferSeconds)
    }

    /**
     * @param serviceAccountEmail  形如 xxx@project-id.iam.gserviceaccount.com
     * @param privateKeyPem        服务账号 JSON 中的 private_key 字段（PKCS#8 PEM, 含 -----BEGIN PRIVATE KEY-----）
     * @param scopes               OAuth scopes，默认 cloud-platform；多个 scope 用 List 传入
     * @return                     access token 字符串
     */
    suspend fun fetchAccessToken(
        serviceAccountEmail: String,
        privateKeyPem: String,
        scopes: List<String> = listOf("https://www.googleapis.com/auth/cloud-platform")
    ): String {
        currentCoroutineContext().ensureActive()
        val cacheKey = generateCacheKey(serviceAccountEmail, privateKeyPem, scopes)

        // Check cache first
        tokenCache[cacheKey]?.let { cachedToken ->
            if (isCachedTokenValid(cachedToken)) {
                return cachedToken.token
            }
        }
        val cachedToken = withTimeoutOrNull(45_000) {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val now = Instant.now().epochSecond
                val exp = now + 3600 // 最长 1h

                val headerJson = """{"alg":"RS256","typ":"JWT"}"""
                val claimJson = """{
                  "iss":"$serviceAccountEmail",
                  "scope":"${scopes.joinToString(" ")}",
                  "aud":"https://oauth2.googleapis.com/token",
                  "iat":$now,
                  "exp":$exp
                }""".trimIndent()

                val headerB64 = base64UrlNoPad(headerJson.toByteArray(Charsets.UTF_8))
                val claimB64 = base64UrlNoPad(claimJson.toByteArray(Charsets.UTF_8))
                val signingInput = "$headerB64.$claimB64"

                val privateKey = parsePkcs8PrivateKey(privateKeyPem)
                val signature = signRs256(signingInput.toByteArray(Charsets.UTF_8), privateKey)
                val assertion = "$signingInput.${base64UrlNoPad(signature)}"
                currentCoroutineContext().ensureActive()

                val form = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    .add("assertion", assertion)
                    .build()

                val req = Request.Builder()
                    .url("https://oauth2.googleapis.com/token")
                    .post(form)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val call = http.newCall(req)
                // A leaf observes cancellation while this coroutine is blocked in execute/body reads.
                val cancellation = Job(currentCoroutineContext().job)
                try {
                    cancellation.invokeOnCompletion { cause ->
                        if (cause is CancellationException) call.cancel()
                    }
                    currentCoroutineContext().ensureActive()
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw ServiceAccountAuthException("Token endpoint HTTP ${resp.code}", statusCode = resp.code)
                        }
                        val body = resp.body.string()
                        val tokenResp = try {
                            json.decodeFromString(TokenResponse.serializer(), body)
                        } catch (_: SerializationException) {
                            throw ServiceAccountAuthException("Invalid service account token response")
                        }
                        val accessToken = tokenResp.accessToken
                            ?: throw ServiceAccountAuthException("No access_token in response")
                        val expiresIn = tokenResp.expiresIn ?: 3600
                        CachedToken(accessToken, now + expiresIn)
                    }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } finally {
                    cancellation.complete()
                }
            }
        } ?: throw SocketTimeoutException("Service account token exchange timed out")
        currentCoroutineContext().ensureActive()
        tokenCache[cacheKey] = cachedToken
        return cachedToken.token
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("token_type")
        val tokenType: String? = null,
        @SerialName("expires_in")
        val expiresIn: Long? = null
    )

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun parsePkcs8PrivateKey(pem: String): PrivateKey = runCatching {
        val normalized = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(normalized)
        val keySpec = PKCS8EncodedKeySpec(der)
        KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }.getOrElse { error ->
        throw ServiceAccountAuthException("Invalid service account private key", cause = error)
    }

    private fun signRs256(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }
}
