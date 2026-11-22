package me.rerere.rikkihub.ui.pages.files

import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.lazy.itemsIndexed
import me.rerere.rikkihub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkihub.ui.components.ui.CardGroupDivider
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderLibrary
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Move
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.SdCard
import me.rerere.hugeicons.stroke.Sent
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.files.FileManagerDeviceAccess
import me.rerere.rikkihub.data.files.FileManagerRoot
import me.rerere.rikkihub.data.files.FileManagerService
import me.rerere.rikkihub.data.workspace.AndroidWorkspaceDeviceStorage
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.FileNameDialog
import me.rerere.rikkihub.ui.components.ui.fileBaseNameSelection
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import me.rerere.rikkihub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceEditorDocument
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceWebResources
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceEditorPaneRole
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceExtractDialog
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceFileEditorPane
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceFileType
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceNewFileDialog
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceNewFileTarget
import me.rerere.rikkihub.ui.pages.extensions.workspace.detectFileType
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.fileSizeToString
import me.rerere.rikkihub.utils.writeClipboardText
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceTransferMode
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Two side-by-side panes, MT style: by default the left one shows the in-app library and the
 * right one the phone's storage; either can switch to any root. Copy / Move put the selection of
 * one pane into the folder open on the other. With [pickRequest] the page also sends the
 * selected files to the chat that opened it (see [FileManagerPicks]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerPage(pickRequest: String? = null, importToWorkspace: Boolean = false) {
    val vm = koinViewModel<FileManagerVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current

    LaunchedEffect(vm) { vm.messages.collect { toaster.show(it, type = ToastType.Info) } }
    // Shown again after the editor (or another page) on top of it: the files may have changed.
    LaunchedEffect(Unit) { vm.onShown() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            // Back from system settings (permission) or another app (edited a file): re-read.
            if (event == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else vm.refreshAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The pane whose selection the toolbar acts on: the active one if it has a selection.
    val opSide = state.active.takeIf { state.pane(it).selected.isNotEmpty() }
        ?: state.active.other.takeIf { state.pane(it).selected.isNotEmpty() }
    BackHandler {
        when {
            state.search != null -> vm.closeSearch()
            state.selectedCount > 0 -> vm.clearSelection()
            !vm.goUp(state.active) -> nav.popBackStack()
        }
    }

    var newItemSide by remember { mutableStateOf<FileManagerSide?>(null) }
    var renameTarget by remember { mutableStateOf<Pair<FileManagerSide, WorkspaceFileEntry>?>(null) }
    var deleteSide by remember { mutableStateOf<FileManagerSide?>(null) }
    var zipSide by remember { mutableStateOf<FileManagerSide?>(null) }
    var propertiesTarget by remember { mutableStateOf<Pair<FileManagerSide, WorkspaceFileEntry>?>(null) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    var importSide by remember { mutableStateOf(FileManagerSide.LEFT) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(importSide, uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(importSide, uri)
    }
    val storage = koinInject<WorkspaceDeviceStorage>()
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshAccess()
    }
    val permissionFailed = stringResource(R.string.workspace_device_permission_open_failed)
    val requestAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = (storage as? AndroidWorkspaceDeviceStorage)?.manageAllFilesAccessIntent()
            try {
                context.startActivity(intent ?: error("No system permission page is available"))
            } catch (_: Exception) {
                toaster.show(permissionFailed, type = ToastType.Error)
            }
        } else {
            legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    val openFailed = stringResource(R.string.file_manager_open_failed)
    fun openEntry(side: FileManagerSide, entry: WorkspaceFileEntry) {
        if (entry.isDirectory) {
            vm.open(side, entry.path)
            return
        }
        if (isArchiveName(entry.name)) {
            vm.startExtract(side, entry)
            return
        }
        when (entry.detectFileType()) {
            WorkspaceFileType.TEXT -> nav.navigate(Screen.FileManagerEditor(state.pane(side).root, entry.path))
            WorkspaceFileType.IMAGE -> previewImage = vm.file(side, entry.path).toUri().toString()
            WorkspaceFileType.OTHER -> if (!openExternally(context, vm.file(side, entry.path))) {
                toaster.show(openFailed, type = ToastType.Error)
            }
        }
    }

    Scaffold(
        topBar = {
            val search = state.search
            if (search != null) {
                FileManagerSearchBar(
                    query = search.query,
                    scope = rootLabel(state.pane(search.side).root, state),
                    onQueryChange = vm::setSearchQuery,
                    onClose = vm::closeSearch,
                )
                return@Scaffold
            }
            TopAppBar(
                title = { Text(stringResource(R.string.file_manager_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::openSearch, modifier = Modifier.testTag("file_manager_search")) {
                        Icon(HugeIcons.Search01, contentDescription = stringResource(R.string.file_manager_search))
                    }
                    ViewMenu(
                        sort = state.sort,
                        showHidden = state.showHidden,
                        onSort = vm::setSort,
                        onToggleHidden = vm::toggleHidden,
                    )
                    IconButton(onClick = vm::refreshAccess) {
                        Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.file_manager_refresh))
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        bottomBar = {
            if (state.search != null) return@Scaffold
            FileManagerToolbar(
                selectionSide = opSide,
                selectedInSide = opSide?.let { state.pane(it).selected.size } ?: 0,
                totalSelected = state.selectedCount,
                singleSelection = opSide?.let { side ->
                    val pane = state.pane(side)
                    pane.selected.singleOrNull()?.let { path -> pane.entries.firstOrNull { it.path == path } }
                },
                pickMode = pickRequest != null,
                pickImports = importToWorkspace,
                busy = state.operation != null,
                onNew = { newItemSide = state.active },
                onImportFiles = { importSide = state.active; filePicker.launch(arrayOf("*/*")) },
                onImportFolder = { importSide = state.active; folderPicker.launch(null) },
                onTransfer = { mode -> opSide?.let { vm.transfer(it, mode) } },
                onDelete = { deleteSide = opSide },
                onRename = { entry -> opSide?.let { renameTarget = it to entry } },
                onZip = { zipSide = opSide },
                onExtract = { entry -> opSide?.let { vm.startExtract(it, entry) } },
                onOpenWith = { entry ->
                    opSide?.let { side ->
                        if (!openExternally(context, vm.file(side, entry.path))) toaster.show(openFailed, type = ToastType.Error)
                    }
                },
                onProperties = { entry -> opSide?.let { propertiesTarget = it to entry } },
                onSelectAll = { vm.selectAll(opSide ?: state.active) },
                onSend = {
                    if (importToWorkspace) {
                        pickRequest?.let { vm.pickForImport(it) { nav.popBackStack() } }
                        return@FileManagerToolbar
                    }
                    if (vm.selectionHasFolders()) {
                        toaster.show(context.getString(R.string.file_manager_send_folders_hint), type = ToastType.Info)
                    }
                    if (pickRequest != null && vm.selectedFiles().isNotEmpty()) vm.send(pickRequest) { nav.popBackStack() }
                },
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        state.search?.let { search ->
            FileManagerSearchResults(
                search = search,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onOpen = { entry ->
                    if (entry.isDirectory) {
                        vm.open(search.side, entry.path)
                        vm.closeSearch()
                    } else {
                        openEntry(search.side, entry)
                    }
                },
                onShowInFolder = { entry ->
                    vm.open(search.side, entry.path.substringBeforeLast('/', missingDelimiterValue = ""))
                    vm.closeSearch()
                },
            )
            return@Scaffold
        }
        Row(Modifier.fillMaxSize().padding(innerPadding)) {
            for (side in FileManagerSide.entries) {
                if (side == FileManagerSide.RIGHT) VerticalDivider()
                FilePane(
                    vm = vm,
                    side = side,
                    pane = state.pane(side),
                    active = state.active == side,
                    sort = state.sort,
                    showHidden = state.showHidden,
                    rootLabel = { root -> rootLabel(root, state) },
                    roots = listOf<FileManagerRoot>(FileManagerRoot.Library) + state.volumes.map { FileManagerRoot.Device(it.id) },
                    onRequestAccess = requestAccess,
                    onOpen = { entry -> openEntry(side, entry) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }

    state.operation?.let { operation ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(operation.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (operation.fraction != null) LinearProgressIndicator(progress = { operation.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (operation.detail.isNotEmpty()) {
                        Text(operation.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::cancelOperation) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    newItemSide?.let { side ->
        WorkspaceNewFileDialog(
            target = WorkspaceNewFileTarget(workspaceId = "", parentPath = vm.displayPath(state.pane(side))),
            busy = false,
            onDismiss = { newItemSide = null },
            onConfirm = { request ->
                newItemSide = null
                val root = state.pane(side).root
                vm.create(side, request.leafName, request.isDirectory) { path ->
                    if (!request.isDirectory) nav.navigate(Screen.FileManagerEditor(root, path))
                }
            },
        )
    }

    renameTarget?.let { (side, entry) ->
        FileNameDialog(
            initial = entry.name,
            initialSelection = if (entry.isDirectory) TextRange(0, entry.name.length) else fileBaseNameSelection(entry.name),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                vm.rename(side, entry.path, name)
            },
        )
    }

    deleteSide?.let { side ->
        val count = state.pane(side).selected.size
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.file_manager_delete_title, count),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                deleteSide = null
                vm.delete(side, state.pane(side).selected.toList())
            },
            onDismiss = { deleteSide = null },
        ) { Text(stringResource(R.string.file_manager_delete_desc)) }
    }

    zipSide?.let { side ->
        val pane = state.pane(side)
        val suggested = pane.selected.singleOrNull()?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: pane.path.substringAfterLast('/').ifEmpty { "archive" }
        FileNameDialog(
            title = stringResource(R.string.file_manager_zip_name),
            initial = suggested,
            onDismiss = { zipSide = null },
            onConfirm = { name ->
                zipSide = null
                vm.zip(side, name)
            },
        )
    }

    state.extraction?.let { (_, extraction) ->
        WorkspaceExtractDialog(
            state = extraction,
            onExtract = vm::extract,
            onCancel = vm::cancelOperation,
            onDismiss = vm::dismissExtract,
        )
    }

    propertiesTarget?.let { (side, entry) ->
        val path = vm.displayPath(state.pane(side).copy(path = entry.path))
        // The workspaces folder exists only here: no real path to copy.
        val realPath = remember(side, entry.path) { vm.realFile(side, entry.path)?.path }
        AlertDialog(
            onDismissRequest = { propertiesTarget = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.file_manager_prop_path, path), style = MaterialTheme.typography.bodySmall)
                    if (!entry.isDirectory) {
                        Text(stringResource(R.string.file_manager_prop_size, entry.sizeBytes.fileSizeToString()), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.file_manager_prop_modified, formatTime(entry.updatedAt)), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { propertiesTarget = null }) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = realPath?.let { real ->
                {
                    TextButton(onClick = {
                        context.writeClipboardText(real)
                        propertiesTarget = null
                    }) { Text(stringResource(R.string.workspace_copy_path)) }
                }
            },
        )
    }

    previewImage?.let { image ->
        ImagePreviewDialog(images = listOf(image), onDismissRequest = { previewImage = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileManagerSearchBar(query: String, scope: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.file_manager_search_hint, scope), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("file_manager_search_field"),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(HugeIcons.ArrowTurnBackward, contentDescription = stringResource(R.string.common_cancel))
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(HugeIcons.Cancel01, contentDescription = null)
                }
            }
        },
        colors = CustomColors.topBarColors,
        expandedHeight = AppTopBarHeight,
    )
}

/** Index status, then the matches: tap opens (folders: go there), long-press shows the file in its folder. */
@Composable
private fun FileManagerSearchResults(
    search: FileManagerSearch,
    modifier: Modifier,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onShowInFolder: (WorkspaceFileEntry) -> Unit,
) {
    Column(modifier) {
        val status = when {
            search.indexing != null -> stringResource(R.string.file_manager_search_indexing, search.indexing)
            search.query.isBlank() -> stringResource(R.string.file_manager_search_ready, search.indexSize)
            search.total > search.hits.size -> stringResource(R.string.file_manager_search_results_limited, search.total, search.hits.size)
            else -> stringResource(R.string.file_manager_search_results, search.total)
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("file_manager_search_status"),
        )
        if (search.indexing != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (search.truncated) {
            Text(
                text = stringResource(R.string.file_manager_search_truncated, search.indexSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(search.hits, key = { _, entry -> entry.path }) { index, entry ->
                SearchHitRow(
                    entry = entry,
                    shape = cardGroupItemShape(index, search.hits.size),
                    divider = index > 0,
                    onClick = { onOpen(entry) },
                    onLongClick = { onShowInFolder(entry) },
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(entry: WorkspaceFileEntry, shape: Shape, divider: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        colors = CustomColors.cardColorsOnSurfaceContainer,
        shape = shape,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (divider) CardGroupDivider()
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = entryIcon(entry),
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val folder = entry.path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
                Text(
                    text = if (entry.isDirectory) folder else "$folder · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
    }
}

@Composable
private fun rootLabel(root: FileManagerRoot, state: FileManagerState): String = when (root) {
    FileManagerRoot.Library -> stringResource(R.string.file_manager_library)
    is FileManagerRoot.Device -> state.volumes.firstOrNull { it.id == root.volumeId }?.label ?: root.volumeId
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilePane(
    vm: FileManagerVM,
    side: FileManagerSide,
    pane: FileManagerPane,
    active: Boolean,
    sort: FileManagerSort,
    showHidden: Boolean,
    rootLabel: @Composable (FileManagerRoot) -> String,
    roots: List<FileManagerRoot>,
    onRequestAccess: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(pane.entries, sort, showHidden) { pane.visible(sort, showHidden) }
    val selecting = pane.selected.isNotEmpty()
    Column(modifier.clickable(indication = null, interactionSource = null) { vm.setActive(side) }) {
        PaneHeader(
            title = rootLabel(pane.root),
            path = pane.path,
            active = active,
            roots = roots,
            rootLabel = rootLabel,
            onRoot = { vm.setActive(side); vm.switchRoot(side, it) },
            onPath = { vm.setActive(side); vm.open(side, it) },
            onUp = { vm.setActive(side); vm.goUp(side) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                pane.permissionRequired -> PermissionCard(onRequestAccess)
                isRestrictedDevicePath(pane) -> PaneNotice(stringResource(R.string.file_manager_restricted))
                pane.error != null -> PaneNotice(pane.error)
                pane.loading && pane.entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                entries.isEmpty() -> PaneNotice(stringResource(R.string.file_manager_empty))
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(pane.root, pane.path) { listState.scrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
                            FileRow(
                                entry = entry,
                                shape = cardGroupItemShape(index, entries.size),
                                divider = index > 0,
                                file = { vm.file(side, entry.path) },
                                selected = entry.path in pane.selected,
                                onClick = {
                                    vm.setActive(side)
                                    if (selecting) vm.toggleSelect(side, entry.path) else onOpen(entry)
                                },
                                onLongClick = {
                                    vm.setActive(side)
                                    vm.toggleSelect(side, entry.path)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneHeader(
    title: String,
    path: String,
    active: Boolean,
    roots: List<FileManagerRoot>,
    rootLabel: @Composable (FileManagerRoot) -> String,
    onRoot: (FileManagerRoot) -> Unit,
    onPath: (String) -> Unit,
    onUp: () -> Unit,
) {
    var rootMenu by remember { mutableStateOf(false) }
    Surface(
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { rootMenu = true }.padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                    DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                        roots.forEach { root ->
                            DropdownMenuItem(
                                text = { Text(rootLabel(root)) },
                                leadingIcon = {
                                    Icon(if (root is FileManagerRoot.Library) HugeIcons.FolderLibrary else HugeIcons.SdCard, contentDescription = null)
                                },
                                onClick = {
                                    rootMenu = false
                                    onRoot(root)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = onUp, enabled = path.isNotEmpty(), modifier = Modifier.size(36.dp)) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = stringResource(R.string.file_manager_up), modifier = Modifier.size(20.dp))
                }
            }
            val scroll = rememberScrollState()
            LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
            Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                val segments = if (path.isEmpty()) emptyList() else path.split('/')
                Crumb("/", onClick = { onPath("") })
                segments.forEachIndexed { index, segment ->
                    Crumb(segment, onClick = { onPath(segments.take(index + 1).joinToString("/")) })
                    if (index < segments.lastIndex) Text("/", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Crumb(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: WorkspaceFileEntry,
    shape: Shape,
    divider: Boolean,
    file: () -> File,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        colors = if (selected) androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CustomColors.cardColorsOnSurfaceContainer,
        shape = shape,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // 分隔线画在卡片里：放在两张卡之间的话，线两端缩进处会露出页面底色
        if (divider) CardGroupDivider()
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                val type = entry.detectFileType()
                if (selected) {
                    Icon(HugeIcons.CheckmarkCircle02, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else if (type == WorkspaceFileType.IMAGE) {
                    AsyncImage(
                        model = remember(entry.path) { file() },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        imageVector = entryIcon(entry),
                        contentDescription = null,
                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val time = DateUtils.formatDateTime(
                    LocalContext.current, entry.updatedAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_TIME,
                )
                Text(
                    text = if (entry.isDirectory) time else "${entry.sizeBytes.fileSizeToString()} · $time",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun entryIcon(entry: WorkspaceFileEntry): ImageVector {
    if (entry.isDirectory) return HugeIcons.Folder01
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase()).orEmpty()
    return when {
        mime.startsWith("video/") -> HugeIcons.Video01
        mime.startsWith("audio/") -> HugeIcons.MusicNote03
        else -> HugeIcons.File02
    }
}

@Composable
private fun PermissionCard(onRequestAccess: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.file_manager_permission_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.file_manager_permission_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.workspace_device_grant_access))
        }
    }
}

@Composable
private fun PaneNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

/** Android 11+ hides other apps' Android/data and Android/obb even with all files access. */
private fun isRestrictedDevicePath(pane: FileManagerPane): Boolean {
    if (pane.root !is FileManagerRoot.Device || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val path = pane.path.lowercase()
    return listOf("android/data", "android/obb").any { path == it || path.startsWith("$it/") }
}

@Composable
private fun ViewMenu(
    sort: FileManagerSort,
    showHidden: Boolean,
    onSort: (FileManagerSort) -> Unit,
    onToggleHidden: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(HugeIcons.Sorting01, contentDescription = stringResource(R.string.file_manager_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                FileManagerSort.NAME to R.string.file_manager_sort_name,
                FileManagerSort.TIME to R.string.file_manager_sort_time,
                FileManagerSort.SIZE to R.string.file_manager_sort_size,
            ).forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(selected = sort == option, onClick = null) },
                    onClick = { onSort(option); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_manager_show_hidden)) },
                leadingIcon = { androidx.compose.material3.Checkbox(checked = showHidden, onCheckedChange = null) },
                onClick = { onToggleHidden(); open = false },
            )
        }
    }
}

@Composable
private fun FileManagerToolbar(
    selectionSide: FileManagerSide?,
    selectedInSide: Int,
    totalSelected: Int,
    singleSelection: WorkspaceFileEntry?,
    pickMode: Boolean,
    /** The pick button imports into a workspace (folders allowed) instead of sending to the chat. */
    pickImports: Boolean,
    busy: Boolean,
    onNew: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onTransfer: (WorkspaceTransferMode) -> Unit,
    onDelete: () -> Unit,
    onRename: (WorkspaceFileEntry) -> Unit,
    onZip: () -> Unit,
    onExtract: (WorkspaceFileEntry) -> Unit,
    onOpenWith: (WorkspaceFileEntry) -> Unit,
    onProperties: (WorkspaceFileEntry) -> Unit,
    onSelectAll: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionSide == null) {
                ToolbarButton(HugeIcons.Add01, stringResource(R.string.file_manager_new), enabled = !busy, onClick = onNew)
                var importMenu by remember { mutableStateOf(false) }
                Box {
                    ToolbarButton(HugeIcons.FileImport, stringResource(R.string.file_manager_import), enabled = !busy) { importMenu = true }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_import_files)) }, onClick = { importMenu = false; onImportFiles() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_import_folder)) }, onClick = { importMenu = false; onImportFolder() })
                    }
                }
                Text(
                    text = stringResource(
                        when {
                            pickImports -> R.string.file_manager_import_hint
                            pickMode -> R.string.file_manager_pick_hint
                            else -> R.string.file_manager_select_hint
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            } else {
                ToolbarButton(HugeIcons.Copy01, stringResource(R.string.workspace_copy), enabled = !busy) { onTransfer(WorkspaceTransferMode.COPY) }
                ToolbarButton(HugeIcons.Move, stringResource(R.string.workspace_move), enabled = !busy) { onTransfer(WorkspaceTransferMode.MOVE) }
                ToolbarButton(HugeIcons.Delete01, stringResource(R.string.common_delete), enabled = !busy, onClick = onDelete)
                var moreMenu by remember { mutableStateOf(false) }
                Box {
                    ToolbarButton(HugeIcons.MoreVertical, stringResource(R.string.file_manager_more), enabled = !busy) { moreMenu = true }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_select_all)) }, onClick = { moreMenu = false; onSelectAll() })
                        if (singleSelection != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_rename)) }, onClick = { moreMenu = false; onRename(singleSelection) })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_zip)) }, onClick = { moreMenu = false; onZip() })
                        if (singleSelection != null && !singleSelection.isDirectory && isArchiveName(singleSelection.name)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_extract)) }, onClick = { moreMenu = false; onExtract(singleSelection) })
                        }
                        if (singleSelection != null && !singleSelection.isDirectory) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_open_with)) }, onClick = { moreMenu = false; onOpenWith(singleSelection) })
                        }
                        if (singleSelection != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_properties)) }, onClick = { moreMenu = false; onProperties(singleSelection) })
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.imggen_page_selected_count, selectedInSide),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            }
            if (pickMode) {
                FilledTonalButton(onClick = onSend, enabled = totalSelected > 0 && !busy, contentPadding = PaddingValues(horizontal = 12.dp), shape = MaterialTheme.shapes.medium) {
                    Icon(if (pickImports) HugeIcons.FileImport else HugeIcons.Sent, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (pickImports) R.string.file_manager_import_count else R.string.file_manager_send_count, totalSelected))
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

private val ARCHIVE_SUFFIXES = listOf(".zip", ".7z", ".rar", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".gz", ".bz2", ".xz")

private fun isArchiveName(name: String) = ARCHIVE_SUFFIXES.any { name.lowercase().endsWith(it) }

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** Opens [file] in another app; a file outside the FileProvider roots (SD card) is shared through a cache copy. */
private fun openExternally(context: Context, file: File): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: IllegalArgumentException) {
        val copy = File(context.cacheDir, "file-manager-share/${file.name}")
        copy.parentFile?.deleteRecursively()
        copy.parentFile?.mkdirs()
        file.copyTo(copy)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
    }
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(Intent.createChooser(intent, null))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Text editor for a file manager file: the workspace editor, reading and saving through [FileManagerService]. */
@Composable
fun FileManagerEditorPage(root: FileManagerRoot, path: String) {
    val service = koinInject<FileManagerService>()
    val nav = LocalNavController.current
    val libraryName = stringResource(R.string.file_manager_library)
    val document = remember(root, path) {
        WorkspaceEditorDocument(
            key = "file-manager\u0000$root\u0000$path",
            fileName = path.substringAfterLast('/'),
            displayPath = if (root is FileManagerRoot.Library) "$libraryName/$path" else service.file(root, path).path,
            editable = true,
            read = { service.readText(root, path) },
            write = { text, revision -> service.writeText(root, path, text, revision) },
            // Relative URLs of an HTML page resolve inside the same root (the file layer refuses `..` and escaping links).
            webResources = WorkspaceWebResources("/$path") { urlPath ->
                runCatching { service.file(root, urlPath.removePrefix("/")) }.getOrNull()?.takeIf { it.isFile }
            },
        )
    }
    WorkspaceFileEditorPane(
        document = document,
        role = WorkspaceEditorPaneRole.MAIN,
        active = true,
        onExit = { nav.popBackStack() },
        onSwitch = null,
    )
}
