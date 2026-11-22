package me.rerere.rikkihub.data.ai

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentRunner
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class VertexGenerationRollbackTest(
    private val kind: String,
    private val done: Boolean,
    private val subagent: Boolean,
) {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun realProviderFailureRollsBackOrRetriesWithoutRepeatedEffects() = runBlocking {
        val requests = AtomicInteger()
        val effects = AtomicInteger()
        val call = """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"call","name":"echo_tool","args":{}}}]}}]}"""
        val stop = """{"candidates":[{"finishReason":"STOP"}]}"""
        val text = """{"candidates":[{"content":{"parts":[{"text":"resumed"}]},"finishReason":"STOP"}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val frames = when (requests.incrementAndGet()) {
                1 -> listOf(call, stop)
                2 -> when (kind) {
                    "EOF" -> listOf(call)
                    "error" -> listOf(call, stop, """{"error":{"code":400,"message":"synthetic-secret"}}""")
                    "transient" -> listOf(call, stop, """{"error":{"code":503,"status":"UNAVAILABLE","message":"synthetic-secret"}}""")
                    else -> listOf(call, """{"candidates":[{"finishReason":"FUTURE_REASON"}]}""", stop)
                }
                else -> listOf(text)
            } + if (done) listOf("[DONE]") else emptyList()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(frames.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val context = FakeContext(temp.newFolder())
            val model = testChatModel()
            val settings = Settings(providers = listOf(ProviderSetting.Google(vertexAI = true, models = listOf(model))))
            val manager = ProviderManager(client, context).apply { registerProvider("google", GoogleProvider(client)) }
            val loop = GenerationLoop(context, manager, JsonInstant).apply {
                statusStrings = StatusStringResolver { _, _ -> "status" }
            }
            val tool = echoTool().copy(execute = { effects.incrementAndGet(); listOf(UIMessagePart.Text("ok")) })
            withTimeout(20_000) {
                if (kind in RETRIED && subagent) {
                    // A subagent's step survives a cut stream the same way.
                    val runner = WorkspaceSubagentRunner(loop, { settings }, object : InputMessageTransformer {}, context,
                        progressFlushIntervalMs = 0)
                    val parts = runner.run(testAssistant(), model, null, false, false, null,
                        JsonInstant.parseToJsonElement("""{"prompt":"synthetic","description":"synthetic"}"""), listOf(tool))
                    val result = JsonInstant.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
                    assertEquals("ok", result["status"]!!.jsonPrimitive.content)
                    assertEquals(3, requests.get())
                } else if (kind in RETRIED) {
                    // Overload and a stream cut without finishReason are retried as network failures:
                    // the turn completes, step 1's tool runs once.
                    val chunks = loop.generateText(settings, model, listOf(UIMessage.user("synthetic")),
                        assistant = testAssistant(), tools = listOf(tool)).toList()
                    assertEquals(3, requests.get())
                    assertTrue(chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last().toText().contains("resumed"))
                    assertTrue(chunks.any { it is GenerationChunk.GenerationEnd })
                } else if (subagent) {
                    val runner = WorkspaceSubagentRunner(loop, { settings }, object : InputMessageTransformer {}, context,
                        progressFlushIntervalMs = 0)
                    val parts = runner.run(testAssistant(), model, null, false, false, null,
                        JsonInstant.parseToJsonElement("""{"prompt":"synthetic","description":"synthetic"}"""), listOf(tool))
                    val result = JsonInstant.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
                    assertEquals("error", result["status"]!!.jsonPrimitive.content)
                    assertEquals("1", result["step_count"]!!.jsonPrimitive.content)
                    val log = File(context.filesDir, FileFolders.SUBAGENT_RUNS).listFiles()!!.single().readText()
                    assertFalse(log.contains("synthetic-secret"))
                    assertEquals(2, requests.get())
                } else {
                    val chunks = mutableListOf<GenerationChunk>()
                    val failure = try {
                        loop.generateText(settings, model, listOf(UIMessage.user("synthetic")), assistant = testAssistant(),
                            tools = listOf(tool)).toList(chunks)
                        throw AssertionError("expected protocol failure")
                    } catch (e: GenerationAttemptProtocolException) { e }
                    assertEquals(2, requests.get())
                    assertEquals(1, chunks.filterIsInstance<GenerationChunk.StepFinished>().size)
                    assertTrue(chunks.none { it is GenerationChunk.GenerationEnd })
                    assertEquals(1, failure.baseMessage.getTools().size)
                    chunks.filterIsInstance<GenerationChunk.Messages>().forEach { chunk ->
                        val restored = chunk.messages.rollbackProtocolFailedAttempt(failure)
                        restored.lastOrNull { it.id == failure.assistantMessageId }?.let {
                            assertEquals(failure.baseMessage, it)
                        }
                    }
                    val resumed = loop.generateText(settings, model,
                        listOf(UIMessage.user("synthetic"), failure.baseMessage), assistant = testAssistant(),
                        tools = listOf(tool)).toList()
                    assertTrue(resumed.filterIsInstance<GenerationChunk.Messages>().last().messages.last().toText().contains("resumed"))
                    assertEquals(3, requests.get())
                }
                assertEquals(1, effects.get())
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        private val RETRIED = listOf("EOF", "transient")

        @JvmStatic @Parameterized.Parameters(name = "{0}, DONE={1}, subagent={2}")
        fun cases(): List<Array<Any>> = buildList {
            for (kind in listOf("error", "mixed reason") + RETRIED)
                for (done in listOf(false, true)) for (subagent in listOf(false, true))
                    add(arrayOf(kind, done, subagent))
        }
    }
}
