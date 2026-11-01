package me.rerere.rikkihub.web

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Web credential material that is NOT the access password.
 *
 * - `secret` is a random 256-bit key generated once into `filesDir/web-auth/`
 *   (host-protected storage, 0600 best-effort). The access password is now only
 *   verification material; rotating/clearing it changes the derived session
 *   `epo` claim so every live token dies immediately — no restart required.
 * - `session` JWTs live 30 days (browsers keep them in an HttpOnly cookie),
 *   `typ=web_session`, `jti`-revocable in process (logout). `epo` binds them to the
 *   current password/enabled state and is stable across app restarts.
 * - `ticket` JWTs are the ONLY credentials accepted in URLs (`?ticket=`):
 *   90s TTL, bound to a scope (`ws`) + exact target path + the issuing session
 *   jti, and strictly single-use via a bounded in-memory consumed-jti set.
 *   Tickets are additionally only honored on WebSocket upgrade requests, so a
 *   leaked ticket cannot mint API calls.
 * - `WebAuthRateLimiter` bounds `/api/auth/token` guessing (per remote host and
 *   global sliding windows) → 429 `WEB_AUTH_RATE_LIMITED` + `Retry-After`.
 *
 * Pure JVM (java.io.File only) — unit-testable without Android.
 */

internal const val WEB_JWT_TYPE_CLAIM = "typ"
internal const val WEB_JWT_TYPE_SESSION = "web_session"
internal const val WEB_JWT_TYPE_TICKET = "web_ticket"
internal const val WEB_JWT_EPOCH_CLAIM = "epo"
internal const val WEB_JWT_SCOPE_CLAIM = "scp"
internal const val WEB_JWT_TARGET_CLAIM = "tgt"
internal const val WEB_JWT_SESSION_CLAIM = "sid"
internal const val WEB_TICKET_QUERY_KEY = "ticket"

internal const val WEB_SESSION_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_TICKET_TTL_MILLIS = 90L * 1000
private const val WEB_AUTH_SECRET_FILE = "web-auth-secret.bin"
private const val WEB_AUTH_SECRET_BYTES = 32
private const val REVOKED_SET_MAX = 4096
private const val CONSUMED_TICKET_MAX = 8192

const val WEB_ERROR_RATE_LIMITED = "WEB_AUTH_RATE_LIMITED"
const val WEB_ERROR_TICKET_INVALID = "WEB_TICKET_INVALID"

/** Short-lived credential purpose. `ws` tickets are only honored on WS upgrade requests. */
enum class WebTicketScope(val serialName: String) {
    WS("ws");

    companion object {
        fun fromSerialName(value: String?): WebTicketScope? = entries.firstOrNull { it.serialName == value }
    }
}

@Serializable
data class WebAuthTicketRequest(
    val scope: String,
    /** Exact request path the ticket will be consumed on, e.g. /api/workspaces/{id}/terminal/sessions/{sid}. */
    val target: String,
)

@Serializable
data class WebAuthTicketResponse(
    val ticket: String,
    val expiresAt: Long,
    val scope: String,
)

class WebRateLimitedException(val retryAfterSeconds: Long) : ApiException(
    message = "Too many authentication attempts",
    status = HttpStatusCode.TooManyRequests,
    errorCode = WEB_ERROR_RATE_LIMITED,
)

class WebTicketInvalidException : ApiException(
    message = "Invalid or expired ticket",
    status = HttpStatusCode.Unauthorized,
    errorCode = WEB_ERROR_TICKET_INVALID,
)

/**
 * Holds the random signing secret and the session/ticket lifecycle.
 * One instance per web server process; [secretDir] must be app-private
 * (context.filesDir). Revocation state is in-memory by design — a restart
 * clears the session revoke list and the consumed-ticket ledger (tickets are
 * 90s anyway; sessions re-verify via epoch/signature).
 */
