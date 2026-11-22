package me.rerere.ai.provider.providers.claude

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageUpdate
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class ClaudePauseTurnUsageTest {
    private val model = Model(modelId = "claude-test")
    private val input = listOf(UIMessage.user("test"))

    @Test fun `stream folds each pass once and completeness can recover from current pass preview`() = runBlocking {
        val passes = listOf(
            flowOf(
                StreamChunk.Usage(UsageUpdate(10, 1)),
                StreamChunk.Usage(UsageUpdate(completionTokens = 2)),
                StreamChunk.Usage(UsageUpdate(completionTokens = 2)),
                StreamChunk.Finish("pause_turn"),
            ),
            flowOf(
                StreamChunk.Usage(UsageUpdate(promptTokens = 5)),
                StreamChunk.Usage(UsageUpdate(completionTokens = 3)),
                StreamChunk.Finish("end_turn"),
            ),
        )
        var pass = 0
        val chunks = streamClaudeWithPauseTurn(input, model) { passes[pass++] }.toList()
        val handler = StreamChunkHandler()
        var messages = input
        val completeness = mutableListOf<Boolean>()
        chunks.forEach {
            messages = handler.handle(messages, it)
            if (it is StreamChunk.Usage) {
                assertNull("Claude total must remain derived", it.update.totalTokens)
                completeness += handler.hasCompleteUsage
            }
        }
        assertTrue(completeness.contains(false))
        assertTrue(handler.hasCompleteUsage)
        assertEquals(TokenUsage(15, 5, 0, 20), handler.usage)
        assertEquals(handler.usage, messages.last().usage)
        assertEquals(listOf("end_turn"), chunks.filterIsInstance<StreamChunk.Finish>().map { it.finishReason })
    }

    @Test fun `missing first or last stream pass cannot inherit previous completeness`() = runBlocking {
        for (missing in 0..1) {
            var pass = 0
            val chunks = streamClaudeWithPauseTurn(input, model) {
                val index = pass++
                val finish = StreamChunk.Finish(if (index == 0) "pause_turn" else "end_turn")
                if (index == missing) flowOf(finish)
                else flowOf(StreamChunk.Usage(UsageUpdate(5, 3)), finish)
            }.toList()
            val handler = StreamChunkHandler()
            chunks.fold(input) { messages, chunk -> handler.handle(messages, chunk) }
            assertEquals(TokenUsage(5, 3, 0, 8), handler.usage)
            assertFalse(handler.hasCompleteUsage)
        }
    }

    @Test fun `entirely missing stream usage remains null instead of fake all-zero`() = runBlocking {
        var pass = 0
        val handler = StreamChunkHandler()
        streamClaudeWithPauseTurn(input, model) {
            flowOf(StreamChunk.Finish(if (pass++ == 0) "pause_turn" else "end_turn"))
        }.toList().fold(input) { messages, chunk -> handler.handle(messages, chunk) }
        assertNull(handler.usage)
        assertFalse(handler.hasCompleteUsage)
    }

    @Test fun `non-stream sum carries missing and partial pass completeness`() = runBlocking {
        for (firstComplete in listOf(true, false, null)) {
            for (missing in listOf(false, true)) {
                var pass = 0
                val result = generateClaudeWithPauseTurn(input, model) {
                    if (pass++ == 0) result(
                        usage = if (missing) null else TokenUsage(10, 2, 0, 12),
                        complete = if (missing) false else firstComplete,
                        finish = "pause_turn",
                    ) else result(TokenUsage(5, 3, 0, 8), true, "end_turn")
                }
                assertEquals(if (missing) TokenUsage(5, 3, 0, 8) else TokenUsage(15, 5, 0, 20), result.usage)
                assertEquals(!missing && firstComplete == true, result.hasCompleteUsage)
                assertEquals(result.usage, result.message.usage)
            }
        }
    }

    private fun result(usage: TokenUsage?, complete: Boolean?, finish: String) = TextGenerationResult(
        id = "r", model = model.modelId,
        message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
        usage = usage, hasCompleteUsage = complete, finishReason = finish,
    )
}
