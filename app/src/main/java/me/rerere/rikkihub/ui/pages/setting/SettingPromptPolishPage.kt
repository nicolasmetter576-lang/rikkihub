package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.polish.PromptPolish
import me.rerere.rikkihub.data.ai.polish.PromptPolishConfig
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/** 润色 settings: the model, whether to send recent conversation messages (and how many), and the prompt. */
@Composable
fun SettingPromptPolishPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.promptPolish
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.prompt_polish_title)) },
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
                    stringResource(R.string.prompt_polish_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item("model") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.prompt_polish_model)) },
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
                                    vm.updatePromptPolish { it.copy(modelId = id) }
                                },
                                modifier = Modifier.testTag("prompt-polish-model"),
                            )
                        },
                        supportingContent = {
                            val missing = config.modelId != null && settings.findModelById(config.modelId) == null
                            Text(
                                stringResource(
                                    if (missing) R.string.prompt_polish_model_missing else R.string.prompt_polish_model_hint
                                ),
                                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            item("context") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.prompt_polish_context_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.prompt_polish_context)) },
                        supportingContent = { Text(stringResource(R.string.prompt_polish_context_desc)) },
                        trailingContent = {
                            Switch(
                                checked = config.includeContext,
                                onCheckedChange = { checked -> vm.updatePromptPolish { it.copy(includeContext = checked) } },
                                modifier = Modifier.testTag("prompt-polish-context"),
                            )
                        },
                    )
                    if (config.includeContext) {
                        item(
                            headlineContent = { Text(stringResource(R.string.prompt_polish_context_count)) },
                            supportingContent = {
                                // Local value while dragging; saved once the drag ends.
                                var dragging by remember(config.effectiveContextMessages) {
                                    mutableFloatStateOf(config.effectiveContextMessages.toFloat())
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = dragging,
                                        onValueChange = { dragging = it },
                                        onValueChangeFinished = {
                                            val count = dragging.roundToInt()
                                            vm.updatePromptPolish { it.copy(contextMessages = count) }
                                        },
                                        valueRange = PromptPolishConfig.MIN_CONTEXT_MESSAGES.toFloat()..
                                            PromptPolishConfig.MAX_CONTEXT_MESSAGES.toFloat(),
                                        steps = PromptPolishConfig.MAX_CONTEXT_MESSAGES - PromptPolishConfig.MIN_CONTEXT_MESSAGES - 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(stringResource(R.string.prompt_polish_context_count_value, dragging.roundToInt()))
                                }
                            },
                        )
                    }
                }
            }
            item("prompt") {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    PromptSettingItem(
                        title = stringResource(R.string.prompt_polish_prompt),
                        promptDescription = stringResource(R.string.prompt_polish_prompt_vars),
                        promptValue = config.effectivePrompt,
                        onPromptChange = { value ->
                            vm.updatePromptPolish { it.copy(prompt = value.take(PromptPolishConfig.MAX_PROMPT_CHARS)) }
                        },
                        onResetPrompt = { vm.updatePromptPolish { it.copy(prompt = null) } },
                        defaultPrompt = PromptPolish.DEFAULT_PROMPT,
                    )
                }
            }
        }
    }
}
