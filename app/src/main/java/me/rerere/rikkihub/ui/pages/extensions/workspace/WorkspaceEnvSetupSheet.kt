package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.workspace.WorkspaceEnvGroup
import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem
import me.rerere.rikkihub.data.workspace.WorkspaceEnvMirror
import me.rerere.rikkihub.data.workspace.WorkspaceEnvSetup
import me.rerere.rikkihub.data.workspace.WorkspaceEnvSetupState
import me.rerere.rikkihub.data.workspace.WorkspaceEnvStatus
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet

private const val LOG_RUNNING_LINES = 8
private const val LOG_FAILED_LINES = 30

/**
 * "Set up the environment" checkbox with its mirror switch, shared by the new-workspace and the
 * install-rootfs dialogs. The mirror switch only shows while the checkbox is on.
 */
@Composable
internal fun WorkspaceEnvSetupOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    mirror: WorkspaceEnvMirror,
    onMirrorChange: (WorkspaceEnvMirror) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .testTag("workspace_env_setup_option"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (checked) WorkspaceEnvMirrorRow(mirror, onMirrorChange)
    }
}

@Composable
private fun WorkspaceEnvMirrorRow(mirror: WorkspaceEnvMirror, onMirrorChange: (WorkspaceEnvMirror) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.workspace_env_mirror_china), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.workspace_env_mirror_china_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = mirror == WorkspaceEnvMirror.CHINA,
            onCheckedChange = { onMirrorChange(if (it) WorkspaceEnvMirror.CHINA else WorkspaceEnvMirror.OFFICIAL) },
            modifier = Modifier.testTag("workspace_env_mirror"),
        )
    }
}

/** Second line of the basic page's "Basic environment" row. */
@Composable
internal fun workspaceEnvRowText(rootfsReady: Boolean, setup: WorkspaceEnvSetupState?, status: WorkspaceEnvStatus?): String = when {
    setup?.status == WorkspaceEnvSetupState.Status.WAITING_ROOTFS -> stringResource(R.string.workspace_env_waiting_rootfs)
    setup?.status == WorkspaceEnvSetupState.Status.RUNNING ->
        setup.step?.let { workspaceEnvStepLabel(it) } ?: stringResource(R.string.workspace_env_running)
    setup?.status == WorkspaceEnvSetupState.Status.FAILED -> stringResource(R.string.workspace_env_failed)
    !rootfsReady -> stringResource(R.string.workspace_env_not_ready)
    status == null -> stringResource(R.string.workspace_env_checking)
    !status.aptAvailable -> stringResource(R.string.workspace_env_no_apt)
    else -> stringResource(R.string.workspace_env_summary, status.installed.size, WorkspaceEnvItem.entries.size)
}

/**
 * Pick-and-run sheet: source switch, the four groups (installed items marked), then progress and
 * the log tail of the running setup; a finished run shows its result until dismissed.
 */
@Composable
internal fun WorkspaceEnvSetupSheet(
    setup: WorkspaceEnvSetupState?,
    status: WorkspaceEnvStatus?,
    initialMirror: WorkspaceEnvMirror,
    onStart: (Set<WorkspaceEnvItem>, WorkspaceEnvMirror) -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.workspace_env_title), style = MaterialTheme.typography.titleLarge)
            if (setup == null) {
                WorkspaceEnvPicker(status, initialMirror, onStart)
            } else {
                WorkspaceEnvProgress(setup, onCancel, onDismissResult)
            }
        }
    }
}

@Composable
private fun WorkspaceEnvPicker(
    status: WorkspaceEnvStatus?,
    initialMirror: WorkspaceEnvMirror,
    onStart: (Set<WorkspaceEnvItem>, WorkspaceEnvMirror) -> Unit,
) {
    if (status == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.workspace_env_checking))
        }
        return
    }
    if (!status.aptAvailable) {
        Text(stringResource(R.string.workspace_env_no_apt), color = MaterialTheme.colorScheme.error)
        return
    }
    var mirror by remember(status) {
        mutableStateOf(if (status.mirror == WorkspaceEnvMirror.CHINA) WorkspaceEnvMirror.CHINA else initialMirror)
    }
    var selection by remember(status) { mutableStateOf(WorkspaceEnvItem.DEFAULT - status.installed) }

    WorkspaceEnvMirrorRow(mirror) { mirror = it }
    WorkspaceEnvGroup.entries.forEach { group ->
        Text(
            text = workspaceEnvGroupLabel(group),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        WorkspaceEnvItem.entries.filter { it.group == group }.forEach { item ->
            WorkspaceEnvItemRow(
                item = item,
                checked = item in selection,
                installed = item in status.installed,
                onCheckedChange = { selection = WorkspaceEnvSetup.toggle(selection, item, it) },
            )
        }
    }
    Button(
        onClick = { onStart(selection, mirror) },
        enabled = selection.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workspace_env_start"),
            shape = MaterialTheme.shapes.medium,
    ) {
        Text(stringResource(R.string.workspace_env_start))
    }
}

