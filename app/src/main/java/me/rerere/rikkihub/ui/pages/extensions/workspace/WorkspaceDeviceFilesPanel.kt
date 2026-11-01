package me.rerere.rikkihub.ui.pages.extensions.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.CheckmarkSquare02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.FolderImport
import me.rerere.hugeicons.stroke.FolderZip
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkihub.utils.writeClipboardText
import me.rerere.workspace.WorkspaceDeviceDirectoryGuard
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.fileSizeToString
import me.rerere.rikkihub.utils.plus
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceDeviceFileAccess
import me.rerere.workspace.WorkspaceDeviceMount
import me.rerere.workspace.WorkspaceFileEntry
import org.koin.compose.koinInject

/** 文本预览/编辑大小上限（1 MiB）；超出只能导出，不读入内存。 */
private const val DEVICE_TEXT_MAX_BYTES = 1L * 1024 * 1024

/**
 * 设备文件面板 VM：所有文件 IO 经 `service.withMountOperation`（gate
 * FILE_OPERATION 登记，mount/授权在 admission 内复查），再调
 * [WorkspaceDeviceFileAccess]（每操作复查 source 可读/写、只作用于相对路径、
 * 根不可删）。不持有宿主绝对路径，不回读 display name。
 *
 * 直连写语义：写/删立即作用原文件，无 COW/回滚；partial failure 如实报 error。
 */
