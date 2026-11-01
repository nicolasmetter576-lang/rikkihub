package me.rerere.rikkihub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class MessageNodePageReadTest {
    @Test
    fun `cancellation is rethrown instead of being skipped as a broken page`() = runBlocking {
        val cancellation = CancellationException("left the conversation")
        val skipped = mutableListOf<Throwable>()
        try {
            readNodePageOrSkip<String>(read = { throw cancellation }, onSkip = { skipped += it })
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertEquals(emptyList<Throwable>(), skipped)
    }

    @Test
    fun `a broken page is skipped`() = runBlocking {
        val broken = IllegalStateException("Couldn't read row 3, col 2 from CursorWindow")
        val skipped = mutableListOf<Throwable>()

        assertNull(readNodePageOrSkip<String>(read = { throw broken }, onSkip = { skipped += it }))
        assertEquals(listOf<Throwable>(broken), skipped)
    }

    @Test
    fun `a readable page is returned`() = runBlocking {
        assertEquals(listOf("a", "b"), readNodePageOrSkip(read = { listOf("a", "b") }, onSkip = { fail() }))
    }
}
