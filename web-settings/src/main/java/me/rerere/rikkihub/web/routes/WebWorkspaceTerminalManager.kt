package me.rerere.rikkihub.web.routes

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.utils.activeDnsServers
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.TooManyRequestsException
import me.rerere.rikkihub.data.workspace.WorkspaceGateCleanupException
import me.rerere.workspace.RootfsPatchOptionsSpec
import me.rerere.workspace.WorkspacePendingCloses
import me.rerere.workspace.WorkspacePty
import me.rerere.workspace.WorkspaceShellRequest
import me.rerere.workspace.WorkspaceShellStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "WebWorkspaceTerminal"
private const val MAX_SESSIONS_PER_WORKSPACE = 4
private const val MAX_SESSIONS_TOTAL = 8
private const val ATTACH_TIMEOUT_MS = 60_000L
private const val REAPER_INTERVAL_MS = 30_000L
// publish 对账等待 PTY 真退出的上限：超时按失败上报 (quarantine), 不冒充已退出
private const val SESSION_CLOSE_AWAIT_MS = 5_000L

class WebWorkspaceTerminalManager(
    context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val mutex = Mutex()
    private val lifecycleLocks = ConcurrentHashMap<String, Any>()
    private val lifecycleRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 已请求 close、唯一 waiter 尚未证明退出的会话：suspend closer 的 join 对象来源，
    // 同步 close 从 sessions 移除后绝不能丢掉它们
    private val pendingCloses = WorkspacePendingCloses<LiveSession>()

    init {
        // publish 前对账的 suspend closer：真实 close + 等待退出；失败抛出 => publish
        // 不写库/欠债记录 quarantine
        workspaceRepository.sessionInvalidation.register("web-terminal") { id, _ ->
            closeWorkspaceAndAwait(id)
        }
        // 定期回收: 进程已退出但没人 close 的死会话 (WS 断开但 handler 异常等),
        // 同时复查在册会话的设备挂载授权 (executionBindMounts 失败 = source 失效,
        // 主动关掉而不是让 shell 在已断开的 /device 上继续跑)
        scope.launch {
            while (isActive) {
                delay(REAPER_INTERVAL_MS)
                reapDeadSessions()
                recheckDeviceMounts()
            }
        }
    }

    private suspend fun recheckDeviceMounts() {
        val workspaceIds = (sessions.values.map { it.workspaceId } +
            pendingCloses.pending().map { it.workspaceId }).toSet()
        for (workspaceId in workspaceIds) {
            runCatching { workspaceRepository.executionMounts(workspaceId) }
                .onFailure { error ->
                    Log.w(TAG, "Device mount check failed for $workspaceId, closing terminals", error)
                    // await 失败只记日志，不把 reaper 循环炸掉
                    runCatching { closeWorkspaceAndAwait(workspaceId) }
                        .onFailure { Log.w(TAG, "close await failed for $workspaceId", it) }
                }
        }
    }

    suspend fun createSession(workspaceId: String, cols: Int, rows: Int): LiveSession =
        workspaceRepository.runtimeGate.withOperation(
            workspaceId = workspaceId,
            kind = me.rerere.rikkihub.data.workspace.WorkspaceOperationKind.HANDLE_CREATION,
            handleClass = me.rerere.rikkihub.data.workspace.WorkspaceHandleClass.USER_TERMINAL,
            expectedViewEpoch = workspaceRepository.runtimeGate.currentViewEpoch(workspaceId),
        ) { createSessionInternal(workspaceId, cols, rows) }

    private suspend fun createSessionInternal(workspaceId: String, cols: Int, rows: Int): LiveSession {
        val workspace = workspaceRepository.getById(workspaceId)
            ?: throw NotFoundException("Workspace not found")
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            throw BadRequestException("Workspace shell is not ready")
        }
        val lifecycleLock = lifecycleLocks.getOrPut(workspaceId) { Any() }
        val revision = lifecycleRevisions.getOrPut(workspaceId) { AtomicLong() }.get()
        val viewEpoch = workspaceRepository.runtimeGate.currentViewEpoch(workspaceId)
        if (workspaceRepository.runtimeGate.snapshot(workspaceId).let { it.sealed || it.closed }) {
            throw BadRequestException("Workspace configuration is changing or unavailable")
        }
        if (!workspaceRepository.rootfsReady(workspaceId)) {
            throw BadRequestException("Workspace rootfs is not installed")
        }

        return mutex.withLock {
            if (sessions.size >= MAX_SESSIONS_TOTAL ||
                sessions.values.count { it.workspaceId == workspaceId } >= MAX_SESSIONS_PER_WORKSPACE
            ) {
                throw TooManyRequestsException("Too many terminal sessions")
            }
            val sessionId = UUID.randomUUID().toString()
            // The repository resolves the full mount selection (exposed mounts + validated
            // device directories); the backend re-validates device sources, failing instead
            // of silently dropping a mount.
            val pty = workspaceRepository.openShell(
                workspaceId,
                WorkspaceShellRequest(
                    rows = rows.coerceIn(8, 120),
                    columns = cols.coerceIn(20, 300),
                    patchOptions = RootfsPatchOptionsSpec(nameservers = appContext.activeDnsServers()),
                ),
            )
            val live = LiveSession(
                id = sessionId,
                workspaceId = workspaceId,
                createdAt = System.currentTimeMillis(),
                cols = cols.coerceIn(20, 300),
                rows = rows.coerceIn(8, 120),
                pty = pty,
            )
            // 60s 内没有 WS 接手就关掉, 避免僵尸 PTY 常驻
            live.attachTimeoutJob = scope.launch {
                delay(ATTACH_TIMEOUT_MS)
                if (!live.attached.get()) {
                    Log.w(TAG, "Terminal session $sessionId was never attached, closing")
                    close(sessionId)
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                synchronized(lifecycleLock) {
                    if (lifecycleRevisions.getValue(workspaceId).get() != revision ||
                        workspaceRepository.runtimeGate.currentViewEpoch(workspaceId) != viewEpoch ||
                        workspaceRepository.runtimeGate.snapshot(workspaceId).let { it.sealed || it.closed }
                    ) throw BadRequestException("Workspace terminal creation became stale")
                    sessions[sessionId] = live
                }
                live
            } catch (failure: Throwable) {
                live.attachTimeoutJob?.cancel()
                live.pty.close()
                // 创建作废的孤儿也有界等待真退出，退出超时 suppressed 到原异常上
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (!live.pty.awaitClosed(SESSION_CLOSE_AWAIT_MS)) {
                        failure.addSuppressed(
                            IllegalStateException("terminal session did not exit within ${SESSION_CLOSE_AWAIT_MS}ms")
                        )
                    }
                }
                throw failure
            }
        }
    }

    fun get(sessionId: String): LiveSession =
        sessions[sessionId] ?: throw NotFoundException("Terminal session not found")

    /** WS handler 接手会话时调用, 取消未接管的自动关闭 */
    fun markAttached(sessionId: String) {
        sessions[sessionId]?.let { session ->
            session.attached.set(true)
            session.attachTimeoutJob?.cancel()
        }
    }

    /**
     * 用户/WS 路径的同步 close：移除活跃表 + 请求关闭。join 对象记录到
     * [pendingCloses] 直到唯一 waiter 证明退出，晚到的 closeWorkspaceAndAwait
     * 仍能等到它。
     */
    fun close(sessionId: String) {
        val peek = sessions[sessionId] ?: return
        // remove 与 pendingCloses.record 必须在同一把 workspace 锁内：否则
        // closeWorkspaceAndAwait 快照可能在 remove 之后、record 之前漏掉这条会话
        val session = synchronized(lifecycleLocks.getOrPut(peek.workspaceId) { Any() }) {
            sessions.remove(sessionId)?.also { pendingCloses.record(it) }
        } ?: return
        session.attachTimeoutJob?.cancel()
        session.pty.close()
        scope.launch {
            // drain：等到 PTY 真退出才把记录出 pendingCloses
            runCatching { session.pty.awaitExit() }
            pendingCloses.complete(session)
        }
    }

    /**
     * 配置发布前的真实收尾：快照式摘除该 workspace 的会话（含 pendingCloses 里
     * 已请求关闭但还没证明退出的），逐个 close + 等待唯一 waiter 报退出。
     * 任何一个超时未退 => 抛 WorkspaceGateCleanupException，publish 中止并 quarantine。
     */
    suspend fun closeWorkspace(workspaceId: String) = closeWorkspaceAndAwait(workspaceId)

    suspend fun closeWorkspaceAndAwait(workspaceId: String) {
        val stale = synchronized(lifecycleLocks.getOrPut(workspaceId) { Any() }) {
            lifecycleRevisions.getOrPut(workspaceId) { AtomicLong() }.incrementAndGet()
            sessions.values.filter { it.workspaceId == workspaceId }.mapNotNull { live ->
                sessions.remove(live.id, live).takeIf { it }?.let {
                    pendingCloses.record(live)
                    live
                }
            } + pendingCloses.pending().filter { it.workspaceId == workspaceId }
        }.distinct()
        val failures = mutableListOf<Throwable>()
        for (live in stale) {
            runCatching {
                live.attachTimeoutJob?.cancel()
                live.pty.close()
                if (!live.pty.awaitClosed(SESSION_CLOSE_AWAIT_MS)) {
                    throw IllegalStateException(
                        "terminal session ${live.id} did not exit within ${SESSION_CLOSE_AWAIT_MS}ms"
                    )
                }
            }.onSuccess { pendingCloses.complete(live) }
                .onFailure { failures += it }
        }
        if (failures.isNotEmpty()) {
            throw WorkspaceGateCleanupException(
                workspaceId = workspaceId,
                pendingHandles = stale.size,
                causes = failures,
            )
        }
    }

    fun closeAll() {
        (sessions.keys.toList()).forEach { close(it) }
    }

    private fun reapDeadSessions() {
        sessions.values
            .filter { it.pty.exitCode != null }
            .forEach { session ->
                Log.d(TAG, "Reaping dead terminal session ${session.id}")
                close(session.id)
            }
    }

    data class LiveSession(
        val id: String,
        val workspaceId: String,
        val createdAt: Long,
        val cols: Int,
        val rows: Int,
        val pty: WorkspacePty,
        val attached: AtomicBoolean = AtomicBoolean(false),
        var attachTimeoutJob: Job? = null,
    )
}
