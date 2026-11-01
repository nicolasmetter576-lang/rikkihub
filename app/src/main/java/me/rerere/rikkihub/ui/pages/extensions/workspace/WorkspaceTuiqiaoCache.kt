package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoEvent
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPrepared

/**
 * One 推敲 of one selection: its session, the candidates so far and each slot's status. Its requests
 * belong to the cache, not to the sheet or the editor page, so hiding the sheet (or leaving the file)
 * lets them finish into the entry.
 */
internal class WorkspaceTuiqiaoEntry(val fileKey: String, prepared: TuiqiaoPrepared, fileName: String) {
    var session: WorkspaceTuiqiaoSession by mutableStateOf(prepared.toSession(fileName))
        private set
    val slotStatus = mutableStateMapOf<String, WorkspaceTuiqiaoStatus>()
    val items = mutableStateListOf<WorkspaceTuiqiaoItem>()
    internal var prepared: TuiqiaoPrepared = prepared
        private set
    internal val jobs = mutableListOf<Job>()

    /** Loading while any slot is; Failed (first slot's reason) only when every slot failed. */
    val status: WorkspaceTuiqiaoStatus
        get() {
            val all = session.slots.map { slotStatus[it.key] ?: WorkspaceTuiqiaoStatus.Done }
            return when {
                all.any { it == WorkspaceTuiqiaoStatus.Loading } -> WorkspaceTuiqiaoStatus.Loading
                all.isNotEmpty() && all.all { it is WorkspaceTuiqiaoStatus.Failed } -> all.first()
                else -> WorkspaceTuiqiaoStatus.Done
            }
        }

    /** The same text found again elsewhere in the document: replace there from now on. */
    internal fun moveTo(start: Int, end: Int) {
        prepared = prepared.copy(start = start, end = end)
        session = session.copy(start = start, end = end)
    }

    internal fun cancel() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}

private fun TuiqiaoPrepared.toSession(fileName: String) = WorkspaceTuiqiaoSession(
    fileName, start, end, original, mode, format, slots.map { WorkspaceTuiqiaoSlotInfo(it.key, it.role?.name, it.count) },
)

/**
 * 推敲 results kept in memory for as long as the process lives, so a hidden sheet and a reopened file
 * still show them. Keyed by file and the trimmed selected text (the position may move); the most
 * recently used [PER_FILE] entries per file and [TOTAL] overall are kept, evicted ones cancelled.
 * Main-thread confined: the UI calls it and [scope] runs on the main dispatcher.
 */
internal class WorkspaceTuiqiaoCache(
    private val scope: CoroutineScope,
    private val events: (TuiqiaoPrepared) -> Flow<TuiqiaoEvent>,
) {
    private val entries = LinkedHashMap<Pair<String, String>, WorkspaceTuiqiaoEntry>(16, 0.75f, true)

    /** The entry for [original] in [fileKey], marked as most recently used; null when there is none. */
    fun find(fileKey: String, original: String): WorkspaceTuiqiaoEntry? = entries[fileKey to original]

    /**
     * The kept entry for the selection `source[start, end)` (trimmed like a request is), moved to where
     * that text now is; null when this text was not refined before.
     */
    fun lookup(fileKey: String, source: String, start: Int, end: Int): WorkspaceTuiqiaoEntry? {
        val range = Tuiqiao.normalizeRange(source, start, end) ?: return null
        val entry = find(fileKey, source.substring(range.first, range.last + 1)) ?: return null
        entry.moveTo(range.first, range.last + 1)
        return entry
    }

    /** Starts a fresh 推敲 of [prepared], replacing (and cancelling) any entry for the same text. */
    fun start(fileKey: String, fileName: String, prepared: TuiqiaoPrepared): WorkspaceTuiqiaoEntry {
        entries.remove(fileKey to prepared.original)?.cancel()
        val entry = WorkspaceTuiqiaoEntry(fileKey, prepared, fileName)
        entries[fileKey to prepared.original] = entry
        evict(fileKey)
        run(entry, prepared)
        return entry
    }

    /** Re-runs one slot of [entry]; ignored while that slot is still loading. */
    fun retrySlot(entry: WorkspaceTuiqiaoEntry, key: String) {
        val only = entry.prepared.only(key) ?: return
        if (entry.slotStatus[key] == WorkspaceTuiqiaoStatus.Loading) return
        entry.items.removeAll { it.slot == key }
        run(entry, only)
    }

    /** Drops [entry] (its text was replaced), cancelling what is still running. */
    fun remove(entry: WorkspaceTuiqiaoEntry) {
        val key = entry.fileKey to entry.session.original
        if (entries[key] === entry) entries.remove(key)
        entry.cancel()
    }

    internal val size: Int get() = entries.size

    private fun evict(fileKey: String) {
        val sameFile = entries.entries.filter { it.key.first == fileKey }
        sameFile.take((sameFile.size - PER_FILE).coerceAtLeast(0)).forEach { drop(it.key) }
        entries.keys.take((entries.size - TOTAL).coerceAtLeast(0)).forEach { drop(it) }
    }

    private fun drop(key: Pair<String, String>) {
        entries.remove(key)?.cancel()
    }

    private fun run(entry: WorkspaceTuiqiaoEntry, target: TuiqiaoPrepared) {
        target.slots.forEach { entry.slotStatus[it.key] = WorkspaceTuiqiaoStatus.Loading }
        entry.jobs += scope.launch {
            try {
                events(target).collect { event ->
                    when (event) {
                        is TuiqiaoEvent.Candidate -> entry.items += WorkspaceTuiqiaoItem(event.slot, event.candidate)
                        is TuiqiaoEvent.SlotFailed -> entry.slotStatus[event.slot] = WorkspaceTuiqiaoStatus.Failed(event.reason, event.message)
                    }
                }
                target.slots.forEach { if (entry.slotStatus[it.key] == WorkspaceTuiqiaoStatus.Loading) entry.slotStatus[it.key] = WorkspaceTuiqiaoStatus.Done }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val failed = WorkspaceTuiqiaoStatus.Failed(null, e.message ?: e.javaClass.simpleName)
                target.slots.forEach { if (entry.slotStatus[it.key] == WorkspaceTuiqiaoStatus.Loading) entry.slotStatus[it.key] = failed }
            }
        }
    }

    companion object {
        const val PER_FILE = 10
        const val TOTAL = 50
    }
}
