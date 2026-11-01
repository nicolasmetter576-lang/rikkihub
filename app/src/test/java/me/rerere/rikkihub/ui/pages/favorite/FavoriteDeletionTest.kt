package me.rerere.rikkihub.ui.pages.favorite

import java.io.IOException
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.entity.FavoriteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production page orchestration, plus the actual pinned Material3 anchor state (not UI gestures). */
class FavoriteDeletionTest {
    private val entity = FavoriteEntity(
        id = "favorite-1", type = "node", refKey = "conversation:node", refJson = "{\"id\":1}",
        snapshotJson = "{\"text\":\"original\"}", metaJson = "{\"title\":\"title\"}",
        createdAt = 123L, updatedAt = 456L,
    )

    @Test fun `delete commits before undo is offered and dismissed undo stays deleted`() = runBlocking {
        var stored: FavoriteEntity? = entity
        val order = mutableListOf<String>()
        deleteFavoriteWithUndo(
            load = { order += "load"; stored },
            remove = { order += "remove"; stored = null },
            offerUndo = { assertEquals(null, stored); order += "offer"; false },
            restore = { error("dismissed Undo must not restore") },
        )
        assertEquals(listOf("load", "remove", "offer"), order)
        assertEquals(null, stored)
    }

    @Test fun `immediate undo restores the complete captured entity`() = runBlocking {
        var stored: FavoriteEntity? = entity
        deleteFavoriteWithUndo(
            load = { stored }, remove = { stored = null },
            offerUndo = { assertEquals(null, stored); true }, restore = { stored = it },
        )
        assertEquals(entity, stored)
    }

