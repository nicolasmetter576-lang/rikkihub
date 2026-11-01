package me.rerere.rikkihub.web

import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the web auth/origin admission policy helpers.
 * Route-level coverage lives in WebApiBoundaryRouteTest.
 */
class WebApiPolicyTest {

    // ---- webAuthState ----

    @Test
    fun `auth state disabled when jwt off`() {
        assertEquals(WebAuthState.DISABLED, webAuthState(jwtEnabled = false, accessPassword = ""))
        assertEquals(WebAuthState.DISABLED, webAuthState(jwtEnabled = false, accessPassword = "secret"))
    }

    @Test
    fun `auth state password missing when jwt on and password blank`() {
        assertEquals(WebAuthState.PASSWORD_MISSING, webAuthState(jwtEnabled = true, accessPassword = ""))
        assertEquals(WebAuthState.PASSWORD_MISSING, webAuthState(jwtEnabled = true, accessPassword = "   "))
    }

    @Test
    fun `auth state active only when jwt on and password set`() {
        assertEquals(WebAuthState.ACTIVE, webAuthState(jwtEnabled = true, accessPassword = "secret"))
    }

    @Test
    fun `auth status dto carries stable state and no secrets`() {
        val dto = webAuthStatusDto(jwtEnabled = true, accessPassword = "hunter2")
        assertEquals(WebAuthState.ACTIVE, dto.authState)
        assertTrue(dto.jwtEnabled)
        assertTrue(dto.passwordConfigured)
        assertNull(dto.hint)

        val disabled = webAuthStatusDto(jwtEnabled = false, accessPassword = "")
        assertEquals(WebAuthState.DISABLED, disabled.authState)
        assertFalse(disabled.passwordConfigured)
        assertNull(disabled.hint)

        val missing = webAuthStatusDto(jwtEnabled = true, accessPassword = " ")
        assertEquals(WebAuthState.PASSWORD_MISSING, missing.authState)
        assertFalse(missing.passwordConfigured)
        assertFalse(missing.hint.isNullOrBlank())
    }

    // ---- public path list ----

    @Test
    fun `public paths limited to status health icon and token`() {
        assertTrue(isPublicWebApiPath("/api/health"))
        assertTrue(isPublicWebApiPath("/api/auth/status"))
        assertTrue(isPublicWebApiPath("/api/auth/token"))
        assertTrue(isPublicWebApiPath("/api/ai-icon"))

        assertFalse(isPublicWebApiPath("/api/settings"))
        assertFalse(isPublicWebApiPath("/api/conversations"))
        assertFalse(isPublicWebApiPath("/api/files/path/upload/x.png"))
        assertFalse(isPublicWebApiPath("/api/events"))
        // Lookalikes must not be treated as public.
        assertFalse(isPublicWebApiPath("/api/health/extra"))
        assertFalse(isPublicWebApiPath("/api/auth/statusx"))
        assertFalse(isPublicWebApiPath("/api/ai-icon-x"))
        assertFalse(isPublicWebApiPath("/api"))
        assertFalse(isPublicWebApiPath("/static/index.html"))
    }

    // ---- Origin checking ----

    @Test
    fun `no origin header is not denied by the origin check`() {
        assertNull(
            isSameOriginRequest(
                originHeader = null,
                serverScheme = "http",
                hostHeader = "localhost:8080",
                localPort = 8080,
            )
        )
    }

    @Test
    fun `same origin request passes`() {
        assertEquals(
            true,
            isSameOriginRequest(
                originHeader = "http://localhost:8080",
                serverScheme = "http",
                hostHeader = "localhost:8080",
                localPort = 8080,
            )
        )
        // Case-insensitive scheme/host.
        assertEquals(
            true,
            isSameOriginRequest(
                originHeader = "HTTP://LocalHost:8080",
                serverScheme = "http",
                hostHeader = "localhost:8080",
                localPort = 8080,
            )
        )
        // IPv6 literal.
        assertEquals(
            true,
            isSameOriginRequest(
                originHeader = "http://[::1]:8080",
                serverScheme = "http",
                hostHeader = "[::1]:8080",
                localPort = 8080,
            )
        )
        // Default-port omission is normalized.
        assertEquals(
            true,
            isSameOriginRequest(
                originHeader = "http://example.com",
                serverScheme = "http",
                hostHeader = "example.com",
                localPort = 80,
            )
        )
    }

