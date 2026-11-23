package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.book.BookConfig
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.ai.ReasoningButton
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.components.ui.rememberSettingSearchListState
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 作品设置：作品功能（找别称、反向大纲、节拍）用的模型和生成参数。 */
@Composable
fun SettingBookPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.book
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_settings_title)) },
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
                    stringResource(R.string.book_settings_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item("model") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = AppSpacing.xxs),
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
                                    vm.updateBook { it.copy(modelId = id) }
                                },
                                modifier = Modifier.testTag("book-model"),
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
                                onUpdateReasoningLevel = { level -> vm.updateBook { it.copy(reasoningLevel = level) } },
                            )
                        },
                        searchKey = R.string.zaizao_reasoning,
                    )
                    item(
                        headlineContent = {
                            CommitOnDoneTextField(
                                value = config.temperature?.toString().orEmpty(),
                                onCommit = { text ->
                                    val value = text.trim().toFloatOrNull()
                                    if (text.isBlank()) vm.updateBook { it.copy(temperature = null) }
                                    else if (value != null && value in BookConfig.MIN_TEMPERATURE..BookConfig.MAX_TEMPERATURE) {
                                        vm.updateBook { it.copy(temperature = value) }
                                    }
                                },
                                label = { Text(stringResource(R.string.zaizao_temperature)) },
                                singleLine = true,
                                isError = { text ->
                                    text.isNotBlank() && text.trim().toFloatOrNull()?.let { it in BookConfig.MIN_TEMPERATURE..BookConfig.MAX_TEMPERATURE } != true
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth().testTag("book-temperature"),
                            )
                        },
                    )
                }
            }
        }
    }
}
