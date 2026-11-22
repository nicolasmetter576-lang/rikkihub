package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.AutoCompressMode
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionConfig
import me.rerere.rikkihub.data.ai.CompressionTemplate
import me.rerere.rikkihub.data.ai.CompressionTemplates
import me.rerere.rikkihub.data.ai.prompts.builtInCompressStructure
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.getCurrentAssistant
import me.rerere.rikkihub.service.compressionInputBudget
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.ai.carryOriginalsModeName
import me.rerere.rikkihub.ui.components.ai.compressModeName
import me.rerere.rikkihub.ui.components.ai.compressTemplateName
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.CardGroupValue
import me.rerere.rikkihub.ui.components.ui.rememberSettingSearchListState
import me.rerere.rikkihub.ui.components.ui.switchItem
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

private val SUMMARY_TOKEN_PRESETS = listOf(1024, 2048, 4096, 8192, 16384)
private val CHUNK_TIMEOUT_PRESETS: List<Int?> = listOf(null, 60, 120, 180, 300)

/** 上下文压缩的全局设置：自动档位、触发 / 目标比例、保留条数、摘要长度、压缩模型、摘要模板。 */
@Composable
fun SettingCompressionPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.compression.normalized()
    var viewing by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CompressionTemplate?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_compression_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            state = rememberSettingSearchListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("intro") {
                Text(
                    stringResource(R.string.setting_compression_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item("mode") {
                val chatModel = settings.findModelById(settings.getCurrentAssistant().chatModelId ?: settings.chatModelId)
                val unknownWindow = chatModel != null && compressionInputBudget(chatModel, null) == null
                CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.compress_mode_label)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_compression_mode_desc))
                                val modes = AutoCompressMode.entries
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("compression-mode")) {
                                    modes.forEachIndexed { index, mode ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                            onClick = { vm.updateCompression { it.copy(mode = mode) } },
                                            selected = config.mode == mode,
                                        ) { Text(compressModeName(mode), maxLines = 1) }
                                    }
                                }
                                if (unknownWindow && config.mode != AutoCompressMode.OFF) {
                                    Text(
                                        stringResource(R.string.setting_compression_unknown_window),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        searchKey = R.string.compress_mode_label,
                    )
                }
            }
            item("thresholds") {
                CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs)) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_compression_trigger)) },
                        searchKey = R.string.setting_compression_trigger,
                    ) {
                        SettingIntSlider(
                            value = config.triggerPercent,
                            range = CompressionConfig.MIN_TRIGGER_PERCENT..CompressionConfig.MAX_TRIGGER_PERCENT,
                            label = { stringResource(R.string.setting_compression_percent_value, it) },
                            onValueChange = { percent -> vm.updateCompression { it.copy(triggerPercent = percent).normalized() } },
                            testTag = "compression-trigger",
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_compression_target)) },
                        supportingContent = { Text(stringResource(R.string.setting_compression_target_desc)) },
                        searchKey = R.string.setting_compression_target,
                    ) {
                        SettingIntSlider(
                            value = config.targetPercent,
                            range = CompressionConfig.MIN_TARGET_PERCENT..(config.triggerPercent - 5),
                            label = { stringResource(R.string.setting_compression_percent_value, it) },
                            onValueChange = { percent -> vm.updateCompression { it.copy(targetPercent = percent) } },
                            testTag = "compression-target",
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.chat_page_compress_keep_recent)) },
                        supportingContent = { Text(stringResource(R.string.setting_compression_keep_recent_desc)) },
                        searchKey = R.string.chat_page_compress_keep_recent,
                    ) {
                        SettingIntSlider(
                            value = config.keepRecent,
                            range = 0..30,
                            label = { it.toString() },
                            onValueChange = { keep -> vm.updateCompression { it.copy(keepRecent = keep) } },
                            testTag = "compression-keep-recent",
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_compression_summary_tokens)) },
                        trailingContent = {
                            CardGroupValue(stringResource(R.string.setting_compression_tokens_value, config.summaryTokens))
                        },
                        searchKey = R.string.setting_compression_summary_tokens,
                    ) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SUMMARY_TOKEN_PRESETS.forEachIndexed { index, tokens ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, SUMMARY_TOKEN_PRESETS.size),
                                    onClick = { vm.updateCompression { it.copy(summaryTokens = tokens) } },
                                    selected = config.summaryTokens == tokens,
                                    icon = {},
                                ) { Text("${tokens / 1024}K", maxLines = 1) }
                            }
                        }
                    }
                }
            }
            item("model") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = AppSpacing.xxs),
                    title = { Text(stringResource(R.string.setting_compression_model)) },
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
                                    vm.updateCompression { it.copy(modelId = id) }
                                },
                                modifier = Modifier.testTag("compression-model"),
                            )
                        },
                        supportingContent = {
                            val chosen = config.modelId?.let { settings.findModelById(it) }
                            Text(
                                stringResource(
                                    if (chosen == null) R.string.setting_compression_model_follow
                                    else R.string.setting_compression_model_cache_warning
                                ),
                                color = if (chosen == null) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        },
                        searchKey = R.string.setting_compression_model,
                    )
                }
            }
            item("carry") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = AppSpacing.xxs),
                    title = { Text(stringResource(R.string.setting_compression_carry_title)) },
                ) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_compression_carry_mode)) },
                        supportingContent = { Text(stringResource(R.string.setting_compression_carry_mode_desc)) },
                        searchKey = R.string.setting_compression_carry_mode,
                    ) {
                        val modes = CarryOriginalsMode.entries
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("compression-carry-mode")) {
                            modes.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    onClick = { vm.updateCompression { it.copy(carryOriginalsMode = mode) } },
                                    selected = config.carryOriginalsMode == mode,
                                ) { Text(carryOriginalsModeName(mode), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_compression_carry_count)) },
                        searchKey = R.string.setting_compression_carry_count,
                    ) {
                        SettingIntSlider(
                            value = config.carryOriginalsCount.coerceIn(CompressionConfig.MIN_CARRY_COUNT, 30),
                            range = CompressionConfig.MIN_CARRY_COUNT..30,
                            enabled = config.carryOriginalsMode == CarryOriginalsMode.RECENT,
                            label = { stringResource(R.string.setting_compression_carry_count_value, it) },
                            onValueChange = { count -> vm.updateCompression { it.copy(carryOriginalsCount = count) } },
                            testTag = "compression-carry-count",
                        )
                    }
                    switchItem(
                        checked = config.carryIncludeReplies,
                        onCheckedChange = { enabled -> vm.updateCompression { it.copy(carryIncludeReplies = enabled) } },
                        enabled = config.carryOriginalsMode != CarryOriginalsMode.OFF,
                        headlineContent = { Text(stringResource(R.string.setting_compression_carry_replies)) },
                        supportingContent = { Text(stringResource(R.string.setting_compression_carry_replies_desc)) },
                        modifier = Modifier.testTag("compression-carry-replies"),
                        searchKey = R.string.setting_compression_carry_replies,
                    )
                }
            }
            item("chunk") {
                var timeoutMenuExpanded by remember { mutableStateOf(false) }
                val timeoutLabel = config.chunkTimeoutSeconds?.let {
                    stringResource(R.string.setting_compression_chunk_timeout_seconds, it)
                } ?: stringResource(R.string.setting_compression_chunk_timeout_follow)
                CardGroup(
                    modifier = Modifier.padding(horizontal = AppSpacing.xxs),
                    title = { Text(stringResource(R.string.setting_compression_chunk_title)) },
                ) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_compression_max_requests)) },
                        supportingContent = {
                            Text(
                                "${stringResource(R.string.setting_compression_max_requests_desc)}\n${stringResource(R.string.setting_compression_chunk_desc)}"
                            )
                        },
                        searchKey = R.string.setting_compression_max_requests,
                    ) {
                        SettingIntSlider(
                            value = config.maxChunkRequests,
                            range = CompressionConfig.MIN_MAX_CHUNK_REQUESTS..CompressionConfig.MAX_MAX_CHUNK_REQUESTS,
                            label = { stringResource(R.string.setting_compression_max_requests_value, it) },
                            onValueChange = { count -> vm.updateCompression { it.copy(maxChunkRequests = count) } },
                            testTag = "compression-max-requests",
                        )
                    }
                    item(
                        onClick = { timeoutMenuExpanded = true },
                        headlineContent = { Text(stringResource(R.string.setting_compression_chunk_timeout)) },
                        trailingContent = {
                            Box {
                                CardGroupValue(timeoutLabel)
                                DropdownMenu(
                                    expanded = timeoutMenuExpanded,
                                    onDismissRequest = { timeoutMenuExpanded = false },
                                ) {
                                    CHUNK_TIMEOUT_PRESETS.forEach { preset ->
                                        val label = preset?.let {
                                            stringResource(R.string.setting_compression_chunk_timeout_seconds, it)
                                        } ?: stringResource(R.string.setting_compression_chunk_timeout_follow)
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                timeoutMenuExpanded = false
                                                vm.updateCompression { it.copy(chunkTimeoutSeconds = preset) }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("compression-chunk-timeout"),
                        searchKey = R.string.setting_compression_chunk_timeout,
                    )
                }
            }
            item("templates") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = AppSpacing.xxs),
                    title = { Text(stringResource(R.string.setting_compression_templates)) },
                ) {
                    val defaultLabel = @Composable { id: String ->
                        if (config.templateId == id) CardGroupValue(stringResource(R.string.setting_compression_template_default))
                    }
                    CompressionTemplates.BUILT_IN.forEach { id ->
                        item(
                            onClick = { viewing = id },
                            headlineContent = { Text(compressTemplateName(config, id)) },
                            supportingContent = { Text(stringResource(R.string.setting_compression_template_builtin)) },
                            trailingContent = { defaultLabel(id) },
                            modifier = Modifier.testTag("compression-template-$id"),
                        )
                    }
                    config.customTemplates.forEach { template ->
                        item(
                            onClick = { editing = template },
                            headlineContent = { Text(template.name) },
                            trailingContent = { defaultLabel(template.id) },
                        )
                    }
                    if (config.customTemplates.size < CompressionConfig.MAX_CUSTOM_TEMPLATES) {
                        item(
                            onClick = { editing = CompressionTemplate(Uuid.random().toString(), "", "") },
                            headlineContent = {
                                Text(
                                    stringResource(R.string.setting_compression_template_add),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.testTag("compression-template-add"),
                            searchKey = R.string.setting_compression_templates,
                        )
                    }
                }
            }
        }
    }

    viewing?.let { id ->
        BuiltInTemplateDialog(
            name = compressTemplateName(config, id),
            prompt = builtInCompressStructure(id),
            isDefault = config.templateId == id,
            onSetDefault = { vm.updateCompression { it.copy(templateId = id) } },
            onCopy = {
                viewing = null
                editing = CompressionTemplate(Uuid.random().toString(), "", builtInCompressStructure(id))
            },
            onDismiss = { viewing = null },
        )
    }
    editing?.let { template ->
        val isNew = config.customTemplates.none { it.id == template.id }
        CustomTemplateDialog(
            template = template,
            isNew = isNew,
            isDefault = config.templateId == template.id,
            onSave = { saved, makeDefault ->
                vm.updateCompression { latest ->
                    val templates = if (isNew) latest.customTemplates + saved
                    else latest.customTemplates.map { if (it.id == saved.id) saved else it }
                    latest.copy(
                        customTemplates = templates,
                        templateId = if (makeDefault) saved.id else latest.templateId,
                    )
                }
                editing = null
            },
            onDelete = {
                vm.updateCompression { latest ->
                    latest.copy(
                        customTemplates = latest.customTemplates.filterNot { it.id == template.id },
                        templateId = if (latest.templateId == template.id) CompressionTemplates.GENERAL else latest.templateId,
                    )
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BuiltInTemplateDialog(
    name: String,
    prompt: String,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Text(
                prompt,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.setting_compression_template_copy)) }
        },
        dismissButton = {
            if (!isDefault) {
                TextButton(onClick = {
                    onSetDefault()
                    onDismiss()
                }) { Text(stringResource(R.string.setting_compression_template_set_default)) }
            }
        },
    )
}

@Composable
private fun CustomTemplateDialog(
    template: CompressionTemplate,
    isNew: Boolean,
    isDefault: Boolean,
    onSave: (CompressionTemplate, makeDefault: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(template.name) }
    var prompt by remember { mutableStateOf(template.prompt) }
    val valid = name.isNotBlank() && prompt.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.setting_compression_template_add else R.string.edit)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CompressionConfig.MAX_TEMPLATE_NAME) },
                    label = { Text(stringResource(R.string.setting_compression_template_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("compression-template-name"),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(CompressionConfig.MAX_TEMPLATE_PROMPT) },
                    label = { Text(stringResource(R.string.setting_compression_template_prompt)) },
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag("compression-template-prompt"),
                )
                Text(
                    stringResource(R.string.setting_compression_template_prompt_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isNew) {
                        TextButton(onClick = onDelete) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (!isDefault) {
                        TextButton(
                            onClick = { onSave(template.copy(name = name.trim(), prompt = prompt.trim()), true) },
                            enabled = valid,
                        ) { Text(stringResource(R.string.setting_compression_template_set_default)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(template.copy(name = name.trim(), prompt = prompt.trim()), false) },
                enabled = valid,
                modifier = Modifier.testTag("compression-template-save"),
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
