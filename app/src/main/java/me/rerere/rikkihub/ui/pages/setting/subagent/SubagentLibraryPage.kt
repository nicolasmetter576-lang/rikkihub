package me.rerere.rikkihub.ui.pages.setting.subagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceSubagentPresetEditor
import me.rerere.rikkihub.ui.pages.extensions.workspace.workspaceSubagentPresetModelSelectionLabel
import me.rerere.rikkihub.ui.pages.extensions.workspace.workspaceSubagentPresetReasoningLabel
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SubagentLibraryPage(presetId: String? = null, vm: SubagentLibraryVM = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var handledInitial by rememberSaveable(presetId) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WorkspaceSubagentPreset?>(null) }
    var reset by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.presets, presetId) {
        if (!handledInitial && !state.loading && state.presets != null) {
            handledInitial = true
            presetId?.let { id -> state.presets?.firstOrNull { it.id == id }?.let { vm.edit(it) } }
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.subagent_library_page_title)) },
            navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.subagent_library_page_notice), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.subagent_library_page_disabled_notice), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.subagent_library_page_model_notice), style = MaterialTheme.typography.bodySmall)
                    if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LibraryError(state, vm::dismissError)
                    if (!state.loading && state.error == R.string.subagent_library_page_load_failed) {
                        Button(onClick = vm::reload) { Text(stringResource(R.string.subagent_library_page_retry)) }
                    }
                    if (state.corrupt) {
                        Text(stringResource(R.string.subagent_library_page_corrupt), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { reset = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.workspace_subagent_preset_repair))
                        }
                    } else {
                        val canCreate = !state.loading && !state.busy && state.presets != null &&
                            state.presets.orEmpty().size < SUBAGENT_LIBRARY_UI_LIMIT &&
                            state.error != R.string.subagent_library_page_load_failed
                        Button(onClick = { vm.edit() }, enabled = canCreate) {
                            Text(stringResource(R.string.workspace_subagent_preset_create))
                        }
                        if (state.presets.orEmpty().size >= SUBAGENT_LIBRARY_UI_LIMIT) {
                            Text(stringResource(R.string.subagent_library_page_limit), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!state.loading && state.presets?.isEmpty() == true) Text(stringResource(R.string.subagent_library_page_empty))
                    }
                }
            }
            if (!state.corrupt) items(state.presets.orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.key })), key = { it.id }) { preset ->
                Card(Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${preset.name} (${preset.key})", style = MaterialTheme.typography.titleMedium)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (preset.enabled) R.string.workspace_subagent_preset_enabled else R.string.subagent_library_page_globally_disabled),
                            style = MaterialTheme.typography.labelMedium)
                        val assigned = subagentLibraryWorkspaceIds(preset.id, state.workspaces)
                        Text(stringResource(R.string.subagent_library_page_assignment_count, assigned.size), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { vm.edit(preset) }, enabled = !state.busy && state.error != R.string.subagent_library_page_load_failed) {
                                Text(stringResource(R.string.workspace_subagent_preset_edit))
                            }
                            TextButton(onClick = { vm.edit(preset, clone = true) },
                                enabled = !state.busy && state.presets.orEmpty().size < SUBAGENT_LIBRARY_UI_LIMIT) {
                                Text(stringResource(R.string.workspace_subagent_preset_clone))
                            }
                            TextButton(onClick = { deleting = preset }, enabled = !state.busy) {
                                Text(stringResource(R.string.common_delete))
                            }
                        }
                    }
                }
            }
        }
    }
    state.draft?.let { draft ->
        val blocked = draft.workspaceIds.any { id -> state.workspaces.firstOrNull { it.id == id }
            ?.let { subagentBindingBlock(draft.role.preset, it) != null } != false }
        WorkspaceSubagentPresetEditor(
            draft = draft.role, conflict = draft.conflict, busy = state.busy,
            corrupt = state.corrupt || state.presets == null, workspace = null, presets = state.presets,
            onChange = vm::updateRole, onSave = vm::save, onDismiss = vm::dismissDraft,
            global = true, saveBlocked = blocked || state.loading || state.error == R.string.subagent_library_page_load_failed,
            extraContent = {
                Text(stringResource(R.string.subagent_library_page_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_subagent_preset_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.subagent_library_page_disabled_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.subagent_library_page_model_notice), style = MaterialTheme.typography.bodySmall)
                LibraryError(state, vm::dismissError)
                if (!state.loading && state.error == R.string.subagent_library_page_load_failed) {
                    TextButton(onClick = vm::reload) { Text(stringResource(R.string.subagent_library_page_retry)) }
                }
                if (draft.conflict) {
                    Text(stringResource(R.string.subagent_library_page_rebase_notice), style = MaterialTheme.typography.bodySmall)
                    val latest = state.presets?.firstOrNull { it.id == draft.role.preset.id }
                    if (latest != null) {
                        Text(stringResource(R.string.subagent_library_page_latest_saved), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${latest.name} (${latest.key}) · v${latest.version}")
                                Text(latest.description)
                                Text(latest.systemPrompt)
                                Text(stringResource(R.string.workspace_subagent_preset_model) + ": " +
                                    workspaceSubagentPresetModelSelectionLabel(latest.modelSelection) + latest.modelId?.let { " ($it)" }.orEmpty())
                                Text(stringResource(R.string.workspace_subagent_preset_temperature) + ": " + latest.generationConfig.temperature?.toString().orEmpty())
                                Text(stringResource(R.string.workspace_subagent_preset_top_p) + ": " + latest.generationConfig.topP?.toString().orEmpty())
                                Text(stringResource(R.string.workspace_subagent_preset_max_tokens) + ": " + latest.generationConfig.maxTokens?.toString().orEmpty())
                                Text(stringResource(R.string.workspace_subagent_preset_reasoning) + ": " + workspaceSubagentPresetReasoningLabel(latest.generationConfig.reasoningLevel))
                                Text(stringResource(R.string.workspace_subagent_preset_accept_context) + ": " +
                                    stringResource(if (latest.acceptContext) R.string.workspace_subagent_preset_enabled else R.string.workspace_subagent_preset_disabled))
                                Text(stringResource(R.string.workspace_subagent_preset_max_steps) + ": ${latest.maxSteps}")
                                Text(stringResource(if (latest.enabled) R.string.subagent_library_page_global_enabled else R.string.subagent_library_page_globally_disabled))
                            }
                        }
                        val assignedNames = state.workspaces.filter {
                            it.id in subagentLibraryWorkspaceIds(latest.id, state.workspaces)
                        }.joinToString { it.name }
                        Text(stringResource(R.string.subagent_library_page_current_assignments, assignedNames), style = MaterialTheme.typography.bodySmall)
                    }
                    val canRebase = draft.role.expectedVersion == null || latest != null
                    TextButton(onClick = vm::keepDraftAgainstLatest, enabled = !state.busy && !state.corrupt && !state.loading &&
                        state.error != R.string.subagent_library_page_load_failed && canRebase) {
                        Text(stringResource(R.string.subagent_library_page_keep_draft))
                    }
                }
                Text(stringResource(R.string.subagent_library_page_assignments), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.subagent_library_page_assignment_notice), style = MaterialTheme.typography.bodySmall)
                if (state.workspaces.isEmpty()) Text(stringResource(R.string.subagent_library_page_no_workspaces))
                state.workspaces.forEach { workspace ->
                    val selected = workspace.id in draft.workspaceIds
                    val reason = subagentBindingBlock(draft.role.preset, workspace)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = { vm.selectWorkspace(workspace.id, it) },
                            modifier = Modifier.semantics { contentDescription = workspace.name },
                            enabled = !state.busy && (selected || reason == null))
                        Column(Modifier.weight(1f)) {
                            Text(workspace.name)
                            if (reason != null) Text(stringResource(subagentBindingBlockResource(reason)),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                draft.workspaceIds.filter { id -> state.workspaces.none { it.id == id } }.forEach { id ->
                    val label = stringResource(R.string.subagent_library_page_missing_workspace, id)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, onCheckedChange = { vm.selectWorkspace(id, false) },
                            modifier = Modifier.semantics { contentDescription = label }, enabled = !state.busy)
                        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (blocked) Text(stringResource(R.string.subagent_library_page_assignment_blocked), color = MaterialTheme.colorScheme.error)
            },
        )
    }
    deleting?.let { preset ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.subagent_library_page_delete_confirm, preset.name)) },
            confirmButton = { TextButton(onClick = { deleting = null; vm.delete(preset) }, enabled = !state.busy) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false },
        title = { Text(stringResource(R.string.workspace_subagent_preset_repair)) },
        text = { Text(stringResource(R.string.subagent_library_page_reset_confirm)) },
        confirmButton = { TextButton(onClick = { reset = false; vm.reset() }, enabled = !state.busy) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun LibraryError(state: SubagentLibraryUiState, onDismiss: () -> Unit) {
    state.error?.let { error ->
        Text(stringResource(error) + state.errorDetail?.let { "\n$it" }.orEmpty(), color = MaterialTheme.colorScheme.error)
        if (error != R.string.subagent_library_page_load_failed) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) }
        }
    }
}

fun subagentBindingBlockResource(reason: SubagentBindingBlock): Int = when (reason) {
    SubagentBindingBlock.CORRUPT -> R.string.workspace_subagent_preset_corrupt
    SubagentBindingBlock.KEY_CONFLICT -> R.string.subagent_library_page_key_conflict
    SubagentBindingBlock.LIMIT -> R.string.workspace_subagent_preset_limit
}
