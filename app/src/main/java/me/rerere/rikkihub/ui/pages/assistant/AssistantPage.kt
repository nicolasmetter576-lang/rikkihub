package me.rerere.rikkihub.ui.pages.assistant

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.ui.components.ai.AssistantCardConfigDialog
import me.rerere.rikkihub.ui.components.ai.AssistantCardGrid
import me.rerere.rikkihub.ui.components.ai.assistantCardSummary
import me.rerere.rikkihub.ui.components.ai.updateCardAppearance
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.FormItem
import me.rerere.rikkihub.ui.components.ui.UIAvatar
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.hooks.EditState
import me.rerere.rikkihub.ui.hooks.EditStateContent
import me.rerere.rikkihub.ui.hooks.heroAnimation
import me.rerere.rikkihub.ui.hooks.useEditState
import me.rerere.rikkihub.ui.modifier.onClick
import me.rerere.rikkihub.ui.pages.assistant.detail.AssistantImporter
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.moveByKey
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid
import androidx.compose.foundation.lazy.items as lazyItems
import me.rerere.rikkihub.ui.components.ui.CompactListItem

@Composable
fun AssistantPage(vm: AssistantVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val createState = useEditState<Assistant> {
        vm.addAssistant(it)
    }
    val navController = LocalNavController.current

    // 搜索关键词状态
    var searchQuery by remember { mutableStateOf("") }
    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    // 操作菜单与卡片展示设置弹窗状态（存 id + 是否自动补位，避免快照过期）
    var actionSlot by remember { mutableStateOf<Pair<Uuid, Boolean>?>(null) }
    var configSlot by remember { mutableStateOf<Pair<Uuid, Boolean>?>(null) }

    // 根据搜索关键词和选中的标签过滤助手
    val filteredAssistants = remember(settings.assistants, selectedTagIds, searchQuery) {
        settings.assistants.filter { assistant ->
            val matchesSearch = searchQuery.isBlank() ||
                assistant.name.contains(searchQuery, ignoreCase = true)
            val matchesTags = selectedTagIds.isEmpty() ||
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            matchesSearch && matchesTags
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            createState.open(Assistant())
                        }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.assistant_page_add))
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(top = 8.dp)
                .consumeWindowInsets(it),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val isFiltering = selectedTagIds.isNotEmpty() || searchQuery.isNotBlank()

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // 标签过滤器
            AssistantTagsFilterRow(
                settings = settings,
                vm = vm,
                selectedTagIds = selectedTagIds,
                onUpdateSelectedTagIds = { ids ->
                    selectedTagIds = ids
                }
            )

            AssistantCardGrid(
                assistants = filteredAssistants,
                dragEnabled = !isFiltering,
                showDisabledBadge = true,
                onClickAssistant = { assistant ->
                    navController.navigate(Screen.AssistantDetail(id = assistant.id.toString()))
                },
                onOpenMenu = { slot ->
                    actionSlot = slot.assistant.id to slot.autoExpanded
                },
                onReorderAssistants = { moved, target ->
                    vm.updateSettings { latest ->
                        latest.copy(assistants = latest.assistants.moveByKey({ it.id }, moved, target))
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                avatarModifier = { assistant ->
                    Modifier.heroAnimation("assistant_${assistant.id}")
                },
                cardContainerColor = { _, _ -> CustomColors.listItemColors.containerColor },
                cardContentColor = { _, _ -> MaterialTheme.colorScheme.onSurface },
            )
        }
    }

    AssistantCreationSheet(createState)

    // 操作菜单 Bottom Sheet
    actionSlot?.let { (assistantId, autoExpanded) ->
        val assistant = settings.assistants.find { it.id == assistantId }
        if (assistant != null) {
            AssistantActionSheet(
                assistant = assistant,
                onDismiss = { actionSlot = null },
                onConfigureCard = {
                    actionSlot = null
                    configSlot = assistant.id to autoExpanded
                },
                onCopy = {
                    vm.copyAssistant(assistant)
                    actionSlot = null
                },
                onDelete = {
                    vm.removeAssistant(assistant)
                    actionSlot = null
                }
            )
        } else {
            actionSlot = null
        }
    }

    // 卡片备注与单双栏设置弹窗
    configSlot?.let { (assistantId, autoExpanded) ->
        val assistant = settings.assistants.find { it.id == assistantId }
        if (assistant != null) {
            AssistantCardConfigDialog(
                assistant = assistant,
                autoExpanded = autoExpanded,
                onDismiss = { configSlot = null },
                onSave = { note, cardWide ->
                    vm.updateSettings { latest ->
                        latest.copy(assistants = latest.assistants.updateCardAppearance(assistant.id, note, cardWide))
                    }
                    configSlot = null
                },
            )
        } else {
            configSlot = null
        }
    }
}

