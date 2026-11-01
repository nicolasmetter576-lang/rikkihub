package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.SUBAGENT_MAX_FIELD_CHARS
import me.rerere.rikkihub.data.ai.tools.SUBAGENT_WORKER_PREAMBLE
import me.rerere.rikkihub.data.ai.tools.SubagentPresetModelSelection
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.isToolCapableChatModel
import me.rerere.rikkihub.data.ai.tools.presetModelConfigConflict
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.ai.tools.workspaceSubagentPresetTemplates
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.theme.CustomColors
import kotlin.uuid.Uuid

/** This augments (rather than replaces) the workspace default model/context and tool settings. */
@Composable
fun WorkspaceSubagentPresetCard(workspace: WorkspaceEntity?, vm: WorkspaceDetailVM) {
    val settings = LocalSettings.current
    val editor by vm.subagentPresetEditor.collectAsStateWithLifecycle()
    val busy by vm.subagentPresetBusy.collectAsStateWithLifecycle()
    val workspaces by vm.subagentPresetWorkspaces.collectAsStateWithLifecycle()
    val presets = remember(workspace?.subagentPresets) {
        workspace?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets)?.sortedBy { preset -> preset.sortOrder } }
    }
    val corrupt = workspace != null && presets == null
    var repair by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WorkspaceSubagentPreset?>(null) }
    var copying by remember { mutableStateOf<WorkspaceSubagentPreset?>(null) }
    var templates by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.subagent_library_page_local_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.subagent_library_page_local_notice), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.workspace_subagent_preset_notice), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (corrupt) {
                Text(stringResource(R.string.workspace_subagent_preset_corrupt), color = MaterialTheme.colorScheme.error)
                Button(onClick = { repair = true }, enabled = !busy) {
                    Text(stringResource(R.string.workspace_subagent_preset_repair))
                }
            } else if (presets != null) {
                val canCreate = !busy && presets.size < WorkspaceSubagentPresets.MAX_PRESETS
                val locals = presets.filter { it.libraryPresetId == null }
                Row {
                    TextButton(onClick = { vm.editSubagentPreset() }, enabled = canCreate) {
                        Text(stringResource(R.string.workspace_subagent_preset_create))
                    }
                    Box {
                        TextButton(onClick = { templates = true }, enabled = canCreate) {
                            Text(stringResource(R.string.workspace_subagent_preset_templates))
                        }
                        DropdownMenu(expanded = templates, onDismissRequest = { templates = false }) {
                            workspaceSubagentPresetTemplates().forEach { template ->
                                val name = workspaceSubagentPresetTemplateName(template.key)
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    templates = false
                                    vm.createSubagentPresetFromTemplate(template.copy(name = name))
                                })
                            }
                        }
                    }
                }
                if (presets.none { it.libraryPresetId == null }) Text(stringResource(R.string.workspace_subagent_preset_empty))
                if (presets.size >= WorkspaceSubagentPresets.MAX_PRESETS) {
                    Text(stringResource(R.string.workspace_subagent_preset_limit), style = MaterialTheme.typography.bodySmall)
                }
                locals.forEachIndexed { index, preset ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${preset.name} (${preset.key})", style = MaterialTheme.typography.titleSmall)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall)
                        if (workspaceSubagentPresetKnownModelUnavailable(settings, preset, workspace?.subagentModelId)) {
                            val reference = if (preset.modelSelection == SubagentPresetModelSelection.FIXED)
                                preset.modelId else workspace?.subagentModelId
                            Text(stringResource(R.string.workspace_subagent_preset_model_unavailable, reference.orEmpty()),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            stringResource(if (preset.enabled) R.string.workspace_subagent_preset_enabled else R.string.workspace_subagent_preset_disabled),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row {
                            TextButton(onClick = { vm.editSubagentPreset(preset) }, enabled = !busy) {
                                Text(stringResource(R.string.workspace_subagent_preset_edit))
                            }
                            TextButton(onClick = { vm.cloneSubagentPreset(preset) }, enabled = canCreate) {
                                Text(stringResource(R.string.workspace_subagent_preset_clone))
                            }
                            TextButton(onClick = { deleting = preset }, enabled = !busy) {
                                Text(stringResource(R.string.common_delete))
                            }
                        }
                        Row {
                            TextButton(onClick = { vm.reorderSubagentPresets(presets, preset.id, -1) }, enabled = !busy && index > 0) {
                                Text(stringResource(R.string.workspace_subagent_preset_up))
                            }
                            TextButton(onClick = { vm.reorderSubagentPresets(presets, preset.id, 1) }, enabled = !busy && index < locals.lastIndex) {
                                Text(stringResource(R.string.workspace_subagent_preset_down))
                            }
                            TextButton(onClick = { copying = preset }, enabled = !busy && workspaces.any { it.id != workspace?.id }) {
                                Text(stringResource(R.string.workspace_subagent_preset_copy))
                            }
                        }
                    }
                }
                TextButton(onClick = { preview = true }) {
                    Text(stringResource(R.string.workspace_subagent_preset_catalogue))
                }
            }
        }
    }
    if (repair) {
        WorkspaceSubagentPresetConfirmation(
            title = stringResource(R.string.workspace_subagent_preset_repair),
            message = stringResource(R.string.workspace_subagent_preset_repair_confirm),
            onDismiss = { repair = false },
            onConfirm = { repair = false; vm.resetSubagentPresets() },
        )
    }
    deleting?.let { preset ->
        WorkspaceSubagentPresetConfirmation(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.workspace_subagent_preset_delete_confirm, preset.name),
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; vm.deleteSubagentPreset(preset) },
        )
    }
    copying?.let { preset ->
        AlertDialog(
            onDismissRequest = { copying = null },
            title = { Text(stringResource(R.string.workspace_subagent_preset_copy)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.workspace_subagent_preset_copy_notice))
                    workspaces.filter { it.id != workspace?.id }.forEach { target ->
                        val targetPresets = WorkspaceSubagentPresets.tryParse(target.subagentPresets)
                        TextButton(
                            onClick = { copying = null; vm.copySubagentPreset(preset, target.id) },
                            enabled = !busy && targetPresets != null && targetPresets.size < WorkspaceSubagentPresets.MAX_PRESETS,
                        ) { Text(target.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { copying = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (preview && presets != null) {
        AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text(stringResource(R.string.workspace_subagent_preset_catalogue)) },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.workspace_subagent_preset_preview_notice))
                        Text(workspaceSubagentPresetCataloguePreview(presets.filterNot {
                            workspaceSubagentPresetKnownModelUnavailable(settings, it, workspace?.subagentModelId)
                        }))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { preview = false }) { Text(stringResource(R.string.common_confirm)) } },
        )
    }
    editor.draft?.let { draft ->
        val targetWorkspace = if (draft.workspaceId == null) workspace else
            workspaces.firstOrNull { it.id == draft.workspaceId }
        val targetPresets = if (draft.workspaceId == null) presets else
            targetWorkspace?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) }
        WorkspaceSubagentPresetEditor(
            draft = draft, conflict = editor.conflict, busy = busy, corrupt = targetPresets == null,
            workspace = targetWorkspace, presets = targetPresets,
            onChange = vm::updateSubagentPresetDraft,
            onSave = vm::saveSubagentPreset,
            onDismiss = vm::dismissSubagentPresetEditor,
        )
    }
}

