package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.LockOpen
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.book.BeatJobState
import me.rerere.rikkihub.data.ai.book.BeatProposal
import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatInsertion
import me.rerere.rikkihub.data.book.BeatPlacement
import me.rerere.rikkihub.data.book.ChapterNotes
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/** 编辑器里的节拍面板要对正文做的改动；都作为一次可撤销的修改。 */
internal class BeatBoardEditor(
    val path: String,
    /** 现在的正文。 */
    val text: String,
    val cursor: Int,
    /** 正文现在能不能改。 */
    val editable: Boolean,
    val insert: (List<BeatInsertion>) -> Unit,
    val replace: (IntRange, String) -> Unit,
    val copy: (String) -> Unit,
)

/** 节拍写作：一章拆成节拍逐个生成、逐个重写，满意了写进正文。 */
@Composable
internal fun BeatBoardSheet(workspaceId: String, chapterId: String, editor: BeatBoardEditor, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val vm: BeatBoardVM = koinViewModel(key = "beats-$workspaceId-$chapterId", parameters = { parametersOf(workspaceId, chapterId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle(null)
    val toaster = LocalToaster.current
    var editing by remember { mutableStateOf<String?>(null) }
    var requesting by remember { mutableStateOf<Beat?>(null) }
    var inserting by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.refresh() }
    LaunchedEffect(state.error) {
        state.error?.let {
            toaster.show(it, type = ToastType.Error)
            vm.clearError()
        }
    }
    val notes = state.notes
    val style = remember(editor.path, editor.text, state.previousText) { BeatPlacement.styleOf(editor.path, listOf(editor.text, state.previousText)) }
    val pending = BeatPlacement.pending(notes)
    val running = job?.running == true

    fun insert(beats: List<Beat>, cursor: Int?) {
        val insertions = BeatPlacement.place(editor.text, notes, beats, style, cursor)
        if (insertions.isEmpty()) return
        editor.insert(insertions)
        vm.markInsertions(insertions)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.book_beats_title), style = MaterialTheme.typography.titleMedium)
                    val target = state.entry?.chapter?.targetWords ?: 0
                    val planned = notes.beats.sumOf { it.words }
                    val words = if (target > 0) target else planned
                    Text(
                        stringResource(R.string.book_beats_count, notes.beats.size) + if (words > 0) " · " + stringResource(R.string.book_beats_target, words) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { editing = "" }, modifier = Modifier.testTag("beats-add")) { Text(stringResource(R.string.book_beat_add)) }
                TextButton(onClick = vm::split, enabled = !running, modifier = Modifier.testTag("beats-split")) { Text(stringResource(R.string.book_beats_split)) }
                IconButton(onClick = onOpenSettings) { Icon(HugeIcons.Settings03, stringResource(R.string.book_settings_title), Modifier.size(20.dp)) }
            }
            JobRow(job, notes, onDismiss = vm::dismissJob)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (notes.beats.isEmpty()) item("empty") {
                    Text(stringResource(R.string.book_beats_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                itemsIndexed(notes.beats, key = { _, beat -> beat.id }) { index, beat ->
                    BeatCard(
                        number = index + 1,
                        beat = beat,
                        live = job?.takeIf { it.current == beat.id }?.text,
                        queued = job?.queue?.contains(beat.id) == true,
                        outdated = notes.outdated(beat),
                        found = BeatPlacement.located(editor.text, beat, style) != null,
                        canReplace = editor.editable && BeatPlacement.replacement(editor.text, beat, style) != null,
                        editable = editor.editable,
                        running = running,
                        onEdit = { editing = beat.id },
                        onStep = { vm.step(beat, it) },
                        onLock = { vm.toggleLock(beat.id) },
                        onWrite = { if (beat.drafts.isEmpty()) vm.write(beat.id) else requesting = beat },
                        onRequest = { requesting = beat },
                        onCopy = { beat.chosenDraft?.let { editor.copy(it.text) } },
                        onRemoveDraft = { beat.chosen?.let { vm.removeDraft(beat.id, it) } },
                        onMove = { vm.move(beat.id, it) },
                        onInsert = { insert(listOf(beat), null) },
                        onInsertAtCursor = { insert(listOf(beat), editor.cursor) },
                        onForget = { vm.forgetInserted(beat.id) },
                        onReplace = {
                            BeatPlacement.replacement(editor.text, beat, style)?.let { (range, text) ->
                                editor.replace(range, text)
                                vm.markInserted(listOf(beat.id to text))
                            }
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) OutlinedButton(
                    onClick = vm::stop,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).testTag("beats-stop"),
                ) { Text(stringResource(R.string.stop)) }
                else OutlinedButton(
                    onClick = vm::writeAll,
                    enabled = notes.beats.any { it.drafts.isEmpty() && !it.locked },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).testTag("beats-write-all"),
                ) { Text(stringResource(R.string.book_beats_write_all), maxLines = 1) }
                Box(Modifier.weight(1f)) {
                    Button(
                        onClick = { inserting = true },
                        enabled = editor.editable && pending.isNotEmpty(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("beats-insert"),
                    ) { Text(stringResource(R.string.book_beats_insert, pending.size), maxLines = 1) }
                    DropdownMenu(expanded = inserting, onDismissRequest = { inserting = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.book_beats_insert_in_order)) }, onClick = {
                            inserting = false
                            insert(pending, null)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.book_beats_insert_at_cursor)) }, onClick = {
                            inserting = false
                            insert(pending, editor.cursor)
                        })
                    }
                }
            }
        }
    }
    editing?.let { target ->
        val beat = notes.beats.firstOrNull { it.id == target }
        BeatDialog(
            beat = beat,
            onDismiss = { editing = null },
            onSave = { text, words ->
                editing = null
                vm.saveBeat(beat?.id, text, words)
            },
            onRemove = {
                editing = null
                beat?.let { vm.removeBeat(it.id) }
            },
        )
    }
    requesting?.let { beat ->
        RequestDialog(onDismiss = { requesting = null }) { note, model ->
            requesting = null
            vm.write(beat.id, note, model)
        }
    }
    job?.proposal?.let { proposal ->
        ProposalDialog(proposal, canReplace = notes.beats.isNotEmpty(), onDismiss = vm::dismissJob, onConfirm = vm::adoptProposal)
    }
}

