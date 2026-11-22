package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.NetworkSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 限流（429/503）退避重试：真实 GoogleProvider（Vertex 模式）+ GenerationLoop，
 * 上游由拦截器扮演——先回 [limitedCount] 次 Vertex 的 429，之后回一段文字。
 */
class GenerationLoopRateLimitRetryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val requests = AtomicInteger(0)
    private val model = Model(modelId = "gemini-4-argon", abilities = listOf(ModelAbility.TOOL))
    private val provider = ProviderSetting.Google(vertexAI = true, models = listOf(model))

    private fun vertexClient(limitedCount: Int, status: Int, retryAfter: String?) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (requests.incrementAndGet() <= limitedCount) {
                builder.code(status).message("limited")
                retryAfter?.let { builder.header("Retry-After", it) }
                builder.body(
                    """{"error":{"code":$status,"message":"Resource exhausted. Please try again later.","status":"RESOURCE_EXHAUSTED"}}"""
                        .toResponseBody("application/json".toMediaType()),
                ).build()
            } else {
                val text = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
                val stream = chain.request().url.encodedPath.contains("streamGenerateContent")
                builder.code(200).message("OK").body(
                    (if (stream) "data: $text\n\n" else text)
                        .toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType()),
                ).build()
            }
        }.build()

    private fun loop(
        limitedCount: Int,
        policy: RateLimitRetryPolicy,
        status: Int = 429,
        retryAfter: String? = null,
    ): GenerationLoop {
        val context = FakeContext(tempFolder.newFolder())
        return GenerationLoop(
            context = context,
            providerManager = ProviderManager(OkHttpClient(), context).apply {
                registerProvider("google", GoogleProvider(vertexClient(limitedCount, status, retryAfter)))
            },
            json = JsonInstant,
        ).apply {
            statusStrings = StatusStringResolver { resId, args -> "status:$resId:" + args.joinToString(",") }
            rateLimitRetryPolicy = policy
        }
    }

    private suspend fun GenerationLoop.reply(
        stream: Boolean,
        network: NetworkSetting = NetworkSetting(),
        status: MutableStateFlow<String?> = MutableStateFlow(null),
    ): String = generateTextObserved(
        settings = Settings(providers = listOf(provider), networkSetting = network),
        model = model,
        messages = listOf(UIMessage.user("hi")),
        assistant = testAssistant(streamOutput = stream),
        tools = emptyList(),
        processingStatus = status,
    ).toList().filterIsInstance<GenerationChunk.Messages>().last().messages.last().toText()

    private val fast = RateLimitRetryPolicy(maxRetries = 2, baseDelayMillis = 10, maxDelayMillis = 50)

    @Test
    fun `Vertex 429 is retried on streaming and non streaming requests`() = runBlocking {
        for (stream in listOf(true, false)) {
            requests.set(0)
            val reply = withTimeout(10_000) { loop(limitedCount = 2, policy = fast).reply(stream) }
            assertEquals("stream=$stream", "ok", reply)
            assertEquals("stream=$stream", 3, requests.get())
        }
    }

    @Test
    fun `switch off surfaces the first 429 without retrying`() = runBlocking {
        for (stream in listOf(true, false)) {
            requests.set(0)
            try {
                withTimeout(10_000) {
                    loop(limitedCount = 1, policy = fast).reply(stream, NetworkSetting(enableRateLimitRetry = false))
                }
                fail("stream=$stream: expected the 429 to surface")
            } catch (expected: HttpException) {
                assertEquals("stream=$stream", 429, expected.statusCode)
            }
            assertEquals("stream=$stream", 1, requests.get())
        }
    }

    @Test
    fun `retries stop after the policy limit and surface the last error`() = runBlocking {
        try {
            withTimeout(10_000) { loop(limitedCount = 10, policy = fast, status = 503).reply(stream = true) }
            fail("expected failure after retries")
        } catch (expected: HttpException) {
            assertEquals(503, expected.statusCode)
        }
        assertEquals(3, requests.get())
    }

    @Test
    fun `status shows code and Retry-After wait then clears once the reply streams`() = runBlocking {
        // Retry-After 1 s beats the 2 s exponential base; the hint names it in whole seconds.
        val loop = loop(limitedCount = 1, policy = RateLimitRetryPolicy(), retryAfter = "1")
        val status = MutableStateFlow<String?>(null)
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        val watcher = launch(Dispatchers.Default) { status.collect { seen += it } }
        val reply = withTimeout(10_000) { loop.reply(stream = true, status = status) }
        watcher.cancel()
        assertEquals("ok", reply)
        assertTrue(seen.toString(), "status:${R.string.chat_generation_rate_limit_retrying}:429,1,1,5" in seen)
        assertNull(status.value)
    }

    @Test
    fun `stopping during the wait sends no further request and clears the hint`() = runBlocking {
        val loop = loop(limitedCount = 1, policy = RateLimitRetryPolicy(baseDelayMillis = 60_000, maxDelayMillis = 60_000))
        val status = MutableStateFlow<String?>(null)
        val job = launch(Dispatchers.Default) { loop.reply(stream = true, status = status) }
        withTimeout(10_000) { while (status.value == null) delay(10) }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, requests.get())
        assertNull(status.value)
    }

    @Test
    fun `rate limited status ignores other errors`() {
        assertNull(rateLimitedStatus(HttpException("bad", statusCode = 400)))
        assertNull(rateLimitedStatus(IllegalStateException("x")))
        assertEquals(429, rateLimitedStatus(RuntimeException(HttpException("x", statusCode = 429)))?.statusCode)
        assertEquals(
            503,
            rateLimitedStatus(me.rerere.ai.provider.providers.devin.DevinApiException(503, "x"))?.statusCode,
        )
    }

    @Test
    fun `multi-key failover succeeds without rate-limit retry delay and shows then clears switch status`() = runBlocking {
        for (stream in listOf(true, false)) {
            val context = FakeContext(tempFolder.newFolder())
            val seenAuthKeys = Collections.synchronizedList(mutableListOf<String>())
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val req = chain.request()
                val key = req.url.queryParameter("key").orEmpty()
                seenAuthKeys += key
                if (key == "key-1") {
                    return@addInterceptor Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1)
                        .code(429).message("Too Many Requests")
                        .body("""{"error":{"code":429,"message":"exhausted","status":"RESOURCE_EXHAUSTED"}}"""
                            .toResponseBody("application/json".toMediaType()))
                        .build()
                }
                val text = """{"candidates":[{"content":{"role":"model","parts":[{"text":"multi-ok"}]},"finishReason":"STOP"}]}"""
                val isStream = req.url.encodedPath.contains("streamGenerateContent")
                Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body((if (isStream) "data: $text\n\n" else text)
                        .toResponseBody((if (isStream) "text/event-stream" else "application/json").toMediaType()))
                    .build()
            }.build()

            val pm = ProviderManager(client, context)
            val loop = GenerationLoop(context = context, providerManager = pm, json = JsonInstant).apply {
                statusStrings = StatusStringResolver { resId, args -> "status:$resId:" + args.joinToString(",") }
            }
            val multiProvider = ProviderSetting.Google(
                vertexAI = true,
                rotationStrategy = me.rerere.ai.provider.CredentialRotationStrategy.FAILOVER,
                models = listOf(model),
                apiKeys = listOf(
                    me.rerere.ai.provider.ApiKeyEntry(id = "k1", label = "Primary", key = "key-1", enabled = true),
                    me.rerere.ai.provider.ApiKeyEntry(id = "k2", label = "Backup", key = "key-2", enabled = true),
                ),
            )
            val status = MutableStateFlow<String?>(null)
            val seenStatus = Collections.synchronizedList(mutableListOf<String?>())
            val watcher = launch(Dispatchers.Default) { status.collect { seenStatus += it } }
            val reply = withTimeout(10_000) {
                loop.generateTextObserved(
                    settings = Settings(
                        providers = listOf(multiProvider),
                        networkSetting = NetworkSetting(enableRateLimitRetry = false),
                    ),
                    model = model,
                    messages = listOf(UIMessage.user("hi")),
                    assistant = testAssistant(streamOutput = stream),
                    tools = emptyList(),
                    processingStatus = status,
                ).toList().filterIsInstance<GenerationChunk.Messages>().last().messages.last().toText()
            }
            watcher.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()

            assertEquals("stream=$stream", "multi-ok", reply)
            assertEquals("stream=$stream", listOf("key-1", "key-2"), seenAuthKeys)
            assertTrue(
                seenStatus.toString(),
                seenStatus.any { it == "status:${R.string.chat_generation_credential_switching}:Primary, (HTTP 429),Backup,2,2" },
            )
            assertNull(status.value)
        }
    }
}
