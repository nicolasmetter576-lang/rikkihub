package me.rerere.rikkihub.ui.pages.extensions

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.prompt.Hint
import me.rerere.rikkihub.ui.components.prompt.IntField
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.tavern.worldinfo.WorldInfoSettings
import org.koin.androidx.compose.koinViewModel
import me.rerere.rikkihub.ui.theme.AppSpacing

/**
 * 全局世界书设置：扫描深度、预算、递归、大小写、整词……条目上没有单独设置的项都按这里。
 * 字段与酒馆的 `world_info_*` 一一对应。依赖角色卡的「角色书策略」和还没有提示界面的「超预算提醒」不在这里，已有的值保留。
 */
@Composable
fun SettingWorldInfoPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val info = settings.tavern.worldInfo
    fun change(transform: (WorldInfoSettings) -> WorldInfoSettings) {
        vm.updateSettings { latest -> latest.copy(tavern = latest.tavern.copy(worldInfo = transform(latest.tavern.worldInfo))) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.worldinfo_global_settings)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = AppSpacing.sm)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IntField(
                        value = info.depth,
                        onCommit = { value -> change { it.copy(depth = value) } },
                        label = stringResource(R.string.worldinfo_set_scan_depth),
                        modifier = Modifier.fillMaxWidth(),
                        min = 0,
                        supportingText = stringResource(R.string.worldinfo_set_scan_depth_hint),
                    )
                    IntField(
                        value = info.budget,
                        onCommit = { value -> change { it.copy(budget = value.coerceIn(1, 100)) } },
                        label = stringResource(R.string.worldinfo_set_budget),
                        modifier = Modifier.fillMaxWidth(),
                        min = 1,
                        supportingText = stringResource(R.string.worldinfo_set_budget_hint),
                    )
                    IntField(
                        value = info.budgetCap,
                        onCommit = { value -> change { it.copy(budgetCap = value) } },
                        label = stringResource(R.string.worldinfo_set_budget_cap),
                        modifier = Modifier.fillMaxWidth(),
                        min = 0,
                        supportingText = stringResource(R.string.worldinfo_set_budget_cap_hint),
                    )
                    IntField(
                        value = info.minActivations,
                        onCommit = { value -> change { it.copy(minActivations = value) } },
                        label = stringResource(R.string.worldinfo_set_min_activations),
                        modifier = Modifier.fillMaxWidth(),
                        min = 0,
                        supportingText = stringResource(R.string.worldinfo_set_min_activations_hint),
                    )
                    IntField(
                        value = info.minActivationsDepthMax,
                        onCommit = { value -> change { it.copy(minActivationsDepthMax = value) } },
                        label = stringResource(R.string.worldinfo_set_min_activations_depth),
                        modifier = Modifier.fillMaxWidth(),
                        min = 0,
                        supportingText = stringResource(R.string.worldinfo_set_min_activations_depth_hint),
                    )
                    IntField(
                        value = info.maxRecursionSteps,
                        onCommit = { value -> change { it.copy(maxRecursionSteps = value) } },
                        label = stringResource(R.string.worldinfo_set_max_recursion),
                        modifier = Modifier.fillMaxWidth(),
                        min = 0,
                        supportingText = stringResource(R.string.worldinfo_set_max_recursion_hint),
                    )
                }
            }
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingSwitch(R.string.worldinfo_set_recursive, R.string.worldinfo_set_recursive_desc, info.recursive) { on ->
                        change { it.copy(recursive = on) }
                    }
                    SettingSwitch(R.string.worldinfo_set_include_names, R.string.worldinfo_set_include_names_desc, info.includeNames) { on ->
                        change { it.copy(includeNames = on) }
                    }
                    SettingSwitch(R.string.prompt_page_case_sensitive, R.string.worldinfo_set_default_desc, info.caseSensitive) { on ->
                        change { it.copy(caseSensitive = on) }
                    }
                    SettingSwitch(R.string.worldinfo_set_whole_words, R.string.worldinfo_set_default_desc, info.matchWholeWords) { on ->
                        change { it.copy(matchWholeWords = on) }
                    }
                    SettingSwitch(R.string.worldinfo_set_group_scoring, R.string.worldinfo_set_group_scoring_desc, info.useGroupScoring) { on ->
                        change { it.copy(useGroupScoring = on) }
                    }
                }
            }
            TextButton(
                onClick = {
                    // 恢复默认只动本页的字段，角色书策略与超预算提醒保持原样
                    change { current ->
                        WorldInfoSettings().copy(
                            characterStrategy = current.characterStrategy,
                            overflowAlert = current.overflowAlert,
                        )
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.worldinfo_set_reset)) }
        }
    }
}

@Composable
private fun SettingSwitch(title: Int, description: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
