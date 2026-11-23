package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.ai.book.BookOutliner
import me.rerere.rikkihub.data.ai.book.OutlineTask
import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatState
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapterEntry
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.ChapterNotes
import me.rerere.rikkihub.data.book.OutlineFile
import me.rerere.rikkihub.data.book.OutlineSection
import me.rerere.tavern.token.TokenCounter

/** 反向大纲列表里的一章。 */
data class OutlineRow(val entry: BookChapterEntry, val notes: ChapterNotes) {
    val extracted: Boolean get() = notes.outline != null
    val stale: Boolean get() = notes.outline?.let { it.hash != entry.file?.sha256 } == true

    /** 还要提取：有正文，没提取过或正文变了。 */
    val pending: Boolean get() = entry.words > 0 && (!extracted || stale)
    val comparable: Boolean get() = extracted && notes.beats.isNotEmpty()

    /** 还要对照：两边都有，没对照过或已经变了。 */
    val comparePending: Boolean get() = comparable && (notes.comparison == null || notes.comparisonStale)

    fun count(state: BeatState): Int = if (comparePending) 0 else notes.comparison?.matches.orEmpty().count { it.state == state }
}

data class BookOutlineUiState(
    val book: Book? = null,
    val rows: List<OutlineRow> = emptyList(),
    /** 确认提取时的 token 估算；null = 还在算。 */
    val estimate: Int? = null,
    /** 大纲文件解析出来、等用户确认的内容。 */
    val sections: List<OutlineSection>? = null,
    val imported: Int? = null,
    val error: String? = null,
)

class BookOutlineVM(
    private val workspaceId: String,
    private val store: BookStore,
    private val outliner: BookOutliner,
) : ViewModel() {
    private val _state = MutableStateFlow(BookOutlineUiState())
    val state = _state.asStateFlow()
    val progress = outliner.progress.map { it[workspaceId] }

    init {
        viewModelScope.launch { store.changes.filter { it == workspaceId }.collect { refresh() } }
        viewModelScope.launch {
            store.noteChanges.filter { it.first == workspaceId }.collect { (_, chapterId) -> reloadNotes(chapterId) }
        }
    }

    fun refresh() = work {
        val book = store.open(workspaceId)
        val rows = withContext(Dispatchers.IO) { book.entries.filterNot { it.missing }.map { OutlineRow(it, notesOf(it.id)) } }
        _state.update { it.copy(book = book, rows = rows) }
    }

    private suspend fun notesOf(chapterId: String): ChapterNotes = runCatching { store.notes(workspaceId, chapterId) }.getOrDefault(ChapterNotes())

    private suspend fun reloadNotes(chapterId: String) {
        val notes = notesOf(chapterId)
        _state.update { state -> state.copy(rows = state.rows.map { if (it.entry.id == chapterId) it.copy(notes = notes) else it }) }
    }

    fun estimate() {
        _state.update { it.copy(estimate = null) }
        work {
            val pending = _state.value.rows.filter { it.pending }
            val tokens = withContext(Dispatchers.Default) {
                pending.sumOf { row -> TokenCounter.count(runCatching { store.read(workspaceId, row.entry.path).content }.getOrDefault("")) }
            }
            _state.update { it.copy(estimate = tokens) }
        }
    }

    fun extractPending() = start(_state.value.rows.filter { it.pending }.map { OutlineTask.Extract(it.entry.id) })

    fun comparePending() = start(_state.value.rows.filter { it.comparePending }.map { OutlineTask.Compare(it.entry.id) })

    private fun start(tasks: List<OutlineTask>) {
        outliner.start(workspaceId, tasks)
    }

    fun stop() = outliner.stop(workspaceId)

    fun dismissProgress() = outliner.dismiss(workspaceId)

    fun openOutlineFile(path: String) = work {
        val book = store.open(workspaceId)
        val text = store.read(workspaceId, path).content
        _state.update { it.copy(book = book, sections = withContext(Dispatchers.Default) { OutlineFile.parse(text, book) }) }
    }

    fun closeOutlineFile() = _state.update { it.copy(sections = null) }

    /** 接到各章计划的末尾，已有的不重复加。 */
    fun importSections(sections: List<OutlineSection>) = work {
        val assigned = sections.filter { it.chapterId != null }.groupBy { it.chapterId!! }
        assigned.forEach { (chapterId, parts) -> store.editNotes(workspaceId, chapterId) { it.addBeats(parts.flatMap { part -> part.beats }) } }
        _state.update { it.copy(sections = null, imported = assigned.size) }
    }

    fun clearMessages() = _state.update { it.copy(error = null, imported = null) }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

data class ChapterOutlineUiState(
    val entry: BookChapterEntry? = null,
    val notes: ChapterNotes = ChapterNotes(),
    val error: String? = null,
) {
    val row: OutlineRow? get() = entry?.let { OutlineRow(it, notes) }
}

class ChapterOutlineVM(
    private val workspaceId: String,
    private val chapterId: String,
    private val store: BookStore,
    private val outliner: BookOutliner,
) : ViewModel() {
    private val _state = MutableStateFlow(ChapterOutlineUiState())
    val state = _state.asStateFlow()
    val progress = outliner.progress.map { it[workspaceId] }

    init {
        viewModelScope.launch { store.changes.filter { it == workspaceId }.collect { refresh() } }
        viewModelScope.launch { store.noteChanges.filter { it == workspaceId to chapterId }.collect { refresh() } }
    }

    fun refresh() = work {
        val entry = store.open(workspaceId).entry(chapterId)
        val notes = store.notes(workspaceId, chapterId)
        _state.update { it.copy(entry = entry, notes = notes) }
    }

    fun extract() {
        outliner.start(workspaceId, listOf(OutlineTask.Extract(chapterId)))
    }

    fun compare() {
        outliner.start(workspaceId, listOf(OutlineTask.Compare(chapterId)))
    }

    fun stop() = outliner.stop(workspaceId)

    fun dismissProgress() = outliner.dismiss(workspaceId)

    fun saveBeat(id: String?, text: String, words: Int) = edit { notes ->
        if (id == null) notes.copy(beats = notes.beats + Beat(BookManifest.newId(), text.trim(), words))
        else notes.updateBeat(id) { it.copy(text = text.trim(), words = words) }
    }

    fun removeBeat(id: String) = edit { notes -> notes.copy(beats = notes.beats.filterNot { it.id == id }) }

    fun reorder(order: List<String>) = edit { notes ->
        val rank = order.withIndex().associate { (index, id) -> id to index }
        notes.copy(beats = notes.beats.sortedBy { rank[it.id] ?: Int.MAX_VALUE })
    }

    /** 把情节点接到计划末尾。 */
    fun planFromOutline() = edit { notes -> notes.addBeats(notes.outline?.points.orEmpty().map { it.text }) }

    fun adoptSynopsis() = work {
        val synopsis = _state.value.notes.outline?.synopsis.orEmpty()
        store.edit(workspaceId) { book -> book.manifest.updateChapter(chapterId) { it.copy(synopsis = synopsis) } }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun edit(transform: (ChapterNotes) -> ChapterNotes) = work {
        val notes = store.editNotes(workspaceId, chapterId, transform)
        _state.update { it.copy(notes = notes) }
    }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
