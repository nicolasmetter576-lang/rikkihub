package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity
import me.rerere.rikkihub.data.workspace.AndroidWorkspaceDeviceStorage
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceAvailability
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryEntry
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 工作区「设备目录」连接页（直连写手机原目录，无 COW/upper/commit）。
 *
 * 高危操作边界：写入/删除直接作用于宿主原文件、无统一回滚、不纳入 App
 * 备份与清理范围；页面顶部常驻高危提示。授权用「所有文件访问」系统页
 * （`AndroidWorkspaceDeviceStorage.manageAllFilesAccessIntent`），返回后
 * 经 [WorkspaceDeviceDirectoryVM.refreshAvailability] 重检。
 */
@Composable
fun WorkspaceDeviceDirectoryPage(workspaceId: String) {
    val service: WorkspaceDeviceDirectoryService = koinInject()
    val storage: WorkspaceDeviceStorage = koinInject()
    val runtimeGate: me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate = koinInject()
    val vm: WorkspaceDeviceDirectoryVM = viewModel(key = "workspace-device:$workspaceId") {
        WorkspaceDeviceDirectoryVM(
            workspaceId = workspaceId,
            service = service,
            storage = storage,
            runtimeGate = runtimeGate,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // 从系统授权页/设置页回到前台时重检授权（结果不可信 resultCode，重查为准）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshAvailability()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var fileMountId by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }
    var fileViewToken by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }
    fileMountId?.let { mountId ->
        BackHandler { fileMountId = null }
        WorkspaceDeviceFilesPanel(
            workspaceId = workspaceId, mountId = mountId, openedViewToken = fileViewToken,
            onBack = { fileMountId = null },
        )
        return
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingMount by remember { mutableStateOf<WorkspaceDeviceMountEntity?>(null) }
    var deletingMount by remember { mutableStateOf<WorkspaceDeviceMountEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.workspace_device_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = vm::refreshAvailability,
                        enabled = !state.mutating,
                    ) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.availability == WorkspaceDeviceAvailability.READY) {
                        IconButton(
                            onClick = { showAddDialog = true },
                            enabled = !state.mutating,
                        ) {
                            Icon(HugeIcons.Add01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DeviceDirectoryRiskCard()
            }
            when (state.availability) {
                WorkspaceDeviceAvailability.UNSUPPORTED_API -> item {
                    DeviceDirectoryNotice(
                        text = stringResource(R.string.workspace_device_unsupported_api),
                    )
                }

                WorkspaceDeviceAvailability.PERMISSION_REQUIRED -> item {
                    DeviceDirectoryPermissionCard(storage = storage, onFailure = vm::showError)
                }

                WorkspaceDeviceAvailability.PRIMARY_VOLUME_UNAVAILABLE -> item {
                    DeviceDirectoryNotice(
                        text = stringResource(R.string.workspace_device_volume_unavailable),
                    )
                }

                WorkspaceDeviceAvailability.READY -> {
                    if (state.mounts.isEmpty()) {
                        item {
                            DeviceDirectoryNotice(
                                text = stringResource(R.string.workspace_device_empty),
                            )
                        }
                    }
                }
            }
            // Configuration cleanup remains available after storage permission is revoked.
            items(state.mounts, key = { it.id }) { mount ->
                DeviceMountCard(
                    mount = mount,
                    available = mount.id in state.availableMountIds,
                    busy = state.mutating,
                    canAccess = state.availability == WorkspaceDeviceAvailability.READY,
                    onToggle = { enabled -> vm.setEnabled(mount, enabled) },
                    onEdit = { editingMount = mount },
                    onDelete = { deletingMount = mount },
                    onFiles = { fileViewToken = state.viewToken; fileMountId = mount.id },
                )
            }
        }
    }

    if (showAddDialog) {
        DeviceMountEditDialog(
            existing = null,
            browsePath = state.browsePath,
            browseEntries = state.browseEntries,
            browseLoading = state.browseLoading,
            onBrowse = vm::browseTo,
            onBrowseUp = vm::browseUp,
            onDismiss = { showAddDialog = false },
            onConfirm = { alias, source ->
                vm.createMount(alias, source)
                showAddDialog = false
            },
        )
    }
    editingMount?.let { mount ->
        DeviceMountEditDialog(
            existing = mount,
            browsePath = state.browsePath,
            browseEntries = state.browseEntries,
            browseLoading = state.browseLoading,
            onBrowse = vm::browseTo,
            onBrowseUp = vm::browseUp,
            onDismiss = { editingMount = null },
            onConfirm = { alias, source ->
                vm.updateMount(mount, alias, source)
                editingMount = null
            },
        )
    }
    deletingMount?.let { mount ->
        AlertDialog(
            onDismissRequest = { deletingMount = null },
            title = { Text(stringResource(R.string.workspace_device_unbind_title)) },
            text = { Text(stringResource(R.string.workspace_device_unbind_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMount(mount)
                        deletingMount = null
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingMount = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(stringResource(R.string.workspace_device_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

@Composable
private fun DeviceDirectoryRiskCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_device_risk_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.workspace_device_risk_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceDirectoryNotice(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
private fun DeviceDirectoryPermissionCard(storage: WorkspaceDeviceStorage, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_device_permission_needed),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.workspace_device_permission_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val intent = (storage as? AndroidWorkspaceDeviceStorage)
                        ?.manageAllFilesAccessIntent()
                    try {
                        if (intent == null) error("No system permission page is available")
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        onFailure(context.getString(R.string.workspace_device_permission_open_failed))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.workspace_device_grant_access))
            }
        }
    }
}

@Composable
private fun DeviceMountCard(
    mount: WorkspaceDeviceMountEntity,
    available: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canAccess: Boolean,
    onFiles: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HugeIcons.Folder01, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mount.alias,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = deviceMountDisplayPath(mount.sourceRelativePath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = mount.enabled,
                    onCheckedChange = onToggle,
                    enabled = !busy && (mount.enabled || canAccess),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (available || !mount.enabled) {
                        stringResource(if (mount.enabled) R.string.workspace_device_status_active else R.string.workspace_device_status_inactive)
                    } else {
                        stringResource(R.string.workspace_device_status_unavailable)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFiles, enabled = available && canAccess && !busy) {
                    Text(stringResource(R.string.workspace_device_files_open))
                }
                IconButton(onClick = onEdit, enabled = !busy && canAccess) {
                    Icon(HugeIcons.PencilEdit01, contentDescription = null)
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 新增/编辑连接对话框：受控目录浏览器（逐级进入主卷下普通目录）+ alias
 * 校验 + 跨工作区共享 source 的显式勾选确认。
 */
@Composable
private fun DeviceMountEditDialog(
    existing: WorkspaceDeviceMountEntity?,
    browsePath: String,
    browseEntries: List<WorkspaceDeviceDirectoryEntry>,
    browseLoading: Boolean,
    onBrowse: (String) -> Unit,
    onBrowseUp: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (alias: String, sourceRelativePath: String) -> Unit,
) {
    var alias by rememberSaveable { mutableStateOf(existing?.alias ?: "") }
    var aliasEdited by rememberSaveable { mutableStateOf(false) }

    // 打开对话框时把浏览器定位到既有 source 或卷根
    LaunchedEffect(existing?.id) {
        onBrowse(existing?.sourceRelativePath ?: "")
    }

    val aliasViolation = validateDeviceMountAlias(alias)
    val showAliasError = aliasEdited && aliasViolation != null
    // 空路径 = 连接整个共享存储（卷根）
    val canConfirm = aliasViolation == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existing == null) {
                    stringResource(R.string.workspace_device_add_title)
                } else {
                    stringResource(R.string.workspace_device_edit_title)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = {
                        alias = it
                        aliasEdited = true
                    },
                    label = { Text(stringResource(R.string.workspace_device_alias_label)) },
                    isError = showAliasError,
                    supportingText = {
                        Text(
                            text = when (aliasViolation) {
                                WorkspaceDeviceAliasViolation.EMPTY ->
                                    stringResource(R.string.workspace_device_alias_required)

                                WorkspaceDeviceAliasViolation.FORMAT ->
                                    stringResource(R.string.workspace_device_alias_invalid)

                                null -> stringResource(R.string.workspace_device_alias_hint)
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ---- 受控目录浏览器 ----
                Text(
                    text = stringResource(R.string.workspace_device_pick_directory),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBrowseUp, enabled = browsePath.isNotEmpty()) {
                        Icon(HugeIcons.ArrowUp01, contentDescription = null)
                    }
                    Text(
                        text = if (browsePath.isEmpty()) "/" else browsePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                ) {
                    if (browseLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn {
                            items(browseEntries, key = { it.relativePath }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onBrowse(entry.relativePath) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(HugeIcons.Folder01, contentDescription = null)
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(
                        R.string.workspace_device_current_selection,
                        if (browsePath.isEmpty()) "/" else browsePath,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(alias.trim(), browsePath) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
