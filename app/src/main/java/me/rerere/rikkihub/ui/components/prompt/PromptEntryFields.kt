package me.rerere.rikkihub.ui.components.prompt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.model.EntryPlacement
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.depth
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.order
import me.rerere.rikkihub.data.model.placement
import me.rerere.rikkihub.data.model.triggers
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.rikkihub.data.model.withPlacement
import me.rerere.rikkihub.data.model.withTriggers
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.PromptEntry

/** 条目在哪里编辑：助手自己的布局，还是全局提示词库（相对条目多一个锚点）。 */
enum class PromptEntryScope { LAYOUT, LIBRARY }

/** rikkihub 里实际会出现的生成类型；酒馆的续写、扮演、滑动在这里没有对应操作，但条目里已有的值照样保留。 */
private val VisibleTriggers = listOf(
    GenerationType.NORMAL to R.string.prompt_layout_trigger_normal,
    GenerationType.REGENERATE to R.string.prompt_layout_trigger_regenerate,
    GenerationType.QUIET to R.string.prompt_layout_trigger_quiet,
)

/**
 * 提示词条目的编辑字段：名称、角色、位置（相对/聊天内，聊天内带深度和顺序）、触发类型、内容。
 * 助手布局和提示词库共用，库里的相对条目多一个「放在」锚点，顺序字段叫「优先级」。
 *
 * 改动以 `(PromptEntry) -> PromptEntry` 的形式交给调用方，调用方负责写回对应位置。
 *
 * @param showContent 助手布局的高级设置面板不带内容（内容在条目卡片里展开编辑）
 * @param showForbidOverrides 「保留原文」只在酒馆模式下有意义（角色卡才会替换主提示词）
 */
@Composable
fun PromptEntryFields(
    entry: PromptEntry,
    scope: PromptEntryScope,
    onChange: ((PromptEntry) -> PromptEntry) -> Unit,
    modifier: Modifier = Modifier,
    showName: Boolean = true,
    showContent: Boolean = true,
    showForbidOverrides: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showName) {
            CommitOnDoneTextField(
                value = entry.name.orEmpty(),
                onCommit = { value -> onChange { it.copy(name = value) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.prompt_layout_entry_name)) },
            )
        }
        RoleChips(role = entry.role ?: PromptRole.SYSTEM, onSelect = { role -> onChange { it.copy(role = role) } })

        SectionLabel(R.string.prompt_layout_position)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = entry.placement == EntryPlacement.RELATIVE,
                onClick = { onChange { it.withPlacement(EntryPlacement.RELATIVE) } },
                label = { Text(stringResource(R.string.prompt_layout_position_relative)) },
            )
            FilterChip(
                selected = entry.placement == EntryPlacement.IN_CHAT,
                onClick = { onChange { it.withPlacement(EntryPlacement.IN_CHAT) } },
                label = { Text(stringResource(R.string.prompt_layout_position_in_chat)) },
            )
        }
        if (entry.placement == EntryPlacement.IN_CHAT) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                IntField(
                    value = entry.depth,
                    onCommit = { value -> onChange { it.withPlacement(EntryPlacement.IN_CHAT, depth = value) } },
                    label = stringResource(R.string.prompt_layout_depth),
                    modifier = Modifier.weight(1f),
                    min = 0,
                )
                IntField(
                    value = entry.order,
                    onCommit = { value -> onChange { it.withPlacement(EntryPlacement.IN_CHAT, order = value) } },
                    label = stringResource(R.string.prompt_layout_order),
                    modifier = Modifier.weight(1f),
                )
            }
            Hint(R.string.prompt_layout_depth_hint)
            Hint(R.string.prompt_layout_order_hint)
        } else if (scope == PromptEntryScope.LIBRARY) {
            AnchorChips(entry = entry, onChange = onChange)
            IntField(
                value = entry.order,
                onCommit = { value -> onChange { it.copy(injectionOrder = value) } },
                label = stringResource(R.string.prompt_layout_priority),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(R.string.prompt_layout_priority_hint)
        }

        TriggerChips(entry = entry, onChange = onChange)

        if (scope == PromptEntryScope.LAYOUT && showForbidOverrides && entry.systemPrompt == true) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_layout_forbid_overrides), style = MaterialTheme.typography.bodyMedium)
                    Hint(R.string.prompt_layout_forbid_overrides_desc)
                }
                Switch(
                    checked = entry.forbidOverrides == true,
                    onCheckedChange = { on -> onChange { it.copy(forbidOverrides = on) } },
                )
            }
        }

        if (showContent) {
            CommitOnDoneTextField(
                value = entry.content.orEmpty(),
                onCommit = { value -> onChange { it.copy(content = value) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 15,
                label = { Text(stringResource(R.string.prompt_layout_entry_content)) },
            )
        }
    }
}

@Composable
fun RoleChips(role: PromptRole, onSelect: (PromptRole) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            PromptRole.SYSTEM to R.string.prompt_layout_role_system,
            PromptRole.USER to R.string.prompt_layout_role_user,
            PromptRole.ASSISTANT to R.string.prompt_layout_role_assistant,
        ).forEach { (value, label) ->
            FilterChip(selected = role == value, onClick = { onSelect(value) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun AnchorChips(entry: PromptEntry, onChange: ((PromptEntry) -> PromptEntry) -> Unit) {
    val current = entry.libraryAnchor ?: LibraryEntryMeta.AFTER_SYSTEM
    SectionLabel(R.string.prompt_layout_anchor)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            LibraryEntryMeta.BEFORE_SYSTEM to R.string.prompt_layout_anchor_before_system,
            LibraryEntryMeta.AFTER_SYSTEM to R.string.prompt_layout_anchor_after_system,
            LibraryEntryMeta.BEFORE_HISTORY to R.string.prompt_layout_anchor_before_history,
        ).forEach { (anchor, label) ->
            FilterChip(
                selected = current == anchor,
                onClick = { onChange { it.withLibraryMeta(anchor = anchor) } },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun TriggerChips(entry: PromptEntry, onChange: ((PromptEntry) -> PromptEntry) -> Unit) {
    val selected = entry.triggers
    SectionLabel(R.string.prompt_layout_triggers)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VisibleTriggers.forEach { (type, label) ->
            FilterChip(
                selected = type in selected,
                onClick = { onChange { it.withTriggers(if (type in it.triggers) it.triggers - type else it.triggers + type) } },
                label = { Text(stringResource(label)) },
            )
        }
    }
    Hint(R.string.prompt_layout_triggers_hint)
}

@Composable
fun SectionLabel(label: Int, modifier: Modifier = Modifier) {
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, modifier = modifier)
}

@Composable
fun Hint(text: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 整数输入框：失焦或点完成时提交；不是整数或小于 [min] 时标红且不提交。 */
@Composable
fun IntField(
    value: Int,
    onCommit: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    min: Int = Int.MIN_VALUE,
    supportingText: String? = null,
) {
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= min }
    CommitOnDoneTextField(
        value = value.toString(),
        onCommit = { text -> parse(text)?.let(onCommit) },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = { parse(it) == null },
        supportingText = supportingText?.let { hint -> { Text(hint) } },
    )
}
