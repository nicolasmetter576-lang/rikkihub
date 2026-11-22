package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.ProviderSpendLimit
import me.rerere.rikkihub.data.ai.UsageStatsService
import me.rerere.rikkihub.data.ai.formatSpendLimit
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import org.koin.compose.koinInject
import me.rerere.rikkihub.ui.components.ui.CompactListItem

/**
 * 提供商页顶部的一行：消费上限与当前状态，点开设置、取消或解除。
 * 直接写设置（不跟页面的「保存」走）：解除限制要立即生效。
 */
@Composable
internal fun ProviderSpendLimitRow(provider: ProviderSetting) {
    val settingsStore: SettingsStore = koinInject()
    val stats: UsageStatsService = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val limit = settings.providerSpendLimits[provider.id]
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val spent by produceState<Double?>(null, limit, settings.providers) {
        value = limit?.let { stats.providerCost(settings.providers, provider.id.toString(), it.since) }
    }

    fun save(update: ProviderSpendLimit?) {
        scope.launch {
            settingsStore.update { latest ->
                latest.copy(
                    providerSpendLimits = if (update == null) latest.providerSpendLimits - provider.id
                    else latest.providerSpendLimits + (provider.id to update)
                )
            }
        }
        editing = false
    }

    CompactListItem(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider_spend_limit")
            .clickable { editing = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(R.string.provider_spend_limit_title)) },
        supportingContent = {
            Text(
                text = when {
                    limit == null -> stringResource(R.string.provider_spend_limit_none)
                    limit.blocked -> stringResource(R.string.provider_spend_limit_blocked, formatSpendLimit(limit.limit))
                    else -> stringResource(
                        R.string.provider_spend_limit_status,
                        spent?.let(::formatSpendLimit) ?: "…",
                        formatSpendLimit(limit.limit),
                    )
                },
                color = if (limit?.blocked == true) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        trailingContent = if (limit?.blocked == true) {
            {
                TextButton(onClick = { save(limit.copy(blocked = false, since = System.currentTimeMillis())) }) {
                    Text(stringResource(R.string.provider_spend_limit_lift))
                }
            }
        } else null,
    )

    if (editing) {
        var input by remember { mutableStateOf(limit?.limit?.let { formatSpendLimit(it).removePrefix("$") }.orEmpty()) }
        val value = input.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 1_000_000 }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.provider_spend_limit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.provider_spend_limit_desc), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.provider_spend_limit_input)) },
                        singleLine = true,
                        isError = input.isNotBlank() && value == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("provider_spend_limit_input"),
                    )
                }
            },
            dismissButton = {
                if (limit != null) {
                    TextButton(onClick = { save(null) }) { Text(stringResource(R.string.provider_spend_limit_remove)) }
                } else {
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = value != null,
                    // 改上限不清零已累计的花费；新设的上限从现在开始算，解除状态一并清掉
                    onClick = { save(ProviderSpendLimit(limit = value!!, since = limit?.since ?: System.currentTimeMillis())) },
                ) { Text(stringResource(R.string.setting_provider_page_save)) }
            },
        )
    }
}
