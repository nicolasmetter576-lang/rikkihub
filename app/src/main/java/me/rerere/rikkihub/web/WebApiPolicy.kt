package me.rerere.rikkihub.web

import io.ktor.http.HttpHeaders
import io.ktor.http.Cookie
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.web.dto.ErrorResponse
import me.rerere.rikkihub.web.dto.WebAuthTokenRequest
import me.rerere.rikkihub.web.dto.WebAuthTokenResponse
import java.net.URI

internal const val WEB_JWT_PROVIDER = "auth-jwt"

/** Admits every call while web auth is switched off; mount routes with `authenticate(WEB_OPEN_PROVIDER, WEB_JWT_PROVIDER)`. */
internal const val WEB_OPEN_PROVIDER = "auth-open"

/** Principal of a call admitted because web auth is switched off. */
object WebOpenAccessPrincipal
private const val WEB_AUTH_REALM = "rikkihub-web-api"
/** Plain name on purpose: the `__Host-` prefix would need `Secure`, which plain-HTTP LAN use cannot set. */
internal const val WEB_SESSION_COOKIE = "rikkihub-session"
const val WEB_ERROR_TLS_REQUIRED = "WEB_TLS_REQUIRED"

/** Hard cap on the submitted password length — oversized bodies are rejected, never compared. */
private const val WEB_AUTH_PASSWORD_MAX_CHARS = 1024

/**
 * Web boundary policy.
 *
 * Fail-closed admin gate: every admin/sensitive `/api` endpoint requires the
 * CURRENT settings to be `jwtEnabled && nonBlank(accessPassword)` — evaluated
 * per request, not only at server startup — and (when JWT is active) a valid
 * token. The gate therefore also blocks tokens issued under an old password or
 * a previously-enabled auth flag after configuration is cleared/disabled;
 * the native app does not need to restart the server to revoke access.
 *
 * Origin gate: browser `Origin` headers are compared against the request
 * authority (scheme/host/port, normalized). `Origin: null`, malformed and
 * cross-origin values are denied. Requests without an Origin header (CLI,
 * same-origin GET navigation) still need auth — Origin is never a standalone
 * authorization signal.
 *
 * All helpers here are pure JVM functions so they can be unit-tested without
 * Android or a running server.
 */

// ---- Stable machine-readable error codes (ErrorResponse.errorCode) ----

/** Server is running but web auth is disabled in settings → admin API closed. */
const val WEB_ERROR_AUTH_DISABLED = "WEB_AUTH_DISABLED"

/** JWT enabled but the access password is missing/blank → admin API closed. */
const val WEB_ERROR_AUTH_NOT_CONFIGURED = "WEB_AUTH_PASSWORD_NOT_CONFIGURED"

/** A token or credential was required but absent/invalid/expired. */
const val WEB_ERROR_UNAUTHORIZED = "WEB_AUTH_UNAUTHORIZED"

/** Password check failed at POST /api/auth/token. */
const val WEB_ERROR_INVALID_CREDENTIALS = "WEB_AUTH_INVALID_CREDENTIALS"

/** Origin header present but null/malformed/not same-origin as the server. */
const val WEB_ERROR_ORIGIN_DENIED = "WEB_ORIGIN_DENIED"

/** /api/files/path target outside the managed allowlist subtrees. */
const val WEB_ERROR_FILE_FORBIDDEN = "WEB_FILE_FORBIDDEN_PATH"

/** /api/files/path target resolves through a symlink/alias. */
const val WEB_ERROR_FILE_SYMLINK_DENIED = "WEB_FILE_SYMLINK_DENIED"

/** /api/files/path target does not exist or is not a regular file. */
const val WEB_ERROR_FILE_NOT_FOUND = "WEB_FILE_NOT_FOUND"

/** Malformed request input (bad JSON body, missing parts, Ktor-level 400s). */
const val WEB_ERROR_BAD_REQUEST = "WEB_BAD_REQUEST"

/** Unhandled server failure; body is a fixed generic message, never a leak. */
const val WEB_ERROR_INTERNAL = "WEB_INTERNAL_ERROR"

class WebTlsRequiredException : ApiException("HTTPS required", HttpStatusCode.Forbidden, WEB_ERROR_TLS_REQUIRED)

