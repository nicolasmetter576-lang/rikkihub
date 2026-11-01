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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.ui.pages.assistant.detail.CustomHeaders
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.devin.DevinAccount
import me.rerere.rikkihub.data.devin.DevinAccountRepository
import me.rerere.rikkihub.data.devin.DevinAccountStatus
import me.rerere.rikkihub.data.devin.DevinOAuthManager
import me.rerere.rikkihub.data.devin.DevinOAuthStatus
import me.rerere.rikkihub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun DevinProviderConfigure(
    provider: ProviderSetting.Devin,
    onEdit: (ProviderSetting.Devin) -> Unit,
) {
    val repository = koinInject<DevinAccountRepository>()
    val oauthManager = koinInject<DevinOAuthManager>()
    val providerManager = koinInject<ProviderManager>()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val oauthStatus by oauthManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val canEnable = accounts.any { it.enabled && it.status != DevinAccountStatus.INVALID }
    var clientVersion by remember(provider.id) { mutableStateOf(provider.clientVersion) }
    var manualInput by remember { mutableStateOf("") }

    LaunchedEffect(oauthStatus) {
        when (val status = oauthStatus) {
            is DevinOAuthStatus.Success -> {
                runCatching {
                    providerManager.getProviderByType(provider).listModels(provider)
                }.onSuccess { models ->
                    onEdit(provider.copy(models = mergeDevinModels(provider.models, models)))
                }
                toaster.show(
                    context.getString(R.string.devin_oauth_success),
                    type = ToastType.Success,
                )
                oauthManager.consumeResult()
            }

            is DevinOAuthStatus.Error -> {
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

        Text(
            text = provider.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.devin_provider_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = oauthManager::startLogin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.devin_sign_in))
        }

        OutlinedTextField(
            value = manualInput,
            onValueChange = { manualInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.devin_manual_token_hint)) },
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
                Text(
                    stringResource(R.string.devin_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (canEnable) {
                        stringResource(R.string.devin_round_robin_description)
                    } else {
                        stringResource(R.string.devin_sign_in_required)
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
            label = { Text(stringResource(R.string.devin_client_version)) },
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = { onEdit(provider.copy(clientVersion = clientVersion.trim())) },
                    enabled = clientVersion.trim() != provider.clientVersion &&
                        clientVersion.isNotBlank(),
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
                text = stringResource(R.string.devin_accounts_count, accounts.size),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { repository.refreshAll() }
                            .onFailure {
                                toaster.show(
                                    it.message ?: context.getString(
                                        R.string.devin_refresh_failed
                                    ),
                                    type = ToastType.Error,
                                )
                            }
                    }
                },
                enabled = accounts.isNotEmpty(),
            ) {
                Text(stringResource(R.string.devin_check_status))
            }
        }

        if (accounts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.devin_no_accounts),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            accounts.forEach { account ->
                DevinAccountCard(
                    account = account,
                    onEnabledChange = { enabled ->
                        scope.launch { repository.setEnabled(account.id, enabled) }
                    },
                    onRefresh = {
                        scope.launch {
                            runCatching { repository.refreshAccount(account.id) }
                                .onFailure {
                                    toaster.show(
                                        it.message ?: context.getString(
                                            R.string.devin_refresh_failed
                                        ),
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

internal fun mergeDevinModels(existing: List<Model>, refreshed: List<Model>): List<Model> {
    val refreshedByModelId = refreshed.associateBy(Model::modelId)
    val merged = existing.map { model ->
        refreshedByModelId[model.modelId]?.let { refreshedModel ->
            model.copy(
                inputModalities = refreshedModel.inputModalities,
                outputModalities = refreshedModel.outputModalities,
                abilities = refreshedModel.abilities,
            )
        } ?: model
    }
    val existingModelIds = existing.mapTo(mutableSetOf(), Model::modelId)
    return merged + refreshed.filterNot { it.modelId in existingModelIds }
}

@Composable
private fun DevinAccountCard(
    account: DevinAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauthenticate: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.devin_remove_account_title)) },
            text = { Text(stringResource(R.string.devin_remove_account_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) {
                    Text(
                        stringResource(R.string.devin_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
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
                    Text(
                        account.userName.ifBlank { account.email.ifBlank { "Devin" } },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (account.email.isNotBlank()) {
                        Text(
                            account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (account.plan.isNotBlank()) {
                        Text(
                            account.plan,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = account.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Text(
                text = when (account.status) {
                    DevinAccountStatus.ACTIVE -> stringResource(R.string.devin_token_available)
                    DevinAccountStatus.INVALID -> stringResource(R.string.devin_token_unavailable)
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (account.status) {
                    DevinAccountStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    DevinAccountStatus.INVALID -> MaterialTheme.colorScheme.error
                },
            )

            DevinQuotaRow(
                name = stringResource(R.string.devin_quota_daily),
                remainingPercent = account.dailyQuotaRemainingPercent,
                resetsAt = account.dailyQuotaResetAt,
            )
            DevinQuotaRow(
                name = stringResource(R.string.devin_quota_weekly),
                remainingPercent = account.weeklyQuotaRemainingPercent,
                resetsAt = account.weeklyQuotaResetAt,
            )

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.devin_refresh))
                }
                TextButton(onClick = onReauthenticate) {
                    Text(stringResource(R.string.devin_reauthenticate))
                }
                TextButton(onClick = { showDeleteConfirmation = true }) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DevinQuotaRow(
    name: String,
    remainingPercent: Long,
    resetsAt: Long,
) {
    val remaining = remainingPercent.toDouble().coerceIn(0.0, 100.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.devin_percent_remaining, remaining.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { (remaining / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        if (resetsAt > 0) {
            Text(
                text = stringResource(
                    R.string.devin_resets_at,
                    RESET_FORMAT.format(Instant.ofEpochSecond(resetsAt)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val RESET_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
