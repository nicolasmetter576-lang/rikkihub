package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.foundation.clickable
import androidx.annotation.StringRes
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
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.data.workspace.WorkspaceEnvMirror
import androidx.compose.material3.Card
import androidx.compose.material3.TopAppBar
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceMode
import androidx.compose.ui.res.stringResource
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import me.rerere.rikkihub.ui.theme.AppSpacing
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkihub.ui.components.ui.CardGroupDivider
import me.rerere.rikkihub.ui.components.ui.cardGroupItemShape

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workspace_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(AppSpacing.sm),
        ) {
            if (workspaces.isEmpty()) {
                item {
                    EmptyWorkspaceState()
                }
            }

            itemsIndexed(workspaces, key = { _, workspace -> workspace.id }) { index, workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    shape = cardGroupItemShape(index, workspaces.size),
                    divider = index > 0,
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                )
            }
        }
    }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            showModeOption = true,
            initialEnvMirror = vm.lastEnvMirror,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, mode, envMirror ->
                vm.create(name, mode, envMirror)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            onDismiss = { editTarget = null },
            onConfirm = { name, _, _ ->
                vm.rename(workspace, name)
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        // 作品里有定稿锁定的章节：删整个工作区前写明
        val history = org.koin.compose.koinInject<me.rerere.rikkihub.data.book.history.BookHistory>()
        val target = deleteTarget
        val locked by androidx.compose.runtime.produceState(0, target?.id) {
            value = target?.let { runCatching { history.locks(it.id).count { (_, l) -> !l.isEmpty } }.getOrDefault(0) } ?: 0
        }
        Column {
            Text(stringResource(R.string.workspace_page_delete_confirm))
            if (locked > 0) Text(stringResource(R.string.book_lock_workspace_delete_note, locked), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyWorkspaceState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.File02,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_page_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_page_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceEntity,
    shape: Shape,
    divider: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = shape,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (divider) CardGroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.sm, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.File02,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = workspace.name,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(workspace.shellStatus.toShellStatusLabel())
                            workspace.mode.badge()?.let {
                                append(" · ")
                                append(stringResource(it))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
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
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    showModeOption: Boolean = false,
    /** Non-null on the create dialog: offers "install the system and set up the environment". */
    initialEnvMirror: WorkspaceEnvMirror? = null,
    onDismiss: () -> Unit,
    /** name, mode, mirror of the environment setup to run (null = none). */
    onConfirm: (String, WorkspaceMode, WorkspaceEnvMirror?) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var mode by rememberSaveable { mutableStateOf(WorkspaceMode.NORMAL) }
    var setupEnvironment by rememberSaveable { mutableStateOf(false) }
    var envMirror by rememberSaveable { mutableStateOf(initialEnvMirror ?: WorkspaceEnvMirror.OFFICIAL) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_page_name)) },
                    singleLine = true,
                    isError = isDuplicate,
                    supportingText = if (isDuplicate) {
                        { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                    } else null,
                )
                if (showModeOption) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WorkspaceModeOption(mode == WorkspaceMode.NORMAL, R.string.workspace_mode_normal, R.string.workspace_mode_normal_desc) {
                            mode = WorkspaceMode.NORMAL
                        }
                        WorkspaceModeOption(mode == WorkspaceMode.BOOK, R.string.workspace_mode_book, R.string.workspace_mode_book_desc) {
                            mode = WorkspaceMode.BOOK
                        }
                        WorkspaceModeOption(
                            mode == WorkspaceMode.HARNESS_MINIMAL,
                            R.string.workspace_page_harness_minimal,
                            R.string.workspace_page_harness_minimal_desc,
                        ) { mode = WorkspaceMode.HARNESS_MINIMAL }
                    }
                }
                if (initialEnvMirror != null) {
                    WorkspaceEnvSetupOption(
                        title = stringResource(R.string.workspace_env_setup_on_create),
                        description = stringResource(R.string.workspace_env_setup_on_create_desc),
                        checked = setupEnvironment,
                        onCheckedChange = { setupEnvironment = it },
                        mirror = envMirror,
                        onMirrorChange = { envMirror = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName, mode, envMirror.takeIf { setupEnvironment }) },
                enabled = name.isNotBlank() && !isDuplicate,
            ) {
                Text(stringResource(R.string.common_save))
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
private fun WorkspaceModeOption(selected: Boolean, @StringRes title: Int, @StringRes description: Int, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 列表里跟在状态后面的模式标记；标准模式不标。 */
@StringRes
internal fun WorkspaceMode.badge(): Int? = when (this) {
    WorkspaceMode.NORMAL -> null
    WorkspaceMode.BOOK -> R.string.workspace_mode_book
    WorkspaceMode.HARNESS_MINIMAL -> R.string.workspace_page_harness_minimal_badge
}
