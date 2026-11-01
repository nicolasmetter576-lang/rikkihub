package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import me.rerere.rikkihub.ui.components.ui.FilledTonalButton
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceTransferMode
import me.rerere.workspace.WorkspaceTransferProgress
import me.rerere.workspace.WorkspaceTransferResult

/**
 * [sourcePath]（相对源 FILES 根）能否移动/复制进 [destinationDir]（相对目标 FILES 根，"" 为根）。
 * 只有同一工作区才有限制：目录不能放进自己或自己的子目录；移动的落点不能是原文件夹（原地不动）。
 * 复制允许落在原文件夹，生成 "name (1)" 副本。供 UI 禁用确认键，VM 也按它过滤。
 */
internal fun workspaceTransferAllowed(
    sourcePath: String,
    sameWorkspace: Boolean,
    destinationDir: String,
    mode: WorkspaceTransferMode,
): Boolean {
    val source = sourcePath.trim('/')
    val dir = destinationDir.trim('/')
    if (source.isEmpty()) return false
    if (!sameWorkspace) return true
    if (dir == source || dir.startsWith("$source/")) return false
    if (mode == WorkspaceTransferMode.MOVE && dir == source.substringBeforeLast('/', missingDelimiterValue = "")) return false
    return true
}

/** 落点里已有同名项、会被自动改名的数量（[destinationNames] 是目标文件夹现有条目名） */
internal fun workspaceTransferConflictCount(sourceNames: List<String>, destinationNames: Set<String>): Int =
    sourceNames.count { it in destinationNames }

/** 总进度 0..1：已完成项 + 当前项的字节比例 */
internal fun workspaceTransferFraction(progress: WorkspaceTransferProgress): Float {
    if (progress.itemCount <= 0) return 0f
    val current = if (progress.bytesTotal > 0) progress.bytesDone.toFloat() / progress.bytesTotal else 0f
    return ((progress.itemIndex + current) / progress.itemCount).coerceIn(0f, 1f)
}

internal data class WorkspaceTransferSummary(
    val succeeded: Int,
    val renamed: Int,
    val skippedLinks: Int,
    val failures: List<me.rerere.workspace.WorkspaceTransferItemResult>,
)

internal fun workspaceTransferSummary(result: WorkspaceTransferResult): WorkspaceTransferSummary {
    val ok = result.succeeded
    return WorkspaceTransferSummary(
        succeeded = ok.size,
        renamed = ok.count { it.renamed },
        skippedLinks = result.items.sumOf { it.skippedLinks },
        failures = result.failures,
    )
}

/**
 * 移动 / 复制：先选目标工作区（默认本工作区），再从它的 FILES 区逐级选文件夹，确认后以原名放进去；
 * 目标里已有同名项时自动改名为 "name (n)"，确认前提示有几项会改名。
 * 本工作区内：被选中的文件夹不能进入/作为落点；移动不能落在原文件夹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceTransferSheet(
    sourceWorkspaceId: String,
    entries: List<WorkspaceFileEntry>,
    mode: WorkspaceTransferMode,
    workspaces: List<WorkspaceEntity>,
    listEntries: suspend (workspaceId: String, path: String) -> List<WorkspaceFileEntry>,
    onDismiss: () -> Unit,
    onConfirm: (targetWorkspaceId: String, destinationDir: String) -> Unit,
) {
    val entry = entries.first()
    val sourcePaths = remember(entries) { entries.map { it.path }.toSet() }
    val sourceParent = entry.path.substringBeforeLast('/', missingDelimiterValue = "")
    var targetWorkspaceId by remember(sourceWorkspaceId) { mutableStateOf(sourceWorkspaceId) }
    var browsePath by remember(entry.path) { mutableStateOf(sourceParent) }
    var dirs by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var names by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var workspaceMenu by remember { mutableStateOf(false) }
    val sameWorkspace = targetWorkspaceId == sourceWorkspaceId

    LaunchedEffect(targetWorkspaceId, browsePath) {
        loading = true
        try {
            val listed = listEntries(targetWorkspaceId, browsePath)
            // 本工作区里，被选中的文件夹本身不能作为落点，也不让进入
            dirs = listed
                .filter { it.isDirectory && !(sameWorkspace && it.path in sourcePaths) }
                .sortedBy { it.name.lowercase() }
            names = listed.mapTo(HashSet()) { it.name }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dirs = emptyList()
            names = emptySet()
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    val allowed = entries.all { workspaceTransferAllowed(it.path, sameWorkspace, browsePath, mode) }
    val canConfirm = allowed && !loading && error == null
    // 只在能放进去时预告改名：移动落在原文件夹（禁用）时源自己也在列表里，不该算重名
    val conflicts = if (canConfirm) workspaceTransferConflictCount(entries.map { it.name }, names) else 0
    val targetName = workspaces.firstOrNull { it.id == targetWorkspaceId }?.name ?: ""

    // 内容超过半屏，半展开时确认键会落到屏幕外（压在导航栏下）：总是完全展开
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    mode == WorkspaceTransferMode.MOVE && entries.size == 1 ->
                        stringResource(R.string.workspace_move_title, entry.name)
                    mode == WorkspaceTransferMode.MOVE -> stringResource(R.string.workspace_move_title_multi, entries.size)
                    entries.size == 1 -> stringResource(R.string.workspace_copy_title, entry.name)
                    else -> stringResource(R.string.workspace_copy_title_multi, entries.size)
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.workspace_move_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.workspace_transfer_workspace),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { workspaceMenu = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("workspace_transfer_workspace"),
                    ) {
                        Text(
                            text = targetName,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = workspaceMenu,
                        onDismissRequest = { workspaceMenu = false },
                    ) {
                        // 本工作区排第一并标注，其余按名字
                        val ordered = workspaces.filter { it.id == sourceWorkspaceId } +
                            workspaces.filter { it.id != sourceWorkspaceId }.sortedBy { it.name.lowercase() }
                        ordered.forEach { workspace ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (workspace.id == sourceWorkspaceId) {
                                            stringResource(R.string.workspace_transfer_current, workspace.name)
                                        } else {
                                            workspace.name
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (workspace.id == targetWorkspaceId) FontWeight.Bold else null,
                                    )
                                },
                                onClick = {
                                    workspaceMenu = false
                                    if (workspace.id != targetWorkspaceId) {
                                        targetWorkspaceId = workspace.id
                                        browsePath = if (workspace.id == sourceWorkspaceId) sourceParent else ""
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    enabled = browsePath.isNotBlank(),
                    onClick = {
                        browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
                    },
                ) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                }
                Text(
                    text = "/$browsePath",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(dirs, key = { it.path }) { dir ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = dir.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Folder01,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { browsePath = dir.path },
                    )
                }

                if (!loading && dirs.isEmpty()) {
                    item {
                        Text(
                            text = error ?: stringResource(R.string.workspace_cwd_no_subdirectories),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            HorizontalDivider()

            if (conflicts > 0) {
                Text(
                    text = stringResource(R.string.workspace_transfer_conflicts, conflicts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                FilledTonalButton(
                    enabled = canConfirm,
                    onClick = { onConfirm(targetWorkspaceId, browsePath) },
                ) {
                    Text(
                        stringResource(
                            if (mode == WorkspaceTransferMode.MOVE) R.string.workspace_move_here else R.string.workspace_copy_here,
                        ),
                    )
                }
            }
        }
    }
}