class WorkspaceDeviceFilesVM(
    private val workspaceId: String,
    private val mountId: String,
    private val service: WorkspaceDeviceDirectoryService,
    context: Context,
    private val openedViewToken: String? = null,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val editorDraftStore = WorkspaceEditorDraftStore(File(appContext.noBackupFilesDir, "workspace_editor_drafts"))
    private var editorDraftToken: String? = null
    private val editorDraftPrefix = "device\u0000$workspaceId\u0000$mountId\u0000"
    // VM-owned, initialized once per document load, never synchronized back on recomposition.
    val editorTextState = TextFieldState()

    private var closeAfterSaveJob: Job? = null

    private fun installEditor(path: String, text: String, original: String?, revision: String?, opened: String?) {
        editorTextState.setTextAndPlaceCursorAtEnd(text)
        editorTextState.undoState.clearHistory()
        _state.update { it.copy(editing = DeviceEditingState(path, original, text, revision = revision, opened = opened)) }
    }

    internal fun persistEditorDraft(): String? {
        val editing = state.value.editing ?: return null
        val selection = editorTextState.selection
        return editorDraftStore.save(editorDraftPrefix + editing.path, editorTextState.text,
            selection.start, selection.end, editorDraftToken, editing.original, editing.revision, editing.opened)?.also { editorDraftToken = it }
            ?: editorDraftToken?.takeIf { editorDraftStore.hasDraft(it) }
    }

    internal fun restoreEditorDraft(token: String) {
        if (state.value.editing != null) return
        val draft = editorDraftStore.restore(token) ?: return
        if (!draft.fileKey.startsWith(editorDraftPrefix)) return
        val path = draft.fileKey.removePrefix(editorDraftPrefix)
        if (runCatching { normalizeDeviceRelativePath(path) }.getOrNull() != path || path.isBlank()) return
        installEditor(path, draft.text, draft.originalText, draft.revision, draft.openedText)
        editorTextState.edit { selection = TextRange(draft.selStart, draft.selEnd) }
        editorDraftToken = token
    }

    fun editorDirty(): Boolean = state.value.editing?.let { it.original == null || editorTextState.text.toString() != it.original } == true
    private val access = WorkspaceDeviceFileAccess(
        WorkspaceConfig(
            maxReadBytes = DEVICE_TEXT_MAX_BYTES,
            maxListEntries = MAX_LIST_ENTRIES,
        )
    )
    private val folderImporter = WorkspaceFolderImporter(appContext.contentResolver)

    private val _state = MutableStateFlow(WorkspaceDeviceFilesState())
    val state = _state.asStateFlow()
    private val fileCreation = WorkspaceFileCreation()
    val fileCreationState = fileCreation.state
    fun dismissFileCreationError() = fileCreation.dismissError()

    init {
        viewModelScope.launch {
            runWorkspaceEditorAutoSave(editorTextState, awaitIdle = { state.first { it.editing?.saving != true } }) {
                saveEditor(auto = true)
            }
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    service.withMountOperation(workspaceId, mountId, expectedViewToken = openedViewToken) { it.alias }
                }
            }.onSuccess { alias -> _state.update { it.copy(alias = alias) } }
        }
        load("")
    }

    private suspend fun <T> mountOp(block: (WorkspaceDeviceMount) -> T): T =
        withContext(Dispatchers.IO) {
            service.withMountOperation(workspaceId, mountId, expectedViewToken = openedViewToken) { mount -> block(mount) }
        }

    private suspend fun <T> mountOpSuspend(block: suspend (WorkspaceDeviceMount) -> T): T =
        withContext(Dispatchers.IO) {
            service.withMountOperation(workspaceId, mountId, expectedViewToken = openedViewToken) { mount -> block(mount) }
        }

    fun load(path: String = state.value.path) {
        val target = runCatching { normalizeDeviceRelativePath(path) }.getOrElse { "" }
        _state.update { it.copy(path = target, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = mountOp { access.list(it, target) }
                _state.update { it.copy(entries = entries, loading = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(entries = emptyList(), loading = false, error = e.message ?: "Load failed")
                }
            }
        }
    }

    fun refresh() = load()

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        load(deviceChildPath(state.value.path, entry.name))
    }

    fun goUp() {
        val parent = deviceParentPath(state.value.path)
        if (parent != state.value.path) load(parent)
    }

    // ---------------------------------------------------------- 写操作

    private fun mutate(refreshAfter: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            try {
                block()
                if (refreshAfter) load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Operation failed") }
            }
        }
    }

    fun mkdir(name: String) = mutate {
        mountOp { access.mkdir(it, deviceChildPath(state.value.path, name)) }
    }

    fun newFile(request: WorkspaceNewFileRequest): Boolean {
        if (request.target.workspaceId != workspaceId || request.target.mountId != mountId ||
            request.target.area != me.rerere.workspace.WorkspaceStorageArea.FILES ||
            state.value.isTransferring || state.value.editing != null
        ) return false
        if (!fileCreation.begin(request)) return false
        viewModelScope.launch {
            try {
                // mount ID/view token and path are frozen; admission rechecks current activation,
                // permission and gate, never retaining a resolved mount across operations.
                mountOp {
                    if (request.isDirectory) access.createDirectory(it, request.path)
                    else access.createFile(it, request.path)
                }
                fileCreation.created(request)
                load()
                val event = fileCreation.state.value.pendingOpen ?: return@launch
                try {
                    val info = mountOp { access.stat(it, request.path) }
                    check(info.exists && info.isFile && info.sizeBytes <= DEVICE_TEXT_MAX_BYTES)
                    val snapshot = mountOp { access.readTextSnapshot(it, request.path) }
                    fileCreation.open(event) {
                        installEditor(request.path, snapshot.content, snapshot.content, snapshot.revision, snapshot.content)
                    }
                } catch (error: CancellationException) {
                    fileCreation.openFailed(event) // The file already exists; do not report create failure.
                    throw error
                } catch (_: Exception) {
                    fileCreation.openFailed(event)
                }
            } catch (error: CancellationException) {
                if (fileCreation.state.value.error != WorkspaceCreateError.OPEN_FAILED) {
                    fileCreation.failed(WorkspaceCreateError.INTERRUPTED)
                    load()
                }
                throw error
            } catch (error: Exception) {
                fileCreation.failed(when (error) {
                    is me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorageException ->
                        WorkspaceCreateError.PERMISSION_DENIED
                    is me.rerere.rikkihub.data.workspace.WorkspaceGateException,
                    is me.rerere.rikkihub.data.workspace.WorkspaceDeviceConfigException ->
                        WorkspaceCreateError.UNAVAILABLE
                    else -> workspaceCreateError(error)
                })
            } finally {
                fileCreation.finish()
            }
        }
        return true
    }

    fun move(source: String, target: String) = mutate {
        mountOp { access.move(it, source, normalizeDeviceRelativePath(target), overwrite = false) }
    }

    fun delete(entry: WorkspaceFileEntry) = mutate {
        val path = deviceChildPath(state.value.path, entry.name)
        mountOp { access.delete(it, path, recursive = entry.isDirectory) }
    }

    /** 批量删除；[paths] 为发起快照（相对 mount.source）。部分失败如实汇总。 */
    fun deleteBatch(paths: List<String>) {
        if (paths.isEmpty() || !beginTransfer()) return
        viewModelScope.launch {
            try {
                val result = mountOpSuspend { access.deleteBatch(it, paths) }
                when {
                    result.failures.isEmpty() -> _state.update {
                        it.copy(transferNotice = "Deleted ${result.deletedCount} item(s)")
                    }

                    else -> _state.update {
                        it.copy(
                            error = "Deleted ${result.deletedCount}; ${result.failures.size} failed: " +
                                result.failures.joinToString("; ") { f -> "${f.path} (${f.error})" },
                        )
                    }
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(error = "Delete cancelled; items already removed cannot be restored")
                }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Delete failed") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
            load()
        }
    }

    // ---------------------------------------------------------- 导入/导出

    /** [inputStream]/[fileName] 已由 SAF 回调解析；落点为发起时目录快照。 */
    fun importFile(destinationPath: String, fileName: String, inputStream: InputStream) {
        if (!beginTransfer()) return
        viewModelScope.launch {
            try {
                mountOp { access.importFile(it, destinationPath, fileName, inputStream) }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Import failed") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
        }
    }

    fun importFolder(destinationPath: String, treeUri: Uri) {
        if (!beginTransfer()) return
        viewModelScope.launch {
            try {
                val tree = withContext(Dispatchers.IO) { folderImporter.openTree(treeUri) }
                val result = mountOpSuspend { access.importFolder(it, destinationPath, tree) }
                _state.update {
                    it.copy(transferNotice = "Imported ${result.rootName} (${result.fileCount} files)")
                }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Folder import failed") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
        }
    }

    /** 单文件导出到 SAF 文档；失败/取消时尽力删除半成品文档。 */
    fun exportFile(path: String, destinationUri: Uri) {
        if (!beginTransfer()) return
        viewModelScope.launch {
            var opened = false
            try {
                val stream = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destinationUri)
                } ?: error("Cannot open output stream")
                opened = true
                stream.use { out ->
                    mountOp { mount -> access.exportFile(mount, path, out as OutputStream) }
                }
                _state.update { it.copy(transferNotice = "Exported") }
            } catch (e: CancellationException) {
                if (opened) deletePartialDocument(destinationUri)
                throw e
            } catch (e: Throwable) {
                if (opened) deletePartialDocument(destinationUri)
                _state.update { it.copy(error = e.message ?: "Export failed") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
        }
    }

    fun exportZip(paths: List<String>, destinationUri: Uri) {
        if (paths.isEmpty() || !beginTransfer()) return
        viewModelScope.launch {
            var opened = false
            try {
                val stream = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destinationUri)
                } ?: error("Cannot open output stream")
                opened = true
                val result = stream.use { out ->
                    mountOpSuspend { mount -> access.exportZip(mount, paths, out) }
                }
                _state.update { it.copy(transferNotice = "Exported ${result.fileCount} file(s)") }
            } catch (e: CancellationException) {
                if (opened) deletePartialDocument(destinationUri)
                throw e
            } catch (e: Throwable) {
                if (opened) deletePartialDocument(destinationUri)
                _state.update { it.copy(error = e.message ?: "ZIP export failed") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
        }
    }

    /** 导出到 cacheDir 真实文件（分享/图片预览/外部打开用）。 */
    fun exportToCacheFile(path: String, name: String, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(appContext.cacheDir, "device_files_share").apply { mkdirs() }
                val file = File(dir, name)
                file.outputStream().use { out -> mountOp { access.exportFile(it, path, out) } }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Export failed") }
            }
        }
    }

    // ---------------------------------------------------------- 编辑器

    /** 文本预览/编辑入口：先 stat 查大小，>1MiB 不读入。 */
    fun openEditor(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            try {
                val info = mountOp { access.stat(it, path) }
                if (!info.exists || !info.isFile) error("Not a file")
                if (info.sizeBytes > DEVICE_TEXT_MAX_BYTES) {
                    _state.update { it.copy(error = "too_large:$path") }
                    return@launch
                }
                val snapshot = mountOp { access.readTextSnapshot(it, path) }
                installEditor(path, snapshot.content, snapshot.content, snapshot.revision, snapshot.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = appContext.getString(R.string.workspace_editor_unavailable)) }
            }
        }
    }

    fun updateEditorText(text: String) {
        _state.update { s -> s.copy(editing = s.editing?.copy(text = text)) }
    }

    fun closeEditor() {
        if (state.value.editing?.saving == true) return
        editorDraftStore.delete(editorDraftToken)
        editorDraftToken = null
        _state.update { it.copy(editing = null) }
    }

    /** [auto]: background save — silent on success, skipped when clean or paused; failure pauses it. */
    fun saveEditor(closeAfter: Boolean = false, auto: Boolean = false) {
        val editing = state.value.editing ?: return
        val expectedRevision = editing.revision?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return
        if (editing.original == null || editing.saving) return
        if (auto && (editing.autoSavePause != null || !editorDirty())) return
        val frozenText = editorTextState.text.toString()
        _state.update { it.copy(editing = it.editing?.copy(text = frozenText, saving = true)) }
        viewModelScope.launch {
            try {
                val result = mountOp { access.writeTextWithRevision(it, editing.path, frozenText, expectedRevision) }
                _state.update {
                    it.copy(editing = it.editing?.takeIf { current -> current.path == editing.path }?.copy(
                        original = frozenText, revision = result.revision, text = editorTextState.text.toString(), saving = false,
                        autoSavePause = null),
                        transferNotice = if (auto) it.transferNotice else "saved")
                }
                if (closeAfter && editorTextState.text.toString() == frozenText) closeEditor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val conflict = e is me.rerere.workspace.WorkspaceFileRevisionConflictException
                _state.update { s ->
                    s.copy(
                        error = if (auto) s.error else appContext.getString(
                            if (conflict) R.string.workspace_editor_conflict else R.string.workspace_editor_save_failed),
                        editing = s.editing?.let { current ->
                            if (current.path != editing.path) current else current.copy(
                                autoSavePause = if (conflict) WorkspaceEditorAutoSavePause.CONFLICT else WorkspaceEditorAutoSavePause.FAILED,
                                confirmLeave = current.confirmLeave || (closeAfter && auto))
                        },
                    )
                }
            } finally {
                _state.update { it.copy(editing = it.editing?.copy(saving = false)) }
            }
        }
    }

    /** Editor Back: flush a pending autosave, then close. True = caller must show the leave dialog. */
    fun requestCloseEditor(): Boolean {
        val editing = state.value.editing ?: return false
        if (!editing.saving && !editorDirty()) {
            closeEditor()
            return false
        }
        if (!editing.canAutoSave) return true
        if (closeAfterSaveJob?.isActive == true) return false
        closeAfterSaveJob = viewModelScope.launch {
            state.first { it.editing?.saving != true }
            val current = state.value.editing ?: return@launch
            when {
                !editorDirty() -> closeEditor()
                current.canAutoSave -> saveEditor(closeAfter = true, auto = true)
                else -> _state.update { s -> s.copy(editing = s.editing?.copy(confirmLeave = true)) }
            }
        }
        return false
    }

    fun dismissLeavePrompt() = _state.update { s -> s.copy(editing = s.editing?.copy(confirmLeave = false)) }

    /** Back to the disk text from when this file was opened, as one undoable edit; autosave writes it. */
    fun revertEditor() {
        val opened = state.value.editing?.takeIf { it.validBaseline }?.opened ?: return
        editorTextState.revertWorkspaceEditorTo(opened)
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissTransferNotice() = _state.update { it.copy(transferNotice = null) }

    private fun beginTransfer(): Boolean {
        val previous = _state.getAndUpdate { s ->
            if (s.isTransferring) s else s.copy(isTransferring = true, transferNotice = null, error = null)
        }
        return !previous.isTransferring
    }

    private suspend fun deletePartialDocument(uri: Uri) {
        withContext(NonCancellable + Dispatchers.IO) {
            deleteDocumentQuietly(appContext.contentResolver, uri)
        }
    }

    private companion object {
        const val MAX_LIST_ENTRIES = 512
    }
}

