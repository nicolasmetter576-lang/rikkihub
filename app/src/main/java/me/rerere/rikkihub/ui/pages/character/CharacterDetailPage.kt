package me.rerere.rikkihub.ui.pages.character

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Star
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.character.CharacterExportFormat
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.prompt.Hint
import me.rerere.rikkihub.ui.components.prompt.IntField
import me.rerere.rikkihub.ui.components.prompt.RoleChips
import me.rerere.rikkihub.ui.components.prompt.SectionLabel
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import me.rerere.rikkihub.ui.theme.AppSpacing

/**
 * 一个角色的页面：头像和名字、开始聊天、卡的全部字段（描述、性格、场景、开场白与备选、示例对话……）、
 * 角色书、卡自带的正则脚本（要用户点头才运行）、导出与删除。每个字段改完（点完成或离开输入框）就写进卡里。
 */
@Composable
fun CharacterDetailPage(id: String, vm: CharacterVM = koinViewModel()) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val uuid = remember(id) { runCatching { Uuid.parse(id) }.getOrNull() }
    val record by remember(uuid) { uuid?.let { vm.observe(it) } ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var addBook by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { toaster.show(it) } }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && uuid != null) vm.setAvatar(uuid, uri)
    }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && uuid != null) vm.export(uuid, CharacterExportFormat.PNG, uri)
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && uuid != null) vm.export(uuid, CharacterExportFormat.JSON, uri)
    }
    val exportCharx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && uuid != null) vm.export(uuid, CharacterExportFormat.CHARX, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(record?.name.orEmpty().ifBlank { stringResource(R.string.character_page_title) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    record?.let { r ->
                        IconButton(onClick = { vm.setFavorite(r.id, !r.card.fav) }) {
                            Icon(
                                HugeIcons.Star, stringResource(R.string.character_favorite),
                                tint = if (r.card.fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.character_export) + " · " + stringResource(R.string.character_export_png)) },
                                onClick = { menu = false; exportPng.launch(exportFileName(r.name, CharacterExportFormat.PNG)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.character_export) + " · " + stringResource(R.string.character_export_json)) },
                                onClick = { menu = false; exportJson.launch(exportFileName(r.name, CharacterExportFormat.JSON)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.character_export) + " · " + stringResource(R.string.character_export_charx)) },
                                onClick = { menu = false; exportCharx.launch(exportFileName(r.name, CharacterExportFormat.CHARX)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.character_delete)) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val r = record
        if (r == null) {
            Column(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.character_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.sm, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CharacterAvatar(r.id, r.name, r.avatarPath, size = 72.dp, modifier = Modifier.clickable { avatarPicker.launch("image/*") })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CardField(R.string.character_field_name, r.card.name, singleLine = true) { vm.editCard(r.id, "name", it) }
                    TextButton(onClick = { avatarPicker.launch("image/*") }) { Text(stringResource(R.string.character_change_picture)) }
                }
            }
            Button(
                onClick = { navController.navigate(Screen.Chat(id = Uuid.random().toString(), characterId = r.id.toString())) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.character_start_chat)) }
            if (r.sourceName.isNotBlank()) Text(stringResource(R.string.character_source, r.sourceName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            CardField(R.string.character_field_tags, r.card.tags.joinToString(", "), singleLine = true) { vm.setTags(r.id, it) }
            CardField(R.string.character_field_description, r.card.description, minLines = 4) { vm.editCard(r.id, "description", it) }
            CardField(R.string.character_field_personality, r.card.personality, minLines = 2) { vm.editCard(r.id, "personality", it) }
            CardField(R.string.character_field_scenario, r.card.scenario, minLines = 2) { vm.editCard(r.id, "scenario", it) }
            CardField(R.string.character_field_first_message, r.card.firstMes, minLines = 3) { vm.editCard(r.id, "first_mes", it) }
            AlternateGreetings(r, vm)
            CardField(R.string.character_field_example_dialogue, r.card.mesExample, minLines = 3) { vm.editCard(r.id, "mes_example", it) }
            CardField(R.string.character_field_creator_notes, r.card.creatorNotes, minLines = 2) { vm.editCard(r.id, "creator_notes", it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { CardField(R.string.character_field_creator, r.card.creator, singleLine = true) { vm.editCard(r.id, "creator", it) } }
                Column(Modifier.weight(1f)) { CardField(R.string.character_field_version, r.card.characterVersion, singleLine = true) { vm.editCard(r.id, "character_version", it) } }
            }

            HorizontalDivider()
            SectionLabel(R.string.character_section_advanced)
            CardField(R.string.character_field_system_prompt, r.card.systemPrompt, minLines = 2) { vm.editCard(r.id, "system_prompt", it) }
            CardField(R.string.character_field_post_history, r.card.postHistoryInstructions, minLines = 2) { vm.editCard(r.id, "post_history_instructions", it) }
            val depth = r.card.depthPrompt
            CardField(R.string.character_field_depth_prompt, depth.prompt, minLines = 2) { vm.setDepthPrompt(r.id, prompt = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IntField(depth.depth, { vm.setDepthPrompt(r.id, depth = it) }, stringResource(R.string.character_field_depth), modifier = Modifier.weight(1f), min = 0)
                Column(Modifier.weight(2f)) { RoleChips(depth.role) { vm.setDepthPrompt(r.id, role = it) } }
            }

            HorizontalDivider()
            BooksSection(r, vm, settings.lorebooks.associate { it.id to it.name }, onOpenBook = { navController.navigate(Screen.Lorebook(it.toString())) }, onAdd = { addBook = true })

            HorizontalDivider()
            RegexSection(r, allowed = ("character:${r.id}") in settings.tavern.regexAllowed, onAllow = { vm.setRegexAllowed(r.id, it) })
        }

        if (confirmDelete) DeleteDialog(r, vm, onDismiss = { confirmDelete = false }, onDone = { navController.popBackStack() })
        if (addBook) AddBookDialog(
            books = settings.lorebooks.filter { it.id !in r.local.bookIds }.map { it.id to it.name },
            onPick = { vm.attachBook(r.id, it); addBook = false },
            onDismiss = { addBook = false },
        )
    }
}

private fun exportFileName(name: String, format: CharacterExportFormat): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "character" }.take(80) + "." + format.extension

@Composable
private fun CardField(label: Int, value: String, singleLine: Boolean = false, minLines: Int = 1, onCommit: (String) -> Unit) {
    CommitOnDoneTextField(
        value = value,
        onCommit = onCommit,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 30,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AlternateGreetings(r: CharacterRecord, vm: CharacterVM) {
    val greetings = r.card.alternateGreetings
    SectionLabel(R.string.character_field_alternate_greetings)
    greetings.forEachIndexed { index, text ->
        Row(verticalAlignment = Alignment.Top) {
            CommitOnDoneTextField(
                value = text,
                onCommit = { v -> vm.setAlternateGreetings(r.id, greetings.toMutableList().also { it[index] = v }) },
                minLines = 2,
                maxLines = 20,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.setAlternateGreetings(r.id, greetings.filterIndexed { i, _ -> i != index }) }) {
                Icon(HugeIcons.Delete01, stringResource(R.string.character_book_remove))
            }
        }
    }
    OutlinedButton(onClick = { vm.setAlternateGreetings(r.id, greetings + "") }, shape = MaterialTheme.shapes.medium) {
        Icon(HugeIcons.Add01, null)
        Text(stringResource(R.string.character_field_add_greeting), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun BooksSection(
    r: CharacterRecord,
    vm: CharacterVM,
    names: Map<Uuid, String>,
    onOpenBook: (Uuid) -> Unit,
    onAdd: () -> Unit,
) {
    SectionLabel(R.string.character_section_books)
    Hint(R.string.character_books_hint)
    r.local.bookIds.forEach { bookId ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = bookId in names) { onOpenBook(bookId) }) {
            Column(Modifier.weight(1f)) {
                Text(names[bookId] ?: bookId.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (bookId == r.local.embeddedBookId) {
                    Text(stringResource(R.string.character_book_from_card), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { vm.detachBook(r.id, bookId) }) { Icon(HugeIcons.Delete01, stringResource(R.string.character_book_remove)) }
        }
    }
    OutlinedButton(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
        Icon(HugeIcons.Add01, null)
        Text(stringResource(R.string.character_book_add), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RegexSection(r: CharacterRecord, allowed: Boolean, onAllow: (Boolean) -> Unit) {
    SectionLabel(R.string.character_section_regex)
    val scripts = r.card.regexScripts
    if (scripts.isEmpty()) {
        Hint(R.string.character_regex_none)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.character_regex_allow), modifier = Modifier.weight(1f))
        Switch(checked = allowed, onCheckedChange = onAllow)
    }
    Hint(R.string.character_regex_hint)
    scripts.forEach { s ->
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(s.scriptName.ifBlank { s.id }, style = MaterialTheme.typography.bodyMedium)
            Text(
                s.findRegex + "  →  " + s.replaceString.ifEmpty { "∅" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeleteDialog(r: CharacterRecord, vm: CharacterVM, onDismiss: () -> Unit, onDone: () -> Unit) {
    var alsoBook by rememberSaveable { mutableStateOf(false) }
    val count = remember { mutableIntStateOf(0) }
    LaunchedEffect(r.id) { count.intValue = vm.conversationCount(r.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.character_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.character_delete_confirm, r.name))
                if (count.intValue > 0) Text(stringResource(R.string.character_conversations_hint, count.intValue), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.local.embeddedBookId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { alsoBook = !alsoBook }) {
                        Checkbox(checked = alsoBook, onCheckedChange = { alsoBook = it })
                        Text(stringResource(R.string.character_delete_with_book), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.delete(r.id, alsoBook, onDone) }) { Text(stringResource(R.string.character_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AddBookDialog(books: List<Pair<Uuid, String>>, onPick: (Uuid) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.character_book_add)) },
        text = {
            if (books.isEmpty()) Text(stringResource(R.string.character_book_none_left))
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                books.forEach { (id, name) ->
                    Text(name.ifBlank { id.toString() }, modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
