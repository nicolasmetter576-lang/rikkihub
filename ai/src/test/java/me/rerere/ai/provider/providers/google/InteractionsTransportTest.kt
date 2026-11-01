package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Canned transport only. Deliberately never calls chain.proceed to ANY host. */
class InteractionsTransportTest {
    private val setting = ProviderSetting.Google(useInteractionsApi = true, baseUrl = "https://offline.invalid/v1beta")
    private val params = TextGenerationParams(model = Model(modelId = "test"))
    private val messages = listOf(UIMessage.user("prompt"))
    private class Capture(private val text: String, private val code: Int = 200, private val stream: Boolean = true) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("canned")
                .body(text.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    private val start = "event: step.start\ndata: {\"index\":0,\"step\":{\"type\":\"model_output\"}}\n\n"
    private val stop = "event: step.stop\ndata: {\"index\":0}\n\n"
    private fun textDelta(text: String) = "event: step.delta\ndata: {\"index\":0,\"delta\":{\"type\":\"text\",\"text\":${JsonPrimitive(text)}}}\n\n"

    @Test fun `bounded SSE backpressure never drops text under a slow collector`() = runBlocking {
        val pieces = (0 until 256).map { "[$it]" }
        Capture(start + pieces.joinToString("") { textDelta(it) } + stop + "data: [DONE]\n\n").use { c ->
            val chunks = mutableListOf<StreamChunk>()
            withTimeout(10_000) {
                GoogleProvider(c.client).streamText(setting, messages, params).collect { chunks += it; delay(1) }
            }
            assertEquals(pieces.joinToString(""), chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
            assertEquals(1, chunks.count { it is StreamChunk.Finish })
            assertEquals(1, c.requests.size)
        }
    }

    @Test fun `bounded SSE backpressure preserves every arguments character`() = runBlocking {
        val complete = "{\"value\":\"" + "a".repeat(256) + "\"}"
        val sse = "event: step.start\ndata: {\"index\":0,\"step\":{\"type\":\"function_call\",\"name\":\"client\",\"id\":\"call\"}}\n\n" +
            complete.map { char -> "event: step.delta\ndata: {\"index\":0,\"delta\":{\"type\":\"arguments_delta\",\"arguments\":${JsonPrimitive(char.toString())}}}\n\n" }.joinToString("") + stop + "data: [DONE]\n\n"
        Capture(sse).use { c ->
            val p = params.copy(model = params.model.copy(abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)),
                tools = listOf(me.rerere.ai.core.Tool("client", "client tool", execute = { emptyList() })))
            val chunks = mutableListOf<StreamChunk>()
            withTimeout(10_000) { GoogleProvider(c.client).streamText(setting, messages, p).collect { chunks += it; delay(1) } }
            assertEquals(complete, chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta })
            assertEquals(1, chunks.count { it is StreamChunk.Finish })
            assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
        }
    }

    @Test fun `decoder failure followed by wire terminal and EOF cannot emit successful Finish`() = runBlocking {
        val bad = "event: step.delta\ndata: {\"index\":99,\"delta\":{\"type\":\"text\",\"text\":\"orphan\"}}\n\n"
        Capture(start + textDelta("partial") + bad + "data: [DONE]\n\n").use { c ->
            val chunks = mutableListOf<StreamChunk>()
            var failure: Throwable? = null
            try {
                withTimeout(10_000) { GoogleProvider(c.client).streamText(setting, messages, params).collect { chunks += it } }
            } catch (e: Throwable) { failure = e }
            assertTrue(failure is ToolStreamProtocolException)
            assertFalse(chunks.any { it is StreamChunk.Finish })
            assertEquals(1, c.requests.size)
        }
    }

    @Test fun `canned clean EOF missing terminal fails after stopped text step`() = runBlocking {
        Capture(start + textDelta("partial") + stop).use { c ->
            val chunks = mutableListOf<StreamChunk>()
            var failure: Throwable? = null
            try {
                withTimeout(10_000) { GoogleProvider(c.client).streamText(setting, messages, params).collect { chunks += it } }
            } catch (e: Throwable) { failure = e }
            assertTrue(failure is ToolStreamProtocolException)
            assertEquals("interactions_unexpected_eof", (failure as ToolStreamProtocolException).code)
            assertFalse(chunks.any { it is StreamChunk.Finish })
        }
    }

    @Test fun `collector cancellation stops transport without later success chunk`() = runBlocking {
        Capture(start + (0 until 256).joinToString("") { textDelta("$it") } + stop + "data: [DONE]\n\n").use { c ->
            val chunks = withTimeout(10_000) { GoogleProvider(c.client).streamText(setting, messages, params).take(3).toList() }
            assertEquals(3, chunks.size)
            assertFalse(chunks.any { it is StreamChunk.Finish })
            assertEquals(1, c.requests.size)
        }
    }

    @Test fun `HTTP failure once and stream both fail without successful stream finish`() = runBlocking {
        for (stream in listOf(false, true)) Capture("{\"error\":{\"message\":\"offline denied\"}}", code = 401, stream = stream).use { c ->
            val chunks = mutableListOf<StreamChunk>()
            var failure: Throwable? = null
            try {
                withTimeout(10_000) {
                    val sdk = GoogleProvider(c.client)
                    if (stream) sdk.streamText(setting, messages, params).collect { chunks += it }
                    else sdk.generateText(setting, messages, params)
                }
            } catch (e: Throwable) { failure = e }
            assertNotNull(failure)
            assertFalse(chunks.any { it is StreamChunk.Finish })
            assertEquals(1, c.requests.size)
        }
    }
}
