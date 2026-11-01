package me.rerere.rikkihub.data.antigravity

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.providers.antigravity.AntigravityChatApi
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.oauth.OAuthCallbackDecision
import me.rerere.rikkihub.data.oauth.OAuthPkceSession
import me.rerere.rikkihub.data.oauth.awaitNetworkUnblocked
import me.rerere.rikkihub.data.oauth.claimCallback
import me.rerere.rikkihub.data.oauth.oauthCallbackPage
import me.rerere.rikkihub.data.oauth.oauthUnknownCallbackPage
import me.rerere.rikkihub.data.oauth.randomHex
import me.rerere.rikkihub.data.oauth.removeExpiredSessions
import java.util.concurrent.ConcurrentHashMap

/**
 * Antigravity Google OAuth 登录（CPA `sdk/auth/antigravity.go`）：
 * 桌面客户端 + client_secret，无 PKCE；回调 `http://localhost:<端口>/oauth-callback`，
 * 先试 51121，被占用则随机端口（Google 桌面客户端接受任意回环端口）。
 * 回调后：换 token → userinfo 取邮箱 → loadCodeAssist/onboardUser 取项目 → 保存账户。
 */
class AntigravityOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: AntigravityChatApi,
    private val repository: AntigravityAccountRepository,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var callbackPort: Int? = null
    private val serverMutex = Mutex()

    // 复用 PKCE 会话记录（verifier 不用，只用 redirectUri 与过期时间）
    private val sessions = ConcurrentHashMap<String, OAuthPkceSession>()
    private val _status = MutableStateFlow<AntigravityOAuthStatus>(AntigravityOAuthStatus.Idle)
    val status: StateFlow<AntigravityOAuthStatus> = _status.asStateFlow()

    fun startLogin() {
        scope.launch {
            sessions.removeExpiredSessions()
            val state = randomHex(16)
            try {
                val redirect = registerSession(state)
                _status.value = AntigravityOAuthStatus.Waiting
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(api.buildAuthUrl(state, redirect))).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (error: Throwable) {
                sessions.remove(state)
                _status.value = AntigravityOAuthStatus.Error(
                    if (error.message == CALLBACK_PORT_UNAVAILABLE) {
                        context.getString(R.string.antigravity_oauth_port_unavailable)
                    } else {
                        error.message ?: "Unable to open the Google sign-in page"
                    }
                )
                stopCallbackServerIfIdle()
            }
        }
    }

    /**
     * 手动粘贴：浏览器跳到回调地址但没回到 App（例如在别的设备上登录）时，
     * 把地址栏里的回调 URL 粘贴进来；只有 code 时用最近一次待完成的登录。
     */
    fun loginWithManualInput(input: String) {
        scope.launch {
            try {
                val parsed = parseAntigravityCallbackInput(input)
                    ?: error(context.getString(R.string.antigravity_manual_input_invalid))
                parsed.error?.let { error(it) }
                val session = if (parsed.state != null) {
                    sessions.remove(parsed.state) ?: error("OAuth state mismatch or session expired")
                } else {
                    sessions.removeExpiredSessions()
                    val entry = sessions.entries.maxByOrNull { it.value.createdAt }
                        ?: error("No pending Antigravity sign-in for the pasted code")
                    sessions.remove(entry.key)
                    entry.value
                }
                completeLogin(parsed.code, session.redirectUri)
            } catch (error: Throwable) {
                _status.value = AntigravityOAuthStatus.Error(error.message ?: "Antigravity sign-in failed")
            } finally {
                stopCallbackServerIfIdle()
            }
        }
    }

    fun consumeResult() {
        _status.value = AntigravityOAuthStatus.Idle
    }

    private suspend fun registerSession(state: String): String = serverMutex.withLock {
        val port = ensureCallbackServerLocked()
        val redirect = "http://localhost:$port/oauth-callback"
        sessions[state] = OAuthPkceSession(verifier = "", redirectUri = redirect)
        redirect
    }

    private suspend fun ensureCallbackServerLocked(): Int {
        callbackPort?.let { return it }
        var lastError: Throwable? = null
        for (port in listOf(AntigravityChatApi.DEFAULT_CALLBACK_PORT, 0)) {
            val created = try {
                createServer(port).start(wait = false)
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            val actual = runCatching { created.engine.resolvedConnectors().firstOrNull()?.port }.getOrNull()
            if (actual == null || actual <= 0) {
                runCatching { created.stop(500, 1_000) }
                continue
            }
            server = created
            callbackPort = actual
            return actual
        }
        throw IllegalStateException(CALLBACK_PORT_UNAVAILABLE, lastError)
    }

    private fun createServer(port: Int) = embeddedServer(CIO, host = "127.0.0.1", port = port) {
        routing {
            get("/oauth-callback") {
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
                            _status.value = AntigravityOAuthStatus.Error(decision.message)
                            call.respondText(callbackPage(false), ContentType.Text.Html)
                        }

                        is OAuthCallbackDecision.Exchange -> {
                            call.respondText(callbackPage(true), ContentType.Text.Html)
                            scope.launch {
                                try {
                                    context.awaitNetworkUnblocked()
                                    completeLogin(decision.code, decision.session.redirectUri)
                                } catch (error: Throwable) {
                                    Log.e(TAG, "OAuth completion failed: ${error::class.java.name}: ${error.message}", error)
                                    _status.value = AntigravityOAuthStatus.Error(
                                        error.message ?: "Antigravity sign-in failed"
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
    }

    private fun callbackPage(success: Boolean): String =
        oauthCallbackPage(success, "rikkihub://antigravity/oauth", CALLBACK_TITLE)

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

    private suspend fun completeLogin(code: String, redirectUri: String) {
        _status.value = AntigravityOAuthStatus.Completing
        val tokens = api.exchangeCode(code, redirectUri)
        val email = api.fetchUserEmail(tokens.accessToken)
        val projectId = api.fetchProjectId(tokens.accessToken)
        val account = repository.saveLogin(tokens, email, projectId)
        runCatching { repository.refreshAccount(account.id) }
        _status.value = AntigravityOAuthStatus.Success(account.id)
    }

    companion object {
        private const val TAG = "AntigravityOAuth"
        private const val CALLBACK_PORT_UNAVAILABLE = "OAuth callback port is unavailable"
        private const val CALLBACK_TITLE = "Rikkihub Antigravity OAuth"
    }
}

sealed interface AntigravityOAuthStatus {
    data object Idle : AntigravityOAuthStatus
    data object Waiting : AntigravityOAuthStatus

    /** 浏览器已回调，正在换 token / 取项目。 */
    data object Completing : AntigravityOAuthStatus
    data class Success(val accountId: String) : AntigravityOAuthStatus
    data class Error(val message: String) : AntigravityOAuthStatus
}

internal data class AntigravityCallbackInput(
    val code: String,
    val state: String?,
    val error: String?,
)

/** 回调 URL（取 code/state/error）或裸 code；空输入、没有 code 的 URL 返回 null。 */
internal fun parseAntigravityCallbackInput(input: String): AntigravityCallbackInput? {
    val text = input.trim().takeIf { it.isNotEmpty() } ?: return null
    val query = when {
        "?" in text -> text.substringAfter('?').substringBefore('#')
        text.startsWith("code=") || "&code=" in text -> text
        else -> return AntigravityCallbackInput(code = text, state = null, error = null)
    }
    val params = query.split('&').mapNotNull { pair ->
        val key = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        key to java.net.URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
    }.toMap()
    val error = params["error"]?.takeIf { it.isNotBlank() }
    val code = params["code"]?.takeIf { it.isNotBlank() }
    if (code == null && error == null) return null
    return AntigravityCallbackInput(code = code.orEmpty(), state = params["state"]?.takeIf { it.isNotBlank() }, error = error)
}
