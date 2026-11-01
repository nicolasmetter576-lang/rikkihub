package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.pages.setting.subagent.subagentBindingBlock
import me.rerere.rikkihub.ui.pages.setting.subagent.subagentBindingBlockResource
import me.rerere.rikkihub.ui.theme.CustomColors

@Composable
fun WorkspaceSubagentLibraryCard(workspace: WorkspaceEntity?, vm: WorkspaceDetailVM) {
    val library by vm.subagentLibrary.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val projections = workspace?.let { WorkspaceSubagentPresets.tryParse(it.subagentPresets) }
    Card(Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.subagent_library_page_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.subagent_library_page_workspace_notice), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.subagent_library_page_disabled_notice), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { nav.navigate(Screen.SettingSubagentPresets()) }) {
                Text(stringResource(R.string.subagent_library_page_manage))
            }
            if (library.loading || library.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            library.error?.let { error ->
                Text(stringResource(error) + library.errorDetail?.let { "\n$it" }.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (error == R.string.subagent_library_page_load_failed) {
                    TextButton(onClick = vm::reloadSubagentLibrary) { Text(stringResource(R.string.subagent_library_page_retry)) }
                }
            }
            if (library.corrupt) Text(stringResource(R.string.subagent_library_page_corrupt), color = MaterialTheme.colorScheme.error)
            if (workspace != null && projections == null) {
                Text(stringResource(R.string.workspace_subagent_preset_corrupt), color = MaterialTheme.colorScheme.error)
            }
            if (!library.loading && !library.corrupt && library.presets?.isEmpty() == true) {
                Text(stringResource(R.string.subagent_library_page_empty))
            }
            if (!library.corrupt) library.presets.orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.key })).forEach { preset ->
                // Do not derive applicability from enabled: global disabling does not remove assignments.
                val assigned = projections?.any { it.libraryPresetId == preset.id } == true
                val reason = workspace?.let { subagentBindingBlock(preset, it) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = assigned,
                            modifier = Modifier.semantics { contentDescription = preset.name },
                            onCheckedChange = { vm.setLibraryPresetEnabled(preset, it) },
                            enabled = !library.loading && !library.busy && workspace != null && projections != null && (assigned || reason == null))
                        Column(Modifier.weight(1f)) {
                            Text("${preset.name} (${preset.key})", style = MaterialTheme.typography.titleSmall)
                            Text(preset.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!preset.enabled) Text(stringResource(R.string.subagent_library_page_globally_disabled),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!assigned && reason != null) Text(stringResource(subagentBindingBlockResource(reason)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { nav.navigate(Screen.SettingSubagentPresets(preset.id)) }, enabled = !library.busy) {
                        Text(stringResource(R.string.subagent_library_page_edit_shared))
                    }
                }
            }
        }
    }
}
