package me.rerere.rikkihub.ui.pages.log

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LogClipboardTest {
    @Test fun `successful copy preserves full body without failure feedback`() = runBlocking {
        val body = "{\"text\":\"多行\\nbody\"}".repeat(100_000)
        var copied: String? = null
        copyLogBodyToClipboard({ copied = body }, { error("unexpected failure feedback") })
        assertSame(body, copied)
    }

    @Test fun `wrapped binder runtime failure is reported exactly once`() = runBlocking {
        var reported = 0
        copyLogBodyToClipboard(
            { throw RuntimeException("Binder failed", IOException("transaction too large")) },
            { reported++ },
        )
        assertEquals(1, reported)
    }

    @Test fun `checked clipboard failure is recoverable`() = runBlocking {
        var reported = false
        copyLogBodyToClipboard({ throw IOException("clipboard failed") }, { reported = true })
        assertTrue(reported)
    }

    @Test fun `security failure is recoverable without a second write`() = runBlocking {
        var writes = 0
        var reported = 0
        copyLogBodyToClipboard({ writes++; throw SecurityException("denied") }, { reported++ })
        assertEquals(1, writes)
        assertEquals(1, reported)
    }

    @Test fun `direct cancellation is never reported as clipboard failure`() = runBlocking {
        val cancelled = CancellationException("page left")
        val failure = runCatching {
            copyLogBodyToClipboard({ throw cancelled }, { error("must not toast") })
        }.exceptionOrNull()
        assertSame(cancelled, failure)
    }

    @Test fun `cancelling suspended copy propagates without feedback`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reported = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            copyLogBodyToClipboard({ entered.complete(Unit); release.await() }, { reported = true })
        }
        entered.await()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertFalse(reported)
    }

    @Test fun `a runtime error after job cancellation does not show a stale toast`() = runBlocking {
        var reported = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            copyLogBodyToClipboard({ cancel(); throw RuntimeException("late error") }, { reported = true })
        }
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertFalse(reported)
    }

    @Test fun `fatal errors are not swallowed as clipboard failures`() = runBlocking {
        val fatal = AssertionError("fatal")
        assertSame(fatal, runCatching {
            copyLogBodyToClipboard({ throw fatal }, { error("must not toast") })
        }.exceptionOrNull())
    }

    @Test fun `failure feedback errors are not silently swallowed`() = runBlocking {
        val feedbackFailure = IllegalStateException("host failed")
        assertSame(feedbackFailure, runCatching {
            copyLogBodyToClipboard({ throw IOException("clipboard") }, { throw feedbackFailure })
        }.exceptionOrNull())
    }
}
