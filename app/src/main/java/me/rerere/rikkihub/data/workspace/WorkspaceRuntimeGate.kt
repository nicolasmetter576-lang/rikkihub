package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * T0 公共运行准入：workspace 级 gate / 双 epoch / per-stable-id 吊销代数 /
 * 操作与 handle 登记 / cancel+join。
 *
 * 接线方依赖的不变量：
 * - 每个 workspaceId 一把 admissionMutex：登记（含 validate 最新 DB 读）与 publish 封禁
 *   严格互斥，先后以拿到锁为准 —— 先登记者成为 victim，后封禁者令准入等待。仅按
 *   workspaceId 隔离，与当前助手/会话无关。
 * - join / handle close / publish 回调全部在 admissionMutex 外执行（封禁由 sealedFlag +
 *   openSignal 表达，不靠持锁），victim 收尾触碰 gate 不会死锁。
 * - 操作体跑在 SupervisorJob(调用方) 子 Job 下：gate 只取消该子 Job，不波及同 run 其他
 *   工具调用与调用方 Job。调用方 catch 到 [WorkspacePolicyCancellationException] =
 *   「已开始、被策略取消」(outcome_unknown)；[WorkspaceUnavailableException] = 「未开始、
 *   零执行」。用户整体取消时原样抛调用方 CE。
 * - 已登记操作体内（同 ws）调用 publish/cancel 明确抛 [WorkspaceGateReentrancyException]
 *   （不排除自身：否则祖先 publish join 本会死锁）；操作体里再 withOperation 嵌套子操作
 *   （subagent child 工具）允许。validate/prepare/publish 回调（持锁上下文）内重入任何
 *   准入/发布同样拒绝。
 * - epoch 单调不回滚；victim 取消/join 失败不静默放行：不解封（quarantine，保持 sealed）、
 *   未成功 close/await 的 handle 留在册、不 publish，抛异常由调用方决定（closeWorkspace
 *   是逃生通道——closed 优先于 sealed 检查）。quarantine 状态下准入/withSnapshot 不再
 *   永久等待而是 fail-fast（CLEANUP_FAILED）；重试 publish 仍走正常 sealed 分支（publish
 *   是持锁方上下文，不是准入）可自我恢复。join 只证明 JVM Job/awaitClosed 完成，
 *   不宣称进程/PTY 已退出（另行证明）。
 *   op 自身收尾 join 超时同理不冒充完成：record 挂 cleanupPending 留在册并 quarantine，
 *   由后续 retry publish/closeWorkspace 对账清理；publish join 阶段结束后重扫在册项，
 *   封禁期新养入的孤儿（createHandle 收尾失败收养进册）同样必须清掉才能 committed=true。
 * - handle 收尾一律经 HandleRecord.cleanupMutex 串行化（publish victim 清理 / releaseHandle /
 *   createHandle 收尾共用一门）：close+await 全成功才标 closedOk 并允许出册；close 成功而
 *   await 失败的重试只重跑 await，不会二次 close；失败保留在册（fail-loud）。
 *   createHandle 登记临界区异常/拒绝产生的孤儿资源：收尾失败时收养进 handles 册并
 *   quarantine（不丢成 live orphan）；登记成功到返回值交付的取消窄窗由所属 op 收尾。
 * - per-stable-id 吊销代数 = 全局吊销基数（toolEpochBase，结构级 spec(null) 全 bump）+
 *   per-id 偏移：结构变更对从未见过的 stableId 同样生效吊销（含未来接线的新工具）。
 * - 手动用户终端登记 USER_TERMINAL + 空 stableIds：单工具关闭（非 null affectedStableIds）
 *   波及不到它；只有结构级 spec(null) 或显式 handleClasses 才命中。
 */
