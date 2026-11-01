package me.rerere.rikkihub.claudelife

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.claudelife.CacheWarmReplayResult
import me.rerere.ai.claudelife.CacheWarmRequest
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [runCacheWarmReplay] / [resolveReplayProvider] 契约测试：
 * 重放只把 maxTokens 改为 1、走 streamText 单次调用无重试、消息/工具 chunk 丢弃、
 * 仅聚合 usage；provider 按最新设置重新解析（认证/baseUrl 随当前 provider）。
 */
class CacheWarmReplayTest {

    /** 一次 provider 调用的完整记录。 */
    private data class Call(
        val providerSetting: ProviderSetting,
        val messages: List<UIMessage>,
        val params: TextGenerationParams,
    )

    /** 记录型 provider：streamText/generateText 入参留档，输出脚本化。 */
    private class ReplayRecordingProvider(
        private val streamFlowFactory: () -> Flow<StreamChunk>,
    ) : Provider<ProviderSetting> {
        val streamCalls = mutableListOf<Call>()
        val generateCalls = mutableListOf<Call>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            generateCalls += Call(providerSetting, messages, params)
            error("ReplayRecordingProvider: generateText must not be called by cache-warm replay")
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            streamCalls += Call(providerSetting, messages, params)
            return streamFlowFactory()
        }

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("not used in tests")
    }

    private fun newRequest(
        model: Model,
        provider: ProviderSetting,
        params: TextGenerationParams = TextGenerationParams(
            model = model,
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 4096,
            reasoningLevel = ReasoningLevel.AUTO,
            customHeaders = listOf(CustomHeader("X-Test", "1")),
            customBody = listOf(CustomBody("foo", JsonPrimitive("bar"))),
            sessionId = "session-1",
        ),
        messages: List<UIMessage> = listOf(UIMessage.user("hello")),
    ) = CacheWarmRequest(
        providerSetting = provider,
        messages = messages,
        params = params,
        contextMessages = messages,
    )

    // ---------------------------------------------------------------
    // 构建输入原样 + maxTokens=1，单次 streamText
    // ---------------------------------------------------------------
    @Test
    fun `replay calls streamText once with maxTokens 1 and original inputs`() = runBlocking {
        val provider = ReplayRecordingProvider {
            flow {
                emit(StreamChunk.Finish(finishReason = "stop"))
            }
        }
        val model = Model(modelId = "chat-model")
        val setting = ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://x")
        val request = newRequest(model, setting)

        val result = runCacheWarmReplay(provider, setting, request)

        assertTrue(result is CacheWarmReplayResult.Succeeded)
        assertEquals(1, provider.streamCalls.size)
        assertEquals(0, provider.generateCalls.size)
        val call = provider.streamCalls.single()
        // 只有 maxTokens 改为 1，其余 params 字段原样
        assertEquals(request.params.copy(maxTokens = 1), call.params)
        assertSame(request.messages, call.messages)
        assertSame(setting, call.providerSetting)
    }

    // ---------------------------------------------------------------
    // usage 聚合：Succeeded 带上流里累计的 usage
    // ---------------------------------------------------------------
    @Test
    fun `usage chunk is merged into succeeded result`() = runBlocking {
        val provider = ReplayRecordingProvider {
            flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10, cachedTokens = 8)))
                emit(StreamChunk.Finish(finishReason = "stop"))
            }
        }
        val model = Model(modelId = "chat-model")
        val setting = ProviderSetting.OpenAI(apiKey = "k")

        val result = runCacheWarmReplay(provider, setting, newRequest(model, setting))

        val succeeded = result as CacheWarmReplayResult.Succeeded
        assertEquals(10, succeeded.usage?.promptTokens)
        assertEquals(8, succeeded.usage?.cachedTokens)
    }

    // ---------------------------------------------------------------
    // 流内异常 → Failed（含首行原因），不重试、不走 generateText
    // ---------------------------------------------------------------
    @Test
    fun `stream failure returns failed without retry`() = runBlocking {
        val provider = ReplayRecordingProvider {
            flow<StreamChunk> { throw IllegalStateException("upstream 500") }
        }
        val model = Model(modelId = "chat-model")
        val setting = ProviderSetting.OpenAI(apiKey = "k")

        val result = runCacheWarmReplay(provider, setting, newRequest(model, setting))

        val failed = result as CacheWarmReplayResult.Failed
        assertTrue(failed.reason.contains("upstream 500"))
        assertEquals(1, provider.streamCalls.size) // 无应用层重试
        assertEquals(0, provider.generateCalls.size)
    }

    // ---------------------------------------------------------------
    // 工具调用 chunk 一律丢弃：不执行、不报错，结果照常 Succeeded
    // ---------------------------------------------------------------
    @Test
    fun `tool call chunks in replay stream are ignored`() = runBlocking {
        val provider = ReplayRecordingProvider {
            flow {
                emit(StreamChunk.ToolCallStart(id = "tc-1", toolName = "echo_tool"))
                emit(StreamChunk.ToolCallDelta(id = "tc-1", inputDelta = """{"a":1}"""))
                emit(StreamChunk.ToolCallEnd(id = "tc-1"))
                emit(StreamChunk.Finish(finishReason = "tool_calls"))
            }
        }
        val model = Model(modelId = "chat-model")
        val setting = ProviderSetting.OpenAI(apiKey = "k")

        val result = runCacheWarmReplay(provider, setting, newRequest(model, setting))

        assertTrue(result is CacheWarmReplayResult.Succeeded)
        assertEquals(1, provider.streamCalls.size)
    }

    // ---------------------------------------------------------------
    // 取消向上传播：收集中被取消，job 以取消收尾，结果不落地
    // ---------------------------------------------------------------
    @Test
    fun `cancellation propagates and produces no result`() = runBlocking {
        val started = CountDownLatch(1)
        val provider = ReplayRecordingProvider {
            flow<StreamChunk> {
                started.countDown()
                awaitCancellation()
            }
        }
        val model = Model(modelId = "chat-model")
        val setting = ProviderSetting.OpenAI(apiKey = "k")
        val outcome = AtomicReference<CacheWarmReplayResult?>()

        val job: Job = launch(Dispatchers.Default) {
            outcome.set(runCacheWarmReplay(provider, setting, newRequest(model, setting)))
        }
        assertTrue("replay did not reach stream collection", started.await(10, TimeUnit.SECONDS))
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(outcome.get())
    }

    // ---------------------------------------------------------------
    // resolveReplayProvider：按最新设置重解析 provider
    // ---------------------------------------------------------------
    @Test
    fun `resolveReplayProvider returns latest provider for known model`() {
        val model = Model(modelId = "chat-model")
        val captured = ProviderSetting.OpenAI(apiKey = "old-key", baseUrl = "http://old")
        val latest = ProviderSetting.OpenAI(apiKey = "new-key", baseUrl = "http://new", models = listOf(model))
        val settings = Settings(providers = listOf(latest))

        val resolved = resolveReplayProvider(model, captured, settings)

        // 命中最新设置里的 provider（认证随当前配置），不是捕获时的那份
        assertSame(latest, resolved)
        assertEquals("new-key", (resolved as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `resolveReplayProvider falls back to captured when model missing`() {
        val model = Model(modelId = "chat-model")
        val captured = ProviderSetting.OpenAI(apiKey = "old-key", baseUrl = "http://old")
        val other = ProviderSetting.OpenAI(apiKey = "k2", models = listOf(Model(modelId = "other")))
        val settings = Settings(providers = listOf(other))

        val resolved = resolveReplayProvider(model, captured, settings)

        assertSame(captured, resolved)
    }

    @Test
    fun `resolveReplayProvider returns provider overwrite when set`() {
        val claudeOverwrite = ProviderSetting.Claude(apiKey = "claude-key")
        val model = Model(modelId = "chat-model", providerOverwrite = claudeOverwrite)
        val openAi = ProviderSetting.OpenAI(apiKey = "openai-key", models = listOf(model))
        val captured = ProviderSetting.OpenAI(apiKey = "old-key")
        val settings = Settings(providers = listOf(openAi))

        val resolved = resolveReplayProvider(model, captured, settings)

        // overwrite 生效：返回 Claude provider（models 置空副本）
        val claude = resolved as ProviderSetting.Claude
        assertEquals("claude-key", claude.apiKey)
        assertTrue(claude.models.isEmpty())
    }
}
