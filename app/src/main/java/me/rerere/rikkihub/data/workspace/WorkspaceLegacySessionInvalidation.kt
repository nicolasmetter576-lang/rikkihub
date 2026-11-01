package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Compatibility bridge for existing rootfs/mount configuration changes.
 * Repository calls these suspend closers after gate cancellation and before writing the new row.
 * Tool-only changes call only registered model-tool closers; user terminals are structural-only.
 * A closer returning is not a claim that an Android PTY/process group has exited.
 */
class WorkspaceLegacySessionInvalidation {
    private data class Closer(
        val toolIds: Set<String>,
        val close: suspend (String, String) -> Unit,
    )
    private val closers = ConcurrentHashMap<String, Closer>()

    fun register(
        owner: String,
        toolIds: Set<String> = emptySet(),
        close: suspend (workspaceId: String, root: String) -> Unit,
    ): () -> Unit {
        require(owner.isNotBlank()) { "Session closer owner must not be blank" }
        val entry = Closer(toolIds.toSet(), close)
        check(closers.putIfAbsent(owner, entry) == null) { "Duplicate workspace session closer: $owner" }
        return { closers.remove(owner, entry) }
    }

    suspend fun closeWorkspace(
        workspaceId: String,
        root: String,
        affectedStableIds: Set<String>? = null,
    ) {
        withContext(NonCancellable) {
            val failures = mutableListOf<Throwable>()
            for (entry in closers.toSortedMap().values) {
                if (affectedStableIds != null && entry.toolIds.none { it in affectedStableIds }) continue
                try { entry.close(workspaceId, root) } catch (error: Throwable) { failures += error }
            }
            if (failures.isNotEmpty()) throw WorkspaceGateCleanupException(workspaceId, failures.size, failures)
        }
    }
}
