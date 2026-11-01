package me.rerere.rikkihub.data.codex

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import me.rerere.common.http.await
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.oauth.OAuthCallbackDecision
import me.rerere.rikkihub.data.oauth.OAuthPkceSession
import me.rerere.rikkihub.data.oauth.awaitNetworkUnblocked
import me.rerere.rikkihub.data.oauth.claimCallback
import me.rerere.rikkihub.data.oauth.oauthCallbackPage
import me.rerere.rikkihub.data.oauth.oauthUnknownCallbackPage
import me.rerere.rikkihub.data.oauth.randomUrlSafe
import me.rerere.rikkihub.data.oauth.removeExpiredSessions
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class CodexOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var callbackPort: Int? = null
    private val sessions = ConcurrentHashMap<String, OAuthPkceSession>()
    private val _status = MutableStateFlow<CodexOAuthStatus>(CodexOAuthStatus.Idle)
    val status: StateFlow<CodexOAuthStatus> = _status.asStateFlow()

    fun startLogin() {
        sessions.removeExpiredSessions()
        val state = randomUrlSafe(32)
        try {
            val verifier = randomUrlSafe(64)
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
            )
            val port = registerSession(state, verifier)
            val redirect = "http://localhost:$port/auth/callback"
            _status.value = CodexOAuthStatus.Waiting

            val authUrl = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", DEFAULT_SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("originator", "codex_cli_rs")
                .build()
            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (error: Throwable) {
            sessions.remove(state)
            _status.value = CodexOAuthStatus.Error(
                if (error.message == CALLBACK_PORTS_UNAVAILABLE) {
                    context.getString(R.string.codex_oauth_ports_unavailable)
                } else {
                    error.message ?: "Unable to open the OpenAI sign-in page"
                }
            )
            stopCallbackServerIfIdle()
        }
    }

    fun consumeResult() {
        _status.value = CodexOAuthStatus.Idle
    }

    /**
     * Atomically starts the loopback server (if needed) and registers the session,
     * so [stopCallbackServerIfIdle] can never tear down a server that still owns a
     * pending session.
     */
    @Synchronized
    private fun registerSession(state: String, verifier: String): Int {
        val port = ensureCallbackServerLocked()
        sessions[state] = OAuthPkceSession(
            verifier = verifier,
            redirectUri = "http://localhost:$port/auth/callback",
        )
        return port
    }

    private fun ensureCallbackServerLocked(): Int {
        callbackPort?.let { return it }
        var lastError: Throwable? = null
        for (port in CALLBACK_PORTS) {
            try {
                server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                    routing {
                        get("/auth/callback") {
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
                                        _status.value = CodexOAuthStatus.Error(decision.message)
                                        call.respondText(callbackPage(false), ContentType.Text.Html)
                                    }

                                    is OAuthCallbackDecision.Exchange -> {
                                        call.respondText(callbackPage(true), ContentType.Text.Html)
                                        scope.launch {
                                            try {
                                                context.awaitNetworkUnblocked()
                                                val account = exchangeCode(decision.code, decision.session)
                                                _status.value = CodexOAuthStatus.Success(account.id)
                                                runCatching { repository.refreshAccount(account.id) }
                                            } catch (error: Throwable) {
                                                Log.e(
                                                    TAG,
                                                    "OAuth token exchange failed: " +
                                                        "${error::class.java.name}: ${error.message}",
                                                    error,
                                                )
                                                _status.value = CodexOAuthStatus.Error(
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
                callbackPort = port
                return port
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(CALLBACK_PORTS_UNAVAILABLE, lastError)
    }

    private fun callbackPage(success: Boolean): String =
        oauthCallbackPage(success, "rikkihub://codex/oauth", CALLBACK_TITLE)

    /**
     * The loopback server only exists to receive the browser redirect. Once no
     * session can still arrive, shut it down instead of holding the port open.
     */
    private fun stopCallbackServerIfIdle() {
        val stopped = synchronized(this) {
            if (sessions.isNotEmpty()) return
            val running = server
            server = null
            callbackPort = null
            running
        }
        if (stopped != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { stopped.stop(500, 1_000) }
            }
        }
    }

    private suspend fun exchangeCode(code: String, session: OAuthPkceSession): CodexAccount {
        val response = client.newCall(
            Request.Builder()
                .url(TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", CLIENT_ID)
                        .add("code", code)
                        .add("redirect_uri", session.redirectUri)
                        .add("code_verifier", session.verifier)
                        .build()
                )
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Token exchange failed: ${response.code}")
        }
        return repository.saveLogin(body)
    }

    companion object {
        private const val TAG = "CodexOAuthManager"
        private const val CALLBACK_PORTS_UNAVAILABLE = "OAuth callback ports are unavailable"
        private const val CALLBACK_TITLE = "Rikkihub Codex OAuth"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val DEFAULT_SCOPES = "openid profile email offline_access"
        const val REFRESH_SCOPES = "openid profile email"
        private val CALLBACK_PORTS = listOf(1455, 1457)
    }
}

sealed interface CodexOAuthStatus {
    data object Idle : CodexOAuthStatus
    data object Waiting : CodexOAuthStatus
    data class Success(val accountId: String) : CodexOAuthStatus
    data class Error(val message: String) : CodexOAuthStatus
}
