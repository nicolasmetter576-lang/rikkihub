package me.rerere.rikkihub.ui.pages.assistant.detail

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.Refresh03
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.rikkihub.data.model.newRegexScript
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.rikkihub.ui.components.prompt.RegexScriptCard
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.ui.components.message.ChatMessage
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.components.ui.FormItem
import me.rerere.rikkihub.ui.components.ui.Select
import me.rerere.rikkihub.ui.components.ui.Tag
import me.rerere.rikkihub.ui.components.ui.TextArea
import me.rerere.rikkihub.ui.theme.ChatFontProvider
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.ui.theme.JetbrainsMono
import me.rerere.rikkihub.utils.UiState
import me.rerere.rikkihub.utils.insertAtCursor
import me.rerere.rikkihub.utils.onError
import me.rerere.rikkihub.utils.onSuccess
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableColumn
import kotlin.uuid.Uuid
import me.rerere.rikkihub.ui.theme.AppSpacing

@Composable
fun AssistantPromptPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_prompt))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantPromptContent(
            innerPadding = innerPadding,
            assistant = assistant,
            settings = settings,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantPromptContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val templateTransformer = koinInject<TemplateTransformer>()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.sm)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_tavern_mode))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_tavern_mode_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.tavernMode,
                        onCheckedChange = { onUpdate(assistant.copy(tavernMode = it)) }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column(modifier = Modifier.padding(AppSpacing.sm)) {
                val modelContextLength = remember(settings.providers, assistant.chatModelId, settings.chatModelId) {
                    settings.findModelById(assistant.chatModelId ?: settings.chatModelId)?.contextLength?.takeIf { it > 0 }
                }
                PromptLayoutEditor(assistant = assistant, modelContextLength = modelContextLength, onUpdate = onUpdate)
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_system_prompt))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_system_prompt_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.allowConversationSystemPrompt,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    allowConversationSystemPrompt = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_prompt_injection))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_prompt_injection_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.allowConversationPromptInjection,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    allowConversationPromptInjection = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.assistant_page_message_template))
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                onUpdate(assistant.copy(messageTemplate = "{{ message }}"))
                            },
                            enabled = assistant.messageTemplate != "{{ message }}",
                        ) {
                            Icon(
                                imageVector = HugeIcons.Refresh03,
                                contentDescription = null,
                            )
                        }
                    }
                },
                content = {
                    val missingMessageTemplate = stringResource(R.string.assistant_page_message_template_missing_message)
                    CommitOnDoneTextField(
                        value = assistant.messageTemplate,
                        onCommit = {
                            onUpdate(
                                assistant.copy(
                                    messageTemplate = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 15,
                        isError = { "{{ message }}" !in it },
                        supportingText = { text ->
                            if ("{{ message }}" !in text) {
                                Text(missingMessageTemplate)
                            }
                        },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 12.sp,
                            fontFamily = JetbrainsMono,
                            lineHeight = 16.sp
                        )
                    )
                },
                description = {
                    Text(stringResource(R.string.assistant_page_message_template_desc))
                    Text(buildAnnotatedString {
                        append(stringResource(R.string.assistant_page_template_variables_label))
                        append(" ")
                        append(stringResource(R.string.assistant_page_template_variable_role))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ role }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_message))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ message }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_time))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ time }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_date))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ date }}")
                        }
                    })
                }
            )
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_template_preview),
                    style = MaterialTheme.typography.titleSmall
                )
                val rawMessages = listOf(
                    UIMessage.user(stringResource(R.string.assistant_page_template_preview_user)),
                    UIMessage.assistant(stringResource(R.string.assistant_page_template_preview_assistant)),
                )
                val preview by produceState<UiState<List<UIMessage>>>(
                    UiState.Success(rawMessages),
                    assistant
                ) {
                    value = runCatching {
                        UiState.Success(
                            templateTransformer.transform(
                                ctx = TransformerContext(
                                    context = context,
                                    model = Model(modelId = "gpt-4o", displayName = "GPT-4o"),
                                    assistant = assistant,
                                    settings = settings
                                ),
                                messages = rawMessages
                            )
                        )
                    }.getOrElse {
                        UiState.Error(it)
                    }
                }
                preview.onError {
                    Text(
                        text = it.message ?: it.javaClass.name,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                preview.onSuccess {
                    ChatFontProvider(displaySetting = settings.displaySetting) {
                        it.fastForEach { message ->
                            ChatMessage(
                                node = message.toMessageNode(),
                                onFork = {},
                                onRegenerate = {},
                                onEdit = {},
                                onShare = {},
                                onDelete = {},
                                onUpdate = {},
                                lastMessage = false,
                            )
                        }
                    }
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_preset_messages))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_preset_messages_desc))
                }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(AppSpacing.sm)
            ) {
                assistant.presetMessages.fastForEachIndexed { index, presetMessage ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Select(
                                options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                                selectedOption = presetMessage.role,
                                onOptionSelected = { role ->
                                    onUpdate(
                                        assistant.copy(
                                            presetMessages = assistant.presetMessages.mapIndexed { i, msg ->
                                                if (i == index) {
                                                    msg.copy(role = role)
                                                } else {
                                                    msg
                                                }
                                            }
                                        )
                                    )
                                },
                                modifier = Modifier.width(160.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    onUpdate(
                                        assistant.copy(
                                            presetMessages = assistant.presetMessages.filterIndexed { i, _ ->
                                                i != index
                                            }
                                        )
                                    )
                                }
                            ) {
                                Icon(HugeIcons.Cancel01, null)
                            }
                        }
                        CommitOnDoneTextField(
                            value = presetMessage.toText(),
                            onCommit = { text ->
                                onUpdate(
                                    assistant.copy(
                                        presetMessages = assistant.presetMessages.mapIndexed { i, msg ->
                                            if (i == index) {
                                                msg.copy(parts = listOf(UIMessagePart.Text(text)))
                                            } else {
                                                msg
                                            }
                                        }
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6
                        )
                    }
                }
                Button(
                    onClick = {
                        val lastRole = assistant.presetMessages.lastOrNull()?.role ?: MessageRole.ASSISTANT
                        val nextRole = when (lastRole) {
                            MessageRole.USER -> MessageRole.ASSISTANT
                            MessageRole.ASSISTANT -> MessageRole.USER
                            else -> MessageRole.USER
                        }
                        onUpdate(
                            assistant.copy(
                                presetMessages = assistant.presetMessages + UIMessage(
                                    role = nextRole,
                                    parts = listOf(UIMessagePart.Text(""))
                                )
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(HugeIcons.Add01, null)
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_regex_title))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_regex_desc))
                }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                val haptic = LocalHapticFeedback.current
                val scripts = assistant.promptPreset.regexScripts
                fun updateScripts(next: List<RegexScript>) =
                    onUpdate(assistant.copy(promptPreset = assistant.promptPreset.withRegexScripts(next)))
                ReorderableColumn(
                    list = scripts,
                    onSettle = { fromIndex, toIndex ->
                        updateScripts(scripts.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
                    },
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { index, regex, isDragging ->
                    key(regex.id.ifEmpty { index.toString() }) {
                        ReorderableItem(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RegexScriptCard(
                                script = regex,
                                settings = settings,
                                assistant = assistant,
                                onChange = { changed -> updateScripts(scripts.mapIndexed { i, old -> if (i == index) changed else old }) },
                                onDelete = { updateScripts(scripts.filterIndexed { i, _ -> i != index }) },
                                modifier = Modifier.scale(if (isDragging) 0.95f else 1f),
                                dragHandleModifier = Modifier
                                    .size(48.dp)
                                    .longPressDraggableHandle(
                                        enabled = scripts.size > 1,
                                        onDragStarted = {
                                            haptic.performHapticFeedback(
                                                HapticFeedbackType.GestureThresholdActivate
                                            )
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    )
                            )
                        }
                    }
                }
                val newScriptName = stringResource(R.string.regex_script_new)
                Button(
                    onClick = {
                        updateScripts(scripts + newRegexScript(newScriptName))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(HugeIcons.Add01, null)
                }
            }
        }
    }
}
