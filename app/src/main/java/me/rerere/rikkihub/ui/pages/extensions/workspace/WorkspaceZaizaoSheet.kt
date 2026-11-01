package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Sent
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.zaizao.Zaizao
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoConfig
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoException
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoType
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet

internal val ZaizaoType.labelRes: Int
    get() = when (this) {
        ZaizaoType.MODIFY -> R.string.zaizao_type_modify
        ZaizaoType.INCREMENT -> R.string.zaizao_type_increment
        ZaizaoType.REWRITE -> R.string.zaizao_type_rewrite
    }

internal val ZaizaoType.descriptionRes: Int
    get() = when (this) {
        ZaizaoType.MODIFY -> R.string.zaizao_type_modify_desc
        ZaizaoType.INCREMENT -> R.string.zaizao_type_increment_desc
        ZaizaoType.REWRITE -> R.string.zaizao_type_rewrite_desc
    }

@Composable
internal fun zaizaoErrorText(reason: ZaizaoException.Reason?, message: String?): String = when (reason) {
    ZaizaoException.Reason.EMPTY_SELECTION -> stringResource(R.string.tuiqiao_error_empty)
    ZaizaoException.Reason.SELECTION_TOO_LONG -> stringResource(R.string.zaizao_error_too_long, Zaizao.MAX_SELECTION_CHARS)
    ZaizaoException.Reason.EMPTY_REQUIREMENT -> stringResource(R.string.zaizao_error_empty_requirement)
    ZaizaoException.Reason.REQUIREMENT_TOO_LONG -> stringResource(R.string.zaizao_error_requirement_too_long, ZaizaoConfig.MAX_REQUIREMENT_CHARS)
    ZaizaoException.Reason.TOO_MANY_ROUNDS -> stringResource(R.string.zaizao_error_too_many_rounds, Zaizao.MAX_ROUNDS)
    ZaizaoException.Reason.NO_MODEL -> stringResource(R.string.zaizao_error_no_model)
    ZaizaoException.Reason.EMPTY_RESULT -> stringResource(R.string.zaizao_error_empty_result)
    null -> message.orEmpty()
}

/** ① … ⑳ for round numbers (the chain is capped at 20 rounds, but 换一版 sessions can number more). */
internal fun zaizaoRoundMark(number: Int): String = if (number in 1..20) ('①' + (number - 1)).toString() else "($number)"

internal val WorkspaceZaizaoDraft.label: String get() = "${zaizaoRoundMark(round.number)}-$number"

