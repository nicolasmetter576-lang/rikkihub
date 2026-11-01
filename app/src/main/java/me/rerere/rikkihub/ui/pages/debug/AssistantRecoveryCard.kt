package me.rerere.rikkihub.ui.pages.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.context.LocalToaster

@Composable
internal fun AssistantRecoveryCard(vm: DebugVM) {
    val settings = LocalSettings.current
    val scan by vm.recoveryScan.collectAsStateWithLifecycle()
    val missing = scan?.let { missingConversationAssistants(it, settings) }.orEmpty()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val failure = stringResource(R.string.debug_recovery_failed)

    suspend fun scanSafely() {
        busy = true
        try {
            vm.scanConversationAssistants()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toaster.show(failure, type = ToastType.Error)
        } finally {
            busy = false
        }
    }
    LaunchedEffect(vm) { scanSafely() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.debug_recovery_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.debug_recovery_description), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.debug_recovery_missing, missing.size))
            if (scan == null) Text(stringResource(R.string.debug_recovery_not_scanned))
            if ((scan?.invalidIdGroups ?: 0) > 0) {
                Text(stringResource(R.string.debug_recovery_invalid_ids, scan!!.invalidIdGroups))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { scope.launch { scanSafely() } }) {
                    Text(stringResource(R.string.debug_recovery_scan))
                }
                Button(enabled = !busy && !settings.init && scan != null && missing.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val count = vm.recoverAssistantsFromConversations { index ->
                                context.getString(R.string.debug_recovery_assistant_name, index)
                            }
                            toaster.show(context.getString(R.string.debug_recovery_success, count), type = ToastType.Success)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toaster.show(failure, type = ToastType.Error)
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.debug_recovery_restore))
                }
            }
        }
    }
}