class WebAuthDisabledException : ApiException(
    message = "Web access auth is disabled; enable it in the app web server settings",
    status = HttpStatusCode.Forbidden,
    errorCode = WEB_ERROR_AUTH_DISABLED,
)

class WebAuthNotConfiguredException : ApiException(
    message = "Access password is not configured; set a non-empty access password in the app web server settings",
    status = HttpStatusCode.Forbidden,
    errorCode = WEB_ERROR_AUTH_NOT_CONFIGURED,
)

class WebAuthInvalidCredentialsException : ApiException(
    message = "Invalid password",
    status = HttpStatusCode.Unauthorized,
    errorCode = WEB_ERROR_INVALID_CREDENTIALS,
)

class WebUnauthorizedException : ApiException(
    message = "Unauthorized",
    status = HttpStatusCode.Unauthorized,
    errorCode = WEB_ERROR_UNAUTHORIZED,
)

class WebOriginDeniedException : ApiException(
    message = "Cross-origin request denied",
    status = HttpStatusCode.Forbidden,
    errorCode = WEB_ERROR_ORIGIN_DENIED,
)

class WebBadRequestApiException(message: String) : ApiException(
    message = message,
    status = HttpStatusCode.BadRequest,
    errorCode = WEB_ERROR_BAD_REQUEST,
)

// ---- Installable boundary ----

/** Snapshot of the web-auth settings checked per request. */
data class WebAuthConfig(
    val jwtEnabled: Boolean,
    val accessPassword: String,
    /** Production listener is TLS-only. False exists for explicit loopback test/dev fixtures. */
    val requireHttps: Boolean = false,
)

/**
 * Error serialization for the web API: stable JSON `{error, code, errorCode}`
 * bodies for [ApiException]s and unknown `/api` 404s. Kept in one place so the
 * production module and JVM route tests install identical behavior.
 */
fun Application.installWebApiStatusPages() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<WebRateLimitedException> { call, cause ->
            call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value, cause.errorCode))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value, cause.errorCode))
        }
        // Versioned writes: a stale base or a management fence is a typed, retryable
        // client condition. The message is fixed; internal keys/IDs are not echoed.
        exception<me.rerere.rikkihub.data.mutation.MutationConflictException> { call, _ ->
            call.respond(HttpStatusCode.Conflict,
                ErrorResponse("Data changed; reload and retry", HttpStatusCode.Conflict.value, "VERSION_CONFLICT"))
        }
        // Generation, approval, queue or a compression owns the conversation.
        exception<me.rerere.rikkihub.data.mutation.ConversationBusyException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(
                "Conversation is busy: ${cause.reasons.joinToString()}",
                HttpStatusCode.Conflict.value, "CONVERSATION_BUSY",
            ))
        }
        exception<me.rerere.rikkihub.data.mutation.ConversationDirtyException> { call, _ ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(
                "Conversation is only partially loaded; reload and retry",
                HttpStatusCode.Conflict.value, "CONVERSATION_PARTIALLY_LOADED",
            ))
        }
        exception<me.rerere.rikkihub.data.mutation.MutationFencedException> { call, _ ->
            call.respond(HttpStatusCode.Locked,
                ErrorResponse("Target is busy with a pending change", HttpStatusCode.Locked.value, "TARGET_BUSY"))
        }
        // Ktor-level bad input (malformed JSON body, missing parts, bad query
        // shapes). Never echo the underlying cause — deserialization messages
        // can contain raw field names/values the client sent.
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Bad request", HttpStatusCode.BadRequest.value, WEB_ERROR_BAD_REQUEST),
            )
        }
        exception<io.ktor.server.plugins.ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Bad request", HttpStatusCode.BadRequest.value, WEB_ERROR_BAD_REQUEST),
            )
        }
        // Unknown failures get a fixed generic 500 — never expose the
        // exception message or stack internals to the client.
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error", 500, WEB_ERROR_INTERNAL),
            )
        }
    }
}

