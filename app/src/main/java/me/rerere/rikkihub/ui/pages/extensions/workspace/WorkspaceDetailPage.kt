package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import me.rerere.rikkihub.data.ai.tools.toolEnabled
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import me.rerere.rikkihub.data.workspace.WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.rikkihub.ui.components.ui.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FolderSymlink
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkihub.data.ai.tools.workspaceToolApprovalItemsFor
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalSettings
import kotlin.uuid.Uuid
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.fileSizeToString
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 工具线 T0–T2 单项快验通过后开放；这不是用户总开关，也不表示设备 COW 已放行。
 */
private const val WORKSPACE_TOOL_ENABLEMENT_UI_ENABLED = true

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val settingsError by vm.settingsError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var showInstallDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = pagerState.currentPage == 1 && state.path.isNotBlank()) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    presetVm = vm,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onCancelInstall = vm::cancelRootfsInstall,
                    onFirstMessageFileSnapshotDepthChange = vm::setFirstMessageFileSnapshotDepth,
                    onOpenDeviceDirectory = { navController.navigate(Screen.WorkspaceDeviceDirectory(id)) },
                    onToolApprovalChange = vm::setToolApproval,
                    onToolEnablementChange = vm::setToolEnablement,
                    onToolEnablementReset = vm::resetToolEnablement,
                    onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                    onFirstMessageFileSnapshotChange = vm::setFirstMessageFileSnapshot,
                    onExposedMountChange = vm::setExposedMount,
                    onSubagentModelChange = vm::setSubagentModel,
                    onSubagentAllowContextChange = vm::setSubagentAllowContext,
                    hostMountEntries = vm::hostMountEntries,
                )

                1 -> WorkspaceFilesBrowser(
                    workspaceId = id,
                    vm = vm,
                    state = state,
                )
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    settingsError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissSettingsError,
            title = { Text(stringResource(R.string.workspace_detail_settings_save_failed)) },
            text = { Text(message.ifBlank { stringResource(R.string.workspace_detail_settings_save_failed) }) },
            confirmButton = {
                TextButton(onClick = vm::dismissSettingsError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

}

/**
 * 基础页：设置页同款分组列表（CardGroup），每行一个开关或入口。Shell 状态行右侧就是安装 Rootfs；
 * 子代理设置与预设只在「工具启用」里子代理开着时显示；兼容模式放最底部。
 */
@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    presetVm: WorkspaceDetailVM,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onCancelInstall: () -> Unit,
    onFirstMessageFileSnapshotDepthChange: (Int) -> Unit,
    onOpenDeviceDirectory: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onToolEnablementChange: (String, Boolean) -> Unit,
    onToolEnablementReset: () -> Unit,
    onShellCompatibilityModeChange: (Boolean) -> Unit,
    onFirstMessageFileSnapshotChange: (Boolean) -> Unit,
    onExposedMountChange: (String, Boolean) -> Unit,
    onSubagentModelChange: (String?) -> Unit,
    onSubagentAllowContextChange: (Boolean) -> Unit,
    hostMountEntries: (String) -> List<WorkspaceFileEntry>,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }
    // null = 存储 JSON 损坏 (fail-closed: 视为全部不可用)
    val enablement = workspace?.let { WorkspaceToolEnablement.tryParse(it.toolEnablement) }
    val subagentEnabled = enablement?.toolEnabled(BuiltinToolIds.SUBAGENT) == true
    val groupModifier = Modifier.padding(horizontal = 8.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CardGroup(
                modifier = groupModifier,
                title = { Text(stringResource(R.string.workspace_detail_workspace_info)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_name)) },
                    trailingContent = {
                        WorkspaceRowValue(workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_shell_status)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                            installProgress?.let { RootfsProgress(it) }
                        }
                    },
                    trailingContent = {
                        if (installProgress != null) {
                            // 安装在服务级跑（App/Web 共用），任意一端都能取消
                            OutlinedButton(
                                onClick = onCancelInstall,
                                contentPadding = ButtonDefaults.SmallContentPadding,
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        } else {
                            FilledTonalButton(
                                onClick = onInstallRootfs,
                                enabled = workspace != null && !installing,
                                contentPadding = ButtonDefaults.SmallContentPadding,
                            ) {
                                Text(installButtonText)
                            }
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_tool_surface)) },
                    trailingContent = {
                        WorkspaceRowValue(
                            if (workspace?.harnessMinimal == true) {
                                stringResource(R.string.workspace_page_harness_minimal_badge)
                            } else {
                                stringResource(R.string.workspace_detail_tool_surface_default)
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_first_message_snapshot)) },
                    supportingContent = { Text(stringResource(R.string.workspace_detail_first_message_snapshot_desc)) },
                    trailingContent = {
                        Switch(
                            checked = workspace?.firstMessageFileSnapshot == true,
                            onCheckedChange = onFirstMessageFileSnapshotChange,
                            enabled = workspace != null,
                        )
                    },
                )
                if (workspace?.firstMessageFileSnapshot == true) {
                    val depth = workspace.firstMessageFileSnapshotDepth
                    item(
                        headlineContent = { Text(stringResource(R.string.workspace_detail_first_message_snapshot_depth)) },
                        supportingContent = { Text(stringResource(R.string.workspace_detail_first_message_snapshot_depth_desc)) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onFirstMessageFileSnapshotDepthChange(depth - 1) },
                                    enabled = depth > 1,
                                ) { Icon(HugeIcons.MinusSign, contentDescription = null) }
                                Text(depth.toString(), style = MaterialTheme.typography.titleMedium)
                                IconButton(
                                    onClick = { onFirstMessageFileSnapshotDepthChange(depth + 1) },
                                    enabled = depth < WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH,
                                ) { Icon(HugeIcons.PlusSign, contentDescription = null) }
                            }
                        },
                    )
                }
                item(
                    onClick = onOpenDeviceDirectory,
                    leadingContent = { Icon(HugeIcons.FolderSymlink, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.workspace_device_title)) },
                    supportingContent = { Text(stringResource(R.string.workspace_device_entry_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                )
            }
        }

        item {
            WorkspaceExposedMountsGroup(
                workspace = workspace,
                onExposedMountChange = onExposedMountChange,
                hostMountEntries = hostMountEntries,
                modifier = groupModifier,
            )
        }

        if (WORKSPACE_TOOL_ENABLEMENT_UI_ENABLED) {
            item {
                WorkspaceToolEnablementGroup(
                    workspace = workspace,
                    enablement = enablement,
                    onToolEnablementChange = onToolEnablementChange,
                    onReset = onToolEnablementReset,
                    modifier = groupModifier,
                )
            }
        }

        if (subagentEnabled) {
            item {
                WorkspaceSubagentGroup(
                    workspace = workspace,
                    onSubagentModelChange = onSubagentModelChange,
                    onSubagentAllowContextChange = onSubagentAllowContextChange,
                    modifier = groupModifier,
                )
            }

            item {
                Box(groupModifier) { WorkspaceSubagentLibraryCard(workspace = workspace, vm = presetVm) }
            }

            item {
                Box(groupModifier) { WorkspaceSubagentPresetCard(workspace = workspace, vm = presetVm) }
            }
        }

        item {
            WorkspaceToolApprovalGroup(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
                modifier = groupModifier,
            )
        }

        item {
            CardGroup(modifier = groupModifier) {
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_compatibility_mode)) },
                    supportingContent = { Text(stringResource(R.string.workspace_detail_compatibility_mode_desc)) },
                    trailingContent = {
                        Switch(
                            checked = workspace?.shellCompatibilityMode ?: false,
                            onCheckedChange = onShellCompatibilityModeChange,
                            enabled = workspace != null,
                        )
                    },
                )
            }
        }
    }
}

