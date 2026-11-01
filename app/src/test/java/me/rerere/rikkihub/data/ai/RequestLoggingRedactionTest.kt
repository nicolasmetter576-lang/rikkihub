package me.rerere.rikkihub.data.ai

import okhttp3.Headers.Companion.headersOf
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * auth material must never reach the request log surface.
 */
class RequestLoggingRedactionTest {

    @Test
    fun `credential headers are masked case insensitively`() {
        val headers = headersOf(
            "Authorization", "Bearer hunter2-token",
            "X-Api-Key", "sk-live",
            "Cookie", "session=abc",
            "Content-Type", "application/json",
            "X-Custom", "visible",
        )
        val map = headers.toRedactedMap()
        assertEquals("██", map["Authorization"])
        assertEquals("██", map["X-Api-Key"])
        assertEquals("██", map["Cookie"])
        assertEquals("application/json", map["Content-Type"])
        assertEquals("visible", map["X-Custom"])
    }

    @Test
    fun `response auth headers are masked too`() {
        val headers = headersOf("Set-Cookie", "sid=xyz", "ETag", "\"1\"")
        val map = headers.toRedactedMap()
        assertEquals("██", map["Set-Cookie"])
        assertEquals("\"1\"", map["ETag"])
    }

    @Test
    fun `url query credentials are masked`() {
        assertEquals(
            "https://a/b?access_token=██&x=1",
            redactLoggedUrl("https://a/b?access_token=hunter2&x=1"),
        )
        assertEquals(
            "https://a/b?x=1&key=██#frag",
            redactLoggedUrl("https://a/b?x=1&key=secret#frag"),
        )
        assertEquals("https://a/b?x=1", redactLoggedUrl("https://a/b?x=1"))
        assertEquals("no-query", redactLoggedUrl("no-query"))
    }

    @Test
    fun `logged body keeps only the head of large bodies`() {
        val big = "a".repeat(10_000).toRequestBody()
        val logged = loggedRequestBody(big, 4096)
        assertTrue(logged.endsWith("…[truncated]"))
        assertEquals(4096, logged.removeSuffix("…[truncated]").length)
        assertEquals("{\"x\":1}", loggedRequestBody("{\"x\":1}".toRequestBody(), 4096))
    }

    @Test
    fun `one shot bodies are never consumed by logging`() {
        var reads = 0
        val oneShot = object : okhttp3.RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) { reads++ }
        }
        assertEquals("[streaming body not captured]", loggedRequestBody(oneShot, 4096))
        assertEquals(0, reads)
    }
}
