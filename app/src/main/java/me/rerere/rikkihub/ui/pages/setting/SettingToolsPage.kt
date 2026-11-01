package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCategory
import me.rerere.rikkihub.data.ai.tools.MAX_PARALLEL_TOOL_CALLS
import me.rerere.rikkihub.data.ai.tools.ToolConcurrencyDefaults
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.ai.tools.isBuiltinParallel
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.resolvedDescription
import me.rerere.rikkihub.data.ai.tools.resolvedName
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingToolsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tools)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("tool_execution") {
                val execution = settings.toolExecution
                // 在最新的配置上只改这一个开关/数值
                fun save(transform: (ToolExecutionConfig) -> ToolExecutionConfig) =
                    vm.updateSettings { latest -> latest.copy(toolExecution = transform(latest.toolExecution)) }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tools_execution_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_tools_parallel)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tools_parallel_desc)) },
                        trailingContent = {
                            Switch(
                                checked = execution.parallel,
                                onCheckedChange = { checked -> save { it.copy(parallel = checked) } },
                            )
                        },
                    )
                    if (execution.parallel) {
                        item(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        R.string.setting_page_tools_max_parallel,
                                        execution.effectiveLimit(),
                                    )
                                )
                            },
                            supportingContent = {
                                Slider(
                                    value = execution.effectiveLimit().toFloat(),
                                    onValueChange = { value ->
                                        val next = value.toInt().coerceIn(1, MAX_PARALLEL_TOOL_CALLS)
                                        if (next != execution.maxParallel) save { it.copy(maxParallel = next) }
                                    },
                                    valueRange = 1f..MAX_PARALLEL_TOOL_CALLS.toFloat(),
                                    steps = MAX_PARALLEL_TOOL_CALLS - 2,
                                )
                            },
                        )
                    }
                }
            }
            BuiltinToolCatalog.grouped().forEach { (category, specs) ->
                item(category.name) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(categoryTitle(category)) },
                    ) {
                        specs.forEach { spec ->
                            item(
                                onClick = { navController.navigate(Screen.SettingToolDetail(spec.id)) },
                                headlineContent = {
                                    Text(
                                        if (spec.isTool) spec.resolvedName(settings.toolOverrides)
                                        else stringResource(R.string.setting_page_tools_workspace_reminder)
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = spec.resolvedDescription(settings.toolOverrides),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingToolDetailPage(id: String, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spec = BuiltinToolCatalog[id]
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        spec?.let {
                            if (it.isTool) it.resolvedName(settings.toolOverrides)
                            else stringResource(R.string.setting_page_tools_workspace_reminder)
                        } ?: id
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        if (spec == null) {
            Text(
                text = stringResource(R.string.setting_page_tools_not_found),
                modifier = Modifier.padding(contentPadding).padding(16.dp),
            )
            return@Scaffold
        }

        val override = settings.toolOverrides[spec.id]
        val name = override?.name ?: spec.defaultName
        val description = override?.description ?: spec.defaultDescription
        val systemPrompt = override?.systemPrompt ?: spec.defaultSystemPrompt.orEmpty()

        fun save(next: ToolOverride?) {
            // 在最新的覆盖表上只改这一个工具
            vm.updateSettings { latest ->
                val updated = latest.toolOverrides.toMutableMap()
                if (next == null || next.isBlank()) {
                    updated.remove(spec.id)
                } else {
                    updated[spec.id] = next
                }
                latest.copy(toolOverrides = updated)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_page_tools_stable_id, spec.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (spec.isTool) {
                ToolParallelSwitch(
                    id = spec.id,
                    execution = settings.toolExecution,
                    onChange = { transform ->
                        vm.updateSettings { latest -> latest.copy(toolExecution = transform(latest.toolExecution)) }
                    },
                )
            }
            if (spec.isTool && spec.renamable) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        save(
                            (override ?: ToolOverride()).copy(
                                name = it,
                                description = override?.description,
                                systemPrompt = override?.systemPrompt,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_page_tools_name)) },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = {
                    save(
                        (override ?: ToolOverride()).copy(
                            name = override?.name,
                            description = it,
                            systemPrompt = override?.systemPrompt,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_page_tools_definition)) },
                minLines = 4,
                maxLines = 12,
            )
            if (spec.defaultSystemPrompt != null) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = {
                        save(
                            (override ?: ToolOverride()).copy(
                                name = override?.name,
                                description = override?.description,
                                systemPrompt = it,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_page_tools_system_prompt)) },
                    minLines = 6,
                    maxLines = 16,
                )
            }
            if (spec.placeholders.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.setting_page_tools_placeholders,
                        spec.placeholders.joinToString(", ") { "{{$it}}" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { save(null) }) {
                Text(stringResource(R.string.setting_model_page_reset_to_default))
            }
        }
    }
}

/**
 * 单个内置工具的「可并行执行」开关。和默认值相同时不存覆盖（以后默认分类调整能跟上）。
 */
@Composable
private fun ToolParallelSwitch(
    id: String,
    execution: ToolExecutionConfig,
    onChange: ((ToolExecutionConfig) -> ToolExecutionConfig) -> Unit,
) {
    val default = id in ToolConcurrencyDefaults.PARALLEL_BY_DEFAULT
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.setting_page_tools_tool_parallel), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    if (default) R.string.setting_page_tools_tool_parallel_default_on
                    else R.string.setting_page_tools_tool_parallel_default_off
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ToolConcurrencyDefaults.argumentFilter(id) != null) {
                Text(
                    text = stringResource(R.string.setting_page_tools_tool_parallel_view_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!execution.parallel) {
                Text(
                    text = stringResource(R.string.setting_page_tools_tool_parallel_off_globally),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(
            checked = execution.isBuiltinParallel(id),
            onCheckedChange = { checked ->
                onChange { latest ->
                    val overrides = latest.parallelOverrides.toMutableMap()
                    if (checked == default) overrides.remove(id) else overrides[id] = checked
                    latest.copy(parallelOverrides = overrides)
                }
            },
        )
    }
}

@Composable
private fun categoryTitle(category: BuiltinToolCategory): String = when (category) {
    BuiltinToolCategory.LOCAL -> stringResource(R.string.setting_page_tools_category_local)
    BuiltinToolCategory.WORKSPACE -> stringResource(R.string.setting_page_tools_category_workspace)
    BuiltinToolCategory.SEARCH -> stringResource(R.string.setting_page_tools_category_search)
    BuiltinToolCategory.MEMORY -> stringResource(R.string.setting_page_tools_category_memory)
    BuiltinToolCategory.CONVERSATION -> stringResource(R.string.setting_page_tools_category_conversation)
    BuiltinToolCategory.SKILLS -> stringResource(R.string.setting_page_tools_category_skills)
    BuiltinToolCategory.CREATOR -> stringResource(R.string.setting_page_tools_category_creator)
}
