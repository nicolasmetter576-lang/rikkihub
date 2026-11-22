package me.rerere.rikkihub.ui.floating

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.cli.AcpParts
import me.rerere.rikkihub.data.model.GenerationPhase
import me.rerere.rikkihub.service.ChatError
import me.rerere.rikkihub.ui.components.ui.RabbitLoadingIndicator
import kotlin.uuid.Uuid

/** 预览只取回复末尾这么多字：纯文本不怕截断，太长的文本每次流式更新都要重新排版 */
private const val PREVIEW_MAX_CHARS = 3000

/** 横条只放一行，取末尾这么多字就够滚动了 */
private const val TICKER_MAX_CHARS = 160

/** 悬浮窗展示的会话状态 */
data class FloatingChatUiState(
    val conversationId: Uuid,
    val title: String,
    val generating: Boolean,
    /** 正在压缩对话历史（不是生成任务，单独标出来） */
    val compressing: Boolean,
    /** 网络重试等临时提示 */
    val processingStatus: String?,
    /** 本轮回复在做什么 */
    val phase: GenerationPhase,
    /** 本轮是否已经有回复 */
    val hasReply: Boolean,
    /** 本轮回复的正文末尾，还没写正文时是思考内容 */
    val preview: String,
    val previewIsReasoning: Boolean,
    /** 等待审批的工具调用。CLI 回合在生成中途等审批，所以生成中也可能不为空 */
    val pendingTools: List<UIMessagePart.Tool>,
    val queuedCount: Int,
    val queuePaused: Boolean,
    /** 这一轮出现的错误 */
    val error: ChatError?,
    val isCliSession: Boolean = false,
    val sendBlockedReason: String? = null,
) {
    val failed: Boolean get() = !generating && error != null
}

/** 气泡角上标的结果。变化时贴边半藏的气泡要弹出来让人看到 */
internal enum class BubbleBadge { None, Busy, Pending, Error, Done }

internal fun FloatingChatUiState.bubbleBadge(): BubbleBadge = when {
    pendingTools.isNotEmpty() -> BubbleBadge.Pending
    generating || compressing -> BubbleBadge.Busy
    error != null -> BubbleBadge.Error
    hasReply -> BubbleBadge.Done
    else -> BubbleBadge.None
}

/** 气泡贴着屏幕哪一边 */
enum class BubbleEdge { Start, End }

private val BUBBLE_SIZE = 44.dp

// 圆和窗口边的距离，给阴影留地方；窗口本身紧贴屏幕边
private val BUBBLE_INSET = 14.dp

/**
 * 收起时的小图标：应用图标，生成中换成加载动画，结束后在朝屏幕内侧的角上标出结果。
 * [tucked] 时内容往屏幕外平移，只露出圆朝内的一半，另一半被窗口边（也就是屏幕边）裁掉。
 * 窗口尺寸保持不变：尺寸一变 FloatingX 就按记下的锚点重新摆放，会打断正在开始的拖动
 */
@Composable
fun ChatFloatingBubble(
    state: FloatingChatUiState,
    edge: BubbleEdge,
    tucked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val outward = if (edge == BubbleEdge.End) 1 else -1
    val offset by animateDpAsState(
        targetValue = if (tucked) (BUBBLE_INSET + BUBBLE_SIZE / 2) * outward else 0.dp,
        label = "bubble_tuck",
    )
    Box(modifier = modifier.padding(horizontal = BUBBLE_INSET, vertical = 6.dp)) {
        // 不用 Surface：它会吃掉触摸事件，拖动交给 FloatingX 处理
        Box(modifier = Modifier.graphicsLayer { translationX = offset.toPx() }) {
            Box(
                modifier = Modifier
                    .size(BUBBLE_SIZE)
                    .shadow(5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh)
                    .clickable(onClick = onClick)
                    .testTag("floating_chat_bubble"),
                contentAlignment = Alignment.Center,
            ) {
                if (state.generating || state.compressing) {
                    RabbitLoadingIndicator(modifier = Modifier.size(36.dp))
                } else {
                    LauncherIcon(BUBBLE_SIZE)
                }
            }
            val badgeAlignment = if (edge == BubbleEdge.End) Alignment.TopStart else Alignment.TopEnd
            when (state.bubbleBadge()) {
                BubbleBadge.Pending -> StatusBadge(HugeIcons.Wrench01, colors.error, colors.onError, badgeAlignment)
                BubbleBadge.Error -> StatusBadge(HugeIcons.Alert01, colors.error, colors.onError, badgeAlignment)
                BubbleBadge.Done -> StatusBadge(HugeIcons.Tick01, colors.primary, colors.onPrimary, badgeAlignment)
                BubbleBadge.Busy, BubbleBadge.None -> {}
            }
        }
    }
}

