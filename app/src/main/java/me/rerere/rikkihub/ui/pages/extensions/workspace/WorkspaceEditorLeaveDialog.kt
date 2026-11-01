package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Column
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkihub.R

/** Both toolbar Back and system Back route through this same three-choice boundary. */
@Composable
internal fun WorkspaceEditorLeaveDialog(saving: Boolean, canSave: Boolean = true, onSave: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!saving) onContinue() },
        title = { Text(stringResource(R.string.workspace_editor_unsaved_title)) },
        text = { Text(stringResource(R.string.workspace_editor_unsaved_body)) },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !saving && canSave) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDiscard, enabled = !saving) { Text(stringResource(R.string.workspace_editor_discard)) }
                TextButton(onClick = onContinue, enabled = !saving) { Text(stringResource(R.string.workspace_editor_continue)) }
            }
        },
    )
}
