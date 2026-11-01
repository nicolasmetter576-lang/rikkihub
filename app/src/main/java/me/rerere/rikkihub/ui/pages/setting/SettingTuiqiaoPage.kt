package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoExplanation
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoMode
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPrompts
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoRole
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.pages.extensions.workspace.labelRes
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableColumn

/**
 * 推敲 settings: candidate count and explanation, roles (sortable; each takes one candidate slot of the
 * levels it applies to), the file types 推敲 works in, and the three default prompts.
 */
@Composable
fun SettingTuiqiaoPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.tuiqiao
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editing by remember { mutableStateOf<TuiqiaoRole?>(null) }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tuiqiao_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("candidates") { TuiqiaoCandidateSettings(config, vm::updateTuiqiao) }
            item("roles") { TuiqiaoRoleSettings(settings, vm::updateTuiqiao, onEdit = { editing = it }) }
            item("files") { TuiqiaoFileSettings(config, vm::updateTuiqiao) }
            item("prompts") {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.tuiqiao_config_section_prompts))
                    TuiqiaoMode.entries.forEach { mode ->
                        PromptSettingItem(
                            title = stringResource(R.string.setting_model_page_prompt_tuiqiao, stringResource(mode.labelRes)),
                            promptDescription = stringResource(R.string.setting_model_page_tuiqiao_prompt_vars),
                            promptValue = config.promptFor(mode),
                            onPromptChange = { value -> vm.updateTuiqiao { it.withPrompt(mode, value) } },
                            onResetPrompt = { vm.updateTuiqiao { it.withPrompt(mode, null) } },
                            defaultPrompt = TuiqiaoPrompts.defaultFor(mode),
                        )
                    }
                }
            }
        }
    }
    editing?.let { role ->
        TuiqiaoRoleEditor(
            role = role,
            isNew = config.roles.none { it.id == role.id },
            settings = settings,
            onSave = { saved ->
                vm.updateTuiqiao { latest ->
                    val index = latest.roles.indexOfFirst { it.id == saved.id }
                    when {
                        index >= 0 -> latest.copy(roles = latest.roles.toMutableList().apply { set(index, saved) })
                        latest.roles.size < TuiqiaoConfig.MAX_ROLES -> latest.copy(roles = latest.roles + saved)
                        else -> latest
                    }
                }
                editing = null
            },
            onDelete = { id ->
                vm.updateTuiqiao { latest -> latest.copy(roles = latest.roles.filterNot { it.id == id }) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun TuiqiaoCandidateSettings(config: TuiqiaoConfig, update: ((TuiqiaoConfig) -> TuiqiaoConfig) -> Unit) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.tuiqiao_config_section_candidates)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.tuiqiao_settings_count)) },
            supportingContent = {
                val counts = (TuiqiaoConfig.MIN_CANDIDATES..TuiqiaoConfig.MAX_CANDIDATES).toList()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    counts.forEachIndexed { index, count ->
                        SegmentedButton(
                            selected = config.effectiveCandidateCount == count,
                            onClick = { update { it.copy(candidateCount = count) } },
                            shape = SegmentedButtonDefaults.itemShape(index, counts.size),
                        ) { Text("$count") }
                    }
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.tuiqiao_settings_explanation)) },
            supportingContent = {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    TuiqiaoExplanation.entries.forEachIndexed { index, explanation ->
                        SegmentedButton(
                            selected = config.explanation == explanation,
                            onClick = { update { it.copy(explanation = explanation) } },
                            shape = SegmentedButtonDefaults.itemShape(index, TuiqiaoExplanation.entries.size),
                        ) {
                            Text(stringResource(when (explanation) {
                                TuiqiaoExplanation.OFF -> R.string.tuiqiao_explanation_off
                                TuiqiaoExplanation.ONE_LINE -> R.string.tuiqiao_explanation_one_line
                                TuiqiaoExplanation.DETAILED -> R.string.tuiqiao_explanation_detailed
                            }))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun TuiqiaoRoleSettings(
    settings: Settings,
    update: ((TuiqiaoConfig) -> TuiqiaoConfig) -> Unit,
    onEdit: (TuiqiaoRole) -> Unit,
) {
    val config = settings.tuiqiao
    val haptic = LocalHapticFeedback.current
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.tuiqiao_config_section_roles))
        Text(stringResource(R.string.tuiqiao_config_roles_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        if (config.roles.isEmpty()) {
            Text(stringResource(R.string.tuiqiao_config_roles_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        ReorderableColumn(
            list = config.roles,
            onSettle = { fromIndex, toIndex ->
                val order = config.roles.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }.map { it.id }
                update { latest -> latest.copy(roles = latest.roles.sortedBy { role -> order.indexOf(role.id).let { if (it < 0) Int.MAX_VALUE else it } }) }
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { _, role, isDragging ->
            key(role.id) {
                ReorderableItem {
                    Card(
                        modifier = Modifier.fillMaxWidth().scale(if (isDragging) 0.95f else 1f)
                            .clickable { onEdit(role) }.testTag("tuiqiao-role-${role.name}"),
                    ) {
                        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).draggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                ),
                                contentAlignment = Alignment.Center,
                            ) { Icon(HugeIcons.DragDropVertical, contentDescription = null) }
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(role.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val modes = TuiqiaoMode.entries.filter { it in role.modes }.map { stringResource(it.labelRes) }
                                val model = role.modelId?.let { settings.findModelById(it) }
                                val missing = role.modelId != null && model == null
                                Text(
                                    (modes + (model?.displayName?.ifBlank { model.modelId }
                                        ?: stringResource(R.string.tuiqiao_config_role_model_assistant))).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (missing) {
                                    Text(stringResource(R.string.tuiqiao_config_role_model_missing),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Switch(
                                checked = role.enabled,
                                onCheckedChange = { enabled ->
                                    update { latest -> latest.copy(roles = latest.roles.map { if (it.id == role.id) it.copy(enabled = enabled) else it }) }
                                },
                            )
                        }
                    }
                }
            }
        }
        // Levels whose roles leave no room for regular candidates (or don't all fit).
        val count = config.effectiveCandidateCount
        TuiqiaoMode.entries.forEach { mode ->
            val matching = config.roles.count { it.enabled && mode in it.modes }
            if (matching >= count) {
                Text(
                    if (matching > count) stringResource(R.string.tuiqiao_config_role_overflow, stringResource(mode.labelRes), count)
                    else stringResource(R.string.tuiqiao_config_role_saturated, stringResource(mode.labelRes), count),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        val full = config.roles.size >= TuiqiaoConfig.MAX_ROLES
        OutlinedButton(onClick = { onEdit(TuiqiaoRole()) }, enabled = !full, modifier = Modifier.testTag("tuiqiao-role-add")) {
            Text(stringResource(R.string.tuiqiao_config_role_add))
        }
        if (full) {
            Text(stringResource(R.string.tuiqiao_config_role_limit, TuiqiaoConfig.MAX_ROLES), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TuiqiaoFileSettings(config: TuiqiaoConfig, update: ((TuiqiaoConfig) -> TuiqiaoConfig) -> Unit) {
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf<String?>(null) }
    fun add() {
        val raw = input.trim().trimStart('.')
        if (raw.isEmpty()) return
        val ext = TuiqiaoConfig.normalizeExtension(raw)
        if (ext == null) {
            invalid = raw
            return
        }
        update { latest -> latest.copy(extensions = (latest.effectiveExtensions + ext).distinct()) }
        input = ""
        invalid = null
    }
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.tuiqiao_config_section_files))
        Text(stringResource(R.string.tuiqiao_config_files_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            config.effectiveExtensions.forEach { ext ->
                InputChip(
                    selected = false,
                    onClick = { update { latest -> latest.copy(extensions = latest.effectiveExtensions - ext) } },
                    label = { Text(".$ext") },
                    trailingIcon = { Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.testTag("tuiqiao-ext-$ext"),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; invalid = null },
                label = { Text(stringResource(R.string.tuiqiao_config_files_input)) },
                singleLine = true,
                isError = invalid != null,
                supportingText = invalid?.let { { Text(stringResource(R.string.tuiqiao_config_files_invalid, it)) } },
                modifier = Modifier.weight(1f).testTag("tuiqiao-ext-input"),
            )
            TextButton(onClick = ::add, modifier = Modifier.testTag("tuiqiao-ext-add")) {
                Text(stringResource(R.string.tuiqiao_config_files_add))
            }
        }
        if (config.extensions != null) {
            TextButton(onClick = { update { it.copy(extensions = null) } }) {
                Text(stringResource(R.string.tuiqiao_config_files_reset))
            }
        }
    }
}

/** Edits a draft; nothing is saved until Save, so typing never races the settings store. */
@Composable
private fun TuiqiaoRoleEditor(
    role: TuiqiaoRole,
    isNew: Boolean,
    settings: Settings,
    onSave: (TuiqiaoRole) -> Unit,
    onDelete: (kotlin.uuid.Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(role.id) { mutableStateOf(role) }
    var showInvalid by remember(role.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(if (isNew) R.string.tuiqiao_config_role_add else R.string.tuiqiao_config_role_edit),
                style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it.take(TuiqiaoRole.MAX_NAME_CHARS)) },
                label = { Text(stringResource(R.string.tuiqiao_config_role_name)) },
                singleLine = true,
                supportingText = { Text("${draft.name.length}/${TuiqiaoRole.MAX_NAME_CHARS}") },
                modifier = Modifier.fillMaxWidth().testTag("tuiqiao-role-name"),
            )
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = { draft = draft.copy(prompt = it.take(TuiqiaoRole.MAX_PROMPT_CHARS)) },
                label = { Text(stringResource(R.string.tuiqiao_config_role_prompt)) },
                placeholder = { Text(stringResource(R.string.tuiqiao_config_role_prompt_hint)) },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth().testTag("tuiqiao-role-prompt"),
            )
            Text(stringResource(R.string.tuiqiao_config_role_modes), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TuiqiaoMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode in draft.modes,
                        onClick = { draft = draft.copy(modes = if (mode in draft.modes) draft.modes - mode else draft.modes + mode) },
                        label = { Text(stringResource(mode.labelRes)) },
                        modifier = Modifier.testTag("tuiqiao-role-mode-${mode.name}"),
                    )
                }
            }
            Text(stringResource(R.string.tuiqiao_config_role_model), style = MaterialTheme.typography.labelMedium)
            ModelSelector(
                modelId = draft.modelId,
                providers = settings.providers,
                type = ModelType.CHAT,
                allowClear = true,
                onSelect = { model -> draft = draft.copy(modelId = model.takeIf { it.modelId.isNotBlank() }?.id) },
            )
            val missing = draft.modelId != null && settings.findModelById(draft.modelId) == null
            Text(
                stringResource(if (missing) R.string.tuiqiao_config_role_model_missing else R.string.tuiqiao_config_role_model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tuiqiao_config_role_enabled), modifier = Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
            }
            if (showInvalid) {
                Text(stringResource(R.string.tuiqiao_config_role_invalid), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) {
                    TextButton(onClick = { onDelete(role.id) }, modifier = Modifier.testTag("tuiqiao-role-delete")) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(Modifier.weight(1f)) {}
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(
                    onClick = {
                        val cleaned = draft.copy(name = draft.name.trim(), prompt = draft.prompt.trim())
                        if (cleaned.name.isEmpty() || cleaned.prompt.isEmpty() || cleaned.modes.isEmpty()) showInvalid = true
                        else onSave(cleaned)
                    },
                    modifier = Modifier.testTag("tuiqiao-role-save"),
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}
