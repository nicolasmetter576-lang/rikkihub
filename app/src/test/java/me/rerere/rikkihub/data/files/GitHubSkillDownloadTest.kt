package me.rerere.rikkihub.data.files

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 回归：GitHub 技能下载按字节保存、限流有准确反馈。
 * 离线：只连本机 loopback HttpServer。
 */
class GitHubSkillDownloadTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `rate limit detection 429 alone or 403 with remaining zero`() {
        // 429 本身就是限流，不要求限流头
        assertTrue(isGitHubRateLimit(429, null))
        assertTrue(isGitHubRateLimit(429, "5"))
        // 403 仅在 X-RateLimit-Remaining 归零（可 trim）时判限流
        assertTrue(isGitHubRateLimit(403, "0"))
        assertTrue(isGitHubRateLimit(403, " 0 "))
        assertFalse(isGitHubRateLimit(403, null))
        assertFalse(isGitHubRateLimit(403, "5"))
        // 其他状态码不误判
        assertFalse(isGitHubRateLimit(401, "0"))
        assertFalse(isGitHubRateLimit(404, "0"))
        assertFalse(isGitHubRateLimit(200, "0"))
    }

    @Test
    fun `download preserves binary bytes verbatim`() {
        // 含非法 UTF-8 序列，按文本下载必然损坏，必须按字节返回
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x01, 0xC3.toByte(), 0x28,
        )
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }

        val bytes = downloadGitHubBytes("$baseUrl/file")

        assertNotNull(bytes)
        assertTrue(png.contentEquals(bytes))
    }

    @Test
    fun `forbidden with exhausted quota throws rate limit exception`() {
        server.createContext("/api") { exchange ->
            exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }

        try {
            downloadGitHubBytes("$baseUrl/api")
            fail("rate limit should throw GitHubRateLimitException")
        } catch (expected: GitHubRateLimitException) {
            // expected: UI 层据此映射到 skills_github_rate_limit 文案
        }
    }

    @Test
    fun `too many requests without header throws rate limit exception`() {
        server.createContext("/api") { exchange ->
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }

        try {
            downloadGitHubBytes("$baseUrl/api")
            fail("429 should throw GitHubRateLimitException")
        } catch (expected: GitHubRateLimitException) {
            // expected
        }
    }

    @Test
    fun `plain forbidden and not found return null without rate limit noise`() {
        server.createContext("/forbidden") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        assertNull(downloadGitHubBytes("$baseUrl/forbidden"))
        assertNull(downloadGitHubBytes("$baseUrl/missing"))
    }
}