/**
 * Gate + Origin admission for the admin surface. Registered on the
 * Application Plugins phase, so it runs before route-level plugins
 * (including `authenticate`) for every call under /api — REST, SSE and the
 * WS terminal upgrade alike.
 *
 * [currentConfig] is read PER REQUEST (not captured at install), so
 * disabling web auth or clearing/changing the access password takes effect
 * immediately on a running server and revokes previously-issued tokens.
 *
 * Behavior:
 * - `X-Content-Type-Options: nosniff` on every `/api` response.
 * - `Origin: null`, malformed, multiple, or cross-origin values → 403
 *   `WEB_ORIGIN_DENIED`. No Origin header (CLI tooling, same-origin GET
 *   navigation) is not authorized by this check — auth still applies.
 * - Auth switched off (intranet use) → the API is open (see [WEB_OPEN_PROVIDER]).
 *   Auth on without a password → 403 `WEB_AUTH_PASSWORD_NOT_CONFIGURED`.
 */
fun Application.installWebApiBoundary(currentConfig: () -> WebAuthConfig) {
    intercept(ApplicationCallPipeline.Plugins) {
        val requestPath = call.request.path()
        val isApi = requestPath == "/api" || requestPath.startsWith("/api/")
        if (!isApi) return@intercept

        val configNow = currentConfig()
        val secureTransport = call.request.local.scheme.equals("https", true)
        if (configNow.requireHttps && !secureTransport) {
            throw WebTlsRequiredException()
        }
        // Cookie-carried authority needs an actual same-origin browser write.
        // CLI callers can explicitly send Origin; cookie possession alone is not CSRF admission.
        if (call.request.cookies[WEB_SESSION_COOKIE] != null && call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options) &&
            call.request.headers[HttpHeaders.Origin] == null) throw WebOriginDeniedException()

        // Defense-in-depth against MIME confusion on every API response.
        call.response.header("X-Content-Type-Options", "nosniff")

        val originHeaders = call.request.headers.getAll(HttpHeaders.Origin)
        val sameOrigin = when {
            originHeaders == null -> null
            originHeaders.size == 1 -> isSameOriginRequest(
                originHeader = originHeaders.single(),
                serverScheme = call.request.local.scheme,
                hostHeader = call.request.headers[HttpHeaders.Host],
                localPort = call.request.local.serverPort,
            )
            // Multiple Origin headers are never produced by browsers — deny.
            else -> false
        }
        if (sameOrigin == false) {
            throw WebOriginDeniedException()
        }

        if (!isPublicWebApiPath(requestPath)) {
            val config = currentConfig()
            when (webAuthState(config.jwtEnabled, config.accessPassword)) {
                WebAuthState.DISABLED -> Unit
                WebAuthState.PASSWORD_MISSING -> throw WebAuthNotConfiguredException()
                WebAuthState.ACTIVE -> Unit
            }
        }
    }
}

// ---- JWT provider + public endpoints (shared by production and tests) ----

/**
 * The `auth-jwt` provider: verifies `Authorization: Bearer` session tokens and
 * `?ticket=` single-use tickets against the RANDOM server secret held by
 * [WebAuthSecurity] (never the access password). Config is re-read per request:
 * the `epo` epoch claim binds every token to the current password/enabled
 * state, so rotating or clearing credentials kills live tokens immediately.
 *
 * URL credentials (`?ticket=`) are only admitted for `scp=ws` tickets on a real
 * WebSocket upgrade whose request path matches the ticket target, and are
 * strictly single-use (consumed at admission). The legacy `?access_token=`
 * transport is removed — anything else in the URL fails to a 401 challenge.
 */
