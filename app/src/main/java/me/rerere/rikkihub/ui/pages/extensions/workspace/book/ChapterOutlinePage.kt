package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatMatch
import me.rerere.rikkihub.data.book.BeatState
import me.rerere.rikkihub.data.book.PlotPoint
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val WIDE = 720.dp

/** 一章的计划和成稿：每个计划节拍下面挂对应的情节点，点情节点跳到正文。宽屏左右两栏。 */
@Composable
fun ChapterOutlinePage(workspaceId: String, chapterId: String) {
    val vm: ChapterOutlineVM = koinViewModel(key = "chapter-outline-$workspaceId-$chapterId", parameters = { parametersOf(workspaceId, chapterId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle(null)
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    var menu by remember { mutableStateOf(false) }
    // null = 关着；"" = 新增
    var editing by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            toaster.show(it, type = ToastType.Error)
            vm.clearError()
        }
    }
    val row = state.row
    val notes = state.notes
    val points = notes.outline?.points.orEmpty()
    val running = progress?.running == true
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.entry?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton() },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_outline_as_plan)) },
                                enabled = points.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    vm.planFromOutline()
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.book_settings_title)) }, onClick = {
                                menu = false
                                nav.navigate(Screen.SettingBook)
                            })
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.md, vertical = 6.dp)) {
                    OutlineProgressRow(progress, title = { if (it == chapterId) state.entry?.title.orEmpty() else "" }, onStop = vm::stop, onDismiss = vm::dismissProgress)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { editing = "" },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).testTag("outline-add-beat"),
                        ) { Text(stringResource(R.string.book_beat_add), maxLines = 1) }
                        OutlinedButton(
                            onClick = vm::compare,
                            enabled = !running && row?.comparable == true,
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).testTag("outline-compare"),
                        ) { Text(stringResource(R.string.book_outline_compare), maxLines = 1) }
                        Button(
                            onClick = vm::extract,
                            enabled = !running && (state.entry?.words ?: 0) > 0,
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).testTag("outline-extract"),
                        ) { Text(stringResource(if (row?.extracted == true) R.string.book_outline_extract_again else R.string.book_outline_extract), maxLines = 1) }
                    }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= WIDE
            val matches = notes.comparison?.matches.orEmpty().associateBy { it.beatId }.takeIf { notes.comparison != null && !notes.comparisonStale }
            val matched = matches.orEmpty().values.flatMapTo(HashSet()) { it.pointIds }
            val byId = points.associateBy { it.id }
            val openPoint: (PlotPoint) -> Unit = { point ->
                state.entry?.let { nav.navigate(Screen.WorkspaceFileEditor(workspaceId, WorkspaceStorageArea.FILES.name, it.path, reveal = point.quote)) }
            }
            val head: LazyListScope.() -> Unit = {
                item("status") { OutlineStatus(row) }
                val outline = notes.outline
                if (outline != null && outline.synopsis.isNotBlank()) item("synopsis") {
                    Synopsis(outline.synopsis, adopted = outline.synopsis == state.entry?.chapter?.synopsis, onAdopt = vm::adoptSynopsis)
                }
            }
            val plan: @Composable (Modifier, Boolean) -> Unit = { modifier, nested ->
                BeatList(
                    beats = notes.beats,
                    matches = matches,
                    points = byId,
                    nested = nested,
                    head = head,
                    onEdit = { editing = it },
                    onReorder = vm::reorder,
                    onPoint = openPoint,
                    // 窄屏：没对照时列全部情节点，对照后只列多出来的
                    rest = if (!nested) emptyList() else points.filterNot { it.id in matched },
                    restTitle = if (matches == null) R.string.book_outline_points else R.string.book_outline_extra,
                    modifier = modifier,
                )
            }
            if (!wide) plan(Modifier.fillMaxSize(), true)
            else Row(Modifier.fillMaxSize()) {
                plan(Modifier.weight(1f), false)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(AppSpacing.sm)) {
                    item { SectionTitle(stringResource(R.string.book_outline_points)) }
                    items(points, key = { it.id }) { point ->
                        val numbers = matches.orEmpty().values.filter { point.id in it.pointIds }.map { match -> notes.beats.indexOfFirst { it.id == match.beatId } + 1 }
                        PointRow(point, label = if (matches == null) "" else if (numbers.isEmpty()) stringResource(R.string.book_outline_extra) else "← " + numbers.joinToString(), onClick = { openPoint(point) })
                    }
                }
            }
        }
    }
    val target = editing
    if (target != null) {
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
}

