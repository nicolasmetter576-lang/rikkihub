package me.rerere.rikkihub.ui.pages.extensions.workspace

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.rikkihub.ui.pages.setting.subagent.SubagentLibraryUiState
import me.rerere.rikkihub.ui.pages.setting.subagent.subagentBindingBlock
import me.rerere.rikkihub.ui.pages.setting.subagent.subagentBindingBlockResource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.SubagentPresetConflictException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import me.rerere.rikkihub.data.workspace.RootfsInstallAlreadyRunningException
import me.rerere.rikkihub.data.workspace.RootfsInstallState
import me.rerere.rikkihub.data.workspace.WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH
import me.rerere.rikkihub.data.workspace.WorkspaceRootfsInstallService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import android.webkit.MimeTypeMap
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.utils.JsonInstant
import android.content.Context
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceTransferMode
import me.rerere.workspace.WorkspaceTransferResult
import me.rerere.rikkihub.data.repository.WorkspaceTransferWorkspaceNotFoundException
import kotlinx.coroutines.Job

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val subagentPresetRepository: SubagentPresetRepository,
    private val rootfsInstallService: WorkspaceRootfsInstallService,
    private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    // Rootfs 安装在服务级跑（App/Web 共用，离开页面不中断），这里只投影本工作区的状态。
    val installProgress = rootfsInstallService.states
        .map { states -> states[id]?.takeIf { it.installing }?.toProgress() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _installStartError = MutableStateFlow<String?>(null)
    val installError = combine(rootfsInstallService.states, _installStartError) { states, startError ->
        startError ?: states[id]?.takeIf { it.status == RootfsInstallState.STATUS_FAILED }?.error
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()

    private val _subagentPresetEditor = MutableStateFlow(WorkspaceSubagentPresetEditorState())
    val subagentPresetEditor = _subagentPresetEditor.asStateFlow()
    private val _subagentPresetBusy = MutableStateFlow(false)
    val subagentPresetBusy = _subagentPresetBusy.asStateFlow()
    val subagentPresetWorkspaces = repository.listFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )

    private val _subagentLibrary = MutableStateFlow(SubagentLibraryUiState())
    val subagentLibrary = _subagentLibrary.asStateFlow()
    private var observingSubagentLibrary = false

    fun reloadSubagentLibrary() {
        if (observingSubagentLibrary) return
        observingSubagentLibrary = true
        _subagentLibrary.update { it.copy(loading = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            try {
                subagentPresetRepository.libraryFlow().catch { error ->
                    if (error is CancellationException) throw error
                    _subagentLibrary.update { it.copy(loading = false, presets = null,
                        error = R.string.subagent_library_page_load_failed, errorDetail = error.message) }
                }.collect { library ->
                    _subagentLibrary.update { it.copy(loading = false, presets = library.presets, corrupt = library.corrupt,
                        error = it.error.takeUnless { error -> error == R.string.subagent_library_page_load_failed },
                        errorDetail = it.errorDetail.takeUnless { _ -> it.error == R.string.subagent_library_page_load_failed }) }
                }
            } finally { observingSubagentLibrary = false }
        }
    }

    fun setLibraryPresetEnabled(preset: WorkspaceSubagentPreset, enabled: Boolean) {
        val library = _subagentLibrary.value
        if (library.busy || library.loading || library.corrupt || library.presets == null) return
        val workspace = state.value.workspace ?: return
        val block = if (enabled) subagentBindingBlock(preset, workspace) else null
        if (block != null) {
            _subagentLibrary.update { it.copy(error = subagentBindingBlockResource(block), errorDetail = null) }
            return
        }
        _subagentLibrary.update { it.copy(busy = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            try {
                subagentPresetRepository.setWorkspaceEnabled(id, preset.id, enabled, preset.version)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SubagentPresetConflictException) {
                _subagentLibrary.update { it.copy(error = R.string.subagent_library_page_binding_conflict, errorDetail = error.message) }
            } catch (error: Throwable) {
                _subagentLibrary.update { it.copy(error = R.string.workspace_subagent_preset_save_failed, errorDetail = error.message) }
            } finally { _subagentLibrary.update { it.copy(busy = false) } }
        }
    }

    private fun refuseLinkedPreset(preset: WorkspaceSubagentPreset): Boolean {
        if (preset.libraryPresetId == null) return false
        _settingsError.value = context.getString(R.string.subagent_library_page_linked_notice)
        return true
    }

    private val folderImporter = WorkspaceFolderImporter(context.contentResolver)

    private val fileCreation = WorkspaceFileCreation()
    val fileCreationState = fileCreation.state
    fun dismissFileCreationError() = fileCreation.dismissError()

    fun openCreatedFile(event: WorkspaceCreatedFile, open: (WorkspaceNewFileRequest) -> Unit) =
        fileCreation.open(event, open)

    fun createFile(request: WorkspaceNewFileRequest): Boolean {
        if (request.target.workspaceId != id || request.target.mountId != null ||
            request.target.area != WorkspaceStorageArea.FILES || state.value.isTransferring
        ) return false
        if (!fileCreation.begin(request)) return false // Synchronous double-tap admission.
        viewModelScope.launch {
            try {
                if (request.isDirectory) repository.createDirectory(id, request.path)
                else repository.createFile(id, request.path)
                fileCreation.created(request)
                refresh()
            } catch (error: CancellationException) {
                // IO cancellation may follow a successful disk write; never retry by overwriting.
                fileCreation.failed(WorkspaceCreateError.INTERRUPTED)
                refresh()
                throw error
            } catch (error: Exception) {
                fileCreation.failed(
                    if (error is me.rerere.rikkihub.data.workspace.WorkspaceGateException) {
                        WorkspaceCreateError.UNAVAILABLE
                    } else workspaceCreateError(error),
                )
            } finally {
                fileCreation.finish()
            }
        }
        return true
    }

    fun dismissSettingsError() {
        _settingsError.value = null
    }

    init {
        // 装完（不论哪一端发起）刷新文件列表：rootfs 区此前是空的
        viewModelScope.launch {
            rootfsInstallService.states
                .map { it[id]?.installing == true }
                .distinctUntilChanged()
                .drop(1)
                .collect { installing -> if (!installing) refresh() }
        }
        // 单工作区 Flow 自动刷新: 配置写 (App/Web 双端) 落库后 state.workspace 自动更新,
        // 各 setter 不再需要写后手动 loadWorkspace()
        viewModelScope.launch {
            repository.getByIdFlow(id).collect { workspace ->
                _state.update { it.copy(workspace = workspace) }
                _subagentPresetEditor.update { editor ->
                    val draft = editor.draft
                    if (draft == null || draft.workspaceId != null) editor else editor.copy(
                        conflict = editor.conflict || workspaceSubagentPresetDraftConflict(
                            draft, workspace?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) },
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.listFlow().collect { workspaces ->
                _subagentPresetEditor.update { editor ->
                    val draft = editor.draft ?: return@update editor
                    val targetId = draft.workspaceId ?: return@update editor
                    editor.copy(
                        conflict = editor.conflict || workspaceSubagentPresetDraftConflict(
                            draft, workspaces.firstOrNull { it.id == targetId }
                                ?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) },
                        ),
                    )
                }
            }
        }
        reloadSubagentLibrary()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    private var initialDirectoryApplied = false

    /** Starts this browser at [path] in [area] once (the editor's reference side opens at the main file's folder). */
    fun openDirectoryOnce(area: WorkspaceStorageArea, path: String) {
        if (initialDirectoryApplied) return
        initialDirectoryApplied = true
        if (state.value.area == area && state.value.path == path) return
        _state.update { it.copy(area = area, path = path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { loadEntries() }
    }

    /** 列当前目录；会清掉 error，所以要在它之后再写结果提示 */
    private suspend fun loadEntries() {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            repository.listFiles(
                id = id,
                area = state.value.area,
                path = state.value.path,
            )
        }.onSuccess { entries ->
            _state.update { it.copy(entries = entries, loading = false) }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    entries = emptyList(),
                    loading = false,
                    error = error.message ?: "加载工作区文件失败",
                )
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    /** 移动/复制面板可选的目标工作区（全部工作区） */
    val transferWorkspaces = repository.listFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )

    /** 移动/复制面板浏览用: 列 [workspaceId] FILES 区 [path] 下的全部条目（文件夹供进入，名字供重名预告）。 */
    suspend fun listTransferEntries(workspaceId: String, path: String): List<WorkspaceFileEntry> =
        repository.listFiles(id = workspaceId, area = WorkspaceStorageArea.FILES, path = path)

    private val _extraction = MutableStateFlow<WorkspaceExtractionState?>(null)
    /** 解压对话框/进度；null = 没有进行中的解压 */
    val extraction = _extraction.asStateFlow()
    private var extractionJob: kotlinx.coroutines.Job? = null

    /** 打开解压对话框（先不带密码，需要时对话框再要密码）。 */
    fun requestExtract(entry: WorkspaceFileEntry) {
        if (_extraction.value?.running == true) return
        _extraction.value = WorkspaceExtractionState(entry)
    }

    fun extract(password: String?, nameEncoding: String?) {
        val current = _extraction.value ?: return
        if (current.running) return
        _extraction.value = current.copy(running = true, progress = null, errorCode = null, errorMessage = null)
        extractionJob = viewModelScope.launch {
            try {
                val result = repository.extractArchive(
                    id = id,
                    path = current.entry.path,
                    password = password?.takeIf { it.isNotEmpty() }?.toCharArray(),
                    nameEncoding = nameEncoding,
                ) { progress -> _extraction.update { it?.copy(progress = progress) } }
                _extraction.value = null
                _state.update {
                    it.copy(
                        transferNotice = if (result.skipped > 0) {
                            context.getString(R.string.workspace_extract_done_skipped, result.name, result.skipped)
                        } else {
                            context.getString(R.string.workspace_extract_done, result.name)
                        },
                    )
                }
                refresh()
            } catch (e: CancellationException) {
                _extraction.value = null
                throw e
            } catch (error: Throwable) {
                val code = (error as? me.rerere.workspace.WorkspaceFileOperationException)?.code
                _extraction.update { it?.copy(running = false, progress = null, errorCode = code, errorMessage = error.message) }
            }
        }
    }

    fun cancelExtract() {
        extractionJob?.cancel()
        _extraction.value = null
    }

    fun dismissExtract() {
        if (_extraction.value?.running == true) return
        _extraction.value = null
    }

    private var transferJob: Job? = null

    /**
     * 「移动到 / 复制到」：[entries] 是确认时的快照，放进 [targetWorkspaceId]（可为本工作区）FILES 区的
     * [destinationDir]，保留原名、重名自动改名。与导入/导出/批删互斥；逐项处理，单项失败不影响其余，
     * 结束后先刷新列表再如实报告（刷新会清掉 error，所以顺序不能反）；可取消，已完成的项保留。
     */
    fun transfer(
        entries: List<WorkspaceFileEntry>,
        targetWorkspaceId: String,
        destinationDir: String,
        mode: WorkspaceTransferMode,
    ) {
        val sameWorkspace = targetWorkspaceId == id
        val paths = entries
            .filter { workspaceTransferAllowed(it.path, sameWorkspace, destinationDir, mode) }
            .map { it.path }
        if (paths.isEmpty() || !beginTransfer()) return
        val destination = workspaceTransferDestinationLabel(
            workspaceName = transferWorkspaces.value.firstOrNull { it.id == targetWorkspaceId }?.name,
            sameWorkspace = sameWorkspace,
            dir = destinationDir,
        )
        var finished = 0
        _state.update {
            it.copy(
                canCancelTransfer = true,
                transferTotal = paths.size,
                transferCompleted = 0,
                transferFraction = 0f,
                transferProgressText = transferProgressText(mode, 0, paths.size, 0L, 0L),
            )
        }
        transferJob = viewModelScope.launch {
            var message: Pair<String?, String?> = null to null
            try {
                val result = repository.transferFiles(id, paths, targetWorkspaceId, destinationDir, mode) { progress ->
                    finished = progress.itemIndex
                    _state.update {
                        it.copy(
                            transferCompleted = progress.itemIndex,
                            transferFraction = workspaceTransferFraction(progress),
                            transferProgressText = transferProgressText(
                                mode, progress.itemIndex, progress.itemCount, progress.bytesDone, progress.bytesTotal,
                            ),
                        )
                    }
                }
                message = transferResultMessage(result, mode, destination)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    loadEntries()
                    _state.update {
                        it.copy(
                            error = if (finished == 0) {
                                context.getString(R.string.workspace_transfer_cancelled_none)
                            } else {
                                context.getString(R.string.workspace_transfer_cancelled, finished)
                            },
                        )
                    }
                }
                throw error
            } catch (error: Throwable) {
                message = null to transferRequestErrorText(error)
            } finally {
                transferJob = null
                _state.update {
                    it.copy(
                        isTransferring = false,
                        canCancelTransfer = false,
                        transferProgressText = null,
                        transferFraction = null,
                        transferCompleted = 0,
                        transferTotal = 0,
                    )
                }
            }
            loadEntries()
            _state.update { it.copy(transferNotice = message.first, error = message.second) }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
    }

    /** 完成提示里的去向：本工作区 "/dir"，其它工作区带名字 */
    private fun workspaceTransferDestinationLabel(workspaceName: String?, sameWorkspace: Boolean, dir: String): String {
        val path = "/" + dir.trim('/')
        return if (sameWorkspace || workspaceName == null) path
        else context.getString(R.string.workspace_transfer_destination_other, workspaceName, path)
    }

    private fun transferProgressText(mode: WorkspaceTransferMode, index: Int, count: Int, done: Long, total: Long): String {
        val position = (index + 1).coerceAtMost(count)
        return when (mode) {
            WorkspaceTransferMode.MOVE -> context.getString(R.string.workspace_transfer_progress_move, position, count)
            WorkspaceTransferMode.COPY -> context.getString(
                R.string.workspace_transfer_progress_copy, position, count,
                if (total > 0) (done * 100 / total).toInt() else 0,
            )
        }
    }

    /** (transferNotice, error)：全部成功给提示，有失败给错误卡并逐条列原因 */
    private fun transferResultMessage(
        result: WorkspaceTransferResult,
        mode: WorkspaceTransferMode,
        destination: String,
    ): Pair<String?, String?> {
        val summary = workspaceTransferSummary(result)
        val details = buildString {
            if (summary.renamed > 0) append(context.getString(R.string.workspace_transfer_renamed, summary.renamed))
            if (summary.skippedLinks > 0) append(context.getString(R.string.workspace_transfer_skipped_links, summary.skippedLinks))
        }
        if (summary.failures.isEmpty()) {
            val done = when (mode) {
                WorkspaceTransferMode.MOVE -> context.getString(R.string.workspace_transfer_move_done, summary.succeeded, destination)
                WorkspaceTransferMode.COPY -> context.getString(R.string.workspace_transfer_copy_done, summary.succeeded, destination)
            }
            return (done + details) to null
        }
        val head = when (mode) {
            WorkspaceTransferMode.MOVE -> context.getString(R.string.workspace_move_selection_partial, summary.succeeded, summary.failures.size)
            WorkspaceTransferMode.COPY -> context.getString(R.string.workspace_transfer_copy_partial, summary.succeeded, summary.failures.size)
        }
        val lines = summary.failures.joinToString("\n") { item ->
            "${item.source.substringAfterLast('/')}: ${transferFailureReason(item.errorCode, item.error)}"
        }
        return null to (head + details + "\n" + lines)
    }

    private fun transferFailureReason(code: String?, raw: String?): String = when (code) {
        WorkspaceFileOperationException.FILE_NOT_FOUND -> context.getString(R.string.workspace_transfer_error_not_found)
        WorkspaceFileOperationException.TRANSFER_SAME_LOCATION -> context.getString(R.string.workspace_transfer_error_same_location)
        WorkspaceFileOperationException.TRANSFER_INTO_ITSELF -> context.getString(R.string.workspace_transfer_error_into_itself)
        WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED -> context.getString(R.string.workspace_transfer_error_symlink)
        WorkspaceFileOperationException.NOT_ENOUGH_SPACE -> context.getString(R.string.workspace_transfer_error_no_space)
        WorkspaceFileOperationException.FILE_ALREADY_EXISTS -> context.getString(R.string.workspace_transfer_error_name_taken)
        WorkspaceFileOperationException.PARENT_NOT_FOUND,
        WorkspaceFileOperationException.PARENT_NOT_DIRECTORY -> context.getString(R.string.workspace_transfer_error_target_missing)
        else -> raw?.takeIf { it.isNotBlank() } ?: context.getString(R.string.workspace_transfer_error_failed)
    }

    /** 整次请求失败（目标文件夹或工作区已不存在等），不是逐项失败 */
    private fun transferRequestErrorText(error: Throwable): String = when (error) {
        is WorkspaceTransferWorkspaceNotFoundException -> context.getString(R.string.workspace_transfer_error_workspace_missing)
        is WorkspaceFileOperationException -> transferFailureReason(error.code, error.message)
        else -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.workspace_transfer_error_failed)
    }

    /**
     * 多选批量删除. [paths] 与 [area] 必须是确认对话框发起时的快照, 这里不读
     * state 的当前目录/选择, 因此确认期间用户切目录或列表刷新不会改目标.
     * 走 beginTransfer 与导入/导出互斥; 单协程内串行删除并等待全部完成才统一刷新.
     * 已删除的条目不可回滚; 部分失败时 error 里如实报告成功/失败数量.
     */
    fun deleteSelection(paths: List<String>, area: WorkspaceStorageArea) {
        if (paths.isEmpty() || !beginTransfer()) return
        viewModelScope.launch {
            try {
                val result = repository.deleteFiles(id = id, area = area, paths = paths)
                when {
                    result.failures.isEmpty() -> _state.update {
                        it.copy(transferNotice = "Deleted ${result.deletedCount} item(s)")
                    }

                    result.deletedCount > 0 -> _state.update {
                        it.copy(
                            error = "Deleted ${result.deletedCount} item(s); " +
                                "${result.failures.size} failed: " +
                                result.failures.joinToString("; ") { f -> "${f.path} (${f.error})" },
                        )
                    }

                    else -> _state.update {
                        it.copy(
                            error = "Delete failed: " +
                                result.failures.joinToString("; ") { f -> "${f.path} (${f.error})" },
                        )
                    }
                }
            } catch (error: CancellationException) {
                // 已删除的条目无法回滚, 但仍要如实告知这是一次被取消的批量删除
                _state.update {
                    it.copy(
                        error = "Delete cancelled; items already removed cannot be restored",
                    )
                }
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "Delete failed") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
            // 删完/失败后统一按当前目录刷新一次 (取消路径协程已死, 由 finally 恢复忙态)
            refresh()
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 整文件夹导入. [uri] 来自 ACTION_OPEN_DOCUMENT_TREE;
     * [area]/[destinationPath] 必须是发起时的快照, 由调用方传入, 这里不读 state,
     * 因此传输期间用户在 picker 里切目录不会改变落点. 已在传输中时忽略重入.
     */
    fun importFolder(uri: Uri, area: WorkspaceStorageArea, destinationPath: String) {
        if (!beginTransfer()) return
        viewModelScope.launch {
            try {
                val result = repository.importFolder(
                    id = id,
                    area = area,
                    destinationPath = destinationPath,
                    treeRoot = withContext(Dispatchers.IO) { folderImporter.openTree(uri) },
                )
                _state.update {
                    it.copy(
                        transferNotice = "已导入文件夹「${result.rootName}」(${result.fileCount} 个文件)",
                    )
                }
                // refresh 按当下 state.path 列目录, 不会把用户导航拽回旧目录
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "导入文件夹失败") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
        }
    }

    /**
     * 批量导出: [paths] 打包成单个 ZIP 写入 SAF 文档 [destinationUri].
     * ContentResolver 流由本函数自己打开/关闭; 已开始写入后失败/取消时尽力删除
     * 刚创建的半成品文档, 不会动用户的其他文件.
     */
    fun exportArchive(paths: List<String>, area: WorkspaceStorageArea, destinationUri: Uri) {
        if (paths.isEmpty() || !beginTransfer()) return
        viewModelScope.launch {
            var openedForWrite = false
            try {
                val stream = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)
                } ?: error("Cannot open output stream for export")
                openedForWrite = true
                // 在创建点立即用 use 包住下游调用: 若协程在 withContext 调度前的
                // 取消窗口里被取消, repository 内部的 exportZip 可能根本没执行,
                // 这层的 use 仍保证流被关掉. Android 的 ParcelFileDescriptor
                // AutoCloseOutputStream.close() 幂等, 与 exportZip 的 use 双重关闭安全.
                val result = stream.use {
                    repository.exportArchive(
                        id = id,
                        area = area,
                        paths = paths,
                        outputStream = it,
                    )
                }
                _state.update {
                    it.copy(transferNotice = "已导出 ${result.fileCount} 个文件")
                }
                refresh()
            } catch (error: CancellationException) {
                if (openedForWrite) deletePartialDocument(destinationUri)
                throw error
            } catch (error: Throwable) {
                if (openedForWrite) deletePartialDocument(destinationUri)
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            } finally {
                _state.update { it.copy(isTransferring = false) }
            }
        }
    }

    /**
     * 普通文件批量导出到用户选择的 SAF 目录.
     * [files] 与 [area] 是 picker 打开前的快照; [treeUri] 来自
     * ACTION_OPEN_DOCUMENT_TREE. 只收普通文件 —— UI 在含目录时禁用入口,
     * 引擎对漏网目录条目也记显式失败. 目标同名自动选非冲突名, 不覆盖用户
     * 现存文件; 取消/失败只清本轮已确认新建的半成品, 成功文件保留.
     */
    fun exportFilesToDirectory(
        files: List<WorkspaceDirectoryExportFile>,
        area: WorkspaceStorageArea,
        treeUri: Uri,
    ) {
        val snapshotFiles = files.toList()
        if (snapshotFiles.isEmpty() || !beginTransfer()) return
        viewModelScope.launch {
            try {
                _state.update { it.copy(transferTotal = snapshotFiles.size, transferCompleted = 0) }
                // All provider queries/create/open/delete are blocking IO, not just construction.
                val result = withContext(Dispatchers.IO) {
                    exportWorkspaceFilesToDirectory(
                        files = snapshotFiles,
                        target = SafExportDirectory(context.contentResolver, treeUri),
                        mimeType = { file ->
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                            ) ?: "application/octet-stream"
                        },
                        onProgress = { completed, _ ->
                            _state.update { it.copy(transferCompleted = completed) }
                        },
                    ) { file, out ->
                        repository.exportFile(
                            id = id,
                            area = area,
                            path = file.path,
                            outputStream = out,
                        )
                    }
                }
                when {
                    result.failures.isEmpty() -> _state.update {
                        it.copy(
                            transferNotice = context.getString(
                                R.string.workspace_export_directory_done,
                                result.exportedCount,
                            ),
                        )
                    }

                    result.exportedCount > 0 -> _state.update {
                        it.copy(
                            error = context.getString(
                                R.string.workspace_export_directory_partial,
                                result.exportedCount,
                                result.totalCount,
                            ) + "\n" + result.failures.joinToString("\n") { failure ->
                                workspaceExportFailureText(failure)
                            },
                        )
                    }

                    else -> _state.update {
                        it.copy(
                            error = context.getString(
                                R.string.workspace_export_directory_failed,
                            ) + "\n" + result.failures.joinToString("\n") { failure ->
                                workspaceExportFailureText(failure)
                            },
                        )
                    }
                }
            } catch (error: CancellationException) {
                // 取消不粉饰成成功: 半成品已由引擎 finally 清理, 如实提示
                _state.update {
                    it.copy(
                        error = context.getString(
                            R.string.workspace_export_directory_cancelled,
                        ),
                    )
                }
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        error = context.getString(R.string.workspace_export_directory_failed) +
                            (error.message?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                    )
                }
            } finally {
                _state.update {
                    it.copy(
                        isTransferring = false,
                        transferCompleted = 0,
                        transferTotal = 0,
                    )
                }
            }
        }
    }

    private fun workspaceExportFailureText(failure: WorkspaceExportFileFailure): String {
        val reason = when (failure.reason) {
            WorkspaceExportFailure.DIRECTORY_REJECTED ->
                context.getString(R.string.workspace_export_directory_error_directory)

            WorkspaceExportFailure.UNSAFE_NAME ->
                context.getString(R.string.workspace_export_directory_error_name)

            WorkspaceExportFailure.NAME_COLLISION_EXHAUSTED ->
                context.getString(R.string.workspace_export_directory_error_collision)

            WorkspaceExportFailure.TARGET_ID_REUSED ->
                context.getString(R.string.workspace_export_directory_error_reused)

            WorkspaceExportFailure.WRITE_FAILED ->
                context.getString(R.string.workspace_export_directory_error_write)
        }
        return buildString {
            append(failure.path)
            append(": ")
            append(reason)
            failure.detail?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
        }
    }

    /** 原子置位 isTransferring; 已在传输中返回 false, 防止两个传输并发 */
    private fun beginTransfer(): Boolean {
        val previous = _state.getAndUpdate { s ->
            if (s.isTransferring) {
                s
            } else {
                s.copy(isTransferring = true, transferNotice = null, error = null)
            }
        }
        return !previous.isTransferring
    }

    /** 取消语境下也要能跑, 所以走 NonCancellable */
    private suspend fun deletePartialDocument(uri: Uri) {
        withContext(NonCancellable + Dispatchers.IO) {
            deleteDocumentQuietly(context.contentResolver, uri)
        }
    }

    fun dismissTransferNotice() {
        _state.update { it.copy(transferNotice = null) }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 统一配置写入口: 所有 patch 走 Repo.applyConfigPatch (单事务 + gate 发布 + 运行
     * 期取消由 repo/gate 协调), UI 不自行 close bash/终端会话。
     * transform 内异常 (如 tool_enablement 损坏时 merge 失败) 写到 settingsError。
     */
    private fun patchWorkspace(transform: suspend (WorkspaceEntity) -> WorkspaceEntity) {
        viewModelScope.launch {
            try {
                repository.applyConfigPatch(id, transform)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message.orEmpty()
            }
        }
    }

    fun setExposedMount(target: String, exposed: Boolean) {
        patchWorkspace { workspace ->
            when (target) {
                "/upload" -> workspace.copy(exposeUpload = exposed)
                "/skills" -> workspace.copy(exposeSkills = exposed)
                "/tool_outputs" -> workspace.copy(exposeToolOutputs = exposed)
                else -> workspace
            }
        }
    }

    fun hostMountEntries(target: String): List<WorkspaceFileEntry> {
        val folder = when (target) {
            "/upload" -> FileFolders.UPLOAD
            "/skills" -> FileFolders.SKILLS
            "/tool_outputs" -> FileFolders.TOOL_OUTPUTS
            else -> return emptyList()
        }
        val dir = File(context.applicationContext.filesDir, folder)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map { file ->
                WorkspaceFileEntry(
                    path = "${target.trimEnd('/')}/${file.name}",
                    name = file.name,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    updatedAt = file.lastModified(),
                )
            }
    }

    fun editSubagentPreset(preset: WorkspaceSubagentPreset? = null, create: Boolean = false) {
        if (_subagentPresetBusy.value) return
        if (preset != null && !create && refuseLinkedPreset(preset)) return
        val saved = state.value.workspace?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) }
        if (saved == null) {
            _settingsError.value = context.getString(R.string.workspace_subagent_preset_corrupt)
            return
        }
        if ((preset == null || create) && saved.size >= WorkspaceSubagentPresets.MAX_PRESETS) {
            _settingsError.value = context.getString(R.string.workspace_subagent_preset_limit)
            return
        }
        val selected = preset ?: WorkspaceSubagentPreset(
            key = if (saved.none { it.key == "preset" }) "preset" else
                workspaceSubagentPresetCopyKey("preset", saved.map { it.key }.toSet()),
        )
        _subagentPresetEditor.value = WorkspaceSubagentPresetEditorState(
            draft = WorkspaceSubagentPresetDraft(
                preset = selected,
                expectedVersion = selected.version.takeIf { preset != null && !create },
            ),
        )
    }

    fun cloneSubagentPreset(preset: WorkspaceSubagentPreset) {
        val saved = state.value.workspace?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) }
            ?: return
        editSubagentPreset(workspaceSubagentPresetClone(preset, saved.map { it.key }.toSet()), create = true)
    }

    fun createSubagentPresetFromTemplate(preset: WorkspaceSubagentPreset) {
        val saved = state.value.workspace?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) }
            ?: return
        val key = if (saved.none { it.key == preset.key }) preset.key else
            workspaceSubagentPresetCopyKey(preset.key, saved.map { it.key }.toSet())
        editSubagentPreset(preset.copy(key = key), create = true)
    }

    fun updateSubagentPresetDraft(transform: (WorkspaceSubagentPresetDraft) -> WorkspaceSubagentPresetDraft) {
        if (_subagentPresetBusy.value) return
        _subagentPresetEditor.update { editor ->
            val draft = editor.draft ?: return@update editor
            val next = transform(draft)
            editor.copy(draft = next.copy(
                // Existing keys and optimistic versions are never editable.
                expectedVersion = draft.expectedVersion,
                workspaceId = draft.workspaceId,
                preset = next.preset.copy(
                    id = draft.preset.id,
                    key = if (draft.expectedVersion != null) draft.preset.key else next.preset.key,
                    version = draft.preset.version,
                    executionRevision = draft.preset.executionRevision,
                ),
            ))
        }
    }

    fun dismissSubagentPresetEditor() {
        if (!_subagentPresetBusy.value) _subagentPresetEditor.value = WorkspaceSubagentPresetEditorState()
    }

    fun saveSubagentPreset() {
        val editor = _subagentPresetEditor.value
        val draft = editor.draft ?: return
        if (refuseLinkedPreset(draft.preset)) return
        if (editor.conflict) {
            _settingsError.value = context.getString(R.string.workspace_subagent_preset_conflict)
            return
        }
        val error = draft.validationError()
        if (error != null) {
            _settingsError.value = context.getString(workspaceSubagentPresetDraftErrorResource(error))
            return
        }
        mutateSubagentPresets {
            val preset = draft.toPreset()
            val targetId = draft.workspaceId ?: id
            if (draft.expectedVersion == null) repository.createSubagentPreset(targetId, preset)
            else repository.updateSubagentPreset(targetId, preset, draft.expectedVersion)
            _subagentPresetEditor.value = WorkspaceSubagentPresetEditorState()
        }
    }

    fun deleteSubagentPreset(preset: WorkspaceSubagentPreset) {
        if (refuseLinkedPreset(preset)) return
        mutateSubagentPresets { repository.deleteSubagentPreset(id, preset.id, preset.version) }
    }

    fun reorderSubagentPresets(presets: List<WorkspaceSubagentPreset>, presetId: String, offset: Int) {
        val selected = presets.firstOrNull { it.id == presetId } ?: return
        if (refuseLinkedPreset(selected)) return
        // Keep shared snapshots in their existing slots while moving local roles relative to each other.
        val localIndices = presets.indices.filter { presets[it].libraryPresetId == null }
        val localIndex = localIndices.indexOfFirst { presets[it].id == presetId }
        val targetLocalIndex = localIndex + offset
        if (localIndex < 0 || targetLocalIndex !in localIndices.indices) return
        val index = localIndices[localIndex]
        val target = localIndices[targetLocalIndex]
        val ordered = presets.toMutableList().apply {
            this[index] = presets[target]
            this[target] = presets[index]
        }
        mutateSubagentPresets {
            repository.reorderSubagentPresets(id, ordered.map { it.id }, presets.associate { it.id to it.version })
        }
    }

    fun resetSubagentPresets() = mutateSubagentPresets { repository.resetSubagentPresets(id) }

    /** Copy is another explicit-save draft, not a live link or an immediate target write. */
    fun copySubagentPreset(preset: WorkspaceSubagentPreset, targetWorkspaceId: String) {
        if (_subagentPresetBusy.value) return
        val target = subagentPresetWorkspaces.value.firstOrNull { it.id == targetWorkspaceId }
        val saved = target?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) }
        if (saved == null) {
            _settingsError.value = context.getString(R.string.workspace_subagent_preset_corrupt)
            return
        }
        if (saved.size >= WorkspaceSubagentPresets.MAX_PRESETS) {
            _settingsError.value = context.getString(R.string.workspace_subagent_preset_limit)
            return
        }
        _subagentPresetEditor.value = WorkspaceSubagentPresetEditorState(
            draft = WorkspaceSubagentPresetDraft(
                preset = workspaceSubagentPresetClone(preset, saved.map { it.key }.toSet()),
                workspaceId = targetWorkspaceId,
            ),
        )
    }

    private fun mutateSubagentPresets(block: suspend () -> Unit) {
        if (!_subagentPresetBusy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SubagentPresetConflictException) {
                _subagentPresetEditor.update { it.copy(conflict = it.draft != null) }
                _settingsError.value = context.getString(R.string.workspace_subagent_preset_conflict)
            } catch (error: Throwable) {
                _settingsError.value = context.getString(R.string.workspace_subagent_preset_save_failed) +
                    (error.message?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
            } finally {
                _subagentPresetBusy.value = false
            }
        }
    }

    fun setSubagentModel(modelId: String?) {
        val normalized = modelId?.trim()?.takeIf { it.isNotEmpty() }
        patchWorkspace { it.copy(subagentModelId = normalized) }
    }

    fun setSubagentAllowContext(allowContext: Boolean) {
        patchWorkspace { it.copy(subagentAllowContext = allowContext) }
    }

    fun setShellCompatibilityMode(enabled: Boolean) {
        patchWorkspace { it.copy(shellCompatibilityMode = enabled) }
    }

    fun setFirstMessageFileSnapshot(enabled: Boolean) {
        patchWorkspace { it.copy(firstMessageFileSnapshot = enabled) }
    }

    fun setFirstMessageFileSnapshotDepth(depth: Int) {
        val clamped = depth.coerceIn(1, WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH)
        patchWorkspace { it.copy(firstMessageFileSnapshotDepth = clamped) }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        patchWorkspace { workspace ->
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(
                    workspace.toolApprovalOverrides() + (toolName to needsApproval)
                )
            )
        }
    }

    /**
     * 逐工具启用开关 (独立於 tool_approvals): 事务内读最新 JSON 增量 merge,
     * [toolId] 必须是 [WorkspaceToolEnablement.configurableIds] 之一。
     * 存储配置损坏时 mergedJson 抛错 -> settingsError, 不会把坏配置写回。
     */
    fun setToolEnablement(toolId: String, enabled: Boolean) {
        patchWorkspace { workspace ->
            workspace.copy(
                toolEnablement = WorkspaceToolEnablement.mergedJson(
                    workspace.toolEnablement,
                    mapOf(toolId to enabled),
                )
            )
        }
    }

    /** 损坏配置修复: 显式把 tool_enablement 重置为 "{}" (全部启用), 需 UI 确认 */
    fun resetToolEnablement() {
        patchWorkspace { it.copy(toolEnablement = WorkspaceToolEnablement.validatedJson(emptyMap())) }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installStartError.value = null
            rootfsInstallService.dismiss(id)
            try {
                rootfsInstallService.start(id, url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RootfsInstallAlreadyRunningException) {
                // 另一端（Web）已经在装，进度会照常显示
            } catch (error: Throwable) {
                _installStartError.value = error.message ?: "Rootfs 安装失败"
            }
        }
    }

    fun cancelRootfsInstall() {
        rootfsInstallService.cancel(id)
    }

    fun dismissInstallError() {
        _installStartError.value = null
        rootfsInstallService.dismiss(id)
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    /** 文件夹导入 / 批量导出进行中; UI 可据此禁用入口并显示进度 */
    val isTransferring: Boolean = false,
    /** 传输成功的一次性提示 (可选消费, dismissTransferNotice 清除); 失败仍走 error */
    val transferNotice: String? = null,
    /** 批量导出到目录的逐文件进度; total=0 表示无明细进度 (ZIP/导入) */
    val transferCompleted: Int = 0,
    val transferTotal: Int = 0,
    /** 移动/复制：含当前项字节进度的总进度（0..1）；null = 按 transferCompleted/transferTotal */
    val transferFraction: Float? = null,
    /** 移动/复制的进度文字；null = 沿用「导出到目录」的文字 */
    val transferProgressText: String? = null,
    /** 当前传输可取消（移动/复制） */
    val canCancelTransfer: Boolean = false,
    val error: String? = null,
)

data class WorkspaceExtractionState(
    val entry: WorkspaceFileEntry,
    val running: Boolean = false,
    val progress: me.rerere.workspace.WorkspaceExtractProgress? = null,
    /** [me.rerere.workspace.WorkspaceArchiveException] 的 code（或其他文件错误码）；null = 无错误 */
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
