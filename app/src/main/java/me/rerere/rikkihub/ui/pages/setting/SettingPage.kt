package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.material3.VerticalDivider
import me.rerere.rikkihub.ui.components.ui.cardGroupDividerColor
import me.rerere.rikkihub.ui.components.ui.CardGroupDividerThickness
import me.rerere.rikkihub.ui.components.ui.CardGroupDivider
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.ui.components.ui.SettingSearchTarget
import me.rerere.rikkihub.ui.components.ui.switchItem
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Search01
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import android.content.Context
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.material3.ListItemDefaults
import me.rerere.rikkihub.utils.plus
import androidx.compose.runtime.getValue
import me.rerere.hugeicons.stroke.Regex
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.Fire
import me.rerere.hugeicons.stroke.Mask
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.TextFont
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.rikkihub.ui.theme.amoledOutline
import me.rerere.rikkihub.ui.components.ui.CardGroupCorner
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import me.rerere.rikkihub.ui.theme.AppSpacing
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.MaskTheater01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderLibrary
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.AiBeautify
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.datastore.isNotConfigured
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.Navigator
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import me.rerere.hugeicons.stroke.ImageToVideo

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val candidates = remember(configuration) { settingSearchCandidates(context) }
    val results = remember(candidates, query) { matchSettingSearch(candidates, query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.xxs),
                    placeholder = { Text(stringResource(R.string.setting_page_search_placeholder)) },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.setting_page_search_clear),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                )
            }

            if (query.isNotBlank()) {
                item("results") {
                    if (results.isEmpty()) {
                        Text(
                            text = stringResource(R.string.setting_page_search_no_result),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                        )
                    } else {
                        SettingSearchResults(
                            results = results,
                            settings = settings,
                            onToggle = { toggle, value -> vm.updateSettings { toggle.set(it, value) } },
                            onOpen = { result ->
                                SettingSearchTarget.key = result.searchKey
                                navController.navigate(result.screen)
                            },
                        )
                    }
                }
            } else {
                if (settings.isNotConfigured()) {
                    item {
                        ProviderConfigWarningCard(navController)
                    }
                }

                items(settingBlocks, key = { it.title }) { block ->
                    SettingGrid(
                        block = block,
                        onOpen = { navController.navigate(it) },
                        modifier = Modifier.padding(horizontal = AppSpacing.xxs),
                    )
                }
            }
        }
    }
}

// 设置首页入口：每块两列网格，点一下直达（原「偏好设置」中转页的 8 个子页也在这里）
internal class SettingEntry(
    @StringRes val title: Int,
    val icon: ImageVector,
    val screen: Screen,
    // 长按另开的页面（请求日志长按进调试页）
    val longPressScreen: Screen? = null,
)

internal class SettingBlock(@StringRes val title: Int, val entries: List<SettingEntry>)

