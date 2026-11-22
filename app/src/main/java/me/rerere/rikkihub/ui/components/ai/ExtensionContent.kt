package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.files.SkillMetadata
import me.rerere.rikkihub.data.model.EntryPlacement
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.data.model.depth
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.rikkihub.data.model.placement
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.rikkihub.ui.components.ui.CompactListItem

/** 提示词库条目的选用列表：库条目的标识符是 UUID（原模式注入的 id），不是 UUID 的（不应出现在库里）跳过。 */
@Composable
fun PromptLibraryContent(
    entries: List<PromptEntry>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    val items = remember(entries) {
        entries.mapNotNull { entry -> runCatching { kotlin.uuid.Uuid.parse(entry.identifier) }.getOrNull()?.let { it to entry } }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items, key = { it.first }, contentType = { "injection" }) { (id, entry) ->
            CompactListItem(
                headlineContent = {
                    Text(entry.name.orEmpty().ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                supportingContent = {
                    Text(
                        text = libraryEntrySummary(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(id),
                        onCheckedChange = { checked -> onToggle(id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

/** 一行说明条目落在哪里；总开关关着的（选了也不会发）加上「已停用」。 */
@Composable
private fun libraryEntrySummary(entry: PromptEntry): String {
    val where = if (entry.placement == EntryPlacement.IN_CHAT) {
        stringResource(R.string.prompt_library_in_chat_depth, entry.depth)
    } else {
        stringResource(
            when (entry.libraryAnchor) {
                LibraryEntryMeta.BEFORE_SYSTEM -> R.string.prompt_layout_anchor_before_system
                LibraryEntryMeta.BEFORE_HISTORY -> R.string.prompt_layout_anchor_before_history
                else -> R.string.prompt_layout_anchor_after_system
            }
        )
    }
    return if (entry.libraryEnabled) where else "$where · ${stringResource(R.string.prompt_page_disabled)}"
}

@Composable
fun LorebooksContent(
    lorebooks: List<Lorebook>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(lorebooks, key = { it.id }, contentType = { "lorebook" }) { lorebook ->
            CompactListItem(
                headlineContent = {
                    Text(lorebook.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) })
                },
                supportingContent = {
                    val detail = buildString {
                        append(stringResource(R.string.prompt_page_entries_count_format, lorebook.entries.size))
                        if (!lorebook.enabled) append(" · ").append(stringResource(R.string.prompt_page_disabled))
                        if (lorebook.description.isNotBlank()) append(" · ").append(lorebook.description)
                    }
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(lorebook.id),
                        onCheckedChange = { checked -> onToggle(lorebook.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(skills, key = { it.skillDir.absolutePath }, contentType = { "skill" }) { skill ->
            CompactListItem(
                headlineContent = { Text(skill.name) },
                supportingContent = if (skill.description.isNotBlank()) {
                    {
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = enabledSkills.contains(skill.name),
                        onCheckedChange = { checked -> onToggle(skill.name, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(quickMessages, key = { it.id }, contentType = { "quickMessage" }) { quickMessage ->
            CompactListItem(
                headlineContent = {
                    Text(quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                supportingContent = if (quickMessage.content.isNotBlank()) {
                    {
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(quickMessage.id),
                        onCheckedChange = { checked -> onToggle(quickMessage.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.extension_content_manage),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (buttonText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(HugeIcons.Link01, contentDescription = null)
                Text(buttonText)
            }
        }
    }
}