    @Test fun `slow delete cannot be overtaken by immediate undo`() = runBlocking {
        var stored: FavoriteEntity? = entity
        val deleteStarted = CompletableDeferred<Unit>()
        val allowDelete = CompletableDeferred<Unit>()
        var offered = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            deleteFavoriteWithUndo(
                load = { stored },
                remove = { deleteStarted.complete(Unit); allowDelete.await(); stored = null },
                offerUndo = { offered = true; assertEquals(null, stored); true },
                restore = { stored = it },
            )
        }
        deleteStarted.await()
        assertFalse(offered)
        assertFalse(job.isCompleted)
        allowDelete.complete(Unit)
        job.await()
        assertTrue(offered)
        assertEquals(entity, stored)
    }

    @Test fun `restoration is awaited before operation completes`() = runBlocking {
        val restoreStarted = CompletableDeferred<FavoriteEntity>()
        val allowRestore = CompletableDeferred<Unit>()
        var stored: FavoriteEntity? = null
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            deleteFavoriteWithUndo(
                load = { entity }, remove = {}, offerUndo = { true },
                restore = { restoreStarted.complete(it); allowRestore.await(); stored = it },
            )
        }
        assertEquals(entity, restoreStarted.await())
        assertFalse(job.isCompleted)
        allowRestore.complete(Unit)
        job.await()
        assertEquals(entity, stored)
    }

    @Test fun `already missing favorite does not delete or show undo`() = runBlocking {
        deleteFavoriteWithUndo(
            load = { null }, remove = { error("remove") },
            offerUndo = { error("offer") }, restore = { error("restore") },
        )
    }

    @Test fun `load failure is propagated without a misleading success snackbar`() = runBlocking {
        val failure = runCatching {
            deleteFavoriteWithUndo(
                load = { throw IOException("load failed") }, remove = { error("remove") },
                offerUndo = { error("offer") }, restore = { error("restore") },
            )
        }.exceptionOrNull()
        assertEquals("load failed", failure?.message)
    }

    @Test fun `delete failure never offers undo`() = runBlocking {
        val failure = runCatching {
            deleteFavoriteWithUndo(
                load = { entity }, remove = { throw IOException("delete failed") },
                offerUndo = { error("offer") }, restore = { error("restore") },
            )
        }.exceptionOrNull()
        assertEquals("delete failed", failure?.message)
    }

    @Test fun `undo host failure does not silently recreate the favorite`() = runBlocking {
        var deleted = false
        val failure = runCatching {
            deleteFavoriteWithUndo(
                load = { entity }, remove = { deleted = true },
                offerUndo = { throw IOException("host failed") }, restore = { error("restore") },
            )
        }.exceptionOrNull()
        assertTrue(deleted)
        assertEquals("host failed", failure?.message)
    }

    @Test fun `restore failure is propagated instead of detached in another job`() = runBlocking {
        val failure = runCatching {
            deleteFavoriteWithUndo(
                load = { entity }, remove = {}, offerUndo = { true },
                restore = { throw IOException("restore failed") },
            )
        }.exceptionOrNull()
        assertEquals("restore failed", failure?.message)
    }

    @Test fun `cancelling suspended delete never offers undo`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverFinish = CompletableDeferred<Unit>()
        var offered = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            deleteFavoriteWithUndo(
                load = { entity }, remove = { started.complete(Unit); neverFinish.await() },
                offerUndo = { offered = true; true }, restore = { error("restore") },
            )
        }
        started.await()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertFalse(offered)
    }

    @Test fun `cancelling waiting undo does not restore`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverFinish = CompletableDeferred<Boolean>()
        var restored = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            deleteFavoriteWithUndo(
                load = { entity }, remove = {},
                offerUndo = { started.complete(Unit); neverFinish.await() },
                restore = { restored = true },
            )
        }
        started.await()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertFalse(restored)
    }

    @Test fun `restore cancellation propagates`() = runBlocking {
        val failure = runCatching {
            deleteFavoriteWithUndo(
                load = { entity }, remove = {}, offerUndo = { true },
                restore = { throw CancellationException("restore cancelled") },
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun `row observer disposal does not cancel page-owned delete and undo`() = runBlocking {
        val page = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val allowUndo = CompletableDeferred<Boolean>()
        var stored: FavoriteEntity? = entity
        try {
            val pageJob = page.launch {
                deleteFavoriteWithUndo(
                    load = { stored }, remove = { stored = null },
                    offerUndo = { allowUndo.await() }, restore = { stored = it },
                )
            }
            row.launch { pageJob.join() }
            row.cancel()
            assertTrue(pageJob.isActive)
            allowUndo.complete(true)
            pageJob.join()
            assertEquals(entity, stored)
        } finally {
            page.cancel()
            row.cancel()
        }
    }

    // The component supplies anchors after layout. Set those on its actual pinned
    // AnchoredDraggableState via the public JVM getter of the internal property.
    @Suppress("UNCHECKED_CAST")
    private fun dragState(): Pair<SwipeToDismissBoxState, AnchoredDraggableState<SwipeToDismissBoxValue>> {
        val state = SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { it / 2 }
        val anchors = state.javaClass.methods.single {
            it.name.startsWith("getAnchoredDraggableState")
        }.invoke(state) as AnchoredDraggableState<SwipeToDismissBoxValue>
        anchors.updateAnchors(DraggableAnchors {
            SwipeToDismissBoxValue.Settled at 0f
            SwipeToDismissBoxValue.EndToStart at -100f
        })
        return state to anchors
    }

    @Test fun `passing the nearest anchor mid-drag does not mean deletion is settled`() {
        val (state, anchors) = dragState()
        anchors.dispatchRawDelta(-70f)
        assertEquals(SwipeToDismissBoxValue.EndToStart, state.currentValue)
        assertEquals(SwipeToDismissBoxValue.Settled, state.settledValue)
        assertFalse(shouldDeleteSettledFavorite(state.settledValue))
    }

    @Test fun `dragging back after passing threshold never settles deletion`() {
        val (state, anchors) = dragState()
        anchors.dispatchRawDelta(-70f)
        anchors.dispatchRawDelta(70f)
        assertEquals(SwipeToDismissBoxValue.Settled, state.settledValue)
        assertFalse(shouldDeleteSettledFavorite(state.settledValue))
    }

    @Test fun `only end-to-start settled anchor allows delete and reset allows retry`() = runBlocking {
        val (state, _) = dragState()
        state.snapTo(SwipeToDismissBoxValue.EndToStart)
        assertTrue(shouldDeleteSettledFavorite(state.settledValue))
        state.snapTo(SwipeToDismissBoxValue.Settled)
        assertFalse(shouldDeleteSettledFavorite(state.settledValue))
        assertFalse(shouldDeleteSettledFavorite(SwipeToDismissBoxValue.StartToEnd))
    }
}