internal val settingBlocks = listOf(
    SettingBlock(
        R.string.setting_page_group_appearance, listOf(
            SettingEntry(R.string.setting_page_preferences_theme, HugeIcons.Sun01, Screen.SettingPreferencesTheme),
            SettingEntry(R.string.setting_page_preferences_ui, HugeIcons.PaintBoard, Screen.SettingPreferencesUI),
            SettingEntry(R.string.setting_page_preferences_general, HugeIcons.Settings03, Screen.SettingPreferencesGeneral),
            SettingEntry(R.string.setting_page_preferences_notification, HugeIcons.Notification01, Screen.SettingPreferencesNotification),
            SettingEntry(R.string.setting_page_preferences_workspace, HugeIcons.TextFont, Screen.SettingPreferencesWorkspace),
            SettingEntry(R.string.setting_page_preferences_network, HugeIcons.Internet, Screen.SettingPreferencesNetwork),
        )
    ),
    SettingBlock(
        R.string.setting_page_model_and_services, listOf(
            SettingEntry(R.string.setting_page_default_model, HugeIcons.AiMagic, Screen.SettingModels),
            SettingEntry(R.string.setting_page_providers, HugeIcons.CloudServer, Screen.SettingProvider),
            SettingEntry(R.string.setting_page_search_service, HugeIcons.GlobalSearch, Screen.SettingSearch),
            SettingEntry(R.string.setting_page_tts_service, HugeIcons.Megaphone01, Screen.SettingSpeech),
            SettingEntry(R.string.setting_page_media, HugeIcons.ImageToVideo, Screen.SettingMedia),
            SettingEntry(R.string.setting_page_mcp, HugeIcons.McpServer, Screen.SettingMcp),
            SettingEntry(R.string.setting_page_cc_disguise, HugeIcons.Mask, Screen.SettingCCDisguise),
            SettingEntry(R.string.setting_page_cache_keep_alive, HugeIcons.Fire, Screen.SettingCacheKeepAlive),
        )
    ),
    SettingBlock(
        R.string.setting_page_group_conversation, listOf(
            SettingEntry(R.string.setting_page_assistant, HugeIcons.LookTop, Screen.Assistant),
            SettingEntry(R.string.subagent_library_page_title, HugeIcons.Robot01, Screen.SettingSubagentPresets()),
            SettingEntry(R.string.assistant_page_quick_messages, HugeIcons.Zap, Screen.QuickMessages),
            SettingEntry(R.string.prompt_page_mode_injection_tab, HugeIcons.MagicWand01, Screen.Prompts(tab = 0)),
            SettingEntry(R.string.prompt_page_lorebook_tab, HugeIcons.Book01, Screen.Prompts(tab = 1)),
            SettingEntry(R.string.prompt_polish_title, HugeIcons.AiBeautify, Screen.SettingPromptPolish),
            SettingEntry(R.string.global_memory_title, HugeIcons.AiBrain01, Screen.GlobalMemory),
            SettingEntry(R.string.setting_memory_dream_title, HugeIcons.Brain02, Screen.SettingMemoryDream),
            SettingEntry(R.string.setting_compression_title, HugeIcons.Package01, Screen.SettingCompression),
        )
    ),
    SettingBlock(
        R.string.extensions_page_workspace, listOf(
            SettingEntry(R.string.extensions_page_workspace, HugeIcons.Folder01, Screen.Workspaces),
            SettingEntry(R.string.file_manager_title, HugeIcons.FolderLibrary, Screen.FileManager()),
            SettingEntry(R.string.extensions_page_agent_skills, HugeIcons.Puzzle, Screen.Skills),
            SettingEntry(R.string.setting_page_tools, HugeIcons.Tools, Screen.SettingTools),
        )
    ),
    SettingBlock(
        R.string.setting_page_writing, listOf(
            SettingEntry(R.string.tuiqiao_title, HugeIcons.QuillWrite01, Screen.SettingTuiqiao),
            SettingEntry(R.string.zaizao_title, HugeIcons.AiEditing, Screen.SettingZaizao),
            SettingEntry(R.string.qubagu_title, HugeIcons.Clean, Screen.SettingQubagu),
            SettingEntry(R.string.book_settings_title, HugeIcons.BookOpen01, Screen.SettingBook),
        )
    ),
    SettingBlock(
        R.string.setting_page_tavern, listOf(
            SettingEntry(R.string.character_page_title, HugeIcons.MaskTheater01, Screen.Characters),
            SettingEntry(R.string.persona_page_title, HugeIcons.UserCircle, Screen.Persona),
            SettingEntry(R.string.regex_global_title, HugeIcons.Regex, Screen.SettingRegex),
        )
    ),
    SettingBlock(
        R.string.setting_page_data_settings, listOf(
            SettingEntry(R.string.setting_page_data_backup, HugeIcons.Database02, Screen.Backup),
            SettingEntry(R.string.setting_page_chat_storage, HugeIcons.ImageUpload, Screen.SettingFiles),
            SettingEntry(R.string.setting_page_request_logs, HugeIcons.Bookshelf01, Screen.Log, longPressScreen = Screen.Debug),
            SettingEntry(R.string.setting_page_web_server, HugeIcons.ServerStack01, Screen.SettingWeb),
        )
    ),
)

