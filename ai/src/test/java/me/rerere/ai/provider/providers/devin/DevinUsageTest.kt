package me.rerere.ai.provider.providers.devin

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageAccumulator
import org.junit.Assert.*
import org.junit.Test

class DevinUsageTest {
    @Test fun `omitted protobuf zero is not an explicit reset and total stays derived`() {
        var raw: DevinUsage? = null
        val accumulator = UsageAccumulator()
        fun accept(next: DevinUsage) {
            raw = raw.mergeFrom(next)
            raw!!.toUsageUpdate()?.let {
                assertNull(it.totalTokens)
                accumulator.update(it)
            }
        }
        accept(DevinUsage(promptTokens = 3, completionTokens = 39))
        accept(DevinUsage(cachedTokens = 100, cacheWriteTokens = 20))
        assertEquals(TokenUsage(123, 39, 100, 162), accumulator.snapshot)
        assertTrue(accumulator.hasCompleteUsage)
        accept(DevinUsage())
        assertEquals(TokenUsage(123, 39, 100, 162), accumulator.snapshot)
        accept(DevinUsage(completionTokens = 10))
        assertEquals(TokenUsage(123, 10, 100, 133), accumulator.snapshot)
    }

    @Test fun `metadata-only protobuf usage is absent and cache-only counts remain partial`() {
        assertNull(parseDevinUsageField(byteArrayOf()).toUsageUpdate())
        assertNull(DevinUsage(modelName = "m", requestId = "r").toUsageUpdate())
        val accumulator = UsageAccumulator()
        accumulator.update(DevinUsage(cachedTokens = 100, completionTokens = 10).toUsageUpdate()!!)
        assertEquals(TokenUsage(100, 10, 100, 110), accumulator.snapshot)
        assertFalse(accumulator.hasCompleteUsage)
    }
}