    @Test
    fun `opaque null origin denied`() {
        assertEquals(
            false,
            isSameOriginRequest(
                originHeader = "null",
                serverScheme = "http",
                hostHeader = "localhost:8080",
                localPort = 8080,
            )
        )
    }

    @Test
    fun `cross origin denied for host port and scheme mismatches`() {
        val args = arrayOf(
            "http://evil.example.com:8080",           // different host
            "http://localhost:9090",                  // different port
            "https://localhost:8080",                 // different scheme
            "http://localhost.evil.com:8080",         // suffix host
            "http://localhost:8080.evil.com",         // port-looking host
            "file://localhost:8080",                  // non-http scheme
            "javascript:alert(1)",                    // nonsense
            "http://localhost:8080/path",             // path in origin
            "http://user@localhost:8080",             // userinfo
            "http://localhost:notaport",              // bad port
            "http://localhost:8080, http://a",        // combined header junk
            "",                                       // empty
        )
        for (origin in args) {
            assertEquals(
                "origin '$origin' must be denied",
                false,
                isSameOriginRequest(
                    originHeader = origin,
                    serverScheme = "http",
                    hostHeader = "localhost:8080",
                    localPort = 8080,
                )
            )
        }
    }

    @Test
    fun `malformed or missing host denies when origin present`() {
        assertEquals(
            false,
            isSameOriginRequest(
                originHeader = "http://localhost:8080",
                serverScheme = "http",
                hostHeader = "not a host",
                localPort = 8080,
            )
        )
        assertEquals(
            false,
            isSameOriginRequest(
                originHeader = "http://localhost:8080",
                serverScheme = "http",
                hostHeader = null,
                localPort = 8080,
            )
        )
    }

    // ---- Exceptions carry stable codes ----

    @Test
    fun `api exceptions expose stable machine codes`() {
        assertEquals(HttpStatusCode.Forbidden, WebAuthDisabledException().status)
        assertEquals(WEB_ERROR_AUTH_DISABLED, WebAuthDisabledException().errorCode)
        assertEquals(HttpStatusCode.Forbidden, WebAuthNotConfiguredException().status)
        assertEquals(WEB_ERROR_AUTH_NOT_CONFIGURED, WebAuthNotConfiguredException().errorCode)
        assertEquals(HttpStatusCode.Unauthorized, WebAuthInvalidCredentialsException().status)
        assertEquals(WEB_ERROR_INVALID_CREDENTIALS, WebAuthInvalidCredentialsException().errorCode)
        assertEquals(HttpStatusCode.Unauthorized, WebUnauthorizedException().status)
        assertEquals(WEB_ERROR_UNAUTHORIZED, WebUnauthorizedException().errorCode)
        assertEquals(HttpStatusCode.Forbidden, WebOriginDeniedException().status)
        assertEquals(WEB_ERROR_ORIGIN_DENIED, WebOriginDeniedException().errorCode)
        assertEquals(HttpStatusCode.Forbidden, WebFileForbiddenException().status)
        assertEquals(WEB_ERROR_FILE_FORBIDDEN, WebFileForbiddenException().errorCode)
        assertEquals(HttpStatusCode.Forbidden, WebFileSymlinkDeniedException().status)
        assertEquals(WEB_ERROR_FILE_SYMLINK_DENIED, WebFileSymlinkDeniedException().errorCode)
        assertEquals(HttpStatusCode.NotFound, WebFileNotFoundException().status)
        assertEquals(WEB_ERROR_FILE_NOT_FOUND, WebFileNotFoundException().errorCode)
    }
}
