package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.ui.components.ui.UIAvatar
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.hooks.rememberAssistantState
import kotlin.uuid.Uuid

@Composable
fun AssistantPicker(
    settings: Settings,
    onSelectAssistant: (Assistant) -> Unit,
    onReorderAssistants: (movedId: Uuid, targetId: Uuid) -> Unit,
    onUpdateCardAppearance: (assistantId: Uuid, note: String, cardWide: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClickSetting: () -> Unit,
) {
    val state = rememberAssistantState(settings, onSelectAssistant)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var showPicker by remember { mutableStateOf(false) }

    NavigationDrawerItem(
        icon = {
            Icon(HugeIcons.LookTop, contentDescription = null)
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))

                UIAvatar(
                    name = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    value = state.currentAssistant.avatar,
                    onClick = onClickSetting
                )
            }
        },
        onClick = {
            showPicker = true
        },
        modifier = modifier,
        selected = false,
    )

    if (showPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = state.currentAssistant,
            onAssistantSelected = { assistant ->
                showPicker = false
                state.setSelectAssistant(assistant)
            },
            onReorderAssistants = onReorderAssistants,
            onUpdateCardAppearance = onUpdateCardAppearance,
            onDismiss = {
                showPicker = false
            }
        )
    }
}

@Composable
private fun AssistantPickerSheet(
    settings: Settings,
    currentAssistant: Assistant,
    onAssistantSelected: (Assistant) -> Unit,
    onReorderAssistants: (movedId: Uuid, targetId: Uuid) -> Unit,
    onUpdateCardAppearance: (assistantId: Uuid, note: String, cardWide: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()

    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    var isDraggingAny by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Pair<Uuid, Boolean>?>(null) }

    // 停用的助手不列（正在用的除外），再按选中的标签过滤
    val filteredAssistants = remember(settings.assistants, currentAssistant.id, selectedTagIds) {
        settings.assistants.filter { assistant ->
            (assistant.enabled || assistant.id == currentAssistant.id) &&
                (selectedTagIds.isEmpty() || assistant.tags.any { tagId -> tagId in selectedTagIds })
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 拖动排序期间禁用下拉手势，避免向下拖卡片时把抽屉关掉
        sheetGesturesEnabled = !isDraggingAny,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 标签过滤器
            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(settings.assistantTags, key = { tag -> tag.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }

            // 双栏助手网格（无筛选时长按拖拽排序）
            AssistantCardGrid(
                assistants = filteredAssistants,
                selectedAssistantId = currentAssistant.id,
                dragEnabled = selectedTagIds.isEmpty(),
                showDisabledBadge = false,
                onClickAssistant = onAssistantSelected,
                onOpenMenu = { slot ->
                    editingSlot = slot.assistant.id to slot.autoExpanded
                },
                onReorderAssistants = onReorderAssistants,
                onDraggingChange = { isDraggingAny = it },
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                cardContainerColor = { _, selected ->
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                },
                cardContentColor = { _, selected ->
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }

    val navController = LocalNavController.current
    editingSlot?.let { (assistantId, autoExpanded) ->
        val target = settings.assistants.find { it.id == assistantId }
        if (target != null) {
            AssistantCardConfigDialog(
                assistant = target,
                autoExpanded = autoExpanded,
                onDismiss = { editingSlot = null },
                onSave = { note, cardWide ->
                    onUpdateCardAppearance(target.id, note, cardWide)
                    editingSlot = null
                },
                onOpenDetail = {
                    editingSlot = null
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                        navController.navigate(Screen.AssistantDetail(target.id.toString()))
                    }
                },
            )
        } else {
            editingSlot = null
        }
    }
}