@Composable
internal fun WorkspaceSubagentPresetEditor(
    draft: WorkspaceSubagentPresetDraft,
    conflict: Boolean,
    busy: Boolean,
    corrupt: Boolean,
    workspace: WorkspaceEntity?,
    presets: List<WorkspaceSubagentPreset>?,
    onChange: ((WorkspaceSubagentPresetDraft) -> WorkspaceSubagentPresetDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    global: Boolean = false,
    saveBlocked: Boolean = false,
    extraContent: @Composable () -> Unit = {},
) {
    val settings = LocalSettings.current
    val preset = draft.preset
    val error = draft.validationError()
    val duplicateKey = presets?.any { it.id != preset.id && it.key == preset.key.trim() } == true
    var preview by remember(preset.id) { mutableStateOf(false) }
    val modelProviders = settings.providers.filter { it.enabled }.map { provider ->
        provider.copyProvider(models = provider.models.filter(::isToolCapableChatModel))
    }
    val reference = when (preset.modelSelection) {
        SubagentPresetModelSelection.PARENT -> null
        SubagentPresetModelSelection.WORKSPACE_DEFAULT -> workspace?.subagentModelId
        SubagentPresetModelSelection.FIXED -> preset.modelId
    }
    val selectedId = reference?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    val selectedModel = settings.findModelById(selectedId)
    val modelAvailable = selectedModel != null && isToolCapableChatModel(selectedModel) &&
        selectedModel.findProvider(settings.providers, checkOverwrite = false)?.enabled == true &&
        presetModelConfigConflict(selectedModel, preset) == null
    val modelPreview = when {
        preset.modelSelection == SubagentPresetModelSelection.PARENT ||
            (preset.modelSelection == SubagentPresetModelSelection.WORKSPACE_DEFAULT && reference == null) ->
            stringResource(R.string.workspace_subagent_preset_runtime_model)
        modelAvailable -> selectedModel?.let { it.displayName.ifBlank { it.modelId } }.orEmpty()
        else -> stringResource(R.string.workspace_subagent_preset_model_unavailable, reference.orEmpty())
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (global) R.string.subagent_library_page_title else R.string.workspace_subagent_preset_edit)) },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (conflict) Text(stringResource(if (global) R.string.subagent_library_page_conflict else R.string.workspace_subagent_preset_conflict), color = MaterialTheme.colorScheme.error)
                if (corrupt) Text(stringResource(if (global) R.string.subagent_library_page_corrupt else R.string.workspace_subagent_preset_corrupt), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.workspace_subagent_preset_explicit_save), style = MaterialTheme.typography.bodySmall)
                if (draft.workspaceId != null) {
                    Text(stringResource(R.string.workspace_subagent_preset_copy_target, workspace?.name.orEmpty()),
                        style = MaterialTheme.typography.titleSmall)
                }
                WorkspaceSubagentPresetTextField(
                    value = preset.key, label = R.string.workspace_subagent_preset_key,
                    limit = WorkspaceSubagentPresets.MAX_KEY_CHARS, enabled = !busy && draft.expectedVersion == null,
                    onChange = { value -> onChange { it.copy(preset = it.preset.copy(key = value)) } },
                )
                WorkspaceSubagentPresetTextField(
                    value = preset.name, label = R.string.workspace_subagent_preset_name,
                    limit = WorkspaceSubagentPresets.MAX_NAME_CHARS, enabled = !busy,
                    onChange = { value -> onChange { it.copy(preset = it.preset.copy(name = value)) } },
                )
                WorkspaceSubagentPresetTextField(
                    value = preset.description, label = R.string.workspace_subagent_preset_description,
                    limit = WorkspaceSubagentPresets.MAX_DESCRIPTION_CHARS, enabled = !busy, multiline = true,
                    onChange = { value -> onChange { it.copy(preset = it.preset.copy(description = value)) } },
                )
                WorkspaceSubagentPresetTextField(
                    value = preset.systemPrompt, label = R.string.workspace_subagent_preset_role_prompt,
                    limit = SUBAGENT_MAX_FIELD_CHARS, enabled = !busy, multiline = true,
                    onChange = { value -> onChange { it.copy(preset = it.preset.copy(systemPrompt = value)) } },
                )
                WorkspaceSubagentPresetChoice(
                    label = stringResource(R.string.workspace_subagent_preset_model),
                    selected = preset.modelSelection,
                    choices = SubagentPresetModelSelection.entries,
                    enabled = !busy,
                    choiceLabel = { workspaceSubagentPresetModelSelectionLabel(it) },
                    onSelect = { selection -> onChange { it.copy(preset = it.preset.copy(
                        modelSelection = selection,
                        modelId = it.preset.modelId.takeIf { selection == SubagentPresetModelSelection.FIXED },
                    )) } },
                )
                if (preset.modelSelection == SubagentPresetModelSelection.FIXED) {
                    ModelSelector(
                        modelId = selectedId, providers = modelProviders, type = ModelType.CHAT,
                        onSelect = { model -> onChange { it.copy(preset = it.preset.copy(modelId = model.id.toString())) } },
                    )
                }
                Text(modelPreview, style = MaterialTheme.typography.bodySmall,
                    color = if (reference != null && !modelAvailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.workspace_subagent_preset_generation_notice), style = MaterialTheme.typography.bodySmall)
                WorkspaceSubagentPresetTextField(draft.temperature, R.string.workspace_subagent_preset_temperature,
                    WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS, !busy,
                    onChange = { value -> onChange { it.copy(temperature = value) } })
                WorkspaceSubagentPresetTextField(draft.topP, R.string.workspace_subagent_preset_top_p,
                    WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS, !busy,
                    onChange = { value -> onChange { it.copy(topP = value) } })
                WorkspaceSubagentPresetTextField(draft.maxTokens, R.string.workspace_subagent_preset_max_tokens,
                    WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS, !busy,
                    onChange = { value -> onChange { it.copy(maxTokens = value) } })
                WorkspaceSubagentPresetChoice(
                    label = stringResource(R.string.workspace_subagent_preset_reasoning),
                    selected = preset.generationConfig.reasoningLevel, choices = ReasoningLevel.entries, enabled = !busy,
                    choiceLabel = { workspaceSubagentPresetReasoningLabel(it) },
                    onSelect = { level -> onChange { it.copy(preset = it.preset.copy(
                        generationConfig = it.preset.generationConfig.copy(reasoningLevel = level),
                    )) } },
                )
                WorkspaceSubagentPresetTextField(draft.maxSteps, R.string.workspace_subagent_preset_max_steps,
                    WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS, !busy,
                    onChange = { value -> onChange { it.copy(maxSteps = value) } })
                WorkspaceSubagentPresetSwitch(stringResource(R.string.workspace_subagent_preset_accept_context), preset.acceptContext, !busy) { value ->
                    onChange { it.copy(preset = it.preset.copy(acceptContext = value)) }
                }
                if (preset.acceptContext && workspace?.subagentAllowContext == false) {
                    Text(stringResource(R.string.workspace_subagent_preset_context_blocked), style = MaterialTheme.typography.bodySmall)
                }
                WorkspaceSubagentPresetSwitch(stringResource(if (global) R.string.subagent_library_page_global_enabled else R.string.workspace_subagent_preset_enabled), preset.enabled, !busy) { value ->
                    onChange { it.copy(preset = it.preset.copy(enabled = value)) }
                }
                extraContent()
                if (error != null) Text(stringResource(workspaceSubagentPresetDraftErrorResource(error)), color = MaterialTheme.colorScheme.error)
                if (duplicateKey) Text(stringResource(R.string.workspace_subagent_preset_duplicate_key), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { preview = !preview }) { Text(stringResource(R.string.workspace_subagent_preset_preview)) }
                if (preview) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(if (global) R.string.subagent_library_page_preview_notice else R.string.workspace_subagent_preset_preview_notice))
                            Text(stringResource(if (global) R.string.subagent_library_page_title else R.string.workspace_subagent_preset_catalogue), style = MaterialTheme.typography.titleSmall)
                            Text(workspaceSubagentPresetCataloguePreview(
                                (if (global) listOf(preset) else presets.orEmpty().filter { it.id != preset.id } + preset).filterNot {
                                    workspaceSubagentPresetKnownModelUnavailable(settings, it, workspace?.subagentModelId)
                                },
                            ))
                            Text(stringResource(R.string.workspace_subagent_preset_worker_preview), style = MaterialTheme.typography.titleSmall)
                            Text(SUBAGENT_WORKER_PREAMBLE + "\n\n" + preset.systemPrompt)
                            Text(modelPreview)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !busy && !conflict && !corrupt && !saveBlocked && error == null && !duplicateKey) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun WorkspaceSubagentPresetTextField(
    value: String, label: Int, limit: Int, enabled: Boolean,
    multiline: Boolean = false, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(workspaceSubagentPresetBoundedText(it, limit)) },
        label = { Text(stringResource(label)) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 8 else 1,
        supportingText = { Text("${value.length}/$limit") }, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WorkspaceSubagentPresetSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> WorkspaceSubagentPresetChoice(
    label: String, selected: T, choices: List<T>, enabled: Boolean,
    choiceLabel: @Composable (T) -> String, onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("$label: ${choiceLabel(selected)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choiceLabel(choice)) }, onClick = { expanded = false; onSelect(choice) })
            }
        }
    }
}

