package me.rerere.rikkihub.ui.pages.files

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.files.FileManagerDeviceAccess
import me.rerere.rikkihub.data.files.FileManagerRoot
import me.rerere.rikkihub.data.files.FileManagerService
import me.rerere.rikkihub.data.files.FileManagerSource
import me.rerere.rikkihub.data.files.FileNameIndex
import me.rerere.rikkihub.data.files.FileManagerVolume
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceExtractionState
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceFolderImporter
import me.rerere.workspace.WorkspaceArchiveException
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceTransferMode
import java.io.File
import kotlin.uuid.Uuid

enum class FileManagerSide {
    LEFT, RIGHT;

    val other: FileManagerSide get() = if (this == LEFT) RIGHT else LEFT
}

enum class FileManagerSort { NAME, TIME, SIZE }

data class FileManagerPane(
    val root: FileManagerRoot,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val permissionRequired: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    fun visible(sort: FileManagerSort, showHidden: Boolean): List<WorkspaceFileEntry> {
        val shown = if (showHidden) entries else entries.filterNot { it.name.startsWith('.') }
        val order = when (sort) {
            FileManagerSort.NAME -> compareBy<WorkspaceFileEntry> { it.name.lowercase() }
            FileManagerSort.TIME -> compareByDescending { it.updatedAt }
            FileManagerSort.SIZE -> compareByDescending { it.sizeBytes }
        }
        return shown.sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.then(order))
    }
}

/** File name search over the whole root of [side] (Everything-style: index once, filter per keystroke). */
data class FileManagerSearch(
    val side: FileManagerSide,
    val query: String = "",
    val hits: List<WorkspaceFileEntry> = emptyList(),
    /** All matches; [hits] holds the first [FileNameIndex.DEFAULT_LIMIT]. */
    val total: Int = 0,
    /** Entries found so far while the index is being built; null once it is ready. */
    val indexing: Int? = 0,
    val indexSize: Int = 0,
    val truncated: Boolean = false,
)

/** A long operation in progress: what it is, the current item and how far it got (null = unknown). */
data class FileManagerOperation(val label: String, val detail: String, val fraction: Float?)

data class FileManagerState(
    val left: FileManagerPane = FileManagerPane(FileManagerRoot.Library),
    val right: FileManagerPane = FileManagerPane(FileManagerRoot.Device(FileManagerService.PRIMARY_VOLUME_ID)),
    val active: FileManagerSide = FileManagerSide.LEFT,
    val volumes: List<FileManagerVolume> = emptyList(),
    val sort: FileManagerSort = FileManagerSort.NAME,
    val showHidden: Boolean = false,
    val operation: FileManagerOperation? = null,
    val extraction: Pair<FileManagerSide, WorkspaceExtractionState>? = null,
    val search: FileManagerSearch? = null,
) {
    fun pane(side: FileManagerSide) = if (side == FileManagerSide.LEFT) left else right
    val selectedCount: Int get() = left.selected.size + right.selected.size
}

/**
 * Picks waiting for the chat that opened the file manager, keyed by the request id the chat
 * passed in. The chat takes its entry when it is shown again.
 */
class FileManagerPicks {
    private val pending = MutableStateFlow<Map<String, List<UIMessagePart>>>(emptyMap())
    private val imports = MutableStateFlow<Map<String, List<FileManagerSource>>>(emptyMap())

    fun put(request: String, parts: List<UIMessagePart>) = pending.update { it + (request to parts) }

    fun take(request: String): List<UIMessagePart>? {
        var taken: List<UIMessagePart>? = null
        pending.update { current -> taken = current[request]; current - request }
        return taken
    }

    /** Files and folders picked for a workspace import (the workspace copies them itself). */
    fun putImport(request: String, sources: List<FileManagerSource>) = imports.update { it + (request to sources) }

    fun takeImport(request: String): List<FileManagerSource>? {
        var taken: List<FileManagerSource>? = null
        imports.update { current -> taken = current[request]; current - request }
        return taken
    }

    companion object {
        fun newRequest(): String = Uuid.random().toString()
    }
}

