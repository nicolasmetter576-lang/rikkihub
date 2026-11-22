package me.rerere.ai.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.google.InteractionsAPI
import me.rerere.ai.provider.providers.google.InteractionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Real decoders/handlers and canned non-stream transports; never calls chain.proceed. */
class ProviderUsageNormalizationTest {
    private val model = Model(modelId = "offline-model")
    private val params = TextGenerationParams(model = model)
    private val input = listOf(UIMessage.user("test"))

    private class Stream(val decoder: StreamChunkDecoder) {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("test"))
        fun accept(data: String, event: String? = null): List<StreamChunk> {
            val chunks = decoder.accept(SseEvent(data = data, event = event)).chunks
            chunks.forEach { messages = handler.handle(messages, it) }
            if (chunks.any { it is StreamChunk.Usage }) assertEquals(handler.usage, messages.last().usage)
            return chunks
        }
    }

    private class Canned(body: String) : AutoCloseable {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("offline").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        override fun close() {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `Google preserves independent total and non-stream equals stream collector`() = runBlocking {
        val body = """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":10,"cachedContentTokenCount":30,"toolUsePromptTokenCount":50,"totalTokenCount":180}}"""
        val stream = Stream(GoogleStreamDecoder("r", "test"))
        stream.accept(body)
        assertEquals(TokenUsage(100, 30, 30, 180), stream.handler.usage)
        assertTrue(stream.handler.hasCompleteUsage)
        Canned(body).use { canned ->
            val result = GoogleProvider(canned.client).generateText(
                ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta"), input, params,
            )
            assertEquals(stream.handler.usage, result.usage)
            assertEquals(true, result.hasCompleteUsage)
        }
    }

    @Test fun `Google candidates and thoughts merge before summing and allow downward corrections`() {
        val stream = Stream(GoogleStreamDecoder("r", "test"))
        stream.accept("""{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":10,"totalTokenCount":180}}""")
        stream.accept("""{"usageMetadata":{"thoughtsTokenCount":5}}""")
        assertEquals(TokenUsage(100, 25, 0, 180), stream.handler.usage)
        stream.accept("""{"usageMetadata":{"candidatesTokenCount":0}}""")
        assertEquals(TokenUsage(100, 5, 0, 180), stream.handler.usage)
        stream.accept("""{"usageMetadata":{"promptTokenCount":0,"thoughtsTokenCount":0,"totalTokenCount":0}}""")
        assertEquals(TokenUsage(), stream.handler.usage)
        assertTrue(stream.handler.hasCompleteUsage)
    }

    @Test fun `thought only output remains partial until candidates are reported`() {
        val stream = Stream(GoogleStreamDecoder("r", "test"))
        stream.accept("""{"usageMetadata":{"promptTokenCount":17,"thoughtsTokenCount":1120,"totalTokenCount":1137}}""")
        assertEquals(1120, stream.handler.usage?.completionTokens)
        assertFalse(stream.handler.hasCompleteUsage)
        stream.accept("""{"usageMetadata":{"candidatesTokenCount":0}}""")
        assertTrue(stream.handler.hasCompleteUsage)
    }

    @Test fun `Claude input corrections preserve cached components and explicit cache zero clears`() = runBlocking {
        val stream = Stream(ClaudeStreamDecoder())
        stream.accept("""{"message":{"usage":{"input_tokens":100,"cache_read_input_tokens":500,"cache_creation_input_tokens":20,"output_tokens":1}}}""", "message_start")
        stream.accept("""{"usage":{"input_tokens":110,"output_tokens":10}}""", "message_delta")
        assertEquals(TokenUsage(630, 10, 500, 640), stream.handler.usage)
        assertTrue(stream.handler.hasCompleteUsage)
        val body = """{"id":"r","model":"test","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":110,"cache_read_input_tokens":500,"cache_creation_input_tokens":20,"output_tokens":10}}"""
        Canned(body).use { canned ->
            val result = ClaudeProvider(canned.client).generateText(
                ProviderSetting.Claude(baseUrl = "https://offline.invalid"), input, params,
            )
            assertEquals(stream.handler.usage, result.usage)
            assertEquals(true, result.hasCompleteUsage)
        }
        stream.accept("""{"usage":{"cache_read_input_tokens":0}}""", "message_delta")
        assertEquals(TokenUsage(130, 10, 0, 140), stream.handler.usage)
        stream.accept("""{"usage":{"output_tokens":0}}""", "message_delta")
        assertEquals(TokenUsage(130, 0, 0, 130), stream.handler.usage)
        assertTrue(stream.handler.hasCompleteUsage)
    }

    @Test fun `Claude cache-only report is a partial input and does not invent raw input`() {
        val stream = Stream(ClaudeStreamDecoder())
        stream.accept("""{"usage":{"cache_read_input_tokens":500,"output_tokens":0}}""", "message_delta")
        assertEquals(TokenUsage(500, 0, 500, 500), stream.handler.usage)
        assertFalse(stream.handler.hasCompleteUsage)
        stream.accept("""{"usage":{"input_tokens":0}}""", "message_delta")
        assertTrue(stream.handler.hasCompleteUsage)
    }

    @Test fun `OpenAI omits missing fields does not double count reasoning and shares non-stream normalization`() = runBlocking {
        val usage = """{"prompt_tokens":100,"completion_tokens":30,"completion_tokens_details":{"reasoning_tokens":10},"prompt_tokens_details":{"cached_tokens":20}}"""
        val stream = Stream(ChatCompletionsStreamDecoder())
        stream.accept("""{"choices":[],"usage":$usage}""")
        assertEquals(TokenUsage(100, 30, 20, 130), stream.handler.usage)
        assertTrue(stream.handler.hasCompleteUsage)
        val body = """{"id":"r","model":"test","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":$usage}"""
        Canned(body).use { canned ->
            val result = ChatCompletionsAPI(canned.client).generateText(
                ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1"), input, params,
            )
            assertEquals(stream.handler.usage, result.usage)
            assertEquals(true, result.hasCompleteUsage)
        }
        stream.accept("""{"choices":[],"usage":{"total_tokens":180}}""")
        stream.accept("""{"choices":[],"usage":{"completion_tokens":0,"prompt_tokens_details":{"cached_tokens":0}}}""")
        assertEquals(TokenUsage(100, 0, 0, 180), stream.handler.usage)
    }

    @Test fun `Responses null empty and total-only usage do not crash or claim completeness`() {
        val api = ResponseAPI(OkHttpClient())
        for (usage in listOf("null", "{}", "{\"input_tokens\":-1}")) {
            val result = api.parseResponseOutput(json.parseToJsonElement("""{"output":[],"usage":$usage}""").jsonObject)
            assertNull(result.usage)
            assertEquals(false, result.hasCompleteUsage)
            val stream = Stream(ResponseApiStreamDecoder())
            stream.accept("""{"type":"response.completed","response":{"output":[],"usage":$usage}}""", "response.completed")
            assertNull(stream.handler.usage)
            assertFalse(stream.handler.hasCompleteUsage)
        }
        val result = api.parseResponseOutput(json.parseToJsonElement("""{"output":[],"usage":{"total_tokens":0}}""").jsonObject)
        assertEquals(TokenUsage(), result.usage)
        assertEquals(false, result.hasCompleteUsage)
    }

    @Test fun `Responses derive missing total using same result in stream and non-stream`() {
        val usage = """{"input_tokens":100,"output_tokens":30,"output_tokens_details":{"reasoning_tokens":10}}"""
        val result = ResponseAPI(OkHttpClient()).parseResponseOutput(
            json.parseToJsonElement("""{"output":[],"usage":$usage}""").jsonObject,
        )
        val stream = Stream(ResponseApiStreamDecoder())
        stream.accept("""{"type":"response.completed","response":{"output":[],"usage":$usage}}""", "response.completed")
        assertEquals(TokenUsage(100, 30, 0, 130), result.usage)
        assertEquals(result.usage, stream.handler.usage)
        assertEquals(true, result.hasCompleteUsage)
        assertTrue(stream.handler.hasCompleteUsage)
    }

    @Test fun `Interactions completion is idempotent and shares thought-aware normalization`() {
        val interaction = """{"id":"r","status":"completed","steps":[],"usage":{"total_input_tokens":100,"total_output_tokens":20,"total_thought_tokens":10,"total_tokens":180}}"""
        val stream = Stream(InteractionsStreamDecoder())
        val body = """{"interaction":$interaction}"""
        stream.accept(body, "interaction.completed")
        assertTrue(stream.accept(body, "interaction.completed").isEmpty())
        val result = InteractionsAPI(OkHttpClient()).parseInteraction(json.parseToJsonElement(interaction).jsonObject)
        assertEquals(TokenUsage(100, 30, 0, 180), result.usage)
        assertEquals(result.usage, stream.handler.usage)
        assertEquals(true, result.hasCompleteUsage)
        assertTrue(stream.handler.hasCompleteUsage)
    }

    @Test fun `empty usage objects never create fake zero snapshots`() {
        val streams = listOf(
            Stream(ClaudeStreamDecoder()) to "usage",
            Stream(GoogleStreamDecoder("r", "test")) to "usageMetadata",
            Stream(ChatCompletionsStreamDecoder()) to "usage",
        )
        streams.forEach { (stream, field) ->
            for (usage in listOf("null", "{}", "{\"unknown\":3}")) {
                assertFalse(stream.accept("""{"$field":$usage}""").any { it is StreamChunk.Usage })
            }
            assertNull(stream.handler.usage)
            assertFalse(stream.handler.hasCompleteUsage)
        }
    }
}