@Composable
private fun WorkspaceEnvItemRow(item: WorkspaceEnvItem, checked: Boolean, installed: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .testTag("workspace_env_item_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(workspaceEnvItemLabel(item), style = MaterialTheme.typography.bodyMedium)
            Text(
                workspaceEnvItemDescription(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = listOfNotNull(
                    stringResource(R.string.workspace_env_size, item.sizeMb),
                    stringResource(R.string.workspace_env_installed).takeIf { installed },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkspaceEnvProgress(setup: WorkspaceEnvSetupState, onCancel: () -> Unit, onDismissResult: () -> Unit) {
    val headline = when (setup.status) {
        WorkspaceEnvSetupState.Status.WAITING_ROOTFS -> stringResource(R.string.workspace_env_waiting_rootfs)
        WorkspaceEnvSetupState.Status.RUNNING -> setup.step?.let { workspaceEnvStepLabel(it) } ?: stringResource(R.string.workspace_env_running)
        WorkspaceEnvSetupState.Status.SUCCEEDED -> stringResource(R.string.workspace_env_succeeded)
        WorkspaceEnvSetupState.Status.FAILED -> stringResource(R.string.workspace_env_failed)
    }
    Text(
        text = headline,
        style = MaterialTheme.typography.titleMedium,
        color = if (setup.status == WorkspaceEnvSetupState.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("workspace_env_headline"),
    )
    Text(
        text = WorkspaceEnvItem.entries.filter { it in setup.items }.map { workspaceEnvItemLabel(it) }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    setup.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (setup.active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    // Running: the last few lines; failed: enough to see why; succeeded: nothing to read.
    val logLines = when (setup.status) {
        WorkspaceEnvSetupState.Status.SUCCEEDED -> emptyList()
        WorkspaceEnvSetupState.Status.FAILED -> setup.log.takeLast(LOG_FAILED_LINES)
        else -> setup.log.takeLast(LOG_RUNNING_LINES)
    }
    if (logLines.isNotEmpty()) {
        Text(stringResource(R.string.workspace_env_log), style = MaterialTheme.typography.labelLarge)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = logLines.joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(10.dp)
                    .heightIn(min = 40.dp)
                    .testTag("workspace_env_log"),
            )
        }
    }
    if (setup.active) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.common_cancel))
        }
    } else {
        Button(onClick = onDismissResult, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.common_confirm))
        }
    }
}

@Composable
private fun workspaceEnvGroupLabel(group: WorkspaceEnvGroup): String = stringResource(
    when (group) {
        WorkspaceEnvGroup.BASE -> R.string.workspace_env_group_base
        WorkspaceEnvGroup.RUNTIME -> R.string.workspace_env_group_runtime
        WorkspaceEnvGroup.OPTIONAL -> R.string.workspace_env_group_optional
        WorkspaceEnvGroup.AGENT -> R.string.workspace_env_group_agent
    }
)

@Composable
private fun workspaceEnvItemLabel(item: WorkspaceEnvItem): String = stringResource(
    when (item) {
        WorkspaceEnvItem.BASE -> R.string.workspace_env_item_base
        WorkspaceEnvItem.PYTHON -> R.string.workspace_env_item_python
        WorkspaceEnvItem.NODE -> R.string.workspace_env_item_node
        WorkspaceEnvItem.DOCS -> R.string.workspace_env_item_docs
        WorkspaceEnvItem.FONTS -> R.string.workspace_env_item_fonts
        WorkspaceEnvItem.MEDIA -> R.string.workspace_env_item_media
        WorkspaceEnvItem.BUILD -> R.string.workspace_env_item_build
        WorkspaceEnvItem.TOOLS -> R.string.workspace_env_item_tools
        WorkspaceEnvItem.AGY -> R.string.workspace_env_item_agy
        WorkspaceEnvItem.DROID -> R.string.workspace_env_item_droid
    }
)

@Composable
private fun workspaceEnvItemDescription(item: WorkspaceEnvItem): String = stringResource(
    when (item) {
        WorkspaceEnvItem.BASE -> R.string.workspace_env_item_base_desc
        WorkspaceEnvItem.PYTHON -> R.string.workspace_env_item_python_desc
        WorkspaceEnvItem.NODE -> R.string.workspace_env_item_node_desc
        WorkspaceEnvItem.DOCS -> R.string.workspace_env_item_docs_desc
        WorkspaceEnvItem.FONTS -> R.string.workspace_env_item_fonts_desc
        WorkspaceEnvItem.MEDIA -> R.string.workspace_env_item_media_desc
        WorkspaceEnvItem.BUILD -> R.string.workspace_env_item_build_desc
        WorkspaceEnvItem.TOOLS -> R.string.workspace_env_item_tools_desc
        WorkspaceEnvItem.AGY, WorkspaceEnvItem.DROID -> R.string.workspace_env_item_agent_desc
    }
)

@Composable
private fun workspaceEnvStepLabel(step: String): String = when (step) {
    "prepare" -> stringResource(R.string.workspace_env_step_prepare)
    "update" -> stringResource(R.string.workspace_env_step_update)
    "packages" -> stringResource(R.string.workspace_env_step_packages)
    WorkspaceEnvItem.DOCS.id -> stringResource(R.string.workspace_env_step_docs)
    WorkspaceEnvItem.NODE.id -> stringResource(R.string.workspace_env_step_node)
    WorkspaceEnvItem.AGY.id -> stringResource(R.string.workspace_env_step_agy)
    WorkspaceEnvItem.DROID.id -> stringResource(R.string.workspace_env_step_droid)
    "finish" -> stringResource(R.string.workspace_env_step_finish)
    else -> stringResource(R.string.workspace_env_running)
}
