package me.rerere.rikkihub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.FolderTransfer
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.repository.MemoryMigrationPreview
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 独立全局记忆库（__global__）管理页：无需借道已开全局记忆的助手即可查看、编辑、做梦整理，
 * 并可安全地从任意拥有独立记忆的助手复制/合并记忆到全局库。
 */
@Composable
fun GlobalMemoryPage(vm: GlobalMemoryVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val consolidation by vm.consolidation.collectAsStateWithLifecycle()
    val fileCounts by vm.fileCountsByAssistant.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val globalAssistants = remember(settings.assistants) {
        settings.assistants.filter { it.enableMemory && it.useGlobalMemory }
    }
    val importableAssistants = remember(settings.assistants, fileCounts) {
        settings.assistants
            .mapNotNull { assistant ->
                val count = fileCounts[assistant.id.toString()] ?: 0
                if (count > 0) assistant to count else null
            }
            .sortedByDescending { it.second }
    }

    var showAssistantPicker by remember { mutableStateOf(false) }
    var migrationSource by remember { mutableStateOf<Assistant?>(null) }
    var migrationPreview by remember { mutableStateOf<MemoryMigrationPreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }

    LaunchedEffect(migrationSource) {
        val source = migrationSource ?: return@LaunchedEffect
        previewLoading = true
        migrationPreview = null
        migrationPreview = runCatching { vm.previewMigrationFrom(source.id) }.getOrNull()
        previewLoading = false
    }

    val autoDream = settings.memoryDream.autoDream && globalAssistants.any { it.autoConsolidateMemory }
    val showConsolidation = globalAssistants.isNotEmpty() || memories.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_memory_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (importableAssistants.isNotEmpty()) {
                        IconButton(onClick = { showAssistantPicker = true }) {
                            Icon(
                                HugeIcons.FolderTransfer,
                                contentDescription = stringResource(R.string.global_memory_import_from_assistant),
                            )
                        }
                    }
                    IconButton(
                        onClick = { navController.navigate(Screen.MemoryFile(MemoryRepository.GLOBAL_MEMORY_ID, null)) }
                    ) {
                        Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.add))
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            MemoryFileListContent(
                memories = memories,
                consolidation = consolidation.takeIf { showConsolidation },
                autoDream = autoDream,
                canBackfill = globalAssistants.isNotEmpty(),
                onOrganizeMemory = { vm.organizeMemory() },
                onCancelDream = { vm.cancelDream() },
                onCountBackfill = { vm.countBackfillCandidates(it) },
                onBackfill = { vm.backfill(it) },
                onOpenMemory = { path ->
                    navController.navigate(Screen.MemoryFile(MemoryRepository.GLOBAL_MEMORY_ID, path))
                },
                onDeleteMemory = { vm.deleteMemory(it) },
                deleteWarning = stringResource(R.string.memory_global_delete_warning),
                headerContent = {
                    CardGroup {
                        item(
                            leadingContent = {
                                Icon(
                                    HugeIcons.Globe02,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.memory_scope_global_label)) },
                            supportingContent = {
                                val summary = if (globalAssistants.isEmpty()) {
                                    stringResource(R.string.global_memory_no_assistants)
                                } else {
                                    val names = globalAssistants.take(4).joinToString("、") {
                                        it.name.ifBlank { context.getString(R.string.assistant_page_default_assistant) }
                                    } + if (globalAssistants.size > 4) "…" else ""
                                    stringResource(R.string.global_memory_used_by, globalAssistants.size, names)
                                }
                                Text(summary)
                            },
                        )
                        if (importableAssistants.isNotEmpty()) {
                            item(
                                onClick = { showAssistantPicker = true },
                                leadingContent = { Icon(HugeIcons.FolderTransfer, contentDescription = null) },
                                headlineContent = { Text(stringResource(R.string.global_memory_import_from_assistant)) },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            R.string.global_memory_import_from_assistant_desc,
                                            importableAssistants.size,
                                        )
                                    )
                                },
                                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                            )
                        }
                        item(
                            onClick = { navController.navigate(Screen.SettingMemoryDream) },
                            leadingContent = { Icon(HugeIcons.Brain02, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.setting_memory_dream_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_memory_dream_entry_desc)) },
                            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                        )
                    }
                },
                emptyStateContent = {
                    MemoryEmptyState(
                        description = stringResource(R.string.global_memory_empty_hint),
                        primaryActionLabel = if (importableAssistants.isNotEmpty()) {
                            stringResource(R.string.global_memory_import_from_assistant)
                        } else {
                            null
                        },
                        onPrimaryAction = if (importableAssistants.isNotEmpty()) {
                            { showAssistantPicker = true }
                        } else {
                            null
                        },
                    )
                },
            )
        }
    }

    if (showAssistantPicker) {
        AlertDialog(
            onDismissRequest = { showAssistantPicker = false },
            title = { Text(stringResource(R.string.global_memory_pick_assistant_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    CardGroup {
                        importableAssistants.forEach { (assistant, count) ->
                            val displayName = assistant.name.ifBlank {
                                context.getString(R.string.assistant_page_default_assistant)
                            }
                            item(
                                onClick = {
                                    showAssistantPicker = false
                                    migrationSource = assistant
                                },
                                headlineContent = {
                                    Text(
                                        text = displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.memory_file_count_summary, count))
                                },
                                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAssistantPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val activeSource = migrationSource
    if (activeSource != null) {
        val displayName = activeSource.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
        MemoryMigrationDialog(
            sourceName = displayName,
            preview = migrationPreview,
            loading = previewLoading,
            migrating = migrating,
            dreamRunning = consolidation.running,
            showSyncMarksOption = true,
            onDismiss = {
                migrationSource = null
                migrationPreview = null
            },
            onConfirm = { strategy, syncMarks ->
                migrating = true
                scope.launch {
                    val outcome = runCatching { vm.migrateFrom(activeSource.id, strategy, syncMarks) }
                    migrating = false
                    migrationSource = null
                    migrationPreview = null
                    outcome.onSuccess { result ->
                        val msg = buildString {
                            append(
                                context.getString(
                                    R.string.memory_migrate_result,
                                    result.copied,
                                    result.merged,
                                    result.overwritten,
                                    result.skipped,
                                )
                            )
                            if (result.failedOversized.isNotEmpty()) {
                                append("\n")
                                append(
                                    context.getString(
                                        R.string.memory_migrate_result_oversized,
                                        result.failedOversized.joinToString(", "),
                                    )
                                )
                            }
                        }
                        toaster.show(
                            message = msg,
                            type = if (result.failedOversized.isEmpty()) ToastType.Success else ToastType.Warning,
                        )
                    }.onFailure { error ->
                        toaster.show(
                            message = error.message ?: context.getString(R.string.memory_migrate_failed),
                            type = ToastType.Error,
                        )
                    }
                }
            },
        )
    }
}
