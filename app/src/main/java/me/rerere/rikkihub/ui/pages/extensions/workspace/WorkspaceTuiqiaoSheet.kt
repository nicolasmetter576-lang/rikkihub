package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoCandidate
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoException
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoExplanation
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoMode

internal val TuiqiaoMode.labelRes: Int
    get() = when (this) {
        TuiqiaoMode.WORD -> R.string.tuiqiao_mode_word
        TuiqiaoMode.SENTENCE -> R.string.tuiqiao_mode_sentence
        TuiqiaoMode.PARAGRAPH -> R.string.tuiqiao_mode_paragraph
    }

private val TuiqiaoExplanation.labelRes: Int
    get() = when (this) {
        TuiqiaoExplanation.OFF -> R.string.tuiqiao_explanation_off
        TuiqiaoExplanation.ONE_LINE -> R.string.tuiqiao_explanation_one_line
        TuiqiaoExplanation.DETAILED -> R.string.tuiqiao_explanation_detailed
    }

@Composable
internal fun tuiqiaoErrorText(reason: TuiqiaoException.Reason?, message: String?): String = when (reason) {
    TuiqiaoException.Reason.EMPTY_SELECTION -> stringResource(R.string.tuiqiao_error_empty)
    TuiqiaoException.Reason.SELECTION_TOO_LONG -> stringResource(R.string.tuiqiao_error_too_long, Tuiqiao.MAX_SELECTION_CHARS)
    TuiqiaoException.Reason.NO_MODEL -> stringResource(R.string.tuiqiao_error_no_model)
    TuiqiaoException.Reason.NO_CANDIDATES -> stringResource(R.string.tuiqiao_error_no_candidates)
    TuiqiaoException.Reason.MARKUP_BROKEN -> stringResource(R.string.tuiqiao_error_markup)
    null -> message.orEmpty()
}

/**
 * Bottom sheet of 推敲: the original, the auto-detected level (tap another to switch), expand buttons,
 * role cards (placeholder until their one result arrives; a failed role shows its error and retries
 * alone), then the streamed regular candidates (tap copies, long press replaces the original; arrow
 * shows why), regenerate and the two quick settings with a link to the full 推敲 settings page.
 */
