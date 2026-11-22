package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageUpdate
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** The real loop must publish the same normalized snapshot to messages and successful-step events. */
class StepUsageMergeBoundaryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun generate(attempts: List<ScriptedAttempt>, tools: Boolean = false): List<GenerationChunk> = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(attempts))
        val model = testChatModel()
        testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateText(
            settings = testSettings(model), model = model,
            messages = listOf(UIMessage.user("go")), assistant = testAssistant(),
            tools = if (tools) listOf(echoTool()) else emptyList(),
        ).toList()
    }

    private fun usageScript(updates: List<UsageUpdate>) = ScriptedAttempt(
        listOf(StreamChunk.TextStart("t"), StreamChunk.TextDelta("t", "answer")) +
            updates.map { StreamChunk.Usage(it) } + StreamChunk.Finish("stop")
    )

    @Test
    fun `presence and total boundary matrix agrees across event message and persisted JSON`() {
        data class Case(val updates: List<UsageUpdate>, val expected: TokenUsage?, val complete: Boolean)
        val cases = listOf(
            Case(listOf(UsageUpdate(100, 30, 30, 180)), TokenUsage(100, 30, 30, 180), true),
            Case(listOf(UsageUpdate(totalTokens = 55), UsageUpdate(promptTokens = 40)), TokenUsage(40, 0, 0, 55), false),
            Case(listOf(UsageUpdate(promptTokens = 50), UsageUpdate(completionTokens = 15)), TokenUsage(50, 15, 0, 65), true),
            Case(listOf(UsageUpdate(100, 5, totalTokens = 105), UsageUpdate(totalTokens = 20)), TokenUsage(100, 5, 0, 20), true),
            // Missing total does not silently erase an explicitly reported upstream total.
            Case(listOf(UsageUpdate(100, 5, totalTokens = 105), UsageUpdate(10, 10)), TokenUsage(10, 10, 0, 105), true),
            Case(listOf(UsageUpdate(100, 5, totalTokens = 105), UsageUpdate(10, 10, totalTokens = 20)), TokenUsage(10, 10, 0, 20), true),
            Case(listOf(UsageUpdate(100, 10, 80), UsageUpdate(cachedTokens = 0)), TokenUsage(100, 10, 0, 110), true),
            Case(listOf(UsageUpdate(100, 10, totalTokens = 150), UsageUpdate(totalTokens = 0)), TokenUsage(100, 10, 0, 0), true),
            Case(listOf(UsageUpdate(0, 0, 0, 0)), TokenUsage(), true),
            Case(listOf(UsageUpdate()), null, false),
            Case(listOf(UsageUpdate(100, 10, partial = true)), TokenUsage(100, 10, 0, 110), false),
        )
        cases.forEach { case ->
            val chunks = generate(listOf(usageScript(case.updates)))
            val step = chunks.filterIsInstance<GenerationChunk.StepFinished>().single()
            val message = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last()
            assertEquals(case.toString(), case.expected, step.usage)
            assertEquals(case.expected, message.usage)
            assertEquals(case.complete, message.usageComplete)
            assertEquals(case.expected.takeIf { case.complete }, message.lastUsage)
            val decoded = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(message))
            assertEquals(message, decoded)
            assertEquals(case.expected?.totalTokens?.toLong(),
                GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }.totalTokens)
        }
    }

    @Test
    fun `independent totals add only across successful steps`() {
        val chunks = generate(listOf(
            toolStepScript("c", "echo_tool", usage = TokenUsage(100, 30, 30, 180)),
            textStepScript("done", usage = TokenUsage(200, 40, 100, 300)),
        ), tools = true)
        val steps = chunks.filterIsInstance<GenerationChunk.StepFinished>()
        val message = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last()
        assertEquals(listOf(180, 300), steps.map { it.usage?.totalTokens })
        assertEquals(TokenUsage(300, 70, 130, 480), message.usage)
        assertEquals(TokenUsage(200, 40, 100, 300), message.lastUsage)
        assertEquals(240, message.contextTokens())
        assertEquals(480L, GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }.totalTokens)
    }

    @Test
    fun `failed attempt is replaced not added when retry succeeds`() {
        val chunks = generate(listOf(
            ScriptedAttempt(listOf(
                StreamChunk.TextStart("t"), StreamChunk.TextDelta("t", "partial"),
                StreamChunk.Usage(UsageUpdate(999, 999, totalTokens = 1998)),
            ), IOException("connection reset")),
            textStepScript("full reply", usage = TokenUsage(40, 20, totalTokens = 60)),
        ))
        val step = chunks.filterIsInstance<GenerationChunk.StepFinished>().single()
        val message = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last()
        assertEquals(60, step.usage?.totalTokens)
        assertEquals(step.usage, message.usage)
        assertEquals(1, message.stepCount)
        assertEquals(true, message.usageComplete)
        assertEquals(60L, GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }.totalTokens)
    }

    @Test
    fun `no usage stays null in both message and events`() {
        val chunks = generate(listOf(toolStepScript("c", "echo_tool"), textStepScript("done")), tools = true)
        chunks.filterIsInstance<GenerationChunk.StepFinished>().forEach { assertNull(it.usage) }
        val message = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last()
        assertNull(message.usage)
        assertNull(message.lastRequestUsage())
        assertEquals(false, message.usageComplete)
        assertNull(GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }.totalTokens)
    }
}