/**
 * Bottom sheet of 再造: the original (with expand buttons and 带后文 before the first round), the rounds with their
 * drafts (tap copies, long press replaces the original; 接着改这版 picks the base for the next round), and the
 * composer at the bottom: type, recent requirements (tap fills, long press forgets) and the requirement field.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceZaizaoSheet(
    entry: WorkspaceZaizaoEntry,
    recent: List<String>,
    onExpandSentence: () -> Unit,
    onExpandParagraph: () -> Unit,
    onSend: () -> Unit,
    onAnother: (WorkspaceZaizaoRound) -> Unit,
    onRetry: (WorkspaceZaizaoDraft) -> Unit,
    onStop: () -> Unit,
    onContinueFrom: (WorkspaceZaizaoDraft) -> Unit,
    onForgetRecent: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCopy: (WorkspaceZaizaoDraft) -> Unit,
    onReplace: (WorkspaceZaizaoDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var originalExpanded by remember(entry) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Open at the newest round and follow the bottom while text streams in; scrolling up stops following,
    // scrolling back down (or a new draft: send, 换一版) resumes. maxValue is Int.MAX_VALUE until the first layout.
    LaunchedEffect(scroll, entry) {
        var stick = true
        var lastMax = -1
        var lastDrafts = -1
        snapshotFlow { Triple(scroll.value, scroll.maxValue, entry.rounds.sumOf { it.drafts.size }) }.collect { (value, max, drafts) ->
            if (max == Int.MAX_VALUE) return@collect
            when {
                drafts != lastDrafts -> {
                    lastDrafts = drafts
                    lastMax = max
                    stick = true
                    if (value != max) scroll.scrollTo(max)
                }
                max != lastMax -> {
                    lastMax = max
                    if (stick && value != max) scroll.scrollTo(max)
                }
                else -> stick = value >= max - 64
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.zaizao_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("zaizao-open-settings")) {
                    Icon(HugeIcons.Settings03, contentDescription = stringResource(R.string.zaizao_settings), modifier = Modifier.size(20.dp))
                }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().clickable { originalExpanded = !originalExpanded },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.tuiqiao_original), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.original, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (originalExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("zaizao-original"))
                    }
                }
                if (!entry.started) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onExpandSentence, modifier = Modifier.testTag("zaizao-expand-sentence")) {
                            Text(stringResource(R.string.tuiqiao_expand_sentence))
                        }
                        OutlinedButton(onClick = onExpandParagraph, modifier = Modifier.testTag("zaizao-expand-paragraph")) {
                            Text(stringResource(R.string.tuiqiao_expand_paragraph))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.zaizao_include_after_short), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(checked = entry.includeAfter, onCheckedChange = { entry.includeAfter = it },
                            modifier = Modifier.testTag("zaizao-include-after-toggle"))
                    }
                }
                if (entry.rounds.any { round -> round.drafts.any { it.usable } }) {
                    Text(stringResource(R.string.tuiqiao_pick_hint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val base = entry.base
                entry.rounds.forEach { round ->
                    ZaizaoRoundHeader(round, canAnother = !entry.busy && round.drafts.any { it.usable }, onAnother = { onAnother(round) })
                    round.drafts.forEach { draft ->
                        ZaizaoDraftCard(
                            draft = draft,
                            isBase = draft === base,
                            busy = entry.busy,
                            onCopy = { onCopy(draft) },
                            onReplace = { onReplace(draft) },
                            onContinue = { onContinueFrom(draft) },
                            onRetry = { onRetry(draft) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ZaizaoComposer(entry, recent, onSend, onStop, onForgetRecent)
        }
    }
}

@Composable
private fun ZaizaoRoundHeader(round: WorkspaceZaizaoRound, canAnother: Boolean, onAnother: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "${zaizaoRoundMark(round.number)} ${stringResource(round.type.labelRes)} · ${round.requirement}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("zaizao-round-${round.number}"),
            )
            if (round.number > 1) {
                Text(
                    stringResource(R.string.zaizao_based_on, round.base?.label ?: stringResource(R.string.zaizao_base_original)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canAnother) {
            TextButton(onClick = onAnother, modifier = Modifier.testTag("zaizao-another-${round.number}")) {
                Text(stringResource(R.string.zaizao_another), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZaizaoDraftCard(
    draft: WorkspaceZaizaoDraft,
    isBase: Boolean,
    busy: Boolean,
    onCopy: () -> Unit,
    onReplace: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
) {
    var planExpanded by remember(draft) { mutableStateOf(false) }
    var thinkingExpanded by remember(draft) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val shape = CardDefaults.outlinedShape
    val status = draft.status
    val clickable = if (draft.usable) Modifier.clip(shape).combinedClickable(
        onClick = onCopy,
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onReplace()
        },
    ) else Modifier
    OutlinedCard(
        shape = shape,
        border = if (isBase) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth().then(clickable).testTag("zaizao-draft-${draft.round.number}-${draft.number}"),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                val note = when (status) {
                    WorkspaceZaizaoStatus.Loading -> stringResource(when {
                        draft.result.isNotEmpty() -> R.string.zaizao_status_writing
                        draft.plan.isNotEmpty() -> R.string.zaizao_status_planning
                        draft.reasoning.isNotEmpty() -> R.string.zaizao_status_thinking
                        else -> R.string.zaizao_status_writing
                    })
                    WorkspaceZaizaoStatus.Stopped -> stringResource(R.string.zaizao_status_stopped)
                    is WorkspaceZaizaoStatus.Failed -> zaizaoErrorText(status.reason, status.message)
                    WorkspaceZaizaoStatus.Done -> if (isBase) stringResource(R.string.zaizao_current_base) else ""
                }
                if (note.isNotEmpty()) {
                    Text(note, style = MaterialTheme.typography.labelSmall,
                        color = if (status is WorkspaceZaizaoStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false).testTag("zaizao-draft-status"))
                }
            }
            if (draft.result.isNotEmpty()) {
                Text(draft.result, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp).testTag("zaizao-draft-text"))
            } else if (status == WorkspaceZaizaoStatus.Loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
            }
            val canContinue = draft.usable && !isBase
            val canRetry = !busy && (status is WorkspaceZaizaoStatus.Failed || status == WorkspaceZaizaoStatus.Stopped)
            if (!canContinue && !canRetry && draft.plan.isEmpty() && draft.reasoning.isEmpty()) {
                Spacer(Modifier.height(8.dp))
            } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 36.dp)) {
                if (canContinue) {
                    TextButton(onClick = onContinue, modifier = Modifier.testTag("zaizao-continue")) {
                        Text(stringResource(R.string.zaizao_continue_this), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (draft.plan.isNotEmpty()) {
                    ZaizaoToggle(stringResource(R.string.zaizao_plan), planExpanded) { planExpanded = !planExpanded }
                }
                if (draft.reasoning.isNotEmpty()) {
                    ZaizaoToggle(stringResource(R.string.zaizao_thinking), thinkingExpanded) { thinkingExpanded = !thinkingExpanded }
                }
                Spacer(Modifier.weight(1f))
                if (canRetry) {
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("zaizao-retry")) {
                        Text(stringResource(R.string.workspace_editor_retry), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            AnimatedVisibility(planExpanded) {
                Text(draft.plan, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp).testTag("zaizao-plan"))
            }
            AnimatedVisibility(thinkingExpanded) {
                Text(draft.reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp).testTag("zaizao-thinking"))
            }
        }
    }
}

@Composable
private fun ZaizaoToggle(label: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(14.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZaizaoComposer(
    entry: WorkspaceZaizaoEntry,
    recent: List<String>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onForgetRecent: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(entry) { if (!entry.started) runCatching { focus.requestFocus() } }
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (entry.started) {
            Text(
                stringResource(R.string.zaizao_base, entry.base?.label ?: stringResource(R.string.zaizao_base_original)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("zaizao-base"),
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ZaizaoType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = entry.type == type,
                    onClick = { entry.type = type },
                    shape = SegmentedButtonDefaults.itemShape(index, ZaizaoType.entries.size),
                    modifier = Modifier.testTag("zaizao-type-${type.name}"),
                    icon = {},
                ) { Text(stringResource(type.labelRes), maxLines = 1) }
            }
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEachIndexed { index, text ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clip(MaterialTheme.shapes.small).combinedClickable(
                            onClick = { entry.draftText = text },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onForgetRecent(text)
                            },
                        ).testTag("zaizao-recent-$index"),
                    ) {
                        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = entry.draftText,
                onValueChange = { entry.draftText = it.take(ZaizaoConfig.MAX_REQUIREMENT_CHARS) },
                placeholder = {
                    Text(stringResource(if (entry.started) R.string.zaizao_requirement_hint_next else R.string.zaizao_requirement_hint))
                },
                maxLines = 5,
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("zaizao-input"),
            )
            if (entry.busy) {
                IconButton(onClick = onStop, modifier = Modifier.testTag("zaizao-stop")) {
                    Icon(HugeIcons.StopCircle, contentDescription = stringResource(R.string.zaizao_stop))
                }
            } else {
                IconButton(onClick = onSend, enabled = entry.draftText.isNotBlank(), modifier = Modifier.testTag("zaizao-send")) {
                    Icon(HugeIcons.Sent, contentDescription = stringResource(R.string.zaizao_send))
                }
            }
        }
    }
}
