package me.rerere.rikkihub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clean
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.qubagu.QubaguConfig
import me.rerere.rikkihub.data.ai.qubagu.QubaguEdit
import me.rerere.rikkihub.data.ai.qubagu.QubaguPartRecord
import me.rerere.rikkihub.data.ai.qubagu.QubaguRules
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.context.LocalSettings
import kotlin.uuid.Uuid

/**
 * 聊天页给消息提供的去八股操作。[setApplied] 的 part、edit 都为 null = 这条消息的全部改动；
 * [scan] 按现在的规则重新检查这条回复（没开开关的助手、以前的消息也能用）。
 */
class QubaguActions(
    val refining: Set<Uuid>,
    val setApplied: (message: UIMessage, part: Int?, edit: Int?, applied: Boolean) -> Unit,
    val scan: (UIMessage) -> Unit,
    val refine: (UIMessage) -> Unit,
)

val LocalQubaguActions = compositionLocalOf<QubaguActions?> { null }

/** 回复下方的一行：改了几处（或发现几处），点开是改动清单。 */
@Composable
fun QubaguBar(records: List<QubaguPartRecord>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val applied = records.sumOf { it.record.appliedCount }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.testTag("qubagu-bar"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(HugeIcons.Clean, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = if (applied > 0) stringResource(R.string.qubagu_bar_applied, applied)
                else stringResource(R.string.qubagu_bar_found, records.sumOf { it.record.edits.size }),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 一条回复的改动清单：逐条应用 / 撤销、全部应用、全部还原、重新检查、AI 精修。 */
@Composable
fun QubaguSheet(message: UIMessage, records: List<QubaguPartRecord>, actions: QubaguActions, onDismiss: () -> Unit) {
    val config = LocalSettings.current.qubagu
    val total = records.sumOf { it.record.edits.size }
    val applied = records.sumOf { it.record.appliedCount }
    val refining = message.id in actions.refining
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.qubagu_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (total == 0) stringResource(R.string.qubagu_test_none) else stringResource(R.string.qubagu_sheet_summary, applied, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("qubagu-summary"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.setApplied(message, null, null, true) },
                    enabled = applied < total && !refining,
                    modifier = Modifier.testTag("qubagu-apply-all"),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.qubagu_apply_all)) }
                OutlinedButton(
                    onClick = { actions.setApplied(message, null, null, false) },
                    enabled = applied > 0 && !refining,
                    modifier = Modifier.testTag("qubagu-restore-all"),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.qubagu_restore_all)) }
                OutlinedButton(
                    onClick = { actions.refine(message) },
                    enabled = total > 0 && !refining,
                    modifier = Modifier.testTag("qubagu-refine"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (refining) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.qubagu_refining), modifier = Modifier.padding(start = 6.dp))
                    } else {
                        Text(stringResource(R.string.qubagu_section_refine))
                    }
                }
                OutlinedButton(
                    onClick = { actions.scan(message) },
                    enabled = !refining,
                    modifier = Modifier.testTag("qubagu-rescan"),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.qubagu_rescan)) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (entry in records) {
                    itemsIndexed(entry.record.edits, key = { index, _ -> "${entry.part}-$index" }) { index, edit ->
                        QubaguEditRow(edit = edit, config = config, modifier = Modifier.testTag("qubagu-edit")) {
                            Switch(
                                checked = edit.applied,
                                enabled = !refining,
                                onCheckedChange = { actions.setApplied(message, entry.part, index, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一处改动：哪条规则、改前（划掉）→ 改后；[trailing] 放开关之类。 */
@Composable
fun QubaguEditRow(edit: QubaguEdit, config: QubaguConfig, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val removed = MaterialTheme.colorScheme.onSurfaceVariant
    val added = MaterialTheme.colorScheme.primary
    val deletes = stringResource(R.string.qubagu_rule_deletes)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(qubaguRuleLabel(edit.rule, config), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = removed, textDecoration = TextDecoration.LineThrough)) { append(onOneLine(edit.before)) }
                    append("  →  ")
                    withStyle(SpanStyle(color = added)) { append(onOneLine(edit.after).ifEmpty { deletes }) }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        trailing?.invoke()
    }
}

private val LINE_BREAKS = Regex("\\s*\\n\\s*")

/** 清单里一处改动占一行：首尾空白去掉，中间的换行显示成 ↵。 */
private fun onOneLine(text: String): String = text.trim().replace(LINE_BREAKS, " ↵ ")

/** 规则 id 给人看的名字：规则包里的规则用它的名字（没名字用 id），规则后来被删了就给个统称。 */
@Composable
fun qubaguRuleLabel(rule: String, config: QubaguConfig): String = when (rule) {
    QubaguRules.MERGE -> stringResource(R.string.qubagu_rule_label_merge)
    QubaguRules.AI -> stringResource(R.string.qubagu_rule_label_ai)
    else -> config.rule(rule)?.let { it.name.ifBlank { it.id } } ?: stringResource(R.string.qubagu_rule_label_deleted)
}