fun io.ktor.server.auth.AuthenticationConfig.installWebJwtAuth(
    security: WebAuthSecurity,
    currentConfig: () -> WebAuthConfig,
) {
    // Listed first: with auth switched off every call is admitted without a token.
    provider(WEB_OPEN_PROVIDER) {
        authenticate { context ->
            val config = currentConfig()
            if (webAuthState(config.jwtEnabled, config.accessPassword) == WebAuthState.DISABLED) {
                context.principal(WebOpenAccessPrincipal)
            }
        }
    }
    jwt(WEB_JWT_PROVIDER) {
        realm = WEB_AUTH_REALM
        verifier { _ -> security.sessionVerifier() }
        authHeader { call ->
            val config = currentConfig()
            val cookie = call.request.cookies[WEB_SESSION_COOKIE]
            val bearer = if (!config.requireHttps) extractBearerToken(call.request.headers[HttpHeaders.Authorization]) else null
            val wsUpgrade = call.request.headers[HttpHeaders.Upgrade]?.equals("websocket", true) == true
            val ticket = call.request.queryParameters[WEB_TICKET_QUERY_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            val token = if (wsUpgrade && config.requireHttps) ticket else ticket ?: cookie ?: bearer
            token?.let { HttpAuthHeader.Single("Bearer", it) }
        }
        validate { credential ->
            // Receiver is ApplicationCall (Ktor 3 jwt validate signature).
            val call = this
            val config = currentConfig()
            if (webAuthState(config.jwtEnabled, config.accessPassword) != WebAuthState.ACTIVE) {
                return@validate null
            }
            val payload = credential.payload
            val admitted = when (payload.getClaim(WEB_JWT_TYPE_CLAIM).asString()) {
                WEB_JWT_TYPE_SESSION -> security.validateSessionJwt(payload, config)
                WEB_JWT_TYPE_TICKET -> {
                    val rawTicket = call.request.queryParameters[WEB_TICKET_QUERY_KEY]
                    rawTicket != null && security.isWsTicketAdmission(call, rawTicket, config)
                }
                else -> false
            }
            if (admitted) JWTPrincipal(payload) else null
        }
        challenge { _, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(
                    "Unauthorized",
                    HttpStatusCode.Unauthorized.value,
                    errorCode = WEB_ERROR_UNAUTHORIZED,
                )
            )
        }
    }
}

/**
 * Public, token-free endpoints: `GET /api/health`, `GET /api/auth/status`,
 * `POST /api/auth/token` (self-checks config so it reports stable codes),
 * plus the existing public AI-icon route. Mounted inside `route("/api")`.
 *
 * `/auth/token` is rate-limited per remote host and globally; the password is
 * only verification material — the issued session JWT is signed with the
 * random server secret, is short-lived, and dies on any credential change.
 */
fun Route.publicWebApiRoutes(
    security: WebAuthSecurity,
    rateLimiter: WebAuthRateLimiter,
    currentConfig: () -> WebAuthConfig,
) {
    post("/auth/token") {
        val remoteHost = call.request.origin.remoteHost
        rateLimiter.checkAttempt(remoteHost)
        val config = currentConfig()
        if (!config.jwtEnabled) {
            throw WebAuthDisabledException()
        }
        if (config.accessPassword.isBlank()) {
            throw WebAuthNotConfiguredException()
        }
        val request = call.receive<WebAuthTokenRequest>()
        if (request.password.length > WEB_AUTH_PASSWORD_MAX_CHARS) {
            throw WebBadRequestApiException("password is too long")
        }
        if (!secureEquals(request.password, config.accessPassword)) {
            rateLimiter.recordFailure(remoteHost)
            throw WebAuthInvalidCredentialsException()
        }
        rateLimiter.recordSuccess(remoteHost)
        val (token, expiresAt) = security.issueSession(config)
        val secureTransport = call.request.local.scheme.equals("https", true)
        if (config.requireHttps && !secureTransport) throw WebTlsRequiredException()
        // Browsers keep the session in this cookie on HTTP and HTTPS alike; the body token
        // (plain HTTP only) is for non-browser clients.
        call.response.cookies.append(sessionCookie(token, (WEB_SESSION_TTL_MILLIS / 1000).toInt(), secureTransport))
        call.respond(HttpStatusCode.OK, WebAuthTokenResponse(
            token = if (secureTransport) "" else token, expiresAt = expiresAt))
    }

    // Secret-free status endpoints so the SPA can distinguish "auth disabled"
    // / "password missing" from "authenticate here".
    get("/health") {
        call.respond(HttpStatusCode.OK, WebHealthDto())
    }
    get("/auth/status") {
        val config = currentConfig()
        call.respond(
            HttpStatusCode.OK,
            webAuthStatusDto(config.jwtEnabled, config.accessPassword),
        )
    }
}

