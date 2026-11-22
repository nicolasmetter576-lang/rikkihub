package me.rerere.rikkihub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaRefreshGateTest {
    @Test fun `three minute limit is per account and includes failed attempts`() {
        var now = 1_000_000L
        val gate = QuotaRefreshGate(180_000) { now }
        assertTrue(gate.tryAcquire("devin:a", 0))
        assertFalse(gate.tryAcquire("devin:a", 0))
        assertTrue(gate.tryAcquire("devin:b", 0))
        now += 179_999
        assertFalse(gate.tryAcquire("devin:a", 0))
        now++
        assertTrue(gate.tryAcquire("devin:a", 0))
    }

    @Test fun `fresh repository samples and clock rollback cannot bypass the guard`() {
        var now = 1_000_000L
        val gate = QuotaRefreshGate(180_000) { now }
        assertFalse(gate.tryAcquire("antigravity:a", now - 1000))
        now += 178_999
        assertFalse(gate.tryAcquire("antigravity:a", 0))
        now++
        assertTrue(gate.tryAcquire("antigravity:a", 0))
        now -= 200_000
        assertFalse(gate.tryAcquire("antigravity:a", 0))
    }
}
