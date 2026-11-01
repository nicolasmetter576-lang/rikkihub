package me.rerere.rikkihub.data.devin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevinJsonTest {
    @Test
    fun `formatDevinSessionToken keeps prefixed token`() {
        val token = "devin-session-token\$abc123"
        assertEquals(token, formatDevinSessionToken(token))
        assertEquals(token, formatDevinSessionToken("  $token  "))
    }

    @Test
    fun `formatDevinSessionToken prefixes JWT`() {
        assertEquals(
            "devin-session-token\$eyJhbGciOiJIUzI1NiJ9",
            formatDevinSessionToken("eyJhbGciOiJIUzI1NiJ9"),
        )
    }

    @Test
    fun `formatDevinSessionToken passes through opaque token`() {
        assertEquals("opaque-token", formatDevinSessionToken("opaque-token"))
    }

    @Test
    fun `parseDevinManualInput returns null for empty input`() {
        assertNull(parseDevinManualInput(""))
        assertNull(parseDevinManualInput("   "))
    }

    @Test
    fun `parseDevinManualInput detects session tokens`() {
        val prefixed = parseDevinManualInput("devin-session-token\$abc")
        assertTrue(prefixed is DevinManualInput.SessionToken)
        assertEquals("devin-session-token\$abc", (prefixed as DevinManualInput.SessionToken).token)

        val jwt = parseDevinManualInput("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig")
        assertTrue(jwt is DevinManualInput.SessionToken)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig", (jwt as DevinManualInput.SessionToken).token)
    }

    @Test
    fun `parseDevinManualInput parses callback URL`() {
        val parsed = parseDevinManualInput(
            "http://127.0.0.1:43210/callback?code=auth-code-1&state=deadbeef"
        )
        assertTrue(parsed is DevinManualInput.CallbackUrl)
        val url = parsed as DevinManualInput.CallbackUrl
        assertEquals("auth-code-1", url.code)
        assertEquals("deadbeef", url.state)
    }

    @Test
    fun `parseDevinManualInput parses encoded and fragment callback params`() {
        val encoded = parseDevinManualInput(
            "rikkihub://devin/oauth?state=s%40t&code=c%2Bd"
        )
        assertTrue(encoded is DevinManualInput.CallbackUrl)
        assertEquals("s@t", (encoded as DevinManualInput.CallbackUrl).state)
        assertEquals("c+d", encoded.code)

        val fragment = parseDevinManualInput("http://localhost/cb#code=x&state=y")
        assertTrue(fragment is DevinManualInput.CallbackUrl)
        assertEquals("x", (fragment as DevinManualInput.CallbackUrl).code)
        assertEquals("y", fragment.state)
    }

    @Test
    fun `parseDevinManualInput treats url missing state or code as bare code`() {
        val noState = parseDevinManualInput("http://127.0.0.1/callback?code=abc")
        assertTrue(noState is DevinManualInput.BareCode)

        val noCode = parseDevinManualInput("http://127.0.0.1/callback?state=abc")
        assertTrue(noCode is DevinManualInput.BareCode)
    }

    @Test
    fun `parseDevinManualInput treats other input as bare code`() {
        val parsed = parseDevinManualInput("plain-auth-code")
        assertTrue(parsed is DevinManualInput.BareCode)
        assertEquals("plain-auth-code", (parsed as DevinManualInput.BareCode).code)
    }
}
