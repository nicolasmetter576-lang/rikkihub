package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.ai.tavern.TurnPreparation
import me.rerere.rikkihub.data.ai.tavern.TurnRequest
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.PromptEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 请求里的 system 消息：提示词文本条目按布局顺序合并，关着的和空白的不发，会话覆盖整个替换。 */
class GenerationLoopSystemPromptTest {
    @get:Rule val folder = TemporaryFolder()
    private val runtime = TavernRuntime()

    @After fun close() = runtime.close()

    private fun textEntry(id: String, title: String, content: String, enabled: Boolean = true) = PromptEntry(
        identifier = id, name = title, role = PromptRole.SYSTEM, content = content,
        systemPrompt = false, marker = false, enabled = enabled,
    )

    private val sectionsAssistant = testAssistant().copy(
        promptPreset = AssistantPresets.create(
            listOf(
                textEntry("a", "Role", "You are A."),
                textEntry("b", "Off", "disabled", enabled = false),
                textEntry("c", "Blank", "  "),
                textEntry("d", "Format", "Answer briefly."),
            )
        ),
    )

    private fun systemTextOf(assistant: Assistant, conversationSystemPrompt: String? = null): String? = runBlocking {
        val impl = ScriptedProvider(ArrayDeque(listOf(textStepScript("done"))))
        val model = testChatModel()
        val settings = testSettings(model)
        val messages = listOf(UIMessage.user("hi"))
        val turn = TurnPreparation(runtime).prepare(
            TurnRequest(
                assistant = assistant, settings = settings, model = model, conversationId = null,
                metadata = JsonObject(emptyMap()), conversationLibraryEntryIds = emptySet(),
                conversationLorebookIds = emptySet(), customSystemPrompt = conversationSystemPrompt,
                messages = messages, userName = "user", charName = "assistant",
            )
        )
        testGenerationLoop(impl, FakeContext(folder.newFolder())).generateText(
            settings = settings, model = model, assistant = assistant,
            messages = messages, tools = emptyList(),
            conversationSystemPrompt = conversationSystemPrompt, turn = turn,
        ).toList()
        impl.streamMessages.single().firstOrNull { it.role == MessageRole.SYSTEM }?.toText()
    }

    @Test fun `enabled non blank text entries are sent in order without titles`() {
        assertEquals("You are A.\nAnswer briefly.", systemTextOf(sectionsAssistant))
    }

    @Test fun `a single prompt is sent as is`() {
        assertEquals("whole text", systemTextOf(testAssistant().copy(promptPreset = AssistantPresets.withSystemPrompt("whole text"))))
    }

    @Test fun `no prompt means no system message`() {
        assertNull(systemTextOf(testAssistant()))
    }

    @Test fun `conversation override replaces all text entries`() {
        val allowed = sectionsAssistant.copy(allowConversationSystemPrompt = true)
        assertEquals("per chat", systemTextOf(allowed, conversationSystemPrompt = "per chat"))
        // 未允许时会话覆盖不生效
        assertEquals("You are A.\nAnswer briefly.", systemTextOf(sectionsAssistant, conversationSystemPrompt = "per chat"))
    }

    @Test fun `a blank override does not replace the prompt`() {
        val allowed = sectionsAssistant.copy(allowConversationSystemPrompt = true)
        assertEquals("You are A.\nAnswer briefly.", systemTextOf(allowed, conversationSystemPrompt = "   "))
    }

    @Test fun `callers without a prepared turn still get a plain system prompt`() = runBlocking {
        val impl = ScriptedProvider(ArrayDeque(listOf(textStepScript("done"))))
        val model = testChatModel()
        val assistant = testAssistant().copy(promptPreset = AssistantPresets.withSystemPrompt("worker prompt"))
        testGenerationLoop(impl, FakeContext(folder.newFolder())).generateText(
            settings = testSettings(model), model = model, assistant = assistant,
            messages = listOf(UIMessage.user("hi")), tools = emptyList(),
        ).toList()
        assertEquals("worker prompt", impl.streamMessages.single().first { it.role == MessageRole.SYSTEM }.toText())
    }
}
