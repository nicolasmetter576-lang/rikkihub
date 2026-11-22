package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.sum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStepStatsTest {
    private fun reply(usage: TokenUsage?) =
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), usage = usage)

    @Test
    fun `sum adds fields and treats null as no reported usage`() {
        val a = TokenUsage(100, 10, 80, 110)
        val b = TokenUsage(150, 20, 100, 170)
        assertEquals(TokenUsage(250, 30, 180, 280), a.sum(b))
        assertEquals(a, a.sum(null))
        assertEquals(b, (null as TokenUsage?).sum(b))
        assertNull((null as TokenUsage?).sum(null))
    }

    @Test
    fun `complete steps accumulate while the current request stays separate`() {
        val first = reply(TokenUsage(100, 10, totalTokens = 110))
            .withStepStats(null, 400, hasCompleteUsage = true, stepFinished = true)
        val second = first.copy(usage = TokenUsage(150, 20, totalTokens = 170))
            .withStepStats(first, 600, hasCompleteUsage = true, stepFinished = true)
        assertEquals(TokenUsage(250, 30, totalTokens = 280), second.usage)
        assertEquals(TokenUsage(150, 20, totalTokens = 170), second.lastUsage)
        assertEquals(2, second.stepCount)
        assertEquals(1000L, second.generationTimeMs)
        assertEquals(170, second.contextTokens())
        assertTrue(second.usageComplete == true)
    }

    @Test
    fun `missing usage preserves known totals but not a stale current snapshot`() {
        val first = reply(TokenUsage(100, 50, totalTokens = 150))
            .withStepStats(null, 500, true, true)
        val second = first.copy(usage = null).withStepStats(first, 4500, false, true)
        assertEquals(first.usage, second.usage)
        assertEquals(5000L, second.generationTimeMs)
        assertEquals(2, second.stepCount)
        assertFalse(second.usageComplete!!)
        assertNull(second.lastUsage)
        assertNull(second.lastRequestUsage())
        assertNull(second.contextTokens())
        val third = second.copy(usage = TokenUsage(200, 10, totalTokens = 210))
            .withStepStats(second, 100, true, true)
        assertFalse(third.usageComplete!!)
        assertEquals(210, third.contextTokens())
    }

    @Test
    fun `previews do not poison coverage or add the same step repeatedly`() {
        val first = reply(TokenUsage(100, 10, totalTokens = 110)).withStepStats(null, 100, true, true)
        val current = first.copy(usage = TokenUsage(150, 20, totalTokens = 170))
        val preview = current.withStepStats(first, 200, true, false)
        val finished = current.withStepStats(first, 300, true, true)
        assertFalse(preview.usageComplete!!)
        assertTrue(finished.usageComplete!!)
        assertEquals(preview.usage, finished.usage)
        assertEquals(2, finished.stepCount)
        assertEquals(400L, finished.generationTimeMs)
    }

    @Test
    fun `legacy continuation does not invent completeness or timing`() {
        val legacy = reply(TokenUsage(100, 10))
        assertEquals(110, legacy.contextTokens())
        val next = legacy.copy(usage = TokenUsage(120, 5))
            .withStepStats(legacy, 300, true, true)
        assertNull(next.generationTimeMs)
        assertFalse(next.usageComplete!!)
        assertEquals(2, next.stepCount)
        assertEquals(TokenUsage(220, 15), next.usage)
        assertEquals(TokenUsage(120, 5), next.lastUsage)
    }

    @Test
    fun `total only reports are not an input output snapshot`() {
        val message = reply(TokenUsage(totalTokens = 180)).withStepStats(null, 200, false, true)
        assertEquals(180, message.usage?.totalTokens)
        assertFalse(message.usageComplete!!)
        assertNull(message.contextTokens())
    }

    @Test
    fun `explicit complete zeros remain complete without inventing time`() {
        val message = reply(TokenUsage()).withStepStats(null, -100, true, true)
        assertTrue(message.usageComplete!!)
        assertEquals(0, message.contextTokens())
        assertEquals(0L, message.generationTimeMs)
    }
}
