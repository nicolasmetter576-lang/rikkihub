package me.rerere.rikkihub.ui.components.message

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageUsageStatsTest {
    @Serializable
    private data class Expected(
        val contextTokens: Int?,
        val outputTokens: Int?,
        val durationMs: Long?,
        val tps: Double?,
        val estimated: Boolean,
        val steps: Int,
        val usageComplete: Boolean?,
    )

    @Serializable
    private data class Case(val name: String, val message: UIMessage, val expected: Expected)

    @Test
    fun `shared App and Web fixtures verify both numeric values and display eligibility`() {
        val text = checkNotNull(javaClass.getResource("/usage/display-cases.json")).readText()
        val cases = Json.decodeFromString<List<Case>>(text)
        cases.forEach { case ->
            val stats = case.message.nerdStats(checkNotNull(case.message.usage))
            val expected = case.expected
            assertEquals(case.name, expected.contextTokens, stats.contextTokens)
            assertEquals(case.name, expected.outputTokens, stats.outputTokens)
            assertEquals(case.name, expected.durationMs, stats.durationMillis)
            assertEquals(case.name, expected.estimated, stats.estimated)
            assertEquals(case.name, expected.steps, stats.steps)
            assertEquals(case.name, expected.usageComplete, stats.usageComplete)
            if (expected.tps == null) assertNull(case.name, stats.tps)
            else assertEquals(case.name, expected.tps, checkNotNull(stats.tps), 0.000001)
            assertEquals(case.name, stats, case.message.nerdStats(checkNotNull(case.message.usage)))
        }
    }

    @Test
    fun `partial and legacy zeros do not imply reported output`() {
        val usage = TokenUsage(totalTokens = 180)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), usage = usage)
        listOf(null, false).forEach { complete ->
            val stats = message.copy(usageComplete = complete).nerdStats(usage)
            assertNull(stats.outputTokens)
            assertNull(stats.reportedTokens(0))
            assertEquals(180, stats.reportedTokens(180))
        }
        assertEquals(0, message.copy(usageComplete = true).nerdStats(usage).outputTokens)
    }

    @Test
    fun `negative counts and overflowing context are not displayable`() {
        val negative = TokenUsage(promptTokens = -1, completionTokens = -1)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            createdAt = LocalDateTime(2026, 11, 2, 10, 0),
            finishedAt = LocalDateTime(2026, 11, 2, 10, 1),
            generationTimeMs = 1000,
            usageComplete = true,
            usage = negative,
            lastUsage = negative,
        )
        val stats = message.nerdStats(negative)
        assertNull(stats.tps)
        assertNull(stats.outputTokens)
        assertNull(stats.contextTokens)
        val huge = TokenUsage(promptTokens = Int.MAX_VALUE, completionTokens = 1)
        assertNull(message.copy(lastUsage = huge).nerdStats(huge).contextTokens)
    }
}
