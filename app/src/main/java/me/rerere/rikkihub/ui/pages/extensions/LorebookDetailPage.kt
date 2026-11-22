package me.rerere.rikkihub.ui.pages.extensions

import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Share03
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.formatKeyList
import me.rerere.rikkihub.data.model.matchesQuery
import me.rerere.rikkihub.data.model.title
import me.rerere.rikkihub.data.model.withAllEntriesEnabled
import me.rerere.rikkihub.data.model.withEntryUpdated
import me.rerere.rikkihub.data.model.withNewEntry
import me.rerere.rikkihub.data.model.withoutEntry
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.prompt.WorldInfoEntryEditor
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import me.rerere.tavern.worldinfo.WorldInfoEntry
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * 一本世界书的页面：书的名称、说明、总开关，下面是条目列表（搜索、启停、批量启停），点条目进编辑面板。
 * 编辑是实时的，每次改动按书的 id 和条目的 uid 落在最新的设置上。
 */
@Composable
fun LorebookDetailPage(id: String, vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bookId = remember(id) { runCatching { Uuid.parse(id) }.getOrNull() }
    val book = settings.lorebooks.firstOrNull { it.id == bookId }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var editingUid by rememberSaveable { mutableStateOf<Int?>(null) }
    var showExport by remember { mutableStateOf(false) }

    fun updateBook(transform: (Lorebook) -> Lorebook) {
        vm.updateSettings { latest ->
            latest.copy(lorebooks = latest.lorebooks.map { if (it.id == bookId) transform(it) else it })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    Text(
                        text = book?.name?.ifEmpty { null } ?: stringResource(R.string.prompt_page_unnamed_lorebook),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (book != null) {
                        IconButton(onClick = { showExport = true }) {
                            Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        floatingActionButton = {
            if (book != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val (_, entry) = book.withNewEntry()
                        updateBook { it.withNewEntry().first }
                        editingUid = entry.uid
                    },
                    icon = { Icon(HugeIcons.Add01, null) },
                    text = { Text(stringResource(R.string.prompt_page_add_entry)) },
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (book == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.prompt_page_lorebook_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        var query by rememberSaveable { mutableStateOf("") }
        val shown = remember(book.entries, query) { book.entries.filter { it.matchesQuery(query) } }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(AppSpacing.sm) + PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommitOnDoneTextField(
                        value = book.name,
                        onCommit = { value -> updateBook { it.copy(name = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.prompt_page_name)) },
                    )
                    CommitOnDoneTextField(
                        value = book.description,
                        onCommit = { value -> updateBook { it.copy(description = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4,
                        label = { Text(stringResource(R.string.prompt_page_description)) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.prompt_page_enabled),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = book.enabled, onCheckedChange = { on -> updateBook { it.copy(enabled = on) } })
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.prompt_page_entries_format, book.entries.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { updateBook { it.withAllEntriesEnabled(true) } }, enabled = book.entries.isNotEmpty()) {
                            Text(stringResource(R.string.worldinfo_enable_all))
                        }
                        TextButton(onClick = { updateBook { it.withAllEntriesEnabled(false) } }, enabled = book.entries.isNotEmpty()) {
                            Text(stringResource(R.string.worldinfo_disable_all))
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.worldinfo_search)) },
                    )
                }
            }
            if (shown.isEmpty() && book.entries.isNotEmpty()) {
                item(key = "no-match") {
                    Text(
                        text = stringResource(R.string.worldinfo_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(shown, key = { it.uid }) { entry ->
                WorldInfoEntryRow(
                    entry = entry,
                    onClick = { editingUid = entry.uid },
                    onToggle = { on -> updateBook { it.withEntryUpdated(entry.uid) { e -> e.copy(disable = !on) } } },
                )
            }
        }

        book.entries.firstOrNull { it.uid == editingUid }?.let { entry ->
            WorldInfoEntrySheet(
                entry = entry,
                onDismiss = { editingUid = null },
                onChange = { transform -> updateBook { it.withEntryUpdated(entry.uid, transform) } },
                onDelete = {
                    updateBook { it.withoutEntry(entry.uid) }
                    editingUid = null
                },
            )
        }
        if (showExport) LorebookExportDialog(book = book, onDismiss = { showExport = false })
    }
}

@Composable
private fun WorldInfoEntryRow(entry: WorldInfoEntry, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.title.ifEmpty { stringResource(R.string.prompt_page_unnamed_entry) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.disable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                entry.constant -> stringResource(R.string.prompt_page_constant_active)
                entry.key.isNotEmpty() -> formatKeyList(entry.key)
                else -> entry.content.trim().replace(Regex("\\s+"), " ")
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = !entry.disable, onCheckedChange = onToggle)
    }
}

@Composable
private fun WorldInfoEntrySheet(
    entry: WorldInfoEntry,
    onDismiss: () -> Unit,
    onChange: ((WorldInfoEntry) -> WorldInfoEntry) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.prompt_page_edit_entry),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_page_confirm)) }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                WorldInfoEntryEditor(entry = entry, onChange = onChange)
            }
        }
    }

    RikkaConfirmDialog(
        show = confirmDelete,
        title = stringResource(R.string.worldinfo_delete_entry_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            confirmDelete = false
            onDelete()
        },
        onDismiss = { confirmDelete = false },
    ) {
        Text(stringResource(R.string.worldinfo_delete_entry_message, entry.title.ifEmpty { stringResource(R.string.prompt_page_unnamed_entry) }))
    }
}
