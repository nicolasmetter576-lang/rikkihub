package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.core.sum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 多步工具调用的 usage 累加、最后一次请求与生成耗时（上游 e7f2c04 / 660b5d6）。 */
class MessageStepStatsTest {

    private fun reply(usage: TokenUsage?) =
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), usage = usage)

    @Test
    fun `sum adds every field and treats null as nothing`() {
        val a = TokenUsage(promptTokens = 100, completionTokens = 10, cachedTokens = 80, totalTokens = 110)
        val b = TokenUsage(promptTokens = 150, completionTokens = 20, cachedTokens = 100, totalTokens = 170)
        assertEquals(TokenUsage(250, 30, 180, 280), a.sum(b))
        assertEquals(a, a.sum(null))
        assertEquals(b, (null as TokenUsage?).sum(b))
        assertNull((null as TokenUsage?).sum(null))
    }

    @Test
    fun `merge still keeps the latest snapshot inside one request`() {
        val first = TokenUsage(promptTokens = 100)
        assertEquals(TokenUsage(100, 20, 0, 120), first.merge(TokenUsage(completionTokens = 20)))
    }

    @Test
    fun `steps accumulate usage and time while last usage is the newest request`() {
        val step1 = reply(TokenUsage(promptTokens = 100, completionTokens = 10))
            .withStepStats(stepBase = null, stepTimeMillis = 400)
        assertEquals(1, step1.stepCount)
        assertEquals(400L, step1.generationTimeMs)
        assertEquals(TokenUsage(100, 10), step1.lastUsage)

        val step2 = step1.copy(usage = TokenUsage(promptTokens = 150, completionTokens = 20), generationTimeMs = null)
            .withStepStats(stepBase = step1, stepTimeMillis = 600)
        assertEquals(TokenUsage(250, 30), step2.usage)
        assertEquals(TokenUsage(150, 20), step2.lastUsage)
        assertEquals(2, step2.stepCount)
        assertEquals(1000L, step2.generationTimeMs)
        assertEquals(170, step2.contextTokens())
    }

    @Test
    fun `a step without usage keeps the previous last usage`() {
        val step1 = reply(TokenUsage(promptTokens = 100, completionTokens = 10)).withStepStats(null, 100)
        val step2 = step1.copy(usage = null).withStepStats(step1, 100)
        assertEquals(TokenUsage(100, 10), step2.usage)
        assertEquals(TokenUsage(100, 10), step2.lastUsage)
        assertEquals(2, step2.stepCount)
    }

    @Test
    fun `continuing a message saved before stats existed does not invent a duration`() {
        // 升级前的消息：usage 只有最后一步，没有耗时和步数
        val legacy = reply(TokenUsage(promptTokens = 100, completionTokens = 10))
        val next = legacy.copy(usage = TokenUsage(promptTokens = 120, completionTokens = 5))
            .withStepStats(stepBase = legacy, stepTimeMillis = 300)
        assertNull(next.generationTimeMs)
        assertEquals(2, next.stepCount)
        assertEquals(TokenUsage(220, 15), next.usage)
        assertEquals(TokenUsage(120, 5), next.lastUsage)
    }
}
