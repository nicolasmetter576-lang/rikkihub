package me.rerere.rikkihub.ui.floating

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.FxListener
import com.petterp.floatingx.core.animation.FxAnimations
import com.petterp.floatingx.core.gesture.FxDrag
import com.petterp.floatingx.core.gesture.FxGesture
import com.petterp.floatingx.core.layout.FxAdsorb
import com.petterp.floatingx.core.layout.FxAnchor
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.core.storage.FxSpStorage
import com.petterp.floatingx.system.permission.FxPermission
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.petterp.floatingx.system.systemHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.ui.displayTextOf
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.R
import me.rerere.rikkihub.conversationIntent
import me.rerere.rikkihub.data.cli.CliAgent
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.getAssistantById
import me.rerere.rikkihub.data.datastore.getCurrentAssistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.GenerationPhase
import me.rerere.rikkihub.data.model.generationPhase
import me.rerere.rikkihub.data.model.pendingToolCalls
import me.rerere.rikkihub.service.ChatError
import me.rerere.rikkihub.service.ChatFloatingTracker
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.service.CompressStatus
import me.rerere.rikkihub.service.FloatingChatWindow
import me.rerere.rikkihub.service.MessageQueueState
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.pages.chat.cli.CliInput
import me.rerere.rikkihub.ui.theme.RikkihubTheme
import kotlin.uuid.Uuid

private const val TAG = "ChatFloatingWindows"
private const val BUBBLE_TAG = "chat_floating_bubble"
private const val PANEL_TAG = "chat_floating_panel"
private const val STRIP_TAG = "chat_floating_strip"
private const val PANEL_TOP_OFFSET_DP = 8f

// 气泡贴边后没有操作这么久就缩进屏幕边
private const val BUBBLE_AUTO_HIDE_DELAY_MS = 3000L

// 发出打开会话的 Intent 后等这么久还没回到前台，就当被系统拦了
private const val OPEN_CONVERSATION_TIMEOUT_MS = 1500L

/**
 * 把 [ChatFloatingTracker] 的结果显示成系统悬浮窗：收起时是贴边的小图标，展开是固定在顶部的面板。
 * 两个窗口共用这里汇总的一份 [FloatingChatUiState]。FloatingX 只能在主线程操作，协程都跑在 AppScope 上。
 */
