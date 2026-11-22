package me.rerere.rikkihub.ui.components.prompt

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tavern.RegexCheck
import me.rerere.rikkihub.data.ai.tavern.TavernRegexes
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.affects
import me.rerere.rikkihub.data.model.normalizeFindRegex
import me.rerere.rikkihub.data.model.withPlacement
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.regex.SubstituteFindRegex
import org.koin.compose.koinInject

/** 界面上列出的作用点；斜杠命令（3）在 rikkihub 里没有对应入口，但脚本里已有的值照样保留。 */
private val Placements = listOf(
    RegexPlacement.USER_INPUT to R.string.regex_script_placement_user,
    RegexPlacement.AI_OUTPUT to R.string.regex_script_placement_assistant,
    RegexPlacement.WORLD_INFO to R.string.regex_script_placement_world_info,
    RegexPlacement.REASONING to R.string.regex_script_placement_reasoning,
)

/**
 * 一条正则脚本的卡片：折叠时是名称与开关，展开后是全部字段（查找、替换、作用点、仅显示/仅提示词/编辑时运行、
 * 深度范围、查找串先代换、修剪、试运行）。助手正则和全局正则共用。
 *
 * @param settings 试运行和查找串检查用的设置（用户昵称、全局变量）
 * @param assistant 试运行时的助手（`{{char}}` 等）；全局正则页没有就传 null
 */
@Composable
fun RegexScriptCard(
    script: RegexScript,
    settings: Settings,
    assistant: Assistant?,
    onChange: (RegexScript) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    showDragHandle: Boolean = true,
) {
    var expanded by rememberSaveable(script.id) { mutableStateOf(false) }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDragHandle) {
                    Box(modifier = dragHandleModifier, contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.DragDropVertical, contentDescription = stringResource(R.string.prompt_layout_drag))
                    }
                }
                Text(
                    text = script.scriptName.ifBlank { stringResource(R.string.regex_script_unnamed) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 220.dp),
                )
                Switch(
                    checked = !script.disabled,
                    onCheckedChange = { enabled -> onChange(script.copy(disabled = !enabled)) },
                    modifier = Modifier.padding(start = 8.dp),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, contentDescription = null)
                }
            }
            if (expanded) RegexScriptFields(script, settings, assistant, onChange, onDelete)
        }
    }
}