/** 分组标题下的一行说明，替代原来卡片里的说明段落。 */
@Composable
private fun WorkspaceGroupTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkspaceRowValue(value: String) {
    Text(
        text = value,
        modifier = Modifier.widthIn(max = 200.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WorkspaceExposedMountsGroup(
    workspace: WorkspaceEntity?,
    onExposedMountChange: (String, Boolean) -> Unit,
    hostMountEntries: (String) -> List<WorkspaceFileEntry>,
    modifier: Modifier = Modifier,
) {
    var previewTarget by remember { mutableStateOf<String?>(null) }
    val mounts = listOf(
        Triple("/upload", R.string.workspace_detail_mount_upload, R.string.workspace_detail_mount_upload_desc),
        Triple("/skills", R.string.workspace_detail_mount_skills, R.string.workspace_detail_mount_skills_desc),
        Triple("/tool_outputs", R.string.workspace_detail_mount_tool_outputs, R.string.workspace_detail_mount_tool_outputs_desc),
    )

    CardGroup(
        modifier = modifier,
        title = {
            WorkspaceGroupTitle(
                stringResource(R.string.workspace_detail_mounts),
                stringResource(R.string.workspace_detail_mounts_desc),
            )
        },
    ) {
        mounts.forEach { (target, titleRes, descRes) ->
            val checked = when (target) {
                "/upload" -> workspace?.exposeUpload == true
                "/skills" -> workspace?.exposeSkills == true
                "/tool_outputs" -> workspace?.exposeToolOutputs == true
                else -> false
            }
            item(
                // 点行预览该目录内容，开关单独切换
                onClick = if (workspace != null) ({ previewTarget = target }) else null,
                headlineContent = { Text(stringResource(titleRes)) },
                supportingContent = { Text(stringResource(descRes)) },
                trailingContent = {
                    Switch(
                        checked = checked,
                        onCheckedChange = { onExposedMountChange(target, it) },
                        enabled = workspace != null,
                    )
                },
            )
        }
    }

    previewTarget?.let { target ->
        val entries = remember(target) { hostMountEntries(target) }
        AlertDialog(
            onDismissRequest = { previewTarget = null },
            title = { Text(target) },
            text = {
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.workspace_detail_mount_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entries.forEach { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (entry.isDirectory) {
                                        stringResource(R.string.workspace_detail_mount_directory)
                                    } else {
                                        entry.sizeBytes.fileSizeToString()
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewTarget = null }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

/**
 * 「工具启用」独立区：按工作区模式 (普通/极简) 展示可配置工具，按稳定内建 ID
 * 保存 (tool_enablement JSON)，与下方工具审批区相互独立。行标题用本地化标签，
 * 副标题显示当前 wire 名 —— 用户在设置里 Override 了工具名时这里看到的就是
 * Override 后的名字。配置损坏时 fail-closed：开关隐藏、只给显式重置入口。
 */
@Composable
private fun WorkspaceToolEnablementGroup(
    workspace: WorkspaceEntity?,
    enablement: Map<String, Boolean>?,
    onToolEnablementChange: (String, Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overrides = LocalSettings.current.toolOverrides
    var showResetDialog by remember { mutableStateOf(false) }
    val corrupt = workspace != null && enablement == null

    CardGroup(
        modifier = modifier,
        title = {
            WorkspaceGroupTitle(
                stringResource(R.string.workspace_detail_tool_enablement),
                stringResource(R.string.workspace_detail_tool_enablement_desc),
            )
        },
    ) {
        if (corrupt) {
            item(
                onClick = { showResetDialog = true },
                headlineContent = { Text(stringResource(R.string.workspace_detail_tool_enablement_reset)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.workspace_detail_tool_enablement_corrupt),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        } else {
            WorkspaceToolEnablement.idsFor(workspace?.harnessMinimal == true).forEach { toolId ->
                // 显示 Override 后的当前 wire 名 (没 Override 时即默认名 = stable id)
                val wireName = overrides[toolId]?.name?.trim().orEmpty().ifBlank { toolId }
                item(
                    headlineContent = { Text(workspaceToolLabel(toolId)) },
                    supportingContent = { Text(wireName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Switch(
                            checked = enablement?.toolEnabled(toolId) ?: true,
                            onCheckedChange = { onToolEnablementChange(toolId, it) },
                            enabled = workspace != null,
                        )
                    },
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.workspace_detail_tool_enablement_reset)) },
            text = { Text(stringResource(R.string.workspace_detail_tool_enablement_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onReset()
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun workspaceToolLabel(toolId: String): String = when (toolId) {
    BuiltinToolIds.WORKSPACE_READ_FILE -> stringResource(R.string.workspace_detail_tool_read_file)
    BuiltinToolIds.WORKSPACE_WRITE_FILE -> stringResource(R.string.workspace_detail_tool_write_file)
    BuiltinToolIds.WORKSPACE_EDIT_FILE -> stringResource(R.string.workspace_detail_tool_edit_file)
    BuiltinToolIds.WORKSPACE_SHELL -> stringResource(R.string.workspace_detail_tool_shell)
    BuiltinToolIds.BASH -> stringResource(R.string.workspace_detail_tool_bash)
    BuiltinToolIds.STR_REPLACE_EDITOR -> stringResource(R.string.workspace_detail_tool_str_replace_editor)
    BuiltinToolIds.SUBAGENT -> stringResource(R.string.workspace_detail_tool_subagent)
    else -> toolId
}

@Composable
private fun WorkspaceToolApprovalGroup(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()
    val items = workspaceToolApprovalItemsFor(workspace?.harnessMinimal == true)

    CardGroup(
        modifier = modifier,
        title = {
            WorkspaceGroupTitle(
                stringResource(R.string.workspace_detail_tool_approval),
                stringResource(R.string.workspace_detail_tool_approval_desc),
            )
        },
    ) {
        items.forEach { toolName ->
            item(
                headlineContent = { Text(workspaceToolLabel(toolName)) },
                supportingContent = { Text(toolName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                },
            )
        }
    }
}

@Composable
private fun WorkspaceSubagentGroup(
    workspace: WorkspaceEntity?,
    onSubagentModelChange: (String?) -> Unit,
    onSubagentAllowContextChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val selectedId = workspace?.subagentModelId
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    val toolProviders = remember(settings.providers) {
        settings.providers.map { provider ->
            provider.copyProvider(
                models = provider.models.filter {
                    it.type == ModelType.CHAT && ModelAbility.TOOL in it.abilities
                },
            )
        }
    }

    CardGroup(
        modifier = modifier,
        title = {
            WorkspaceGroupTitle(
                stringResource(R.string.workspace_detail_subagent),
                stringResource(R.string.workspace_detail_subagent_desc),
            )
        },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.workspace_detail_subagent_model)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.workspace_detail_subagent_model_desc))
                    ModelSelector(
                        modelId = selectedId,
                        providers = toolProviders,
                        type = ModelType.CHAT,
                        allowClear = true,
                        onSelect = { model ->
                            val id = model.modelId.takeIf { it.isNotBlank() }?.let { model.id.toString() }
                            onSubagentModelChange(id)
                        },
                    )
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.workspace_detail_subagent_allow_context)) },
            supportingContent = { Text(stringResource(R.string.workspace_detail_subagent_allow_context_desc)) },
            trailingContent = {
                Switch(
                    checked = workspace?.subagentAllowContext == true,
                    onCheckedChange = onSubagentAllowContextChange,
                    enabled = workspace != null,
                )
            },
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(defaultRootfsUrl()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}


@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}
