package me.rerere.ai.provider

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预设带来的采样参数：没设就一个键都不多发；设了各协议只发自己认的。 */
class SamplingParamsRequestTest {
    private val messages = listOf(UIMessage.user("hello"))
    private val model = Model(modelId = "test-model")
    private val full = TextGenerationParams(
        model = model, temperature = 0.7f, topP = 0.9f, topK = 40, frequencyPenalty = 0.3f, presencePenalty = 0.2f,
        repetitionPenalty = 1.1f, minP = 0.05f, topA = 0.2f, seed = 42L,
    )
    private val plain = TextGenerationParams(model = model, temperature = 0.7f, topP = 0.9f)

    private val chat = """{"id":"c","model":"test-model","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
    private val google = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
    private val claude = """{"id":"m","model":"test-model","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""

    private class Capture(private val reply: String) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        fun body(): JsonObject = json.parseToJsonElement(Buffer().also { requireNotNull(requests.last().body).writeTo(it) }.readUtf8()).jsonObject
    }

    private fun openAiBody(p: TextGenerationParams) = Capture(chat).use { c ->
        runBlocking { withTimeout(10_000) { OpenAIProvider(c.client).generateText(ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1", apiKey = "k"), messages, p) } }
        c.body()
    }

    private fun claudeBody(p: TextGenerationParams) = Capture(claude).use { c ->
        runBlocking { withTimeout(10_000) { ClaudeProvider(c.client).generateText(ProviderSetting.Claude(baseUrl = "https://offline.invalid/v1", apiKey = "k"), messages, p) } }
        c.body()
    }

    private fun googleBody(p: TextGenerationParams) = Capture(google).use { c ->
        runBlocking { withTimeout(10_000) { GoogleProvider(c.client).generateText(ProviderSetting.Google(apiKey = "k", baseUrl = "https://offline.invalid/v1beta", vertexAI = false), messages, p) } }
        c.body()
    }

    @Test fun `OpenAI compatible requests carry every set sampling parameter and nothing extra when unset`() {
        val body = openAiBody(full)
        assertEquals("40", body["top_k"]!!.jsonPrimitive.content)
        assertEquals(0.3f, body["frequency_penalty"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals(0.2f, body["presence_penalty"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals(1.1f, body["repetition_penalty"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals(0.05f, body["min_p"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals(0.2f, body["top_a"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals("42", body["seed"]!!.jsonPrimitive.content)
        val unset = openAiBody(plain)
        for (k in listOf("top_k", "frequency_penalty", "presence_penalty", "repetition_penalty", "min_p", "top_a", "seed")) assertFalse(k, k in unset)
        assertEquals("0.9", unset["top_p"]!!.jsonPrimitive.content)
    }

    @Test fun `Claude only gets top_k and not while thinking is on`() {
        val body = claudeBody(full)
        assertEquals("40", body["top_k"]!!.jsonPrimitive.content)
        for (k in listOf("frequency_penalty", "presence_penalty", "repetition_penalty", "min_p", "top_a", "seed")) assertFalse(k, k in body)
        assertFalse("top_k" in claudeBody(plain))
        assertFalse("top_k" in claudeBody(full.copy(reasoningLevel = me.rerere.ai.core.ReasoningLevel.HIGH)))
    }

    @Test fun `Gemini gets topK, the two penalties and the seed inside generationConfig`() {
        val config = googleBody(full)["generationConfig"]!!.jsonObject
        assertEquals("40", config["topK"]!!.jsonPrimitive.content)
        assertEquals(0.3f, config["frequencyPenalty"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals(0.2f, config["presencePenalty"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals("42", config["seed"]!!.jsonPrimitive.content)
        for (k in listOf("repetitionPenalty", "minP", "topA")) assertFalse(k, k in config)
        val none = googleBody(plain)["generationConfig"]!!.jsonObject
        for (k in listOf("topK", "frequencyPenalty", "presencePenalty", "seed")) assertFalse(k, k in none)
        assertTrue("topP" in none)
    }
}
