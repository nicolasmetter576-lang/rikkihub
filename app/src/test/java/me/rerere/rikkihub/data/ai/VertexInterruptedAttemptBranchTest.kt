package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vertex 流在一步中途断开（EOF 无 finishReason / 流内 429 帧）后自动重试：这次尝试已经显示出来的
 * 文字和完整调用留作前一个分支，其中未执行的调用写成 unavailable 且从不执行；之前已执行的步骤两边都在。
 * 真实 GoogleProvider（Vertex 模式）+ GenerationLoop，上游由拦截器按顺序回放 SSE。
 */
class VertexInterruptedAttemptBranchTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val model = Model(modelId = "gemini-4-argon", abilities = listOf(ModelAbility.TOOL))
    private val provider = ProviderSetting.Google(vertexAI = true, models = listOf(model))
    private val requestBodies = mutableListOf<String>()
    private val executions = AtomicInteger(0)

    private val countingEcho = Tool(
        name = "echo_tool",
        description = "echo input",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
        execute = { args: JsonElement ->
            executions.incrementAndGet()
            listOf(UIMessagePart.Text("""{"ok":true,"input":$args}"""))
        },
    )

    private fun text(value: String) = """{"candidates":[{"content":{"role":"model","parts":[{"text":"$value"}]}}]}"""
    private fun call(id: String, n: Int) =
        """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"$id","name":"echo_tool","args":{"n":$n}}}]}}]}"""
    private val stop = """{"candidates":[{"finishReason":"STOP"}]}"""
    private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" }

    private fun loop(vararg responses: String): GenerationLoop {
        val queue = ArrayDeque(responses.toList())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer().also { chain.request().body?.writeTo(it) }
            synchronized(requestBodies) { requestBodies += buffer.readUtf8() }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(queue.removeFirst().toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val context = FakeContext(tempFolder.newFolder())
        return GenerationLoop(
            context = context,
            providerManager = ProviderManager(OkHttpClient(), context).apply {
                registerProvider("google", GoogleProvider(client))
            },
            json = JsonInstant,
        ).apply {
            statusStrings = StatusStringResolver { resId, args -> "status:$resId:" + args.joinToString(",") }
            rateLimitRetryPolicy = RateLimitRetryPolicy(maxRetries = 2, baseDelayMillis = 10, maxDelayMillis = 50)
        }
    }

    private suspend fun GenerationLoop.runConversation(): Conversation {
        val user = UIMessage.user("write the articles")
        var conversation = Conversation(assistantId = DEFAULT_ASSISTANT_ID, messageNodes = listOf(user.toMessageNode()))
        withTimeout(15_000) {
            generateTextObserved(
                settings = Settings(providers = listOf(provider)),
                model = model,
                messages = listOf(user),
                assistant = testAssistant(),
                tools = listOf(countingEcho),
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) conversation = conversation.updateCurrentMessages(chunk.messages)
            }
        }
        return conversation
    }

    private fun UIMessage.plainText() = parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `EOF after a complete call keeps the shown step as the previous branch and never runs the call`() = runBlocking {
        val conversation = loop(
            sse(text("ARTICLE-ONE saved."), call("c1", 1), stop),
            // 第二步：文字和完整调用都已经到了，STOP 之前连接断开（EOF 无 finishReason → 重试）
            sse(text("ARTICLE-TWO draft"), call("c2", 2)),
            sse(text("all done"), stop),
        ).runConversation()

        assertEquals(3, requestBodies.size)
        val node = conversation.messageNodes[1]
        assertEquals("interrupted attempt kept as a branch", 2, node.messages.size)
        assertEquals(1, node.selectIndex)
        val (previous, current) = node.messages

        // 前一个分支：第一步（已执行）+ 被打断的第二步，半截调用写成 unavailable
        val (step1Call, interruptedCall) = previous.getTools()
        assertEquals("c1", step1Call.toolCallId)
        assertTrue((step1Call.output.single() as UIMessagePart.Text).text.contains("\"ok\":true"))
        assertEquals("c2", interruptedCall.toolCallId)
        assertTrue(interruptedCall.isExecuted)
        assertTrue(interruptedCall.hasUnavailableOutcome(JsonInstant))
        assertTrue((interruptedCall.output.single() as UIMessagePart.Text).text.contains(INTERRUPTED_TOOL_CALL_REASON))
        assertTrue(previous.plainText().contains("ARTICLE-TWO draft"))

        // 当前分支：第一步还在，重试结果接在后面
        assertEquals(listOf("c1"), current.getTools().map { it.toolCallId })
        assertTrue(current.plainText().contains("ARTICLE-ONE saved."))
        assertTrue(current.plainText().endsWith("all done"))
        assertFalse(current.plainText().contains("ARTICLE-TWO draft"))

        // 只执行过第一步的调用；重试请求带着第一步，但不带被打断的内容
        assertEquals(1, executions.get())
        assertTrue(requestBodies[2].contains("ARTICLE-ONE saved."))
        assertFalse(requestBodies[2].contains("ARTICLE-TWO draft"))
    }

    @Test
    fun `an in-stream 429 frame after visible text keeps that text as the previous branch`() = runBlocking {
        val conversation = loop(
            sse(text("half of it"), """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""),
            sse(text("whole reply"), stop),
        ).runConversation()

        assertEquals(2, requestBodies.size)
        val node = conversation.messageNodes[1]
        assertEquals(2, node.messages.size)
        assertEquals(1, node.selectIndex)
        assertEquals("half of it", node.messages[0].plainText())
        assertEquals("whole reply", node.messages[1].plainText())
    }
}
