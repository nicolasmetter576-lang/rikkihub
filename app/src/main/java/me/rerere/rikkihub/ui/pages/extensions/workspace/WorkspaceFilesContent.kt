package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.rikkihub.ui.theme.amoledOutlineBorder
import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.CheckmarkSquare02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderImport
import me.rerere.hugeicons.stroke.FolderTransfer
import me.rerere.hugeicons.stroke.FolderZip
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Copy02
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.utils.writeClipboardText
import me.rerere.workspace.WorkspaceArchiveExtractor
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.fileSizeToString
import me.rerere.rikkihub.utils.plus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTransferMode
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Shared workspace file browser used by the dedicated workspace detail page
 * and the chat-page right-edge swipe overlay.
 */
@Composable
fun WorkspaceFilesBrowser(
    workspaceId: String,
    modifier: Modifier = Modifier,
    interceptBackToGoUp: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val vm: WorkspaceDetailVM = koinViewModel(
        key = "workspace-files-$workspaceId",
        parameters = { parametersOf(workspaceId) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    WorkspaceFilesBrowser(
        workspaceId = workspaceId,
        vm = vm,
        state = state,
        modifier = modifier,
        interceptBackToGoUp = interceptBackToGoUp,
        contentPadding = contentPadding,
    )
}

@Composable
fun WorkspaceFilesBrowser(
    workspaceId: String,
    vm: WorkspaceDetailVM,
    state: WorkspaceDetailState,
    modifier: Modifier = Modifier,
    interceptBackToGoUp: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    /** Opens text files (also a file just created here) somewhere else than a new editor page: the editor's reference side. */
    onOpenTextFile: ((WorkspaceStorageArea, String) -> Unit)? = null,
    /** False while the browser is off screen (the editor's reference side): its back handlers stand down. */
    backEnabled: Boolean = true,
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var pendingTransfer by remember { mutableStateOf<PendingTransferSheet?>(null) }
    val transferWorkspaces by vm.transferWorkspaces.collectAsStateWithLifecycle()
    val extraction by vm.extraction.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val creation by vm.fileCreationState.collectAsStateWithLifecycle()
    var newFileTarget by rememberSaveable(workspaceId) {
        mutableStateOf<WorkspaceNewFileTarget?>(null)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, navController, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.fileCreationState.collect { completion ->
                completion.pendingOpen?.let { event ->
                    vm.openCreatedFile(event) { request ->
                        // Bypass extension detection for this newly created empty text file.
                        if (onOpenTextFile != null) onOpenTextFile(request.target.area, request.path)
                        else navController.navigate(Screen.WorkspaceFileEditor(
                            request.target.workspaceId, request.target.area.name, request.path,
                        ))
                    }
                }
            }
        }
    }

    // Multi-select state. Saveable so a SAF picker / config change does not
    // drop an in-progress selection; keyed by workspace so switching
    // workspaces starts with a clean selection.
    var selectionMode by rememberSaveable(workspaceId) { mutableStateOf(false) }
    var selectedPaths by rememberSaveable(
        workspaceId,
        stateSaver = Saver<Set<String>, ArrayList<String>>(
            save = { paths -> ArrayList(paths) },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf<Set<String>>(emptySet()) }

    // Clear the selection whenever the location changes (navigate into a
    // directory, go up, switch storage area). The first run after composition
    // is skipped so a selection restored via rememberSaveable (e.g. after the
    // activity was recreated for a SAF picker) is not wiped immediately.
    var lastLocation by remember {
        mutableStateOf<Pair<WorkspaceStorageArea, String>?>(null)
    }
    LaunchedEffect(state.area, state.path) {
        val location = state.area to state.path
        if (lastLocation != null && lastLocation != location) {
            selectedPaths = emptySet()
            selectionMode = false
        }
        lastLocation = location
    }
    // Entries that vanished after a refresh must not stay selected.
    LaunchedEffect(state.entries) {
        val pruned = pruneWorkspaceSelection(selectedPaths, state.entries)
        if (pruned != selectedPaths) selectedPaths = pruned
    }

    fun clearSelection() {
        selectedPaths = emptySet()
        selectionMode = false
    }

    fun toggleSelection(entry: WorkspaceFileEntry) {
        selectionMode = true
        selectedPaths = toggleWorkspacePathSelection(selectedPaths, entry.path)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }

    // Area + destination path are snapshotted before launching the tree picker
    // so a user navigating elsewhere while picking cannot redirect the import.
    var pendingFolderImport by rememberSaveable(workspaceId) {
        mutableStateOf<PendingFolderImport?>(null)
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val pending = pendingFolderImport.also { pendingFolderImport = null }
            ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult // 取消无副作用
        vm.importFolder(
            uri = uri,
            area = pending.area,
            destinationPath = pending.destinationPath,
        )
    }

    // Single-file export keeps a saveable path snapshot for the same reason.
    var pendingFileExportPath by rememberSaveable(workspaceId) {
        mutableStateOf<String?>(null)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val path = pendingFileExportPath.also { pendingFileExportPath = null }
            ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(
            WorkspaceFileEntry(
                path = path,
                name = path.substringAfterLast('/'),
                isDirectory = false,
                sizeBytes = 0L,
                updatedAt = 0L,
            ),
            outputStream,
        )
    }

    // Batch ZIP export: paths + area are snapshotted at launch time so the
    // callback is unaffected by selection changes while the picker is open.
    var pendingArchiveExport by rememberSaveable(workspaceId) {
        mutableStateOf<PendingArchiveExport?>(null)
    }
    val archiveExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val pending = pendingArchiveExport.also { pendingArchiveExport = null }
            ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportArchive(
            paths = pending.paths,
            area = pending.area,
            destinationUri = uri,
        )
        // The archive request was accepted: leave selection mode.
        clearSelection()
    }

    // Batch export of plain files into a user-picked SAF directory.
    // Directories are not eligible here (they go through the ZIP path); the UI
    // disables this action while a directory is selected and the engine would
    // still flag any leaked directory entry as an explicit failure.
    var pendingDirectoryExport by rememberSaveable(workspaceId) {
        mutableStateOf<PendingDirectoryExport?>(null)
    }
    val directoryExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val pending = pendingDirectoryExport.also { pendingDirectoryExport = null }
            ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult // 取消无副作用
        vm.exportFilesToDirectory(
            files = pending.files,
            area = pending.area,
            treeUri = uri,
        )
        clearSelection()
    }

    // Multi-delete confirmation snapshot: taken when the delete button is
    // tapped. Saveable so a config change while the dialog is open keeps the
    // original area+paths (never re-reads live selection at confirm time).
    var pendingBatchDelete by rememberSaveable(workspaceId) {
        mutableStateOf<PendingBatchDelete?>(null)
    }

    fun requestBatchDelete() {
        if (state.isTransferring || selectedPaths.isEmpty()) return
        val selected = selectedPaths
        pendingBatchDelete = PendingBatchDelete(
            area = state.area,
            paths = ArrayList(selected),
            includesDirectories = state.entries.any { it.path in selected && it.isDirectory },
        )
    }

    fun launchDirectoryExport() {
        if (state.isTransferring || selectedPaths.isEmpty()) return
        // 普通文件出口: 含目录的选中集不允许发起 (按钮已禁用, 这里双保险)
        if (state.entries.any { it.path in selectedPaths && it.isDirectory }) return
        val files = state.entries.filter { it.path in selectedPaths && !it.isDirectory }
            .map {
                WorkspaceDirectoryExportFile(
                    path = it.path,
                    name = it.name,
                    isDirectory = false,
                )
            }
        if (files.isEmpty()) return
        pendingDirectoryExport = PendingDirectoryExport(
            area = state.area,
            files = ArrayList(files),
        )
        directoryExportLauncher.launch(null)
    }

    fun launchArchiveExport(paths: Collection<String>) {
        if (state.isTransferring || paths.isEmpty()) return
        pendingArchiveExport = PendingArchiveExport(
            area = state.area,
            paths = ArrayList(paths),
        )
        archiveExportLauncher.launch(
            defaultWorkspaceArchiveName(
                workspaceName = state.workspace?.name,
                directoryPath = state.path,
                selectedPaths = paths.toList(),
            )
        )
    }

    if (interceptBackToGoUp) {
        // Composed before the selection BackHandler below: selection clearing
        // must win over navigation, so it is registered last.
        BackHandler(enabled = state.path.isNotBlank()) {
            vm.goUp()
        }
    }
    BackHandler(enabled = backEnabled && (selectionMode || selectedPaths.isNotEmpty())) {
        clearSelection()
    }

    WorkspaceFilesPage(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        selectionMode = selectionMode,
        selectedPaths = selectedPaths,
        transferring = state.isTransferring,
        creation = creation,
        onDismissCreationError = vm::dismissFileCreationError,
        onCreateFile = if (state.area == WorkspaceStorageArea.FILES) {
            {
                if (!creation.busy && creation.pendingOpen == null && !state.isTransferring &&
                    !state.loading && state.error == null && state.workspace != null
                ) {
                    vm.dismissFileCreationError()
                    newFileTarget = WorkspaceNewFileTarget(workspaceId, state.path, state.area)
                }
            }
        } else null,
        onSelectArea = vm::selectArea,
        onGoUp = vm::goUp,
        onImport = { filePicker.launch(arrayOf("*/*")) },
        onImportFolder = {
            if (!state.isTransferring) {
                pendingFolderImport = PendingFolderImport(
                    area = state.area,
                    destinationPath = state.path,
                )
                folderPicker.launch(null)
            }
        },
        onToggleSelect = { toggleSelection(it) },
        onSelectAll = {
            val all = state.entries.mapTo(LinkedHashSet()) { it.path }
            selectedPaths = if (selectedPaths.size == all.size && selectedPaths.containsAll(all)) {
                emptySet()
            } else {
                all
            }
        },
        selectionHasDirectories = state.entries.any {
            it.path in selectedPaths && it.isDirectory
        },
        onExportSelection = { launchArchiveExport(selectedPaths) },
        onExportFilesToDirectory = { launchDirectoryExport() },
        onDeleteSelection = { requestBatchDelete() },
        onMoveSelection = if (state.area == WorkspaceStorageArea.FILES) {
            {
                val selected = state.entries.filter { it.path in selectedPaths }
                if (selected.isNotEmpty() && !state.isTransferring) {
                    pendingTransfer = PendingTransferSheet(selected, WorkspaceTransferMode.MOVE, fromSelection = true)
                }
            }
        } else {
            null
        },
        onCopySelection = if (state.area == WorkspaceStorageArea.FILES) {
            {
                val selected = state.entries.filter { it.path in selectedPaths }
                if (selected.isNotEmpty() && !state.isTransferring) {
                    pendingTransfer = PendingTransferSheet(selected, WorkspaceTransferMode.COPY, fromSelection = true)
                }
            }
        } else {
            null
        },
        onCancelTransfer = vm::cancelTransfer,
        onCancelSelection = { clearSelection() },
        onDismissTransferNotice = vm::dismissTransferNotice,
        onOpen = { entry ->
            when {
                entry.isDirectory -> vm.open(entry)

                else -> when (entry.detectFileType()) {
                    WorkspaceFileType.TEXT -> if (onOpenTextFile != null) onOpenTextFile(state.area, entry.path)
                    else navController.navigate(Screen.WorkspaceFileEditor(workspaceId, state.area.name, entry.path))

                    WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                        // Pass an absolute path (not a content:// URI): Coil can load it
                        // directly, and ImagePreviewDialog's save button only accepts "/" paths.
                        previewImageUri = file.absolutePath
                    }

                    WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
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
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                }
            }
        },
        onDelete = { deleteTarget = it },
        onMove = if (state.area == WorkspaceStorageArea.FILES) {
            { pendingTransfer = PendingTransferSheet(listOf(it), WorkspaceTransferMode.MOVE, fromSelection = false) }
        } else {
            null
        },
        onCopyTo = if (state.area == WorkspaceStorageArea.FILES) {
            { pendingTransfer = PendingTransferSheet(listOf(it), WorkspaceTransferMode.COPY, fromSelection = false) }
        } else {
            null
        },
        onExport = { entry ->
            if (!state.isTransferring) {
                pendingFileExportPath = entry.path
                exportLauncher.launch(entry.name)
            }
        },
        onExportZip = { entry -> launchArchiveExport(listOf(entry.path)) },
        onCopyPath = { entry ->
            context.writeClipboardText(workspaceGuestPath(state.area, entry.path))
            toaster.show(context.getString(R.string.workspace_path_copied), type = ToastType.Success)
        },
        onExtract = if (state.area == WorkspaceStorageArea.FILES) {
            { entry -> vm.requestExtract(entry) }
        } else {
            null
        },
    )

    newFileTarget?.let { target ->
        WorkspaceNewFileDialog(
            target = target,
            busy = creation.busy,
            onDismiss = { newFileTarget = null },
            onConfirm = { request ->
                if (vm.createFile(request)) newFileTarget = null
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    pendingBatchDelete?.let { pending ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.common_delete),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                // 只使用发起时的快照; 确认期间的选择/目录变化不影响删除目标
                vm.deleteSelection(paths = pending.paths, area = pending.area)
                pendingBatchDelete = null
                clearSelection()
            },
            onDismiss = { pendingBatchDelete = null },
        ) {
            Text(
                buildString {
                    append("Delete ${pending.paths.size} item(s)")
                    if (pending.includesDirectories) {
                        append(", including folders whose contents are removed recursively")
                    }
                    append(". This cannot be undone.")
                }
            )
        }
    }

    pendingTransfer?.let { pending ->
        WorkspaceTransferSheet(
            sourceWorkspaceId = workspaceId,
            entries = pending.entries,
            mode = pending.mode,
            workspaces = transferWorkspaces,
            listEntries = vm::listTransferEntries,
            onDismiss = { pendingTransfer = null },
            onConfirm = { targetWorkspaceId, destinationDir ->
                vm.transfer(pending.entries, targetWorkspaceId, destinationDir, pending.mode)
                pendingTransfer = null
                if (pending.fromSelection) clearSelection()
            },
        )
    }

    extraction?.let { current ->
        WorkspaceExtractDialog(
            state = current,
            onExtract = vm::extract,
            onCancel = vm::cancelExtract,
            onDismiss = vm::dismissExtract,
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
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

@Composable
internal fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    selectedPaths: Set<String>,
    transferring: Boolean,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onExportZip: (WorkspaceFileEntry) -> Unit,
    onCopyPath: (WorkspaceFileEntry) -> Unit,
    /** null hides Extract (only the FILES area supports it) */
    onExtract: ((WorkspaceFileEntry) -> Unit)?,
    /** null hides Move (only the FILES area supports moving) */
    onMove: ((WorkspaceFileEntry) -> Unit)?,
    /** null hides Copy to (FILES area only) */
    onCopyTo: ((WorkspaceFileEntry) -> Unit)? = null,
    onToggleSelect: (WorkspaceFileEntry) -> Unit,
    onSelectAll: () -> Unit,
    onExportSelection: () -> Unit,
    onExportFilesToDirectory: () -> Unit,
    onDeleteSelection: () -> Unit,
    /** null hides batch Move (only the FILES area supports moving) */
    onMoveSelection: (() -> Unit)? = null,
    /** null hides batch Copy (FILES area only) */
    onCopySelection: (() -> Unit)? = null,
    onCancelTransfer: () -> Unit = {},
    onCancelSelection: () -> Unit,
    onDismissTransferNotice: () -> Unit,
    creation: WorkspaceFileCreationState,
    onDismissCreationError: () -> Unit,
    onCreateFile: (() -> Unit)?,
    /** true when the current selection contains at least one directory entry */
    selectionHasDirectories: Boolean = false,
    onImport: (() -> Unit)? = null,
    onImportFolder: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(WorkspaceFileRowSpacing),
    ) {
        workspaceBlock {
            WorkspaceAreaSelector(
                selected = state.area,
                onSelected = onSelectArea,
            )
        }

        if (selectionMode) {
            workspaceBlock {
                WorkspaceSelectionBar(
                    selectedCount = selectedPaths.size,
                    totalCount = state.entries.size,
                    transferring = transferring,
                    selectionHasDirectories = selectionHasDirectories,
                    onSelectAll = onSelectAll,
                    onExportSelection = onExportSelection,
                    onExportFilesToDirectory = onExportFilesToDirectory,
                    onDeleteSelection = onDeleteSelection,
                    onMoveSelection = onMoveSelection,
                    onCopySelection = onCopySelection,
                    onCancelSelection = onCancelSelection,
                )
            }
        } else {
            workspaceBlock {
                WorkspacePathBar(
                    path = state.path,
                    canGoUp = state.path.isNotBlank(),
                    transferring = transferring,
                    onGoUp = onGoUp,
                    onImport = onImport,
                    onImportFolder = onImportFolder,
                    onCreateFile = onCreateFile,
                    canCreateFile = !creation.busy && creation.pendingOpen == null &&
                        !state.loading && state.error == null && state.workspace != null,
                )
            }
        }

        if (creation.busy || creation.error != null || creation.createdDirectoryPath != null) {
            workspaceBlock { WorkspaceFileCreationFeedback(creation, onDismissCreationError) }
        }

        if (transferring) {
            workspaceBlock {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.transferTotal > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (state.transferFraction ?: (state.transferCompleted.toFloat() / state.transferTotal))
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.transferProgressText ?: stringResource(
                                    R.string.workspace_export_directory_progress,
                                    state.transferCompleted,
                                    state.transferTotal,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.canCancelTransfer) {
                                TextButton(onClick = onCancelTransfer) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            }
                        }
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        state.transferNotice?.let { notice ->
            workspaceBlock {
                NoticeCard(notice, onDismiss = onDismissTransferNotice)
            }
        }

        state.error?.let { error ->
            workspaceBlock {
                ErrorCard(error)
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            workspaceBlock {
                EmptyDirectoryState()
            }
        }

        itemsIndexed(state.entries, key = { _, it -> "${state.area.name}:${it.path}" }) { index, entry ->
            WorkspaceFileCard(
                entry = entry,
                shape = workspaceFileRowShape(index, state.entries.size),
                selectionMode = selectionMode,
                selected = entry.path in selectedPaths,
                transferring = transferring,
                onOpen = { onOpen(entry) },
                onToggleSelect = { onToggleSelect(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onExportZip = { onExportZip(entry) },
                onCopyPath = { onCopyPath(entry) },
                onExtract = onExtract?.takeIf { !entry.isDirectory && WorkspaceArchiveExtractor.isArchiveName(entry.name) }
                    ?.let { extract -> { extract(entry) } },
                onMove = onMove?.let { move -> { move(entry) } },
                onCopyTo = onCopyTo?.let { copy -> { copy(entry) } },
            )
        }
    }
}

@Composable
private fun WorkspaceSelectionBar(
    selectedCount: Int,
    totalCount: Int,
    transferring: Boolean,
    selectionHasDirectories: Boolean,
    onSelectAll: () -> Unit,
    onExportSelection: () -> Unit,
    onExportFilesToDirectory: () -> Unit,
    onDeleteSelection: () -> Unit,
    onMoveSelection: (() -> Unit)?,
    onCopySelection: (() -> Unit)?,
    onCancelSelection: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onCancelSelection) {
                Text(stringResource(R.string.common_cancel))
            }
            Text(
                text = stringResource(R.string.imggen_page_selected_count, selectedCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onSelectAll) {
                Text(
                    if (selectedCount < totalCount) {
                        stringResource(R.string.setting_provider_page_select_all, totalCount - selectedCount)
                    } else {
                        stringResource(R.string.setting_provider_page_deselect_models)
                    }
                )
            }
            TextButton(
                onClick = onExportSelection,
                enabled = selectedCount > 0 && !transferring,
            ) {
                Text("Export ZIP")
            }
            IconButton(
                onClick = onDeleteSelection,
                enabled = selectedCount > 0 && !transferring,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        // A second row avoids pushing the existing ZIP/delete controls off a narrow screen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onMoveSelection != null) {
                TextButton(
                    onClick = onMoveSelection,
                    enabled = selectedCount > 0 && !transferring,
                ) {
                    Text(stringResource(R.string.workspace_move))
                }
            }
            if (onCopySelection != null) {
                TextButton(
                    onClick = onCopySelection,
                    enabled = selectedCount > 0 && !transferring,
                ) {
                    Text(stringResource(R.string.workspace_copy))
                }
            }
            TextButton(
                onClick = onExportFilesToDirectory,
                enabled = selectedCount > 0 && !transferring && !selectionHasDirectories,
            ) {
                Text(stringResource(R.string.workspace_export_directory_action))
            }
        }
        if (selectionHasDirectories) {
            Text(
                text = stringResource(R.string.workspace_export_directory_dirs_use_zip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    transferring: Boolean,
    onGoUp: () -> Unit,
    onImport: (() -> Unit)? = null,
    onImportFolder: (() -> Unit)? = null,
    onCreateFile: (() -> Unit)? = null,
    canCreateFile: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onCreateFile != null) {
            IconButton(onClick = onCreateFile, enabled = !transferring && canCreateFile) {
                Icon(HugeIcons.FileAdd, contentDescription = stringResource(R.string.workspace_new_file_title))
            }
        }
        if (onImport != null) {
            IconButton(onClick = onImport, enabled = !transferring) {
                Icon(
                    HugeIcons.FileImport,
                    contentDescription = stringResource(R.string.workspace_detail_import_file),
                )
            }
        }
        if (onImportFolder != null) {
            IconButton(onClick = onImportFolder, enabled = !transferring) {
                Icon(
                    HugeIcons.FolderImport,
                    contentDescription = "Import folder",
                )
            }
        }
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    shape: Shape,
    selectionMode: Boolean,
    selected: Boolean,
    transferring: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onExportZip: () -> Unit,
    onCopyPath: () -> Unit,
    onExtract: (() -> Unit)?,
    onMove: (() -> Unit)?,
    onCopyTo: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                if (selectionMode) this.selected = selected
            }
            .combinedClickable(
                onLongClickLabel = "Select",
                onLongClick = { onToggleSelect() },
                onClick = {
                    // In selection mode taps only toggle the checkbox state and
                    // never navigate into directories or open files.
                    if (selectionMode) onToggleSelect() else onOpen()
                },
            ),
        shape = shape,
        colors = CustomColors.cardColorsOnSurfaceContainer,
        border = if (selectionMode && selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
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
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                )
            } else {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Select") },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.CheckmarkSquare02,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleSelect()
                            },
                        )
                        if (!entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_export)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.FileImport,
                                        contentDescription = null,
                                    )
                                },
                                enabled = !transferring,
                                onClick = {
                                    menuExpanded = false
                                    onExport()
                                },
                            )
                            if (onExtract != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_extract)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = HugeIcons.FileZip,
                                            contentDescription = null,
                                        )
                                    },
                                    enabled = !transferring,
                                    onClick = {
                                        menuExpanded = false
                                        onExtract()
                                    },
                                )
                            }
                        } else {
                            // Let users export a whole folder directly without
                            // first entering selection mode.
                            DropdownMenuItem(
                                text = { Text("Export ZIP") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.FolderZip,
                                        contentDescription = null,
                                    )
                                },
                                enabled = !transferring,
                                onClick = {
                                    menuExpanded = false
                                    onExportZip()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_copy_path)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Copy01,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onCopyPath()
                            },
                        )
                        if (onMove != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_move)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.FolderTransfer,
                                        contentDescription = null,
                                    )
                                },
                                enabled = !transferring,
                                onClick = {
                                    menuExpanded = false
                                    onMove()
                                },
                            )
                        }
                        if (onCopyTo != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_copy_to)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.Copy02,
                                        contentDescription = null,
                                    )
                                },
                                enabled = !transferring,
                                onClick = {
                                    menuExpanded = false
                                    onCopyTo()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDirectoryState() {
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

@Composable
private fun NoticeCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss),
        colors = CustomColors.cardColorsOnSurfaceContainer,
        border = amoledOutlineBorder(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
        border = amoledOutlineBorder(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 移动/复制面板的发起快照；[fromSelection] = 多选栏发起，确认后清掉选择 */
private data class PendingTransferSheet(
    val entries: List<WorkspaceFileEntry>,
    val mode: WorkspaceTransferMode,
    val fromSelection: Boolean,
)
