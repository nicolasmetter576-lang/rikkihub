package me.rerere.rikkihub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkihub.ui.components.message.MessagePartBlock
import me.rerere.rikkihub.ui.components.message.ThinkingStep
import me.rerere.rikkihub.ui.components.message.groupMessageParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportContentTest {
    private val hidden = ImageExportOptions(includeReasoning = false)
    private val text = UIMessagePart.Text("answer")
    private val reasoning = UIMessagePart.Reasoning("private thought\nsecond line")
    private val tool = UIMessagePart.Tool(
        toolCallId = "call-1", toolName = "my_overridden_tool", input = "{\"path\":\"file.txt\"}",
        output = listOf(UIMessagePart.Text("result"), UIMessagePart.Reasoning("tool private thought")),
        approvalState = ToolApprovalState.Approved,
    )
    private fun markdown(vararg parts: UIMessagePart, options: ImageExportOptions = ImageExportOptions()): String =
        buildConversationMarkdown("Title", "2026-10-04", listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())), options)

    @Test fun `original positional option still means expand and include defaults on`() {
        assertTrue(ImageExportOptions(true).expandReasoning)
        assertTrue(ImageExportOptions(true).includeReasoning)
        assertFalse(ImageExportOptions().expandReasoning)
    }

    @Test fun `multiple reasoning lines are individually quoted and terminated`() {
        val output = markdown(UIMessagePart.Reasoning("first\nsecond\nthird"))
        assertTrue(output.contains("> first\n> second\n> third\n\n"))
        assertFalse(output.contains("> first> second"))
    }

    @Test fun `CRLF blank and whitespace reasoning lines retain existing blank filtering`() {
        val output = markdown(UIMessagePart.Reasoning("first\r\n\r\n \r\n  second\r\n"))
        assertTrue(output.contains("> first\n>   second\n"))
        assertFalse(output.contains("\r"))
        assertFalse(output.contains("> \n"))
    }

    @Test fun `empty reasoning does not invent quoted text`() {
        assertFalse(markdown(UIMessagePart.Reasoning(" \n\n")).contains("> "))
    }

    @Test fun `disabled reasoning is absent from top level markdown but answer stays`() {
        val output = markdown(reasoning, text, options = hidden)
        assertFalse(output.contains("private thought"))
        assertFalse(output.contains("second line"))
        assertTrue(output.contains("answer"))
    }

    @Test fun `disabled reasoning does not leak through tool output`() {
        val output = markdown(reasoning, tool, text, options = hidden)
        assertFalse(output.contains("private thought"))
        assertTrue(output.contains("result"))
        assertTrue(output.contains("my_overridden_tool"))
        assertTrue(output.contains("Call ID: `call-1`"))
        assertTrue(output.contains("\"path\": \"file.txt\""))
    }

    @Test fun `enabled nested reasoning uses the same multiline quote formatter`() {
        val output = markdown(tool.copy(output = listOf(UIMessagePart.Reasoning("one\ntwo"))))
        assertTrue(output.contains("> one\n> two\n"))
    }

    @Test fun `expand flag does not alter markdown include policy`() {
        assertEquals(markdown(reasoning), markdown(reasoning, options = ImageExportOptions(expandReasoning = true)))
        assertEquals(markdown(reasoning, options = hidden), markdown(reasoning, options = hidden.copy(expandReasoning = true)))
    }

    @Test fun `title role ordering date and separators preserve existing output`() {
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(text)))
        assertEquals("# Chat\n\n*Exported on Today*\n\n**User**:\n\nquestion\n\n---\n**Assistant**:\n\nanswer\n\n---\n",
            buildConversationMarkdown("Chat", "Today", messages))
    }

    @Test fun `images and tool attachments preserve existing export conventions`() {
        val output = buildConversationMarkdown("Title", "Now", listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Image("first.png"), tool.copy(output = listOf(
                UIMessagePart.Image("tool.png"), UIMessagePart.Document("file.pdf", "Report"),
                UIMessagePart.Video("movie.mp4"), UIMessagePart.Audio("sound.mp3"),
            )),
        ))), hidden, imageData = { "data:${it.url}" })
        assertTrue(output.contains("![Image](data:first.png)"))
        assertTrue(output.contains("![Tool Image](data:tool.png)"))
        assertTrue(output.contains("[Document: Report](file.pdf)"))
        assertTrue(output.contains("[Video](movie.mp4)"))
        assertTrue(output.contains("[Audio](sound.mp3)"))
    }

    @Test fun `bad tool input keeps the old safe object fallback`() {
        val output = markdown(tool.copy(input = "not json"))
        assertTrue(output.contains("Input:\n```json\n{}\n```"))
    }

    @Test fun `including reasoning returns the original part list and objects`() {
        val parts = listOf(reasoning, tool, text)
        assertSame(parts, exportedMessageParts(parts, ImageExportOptions()))
    }

    @Test fun `image projection removes reasoning before grouping but retains both tool kinds`() {
        val serverTool = UIMessagePart.ServerTool("server-1", "web_search", status = ServerToolStatus.COMPLETED)
        val parts = listOf(reasoning, tool, serverTool, text)
        val projected = exportedMessageParts(parts, hidden)
        assertEquals(listOf(tool, serverTool, text), projected)
        val groups = projected.groupMessageParts()
        assertEquals(listOf(ThinkingStep.ToolStep(tool), ThinkingStep.ServerToolStep(serverTool)),
            (groups.first() as MessagePartBlock.ThinkingBlock).steps)
        assertSame(tool, projected[0])
        assertSame(serverTool, projected[1])
        assertEquals(listOf(reasoning, tool, serverTool, text), parts)
    }

    @Test fun `reasoning only image message becomes empty instead of an empty thought card`() {
        assertTrue(exportedMessageParts(listOf(reasoning), hidden).isEmptyUIMessage())
    }

    @Test fun `filtering exports never changes source tool output approval or execution status`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning, tool, text))
        val before = message.copy()
        buildConversationMarkdown("Title", "Now", listOf(message), hidden)
        exportedMessageParts(message.parts, hidden)
        assertEquals(before, message)
        assertSame(tool, message.parts[1])
        assertTrue(tool.isExecuted)
        assertEquals(ToolApprovalState.Approved, tool.approvalState)
        assertTrue(tool.output[1] is UIMessagePart.Reasoning)
    }

    @Test fun `reasoning data in plain text or opaque server metadata is not heuristically scrubbed`() {
        val output = markdown(UIMessagePart.Text("literal <think>public quote</think>"), options = hidden)
        assertTrue(output.contains("literal <think>public quote</think>"))
    }
}