@Composable
private fun WorkspaceSubagentPresetConfirmation(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
internal fun workspaceSubagentPresetModelSelectionLabel(selection: SubagentPresetModelSelection): String = stringResource(when (selection) {
    SubagentPresetModelSelection.WORKSPACE_DEFAULT -> R.string.workspace_subagent_preset_workspace_model
    SubagentPresetModelSelection.PARENT -> R.string.workspace_subagent_preset_parent_model
    SubagentPresetModelSelection.FIXED -> R.string.workspace_subagent_preset_fixed_model
})

@Composable
private fun workspaceSubagentPresetTemplateName(key: String): String = stringResource(when (key) {
    "explorer" -> R.string.workspace_subagent_preset_template_explorer
    "developer" -> R.string.workspace_subagent_preset_template_developer
    "reviewer" -> R.string.workspace_subagent_preset_template_reviewer
    else -> R.string.workspace_subagent_preset_template_tester
})

@Composable
internal fun workspaceSubagentPresetReasoningLabel(level: ReasoningLevel): String = stringResource(when (level) {
    ReasoningLevel.OFF -> R.string.reasoning_off
    ReasoningLevel.AUTO -> R.string.reasoning_auto
    ReasoningLevel.LOW -> R.string.reasoning_light
    ReasoningLevel.MEDIUM -> R.string.reasoning_medium
    ReasoningLevel.HIGH -> R.string.reasoning_heavy
    ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
    ReasoningLevel.MAX -> R.string.reasoning_max
})

/** Unknown parent resolution is not treated as unavailable; fixed references never fall back. */
private fun workspaceSubagentPresetKnownModelUnavailable(
    settings: Settings, preset: WorkspaceSubagentPreset, workspaceModelId: String?,
): Boolean {
    val reference = when (preset.modelSelection) {
        SubagentPresetModelSelection.PARENT -> return false
        SubagentPresetModelSelection.WORKSPACE_DEFAULT -> workspaceModelId ?: return false
        SubagentPresetModelSelection.FIXED -> preset.modelId ?: return true
    }
    val model = settings.findModelById(runCatching { Uuid.parse(reference) }.getOrNull()) ?: return true
    return !isToolCapableChatModel(model) || model.findProvider(settings.providers, checkOverwrite = false)?.enabled != true ||
        presetModelConfigConflict(model, preset) != null
}

fun workspaceSubagentPresetDraftErrorResource(error: WorkspaceSubagentPresetDraftError): Int = when (error) {
    WorkspaceSubagentPresetDraftError.KEY -> R.string.workspace_subagent_preset_invalid_key
    WorkspaceSubagentPresetDraftError.NAME -> R.string.workspace_subagent_preset_invalid_name
    WorkspaceSubagentPresetDraftError.DESCRIPTION -> R.string.workspace_subagent_preset_invalid_description
    WorkspaceSubagentPresetDraftError.PROMPT -> R.string.workspace_subagent_preset_invalid_prompt
    WorkspaceSubagentPresetDraftError.MODEL -> R.string.workspace_subagent_preset_invalid_model
    WorkspaceSubagentPresetDraftError.TEMPERATURE -> R.string.workspace_subagent_preset_invalid_temperature
    WorkspaceSubagentPresetDraftError.TOP_P -> R.string.workspace_subagent_preset_invalid_top_p
    WorkspaceSubagentPresetDraftError.MAX_TOKENS -> R.string.workspace_subagent_preset_invalid_max_tokens
    WorkspaceSubagentPresetDraftError.MAX_STEPS -> R.string.workspace_subagent_preset_invalid_max_steps
}