@Composable
private fun RegexScriptFields(
    script: RegexScript,
    settings: Settings,
    assistant: Assistant?,
    onChange: (RegexScript) -> Unit,
    onDelete: () -> Unit,
) {
    val runtime = koinInject<TavernRuntime>()
    val regexes = remember(runtime) { TavernRegexes(runtime) }

    CommitOnDoneTextField(
        value = script.scriptName,
        onCommit = { name -> onChange(script.copy(scriptName = name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.assistant_page_regex_name)) },
    )

    // 查找串：提交时把裸模式补成 /模式/g；检查的是已提交的值，免得每敲一个字都去编译
    val check by produceState<RegexCheck?>(null, script.findRegex, script.substituteRegex) {
        value = regexes.checkFindRegex(script.findRegex, script.substituteRegex)
    }
    CommitOnDoneTextField(
        value = script.findRegex,
        onCommit = { findRegex -> onChange(script.copy(findRegex = normalizeFindRegex(findRegex))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
        placeholder = { Text("/\\b\\w+@\\w+\\.\\w+\\b/g") },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        isError = { check is RegexCheck.Invalid },
        supportingText = {
            when (val c = check) {
                is RegexCheck.Invalid -> Text(
                    text = c.message?.let { stringResource(R.string.regex_script_find_invalid_detail, it) }
                        ?: stringResource(R.string.regex_script_find_invalid),
                    color = MaterialTheme.colorScheme.error,
                )
                RegexCheck.Skipped -> Text(stringResource(R.string.regex_script_find_skipped))
                else -> Text(stringResource(R.string.regex_script_find_hint))
            }
        },
    )

    CommitOnDoneTextField(
        value = script.replaceString,
        onCommit = { replaceString -> onChange(script.copy(replaceString = replaceString)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 8,
        label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
        placeholder = { Text("[EMAIL]") },
        supportingText = { Text(stringResource(R.string.regex_script_replace_hint)) },
    )

    CommitOnDoneTextField(
        value = script.trimStrings.joinToString("\n"),
        onCommit = { text -> onChange(script.copy(trimStrings = text.lines().filter { it.isNotEmpty() })) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 6,
        label = { Text(stringResource(R.string.regex_script_trim)) },
        supportingText = { Text(stringResource(R.string.regex_script_trim_hint)) },
    )

    Text(stringResource(R.string.assistant_page_regex_affecting_scopes), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Placements.forEach { (point, label) ->
            FilterChip(
                selected = script.affects(point),
                onClick = { onChange(script.withPlacement(point, !script.affects(point))) },
                label = { Text(stringResource(label)) },
            )
        }
    }

    ToggleRow(R.string.regex_script_markdown_only, R.string.regex_script_markdown_only_desc, script.markdownOnly) {
        onChange(script.copy(markdownOnly = it))
    }
    ToggleRow(R.string.regex_script_prompt_only, R.string.regex_script_prompt_only_desc, script.promptOnly) {
        onChange(script.copy(promptOnly = it))
    }
    ToggleRow(R.string.regex_script_run_on_edit, R.string.regex_script_run_on_edit_desc, script.runOnEdit) {
        onChange(script.copy(runOnEdit = it))
    }

    Text(stringResource(R.string.regex_script_depth), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OptionalIntField(
            value = script.minDepth,
            onCommit = { onChange(script.copy(minDepth = it)) },
            label = stringResource(R.string.regex_script_depth_min),
            modifier = Modifier.weight(1f),
        )
        OptionalIntField(
            value = script.maxDepth,
            onCommit = { onChange(script.copy(maxDepth = it)) },
            label = stringResource(R.string.regex_script_depth_max),
            modifier = Modifier.weight(1f),
        )
    }
    Hint(R.string.regex_script_depth_hint)

    Text(stringResource(R.string.regex_script_substitute), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            SubstituteFindRegex.NONE to R.string.regex_script_substitute_none,
            SubstituteFindRegex.RAW to R.string.regex_script_substitute_raw,
            SubstituteFindRegex.ESCAPED to R.string.regex_script_substitute_escaped,
        ).forEach { (mode, label) ->
            FilterChip(
                selected = script.substituteRegex == mode,
                onClick = { onChange(script.copy(substituteRegex = mode)) },
                label = { Text(stringResource(label)) },
            )
        }
    }

    TryRun(script, settings, assistant, regexes)

    TextButton(onClick = onDelete) {
        Icon(HugeIcons.Delete01, null)
        Text(stringResource(R.string.delete), modifier = Modifier.padding(start = 4.dp))
    }
}

/** 试运行：输入一段示例，看这条脚本单独作用后的结果（不看作用点、深度这些过滤，也不管脚本是否启用）。 */
@Composable
private fun TryRun(script: RegexScript, settings: Settings, assistant: Assistant?, regexes: TavernRegexes) {
    var sample by rememberSaveable(script.id) { mutableStateOf("") }
    OutlinedTextField(
        value = sample,
        onValueChange = { sample = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 6,
        label = { Text(stringResource(R.string.regex_script_try)) },
        supportingText = { Text(stringResource(R.string.regex_script_try_hint)) },
    )
    if (sample.isNotEmpty()) {
        val result by produceState<String?>(null, sample, script, settings.tavern.globalVariables) {
            value = null
            delay(250) // 敲字时不要每个字都跑一遍
            value = runCatching { regexes.preview(script, sample, settings, assistant) }.getOrElse { it.message.orEmpty() }
        }
        result?.let { text ->
            SelectionContainer {
                Text(
                    text = text.ifEmpty { stringResource(R.string.regex_script_try_empty) },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (text == sample) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: Int, description: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 可空整数输入框：空白 = 不限（null）；不是整数时标红且不提交。 */
@Composable
fun OptionalIntField(
    value: Int?,
    onCommit: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    CommitOnDoneTextField(
        value = value?.toString().orEmpty(),
        onCommit = { text ->
            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> onCommit(null)
                else -> trimmed.toIntOrNull()?.let { onCommit(it) }
            }
        },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = { it.isNotBlank() && it.trim().toIntOrNull() == null },
    )
}
