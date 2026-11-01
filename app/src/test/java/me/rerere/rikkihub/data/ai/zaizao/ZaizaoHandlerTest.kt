package me.rerere.rikkihub.data.ai.zaizao

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
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
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.uuid.Uuid

class ZaizaoHandlerTest {
    private data class Call(val model: String, val messages: List<UIMessage>, val params: TextGenerationParams)

    /** Streams [pieces] as text deltas, after one reasoning block when [thinking] is set. */
    private class ScriptProvider(private val pieces: List<String>, private val thinking: String? = null) : Provider<ProviderSetting> {
        val calls = ConcurrentLinkedQueue<Call>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = error("not used")

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = flow {
            calls += Call(params.model.modelId, messages, params)
            if (thinking != null) {
                emit(StreamChunk.ReasoningStart(id = "r"))
                emit(StreamChunk.ReasoningDelta(id = "r", text = thinking))
                emit(StreamChunk.ReasoningEnd(id = "r"))
            }
            emit(StreamChunk.TextStart(id = "t"))
            pieces.forEach { emit(StreamChunk.TextDelta(id = "t", text = it)) }
            emit(StreamChunk.TextEnd(id = "t"))
            emit(StreamChunk.Finish(finishReason = "stop"))
        }

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("not used")
    }

    private val assistantModel = Model(modelId = "assistant-model")
    private val ownModel = Model(modelId = "zaizao-model")
    private val assistant = Assistant(chatModelId = assistantModel.id, systemPrompt = "助手提示词不该出现")

    private fun settings(config: ZaizaoConfig = ZaizaoConfig()) = Settings(
        providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://localhost", models = listOf(assistantModel, ownModel))),
        assistants = listOf(assistant),
        assistantId = assistant.id,
        zaizao = config,
    )

    private fun handler(provider: ScriptProvider): ZaizaoHandler {
        val context = FakeContext(Files.createTempDirectory("zaizao").toFile())
        return ZaizaoHandler(ProviderManager(OkHttpClient(), context).apply { registerProvider("openai", provider) })
    }

    private fun prepared(config: ZaizaoConfig, round: ZaizaoRound = ZaizaoRound(ZaizaoType.MODIFY, "铲子上有三斤大便")) =
        Zaizao.prepare(config, ZaizaoRequest("a.md", "他抄起铁锹。", 0, 6, true, emptyList(), round))

    @Test
    fun streamsTheResultAndEndsWithTheFullReply() = runBlocking {
        val provider = ScriptProvider(listOf("<result>他抄起", "那把铁锹。", "</result>"))
        val config = ZaizaoConfig()
        val updates = handler(provider).generate(settings(config), prepared(config)).toList()
        assertEquals(listOf("他抄起", "他抄起那把铁锹。"), updates.filter { !it.done }.map { it.result }.distinct().filter { it.isNotEmpty() })
        val last = updates.last()
        assertTrue(last.done)
        assertEquals("他抄起那把铁锹。", last.result)
        assertEquals("<result>他抄起那把铁锹。</result>", last.output)
        assertEquals(1, updates.count { it.done })
    }

    @Test
    fun usesTheConfiguredModelAndParametersWithoutAssistantContextOrTools() = runBlocking {
        val provider = ScriptProvider(listOf("<result>好</result>"))
        val config = ZaizaoConfig(modelId = ownModel.id, reasoningLevel = ReasoningLevel.HIGH, temperature = 0.3f, topP = 0.8f, maxTokens = 4096)
        handler(provider).generate(settings(config), prepared(config)).toList()
        val call = provider.calls.single()
        assertEquals("zaizao-model", call.model)
        assertEquals(ReasoningLevel.HIGH, call.params.reasoningLevel)
        assertEquals(0.3f, call.params.temperature)
        assertEquals(0.8f, call.params.topP)
        assertEquals(4096, call.params.maxTokens)
        assertTrue(call.params.tools.isEmpty())
        assertTrue(call.messages.none { it.role == MessageRole.SYSTEM })
        assertTrue(call.messages.none { "助手提示词不该出现" in it.toText() })
    }

    @Test
    fun defaultsToTheAssistantModelWithAutoReasoning() = runBlocking {
        val provider = ScriptProvider(listOf("<result>好</result>"))
        val gone = ZaizaoConfig(modelId = Uuid.random())
        handler(provider).generate(settings(gone), prepared(gone)).toList()
        val call = provider.calls.single()
        assertEquals("assistant-model", call.model)
        assertEquals(ReasoningLevel.AUTO, call.params.reasoningLevel)
        assertEquals(null, call.params.temperature)
        assertEquals(null, call.params.maxTokens)
    }

    @Test
    fun reportsReasoningAndPlanSeparately() = runBlocking {
        val provider = ScriptProvider(listOf("<plan>事件：出门</plan>\n", "<result>门被截成两半。</result>"), thinking = "想一想")
        val config = ZaizaoConfig()
        val last = handler(provider).generate(settings(config), prepared(config, ZaizaoRound(ZaizaoType.REWRITE, "换个写法"))).toList().last()
        assertEquals("想一想", last.reasoning)
        assertEquals("事件：出门", last.plan)
        assertEquals("门被截成两半。", last.result)
    }

    @Test
    fun emptyResultAndMissingModelFail() = runBlocking {
        val config = ZaizaoConfig()
        val empty = runCatching { handler(ScriptProvider(listOf("<plan>只想了</plan>"))).generate(settings(config), prepared(config)).toList() }
        assertEquals(ZaizaoException.Reason.EMPTY_RESULT, (empty.exceptionOrNull() as ZaizaoException).reason)
        val noModel = runCatching {
            handler(ScriptProvider(listOf("x"))).generate(settings(config).copy(providers = emptyList()), prepared(config)).toList()
        }
        assertEquals(ZaizaoException.Reason.NO_MODEL, (noModel.exceptionOrNull() as ZaizaoException).reason)
    }
}
