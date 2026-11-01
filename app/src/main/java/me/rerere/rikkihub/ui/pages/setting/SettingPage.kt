package me.rerere.rikkihub.ui.pages.setting

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
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.AiBeautify
import me.rerere.hugeicons.stroke.AiEditing
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
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.Navigator
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import me.rerere.hugeicons.stroke.ImageToVideo

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            item("generalSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferences) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { Icon(HugeIcons.LookTop, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSubagentPresets()) },
                        leadingContent = { Icon(HugeIcons.LookTop, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.subagent_library_page_title)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.QuickMessages) },
                        leadingContent = { Icon(HugeIcons.Zap, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Prompts(tab = 0)) },
                        leadingContent = { Icon(HugeIcons.MagicWand01, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.prompt_page_mode_injection_tab)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Prompts(tab = 1)) },
                        leadingContent = { Icon(HugeIcons.Book01, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.prompt_page_lorebook_tab)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Skills) },
                        leadingContent = { Icon(HugeIcons.Puzzle, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Workspaces) },
                        leadingContent = { Icon(HugeIcons.Folder01, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.extensions_page_workspace)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTuiqiao) },
                        leadingContent = { Icon(HugeIcons.QuillWrite01, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.tuiqiao_title)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingZaizao) },
                        leadingContent = { Icon(HugeIcons.AiEditing, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.zaizao_title)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPromptPolish) },
                        leadingContent = { Icon(HugeIcons.AiBeautify, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.prompt_polish_title)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMemoryDream) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_memory_dream_title)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTools) },
                        leadingContent = { Icon(HugeIcons.Tools, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_tools)) },
                    )
                }
            }

            item("modelServices") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_model_and_services)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { Icon(HugeIcons.AiMagic, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                        leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMedia) },
                        leadingContent = { Icon(HugeIcons.ImageToVideo, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_media)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { Icon(HugeIcons.McpServer, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                    )
                }
            }

            item("dataSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_data_settings)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { Icon(HugeIcons.Database02, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                    // Long press opens the debug page (its only entry since the About page was removed).
                    item(
                        modifier = Modifier.combinedClickable(
                            onClick = { navController.navigate(Screen.Log) },
                            onLongClick = { navController.navigate(Screen.Debug) },
                        ),
                        leadingContent = { Icon(HugeIcons.Bookshelf01, null) },
                        trailingContent = { SettingNavArrow() },
                        headlineContent = { Text(stringResource(R.string.setting_page_request_logs)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingNavArrow() {
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
