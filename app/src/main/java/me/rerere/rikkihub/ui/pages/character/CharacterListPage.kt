package me.rerere.rikkihub.ui.pages.character

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Star
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 角色库：搜索、导入（PNG / JSON / CHARX）、新建；收藏的在前。点一个进详情，那里可以开始聊天、编辑、导出。 */
@Composable
fun CharacterListPage(vm: CharacterVM = koinViewModel()) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val characters by vm.characters.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var creating by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { toaster.show(it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.character_page_title)) },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(HugeIcons.Download01, stringResource(R.string.character_import))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(HugeIcons.Add01, null) },
                text = { Text(stringResource(R.string.character_new)) },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.character_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = characters
            when {
                list == null -> Unit
                list.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.character_page_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp) + PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(list, key = { it.id.toString() }) { c ->
                        CharacterRow(c, onOpen = { navController.navigate(Screen.CharacterDetail(c.id.toString())) },
                            onToggleFav = { vm.setFavorite(c.id, !c.fav) })
                    }
                }
            }
        }
    }

    if (creating) NewCharacterDialog(
        onDismiss = { creating = false },
        onCreate = { name ->
            creating = false
            vm.createBlank(name) { id -> navController.navigate(Screen.CharacterDetail(id.toString())) }
        },
    )

    vm.lastImport?.let { result ->
        ImportReportDialog(
            result = result,
            onAllowScripts = { vm.setRegexAllowed(result.record.id, true); vm.dismissReport() },
            onDismiss = vm::dismissReport,
            onOpen = {
                vm.dismissReport()
                navController.navigate(Screen.CharacterDetail(result.record.id.toString()))
            },
        )
    }
}

/** 角色库和聊天抽屉的选择层共用的一行：头像、名字、标签、收藏星标；点一行做什么由调用方定。 */
@Composable
internal fun CharacterRow(c: CharacterSummary, onOpen: () -> Unit, onToggleFav: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterAvatar(c)
        Column(modifier = Modifier.weight(1f)) {
            Text(c.name.ifBlank { "?" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.tags.isNotEmpty()) {
                Text(
                    c.tags.take(4).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFav) {
            Icon(
                HugeIcons.Star,
                contentDescription = stringResource(R.string.character_favorite),
                tint = if (c.fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun NewCharacterDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.character_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.character_new_name)) },
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.character_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
