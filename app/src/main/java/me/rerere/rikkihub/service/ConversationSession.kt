package me.rerere.rikkihub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.claudelife.CacheWarmer
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkihub.data.ai.GenerationAttemptProtocolException
import me.rerere.rikkihub.data.ai.restoreProtocolFailedAttempt
import me.rerere.rikkihub.data.model.Conversation
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
    /**
     * 缓存保活（claudelife）：每个会话自有一个 warmer，同一时刻最多一个活动任务。
     * 参数为任务结束回调（用于恢复空闲回收）；null 表示不启用（测试/无 Koin 场景）。
     */
    cacheWarmerFactory: ((onTaskEnded: () -> Unit) -> CacheWarmer)? = null,
) {
    // 会话状态
    val state = MutableStateFlow(initial)
    private val initializationMutex = Mutex()
    private val persistenceMutex = Mutex()
    @Volatile
    private var initialized = false
    val messageQueue = MessageQueue()

    /** Load once. A delayed DB read may not replace state edited while it was suspended. */
    suspend fun initialize(load: suspend () -> Conversation) {
        initializationMutex.withLock {
            if (initialized) return
            val before = state.value
            val loaded = load()
            require(loaded.id == id)
            synchronized(this) {
                if (!initialized && state.value === before && getJob() == null) {
                    state.value = loaded
                }
                initialized = true
            }
        }
    }

    /** Full message snapshots never undo metadata changed by a concurrent UI/REST operation. */
    @Synchronized
    internal fun replaceConversation(
        conversation: Conversation,
        onReplace: (Conversation, Conversation) -> Unit = { _, _ -> },
    ): Conversation {
        require(conversation.id == id)
        val current = state.value
        val updated = if (initialized) conversation.copy(
            assistantId = current.assistantId,
            folderId = current.folderId,
            isPinned = current.isPinned,
            title = current.title,
            customSystemPrompt = current.customSystemPrompt,
            workspaceCwd = current.workspaceCwd,
            modeInjectionIds = current.modeInjectionIds,
            lorebookIds = current.lorebookIds,
        ) else conversation
        onReplace(updated, current)
        state.value = updated
        initialized = true
        return updated
    }

    /** False until the DB load (or a first in-memory write) has populated [state]. */
    internal val isInitialized: Boolean get() = initialized

    @Synchronized
    internal fun updateState(update: (Conversation) -> Conversation): Conversation {
        val updated = update(state.value)
        require(updated.id == id)
        state.value = updated
        initialized = true
        return updated
    }

    /** Full saves and partial metadata writes share this lock; no stale save can land last. */
    internal suspend fun <T> withPersistence(block: suspend () -> T): T =
        persistenceMutex.withLock { block() }

    internal suspend fun updateMetadata(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) = withPersistence {
        withContext(NonCancellable) {
            persist(updateState(update))
        }
    }

    /** Await final persistence even after cancellation, before the generation slot is released. */
    internal suspend fun finishGeneration(save: suspend (Conversation) -> Unit): Conversation =
        withContext(NonCancellable) {
            withPersistence {
                val finished = updateState { current ->
                    current.copy(
                        messageNodes = current.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                        updateAt = Instant.now(),
                    )
                }
                save(finished)
                finished
            }
        }

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true

    /**
     * 会话树（全分支节点）上是否还有待审批工具调用：!isExecuted 且
     * approvalState is Pending。审批等待期间通常没有 job 在跑，但决策
     * 一旦落地会继续本 turn——设备连接结构变更把这一刻视为 busy。
     */
    fun hasPendingToolApprovals(): Boolean = state.value.messageNodes.any { node ->
        node.messages.any { message ->
            message.parts.any { it is UIMessagePart.Tool && it.isPending }
        }
    }
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
                messageQueue.state.value.messages.isNotEmpty() ||
                cacheWarmer?.isActive == true || isCompressing

    /** Background compression; the last terminal state stays until the next start. */
    val compressState = MutableStateFlow<CompressState?>(null)

    /** Guarded by this session's monitor, like the generation job slot. */
    @Volatile
    internal var compressJob: Job? = null

    val isCompressing: Boolean get() = compressJob?.isActive == true

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    /** 本会话的缓存保活器；任务活动期间会话不被空闲回收（任务不持久化，回收即丢失）。 */
    val cacheWarmer: CacheWarmer? = cacheWarmerFactory?.invoke {
        // 会话已销毁（close）后不再排空闲检查，避免误回收同 id 的新会话
        if (cacheWarmer?.isClosed != true && refCount.get() <= 0 && _generationJob.value == null) {
            scheduleIdleCheck()
        }
    }

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    /**
     * 批量取消（用户停止/会话清理用 [cause] = null；政策取消——如会话助手重绑
     * 工作区——由调用方传 `WorkspacePolicyCancellationException`，使链上每个 job 的
     * 完成原因可区分于用户停止：onGenerationFinished 不 pause 队列、在途工具由
     * 守卫写 outcome_unknown）。waiters 先取消，保证前任完成不会启动下一个任务。
     */
    @Synchronized
    fun cancelJobs(cause: CancellationException? = null): List<Job> =
        activeJobs.toList().also { jobs ->
            // Cancel waiters first so a predecessor finishing cannot start the next approval.
            jobs.asReversed().forEach { it.cancel(cause) }
        }

    internal fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating && cacheWarmer?.isActive != true) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
        cacheWarmer?.close()
    }
}