private fun sessionCookie(value: String, maxAgeSeconds: Int, secure: Boolean) = Cookie(WEB_SESSION_COOKIE, value,
    path = "/", maxAge = maxAgeSeconds, secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Strict"))

/**
 * Session-scoped auth endpoints installed INSIDE the JWT-authenticated branch:
 * `POST /api/auth/logout` revokes the caller's session jti in-process, and
 * `POST /api/auth/ticket` mints a single-use `?ticket=` credential bound to a
 * scope + exact target path (currently only `ws` for terminal sockets).
 */
fun Route.authenticatedWebAuthRoutes(
    security: WebAuthSecurity,
    currentConfig: () -> WebAuthConfig,
) {
    post("/auth/logout") {
        val jti = call.principal<JWTPrincipal>()?.payload?.id
        if (jti != null) security.revokeSession(jti)
        call.response.cookies.append(sessionCookie("", 0, call.request.local.scheme.equals("https", true)))
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }

    post("/auth/ticket") {
        // Auth switched off: sockets need no credential; an empty ticket keeps the client flow uniform.
        if (call.principal<WebOpenAccessPrincipal>() != null) {
            return@post call.respond(HttpStatusCode.OK, WebAuthTicketResponse(ticket = "", expiresAt = 0L, scope = "ws"))
        }
        val principal = call.principal<JWTPrincipal>()
            ?: throw WebUnauthorizedException()
        val sessionJti = principal.payload.id
            ?: throw WebUnauthorizedException()
        val request = call.receive<WebAuthTicketRequest>()
        val scope = WebTicketScope.fromSerialName(request.scope)
            ?: throw WebBadRequestApiException("unsupported ticket scope")
        val target = request.target.trim()
        // Tickets bind an exact request path under /api — no queries, no
        // fragments, no foreign paths. The route itself re-verifies admission.
        if (!target.startsWith("/api/") || target.contains('?') ||
            target.contains('#') || target.contains("..")
        ) {
            throw WebBadRequestApiException("invalid ticket target")
        }
        val (ticket, expiresAt) = security.issueTicket(
            config = currentConfig(),
            sessionJti = sessionJti,
            scope = scope,
            target = target,
            sessionExpiresAtMillis = principal.payload.expiresAt?.time
                ?: throw WebUnauthorizedException(),
        )
        call.respond(
            HttpStatusCode.OK,
            WebAuthTicketResponse(ticket = ticket, expiresAt = expiresAt, scope = scope.serialName)
        )
    }
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

// ---- Auth state ----

@Serializable
enum class WebAuthState {
    /** Web auth is off; the API is open (intranet use). */
    @SerialName("disabled")
    DISABLED,

    /** Auth on but no usable password; admin/sensitive API is fail-closed. */
    @SerialName("password_missing")
    PASSWORD_MISSING,

    /** Auth on with a password; bearer token required for admin API. */
    @SerialName("active")
    ACTIVE,
}

/**
 * Public, secret-free auth status for the SPA bootstrap. `hint` is set for
 * non-active states so the frontend can show a stable actionable string; it
 * contains no credentials.
 */
@Serializable
data class WebAuthStatusDto(
    val jwtEnabled: Boolean,
    val passwordConfigured: Boolean,
    val authState: WebAuthState,
    val hint: String? = null,
)

@Serializable
data class WebHealthDto(
    val status: String = "ok",
)

fun webAuthState(jwtEnabled: Boolean, accessPassword: String): WebAuthState = when {
    !jwtEnabled -> WebAuthState.DISABLED
    accessPassword.isBlank() -> WebAuthState.PASSWORD_MISSING
    else -> WebAuthState.ACTIVE
}

fun webAuthStatusDto(jwtEnabled: Boolean, accessPassword: String): WebAuthStatusDto {
    val state = webAuthState(jwtEnabled, accessPassword)
    return WebAuthStatusDto(
        jwtEnabled = jwtEnabled,
        passwordConfigured = accessPassword.isNotBlank(),
        authState = state,
        hint = when (state) {
            WebAuthState.DISABLED -> null
            WebAuthState.PASSWORD_MISSING ->
                "access password not configured — set a password in the app web server settings"
            WebAuthState.ACTIVE -> null
        },
    )
}

/**
 * Paths reachable without any token. Everything else under /api is admin or
 * sensitive and is gated. /api/auth/token is public-listed so callers get the
 * handler's stable error codes (auth disabled / password missing / invalid
 * credentials) instead of a gate rejection.
 */
fun isPublicWebApiPath(path: String): Boolean =
    path == "/api/health" ||
        path == "/api/auth/status" ||
        path == "/api/ai-icon" ||
        path == "/api/auth/token"

// ---- Origin checking ----

/** Default port for a URL scheme; null when the scheme is not web/WS. */
internal fun defaultWebSchemePort(scheme: String): Int? = when (scheme.lowercase()) {
    "http", "ws" -> 80
    "https", "wss" -> 443
    else -> null
}

/** Normalized host for comparison: trimmed, lowercased, brackets stripped. */
internal fun normalizeWebHost(host: String?): String? {
    val trimmed = host?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('@')) return null
    val unwrapped =
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    return unwrapped.takeIf { it.isNotEmpty() }?.lowercase()
}

/** Parsed `host[:port]` authority; port may be absent (then effective = default). */
data class WebAuthority(val host: String, val port: Int?)

/**
 * Parse an HTTP Host header. `fallbackPort` is used when the header has no
 * explicit port (HTTP/1.0 or default-port omission). Returns null on malformed
 * input — including unbracketed IPv6 literals and out-of-range ports.
 */
internal fun parseHostAuthority(value: String?, fallbackPort: Int?): WebAuthority? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    var hostPart = trimmed
    var port: Int? = null
    if (hostPart.startsWith("[")) {
        val closing = hostPart.indexOf(']')
        if (closing < 0) return null
        val literal = hostPart.substring(1, closing)
        if (literal.isEmpty()) return null
        val rest = hostPart.substring(closing + 1)
        port = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
            else -> return null
        }
        hostPart = "[$literal]"
    } else {
        val colonIndex = hostPart.indexOf(':')
        if (colonIndex >= 0) {
            val rawPort = hostPart.substring(colonIndex + 1)
            if (rawPort.contains(':')) return null // unbracketed IPv6 is invalid in Host
            port = rawPort.toIntOrNull() ?: return null
            hostPart = hostPart.substring(0, colonIndex)
        }
    }
    if (port != null && (port <= 0 || port > 65535)) return null
    val host = normalizeWebHost(hostPart) ?: return null
    return WebAuthority(host = host, port = port ?: fallbackPort)
}

