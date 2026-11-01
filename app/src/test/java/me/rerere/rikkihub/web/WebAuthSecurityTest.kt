package me.rerere.rikkihub.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [WebAuthSecurity] (random secret / session epoch / single-use
 * tickets) and [WebAuthRateLimiter]. Route-level coverage (login / logout /
 * ticket admission / access_token removal) lives in WebApiBoundaryRouteTest.
 */
class WebAuthSecurityTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val active = WebAuthConfig(jwtEnabled = true, accessPassword = "s3cret")

    private fun newSecurity(now: AtomicLong = AtomicLong(1_000_000L)): WebAuthSecurity =
        WebAuthSecurity(tmp.newFolder("web-auth"), nowMillis = now::get)

    // ---- secret material ----

    @Test
    fun `secret is random persisted and independent of the password`() {
        val dir = tmp.newFolder("web-auth")
        val a = WebAuthSecurity(dir)
        val b = WebAuthSecurity(dir)
        assertArrayEquals("secret must persist across instances", a.secret(), b.secret())
        assertEquals(32, a.secret().size)

        val other = WebAuthSecurity(tmp.newFolder("other"))
        assertFalse("secrets must be random", a.secret().contentEquals(other.secret()))
    }

    // ---- sessions ----

    @Test
    fun `session verifies while config unchanged and dies on credential change`() {
        val security = newSecurity()
        val jwt = com.auth0.jwt.JWT.decode(security.issueSession(active).first)
        assertTrue(security.validateSessionJwt(jwt, active))

        // Password rotation → epoch flip → same token invalid.
        val rotated = active.copy(accessPassword = "rotated")
        assertFalse(security.validateSessionJwt(jwt, rotated))
        // Disabled → epoch flip as well.
        assertFalse(security.validateSessionJwt(jwt, active.copy(jwtEnabled = false)))
    }

    @Test
    fun `revoked session jti fails closed`() {
        val security = newSecurity()
        val jwt = com.auth0.jwt.JWT.decode(security.issueSession(active).first)
        assertTrue(security.validateSessionJwt(jwt, active))
        security.revokeSession(jwt.id)
        assertFalse(security.validateSessionJwt(jwt, active))
    }

    @Test
    fun `sessions last thirty days`() {
        val security = newSecurity(AtomicLong(1_000_000L))
        val (_, expiresAt) = security.issueSession(active, now = 1_000_000L)
        assertEquals(1_000_000L + 30L * 24 * 60 * 60 * 1000, expiresAt)
    }

    // ---- tickets ----

    @Test
    fun `ticket is bound to scope target session and consumed once`() {
        val security = newSecurity()
        val session = com.auth0.jwt.JWT.decode(security.issueSession(active).first)
        val (ticket, _) = security.issueTicket(
            config = active,
            sessionJti = session.id,
            scope = WebTicketScope.WS,
            target = "/api/workspaces/w1/terminal/sessions/s1",
        )

        // Wrong target → rejected, and NOT consumed (still usable at the right target).
        assertFalse(
            security.consumeTicket(ticket, active, WebTicketScope.WS, "/api/other")
        )
        // First consume at the bound target succeeds.
        assertTrue(
            security.consumeTicket(ticket, active, WebTicketScope.WS, "/api/workspaces/w1/terminal/sessions/s1")
        )
        // Replay always fails — tickets are strictly single-use.
        assertFalse(
            security.consumeTicket(ticket, active, WebTicketScope.WS, "/api/workspaces/w1/terminal/sessions/s1")
        )
    }

    @Test
    fun `ticket dies when the parent session is revoked or epoch flips`() {
        val security = newSecurity()
        val session = com.auth0.jwt.JWT.decode(security.issueSession(active).first)
        val target = "/api/workspaces/w/terminal/sessions/s"
        val (ticket1, _) = security.issueTicket(active, session.id, WebTicketScope.WS, target)
        security.revokeSession(session.id)
        assertFalse(security.consumeTicket(ticket1, active, WebTicketScope.WS, target))

        val (ticket2, _) = security.issueTicket(active, session.id, WebTicketScope.WS, target)
        val rotated = active.copy(accessPassword = "rotated")
        assertFalse(security.consumeTicket(ticket2, rotated, WebTicketScope.WS, target))
    }

    @Test
    fun `sessions survive an app restart but not a password change`() {
        val dir = tmp.newFolder("restart-auth")
        val before = WebAuthSecurity(dir)
        val jwt = com.auth0.jwt.JWT.decode(before.issueSession(active).first)
        assertTrue(before.validateSessionJwt(jwt, active))
        val restarted = WebAuthSecurity(dir)
        assertTrue(restarted.validateSessionJwt(jwt, active))
        assertFalse(restarted.validateSessionJwt(jwt, active.copy(accessPassword = "rotated")))
    }

    @Test
    fun `ticket cannot outlive its parent session`() {
        val now = AtomicLong(1_000_000L)
        val security = newSecurity(now)
        val session = com.auth0.jwt.JWT.decode(security.issueSession(active).first)
        val (ticket, expiry) = security.issueTicket(
            active, session.id, WebTicketScope.WS, "/api/terminal",
            sessionExpiresAtMillis = now.get() + 1_000,
        )
        assertEquals(now.get() + 1_000, expiry)
        now.addAndGet(1_001)
        assertFalse(security.consumeTicket(ticket, active, WebTicketScope.WS, "/api/terminal"))
    }

    // ---- rate limiter ----

    @Test
    fun `rate limiter trips per host after repeated failures`() {
        val now = AtomicLong(0L)
        val limiter = WebAuthRateLimiter(nowMillis = now::get, hostFailureLimit = 3, globalAttemptLimit = 100)
        repeat(3) {
            limiter.checkAttempt("10.0.0.1")
            limiter.recordFailure("10.0.0.1")
        }
        val e = runCatching { limiter.checkAttempt("10.0.0.1") }.exceptionOrNull()
        assertTrue(e is WebRateLimitedException)
        assertTrue((e as WebRateLimitedException).retryAfterSeconds > 0)
        // Other hosts unaffected.
        limiter.checkAttempt("10.0.0.2")
    }

    @Test
    fun `successful exchange resets the host failure window`() {
        val now = AtomicLong(0L)
        val limiter = WebAuthRateLimiter(nowMillis = now::get, hostFailureLimit = 2, globalAttemptLimit = 100)
        repeat(2) {
            limiter.checkAttempt("10.0.0.1")
            limiter.recordFailure("10.0.0.1")
        }
        limiter.recordSuccess("10.0.0.1")
        limiter.checkAttempt("10.0.0.1") // must not throw
    }

    @Test
    fun `global attempt window bounds distributed guessing`() {
        val now = AtomicLong(0L)
        val limiter = WebAuthRateLimiter(nowMillis = now::get, hostFailureLimit = 1000, globalAttemptLimit = 5)
        repeat(5) { i -> limiter.checkAttempt("10.0.0.$i") }
        val e = runCatching { limiter.checkAttempt("10.0.0.6") }.exceptionOrNull()
        assertTrue(e is WebRateLimitedException)
    }

    @Test
    fun `limiter windows expire`() {
        val now = AtomicLong(0L)
        val limiter = WebAuthRateLimiter(
            nowMillis = now::get,
            hostFailureLimit = 1,
            hostFailureWindowMillis = 60_000,
            globalAttemptLimit = 1000,
        )
        limiter.checkAttempt("10.0.0.1")
        limiter.recordFailure("10.0.0.1")
        assertTrue(runCatching { limiter.checkAttempt("10.0.0.1") }.exceptionOrNull() is WebRateLimitedException)
        now.addAndGet(61_000)
        limiter.checkAttempt("10.0.0.1") // window slid → allowed again
    }
}
