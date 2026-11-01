package me.rerere.rikkihub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页"添加请求头"后未填写名称会留下空白条目，
 * 原样传给 Ktor/OkHttp 会抛 "name is empty"。resolvedHeaders 必须忽略空白名称。
 */
class McpBlankHeaderTest {
    private fun httpServer(headers: List<Pair<String, String>>) = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo", headers = headers),
        url = "https://example.com/mcp",
    )

    private fun sseServer(headers: List<Pair<String, String>>) = McpServerConfig.SseTransportServer(
        commonOptions = McpCommonOptions(name = "demo", headers = headers),
        url = "https://example.com/sse",
    )

    @Test
    fun `blank header names are dropped for streamable http`() {
        val config = httpServer(
            listOf(
                "" to "value",
                "   " to "other",
                "X-API-Key" to "secret",
            )
        )

        assertEquals(listOf("X-API-Key" to "secret"), config.connectionKey().headers)
    }

    @Test
    fun `blank header names are dropped for sse`() {
        val config = sseServer(
            listOf(
                "" to "value",
                "\t" to "other",
                "X-API-Key" to "secret",
            )
        )

        assertEquals(listOf("X-API-Key" to "secret"), config.connectionKey().headers)
    }

    @Test
    fun `valid headers are kept unchanged and order preserved`() {
        val headers = listOf("X-API-Key" to "secret", "X-Trace" to "")
        assertEquals(headers, httpServer(headers).connectionKey().headers)
        assertEquals(headers, sseServer(headers).connectionKey().headers)
    }

    @Test
    fun `oauth bearer header is still appended after blank entries are removed`() {
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                name = "demo",
                headers = listOf(" " to "junk"),
                oauth = McpOAuthState(enabled = true, accessToken = "tok"),
            ),
            url = "https://example.com/mcp",
        )

        assertEquals(listOf("Authorization" to "Bearer tok"), config.connectionKey().headers)
    }

    @Test
    fun `manual authorization header still wins over oauth token`() {
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                name = "demo",
                headers = listOf("" to "junk", "Authorization" to "Bearer manual"),
                oauth = McpOAuthState(enabled = true, accessToken = "tok"),
            ),
            url = "https://example.com/mcp",
        )

        val headers = config.connectionKey().headers
        assertEquals(1, headers.size)
        assertEquals("Authorization" to "Bearer manual", headers.single())
        assertTrue(headers.none { it.first.isBlank() })
    }
}
