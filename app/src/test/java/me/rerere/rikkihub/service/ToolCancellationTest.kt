package me.rerere.rikkihub.service

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class ToolCancellationTest {
    private val started = Instant.parse("2026-10-26T12:00:00Z")
    private val now = Instant.parse("2026-10-26T12:00:07Z")

    @Test
    fun `a call that was running is timed up to the cancel`() {
        val cancelled = UIMessagePart.Tool("c1", "slow", "{}", startedAt = started).cancelledByUser(now)

        assertEquals(started, cancelled.startedAt)
        assertEquals(now, cancelled.finishedAt)
        assertTrue(cancelled.isExecuted)
        assertTrue((cancelled.output.single() as UIMessagePart.Text).text.contains("cancelled"))
    }

    @Test
    fun `a call that never started stays untimed`() {
        val cancelled = UIMessagePart.Tool("c1", "slow", "{}").cancelledByUser(now)

        assertNull(cancelled.startedAt)
        assertNull(cancelled.finishedAt)
        assertTrue(cancelled.isExecuted)
    }

    @Test
    fun `an existing finish time is kept`() {
        val finished = Instant.parse("2026-10-26T12:00:03Z")
        val cancelled = UIMessagePart.Tool("c1", "slow", "{}", startedAt = started, finishedAt = finished).cancelledByUser(now)

        assertEquals(finished, cancelled.finishedAt)
    }
}