class FileManagerVM(
    private val context: Context,
    private val service: FileManagerService,
    private val filesManager: FilesManager,
    private val picks: FileManagerPicks,
) : ViewModel() {
    private val _state = MutableStateFlow(FileManagerState())
    val state = _state.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-line results and errors for a toast. */
    val messages = _messages.asSharedFlow()
    private var operationJob: Job? = null

    private var shown = false

    init {
        refreshAccess()
    }

    /** The page is on screen again (back from the editor): re-read; the first showing already loads in init. */
    fun onShown() {
        if (shown) refreshAccess() else shown = true
    }

    /** Re-reads volumes and permission (after returning from system settings) and reloads both panes. */
    fun refreshAccess() {
        _state.update { it.copy(volumes = runCatching { service.volumes() }.getOrDefault(emptyList())) }
        reload(FileManagerSide.LEFT)
        reload(FileManagerSide.RIGHT)
    }

    fun deviceAccess(): FileManagerDeviceAccess = service.deviceAccess()

    fun setActive(side: FileManagerSide) = _state.update { it.copy(active = side) }

    fun setSort(sort: FileManagerSort) = _state.update { it.copy(sort = sort) }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        runSearch()
    }

    private var searchIndex: FileNameIndex? = null
    private var indexJob: Job? = null
    private var queryJob: Job? = null

    /** Searches the whole root of the active pane; the index is built (or reused) right away. */
    fun openSearch() {
        val side = state.value.active
        val root = state.value.pane(side).root
        _state.update { it.copy(search = FileManagerSearch(side)) }
        searchIndex = null
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            try {
                val index = service.nameIndex(root) { found ->
                    _state.update { it.copy(search = it.search?.copy(indexing = found)) }
                }
                searchIndex = index
                _state.update {
                    it.copy(search = it.search?.copy(indexing = null, indexSize = index.size, truncated = index.truncated))
                }
                runSearch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(errorText(e))
                closeSearch()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(search = it.search?.copy(query = query)) }
        runSearch()
    }

    fun closeSearch() {
        indexJob?.cancel()
        queryJob?.cancel()
        searchIndex = null
        _state.update { it.copy(search = null) }
    }

    private fun runSearch() {
        val search = state.value.search ?: return
        val index = searchIndex ?: return
        val showHidden = state.value.showHidden
        queryJob?.cancel()
        queryJob = viewModelScope.launch(Dispatchers.Default) {
            val result = index.search(search.query, showHidden)
            ensureActive()
            _state.update { current ->
                val latest = current.search?.takeIf { it.query == search.query } ?: return@update current
                current.copy(search = latest.copy(hits = result.hits, total = result.total))
            }
        }
    }

    fun open(side: FileManagerSide, dir: String) {
        updatePane(side) { it.copy(path = dir, selected = emptySet()) }
        reload(side)
    }

    /** Goes to the parent folder; false at the root. */
    fun goUp(side: FileManagerSide): Boolean {
        val path = state.value.pane(side).path
        if (path.isEmpty()) return false
        open(side, path.substringBeforeLast('/', missingDelimiterValue = ""))
        return true
    }

    fun switchRoot(side: FileManagerSide, root: FileManagerRoot) {
        updatePane(side) { FileManagerPane(root) }
        reload(side)
    }

    fun toggleSelect(side: FileManagerSide, path: String) = updatePane(side) {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun selectAll(side: FileManagerSide) = updatePane(side) { pane ->
        val all = pane.visible(state.value.sort, state.value.showHidden).map { it.path }.toSet()
        pane.copy(selected = if (pane.selected.containsAll(all)) emptySet() else all)
    }

    fun clearSelection() {
        updatePane(FileManagerSide.LEFT) { it.copy(selected = emptySet()) }
        updatePane(FileManagerSide.RIGHT) { it.copy(selected = emptySet()) }
    }

    fun file(side: FileManagerSide, path: String): File = service.file(state.value.pane(side).root, path)

    fun realFile(side: FileManagerSide, path: String): File? = service.realFile(state.value.pane(side).root, path)

    fun reload(side: FileManagerSide) {
        val pane = state.value.pane(side)
        updatePane(side) { it.copy(loading = true) }
        viewModelScope.launch {
            val root = pane.root
            val result = runCatching { service.list(root, pane.path) }
            updatePane(side) { current ->
                if (current.root != root || current.path != pane.path) return@updatePane current
                result.fold(
                    onSuccess = { entries ->
                        val paths = entries.mapTo(HashSet()) { it.path }
                        current.copy(entries = entries, loading = false, error = null, permissionRequired = false,
                            selected = current.selected.filterTo(LinkedHashSet()) { it in paths })
                    },
                    onFailure = { error ->
                        val denied = (error as? WorkspaceFileOperationException)?.code ==
                            WorkspaceFileOperationException.FILE_PERMISSION_DENIED
                        current.copy(entries = emptyList(), loading = false, permissionRequired = denied,
                            error = if (denied) null else errorText(error), selected = emptySet())
                    },
                )
            }
        }
    }

    fun create(side: FileManagerSide, leafName: String, directory: Boolean, onCreated: (String) -> Unit = {}) {
        val pane = state.value.pane(side)
        val path = child(pane.path, leafName)
        viewModelScope.launch {
            runCatching {
                if (directory) service.createFolder(pane.root, path) else service.createFile(pane.root, path)
            }.onSuccess {
                reload(side)
                onCreated(path)
            }.onFailure { emit(errorText(it)) }
        }
    }

    fun rename(side: FileManagerSide, path: String, newName: String) {
        val root = state.value.pane(side).root
        viewModelScope.launch {
            runCatching { service.rename(root, path, newName) }
                .onSuccess { reload(side) }
                .onFailure { emit(errorText(it)) }
        }
    }

    fun delete(side: FileManagerSide, paths: List<String>) = runOperation(context.getString(R.string.common_delete)) {
        val pane = state.value.pane(side)
        val result = service.delete(pane.root, paths)
        clearSelection()
        reloadAll()
        emit(if (result.failures.isEmpty()) context.getString(R.string.file_manager_deleted, result.deletedCount)
        else context.getString(R.string.file_manager_delete_partial, result.deletedCount, result.failures.size) +
            "\n" + result.failures.joinToString("\n") { "${it.path.substringAfterLast('/')}: ${it.error.orEmpty()}" })
    }

    /** Copies or moves the selection of [side] into the folder open on the other side. */
    fun transfer(side: FileManagerSide, mode: WorkspaceTransferMode) {
        val from = state.value.pane(side)
        val to = state.value.pane(side.other)
        val paths = from.selected.toList()
        if (paths.isEmpty()) return
        val label = context.getString(if (mode == WorkspaceTransferMode.MOVE) R.string.workspace_move else R.string.workspace_copy)
        var finished = 0
        runOperation(label, onCancelled = {
            emit(if (finished > 0) context.getString(R.string.workspace_transfer_cancelled, finished)
            else context.getString(R.string.workspace_transfer_cancelled_none))
        }) {
            val result = service.transfer(from.root, paths, to.root, to.path, mode) { progress ->
                finished = progress.itemIndex
                val fraction = if (progress.bytesTotal > 0) {
                    (progress.itemIndex + progress.bytesDone.toFloat() / progress.bytesTotal) / progress.itemCount
                } else progress.itemIndex.toFloat() / progress.itemCount
                setProgress(label, "${progress.itemIndex + 1}/${progress.itemCount} · ${progress.name}", fraction)
            }
            clearSelection()
            reloadAll()
            val ok = result.succeeded
            val destination = displayPath(to)
            var text = when (mode) {
                WorkspaceTransferMode.MOVE -> if (result.failures.isEmpty()) context.getString(R.string.workspace_transfer_move_done, ok.size, destination)
                else context.getString(R.string.workspace_move_selection_partial, ok.size, result.failures.size)
                WorkspaceTransferMode.COPY -> if (result.failures.isEmpty()) context.getString(R.string.workspace_transfer_copy_done, ok.size, destination)
                else context.getString(R.string.workspace_transfer_copy_partial, ok.size, result.failures.size)
            }
            ok.count { it.renamed }.takeIf { it > 0 }?.let { text += context.getString(R.string.workspace_transfer_renamed, it) }
            result.items.sumOf { it.skippedLinks }.takeIf { it > 0 }?.let { text += context.getString(R.string.workspace_transfer_skipped_links, it) }
            if (result.failures.isNotEmpty()) {
                text += "\n" + result.failures.joinToString("\n") { "${it.source.substringAfterLast('/')}: ${reason(it.errorCode, it.error)}" }
            }
            emit(text)
        }
    }

    fun zip(side: FileManagerSide, name: String) {
        val pane = state.value.pane(side)
        val paths = pane.selected.toList()
        if (paths.isEmpty()) return
        runOperation(context.getString(R.string.file_manager_zip)) {
            val entry = service.zip(pane.root, pane.path, paths, name)
            clearSelection()
            reload(side)
            emit(context.getString(R.string.file_manager_zip_done, entry.name))
        }
    }

    fun startExtract(side: FileManagerSide, entry: WorkspaceFileEntry) =
        _state.update { it.copy(extraction = side to WorkspaceExtractionState(entry)) }

    fun dismissExtract() {
        if (state.value.extraction?.second?.running != true) _state.update { it.copy(extraction = null) }
    }

    fun extract(password: String?, nameEncoding: String?) {
        val (side, extraction) = state.value.extraction ?: return
        val root = state.value.pane(side).root
        _state.update { it.copy(extraction = side to extraction.copy(running = true, errorCode = null, errorMessage = null)) }
        operationJob = viewModelScope.launch {
            try {
                val result = service.extract(root, extraction.entry.path, password?.toCharArray(), nameEncoding) { progress ->
                    _state.update { current ->
                        current.extraction?.let { (s, e) -> current.copy(extraction = s to e.copy(progress = progress)) } ?: current
                    }
                }
                _state.update { it.copy(extraction = null) }
                reload(side)
                emit(if (result.skipped > 0) context.getString(R.string.workspace_extract_done_skipped, result.name, result.skipped)
                else context.getString(R.string.workspace_extract_done, result.name))
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(extraction = null) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update { current ->
                    current.copy(extraction = side to extraction.copy(
                        running = false,
                        errorCode = (error as? WorkspaceFileOperationException)?.code ?: WorkspaceArchiveException.ARCHIVE_CORRUPT,
                        errorMessage = error.message,
                    ))
                }
            }
        }
    }

    fun importUris(side: FileManagerSide, uris: List<Uri>) {
        val pane = state.value.pane(side)
        runOperation(context.getString(R.string.file_manager_import)) {
            var imported = 0
            for ((index, uri) in uris.withIndex()) {
                val name = filesManager.getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                setProgress(context.getString(R.string.file_manager_import), name, index.toFloat() / uris.size)
                val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri) } ?: continue
                service.import(pane.root, pane.path, name, input)
                imported++
            }
            reload(side)
            emit(context.getString(R.string.file_manager_imported, imported))
        }
    }

    fun importFolder(side: FileManagerSide, treeUri: Uri) {
        val pane = state.value.pane(side)
        runOperation(context.getString(R.string.file_manager_import)) {
            val tree = withContext(Dispatchers.IO) { WorkspaceFolderImporter(context.contentResolver).openTree(treeUri) }
            val result = service.importFolder(pane.root, pane.path, tree)
            reload(side)
            emit(context.getString(R.string.file_manager_imported_folder, result.rootName, result.fileCount))
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    /** Selected files of both panes (folders are not sent). */
    fun selectedFiles(): List<File> = FileManagerSide.entries.flatMap { side ->
        val pane = state.value.pane(side)
        pane.entries.filter { it.path in pane.selected && !it.isDirectory }.map { file(side, it.path) }
    }

    fun selectionHasFolders(): Boolean = FileManagerSide.entries.any { side ->
        val pane = state.value.pane(side)
        pane.entries.any { it.path in pane.selected && it.isDirectory }
    }

    /** Turns the selected files into chat attachments for [request]; true when the picker can close. */
    fun send(request: String, onDone: () -> Unit) {
        val files = selectedFiles()
        runOperation(context.getString(R.string.file_manager_send)) {
            val result = filesManager.createChatPartsFromLocalFiles(files)
            result.unsupported.forEach { emit(context.getString(R.string.chat_input_unsupported_file_type, it)) }
            result.failed.forEach { emit(context.getString(R.string.chat_input_file_read_failed, it)) }
            if (result.attachments.isNotEmpty()) {
                picks.put(request, result.attachments.map { it.part })
                clearSelection()
                onDone()
            }
        }
    }

    /** Hands the selection (files and folders) to the workspace that asked for [request]. */
    fun pickForImport(request: String, onDone: () -> Unit) {
        val sources = FileManagerSide.entries.flatMap { side ->
            val pane = state.value.pane(side)
            val paths = pane.entries.filter { it.path in pane.selected }.map { it.path }
            if (paths.isEmpty()) emptyList() else runCatching { service.sources(pane.root, paths) }
                .onFailure { _messages.tryEmit(errorText(it)) }
                .getOrDefault(emptyList())
        }
        if (sources.isEmpty()) return
        picks.putImport(request, sources)
        clearSelection()
        onDone()
    }

    fun displayPath(pane: FileManagerPane): String {
        val rootName = when (val root = pane.root) {
            FileManagerRoot.Library -> context.getString(R.string.file_manager_library)
            is FileManagerRoot.Device -> state.value.volumes.firstOrNull { it.id == root.volumeId }?.label ?: root.volumeId
        }
        return if (pane.path.isEmpty()) rootName else "$rootName/${pane.path}"
    }

    private fun runOperation(label: String, onCancelled: suspend () -> Unit = {}, block: suspend () -> Unit) {
        if (state.value.operation != null) return
        _state.update { it.copy(operation = FileManagerOperation(label, "", null)) }
        operationJob = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    reloadAll()
                    onCancelled()
                }
                throw cancelled
            } catch (error: Throwable) {
                reloadAll()
                emit(errorText(error))
            } finally {
                _state.update { it.copy(operation = null) }
            }
        }
    }

    private fun setProgress(label: String, detail: String, fraction: Float?) =
        _state.update { it.copy(operation = FileManagerOperation(label, detail, fraction)) }

    private fun reloadAll() {
        reload(FileManagerSide.LEFT)
        reload(FileManagerSide.RIGHT)
    }

    private fun updatePane(side: FileManagerSide, transform: (FileManagerPane) -> FileManagerPane) = _state.update {
        if (side == FileManagerSide.LEFT) it.copy(left = transform(it.left)) else it.copy(right = transform(it.right))
    }

    private suspend fun emit(message: String) = _messages.emit(message)

    private fun child(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun errorText(error: Throwable): String =
        (error as? WorkspaceFileOperationException)?.let { reason(it.code, it.message) }
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.workspace_transfer_error_failed)

    private fun reason(code: String?, raw: String?): String = when (code) {
        WorkspaceFileOperationException.FILE_NOT_FOUND -> context.getString(R.string.workspace_transfer_error_not_found)
        WorkspaceFileOperationException.TRANSFER_SAME_LOCATION -> context.getString(R.string.workspace_transfer_error_same_location)
        WorkspaceFileOperationException.TRANSFER_INTO_ITSELF -> context.getString(R.string.workspace_transfer_error_into_itself)
        WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED -> context.getString(R.string.workspace_transfer_error_symlink)
        WorkspaceFileOperationException.NOT_ENOUGH_SPACE -> context.getString(R.string.workspace_transfer_error_no_space)
        WorkspaceFileOperationException.FILE_ALREADY_EXISTS -> context.getString(R.string.workspace_new_file_exists)
        WorkspaceFileOperationException.INVALID_FILE_NAME -> context.getString(R.string.workspace_new_file_invalid_name)
        WorkspaceFileOperationException.FILE_PERMISSION_DENIED -> context.getString(R.string.file_manager_permission_title)
        FileManagerService.READ_ONLY_FOLDER -> context.getString(R.string.file_manager_workspaces_read_only)
        WorkspaceFileOperationException.PARENT_NOT_FOUND,
        WorkspaceFileOperationException.PARENT_NOT_DIRECTORY -> context.getString(R.string.workspace_transfer_error_target_missing)
        else -> raw?.takeIf { it.isNotBlank() } ?: context.getString(R.string.workspace_transfer_error_failed)
    }
}
