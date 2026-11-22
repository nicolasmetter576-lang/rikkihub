package me.rerere.rikkihub.ui.components.prompt

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.model.TriState
import me.rerere.rikkihub.data.model.formatKeyList
import me.rerere.rikkihub.data.model.toBoolean
import me.rerere.rikkihub.data.model.triState
import me.rerere.rikkihub.data.model.triggerSet
import me.rerere.rikkihub.data.model.withPrimaryKeys
import me.rerere.rikkihub.data.model.withProbability
import me.rerere.rikkihub.data.model.withSecondaryKeys
import me.rerere.rikkihub.data.model.withTriggerSet
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.worldinfo.WI_MAX_SCAN_DEPTH
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoLogic
import me.rerere.tavern.worldinfo.WorldInfoPosition

/**
 * 世界书条目的编辑器，按组折叠：基本、触发、位置、递归、分组、计时、过滤。
 *
 * 只列在 rikkihub 里已经有效果的字段。依赖角色卡的几项（匹配角色描述/性格/场景/作者备注、角色过滤、示例对话位置）、
 * 向量化和自动化 ID 不显示，已有的值编辑时原样保留；等角色卡到来后再补。
 *
 * 改动以 `(WorldInfoEntry) -> WorldInfoEntry` 交给调用方，调用方负责按 uid 写回。
 */
@Composable
fun WorldInfoEntryEditor(
    entry: WorldInfoEntry,
    onChange: ((WorldInfoEntry) -> WorldInfoEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EditorSection(R.string.worldinfo_section_basic, initiallyOpen = true) { BasicSection(entry, onChange) }
        EditorSection(R.string.worldinfo_section_trigger) { TriggerSection(entry, onChange) }
        EditorSection(R.string.worldinfo_section_position) { PositionSection(entry, onChange) }
        EditorSection(R.string.worldinfo_section_recursion) { RecursionSection(entry, onChange) }
        EditorSection(R.string.worldinfo_section_group) { GroupSection(entry, onChange) }
        EditorSection(R.string.worldinfo_section_timing) { TimingSection(entry, onChange) }
        EditorSection(R.string.worldinfo_section_filter) { FilterSection(entry, onChange) }
    }
}