class ChatFloatingWindows(
    private val context: Application,
    private val appScope: AppScope,
    private val tracker: ChatFloatingTracker,
    private val settingsStore: SettingsStore,
    lazyChatService: Lazy<ChatService>,
) {
    private val chatService by lazyChatService
    private var bubble: FxControl? = null
    private var strip: FxControl? = null
    private var panel: FxControl? = null

    // 气泡的贴边半藏：显示、拖动、换边都由 FloatingX 回调更新，气泡内容据此决定何时缩进去
    private val bubbleEdge = MutableStateFlow(BubbleEdge.End)
    private val bubbleDragging = MutableStateFlow(false)
    private val bubbleShows = MutableStateFlow(0)

    // 悬浮窗显示期间一直汇总，气泡和面板切换时直接拿到当前状态，不会先空一帧
    private val uiState: StateFlow<FloatingChatUiState?> = tracker.window
        .map { it?.conversationId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else chatState(id) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        appScope.launch {
            combine(
                tracker.window,
                uiState,
                settingsStore.settingsFlow.map { it.displaySetting.floatingChatStripWhileGenerating },
            ) { window, state, stripEnabled ->
                // 刚换会话时汇总的还是上一个会话的状态，不能拿它的生成状态
                val generating = state?.conversationId == window?.conversationId && state?.generating == true
                floatingWindowMode(window, generating, stripEnabled)
            }.distinctUntilChanged().collect(::render)
        }
    }

    private fun chatState(id: Uuid): Flow<FloatingChatUiState> {
        // 只显示这一轮的错误。开始时间要用启动任务时记下的，等这里观察到任务再取会晚于快速失败时报的错
        val error = combine(chatService.errors, chatService.getGenerationStartedAtFlow(id)) { errors, since ->
            turnError(errors, id, since)
        }
        val conversationAndSettings = combine(
            chatService.getConversationFlow(id),
            chatService.getReplyMessageIdFlow(id),
            settingsStore.settingsFlow,
            ::Triple,
        )
        val activity = combine(
            chatService.getGenerationJobStateFlow(id).map { it != null },
            chatService.compressStateFlow(id).map { it?.status == CompressStatus.RUNNING },
            ::Pair,
        )
        return combine(
            conversationAndSettings,
            activity,
            chatService.getProcessingStatusFlow(id),
            chatService.getMessageQueueFlow(id),
            error,
        ) { (conversation, replyMessageId, settings), (generating, compressing), processingStatus, queue, error ->
            val blockedReason = resolveSendBlockedReason(
                conversation = conversation,
                settings = settings,
                noModelMessage = context.getString(R.string.chat_page_voice_select_model),
                noWorkspaceMessage = context.getString(R.string.cli_error_no_workspace),
                noAgentMessage = context.getString(R.string.cli_error_agent_missing),
            )
            buildFloatingChatUiState(
                conversation = conversation,
                replyMessageId = replyMessageId,
                generating = generating,
                compressing = compressing,
                processingStatus = processingStatus,
                queue = queue,
                error = error,
                sendBlockedReason = blockedReason,
            )
        }.flowOn(Dispatchers.Default)
    }

    private fun render(mode: FloatingWindowMode?) {
        // 后台弹不出权限申请页，没有权限就不显示
        if (mode == null || !FxPermission.isGranted(context)) {
            listOfNotNull(bubble, strip, panel).forEach { it.cancel() }
            bubble = null
            strip = null
            panel = null
            return
        }
        val shown = when (mode) {
            FloatingWindowMode.Bubble -> bubble ?: installBubble().also { bubble = it }
            FloatingWindowMode.Strip -> strip ?: installStrip().also { strip = it }
            FloatingWindowMode.Panel -> panel ?: installPanel().also { panel = it }
        }
        listOfNotNull(bubble, strip, panel).filter { it !== shown }.forEach { it.hide() }
        shown.show()
    }

    // 窗口紧贴屏幕边（不留 margin），半藏时内容往外平移，被窗口边裁掉的就是屏幕外的那一半
    private fun installBubble(): FxControl = FloatingX.install(BUBBLE_TAG) {
        compose {
            WindowContent { state ->
                val autoHide = LocalSettings.current.displaySetting.floatingChatAutoHide
                val edge by bubbleEdge.collectAsState()
                val dragging by bubbleDragging.collectAsState()
                val shows by bubbleShows.collectAsState()
                var pressed by remember { mutableStateOf(false) }
                var tucked by remember { mutableStateOf(false) }
                LaunchedEffect(autoHide, dragging, pressed, shows, state.bubbleBadge()) {
                    tucked = false
                    if (autoHide && !dragging && !pressed) {
                        delay(BUBBLE_AUTO_HIDE_DELAY_MS)
                        tucked = true
                    }
                }
                ChatFloatingBubble(
                    state = state,
                    edge = edge,
                    tucked = tucked,
                    onClick = tracker::expand,
                    // 拖动由 FloatingX 在父容器里拦截，这里只看按下和松开（拦截后收到的是取消）
                    modifier = Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            waitForUpOrCancellation()
                            pressed = false
                        }
                    },
                )
            }
        }
        anchor(FxGravity.CENTER_END, dy = -dp(96f))
        adsorb(FxAdsorb.horizontal())
        persist(FxSpStorage(context))
        animation(FxAnimations.scale())
        systemHost(context) { permission(FxPermissionStrategy.skip()) }
    }.apply {
        addListener(object : FxListener {
            override fun onShow(control: FxControl) {
                updateBubbleEdge(control)
                bubbleShows.update { it + 1 }
            }

            override fun onDragStart(control: FxControl) {
                bubbleDragging.value = true
            }

            override fun onDragEnd(control: FxControl, x: Float, y: Float) {
                updateBubbleEdge(control)
                bubbleDragging.value = false
            }

            override fun onPositionChanged(control: FxControl, anchor: FxAnchor) {
                updateBubbleEdge(control)
            }
        })
    }

    // 按窗口在屏幕上的实际位置判断贴哪边：拖动后 FloatingX 记下的锚点都换成了左上角加偏移，看不出贴边方向。
    // 第一次显示时窗口还没布局，宽度为 0，等之后的位置回调
    private fun updateBubbleEdge(control: FxControl) {
        val view = control.contentView ?: return
        if (view.width == 0) return
        val location = IntArray(2).also(view::getLocationOnScreen)
        val onEnd = location[0] + view.width / 2 > context.resources.displayMetrics.widthPixels / 2
        bubbleEdge.value = if (onEnd) BubbleEdge.End else BubbleEdge.Start
    }

    // 横条和屏幕一样宽，只能上下拖，位置单独记住
    private fun installStrip(): FxControl = FloatingX.install(STRIP_TAG) {
        compose {
            WindowContent { state -> ChatFloatingStrip(state = state, onClick = tracker::expand) }
        }
        anchor(FxGravity.TOP_CENTER, dy = dp(PANEL_TOP_OFFSET_DP))
        adsorb(FxAdsorb.none())
        persist(FxSpStorage(context))
        animation(FxAnimations.fade())
        systemHost(context) {
            permission(FxPermissionStrategy.skip())
            layoutParams { it.width = WindowManager.LayoutParams.MATCH_PARENT }
        }
    }

    private fun installPanel(): FxControl = FloatingX.install(PANEL_TAG) {
        compose {
            WindowContent { state ->
                val conversationId = state.conversationId
                PanelBackHandler(onBack = tracker::collapse)
                ChatFloatingPanel(
                    state = state,
                    maxHeight = rememberPanelMaxHeight(),
                    onCollapse = tracker::collapse,
                    onDismiss = tracker::dismiss,
                    onOpenConversation = { openConversation(conversationId) },
                    onToolApproval = { toolCallId, approved ->
                        reportFailure(conversationId) { chatService.handleToolApproval(conversationId, toolCallId, approved) }
                    },
                    onStop = { appScope.launch { chatService.stopGeneration(conversationId) } },
                    onSend = { text ->
                        fun sendPrompt() {
                            val blockedReason = state.sendBlockedReason
                            if (blockedReason != null) {
                                Toast.makeText(context, blockedReason, Toast.LENGTH_SHORT).show()
                                return
                            }
                            reportFailure(conversationId) {
                                chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(text)))
                            }
                        }
                        if (state.isCliSession) {
                            when (val input = CliInput.parse(text)) {
                                is CliInput.Shell -> {
                                    if (state.generating || state.compressing) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.floating_chat_cli_cmd_busy),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else if (state.sendBlockedReason != null) {
                                        Toast.makeText(context, state.sendBlockedReason, Toast.LENGTH_SHORT).show()
                                    } else {
                                        appScope.launch {
                                            runCatching { chatService.runCliShellCommand(conversationId, input.command) }
                                                .onFailure { chatService.addError(it, conversationId) }
                                        }
                                    }
                                }
                                is CliInput.LocalCommand -> {
                                    if (input.local == CliInput.Local.RESTART) {
                                        appScope.launch {
                                            runCatching { chatService.restartCliSession(conversationId) }
                                                .onFailure { chatService.addError(it, conversationId) }
                                        }
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.floating_chat_cli_cmd_open_app),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                CliInput.Prompt -> sendPrompt()
                            }
                        } else {
                            sendPrompt()
                        }
                    },
                    onResumeQueue = { reportFailure(conversationId) { chatService.resumeMessageQueue(conversationId) } },
                )
            }
        }
        anchor(FxGravity.TOP_CENTER, dy = dp(PANEL_TOP_OFFSET_DP))
        gesture(FxGesture(click = false, longPress = false, drag = FxDrag.DISABLED))
        animation(FxAnimations.fade())
        systemHost(context) {
            permission(FxPermissionStrategy.skip())
            layoutParams {
                // 面板里有输入框，窗口要能获得焦点才能弹键盘；收起时窗口是 GONE，不会抢走其他应用的焦点
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                // wrap_content 的窗口会先按对话框宽度（约 320dp）测量，面板会被压窄
                it.width = WindowManager.LayoutParams.MATCH_PARENT
            }
            // Android 13 以下返回键以按键事件送到窗口，FloatingX 在这里接
            onBackPressed {
                tracker.collapse()
                true
            }
        }
    }

    // 悬浮窗里的操作直接从点击调用，同步抛出的异常（比如压缩中审批）不能让应用崩溃，记成本轮的错误显示在面板上
    private inline fun reportFailure(conversationId: Uuid, block: () -> Unit) {
        runCatching(block).onFailure { chatService.addError(it, conversationId) }
    }

    // 窗口隐藏时生命周期退回 STARTED，只在显示时订阅，流式输出不会让隐藏的窗口跟着重组
    @Composable
    private fun WindowContent(content: @Composable (FloatingChatUiState) -> Unit) {
        val state by uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        // 悬浮窗只在应用退到后台时显示，这时 Activity 上的常亮标志已经失效，生成期间改由显示着的悬浮窗保持亮屏。
        // 隐藏的窗口是 GONE，它的标志不起作用；手动按电源键锁屏不受影响
        val view = LocalView.current
        val generating = state?.generating == true
        DisposableEffect(view, generating) {
            view.keepScreenOn = generating
            onDispose { view.keepScreenOn = false }
        }
        CompositionLocalProvider(LocalSettings provides settings) {
            RikkihubTheme {
                // 整个窗口一起变淡，透出下面应用的内容；只影响绘制，触摸照常
                Box(modifier = Modifier.graphicsLayer { alpha = settings.displaySetting.floatingChatOpacity }) {
                    state?.let { content(it) }
                }
            }
        }
    }

    // 应用开了 enableOnBackInvokedCallback，Android 13 起返回键不再以按键事件送到窗口，FloatingX 的 onBackPressed 收不到，
    // 要在窗口自己的 OnBackInvokedDispatcher 上注册
    @Composable
    private fun PanelBackHandler(onBack: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val view = LocalView.current
        DisposableEffect(view) {
            val dispatcher = view.findOnBackInvokedDispatcher()
            val callback = OnBackInvokedCallback { onBack() }
            dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
        }
    }

    // 面板窗口是 wrap_content 且不受屏幕限制（FloatingX 用了 NO_LIMITS），内容多了会伸出屏幕底部，按可用高度封顶
    @Composable
    private fun rememberPanelMaxHeight(): Dp {
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        return remember(configuration, density) {
            with(density) { (safeScreenHeight() - dp(PANEL_TOP_OFFSET_DP)).toDp() }
        }
    }

    // 和 FloatingX 摆放窗口用的安全区一致：R 及以上去掉屏幕级的系统栏和刘海，R 以下没有公开入口，用不含导航栏的高度
    private fun safeScreenHeight(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context.resources.displayMetrics.heightPixels
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return metrics.bounds.height() - insets.top - insets.bottom
    }

    private fun openConversation(conversationId: Uuid) {
        // 有悬浮窗权限的应用允许从后台启动 Activity；部分系统（如 ColorOS）还另有「后台弹出界面」限制，会静默拦下
        try {
            context.startActivity(
                conversationIntent(context, conversationId.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "openConversation failed", e)
            return
        }
        appScope.launch {
            delay(OPEN_CONVERSATION_TIMEOUT_MS)
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Toast.makeText(context, R.string.floating_chat_open_blocked, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}

/** 悬浮窗此刻用哪个窗口显示 */
internal enum class FloatingWindowMode { Bubble, Strip, Panel }

/** 展开时是面板；收起时生成中、开了横条显示横条，其余是气泡；没有目标会话时不显示 */
internal fun floatingWindowMode(window: FloatingChatWindow?, generating: Boolean, stripEnabled: Boolean): FloatingWindowMode? =
    when {
        window == null -> null
        window.expanded -> FloatingWindowMode.Panel
        generating && stripEnabled -> FloatingWindowMode.Strip
        else -> FloatingWindowMode.Bubble
    }

/** 这一轮（[since] 之后）让回复失败的最近一个错误；标题生成失败这类不影响回复的不算 */
internal fun turnError(errors: List<ChatError>, conversationId: Uuid, since: Long?): ChatError? =
    since?.let { errors.lastOrNull { it.conversationId == conversationId && it.timestamp >= since && it.affectsReply } }

internal fun resolveSendBlockedReason(
    conversation: Conversation,
    settings: Settings,
    noModelMessage: String,
    noWorkspaceMessage: String,
    noAgentMessage: String,
): String? {
    val cli = conversation.cliSession
    return if (cli != null) {
        val assistant = settings.getAssistantById(conversation.assistantId)
        when {
            assistant?.workspaceId == null -> noWorkspaceMessage
            CliAgent.find(settings, cli.agentId) == null -> noAgentMessage
            else -> null
        }
    } else {
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        if (settings.findModelById(assistant.chatModelId ?: settings.chatModelId) == null) noModelMessage else null
    }
}

/** [replyMessageId] 是本轮正在写的助手消息，这一轮还没有输出时为空 */
internal fun buildFloatingChatUiState(
    conversation: Conversation,
    replyMessageId: Uuid?,
    generating: Boolean,
    compressing: Boolean,
    processingStatus: String?,
    queue: MessageQueueState,
    error: ChatError?,
    sendBlockedReason: String? = null,
): FloatingChatUiState {
    // 不看末尾也不看节点选中的分支：重新生成较早的消息时，后面的节点还在，用户也可能切回了旧回复
    val reply = replyMessageId?.let { id ->
        conversation.messageNodes.firstNotNullOfOrNull { node -> node.messages.firstOrNull { it.id == id } }
    }
    val text = reply?.parts?.filterIsInstance<UIMessagePart.Text>()?.lastOrNull()?.let { reply.displayTextOf(it, streaming = true) }?.trim().orEmpty()
    val reasoning = reply?.parts?.filterIsInstance<UIMessagePart.Reasoning>()?.lastOrNull()?.reasoning?.trim().orEmpty()
    return FloatingChatUiState(
        conversationId = conversation.id,
        title = conversation.title,
        generating = generating,
        compressing = compressing,
        processingStatus = processingStatus,
        phase = reply?.generationPhase() ?: GenerationPhase.Waiting,
        hasReply = reply != null,
        // 还没写正文时显示思考内容
        preview = previewTail(text.ifEmpty { reasoning }),
        previewIsReasoning = text.isEmpty(),
        // 和 ChatService 判断是否在等审批用同一个条件，否则快速回复会被审批挡住却看不到原因
        pendingTools = conversation.pendingToolCalls,
        queuedCount = queue.messages.size,
        queuePaused = queue.paused,
        error = error,
        isCliSession = conversation.cliSession != null,
        sendBlockedReason = sendBlockedReason,
    )
}
