package me.rerere.rikkihub.data.ai.polish

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import kotlin.uuid.Uuid

class PromptPolishTest {
    private fun user(text: String) = UIMessage.user(text)
    private fun assistantMessage(text: String) = UIMessage.assistant(text)

    @Test
    fun `config defaults and old or partial JSON decode`() {
        val defaults = PromptPolishConfig()
        assertNull(defaults.modelId)
        assertFalse(defaults.includeContext)
        assertEquals(4, defaults.contextMessages)
        assertEquals(PromptPolish.DEFAULT_PROMPT, defaults.effectivePrompt)
        assertEquals(defaults, JsonInstant.decodeFromString<PromptPolishConfig>("{}"))
        val decoded = JsonInstant.decodeFromString<PromptPolishConfig>("""{"includeContext":true,"contextMessages":99,"future":1}""")
        assertTrue(decoded.includeContext)
        assertEquals(PromptPolishConfig.MAX_CONTEXT_MESSAGES, decoded.effectiveContextMessages)
        assertEquals(PromptPolishConfig.MIN_CONTEXT_MESSAGES, PromptPolishConfig(contextMessages = 0).effectiveContextMessages)
        assertEquals(PromptPolish.DEFAULT_PROMPT, PromptPolishConfig(prompt = "  ").effectivePrompt)
        // Settings without the key (old backups) decode to the default.
        assertEquals(PromptPolishConfig(), JsonInstant.decodeFromString<Settings>("{}").promptPolish)
    }

    @Test
    fun `default prompt carries the input and no context when the switch is off`() {
        val prompt = PromptPolish.buildPrompt(PromptPolishConfig(), "帮我看看这个", listOf(user("上文")))
        assertTrue(prompt.endsWith("待润色的提示词：\n帮我看看这个"))
        assertFalse("<conversation>" in prompt)
        assertFalse("{context}" in prompt)
        assertFalse("上文" in prompt)
    }

