package me.rerere.rikkihub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.MaskTheater01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.character.CharacterListing
import me.rerere.rikkihub.data.datastore.getCurrentAssistant
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.pages.character.CharacterAvatar
import me.rerere.rikkihub.ui.pages.character.CharacterRow

/** 抽屉里的角色入口：一行“角色”（点开选择层），有收藏的角色时下面跟一条头像，点一下直接开聊。 */
@Composable
internal fun DrawerCharacterEntry(
    favourites: List<CharacterSummary>,
    onOpenPicker: () -> Unit,
    onStartChat: (CharacterSummary) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPicker)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.MaskTheater01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.character_page_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (favourites.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    items(favourites, key = { it.id.toString() }) { character ->
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onStartChat(character) }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            CharacterAvatar(character, size = 40.dp)
                            Text(
                                text = character.name.ifBlank { "?" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 选择角色的底部弹层：搜索（名字、作者、标签、描述开头）、全部角色（收藏在前）、每行可点星标收藏；点一行就和它开聊。
 * 角色再多也只排出看得见的几行；结果只在弹层开着时去查。
 */
@Composable
internal fun CharacterPickerSheet(
    vm: ChatDrawerVM,
    assistantName: String,
    onStartChat: (CharacterSummary) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val characters by vm.characters.collectAsStateWithLifecycle()
    val query by vm.characterQuery.collectAsStateWithLifecycle()
    val defaultCliActive = LocalSettings.current.getCurrentAssistant().defaultCliAgentId != null
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.character_page_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onManage) { Text(stringResource(R.string.character_picker_manage)) }
            }
            Text(
                text = stringResource(R.string.character_picker_assistant, assistantName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (defaultCliActive) {
                Text(
                    text = stringResource(R.string.cli_character_chat_plain_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.characterQuery.value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.character_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            val list = characters
            when {
                list == null -> Unit
                list.isEmpty() && query.isBlank() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        text = stringResource(R.string.character_page_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onManage, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.character_picker_manage)) }
                }
                list.isEmpty() -> Text(
                    text = stringResource(R.string.character_picker_no_match),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> {
                    // 点星标只换星的样子，行不动（否则被星标的角色会跑到视野之外）；换搜索词或角色增减时才重新排
                    val ids = list.mapTo(HashSet()) { it.id }
                    val order = remember(query, ids) { list.map { it.id } }
                    val shown = remember(order, list) { CharacterListing.inOrder(order, list) }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(shown, key = { it.id.toString() }) { character ->
                            CharacterRow(
                                c = character,
                                onOpen = { onStartChat(character) },
                                onToggleFav = { vm.setCharacterFavourite(character.id, !character.fav) },
                            )
                        }
                    }
                }
            }
        }
    }
}
