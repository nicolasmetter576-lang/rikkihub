package me.rerere.rikkihub.ui.pages.character

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.prompt.Hint
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.pages.setting.SettingVM
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import me.rerere.rikkihub.ui.theme.AppSpacing

/** 用户设定：名字（就是昵称，`{{user}}`）和一段描述（进提示词里“用户设定”那一项，也是 `{{persona}}`）。 */
@Composable
fun PersonaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.persona_page_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CommitOnDoneTextField(
                value = settings.displaySetting.userNickname,
                onCommit = { name -> vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(userNickname = name.trim())) } },
                label = { Text(stringResource(R.string.persona_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(R.string.persona_name_hint)
            CommitOnDoneTextField(
                value = settings.tavern.personaDescription,
                onCommit = { text -> vm.updateSettings { it.copy(tavern = it.tavern.copy(personaDescription = text)) } },
                label = { Text(stringResource(R.string.persona_description)) },
                minLines = 5,
                maxLines = 30,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(R.string.persona_description_hint)
        }
    }
}