@Composable
private fun OutlineStatus(row: OutlineRow?) {
    val text = when {
        row == null -> return
        row.stale -> R.string.book_outline_stale_hint
        !row.extracted -> R.string.book_outline_empty_hint
        row.comparable && row.comparePending -> R.string.book_outline_compare_hint
        else -> return
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = if (row.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Synopsis(text: String, adopted: Boolean, onAdopt: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!adopted) TextButton(onClick = onAdopt) { Text(stringResource(R.string.book_outline_adopt)) }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
}

@Composable
private fun BeatList(
    beats: List<Beat>,
    matches: Map<String, BeatMatch>?,
    points: Map<String, PlotPoint>,
    nested: Boolean,
    head: LazyListScope.() -> Unit,
    onEdit: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onPoint: (PlotPoint) -> Unit,
    rest: List<PlotPoint>,
    restTitle: Int,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var items by remember(beats) { mutableStateOf(beats) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = items.indexOfFirst { it.id == from.key }
        val b = items.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) items = items.toMutableList().apply { add(b, removeAt(a)) }
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)) {
        head()
        item("plan-title") { SectionTitle(stringResource(R.string.book_outline_plan)) }
        if (items.isEmpty()) item("plan-empty") {
            Text(
                stringResource(R.string.book_outline_plan_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        items(items, key = { it.id }) { beat ->
            ReorderableItem(reorderState, key = beat.id) {
                Column {
                    val match = matches?.get(beat.id)
                    Row(Modifier.fillMaxWidth().clickable { onEdit(beat.id) }.heightIn(min = 44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (items.indexOf(beat) + 1).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(beat.text)
                            if (match != null && match.note.isNotBlank()) {
                                Text(match.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (match != null) StateLabel(match.state)
                        Box(
                            Modifier.size(40.dp).draggableHandle(
                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                onDragStopped = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    onReorder(items.map { it.id })
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Icon(HugeIcons.DragDropVertical, stringResource(R.string.prompt_layout_drag), Modifier.size(18.dp)) }
                    }
                    if (nested) match?.pointIds.orEmpty().mapNotNull(points::get).forEach { point -> PointRow(point, "", { onPoint(point) }, indent = true) }
                }
            }
        }
        if (rest.isNotEmpty()) {
            item("rest-title") { SectionTitle(stringResource(restTitle)) }
            items(rest, key = { "point:" + it.id }) { point -> PointRow(point, "", { onPoint(point) }) }
        }
    }
}

@Composable
private fun StateLabel(state: BeatState) {
    val color = when (state) {
        BeatState.WRITTEN -> MaterialTheme.colorScheme.primary
        BeatState.CHANGED -> MaterialTheme.colorScheme.tertiary
        BeatState.MISSING -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(horizontal = 4.dp).size(8.dp).background(color, CircleShape))
        Text(beatStateText(state), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun PointRow(point: PlotPoint, label: String, onClick: () -> Unit, indent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = point.quote.isNotEmpty(), onClick = onClick).heightIn(min = 40.dp)
            .padding(start = if (indent) 28.dp else 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(point.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                point.quote.ifEmpty { stringResource(R.string.book_outline_unlocated) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun BeatDialog(beat: Beat?, onDismiss: () -> Unit, onSave: (String, Int) -> Unit, onRemove: () -> Unit) {
    var text by remember { mutableStateOf(beat?.text.orEmpty()) }
    var words by remember { mutableStateOf(beat?.words?.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (beat == null) R.string.book_beat_add else R.string.book_beat_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(stringResource(R.string.book_beat_text)) },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("beat-text"),
                )
                OutlinedTextField(
                    words,
                    { input -> words = input.filter { it.isDigit() }.take(6) },
                    label = { Text(stringResource(R.string.book_beat_words)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (beat != null && beat.drafts.isNotEmpty()) {
                    Text(stringResource(R.string.book_beat_remove_drafts, beat.drafts.size), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, words.toIntOrNull() ?: 0) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                if (beat != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
