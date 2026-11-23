package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookManifestException
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterStatus

data class BookUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    /** 结构文件读不了（坏了或版本更新）。 */
    val manifestError: BookManifestException.Reason? = null,
    val error: String? = null,
)

class BookVM(
    private val workspaceId: String,
    private val store: BookStore,
) : ViewModel() {
    private val _state = MutableStateFlow(BookUiState())
    val state = _state.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch { store.changes.filter { it == workspaceId }.collect { refresh() } }
    }

    fun refresh() = launch { store.open(workspaceId) }

    fun rebuild() = launch { store.rebuild(workspaceId) }

    fun edit(transform: (BookManifest) -> BookManifest) = launch { store.edit(workspaceId) { transform(it.manifest) } }

    fun addFiles(paths: List<String>) = edit { manifest ->
        manifest.addChapters(paths.map { BookChapter(BookManifest.newId(), it) })
    }

    fun ignoreFiles(paths: List<String>) = edit { it.copy(ignored = it.ignored + paths) }

    fun setStatus(ids: Set<String>, status: ChapterStatus) = edit { manifest ->
        ids.fold(manifest) { current, id -> current.updateChapter(id) { it.copy(status = status) } }
    }

    fun updateChapter(id: String, title: String, synopsis: String, targetWords: Int, status: ChapterStatus) = edit { manifest ->
        manifest.updateChapter(id) {
            it.copy(title = title.trim().ifBlank { null }, synopsis = synopsis.trim(), targetWords = targetWords, status = status)
        }
    }

    fun addVolume(title: String, afterId: String?) = edit { it.addVolume(BookVolume(BookManifest.newId(), title.trim()), afterId) }

    fun setInfo(title: String, author: String) = edit { it.copy(title = title.trim(), author = author.trim()) }

    fun moveToVolume(ids: Set<String>, volumeId: String?) = edit { it.moveToVolume(ids, volumeId) }

    fun reorder(order: List<String>) = edit { it.reorder(order) }

    fun remove(ids: Set<String>) = edit { it.remove(ids) }

    fun delete(ids: Set<String>) = launch { store.deleteChapters(workspaceId, ids) }

    fun relink(chapterId: String, path: String) = launch { store.relink(workspaceId, chapterId, path) }

    fun createChapter(path: String, afterId: String?, onCreated: (String) -> Unit) = launch(
        onDone = { onCreated(path) },
    ) { store.createChapter(workspaceId, path, "", afterId) }

    private fun launch(onDone: () -> Unit = {}, block: suspend () -> Book) {
        viewModelScope.launch {
            try {
                val book = block()
                _state.value = BookUiState(loading = false, book = book)
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (broken: BookManifestException) {
                _state.value = BookUiState(loading = false, manifestError = broken.reason)
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                _state.update { it.copy(loading = false, error = message.takeIf { _ -> it.book == null }) }
                if (_state.value.book != null) _messages.tryEmit(message)
            }
        }
    }
}
