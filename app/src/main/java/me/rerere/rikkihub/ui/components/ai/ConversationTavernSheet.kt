package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tavern.TavernConversationState
import me.rerere.rikkihub.data.ai.tavern.TavernConversationState.NoteFields
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.AuthorsNoteDefaults
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.variableDisplay
import me.rerere.rikkihub.data.model.withVariable
import me.rerere.rikkihub.data.model.withoutVariable
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.ui.components.prompt.Hint
import me.rerere.rikkihub.ui.components.prompt.IntField
import me.rerere.rikkihub.ui.components.prompt.RoleChips
import me.rerere.rikkihub.ui.components.prompt.SectionLabel
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.tavern.prompt.ExtensionPromptType
import me.rerere.rikkihub.AppScope
import org.koin.compose.koinInject

/**
 * 会话级的酒馆设置面板，两页：作者注释（文本、间隔、位置、深度、角色，可存为全局默认）和变量（本会话的聊天变量、全局变量，可查看、修改、删除）。
 * 改动按「最新状态上做变换」落到会话和设置里，不会盖掉生成过程中同时写回的变量。
 */
@Composable
internal fun ConversationTavernSheet(
    conversation: Conversation,
    settings: Settings,
    onDismiss: () -> Unit,
    initialPage: Int = 0,
) {
    val chatService: ChatService = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val pagerState = rememberPagerState(initialPage = initialPage) { 2 }

    // 经 updateTavernMetadata 直接落库（只写这一列）：只改内存的话，不发消息就离开会话时状态被回收，改动会丢。
    // 输入框在面板关闭（离开组合）时才提交最后一次输入，所以写入要放在比面板更长命的作用域里。
    val appScope: AppScope = koinInject()
    fun updateMetadata(transform: (JsonObject) -> JsonObject) {
        appScope.launch { chatService.updateTavernMetadata(conversation.id, transform) }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        scope.launch { settingsStore.tryUpdate(transform) }
    }

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
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
        ) {
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                listOf(R.string.tavern_sheet_note to 0, R.string.tavern_sheet_variables to 1).forEach { (label, page) ->
                    Tab(
                        selected = pagerState.currentPage == page,
                        onClick = { scope.launch { pagerState.animateScrollToPage(page) } },
                        text = { Text(stringResource(label)) },
                    )
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), userScrollEnabled = false) { page ->
                when (page) {
                    0 -> AuthorsNotePage(
                        metadata = conversation.tavernMetadata,
                        defaults = settings.tavern.authorsNote,
                        onMetadata = ::updateMetadata,
                        onSaveDefaults = { fields ->
                            updateSettings { s ->
                                s.copy(tavern = s.tavern.copy(authorsNote = AuthorsNoteDefaults(
                                    prompt = fields.prompt, interval = fields.interval, depth = fields.depth,
                                    position = fields.position, role = fields.role.code,
                                )))
                            }
                        },
                    )

                    else -> VariablesPage(
                        chatVariables = (conversation.tavernMetadata[TavernConversationState.VARIABLES] as? JsonObject) ?: JsonObject(emptyMap()),
                        globalVariables = settings.tavern.globalVariables,
                        onChatVariables = { transform ->
                            updateMetadata { meta ->
                                val current = (meta[TavernConversationState.VARIABLES] as? JsonObject) ?: JsonObject(emptyMap())
                                TavernConversationState.writeVariables(meta, transform(current))
                            }
                        },
                        onGlobalVariables = { transform ->
                            updateSettings { s -> s.copy(tavern = s.tavern.copy(globalVariables = transform(s.tavern.globalVariables))) }
                        },
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.prompt_page_confirm))
            }
        }
    }
}

