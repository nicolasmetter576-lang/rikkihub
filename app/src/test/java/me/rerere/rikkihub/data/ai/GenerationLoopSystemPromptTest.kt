package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.model.SystemPromptMode
import me.rerere.rikkihub.data.model.SystemPromptSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 请求里的 system 消息：有分块时为分块合并结果，与编辑视图无关（2.13.0 起）。 */
class GenerationLoopSystemPromptTest {
    @get:Rule val folder = TemporaryFolder()

    private val sectionsAssistant = testAssistant().copy(
        systemPrompt = "whole text is not sent",
        systemPromptMode = SystemPromptMode.SECTIONS,
        promptSections = listOf(
            SystemPromptSection(title = "Role", content = "You are A."),
            SystemPromptSection(title = "Off", content = "disabled", enabled = false),
            SystemPromptSection(title = "Blank", content = "  "),
            SystemPromptSection(title = "Format", content = "Answer briefly."),
        ),
    )

    private fun systemTextOf(
        assistant: me.rerere.rikkihub.data.model.Assistant,
        conversationSystemPrompt: String? = null,
    ): String? = runBlocking {
        val impl = ScriptedProvider(ArrayDeque(listOf(textStepScript("done"))))
        val model = testChatModel()
        testGenerationLoop(impl, FakeContext(folder.newFolder())).generateText(
            settings = testSettings(model), model = model, assistant = assistant,
            messages = listOf(UIMessage.user("hi")), tools = emptyList(),
            conversationSystemPrompt = conversationSystemPrompt,
        ).toList()
        impl.streamMessages.single().firstOrNull { it.role == MessageRole.SYSTEM }?.toText()
    }

    @Test fun `sections mode sends enabled non blank sections in order without titles`() {
        assertEquals("You are A.\n\nAnswer briefly.", systemTextOf(sectionsAssistant))
    }

    @Test fun `the editor view does not change what is sent when sections exist`() {
        val whole = sectionsAssistant.copy(systemPromptMode = SystemPromptMode.WHOLE)
        assertEquals("You are A.\n\nAnswer briefly.", systemTextOf(whole))
        val plain = sectionsAssistant.copy(promptSections = emptyList())
        assertEquals("whole text is not sent", systemTextOf(plain))
    }

    @Test fun `conversation override replaces all sections`() {
        val allowed = sectionsAssistant.copy(allowConversationSystemPrompt = true)
        assertEquals("per chat", systemTextOf(allowed, conversationSystemPrompt = "per chat"))
        // 未允许时会话覆盖不生效
        assertEquals("You are A.\n\nAnswer briefly.", systemTextOf(sectionsAssistant, conversationSystemPrompt = "per chat"))
    }
}
