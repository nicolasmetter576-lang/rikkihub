package me.rerere.rikkihub.ui.pages.assistant.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.EntryPlacement
import me.rerere.rikkihub.data.model.contextUnlimited
import me.rerere.rikkihub.data.model.depth
import me.rerere.rikkihub.data.model.isPlainPromptEntry
import me.rerere.rikkihub.data.model.newPromptTextEntry
import me.rerere.rikkihub.data.model.order
import me.rerere.rikkihub.data.model.placement
import me.rerere.rikkihub.data.model.textEntries
import me.rerere.rikkihub.data.model.withContextSize
import me.rerere.rikkihub.data.model.withEntryAdded
import me.rerere.rikkihub.data.model.withEntryEnabled
import me.rerere.rikkihub.data.model.withEntryMovedTo
import me.rerere.rikkihub.data.model.withEntryRemoved
import me.rerere.rikkihub.data.model.withEntryUpdated
import me.rerere.rikkihub.data.model.withOptions
import me.rerere.rikkihub.ui.components.prompt.Hint
import me.rerere.rikkihub.ui.components.prompt.IntField
import me.rerere.rikkihub.ui.components.prompt.PromptEntryFields
import me.rerere.rikkihub.ui.components.prompt.PromptEntryScope
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.AssemblyOptions
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptMarkers
import me.rerere.tavern.prompt.PromptPreset
import sh.calvin.reorderable.ReorderableColumn

/**
 * 助手的提示词布局（酒馆 Prompt Manager 的布局）：按发送顺序列出条目，可拖动排序。
 * 点条目或右侧箭头展开内容输入框；长按条目打开高级设置（名称、角色、位置、发送时机、保留原文，以及上移下移和删除）。
 *
 * 酒馆模式开时列出全部条目（标记由应用自动填入，只能开关和挪动），下面是组装选项；
 * 关时只列主提示词和用户自己加的条目（[isPlainPromptEntry]），其余条目和选项原样留在预设里，发送时也不用（见 `plainLayout`）。
 *
 * @param modelContextLength 助手所用模型的上下文长度，只在设置上下文长度时作参考显示
 */
