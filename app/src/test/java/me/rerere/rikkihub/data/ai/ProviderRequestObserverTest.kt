package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * [ProviderRequestObserver] 契约测试：生成循环在每次真实 provider 请求
 * (`streamText`/`generateText`) 之前同步回调一次，网络重试的每个 attempt 各一次，
 * 回调异常不中断生成。
 *
 * Provider 用本文件自带的 [RecordingProvider]（记录每次调用的 providerSetting /
 * messages / params 与调用时刻的调用计数），不改共享 fixtures。
 */
class ProviderRequestObserverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 一次 provider 调用的完整记录。 */
    private data class ProviderCall(
        val providerSetting: ProviderSetting,
        val messages: List<UIMessage>,
        val params: TextGenerationParams,
    )

    /** 记录型 provider：streamText/generateText 调用入参全部留档，脚本化输出。 */
    private class RecordingProvider(
        private val streamFlows: ArrayDeque<Flow<StreamChunk>> = ArrayDeque(),
        private val generateResults: ArrayDeque<TextGenerationResult> = ArrayDeque(),
    ) : Provider<ProviderSetting> {
        val streamCalls = mutableListOf<ProviderCall>()
        val generateCalls = mutableListOf<ProviderCall>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            generateCalls += ProviderCall(providerSetting, messages, params)
            return generateResults.removeFirstOrNull()
                ?: error("RecordingProvider: no scripted generateText result left")
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            streamCalls += ProviderCall(providerSetting, messages, params)
            return streamFlows.removeFirstOrNull()
                ?: error("RecordingProvider: no scripted streamText flow left")
        }

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("not used in tests")
    }

    /** 一次 observer 回调的留档，含回调时刻 provider 已被调用的次数。 */
    private data class Observed(
        val providerSetting: ProviderSetting,
        val messages: List<UIMessage>,
        val params: TextGenerationParams,
        val contextMessages: List<UIMessage>,
        val streamCallsAtTime: Int,
        val generateCallsAtTime: Int,
    )

    private fun textFlow(text: String): Flow<StreamChunk> = flow {
        emit(StreamChunk.TextStart(id = "t"))
        emit(StreamChunk.TextDelta(id = "t", text = text))
        emit(StreamChunk.TextEnd(id = "t"))
        emit(StreamChunk.Finish(finishReason = "stop"))
    }

    private fun textResult(text: String): TextGenerationResult = TextGenerationResult(
        id = "r-1",
        model = "scripted-chat",
        message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(text)),
        ),
        finishReason = "stop",
        usage = null,
    )

    private fun newLoop(provider: Provider<ProviderSetting>): GenerationLoop {
        val context = FakeContext(tempFolder.newFolder())
        return GenerationLoop(
            context = context,
            providerManager = ProviderManager(OkHttpClient(), context).apply {
                registerProvider("openai", provider)
            },
            json = JsonInstant,
        ).apply {
            statusStrings = StatusStringResolver { resId, args ->
                "status:$resId" + if (args.isNotEmpty()) ":" + args.joinToString(",") else ""
            }
        }
    }

    private fun List<GenerationChunk>.end() = filterIsInstance<GenerationChunk.GenerationEnd>()

    // ---------------------------------------------------------------
    // 流式单步：恰一次回调，且在 provider streamText 调用之前
    // ---------------------------------------------------------------
    @Test
    fun `streaming single step notifies once before provider with built params`() = runBlocking {
        val provider = RecordingProvider(streamFlows = ArrayDeque(listOf(textFlow("hi there"))))
        val loop = newLoop(provider)
        val model = testChatModel()
        val settings = testSettings(model)
        val conversationId = Uuid.random()
        val input = listOf(UIMessage.user("hi"))

        val observed = mutableListOf<Observed>()
        val chunks = loop.generateTextObserved(
            settings = settings,
            model = model,
            messages = input,
            assistant = testAssistant(),
            conversationId = conversationId,
            requestObserver = ProviderRequestObserver { providerSetting, messages, params, contextMessages ->
                observed += Observed(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = params,
                    contextMessages = contextMessages,
                    streamCallsAtTime = provider.streamCalls.size,
                    generateCallsAtTime = provider.generateCalls.size,
                )
            },
        ).toList()

        // 恰一次，且在 streamText 调用之前（回调时 provider 尚未被调用）
        assertEquals(1, observed.size)
        assertEquals(0, observed.single().streamCallsAtTime)
        assertEquals(0, observed.single().generateCallsAtTime)
        assertEquals(1, provider.streamCalls.size)

        val obs = observed.single()
        val call = provider.streamCalls.single()

        // params: sessionId 来自 conversationId, model 为请求模型, 且与 provider 收到的是同一实例
        assertEquals(conversationId.toString(), obs.params.sessionId)
        assertEquals(model.id, obs.params.model.id)
        assertSame(obs.params, call.params)

        // messages: 交给 provider 的正是 observer 看到的同一列表（含构建的 system 在前，若有）
        assertSame(obs.messages, call.messages)
        assertEquals(MessageRole.USER, obs.messages.last().role)
        assertEquals("hi", (obs.messages.last().parts.single() as UIMessagePart.Text).text)

        // providerSetting 为 settings 中解析到的 provider；contextMessages 为输入快照
        assertSame(settings.providers.single(), obs.providerSetting)
        assertEquals(input, obs.contextMessages)

        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.end().single().reason,
        )
    }

    // ---------------------------------------------------------------
    // 两步（工具调用 + 文本）：每个 provider 请求各回调一次
    // ---------------------------------------------------------------
    @Test
    fun `tool step then text step notifies once per provider request`() = runBlocking {
        // 先解析脚本为 chunk 流（toolStepScript 产生 List<StreamChunk> 经 ScriptedAttempt）
        val attempt1 = toolStepScript("call-1", "echo_tool")
        val attempt2 = textStepScript("done after tool")
        val provider = RecordingProvider(
            streamFlows = ArrayDeque(
                listOf(
                    flow { attempt1.chunks.forEach { emit(it) } },
                    flow { attempt2.chunks.forEach { emit(it) } },
                )
            )
        )
        val loop = newLoop(provider)
        val model = testChatModel()
        val settings = testSettings(model)

        val observed = mutableListOf<Observed>()
        val chunks = loop.generateTextObserved(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            tools = listOf(echoTool()),
            requestObserver = ProviderRequestObserver { providerSetting, messages, params, contextMessages ->
                observed += Observed(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = params,
                    contextMessages = contextMessages,
                    streamCallsAtTime = provider.streamCalls.size,
                    generateCallsAtTime = provider.generateCalls.size,
                )
            },
        ).toList()

        assertEquals(2, observed.size)
        assertEquals(2, provider.streamCalls.size)
        // 第 N 次回调时 provider 已被调用 N-1 次 → 每次都在本次调用之前
        assertEquals(0, observed[0].streamCallsAtTime)
        assertEquals(1, observed[1].streamCallsAtTime)
        // 两次请求都携带工具定义
        observed.forEach { o ->
            assertEquals(listOf("echo_tool"), o.params.tools.map { it.name })
        }
        // 第二次请求的 contextMessages 应已包含第一步的 assistant 工具调用
        assertTrue(
            observed[1].contextMessages.last().parts
                .filterIsInstance<UIMessagePart.Tool>()
                .any { it.toolCallId == "call-1" }
        )
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.end().single().reason,
        )
    }

    // ---------------------------------------------------------------
    // 网络重试：失败的 attempt 也会回调（每个 attempt 各一次）
    // ---------------------------------------------------------------
    @Test
    fun `network retry notifies once per attempt`() = runBlocking {
        val provider = RecordingProvider(
            streamFlows = ArrayDeque(
                listOf(
                    flow<StreamChunk> { throw IOException("boom") }, // attempt1: 0 chunk 即断
                    textFlow("retried ok"),                            // attempt2: 成功
                )
            )
        )
        val loop = newLoop(provider)
        val model = testChatModel()
        val settings = testSettings(model)

        val observed = mutableListOf<Observed>()
        val chunks = loop.generateTextObserved(
            settings = settings, // enableAutoRetry 默认 true
            model = model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            requestObserver = ProviderRequestObserver { providerSetting, messages, params, contextMessages ->
                observed += Observed(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = params,
                    contextMessages = contextMessages,
                    streamCallsAtTime = provider.streamCalls.size,
                    generateCallsAtTime = provider.generateCalls.size,
                )
            },
        ).toList()

        // 两个 attempt 各回调一次，失败 attempt 的回调不被丢弃
        assertEquals(2, observed.size)
        assertEquals(2, provider.streamCalls.size)
        assertEquals(0, observed[0].streamCallsAtTime)
        assertEquals(1, observed[1].streamCallsAtTime)
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.end().single().reason,
        )
    }

    // ---------------------------------------------------------------
    // observer 抛异常：不冒充 provider 错误，不终止生成
    // ---------------------------------------------------------------
    @Test
    fun `throwing request observer does not break generation`() = runBlocking {
        val provider = RecordingProvider(streamFlows = ArrayDeque(listOf(textFlow("still works"))))
        val loop = newLoop(provider)
        val model = testChatModel()
        val settings = testSettings(model)

        var observerCalls = 0
        val chunks = loop.generateTextObserved(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            requestObserver = ProviderRequestObserver { _, _, _, _ ->
                observerCalls++
                throw RuntimeException("observer bug")
            },
        ).toList()

        assertEquals(1, observerCalls)
        assertEquals(1, provider.streamCalls.size)
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.end().single().reason,
        )
    }

    // ---------------------------------------------------------------
    // 非流式（assistant.streamOutput=false）：generateText 之前回调一次
    // ---------------------------------------------------------------
    @Test
    fun `non streaming notifies once before generateText`() = runBlocking {
        val provider = RecordingProvider(
            generateResults = ArrayDeque(listOf(textResult("non stream ok")))
        )
        val loop = newLoop(provider)
        val model = testChatModel()
        val settings = testSettings(model)

        val observed = mutableListOf<Observed>()
        val chunks = loop.generateTextObserved(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(streamOutput = false),
            requestObserver = ProviderRequestObserver { providerSetting, messages, params, contextMessages ->
                observed += Observed(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = params,
                    contextMessages = contextMessages,
                    streamCallsAtTime = provider.streamCalls.size,
                    generateCallsAtTime = provider.generateCalls.size,
                )
            },
        ).toList()

        assertEquals(1, observed.size)
        assertEquals(0, observed.single().generateCallsAtTime) // 回调在 generateText 调用之前
        assertEquals(1, provider.generateCalls.size)
        assertEquals(0, provider.streamCalls.size) // 非流式不走 streamText
        assertSame(observed.single().messages, provider.generateCalls.single().messages)
        assertSame(observed.single().params, provider.generateCalls.single().params)
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.end().single().reason,
        )
    }
}
