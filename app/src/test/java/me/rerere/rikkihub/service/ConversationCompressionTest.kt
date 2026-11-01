package me.rerere.rikkihub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.insertContextCheckpoint
import me.rerere.rikkihub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConversationCompressionTest {
    private fun node(text: String, alternatives: Int = 1, favorite: Boolean = false) = MessageNode(
        messages = (0 until alternatives).map { UIMessage.user("$text#$it") },
        selectIndex = alternatives - 1,
        isFavorite = favorite,
    )

    private fun checkpoint(text: String = "summary") =
        UIMessage.user(text).copy(isContextCheckpoint = true).toMessageNode()

    private val nodes = (1..5).map { node("m$it") }

    @Test
    fun `range is everything after the last checkpoint and nothing when it is the last node`() {
        assertEquals(nodes, compressionRange(nodes))
        val withCheckpoint = nodes.take(2) + checkpoint() + nodes.drop(2)
        assertEquals(nodes.drop(2), compressionRange(withCheckpoint))
        assertNull(compressionRange(nodes + checkpoint()))
        assertNull(compressionRange(emptyList()))
    }

    @Test
    fun `keeping recent nodes leaves them out of the range and after the checkpoint`() {
        assertEquals(nodes.take(3), compressionRange(nodes, keepRecent = 2))
        assertEquals(nodes, compressionRange(nodes, keepRecent = 0))
        // 只算上一个检查点之后的节点
        val withCheckpoint = nodes.take(2) + checkpoint() + nodes.drop(2)
        assertEquals(nodes.drop(2).take(2), compressionRange(withCheckpoint, keepRecent = 1))
        // 保留的比新消息还多或一样多：没有可压缩的
        assertNull(compressionRange(nodes, keepRecent = nodes.size))
        assertNull(compressionRange(nodes, keepRecent = nodes.size + 10))
        assertNull(compressionRange(withCheckpoint, keepRecent = 3))
        assertNull(compressionRange(withCheckpoint, keepRecent = 4))
    }

    @Test
    fun `the checkpoint goes between the summarized part and the kept recent nodes`() {
        val keep = 2
        val cut = nodes.take(nodes.size - keep)
        val updated = conversation(nodes).insertContextCheckpoint(afterNodeId = cut.last().id, summary = "S")!!
        assertEquals(
            nodes.take(3).map { it.id } + updated.messageNodes[3].id + nodes.drop(3).map { it.id },
            updated.messageNodes.map { it.id },
        )
        assertTrue(updated.messageNodes[3].currentMessage.isContextCheckpoint)
        // 提交校验只看切点之前的节点：保留的节点之后怎么变都不影响
        val snapshot = conversation(cut)
        assertNull(compressionCommitError(snapshot, conversation(nodes), busyReason = null))
        assertNull(compressionCommitError(snapshot, conversation(nodes + node("later")), busyReason = null))
    }

    @Test
    fun `cache warm notices a checkpoint inserted before the end of the baseline`() {
        val baseline = nodes.map { it.currentMessage }
        val cut = nodes.take(3)
        val compressed = conversation(nodes).insertContextCheckpoint(afterNodeId = cut.last().id, summary = "S")!!
        assertTrue(compressedAfterBaseline(baseline, compressed.currentMessages))
        // 基线里已有的检查点（更早的压缩）仍然不算
        val earlier = conversation(listOf(checkpoint()) + nodes).currentMessages
        val again = conversation(listOf(checkpoint()) + nodes)
            .insertContextCheckpoint(afterNodeId = nodes[2].id, summary = "S2")!!
        assertTrue(compressedAfterBaseline(earlier, again.currentMessages))
        assertTrue(!compressedAfterBaseline(earlier, earlier + UIMessage.user("next")))
    }

    @Test
    fun `unfinished tool calls in the range block compression`() {
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c1", "write", "{}", approvalState = ToolApprovalState.Pending)),
        ).toMessageNode()
        val done = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c1", "write", "{}", output = listOf(UIMessagePart.Text("ok")))),
        ).toMessageNode()
        val approved = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c1", "write", "{}", approvalState = ToolApprovalState.Approved)),
        ).toMessageNode()
        // 免审批但没有输出（中断残留、旧版本的空输出）：不挡压缩
        val staleAuto = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c1", "silent", "{}")),
        ).toMessageNode()
        assertTrue(listOf(pending).hasUnfinishedToolCalls())
        assertTrue(listOf(approved).hasUnfinishedToolCalls())
        assertTrue(!listOf(done).hasUnfinishedToolCalls())
        assertTrue(!listOf(staleAuto).hasUnfinishedToolCalls())
        assertTrue(!nodes.hasUnfinishedToolCalls())
    }

    @Test
    fun `cache warm stops when a checkpoint is inserted after the baseline`() {
        val baseline = nodes.map { it.currentMessage }
        assertTrue(!compressedAfterBaseline(baseline, baseline))
        assertTrue(!compressedAfterBaseline(baseline, baseline + UIMessage.user("next")))
        assertTrue(compressedAfterBaseline(baseline, baseline + checkpoint().currentMessage))
        // 基线里已有的检查点（更早的压缩）不算
        val earlier = listOf(checkpoint().currentMessage) + baseline
        assertTrue(!compressedAfterBaseline(earlier, earlier + UIMessage.user("next")))
    }

    private fun conversation(nodes: List<MessageNode>, revision: Long = 7, generation: Long = 1) =
        Conversation(assistantId = Uuid.random(), messageNodes = nodes, revision = revision, generation = generation)

    @Test
    fun `commit needs an idle conversation whose summarized range is unchanged`() {
        val snapshot = conversation(nodes)
        assertNull(compressionCommitError(snapshot, snapshot, busyReason = null))
        assertEquals(CompressErrorCodes.CONVERSATION_BUSY, compressionCommitError(snapshot, snapshot, "GENERATING"))
        // 标题、翻译这类写入只升版本，不影响被总结的内容
        assertNull(compressionCommitError(snapshot, snapshot.copy(revision = 8, title = "t"), null))
        // 之后追加的节点（压缩期间被扣住的消息）也不影响
        assertNull(compressionCommitError(snapshot, snapshot.copy(messageNodes = nodes + node("later")), null))
        // 切点没了、范围里删了节点、或切到了别的分支都算变化
        assertEquals(
            CompressErrorCodes.CONVERSATION_CHANGED,
            compressionCommitError(snapshot, snapshot.copy(messageNodes = nodes.dropLast(1)), null),
        )
        assertEquals(
            CompressErrorCodes.CONVERSATION_CHANGED,
            compressionCommitError(snapshot, snapshot.copy(messageNodes = nodes.drop(1)), null),
        )
        val branched = nodes.toMutableList().apply {
            this[1] = this[1].copy(messages = this[1].messages + UIMessage.user("edited"), selectIndex = 1)
        }
        assertEquals(
            CompressErrorCodes.CONVERSATION_CHANGED,
            compressionCommitError(snapshot, snapshot.copy(messageNodes = branched), null),
        )
    }

    @Test
    fun `checkpoint is inserted after the cut and nothing is removed`() {
        val conversation = conversation(nodes + node("later"))
        val updated = conversation.insertContextCheckpoint(afterNodeId = nodes.last().id, summary = "S")!!
        assertEquals(conversation.messageNodes.size + 1, updated.messageNodes.size)
        val inserted = updated.messageNodes[nodes.size]
        assertTrue(inserted.currentMessage.isContextCheckpoint)
        assertEquals("S", inserted.currentMessage.toText())
        assertEquals(conversation.messageNodes, updated.messageNodes.filterNot { it.currentMessage.isContextCheckpoint })
        assertNull(conversation.insertContextCheckpoint(afterNodeId = Uuid.random(), summary = "S"))
    }

    @Test
    fun `request keeps the additional prompt and the kept message count in bounds`() {
        CompressRequest(additionalPrompt = "x".repeat(CompressRequest.MAX_ADDITIONAL_PROMPT))
        assertThrows(IllegalArgumentException::class.java) {
            CompressRequest(additionalPrompt = "x".repeat(CompressRequest.MAX_ADDITIONAL_PROMPT + 1))
        }
        assertEquals(0, CompressRequest().keepRecent)
        assertEquals(CompressRequest.MAX_KEEP_RECENT, CompressRequest(keepRecent = CompressRequest.MAX_KEEP_RECENT).keepRecent)
        assertThrows(IllegalArgumentException::class.java) { CompressRequest(keepRecent = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            CompressRequest(keepRecent = CompressRequest.MAX_KEEP_RECENT + 1)
        }
    }
}

class ConversationSessionCompressionTest {
    @Test
    fun `a running compression keeps the session in use and is reported as compressing`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            assertNull(session.compressState.value)
            assertTrue(!session.isCompressing && !session.isInUse)
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { kotlinx.coroutines.awaitCancellation() }
            session.compressJob = job
            job.start()
            assertTrue(session.isCompressing)
            assertTrue(session.isInUse)
            job.cancel()
            job.join()
            assertTrue(!session.isCompressing && !session.isInUse)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