/** Keep the original stream failure, and leave invalid protocol snapshots to authoritative rollback. */
internal suspend fun ConversationSession.finishGenerationAfterStream(
    cause: Throwable?,
    save: suspend (Conversation) -> Unit,
    onSaveFailure: (Exception) -> Unit,
): Conversation = try {
    finishGeneration { finished ->
        if (cause !is GenerationAttemptProtocolException) save(finished)
    }
} catch (saveError: Exception) {
    if (cause == null) throw saveError
    onSaveFailure(saveError)
    state.value
}

/**
 * 消费 [GenerationAttemptProtocolException] 的权威清理: 把失败 attempt 的尾消息
 * 在内存会话态中恢复为 attempt 开始前的 `baseMessage`。
 *
 * - 任务归属校验: descriptor 的 conversationId 必须是本会话, 否则拒绝清理
 *   (串会话防护)。不按当前 session job 门控 —— 清理挂在前任任务
 *   `NonCancellable` 收尾里, 期间后继任务可能已接管 slot; 若因"后继已安装"
 *   跳过清理, 会留下 Auto/Approved 未执行的半截工具 part 被下一个生成步
 *   继续执行。安全性改由排序不变量保证: 所有后继生成都在前任完整结束
 *   (含其 NonCancellable 清理) 后才读取/改写会话态 —— sendMessage/regenerate
 *   路径取 `previousJob` 后第一步 `join()`, 审批路径走
 *   `afterPreviousGeneration`; 因此这里的恢复不可能覆盖任何后继的写入。
 * - 目标消息从未发布或已恢复 -> no-op (幂等), 返回 false, 不为清理新建消息。
 * - 只原位替换同 id 消息, 不回写整棵会话树; 先前成功步/已执行工具保留。
 *
 * 返回 true 表示内存态已变更, 调用方负责持久化 (落库失败只记日志,
 * 不得吞掉/替换原始协议错误)。
 */
internal fun ConversationSession.rollbackProtocolFailedAttempt(
    failure: GenerationAttemptProtocolException,
): Boolean {
    if (failure.conversationId != null && failure.conversationId != id) return false
    synchronized(this) {
        val current = state.value
        val restored = current.restoreProtocolFailedAttempt(failure)
        if (restored === current) return false
        updateState { restored }
        return true
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