data class WorkspaceDeviceFilesState(
    val alias: String = "",
    /** 相对 mount.source 的当前目录；"" = 连接根。 */
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val isTransferring: Boolean = false,
    val transferNotice: String? = null,
    val error: String? = null,
    val editing: DeviceEditingState? = null,
)

data class DeviceEditingState(
    val path: String,
    val original: String?,
    val text: String,
    val saving: Boolean = false,
    val revision: String? = null,
    /** Disk text when this file was opened: the Revert target. Null after a pre-v3 draft. */
    val opened: String? = null,
    val autoSavePause: WorkspaceEditorAutoSavePause? = null,
    /** A Back-triggered autosave failed: the UI must offer save/discard/continue. */
    val confirmLeave: Boolean = false,
) {
    val validBaseline: Boolean get() = original != null && revision?.matches(Regex("[0-9a-f]{64}")) == true
    val dirty: Boolean get() = original == null || text != original
    val canAutoSave: Boolean get() = validBaseline && autoSavePause == null
}

/**
 * 已连接设备目录的文件面板（直连写手机原目录，无 COW/回滚）。
 *
 * 身份 = mountId + 相对路径；不接触宿主绝对路径。文件操作走
 * `WorkspaceDeviceDirectoryService.withMountOperation`（gate FILE_OPERATION，
 * 不受模型工具开关约束）。删除/写仅相对路径内，根不可删。
 */
