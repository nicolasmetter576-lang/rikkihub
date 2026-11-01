package me.rerere.rikkihub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.GenerationAttemptProtocolException
import me.rerere.rikkihub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class GenerationFinalSaveTest {
    @Test
    fun `failed final save never replaces a network or cancellation failure`() = runBlocking {
        for (original in listOf(IOException("network lost"), CancellationException("stopped"))) {
            val id = Uuid.random()
            val session = ConversationSession(id, Conversation.ofId(id), this, {})
            val saveError = IOException("disk full")
            var reported: Exception? = null
            val result = runCatching {
                flow<Unit> { throw original }.onCompletion { cause ->
                    session.finishGenerationAfterStream(cause, { throw saveError }, { reported = it })
                }.collect {}
            }
            assertSame(original, result.exceptionOrNull())
            // Coroutine stack-trace recovery may clone an IOException, retaining the original as cause.
            assertTrue(generateSequence(reported as Throwable?) { it.cause }.any { it === saveError })
            session.cleanup()
        }
    }

    @Test
    fun `successful stream reports final persistence failure instead of claiming success`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), this, {})
        val saveError = IOException("disk full")
        val result = runCatching {
            session.finishGenerationAfterStream(null, { throw saveError }, { fail("must propagate") })
        }
        assertTrue(generateSequence(result.exceptionOrNull()) { it.cause }.any { it === saveError })
        session.cleanup()
    }

    @Test
    fun `protocol error never persists provisional parts and rollback preserves metadata and files`() = runBlocking {
        val id = Uuid.random()
        val base = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("previous step")))
        val invalid = base.copy(parts = base.parts + UIMessagePart.Tool(
            toolCallId = "provisional", toolName = "edit_file", input = "{\"path\":\"file:///upload/original\"}",
        ))
        val session = ConversationSession(id, Conversation.ofId(id).updateCurrentMessages(listOf(invalid)), this, {})
        session.updateState { it.copy(isPinned = true) }
        val failure = GenerationAttemptProtocolException(
            ToolStreamProtocolException("conflicting_arguments", "fixture"), id, base.id, 0, base,
        )
        var writes = 0
        session.finishGenerationAfterStream(failure, { writes++ }, { fail("unexpected save") })
        assertEquals(0, writes)
        assertTrue(session.rollbackProtocolFailedAttempt(failure))
        // Match ChatService's no-GC protocol persistence path, not the normal attachment-replacing save.
        session.withPersistence {
            val restored = session.replaceConversation(session.state.value)
            assertEquals(listOf(base), restored.currentMessages)
            assertTrue(restored.isPinned)
            writes++
        }
        assertEquals(1, writes)
        assertFalse(session.rollbackProtocolFailedAttempt(failure))
        session.cleanup()
    }
}