/**
 * 按桌面自适应图标的样子画应用图标：前后景是 108dp 画布，可见部分是中间 72dp，按比例放大后由外层裁成圆；
 * 前景和 ic_launcher.xml 一样按 [R.fraction.launcher_foreground_inset] 往里缩
 */
@Composable
private fun LauncherIcon(size: Dp) {
    val canvas = size * 1.5f
    val inset = LocalResources.current.getFraction(R.fraction.launcher_foreground_inset, 1, 1)
    Image(
        painter = painterResource(R.mipmap.ic_launcher_background),
        contentDescription = null,
        modifier = Modifier.requiredSize(canvas),
    )
    Image(
        painter = painterResource(R.mipmap.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.requiredSize(canvas * (1 - 2 * inset)),
    )
}

/**
 * 生成中代替气泡的横条：左边加载动画，右边一行滚动显示正在写的正文（还没写正文时是思考内容），
 * 新字出来时往左滚，始终露出最新的部分。执行工具、等审批或还没有输出时显示状态
 */
@Composable
fun ChatFloatingStrip(state: FloatingChatUiState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val showsPreview = state.preview.isNotEmpty() && state.pendingTools.isEmpty() &&
        state.phase !is GenerationPhase.RunningTool
    // 不用 Surface：它会吃掉触摸事件，拖动交给 FloatingX 处理
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .height(44.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(start = 6.dp, end = 16.dp)
                .testTag("floating_chat_strip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RabbitLoadingIndicator(modifier = Modifier.size(32.dp))
            TickerText(
                text = if (showsPreview) state.preview.takeLast(TICKER_MAX_CHARS).replace('\n', ' ') else state.statusText(),
                color = when {
                    state.pendingTools.isNotEmpty() -> colors.error
                    showsPreview && !state.previewIsReasoning -> colors.onSurface
                    else -> colors.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// 单行不换行，内容超出时滚到末尾；新内容到来时打断上一段滚动直接追到新的末尾
@Composable
private fun TickerText(text: String, color: Color, modifier: Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collectLatest { scrollState.animateScrollTo(it) }
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .horizontalScroll(scrollState, enabled = false)
            .testTag("floating_chat_ticker"),
    )
}

@Composable
private fun BoxScope.StatusBadge(icon: ImageVector, containerColor: Color, contentColor: Color, alignment: Alignment) {
    Box(
        modifier = Modifier
            .align(alignment)
            .size(18.dp)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(9.dp))
    }
}

/** 展开后的面板：看进度、审批工具、停止生成和快速回复。[maxHeight] 是窗口能占的高度 */
@Composable
fun ChatFloatingPanel(
    state: FloatingChatUiState,
    maxHeight: Dp,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onOpenConversation: () -> Unit,
    onToolApproval: (toolCallId: String, approved: Boolean) -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onResumeQueue: () -> Unit,
) {
    val textMenu = remember { InlineTextContextMenu() }
    // 窗口和屏幕一样宽，大屏上限制宽度并居中
    Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("floating_chat_panel"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PanelHeader(
                    state = state,
                    onOpenConversation = onOpenConversation,
                    onCollapse = onCollapse,
                    onDismiss = onDismiss,
                )
                // 多个审批、横屏或大字体时放不下，中间这段滚动，标题和输入栏始终可见
                if (state.preview.isNotEmpty() || state.pendingTools.isNotEmpty() || state.queuedCount > 0) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.preview.isNotEmpty()) {
                            MessagePreview(
                                text = state.preview,
                                isReasoning = state.previewIsReasoning,
                                generating = state.generating,
                            )
                        }
                        state.pendingTools.forEach { tool ->
                            PendingToolCard(
                                tool = tool,
                                onApproval = { approved -> onToolApproval(tool.toolCallId, approved) },
                                onOpenConversation = onOpenConversation,
                            )
                        }
                        if (state.queuedCount > 0) {
                            QueueRow(count = state.queuedCount, paused = state.queuePaused, onResume = onResumeQueue)
                        }
                    }
                }
                textMenu.Bar()
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides textMenu) {
                    key(state.conversationId) {
                        ReplyBar(
                            conversationId = state.conversationId,
                            generating = state.generating,
                            onSend = onSend,
                            onStop = onStop,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 悬浮窗没有 Activity，系统的文本工具栏（ActionMode）起不来，长按输入框什么都不出。
 * 这里接管 Compose 的文本菜单，把复制、粘贴等菜单项显示成面板里输入框上方的一行按钮，不另开窗口。
 */
private class InlineTextContextMenu : TextContextMenuProvider {
    private class Shown(val dataProvider: TextContextMenuDataProvider, val session: TextContextMenuSession)

    private val mutex = MutatorMutex()
    private var shown by mutableStateOf<Shown?>(null)

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        // 新的菜单请求会取消正在显示的那个
        mutex.mutate {
            val closed = CompletableDeferred<Unit>()
            val session = object : TextContextMenuSession {
                override fun close() {
                    closed.complete(Unit)
                }
            }
            shown = Shown(dataProvider, session)
            try {
                closed.await()
            } finally {
                shown = null
            }
        }
    }

    @Composable
    fun Bar() {
        val current = shown ?: return
        // data() 读的是快照状态，选区变化时跟着重组
        val items = current.dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
        if (items.isEmpty()) return
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("floating_chat_text_menu"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { item ->
                TextButton(onClick = { item.onClick(current.session) }) { Text(item.label) }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    state: FloatingChatUiState,
    onOpenConversation: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.generating || state.compressing) {
            RabbitLoadingIndicator(modifier = Modifier.size(40.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (state.failed) colors.errorContainer else colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = state.statusIcon(),
                    contentDescription = null,
                    tint = if (state.failed) colors.onErrorContainer else colors.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.statusText(),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.failed) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("floating_chat_status"),
            )
        }
        IconButton(onClick = onOpenConversation) {
            Icon(HugeIcons.Fullscreen, contentDescription = stringResource(R.string.floating_chat_open_conversation))
        }
        IconButton(onClick = onCollapse) {
            Icon(HugeIcons.Remove01, contentDescription = stringResource(R.string.floating_chat_collapse))
        }
        IconButton(onClick = onDismiss) {
            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.floating_chat_close))
        }
    }
}

// 纯文本预览：应用里的 MarkdownBlock 依赖导航、Toaster 和 Activity，悬浮窗里没有
@Composable
private fun MessagePreview(text: String, isReasoning: Boolean, generating: Boolean) {
    val scrollState = rememberScrollState()
    // 生成中跟随最新内容；生成完再打开时停在末尾
    LaunchedEffect(generating) {
        if (generating) {
            snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
        } else {
            scrollState.scrollTo(snapshotFlow { scrollState.maxValue }.first { it > 0 })
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (isReasoning) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(scrollState)
                .padding(12.dp)
                .testTag("floating_chat_preview"),
        )
    }
}

@Composable
private fun PendingToolCard(
    tool: UIMessagePart.Tool,
    onApproval: (approved: Boolean) -> Unit,
    onOpenConversation: () -> Unit,
) {
    val isAskUser = tool.isAskUserTool()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isAskUser) {
                    stringResource(R.string.floating_chat_ask_user)
                } else {
                    val toolTitle = if (AcpParts.isAcpTool(tool)) {
                        AcpParts.titleOf(tool)
                    } else {
                        tool.toolName.substringAfterLast("__")
                    }
                    stringResource(R.string.floating_chat_tool_request, toolTitle)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!isAskUser && tool.input.isNotBlank()) {
                Text(
                    text = tool.input.take(300),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (isAskUser) {
                    // 问答有多个问题和选项，在应用里回答
                    Button(onClick = onOpenConversation, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.floating_chat_answer)) }
                } else {
                    OutlinedButton(onClick = { onApproval(false) }, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.chat_message_tool_deny)) }
                    Button(onClick = { onApproval(true) }, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.chat_message_tool_approve)) }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(count: Int, paused: Boolean, onResume: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                if (paused) R.string.chat_page_queue_paused_count else R.string.chat_page_queue_pending_count,
                count,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (paused) {
            TextButton(onClick = onResume) { Text(stringResource(R.string.chat_page_queue_resume)) }
        }
    }
}

