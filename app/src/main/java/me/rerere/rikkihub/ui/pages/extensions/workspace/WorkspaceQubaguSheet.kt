package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.qubagu.QubaguConfig
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefineException
import me.rerere.rikkihub.ui.components.message.qubaguRuleLabel
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet

/**
 * 一条八股标注的修改窗口：上方忽略 / 撤销 / 应用，下面是原句（套话加粗）、命中的规则、建议改法（本地规则拼的，
 * 点「AI 建议」换成模型的三种改写），点一条建议填进输入框，输入框可以手改；应用的是输入框里的内容，留空 = 删掉这句。
 */
@Composable
internal fun WorkspaceQubaguSheet(
    mark: WorkspaceQubaguMark,
    config: QubaguConfig,
    ai: WorkspaceQubaguAi?,
    onIgnore: () -> Unit,
    onUndo: () -> Unit,
    onApply: (String) -> Unit,
    onRequestAi: () -> Unit,
    onDismiss: () -> Unit,
) {
    val annotation = mark.annotation
    val suggestions = (ai as? WorkspaceQubaguAi.Done)?.suggestions ?: annotation.suggestions
    var draft by remember(annotation, mark.applied) { mutableStateOf(mark.applied ?: annotation.suggestions.firstOrNull() ?: annotation.original) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onIgnore, modifier = Modifier.testTag("qubagu-mark-ignore")) { Text(stringResource(R.string.qubagu_mark_ignore)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onUndo, enabled = mark.applied != null, modifier = Modifier.testTag("qubagu-mark-undo"), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.qubagu_mark_undo))
                }
                Button(onClick = { onApply(draft) }, enabled = draft != mark.shown, modifier = Modifier.testTag("qubagu-mark-apply"), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.qubagu_mark_apply))
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.qubagu_mark_original), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(highlightPhrases(annotation.original, annotation.phrases), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("qubagu-mark-original"))
                    val labels = annotation.rules.map { qubaguRuleLabel(it, config) }.distinct()
                    Text(
                        stringResource(R.string.qubagu_mark_rules, labels.joinToString(" · ")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (ai is WorkspaceQubaguAi.Done) R.string.qubagu_mark_ai_suggestions else R.string.qubagu_mark_suggestions),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRequestAi, enabled = ai != WorkspaceQubaguAi.Loading, modifier = Modifier.testTag("qubagu-mark-ai")) {
                    if (ai == WorkspaceQubaguAi.Loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (ai is WorkspaceQubaguAi.Done) R.string.qubagu_mark_ai_again else R.string.qubagu_mark_ai))
                }
            }
            if (ai is WorkspaceQubaguAi.Failed) {
                Text(qubaguAiErrorText(ai), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("qubagu-mark-ai-error"))
            }
            if (suggestions.isEmpty()) {
                Text(stringResource(R.string.qubagu_mark_no_suggestions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            suggestions.forEachIndexed { index, suggestion ->
                OutlinedCard(onClick = { draft = suggestion }, modifier = Modifier.fillMaxWidth().testTag("qubagu-mark-suggestion-$index")) {
                    Text(
                        suggestion.ifEmpty { stringResource(R.string.qubagu_rule_deletes) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (suggestion == draft) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.qubagu_mark_input)) },
                supportingText = { Text(stringResource(R.string.qubagu_mark_input_hint)) },
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("qubagu-mark-input"),
            )
        }
    }
}

/** 原句里被标出的套话加粗。 */
private fun highlightPhrases(text: String, phrases: List<String>) = buildAnnotatedString {
    append(text)
    for (phrase in phrases) {
        var from = text.indexOf(phrase)
        while (from >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), from, from + phrase.length)
            from = text.indexOf(phrase, from + phrase.length)
        }
    }
}

@Composable
private fun qubaguAiErrorText(failed: WorkspaceQubaguAi.Failed): String = when (failed.error) {
    QubaguRefineException.Reason.NO_MODEL -> stringResource(R.string.qubagu_refine_no_model)
    QubaguRefineException.Reason.NOTHING_TO_REFINE -> stringResource(R.string.qubagu_refine_nothing)
    QubaguRefineException.Reason.NO_REWRITES -> stringResource(R.string.qubagu_refine_no_rewrites)
    null -> failed.message.orEmpty()
}