@Composable
fun PromptLayoutEditor(
    assistant: Assistant,
    modelContextLength: Int?,
    onUpdate: (Assistant) -> Unit,
) {
    val preset = assistant.promptPreset
    val tavernMode = assistant.tavernMode
    val entries = assistant.visiblePromptEntries()
    val haptic = LocalHapticFeedback.current
    fun change(next: PromptPreset) = onUpdate(assistant.copy(promptPreset = next))

    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val deleting = entries.firstOrNull { it.identifier == pendingDelete }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (tavernMode) R.string.prompt_layout_hint else R.string.prompt_layout_hint_plain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Hint(R.string.prompt_layout_gesture_hint)
        ReorderableColumn(
            list = entries,
            onSettle = { from, to -> change(preset.withEntryMovedTo(entries[from].identifier, entries[to].identifier)) },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { index, entry, isDragging ->
            key(entry.identifier) {
                ReorderableItem {
                    LayoutEntryCard(
                        entry = entry,
                        tavernMode = tavernMode,
                        isFirst = index == 0,
                        isLast = index == entries.lastIndex,
                        modifier = Modifier.scale(if (isDragging) 0.97f else 1f),
                        dragHandle = Modifier
                            .size(48.dp)
                            .draggableHandle(
                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                            ),
                        onEnabled = { change(preset.withEntryEnabled(entry.identifier, it)) },
                        // 按列表里看得见的邻居挪：酒馆模式关时隐藏的标记夹在中间，按全局顺序挪一位可能看不出变化
                        onMove = { delta ->
                            entries.getOrNull(index + delta)?.let { target -> change(preset.withEntryMovedTo(entry.identifier, target.identifier)) }
                        },
                        onChange = { transform -> change(preset.withEntryUpdated(entry.identifier, transform)) },
                        onDelete = { pendingDelete = entry.identifier },
                    )
                }
            }
        }
        val newName = stringResource(R.string.prompt_layout_new_entry)
        Button(
            onClick = { change(preset.withEntryAdded(newPromptTextEntry(newName))) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(HugeIcons.Add01, null)
            Text(stringResource(R.string.prompt_layout_add), modifier = Modifier.padding(start = 8.dp))
        }
        if (tavernMode) {
            AssemblyOptionsCard(
                options = preset.options,
                modelContextLength = modelContextLength,
                onChange = { transform -> change(preset.withOptions(transform)) },
            )
        }
    }

    RikkaConfirmDialog(
        show = deleting != null,
        title = stringResource(R.string.prompt_layout_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleting?.let { change(preset.withEntryRemoved(it.identifier)) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    ) {
        Text(stringResource(R.string.prompt_layout_delete_message, deleting?.let { entryTitle(it) }.orEmpty()))
    }
}

/** 布局里给用户看的条目：酒馆模式开时是全部，关时只有主提示词和用户自己加的（[isPlainPromptEntry]）。 */
internal fun Assistant.visiblePromptEntries(): List<PromptEntry> =
    promptPreset.layout().entries.let { all -> if (tavernMode) all else all.filter { it.isPlainPromptEntry } }

/** 只有开关的条目列表（聊天「+」面板的提示词面板）：和布局编辑器同一批条目、同一份开关。 */
@Composable
internal fun PromptEntrySwitches(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    isCliSession: Boolean = false,
) {
    val entries = if (isCliSession) {
        assistant.promptPreset.textEntries()
    } else {
        assistant.visiblePromptEntries()
    }
    CardGroup {
        entries.forEach { entry ->
            item(
                headlineContent = { Text(entryTitle(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(entrySummary(entry), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { onUpdate(assistant.copy(promptPreset = assistant.promptPreset.withEntryEnabled(entry.identifier, it))) },
                    )
                },
            )
        }
    }
}

/**
 * 布局里的一个条目。标题行：拖动把手、名称与摘要、开关、展开箭头（标记没有内容，没有箭头）。
 * 点标题行或箭头只展开一个大的内容输入框；长按标题行（标记点一下也行）打开 [EntryAdvancedSheet]。
 */
@Composable
private fun LayoutEntryCard(
    entry: PromptEntry,
    tavernMode: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier,
    dragHandle: Modifier,
    onEnabled: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onChange: ((PromptEntry) -> PromptEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(entry.identifier) { mutableStateOf(false) }
    var advanced by rememberSaveable(entry.identifier) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val isMarker = entry.marker == true
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                Box(modifier = dragHandle, contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.DragDropVertical, contentDescription = stringResource(R.string.prompt_layout_drag))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClickLabel = stringResource(if (isMarker) R.string.prompt_layout_advanced else R.string.prompt_layout_expand),
                            onLongClickLabel = stringResource(R.string.prompt_layout_advanced),
                            onClick = { if (isMarker) advanced = true else expanded = !expanded },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                advanced = true
                            },
                        )
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = entryTitle(entry),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entrySummary(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = entry.enabled, onCheckedChange = onEnabled, modifier = Modifier.padding(horizontal = 8.dp))
                if (!isMarker) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            stringResource(if (expanded) R.string.prompt_layout_collapse else R.string.prompt_layout_expand),
                        )
                    }
                }
            }
            if (expanded && !isMarker) {
                CommitOnDoneTextField(
                    value = entry.content.orEmpty(),
                    onCommit = { value -> onChange { it.copy(content = value) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    minLines = 8,
                    maxLines = 20,
                    placeholder = { Text(stringResource(R.string.prompt_layout_entry_content)) },
                )
            }
        }
    }

    if (advanced) {
        EntryAdvancedSheet(
            entry = entry,
            tavernMode = tavernMode,
            isFirst = isFirst,
            isLast = isLast,
            onMove = onMove,
            onChange = onChange,
            onDelete = {
                advanced = false
                onDelete()
            },
            onDismiss = { advanced = false },
        )
    }
}

/**
 * 条目的高级设置（长按打开）：名称、角色、位置、发送时机，主提示词与历史之后的指令在酒馆模式下还有「保留原文」；
 * 底部是上移、下移和删除。标记只有上移下移。内容不在这里，在卡片里展开编辑。
 */