@Composable
private fun ReplyBar(
    conversationId: Uuid,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember(conversationId) { mutableStateOf("") }

    fun send() {
        val content = text.trim()
        if (content.isEmpty()) return
        onSend(content)
        text = ""
        focusManager.clearFocus()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).testTag("floating_chat_input"),
            // 生成中发送的消息会排队，等这一轮结束后再发
            placeholder = {
                Text(
                    text = stringResource(
                        if (generating) R.string.floating_chat_reply_queued_placeholder else R.string.floating_chat_reply_placeholder
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        if (generating) {
            FilledTonalIconButton(onClick = onStop) {
                Icon(HugeIcons.Stop, contentDescription = stringResource(R.string.stop))
            }
        }
        FilledIconButton(onClick = ::send, enabled = text.isNotBlank(), modifier = Modifier.testTag("floating_chat_send")) {
            Icon(HugeIcons.ArrowUp02, contentDescription = stringResource(R.string.send))
        }
    }
}

private fun FloatingChatUiState.statusIcon(): ImageVector = when {
    error != null -> HugeIcons.Alert01
    pendingTools.isNotEmpty() -> HugeIcons.Wrench01
    hasReply -> HugeIcons.Tick01
    else -> HugeIcons.Sparkles
}

internal fun UIMessagePart.Tool.isAskUserTool(): Boolean =
    toolName == BuiltinToolIds.ASK_USER || toolName == AcpParts.ASK_QUESTION

@Composable
private fun FloatingChatUiState.statusText(): String {
    // CLI 回合在生成中途等审批，有待审批的就先说这个，免得以为卡住了
    when {
        pendingTools.any { it.isAskUserTool() } ->
            return stringResource(R.string.floating_chat_status_awaiting_answer)
        pendingTools.isNotEmpty() -> return stringResource(R.string.floating_chat_status_awaiting_approval)
        compressing -> return stringResource(R.string.chat_page_compressing)
    }
    if (!generating) {
        return when {
            error != null -> error.title ?: error.error.message ?: stringResource(R.string.floating_chat_status_failed)
            hasReply -> stringResource(R.string.floating_chat_status_done)
            else -> stringResource(R.string.floating_chat_status_idle)
        }
    }
    // 网络重试等提示优先
    processingStatus?.let { return it }
    return when (phase) {
        is GenerationPhase.RunningTool -> stringResource(R.string.notification_live_update_tool, phase.toolName)
        is GenerationPhase.Thinking -> stringResource(R.string.notification_live_update_thinking)
        is GenerationPhase.Writing -> stringResource(R.string.notification_live_update_writing)
        GenerationPhase.Waiting -> stringResource(R.string.notification_live_update_title)
    }
}

/** 预览只要末尾，而且要从行首开始，避免第一行是半截 */
internal fun previewTail(text: String): String {
    if (text.length <= PREVIEW_MAX_CHARS) return text
    val tail = text.takeLast(PREVIEW_MAX_CHARS)
    val lineStart = tail.indexOf('\n')
    return if (lineStart in 0 until tail.length - 1) tail.substring(lineStart + 1) else tail
}
