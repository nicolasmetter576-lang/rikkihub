package me.rerere.rikkihub.ui.pages.assistant.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.FolderTransfer
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.repository.MemoryMigrationPreview
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.FilledTonalButton
import me.rerere.rikkihub.ui.components.ui.PagerTab
import me.rerere.rikkihub.ui.components.ui.PagerTabs
import me.rerere.rikkihub.ui.components.ui.switchItem
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.pages.memory.MemoryEmptyState
import me.rerere.rikkihub.ui.pages.memory.MemoryFileListContent
import me.rerere.rikkihub.ui.pages.memory.MemoryMigrationDialog
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// 记忆文件放第一页：从侧边栏的整理入口进来时能直接看到整理状态
private enum class MemoryTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    FILES(HugeIcons.Brain02, R.string.assistant_page_manage_memory_title),
    SETTINGS(HugeIcons.Settings03, R.string.settings);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val consolidation by vm.memoryConsolidation.collectAsStateWithLifecycle()
    val privateMemoryCount by vm.privateMemoryCount.collectAsStateWithLifecycle()
    val globalMemoryCount by vm.globalMemoryCount.collectAsStateWithLifecycle()
    val globalMemoryAssistantCount by vm.globalMemoryAssistantCount.collectAsStateWithLifecycle()
    val globalMemoryDreamRunning by vm.globalMemoryDreamRunning.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val pagerState = rememberPagerState { MemoryTab.entries.size }
    val scope = rememberCoroutineScope()

    var showMigrateDialog by remember { mutableStateOf(false) }
    var migrationPreview by remember { mutableStateOf<MemoryMigrationPreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }

    LaunchedEffect(showMigrateDialog) {
        if (!showMigrateDialog) return@LaunchedEffect
        previewLoading = true
        migrationPreview = null
        migrationPreview = runCatching { vm.previewMigrateToGlobal() }.getOrNull()
        previewLoading = false
    }

    val isGlobal = assistant.useGlobalMemory

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                subtitle = {
                    Text(
                        stringResource(
                            if (isGlobal) R.string.memory_scope_global_label else R.string.memory_scope_assistant_label
                        )
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (MemoryTab.entries[pagerState.currentPage] == MemoryTab.FILES) {
                        IconButton(onClick = { navController.navigate(Screen.MemoryFile(vm.memoryId, null)) }) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.add))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = MemoryTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = AppSpacing.sm),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (MemoryTab.entries[page]) {
                    MemoryTab.FILES -> MemoryFileListContent(
                        memories = memories,
                        consolidation = consolidation.takeIf { assistant.enableMemory },
                        autoDream = assistant.autoConsolidateMemory && settings.memoryDream.autoDream,
                        canBackfill = assistant.enableMemory,
                        onOrganizeMemory = { vm.organizeMemory() },
                        onCancelDream = { vm.cancelDream() },
                        onCountBackfill = { vm.countBackfillCandidates(it) },
                        onBackfill = { vm.backfillMemory(it) },
                        onOpenMemory = { path -> navController.navigate(Screen.MemoryFile(vm.memoryId, path)) },
                        onDeleteMemory = { vm.deleteMemory(it) },
                        deleteWarning = if (isGlobal) stringResource(R.string.memory_global_delete_warning) else null,
                        headerContent = {
                            MemoryScopeBanner(
                                isGlobal = isGlobal,
                                privateMemoryCount = privateMemoryCount,
                                globalMemoryAssistantCount = globalMemoryAssistantCount,
                                onMigrateToGlobal = { showMigrateDialog = true },
                            )
                        },
                        emptyStateContent = {
                            val openSettings = {
                                scope.launch { pagerState.animateScrollToPage(MemoryTab.SETTINGS.ordinal) }
                                Unit
                            }
                            when {
                                !assistant.enableMemory -> MemoryEmptyState(
                                    description = stringResource(R.string.assistant_page_memory_empty_disabled_hint),
                                    secondaryActionLabel = stringResource(R.string.settings),
                                    onSecondaryAction = openSettings,
                                )

                                isGlobal && privateMemoryCount > 0 -> MemoryEmptyState(
                                    description = stringResource(
                                        R.string.memory_global_empty_with_private_hint,
                                        privateMemoryCount,
                                    ),
                                    primaryActionLabel = stringResource(R.string.memory_migrate_to_global_button),
                                    onPrimaryAction = { showMigrateDialog = true },
                                    secondaryActionLabel = stringResource(R.string.settings),
                                    onSecondaryAction = openSettings,
                                )

                                isGlobal -> MemoryEmptyState(
                                    description = stringResource(R.string.global_memory_empty_hint),
                                )

                                else -> MemoryEmptyState(
                                    description = stringResource(R.string.assistant_page_memory_empty_hint),
                                )
                            }
                        },
                    )

                    MemoryTab.SETTINGS -> MemorySettingsTab(
                        assistant = assistant,
                        privateMemoryCount = privateMemoryCount,
                        globalMemoryCount = globalMemoryCount,
                        onUpdateAssistant = { updated ->
                            val switchedGlobal = updated.useGlobalMemory != assistant.useGlobalMemory
                            vm.update(updated)
                            if (switchedGlobal) {
                                val message = when {
                                    updated.useGlobalMemory && privateMemoryCount > 0 -> context.getString(
                                        R.string.memory_switched_to_global_preserved_toast,
                                        privateMemoryCount,
                                    )

                                    updated.useGlobalMemory -> context.getString(R.string.memory_switched_to_global_toast)
                                    else -> context.getString(R.string.memory_switched_to_assistant_toast)
                                }
                                toaster.show(message = message, type = ToastType.Info)
                            }
                        },
                        onMigrateToGlobal = { showMigrateDialog = true },
                        onOpenGlobalMemory = { navController.navigate(Screen.GlobalMemory) },
                        onOpenDreamSettings = { navController.navigate(Screen.SettingMemoryDream) },
                    )
                }
            }
        }
    }

    if (showMigrateDialog) {
        val displayName = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
        MemoryMigrationDialog(
            sourceName = displayName,
            preview = migrationPreview,
            loading = previewLoading,
            migrating = migrating,
            dreamRunning = globalMemoryDreamRunning,
            showSyncMarksOption = true,
            onDismiss = {
                showMigrateDialog = false
                migrationPreview = null
            },
            onConfirm = { strategy, syncMarks ->
                migrating = true
                scope.launch {
                    val outcome = runCatching { vm.migrateToGlobal(strategy, syncMarks) }
                    migrating = false
                    showMigrateDialog = false
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

@Composable
private fun MemoryScopeBanner(
    isGlobal: Boolean,
    privateMemoryCount: Int,
    globalMemoryAssistantCount: Int,
    onMigrateToGlobal: () -> Unit,
) {
    CardGroup {
        item(
            leadingContent = {
                Icon(
                    imageVector = if (isGlobal) HugeIcons.Globe02 else HugeIcons.Brain02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text(
                    stringResource(
                        if (isGlobal) R.string.memory_scope_global_label else R.string.memory_scope_assistant_label
                    )
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (isGlobal) {
                            stringResource(R.string.memory_scope_global_hint, globalMemoryAssistantCount)
                        } else {
                            stringResource(R.string.memory_scope_assistant_hint)
                        }
                    )
                    if (isGlobal && privateMemoryCount > 0) {
                        Text(
                            text = stringResource(R.string.memory_private_preserved_notice, privateMemoryCount),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            trailingContent = if (privateMemoryCount > 0) {
                {
                    if (isGlobal) {
                        FilledTonalButton(onClick = onMigrateToGlobal) {
                            Text(stringResource(R.string.memory_migrate_short_action))
                        }
                    } else {
                        TextButton(onClick = onMigrateToGlobal) {
                            Text(stringResource(R.string.memory_migrate_short_action))
                        }
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun MemorySettingsTab(
    assistant: Assistant,
    privateMemoryCount: Int,
    globalMemoryCount: Int,
    onUpdateAssistant: (Assistant) -> Unit,
    onMigrateToGlobal: () -> Unit,
    onOpenGlobalMemory: () -> Unit,
    onOpenDreamSettings: () -> Unit,
) {
    var showTimeReminderIntervalDialog by remember(assistant.id) { mutableStateOf(false) }
    var timeReminderIntervalInput by remember(assistant.id) { mutableStateOf("") }

    if (showTimeReminderIntervalDialog) {
        val interval = timeReminderIntervalInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { showTimeReminderIntervalDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeReminderIntervalInput,
                    onValueChange = { timeReminderIntervalInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = interval != null,
                    onClick = {
                        interval?.let {
                            onUpdateAssistant(assistant.copy(timeReminderIntervalMinutes = it))
                        }
                        showTimeReminderIntervalDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeReminderIntervalDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.sm, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            switchItem(
                checked = assistant.enableMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableMemory = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
            )
            switchItem(
                checked = assistant.useGlobalMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            useGlobalMemory = it
                        )
                    )
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text(stringResource(R.string.assistant_page_global_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
            )
            if (privateMemoryCount > 0) {
                item(
                    onClick = onMigrateToGlobal,
                    leadingContent = { Icon(HugeIcons.FolderTransfer, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.memory_migrate_to_global_button)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (assistant.useGlobalMemory) {
                                    R.string.memory_private_preserved_notice
                                } else {
                                    R.string.memory_migrate_from_private_desc
                                },
                                privateMemoryCount,
                            )
                        )
                    },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                )
            }
            item(
                onClick = onOpenGlobalMemory,
                leadingContent = { Icon(HugeIcons.Globe02, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.global_memory_manage_entry)) },
                supportingContent = {
                    Text(stringResource(R.string.memory_file_count_summary, globalMemoryCount))
                },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
            )
            switchItem(
                checked = assistant.autoConsolidateMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            autoConsolidateMemory = it
                        )
                    )
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text(stringResource(R.string.assistant_page_memory_auto_dream_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_memory_auto_dream)) },
            )
            item(
                onClick = onOpenDreamSettings,
                supportingContent = { Text(stringResource(R.string.setting_memory_dream_entry_desc)) },
                headlineContent = { Text(stringResource(R.string.setting_memory_dream_title)) },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
            )
            switchItem(
                checked = assistant.enableRecentChatsReference,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableRecentChatsReference = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
            )
        }

        CardGroup {
            switchItem(
                checked = assistant.enableTimeReminder,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableTimeReminder = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
            )
            if (assistant.enableTimeReminder) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                    trailingContent = {
                        Text(
                            text = stringResource(
                                R.string.assistant_page_time_reminder_interval_value,
                                assistant.timeReminderIntervalMinutes
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        timeReminderIntervalInput = assistant.timeReminderIntervalMinutes.toString()
                        showTimeReminderIntervalDialog = true
                    },
                )
            }
        }
    }
}