@Composable
private fun AuthorsNotePage(
    metadata: JsonObject,
    defaults: AuthorsNoteDefaults,
    onMetadata: ((JsonObject) -> JsonObject) -> Unit,
    onSaveDefaults: (NoteFields) -> Unit,
) {
    val fields = TavernConversationState.readNoteFields(metadata, defaults)
    val own = TavernConversationState.hasOwnNote(metadata)
    fun change(transform: (NoteFields) -> NoteFields) {
        onMetadata { meta -> TavernConversationState.writeNoteFields(meta, transform(TavernConversationState.readNoteFields(meta, defaults))) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Hint(R.string.tavern_note_desc)
        CommitOnDoneTextField(
            value = fields.prompt,
            onCommit = { value -> change { it.copy(prompt = value) } },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 12,
            label = { Text(stringResource(R.string.tavern_note_text)) },
        )
        SectionLabel(R.string.worldinfo_position)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ExtensionPromptType.IN_PROMPT to R.string.tavern_note_pos_after_prompt,
                ExtensionPromptType.IN_CHAT to R.string.tavern_note_pos_in_chat,
                ExtensionPromptType.BEFORE_PROMPT to R.string.tavern_note_pos_before_prompt,
            ).forEach { (position, label) ->
                FilterChip(
                    selected = fields.position == position,
                    onClick = { change { it.copy(position = position) } },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        if (fields.position == ExtensionPromptType.IN_CHAT) {
            IntField(
                value = fields.depth,
                onCommit = { value -> change { it.copy(depth = value) } },
                label = stringResource(R.string.prompt_layout_depth),
                modifier = Modifier.fillMaxWidth(),
                min = 0,
                supportingText = stringResource(R.string.prompt_layout_depth_hint),
            )
            SectionLabel(R.string.worldinfo_role)
            RoleChips(role = fields.role, onSelect = { role -> change { it.copy(role = role) } })
        }
        IntField(
            value = fields.interval,
            onCommit = { value -> change { it.copy(interval = value) } },
            label = stringResource(R.string.tavern_note_interval),
            modifier = Modifier.fillMaxWidth(),
            min = 0,
            supportingText = stringResource(R.string.tavern_note_interval_hint),
        )
        Hint(if (own) R.string.tavern_note_own else R.string.tavern_note_following_default)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onMetadata(TavernConversationState::clearNoteFields) }, enabled = own, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.tavern_note_use_default))
            }
            OutlinedButton(onClick = { onSaveDefaults(fields) }, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.tavern_note_save_default))
            }
        }
    }
}

@Composable
private fun VariablesPage(
    chatVariables: JsonObject,
    globalVariables: JsonObject,
    onChatVariables: ((JsonObject) -> JsonObject) -> Unit,
    onGlobalVariables: ((JsonObject) -> JsonObject) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Hint(R.string.tavern_vars_desc)
        VariableSection(R.string.tavern_vars_chat, chatVariables, onChatVariables)
        VariableSection(R.string.tavern_vars_global, globalVariables, onGlobalVariables)
    }
}

@Composable
private fun VariableSection(title: Int, variables: JsonObject, onChange: ((JsonObject) -> JsonObject) -> Unit) {
    // name == null：不在编辑；"" ：新增；其余：改这个变量
    var editing by rememberSaveable(title) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = { editing = "" }) { Icon(HugeIcons.Add01, stringResource(R.string.tavern_vars_add)) }
    }
    if (variables.isEmpty()) {
        Text(stringResource(R.string.tavern_vars_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    variables.forEach { (name, value) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = variableDisplay(value).ifEmpty { stringResource(R.string.tavern_vars_empty_value) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { editing = name }) { Icon(HugeIcons.Edit02, stringResource(R.string.prompt_page_edit)) }
            IconButton(onClick = { onChange { it.withoutVariable(name) } }) { Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete)) }
        }
    }
    editing?.let { name ->
        VariableDialog(
            initialName = name,
            initialValue = variables[name]?.let(::variableDisplay).orEmpty(),
            onDismiss = { editing = null },
            onConfirm = { newName, text ->
                onChange { it.withVariable(newName, text) }
                editing = null
            },
        )
    }
}

@Composable
private fun VariableDialog(initialName: String, initialValue: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var value by rememberSaveable { mutableStateOf(initialValue) }
    val isNew = initialName.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.tavern_vars_add else R.string.prompt_page_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = isNew,
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.tavern_vars_value)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, value) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.prompt_page_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
