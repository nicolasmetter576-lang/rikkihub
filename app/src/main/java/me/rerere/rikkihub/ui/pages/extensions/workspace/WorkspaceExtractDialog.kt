package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.workspace.WorkspaceArchiveException
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea

/** The path the model and the terminal see: FILES → `/workspace/…`, rootfs → `/…`. */
internal fun workspaceGuestPath(area: WorkspaceStorageArea, path: String): String {
    val clean = path.trim('/')
    return when (area) {
        WorkspaceStorageArea.FILES ->
            if (clean.isEmpty()) WorkspaceManager.ROOTFS_WORKSPACE_DIR else "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$clean"
        WorkspaceStorageArea.LINUX -> "/$clean"
    }
}

/** Charsets offered for legacy archive file names; null = detect automatically. */
internal val WORKSPACE_EXTRACT_ENCODINGS: List<String?> =
    listOf(null, "UTF-8", "GB18030", "Big5", "Shift_JIS", "EUC-KR", "IBM437")

/**
 * Extract dialog: optional password and file-name encoding, progress while running,
 * and the archive error (wrong/missing password, unsupported, damaged) shown in place for a retry.
 */
@Composable
internal fun WorkspaceExtractDialog(
    state: WorkspaceExtractionState,
    onExtract: (password: String?, nameEncoding: String?) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Not saveable: a password must not end up in the saved-instance bundle.
    var password by remember(state.entry.path) { mutableStateOf("") }
    var encoding by rememberSaveable(state.entry.path) { mutableStateOf<String?>(null) }
    var encodingMenu by rememberSaveable { mutableStateOf(false) }
    val autoLabel = stringResource(R.string.workspace_extract_encoding_auto)

    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = {
            Text(
                stringResource(R.string.workspace_extract_title, state.entry.name),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.workspace_extract_progress, state.progress?.entries ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.progress?.currentEntry?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.workspace_extract_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.workspace_extract_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = state.errorCode == WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED ||
                            state.errorCode == WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD,
                    )
                    Box {
                        OutlinedButton(onClick = { encodingMenu = true }) {
                            Text(stringResource(R.string.workspace_extract_encoding, encoding ?: autoLabel))
                        }
                        DropdownMenu(expanded = encodingMenu, onDismissRequest = { encodingMenu = false }) {
                            WORKSPACE_EXTRACT_ENCODINGS.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option ?: autoLabel) },
                                    onClick = {
                                        encoding = option
                                        encodingMenu = false
                                    },
                                )
                            }
                        }
                    }
                    state.errorCode?.let { code ->
                        Text(
                            text = when (code) {
                                WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED -> stringResource(R.string.workspace_extract_password_required)
                                WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD -> stringResource(R.string.workspace_extract_wrong_password)
                                WorkspaceArchiveException.ARCHIVE_UNSUPPORTED -> stringResource(R.string.workspace_extract_unsupported)
                                WorkspaceArchiveException.ARCHIVE_CORRUPT -> stringResource(R.string.workspace_extract_corrupt)
                                else -> stringResource(R.string.workspace_extract_failed, state.errorMessage ?: code)
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } ?: state.errorMessage?.let {
                        Text(
                            stringResource(R.string.workspace_extract_failed, it),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!state.running) {
                TextButton(onClick = { onExtract(password.ifEmpty { null }, encoding) }) {
                    Text(stringResource(R.string.workspace_extract))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = if (state.running) onCancel else onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