@Composable
fun WorkspaceDeviceFilesPanel(
    workspaceId: String,
    mountId: String,
    modifier: Modifier = Modifier,
    openedViewToken: String? = null,
    onBack: () -> Unit = {},
) {
    val service: WorkspaceDeviceDirectoryService = koinInject()
    val context = LocalContext.current
    val viewToken = remember(workspaceId, mountId, openedViewToken) { openedViewToken ?: service.viewToken(workspaceId) }
    val vm: WorkspaceDeviceFilesVM = viewModel(key = "workspace-device-files:$workspaceId:$mountId:$viewToken") {
        WorkspaceDeviceFilesVM(
            workspaceId = workspaceId,
            mountId = mountId,
            service = service,
            context = context,
            openedViewToken = viewToken,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    rememberSaveable(workspaceId, mountId, viewToken, saver = Saver<WorkspaceDeviceFilesVM, String>(
        save = { it.persistEditorDraft() },
        restore = { token -> vm.restoreEditorDraft(token); vm },
    )) { vm }
    var showEditorPreview by rememberSaveable(workspaceId, mountId) { mutableStateOf(false) }

    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var newFolderDialog by rememberSaveable { mutableStateOf(false) }
    val creation by vm.fileCreationState.collectAsStateWithLifecycle()
    var newFileTarget by rememberSaveable(workspaceId, mountId, viewToken) {
        mutableStateOf<WorkspaceNewFileTarget?>(null)
    }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    var discardConfirm by rememberSaveable { mutableStateOf(false) }
    val toaster = LocalToaster.current
    val revertedMessage = stringResource(R.string.workspace_editor_reverted)

    var selectionMode by rememberSaveable(mountId) { mutableStateOf(false) }
    var selectedPaths by rememberSaveable(
        mountId,
        stateSaver = Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf<Set<String>>(emptySet()) }

    var lastPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.path) {
        if (lastPath != null && lastPath != state.path) {
            selectedPaths = emptySet()
            selectionMode = false
        }
        lastPath = state.path
    }
    LaunchedEffect(state.entries) {
        val full = state.entries.mapTo(HashSet()) { deviceChildPath(state.path, it.name) }
        val kept = selectedPaths.filterTo(LinkedHashSet()) { it in full }
        if (kept.size != selectedPaths.size) selectedPaths = kept
    }

    // ---- SAF 发起前快照（picker 期间目录/选择可能变）----
    var pendingImportDir by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val dest = pendingImportDir.also { pendingImportDir = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val input = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(dest, fileName, input)
    }

    var pendingFolderImport by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val dest = pendingFolderImport.also { pendingFolderImport = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importFolder(dest, uri)
    }

    var pendingFileExport by rememberSaveable(mountId) {
        mutableStateOf<PendingDeviceFileExport?>(null)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val pending = pendingFileExport.also { pendingFileExport = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportFile(pending.path, uri)
    }

    var pendingZipExport by rememberSaveable(mountId) {
        mutableStateOf<PendingDeviceZipExport?>(null)
    }
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val pending = pendingZipExport.also { pendingZipExport = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportZip(pending.paths, uri)
        selectedPaths = emptySet()
        selectionMode = false
    }

    var pendingBatchDelete by rememberSaveable(mountId) {
        mutableStateOf<PendingDeviceBatchDelete?>(null)
    }

    fun launchZip(paths: Collection<String>) {
        if (state.isTransferring || paths.isEmpty()) return
        pendingZipExport = PendingDeviceZipExport(
            paths = ArrayList(paths),
            suggestedName = "",
        )
        zipLauncher.launch(
            defaultWorkspaceArchiveName(
                workspaceName = state.alias.ifBlank { null },
                directoryPath = state.path,
                selectedPaths = paths.toList(),
            ),
        )
    }

    // ---- 返回栈：编辑器(未保存确认) > 选择模式 > 目录上钻 > onBack ----
    val editing = state.editing
    if (editing != null) {
        BackHandler {
            if (vm.requestCloseEditor()) discardConfirm = true
        }
    } else {
        BackHandler(enabled = selectionMode || selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
            selectionMode = false
        }
        BackHandler(enabled = state.path.isNotBlank()) { vm.goUp() }
    }

    val navigationIcon: @Composable () -> Unit = {
        IconButton(
            onClick = {
                when {
                    editing != null ->
                        if (vm.requestCloseEditor()) discardConfirm = true
                    selectionMode || selectedPaths.isNotEmpty() -> {
                        selectedPaths = emptySet()
                        selectionMode = false
                    }
                    state.path.isNotBlank() -> vm.goUp()
                    else -> onBack()
                }
            },
        ) {
            Icon(
                if (editing != null || state.path.isNotBlank() || selectionMode) {
                    HugeIcons.ArrowTurnBackward
                } else {
                    HugeIcons.ArrowLeft01
                },
                contentDescription = null,
            )
        }
    }
    // Editing a file is the editor page: the same compact bar as the workspace editor.
    Scaffold(
        modifier = modifier,
        topBar = {
            if (editing != null) WorkspaceEditorTopBar(
                navigation = navigationIcon,
                actions = {
                    if (workspaceFilePreviewKind(editing.path) == WorkspaceFilePreviewKind.MARKDOWN) {
                        TextButton(onClick = { showEditorPreview = !showEditorPreview }) {
                            Text(stringResource(if (showEditorPreview) R.string.workspace_editor_source else R.string.workspace_editor_preview))
                        }
                    }
                    val opened = editing.opened
                    if (editing.validBaseline && opened != null && workspaceEditorCanRevert(opened, vm.editorTextState.text)) {
                        TextButton(onClick = {
                            vm.revertEditor()
                            toaster.show(revertedMessage, type = ToastType.Info)
                        }) { Text(stringResource(R.string.workspace_editor_revert)) }
                    }
                    IconButton(
                        onClick = { vm.saveEditor() },
                        enabled = !editing.saving && editing.validBaseline && editing.dirty,
                    ) {
                        Icon(HugeIcons.FloppyDisk, contentDescription = stringResource(R.string.common_save))
                    }
                },
            ) else TopAppBar(
                title = {
                    Text(
                        text = state.alias.ifBlank { stringResource(R.string.workspace_device_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.isTransferring) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (editing != null) {
            // VM state initialized on load ONLY; source-only observer updates String model.
            // innerPadding already holds the navigation bar; consumed, imePadding adds only the keyboard above it.
            Column(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).fillMaxSize().imePadding()) {
                if (!editing.validBaseline) {
                    Text(stringResource(R.string.workspace_editor_unknown_baseline), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                    TextButton(onClick = { discardConfirm = true }) { Text(stringResource(R.string.workspace_editor_reload_discard)) }
                }
                if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                val pause = editing.autoSavePause
                if (editing.validBaseline && pause != null) {
                    Text(stringResource(pause.messageRes), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                    if (pause == WorkspaceEditorAutoSavePause.CONFLICT) {
                        TextButton(onClick = { discardConfirm = true }) { Text(stringResource(R.string.workspace_editor_reload_discard)) }
                    }
                }
                val previewing = showEditorPreview && workspaceFilePreviewKind(editing.path) == WorkspaceFilePreviewKind.MARKDOWN
                if (previewing) {
                    WorkspaceMarkdownPreview(vm.editorTextState.text.toString(), Modifier.weight(1f))
                } else WorkspaceMarkdownEditor(
                    state = vm.editorTextState,
                    markdown = workspaceFilePreviewKind(editing.path) == WorkspaceFilePreviewKind.MARKDOWN,
                    readOnly = !editing.validBaseline,
                    modifier = Modifier.weight(1f),
                    formatKind = workspaceFormatKind(editing.path),
                    onSourceChange = vm::updateEditorText,
                )
                val editingPath = editing.path.trim('/')
                WorkspaceEditorStatusBar(
                    fileName = deviceFileName(editing.path),
                    path = "${WorkspaceDeviceDirectoryGuard.DEVICE_GUEST_ROOT}/${state.alias}" + if (editingPath.isEmpty()) "" else "/$editingPath",
                    state = vm.editorTextState,
                    withSelection = !previewing,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(WorkspaceFileRowSpacing),
            ) {
                // 直连写高危提示
                workspaceBlock {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CustomColors.cardColorsOnSurfaceContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_device_risk_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                workspaceBlock {
                    if (selectionMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = {
                                selectedPaths = emptySet()
                                selectionMode = false
                            }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                            Text(
                                text = stringResource(R.string.imggen_page_selected_count, selectedPaths.size),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = { launchZip(selectedPaths) },
                                enabled = selectedPaths.isNotEmpty() && !state.isTransferring,
                            ) {
                                Text(stringResource(R.string.workspace_device_files_export_zip))
                            }
                            IconButton(
                                onClick = {
                                    if (selectedPaths.isNotEmpty() && !state.isTransferring) {
                                        val names = state.entries.filter {
                                            deviceChildPath(state.path, it.name) in selectedPaths
                                        }
                                        pendingBatchDelete = PendingDeviceBatchDelete(
                                            paths = ArrayList(selectedPaths),
                                            includesDirectories = names.any { it.isDirectory },
                                        )
                                    }
                                },
                                enabled = selectedPaths.isNotEmpty() && !state.isTransferring,
                            ) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(enabled = state.path.isNotBlank(), onClick = vm::goUp) {
                                Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                            }
                            Text(
                                text = "/${state.path}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { newFolderDialog = true },
                                enabled = !state.isTransferring,
                            ) {
                                Icon(
                                    HugeIcons.FolderAdd,
                                    contentDescription = stringResource(R.string.workspace_device_files_new_folder),
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (!creation.busy && creation.pendingOpen == null && !state.loading &&
                                        state.error == null && !state.isTransferring
                                    ) {
                                        vm.dismissFileCreationError()
                                        newFileTarget = WorkspaceNewFileTarget(
                                            workspaceId = workspaceId, parentPath = state.path, mountId = mountId,
                                        )
                                    }
                                },
                                enabled = !state.isTransferring && !state.loading && state.error == null &&
                                    !creation.busy && creation.pendingOpen == null,
                            ) {
                                Icon(
                                    HugeIcons.FileAdd,
                                    contentDescription = stringResource(R.string.workspace_new_file_title),
                                )
                            }
                            IconButton(
                                onClick = {
                                    pendingImportDir = state.path
                                    filePicker.launch(arrayOf("*/*"))
                                },
                                enabled = !state.isTransferring,
                            ) {
                                Icon(
                                    HugeIcons.FileImport,
                                    contentDescription = stringResource(R.string.workspace_detail_import_file),
                                )
                            }
                            IconButton(
                                onClick = {
                                    pendingFolderImport = state.path
                                    folderPicker.launch(null)
                                },
                                enabled = !state.isTransferring,
                            ) {
                                Icon(
                                    HugeIcons.FolderImport,
                                    contentDescription = stringResource(R.string.workspace_device_files_import_folder),
                                )
                            }
                        }
                    }
                }

                if (creation.busy || creation.error != null || creation.createdDirectoryPath != null) {
                    workspaceBlock { WorkspaceFileCreationFeedback(creation, vm::dismissFileCreationError) }
                }

                if (state.isTransferring || state.loading) {
                    workspaceBlock { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }

                state.transferNotice?.let { notice ->
                    workspaceBlock {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = vm::dismissTransferNotice),
                            colors = CustomColors.cardColorsOnSurfaceContainer,
                        ) {
                            Text(
                                text = when (notice) {
                                    "saved" -> stringResource(R.string.workspace_device_files_saved)
                                    "Exported" -> stringResource(R.string.workspace_device_files_exported)
                                    else -> notice
                                },
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                state.error?.let { error ->
                    workspaceBlock {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = vm::dismissError),
                            colors = CustomColors.cardColorsOnSurfaceContainer,
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = if (error.startsWith("too_large:")) {
                                        stringResource(R.string.workspace_device_files_too_large)
                                    } else {
                                        error
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = vm::refresh) {
                                    Text(stringResource(R.string.workspace_device_files_retry))
                                }
                            }
                        }
                    }
                }

                if (!state.loading && state.entries.isEmpty() && state.error == null) {
                    workspaceBlock {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Folder01,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.workspace_detail_empty_directory),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                itemsIndexed(state.entries, key = { _, it -> it.name }) { index, entry ->
                    DeviceFileCard(
                        entry = entry,
                        shape = workspaceFileRowShape(index, state.entries.size),
                        selectionMode = selectionMode,
                        selected = deviceChildPath(state.path, entry.name) in selectedPaths,
                        transferring = state.isTransferring,
                        onOpen = {
                            if (entry.isDirectory) {
                                vm.open(entry)
                            } else {
                                val path = deviceChildPath(state.path, entry.name)
                                when (entry.detectFileType()) {
                                    WorkspaceFileType.TEXT -> vm.openEditor(path)
                                    WorkspaceFileType.IMAGE -> vm.exportToCacheFile(path, entry.name) { file ->
                                        previewImageUri = file.absolutePath
                                    }
                                    WorkspaceFileType.OTHER -> vm.exportToCacheFile(path, entry.name) { file ->
                                        openDeviceFileExternally(context, file)
                                    }
                                }
                            }
                        },
                        onToggleSelect = {
                            selectionMode = true
                            val path = deviceChildPath(state.path, entry.name)
                            selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                        },
                        onRename = { renameTarget = entry },
                        onExport = {
                            pendingFileExport = PendingDeviceFileExport(
                                path = deviceChildPath(state.path, entry.name),
                                suggestedName = entry.name,
                            )
                            exportLauncher.launch(entry.name)
                        },
                        onExportZip = { launchZip(listOf(deviceChildPath(state.path, entry.name))) },
                        onCopyPath = {
                            val path = deviceChildPath(state.path, entry.name).trim('/')
                            val root = "${WorkspaceDeviceDirectoryGuard.DEVICE_GUEST_ROOT}/${state.alias}"
                            context.writeClipboardText(if (path.isEmpty()) root else "$root/$path")
                            toaster.show(context.getString(R.string.workspace_path_copied), type = ToastType.Success)
                        },
                        onDelete = { deleteTarget = entry },
                    )
                }
            }
        }
    }

    // ---- 对话框 ----
    if (newFolderDialog) {
        DeviceNameDialog(
            title = stringResource(R.string.workspace_device_files_new_folder),
            onDismiss = { newFolderDialog = false },
            onConfirm = { name ->
                vm.mkdir(name)
                newFolderDialog = false
            },
        )
    }
    newFileTarget?.let { target ->
        WorkspaceNewFileDialog(
            target = target,
            busy = creation.busy,
            onDismiss = { newFileTarget = null },
            onConfirm = { request ->
                if (vm.newFile(request)) newFileTarget = null
            },
        )
    }
    renameTarget?.let { entry ->
        DeviceMoveDialog(
            entry = entry,
            currentDir = state.path,
            onDismiss = { renameTarget = null },
            onConfirm = { target ->
                vm.move(deviceChildPath(state.path, entry.name), target)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) {
                stringResource(R.string.workspace_detail_delete_directory)
            } else {
                stringResource(R.string.workspace_detail_delete_file)
            },
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.name))
        }
    }
    pendingBatchDelete?.let { pending ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.common_delete),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.deleteBatch(pending.paths)
                pendingBatchDelete = null
                selectedPaths = emptySet()
                selectionMode = false
            },
            onDismiss = { pendingBatchDelete = null },
        ) {
            Text(
                stringResource(R.string.workspace_device_files_batch_delete_message, pending.paths.size) +
                    if (pending.includesDirectories) {
                        " " + stringResource(R.string.workspace_device_files_batch_delete_dirs)
                    } else "",
            )
        }
    }
    if ((discardConfirm || editing?.confirmLeave == true) && editing != null) {
        WorkspaceEditorLeaveDialog(
            saving = editing.saving,
            canSave = editing.validBaseline,
            onSave = { discardConfirm = false; vm.dismissLeavePrompt(); vm.saveEditor(closeAfter = true) },
            onDiscard = { discardConfirm = false; vm.dismissLeavePrompt(); vm.closeEditor() },
            onContinue = { discardConfirm = false; vm.dismissLeavePrompt() },
        )
    }
    previewImageUri?.let { uri ->
        ImagePreviewDialog(images = listOf(uri), onDismissRequest = { previewImageUri = null })
    }
}

private fun openDeviceFileExternally(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
        file.extension.lowercase()
    ) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

@Composable
private fun DeviceFileCard(
    entry: WorkspaceFileEntry,
    shape: Shape,
    selectionMode: Boolean,
    selected: Boolean,
    transferring: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onExportZip: () -> Unit,
    onCopyPath: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { if (selectionMode) this.selected = selected }
            .combinedClickable(
                onLongClickLabel = "Select",
                onLongClick = onToggleSelect,
                onClick = { if (selectionMode) onToggleSelect() else onOpen() },
            ),
        shape = shape,
        colors = CustomColors.cardColorsOnSurfaceContainer,
        border = if (selectionMode && selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkspaceFileRowMinHeight)
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // One line like the settings rows: the path is already in the path bar, so only the size is extra.
            if (!entry.isDirectory) {
                Text(
                    text = entry.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            } else {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_device_files_select)) },
                            leadingIcon = { Icon(HugeIcons.CheckmarkSquare02, contentDescription = null) },
                            onClick = { menuExpanded = false; onToggleSelect() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_copy_path)) },
                            leadingIcon = { Icon(HugeIcons.Copy01, contentDescription = null) },
                            onClick = { menuExpanded = false; onCopyPath() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_device_files_move_rename)) },
                            leadingIcon = { Icon(HugeIcons.PencilEdit01, contentDescription = null) },
                            enabled = !transferring,
                            onClick = { menuExpanded = false; onRename() },
                        )
                        if (!entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_export)) },
                                leadingIcon = { Icon(HugeIcons.FileImport, contentDescription = null) },
                                enabled = !transferring,
                                onClick = { menuExpanded = false; onExport() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_device_files_export_zip)) },
                                leadingIcon = { Icon(HugeIcons.FolderZip, contentDescription = null) },
                                enabled = !transferring,
                                onClick = { menuExpanded = false; onExportZip() },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Delete01, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            enabled = !transferring,
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceNameDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val valid = runCatching { normalizeDeviceRelativePath(name) }
        .getOrNull()?.isNotBlank() == true && '/' !in name && '\\' !in name
    me.rerere.rikkihub.ui.components.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.workspace_device_files_name_label)) },
                singleLine = true,
                isError = name.isNotBlank() && !valid,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = valid) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 移动/重命名：目标是相对连接根的完整相对路径（改名=同目录新名）。 */
@Composable
private fun DeviceMoveDialog(
    entry: WorkspaceFileEntry,
    currentDir: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val source = deviceChildPath(currentDir, entry.name)
    var target by rememberSaveable { mutableStateOf(source) }
    val normalized = validDeviceMoveTarget(source, target)
    me.rerere.rikkihub.ui.components.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_device_files_move_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(stringResource(R.string.workspace_device_files_move_label)) },
                    singleLine = true,
                    isError = target.isNotBlank() && normalized == null,
                    supportingText = {
                        Text(stringResource(R.string.workspace_device_files_move_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { normalized?.let(onConfirm) }, enabled = normalized != null) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
