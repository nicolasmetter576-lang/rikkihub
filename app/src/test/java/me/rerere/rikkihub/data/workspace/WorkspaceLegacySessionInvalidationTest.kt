package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLegacySessionInvalidationTest {
    @Test
    fun closersReceiveWorkspaceIdAndRootSeparately() = runBlocking {
        val registry = WorkspaceLegacySessionInvalidation()
        val calls = mutableListOf<String>()
        registry.register("app") { id, root -> calls += "$id:$root" }
        registry.closeWorkspace("id", "root")
        assertEquals(listOf("id:root"), calls)
    }

    @Test
    fun toolToggleClosesOnlyOwnedModelSessionsNotUserTerminals() = runBlocking {
        val registry = WorkspaceLegacySessionInvalidation()
        var userCloses = 0
        var modelCloses = 0
        registry.register("app") { _, _ -> userCloses++ }
        registry.register("web") { _, _ -> userCloses++ }
        registry.register("bash", setOf("bash")) { _, _ -> modelCloses++ }
        registry.closeWorkspace("id", "root", setOf("workspace_read_file"))
        assertEquals(0, userCloses)
        assertEquals(0, modelCloses)
        registry.closeWorkspace("id", "root", setOf("bash"))
        assertEquals(0, userCloses)
        assertEquals(1, modelCloses)
        registry.closeWorkspace("id", "root")
        assertEquals(2, userCloses)
        assertEquals(2, modelCloses)
    }

    @Test
    fun publicationWaitsForAllClosers() = runBlocking {
        val registry = WorkspaceLegacySessionInvalidation()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var secondClosed = false
        registry.register("first") { _, _ -> started.complete(Unit); finish.await() }
        registry.register("second") { _, _ -> secondClosed = true }
        val cleanup = async { registry.closeWorkspace("id", "root") }
        withTimeout(2_000) { started.await() }
        assertFalse(cleanup.isCompleted)
        assertFalse(secondClosed)
        finish.complete(Unit)
        withTimeout(2_000) { cleanup.await() }
        assertTrue(secondClosed)
    }

    @Test
    fun closerFailureStopsPublicationAndIsNotSwallowed() = runBlocking {
        val registry = WorkspaceLegacySessionInvalidation()
        val failure = IllegalStateException("close failed")
        registry.register("app") { _, _ -> throw failure }
        var webClosed = false
        registry.register("web") { _, _ -> webClosed = true }
        val result = runCatching { registry.closeWorkspace("id", "root") }
        val cleanup = result.exceptionOrNull() as WorkspaceGateCleanupException
        assertTrue(cleanup.causes.single() === failure)
        assertTrue(webClosed) // one failed closer must not leak all other user resources
    }

    @Test
    fun registrationOwnerIsUniqueAndUnregisterIsExact() = runBlocking {
        val registry = WorkspaceLegacySessionInvalidation()
        var count = 0
        val unregister = registry.register("app") { _, _ -> count++ }
        assertTrue(runCatching { registry.register("app") { _, _ -> } }.isFailure)
        unregister()
        unregister()
        registry.register("app") { _, _ -> count += 2 }
        registry.closeWorkspace("id", "root")
        assertEquals(2, count)
    }
}
