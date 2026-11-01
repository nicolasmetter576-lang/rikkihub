package me.rerere.rikkihub.data.devin

import android.content.Context
import android.content.Intent
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.oauth.OAuthCallbackDecision
import me.rerere.rikkihub.data.oauth.OAuthPkceSession
import me.rerere.rikkihub.data.oauth.awaitNetworkUnblocked
import me.rerere.rikkihub.data.oauth.claimCallback
import me.rerere.rikkihub.data.oauth.oauthCallbackPage
import me.rerere.rikkihub.data.oauth.oauthUnknownCallbackPage
import me.rerere.rikkihub.data.oauth.randomHex
import me.rerere.rikkihub.data.oauth.randomUrlSafe
import me.rerere.rikkihub.data.oauth.removeExpiredSessions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Devin CLI OAuth 登录 (PKCE + 随机回环端口回调).
 *
 * 授权 URL 与官方 devin-cli 一致, 手工按序拼接, 无 client_id/scope/response_type.
 * redirect_uri 未在服务端注册, 必须用 127.0.0.1 随机端口.
 */
class DevinOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: DevinAccountRepository,
    private val json: Json,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var callbackPort: Int? = null
    private val serverMutex = Mutex()
    private val sessions = ConcurrentHashMap<String, OAuthPkceSession>()
    private val _status = MutableStateFlow<DevinOAuthStatus>(DevinOAuthStatus.Idle)
    val status: StateFlow<DevinOAuthStatus> = _status.asStateFlow()

    fun startLogin() {
        scope.launch {
            sessions.removeExpiredSessions()
            val state = randomHex(16)
            try {
                val verifier = randomUrlSafe(64)
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
                )
                val port = registerSession(state, verifier)
                val redirect = "http://127.0.0.1:$port/callback"
                _status.value = DevinOAuthStatus.Waiting

                val authUrl = AUTHORIZE_URL +
                    "?redirect_uri=" + urlEncode(redirect) +
                    "&state=" + urlEncode(state) +
                    "&prompt=select_account" +
                    "&code_challenge=" + urlEncode(challenge) +
                    "&code_challenge_method=S256"
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (error: Throwable) {
                sessions.remove(state)
                _status.value = DevinOAuthStatus.Error(
                    if (error.message == CALLBACK_PORT_UNAVAILABLE) {
                        context.getString(R.string.devin_oauth_ports_unavailable)
                    } else {
                        error.message ?: "Unable to open the Devin sign-in page"
                    }
                )
                stopCallbackServerIfIdle()
            }
        }
    }

    /**
     * 手动粘贴登录:
     * - session token → 直接保存
     * - 回调 URL → 按 state 找 pending session, 用其 verifier 换 token
     * - 裸 code → 用最近的 pending session 的 verifier 换 token
     */
    fun loginWithManualInput(input: String) {
        scope.launch {
            try {
                when (val parsed = parseDevinManualInput(input)) {
                    is DevinManualInput.SessionToken -> completeLogin(parsed.token)

                    is DevinManualInput.CallbackUrl -> {
                        val session = sessions.remove(parsed.state)
                            ?: error("OAuth state mismatch or session expired")
                        completeLogin(exchangeCode(parsed.code, session))
                    }

                    is DevinManualInput.BareCode -> {
                        sessions.removeExpiredSessions()
                        val entry = sessions.entries.maxByOrNull { it.value.createdAt }
                            ?: error("No pending Devin sign-in session for the pasted code")
                        sessions.remove(entry.key)
                        completeLogin(exchangeCode(parsed.code, entry.value))
                    }

                    null -> _status.value = DevinOAuthStatus.Error("Empty Devin sign-in input")
                }
            } catch (error: Throwable) {
                _status.value = DevinOAuthStatus.Error(
                    error.message ?: "Devin sign-in failed"
                )
            } finally {
                stopCallbackServerIfIdle()
            }
        }
    }

    fun consumeResult() {
        _status.value = DevinOAuthStatus.Idle
    }

    /**
     * Atomically starts the loopback server (if needed) and registers the session,
     * so [stopCallbackServerIfIdle] can never tear down a server that still owns a
     * pending session.
     */
    private suspend fun registerSession(state: String, verifier: String): Int =
        serverMutex.withLock {
            val port = ensureCallbackServerLocked()
            sessions[state] = OAuthPkceSession(
                verifier = verifier,
                redirectUri = "http://127.0.0.1:$port/callback",
            )
            port
        }

    /** Ktor CIO port=0: OS 分配随机端口, 启动后经 resolvedConnectors 取实际端口 */
    private suspend fun ensureCallbackServerLocked(): Int {
        callbackPort?.let { return it }
        val created = try {
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                routing {
                    get("/callback") {
                        val decision = sessions.claimCallback(
                            state = call.request.queryParameters["state"],
                            code = call.request.queryParameters["code"],
                            error = call.request.queryParameters["error"],
                        )
                        try {
                            when (decision) {
                                OAuthCallbackDecision.Unknown -> call.respondText(
                                    oauthUnknownCallbackPage(CALLBACK_TITLE),
                                    ContentType.Text.Html,
                                    HttpStatusCode.BadRequest,
                                )

                                is OAuthCallbackDecision.Failed -> {
                                    _status.value = DevinOAuthStatus.Error(decision.message)
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                }

                                is OAuthCallbackDecision.Exchange -> {
                                    call.respondText(callbackPage(true), ContentType.Text.Html)
                                    scope.launch {
                                        try {
                                            context.awaitNetworkUnblocked()
                                            completeLogin(exchangeCode(decision.code, decision.session))
                                        } catch (error: Throwable) {
                                            Log.e(
                                                TAG,
                                                "OAuth token exchange failed: " +
                                                    "${error::class.java.name}: ${error.message}",
                                                error,
                                            )
                                            _status.value = DevinOAuthStatus.Error(
                                                error.message ?: "OAuth token exchange failed"
                                            )
                                        } finally {
                                            stopCallbackServerIfIdle()
                                        }
                                    }
                                }
                            }
                        } finally {
                            stopCallbackServerIfIdle()
                        }
                    }
                }
            }.start(wait = false)
        } catch (error: Throwable) {
            throw IllegalStateException(CALLBACK_PORT_UNAVAILABLE, error)
        }
        val port = runCatching {
            created.engine.resolvedConnectors().firstOrNull()?.port
        }.getOrNull()
        if (port == null || port <= 0) {
            runCatching { created.stop(500, 1_000) }
            throw IllegalStateException(CALLBACK_PORT_UNAVAILABLE)
        }
        server = created
        callbackPort = port
        return port
    }

    private fun callbackPage(success: Boolean): String =
        oauthCallbackPage(success, "rikkihub://devin/oauth", CALLBACK_TITLE)

    /**
     * The loopback server only exists to receive the browser redirect. Once no
     * session can still arrive, shut it down instead of holding the port open.
     */
    private fun stopCallbackServerIfIdle() {
        scope.launch(Dispatchers.IO) {
            serverMutex.withLock {
                if (sessions.isNotEmpty()) return@withLock
                val running = server
                server = null
                callbackPort = null
                running?.let { runCatching { it.stop(500, 1_000) } }
            }
        }
    }

    /** POST /auth/cli/token {code, code_verifier} → .token (session token) */
    private suspend fun exchangeCode(code: String, session: OAuthPkceSession): String {
        val payload = buildJsonObject {
            put("code", code)
            put("code_verifier", session.verifier)
        }
        val response = client.newCall(
            Request.Builder()
                .url(TOKEN_URL)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Token exchange failed: ${response.code}")
        }
        return json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.contentOrNull
            ?: error("Token exchange response missing token")
    }

    private suspend fun completeLogin(sessionToken: String) {
        val account = repository.saveLogin(sessionToken)
        _status.value = DevinOAuthStatus.Success(account.id)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val TAG = "DevinOAuthManager"
        private const val CALLBACK_PORT_UNAVAILABLE = "OAuth callback port is unavailable"
        private const val CALLBACK_TITLE = "Rikkihub Devin OAuth"
        const val AUTHORIZE_URL = "https://app.devin.ai/auth/cli/continue"
        const val TOKEN_URL = "https://api.devin.ai/auth/cli/token"
    }
}

sealed interface DevinOAuthStatus {
    data object Idle : DevinOAuthStatus
    data object Waiting : DevinOAuthStatus
    data class Success(val accountId: String) : DevinOAuthStatus
    data class Error(val message: String) : DevinOAuthStatus
}
