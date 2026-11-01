package me.rerere.ai.util

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpFailureTest {
    private fun response(code: Int, retryAfter: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/v1/chat").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .apply { retryAfter?.let { header("Retry-After", it) } }
        .build()

    @Test
    fun `parsed error keeps its message and gains status`() {
        val parsed = HttpException("rate limited by upstream")
        val failure = httpFailure(parsed, response(429, "7")) as HttpException
        assertEquals("rate limited by upstream", failure.message)
        assertEquals(429, failure.statusCode)
        assertEquals(7_000L, failure.retryAfterMillis)
    }

    @Test
    fun `missing error becomes an http status error instead of a silent end`() {
        val failure = httpFailure(null, response(503), bodyRaw = "") as HttpException
        assertEquals("HTTP 503", failure.message)
        assertEquals(503, failure.statusCode)
        assertNull(failure.retryAfterMillis)
        val withBody = httpFailure(null, response(502), bodyRaw = "<html>bad gateway</html>") as HttpException
        assertTrue(withBody.message!!.startsWith("HTTP 502: <html>"))
    }

    @Test
    fun `other errors are wrapped with status, network errors and success pass through`() {
        val parse = IllegalArgumentException("not json")
        val wrapped = httpFailure(parse, response(500)) as HttpException
        assertEquals("not json", wrapped.message)
        assertEquals(500, wrapped.statusCode)
        assertSame(parse, wrapped.cause)

        val network = IOException("reset")
        assertSame(network, httpFailure(network, response(500)))
        assertSame(network, httpFailure(network, null))
        assertSame(parse, httpFailure(parse, response(200)))
        assertNull(httpFailure(null, null))
    }

    @Test
    fun `retry after accepts seconds and http dates`() {
        assertEquals(0L, parseRetryAfterMillis("0"))
        assertEquals(120_000L, parseRetryAfterMillis(" 120 "))
        assertNull(parseRetryAfterMillis(null))
        assertNull(parseRetryAfterMillis("soon"))
        val now = java.time.ZonedDateTime.parse("Sat, 10 Oct 2026 08:00:00 GMT",
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        assertEquals(30_000L, parseRetryAfterMillis("Sat, 10 Oct 2026 08:00:30 GMT", nowMillis = now))
        assertEquals(0L, parseRetryAfterMillis("Sat, 10 Oct 2026 07:59:00 GMT", nowMillis = now))
    }
}
