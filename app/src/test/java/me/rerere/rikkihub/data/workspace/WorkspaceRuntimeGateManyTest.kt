package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

/** Bulk runtime regressions only; the repository owns the outer Room transaction. */
class WorkspaceRuntimeGateManyTest {
    private fun specs(vararg ids: String): Map<String, WorkspaceChangeSpec?> =
        ids.associateWith { WorkspaceChangeSpec.toolPolicy(setOf("subagent")) }

    @Test
    fun `bulk deduplicates sorts seals all and cancels all before joining any`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000)
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val bCancelled = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val a = async {
            runCatching {
                gate.withOperation("a", WorkspaceOperationKind.TOOL, setOf("subagent")) {
                    aStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        // Joining a before cancelling b would deadlock until the deadline.
                        withContext(NonCancellable) { bCancelled.await() }
                    }
                }
            }
        }
        val b = async {
            runCatching {
                gate.withOperation("b", WorkspaceOperationKind.CHILD, setOf("subagent")) {
                    bStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        bCancelled.complete(Unit)
                    }
                }
            }
        }
        try {
            aStarted.await()
            bStarted.await()
            gate.addOnBeforeChangeListener { event ->
                assertTrue(gate.snapshot("a").sealed)
                assertTrue(gate.snapshot("b").sealed)
                assertEquals(1L, gate.currentEpoch("a"))
                assertEquals(1L, gate.currentEpoch("b"))
                order += event.workspaceId
            }
            var preparations = 0
            val result = withTimeout(3_000) {
                gate.publishPreparedMany(listOf("b", "a", "b"), {
                    preparations++
                    assertEquals(0L, gate.currentEpoch("a"))
                    assertEquals(0L, gate.currentEpoch("b"))
                    specs("b", "a")
                }) { epochs ->
                    assertEquals(listOf("a", "b"), epochs.keys.toList())
                    assertEquals(listOf("a", "b"), order)
                    assertEquals(0, gate.snapshot("a").activeOperations)
                    assertEquals(0, gate.snapshot("b").activeOperations)
                    "published"
                }
            }
            assertEquals("published", result)
            assertEquals(1, preparations)
            assertTrue(a.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            assertTrue(b.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            for (id in listOf("a", "b")) {
                assertEquals(true, gate.eventsFlow(id).value?.committed)
                assertFalse(gate.snapshot(id).sealed)
            }
        } finally {
            bCancelled.complete(Unit)
            a.cancel()
            b.cancel()
        }
    }

    @Test
    fun `overlapping opposite order publications prepare latest baselines once all gates are acquired`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val firstPrepared = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val rows = mutableMapOf("a" to 0, "b" to 0)
        val first = async {
            gate.publishPreparedMany(listOf("b", "a"), {
                assertEquals(mapOf("a" to 0, "b" to 0), rows)
                firstPrepared.complete(Unit)
                releaseFirst.await()
                specs("a", "b")
            }) { rows.keys.forEach { rows[it] = 1 } }
        }
        try {
            firstPrepared.await()
            val second = async {
                gate.publishPreparedMany(listOf("a", "b"), {
                    assertEquals(mapOf("a" to 1, "b" to 1), rows)
                    specs("a", "b")
                }) { epochs ->
                    assertEquals(WorkspaceEpochs(2, 0), epochs.getValue("a"))
                    rows.keys.forEach { rows[it] = 2 }
                }
            }
            releaseFirst.complete(Unit)
            withTimeout(2_000) { first.await(); second.await() }
            assertEquals(mapOf("a" to 2, "b" to 2), rows)
        } finally {
            releaseFirst.complete(Unit)
        }
    }

    @Test
    fun `missing extra and thrown preparation cause no epochs cancellation events or publication`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val victim = async {
            gate.withOperation("a", WorkspaceOperationKind.TOOL, setOf("subagent")) {
                started.complete(Unit)
                release.await()
            }
        }
        try {
            started.await()
            val invalid = listOf<Map<String, WorkspaceChangeSpec?>>(
                specs("a"), specs("a", "b", "extra"),
            )
            invalid.forEach { map ->
                val result = runCatching {
                    gate.publishPreparedMany(listOf("a", "b"), { map }) { error("must not publish") }
                }
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }
            val thrown = runCatching {
                gate.publishPreparedMany(listOf("a", "b"), { error("prepare refused") }) {
                    error("must not publish")
                }
            }
            assertEquals("prepare refused", thrown.exceptionOrNull()?.message)
            assertFalse(victim.isCancelled)
            for (id in listOf("a", "b")) {
                assertEquals(WorkspaceEpochs(), gate.currentEpochs(id))
                assertEquals(0L, gate.currentToolEpoch(id, "subagent"))
                assertNull(gate.eventsFlow(id).value)
                assertFalse(gate.snapshot(id).sealed)
            }
        } finally {
            release.complete(Unit)
            victim.await()
        }
    }

    @Test
    fun `closed participant prevents preparation and mutation in every participant`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        gate.closeWorkspace("b")
        val result = runCatching {
            gate.publishPreparedMany(listOf("a", "b"), { error("must not prepare") }) {
                error("must not publish")
            }
        }
        assertTrue(result.exceptionOrNull() is WorkspaceUnavailableException)
        assertEquals(WorkspaceEpochs(), gate.currentEpochs("a"))
        assertFalse(gate.snapshot("a").sealed)
        assertEquals(WorkspaceEpochs(1, 1), gate.currentEpochs("b"))
    }

    @Test
    fun `empty participant list supports library only CRUD but still validates prepare keys`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        var preparations = 0
        assertEquals("library", gate.publishPreparedMany(emptyList(), {
            preparations++
            emptyMap()
        }) { epochs ->
            assertTrue(epochs.isEmpty())
            "library"
        })
        assertEquals(1, preparations)
        assertTrue(runCatching {
            gate.publishPreparedMany(emptyList(), { specs("unexpected") }) { error("no write") }
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `null participant is sealed without revocation and holds no admission lock across other joins`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sealed = CompletableDeferred<Unit>()
        val victim = async {
            runCatching {
                gate.withOperation("a", WorkspaceOperationKind.TOOL, setOf("subagent")) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
        }
        val terminal = gate.createHandle("b", WorkspaceHandleClass.USER_TERMINAL, spawn = { "terminal" })
        gate.addOnBeforeChangeListener { if (it.workspaceId == "b") sealed.complete(Unit) }
        try {
            started.await()
            val publication = async {
                gate.publishPreparedMany(listOf("a", "b"), {
                    mapOf("a" to WorkspaceChangeSpec.toolPolicy(setOf("subagent")), "b" to null)
                }) { }
            }
            sealed.await()
            // If b's admission mutex were held for its metadata fast path this probe
            // would block until a's join finishes, rather than observing SEALED now.
            val rejection = withTimeout(500) {
                runCatching {
                    gate.withOperation("b", WorkspaceOperationKind.TOOL, waitIfSealed = false) { error("sealed") }
                }.exceptionOrNull()
            }
            assertEquals(WorkspaceUnavailableReason.SEALED, (rejection as WorkspaceUnavailableException).reason)
            assertFalse(publication.isCompleted)
            release.complete(Unit)
            publication.await()
            assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            assertEquals(1L, gate.currentEpoch("b"))
            assertEquals(0L, gate.currentToolEpoch("b", "subagent"))
            assertTrue(gate.isHandleFresh(terminal.token))
            gate.releaseHandle(terminal.token)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `bulk retains independent per stable ID epochs structural base and user terminal immunity`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        gate.publishAndCancel("a", WorkspaceChangeSpec.toolPolicy(setOf("shell"))) { }
        var terminalCloses = 0
        val terminal = gate.createHandle("a", WorkspaceHandleClass.USER_TERMINAL,
            close = { terminalCloses++ }, spawn = { "terminal" })
        gate.publishPreparedMany(listOf("a", "b"), {
            mapOf("a" to WorkspaceChangeSpec.toolPolicy(setOf("subagent")), "b" to WorkspaceChangeSpec.structural())
        }) { epochs ->
            assertEquals(WorkspaceEpochs(2, 0), epochs.getValue("a"))
            assertEquals(WorkspaceEpochs(1, 1), epochs.getValue("b"))
        }
        assertEquals(1L, gate.currentToolEpoch("a", "shell"))
        assertEquals(1L, gate.currentToolEpoch("a", "subagent"))
        assertEquals(0L, gate.currentToolEpoch("a", "future"))
        assertEquals(1L, gate.currentToolEpoch("b", "future"))
        assertEquals(0, terminalCloses)
        assertTrue(gate.isHandleFresh(terminal.token))
        gate.releaseHandle(terminal.token)
    }

    @Test
    fun `one global cleanup deadline not a deadline per workspace`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 120)
        val ids = (1..8).map { "ws-$it" }
        val closes = mutableMapOf<String, Int>()
        var slowExit = true
        ids.forEach { id ->
            gate.createHandle(id, WorkspaceHandleClass.MODEL_BASH,
                close = { closes[id] = (closes[id] ?: 0) + 1 },
                awaitClosed = { if (slowExit) delay(10_000) }, spawn = { "bash" })
        }
        // Eight separate 120ms waits cannot satisfy this bound; one global wait can.
        val failure = withTimeout(700) {
            runCatching {
                gate.publishPreparedMany(ids, { ids.associateWith { WorkspaceChangeSpec.structural() } }) {
                    error("must not commit")
                }
            }.exceptionOrNull()
        }
        assertTrue(failure is WorkspaceGateJoinTimeoutException)
        ids.forEach { id ->
            assertTrue(gate.snapshot(id).quarantined)
            assertEquals(1, gate.snapshot(id).registeredHandles)
            assertEquals(false, gate.eventsFlow(id).value?.committed)
        }
        // The deadline left later handles unattempted. They must remain cleanup debt
        // even when the next publication selects no stable IDs, not merely look sealed.
        slowExit = false
        gate.publishPreparedMany(ids, { ids.associateWith { null } }) { }
        ids.forEach { id ->
            assertFalse(gate.snapshot(id).quarantined)
            assertEquals(0, gate.snapshot(id).registeredHandles)
            assertEquals(1, closes.getValue(id))
        }
    }

    @Test
    fun `cleanup failure blocks all writes quarantines only failures and retry pays unrelated debt without reclosing`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        var closes = 0
        var awaits = 0
        var failAwait = true
        gate.createHandle("bad", WorkspaceHandleClass.MODEL_BASH, setOf("subagent"),
            close = { closes++ }, awaitClosed = { awaits++; if (failAwait) error("exit refused") },
            spawn = { "bash" })
        var publications = 0
        val failure = runCatching {
            gate.publishPreparedMany(listOf("good", "bad"), { specs("good", "bad") }) { publications++ }
        }.exceptionOrNull()
        assertTrue(failure is WorkspaceGateCleanupException)
        assertEquals(0, publications)
        assertTrue(gate.snapshot("bad").quarantined)
        assertFalse(gate.snapshot("good").quarantined)
        assertFalse(gate.snapshot("good").sealed)
        assertEquals("ready", gate.withOperation("good", WorkspaceOperationKind.TOOL) { "ready" })
        for (id in listOf("good", "bad")) assertEquals(false, gate.eventsFlow(id).value?.committed)
        failAwait = false
        // Empty specs do not select the resource family; pendingCleanup still must be paid.
        gate.publishPreparedMany(listOf("bad", "good"), { mapOf("bad" to null, "good" to null) }) { publications++ }
        assertEquals(1, publications)
        assertEquals(1, closes)
        assertEquals(2, awaits)
        assertEquals(0, gate.snapshot("bad").registeredHandles)
        assertFalse(gate.snapshot("bad").quarantined)
        assertEquals(2L, gate.currentEpoch("bad"))
        assertEquals(1L, gate.currentToolEpoch("bad", "subagent"))
    }

    @Test
    fun `global timeout retains only unfinished participants in quarantine`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 120)
        var successfulClose = false
        gate.createHandle("a-success", WorkspaceHandleClass.MODEL_BASH,
            close = { successfulClose = true }, spawn = { "bash" })
        gate.createHandle("z-timeout", WorkspaceHandleClass.MODEL_BASH,
            awaitClosed = { delay(10_000) }, spawn = { "bash" })
        val ids = listOf("a-success", "middle-empty", "z-timeout")
        val failure = runCatching {
            gate.publishPreparedMany(ids, { ids.associateWith { WorkspaceChangeSpec.structural() } }) {
                error("must not publish")
            }
        }.exceptionOrNull()
        assertTrue(failure is WorkspaceGateJoinTimeoutException)
        assertTrue(successfulClose)
        assertEquals(0, gate.snapshot("a-success").registeredHandles)
        assertFalse(gate.snapshot("a-success").sealed)
        assertFalse(gate.snapshot("middle-empty").sealed)
        assertTrue(gate.snapshot("z-timeout").quarantined)
        ids.forEach { assertEquals(false, gate.eventsFlow(it).value?.committed) }
    }

    @Test
    fun `bulk rescans orphan adopted in a different workspace during victim join and retries its debt`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000)
        val victimStarted = CompletableDeferred<Unit>()
        val releaseVictim = CompletableDeferred<Unit>()
        val spawnEntered = CompletableDeferred<Unit>()
        val releaseSpawn = CompletableDeferred<Unit>()
        val allSealed = CompletableDeferred<Unit>()
        var failClose = true
        var closes = 0
        var awaits = 0
        val victim = async {
            runCatching {
                gate.withOperation("a", WorkspaceOperationKind.TOOL, setOf("subagent")) {
                    victimStarted.complete(Unit)
                    withContext(NonCancellable) { releaseVictim.await() }
                }
            }
        }
        val create = async {
            runCatching {
                // Empty stable IDs: not a victim, but registration after sealing will fail.
                gate.createHandle("b", WorkspaceHandleClass.MODEL_BASH,
                    close = { closes++; if (failClose) error("orphan exit refused") },
                    awaitClosed = { awaits++ }, spawn = {
                        spawnEntered.complete(Unit)
                        releaseSpawn.await()
                        "orphan"
                    })
            }
        }
        gate.addOnBeforeChangeListener { if (it.workspaceId == "b") allSealed.complete(Unit) }
        try {
            victimStarted.await()
            spawnEntered.await()
            val publication = async {
                runCatching {
                    gate.publishPreparedMany(listOf("a", "b"), { specs("a", "b") }) {
                        error("orphan debt must block write")
                    }
                }
            }
            allSealed.await()
            releaseSpawn.complete(Unit)
            assertTrue(withTimeout(1_000) { create.await() }.exceptionOrNull() is WorkspaceStaleHandleException)
            assertEquals(1, gate.snapshot("b").registeredHandles)
            assertTrue(gate.snapshot("b").quarantined)
            releaseVictim.complete(Unit)
            val failure = withTimeout(2_000) { publication.await() }.exceptionOrNull()
            assertTrue(failure is WorkspaceGateCleanupException)
            assertEquals(2, closes)
            assertEquals(0, awaits)
            assertFalse(gate.snapshot("a").sealed)
            assertTrue(gate.snapshot("b").sealed)
            assertEquals(false, gate.eventsFlow("b").value?.committed)
            assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            failClose = false
            gate.publishPreparedMany(listOf("b", "a"), { mapOf("a" to null, "b" to null) }) { }
            assertEquals(3, closes)
            assertEquals(1, awaits)
            assertEquals(0, gate.snapshot("b").registeredHandles)
            assertFalse(gate.snapshot("b").quarantined)
        } finally {
            releaseSpawn.complete(Unit)
            releaseVictim.complete(Unit)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `bulk null spec retries unrelated operation cleanup debt before committing`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 300)
        val parked = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        val sealed = CompletableDeferred<Unit>()
        try {
            val abandoned = runCatching {
                gate.withOperation("a", WorkspaceOperationKind.TOOL, setOf("unrelated")) {
                    val opContext = kotlinx.coroutines.currentCoroutineContext()
                    val recordJob = requireNotNull(opContext.job.parent)
                    CoroutineScope(opContext.minusKey(Job) + recordJob).launch {
                        withContext(NonCancellable) {
                            parked.complete(Unit)
                            releaseChild.await()
                        }
                    }
                    parked.await()
                    "body done"
                }
            }
            assertTrue(abandoned.exceptionOrNull() is WorkspaceGateJoinTimeoutException)
            assertEquals(1, gate.snapshot("a").activeOperations)
            assertTrue(gate.snapshot("a").quarantined)
            gate.addOnBeforeChangeListener { if (it.workspaceId == "a") sealed.complete(Unit) }
            val publication = async {
                gate.publishPreparedMany(listOf("a", "b"), { mapOf("a" to null, "b" to null) }) { "recovered" }
            }
            sealed.await()
            releaseChild.complete(Unit)
            assertEquals("recovered", withTimeout(1_000) { publication.await() })
            assertEquals(0, gate.snapshot("a").activeOperations)
            assertFalse(gate.snapshot("a").quarantined)
            assertEquals(true, gate.eventsFlow("a").value?.committed)
            assertEquals(0L, gate.currentToolEpoch("a", "unrelated"))
        } finally {
            releaseChild.complete(Unit)
        }
    }

    @Test
    fun `cancellation acquiring a later change mutex releases already acquired gates without preparing`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val holdingB = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val first = async {
            gate.publishPrepared("b", {
                holdingB.complete(Unit)
                releaseB.await()
                null
            }) { }
        }
        try {
            holdingB.await()
            val bulk = async(start = CoroutineStart.UNDISPATCHED) {
                gate.publishPreparedMany(listOf("b", "a"), { error("must not prepare") }) { error("must not write") }
            }
            bulk.cancel()
            bulk.join()
            // b remains owned by the first publisher. a's change lock must be released.
            withTimeout(500) { gate.publishPrepared("a", { null }) { } }
            assertEquals(WorkspaceEpochs(), gate.currentEpochs("a"))
            assertNull(gate.eventsFlow("a").value)
            assertFalse(gate.snapshot("a").sealed)
        } finally {
            releaseB.complete(Unit)
            first.await()
        }
    }

    @Test
    fun `cancellation acquiring a later admission mutex releases admissions and change gates without preparing`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val readingB = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val reader = async {
            gate.withSnapshot("b") {
                readingB.complete(Unit)
                releaseB.await()
            }
        }
        try {
            readingB.await()
            val bulk = async(start = CoroutineStart.UNDISPATCHED) {
                gate.publishPreparedMany(listOf("b", "a"), { error("must not prepare") }) { error("must not write") }
            }
            bulk.cancel()
            bulk.join()
            withTimeout(500) {
                gate.withSnapshot("a") { assertEquals(WorkspaceEpochs(), it) }
                gate.publishPrepared("a", { null }) { }
            }
            assertEquals(WorkspaceEpochs(), gate.currentEpochs("b"))
            assertNull(gate.eventsFlow("a").value)
            assertNull(gate.eventsFlow("b").value)
        } finally {
            releaseB.complete(Unit)
            reader.await()
        }
    }

    @Test
    fun `legacy cleanup failure attributes quarantine to the relevant participant`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val failure = runCatching {
            gate.publishPreparedMany(listOf("a", "b"), { specs("a", "b") }) {
                throw WorkspaceGateCleanupException("b", 1, listOf(IllegalStateException("legacy exit")))
            }
        }.exceptionOrNull()
        assertTrue(failure is WorkspaceGateCleanupException)
        assertFalse(gate.snapshot("a").sealed)
        assertTrue(gate.snapshot("b").quarantined)
        assertEquals(false, gate.eventsFlow("a").value?.committed)
        assertEquals(false, gate.eventsFlow("b").value?.committed)
        assertEquals(1L, gate.currentEpoch("a"))
        assertEquals(1L, gate.currentEpoch("b"))
    }

    @Test
    fun `ordinary publication failure unseals all but never rolls back epochs`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val failure = runCatching {
            gate.publishPreparedMany(listOf("a", "b"), { specs("a", "b") }) { error("transaction rollback") }
        }.exceptionOrNull()
        assertEquals("transaction rollback", failure?.message)
        for (id in listOf("a", "b")) {
            assertFalse(gate.snapshot(id).sealed)
            assertEquals(1L, gate.currentToolEpoch(id, "subagent"))
            assertEquals(false, gate.eventsFlow(id).value?.committed)
        }
    }

    @Test
    fun `publisher cancellation still completes cleanup and never calls publication`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000)
        val closeEntered = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        var closed = false
        var published = false
        gate.createHandle("a", WorkspaceHandleClass.MODEL_BASH, setOf("subagent"), close = {
            closeEntered.complete(Unit)
            releaseClose.await()
            closed = true
        }, spawn = { "bash" })
        val publisher = async {
            gate.publishPreparedMany(listOf("a", "b"), { specs("a", "b") }) { published = true }
        }
        try {
            closeEntered.await()
            publisher.cancel(CancellationException("user stop"))
            assertFalse(closed)
            releaseClose.complete(Unit)
            withTimeout(2_000) { publisher.join() }
            assertTrue(closed)
            assertFalse(published)
            assertEquals(0, gate.snapshot("a").registeredHandles)
            for (id in listOf("a", "b")) {
                assertFalse(gate.snapshot(id).sealed)
                assertEquals(false, gate.eventsFlow(id).value?.committed)
            }
        } finally {
            releaseClose.complete(Unit)
        }
    }

    @Test
    fun `bulk callbacks cannot reenter any participant even through disjoint nested publication`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        suspend fun rejects(id: String) {
            assertTrue(runCatching { gate.withSnapshot(id) { } }.exceptionOrNull() is WorkspaceGateReentrancyException)
            assertTrue(runCatching { gate.awaitAdmission(id) }.exceptionOrNull() is WorkspaceGateReentrancyException)
            assertTrue(runCatching { gate.publishPrepared(id, { null }) { } }.exceptionOrNull() is WorkspaceGateReentrancyException)
            assertTrue(runCatching { gate.closeWorkspace(id) }.exceptionOrNull() is WorkspaceGateReentrancyException)
        }
        withTimeout(2_000) {
            gate.publishPreparedMany(listOf("a", "b"), {
                rejects("a")
                rejects("b")
                specs("a", "b")
            }) {
                rejects("a")
                rejects("b")
                gate.publishPrepared("c", { null }) {
                    rejects("a")
                    rejects("b")
                    rejects("c")
                }
            }
        }
    }

    @Test
    fun `admission and publication callbacks retain operation ancestors while nested child admission remains allowed`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        withTimeout(2_000) {
            gate.withOperation("ancestor", WorkspaceOperationKind.TOOL) {
                gate.withOperation("child", WorkspaceOperationKind.CHILD, validate = {
                    assertTrue(runCatching {
                        gate.withSnapshot("ancestor") { }
                    }.exceptionOrNull() is WorkspaceGateReentrancyException)
                    true
                }) {
                    assertTrue(runCatching {
                        gate.publishPreparedMany(listOf("other", "ancestor"), { specs("other", "ancestor") }) { }
                    }.exceptionOrNull() is WorkspaceGateReentrancyException)
                    gate.publishPreparedMany(listOf("a", "b"), { specs("a", "b") }) {
                        for (id in listOf("ancestor", "child", "a", "b")) {
                            assertTrue(runCatching {
                                gate.withOperation(id, WorkspaceOperationKind.CHILD) { }
                            }.exceptionOrNull() is WorkspaceGateReentrancyException)
                        }
                        gate.publishPrepared("other", { null }) {
                            assertTrue(runCatching {
                                gate.withSnapshot("ancestor") { }
                            }.exceptionOrNull() is WorkspaceGateReentrancyException)
                        }
                    }
                }
            }
        }
        assertFalse(gate.snapshot("ancestor").closed)
        withTimeout(1_000) {
            gate.withSnapshot("outer-read") {
                gate.withSnapshot("inner-read") {
                    assertTrue(runCatching {
                        gate.withSnapshot("outer-read") { }
                    }.exceptionOrNull() is WorkspaceGateReentrancyException)
                    // Switching from admission to publication context must retain BOTH
                    // read ancestors too, even for library-only publication with no IDs.
                    gate.publishPreparedMany(emptyList(), { emptyMap() }) {
                        for (id in listOf("outer-read", "inner-read")) {
                            assertTrue(runCatching {
                                gate.withSnapshot(id) { }
                            }.exceptionOrNull() is WorkspaceGateReentrancyException)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `cancelled preparation releases all locks with no epochs or writes`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val entered = CompletableDeferred<Unit>()
        val publication = async {
            gate.publishPreparedMany(listOf("a", "b"), {
                entered.complete(Unit)
                coroutineContext.job.cancel()
                specs("a", "b")
            }) { error("cancelled preparation must not write") }
        }
        entered.await()
        publication.join()
        for (id in listOf("a", "b")) {
            assertEquals(WorkspaceEpochs(), gate.currentEpochs(id))
            assertNull(gate.eventsFlow(id).value)
            assertFalse(gate.snapshot(id).sealed)
        }
        withTimeout(1_000) { gate.publishPreparedMany(listOf("b", "a"), { specs("a", "b") }) { } }
    }
}
