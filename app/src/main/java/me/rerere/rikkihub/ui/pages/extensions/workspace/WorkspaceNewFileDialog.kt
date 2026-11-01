package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.workspace.WorkspaceFileNames

internal fun WorkspaceCreateError.stringResourceId(): Int = when (this) {
    WorkspaceCreateError.INVALID_NAME -> R.string.workspace_new_file_invalid_name
    WorkspaceCreateError.ALREADY_EXISTS -> R.string.workspace_new_file_exists
    WorkspaceCreateError.PARENT_MISSING -> R.string.workspace_new_file_parent_missing
    WorkspaceCreateError.PERMISSION_DENIED -> R.string.workspace_new_file_permission_denied
    WorkspaceCreateError.UNAVAILABLE -> R.string.workspace_new_file_unavailable
    WorkspaceCreateError.INVALID_PATH -> R.string.workspace_new_file_invalid_path
    WorkspaceCreateError.FAILED -> R.string.workspace_new_file_failed
    WorkspaceCreateError.OPEN_FAILED -> R.string.workspace_new_file_open_failed
    WorkspaceCreateError.INTERRUPTED -> R.string.workspace_new_file_interrupted
}

@Composable
internal fun WorkspaceNewFileDialog(
    target: WorkspaceNewFileTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (WorkspaceNewFileRequest) -> Unit,
) {
    var name by rememberSaveable(target) { mutableStateOf(DEFAULT_NEW_FILE_NAME) }
    var extension by rememberSaveable(target) { mutableStateOf(DEFAULT_NEW_FILE_EXTENSION) }
    val valid = runCatching { WorkspaceFileNames.creationName(name, extension) }.isSuccess
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.workspace_new_file_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_new_file_parent, "/${target.parentPath}"))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.workspace_new_file_name)) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = {
                        if (!valid) Text(stringResource(R.string.workspace_new_file_invalid_name))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = extension,
                    onValueChange = { extension = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.workspace_new_file_extension)) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { Text(stringResource(R.string.workspace_new_file_extension_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = { onConfirm(target.request(name, extension)) }, // Frozen before submission.
            ) { Text(stringResource(
                if (extension.isEmpty()) R.string.workspace_new_file_create_directory
                else R.string.workspace_new_file_create_file,
            )) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Also visible outside the dialog, including a create-success/open-failure outcome. */
@Composable
internal fun WorkspaceFileCreationFeedback(
    state: WorkspaceFileCreationState,
    onDismissError: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.createdDirectoryPath?.let { path ->
            Text(stringResource(R.string.workspace_new_file_directory_created, path))
            TextButton(onClick = onDismissError) { Text(stringResource(R.string.common_confirm)) }
        }
        state.error?.let { error ->
            Text(stringResource(error.stringResourceId()), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismissError) { Text(stringResource(R.string.common_confirm)) }
        }
    }
}
