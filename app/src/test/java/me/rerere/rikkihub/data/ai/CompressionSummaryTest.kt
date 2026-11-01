package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 压缩摘要（移植上游 7cceda2 的 CompressionSummaryTest，另加请求前缀一致的用例）：
 * 摘要请求与正常对话用同一套组装，只在末尾多一条指令，才能命中提示词缓存。
 */
class CompressionSummaryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun reply(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `plain text reply is the summary`() {
        val summary = reply(UIMessagePart.Text("  [Summary of previous conversation]\n## User Goals\n...  "))
            .compressionSummaryOrNull()
        assertEquals("[Summary of previous conversation]\n## User Goals\n...", summary)
    }

    @Test
    fun `reply with a tool call is rejected even if it has text`() {
        val summary = reply(
            UIMessagePart.Text("Let me check the file first."),
            UIMessagePart.Tool(toolCallId = "call_1", toolName = "workspace_shell", input = "{}"),
        ).compressionSummaryOrNull()
        assertNull(summary)
    }

    @Test
    fun `inline think tags and reasoning parts are not part of the summary`() {
        assertEquals(
            "[Summary of previous conversation]",
            reply(UIMessagePart.Text("<think>planning</think>\n[Summary of previous conversation]")).compressionSummaryOrNull(),
        )
        assertEquals(
            "[Summary]",
            reply(UIMessagePart.Reasoning(reasoning = "planning"), UIMessagePart.Text("[Summary]")).compressionSummaryOrNull(),
        )
        assertEquals("", reply().compressionSummaryOrNull())
    }

    private fun textResult(message: UIMessage) = TextGenerationResult(
        id = "r", model = "scripted-chat", message = message, finishReason = "stop",
    )

    @Test
    fun `summary request is the chat request plus one instruction, without the output cap`() = runBlocking {
        val model = testChatModel()
        val settings = testSettings(model)
        val assistant = testAssistant().copy(systemPrompt = "You are helpful.", maxTokens = 256)
        val history = listOf(UIMessage.user("hello"), UIMessage.assistant("hi there"))
        val tool = echoTool()

        // 正常对话的一步：记下发给 provider 的消息
        val chatProvider = ScriptedProvider(ArrayDeque(listOf(textStepScript("ok"))))
        testGenerationLoop(chatProvider, FakeContext(tempFolder.newFolder())).generateText(
            settings = settings, model = model, messages = history, assistant = assistant, tools = listOf(tool),
            conversationId = kotlin.uuid.Uuid.random(),
        ).toList()
        val chatRequest = chatProvider.streamMessages.single()

        val summaryProvider = ScriptedProvider(
            attempts = ArrayDeque(),
            results = ArrayDeque(listOf(textResult(UIMessage.assistant("[Summary]")))),
        )
        val conversationId = kotlin.uuid.Uuid.random()
        val summary = testGenerationLoop(summaryProvider, FakeContext(tempFolder.newFolder())).generateCompressionSummary(
            settings = settings,
            model = model,
            messages = history,
            inputTransformers = emptyList(),
            assistant = assistant,
            memories = emptyList(),
            toolSnapshotProvider = { fixedToolsSnapshot(listOf(tool)) },
            compressPrompt = "SUMMARIZE",
            conversationSystemPrompt = null,
            conversationId = conversationId,
        )
        assertEquals("[Summary]", summary)
        val params = summaryProvider.generateParams.single()
        assertNull("the summary must not be cut off by the chat reply cap", params.maxTokens)
        assertEquals(listOf(tool.name), params.tools.map { it.name })
        assertEquals(conversationId.toString(), params.sessionId)
        val sent = summaryProvider.generateMessages.single()
        // 同样的系统提示词和历史，末尾多一条指令
        assertEquals(chatRequest.map { it.role to it.toText() }, sent.dropLast(1).map { it.role to it.toText() })
        assertEquals(MessageRole.USER to "SUMMARIZE", sent.last().role to sent.last().toText())
    }

    @Test
    fun `a summary request answered with a tool call fails instead of saving`() {
        val model = testChatModel()
        val provider = ScriptedProvider(
            attempts = ArrayDeque(),
            results = ArrayDeque(
                listOf(textResult(reply(UIMessagePart.Tool(toolCallId = "c", toolName = "echo_tool", input = "{}"))))
            ),
        )
        assertThrows(CompressionToolCallException::class.java) {
            runBlocking {
                testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateCompressionSummary(
                    settings = testSettings(model),
                    model = model,
                    messages = listOf(UIMessage.user("hello")),
                    inputTransformers = emptyList(),
                    assistant = testAssistant(),
                    memories = emptyList(),
                    toolSnapshotProvider = { fixedToolsSnapshot(listOf(echoTool())) },
                    compressPrompt = "SUMMARIZE",
                    conversationSystemPrompt = null,
                    conversationId = kotlin.uuid.Uuid.random(),
                )
            }
        }
    }

    @Test
    fun `requests after a checkpoint start from the summary`() = runBlocking {
        val model = testChatModel()
        val provider = ScriptedProvider(ArrayDeque(listOf(textStepScript("ok"))))
        val checkpoint = UIMessage.user("[Summary]").copy(isContextCheckpoint = true)
        testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateText(
            settings = testSettings(model),
            model = model,
            messages = listOf(UIMessage.user("old"), UIMessage.assistant("old reply"), checkpoint, UIMessage.user("new")),
            assistant = testAssistant(),
        ).toList()
        val sent = provider.streamMessages.single().filter { it.role != MessageRole.SYSTEM }
        assertEquals(listOf("[Summary]", "new"), sent.map { it.toText() })
        assertTrue(sent.first().isContextCheckpoint)
    }
}
