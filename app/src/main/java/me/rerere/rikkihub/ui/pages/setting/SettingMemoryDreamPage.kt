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
import me.rerere.rikkihub.data.ai.MemoryDreamConfig
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_MEMORY_DREAM_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_MEMORY_FULL_REVIEW_PROMPT
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.switchItem
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/** 记忆库「做梦」的全局设置：自动开关、触发时机、模型、步数和两段提示词。 */
@Composable
fun SettingMemoryDreamPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.memoryDream
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_memory_dream_title)) },
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
                    stringResource(R.string.setting_memory_dream_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item("auto") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    switchItem(
                        checked = config.autoDream,
                        onCheckedChange = { checked -> vm.updateMemoryDream { it.copy(autoDream = checked) } },
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_auto)) },
                        supportingContent = { Text(stringResource(R.string.setting_memory_dream_auto_desc)) },
                        modifier = Modifier.testTag("memory-dream-auto"),
                    )
                }
            }
            item("triggers") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_memory_dream_triggers)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_idle)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_memory_dream_idle_desc))
                                DreamSlider(
                                    value = config.idleDelay.inWholeMinutes.toInt(),
                                    range = MemoryDreamConfig.MIN_IDLE_MINUTES..MemoryDreamConfig.MAX_IDLE_MINUTES,
                                    enabled = config.autoDream,
                                    label = { stringResource(R.string.setting_memory_dream_idle_value, it) },
                                    onValueChange = { minutes -> vm.updateMemoryDream { it.copy(idleMinutes = minutes) } },
                                    testTag = "memory-dream-idle",
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_turns)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_memory_dream_turns_desc))
                                DreamSlider(
                                    value = config.effectiveMaxPendingTurns,
                                    range = MemoryDreamConfig.MIN_PENDING_TURNS..MemoryDreamConfig.MAX_PENDING_TURNS,
                                    enabled = config.autoDream,
                                    label = { stringResource(R.string.setting_memory_dream_turns_value, it) },
                                    onValueChange = { turns -> vm.updateMemoryDream { it.copy(maxPendingTurns = turns) } },
                                    testTag = "memory-dream-turns",
                                )
                            }
                        },
                    )
                    switchItem(
                        checked = config.dreamOnLeave,
                        onCheckedChange = { checked -> vm.updateMemoryDream { it.copy(dreamOnLeave = checked) } },
                        enabled = config.autoDream,
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_on_leave)) },
                        supportingContent = { Text(stringResource(R.string.setting_memory_dream_on_leave_desc)) },
                    )
                    switchItem(
                        checked = config.dreamOnBackground,
                        onCheckedChange = { checked -> vm.updateMemoryDream { it.copy(dreamOnBackground = checked) } },
                        enabled = config.autoDream,
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_on_background)) },
                        supportingContent = { Text(stringResource(R.string.setting_memory_dream_on_background_desc)) },
                    )
                    switchItem(
                        checked = config.catchUpOnStartup,
                        onCheckedChange = { checked -> vm.updateMemoryDream { it.copy(catchUpOnStartup = checked) } },
                        enabled = config.autoDream,
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_on_startup)) },
                        supportingContent = { Text(stringResource(R.string.setting_memory_dream_on_startup_desc)) },
                    )
                }
            }
            item("model") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_memory_dream_model)) },
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
                                    vm.updateMemoryDream { it.copy(modelId = id) }
                                },
                                modifier = Modifier.testTag("memory-dream-model"),
                            )
                        },
                        supportingContent = {
                            val missing = config.modelId != null && settings.findModelById(config.modelId) == null
                            Text(
                                stringResource(
                                    if (missing) R.string.setting_memory_dream_model_missing else R.string.setting_memory_dream_model_hint
                                ),
                                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_steps)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_memory_dream_steps_desc))
                                DreamSlider(
                                    value = config.effectiveMaxSteps,
                                    range = MemoryDreamConfig.MIN_STEPS..MemoryDreamConfig.MAX_STEPS,
                                    label = { it.toString() },
                                    onValueChange = { steps -> vm.updateMemoryDream { it.copy(maxSteps = steps) } },
                                    testTag = "memory-dream-steps",
                                )
                            }
                        },
                    )
                }
            }
            item("prompt") {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    PromptSettingItem(
                        title = stringResource(R.string.setting_memory_dream_prompt),
                        promptDescription = stringResource(R.string.setting_memory_dream_prompt_vars),
                        promptValue = config.effectivePrompt,
                        onPromptChange = { value ->
                            vm.updateMemoryDream { it.copy(prompt = value.take(MemoryDreamConfig.MAX_PROMPT_CHARS)) }
                        },
                        onResetPrompt = { vm.updateMemoryDream { it.copy(prompt = null) } },
                        defaultPrompt = DEFAULT_MEMORY_DREAM_PROMPT,
                    )
                }
            }
            item("full-review-prompt") {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    PromptSettingItem(
                        title = stringResource(R.string.setting_memory_dream_full_review_prompt),
                        promptDescription = stringResource(R.string.setting_memory_dream_full_review_prompt_desc),
                        promptValue = config.effectiveFullReviewPrompt,
                        onPromptChange = { value ->
                            vm.updateMemoryDream {
                                it.copy(fullReviewPrompt = value.take(MemoryDreamConfig.MAX_PROMPT_CHARS))
                            }
                        },
                        onResetPrompt = { vm.updateMemoryDream { it.copy(fullReviewPrompt = null) } },
                        defaultPrompt = DEFAULT_MEMORY_FULL_REVIEW_PROMPT,
                    )
                }
            }
        }
    }
}

/** 整数滑块：拖动时只改本地值，松手才保存 */
@Composable
private fun DreamSlider(
    value: Int,
    range: IntRange,
    label: @Composable (Int) -> String,
    onValueChange: (Int) -> Unit,
    testTag: String,
    enabled: Boolean = true,
) {
    var dragging by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onValueChange(dragging.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .testTag(testTag),
        )
        Text(label(dragging.roundToInt()))
    }
}
