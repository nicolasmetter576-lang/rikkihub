package me.rerere.rikkihub.data.creator

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.creator.storage.CreatorTranscriptStore
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * transcript 存储语义：原子写、未执行 part 固化
 * INTERRUPTED（防旧 part 在新 run 复活执行）、超限拒绝、损坏返回空。
 */
class CreatorTranscriptStoreTest {

    private fun newStore(): Pair<CreatorTranscriptStore, File> {
        val dir = kotlin.io.path.createTempDirectory("creator-tx").toFile()
        return CreatorTranscriptStore(dir, JsonInstant) to dir
    }

    @Test
    fun `executed tool part round trips`() = runBlocking<Unit> {
        val (store, _) = newStore()
        val session = Uuid.random()
        val part = UIMessagePart.Tool(
            toolCallId = "c1", toolName = "creator_list_objects", input = "{}",
            output = listOf(UIMessagePart.Text("ok")),
            approvalState = ToolApprovalState.Approved,
        )
        store.save(session, listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))))
        val loaded = store.load(session)
        assertEquals(listOf(part), loaded.single().parts)
    }

    @Test
    fun `unexecuted tool part is persisted as interrupted`() = runBlocking<Unit> {
        val (store, _) = newStore()
        val session = Uuid.random()
        val part = UIMessagePart.Tool(
            toolCallId = "c1", toolName = "creator_list_objects", input = "{}",
        )
        store.save(session, listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))))
        val loaded = store.load(session)
        val loadedPart = loaded[0].parts[0] as UIMessagePart.Tool
        assertTrue(loadedPart.isExecuted) // interrupted 占位输出 → 不再可恢复执行
        assertTrue(
            (loadedPart.output[0] as UIMessagePart.Text).text.contains("interrupted"),
        )
    }

    @Test
    fun `oversize transcript is refused`() = runBlocking<Unit> {
        val (store, _) = newStore()
        val session = Uuid.random()
        val huge = "x".repeat((CreatorTranscriptStore.MAX_TRANSCRIPT_BYTES + 10).toInt())
        try {
            store.save(session, listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(huge)))))
            error("must reject oversize")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `corrupt file loads empty`() = runBlocking<Unit> {
        val (store, dir) = newStore()
        val session = Uuid.random()
        File(dir, "transcript_$session.json").writeText("{not json")
        assertTrue(store.load(session).isEmpty())
    }

    @Test
    fun `missing file loads empty`() = runBlocking<Unit> {
        val (store, _) = newStore()
        assertTrue(store.load(Uuid.random()).isEmpty())
    }
}
