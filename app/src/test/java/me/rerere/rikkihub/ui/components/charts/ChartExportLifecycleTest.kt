package me.rerere.rikkihub.ui.components.charts

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.ui.pages.chat.performConversationExport
import org.junit.Assert.*
import org.junit.Test

class ChartExportLifecycleTest {
    private suspend fun export(
        events: MutableList<String>,
        failStage: String? = null,
        failure: Exception = IOException("failure"),
        gate: (suspend (String) -> Unit)? = null,
        cleanupThrows: Boolean = false,
    ) {
        suspend fun stage(name: String) {
            events += name
            gate?.invoke(name)
            if (failStage == name) throw failure
        }
        exportChartResource(
            capture = { stage("capture"); Any() },
            encode = { stage("encode") }, gallery = { stage("gallery") }, share = { stage("share") },
            recycle = { events += "recycle" },
            cleanupFailedFile = { events += "cleanup"; if (cleanupThrows) throw IOException("cleanup") },
        )
    }
    @Test fun `successful PNG lifecycle awaits encode gallery and share then recycles before feedback`() = runBlocking {
        val events = mutableListOf<String>()
        performConversationExport({ export(events) }, { events += "success" }, { error("failure") })
        assertEquals(listOf("capture", "encode", "gallery", "share", "recycle", "success"), events)
        // Success retains owned PNG cache file because a share receiver may read it asynchronously.
        assertFalse(events.contains("cleanup"))
    }
    @Test fun `capture failure cleans cache but cannot recycle a resource not returned`() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(runCatching { export(events, "capture") }.exceptionOrNull() is IOException)
        assertEquals(listOf("capture", "cleanup"), events)
    }
    private suspend fun failure(stage: String, expected: List<String>) {
        val events = mutableListOf<String>()
        var failures = 0
        performConversationExport({ export(events, stage) }, { error("optimistic success") }, { failures++ })
        assertEquals(expected, events); assertEquals(1, failures)
    }
    @Test fun `failed file write or checked PNG compression never reaches gallery`() = runBlocking {
        failure("encode", listOf("capture", "encode", "recycle", "cleanup"))
    }
    @Test fun `gallery permission insert stream or encoding failure never reaches share`() = runBlocking {
        failure("gallery", listOf("capture", "encode", "gallery", "recycle", "cleanup"))
    }
    @Test fun `share failure is not a successful manual export`() = runBlocking {
        failure("share", listOf("capture", "encode", "gallery", "share", "recycle", "cleanup"))
    }
    @Test fun `every stage cancellation propagates unchanged without feedback`() = runBlocking {
        listOf("capture", "encode", "gallery", "share").forEach { stage ->
            val events = mutableListOf<String>()
            val cancellation = CancellationException(stage)
            val failure = runCatching {
                performConversationExport({ export(events, stage, cancellation) }, { error("success") }, { error("failure toast") })
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertEquals("cleanup", events.last())
            assertEquals(if (stage == "capture") 0 else 1, events.count { it == "recycle" })
        }
    }
    @Test fun `suspended share cannot yield early feedback or recycling`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            performConversationExport({ export(events, gate = { if (it == "share") { entered.complete(Unit); release.await() } }) },
                { events += "success" }, { error("failure") })
        }
        entered.await()
        assertFalse(events.contains("success")); assertFalse(events.contains("recycle")); assertFalse(job.isCompleted)
        release.complete(Unit); job.await()
        assertEquals(listOf("recycle", "success"), events.takeLast(2))
    }
    @Test fun `cancelling suspended export recycles once and cleans owned file without feedback`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            performConversationExport({ export(events, gate = { if (it == "gallery") { entered.complete(Unit); release.await() } }) },
                { error("success") }, { error("failure toast") })
        }
        entered.await(); job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("capture", "encode", "gallery", "recycle", "cleanup"), events)
    }
    @Test fun `already cancelled coroutine cannot allocate a bitmap`() = runBlocking {
        val events = mutableListOf<String>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            export(events)
        }
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("cleanup"), events)
    }
    @Test fun `cancelled context after share suppresses success and cleans own file`() = runBlocking {
        val events = mutableListOf<String>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            performConversationExport({ export(events, gate = { if (it == "share") currentCoroutineContext().cancel() }) },
                { error("success") }, { error("failure") })
        }
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("recycle", "cleanup"), events.takeLast(2))
    }
    @Test fun `best effort own cache cleanup does not replace original stage error`() = runBlocking {
        val original = IOException("encoding")
        assertSame(original, runCatching { export(mutableListOf(), "encode", original, cleanupThrows = true) }.exceptionOrNull())
    }
    @Test fun `failure in success callback is not reported as another export outcome`() = runBlocking {
        val events = mutableListOf<String>()
        val callback = IllegalStateException("toast")
        assertSame(callback, runCatching {
            performConversationExport({ export(events) }, { throw callback }, { error("second outcome") })
        }.exceptionOrNull())
        assertEquals("recycle", events.last())
    }
}
