package me.rerere.rikkihub.data.ai.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 兼容契约：
 * - 手工配置的 Authorization 请求头始终优先，绝不触发 OAuth 授权流程（即便已启用 OAuth）。
 * - 首次 401（或等价 token 错误）无需已有 OAuth 状态、无需 RFC 9728 元数据即要求授权，
 *   且不发送探测请求。
 * - 其他错误回退到 protected-resource metadata 探测：探测成功要求授权，失败则视为普通错误。
 * - 取消必须向上传播，不能被吞掉后继续探测。
 */
class McpOAuthCompatibilityTest {

    private fun server(
        headers: List<Pair<String, String>> = emptyList(),
        oauth: McpOAuthState? = null,
    ): McpServerConfig = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo", headers = headers, oauth = oauth),
        url = "https://mcp.example.com/mcp",
    )

    @Test
    fun `manual authorization header wins even when oauth enabled`() = runBlocking {
        var probed = false
        val config = server(
            headers = listOf("Authorization" to "Bearer manual"),
            oauth = McpOAuthState(enabled = true),
        )

        val needed = needsMcpAuthorization(config, RuntimeException("HTTP 401 Unauthorized")) {
            probed = true
        }

        assertFalse(needed)
        assertFalse(probed)
    }

    @Test
    fun `authorization header match is case insensitive`() = runBlocking {
        var probed = false
        val config = server(headers = listOf("authorization" to "Bearer manual"))

        val needed = needsMcpAuthorization(config, RuntimeException("401")) { probed = true }

        assertFalse(needed)
        assertFalse(probed)
    }

    @Test
    fun `first 401 requires authorization without oauth state or metadata`() = runBlocking {
        var probed = false
        val config = server() // 无 headers、无 oauth 状态

        val needed = needsMcpAuthorization(config, RuntimeException("Server returned 401")) {
            probed = true
        }

        assertTrue(needed)
        assertFalse(probed)
    }

    @Test
    fun `unauthorized and token error messages require authorization`() = runBlocking {
        val config = server()
        val messages = listOf(
            "unauthorized",
            "invalid_token",
            "invalid access token",
            "missing or invalid token",
        )
        for (message in messages) {
            var probed = false
            val needed = needsMcpAuthorization(config, RuntimeException(message)) { probed = true }
            assertTrue("expected authorization for: $message", needed)
            assertFalse("probe must not run for: $message", probed)
        }
    }

    @Test
    fun `401 nested in cause chain is detected`() = runBlocking {
        val config = server()
        val error = RuntimeException("connection failed", IllegalStateException("http status 401"))

        assertTrue(needsMcpAuthorization(config, error) { fail("probe must not run") })
    }

    @Test
    fun `other errors probe protected resource metadata and authorize when it exists`() = runBlocking {
        var probed = false
        val needed = needsMcpAuthorization(server(), RuntimeException("connection reset")) {
            probed = true // 模拟 RFC 9728 元数据探测成功
        }

        assertTrue(needed)
        assertTrue(probed)
    }

    @Test
    fun `non auth error is not authorization when probe fails`() = runBlocking {
        val needed = needsMcpAuthorization(server(), RuntimeException("connect timed out")) {
            throw java.io.IOException("no protected resource metadata")
        }

        assertFalse(needed)
    }

    @Test
    fun `cancellation propagates instead of probing`() = runBlocking {
        var probed = false
        try {
            needsMcpAuthorization(server(), CancellationException("job cancelled")) { probed = true }
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("job cancelled", e.message)
        }
        assertFalse(probed)
    }

    @Test
    fun `cancellation inside probe propagates`() = runBlocking {
        try {
            needsMcpAuthorization(server(), RuntimeException("connection reset")) {
                throw CancellationException("probe cancelled")
            }
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("probe cancelled", e.message)
        }
    }

    @Test
    fun `new client uses localhost callback without mutating stored state`() {
        assertEquals(MCP_OAUTH_REDIRECT_URI, mcpOAuthRedirectUri(null, MCP_OAUTH_REDIRECT_URI))
        val state = McpOAuthState(redirectUri = MCP_OAUTH_LEGACY_REDIRECT_URI)
        assertEquals(MCP_OAUTH_REDIRECT_URI, mcpOAuthRedirectUri(state, MCP_OAUTH_REDIRECT_URI))
        assertEquals(MCP_OAUTH_LEGACY_REDIRECT_URI, state.redirectUri)
    }

    @Test
    fun `previously registered ipv4 client keeps its matching redirect uri`() {
        val state = McpOAuthState(clientId = "legacy-client", redirectUri = MCP_OAUTH_LEGACY_REDIRECT_URI)
        assertEquals(MCP_OAUTH_LEGACY_REDIRECT_URI, mcpOAuthRedirectUri(state, MCP_OAUTH_REDIRECT_URI))
    }

    @Test
    fun `external or mismatched stored redirect uri is not reused`() {
        for (uri in listOf("https://example.com/oauth/callback", "http://127.0.0.1:9999/oauth/callback")) {
            val state = McpOAuthState(clientId = "client", redirectUri = uri)
            assertEquals(MCP_OAUTH_REDIRECT_URI, mcpOAuthRedirectUri(state, MCP_OAUTH_REDIRECT_URI))
        }
    }

    @Test
    fun `callback listener uri mismatch fails closed`() {
        try {
            mcpOAuthRedirectUri(null, "http://localhost:9999/oauth/callback")
            fail("mismatched listener must be rejected")
        } catch (_: IllegalStateException) {
            // No authorization URL may be built for a callback that our listener cannot receive.
        }
    }

    @Test
    fun `number containing 401 is not an http unauthorized status`() = runBlocking {
        var probed = false
        assertFalse(needsMcpAuthorization(server(), RuntimeException("error 40123")) {
            probed = true
            throw java.io.IOException("no metadata")
        })
        assertTrue(probed)
    }

    @Test
    fun `mcp oauth redirect uri uses localhost for WAF compatibility`() {
        // 部分授权服务器/WAF 会拦截 127.0.0.1 回调，对外公布 localhost，
        // 监听仍绑定 IPv4 回环（见 OAuthLoopbackCallbackServerTest）。
        assertEquals("http://localhost:52134/oauth/callback", MCP_OAUTH_REDIRECT_URI)
    }
}