/** 设置搜索的一条候选：页面入口（searchKey 为空）或页里的一项；path 是它所在的分块或页面 */
internal class SettingSearchCandidate(
    val title: String,
    val path: String,
    val screen: Screen,
    @StringRes val searchKey: Int? = null,
    val toggle: SettingToggle? = null,
)

private fun settingSearchCandidates(context: Context): List<SettingSearchCandidate> {
    val pages = settingBlocks.flatMap { block ->
        block.entries.map { entry ->
            SettingSearchCandidate(context.getString(entry.title), context.getString(block.title), entry.screen)
        }
    }
    val pageTitles = settingBlocks.flatMap { it.entries }.associate { it.screen to context.getString(it.title) }
    val items = settingSearchItems.map { item ->
        SettingSearchCandidate(
            title = context.getString(item.title),
            path = pageTitles.getValue(item.screen),
            screen = item.screen,
            searchKey = item.title,
            toggle = item.toggle,
        )
    }
    return pages + items
}

/**
 * 空格分开的每个词都要出现在标题或路径里（不分大小写）；标题开头命中的排最前，其次标题包含，最后只有路径命中的
 * （比如搜「界面」，先是界面页本身，再是界面页里的各项）。
 */
internal fun matchSettingSearch(candidates: List<SettingSearchCandidate>, query: String): List<SettingSearchCandidate> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return candidates
        .filter { c -> words.all { c.title.contains(it, ignoreCase = true) || c.path.contains(it, ignoreCase = true) } }
        .sortedBy { c ->
            when {
                c.title.startsWith(words.first(), ignoreCase = true) -> 0
                words.any { c.title.contains(it, ignoreCase = true) } -> 1
                else -> 2
            }
        }
}

@Composable
private fun SettingSearchResults(
    results: List<SettingSearchCandidate>,
    settings: Settings,
    onToggle: (SettingToggle, Boolean) -> Unit,
    onOpen: (SettingSearchCandidate) -> Unit,
) {
    CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs)) {
        results.forEach { result ->
            val toggle = result.toggle
            if (toggle != null) {
                switchItem(
                    checked = toggle.get(settings),
                    onCheckedChange = { onToggle(toggle, it) },
                    supportingContent = { Text(result.path) },
                ) { Text(result.title) }
            } else {
                item(
                    onClick = { onOpen(result) },
                    supportingContent = { Text(result.path) },
                    trailingContent = { SettingNavArrow() },
                ) { Text(result.title) }
            }
        }
    }
}

@Composable
private fun SettingGrid(block: SettingBlock, onOpen: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            Text(
                text = stringResource(block.title),
                style = MaterialTheme.typography.titleSmallEmphasized,
                modifier = Modifier.padding(start = AppSpacing.xxs, top = AppSpacing.xs, bottom = AppSpacing.xs),
            )
        }
        // 和 CardGroup 一样整块一张卡：格子紧贴，行间横线、两列之间竖线
        val shape = RoundedCornerShape(CardGroupCorner)
        val dividerColor = cardGroupDividerColor()
        Column(
            modifier = Modifier
                .clip(shape)
                .background(CustomColors.listItemColors.containerColor)
                .amoledOutline(shape)
        ) {
            val rows = block.entries.chunked(2)
            rows.forEachIndexed { index, row ->
                if (index > 0) CardGroupDivider()
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    SettingGridCell(row[0], onOpen, Modifier.weight(1f).fillMaxHeight())
                    if (row.size == 2) {
                        VerticalDivider(
                            modifier = Modifier.padding(vertical = AppSpacing.sm),
                            thickness = CardGroupDividerThickness,
                            color = dividerColor,
                        )
                        SettingGridCell(row[1], onOpen, Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingGridCell(entry: SettingEntry, onOpen: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .combinedClickable(
                onClick = { onOpen(entry.screen) },
                onLongClick = entry.longPressScreen?.let { screen -> { onOpen(screen) } },
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Icon(entry.icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(
            text = stringResource(entry.title),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
internal fun SettingNavArrow() {
    Icon(
        imageVector = HugeIcons.ArrowRight01,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            ) {
                Text(stringResource(R.string.setting_page_config_api_title))
            }

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}