class WebAuthSecurity(
    secretDir: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val secretFile = File(secretDir, WEB_AUTH_SECRET_FILE)

    @Volatile
    private var cachedSecret: ByteArray? = null

    private val revokedSessionJtis = ExpiringStringSet(REVOKED_SET_MAX, nowMillis)
    private val consumedTicketJtis = ExpiringStringSet(CONSUMED_TICKET_MAX, nowMillis)
    private var epochConfig: WebAuthConfig? = null
    /** Empty unless revocation capacity overflowed; then live tokens die with the overflowing set. */
    private var epochNonce = ""

    /** Random 256-bit signing secret; created once, never derived from the password. */
    fun secret(): ByteArray {
        cachedSecret?.let { return it }
        synchronized(this) {
            cachedSecret?.let { return it }
            val loaded = runCatching {
                secretFile.takeIf { it.isFile }?.readBytes()
            }.getOrNull()
            val secret = if (loaded != null && loaded.size >= WEB_AUTH_SECRET_BYTES) {
                loaded.copyOf(WEB_AUTH_SECRET_BYTES)
            } else {
                val generated = ByteArray(WEB_AUTH_SECRET_BYTES).also { SecureRandom().nextBytes(it) }
                writeSecretFile(generated)
                generated
            }
            cachedSecret = secret
            return secret
        }
    }

    private fun writeSecretFile(bytes: ByteArray) {
        runCatching {
            secretFile.parentFile?.mkdirs()
            val tmp = File(secretFile.parentFile, "${secretFile.name}.tmp")
            tmp.writeBytes(bytes)
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(secretFile)) {
                secretFile.writeBytes(bytes)
                tmp.delete()
            }
            secretFile.setReadable(false, false)
            secretFile.setReadable(true, true)
            secretFile.setWritable(true, true)
        }
    }

    private fun algorithm(): Algorithm = Algorithm.HMAC256(secret())

    /**
     * Revocation epoch: binds every session/ticket to the CURRENT auth config.
     * Changes when the password or enabled flag changes → all live tokens die.
     * Derived only from persisted material, so sessions survive app restarts.
     * Never embeds password material (HMAC over the random secret).
     */
    fun currentEpoch(config: WebAuthConfig): String = synchronized(this) {
        if (epochConfig != config) {
            epochConfig = config
            revokedSessionJtis.clear()
            consumedTicketJtis.clear()
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret(), "HmacSHA256"))
        val digest = mac.doFinal(
            "web-epoch|$epochNonce|${config.jwtEnabled}|${config.accessPassword}".toByteArray(Charsets.UTF_8)
        )
        digest.toHex()
    }

    // ---- sessions ----

    fun issueSession(config: WebAuthConfig, now: Long = nowMillis()): Pair<String, Long> {
        val expiresAt = now + WEB_SESSION_TTL_MILLIS
        val token = JWT.create()
            .withIssuer(WEB_JWT_ISSUER)
            .withAudience(WEB_JWT_AUDIENCE)
            .withSubject(WEB_JWT_SUBJECT)
            .withClaim(WEB_JWT_TYPE_CLAIM, WEB_JWT_TYPE_SESSION)
            .withClaim(WEB_JWT_EPOCH_CLAIM, currentEpoch(config))
            .withJWTId(Uuid.random().toString())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(expiresAt))
            .sign(algorithm())
        return token to expiresAt
    }

    /** Verifies signature/issuer/audience/subject only; `typ`/`epo`/revocation in [validateSessionJwt]. */
    fun sessionVerifier(): JWTVerifier {
        val verifier = JWT.require(algorithm())
            .withIssuer(WEB_JWT_ISSUER)
            .withAudience(WEB_JWT_AUDIENCE)
            .withSubject(WEB_JWT_SUBJECT)
        val clock = object : java.time.Clock() {
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant(): java.time.Instant = java.time.Instant.ofEpochMilli(nowMillis())
        }
        return (verifier as JWTVerifier.BaseVerification).build(clock)
    }

    fun validateSessionJwt(jwt: Payload, config: WebAuthConfig): Boolean {
        if (jwt.getClaim(WEB_JWT_TYPE_CLAIM).asString() != WEB_JWT_TYPE_SESSION) return false
        if (jwt.getClaim(WEB_JWT_EPOCH_CLAIM).asString() != currentEpoch(config)) return false
        val jti = jwt.id ?: return false
        return !revokedSessionJtis.contains(jti)
    }

    fun revokeSession(jti: String) = synchronized(this) {
        if (!revokedSessionJtis.addIfAbsent(jti, nowMillis() + WEB_SESSION_TTL_MILLIS)) {
            // Capacity must never resurrect an unexpired revoked credential.
            epochNonce = Uuid.random().toString()
            revokedSessionJtis.clear()
            consumedTicketJtis.clear()
        }
    }

    fun isSessionRevoked(jti: String): Boolean = revokedSessionJtis.contains(jti)

    // ---- tickets (URL-carried, single-use) ----

    fun issueTicket(
        config: WebAuthConfig,
        sessionJti: String,
        scope: WebTicketScope,
        target: String,
        now: Long = nowMillis(),
        sessionExpiresAtMillis: Long = now + WEB_SESSION_TTL_MILLIS,
    ): Pair<String, Long> {
        val expiresAt = minOf(now + WEB_TICKET_TTL_MILLIS, sessionExpiresAtMillis)
        require(expiresAt > now) { "parent session expired" }
        val token = JWT.create()
            .withIssuer(WEB_JWT_ISSUER)
            .withAudience(WEB_JWT_AUDIENCE)
            .withSubject(WEB_JWT_SUBJECT)
            .withClaim(WEB_JWT_TYPE_CLAIM, WEB_JWT_TYPE_TICKET)
            .withClaim(WEB_JWT_EPOCH_CLAIM, currentEpoch(config))
            .withClaim(WEB_JWT_SCOPE_CLAIM, scope.serialName)
            .withClaim(WEB_JWT_TARGET_CLAIM, target)
            .withClaim(WEB_JWT_SESSION_CLAIM, sessionJti)
            .withJWTId(Uuid.random().toString())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(expiresAt))
            .sign(algorithm())
        return token to expiresAt
    }

    /**
     * Validate + single-use consume a ticket for [scope]/[targetPath].
     * Any failure → null (caller treats as unauthenticated). Consumed jtis are
     * remembered so replays always fail, even within the 90s window.
     */
    fun consumeTicket(
        rawToken: String,
        config: WebAuthConfig,
        scope: WebTicketScope,
        targetPath: String,
    ): Boolean {
        val jwt = runCatching {
            sessionVerifier().verify(rawToken)
        }.getOrNull() ?: return false
        if (jwt.getClaim(WEB_JWT_TYPE_CLAIM).asString() != WEB_JWT_TYPE_TICKET) return false
        if (jwt.getClaim(WEB_JWT_EPOCH_CLAIM).asString() != currentEpoch(config)) return false
        if (jwt.getClaim(WEB_JWT_SCOPE_CLAIM).asString() != scope.serialName) return false
        if (jwt.getClaim(WEB_JWT_TARGET_CLAIM).asString() != targetPath) return false
        val sessionJti = jwt.getClaim(WEB_JWT_SESSION_CLAIM).asString() ?: return false
        if (revokedSessionJtis.contains(sessionJti)) return false
        val jti = jwt.id ?: return false
        return consumedTicketJtis.addIfAbsent(jti, jwt.expiresAt?.time ?: return false)
    }

    /**
     * Admission helper for the auth provider: a `?ticket=` credential is only
     * valid on a WebSocket upgrade (the `ws` scope) whose request path exactly
     * matches the ticket target.
     */
    fun isWsTicketAdmission(call: ApplicationCall, ticket: String, config: WebAuthConfig): Boolean {
        val upgrade = call.request.headers[io.ktor.http.HttpHeaders.Upgrade]
            ?: call.request.headers["upgrade"]
        if (upgrade == null || !upgrade.equals("websocket", ignoreCase = true)) return false
        if (call.request.httpMethod != io.ktor.http.HttpMethod.Get) return false
        return consumeTicket(
            rawToken = ticket,
            config = config,
            scope = WebTicketScope.WS,
            targetPath = call.request.path(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Capacity exhaustion rejects new admission; unexpired replay records are never evicted. */
private class ExpiringStringSet(private val max: Int, private val nowMillis: () -> Long) {
    private val entries = HashMap<String, Long>()

    @Synchronized
    fun addIfAbsent(value: String, expiresAt: Long): Boolean {
        val now = nowMillis()
        entries.entries.removeIf { it.value <= now }
        if (entries.containsKey(value) || entries.size >= max || expiresAt <= now) return false
        entries[value] = expiresAt
        return true
    }

    @Synchronized
    fun contains(value: String): Boolean {
        val expiresAt = entries[value] ?: return false
        if (expiresAt <= nowMillis()) { entries.remove(value); return false }
        return true
    }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Bounded sliding-window limiter for `POST /api/auth/token`.
 * Two windows: per remote host (failed attempts — a working password is not
 * punished after success) and global attempts (all calls) so distributed
 * guessing is still bounded.
 */
class WebAuthRateLimiter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    val hostFailureLimit: Int = 10,
    val hostFailureWindowMillis: Long = 10L * 60 * 1000,
    val globalAttemptLimit: Int = 60,
    val globalAttemptWindowMillis: Long = 60L * 1000,
    private val maxTrackedHosts: Int = 2048,
) {
    private val globalAttempts = ConcurrentLinkedDeque<Long>()
    private val hostFailures = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    /**
     * Gate BEFORE processing the request body. Throws [WebRateLimitedException]
     * with a Retry-After hint when either window is saturated.
     */
    fun checkAttempt(remoteHost: String) {
        val now = nowMillis()
        prune(globalAttempts, now, globalAttemptWindowMillis)
        if (globalAttempts.size >= globalAttemptLimit) {
            throw WebRateLimitedException(retryAfterSeconds(globalAttempts, globalAttemptWindowMillis))
        }
        globalAttempts.addLast(now)

        val failures = hostFailures.getOrPut(remoteHost) { ConcurrentLinkedDeque() }
        synchronized(failures) {
            prune(failures, now, hostFailureWindowMillis)
            if (failures.size >= hostFailureLimit) {
                throw WebRateLimitedException(retryAfterSeconds(failures, hostFailureWindowMillis))
            }
        }
        if (hostFailures.size > maxTrackedHosts) {
            // DoS bound: drop stale hosts (oldest-last-seen order is approximated
            // by pruning empty windows first, then arbitrary eviction).
            hostFailures.entries.removeIf { it.value.isEmpty() }
            if (hostFailures.size > maxTrackedHosts) {
                hostFailures.keys.asSequence().take(hostFailures.size - maxTrackedHosts)
                    .forEach { hostFailures.remove(it) }
            }
        }
    }

    fun recordFailure(remoteHost: String) {
        val failures = hostFailures.getOrPut(remoteHost) { ConcurrentLinkedDeque() }
        synchronized(failures) {
            failures.addLast(nowMillis())
        }
    }

    /** A successful exchange clears the per-host failure window. */
    fun recordSuccess(remoteHost: String) {
        hostFailures.remove(remoteHost)
    }

    private fun prune(deque: ConcurrentLinkedDeque<Long>, now: Long, window: Long) {
        while (true) {
            val head = deque.peekFirst() ?: break
            if (now - head < window) break
            deque.pollFirst()
        }
    }

    private fun retryAfterSeconds(deque: ConcurrentLinkedDeque<Long>, window: Long): Long {
        val now = nowMillis()
        val oldest = deque.peekFirst() ?: now
        return ((window - (now - oldest)).coerceAtLeast(1_000L) + 999L) / 1000L
    }
}

private val WEB_JWT_ISSUER = "rikkihub-web"
private val WEB_JWT_AUDIENCE = "rikkihub-web-client"
private val WEB_JWT_SUBJECT = "web-access"

internal fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