class WorkspaceRuntimeGate(
    private val joinTimeoutMillis: Long = DEFAULT_JOIN_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_JOIN_TIMEOUT_MS = 30_000L
        private val nextRecordId = AtomicLong(1L)
    }

    /**
     * 进程级激活会话标识（创建时随机）。epoch 在进程重启后归零，单靠 epoch 比对会让
     * 旧 pending 绑定与新进程里递增到相同值的 epoch 碰撞复活；绑定侧把本值记入 identity
     * provenance（activationSession，缺省 null 兼容旧字段），跨进程旧 pending 比对必失配
     * → 明确 unavailable 而非复活，已完成 history 不受影响。只读，不随 publish 变化。
     */
    val activationSession: String = UUID.randomUUID().toString()

    private val gates = ConcurrentHashMap<String, PerWorkspace>()
    private val beforeChangeListeners = CopyOnWriteArrayList<(WorkspaceChangeImminent) -> Unit>()

    private val _changes = MutableSharedFlow<WorkspaceConfigChangeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 全局变更事件（每次 publish 尝试结束发出，committed=false 表示未提交）。 */
    val changes: SharedFlow<WorkspaceConfigChangeEvent> = _changes

    /**
     * 变更前同步观察者（锁外、纯内存、禁止 suspend/block）：用于 ChatService 即时停 warmer，
     * 不等 Room Flow 观察延迟。返回注销函数；listener 异常被吞，不影响发布。
     */
    fun addOnBeforeChangeListener(listener: (WorkspaceChangeImminent) -> Unit): () -> Unit {
        beforeChangeListeners += listener
        return { beforeChangeListeners -= listener }
    }

    // ---------------------------------------------------------------- 读侧（全非 suspend）

    fun currentEpoch(workspaceId: String): Long = currentEpochs(workspaceId).policyEpoch

    fun currentViewEpoch(workspaceId: String): Long = currentEpochs(workspaceId).viewEpoch

    fun currentEpochs(workspaceId: String): WorkspaceEpochs =
        gates[workspaceId]?.snapshotRef?.get()?.epochs ?: WorkspaceEpochs()

    /**
     * per-stable-id 吊销代数（基数+偏移的有效值）。affected 非 null 时只对列出的 ID 自增偏移；
     * 结构级 spec(null) bump 全局基数，对从未登记过的 ID 同样吊销旧捕获。
     */
    fun currentToolEpoch(workspaceId: String, stableId: String): Long {
        val gate = gates[workspaceId] ?: return 0L
        return gate.toolEpochBase.get() + (gate.toolEpochsRef.get()[stableId] ?: 0L)
    }

    fun snapshot(workspaceId: String): WorkspaceGateSnapshot =
        gates[workspaceId]?.snapshotRef?.get()
            ?: WorkspaceGateSnapshot(
                workspaceId, WorkspaceEpochs(), sealed = false, closed = false,
                quarantined = false, 0, 0, emptyMap(),
            )

    fun epochsFlow(workspaceId: String): StateFlow<WorkspaceEpochs> = gate(workspaceId).epochsFlow

    fun eventsFlow(workspaceId: String): StateFlow<WorkspaceConfigChangeEvent?> = gate(workspaceId).eventsFlow

    // ---------------------------------------------------------------- 准入

    /**
     * 执行准入：登记后运行 [operation]（参数为准入瞬间的 epochs 快照）。
     * validate 在 admissionMutex 内调用（约定：单条最新 DB 读，不得再调 gate 变更/准入 API）。
     * 封禁中：waitIfSealed=true 挂起至解封后用新配置重新校验；false 抛 SEALED。
     * 操作体允许再嵌套 withOperation（如 subagent child），但不允许同 ws 的 publish/cancel。
     */
    suspend fun <T> withOperation(
        workspaceId: String,
        kind: WorkspaceOperationKind,
        stableIds: Set<String> = emptySet(),
        handleClass: WorkspaceHandleClass = WorkspaceHandleClass.MODEL_EXECUTION,
        expectedPolicyEpoch: Long? = null,
        expectedViewEpoch: Long? = null,
        expectedToolEpochs: Map<String, Long>? = null,
        waitIfSealed: Boolean = true,
        validate: (suspend () -> Boolean)? = null,
        operation: suspend (WorkspaceEpochs) -> T,
    ): T {
        rejectIfLockContext(workspaceId)
        val gate = gate(workspaceId)
        val record = OpRecord(
            id = nextRecordId.getAndIncrement(),
            kind = kind,
            stableIds = stableIds,
            handleClass = handleClass,
            job = SupervisorJob(coroutineContext.job),
            startedAtMillis = System.currentTimeMillis(),
        )
        // 本 op 期间经 registerHandle 入册的 handle：返回值未交付前归 gate 所有，
        // 登记成功→交付调用方的取消窄窗内由本 op 收尾，不把孤儿漏给调用方。
        // 嵌套 op 正常返回时把 minted 集上移给父 op（随返回值交付 ownership）。
        val mintedHandles: MutableSet<WorkspaceHandleToken> = ConcurrentHashMap.newKeySet()
        // 祖先链：嵌套 withOperation 累积（同 ws 嵌套 op 允许；禁止的是同 ws publish/close）
        val activeWorkspaces =
            (coroutineContext[WorkspaceOpElement]?.workspaceIds ?: emptySet()) + workspaceId
        var primaryFailure: Throwable? = null
        try {
            val admitted = admit(
                gate, workspaceId, record,
                expectedPolicyEpoch, expectedViewEpoch, expectedToolEpochs,
                waitIfSealed, validate,
            )
            val result = withContext(
                record.job + WorkspaceOpElement(workspaceId, record.id, activeWorkspaces, mintedHandles),
            ) {
                operation(admitted)
            }
            // 交付给父 op/调用方前，把 minted ownership 上移；随后的取消由本 op
            // finally 兜底 retire（集合仍持有 token，retire 幂等不会误关已交付资源）。
            coroutineContext[WorkspaceOpElement]?.mintedHandles?.addAll(mintedHandles)
            // 操作正常结束但调用方已被取消（用户整体停止）：仍报调用方原 CE
            coroutineContext.ensureActive()
            return result
        } catch (e: CancellationException) {
            // gate 取消优先识别：publish 先置 policyCancellation 再 cancel，无竞态；
            // 其余（调用方取消/操作内部取消）原样重抛，尊重用户整体取消语义。
            val policy = record.policyCancellation.get()
            primaryFailure = policy ?: e
            throw (policy ?: e)
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            // admit 拒绝也走这里：子 Job 必须 cancel，否则调用方 Job 永不完成
            record.job.cancel()
            withContext(NonCancellable) {
                // 等 record.job 下的全部子节点结束再出册：body 内嵌套 op / launch 的子协程
                // 未走完前操作仍计为在册 victim。join 跑在调用方协程（record.job 不是其
                // 父节点），不会自 join；有界等待防止无视取消的子协程永久拖住收尾。
                val joined = withTimeoutOrNull(joinTimeoutMillis) { record.job.join() } != null
                val lateFailure = mutableListOf<Throwable>()
                if (!joined) {
                    // join 超时 ≠ 完成：留在册（挂 cleanupPending）并 quarantine 该 ws，
                    // 阻塞后续准入与无关 publish；超时本身不静默冒充收尾完成。
                    val timeout = WorkspaceGateJoinTimeoutException(workspaceId, pendingOperations = 1)
                    gate.admissionMutex.withLock {
                        record.cleanupPending.set(true)
                        gate.sealedFlag.set(true)
                        gate.quarantinedFlag.set(true)
                        gate.openSignal.value = gate.openSignal.value + 1
                        gate.refreshSnapshot()
                    }
                    lateFailure += timeout
                } else {
                    gate.admissionMutex.withLock {
                        gate.operations.remove(record.id)
                        gate.refreshSnapshot()
                    }
                }
                // 登记成功但返回值未交付（登记→return 取消窄窗 / op 异常退出）的 handle：
                // ownership 在本 op，按 gate-owner 收尾；仅失败路径执行——op 正常交付的
                // handle 归调用方。失败转 quarantine 并随主异常 suppressed。
                if (primaryFailure != null) {
                    mintedHandles.forEach { token ->
                        runCatching {
                            gates[token.workspaceId]?.let { retireRegisteredHandle(it, token.handleId) }
                        }.getOrElse { it }?.let { lateFailure += it }
                    }
                }
                lateFailure.forEach { f ->
                    val primary = primaryFailure
                    if (primary != null) primary.addSuppressed(f) else primaryFailure = f
                }
            }
            primaryFailure?.let { throw it }
        }
    }

    /**
     * 一致性读：解封后在 admissionMutex 内执行 [read]（约定为单次最新 DB 读），参数为
     * 同一瞬间的 epochs —— 装配/构建快照与 epoch 捕获天然一致。
     */
    suspend fun <T> withSnapshot(
        workspaceId: String,
        read: suspend (WorkspaceEpochs) -> T,
    ): T {
        rejectIfLockContext(workspaceId)
        val gate = gate(workspaceId)
        while (true) {
            var sealToken = -1
            val box = gate.admissionMutex.withLock {
                when {
                    gate.closedFlag.get() -> throw WorkspaceUnavailableException(
                        workspaceId, WorkspaceUnavailableReason.WORKSPACE_CLOSED, "workspace is closed"
                    )
                    // quarantine（victim 清理失败/超时）不再静默等待：fail-fast 由调用方决定
                    // 重试/中止，唯一恢复通道是 retry publish（走正常 sealed 分支）或 closeWorkspace
                    gate.quarantinedFlag.get() -> throw WorkspaceUnavailableException(
                        workspaceId, WorkspaceUnavailableReason.CLEANUP_FAILED,
                        "workspace quarantined by failed victim cleanup"
                    )
                    gate.sealedFlag.get() -> {
                        sealToken = gate.openSignal.value
                        null
                    }
                    else -> Box(
                        withContext(workspaceAdmissionContext(workspaceId)) { read(gate.epochsFlow.value) }
                    )
                }
            }
            if (box != null) {
                return box.value
            }
            gate.openSignal.first { it != sealToken }
        }
    }

    /** 等至未封禁（workspace 已关闭立即抛）。测试/barrier 与惰性复核用。 */
    suspend fun awaitAdmission(workspaceId: String) {
        rejectIfLockContext(workspaceId)
        val gate = gate(workspaceId)
        while (true) {
            var sealToken = -1
            val waiting = gate.admissionMutex.withLock {
                if (gate.closedFlag.get()) throw WorkspaceUnavailableException(
                    workspaceId, WorkspaceUnavailableReason.WORKSPACE_CLOSED, "workspace is closed"
                )
                if (gate.quarantinedFlag.get()) throw WorkspaceUnavailableException(
                    workspaceId, WorkspaceUnavailableReason.CLEANUP_FAILED,
                    "workspace quarantined by failed victim cleanup"
                )
                if (gate.sealedFlag.get()) {
                    sealToken = gate.openSignal.value
                    true
                } else false
            }
            if (!waiting) return
            gate.openSignal.first { it != sealToken }
        }
    }

    // ---------------------------------------------------------------- handle 登记/创建

    /**
     * 登记存活 handle。在 admissionMutex 内复查 closed/sealed/epochs/validate：任一失配返回
     * null（调用方自行关闭 handle）。可在操作体内调用（createHandle 即如此）；不得在
     * validate/publish 回调内调用。
     */
    suspend fun <H> registerHandle(
        workspaceId: String,
        handle: H,
        handleClass: WorkspaceHandleClass,
        stableIds: Set<String> = emptySet(),
        close: suspend (H) -> Unit = {},
        awaitClosed: suspend (H) -> Unit = {},
        expectedPolicyEpoch: Long? = null,
        expectedViewEpoch: Long? = null,
        expectedToolEpochs: Map<String, Long>? = null,
        validate: (suspend () -> Boolean)? = null,
    ): WorkspaceHandleToken? {
        rejectIfLockContext(workspaceId)
        val gate = gate(workspaceId)
        val id = nextRecordId.getAndIncrement()
        return gate.admissionMutex.withLock {
            if (!admissibleLocked(gate, expectedPolicyEpoch, expectedViewEpoch, expectedToolEpochs)) {
                return@withLock null
            }
            val ok = validate == null || withContext(workspaceAdmissionContext(workspaceId)) { validate() }
            if (!ok) return@withLock null
            val epochsNow = gate.epochsFlow.value
            // 若当前在 op 体内（createHandle）：登记成功即计入 op 的 minted 集——
            // 从登记到返回值交付的取消窄窗由所属 op 按 gate-owner 收尾。
            coroutineContext[WorkspaceOpElement]?.mintedHandles?.add(
                WorkspaceHandleToken(workspaceId, id, epochsNow)
            )
            gate.handles[id] = HandleRecord(
                id = id,
                workspaceId = workspaceId,
                handleClass = handleClass,
                stableIds = stableIds,
                epochsAtAdmission = epochsNow,
                closeNow = { close(handle) },
                awaitClosed = { awaitClosed(handle) },
            )
            gate.refreshSnapshot()
            WorkspaceHandleToken(workspaceId, id, epochsNow)
        }
    }

    /** 非 suspend 复核：记录仍在册且未 sealed/closed。写关键路径前复查用。 */
    fun isHandleFresh(token: WorkspaceHandleToken): Boolean {
        val gate = gates[token.workspaceId] ?: return false
        return !gate.closedFlag.get() && !gate.sealedFlag.get() && gate.handles.containsKey(token.handleId)
    }

    /** 仅注销记录，不触碰资源（资源已由调用方关闭时）。 */
    suspend fun unregisterHandle(token: WorkspaceHandleToken): Boolean {
        val gate = gates[token.workspaceId] ?: return false
        return gate.admissionMutex.withLock {
            val removed = gate.handles.remove(token.handleId) != null
            if (removed) gate.refreshSnapshot()
            removed
        }
    }

    /**
     * 正常关闭路径：锁外、经 per-record cleanupMutex 串行化的 close + awaitClosed，**都成功**
     * 才出册。与并发 publish victim 清理共用同一门：同一资源只会 close 一次；close 成功而
     * await 失败的重试只重跑 await。失败则留在册并把 workspace quarantine（sealed +
     * quarantined，唤醒的等待者 fail-fast），抛异常；恢复靠 retry publish / closeWorkspace。
     */
    suspend fun releaseHandle(token: WorkspaceHandleToken) {
        val gate = gates[token.workspaceId] ?: return
        val record = gate.handles[token.handleId] ?: return
        var failure: Throwable? = null
        try {
            // NonCancellable：调用方取消时仍完成收尾（否则留在 half-closed 状态）
            withContext(NonCancellable) { record.cleanupHandle() }
        } catch (e: Throwable) {
            failure = e
        }
        if (failure != null) {
            gate.admissionMutex.withLock {
                record.pendingCleanup.set(true)
                gate.sealedFlag.set(true)
                gate.quarantinedFlag.set(true)
                // 唤醒 openSignal 等待者让它们走 quarantine fail-fast，而非继续挂起
                gate.openSignal.value = gate.openSignal.value + 1
                gate.refreshSnapshot()
            }
            throw WorkspaceGateCleanupException(workspaceId = token.workspaceId, pendingHandles = 1, causes = listOf(failure))
        }
        gate.admissionMutex.withLock {
            gate.handles.remove(token.handleId)
            gate.refreshSnapshot()
        }
    }

    /**
     * 一站式创建：HANDLE_CREATION 准入 → spawn（锁外）→ admission 临界区内复核 epoch/validate
     * → 登记。spawn 期间被变更波及：NonCancellable close+awaitClosed 后抛
     * [WorkspaceStaleHandleException]，不发布过期 session。
     */
    suspend fun <H> createHandle(
        workspaceId: String,
        handleClass: WorkspaceHandleClass,
        stableIds: Set<String> = emptySet(),
        expectedPolicyEpoch: Long? = null,
        expectedViewEpoch: Long? = null,
        expectedToolEpochs: Map<String, Long>? = null,
        waitIfSealed: Boolean = true,
        validate: (suspend () -> Boolean)? = null,
        close: suspend (H) -> Unit = {},
        awaitClosed: suspend (H) -> Unit = {},
        spawn: suspend () -> H,
    ): WorkspaceRuntimeHandle<H> = withOperation(
        workspaceId = workspaceId,
        kind = WorkspaceOperationKind.HANDLE_CREATION,
        stableIds = stableIds,
        handleClass = handleClass,
        expectedPolicyEpoch = expectedPolicyEpoch,
        expectedViewEpoch = expectedViewEpoch,
        expectedToolEpochs = expectedToolEpochs,
        waitIfSealed = waitIfSealed,
        validate = validate,
    ) { admittedEpochs ->
        // 复核口径：view epoch（结构）+ 本 handle stableIds 的吊销代数，不凭全局 policyEpoch
        // —— 无关工具开关 bump policyEpoch 不会误杀在途创建。op 存活到 body 才捕获 stable
        // epochs：本 ids 被 bump 等价于本 op 已 victim 化，不会走到这里。
        val perWs = gate(workspaceId)
        val capturedToolEpochs = stableIds.associateWith { currentToolEpoch(workspaceId, it) }
        val handle = spawn()
        // spawn 已成功但登记前任何异常（含 CE）：资源归属还在本函数，必须 NonCancellable 清理。
        // 未入册的孤儿资源不经 HandleRecord 门：close/await 都尽力跑、错误随主异常 suppressed
        // 上报（不静默吞——否则外层会以为已收回）。
        val token = try {
            registerHandle(
                workspaceId = workspaceId,
                handle = handle,
                handleClass = handleClass,
                stableIds = stableIds,
                close = close,
                awaitClosed = awaitClosed,
                expectedViewEpoch = admittedEpochs.viewEpoch,
                expectedToolEpochs = capturedToolEpochs,
                validate = validate,
            )
        } catch (e: Throwable) {
            val cleanupError = closeOrphanedHandle(perWs, handle, handleClass, stableIds, close, awaitClosed)
            if (cleanupError != null) e.addSuppressed(cleanupError)
            throw e
        }
        if (token == null) {
            val stale = WorkspaceStaleHandleException(workspaceId, handleId = -1L)
            closeOrphanedHandle(perWs, handle, handleClass, stableIds, close, awaitClosed)?.let { stale.addSuppressed(it) }
            throw stale
        }
        // 登记成功到返回前若调用方被取消：handle 已入册但不会交付，按 owner=gate 收尾。
        // 出册+清理走与 releaseHandle/victim 相同的串行门（已发生的 victim close 不重复）。
        if (!coroutineContext.isActive) {
            var teardownFailure: Throwable? = null
            withContext(NonCancellable) {
                teardownFailure = runCatching { retireRegisteredHandle(perWs, token.handleId) }
                    .exceptionOrNull()
            }
            try {
                coroutineContext.ensureActive()
            } catch (ce: CancellationException) {
                // 收尾失败 = 资源留在册 + quarantine；依然抛调用方原 CE，但带 suppressed
                // 让上层知道有 handle 未收回，不假装已回收干净。
                teardownFailure?.let { ce.addSuppressed(it) }
                throw ce
            }
        }
        WorkspaceRuntimeHandle(token = token, value = handle)
    }

    /**
     * 未入册孤儿资源的收尾：先走 HandleRecord 串行门（close 成功而 await 失败的欠债
     * 状态照样保留，重试只重跑 await，不会二次 close）。收尾失败时不再丢成册外孤儿：
     * 把资源收养进 handles 注册表并置 quarantine（pendingCleanup），留给后续 retry
     * publish/closeWorkspace 按册重试；返回收尾失败（无则 null）。
     */
    private suspend fun <H> closeOrphanedHandle(
        gate: PerWorkspace,
        handle: H,
        handleClass: WorkspaceHandleClass,
        stableIds: Set<String>,
        close: suspend (H) -> Unit,
        awaitClosed: suspend (H) -> Unit,
    ): Throwable? {
        val record = HandleRecord(
            id = nextRecordId.getAndIncrement(),
            workspaceId = gate.workspaceId,
            handleClass = handleClass,
            stableIds = stableIds,
            epochsAtAdmission = gate.epochsFlow.value,
            closeNow = { close(handle) },
            awaitClosed = { awaitClosed(handle) },
        )
        val failure = runCatching {
            withContext(NonCancellable) { record.cleanupHandle() }
        }.exceptionOrNull() ?: return null
        // 失败欠债：入册 + quarantine，避免 live orphan（在 publish 对账可见）。
        record.pendingCleanup.set(true)
        gate.admissionMutex.withLock {
            gate.handles[record.id] = record
            gate.sealedFlag.set(true)
            gate.quarantinedFlag.set(true)
            gate.openSignal.value = gate.openSignal.value + 1
            gate.refreshSnapshot()
        }
        return failure
    }

    /**
     * 已入册 handle 的 gate 侧收尾：串行 cleanup 成功才出册；失败保持登记并 quarantine，
     * 返回收尾异常（调用方决定如何上报）。
     */
    private suspend fun retireRegisteredHandle(gate: PerWorkspace, handleId: Long): Throwable? {
        val record = gate.handles[handleId] ?: return null
        val failure = runCatching { record.cleanupHandle() }.exceptionOrNull()
        if (failure == null) {
            gate.admissionMutex.withLock {
                gate.handles.remove(handleId)
                gate.refreshSnapshot()
            }
        } else {
            gate.admissionMutex.withLock {
                record.pendingCleanup.set(true)
                gate.sealedFlag.set(true)
                gate.quarantinedFlag.set(true)
                gate.openSignal.value = gate.openSignal.value + 1
                gate.refreshSnapshot()
            }
        }
        return failure
    }

    // ---------------------------------------------------------------- 发布（唯一变更通道）

    /** 固定 spec 的便捷入口，委托 [publishPrepared]。 */
    suspend fun <T> publishAndCancel(
        workspaceId: String,
        spec: WorkspaceChangeSpec,
        publish: suspend (WorkspaceEpochs) -> T,
    ): T = publishPrepared(workspaceId, prepare = { spec }, publish = publish)

    /**
     * 两阶段发布 —— Repository 所有配置写统一走这里，prepare 因此总读最新：
     * - [prepare] 在 changeMutex+admissionMutex 内执行（读最新 DB / diff 出受影响 stableIds）。
     *   返回 null = 仅普通 metadata：同一 admission 临界区内直接跑 [publish]（不 bump、不
     *   cancel、不发 event）；准入只在写库瞬间短暂排队，顺序仍确定。
     *   返回非 null = 结构/策略变更：同临界区接着置 sealed + bump epoch(s) + 自增 affected
     *   toolEpochs + 快照 victims（含在册 handle），然后锁外 onBeforeChange → 锁外
     *   cancel+有界 join/close+awaitClosed → publish → finally 发 event 并解封。
     * - victim 清理失败/超时不解封（quarantine）：不 publish、抛异常、成功 close 的 handle
     *   才出册。调用方取消：清理仍在 NonCancellable 内完成再抛原 CE。
     * - 同 ws 已登记操作体内 / validate / publish 回调内调用本方法 →
     *   [WorkspaceGateReentrancyException]（避免自身/祖先 join 死锁与持锁重入）。
     */
    suspend fun <T> publishPrepared(
        workspaceId: String,
        prepare: suspend () -> WorkspaceChangeSpec?,
        publish: suspend (WorkspaceEpochs) -> T,
    ): T {
        rejectIfLockContext(workspaceId)
        // 已登记操作体（自身或祖先链）内发布同 ws：明确拒绝（否则祖先 publish join 本会死锁）
        if (coroutineContext[WorkspaceOpElement]?.workspaceIds?.contains(workspaceId) == true) {
            throw WorkspaceGateReentrancyException(workspaceId)
        }
        val gate = gate(workspaceId)
        return gate.changeMutex.withLock {
            var sealToken = 0
            var victims: List<OpRecord> = emptyList()
            val effectiveSpec = gate.admissionMutex.withLock {
                if (gate.closedFlag.get()) throw WorkspaceUnavailableException(
                    workspaceId, WorkspaceUnavailableReason.WORKSPACE_CLOSED, "workspace is closed"
                )
                val spec = withContext(workspacePublishContext(workspaceId)) { prepare() }
                if (spec == null) return@withLock null
                sealToken = gate.openSignal.value
                gate.sealedFlag.set(true)
                var next = gate.epochsFlow.value
                if (spec.bumpPolicyEpoch) next = next.copy(policyEpoch = next.policyEpoch + 1)
                if (spec.bumpViewEpoch) next = next.copy(viewEpoch = next.viewEpoch + 1)
                gate.epochsFlow.value = next
                bumpToolEpochsLocked(gate, spec.affectedStableIds)
                victims = gate.operations.values.filter {
                    matchesVictim(it.stableIds, it.handleClass, spec)
                }
                gate.refreshSnapshot()
                spec
            }
            if (effectiveSpec == null) {
                // metadata-only：持 admission 锁直接发布，不外泄到锁外路径
                return@withLock gate.admissionMutex.withLock {
                    withContext(workspacePublishContext(workspaceId)) { publish(gate.epochsFlow.value) }
                }
            }
            fireBeforeChange(WorkspaceChangeImminent(workspaceId, effectiveSpec.affectedStableIds, gate.epochsFlow.value))
            var committed = false
            var quarantine = false
            try {
                victims.forEach { rec ->
                    val cause = WorkspacePolicyCancellationException(workspaceId)
                    rec.policyCancellation.set(cause)
                    rec.job.cancel(cause)
                }
                val cleanupFailures = mutableListOf<Throwable>()
                val attemptedHandles = mutableSetOf<Long>()
                val done = withContext(NonCancellable) {
                    withTimeoutOrNull(joinTimeoutMillis) {
                        victims.forEach { it.job.join() }
                        // join 之后对账重扫：创建 op 收尾期间新养入册的孤儿（封禁期诞生）
                        // 不能被当作资源已退出——逐轮清理到无新增受害者为止；每轮重扫前
                        // 先取锁拿在册新成员。每条记录只尝试一次，失败留给 retry 发布。
                        while (true) {
                            val fresh = gate.admissionMutex.withLock {
                                // victim（按 spec 波及）或欠债收养记录（pendingCleanup，
                                // 与本 spec 无关也要重试——retry 发布是它们的恢复通道）
                                gate.handles.values.filter {
                                    !it.closedOk && it.id !in attemptedHandles &&
                                        (it.pendingCleanup.get() ||
                                            matchesVictim(it.stableIds, it.handleClass, effectiveSpec))
                                }
                            }
                            if (fresh.isEmpty()) break
                            fresh.forEach { h ->
                                attemptedHandles += h.id
                                try {
                                    // per-record 串行门：与并发 releaseHandle/createHandle 收尾
                                    // 互斥，close 至多一次；失败保持 closedOk=false 留在册
                                    h.cleanupHandle()
                                } catch (e: Throwable) {
                                    cleanupFailures += e
                                    h.pendingCleanup.set(true)
                                }
                            }
                        }
                    } != null
                }
                // 出册 + 清账在 admissionMutex 内完成：只有 close+await 成功的 handle 出册；
                // 失败/未尝试的留在册（fail-loud）。已完成收尾的 op 记录一并清扫（op 自身
                // finally 可能因 join 超时弃册，job 完成后记录是尸体，这里代为清理）。
                var pendingHandleCount = 0
                var leftoverPending = false
                gate.admissionMutex.withLock {
                    if (done) {
                        gate.handles.values.filter { it.closedOk }.forEach { gate.handles.remove(it.id) }
                        gate.operations.values.filter { it.job.isCompleted }
                            .forEach { gate.operations.remove(it.id) }
                    }
                    pendingHandleCount = gate.handles.values.count { !it.closedOk }
                    leftoverPending = gate.handles.values.any { it.pendingCleanup.get() && !it.closedOk } ||
                        gate.operations.values.any { it.cleanupPending.get() && !it.job.isCompleted }
                    gate.refreshSnapshot()
                }
                if (!done) {
                    quarantine = true
                    throw WorkspaceGateJoinTimeoutException(
                        workspaceId = workspaceId,
                        pendingOperations = victims.count { it.job.isActive } + pendingHandleCount,
                    )
                }
                if (cleanupFailures.isNotEmpty() || leftoverPending) {
                    // 对账后仍有欠债记录（本发布外的 quarantine 来源）：必须失败而
                    // 不能发 committed=true 冒充全部收回。
                    quarantine = true
                    throw WorkspaceGateCleanupException(
                        workspaceId = workspaceId,
                        pendingHandles = pendingHandleCount,
                        causes = cleanupFailures.toList(),
                    )
                }
                coroutineContext.ensureActive()
                val result = withContext(workspacePublishContext(workspaceId)) {
                    publish(gate.epochsFlow.value)
                }
                committed = true
                result
            } catch (cleanup: WorkspaceGateCleanupException) {
                // Legacy App/Web closers also own real processes, even when their handles
                // predate gate registration. A failed close/exit wait is cleanup debt.
                quarantine = true
                throw cleanup
            } finally {
                emitEvent(
                    gate,
                    WorkspaceConfigChangeEvent(workspaceId, effectiveSpec.affectedStableIds, gate.epochsFlow.value, committed),
                )
                // 两种情况都 bump openSignal：正常解封唤醒等待者继续准入；quarantine 唤醒
                // 等待者走 CLEANUP_FAILED fail-fast，而不是无限挂起。解封/quarantine 决策
                // 须在 admissionMutex 内重查欠债——committed=true 之后仍可能有并发收尾把
                // 孤儿收养进册，不能在尚有 pending 时解封冒充全部收回。
                withContext(NonCancellable) {
                    gate.admissionMutex.withLock {
                        val stillPending =
                            gate.handles.values.any { it.pendingCleanup.get() && !it.closedOk } ||
                                gate.operations.values.any { it.cleanupPending.get() && !it.job.isCompleted }
                        val effectiveQuarantine = quarantine || stillPending
                        gate.quarantinedFlag.set(effectiveQuarantine)
                        if (!effectiveQuarantine) {
                            gate.sealedFlag.set(false)
                        }
                        gate.openSignal.value = sealToken + 1
                        gate.refreshSnapshot()
                    }
                }
            }
        }
    }

    /**
     * Atomic runtime preparation for a publication spanning several workspaces.
     *
     * IDs are deduplicated and sorted: acquire ALL change mutexes, then ALL admission
     * mutexes, read/diff once in [prepare], and validate its exact key set before changing
     * any epoch or cancelling anything. [prepare] must only read; the caller owns the ONE
     * outer Room transaction in [publish]. No transaction may span gate acquisition or
     * victim cleanup. All gates are sealed before releasing the admission mutexes.
     *
     * Unlike the single-workspace metadata fast path, a null bulk spec is promoted to
     * toolPolicy(emptySet()): it seals and bumps policyEpoch, but revokes no stable IDs or
     * user terminals. Thus metadata participants never hold an admission lock while a
     * different workspace is joining, and publication cannot admit a mixed projection.
     * Empty IDs still call prepare (requiring an empty map) and publish(emptyMap()).
     *
     * Cancel ALL victims before joining ANY. Cleanup has one global timeout, retains
     * orphan/debt records and the per-handle serial cleanup contract, and completes in
     * NonCancellable even when the publisher is cancelled. Failed participants remain
     * quarantined; successfully cleaned participants unseal even if another fails.
     * Epochs never roll back. Each sealed participant emits its own committed event.
     */
    suspend fun <T> publishPreparedMany(
        workspaceIds: Collection<String>,
        prepare: suspend () -> Map<String, WorkspaceChangeSpec?>,
        publish: suspend (Map<String, WorkspaceEpochs>) -> T,
    ): T {
        val ids = workspaceIds.distinct().sorted()
        ids.forEach { workspaceId ->
            rejectIfLockContext(workspaceId)
            if (coroutineContext[WorkspaceOpElement]?.workspaceIds?.contains(workspaceId) == true) {
                throw WorkspaceGateReentrancyException(workspaceId)
            }
        }
        val participants = ids.map { BulkParticipant(gate(it)) }
        return withLocks(participants.map { it.gate.changeMutex }) {
            withContext(workspacePublishContext(ids.toSet())) {
                withLocks(participants.map { it.gate.admissionMutex }) {
                    participants.forEach { p ->
                        if (p.gate.closedFlag.get()) throw WorkspaceUnavailableException(
                            p.gate.workspaceId, WorkspaceUnavailableReason.WORKSPACE_CLOSED, "workspace is closed"
                        )
                    }
                    val specs = prepare().toMap()
                    require(specs.keys == ids.toSet()) {
                        "Bulk preparation must return exactly the participating workspace IDs"
                    }
                    participants.forEach { p ->
                        p.spec = specs.getValue(p.gate.workspaceId) ?: WorkspaceChangeSpec.toolPolicy(emptySet())
                    }
                    // No externally visible change before ALL preparation/validation succeeds.
                    coroutineContext.ensureActive()
                    participants.forEach { p ->
                        val gate = p.gate
                        gate.sealedFlag.set(true)
                        var next = gate.epochsFlow.value
                        if (p.spec.bumpPolicyEpoch) next = next.copy(policyEpoch = next.policyEpoch + 1)
                        if (p.spec.bumpViewEpoch) next = next.copy(viewEpoch = next.viewEpoch + 1)
                        gate.epochsFlow.value = next
                        bumpToolEpochsLocked(gate, p.spec.affectedStableIds)
                        p.victims = gate.operations.values.filter {
                            it.cleanupPending.get() || matchesVictim(it.stableIds, it.handleClass, p.spec)
                        }
                        gate.refreshSnapshot()
                    }
                }
                var committed = false
                try {
                    participants.forEach { p ->
                        fireBeforeChange(WorkspaceChangeImminent(
                            p.gate.workspaceId, p.spec.affectedStableIds, p.gate.epochsFlow.value,
                        ))
                    }
                    // Do not put a join/suspension between these cancellations: cross-workspace
                    // parent/child operations can depend on one another's cancellation.
                    participants.forEach { p ->
                        p.victims.forEach { rec ->
                            val cause = WorkspacePolicyCancellationException(p.gate.workspaceId)
                            rec.policyCancellation.set(cause)
                            rec.job.cancel(cause)
                        }
                    }
                    val failures = withContext(NonCancellable) { cleanupMany(participants) }
                    if (failures.isNotEmpty()) {
                        val first = failures.first()
                        failures.drop(1).forEach { first.addSuppressed(it) }
                        throw first
                    }
                    coroutineContext.ensureActive()
                    val result = withContext(workspacePublishContext(ids.toSet())) {
                        publish(participants.associate { it.gate.workspaceId to it.gate.epochsFlow.value })
                    }
                    committed = true
                    result
                } catch (cleanup: WorkspaceGateCleanupException) {
                    // Legacy closers in the caller's publication callback also own resources.
                    // If attribution is unavailable, fail closed for all participants.
                    val relevant = participants.filter { it.gate.workspaceId == cleanup.workspaceId }
                        .ifEmpty { participants }
                    relevant.forEach { it.quarantine = true }
                    throw cleanup
                } finally {
                    withContext(NonCancellable) {
                        participants.forEach { p ->
                            val gate = p.gate
                            emitEvent(gate, WorkspaceConfigChangeEvent(
                                gate.workspaceId, p.spec.affectedStableIds, gate.epochsFlow.value, committed,
                            ))
                            gate.admissionMutex.withLock {
                                val stillPending =
                                    gate.handles.values.any { it.pendingCleanup.get() && !it.closedOk } ||
                                        gate.operations.values.any { it.cleanupPending.get() && !it.job.isCompleted }
                                val quarantine = p.quarantine || stillPending
                                gate.quarantinedFlag.set(quarantine)
                                gate.sealedFlag.set(quarantine)
                                gate.openSignal.value = gate.openSignal.value + 1
                                gate.refreshSnapshot()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Called only in NonCancellable; a single deadline covers every join and handle cleanup. */
    private suspend fun cleanupMany(participants: List<BulkParticipant>): List<Throwable> {
        val done = withTimeoutOrNull(joinTimeoutMillis) {
            participants.forEach { p -> p.victims.forEach { it.job.join() } }
            // Re-scan ALL gates every round: orphan adoption can occur while another gate's
            // resources are closing. Existing debt is retried even for an unrelated/empty spec.
            while (true) {
                var attemptedAny = false
                participants.forEach { p ->
                    val fresh = p.gate.admissionMutex.withLock {
                        p.gate.handles.values.filter {
                            !it.closedOk && it.id !in p.attemptedHandles &&
                                (it.pendingCleanup.get() || matchesVictim(it.stableIds, it.handleClass, p.spec))
                        }
                    }
                    fresh.forEach { h ->
                        attemptedAny = true
                        p.attemptedHandles += h.id
                        try {
                            h.cleanupHandle()
                        } catch (e: Throwable) {
                            p.cleanupFailures += e
                            h.pendingCleanup.set(true)
                        }
                    }
                }
                if (!attemptedAny) break
            }
        } != null
        val failures = mutableListOf<Throwable>()
        participants.forEach { p ->
            val gate = p.gate
            gate.admissionMutex.withLock {
                // Partial success is real success even when the global deadline expired:
                // remove only proven-complete resources, never a live/debt record.
                gate.handles.values.filter { it.closedOk }.forEach { gate.handles.remove(it.id) }
                gate.operations.values.filter { it.job.isCompleted }.forEach { gate.operations.remove(it.id) }
                if (!done) {
                    // A global timeout may leave later workspaces entirely unattempted.
                    // Preserve their selected records as debt too, so a retry with a null
                    // or unrelated spec cannot erase quarantine while those resources live.
                    gate.handles.values.filter {
                        !it.closedOk && matchesVictim(it.stableIds, it.handleClass, p.spec)
                    }.forEach { it.pendingCleanup.set(true) }
                    p.victims.filter { !it.job.isCompleted }.forEach { it.cleanupPending.set(true) }
                }
                val pendingHandles = gate.handles.values.count {
                    !it.closedOk && (it.pendingCleanup.get() || matchesVictim(it.stableIds, it.handleClass, p.spec))
                }
                val pendingOperations = p.victims.count { !it.job.isCompleted }
                val leftoverPending = gate.operations.values.any { it.cleanupPending.get() && !it.job.isCompleted }
                val failure = when {
                    !done && (pendingHandles > 0 || pendingOperations > 0 || leftoverPending) ->
                        WorkspaceGateJoinTimeoutException(gate.workspaceId, pendingOperations + pendingHandles)
                    p.cleanupFailures.isNotEmpty() || pendingHandles > 0 || leftoverPending ->
                        WorkspaceGateCleanupException(gate.workspaceId, pendingHandles, p.cleanupFailures.toList())
                    else -> null
                }
                if (failure != null) {
                    p.quarantine = true
                    failures += failure
                }
                gate.refreshSnapshot()
            }
        }
        return failures
    }

    private class BulkParticipant(val gate: PerWorkspace) {
        lateinit var spec: WorkspaceChangeSpec
        var victims: List<OpRecord> = emptyList()
        val attemptedHandles = mutableSetOf<Long>()
        val cleanupFailures = mutableListOf<Throwable>()
        var quarantine = false
    }

    /** Iterative acquisition avoids a coroutine/stack frame per workspace; cancellation releases all acquired locks. */
    private suspend fun <T> withLocks(mutexes: List<Mutex>, block: suspend () -> T): T {
        var acquired = 0
        try {
            for (mutex in mutexes) {
                mutex.lock()
                acquired++
            }
            return block()
        } finally {
            for (index in acquired - 1 downTo 0) mutexes[index].unlock()
        }
    }

    /** 只取消、无持久变更：仍 bump policy epoch（及 affected toolEpochs）以失效旧捕获。 */
    suspend fun cancelAffected(
        workspaceId: String,
        affectedStableIds: Set<String>?,
        handleClasses: Set<WorkspaceHandleClass>? = null,
    ) {
        publishPrepared(
            workspaceId = workspaceId,
            prepare = {
                WorkspaceChangeSpec(
                    affectedStableIds = affectedStableIds,
                    handleClasses = handleClasses,
                    bumpPolicyEpoch = true,
                    bumpViewEpoch = false,
                )
            },
            publish = { },
        )
    }

    /**
     * 工作区删除：永久 closed（之后所有 admission WORKSPACE_CLOSED，优先于 sealed），取消并
     * join 全部在册操作与 handle。**清理超时/失败原样抛出** —— 调用方（Repo 删除）不得据此
     * 继续删数据；重复调用会重试清理仍残留的在册项（closed 后不跳过清理、不无脑成功）。
     * 从同 ws 已登记操作体（含祖先链）内调用 = 自己 join 自己的死锁，直接拒绝。
     */
    suspend fun closeWorkspace(workspaceId: String) {
        rejectIfLockContext(workspaceId)
        if (coroutineContext[WorkspaceOpElement]?.workspaceIds?.contains(workspaceId) == true) {
            throw WorkspaceGateReentrancyException(workspaceId)
        }
        val gate = gate(workspaceId)
        gate.changeMutex.withLock {
            var sealToken = 0
            var victims: List<OpRecord> = emptyList()
            gate.admissionMutex.withLock {
                if (!gate.closedFlag.get()) {
                    sealToken = gate.openSignal.value
                    gate.closedFlag.set(true)
                    gate.sealedFlag.set(true)
                    val now = gate.epochsFlow.value
                    gate.epochsFlow.value = WorkspaceEpochs(now.policyEpoch + 1, now.viewEpoch + 1)
                    bumpToolEpochsLocked(gate, null)
                }
                victims = gate.operations.values.toList()
                gate.refreshSnapshot()
            }
            fireBeforeChange(WorkspaceChangeImminent(workspaceId, null, gate.epochsFlow.value))
            var committed = false
            try {
                victims.forEach { rec ->
                    val cause = WorkspacePolicyCancellationException(workspaceId)
                    rec.policyCancellation.set(cause)
                    rec.job.cancel(cause)
                }
                val cleanupFailures = mutableListOf<Throwable>()
                val attemptedHandles = mutableSetOf<Long>()
                val done = withContext(NonCancellable) {
                    withTimeoutOrNull(joinTimeoutMillis) {
                        victims.forEach { it.job.join() }
                        // 与 publishPrepared 同口径：join 后对账重扫，收养进册的新孤儿
                        // 同样要被清理，不留 live orphan；每条只尝试一次。
                        while (true) {
                            val fresh = gate.admissionMutex.withLock {
                                gate.handles.values.filter {
                                    !it.closedOk && it.id !in attemptedHandles
                                }
                            }
                            if (fresh.isEmpty()) break
                            fresh.forEach { h ->
                                attemptedHandles += h.id
                                try {
                                    h.cleanupHandle()
                                } catch (e: Throwable) {
                                    cleanupFailures += e
                                    h.pendingCleanup.set(true)
                                }
                            }
                        }
                    } != null
                }
                var pendingHandleCount = 0
                var leftoverPending = false
                gate.admissionMutex.withLock {
                    if (done) {
                        gate.handles.values.filter { it.closedOk }.forEach { gate.handles.remove(it.id) }
                        gate.operations.values.filter { it.job.isCompleted }
                            .forEach { gate.operations.remove(it.id) }
                    }
                    pendingHandleCount = gate.handles.values.count { !it.closedOk }
                    leftoverPending = gate.handles.values.any { it.pendingCleanup.get() && !it.closedOk } ||
                        gate.operations.values.any { it.cleanupPending.get() && !it.job.isCompleted }
                    gate.refreshSnapshot()
                    if (!done) {
                        gate.quarantinedFlag.set(true)
                        throw WorkspaceGateJoinTimeoutException(
                            workspaceId = workspaceId,
                            pendingOperations = victims.count { it.job.isActive } + pendingHandleCount,
                        )
                    }
                    if (cleanupFailures.isNotEmpty() || leftoverPending) {
                        gate.quarantinedFlag.set(true)
                        throw WorkspaceGateCleanupException(
                            workspaceId = workspaceId,
                            pendingHandles = pendingHandleCount,
                            causes = cleanupFailures.toList(),
                        )
                    }
                    // committed 决策与欠债检查同锁：本窗口收养进册的孤儿不会被判作已收回
                    committed = true
                }
            } finally {
                emitEvent(
                    gate,
                    WorkspaceConfigChangeEvent(workspaceId, affectedStableIds = null, gate.epochsFlow.value, committed = committed),
                )
                // closed 优先于 sealed：admission 走 WORKSPACE_CLOSED 快路径；解除 sealed 让诊断一致
                gate.sealedFlag.set(false)
                gate.openSignal.value = sealToken + 1
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun gate(workspaceId: String): PerWorkspace =
        gates.getOrPut(workspaceId) { PerWorkspace(workspaceId) }

    /** 持锁上下文（admission 临界区/publish 回调）内的重入拒绝。 */
    private suspend fun rejectIfLockContext(workspaceId: String) {
        if (workspaceId in (coroutineContext[WorkspaceAdmissionElement]?.workspaceIds ?: emptySet()) ||
            workspaceId in (coroutineContext[WorkspacePublishElement]?.workspaceIds ?: emptySet())
        ) {
            throw WorkspaceGateReentrancyException(workspaceId)
        }
    }

    /** Carry the entire protected set through nested, otherwise-disjoint callbacks. */
    private suspend fun protectedWorkspaces(): Set<String> =
        (coroutineContext[WorkspaceAdmissionElement]?.workspaceIds ?: emptySet()) +
            (coroutineContext[WorkspacePublishElement]?.workspaceIds ?: emptySet()) +
            (coroutineContext[WorkspaceOpElement]?.workspaceIds ?: emptySet())

    private suspend fun workspaceAdmissionContext(workspaceId: String) =
        WorkspaceAdmissionElement(protectedWorkspaces() + workspaceId)

    private suspend fun workspacePublishContext(workspaceIds: Set<String>) =
        WorkspacePublishElement(protectedWorkspaces() + workspaceIds)

    private suspend fun workspacePublishContext(workspaceId: String) =
        workspacePublishContext(setOf(workspaceId))

    private sealed class AdmitOutcome {
        class Denied(val exception: WorkspaceUnavailableException) : AdmitOutcome()
        class Wait(val sealToken: Int) : AdmitOutcome()
        class Admitted(val epochs: WorkspaceEpochs) : AdmitOutcome()
    }

    private suspend fun admit(
        gate: PerWorkspace,
        workspaceId: String,
        record: OpRecord,
        expectedPolicyEpoch: Long?,
        expectedViewEpoch: Long?,
        expectedToolEpochs: Map<String, Long>?,
        waitIfSealed: Boolean,
        validate: (suspend () -> Boolean)?,
    ): WorkspaceEpochs {
        while (true) {
            val outcome = gate.admissionMutex.withLock<AdmitOutcome> {
                when {
                    gate.closedFlag.get() -> AdmitOutcome.Denied(
                        WorkspaceUnavailableException(workspaceId, WorkspaceUnavailableReason.WORKSPACE_CLOSED, "workspace is closed")
                    )
                    gate.quarantinedFlag.get() -> AdmitOutcome.Denied(
                        WorkspaceUnavailableException(
                            workspaceId, WorkspaceUnavailableReason.CLEANUP_FAILED,
                            "workspace quarantined by failed victim cleanup"
                        )
                    )
                    gate.sealedFlag.get() ->
                        if (waitIfSealed) {
                            AdmitOutcome.Wait(gate.openSignal.value)
                        } else {
                            AdmitOutcome.Denied(
                                WorkspaceUnavailableException(workspaceId, WorkspaceUnavailableReason.SEALED, "admission sealed by config change")
                            )
                        }
                    else -> {
                        val reason = denialReasonLocked(gate, expectedPolicyEpoch, expectedViewEpoch, expectedToolEpochs)
                        when {
                            reason != null -> AdmitOutcome.Denied(
                                WorkspaceUnavailableException(workspaceId, reason, "epoch mismatch: $reason")
                            )
                            !(validate == null || withContext(workspaceAdmissionContext(workspaceId)) { validate() }) ->
                                AdmitOutcome.Denied(
                                    WorkspaceUnavailableException(workspaceId, WorkspaceUnavailableReason.VALIDATION_FAILED, "validate() rejected latest state")
                                )
                            else -> {
                                gate.operations[record.id] = record
                                gate.refreshSnapshot()
                                AdmitOutcome.Admitted(gate.epochsFlow.value)
                            }
                        }
                    }
                }
            }
            when (outcome) {
                is AdmitOutcome.Denied -> throw outcome.exception
                is AdmitOutcome.Admitted -> return outcome.epochs
                is AdmitOutcome.Wait -> gate.openSignal.first { it != outcome.sealToken }
            }
        }
    }

    private fun denialReasonLocked(
        gate: PerWorkspace,
        expectedPolicyEpoch: Long?,
        expectedViewEpoch: Long?,
        expectedToolEpochs: Map<String, Long>?,
    ): WorkspaceUnavailableReason? {
        val epochs = gate.epochsFlow.value
        if (expectedPolicyEpoch != null && expectedPolicyEpoch != epochs.policyEpoch) {
            return WorkspaceUnavailableReason.STALE_POLICY_EPOCH
        }
        if (expectedViewEpoch != null && expectedViewEpoch != epochs.viewEpoch) {
            return WorkspaceUnavailableReason.STALE_VIEW_EPOCH
        }
        if (expectedToolEpochs != null) {
            val base = gate.toolEpochBase.get()
            for ((id, expected) in expectedToolEpochs) {
                if (((gate.toolEpochs[id] ?: 0L) + base) != expected) return WorkspaceUnavailableReason.STABLE_ID_REVOKED
            }
        }
        return null
    }

    private fun admissibleLocked(
        gate: PerWorkspace,
        expectedPolicyEpoch: Long?,
        expectedViewEpoch: Long?,
        expectedToolEpochs: Map<String, Long>?,
    ): Boolean {
        if (gate.closedFlag.get() || gate.sealedFlag.get()) return false
        return denialReasonLocked(gate, expectedPolicyEpoch, expectedViewEpoch, expectedToolEpochs) == null
    }

    private fun matchesVictim(
        stableIds: Set<String>,
        handleClass: WorkspaceHandleClass,
        spec: WorkspaceChangeSpec,
    ): Boolean {
        if (spec.handleClasses != null && handleClass !in spec.handleClasses) return false
        val affected = spec.affectedStableIds ?: return true
        return stableIds.any { it in affected }
    }

    private fun bumpToolEpochsLocked(gate: PerWorkspace, affectedStableIds: Set<String>?) {
        if (affectedStableIds == null) {
            // 结构级：基数+1 对所有已知与未知 stableId 统一生效吊销（新工具自动覆盖）
            gate.toolEpochBase.incrementAndGet()
        } else {
            affectedStableIds.forEach { id -> gate.toolEpochs.merge(id, 1L, Long::plus) }
        }
    }

    private fun fireBeforeChange(imminent: WorkspaceChangeImminent) {
        // 锁外同步调用：listener 必须纯内存且立即返回（典型：停 warmer、置失效标记）
        for (listener in beforeChangeListeners) {
            runCatching { listener(imminent) }
        }
    }

    private fun emitEvent(gate: PerWorkspace, event: WorkspaceConfigChangeEvent) {
        gate.eventsFlow.value = event
        _changes.tryEmit(event)
    }

    private class PerWorkspace(val workspaceId: String) {
        val admissionMutex = Mutex()
        val changeMutex = Mutex()
        /** 单调信号：seal 时读 token，unseal 时 +1 唤醒等待者。 */
        val openSignal = MutableStateFlow(0)
        val epochsFlow = MutableStateFlow(WorkspaceEpochs())
        val eventsFlow = MutableStateFlow<WorkspaceConfigChangeEvent?>(null)
        val snapshotRef = AtomicReference(
            WorkspaceGateSnapshot(
                workspaceId, WorkspaceEpochs(), sealed = false, closed = false,
                quarantined = false, 0, 0, emptyMap(),
            )
        )
        val toolEpochsRef = AtomicReference<Map<String, Long>>(emptyMap())
        /** 全局吊销基数：spec(null) 结构变更 +1，对所有（含未登记的）stableId 生效。 */
        val toolEpochBase = AtomicLong(0L)
        val sealedFlag = AtomicBoolean(false)
        val closedFlag = AtomicBoolean(false)
        /** victim 清理失败/超时的隔离态：准入/快照 fail-fast（CLEANUP_FAILED）而非永久等待。 */
        val quarantinedFlag = AtomicBoolean(false)

        // admissionMutex 下变更；handles 同时允许锁外 containsKey 只读。
        val operations = LinkedHashMap<Long, OpRecord>()
        val handles = ConcurrentHashMap<Long, HandleRecord>()
        val toolEpochs = HashMap<String, Long>()

        fun refreshSnapshot() {
            toolEpochsRef.set(HashMap(toolEpochs))
            val base = toolEpochBase.get()
            snapshotRef.set(
                WorkspaceGateSnapshot(
                    workspaceId = workspaceId,
                    epochs = epochsFlow.value,
                    sealed = sealedFlag.get(),
                    closed = closedFlag.get(),
                    quarantined = quarantinedFlag.get(),
                    activeOperations = operations.size,
                    activeOperationsByKind = operations.values
                        .groupingBy { it.kind }.eachCount(),
                    registeredHandles = handles.size,
                    // 快照里给调用方的是有效值（base+offset），可直接与捕获比对
                    toolEpochs = toolEpochsRef.get().mapValues { it.value + base },
                )
            )
        }
    }

    private class OpRecord(
        val id: Long,
        val kind: WorkspaceOperationKind,
        val stableIds: Set<String>,
        val handleClass: WorkspaceHandleClass,
        val job: CompletableJob,
        val startedAtMillis: Long,
    ) {
        /** publish 先置位再 cancel：catch 侧据此确定性地区分 gate 取消与外部取消。 */
        val policyCancellation = AtomicReference<WorkspacePolicyCancellationException?>()
        /** 自身收尾 join 超时弃册标记：job 仍在跑，后续 publish 不得把它当作已完成。 */
        val cleanupPending = AtomicBoolean(false)
    }

    private class HandleRecord(
        val id: Long,
        val workspaceId: String,
        val handleClass: WorkspaceHandleClass,
        val stableIds: Set<String>,
        val epochsAtAdmission: WorkspaceEpochs,
        val closeNow: suspend () -> Unit,
        val awaitClosed: suspend () -> Unit,
    ) {
        /** close+awaitClosed 均成功才置位；出册判定依据（fail-loud）。 */
        @Volatile var closedOk: Boolean = false
        /** 收尾失败欠债标记：publish 对账时据此判定该 ws 是否仍有未收尾资源。 */
        val pendingCleanup = AtomicBoolean(false)
        @Volatile private var closeInvoked: Boolean = false

        /**
         * 单资源串行收尾门：publish victim 清理 / releaseHandle / createHandle 收尾共用。
         * - close+await 全成功才标 closedOk（调用方据此出册）；
         * - close 成功而 await 失败：重试只重跑 await，不二次 close；
         * - close 失败：可整体重试。
         * 调用方须处于不可取消上下文（或在锁外接受串行等待），避免半收尾状态。
         */
        val cleanupMutex = Mutex()

        suspend fun cleanupHandle() = cleanupMutex.withLock {
            if (closedOk) return@withLock
            if (!closeInvoked) {
                closeNow()      // 抛错 → 保持未标记，下次重试会重新 close
                closeInvoked = true
            }
            awaitClosed()       // 抛错 → closeInvoked 保留，重试只重跑 await
            closedOk = true
            pendingCleanup.set(false)
        }
    }

    private class Box<T>(val value: T)

    private class WorkspaceAdmissionElement(val workspaceIds: Set<String>) :
        AbstractCoroutineContextElement(WorkspaceAdmissionElement) {
        companion object Key : CoroutineContext.Key<WorkspaceAdmissionElement>
    }

    private class WorkspaceOpElement(
        val workspaceId: String,
        val opId: Long,
        /** 嵌套 op 的祖先 ws 链（含自身 ws）：publish/closeWorkspace 重入拒绝按集合判断。 */
        val workspaceIds: Set<String> = setOf(workspaceId),
        /** 本 op 期间入册但未交付的 handle token：op 未正常返回时由 gate 代收尾。 */
        val mintedHandles: MutableSet<WorkspaceHandleToken> = mutableSetOf(),
    ) : AbstractCoroutineContextElement(WorkspaceOpElement) {
        companion object Key : CoroutineContext.Key<WorkspaceOpElement>
    }

    private class WorkspacePublishElement(val workspaceIds: Set<String>) :
        AbstractCoroutineContextElement(WorkspacePublishElement) {
        companion object Key : CoroutineContext.Key<WorkspacePublishElement>
    }
}

// ------------------------------------------------------------------ 公共类型

enum class WorkspaceOperationKind {
    TOOL, CHILD, HANDLE_CREATION, GENERATION, PENDING_APPROVAL,
    /** 设备目录的手动/文件 IO 操作：计入 victim（结构变更会取消它），但不计入
     *  「busy 拒绝」判定——用户手动文件操作不阻塞连接配置编辑。 */
    FILE_OPERATION,
}

/**
 * 通用 handle/操作分类（与 kind 正交，kind 仅诊断）：victim 判定可作 AND 过滤。
 * USER_TERMINAL 配空 stableIds —— 单工具关闭波及不到手动终端。
 */
enum class WorkspaceHandleClass { MODEL_EXECUTION, MODEL_BASH, CHILD, USER_TERMINAL, FILE_OPERATION, GENERIC }

enum class WorkspaceUnavailableReason {
    STALE_POLICY_EPOCH,
    STALE_VIEW_EPOCH,
    STABLE_ID_REVOKED,
    VALIDATION_FAILED,
    WORKSPACE_CLOSED,
    SEALED,
    /** 上次变更的 victim 清理失败/超时进入 quarantine：拒绝并唤醒等待者，retry publish/closeWorkspace 恢复。 */
    CLEANUP_FAILED,
}

/** policyEpoch=准入策略（tool_enablement 等）；viewEpoch=设备/merged 结构。独立比对。 */
data class WorkspaceEpochs(
    val policyEpoch: Long = 0,
    val viewEpoch: Long = 0,
)

data class WorkspaceGateSnapshot(
    val workspaceId: String,
    val epochs: WorkspaceEpochs,
    val sealed: Boolean,
    val closed: Boolean,
    /** 清理失败隔离态（sealed 的真子集）：该状态下准入/快照读 fail-fast CLEANUP_FAILED。 */
    val quarantined: Boolean,
    val activeOperations: Int,
    val registeredHandles: Int,
    /** per-id 有效吊销代数（含全局基数，可直接与捕获值比对）。 */
    val toolEpochs: Map<String, Long>,
    /** 按 kind 分类的活跃操作数（诊断/设备配置 busy 判定用，默认空=零值快照）。 */
    val activeOperationsByKind: Map<WorkspaceOperationKind, Int> = emptyMap(),
)

/**
 * 变更描述。affectedStableIds=null 波及该 workspace 全部在册项；空集合或不含交集则
 * 只动 epoch、不取消任何 victim（合法 no-op 发布）。
 */
data class WorkspaceChangeSpec(
    val affectedStableIds: Set<String>?,
    val handleClasses: Set<WorkspaceHandleClass>? = null,
    val bumpPolicyEpoch: Boolean = true,
    val bumpViewEpoch: Boolean = false,
) {
    companion object {
        /** 工具开关级变更：只 bump policy epoch，不动 view（其他仍启用工具凭 view 不过期）。 */
        fun toolPolicy(affectedStableIds: Set<String>) = WorkspaceChangeSpec(
            affectedStableIds = affectedStableIds,
            bumpPolicyEpoch = true,
            bumpViewEpoch = false,
        )

        /** 结构级变更：波及全部在册项，双 epoch 均 bump。 */
        fun structural() = WorkspaceChangeSpec(
            affectedStableIds = null,
            bumpPolicyEpoch = true,
            bumpViewEpoch = true,
        )
    }
}

data class WorkspaceConfigChangeEvent(
    val workspaceId: String,
    val affectedStableIds: Set<String>?,
    val epochs: WorkspaceEpochs,
    /** publish 回调成功=true；失败/取消/清理失败=false（epoch 已 bump）。 */
    val committed: Boolean,
)

/** onBeforeChange 同步事件：epochs 为 bump 后的新值。 */
data class WorkspaceChangeImminent(
    val workspaceId: String,
    val affectedStableIds: Set<String>?,
    val epochs: WorkspaceEpochs,
)

data class WorkspaceHandleToken(
    val workspaceId: String,
    val handleId: Long,
    val epochsAtAdmission: WorkspaceEpochs,
)

data class WorkspaceRuntimeHandle<H>(
    val token: WorkspaceHandleToken,
    val value: H,
) {
    val handleId: Long get() = token.handleId
    val workspaceId: String get() = token.workspaceId
}

// ------------------------------------------------------------------ 异常

open class WorkspaceGateException(message: String) : IllegalStateException(message)

/** 未开始即拒绝：零执行，T1 映射为 tool result "unavailable"，不进 body/审批。 */
class WorkspaceUnavailableException(
    val workspaceId: String,
    val reason: WorkspaceUnavailableReason,
    message: String,
) : WorkspaceGateException(message)

/** victim join/close 在 [WorkspaceRuntimeGate.DEFAULT_JOIN_TIMEOUT_MS] 内未完成：不发布且不解封。 */
class WorkspaceGateJoinTimeoutException(
    val workspaceId: String,
    val pendingOperations: Int,
) : WorkspaceGateException("join timed out for workspace $workspaceId ($pendingOperations pending)")

/** victim handle close/awaitClosed 抛错：不发布且不解封（fail-loud，不吞异常冒充成功）。 */
class WorkspaceGateCleanupException(
    val workspaceId: String,
    val pendingHandles: Int,
    val causes: List<Throwable>,
) : WorkspaceGateException(
    "handle cleanup failed for workspace $workspaceId ($pendingHandles handles, causes=${causes.size})"
)

class WorkspaceStaleHandleException(
    val workspaceId: String,
    val handleId: Long,
) : WorkspaceGateException("handle became stale during creation for workspace $workspaceId")

class WorkspaceGateReentrancyException(
    val workspaceId: String,
) : WorkspaceGateException("reentrant gate call inside op/validate/publish for workspace $workspaceId")

/**
 * gate 发起的操作取消：已开始执行、被策略/结构变更打断（→ outcome_unknown）。
 * 识别依据是 OpRecord.policyCancellation 置位（publish 先 set 后 cancel），不依赖 CE 解包。
 */
class WorkspacePolicyCancellationException(
    val workspaceId: String,
    cause: CancellationException? = null,
) : CancellationException("operation cancelled by workspace policy change: $workspaceId") {
    init {
        if (cause != null) initCause(cause)
    }
}
