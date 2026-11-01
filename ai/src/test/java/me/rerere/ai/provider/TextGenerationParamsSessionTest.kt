package me.rerere.ai.provider

import me.rerere.ai.util.configureSessionHeaders
import okhttp3.Request
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import me.rerere.ai.util.toHeaders
import kotlin.uuid.Uuid

/**
 * 非聊天/无 conversationId 文本请求的 session 身份。
 * 断言落在生产原语上：TextGenerationParams 默认身份、copy/显式 null 语义、
 * 以及真实的 configureSessionHeaders 请求头构建（不重复其内部算法）。
 */
class TextGenerationParamsSessionTest {

    private fun model() = Model(modelId = "m")

    private fun headers(sessionId: String?, url: String = "https://api.example.com/v1/chat"): okhttp3.Headers =
        Request.Builder()
            .url(url)
            .configureSessionHeaders(url, sessionId)
            .build()
            .headers

    @Test
    fun `default sessionId is generated once and non-null`() {
        val params = TextGenerationParams(model = model())
        assertNotNull(params.sessionId)
        // 合法 UUID 身份
        assertTrue(Uuid.parse(params.sessionId!!).toString() == params.sessionId)
    }

    @Test
    fun `different params instances get different default identities`() {
        val a = TextGenerationParams(model = model())
        val b = TextGenerationParams(model = model())
        assertTrue(a.sessionId != b.sessionId)
    }

    @Test
    fun `default identity is stable across params copy used by retries`() {
        val params = TextGenerationParams(model = model())
        val retried = params.copy(maxTokens = 1)
        assertEquals(params.sessionId, retried.sessionId)
    }

    @Test
    fun `explicit null sessionId stays null and emits no session headers`() {
        val params = TextGenerationParams(model = model(), sessionId = null)
        assertNull(params.sessionId)
        val h = headers(params.sessionId)
        assertNull(h["X-Session-ID"])
        val opencode = headers(params.sessionId, "https://opencode.ai/v1/chat")
        assertNull(opencode["X-Session-ID"])
        assertNull(opencode["x-opencode-session"])
    }

    @Test
    fun `explicit sessionId produces X-Session-ID header`() {
        val conversationId = Uuid.random().toString()
        val h = headers(conversationId)
        assertEquals(conversationId, h["X-Session-ID"])
    }

    @Test fun `manual standalone session headers are not replaced by default identity`() {
        val params = TextGenerationParams(model(), customHeaders = listOf(
            CustomHeader("x-session-id", "manual"), CustomHeader("x-opencode-session", "separate"),
        ))
        assertNull(params.sessionId)
        val request = Request.Builder().url("https://opencode.ai/v1/chat")
            .headers(params.customHeaders.toHeaders())
            .configureSessionHeaders("https://opencode.ai/v1/chat", params.sessionId).build()
        assertEquals("manual", request.header("X-Session-ID"))
        assertEquals("separate", request.header("x-opencode-session"))
        assertNull(params.copy(maxTokens = 1).sessionId)
    }

    @Test fun `explicit conversation id keeps its original priority over manual session headers`() {
        val params = TextGenerationParams(model(), sessionId = "conversation", customHeaders = listOf(
            CustomHeader("X-Session-ID", "manual"), CustomHeader("x-opencode-session", "manual-code"),
        ))
        val request = Request.Builder().url("https://opencode.ai/v1/chat")
            .headers(params.customHeaders.toHeaders())
            .configureSessionHeaders("https://opencode.ai/v1/chat", params.sessionId).build()
        assertEquals("conversation", request.header("X-Session-ID"))
        assertEquals("conversation", request.header("x-opencode-session"))
    }

    @Test fun `unrelated headers do not suppress default identity`() {
        val params = TextGenerationParams(model(), customHeaders = listOf(CustomHeader("X-Trace", "a")))
        assertNotNull(params.sessionId)
    }

    @Test fun `serialized explicit session identity survives decode`() {
        val params = TextGenerationParams(model(), sessionId = "saved-session")
        val codec = Json { encodeDefaults = true }
        val restored = codec.decodeFromString<TextGenerationParams>(codec.encodeToString(params))
        assertEquals("saved-session", restored.sessionId)
    }

    @Test
    fun `opencode host also receives x-opencode-session`() {
        val h = headers("s-1", "https://opencode.ai/v1/chat")
        assertEquals("s-1", h["X-Session-ID"])
        assertEquals("s-1", h["x-opencode-session"])
        // 非 opencode 主机不带额外头
        val other = headers("s-1", "https://api.anthropic.com/v1/messages")
        assertEquals("s-1", other["X-Session-ID"])
        assertNull(other["x-opencode-session"])
    }
}
