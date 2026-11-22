package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.UIAvatar
import me.rerere.rikkihub.ui.theme.extendColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import kotlin.uuid.Uuid

internal data class AssistantGridSlot(
    val assistant: Assistant,
    val span: Int,
    /** 是否因空位补齐而临时拉满整行（非用户显式开启 cardWide） */
    val autoExpanded: Boolean,
    /** 同行任意一张卡有次级文本（备注或停用标记）时统一行高，保证左右底边对齐 */
    val rowHasNote: Boolean,
)

internal fun List<Assistant>.updateCardAppearance(id: Uuid, note: String, cardWide: Boolean): List<Assistant> =
    map { if (it.id == id) it.copy(cardNote = note, cardWide = cardWide) else it }

@Composable
internal fun assistantCardSummary(assistant: Assistant): String {
    val wideLabel = if (assistant.cardWide) stringResource(R.string.assistant_page_card_wide) else null
    val noteText = assistant.cardNote.trim().ifEmpty { null }
    return listOfNotNull(wideLabel, noteText).joinToString(" · ")
}

internal fun Assistant.hasCardSubline(includeDisabledBadge: Boolean = false): Boolean =
    cardNote.isNotBlank() || (includeDisabledBadge && !enabled)

/**
 * 规划双栏助手网格的每张卡片跨度与行对齐：
 * - 用户标记为 [Assistant.cardWide] 的助手占整行（span = 2）；
 * - 任意行尾落单的短卡（紧邻长卡，或处于列表末尾）自动补位占满整行（span = 2, autoExpanded = true），不修改原始字段；
 * - 拖拽期间通过 [frozenSpans] 锁定各卡宽度，避免长按移动过程中卡宽跳变导致抖动。
 */
internal fun planAssistantGrid(
    assistants: List<Assistant>,
    frozenSpans: Map<Uuid, Int>? = null,
    showDisabledBadge: Boolean = false,
): List<AssistantGridSlot> {
    if (assistants.isEmpty()) return emptyList()
    val result = ArrayList<AssistantGridSlot>(assistants.size)
    var index = 0
    while (index < assistants.size) {
        val left = assistants[index]
        val next = assistants.getOrNull(index + 1)
        val leftSpan = if (frozenSpans != null) {
            frozenSpans[left.id] ?: if (left.cardWide) 2 else 1
        } else {
            if (left.cardWide || next == null || next.cardWide) 2 else 1
        }

        if (leftSpan >= 2 || next == null) {
            result += AssistantGridSlot(
                assistant = left,
                span = leftSpan,
                autoExpanded = leftSpan == 2 && !left.cardWide,
                rowHasNote = left.hasCardSubline(showDisabledBadge),
            )
            index += 1
        } else {
            val rightSpan = if (frozenSpans != null) {
                frozenSpans[next.id] ?: if (next.cardWide) 2 else 1
            } else {
                if (next.cardWide) 2 else 1
            }
            if (rightSpan >= 2) {
                result += AssistantGridSlot(
                    assistant = left,
                    span = 1,
                    autoExpanded = false,
                    rowHasNote = left.hasCardSubline(showDisabledBadge),
                )
                index += 1
            } else {
                val rowHasNote = left.hasCardSubline(showDisabledBadge) ||
                    next.hasCardSubline(showDisabledBadge)
                result += AssistantGridSlot(
                    assistant = left,
                    span = 1,
                    autoExpanded = false,
                    rowHasNote = rowHasNote,
                )
                result += AssistantGridSlot(
                    assistant = next,
                    span = 1,
                    autoExpanded = false,
                    rowHasNote = rowHasNote,
                )
                index += 2
            }
        }
    }
    return result
}