@Composable
private fun AssistantTagsFilterRow(
    settings: Settings,
    vm: AssistantVM,
    selectedTagIds: Set<Uuid>,
    onUpdateSelectedTagIds: (Set<Uuid>) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    if (settings.assistantTags.isNotEmpty()) {
        val tagsListState = rememberLazyListState()
        val tagsReorderableState = rememberReorderableLazyListState(tagsListState) { from, to ->
            val moved = settings.assistantTags.getOrNull(from.index)?.id
            val target = settings.assistantTags.getOrNull(to.index)?.id
            if (moved != null && target != null) {
                vm.updateSettings { latest ->
                    latest.copy(assistantTags = latest.assistantTags.moveByKey({ it.id }, moved, target))
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp),
            state = tagsListState
        ) {
            lazyItems(items = settings.assistantTags, key = { tag -> tag.id }) { tag ->
                ReorderableItem(
                    state = tagsReorderableState, key = tag.id
                ) { isDragging ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            onClick = {
                                onUpdateSelectedTagIds(
                                    if (tag.id in selectedTagIds) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                )
                            },
                            label = {
                                Text(tag.name)
                            },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantCreationSheet(
    state: EditState<Assistant>,
) {
    state.EditStateContent { assistant, update ->
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            dragHandle = {},
            sheetGesturesEnabled = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FormItem(
                        label = {
                            Text(stringResource(R.string.assistant_page_name))
                        },
                    ) {
                        OutlinedTextField(
                            value = assistant.name, onValueChange = {
                                update(
                                    assistant.copy(
                                        name = it
                                    )
                                )
                            }, modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AssistantImporter(
                        onUpdate = {
                            update(it)
                            state.confirm()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            state.dismiss()
                        }) {
                        Text(stringResource(R.string.assistant_page_cancel))
                    }
                    TextButton(
                        onClick = {
                            state.confirm()
                        }) {
                        Text(stringResource(R.string.assistant_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantActionSheet(
    assistant: Assistant,
    onDismiss: () -> Unit,
    onConfigureCard: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // 助手信息头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UIAvatar(
                    name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    value = assistant.avatar,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 卡片备注与单双栏设置
            val cardSummary = assistantCardSummary(assistant)
            CompactListItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_card_settings)) },
                supportingContent = if (cardSummary.isNotEmpty()) {
                    { Text(cardSummary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else {
                    null
                },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Edit03,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.onClick { onConfigureCard() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            // 创造助手不可克隆/删除（克隆出来不带创造工具；删除后会被设置自动补回）
            val isCreator = CreatorAssistantDefinition.isCreator(assistant.id)

            // 克隆选项
            if (!isCreator) ListItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_clone)) },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.onClick { onCopy() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            // 删除选项（仅非默认助手显示）
            if (assistant.id !in DEFAULT_ASSISTANTS_IDS && !isCreator) {
                CompactListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.assistant_page_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.onClick { showDeleteDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.assistant_page_delete)) },
            text = { Text(stringResource(R.string.assistant_page_delete_dialog_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
