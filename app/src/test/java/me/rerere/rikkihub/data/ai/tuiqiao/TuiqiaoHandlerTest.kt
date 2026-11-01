package me.rerere.rikkihub.data.ai.tuiqiao

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
import me.rerere.rikkihub.data.ai.textStepScript
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.uuid.Uuid

class TuiqiaoHandlerTest {
    private data class Call(val model: String, val messages: List<UIMessage>, val params: TextGenerationParams)

    /** Answers by prompt: a role prompt gets one line (or fails when the role says FAIL), the rest two lines. */
    private class PromptProvider : Provider<ProviderSetting> {
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
            val prompt = messages.single().toText()
            val text = when {
                "FAIL" in prompt -> throw IllegalStateException("boom")
                "给出 1 个结果" in prompt -> """{"text":"他推开门（门：我招谁惹谁了）。","why":"w"}"""
                else -> """{"text":"他拉开门。","tone":"平","why":"a"}""" + "\n" + """{"text":"他撞开门。","tone":"急","why":"b"}"""
            }
            textStepScript(text).chunks.forEach { emit(it) }
        }

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("not used")
    }

    private val assistantModel = Model(modelId = "assistant-model")
    private val roleModel = Model(modelId = "role-model")
    private val assistant = Assistant(chatModelId = assistantModel.id, systemPrompt = "助手提示词不该出现")

    private fun settings(roles: List<TuiqiaoRole>) = Settings(
        providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://localhost", models = listOf(assistantModel, roleModel))),
        assistants = listOf(assistant),
        assistantId = assistant.id,
        tuiqiao = TuiqiaoConfig(candidateCount = 4, roles = roles),
    )

    private fun handler(provider: PromptProvider): TuiqiaoHandler {
        val context = FakeContext(Files.createTempDirectory("tuiqiao").toFile())
        return TuiqiaoHandler(ProviderManager(OkHttpClient(), context).apply { registerProvider("openai", provider) })
    }

    @Test
    fun slotsRunInParallelWithTheirOwnModelsAndNoAssistantContext() = runBlocking {
        val inherit = TuiqiaoRole(name = "幽默旁白", prompt = "为选中部分加上幽默讽刺的旁白", modes = setOf(TuiqiaoMode.SENTENCE))
        val own = TuiqiaoRole(name = "冷峻", prompt = "改得冷峻", modes = setOf(TuiqiaoMode.SENTENCE), modelId = roleModel.id)
        val deleted = TuiqiaoRole(name = "已删模型", prompt = "随便", modes = setOf(TuiqiaoMode.SENTENCE), modelId = Uuid.random())
        val settings = settings(listOf(inherit, own, deleted))
        val prepared = TuiqiaoPreparer.prepare(settings.tuiqiao, TuiqiaoRequest("a.md", "他推开门。", 0, 5))
        val provider = PromptProvider()
        val events = handler(provider).events(settings, prepared).toList()

        val bySlot = events.filterIsInstance<TuiqiaoEvent.Candidate>().groupBy({ it.slot }, { it.candidate })
        assertEquals(1, bySlot.getValue(inherit.id.toString()).size)
        assertEquals("", bySlot.getValue(inherit.id.toString()).single().tone) // the card shows the role name instead
        assertEquals(listOf("他拉开门。"), bySlot.getValue(TuiqiaoSlot.DEFAULT_KEY).map { it.text }) // 4 - 3 roles = 1
        assertTrue(events.none { it is TuiqiaoEvent.SlotFailed })

        assertEquals(4, provider.calls.size)
        val models = provider.calls.map { it.model }.sorted()
        assertEquals(listOf("assistant-model", "assistant-model", "assistant-model", "role-model"), models)
        for (call in provider.calls) {
            assertEquals(MessageRole.USER, call.messages.single().role)
            assertTrue(call.params.tools.isEmpty())
            assertEquals(ReasoningLevel.OFF, call.params.reasoningLevel)
            assertEquals(null, call.params.temperature)
            assertTrue("助手提示词不该出现" !in call.messages.single().toText())
        }
    }

    @Test
    fun aFailingRoleOnlyFailsItsOwnSlot() = runBlocking {
        val failing = TuiqiaoRole(name = "坏", prompt = "FAIL", modes = setOf(TuiqiaoMode.SENTENCE))
        val settings = settings(listOf(failing))
        val prepared = TuiqiaoPreparer.prepare(settings.tuiqiao, TuiqiaoRequest("a.txt", "他推开门。", 0, 5))
        val events = handler(PromptProvider()).events(settings, prepared).toList()
        val failed = events.filterIsInstance<TuiqiaoEvent.SlotFailed>().single()
        assertEquals(failing.id.toString(), failed.slot)
        assertEquals("boom", failed.message)
        assertEquals(2, events.filterIsInstance<TuiqiaoEvent.Candidate>().count { it.slot == TuiqiaoSlot.DEFAULT_KEY })

        // Retrying just that slot runs only its request.
        val provider = PromptProvider()
        handler(provider).events(settings, prepared.only(TuiqiaoSlot.DEFAULT_KEY)!!).toList()
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun missingModelFailsTheSlot() = runBlocking {
        val settings = settings(emptyList()).copy(providers = emptyList())
        val prepared = TuiqiaoPreparer.prepare(settings.tuiqiao, TuiqiaoRequest("a.md", "推", 0, 1))
        val failed = handler(PromptProvider()).events(settings, prepared).toList().single() as TuiqiaoEvent.SlotFailed
        assertEquals(TuiqiaoException.Reason.NO_MODEL, failed.reason)
    }
}
