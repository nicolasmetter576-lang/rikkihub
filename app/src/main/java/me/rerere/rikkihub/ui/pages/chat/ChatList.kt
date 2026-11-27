package me.rerere.rikkihub.ui.pages.chat

import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.components.richtext.findMatches
import me.rerere.rikkihub.ui.components.richtext.LocalFindHighlight
import me.rerere.rikkihub.ui.components.richtext.FindHighlight
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import me.rerere.rikkihub.ui.theme.LocalAmoledStrong
import me.rerere.rikkihub.ui.theme.amoledOutline
import me.rerere.rikkihub.ui.theme.amoledOutlineBorder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.cli.displayModelOrNull
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.getAssistantById
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.service.ChatError
import me.rerere.rikkihub.ui.components.message.ChatMessage
import me.rerere.rikkihub.ui.components.message.LocalIsCliSession
import me.rerere.rikkihub.ui.components.message.LocalMessageDepthFromEnd
import me.rerere.rikkihub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkihub.ui.components.ui.ListSelectableItem
import me.rerere.rikkihub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkihub.ui.components.ui.Tooltip
import me.rerere.rikkihub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkihub.ui.theme.ChatFontProvider
import me.rerere.rikkihub.utils.plus
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import androidx.compose.runtime.CompositionLocalProvider
import me.rerere.rikkihub.data.workspace.workspaceImageContext
import me.rerere.rikkihub.ui.context.LocalWorkspaceImages

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onCompressAndRetry: (conversationId: Uuid) -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onPreviewWorkspaceFile: ((String) -> Unit)? = null,
    onOpenContextBreakdown: (() -> Unit)? = null,
) {
    // 消息里写成工作区路径的图片（/workspace/out.png）要到这个会话的工作区里去读
    val workspaceId = settings.getAssistantById(conversation.assistantId)?.workspaceId?.toString()
    val workspaceImages = remember(workspaceId, conversation.workspaceCwd, conversation.workspaceCwdScoped) {
        workspaceImageContext(workspaceId, conversation.workspaceCwd, conversation.workspaceCwdScoped)
    }
    // 会话内查找：从预览列表点开一个命中后进入，正文里标出命中，顶部一条查找栏可以上一个 / 下一个
    var findQuery by remember(conversation.id) { mutableStateOf<String?>(null) }
    var findIndex by remember(conversation.id) { mutableIntStateOf(0) }
    val findHits = remember(conversation.messageNodes, findQuery) {
        findQuery?.let { conversationFindHits(conversation.messageNodes, it) } ?: emptyList()
    }
    CompositionLocalProvider(LocalWorkspaceImages provides workspaceImages) {
        AnimatedContent(
            targetState = previewMode,
            label = "ChatListMode",
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
            }
        ) { target ->
            if (target) {
                ChatListPreview(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    settings = settings,
                    hazeState = hazeState,
                    onJumpToMessage = { index, query ->
                        if (query.isBlank()) {
                            findQuery = null
                        } else {
                            findQuery = query
                            findIndex = conversationFindHits(conversation.messageNodes, query)
                                .indexOfFirst { it.nodeIndex == index }.coerceAtLeast(0)
                        }
                        onJumpToMessage(index)
                    },
                    animatedVisibilityScope = this@AnimatedContent,
                )
            } else {
                ChatListNormal(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    state = state,
                    loading = loading,
                    processingStatus = processingStatus,
                    settings = settings,
                    hazeState = hazeState,
                    errors = errors,
                    onDismissError = onDismissError,
                    onClearAllErrors = onClearAllErrors,
                    onCompressAndRetry = onCompressAndRetry,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onForkMessage = onForkMessage,
                    onDelete = onDelete,
                    onUpdateMessage = onUpdateMessage,
                    onClickSuggestion = onClickSuggestion,
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation,
                    animatedVisibilityScope = this@AnimatedContent,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onToggleFavorite = onToggleFavorite,
                    onConversationSystemPromptChange = onConversationSystemPromptChange,
                    onPreviewWorkspaceFile = onPreviewWorkspaceFile,
                    onOpenContextBreakdown = onOpenContextBreakdown,
                    findQuery = findQuery,
                    findHits = findHits,
                    findIndex = findIndex.coerceIn(0, (findHits.size - 1).coerceAtLeast(0)),
                    onFindStep = { delta ->
                        if (findHits.isNotEmpty()) findIndex = Math.floorMod(findIndex + delta, findHits.size)
                    },
                    onFindClose = { findQuery = null },
                )
            }
        }
    }
}

