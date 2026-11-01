package me.rerere.workspace

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide coordination, keyed by canonical storage root, not FS instance,
 * workspace id, device mount id, or filename. Recursive mutations hold one root
 * lock for their entire operation. Same-source device mounts share this lock.
 *
 * This is resource coordination, NOT authorization or a filesystem sandbox.
 * App resource/mount gates still authorize each operation. Shells, terminals,
 * other processes and overlapping (but unequal) roots do not participate.
 * External rename/path substitution can still race canonical checks and rename.
 * Locks are retained for the process lifetime to avoid split-lock eviction races.
 * Call blocking APIs from an IO thread. Blocks must not nest mutation APIs;
 * multi-root structural deletion uses the sorted, deduplicated withLocks helper.
 */
internal object WorkspaceFileMutations {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(root: File): Mutex = locks.computeIfAbsent(root.canonicalPath) { Mutex() }

    fun <T> withLock(root: File, block: () -> T): T {
        val mutex = lock(root)
        runBlocking { mutex.lock() }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    fun <T> withLocks(roots: List<File>, block: () -> T): T {
        val mutexes = roots.map { it.canonicalPath }.distinct().sorted()
            .map { locks.computeIfAbsent(it) { Mutex() } }
        val acquired = ArrayList<Mutex>()
        try {
            for (mutex in mutexes) {
                runBlocking { mutex.lock() }
                acquired += mutex
            }
            return block()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    suspend fun <T> withSuspendingLock(root: File, block: suspend () -> T): T =
        lock(root).withLock { block() }

    /** Suspending [withLocks]: same sorted, deduplicated order, so the two never deadlock each other. */
    suspend fun <T> withSuspendingLocks(roots: List<File>, block: suspend () -> T): T {
        val mutexes = roots.map { it.canonicalPath }.distinct().sorted()
            .map { locks.computeIfAbsent(it) { Mutex() } }
        val acquired = ArrayList<Mutex>()
        try {
            for (mutex in mutexes) {
                mutex.lock()
                acquired += mutex
            }
            return block()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }
}