@Composable
internal fun WorkspaceTuiqiaoSheet(
    session: WorkspaceTuiqiaoSession,
    status: WorkspaceTuiqiaoStatus,
    slotStatus: Map<String, WorkspaceTuiqiaoStatus>,
    items: List<WorkspaceTuiqiaoItem>,
    config: TuiqiaoConfig,
    onModeChange: (TuiqiaoMode) -> Unit,
    onExpandSentence: () -> Unit,
    onExpandParagraph: () -> Unit,
    onRegenerate: () -> Unit,
    onRetrySlot: (String) -> Unit,
    onConfigChange: ((TuiqiaoConfig) -> TuiqiaoConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onCopy: (TuiqiaoCandidate) -> Unit,
    onReplace: (TuiqiaoCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var originalExpanded by remember(session) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tuiqiao_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(HugeIcons.Settings03, contentDescription = stringResource(R.string.tuiqiao_settings), modifier = Modifier.size(20.dp))
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TuiqiaoMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = session.mode == mode,
                        onClick = { if (session.mode != mode) onModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, TuiqiaoMode.entries.size),
                        modifier = Modifier.testTag("tuiqiao-mode-${mode.name}"),
                        icon = {},
                    ) { Text(stringResource(mode.labelRes), maxLines = 1) }
                }
            }
            AnimatedVisibility(showSettings) {
                TuiqiaoSettings(config, onConfigChange, onOpenSettings)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clickable { originalExpanded = !originalExpanded },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.tuiqiao_original), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(session.original, style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (originalExpanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExpandSentence, modifier = Modifier.testTag("tuiqiao-expand-sentence")) {
                    Text(stringResource(R.string.tuiqiao_expand_sentence))
                }
                OutlinedButton(onClick = onExpandParagraph, modifier = Modifier.testTag("tuiqiao-expand-paragraph")) {
                    Text(stringResource(R.string.tuiqiao_expand_paragraph))
                }
            }
            if (status == WorkspaceTuiqiaoStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (items.isNotEmpty()) {
                Text(stringResource(R.string.tuiqiao_pick_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("tuiqiao-pick-hint"))
            }
            var index = 0
            for (slot in session.slots) {
                val slotState = slotStatus[slot.key] ?: WorkspaceTuiqiaoStatus.Done
                val found = items.filter { it.slot == slot.key }
                found.forEach { item ->
                    TuiqiaoCandidateCard(item.candidate, slot.roleName, Modifier.testTag("tuiqiao-candidate-${index++}"),
                        onClick = { onCopy(item.candidate) }, onLongClick = { onReplace(item.candidate) })
                }
                when {
                    slotState == WorkspaceTuiqiaoStatus.Loading && found.isEmpty() -> if (slot.roleName != null) {
                        TuiqiaoRolePlaceholder(slot.roleName, null, null)
                    } else {
                        Text(stringResource(R.string.tuiqiao_loading), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    slotState is WorkspaceTuiqiaoStatus.Failed && status !is WorkspaceTuiqiaoStatus.Failed ->
                        TuiqiaoRolePlaceholder(slot.roleName, tuiqiaoErrorText(slotState.reason, slotState.message)) {
                            onRetrySlot(slot.key)
                        }
                }
            }
            if (status is WorkspaceTuiqiaoStatus.Failed) {
                Text(tuiqiaoErrorText(status.reason, status.message), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (status != WorkspaceTuiqiaoStatus.Loading) {
                TextButton(onClick = onRegenerate, modifier = Modifier.align(Alignment.End).testTag("tuiqiao-regenerate")) {
                    Text(stringResource(if (status is WorkspaceTuiqiaoStatus.Failed) R.string.workspace_editor_retry else R.string.tuiqiao_regenerate))
                }
            }
        }
    }
}

/** A slot without a result: a role still writing (no [error]) or any slot that failed (with retry). */
@Composable
private fun TuiqiaoRolePlaceholder(roleName: String?, error: String?, onRetry: (() -> Unit)?) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(if (error == null) "tuiqiao-slot-loading" else "tuiqiao-slot-failed")) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 6.dp)) {
            Text(
                error ?: stringResource(R.string.tuiqiao_slot_loading),
                style = MaterialTheme.typography.bodySmall,
                color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
                if (roleName != null) TuiqiaoRoleBadge(roleName)
                Row(Modifier.weight(1f)) {}
                if (onRetry != null) {
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("tuiqiao-slot-retry")) {
                        Text(stringResource(R.string.workspace_editor_retry), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TuiqiaoRoleBadge(name: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun TuiqiaoCandidateCard(
    candidate: TuiqiaoCandidate,
    roleName: String?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var expanded by remember(candidate) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val shape = CardDefaults.outlinedShape
    OutlinedCard(
        shape = shape,
        modifier = modifier.fillMaxWidth().clip(shape).combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 6.dp)) {
            Text(candidate.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
                if (roleName != null) {
                    TuiqiaoRoleBadge(roleName)
                } else if (candidate.tone.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(candidate.tone, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Row(Modifier.weight(1f)) {}
                if (candidate.why.isNotBlank()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(R.string.tuiqiao_why), style = MaterialTheme.typography.labelSmall)
                        Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, contentDescription = null,
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
            AnimatedVisibility(expanded) {
                Text(candidate.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun TuiqiaoSettings(
    config: TuiqiaoConfig,
    onConfigChange: ((TuiqiaoConfig) -> TuiqiaoConfig) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tuiqiao_settings_count), style = MaterialTheme.typography.labelMedium)
        val counts = (TuiqiaoConfig.MIN_CANDIDATES..TuiqiaoConfig.MAX_CANDIDATES).toList()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            counts.forEachIndexed { index, count ->
                SegmentedButton(
                    selected = config.effectiveCandidateCount == count,
                    onClick = { onConfigChange { it.copy(candidateCount = count) } },
                    shape = SegmentedButtonDefaults.itemShape(index, counts.size),
                ) { Text("$count") }
            }
        }
        Text(stringResource(R.string.tuiqiao_settings_explanation), style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TuiqiaoExplanation.entries.forEachIndexed { index, explanation ->
                SegmentedButton(
                    selected = config.explanation == explanation,
                    onClick = { onConfigChange { it.copy(explanation = explanation) } },
                    shape = SegmentedButtonDefaults.itemShape(index, TuiqiaoExplanation.entries.size),
                ) { Text(stringResource(explanation.labelRes)) }
            }
        }
        Text(stringResource(R.string.tuiqiao_settings_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("tuiqiao-open-settings")) {
            Text(stringResource(R.string.tuiqiao_settings_all))
        }
    }
}
