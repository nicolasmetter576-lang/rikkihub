package me.rerere.ai.provider.providers.google

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class VertexProviderStreamTest {
    private val params = TextGenerationParams(Model(modelId = "test"))
    private val tool = """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"call","name":"save","args":{}}}]}}]}"""
    private val stop = """{"candidates":[{"finishReason":"STOP"}]}"""
    private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" }

    private fun client(body: ResponseBody) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
            .message("OK").body(body).build()
    }.build()

    @Test
    fun `non Vertex decoder enforces EOF finishReason and tolerates null error`() {
        val decoder = GoogleStreamDecoder("resp", "test")
        decoder.accept(me.rerere.ai.provider.stream.SseEvent(data = tool))
        assertThrows(IOException::class.java) { decoder.onClosed() }
        assertTrue(decoder.onClosed().isEmpty())

        val nullErrorWithStop = GoogleStreamDecoder("resp", "test")
        nullErrorWithStop.accept(me.rerere.ai.provider.stream.SseEvent(data = """{"error":null}"""))
        nullErrorWithStop.accept(me.rerere.ai.provider.stream.SseEvent(data = stop))
        assertEquals(1, nullErrorWithStop.onClosed().filterIsInstance<StreamChunk.Finish>().size)
    }

    @Test
    fun `Google non2xx errors exclude body echoes and retain status and Retry-After`() = runBlocking {
        for (stream in listOf(false, true)) for (status in listOf(400, 429, 503)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("rejected").header("Retry-After", "3")
                    .body("""{"error":{"message":"Bearer synthetic-secret synthetic-jwt"}}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                val failure = try {
                    withTimeout(5_000) {
                        val provider = GoogleProvider(client)
                        val setting = ProviderSetting.Google(vertexAI = true)
                        if (stream) provider.streamText(setting, listOf(UIMessage.user("synthetic")), params).collect {}
                        else provider.generateText(setting, listOf(UIMessage.user("synthetic")), params)
                    }
                    null
                } catch (e: Exception) { e }
                assertNotNull(failure)
                assertFalse(failure!!.stackTraceToString().contains("synthetic-"))
                assertTrue(generateSequence<Throwable>(failure) { it.cause }
                    .all { it.message?.contains(status.toString()) == true })
                assertTrue(failure.suppressed.isEmpty())
                assertTrue(failure is HttpException)
                assertEquals(status, (failure as HttpException).statusCode)
                assertEquals(3000L, failure.retryAfterMillis)
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `provider EOF and DONE fail the attempt without hanging`() = runBlocking {
        for (done in listOf(false, true)) for (error in listOf(false, true)) {
            val frames = listOf(tool) + if (error) listOf("""{"error":{"code":400,"message":"synthetic-secret"}}""") else emptyList()
            val client = client(sse(*(frames + if (done) listOf("[DONE]") else emptyList()).toTypedArray())
                .toResponseBody("text/event-stream".toMediaType()))
            try {
                val chunks = mutableListOf<StreamChunk>()
                val failure = try {
                    withTimeout(5_000) {
                        GoogleProvider(client).streamText(ProviderSetting.Google(vertexAI = true),
                            listOf(UIMessage.user("synthetic")), params).collect { chunks += it }
                    }
                    null
                } catch (e: ToolStreamProtocolException) { e } catch (e: IOException) { e }
                if (error) {
                    assertEquals("google_stream_upstream_error", (failure as? ToolStreamProtocolException)?.code)
                } else {
                    assertTrue("EOF without finishReason is retryable: $failure", failure is IOException)
                }
                assertFalse(failure!!.stackTraceToString().contains("synthetic-secret"))
                assertTrue(chunks.none { it is StreamChunk.ToolCallEnd || it is StreamChunk.Finish })
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `transient upstream error frame surfaces as a retryable network failure`() = runBlocking {
        val client = client(sse(tool, """{"error":{"code":503,"status":"UNAVAILABLE","message":"synthetic-secret"}}""")
            .toResponseBody("text/event-stream".toMediaType()))
        try {
            val chunks = mutableListOf<StreamChunk>()
            val failure = runCatching {
                withTimeout(5_000) {
                    GoogleProvider(client).streamText(ProviderSetting.Google(vertexAI = true),
                        listOf(UIMessage.user("synthetic")), params).collect { chunks += it }
                }
            }.exceptionOrNull()
            assertTrue("expected IOException, got $failure", failure is java.io.IOException)
            assertTrue(failure!!.message.orEmpty().contains("503 UNAVAILABLE"))
            assertFalse(failure.stackTraceToString().contains("synthetic-secret"))
            assertTrue(chunks.none { it is StreamChunk.ToolCallEnd || it is StreamChunk.Finish })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `STOP waits for EOF and preserves late metadata and decreasing usage`() = runBlocking {
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val initial = Buffer().writeUtf8(sse(tool, stop,
            """{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":100,"totalTokenCount":110}}"""))
        val tail = Buffer().writeUtf8(sse(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"call","name":"save","args":{}},"thoughtSignature":"synthetic-signature"}]}}],"usageMetadata":{"candidatesTokenCount":20,"totalTokenCount":30}}"""))
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (initial.size > 0) return initial.read(sink, byteCount)
                waiting.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return tail.read(sink, byteCount)
            }
            override fun timeout() = Timeout.NONE
            override fun close() { release.countDown() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        val client = client(body)
        try {
            val chunks = CopyOnWriteArrayList<StreamChunk>()
            val task = async {
                withTimeout(7_000) {
                    GoogleProvider(client).streamText(ProviderSetting.Google(vertexAI = true),
                        listOf(UIMessage.user("synthetic")), params).collect { chunks += it }
                }
            }
            assertTrue(withContext(Dispatchers.IO) { waiting.await(5, TimeUnit.SECONDS) })
            assertFalse(task.isCompleted)
            assertTrue(chunks.none { it is StreamChunk.ToolCallEnd || it is StreamChunk.Finish })
            release.countDown()
            task.await()
            assertEquals(1, chunks.filterIsInstance<StreamChunk.Finish>().size)
            assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
            assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().metadata.toString().contains("synthetic-signature"))
            assertEquals(30, chunks.filterIsInstance<StreamChunk.Usage>().last().usage.totalTokens)
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
