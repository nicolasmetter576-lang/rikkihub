package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.book.BeatProposal
import me.rerere.rikkihub.data.ai.book.BeatWriter
import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatInsertion
import me.rerere.rikkihub.data.book.BookChapterEntry
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.ChapterNotes
import kotlin.uuid.Uuid

data class BeatBoardUiState(
    val entry: BookChapterEntry? = null,
    val notes: ChapterNotes = ChapterNotes(),
    /** 上一章的正文，本章太短看不出段落习惯时照它。 */
    val previousText: String = "",
    val error: String? = null,
)

class BeatBoardVM(
    private val workspaceId: String,
    private val chapterId: String,
    private val store: BookStore,
    private val writer: BeatWriter,
) : ViewModel() {
    private val key = BeatWriter.Key(workspaceId, chapterId)
    private val _state = MutableStateFlow(BeatBoardUiState())
    val state = _state.asStateFlow()
    val job = writer.states.map { it[key] }

    init {
        viewModelScope.launch { store.noteChanges.filter { it == workspaceId to chapterId }.collect { refresh() } }
    }

    fun refresh() = work {
        val book = store.open(workspaceId)
        val entry = book.entry(chapterId)
        val notes = store.notes(workspaceId, chapterId)
        val previous = entry?.let { book.entries.getOrNull(it.number - 2) }?.takeUnless { it.missing }
        val previousText = previous?.let { runCatching { store.read(workspaceId, it.path).content }.getOrNull() }.orEmpty()
        _state.update { it.copy(entry = entry, notes = notes, previousText = previousText) }
    }

    fun saveBeat(id: String?, text: String, words: Int) = edit { notes ->
        if (id == null) notes.copy(beats = notes.beats + Beat(BookManifest.newId(), text.trim(), words))
        else notes.updateBeat(id) { it.copy(text = text.trim(), words = words) }
    }

    fun removeBeat(id: String) = edit { notes -> notes.copy(beats = notes.beats.filterNot { it.id == id }) }

    fun move(id: String, delta: Int) = edit { notes ->
        val index = notes.beats.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in notes.beats.indices) notes
        else notes.copy(beats = notes.beats.toMutableList().apply { add(target, removeAt(index)) })
    }

    /** 换到另一稿并采用它。 */
    fun step(beat: Beat, delta: Int) = edit { notes ->
        notes.updateBeat(beat.id) { current ->
            val index = current.drafts.indexOfFirst { it.id == current.chosen } + delta
            current.drafts.getOrNull(index)?.let { current.copy(chosen = it.id) } ?: current
        }
    }

    fun toggleLock(id: String) = edit { notes -> notes.updateBeat(id) { it.copy(locked = !it.locked) } }

    fun removeDraft(beatId: String, draftId: String) = edit { it.removeDraft(beatId, draftId) }

    /** 当作没写进过正文。 */
    fun forgetInserted(id: String) = edit { notes -> notes.updateBeat(id) { it.copy(inserted = "") } }

    fun markInserted(blocks: List<Pair<String, String>>) = edit { notes ->
        blocks.fold(notes) { current, (id, block) -> current.updateBeat(id) { it.copy(inserted = block) } }
    }

    fun markInsertions(insertions: List<BeatInsertion>) = markInserted(insertions.map { it.beatId to it.block })

    fun write(beatId: String, note: String = "", modelId: Uuid? = null) {
        writer.write(key, listOf(beatId), note, modelId)
    }

    fun writeAll() = work { writer.writeAll(key) }

    fun split() {
        writer.split(key)
    }

    fun adoptProposal(proposal: List<BeatProposal>, replace: Boolean) = work { writer.adoptProposal(key, proposal, replace) }

    fun stop() = writer.stop(key)

    fun dismissJob() = writer.dismiss(key)

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