@Composable
private fun EntryAdvancedSheet(
    entry: PromptEntry,
    tavernMode: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onMove: (Int) -> Unit,
    onChange: ((PromptEntry) -> PromptEntry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isMarker = entry.marker == true
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entryTitle(entry),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isMarker) {
                Hint(R.string.prompt_layout_marker_desc)
            } else {
                PromptEntryFields(
                    entry = entry,
                    scope = PromptEntryScope.LAYOUT,
                    onChange = onChange,
                    showContent = false,
                    showForbidOverrides = tavernMode,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(HugeIcons.ArrowUp01, null)
                    Text(stringResource(R.string.prompt_layout_move_up), modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(HugeIcons.ArrowDown01, null)
                    Text(stringResource(R.string.prompt_layout_move_down), modifier = Modifier.padding(start = 4.dp))
                }
                Box(modifier = Modifier.weight(1f))
                if (!isMarker) {
                    TextButton(onClick = onDelete) {
                        Icon(HugeIcons.Delete01, null)
                        Text(stringResource(R.string.prompt_layout_delete), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

/** 条目显示名：内置条目用本地化名称，其余用条目自己的名字。 */
@Composable
private fun entryTitle(entry: PromptEntry): String {
    val builtin = when {
        entry.marker == true -> when (entry.identifier) {
            "worldInfoBefore" -> R.string.prompt_layout_marker_world_info_before
            "worldInfoAfter" -> R.string.prompt_layout_marker_world_info_after
            "dialogueExamples" -> R.string.prompt_layout_marker_dialogue_examples
            "chatHistory" -> R.string.prompt_layout_marker_chat_history
            PromptMarkers.RIKKIHUB_CONTEXT -> R.string.prompt_layout_marker_context
            PromptMarkers.PERSONA_DESCRIPTION -> R.string.prompt_layout_marker_persona
            PromptMarkers.CHAR_DESCRIPTION -> R.string.prompt_layout_marker_char_description
            PromptMarkers.CHAR_PERSONALITY -> R.string.prompt_layout_marker_char_personality
            PromptMarkers.SCENARIO -> R.string.prompt_layout_marker_scenario
            else -> null
        }
        entry.identifier == PromptMarkers.MAIN && entry.name == "Main Prompt" -> R.string.prompt_layout_entry_main
        entry.identifier == PromptMarkers.JAILBREAK && entry.name == "Post-History Instructions" -> R.string.prompt_layout_entry_post_history
        else -> null
    }
    return builtin?.let { stringResource(it) } ?: entry.name?.takeIf { it.isNotBlank() } ?: entry.identifier
}

@Composable
private fun entrySummary(entry: PromptEntry): String {
    if (entry.marker == true) return stringResource(R.string.prompt_layout_marker)
    val role = stringResource(
        when (entry.role ?: PromptRole.SYSTEM) {
            PromptRole.SYSTEM -> R.string.prompt_layout_role_system
            PromptRole.USER -> R.string.prompt_layout_role_user
            PromptRole.ASSISTANT -> R.string.prompt_layout_role_assistant
        }
    )
    val where = if (entry.placement == EntryPlacement.IN_CHAT) {
        stringResource(R.string.prompt_layout_summary_in_chat, role, entry.depth, entry.order)
    } else {
        stringResource(R.string.prompt_layout_summary_relative, role)
    }
    val preview = entry.content.orEmpty().trim().replace(Regex("\\s+"), " ").take(60)
    return if (preview.isEmpty()) where else "$where\n$preview"
}

/** 组装选项：只列在 rikkihub 里真有效果的几项；其余选项（示例对话、角色卡格式……）随角色卡一起到来，现有的值原样保留。 */
@Composable
private fun AssemblyOptionsCard(
    options: AssemblyOptions,
    modelContextLength: Int?,
    onChange: ((AssemblyOptions) -> AssemblyOptions) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.prompt_layout_options_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null)
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SwitchRow(
                        title = R.string.prompt_layout_context_unlimited,
                        description = R.string.prompt_layout_context_unlimited_desc,
                        checked = options.contextUnlimited,
                        onCheckedChange = { unlimited ->
                            // 关掉「不限制」时先落在模型的上下文长度上（没有就取一个常见值），用户再改
                            onChange { it.withContextSize(if (unlimited) null else limitedStart(modelContextLength)) }
                        },
                    )
                    if (!options.contextUnlimited) {
                        IntField(
                            value = options.contextSize,
                            onCommit = { value -> onChange { it.withContextSize(value) } },
                            label = stringResource(R.string.prompt_layout_context_size),
                            modifier = Modifier.fillMaxWidth(),
                            min = 1,
                            supportingText = modelContextLength?.let { stringResource(R.string.prompt_layout_context_model, java.text.NumberFormat.getIntegerInstance().format(it)) },
                        )
                        IntField(
                            value = options.responseTokens,
                            onCommit = { value -> onChange { it.copy(responseTokens = value) } },
                            label = stringResource(R.string.prompt_layout_response_tokens),
                            modifier = Modifier.fillMaxWidth(),
                            min = 0,
                            supportingText = stringResource(R.string.prompt_layout_response_tokens_desc),
                        )
                    }
                    SwitchRow(
                        title = R.string.prompt_layout_squash,
                        description = R.string.prompt_layout_squash_desc,
                        checked = options.squashSystemMessages,
                        onCheckedChange = { on -> onChange { it.copy(squashSystemMessages = on) } },
                    )
                    CommitOnDoneTextField(
                        value = options.newChatPrompt,
                        onCommit = { value -> onChange { it.copy(newChatPrompt = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.prompt_layout_new_chat_prompt)) },
                        supportingText = { Text(stringResource(R.string.prompt_layout_new_chat_prompt_desc)) },
                    )
                    CommitOnDoneTextField(
                        value = options.wiFormat,
                        onCommit = { value -> onChange { it.copy(wiFormat = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.prompt_layout_wi_format)) },
                        supportingText = { Text(stringResource(R.string.prompt_layout_wi_format_desc)) },
                    )
                }
            }
        }
    }
}

private const val DEFAULT_LIMITED_CONTEXT = 32_000

/** 关掉「不限制」时的起点：模型自己的上下文长度；模型长度未知或本身就是「不限」的默认值（100 万）时取一个常见值。 */
private fun limitedStart(modelContextLength: Int?): Int =
    modelContextLength?.takeIf { it in 1 until AssistantPresets.UNLIMITED_CONTEXT } ?: DEFAULT_LIMITED_CONTEXT

@Composable
private fun SwitchRow(title: Int, description: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
