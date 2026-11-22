package me.rerere.rikkihub.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.pendingToolCalls
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/** 悬浮窗要显示的会话，[expanded] 为 true 时展开成面板 */
data class FloatingChatWindow(val conversationId: Uuid, val expanded: Boolean)

/**
 * 决定后台悬浮窗显示哪个会话、是否展开，不涉及窗口本身。
 *
 * 只在开关打开、应用在后台时工作：切到后台时正在生成的会话，以及之后开始生成的会话（含 Web 端发起的）成为目标；
 * 生成结束后继续显示，方便直接回复推进，用户关掉后等下一个开始生成的会话。
 * 回到前台时全部重置，在应用里看着生成完的会话，切到后台不会再弹出来。
 */
class ChatFloatingTracker(
    appScope: AppScope,
    lazyChatService: Lazy<ChatService>,
    settingsStore: SettingsStore,
) {
    // 只在后台用到，进程启动时不急着创建 ChatService
    private val chatService by lazyChatService

    // 启动时当作在前台：没有界面时启动的进程也没有用户在等的生成
    private val isForeground = MutableStateFlow(true)
    private val actions = MutableSharedFlow<Action>(extraBufferCapacity = 8)

    val window: StateFlow<FloatingChatWindow?> = combine(
        settingsStore.settingsFlow.map { it.displaySetting.enableFloatingChatWindow },
        isForeground,
    ) { enabled, foreground -> enabled && !foreground }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) trackInBackground() else flowOf(null) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        // 持有目标会话的引用，悬浮窗显示期间会话不会因为没有页面打开而被回收；换会话或不再显示时释放
        appScope.launch {
            window.map { it?.conversationId }.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                chatService.addConversationReference(id)
                try {
                    awaitCancellation()
                } finally {
                    chatService.removeConversationReference(id)
                }
            }
        }
    }

    fun expand() {
        actions.tryEmit(Action.Expand)
    }

    fun collapse() {
        actions.tryEmit(Action.Collapse)
    }

    /** 关掉悬浮窗，等下一个开始生成的会话 */
    fun dismiss() {
        actions.tryEmit(Action.Dismiss)
    }

    // 每次切到后台从头开始，这时已经在生成的会话也算刚开始
    private fun trackInBackground(): Flow<FloatingChatWindow?> {
        val currentTarget = AtomicReference<Uuid?>(null)
        return merge(
            chatService.getConversationJobs().map { jobs ->
                val keepTarget = currentTarget.get()?.let { id ->
                    val conv = chatService.getConversationFlow(id).value
                    conv.cliSession != null && conv.pendingToolCalls.isNotEmpty()
                } ?: false
                Action.JobsChanged(jobs.keys, keepTarget = keepTarget)
            },
            actions,
        )
            .scan(TrackingState()) { state, action -> state.reduce(action) }
            .onEach { currentTarget.set(it.target) }
            .map { it.window }
    }

    internal sealed interface Action {
        data class JobsChanged(val running: Set<Uuid>, val keepTarget: Boolean = false) : Action
        data object Expand : Action
        data object Collapse : Action
        data object Dismiss : Action
    }

    // 状态转移是纯函数，单测直接覆盖
    internal data class TrackingState(
        val running: Set<Uuid> = emptySet(),
        val target: Uuid? = null,
        val expanded: Boolean = false,
    ) {
        val window: FloatingChatWindow? get() = target?.let { FloatingChatWindow(it, expanded) }

        fun reduce(action: Action): TrackingState = when (action) {
            is Action.JobsChanged -> copy(
                running = action.running,
                // 同时开始的会话任取一个；面板展开时用户可能正在输入，或当前 CLI 会话有待审批工具时，别的会话开始也不换走
                target = target?.takeIf { expanded || action.keepTarget } ?: (action.running - running).lastOrNull() ?: target,
            )

            Action.Expand -> copy(expanded = target != null)
            Action.Collapse -> copy(expanded = false)
            Action.Dismiss -> copy(target = null, expanded = false)
        }
    }
}