/** Parsed Origin value: `scheme://host[:port]` only — no path/query/userinfo. */
data class WebOrigin(val scheme: String, val host: String, val port: Int?)

/**
 * Strict Origin parser. Browsers serialize origins as `scheme://host[:port]`
 * (or the literal `null` for opaque origins); anything else is rejected.
 */
internal fun parseWebOrigin(origin: String): WebOrigin? {
    val trimmed = origin.trim()
    if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (defaultWebSchemePort(scheme) == null) return null
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val rawPath = uri.rawPath.orEmpty()
    if (rawPath.isNotEmpty() && rawPath != "/") return null
    val host = normalizeWebHost(uri.host) ?: return null
    val port = uri.port.takeIf { it >= 0 }
    if (port != null && port > 65535) return null
    return WebOrigin(scheme = scheme, host = host, port = port)
}

/**
 * Origin admission for the admin surface.
 *
 * Returns:
 * - `null`  — no Origin header (non-browser client or same-origin GET): not
 *   denied by this check; authentication still applies.
 * - `true`  — same-origin browser request.
 * - `false` — `null`, malformed, or cross-origin: deny.
 */
internal fun isSameOriginRequest(
    originHeader: String?,
    serverScheme: String?,
    hostHeader: String?,
    localPort: Int,
): Boolean? {
    if (originHeader == null) return null
    val requestScheme = serverScheme?.lowercase() ?: return false
    if (defaultWebSchemePort(requestScheme) == null) return false
    val origin = parseWebOrigin(originHeader) ?: return false
    val authority = parseHostAuthority(hostHeader, fallbackPort = localPort) ?: return false
    if (origin.scheme != requestScheme) return false
    if (origin.host != authority.host) return false
    val requestPort = authority.port ?: localPort
    val originPort = origin.port ?: defaultWebSchemePort(origin.scheme) ?: return false
    return originPort == requestPort
}
