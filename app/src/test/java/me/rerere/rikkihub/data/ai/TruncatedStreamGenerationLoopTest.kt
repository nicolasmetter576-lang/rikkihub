package me.rerere.rikkihub.data.ai

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkihub.data.datastore.NetworkSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 端到端单测：真实 OpenAI/Claude SSE Provider + GenerationLoop。
 * 确认缺结束帧的截断流不会把半截工具调用送入执行，自动重试保留中断分支且不重复副作用。
 */
class TruncatedStreamGenerationLoopTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `chat completions truncated tool stream retries without running partial tool and keeps interrupted branch`() = runBlocking {
        val executions = mutableListOf<String>()
        val tool = Tool(
            name = "echo_tool",
            description = "echo input",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
            execute = { args: JsonElement ->
                executions += args.toString()
                listOf(UIMessagePart.Text("""{"ok":true}"""))
            },
        )
        val model = Model(modelId = "gpt-test", abilities = listOf(ModelAbility.TOOL))
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://offline.invalid/v1",
            apiKey = "k",
            useResponseApi = false,
            models = listOf(model),
        )
        val responses = ArrayDeque(
            listOf(
                // 尝试 1：吐出半截文本与合法 JSON 工具调用，但缺少 finish_reason 直接 EOF
                buildString {
                    append("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"draft half\"}}]}\n\n")
                    append("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_bad\",\"type\":\"function\",\"function\":{\"name\":\"echo_tool\",\"arguments\":\"{\\\"n\\\":1}\"}}]}}]}\n\n")
                },
                // 尝试 2（自动重试）：正常带 finish_reason 发出合法工具调用
                buildString {
                    append("data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_good\",\"type\":\"function\",\"function\":{\"name\":\"echo_tool\",\"arguments\":\"{\\\"n\\\":2}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n")
                    append("data: [DONE]\n\n")
                },
                // 工具结果回传后：正常结束文本回复
                buildString {
                    append("data: {\"id\":\"c3\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"final ok\"},\"finish_reason\":\"stop\"}]}\n\n")
                    append("data: [DONE]\n\n")
                },
            ),
        )
        val requests = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responses.removeFirst().toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        try {
            val context = FakeContext(tempFolder.newFolder())
            val loop = GenerationLoop(
                context = context,
                providerManager = ProviderManager(OkHttpClient(), context).apply {
                    registerProvider("openai", OpenAIProvider(client))
                },
                json = JsonInstant,
            ).apply {
                statusStrings = StatusStringResolver { resId, args -> "status:$resId:" + args.joinToString(",") }
            }
            val user = UIMessage.user("go")
            var conversation = Conversation(assistantId = DEFAULT_ASSISTANT_ID, messageNodes = listOf(user.toMessageNode()))
            withTimeout(15_000) {
                loop.generateTextObserved(
                    settings = Settings(providers = listOf(providerSetting)),
                    model = model,
                    messages = listOf(user),
                    assistant = testAssistant(),
                    tools = listOf(tool),
                ).collect { chunk ->
                    if (chunk is GenerationChunk.Messages) {
                        conversation = conversation.updateCurrentMessages(chunk.messages)
                    }
                }
            }

            // 尝试 1 的 call_bad 绝不能执行，仅尝试 2 的 call_good 执行一次
            assertEquals(3, requests.get())
            assertEquals(listOf("""{"n":2}"""), executions)

            val assistantNode = conversation.messageNodes[1]
            assertEquals(2, assistantNode.messages.size)
            assertEquals(1, assistantNode.selectIndex)
            val (interruptedBranch, retriedBranch) = assistantNode.messages
            val badTool = interruptedBranch.getTools().single()
            assertEquals("call_bad", badTool.toolCallId)
            assertTrue(badTool.isExecuted)
            assertTrue((badTool.output.single() as UIMessagePart.Text).text.contains(INTERRUPTED_TOOL_CALL_REASON))
            assertEquals("final ok", retriedBranch.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `claude truncated tool stream with auto retry disabled fails with IOException and never runs partial tool`() = runBlocking {
        val executions = AtomicInteger(0)
        val tool = Tool(
            name = "echo_tool",
            description = "echo input",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("""{"ok":true}"""))
            },
        )
        val model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL))
        val providerSetting = ProviderSetting.Claude(
            baseUrl = "https://offline.invalid/v1",
            apiKey = "k",
            models = listOf(model),
        )
        val truncatedSse = buildString {
            append("event: content_block_start\n")
            append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_bad\",\"name\":\"echo_tool\",\"input\":{\"n\":1}}}\n\n")
        }
        val requests = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(truncatedSse.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        try {
            val context = FakeContext(tempFolder.newFolder())
            val loop = GenerationLoop(
                context = context,
                providerManager = ProviderManager(OkHttpClient(), context).apply {
                    registerProvider("claude", ClaudeProvider(client))
                },
                json = JsonInstant,
            ).apply {
                statusStrings = StatusStringResolver { resId, args -> "status:$resId:" + args.joinToString(",") }
            }
            val user = UIMessage.user("go")
            val failure = runCatching {
                withTimeout(10_000) {
                    loop.generateTextObserved(
                        settings = Settings(
                            providers = listOf(providerSetting),
                            networkSetting = NetworkSetting(enableAutoRetry = false),
                        ),
                        model = model,
                        messages = listOf(user),
                        assistant = testAssistant(),
                        tools = listOf(tool),
                    ).collect {}
                }
            }.exceptionOrNull()

            assertTrue("expected IOException, got=$failure", failure is IOException)
            assertEquals(1, requests.get())
            assertEquals(0, executions.get())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
