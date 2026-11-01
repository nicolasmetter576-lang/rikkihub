package me.rerere.rikkihub.ui.components.ai

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.rikkihub.ui.theme.LocalAmoledStrong
import me.rerere.rikkihub.ui.theme.amoledOutline
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Fire
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.ai.mcp.McpManager
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.getCurrentChatModel
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.components.ui.ExtensionSelector
import me.rerere.rikkihub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkihub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkihub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.hooks.ChatInputState
import me.rerere.workspace.WorkspaceShellStatus
import androidx.compose.material3.CircularProgressIndicator
import me.rerere.rikkihub.data.model.lastContextTokens
import me.rerere.rikkihub.utils.formatNumber
import me.rerere.rikkihub.data.datastore.findModelById
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import me.rerere.hugeicons.stroke.PaintBoard

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    mcpManager: McpManager,
    compressState: CompressState?,
    onCompressContext: (additionalPrompt: String, keepRecent: Int) -> Unit,
    onCancelCompression: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onSketch: () -> Unit,
    onStartVoiceMode: (() -> Unit)? = null,
    onToggleCacheKeepAlive: (Boolean) -> Unit = {},
    onToggleSearch: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val chatModel = settings.getCurrentChatModel()
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 上传文件在输入栏工具条上已有按钮，这里不再重复
        val searchMode = currentSearchMode(assistant.enableWebSearch, chatModel)
        AttachmentGrid(
            actions = buildList {
                add(AttachmentAction(HugeIcons.Camera01, R.string.take_picture, onTakePic))
                add(AttachmentAction(HugeIcons.Image02, R.string.photo, onPickImage))
                add(AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, onSketch))
                if (provider != null && provider is ProviderSetting.Google) {
                    add(AttachmentAction(HugeIcons.Video01, R.string.video, onPickVideo))
                    add(AttachmentAction(HugeIcons.MusicNote03, R.string.audio, onPickAudio))
                }
                // 网络搜索：点按开关，长按打开搜索设置面板
                if (onToggleSearch != null) {
                    add(
                        AttachmentAction(
                            icon = if (searchMode == SearchMode.BUILT_IN) HugeIcons.AiSearch02 else HugeIcons.Search01,
                            label = R.string.use_web_search,
                            onClick = onToggleSearch,
                            onLongClick = onOpenSearch,
                            longClickLabel = R.string.search_picker_title,
                            checked = searchMode != SearchMode.OFF,
                            testTag = "chat_more_search",
                        )
                    )
                }
                onStartVoiceMode?.let { start ->
                    add(AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, start))
                }
            }
        )

        if (workspaces.isNotEmpty()) {
            WorkspacePickerListItem(
                assistant = assistant,
                conversation = conversation,
                workspaces = workspaces,
                onUpdateAssistant = onUpdateAssistant,
                onUpdateConversation = onUpdateConversation,
                onNavigateToDetail = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceDetail(id))
                },
                onNavigateToTerminal = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceTerminal(id))
                },
                onNavigateToManage = {
                    onDismiss()
                    navController.navigate(Screen.Workspaces)
                },
            )
        }

        if (settings.mcpServers.isNotEmpty()) {
            McpPickerListItem(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
                modifier = Modifier.amoledOutline(MaterialTheme.shapes.large),
            )
        }

        // claudelife：原「扩展管理」抽屉项换成全局缓存保活启停项。
        // 抽屉外（输入栏工具条的扩展按钮）的扩展管理入口保留不动。
        // 点击整项 → 进入配置页；点击开关 → 只切换启停（开关自身消费点击，不触发整项）。
        CacheKeepAliveDrawerItem(
            enabled = settings.cacheKeepAliveConfig.enabled,
            onToggle = onToggleCacheKeepAlive,
            onOpenSettings = {
                onDismiss()
                navController.navigate(Screen.SettingCacheKeepAlive)
            },
        )

        // Compress History Button
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = stringResource(R.string.chat_page_compress_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_compress_context))
            },
            trailingContent = {
                // 最近一次回复的上下文占用 / 会话模型的上下文窗口；压缩之后、新回复之前取不到
                val contextTokens = remember(conversation.messageNodes) { conversation.lastContextTokens() }
                val contextLength = remember(settings.providers, assistant.chatModelId, settings.chatModelId) {
                    settings.findModelById(assistant.chatModelId ?: settings.chatModelId)?.contextLength?.takeIf { it > 0 }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (conversation.messageNodes.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (contextTokens != null && contextLength != null) {
                            Text(
                                text = "${contextTokens.formatNumber()} / ${contextLength.formatNumber()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (contextTokens != null && contextLength != null) {
                        ContextUsageRing(fraction = contextTokens.toFloat() / contextLength)
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .amoledOutline(MaterialTheme.shapes.large)
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowCompressDialogChange(true)
                },
        )

        // Workspace CWD
        val boundWorkspace = remember(workspaces, assistant.workspaceId) {
            workspaces.find { it.id == assistant.workspaceId?.toString() }
        }
        if (boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name) {
            var showCwdSheet by remember { mutableStateOf(false) }
            // 工作目录 + 「仅此目录」开关并排：开且选了子目录时模型只看得到该目录。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { showCwdSheet = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Folder01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = conversation.workspaceCwd ?: "/workspace",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.workspace_cwd_scoped),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (conversation.workspaceCwd != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = conversation.workspaceCwdScoped,
                    onCheckedChange = { checked ->
                        onUpdateConversation(conversation.copy(workspaceCwdScoped = checked))
                    },
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            if (showCwdSheet) {
                WorkspaceCwdPickerSheet(
                    workspaceId = boundWorkspace.id,
                    currentCwd = conversation.workspaceCwd,
                    onSelectCwd = { newCwd ->
                        onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                    },
                    onDismiss = { showCwdSheet = false },
                )
            }
        }
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(
            compressState = compressState,
            onDismiss = {
                onShowCompressDialogChange(false)
                onDismiss()
            },
            onConfirm = onCompressContext,
            onCancelCompression = onCancelCompression,
        )
    }
}

/** 上下文用量环，接近上限时变色提醒压缩 */
@Composable
private fun ContextUsageRing(fraction: Float) {
    val color = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    CircularProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier
            .size(24.dp)
            .testTag("context_usage_ring"),
        color = color,
        strokeWidth = 3.dp,
        // AMOLED 强劲模式下 surfaceContainerHighest 是纯黑，轨道改用描边色
        trackColor = if (LocalAmoledStrong.current) MaterialTheme.colorScheme.outlineVariant
        else MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    conversation: Conversation,
    workspaces: List<WorkspaceEntity>,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = stringResource(R.string.assistant_page_workspace),
            )
        },
        headlineContent = {
            Text(stringResource(R.string.assistant_page_workspace))
        },
        supportingContent = {
            Text(
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .amoledOutline(MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable { showSheet = true },
    )

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                    if (conversation.workspaceCwd != null) {
                        onUpdateConversation(conversation.copy(workspaceCwd = null))
                    }
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
internal fun InjectionQuickConfigSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val navController = LocalNavController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                conversation = conversation,
                onUpdateConversation = onUpdateConversation,
                modifier = Modifier.weight(1f),
                onNavigateToQuickMessages = {
                    onDismissAll()
                    navController.navigate(Screen.QuickMessages)
                },
                onNavigateToPrompts = { tab ->
                    onDismissAll()
                    navController.navigate(Screen.Prompts(tab = tab))
                },
                onNavigateToSkills = {
                    onDismissAll()
                    navController.navigate(Screen.Skills)
                })

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private class AttachmentAction(
    val icon: ImageVector,
    @StringRes val label: Int,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    @StringRes val longClickLabel: Int? = null,
    // 不为空时是开关方块，开着时高亮
    val checked: Boolean? = null,
    val testTag: String? = null,
)

private const val ATTACHMENT_GRID_COLUMNS = 4

/** 「+」面板顶部的方块：每行四个等宽，不满一行用空位补齐，方块大小始终一致。 */
@Composable
private fun AttachmentGrid(
    actions: List<AttachmentAction>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(ATTACHMENT_GRID_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { action ->
                    AttachmentTile(action = action, modifier = Modifier.weight(1f))
                }
                repeat(ATTACHMENT_GRID_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
) {
    val strong = LocalAmoledStrong.current
    val checked = action.checked == true
    val shape = MaterialTheme.shapes.largeIncreased
    val colorScheme = MaterialTheme.colorScheme
    // 强劲模式底色保持纯黑，用描边区分方块、用主色描边和主色图标文字表示「开着」
    val containerColor by animateColorAsState(
        if (checked && !strong) colorScheme.primaryContainer else colorScheme.surfaceContainerHigh
    )
    val contentColor by animateColorAsState(
        when {
            checked && strong -> colorScheme.primary
            checked -> colorScheme.onPrimaryContainer
            else -> colorScheme.onSurface
        }
    )
    val border = when {
        strong && checked -> BorderStroke(1.5.dp, colorScheme.primary)
        strong -> BorderStroke(1.dp, colorScheme.outlineVariant)
        else -> null
    }
    val longClickLabel = action.longClickLabel?.let { stringResource(it) }
    Box(
        modifier = modifier
            .heightIn(min = 88.dp)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clip(shape)
            .background(containerColor)
            .then(if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier)
            .combinedClickable(
                onClick = action.onClick,
                onLongClick = action.onLongClick,
                onLongClickLabel = longClickLabel,
                role = if (action.checked != null) Role.Switch else Role.Button,
            )
            .then(if (action.checked != null) Modifier.semantics { selected = checked } else Modifier)
            .padding(horizontal = 6.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = contentColor,
            )
            Text(
                text = stringResource(action.label),
                color = contentColor,
                // 四个并排时较长的译文放不下，自动缩小字号
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AttachmentGridPreview() {
    AttachmentGrid(
        actions = listOf(
            AttachmentAction(HugeIcons.Camera01, R.string.take_picture, {}),
            AttachmentAction(HugeIcons.Image02, R.string.photo, {}),
            AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, {}),
            AttachmentAction(HugeIcons.Search01, R.string.use_web_search, {}, checked = true),
            AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, {}),
        ),
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun CacheKeepAliveDrawerItem(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Fire,
                contentDescription = stringResource(R.string.setting_page_cache_keep_alive),
            )
        },
        headlineContent = {
            Text(stringResource(R.string.setting_page_cache_keep_alive))
        },
        trailingContent = {
            me.rerere.rikkihub.ui.components.ui.Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .amoledOutline(MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable { onOpenSettings() },
    )
}
