package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.book.transfer.BookImport
import me.rerere.rikkihub.data.book.transfer.BookImportException
import me.rerere.rikkihub.data.book.transfer.BookImportSource
import me.rerere.rikkihub.data.book.transfer.BookImporter
import me.rerere.rikkihub.data.book.transfer.EpubException
import me.rerere.rikkihub.data.book.transfer.ImportEdits
import me.rerere.rikkihub.data.book.transfer.ImportRow
import me.rerere.rikkihub.data.book.transfer.SplitRule
import me.rerere.rikkihub.data.book.transfer.edited
import me.rerere.rikkihub.data.book.transfer.rows
import java.io.File
import kotlin.uuid.Uuid

data class BookImportUiState(
    val fileName: String? = null,
    val busy: Boolean = false,
    /** TXT 才有：能换编码和拆章方式。 */
    val isText: Boolean = false,
    val charset: String? = null,
    val detectedCharset: String? = null,
    val rule: SplitRule = SplitRule.Auto,
    val rows: List<ImportRow> = emptyList(),
    val importError: BookImportException.Reason? = null,
    val epubError: EpubException.Reason? = null,
    val error: String? = null,
    val done: Int? = null,
)

class BookImportVM(
    private val workspaceId: String,
    private val context: Context,
    private val importer: BookImporter,
) : ViewModel() {
    private val _state = MutableStateFlow(BookImportUiState())
    val state = _state.asStateFlow()

    private var file: File? = null
    private var source: BookImportSource? = null
    private var loaded: BookImport? = null
    private var edits = ImportEdits()
    private var job: Job? = null

    fun open(uri: Uri) = work {
        close()
        _state.value = BookImportUiState(busy = true)
        withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "book"
            val copy = File(context.cacheDir, "book_import/${Uuid.random()}").also { file = it }
            (context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")).use { BookImportSource.copy(it, copy) }
            val opened = BookImportSource(copy, name).also { source = it }
            _state.update { it.copy(fileName = name, isText = opened.isText) }
        }
        load()
    }

    fun setCharset(charset: String?) = work {
        _state.update { it.copy(charset = charset) }
        load()
    }

    fun setRule(rule: SplitRule) = work {
        _state.update { it.copy(rule = rule) }
        load()
    }

    fun rename(source: Int, title: String) = work {
        edits = edits.copy(titles = edits.titles + (source to title.trim()))
        refresh()
    }

    fun mergeUp(source: Int) = work {
        edits = edits.copy(merged = edits.merged + source)
        refresh()
    }

    private suspend fun load() {
        edits = ImportEdits()
        val opened = source ?: return
        val state = _state.value
        loaded = withContext(Dispatchers.IO) { opened.load(state.charset, state.rule) }
        _state.update { it.copy(detectedCharset = opened.charset) }
        refresh()
    }

    private suspend fun refresh() {
        val import = loaded ?: return
        val rows = withContext(Dispatchers.Default) { import.rows(edits) }
        _state.update { it.copy(rows = rows) }
    }

    fun import() = work {
        val import = withContext(Dispatchers.Default) { checkNotNull(loaded).edited(edits) }
        importer.import(workspaceId, import)
        _state.update { it.copy(done = import.chapters.size) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun work(block: suspend () -> Unit) {
        val previous = job
        job = viewModelScope.launch {
            previous?.join()
            _state.update { it.copy(busy = true, error = null, importError = null, epubError = null) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: BookImportException) {
                _state.update { it.copy(importError = e.reason, rows = emptyList()) }
            } catch (e: EpubException) {
                _state.update { it.copy(epubError = e.reason, rows = emptyList()) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun close() {
        source?.close()
        source = null
        loaded = null
        edits = ImportEdits()
        file?.delete()
        file = null
    }

    override fun onCleared() = close()
}