@Composable
internal fun AssistantCardGrid(
    assistants: List<Assistant>,
    dragEnabled: Boolean,
    onClickAssistant: (Assistant) -> Unit,
    onOpenMenu: (AssistantGridSlot) -> Unit,
    onReorderAssistants: (movedId: Uuid, targetId: Uuid) -> Unit,
    modifier: Modifier = Modifier,
    selectedAssistantId: Uuid? = null,
    showDisabledBadge: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    onDraggingChange: (Boolean) -> Unit = {},
    avatarModifier: @Composable (Assistant) -> Modifier = { Modifier },
    cardContainerColor: @Composable (Assistant, Boolean) -> Color,
    cardContentColor: @Composable (Assistant, Boolean) -> Color,
) {
    val lazyGridState = rememberLazyGridState()
    var frozenSpans by remember { mutableStateOf<Map<Uuid, Int>?>(null) }
    val slots = remember(assistants, frozenSpans, showDisabledBadge) {
        planAssistantGrid(
            assistants = assistants,
            frozenSpans = frozenSpans,
            showDisabledBadge = showDisabledBadge,
        )
    }
    val currentSlots by rememberUpdatedState(slots)
    val currentDragEnabled by rememberUpdatedState(dragEnabled)
    val reorderableState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        if (currentDragEnabled) {
            val moved = (from.key as? Uuid) ?: currentSlots.getOrNull(from.index)?.assistant?.id
            val target = (to.key as? Uuid) ?: currentSlots.getOrNull(to.index)?.assistant?.id
            if (moved != null && target != null && moved != target) {
                onReorderAssistants(moved, target)
            }
        }
    }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            frozenSpans = null
        }
        onDraggingChange(reorderableState.isAnyItemDragging)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = lazyGridState,
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(
            items = slots,
            key = { it.assistant.id },
            span = { slot -> GridItemSpan(slot.span) },
        ) { slot ->
            val assistant = slot.assistant
            val selected = selectedAssistantId != null && assistant.id == selectedAssistantId
            ReorderableItem(
                state = reorderableState,
                key = assistant.id,
            ) { isDragging ->
                AssistantGridCard(
                    slot = slot,
                    selected = selected,
                    showDisabledBadge = showDisabledBadge,
                    onClick = { onClickAssistant(assistant) },
                    onOpenMenu = { onOpenMenu(slot) },
                    avatarModifier = avatarModifier(assistant),
                    containerColor = cardContainerColor(assistant, selected),
                    contentColor = cardContentColor(assistant, selected),
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .scale(if (isDragging) 0.96f else 1f)
                        .then(
                            if (dragEnabled) {
                                Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        frozenSpans = currentSlots.associate { it.assistant.id to it.span }
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        frozenSpans = null
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                )
                            } else {
                                Modifier
                            }
                        ),
                )
            }
        }
    }
}

@Composable
private fun AssistantGridCard(
    slot: AssistantGridSlot,
    selected: Boolean,
    showDisabledBadge: Boolean,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    avatarModifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val assistant = slot.assistant
    val defaultName = stringResource(R.string.assistant_page_default_assistant)
    val displayName = assistant.name.ifBlank { defaultName }
    val trimmedNote = assistant.cardNote.trim()
    val showDisabled = showDisabledBadge && !assistant.enabled
    val minHeight = if (slot.rowHasNote) 54.dp else 44.dp

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .alpha(if (assistant.enabled) 1f else 0.6f)
                .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UIAvatar(
                name = displayName,
                value = assistant.avatar,
                modifier = avatarModifier.size(30.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = if (slot.span >= 2) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (showDisabled || trimmedNote.isNotEmpty()) {
                    val noteColor = if (selected) {
                        contentColor.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val disabledText = if (showDisabled) stringResource(R.string.assistant_page_disabled_tag) else ""
                    val warningColor = MaterialTheme.extendColors.orange8
                    val subline = remember(showDisabled, disabledText, warningColor, trimmedNote) {
                        buildAnnotatedString {
                            if (showDisabled) {
                                withStyle(SpanStyle(color = warningColor)) {
                                    append(disabledText)
                                }
                                if (trimmedNote.isNotEmpty()) {
                                    append(" · ")
                                }
                            }
                            append(trimmedNote)
                        }
                    }
                    Text(
                        text = subline,
                        style = MaterialTheme.typography.labelSmall,
                        color = noteColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.assistant_page_actions),
                        onClick = onOpenMenu,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.assistant_page_actions),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun AssistantCardConfigDialog(
    assistant: Assistant,
    autoExpanded: Boolean,
    onDismiss: () -> Unit,
    onSave: (note: String, cardWide: Boolean) -> Unit,
    onOpenDetail: (() -> Unit)? = null,
) {
    val defaultName = stringResource(R.string.assistant_page_default_assistant)
    val displayName = assistant.name.ifBlank { defaultName }
    var note by remember(assistant.id) { mutableStateOf(assistant.cardNote) }
    var cardWide by remember(assistant.id) { mutableStateOf(assistant.cardWide) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                UIAvatar(
                    name = displayName,
                    value = assistant.avatar,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = displayName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onOpenDetail != null) {
                    IconButton(onClick = onOpenDetail) {
                        Icon(
                            imageVector = HugeIcons.Edit03,
                            contentDescription = stringResource(R.string.assistant_page_title),
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.assistant_page_card_note)) },
                    placeholder = { Text(stringResource(R.string.assistant_page_card_note_placeholder)) },
                    trailingIcon = {
                        if (note.isNotEmpty()) {
                            IconButton(onClick = { note = "" }) {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { cardWide = !cardWide }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_page_card_wide),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.assistant_page_card_wide_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = cardWide,
                        onCheckedChange = { cardWide = it },
                    )
                }

                if (autoExpanded && !cardWide) {
                    Text(
                        text = stringResource(R.string.assistant_page_card_auto_wide_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(note.trim(), cardWide)
                }
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.assistant_page_cancel))
            }
        },
    )
}
