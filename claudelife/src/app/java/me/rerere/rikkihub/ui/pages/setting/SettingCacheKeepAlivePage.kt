package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.ai.claudelife.CacheKeepAliveConfigError
import me.rerere.ai.claudelife.parseCacheKeepAlivePositiveInt
import me.rerere.ai.claudelife.validate
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.Switch
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingCacheKeepAlivePage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    var intervalText by remember(settings.cacheKeepAliveConfig.intervalSeconds) {
        mutableStateOf(settings.cacheKeepAliveConfig.intervalSeconds.toString())
    }
    var maxDurationText by remember(settings.cacheKeepAliveConfig.maxDurationMinutes) {
        mutableStateOf(settings.cacheKeepAliveConfig.maxDurationMinutes.toString())
    }

    fun save() {
        val interval = parseCacheKeepAlivePositiveInt(intervalText)
        if (interval == null) {
            toaster.show(
                context.getString(R.string.setting_cache_keep_alive_error_interval),
                type = ToastType.Error,
            )
            return
        }
        val maxDuration = parseCacheKeepAlivePositiveInt(maxDurationText)
        if (maxDuration == null) {
            toaster.show(
                context.getString(R.string.setting_cache_keep_alive_error_max_duration),
                type = ToastType.Error,
            )
            return
        }
        val newConfig = settings.cacheKeepAliveConfig.copy(
            intervalSeconds = interval,
            maxDurationMinutes = maxDuration,
        )
        when (newConfig.validate()) {
            CacheKeepAliveConfigError.INTERVAL_NOT_POSITIVE -> toaster.show(
                context.getString(R.string.setting_cache_keep_alive_error_interval),
                type = ToastType.Error,
            )
            CacheKeepAliveConfigError.MAX_DURATION_NOT_POSITIVE -> toaster.show(
                context.getString(R.string.setting_cache_keep_alive_error_max_duration),
                type = ToastType.Error,
            )
            CacheKeepAliveConfigError.MAX_DURATION_NOT_GREATER_THAN_INTERVAL -> toaster.show(
                context.getString(R.string.setting_cache_keep_alive_error_max_le_interval),
                type = ToastType.Error,
            )
            null -> {
                // 只改这页编辑的两项，开关等其余字段取最新值
                vm.updateSettings { latest ->
                    latest.copy(
                        cacheKeepAliveConfig = latest.cacheKeepAliveConfig.copy(
                            intervalSeconds = newConfig.intervalSeconds,
                            maxDurationMinutes = newConfig.maxDurationMinutes,
                        )
                    )
                }
                toaster.show(
                    context.getString(R.string.setting_cache_keep_alive_saved),
                    type = ToastType.Success,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_cache_keep_alive_title)) },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Enable switch
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_cache_keep_alive_enable)) },
                        supportingContent = { Text(stringResource(R.string.setting_cache_keep_alive_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.cacheKeepAliveConfig.enabled,
                                onCheckedChange = {
                                    vm.updateSettings { latest -> latest.copy(
                                            cacheKeepAliveConfig = latest.cacheKeepAliveConfig.copy(enabled = it)
                                        ) }
                                }
                            )
                        }
                    )
                }
            }

            // Interval & max duration fields + save button
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it },
                        label = { Text(stringResource(R.string.setting_cache_keep_alive_interval)) },
                        supportingText = { Text(stringResource(R.string.setting_cache_keep_alive_interval_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = maxDurationText,
                        onValueChange = { maxDurationText = it },
                        label = { Text(stringResource(R.string.setting_cache_keep_alive_max_duration)) },
                        supportingText = { Text(stringResource(R.string.setting_cache_keep_alive_max_duration_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(
                        onClick = { save() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setting_cache_keep_alive_save))
                    }
                }
            }

            // Note
            item {
                Text(
                    text = stringResource(R.string.setting_cache_keep_alive_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // Bottom spacer
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
