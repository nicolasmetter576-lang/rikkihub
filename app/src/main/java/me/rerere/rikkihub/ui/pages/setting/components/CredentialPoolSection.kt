package me.rerere.rikkihub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.CredentialRotationStrategy
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VertexServiceAccount
import me.rerere.ai.provider.displayLabel
import me.rerere.ai.provider.effectiveApiKeyEntries
import me.rerere.ai.provider.effectiveServiceAccounts
import me.rerere.ai.provider.maskApiKeyPreview
import me.rerere.ai.provider.parseVertexServiceAccountJsons
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.resolvedLocation
import me.rerere.ai.provider.rotationStrategyOrDefault
import me.rerere.ai.provider.splitLegacyApiKeys
import me.rerere.ai.util.CredentialHealthStatus
import me.rerere.ai.util.CredentialRuntimeSnapshot
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.creator.runtime.CreatorProviderTester
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.amoledOutlineBorder
import me.rerere.rikkihub.ui.theme.JetbrainsMono
import me.rerere.rikkihub.ui.theme.extendColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val CREDENTIAL_TEST_TIMEOUT_MS = 20_000L

@Composable
internal fun RotationStrategySelector(
    strategy: CredentialRotationStrategy,
    onSelect: (CredentialRotationStrategy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.setting_provider_page_rotation_strategy),
            style = MaterialTheme.typography.titleSmall,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CredentialRotationStrategy.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = CredentialRotationStrategy.entries.size,
                    ),
                    selected = strategy == option,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            when (option) {
                                CredentialRotationStrategy.ROUND_ROBIN ->
                                    stringResource(R.string.setting_provider_page_rotation_round_robin)
                                CredentialRotationStrategy.FAILOVER ->
                                    stringResource(R.string.setting_provider_page_rotation_failover)
                            }
                        )
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.setting_provider_page_rotation_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberCredentialSnapshots(
    providerId: String,
    credentialIds: List<String>,
): Map<String, CredentialRuntimeSnapshot> {
    val providerManager = koinInject<ProviderManager>()
    val pool = providerManager.credentialPool
    val version by pool.stateVersion.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snapshots = remember(version, nowMillis, providerId, credentialIds) {
        pool.snapshotsFor(providerId, credentialIds)
    }
    val hasActiveCooldown = snapshots.values.any { it.status != CredentialHealthStatus.HEALTHY }
    LaunchedEffect(hasActiveCooldown, version) {
        while (hasActiveCooldown) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    return snapshots
}

@Composable
private fun CredentialStatusBadge(
    enabled: Boolean,
    snapshot: CredentialRuntimeSnapshot?,
) {
    val label: String
    val containerColor = when {
        !enabled -> {
            label = stringResource(R.string.setting_provider_page_cred_disabled)
            MaterialTheme.colorScheme.surfaceVariant
        }
        snapshot?.status == CredentialHealthStatus.AUTH_ERROR -> {
            val sec = ((snapshot.remainingMillis + 999L) / 1000L).coerceAtLeast(1L).toInt()
            label = stringResource(R.string.setting_provider_page_cred_status_auth_error, sec)
            MaterialTheme.extendColors.red2
        }
        snapshot?.status == CredentialHealthStatus.COOLING_DOWN -> {
            val sec = ((snapshot.remainingMillis + 999L) / 1000L).coerceAtLeast(1L).toInt()
            label = stringResource(R.string.setting_provider_page_cred_status_cooling, sec)
            MaterialTheme.extendColors.orange2
        }
        else -> {
            label = stringResource(R.string.setting_provider_page_cred_status_healthy)
            MaterialTheme.extendColors.green2
        }
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        snapshot?.status == CredentialHealthStatus.AUTH_ERROR -> MaterialTheme.extendColors.red8
        snapshot?.status == CredentialHealthStatus.COOLING_DOWN -> MaterialTheme.extendColors.orange8
        else -> MaterialTheme.extendColors.green8
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApiKeyPoolSection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val providerManager = koinInject<ProviderManager>()
    val entries = remember(provider) { provider.effectiveApiKeyEntries() }
    val enabledCount = entries.count { it.enabled && it.key.isNotBlank() }
    val snapshots = rememberCredentialSnapshots(
        providerId = provider.id.toString(),
        credentialIds = entries.map { it.id },
    )
    val anyCooldown = snapshots.values.any { it.status != CredentialHealthStatus.HEALTHY }

    val legacyMode = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKeys.isEmpty() && entries.isNotEmpty()
        is ProviderSetting.Google -> provider.apiKeys.isEmpty() && entries.isNotEmpty()
        is ProviderSetting.Claude -> provider.apiKeys.isEmpty() && entries.isNotEmpty()
        else -> false
    }

    fun commitEntries(nextList: List<ApiKeyEntry>) {
        val updated: ProviderSetting = when (provider) {
            is ProviderSetting.OpenAI -> provider.copy(apiKey = "", apiKeys = nextList)
            is ProviderSetting.Google -> provider.copy(apiKey = "", apiKeys = nextList)
            is ProviderSetting.Claude -> provider.copy(apiKey = "", apiKeys = nextList)
            else -> provider
        }
        onEdit(updated)
    }

    fun updateStrategy(strategy: CredentialRotationStrategy) {
        val updated: ProviderSetting = when (provider) {
            is ProviderSetting.OpenAI -> provider.copy(rotationStrategy = strategy)
            is ProviderSetting.Google -> provider.copy(rotationStrategy = strategy)
            is ProviderSetting.Claude -> provider.copy(rotationStrategy = strategy)
            else -> provider
        }
        onEdit(updated)
    }

    var editingEntry by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var showBatchPasteDialog by remember { mutableStateOf(false) }
    var testingCredId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entries.size > 1) {
            RotationStrategySelector(
                strategy = provider.rotationStrategyOrDefault(),
                onSelect = ::updateStrategy,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_api_key_pool, enabledCount, entries.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            if (anyCooldown) {
                TextButton(
                    onClick = { providerManager.credentialPool.resetProvider(provider.id.toString()) },
                ) {
                    Icon(HugeIcons.Refresh03, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.setting_provider_page_reset_cooldown))
                }
            }
        }

        if (legacyMode) {
            Text(
                text = stringResource(R.string.setting_provider_page_legacy_single_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            OutlinedButton(
                onClick = {
                    isCreatingNew = true
                    editingEntry = ApiKeyEntry(id = Uuid.random().toString(), key = "", label = "", enabled = true)
                },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.setting_provider_page_add_api_key))
            }
            OutlinedButton(
                onClick = { showBatchPasteDialog = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            ) {
                Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.setting_provider_page_batch_paste_keys))
            }
        }

        if (entries.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
                border = amoledOutlineBorder(),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_cred_api_key_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            entries.forEachIndexed { index, entry ->
                val snapshot = snapshots[entry.id]
                val preview = maskApiKeyPreview(entry.key)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                    border = amoledOutlineBorder(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = entry.displayLabel(index),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    CredentialStatusBadge(enabled = entry.enabled, snapshot = snapshot)
                                }
                                if (preview.isNotEmpty() && entry.label.isNotBlank()) {
                                    Text(
                                        text = preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = JetbrainsMono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { checked ->
                                    commitEntries(entries.map { if (it.id == entry.id) it.copy(enabled = checked) else it })
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = testingCredId == null && entry.key.isNotBlank(),
                                onClick = {
                                    testingCredId = entry.id
                                    scope.launch {
                                        val testEntry = entry.copy(enabled = true)
                                        val isolated: ProviderSetting = when (provider) {
                                            is ProviderSetting.OpenAI -> provider.copy(apiKey = "", apiKeys = listOf(testEntry))
                                            is ProviderSetting.Google -> provider.copy(apiKey = "", apiKeys = listOf(testEntry), vertexAI = false, useServiceAccount = false)
                                            is ProviderSetting.Claude -> provider.copy(apiKey = "", apiKeys = listOf(testEntry))
                                            else -> provider
                                        }
                                        val secrets = (CreatorProviderTester.secretsOf(provider) + entry.key)
                                            .filter { it.length >= 6 }
                                            .distinct()
                                            .sortedByDescending { it.length }
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                withTimeout(CREDENTIAL_TEST_TIMEOUT_MS) {
                                                    providerManager.getProviderByType(isolated).listModels(isolated)
                                                }
                                            }
                                        }
                                        testingCredId = null
                                        if (result.isSuccess) {
                                            providerManager.credentialPool.resetCredential(provider.id.toString(), entry.id)
                                            toaster.show(
                                                context.getString(R.string.setting_provider_page_cred_test_ok),
                                                type = ToastType.Success,
                                            )
                                        } else {
                                            val err = result.exceptionOrNull()
                                            val masked = CreatorProviderTester.mask(
                                                err?.message ?: err?.javaClass?.simpleName ?: "Unknown error",
                                                secrets,
                                            )
                                            toaster.show(
                                                context.getString(R.string.setting_provider_page_cred_test_failed, masked),
                                                type = ToastType.Error,
                                            )
                                        }
                                    }
                                },
                            ) {
                                if (testingCredId == entry.id) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(HugeIcons.Connect, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text(stringResource(R.string.setting_provider_page_test_credential))
                                }
                            }
                            IconButton(
                                onClick = {
                                    isCreatingNew = false
                                    editingEntry = entry
                                },
                            ) {
                                Icon(HugeIcons.Tools, contentDescription = null)
                            }
                            IconButton(
                                onClick = {
                                    commitEntries(entries.filterNot { it.id == entry.id })
                                },
                            ) {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }

    editingEntry?.let { current ->
        var label by remember(current.id) { mutableStateOf(current.label) }
        var key by remember(current.id) { mutableStateOf(current.key) }
        var keyVisible by remember(current.id) { mutableStateOf(isCreatingNew) }
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            title = { Text(stringResource(R.string.setting_provider_page_api_key)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.setting_provider_page_cred_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim() },
                        label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                        maxLines = 4,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEntry = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = key.isNotBlank(),
                    onClick = {
                        val saved = current.copy(label = label.trim(), key = key.trim())
                        val next = if (isCreatingNew) {
                            entries + saved
                        } else {
                            entries.map { if (it.id == saved.id) saved else it }
                        }
                        commitEntries(next)
                        editingEntry = null
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_save))
                }
            },
        )
    }

    if (showBatchPasteDialog) {
        var rawText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBatchPasteDialog = false },
            title = { Text(stringResource(R.string.setting_provider_page_batch_paste_keys)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_batch_paste_keys_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        minLines = 4,
                        maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchPasteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = rawText.isNotBlank(),
                    onClick = {
                        val existingKeys = entries.map { it.key.trim() }.toSet()
                        val newKeys = splitLegacyApiKeys(rawText).filter { it !in existingKeys }
                        if (newKeys.isNotEmpty()) {
                            val appended = entries + newKeys.map { k -> ApiKeyEntry(key = k) }
                            commitEntries(appended)
                        }
                        toaster.show(
                            context.getString(R.string.setting_provider_page_import_keys_result, newKeys.size),
                            type = ToastType.Success,
                        )
                        showBatchPasteDialog = false
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_import_btn))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VertexServiceAccountPoolSection(
    provider: ProviderSetting.Google,
    onEdit: (ProviderSetting.Google) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val providerManager = koinInject<ProviderManager>()
    val accounts = remember(provider) { provider.effectiveServiceAccounts() }
    val enabledCount = accounts.count { it.enabled && it.privateKey.isNotBlank() && it.projectId.isNotBlank() && it.serviceAccountEmail.isNotBlank() }
    val snapshots = rememberCredentialSnapshots(
        providerId = provider.id.toString(),
        credentialIds = accounts.map { it.id },
    )
    val anyCooldown = snapshots.values.any { it.status != CredentialHealthStatus.HEALTHY }
    val legacyMode = provider.serviceAccounts.isEmpty() && accounts.isNotEmpty()

    fun commitAccounts(nextList: List<VertexServiceAccount>) {
        onEdit(
            provider.copy(
                privateKey = "",
                serviceAccountEmail = "",
                projectId = "",
                serviceAccounts = nextList,
            )
        )
    }

    fun importAccounts(imported: List<VertexServiceAccount>, skipped: Int) {
        val updated = accounts + imported
        commitAccounts(updated)
        val msg = if (skipped > 0) {
            context.getString(R.string.setting_provider_page_import_sa_result_skipped, imported.size, skipped)
        } else {
            context.getString(R.string.setting_provider_page_import_sa_result, imported.size)
        }
        toaster.show(msg, type = if (imported.isNotEmpty()) ToastType.Success else ToastType.Warning)
    }

    val multiJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val collected = mutableListOf<VertexServiceAccount>()
            var totalSkipped = 0
            for (uri in uris) {
                val parsed = runCatching {
                    val content = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText()
                        .orEmpty()
                    parseVertexServiceAccountJsons(content)
                }.getOrNull()
                if (parsed == null) {
                    totalSkipped++
                } else {
                    collected += parsed.accounts
                    totalSkipped += parsed.skippedCount
                }
            }
            withContext(Dispatchers.Main) {
                importAccounts(collected, totalSkipped)
            }
        }
    }

    var editingAccount by remember { mutableStateOf<VertexServiceAccount?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var testingCredId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accounts.size > 1) {
            RotationStrategySelector(
                strategy = provider.rotationStrategy,
                onSelect = { onEdit(provider.copy(rotationStrategy = it)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_sa_pool, enabledCount, accounts.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            if (anyCooldown) {
                TextButton(
                    onClick = { providerManager.credentialPool.resetProvider(provider.id.toString()) },
                ) {
                    Icon(HugeIcons.Refresh03, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.setting_provider_page_reset_cooldown))
                }
            }
        }

        if (legacyMode) {
            Text(
                text = stringResource(R.string.setting_provider_page_legacy_single_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { multiJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.setting_provider_page_import_sa_files))
            }
            OutlinedButton(
                onClick = { showPasteDialog = true },
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.setting_provider_page_paste_sa_json))
            }
            OutlinedButton(
                onClick = {
                    isCreatingNew = true
                    editingAccount = VertexServiceAccount(id = Uuid.random().toString())
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.setting_tts_page_add))
            }
        }

        if (accounts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
                border = amoledOutlineBorder(),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_cred_sa_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            accounts.forEachIndexed { index, account ->
                val snapshot = snapshots[account.id]
                val effectiveLocation = account.resolvedLocation(provider.location)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                    border = amoledOutlineBorder(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = account.displayLabel(index),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    CredentialStatusBadge(enabled = account.enabled, snapshot = snapshot)
                                }
                                Text(
                                    text = "${account.projectId.ifBlank { "-" }} · $effectiveLocation",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetbrainsMono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (account.serviceAccountEmail.isNotBlank()) {
                                    Text(
                                        text = account.serviceAccountEmail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Switch(
                                checked = account.enabled,
                                onCheckedChange = { checked ->
                                    commitAccounts(accounts.map { if (it.id == account.id) it.copy(enabled = checked) else it })
                                },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = testingCredId == null &&
                                    account.serviceAccountEmail.isNotBlank() &&
                                    account.privateKey.isNotBlank(),
                                onClick = {
                                    testingCredId = account.id
                                    scope.launch {
                                        val googleImpl = providerManager.getProviderByType(provider) as GoogleProvider
                                        val secrets = (CreatorProviderTester.secretsOf(provider) +
                                            account.privateKey.split(Regex("[\\s,]+")))
                                            .map { it.trim() }
                                            .filter { it.length >= 6 }
                                            .distinct()
                                            .sortedByDescending { it.length }
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                withTimeout(CREDENTIAL_TEST_TIMEOUT_MS) {
                                                    googleImpl.verifyServiceAccountToken(account)
                                                }
                                            }
                                        }
                                        testingCredId = null
                                        if (result.isSuccess) {
                                            providerManager.credentialPool.resetCredential(provider.id.toString(), account.id)
                                            toaster.show(
                                                context.getString(R.string.setting_provider_page_cred_test_ok),
                                                type = ToastType.Success,
                                            )
                                        } else {
                                            val err = result.exceptionOrNull()
                                            val masked = CreatorProviderTester.mask(
                                                err?.message ?: err?.javaClass?.simpleName ?: "Unknown error",
                                                secrets,
                                            )
                                            toaster.show(
                                                context.getString(R.string.setting_provider_page_cred_test_failed, masked),
                                                type = ToastType.Error,
                                            )
                                        }
                                    }
                                },
                            ) {
                                if (testingCredId == account.id) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(HugeIcons.Connect, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text(stringResource(R.string.setting_provider_page_test_credential))
                                }
                            }
                            TextButton(
                                onClick = {
                                    isCreatingNew = true
                                    editingAccount = account.copy(
                                        id = Uuid.random().toString(),
                                        location = "",
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.setting_provider_page_duplicate_for_location))
                            }
                            IconButton(
                                onClick = {
                                    isCreatingNew = false
                                    editingAccount = account
                                },
                            ) {
                                Icon(HugeIcons.Tools, contentDescription = null)
                            }
                            IconButton(
                                onClick = {
                                    commitAccounts(accounts.filterNot { it.id == account.id })
                                },
                            ) {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }

    editingAccount?.let { current ->
        var label by remember(current.id) { mutableStateOf(current.label) }
        var email by remember(current.id) { mutableStateOf(current.serviceAccountEmail) }
        var projectId by remember(current.id) { mutableStateOf(current.projectId) }
        var location by remember(current.id) { mutableStateOf(current.location) }
        var privateKey by remember(current.id) { mutableStateOf(current.privateKey) }
        var privateKeyVisible by remember(current.id) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { editingAccount = null },
            title = { Text(stringResource(R.string.setting_provider_page_use_service_account)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.setting_provider_page_cred_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = projectId,
                        onValueChange = { projectId = it.trim() },
                        label = { Text(stringResource(R.string.setting_provider_page_project_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim() },
                        label = { Text(stringResource(R.string.setting_provider_page_service_account_email)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it.trim() },
                        label = { Text(stringResource(R.string.setting_provider_page_sa_location_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it.trim() },
                        label = { Text(stringResource(R.string.setting_provider_page_private_key)) },
                        minLines = 3,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                        visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { privateKeyVisible = !privateKeyVisible }) {
                                Icon(
                                    if (privateKeyVisible) HugeIcons.ViewOff else HugeIcons.View,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { editingAccount = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = projectId.isNotBlank() && email.isNotBlank() && privateKey.isNotBlank(),
                    onClick = {
                        val saved = current.copy(
                            label = label.trim(),
                            projectId = projectId.trim(),
                            serviceAccountEmail = email.trim(),
                            location = location.trim(),
                            privateKey = privateKey.trim(),
                        )
                        val next = if (isCreatingNew) {
                            accounts + saved
                        } else {
                            accounts.map { if (it.id == saved.id) saved else it }
                        }
                        commitAccounts(next)
                        editingAccount = null
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_save))
                }
            },
        )
    }

    if (showPasteDialog) {
        var rawJson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text(stringResource(R.string.setting_provider_page_paste_sa_json)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_paste_sa_json_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = rawJson,
                        onValueChange = { rawJson = it },
                        minLines = 5,
                        maxLines = 9,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = rawJson.isNotBlank(),
                    onClick = {
                        val parsed = parseVertexServiceAccountJsons(rawJson)
                        importAccounts(parsed.accounts, parsed.skippedCount)
                        showPasteDialog = false
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_import_btn))
                }
            },
        )
    }
}
