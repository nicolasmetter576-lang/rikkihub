package me.rerere.rikkihub.data.ai

import okhttp3.Headers.Companion.headersOf
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * auth material must never reach the request log surface.
 */
class RequestLoggingRedactionTest {
    @Test
    fun `token form is skipped before capture but still sent once`() {
        val enabled = me.rerere.common.android.Logging.isRequestLoggingEnabled()
        me.rerere.common.android.Logging.setRequestLoggingEnabled(true)
        var writes = 0
        val secret = "synthetic-jwt-" + "x".repeat(5000)
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: okio.BufferedSink) { writes++; sink.writeUtf8(secret) }
        }
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(RequestLoggingInterceptor())
            .addInterceptor { chain ->
                assertEquals(0, writes)
                val sent = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                assertEquals(secret, sent)
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .header("Set-Cookie", "synthetic-cookie")
                    .body("{}".toResponseBody()).build()
            }.build()
        try {
            client.newCall(okhttp3.Request.Builder()
                .url("https://oauth2.googleapis.com/token?key=synthetic-key")
                .header("Authorization", "Bearer synthetic-token").post(body).build()).execute().close()
            assertEquals(1, writes)
            val log = me.rerere.common.android.Logging.getRequestLogs().first()
            assertEquals("[token exchange body not captured]", log.requestBody)
            assertEquals("██", log.requestHeaders["Authorization"])
            assertEquals("██", log.responseHeaders["Set-Cookie"])
            assertFalse(log.toString().contains("synthetic-"))
        } finally {
            me.rerere.common.android.Logging.setRequestLoggingEnabled(enabled)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `HTTP header logger masks both header directions and URL credentials`() {
        val lines = mutableListOf<String>()
        val logger = okhttp3.logging.HttpLoggingInterceptor { lines += it }.apply {
            SENSITIVE_HEADER_NAMES.forEach(::redactHeader)
            redactQueryParams(*SENSITIVE_QUERY_PARAMS.toTypedArray())
            level = okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS
        }
        val client = okhttp3.OkHttpClient.Builder().addInterceptor(logger).addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").header("Set-Cookie", "synthetic-response-cookie")
                .body("{}".toResponseBody()).build()
        }.build()
        try {
            val request = okhttp3.Request.Builder().url("https://example.invalid/token?key=synthetic-key&access_token=synthetic-access&api_key=synthetic-api&visible=yes")
            for (name in SENSITIVE_HEADER_NAMES) request.header(name, "synthetic-header-$name")
            client.newCall(request.build()).execute().close()
            val output = lines.joinToString("\n")
            assertFalse(output, output.contains("synthetic-"))
            assertTrue(output.contains("visible=yes"))
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

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