    @Test
    fun `context takes the latest N text messages, truncated, without tool or empty ones`() {
        val config = PromptPolishConfig(includeContext = true, contextMessages = 2)
        val long = "长".repeat(PromptPolish.MAX_CONTEXT_MESSAGE_CHARS + 50)
        val messages = listOf(
            user("最早的一条"),
            assistantMessage("中间"),
            UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning(reasoning = "思考"))),
            user(long),
            assistantMessage("最后的回复"),
        )
        val block = PromptPolish.contextBlock(config, messages)
        assertFalse("最早的一条" in block)
        assertFalse("中间" in block)
        assertFalse("思考" in block)
        assertTrue("[USER]: " in block && "[ASSISTANT]: 最后的回复" in block)
        assertTrue("..." in block)
        assertFalse(long in block)
        val prompt = PromptPolish.buildPrompt(config, "输入", messages)
        assertTrue(prompt.indexOf("<conversation>") < prompt.indexOf("待润色的提示词："))
        assertEquals("", PromptPolish.contextBlock(config, emptyList()))
        assertEquals("", PromptPolish.contextBlock(config.copy(includeContext = false), messages))
    }

    @Test
    fun `placeholders are replaced once and missing ones are filled in`() {
        val tricky = PromptPolish.buildPrompt(PromptPolishConfig(), "原样保留 {context} 和 {input}", emptyList())
        assertTrue(tricky.endsWith("原样保留 {context} 和 {input}"))

        val noInput = PromptPolish.buildPrompt(PromptPolishConfig(prompt = "改写一下"), "文字", emptyList())
        assertEquals("改写一下\n\n文字", noInput)

        val noContext = PromptPolish.buildPrompt(
            PromptPolishConfig(prompt = "改写：{input}", includeContext = true),
            "文字",
            listOf(user("前文")),
        )
        assertTrue(noContext.startsWith(PromptPolish.contextBlock(PromptPolishConfig(includeContext = true), listOf(user("前文"))).trim()))
        assertTrue(noContext.endsWith("改写：文字"))
    }

    @Test
    fun `clean trims and strips one wrapping fence`() {
        assertEquals("结果", PromptPolish.clean("  结果 \n"))
        assertEquals("第一行\n第二行", PromptPolish.clean("```markdown\n第一行\n第二行\n```"))
        assertEquals("a ```x``` b", PromptPolish.clean("a ```x``` b"))
        val inner = "说明\n```kotlin\nval a = 1\n```"
        assertEquals(inner, PromptPolish.clean(inner))
    }

    @Test
    fun `validation and model resolution`() {
        assertReason(PromptPolishException.Reason.EMPTY_INPUT) { PromptPolish.validateInput(" \n") }
        assertReason(PromptPolishException.Reason.INPUT_TOO_LONG) { PromptPolish.validateInput("x".repeat(PromptPolish.MAX_INPUT_CHARS + 1)) }
        val chat = Model(modelId = "chat")
        val polish = Model(modelId = "polish")
        val assistant = Assistant(chatModelId = chat.id)
        val base = Settings(
            providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://localhost", models = listOf(chat, polish))),
            assistants = listOf(assistant),
            assistantId = assistant.id,
        )
        assertEquals(chat.id, PromptPolish.resolveModel(base)?.id)
        assertEquals(polish.id, PromptPolish.resolveModel(base.copy(promptPolish = PromptPolishConfig(modelId = polish.id)))?.id)
        assertEquals(chat.id, PromptPolish.resolveModel(base.copy(promptPolish = PromptPolishConfig(modelId = Uuid.random())))?.id)
    }

    private class ScriptProvider(val chunks: (String) -> List<StreamChunk>) : Provider<ProviderSetting> {
        val calls = mutableListOf<Pair<List<UIMessage>, TextGenerationParams>>()
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
            calls += messages to params
            chunks(messages.single().toText()).forEach { emit(it) }
        }

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("not used")
    }

    private fun stream(vararg deltas: String) = buildList {
        add(StreamChunk.TextStart(id = "t"))
        deltas.forEach { add(StreamChunk.TextDelta(id = "t", text = it)) }
        add(StreamChunk.TextEnd(id = "t"))
        add(StreamChunk.Finish(finishReason = "stop"))
    }

    private val chat = Model(modelId = "chat")
    private val assistant = Assistant(chatModelId = chat.id, systemPrompt = "助手提示词不该出现")
    private val settings = Settings(
        providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://localhost", models = listOf(chat))),
        assistants = listOf(assistant),
        assistantId = assistant.id,
    )

    private fun handler(provider: ScriptProvider): PromptPolishHandler {
        val context = FakeContext(Files.createTempDirectory("polish").toFile())
        return PromptPolishHandler(ProviderManager(OkHttpClient(), context).apply { registerProvider("openai", provider) })
    }

    @Test
    fun `handler streams cumulative text, ends with the cleaned result, sends one bare user message`() = runBlocking {
        val provider = ScriptProvider { stream("```\n请帮我", "检查代码\n```") }
        val conversationId = Uuid.random()
        val results = handler(provider).polish(settings, "看看代码", conversationId = conversationId).toList()
        assertEquals("请帮我检查代码", results.last())
        assertTrue(results.first().startsWith("```"))
        val (messages, params) = provider.calls.single()
        assertEquals(1, messages.size)
        assertFalse("助手提示词不该出现" in messages.single().toText())
        assertTrue(params.tools.isEmpty())
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(conversationId.toString(), params.sessionId)
    }

    @Test
    fun `handler fails on empty output and on a missing model`() = runBlocking {
        val empty = ScriptProvider { stream("  ") }
        assertReason(PromptPolishException.Reason.EMPTY_RESULT) { runBlocking { handler(empty).polish(settings, "x").toList() } }
        val noModel = settings.copy(providers = emptyList())
        assertReason(PromptPolishException.Reason.NO_MODEL) { runBlocking { handler(empty).polish(noModel, "x").toList() } }
        assertReason(PromptPolishException.Reason.EMPTY_INPUT) { runBlocking { handler(empty).polish(settings, "").toList() } }
        assertTrue(empty.calls.size == 1)
    }

    private fun assertReason(reason: PromptPolishException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: PromptPolishException) {
            assertEquals(reason, e.reason)
        }
    }
}
