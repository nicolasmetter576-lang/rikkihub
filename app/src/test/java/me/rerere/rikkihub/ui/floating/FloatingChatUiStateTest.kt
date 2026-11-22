package me.rerere.rikkihub.ui.floating

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.GenerationPhase
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.service.ChatError
import me.rerere.rikkihub.service.FloatingChatWindow
import me.rerere.rikkihub.service.MessageQueueState
import me.rerere.rikkihub.service.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FloatingChatUiStateTest {
    private fun state(
        conversation: Conversation,
        replyMessageId: Uuid?,
        generating: Boolean = false,
        queue: MessageQueueState = MessageQueueState(),
        error: ChatError? = null,
    ) = buildFloatingChatUiState(
        conversation = conversation,
        replyMessageId = replyMessageId,
        generating = generating,
        compressing = false,
        processingStatus = null,
        queue = queue,
        error = error,
    )

    private fun pendingToolReply() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "1",
                toolName = "shell",
                input = "{}",
                approvalState = ToolApprovalState.Pending
            )
        ),
    )

    // 重新生成较早的助手消息：回复写回那个节点的新分支，后面的节点保留
    private fun regenerated(newBranch: UIMessage, selectIndex: Int = 1): Conversation {
        val node =
            MessageNode(messages = listOf(UIMessage.assistant("old answer"), newBranch), selectIndex = selectIndex)
        return Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(
                UIMessage.user("first").toMessageNode(),
                node,
                UIMessage.user("second").toMessageNode(),
                UIMessage.assistant("tail answer").toMessageNode(),
            )
        )
    }

    @Test
    fun `regenerating an earlier reply previews that reply instead of the tail`() {
        val reply = UIMessage.assistant("new answer")
        val state = state(regenerated(reply), reply.id, generating = true)
        assertEquals("new answer", state.preview)
        assertEquals(GenerationPhase.Writing("new answer"), state.phase)
    }

    @Test
    fun `switching the node back to the old branch keeps previewing the generating reply`() {
        val reply = UIMessage.assistant("new answer")
        val state = state(regenerated(reply, selectIndex = 0), reply.id, generating = true)
        assertEquals("new answer", state.preview)
    }

    @Test
    fun `pending approval in an earlier node is reported`() {
        val reply = pendingToolReply()
        val conversation = regenerated(reply)
        assertEquals(listOf("1"), state(conversation, reply.id).pendingTools.map { it.toolCallId })
        // 即使不知道本轮写的是哪条消息，审批也不能漏
        assertEquals(1, state(conversation, replyMessageId = null).pendingTools.size)
    }

    @Test
    fun `new turn without output yet does not show the previous reply`() {
        val state = state(regenerated(UIMessage.assistant("new answer")), replyMessageId = null, generating = true)
        assertFalse(state.hasReply)
        assertEquals("", state.preview)
    }

    @Test
    fun `cli approval asked in the middle of a running turn is shown while generating`() {
        val reply = pendingToolReply()
        val state = state(regenerated(reply), reply.id, generating = true)
        assertTrue(state.generating)
        assertEquals(listOf("1"), state.pendingTools.map { it.toolCallId })
        assertFalse(state.failed)
    }

    @Test
    fun `reasoning is previewed until the reply text starts`() {
        val thinking = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "  pondering  ", finishedAt = null)),
        )
        val state = state(regenerated(thinking), thinking.id, generating = true)
        assertEquals("pondering", state.preview)
        assertTrue(state.previewIsReasoning)

        val writing = thinking.copy(parts = thinking.parts + UIMessagePart.Text("answer"))
        val written = state(regenerated(writing), writing.id, generating = true)
        assertEquals("answer", written.preview)
        assertFalse(written.previewIsReasoning)
    }

    @Test
    fun `error only counts as failed once the turn stopped`() {
        val reply = UIMessage.assistant("partial")
        val error = ChatError(error = IllegalStateException("boom"))
        assertFalse(state(regenerated(reply), reply.id, generating = true, error = error).failed)
        assertTrue(state(regenerated(reply), reply.id, generating = false, error = error).failed)
    }

    @Test
    fun `queue size and pause come from the message queue`() {
        val queue = MessageQueueState(
            messages = listOf(
                QueuedMessage(parts = listOf(UIMessagePart.Text("a"))),
                QueuedMessage(parts = listOf(UIMessagePart.Text("b"))),
            ),
            paused = true,
        )
        val state = state(regenerated(UIMessage.assistant("x")), null, queue = queue)
        assertEquals(2, state.queuedCount)
        assertTrue(state.queuePaused)
    }

    @Test
    fun `long reply preview keeps only the tail starting at a line break`() {
        val head = "head line\n".repeat(400)
        val tail = "last line"
        val preview = previewTail(head + tail)
        assertTrue(preview.length <= 3000)
        assertTrue(preview.endsWith(tail))
        assertTrue(preview.startsWith("head line") || preview.startsWith(tail))
        assertEquals("short", previewTail("short"))
    }

    @Test
    fun `turn error ignores earlier turns, other conversations and errors that leave the reply intact`() {
        val id = Uuid.random()
        val other = Uuid.random()
        val old = ChatError(error = IllegalStateException("old"), conversationId = id, timestamp = 100)
        val elsewhere = ChatError(error = IllegalStateException("x"), conversationId = other, timestamp = 300)
        val title = ChatError(error = IllegalStateException("title"), conversationId = id, timestamp = 400, affectsReply = false)
        assertEquals(null, turnError(listOf(old, elsewhere, title), id, since = 200))
        assertEquals(null, turnError(listOf(old), id, since = null))

        val failed = ChatError(error = IllegalStateException("boom"), conversationId = id, timestamp = 250)
        assertEquals(failed, turnError(listOf(old, failed, elsewhere, title), id, since = 200))
    }

    @Test
    fun `window mode is the panel when expanded, the strip only while generating with the strip on`() {
        val id = Uuid.random()
        assertEquals(null, floatingWindowMode(null, generating = true, stripEnabled = true))
        assertEquals(FloatingWindowMode.Panel, floatingWindowMode(FloatingChatWindow(id, expanded = true), true, true))
        assertEquals(FloatingWindowMode.Strip, floatingWindowMode(FloatingChatWindow(id, expanded = false), true, true))
        assertEquals(FloatingWindowMode.Bubble, floatingWindowMode(FloatingChatWindow(id, expanded = false), true, false))
        assertEquals(FloatingWindowMode.Bubble, floatingWindowMode(FloatingChatWindow(id, expanded = false), false, true))
    }

    @Test
    fun `bubble badge puts a pending approval above a running turn, and an error above a finished reply`() {
        val pending = pendingToolReply()
        assertEquals(BubbleBadge.Pending, state(regenerated(pending), pending.id, generating = true).bubbleBadge())

        val reply = UIMessage.assistant("done")
        val error = ChatError(error = IllegalStateException("boom"))
        assertEquals(BubbleBadge.Busy, state(regenerated(reply), reply.id, generating = true, error = error).bubbleBadge())
        assertEquals(BubbleBadge.Error, state(regenerated(reply), reply.id, error = error).bubbleBadge())
        assertEquals(BubbleBadge.Done, state(regenerated(reply), reply.id).bubbleBadge())
        assertEquals(BubbleBadge.None, state(regenerated(reply), replyMessageId = null).bubbleBadge())
    }
}
