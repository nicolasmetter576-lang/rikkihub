package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextLengthErrorTest {
    private fun parsed(body: String): HttpException =
        Json.parseToJsonElement(body).let { it as JsonElement }.parseErrorDetail()

    @Test
    fun `provider overflow bodies are recognised after error parsing`() {
        val bodies = mapOf(
            "openai" to """{"error":{"message":"This model's maximum context length is 128000 tokens. However, your messages resulted in 130512 tokens.","type":"invalid_request_error","code":"context_length_exceeded"}}""",
            "anthropic" to """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 215034 tokens > 200000 maximum"}}""",
            "google" to """{"error":{"code":400,"message":"The input token count (1105426) exceeds the maximum number of tokens allowed (1048576).","status":"INVALID_ARGUMENT"}}""",
            "deepseek" to """{"error":{"message":"This model's maximum context length is 65536 tokens. However, you requested 70210 tokens. Please reduce the length of the messages.","type":"invalid_request_error"}}""",
        )
        val missed = bodies.filterValues { !parsed(it).isContextLengthError() }.keys
        assertTrue("not recognised: $missed", missed.isEmpty())
    }

    @Test
    fun `wrapped overflow is found through the cause chain`() {
        val inner = HttpException("prompt is too long: 9 tokens > 8 maximum", statusCode = 400)
        assertTrue(IllegalStateException("generation failed", inner).isContextLengthError())
    }

    @Test
    fun `output limit and rate limit errors are not overflow`() {
        val notOverflow = listOf(
            HttpException("max_tokens: 200000 > 128000, which is the maximum allowed number of output tokens", statusCode = 400),
            HttpException("Rate limit reached: too many tokens per minute", statusCode = 429),
            HttpException("Invalid API key", statusCode = 401),
            RuntimeException(null as String?),
        )
        val wrong = notOverflow.filter { it.isContextLengthError() }.map { it.message }
        assertTrue("misclassified: $wrong", wrong.isEmpty())
        assertFalse(java.io.IOException("timeout").isContextLengthError())
    }
}
