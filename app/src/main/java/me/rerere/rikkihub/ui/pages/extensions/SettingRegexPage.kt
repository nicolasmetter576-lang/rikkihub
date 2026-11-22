package me.rerere.rikkihub.ui.pages.extensions

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.model.newRegexScript
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.prompt.RegexScriptCard
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.tavern.regex.RegexScript
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableColumn
import me.rerere.rikkihub.ui.theme.AppSpacing

/**
 * 全局正则脚本：对所有助手生效，先于助手自己的脚本执行。字段和助手里的正则卡完全一样。
 */
@Composable
fun SettingRegexPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scripts = settings.tavern.regexScripts
    val haptic = LocalHapticFeedback.current
    val newScriptName = stringResource(R.string.regex_script_new)
    fun change(transform: (List<RegexScript>) -> List<RegexScript>) {
        vm.updateSettings { latest -> latest.copy(tavern = latest.tavern.copy(regexScripts = transform(latest.tavern.regexScripts))) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.regex_global_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = AppSpacing.sm)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.regex_global_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReorderableColumn(
                list = scripts,
                onSettle = { from, to ->
                    change { list -> if (from in list.indices && to in list.indices) list.toMutableList().apply { add(to, removeAt(from)) } else list }
                },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { index, script, isDragging ->
                key(script.id.ifEmpty { index.toString() }) {
                    ReorderableItem {
                        RegexScriptCard(
                            script = script,
                            settings = settings,
                            assistant = null,
                            onChange = { changed -> change { list -> list.map { if (it.id == script.id) changed else it } } },
                            onDelete = { change { list -> list.filter { it.id != script.id } } },
                            modifier = Modifier.scale(if (isDragging) 0.95f else 1f),
                            dragHandleModifier = Modifier
                                .size(48.dp)
                                .draggableHandle(
                                    enabled = scripts.size > 1,
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                ),
                        )
                    }
                }
            }
            Button(
                onClick = { change { it + newRegexScript(newScriptName) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                    shape = MaterialTheme.shapes.medium,
            ) {
                Icon(HugeIcons.Add01, null)
            }
        }
    }
}