@Composable
private fun EditorSection(title: Int, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Column(modifier = Modifier.animateContentSize()) {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 12.dp),
        ) {
            Icon(if (open) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        }
        if (open) {
            Column(modifier = Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

private typealias Change = ((WorldInfoEntry) -> WorldInfoEntry) -> Unit

@Composable
private fun BasicSection(entry: WorldInfoEntry, onChange: Change) {
    CommitOnDoneTextField(
        value = entry.comment,
        onCommit = { value -> onChange { it.copy(comment = value) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.worldinfo_entry_title)) },
    )
    SwitchField(R.string.prompt_page_enabled, null, !entry.disable) { on -> onChange { it.copy(disable = !on) } }
    SwitchField(R.string.prompt_page_constant_active, R.string.prompt_page_constant_active_desc, entry.constant) { on ->
        onChange { it.copy(constant = on) }
    }
    CommitOnDoneTextField(
        value = formatKeyList(entry.key),
        onCommit = { value -> onChange { it.withPrimaryKeys(value) } },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 6,
        label = { Text(stringResource(R.string.prompt_page_keywords_label)) },
        supportingText = { Text(stringResource(R.string.worldinfo_keys_hint)) },
    )
    CommitOnDoneTextField(
        value = formatKeyList(entry.keysecondary),
        onCommit = { value -> onChange { it.withSecondaryKeys(value) } },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 6,
        label = { Text(stringResource(R.string.worldinfo_keys_secondary)) },
        supportingText = { Text(stringResource(R.string.worldinfo_keys_secondary_hint)) },
    )
    if (entry.keysecondary.isNotEmpty()) {
        SectionLabel(R.string.worldinfo_logic)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                WorldInfoLogic.AND_ANY to R.string.worldinfo_logic_and_any,
                WorldInfoLogic.AND_ALL to R.string.worldinfo_logic_and_all,
                WorldInfoLogic.NOT_ANY to R.string.worldinfo_logic_not_any,
                WorldInfoLogic.NOT_ALL to R.string.worldinfo_logic_not_all,
            ).forEach { (logic, label) ->
                FilterChip(
                    selected = entry.selectiveLogic == logic,
                    onClick = { onChange { it.copy(selectiveLogic = logic) } },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
    CommitOnDoneTextField(
        value = entry.content,
        onCommit = { value -> onChange { it.copy(content = value) } },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 20,
        label = { Text(stringResource(R.string.prompt_layout_entry_content)) },
    )
    IntField(
        value = entry.order,
        onCommit = { value -> onChange { it.copy(order = value) } },
        label = stringResource(R.string.worldinfo_order),
        modifier = Modifier.fillMaxWidth(),
        supportingText = stringResource(R.string.worldinfo_order_hint),
    )
}

@Composable
private fun TriggerSection(entry: WorldInfoEntry, onChange: Change) {
    IntField(
        value = entry.probability,
        onCommit = { value -> onChange { it.withProbability(value) } },
        label = stringResource(R.string.worldinfo_probability),
        modifier = Modifier.fillMaxWidth(),
        min = 0,
        supportingText = stringResource(R.string.worldinfo_probability_hint),
    )
    OptionalIntField(
        value = entry.scanDepth,
        onCommit = { value -> onChange { it.copy(scanDepth = value?.coerceIn(0, WI_MAX_SCAN_DEPTH)) } },
        label = stringResource(R.string.prompt_page_scan_depth),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(R.string.worldinfo_scan_depth_hint)
    TriStateField(R.string.prompt_page_case_sensitive, entry.caseSensitive.triState) { state -> onChange { it.copy(caseSensitive = state.toBoolean()) } }
    TriStateField(R.string.worldinfo_whole_words, entry.matchWholeWords.triState) { state -> onChange { it.copy(matchWholeWords = state.toBoolean()) } }
    SwitchField(R.string.worldinfo_ignore_budget, R.string.worldinfo_ignore_budget_desc, entry.ignoreBudget) { on ->
        onChange { it.copy(ignoreBudget = on) }
    }
}

@Composable
private fun PositionSection(entry: WorldInfoEntry, onChange: Change) {
    // 示例对话上/下只在条目已经是这个值时才列出（来自导入的酒馆世界书）
    val positions = buildList {
        add(WorldInfoPosition.BEFORE to R.string.worldinfo_pos_before)
        add(WorldInfoPosition.AFTER to R.string.worldinfo_pos_after)
        add(WorldInfoPosition.AN_TOP to R.string.worldinfo_pos_an_top)
        add(WorldInfoPosition.AN_BOTTOM to R.string.worldinfo_pos_an_bottom)
        add(WorldInfoPosition.AT_DEPTH to R.string.worldinfo_pos_depth)
        add(WorldInfoPosition.OUTLET to R.string.worldinfo_pos_outlet)
        if (entry.position == WorldInfoPosition.EM_TOP) add(WorldInfoPosition.EM_TOP to R.string.worldinfo_pos_em_top)
        if (entry.position == WorldInfoPosition.EM_BOTTOM) add(WorldInfoPosition.EM_BOTTOM to R.string.worldinfo_pos_em_bottom)
    }
    SectionLabel(R.string.worldinfo_position)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        positions.forEach { (position, label) ->
            FilterChip(
                selected = entry.position == position,
                onClick = { onChange { it.copy(position = position) } },
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (entry.position == WorldInfoPosition.AT_DEPTH) {
        IntField(
            value = entry.depth,
            onCommit = { value -> onChange { it.copy(depth = value) } },
            label = stringResource(R.string.prompt_layout_depth),
            modifier = Modifier.fillMaxWidth(),
            min = 0,
            supportingText = stringResource(R.string.prompt_layout_depth_hint),
        )
        SectionLabel(R.string.worldinfo_role)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                0 to R.string.prompt_layout_role_system,
                1 to R.string.prompt_layout_role_user,
                2 to R.string.prompt_layout_role_assistant,
            ).forEach { (role, label) ->
                FilterChip(
                    selected = (entry.role ?: 0) == role,
                    onClick = { onChange { it.copy(role = role) } },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
    if (entry.position == WorldInfoPosition.OUTLET) {
        CommitOnDoneTextField(
            value = entry.outletName,
            onCommit = { value -> onChange { it.copy(outletName = value.trim()) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.worldinfo_outlet_name)) },
            supportingText = { Text(stringResource(R.string.worldinfo_outlet_hint)) },
        )
    }
}

@Composable
private fun RecursionSection(entry: WorldInfoEntry, onChange: Change) {
    SwitchField(R.string.worldinfo_exclude_recursion, R.string.worldinfo_exclude_recursion_desc, entry.excludeRecursion) { on ->
        onChange { it.copy(excludeRecursion = on) }
    }
    SwitchField(R.string.worldinfo_prevent_recursion, R.string.worldinfo_prevent_recursion_desc, entry.preventRecursion) { on ->
        onChange { it.copy(preventRecursion = on) }
    }
    SwitchField(R.string.worldinfo_delay_recursion, R.string.worldinfo_delay_recursion_desc, entry.delayUntilRecursion > 0) { on ->
        onChange { it.copy(delayUntilRecursion = if (on) 1 else 0) }
    }
    if (entry.delayUntilRecursion > 0) {
        IntField(
            value = entry.delayUntilRecursion,
            onCommit = { value -> onChange { it.copy(delayUntilRecursion = value) } },
            label = stringResource(R.string.worldinfo_delay_recursion_level),
            modifier = Modifier.fillMaxWidth(),
            min = 1,
        )
    }
}

@Composable
private fun GroupSection(entry: WorldInfoEntry, onChange: Change) {
    CommitOnDoneTextField(
        value = entry.group,
        onCommit = { value -> onChange { it.copy(group = value.trim()) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.worldinfo_group)) },
        supportingText = { Text(stringResource(R.string.worldinfo_group_hint)) },
    )
    SwitchField(R.string.worldinfo_group_override, R.string.worldinfo_group_override_desc, entry.groupOverride) { on ->
        onChange { it.copy(groupOverride = on) }
    }
    IntField(
        value = entry.groupWeight,
        onCommit = { value -> onChange { it.copy(groupWeight = value) } },
        label = stringResource(R.string.worldinfo_group_weight),
        modifier = Modifier.fillMaxWidth(),
        min = 0,
    )
    TriStateField(R.string.worldinfo_group_scoring, entry.useGroupScoring.triState) { state -> onChange { it.copy(useGroupScoring = state.toBoolean()) } }
}

@Composable
private fun TimingSection(entry: WorldInfoEntry, onChange: Change) {
    OptionalIntField(
        value = entry.sticky,
        onCommit = { value -> onChange { it.copy(sticky = value?.coerceAtLeast(0)) } },
        label = stringResource(R.string.worldinfo_sticky),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(R.string.worldinfo_sticky_hint)
    OptionalIntField(
        value = entry.cooldown,
        onCommit = { value -> onChange { it.copy(cooldown = value?.coerceAtLeast(0)) } },
        label = stringResource(R.string.worldinfo_cooldown),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(R.string.worldinfo_cooldown_hint)
    OptionalIntField(
        value = entry.delay,
        onCommit = { value -> onChange { it.copy(delay = value?.coerceAtLeast(0)) } },
        label = stringResource(R.string.worldinfo_delay),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(R.string.worldinfo_delay_hint)
}

@Composable
private fun FilterSection(entry: WorldInfoEntry, onChange: Change) {
    val selected = entry.triggerSet
    SectionLabel(R.string.prompt_layout_triggers)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            GenerationType.NORMAL to R.string.prompt_layout_trigger_normal,
            GenerationType.REGENERATE to R.string.prompt_layout_trigger_regenerate,
            GenerationType.QUIET to R.string.prompt_layout_trigger_quiet,
        ).forEach { (type, label) ->
            FilterChip(
                selected = type in selected,
                onClick = { onChange { it.withTriggerSet(if (type in it.triggerSet) it.triggerSet - type else it.triggerSet + type) } },
                label = { Text(stringResource(label)) },
            )
        }
    }
    Hint(R.string.prompt_layout_triggers_hint)
}

@Composable
private fun SwitchField(title: Int, description: Int?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            description?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TriStateField(title: Int, value: TriState, onSelect: (TriState) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TriState.INHERIT to R.string.worldinfo_tri_inherit,
                TriState.ON to R.string.worldinfo_tri_on,
                TriState.OFF to R.string.worldinfo_tri_off,
            ).forEach { (state, label) ->
                FilterChip(selected = value == state, onClick = { onSelect(state) }, label = { Text(stringResource(label)) })
            }
        }
    }
}