/** 一处命中：所在消息的下标，和命中在这条消息文字里的相对位置（0..1，滚动时据此定位；标题里的算 0）。 */
internal data class FindHit(val nodeIndex: Int, val fraction: Float)

/** 当前分支每条消息的正文（文字部分）和每轮标题里的全部命中，按顺序。 */
internal fun conversationFindHits(nodes: List<MessageNode>, query: String): List<FindHit> = buildList {
    nodes.forEachIndexed { index, node ->
        val message = node.currentMessage
        message.title?.let { title -> repeat(findMatches(title, query).size) { add(FindHit(index, 0f)) } }
        val text = message.toText()
        findMatches(text, query).forEach { add(FindHit(index, it.first.toFloat() / text.length.coerceAtLeast(1))) }
    }
}

@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onCompressAndRetry: (conversationId: Uuid) -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onPreviewWorkspaceFile: ((String) -> Unit)? = null,
    onOpenContextBreakdown: (() -> Unit)? = null,
    findQuery: String? = null,
    findHits: List<FindHit> = emptyList(),
    findIndex: Int = 0,
    onFindStep: (Int) -> Unit = {},
    onFindClose: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    val findNodes = remember(findHits) { findHits.mapTo(HashSet()) { it.nodeIndex } }
    // 跳到当前命中：先滚到那条消息，再按命中在消息里的相对位置往下挪，让它落在视口上部
    LaunchedEffect(findQuery, findIndex, findHits.size) {
        val hit = findHits.getOrNull(findIndex) ?: return@LaunchedEffect
        state.scrollToItem(hit.nodeIndex)
        withFrameNanos { }
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == hit.nodeIndex } ?: return@LaunchedEffect
        val viewport = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset
        val offset = (hit.fraction * info.size - viewport / 3f).toInt()
        if (offset > 0) state.scrollToItem(hit.nodeIndex, offset)
    }
    var isRecentScroll by remember { mutableStateOf(false) }
    val conversationUpdated by rememberUpdatedState(conversation)
    val settingsState by rememberUpdatedState(settings)
    val innerPaddingState by rememberUpdatedState(innerPadding)
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkihub.RouteActivity

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            val latestSettings = settingsState
            val latestInnerPadding = innerPaddingState
            if (latestSettings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + latestInnerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    latestSettings.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = (state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt())
        // println("lastPos = $lastPos, inputPos = $inputPos  | ${lastPos <= inputPos - 8}")
        return lastPos <= inputPos - 8
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    // 定稿锁：这个对话的回复动了锁定内容时，在那条回复下面显示一张卡片
    val bookHistory = org.koin.compose.koinInject<me.rerere.rikkihub.data.book.history.BookHistory>()
    val navController = me.rerere.rikkihub.ui.context.LocalNavController.current
    val lockWorkspace = settings.assistants.firstOrNull { it.id == conversation.assistantId }?.workspaceId?.toString()
    val lockNotices by remember(lockWorkspace, conversation.id) {
        lockWorkspace?.let { bookHistory.conversationNotices(it, conversation.id.toString()) } ?: kotlinx.coroutines.flow.flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
    // 逐处审阅：回复下面的审阅行（与锁提醒合成一张卡）
    val turnRecords by remember(lockWorkspace, conversation.id) {
        lockWorkspace?.let { bookHistory.conversationTurns(it, conversation.id.toString()) } ?: kotlinx.coroutines.flow.flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
    val turnMuted by remember(lockWorkspace) {
        lockWorkspace?.let { bookHistory.mutedFlow(it) } ?: kotlinx.coroutines.flow.flowOf(emptySet())
    }.collectAsStateWithLifecycle(emptySet())
    val turnCards = remember(turnRecords, turnMuted, conversation.messageNodes) {
        me.rerere.rikkihub.ui.pages.extensions.workspace.book.turnCards(turnRecords, conversation.id.toString() in turnMuted, conversation.messageNodes)
    }
    val turnScope = androidx.compose.runtime.rememberCoroutineScope()
    val assistant = remember(settings.assistants, conversation.assistantId) {
        settings.getAssistantById(conversation.assistantId)
    }
    val lastMessageIndex = conversation.messageNodes.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 自动滚动到底部
        if (settings.displaySetting.enableAutoScroll) {
            LaunchedEffect(state) {
                snapshotFlow {
                    state.layoutInfo.visibleItemsInfo to conversationUpdated.messageNodes.size
                }
                    .conflate()
                    .distinctUntilChanged { old, new ->
                        old.first.isAtBottom() == new.first.isAtBottom() && old.second == new.second
                    }
                    .collect { (visibleItemsInfo, _) ->
                        // println("is bottom = ${visibleItemsInfo.isAtBottom()}, scroll = ${state.isScrollInProgress}, can_scroll = ${state.canScrollForward}, loading = $loading")
                        if (!state.isScrollInProgress && loadingState) {
                            if (visibleItemsInfo.isAtBottom()) {
                                state.requestScrollToItem(conversationUpdated.messageNodes.lastIndex + 10)
                                // Log.i(TAG, "ChatList: scroll to ${conversationUpdated.messageNodes.lastIndex}")
                            }
                        }
                    }
            }
        }

        // 判断最近是否滚动
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        ChatFontProvider(displaySetting = settings.displaySetting) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.md) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id },
                contentType = { _, _ -> "message" },
            ) { index, node ->
                Column {
                    ListSelectableItem(
                        key = node.id,
                        onSelectChange = {
                            if (!selectedItems.contains(node.id)) {
                                selectedItems.add(node.id)
                            } else {
                                selectedItems.remove(node.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting,
                    ) {
                        CompositionLocalProvider(
                            LocalMessageDepthFromEnd provides (lastMessageIndex - index),
                            LocalIsCliSession provides (conversation.cliSession != null),
                            LocalFindHighlight provides findQuery?.takeIf { index in findNodes }?.let {
                                FindHighlight(it, current = findHits.getOrNull(findIndex)?.nodeIndex == index)
                            },
                        ) {
                            ChatMessage(
                                node = node,
                                model = node.currentMessage.displayModelOrNull(settings.providers),
                                assistant = assistant,
                                loading = loading && index == lastMessageIndex,
                                onRegenerate = {
                                    onRegenerate(node.currentMessage)
                                },
                                onEdit = {
                                    onEdit(node.currentMessage)
                                },
                                onFork = {
                                    onForkMessage(node.currentMessage)
                                },
                                onDelete = {
                                    onDelete(node.currentMessage)
                                },
                                onShare = {
                                    selecting = true  // 使用 CoroutineScope 延迟状态更新
                                    selectedItems.clear()
                                    selectedItems.addAll(conversation.messageNodes.map { it.id }
                                        .subList(0, conversation.messageNodes.indexOf(node) + 1))
                                },
                                onUpdate = {
                                    onUpdateMessage(it)
                                },
                                isFavorite = node.isFavorite,
                                onToggleFavorite = {
                                    onToggleFavorite?.invoke(node)
                                },
                                onTranslate = onTranslate,
                                onClearTranslation = onClearTranslation,
                                onToolApproval = onToolApproval,
                                onToolAnswer = onToolAnswer,
                                onPreviewWorkspaceFile = onPreviewWorkspaceFile,
                                onOpenContextBreakdown = onOpenContextBreakdown,
                                workspaceCwd = conversation.workspaceCwd,
                                workspaceCwdScoped = conversation.workspaceCwdScoped,
                                isCliSession = (conversation.cliSession != null),
                                lastMessage = index == lastMessageIndex,
                            )
                        }
                    }
                    val notices = lockNotices[node.currentMessage.id.toString()].orEmpty()
                    val turnCard = turnCards[node.currentMessage.id.toString()]
                    if (lockWorkspace != null && !selecting) {
                        val review = { record: me.rerere.rikkihub.data.book.history.TurnRecord ->
                            navController.navigate(me.rerere.rikkihub.Screen.BookTurnReview(lockWorkspace, record.conversationId, record.messageId))
                        }
                        if (notices.isNotEmpty() || turnCard?.full == true) {
                            me.rerere.rikkihub.ui.pages.extensions.workspace.book.ConversationLockCard(
                                lockWorkspace,
                                notices,
                                onView = { notice -> navController.navigate(me.rerere.rikkihub.Screen.BookHistory(lockWorkspace, notice.path, notice.id)) },
                                review = turnCard?.takeIf { it.full }?.let { card ->
                                    {
                                        me.rerere.rikkihub.ui.pages.extensions.workspace.book.TurnReviewRow(
                                            card,
                                            onReview = { review(card.record) },
                                            onMute = { turnScope.launch { bookHistory.mute(lockWorkspace, conversation.id.toString(), true) } },
                                        )
                                    }
                                },
                            )
                        }
                        if (turnCard != null && !turnCard.full) {
                            me.rerere.rikkihub.ui.pages.extensions.workspace.book.TurnSmallLine(turnCard) { review(turnCard.record) }
                        }
                        turnCard?.sibling?.let { sibling ->
                            me.rerere.rikkihub.ui.pages.extensions.workspace.book.TurnSiblingLine { review(sibling) }
                        }
                    }
                }
            }

            if (!loading && assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) {
                item(key = "ConversationSystemPrompt") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ConversationSystemPromptButton(
                            customSystemPrompt = conversation.customSystemPrompt,
                            onSystemPromptChange = onConversationSystemPromptChange,
                        )
                        if (conversation.cliSession != null) {
                            Text(
                                text = stringResource(R.string.cli_system_prompt_restarts_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(28.dp)
                        )
                        AnimatedVisibility(
                            visible = processingStatus != null,
                        ) {
                            Text(
                                text = processingStatus ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 为了能正确滚动到这
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onCompressAndRetry = onCompressAndRetry,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            if (findQuery != null) {
                FindBar(
                    query = findQuery,
                    index = findIndex,
                    total = findHits.size,
                    onStep = onFindStep,
                    onClose = onFindClose,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (index: Int, query: String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val debouncedQuery by produceState(initialValue = "", key1 = searchQuery) {
        snapshotFlow { searchQuery }
            .debounce(200)
            .collect { value = it }
    }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, debouncedQuery) {
        if (debouncedQuery.isBlank()) {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
        } else {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
                .filter { (_, node) ->
                    val message = node.currentMessage
                    message.title?.contains(debouncedQuery, ignoreCase = true) == true ||
                        message.toText().contains(debouncedQuery, ignoreCase = true)
                }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize()
            .hazeSource(state = hazeState),
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        if (debouncedQuery.isNotBlank()) {
            val total = remember(conversation.messageNodes, debouncedQuery) {
                conversationFindHits(conversation.messageNodes, debouncedQuery.trim()).size
            }
            Text(
                text = stringResource(R.string.chat_find_match_count, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.md) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
                contentType = { _, _ -> "preview_message" },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex, debouncedQuery.trim())
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightedText = remember(debouncedQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val title = message.title
                                // 有每轮标题就显示标题；只有正文命中时才显示正文里命中的那段
                                val messageText = if (title != null && (debouncedQuery.isBlank() ||
                                        title.contains(debouncedQuery, ignoreCase = true) ||
                                        !fullText.contains(debouncedQuery, ignoreCase = true))
                                ) {
                                    title
                                } else {
                                    extractMatchingSnippet(text = fullText, query = debouncedQuery)
                                }
                                buildHighlightedText(
                                    text = messageText,
                                    query = debouncedQuery,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    index: Int,
    total: Int,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(HugeIcons.Search01, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = query,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f, fill = false),
            )
            Text(
                text = if (total == 0) "0/0" else "${index + 1}/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { onStep(-1) }, enabled = total > 0) {
                Icon(HugeIcons.ArrowUp01, contentDescription = stringResource(R.string.chat_find_previous), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { onStep(1) }, enabled = total > 0) {
                Icon(HugeIcons.ArrowDown01, contentDescription = stringResource(R.string.chat_find_next), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onClose) {
                Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.chat_find_close), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    // AMOLED 强劲模式下不叠色调，纯黑加细描边
                    .amoledOutline(RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(
                        if (LocalAmoledStrong.current) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                    )
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        // AMOLED 强劲模式下按钮不叠色调，纯黑加细描边
        val jumperColor = if (LocalAmoledStrong.current) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f)
        }
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = jumperColor,
                border = amoledOutlineBorder()
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = jumperColor,
                border = amoledOutlineBorder()
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                color = jumperColor,
                border = amoledOutlineBorder()
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = jumperColor,
                border = amoledOutlineBorder(),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
