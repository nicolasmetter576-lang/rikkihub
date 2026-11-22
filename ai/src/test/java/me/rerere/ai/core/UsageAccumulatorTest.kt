package me.rerere.ai.core

import org.junit.Assert.*
import org.junit.Test

class UsageAccumulatorTest {
    @Test fun `cache above corrected input stays visible but cannot be considered complete usage`() {
        val usage = UsageAccumulator()
        usage.update(UsageUpdate(100, 20, 106))
        assertEquals(TokenUsage(100, 20, 106, 120), usage.snapshot)
        assertFalse(usage.hasCompleteUsage)
        usage.update(UsageUpdate(cachedTokens = 80))
        assertTrue(usage.hasCompleteUsage)
        usage.update(UsageUpdate(promptTokens = 75))
        assertFalse(usage.hasCompleteUsage)
        usage.update(UsageUpdate(cachedTokens = 0))
        assertTrue(usage.hasCompleteUsage)
    }

    @Test fun `empty and invalid reports are not zero usage`() {
        val usage = UsageAccumulator()
        usage.update(UsageUpdate())
        usage.update(UsageUpdate(-1, -1, -1, -1))
        assertNull(usage.snapshot)
        assertFalse(usage.hasCompleteUsage)
        usage.update(UsageUpdate(0, 0, 0, 0))
        assertEquals(TokenUsage(), usage.snapshot)
        assertTrue(usage.hasCompleteUsage)
    }

    @Test fun `independent total survives component patches including zero and downward corrections`() {
        val usage = UsageAccumulator()
        usage.update(UsageUpdate(100, 30, 30, 180))
        usage.update(UsageUpdate(completionTokens = 20))
        assertEquals(TokenUsage(100, 20, 30, 180), usage.snapshot)
        usage.update(UsageUpdate(promptTokens = 0, cachedTokens = 0, totalTokens = 0))
        assertEquals(TokenUsage(0, 20, 0, 0), usage.snapshot)
        usage.update(UsageUpdate(completionTokens = 0, totalTokens = -1))
        assertEquals(TokenUsage(), usage.snapshot)
        assertTrue(usage.hasCompleteUsage)
    }

    @Test fun `derived total stays derived until an explicit total is reported`() {
        val usage = UsageAccumulator()
        usage.update(UsageUpdate(promptTokens = 100))
        assertEquals(TokenUsage(100, 0, 0, 100), usage.snapshot)
        assertFalse(usage.hasCompleteUsage)
        usage.update(UsageUpdate(completionTokens = 20))
        assertEquals(TokenUsage(100, 20, 0, 120), usage.snapshot)
        assertTrue(usage.hasCompleteUsage)
        usage.update(UsageUpdate(completionTokens = 5))
        assertEquals(105, usage.snapshot?.totalTokens)
        usage.update(UsageUpdate(totalTokens = 105))
        usage.update(UsageUpdate(completionTokens = 10))
        // Equality with p+c at the time it was reported does not make it a derived total.
        assertEquals(105, usage.snapshot?.totalTokens)
    }

    @Test fun `total only is not complete and repeated snapshots never sum`() {
        val usage = UsageAccumulator()
        repeat(3) { usage.update(UsageUpdate(totalTokens = 180)) }
        assertEquals(TokenUsage(totalTokens = 180), usage.snapshot)
        assertFalse(usage.hasCompleteUsage)
        usage.update(UsageUpdate(100, 30))
        assertEquals(TokenUsage(100, 30, 0, 180), usage.snapshot)
        assertTrue(usage.hasCompleteUsage)
    }

    @Test fun `folded partial is replaced by latest completeness and survives missing-pass markers`() {
        val usage = UsageAccumulator()
        usage.update(UsageUpdate(100, 0, partial = true))
        assertFalse(usage.hasCompleteUsage)
        usage.update(UsageUpdate())
        usage.update(UsageUpdate(completionTokens = -1))
        assertFalse(usage.hasCompleteUsage)
        usage.update(UsageUpdate(100, 30))
        assertTrue(usage.hasCompleteUsage)
        usage.update(UsageUpdate(partial = true))
        assertEquals(TokenUsage(100, 30, 0, 130), usage.snapshot)
        assertFalse(usage.hasCompleteUsage)
    }

    @Test fun `materialized compatibility snapshot does not infer absence from zeros`() {
        val usage = UsageAccumulator()
        usage.update(UsageUpdate(TokenUsage(100, 30, 30, 180)))
        usage.update(UsageUpdate(TokenUsage()))
        assertEquals(TokenUsage(), usage.snapshot)
        assertTrue(usage.hasCompleteUsage)
    }
}
