package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoConfig
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoPrompts
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoType
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.ai.ReasoningButton
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.pages.extensions.workspace.descriptionRes
import me.rerere.rikkihub.ui.pages.extensions.workspace.labelRes
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import me.rerere.rikkihub.Screen
import org.koin.androidx.compose.koinViewModel

/**
 * 再造 settings: the model and its generation parameters, whether the text after the selection is sent,
 * the default type and the three type prompts, the recent requirements, and a pointer to the file types
 * (shared with 推敲).
 */
@Composable
fun SettingZaizaoPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.zaizao
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.zaizao_title)) },
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
            item("intro") {
                Text(
                    stringResource(R.string.zaizao_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item("model") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.zaizao_config_section_model)) },
                ) {
                    item(
                        headlineContent = {
                            ModelSelector(
                                modelId = config.modelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    val id = model.takeIf { it.modelId.isNotBlank() }?.id
                                    vm.updateZaizao { it.copy(modelId = id) }
                                },
                                modifier = Modifier.testTag("zaizao-model"),
                            )
                        },
                        supportingContent = {
                            val missing = config.modelId != null && settings.findModelById(config.modelId) == null
                            Text(
                                stringResource(if (missing) R.string.zaizao_model_missing else R.string.zaizao_model_hint),
                                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.zaizao_reasoning)) },
                        trailingContent = {
                            ReasoningButton(
                                reasoningLevel = config.reasoningLevel,
                                onUpdateReasoningLevel = { level -> vm.updateZaizao { it.copy(reasoningLevel = level) } },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            NumberField(
                                value = config.temperature?.toString().orEmpty(),
                                label = stringResource(R.string.zaizao_temperature),
                                decimal = true,
                                valid = { it.toFloatOrNull()?.let { v -> v in ZaizaoConfig.MIN_TEMPERATURE..ZaizaoConfig.MAX_TEMPERATURE } == true },
                                onCommit = { text -> vm.updateZaizao { it.copy(temperature = text?.toFloat()) } },
                                tag = "zaizao-temperature",
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            NumberField(
                                value = config.topP?.toString().orEmpty(),
                                label = stringResource(R.string.zaizao_top_p),
                                decimal = true,
                                valid = { it.toFloatOrNull()?.let { v -> v > ZaizaoConfig.MIN_TOP_P && v <= ZaizaoConfig.MAX_TOP_P } == true },
                                onCommit = { text -> vm.updateZaizao { it.copy(topP = text?.toFloat()) } },
                                tag = "zaizao-top-p",
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            NumberField(
                                value = config.maxTokens?.toString().orEmpty(),
                                label = stringResource(R.string.zaizao_max_tokens),
                                decimal = false,
                                valid = { (it.toIntOrNull() ?: 0) > 0 },
                                onCommit = { text -> vm.updateZaizao { it.copy(maxTokens = text?.toInt()) } },
                                tag = "zaizao-max-tokens",
                            )
                        },
                    )
                }
            }
            item("context") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.zaizao_config_section_context)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.zaizao_include_after)) },
                        supportingContent = { Text(stringResource(R.string.zaizao_include_after_desc)) },
                        trailingContent = {
                            Switch(
                                checked = config.includeAfter,
                                onCheckedChange = { checked -> vm.updateZaizao { it.copy(includeAfter = checked) } },
                                modifier = Modifier.testTag("zaizao-include-after"),
                            )
                        },
                    )
                }
            }
            item("types") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.zaizao_config_section_types)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.zaizao_default_type)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    ZaizaoType.entries.forEachIndexed { index, type ->
                                        SegmentedButton(
                                            selected = config.defaultType == type,
                                            onClick = { vm.updateZaizao { it.copy(defaultType = type) } },
                                            shape = SegmentedButtonDefaults.itemShape(index, ZaizaoType.entries.size),
                                            modifier = Modifier.testTag("zaizao-default-type-${type.name}"),
                                        ) { Text(stringResource(type.labelRes)) }
                                    }
                                }
                                ZaizaoType.entries.forEach { type ->
                                    Text(
                                        stringResource(R.string.zaizao_type_line, stringResource(type.labelRes), stringResource(type.descriptionRes)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            item("prompts") {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZaizaoType.entries.forEach { type ->
                        PromptSettingItem(
                            title = stringResource(R.string.zaizao_prompt_title, stringResource(type.labelRes)),
                            promptDescription = stringResource(R.string.zaizao_prompt_vars),
                            promptValue = config.promptFor(type),
                            onPromptChange = { value -> vm.updateZaizao { it.withPrompt(type, value.take(ZaizaoConfig.MAX_PROMPT_CHARS)) } },
                            onResetPrompt = { vm.updateZaizao { it.withPrompt(type, null) } },
                            defaultPrompt = ZaizaoPrompts.defaultFor(type),
                        )
                    }
                }
            }
            item("recent") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.zaizao_config_section_recent)) },
                ) {
                    if (config.recentRequirements.isEmpty()) {
                        item(headlineContent = {
                            Text(stringResource(R.string.zaizao_recent_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        })
                    }
                    config.recentRequirements.forEach { requirement ->
                        item(
                            headlineContent = { Text(requirement, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { vm.updateZaizao { it.forget(requirement) } }) {
                                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.zaizao_recent_delete),
                                        modifier = Modifier.size(18.dp))
                                }
                            },
                        )
                    }
                    if (config.recentRequirements.isNotEmpty()) {
                        item(headlineContent = {
                            TextButton(onClick = { vm.updateZaizao { it.copy(recentRequirements = emptyList()) } }) {
                                Text(stringResource(R.string.zaizao_recent_clear))
                            }
                        })
                    }
                }
            }
            item("files") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.tuiqiao_config_section_files)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingTuiqiao) },
                        headlineContent = { Text(stringResource(R.string.zaizao_files_shared)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}

/** A number that is saved when the field loses focus (or on Done); blank clears it, an invalid value is not saved. */
@Composable
private fun NumberField(
    value: String,
    label: String,
    decimal: Boolean,
    valid: (String) -> Boolean,
    onCommit: (String?) -> Unit,
    tag: String,
) {
    CommitOnDoneTextField(
        value = value,
        onCommit = { text ->
            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> onCommit(null)
                valid(trimmed) -> onCommit(trimmed)
            }
        },
        label = { Text(label) },
        singleLine = true,
        isError = { it.isNotBlank() && !valid(it.trim()) },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