private const val LIVE_CHARS = 120

@Composable
private fun JobRow(job: BeatJobState?, notes: ChapterNotes, onDismiss: () -> Unit) {
    if (job == null || job.proposal != null) return
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (job.running) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                if (job.splitting) stringResource(R.string.book_beats_splitting)
                else stringResource(R.string.book_beats_writing, notes.beats.indexOfFirst { it.id == job.current } + 1),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                job.aiError?.let { bookAiErrorText(it) } ?: job.error.orEmpty(),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_terminal_close)) }
        }
    }
}

@Composable
private fun BeatCard(
    number: Int,
    beat: Beat,
    live: String?,
    queued: Boolean,
    outdated: Boolean,
    found: Boolean,
    canReplace: Boolean,
    editable: Boolean,
    running: Boolean,
    onEdit: () -> Unit,
    onStep: (Int) -> Unit,
    onLock: () -> Unit,
    onWrite: () -> Unit,
    onRequest: () -> Unit,
    onCopy: () -> Unit,
    onRemoveDraft: () -> Unit,
    onMove: (Int) -> Unit,
    onInsert: () -> Unit,
    onInsertAtCursor: () -> Unit,
    onForget: () -> Unit,
    onReplace: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val draft = beat.chosenDraft
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$number  ${beat.text}",
                    Modifier.weight(1f).padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (beat.words > 0) Text(
                    stringResource(R.string.book_words, beat.words.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            // 正在写时看末尾几行
            val shown = live?.takeLast(LIVE_CHARS) ?: draft?.text
            if (!shown.isNullOrBlank()) Text(
                shown,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded || live != null) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(end = 8.dp).testTag("beat-draft-$number"),
            )
            val status = when {
                live != null -> stringResource(R.string.book_beats_writing_now)
                queued -> stringResource(R.string.book_beats_queued)
                draft == null -> stringResource(R.string.book_beats_unwritten)
                beat.inserted.isNotEmpty() && !found -> stringResource(R.string.book_beats_edited)
                canReplace -> stringResource(R.string.book_beats_inserted_other)
                beat.inserted.isNotEmpty() -> stringResource(R.string.book_beats_inserted)
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (beat.drafts.size > 1) {
                    val index = beat.drafts.indexOfFirst { it.id == beat.chosen }
                    IconButton(onClick = { onStep(-1) }, enabled = index > 0 && !beat.locked, modifier = Modifier.size(36.dp)) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.book_beats_previous_draft), Modifier.size(16.dp))
                    }
                    Text("${index + 1}/${beat.drafts.size}", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onStep(1) }, enabled = index < beat.drafts.lastIndex && !beat.locked, modifier = Modifier.size(36.dp)) {
                        Icon(HugeIcons.ArrowRight01, stringResource(R.string.book_beats_next_draft), Modifier.size(16.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (outdated && live == null) Text(stringResource(R.string.book_beats_outdated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (canReplace) TextButton(onClick = onReplace, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.book_beats_replace)) }
                else TextButton(
                    onClick = onWrite,
                    enabled = !running && !beat.locked,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("beat-write-$number"),
                ) { Text(stringResource(if (beat.drafts.isEmpty()) R.string.book_beats_write else R.string.book_beats_rewrite)) }
                IconButton(onClick = onLock, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (beat.locked) HugeIcons.Lock else HugeIcons.LockOpen,
                        stringResource(if (beat.locked) R.string.book_beats_unlock else R.string.book_beats_lock),
                        Modifier.size(16.dp),
                        tint = if (beat.locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp).testTag("beat-menu-$number")) {
                        Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options), Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun entry(text: Int, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
                            text = { Text(stringResource(text)) },
                            enabled = enabled,
                            onClick = {
                                menu = false
                                onClick()
                            },
                        )
                        entry(R.string.book_beats_write_with, enabled = !running && !beat.locked, onClick = onRequest)
                        if (draft != null) {
                            if (beat.inserted.isEmpty()) {
                                entry(R.string.book_beats_insert_in_order, enabled = editable, onClick = onInsert)
                                entry(R.string.book_beats_insert_at_cursor, enabled = editable, onClick = onInsertAtCursor)
                            } else entry(R.string.book_beats_forget, onClick = onForget)
                            entry(R.string.copy, onClick = onCopy)
                            entry(R.string.book_beats_remove_draft, enabled = !beat.locked, onClick = onRemoveDraft)
                        }
                        entry(R.string.book_beats_move_up) { onMove(-1) }
                        entry(R.string.book_beats_move_down) { onMove(1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestDialog(onDismiss: () -> Unit, onConfirm: (String, Uuid?) -> Unit) {
    val settings = LocalSettings.current
    var note by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<Uuid?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_beats_rewrite)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text(stringResource(R.string.book_beats_note)) },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("beat-note"),
                )
                ModelSelector(
                    modelId = model,
                    providers = settings.providers,
                    type = ModelType.CHAT,
                    allowClear = true,
                    onSelect = { picked -> model = picked.takeIf { it.modelId.isNotBlank() }?.id },
                )
                Text(stringResource(R.string.book_beats_model_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(note, model) }, modifier = Modifier.testTag("beat-request-confirm")) { Text(stringResource(R.string.book_beats_write)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ProposalDialog(
    proposal: List<BeatProposal>,
    canReplace: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<BeatProposal>, Boolean) -> Unit,
) {
    var items by remember(proposal) { mutableStateOf(proposal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_beats_proposal)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { index, item ->
                    OutlinedTextField(
                        item.text,
                        { text -> items = items.mapIndexed { i, old -> if (i == index) old.copy(text = text) else old } },
                        supportingText = if (item.words > 0) ({ Text(stringResource(R.string.book_words, item.words.toString())) }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item("hint") {
                    Text(stringResource(R.string.book_beats_proposal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            val kept = items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
            Row {
                if (canReplace) TextButton(onClick = { onConfirm(kept, false) }, enabled = kept.isNotEmpty()) { Text(stringResource(R.string.book_beats_proposal_append)) }
                TextButton(onClick = { onConfirm(kept, true) }, enabled = kept.isNotEmpty(), modifier = Modifier.testTag("beats-proposal-use")) {
                    Text(stringResource(if (canReplace) R.string.book_beats_proposal_replace else R.string.book_outline_adopt))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
