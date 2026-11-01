package me.rerere.rikkihub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.antigravity.AntigravityAccount
import me.rerere.rikkihub.data.antigravity.AntigravityAccountRepository
import me.rerere.rikkihub.data.antigravity.AntigravityModelQuota
import me.rerere.rikkihub.data.antigravity.AntigravityOAuthManager
import me.rerere.rikkihub.data.antigravity.AntigravityOAuthStatus
import me.rerere.rikkihub.data.antigravity.AntigravityTokenStatus
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.pages.assistant.detail.CustomHeaders
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun AntigravityProviderConfigure(
    provider: ProviderSetting.Antigravity,
    onEdit: (ProviderSetting.Antigravity) -> Unit,
) {
    val repository = koinInject<AntigravityAccountRepository>()
    val oauthManager = koinInject<AntigravityOAuthManager>()
    val providerManager = koinInject<ProviderManager>()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val oauthStatus by oauthManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val canEnable = accounts.any { it.enabled && it.status != AntigravityTokenStatus.INVALID }
    var clientVersion by remember(provider.id) { mutableStateOf(provider.clientVersion) }
    var manualInput by remember { mutableStateOf("") }

    LaunchedEffect(oauthStatus) {
        when (val status = oauthStatus) {
            is AntigravityOAuthStatus.Success -> {
                runCatching {
                    providerManager.getProviderByType(provider).listModels(provider)
                }.onSuccess { models ->
                    onEdit(provider.copy(models = mergeDevinModels(provider.models, models)))
                }
                toaster.show(context.getString(R.string.antigravity_oauth_success), type = ToastType.Success)
                oauthManager.consumeResult()
            }

            is AntigravityOAuthStatus.Error -> {
                toaster.show(status.message, type = ToastType.Error)
                oauthManager.consumeResult()
            }

            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CustomHeaders(provider.customHeaders) { onEdit(provider.copy(customHeaders = it)) }

        Text(text = provider.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.antigravity_provider_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = oauthManager::startLogin,
            enabled = oauthStatus != AntigravityOAuthStatus.Completing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.antigravity_sign_in))
        }
        when (oauthStatus) {
            AntigravityOAuthStatus.Waiting -> Text(
                stringResource(R.string.antigravity_oauth_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AntigravityOAuthStatus.Completing -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.antigravity_oauth_completing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            else -> Unit
        }

        OutlinedTextField(
            value = manualInput,
            onValueChange = { manualInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.antigravity_manual_input_hint)) },
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = {
                        oauthManager.loginWithManualInput(manualInput)
                        manualInput = ""
                    },
                    enabled = manualInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.add))
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.antigravity_enable), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (canEnable) {
                        stringResource(R.string.antigravity_round_robin_description)
                    } else {
                        stringResource(R.string.antigravity_sign_in_required)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copy(enabled = it)) },
                enabled = provider.enabled || canEnable,
            )
        }

        OutlinedTextField(
            value = clientVersion,
            onValueChange = { clientVersion = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.antigravity_client_version)) },
            placeholder = { Text(stringResource(R.string.antigravity_client_version_auto)) },
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = { onEdit(provider.copy(clientVersion = clientVersion.trim())) },
                    enabled = clientVersion.trim() != provider.clientVersion,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.antigravity_accounts_count, accounts.size),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { repository.refreshAll() }.onFailure {
                            toaster.show(
                                it.message ?: context.getString(R.string.antigravity_refresh_failed),
                                type = ToastType.Error,
                            )
                        }
                    }
                },
                enabled = accounts.isNotEmpty(),
            ) {
                Text(stringResource(R.string.antigravity_check_status))
            }
        }

        if (accounts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Text(
                    text = stringResource(R.string.antigravity_no_accounts),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            accounts.forEach { account ->
                AntigravityAccountCard(
                    account = account,
                    onEnabledChange = { enabled -> scope.launch { repository.setEnabled(account.id, enabled) } },
                    onRefresh = {
                        scope.launch {
                            runCatching { repository.refreshAccount(account.id) }.onFailure {
                                toaster.show(
                                    it.message ?: context.getString(R.string.antigravity_refresh_failed),
                                    type = ToastType.Error,
                                )
                            }
                        }
                    },
                    onReauthenticate = oauthManager::startLogin,
                    onDelete = {
                        scope.launch {
                            repository.delete(account.id)
                            if (repository.accounts.value.isEmpty() && provider.enabled) {
                                onEdit(provider.copy(enabled = false))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AntigravityAccountCard(
    account: AntigravityAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauthenticate: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.antigravity_remove_account_title)) },
            text = { Text(stringResource(R.string.antigravity_remove_account_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.antigravity_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.email.ifBlank { "Antigravity" }, style = MaterialTheme.typography.titleMedium)
                    if (account.projectId.isNotBlank()) {
                        Text(
                            stringResource(R.string.antigravity_project, account.projectId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = account.enabled, onCheckedChange = onEnabledChange)
            }

            Text(
                text = when (account.status) {
                    AntigravityTokenStatus.AVAILABLE -> stringResource(R.string.antigravity_token_available)
                    AntigravityTokenStatus.EXPIRED -> stringResource(R.string.antigravity_token_expired)
                    AntigravityTokenStatus.INVALID -> stringResource(R.string.antigravity_token_invalid)
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (account.status) {
                    AntigravityTokenStatus.INVALID -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            if (account.cooldownUntil > System.currentTimeMillis()) {
                Text(
                    stringResource(R.string.antigravity_cooling_down, TIME_FORMAT.format(Instant.ofEpochMilli(account.cooldownUntil))),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (account.quotaUpdatedAt > 0) {
                Text(stringResource(R.string.antigravity_quota_title), style = MaterialTheme.typography.titleSmall)
                if (account.quotas.isEmpty()) {
                    Text(
                        stringResource(R.string.antigravity_quota_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    account.quotas.forEach { AntigravityQuotaRow(it) }
                }
            }

            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.antigravity_refresh)) }
                TextButton(onClick = onReauthenticate) { Text(stringResource(R.string.antigravity_reauthenticate)) }
                TextButton(onClick = { showDeleteConfirmation = true }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AntigravityQuotaRow(quota: AntigravityModelQuota) {
    val remaining = (quota.remainingFraction * 100).coerceIn(0.0, 100.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                quota.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.antigravity_percent_remaining, remaining.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { (remaining / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        if (quota.resetAt > 0) {
            Text(
                text = stringResource(R.string.antigravity_resets_at, TIME_FORMAT.format(Instant.ofEpochMilli(quota.resetAt))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
