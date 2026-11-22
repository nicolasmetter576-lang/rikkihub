package me.rerere.rikkihub.ui.pages.extensions

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/** 提示词页：两个页签，提示词库（原模式注入）和世界书。 */
@Composable
fun PromptPage(initialTab: Int = 0, vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 1)) { 2 }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    Text(
                        stringResource(
                            if (pagerState.currentPage == 0) R.string.prompt_page_mode_injection_tab
                            else R.string.prompt_page_lorebook_tab
                        )
                    )
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> PromptLibraryTab(
                    library = settings.promptLibrary,
                    onUpdate = { transform -> vm.updateSettings { latest -> latest.copy(promptLibrary = transform(latest.promptLibrary)) } }
                )

                1 -> LorebookTab(
                    books = settings.lorebooks,
                    onUpdate = { transform -> vm.updateSettings { latest -> latest.copy(lorebooks = transform(latest.lorebooks)) } },
                    onOpen = { navController.navigate(Screen.Lorebook(it.id.toString())) },
                    onOpenGlobalSettings = { navController.navigate(Screen.SettingWorldInfo) },
                )
            }
        }
    }
}
