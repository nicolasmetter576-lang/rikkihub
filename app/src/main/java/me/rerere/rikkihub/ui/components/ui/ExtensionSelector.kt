package me.rerere.rikkihub.ui.components.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.files.SkillMetadata
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkihub.ui.components.ai.LorebooksContent
import me.rerere.rikkihub.ui.components.ai.PromptLibraryContent
import me.rerere.rikkihub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkihub.ui.components.ai.SkillsContent
import org.koin.compose.koinInject


private enum class ExtensionTab(@param:StringRes val labelRes: Int) {
    QUICK_MESSAGES(R.string.extension_selector_tab_quick_messages),
    MODE_INJECTIONS(R.string.extension_selector_tab_mode_injections),
    LOREBOOKS(R.string.extension_selector_tab_lorebooks),
    SKILLS(R.string.extension_selector_tab_skills),
}

@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    conversation: Conversation? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: (tab: Int) -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 打开扩展面板时清理运行时被删除的技能（残留的 enabledSkills 引用），
        // prune 顺带返回现存技能列表，避免重复读盘
        skills = skillManager.pruneOrphanedEnabledSkills()
    }

    val useConversationInjections =
        assistant.allowConversationPromptInjection && conversation != null && onUpdateConversation != null
    val selectedModeInjectionIds = if (useConversationInjections) {
        conversation.modeInjectionIds
    } else {
        assistant.libraryEntryIds
    }
    val selectedLorebookIds = if (useConversationInjections) {
        conversation.lorebookIds
    } else {
        assistant.lorebookIds
    }

    val isCliSession = conversation?.cliSession != null
    val tabs = remember(isCliSession) {
        if (isCliSession) {
            listOf(ExtensionTab.QUICK_MESSAGES, ExtensionTab.MODE_INJECTIONS, ExtensionTab.SKILLS)
        } else {
            ExtensionTab.entries
        }
    }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    LaunchedEffect(tabs.size) {
        if (pagerState.currentPage >= tabs.size) {
            pagerState.scrollToPage(0)
        }
    }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
    ) {
        SecondaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val tabTextStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = {
                        Text(
                            stringResource(tab.labelRes),
                            style = tabTextStyle,
                            maxLines = 1,
                        )
                    }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (tabs[page]) {
                ExtensionTab.QUICK_MESSAGES -> {
                    if (settings.quickMessages.isNotEmpty()) {
                        QuickMessagesContent(
                            quickMessages = settings.quickMessages,
                            selectedIds = assistant.quickMessageIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    assistant.quickMessageIds + id
                                } else {
                                    assistant.quickMessageIds - id
                                }
                                onUpdate(assistant.copy(quickMessageIds = newIds))
                            },
                            onManage = onNavigateToQuickMessages,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_quick_messages_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToQuickMessages,
                        )
                    }
                }

                ExtensionTab.MODE_INJECTIONS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (isCliSession) {
                            Text(
                                text = stringResource(R.string.cli_mode_injections_scope_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        if (settings.promptLibrary.isNotEmpty()) {
                            PromptLibraryContent(
                                entries = settings.promptLibrary,
                                selectedIds = selectedModeInjectionIds,
                                onToggle = { id, checked ->
                                    val newIds = if (checked) {
                                        selectedModeInjectionIds + id
                                    } else {
                                        selectedModeInjectionIds - id
                                    }
                                    if (useConversationInjections) {
                                        onUpdateConversation(conversation.copy(modeInjectionIds = newIds))
                                    } else {
                                        onUpdate(assistant.copy(libraryEntryIds = newIds))
                                    }
                                },
                                onManage = { onNavigateToPrompts(0) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Box(modifier = Modifier.weight(1f)) {
                                ExtensionEmptyState(
                                    message = stringResource(R.string.extension_selector_mode_injections_empty),
                                    buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                                    onAction = { onNavigateToPrompts(0) },
                                )
                            }
                        }
                    }
                }

                ExtensionTab.LOREBOOKS -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksContent(
                            lorebooks = settings.lorebooks,
                            selectedIds = selectedLorebookIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    selectedLorebookIds + id
                                } else {
                                    selectedLorebookIds - id
                                }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(lorebookIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(lorebookIds = newIds))
                                }
                            },
                            onManage = { onNavigateToPrompts(1) },
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = { onNavigateToPrompts(1) },
                        )
                    }
                }

                ExtensionTab.SKILLS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (isCliSession) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.cli_skills_unsupported_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                        if (skills.isNotEmpty()) {
                            SkillsContent(
                                skills = skills,
                                enabledSkills = assistant.enabledSkills,
                                onToggle = { name, checked ->
                                    if (isCliSession) return@SkillsContent
                                    val newSkills = if (checked) {
                                        assistant.enabledSkills + name
                                    } else {
                                        assistant.enabledSkills - name
                                    }
                                    onUpdate(assistant.copy(enabledSkills = newSkills))
                                },
                                onManage = onNavigateToSkills,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Box(modifier = Modifier.weight(1f)) {
                                ExtensionEmptyState(
                                    message = stringResource(R.string.extension_selector_skills_empty),
                                    buttonText = stringResource(R.string.extension_selector_go_to_skills),
                                    onAction = onNavigateToSkills,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
