package me.rerere.rikkihub.ui.pages.backup.components

import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import kotlin.system.exitProcess

@Composable
fun BackupDialog() {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_page_restart_app)) },
        text = { Text(stringResource(R.string.backup_page_restart_hint)) },
        confirmButton = {
            Button(
                onClick = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                        context.startActivity(launchIntent)
                    }
                    exitProcess(0)
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.backup_page_restart_and_apply))
            }
        },
    )
}
