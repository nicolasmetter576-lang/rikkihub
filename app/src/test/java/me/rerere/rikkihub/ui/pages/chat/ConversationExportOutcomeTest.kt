package me.rerere.rikkihub.ui.pages.chat

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

class ConversationExportOutcomeTest {
    @Test fun `success and dismissal happen only after every export stage completes`() = runBlocking {
        val events = mutableListOf<String>()
        performConversationExport(
            export = { events += listOf("capture", "encode", "save", "share") },
            onSuccess = { events += listOf("success", "dismiss") }, onFailure = { error("failure") },
        )
        assertEquals(listOf("capture", "encode", "save", "share", "success", "dismiss"), events)
    }

    @Test fun `suspended export cannot produce an optimistic success or dismissal`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var succeeded = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            performConversationExport({ entered.complete(Unit); release.await() }, { succeeded = true }, { error("failure") })
        }
        entered.await()
        assertFalse(succeeded)
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.await()
        assertTrue(succeeded)
    }

    private suspend fun assertFailure(failure: Exception) {
        var reported: Exception? = null
        performConversationExport({ throw failure }, { error("must not announce success or dismiss") }, { reported = it })
        assertSame(failure, reported)
    }

    @Test fun `capture failure does not announce success`() = runBlocking { assertFailure(IllegalStateException("capture failed")) }
    @Test fun `file write failure does not announce success`() = runBlocking { assertFailure(IOException("write failed")) }
    @Test fun `encoding false promoted to exception does not announce success`() = runBlocking { assertFailure(IllegalStateException("PNG encoding failed")) }
    @Test fun `gallery permission request is not a completed export`() = runBlocking { assertFailure(SecurityException("permission required")) }
    @Test fun `sharing failure does not announce overall success`() = runBlocking { assertFailure(IllegalStateException("no activity")) }

    @Test fun `delayed IO failure is reported once instead of after a success toast`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failures = 0
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            performConversationExport({ entered.complete(Unit); release.await(); throw IOException("late") },
                { error("success") }, { failures++ })
        }
        entered.await()
        assertEquals(0, failures)
        release.complete(Unit)
        job.await()
        assertEquals(1, failures)
    }

    @Test fun `cancellation propagates without failure toast or dismissal`() = runBlocking {
        val cancellation = CancellationException("navigation")
        assertSame(cancellation, runCatching {
            performConversationExport({ throw cancellation }, { error("success") }, { error("failure") })
        }.exceptionOrNull())
    }

    @Test fun `cancelling a suspended export has no outcome feedback`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reported = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            performConversationExport({ entered.complete(Unit); release.await() }, { reported = true }, { reported = true })
        }
        entered.await()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertFalse(reported)
    }

    @Test fun `cancellation after export work suppresses stale success`() = runBlocking {
        var reported = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            performConversationExport({ cancel() }, { reported = true }, { reported = true })
        }
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertFalse(reported)
    }

    @Test fun `fatal errors and callback errors are not remapped as export failures`() = runBlocking {
        val fatal = AssertionError("fatal")
        assertSame(fatal, runCatching {
            performConversationExport({ throw fatal }, { error("success") }, { error("failure") })
        }.exceptionOrNull())
        val callback = IllegalStateException("callback")
        assertSame(callback, runCatching {
            performConversationExport({}, { throw callback }, { error("must not report a second outcome") })
        }.exceptionOrNull())
    }
}
