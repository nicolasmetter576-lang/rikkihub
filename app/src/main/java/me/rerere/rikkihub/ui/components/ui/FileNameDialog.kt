package me.rerere.rikkihub.ui.components.ui

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import me.rerere.rikkihub.R
import me.rerere.workspace.WorkspaceFileNames

/**
 * Asks for one file or folder name (validated by [WorkspaceFileNames]). The field starts with
 * [initial] and [initialSelection] selected, e.g. [fileBaseNameSelection] so typing keeps the extension.
 */
@Composable
fun FileNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String = stringResource(R.string.common_rename),
    initialSelection: TextRange = TextRange(0, initial.length),
) {
    val field = rememberTextFieldState(initial)
    LaunchedEffect(Unit) {
        field.setTextAndPlaceCursorAtEnd(initial)
        field.edit { selection = initialSelection }
    }
    val name = field.text.toString()
    val valid = runCatching { WorkspaceFileNames.validate(name) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(state = field, lineLimits = TextFieldLineLimits.SingleLine) },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid && name != initial) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Selects the name before the extension ("notes" in "notes.md", "backup" in "backup.tar.gz");
 * dotfiles and extensionless names select all.
 */
fun fileBaseNameSelection(name: String): TextRange {
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return TextRange(0, name.length)
    val inner = name.lastIndexOf('.', dot - 1)
    val tar = inner > 0 && name.substring(inner, dot).equals(".tar", ignoreCase = true)
    return TextRange(0, if (tar) inner else dot)
}
