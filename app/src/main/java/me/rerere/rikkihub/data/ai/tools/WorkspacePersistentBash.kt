package me.rerere.rikkihub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceGateCleanupException
import me.rerere.rikkihub.data.workspace.WorkspaceHandleClass
import me.rerere.rikkihub.data.workspace.WorkspaceHandleToken
import me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException
import me.rerere.rikkihub.utils.activeDnsServers
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspacePendingCloses
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

internal const val PERSISTENT_BASH_TIMEOUT_MS = 300_000L
// 设备挂载失效复查间隔 (轻量 File 检查, 无 DB 读)
private const val DEVICE_MOUNT_RECHECK_MS = 30_000L
internal const val PERSISTENT_BASH_MAX_OUTPUT_CHARS = 16_000
internal const val PERSISTENT_BASH_READ_BUFFER_BYTES = 8_192
internal const val PERSISTENT_BASH_INIT_SETTLE_MS = 150L
// gate closer/publish 前对账用的进程退出等待上限：超时按失败上报 (quarantine), 不冒充已退出
private const val SESSION_CLOSE_AWAIT_MS = 5_000L
private const val TAG = "WorkspacePersistentBash"
// end marker 到达后 exit code 数字可能还差几个字节, 重解析的短宽限
private const val MARKER_STATUS_GRACE_MS = 500L
// PTY EOF 后 waitFor 线程回填 exitCode 的宽限
private const val PTY_EXIT_GRACE_MS = 1_000L

private const val TRUNCATED_MESSAGE =
    "<response clipped><NOTE>To save on context only part of this file has been shown to you. You should retry this tool after you have searched inside the file with grep -n in order to find the line numbers of what you are looking for.</NOTE>"
private const val LOST_PREFIX_MESSAGE =
    "<response clipped><NOTE>The beginning of this command output was dropped by the terminal scrollback limit. The following text is the earliest retained output.</NOTE>\n"
private const val SHELL_RESET_MESSAGE =
    "The persistent bash shell was reset; the next bash call starts from the workspace with a fresh current directory and environment."
private const val STTY_DISABLE_ECHO = "stty -echo\n"

private val TRAILING_NEWLINE_REGEX = Regex("\\r?\\n$")
private val LEADING_NEWLINE_REGEX = Regex("^\\r?\\n")
private val EXIT_STATUS_REGEX = Regex("^(\\d+)\\r?\\n")

/**
 * 持久 bash 会话的所属者。协程上下文里带它时，`bash` 工具使用该所属者自己的 shell，
 * 不和同工作区的其他所属者共用 cwd/环境变量；不带时（普通对话）沿用每个工作区一个
 * 会话。并行的子代理各带自己的所属者，运行结束由派发方 [WorkspacePersistentBashSessionManager.closeOwner]。
 */
class PersistentBashOwner(val owner: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PersistentBashOwner>
}

/**
 * 会话表键：普通对话就是 workspaceId（与旧行为一致），带所属者时追加所属者；
 * 限定工作目录时再追加范围，不同范围的会话绝不共用一个 shell。
 */
internal fun persistentBashSessionKey(workspaceId: String, owner: String?, filesScope: String? = null): String {
    val base = if (owner == null) workspaceId else "$workspaceId\u0000$owner"
    return if (filesScope == null) base else "$base\u0000scope=$filesScope"
}

internal data class PersistentBashCaptured(
    val text: String,
    val incomplete: Boolean,
    val exitCode: Int? = null,
)

internal data class PersistentBashMarkers(
    val start: String,
    val end: String,
)

internal fun persistentBashMarkers(nonce: String = UUID.randomUUID().toString()): PersistentBashMarkers =
    PersistentBashMarkers(
        start = "__DSH_PERSISTENT_BASH_START_${nonce}__",
        end = "__DSH_PERSISTENT_BASH_END_${nonce}:",
    )

internal fun quoteForPersistentBash(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    return "\$'$escaped'"
}

// eval 的参数前面加一个空格，以 '-' 开头的命令不会被当成 eval 的选项；不用 "eval --"：
// busybox ash（Alpine 的 /bin/sh）会把 "--" 当命令执行。$'…' 在 bash 和 ash 里都能用。
internal fun wrapPersistentBashCommand(command: String, marker: PersistentBashMarkers): String =
    "printf '%s\\n' ${quoteForPersistentBash(marker.start)}; " +
        "eval ${quoteForPersistentBash(" $command")}; " +
        "__dsh_persistent_bash_status=\$?; " +
        "printf '%s%s\\n' ${quoteForPersistentBash(marker.end)} \"\$__dsh_persistent_bash_status\""

internal fun maybeTruncatePersistentBash(content: String, maxOutputChars: Int, incomplete: Boolean = false): String {
    if (content.length <= maxOutputChars && !incomplete) return content
    return if (content.length <= maxOutputChars) {
        content + TRUNCATED_MESSAGE
    } else {
        content.take(maxOutputChars) + TRUNCATED_MESSAGE
    }
}

internal fun trimTrailingNewline(text: String): String = text.replace(TRAILING_NEWLINE_REGEX, "")

internal fun commandOutputFromScrollback(text: String, marker: PersistentBashMarkers): PersistentBashCaptured? {
    val end = text.lastIndexOf(marker.end)
    if (end < 0) return null
    val afterEnd = text.substring(end + marker.end.length)
    val statusMatch = EXIT_STATUS_REGEX.find(afterEnd) ?: return null
    val startMarker = text.lastIndexOf(marker.start, end)
    val start = if (startMarker < 0) 0 else startMarker + marker.start.length
    val raw = text.substring(start, end).replace(LEADING_NEWLINE_REGEX, "")
    return PersistentBashCaptured(
        text = trimTrailingNewline(raw),
        incomplete = startMarker < 0,
        exitCode = statusMatch.groupValues[1].toInt(),
    )
}

internal fun partialOutputFromScrollback(
    text: String,
    marker: PersistentBashMarkers,
    fallback: String,
    fallbackTruncated: Boolean,
): PersistentBashCaptured {
    val startMarker = text.lastIndexOf(marker.start)
    if (startMarker >= 0) {
        val raw = text.substring(startMarker + marker.start.length).replace(LEADING_NEWLINE_REGEX, "")
        return PersistentBashCaptured(trimTrailingNewline(raw), incomplete = false)
    }
    val fallbackStart = fallback.lastIndexOf(marker.start)
    val afterStart = if (fallbackStart < 0) {
        fallback
    } else {
        fallback.substring(fallbackStart + marker.start.length).replace(LEADING_NEWLINE_REGEX, "")
    }
    val fallbackEnd = afterStart.lastIndexOf(marker.end)
    val raw = if (fallbackEnd < 0) afterStart else afterStart.substring(0, fallbackEnd)
    return PersistentBashCaptured(
        text = trimTrailingNewline(raw),
        incomplete = fallbackTruncated || fallbackStart < 0,
    )
}

internal fun renderPersistentBashCaptured(output: PersistentBashCaptured, maxOutputChars: Int): String {
    val rendered = maybeTruncatePersistentBash(output.text, maxOutputChars, output.incomplete)
    val withLostPrefix = if (output.incomplete && output.text.isNotEmpty()) {
        LOST_PREFIX_MESSAGE + rendered
    } else {
        rendered
    }
    val status = output.exitCode?.takeIf { it != 0 }?.let { "[exit code: $it]" }
    return appendStatusMarker(withLostPrefix, status)
}

internal fun renderPersistentBashShellExit(content: String, exitCode: Int?): String {
    val marker = if (exitCode != null) "[shell exited: code $exitCode]" else "[shell exited]"
    return appendStatusMarker(content, marker)
}

internal fun renderPersistentBashTimeout(
    partial: String,
    timeoutMs: Long,
): String = listOf(
    "Your command timed out after ${(timeoutMs / 1000.0).roundToInt()} seconds or experienced an OOM error. Below is partial output:",
    partial,
    SHELL_RESET_MESSAGE,
).joinToString("\n")

private fun appendStatusMarker(content: String, marker: String?): String {
    if (marker == null) return content
    return if (content.isEmpty()) marker else "$content\n$marker"
}

class WorkspacePersistentBashSessionManager(
    context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceManager: me.rerere.workspace.WorkspaceBackend,
) {
    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    private val lifecycleLocks = ConcurrentHashMap<String, Any>()
    private val lifecycleRevisions = ConcurrentHashMap<String, AtomicLong>()
    // 已请求 close、唯一 waiter 尚未证明退出的会话：closeWorkspaceAndAwait 的 join 对象来源
    private val pendingCloses = WorkspacePendingCloses<LiveSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // closer 是 publish 对账路径：suspend 直到 PTY 真退出才返回；超时/失败抛出让
        // gate 把欠债记录挂 pendingCleanup + quarantine，绝不放行未完成收尾的新配置
        workspaceRepository.sessionInvalidation.register("model-bash", setOf(BuiltinToolIds.BASH)) { id, _ ->
            closeWorkspaceAndAwait(id)
        }
        // Model-only session manager: closing bash never closes App/Web user terminals.
        workspaceRepository.runtimeGate.addOnBeforeChangeListener { change ->
            if (change.affectedStableIds == null || BuiltinToolIds.BASH in change.affectedStableIds) {
                // Invalidate publication immediately, without blocking this pure callback on PTY IO.
                // Cancellation resets in-flight commands; the repository closer retires idle shells.
                synchronized(lifecycleLocks.getOrPut(change.workspaceId) { Any() }) {
                    lifecycleRevisions.getOrPut(change.workspaceId) { AtomicLong() }.incrementAndGet()
                }
            }
        }
        // 设备目录授权失效 (source 被删/权限回收) 不走 runtime gate publish:
        // 轻量定时复查每个在册会话的挂载指纹, 失效即按该 workspace 关闭 shell。
        scope.launch {
            while (isActive) {
                delay(DEVICE_MOUNT_RECHECK_MS)
                recheckDeviceMounts()
            }
        }
    }

    private suspend fun recheckDeviceMounts() {
        val workspaceIds = (sessions.values.map { it.workspaceId } + pendingCloses.pending().map { it.workspaceId })
            .distinct()
        for (workspaceId in workspaceIds) {
            runCatching { workspaceRepository.executionMounts(workspaceId) }
                .onFailure {
                    // source 失效主动关 shell；await 失败只记日志，不炸掉 reaper 循环
                    runCatching { closeWorkspaceAndAwait(workspaceId) }
                        .onFailure { error -> Log.w(TAG, "close await failed for $workspaceId", error) }
                }
        }
    }

    suspend fun execute(
        workspaceId: String,
        command: String,
        timeoutMs: Long = PERSISTENT_BASH_TIMEOUT_MS,
        maxOutputChars: Int = PERSISTENT_BASH_MAX_OUTPUT_CHARS,
        /** 会话所属者；null 时取协程上下文里的 [PersistentBashOwner]，都没有 = 工作区共用会话。 */
        owner: String? = null,
        /** 限定的工作目录；null = 整个工作区。 */
        filesScope: String? = null,
    ): String {
        require(command.isNotBlank()) { "command must be a non-empty string" }
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        require(workspace.shellStatus == WorkspaceShellStatus.READY.name) {
            "Rootfs is not installed"
        }
        require(workspaceManager.hasRootfs(workspace.root)) { "Rootfs is not installed" }

        // 按会话（工作区 + 所属者）串行, 不同会话之间互不阻塞
        val key = persistentBashSessionKey(
            workspaceId,
            owner ?: currentCoroutineContext()[PersistentBashOwner]?.owner,
            filesScope,
        )
        return sessionMutexes.getOrPut(key) { Mutex() }.withLock {
            // 已死/被 gate 退役/封禁期不新鲜的 session 不复用，重建一条在新配置下登记的
            sessions[key]?.let { existing ->
                val stale = existing.isEof || existing.pty.exitCode != null ||
                    existing.gateToken?.let { !workspaceRepository.runtimeGate.isHandleFresh(it) } == true
                if (stale) dropSession(existing)
            }
            val session = sessions[key] ?: createSession(workspace, key, filesScope)
            runCommand(session, command, timeoutMs, maxOutputChars)
        }
    }

    /**
     * 用户请求路径的同步 close（rootfs 重装/删除前的立即停会话）：把会话从活跃表移除并
     * 请求关闭，但 join 对象必须留在 [pendingCloses] —— 随后到来的
     * [closeWorkspaceAndAwait]（publish 对账）仍然等得到它的真实退出。
     */
    fun closeWorkspace(workspaceId: String) {
        val old = synchronized(lifecycleLocks.getOrPut(workspaceId) { Any() }) {
            lifecycleRevisions.getOrPut(workspaceId) { AtomicLong() }.incrementAndGet()
            removeWorkspaceSessionsLocked(workspaceId).onEach { pendingCloses.record(it) }
        }
        old.forEach { session ->
            session.close()
            drainPendingClose(session)
        }
    }

    /**
     * 关闭一个所属者（如一次子代理运行）自己的 shell，并等它真正退出。只影响这一个会话：
     * 不 bump 工作区 revision，同工作区其他会话照常。没有该会话时什么也不做。
     */
    suspend fun closeOwner(workspaceId: String, owner: String, filesScope: String? = null) {
        val key = persistentBashSessionKey(workspaceId, owner, filesScope)
        val session = sessionMutexes.getOrPut(key) { Mutex() }.withLock {
            synchronized(lifecycleLocks.getOrPut(workspaceId) { Any() }) {
                sessions.remove(key)?.also { pendingCloses.record(it) }
            }
        }
        sessionMutexes.remove(key)
        if (session != null) {
            runCatching { retireSession(session) }
                .onFailure { error ->
                    Log.w(TAG, "close owner bash session failed for $workspaceId", error)
                    drainPendingClose(session)
                }
        }
    }

    /** 摘除某工作区的全部会话（任意所属者）；调用方持有该工作区的 lifecycleLock。 */
    private fun removeWorkspaceSessionsLocked(workspaceId: String): List<LiveSession> =
        sessions.entries.filter { it.value.workspaceId == workspaceId }
            .mapNotNull { (key, session) -> session.takeIf { sessions.remove(key, it) } }

    /**
     * 配置发布前的真实收尾（sessionInvalidation closer）：快照式摘除活跃会话 + 扫
     * [pendingCloses] 里还没证明退出的，逐个 close + 等待唯一 waiter 报退出。
     * 任何一个在 [SESSION_CLOSE_AWAIT_MS] 内没退 => 抛 [WorkspaceGateCleanupException]
     * （调用方在 publish 回调里 => 不写库 + 欠债记录 quarantine，新配置被禁止）。
     */
    suspend fun closeWorkspaceAndAwait(workspaceId: String) {
        val snapshot = synchronized(lifecycleLocks.getOrPut(workspaceId) { Any() }) {
            lifecycleRevisions.getOrPut(workspaceId) { AtomicLong() }.incrementAndGet()
            removeWorkspaceSessionsLocked(workspaceId).forEach { pendingCloses.record(it) }
            pendingCloses.pending().filter { it.workspaceId == workspaceId }
        }
        val failures = mutableListOf<Throwable>()
        for (session in snapshot) {
            runCatching { retireSession(session) }.onFailure { failures += it }
        }
        if (failures.isNotEmpty()) {
            throw WorkspaceGateCleanupException(
                workspaceId = workspaceId,
                pendingHandles = snapshot.size,
                causes = failures,
            )
        }
    }

    /**
     * 单个会话的串行收尾：已登记的走 gate releaseHandle（cleanupMutex 保证 close 至多一次、
     * close 成功后重试只重跑 await）；未登记的兜底 close + awaitClosed。
     * 成功后把 record 出 pendingCloses（exit 已被证明）；失败留下次重试。
     */
    private suspend fun retireSession(session: LiveSession) {
        val token = session.gateToken
        if (token != null) {
            workspaceRepository.runtimeGate.releaseHandle(token)
        } else {
            session.close()
            if (!session.pty.awaitClosed(SESSION_CLOSE_AWAIT_MS)) {
                throw IllegalStateException(
                    "workspace bash session did not exit within ${SESSION_CLOSE_AWAIT_MS}ms"
                )
            }
        }
        pendingCloses.complete(session)
    }

    /** 同步 close 后的后台收尾：retire（含 gate 出册）→ 只有 retire 失败才继续靠
     *  唯一 waiter 的 exitSignal 兜底出 pendingCloses。 */
    private fun drainPendingClose(session: LiveSession) {
        scope.launch {
            runCatching { retireSession(session) }
                .onFailure { error ->
                    Log.w(TAG, "bash session retire failed for ${session.workspaceId}", error)
                    // retire 失败但进程真退了的话仍把记录消掉，避免永久堆积
                    runCatching { session.pty.awaitExit() }
                        .onSuccess { pendingCloses.complete(session) }
                }
        }
    }

    /** 从活跃表摘除 + 记录 pendingCloses + 请求关闭 + 后台 drain（不等待退出）。 */
    private fun dropSession(session: LiveSession) {
        synchronized(lifecycleLocks.getOrPut(session.workspaceId) { Any() }) {
            if (sessions.remove(session.key, session)) {
                pendingCloses.record(session)
            }
        }
        session.close()
        drainPendingClose(session)
    }

    fun closeAll() {
        sessions.values.map { it.workspaceId }.distinct().forEach { closeWorkspace(it) }
    }

    private suspend fun createSession(workspace: WorkspaceEntity, key: String, filesScope: String?): LiveSession {
        val workspaceId = workspace.id
        val lifecycleLock = lifecycleLocks.getOrPut(workspaceId) { Any() }
        val revision = lifecycleRevisions.getOrPut(workspaceId) { AtomicLong() }.get()
        val toolEpoch = workspaceRepository.runtimeGate.currentToolEpoch(workspaceId, BuiltinToolIds.BASH)
        val viewEpoch = workspaceRepository.runtimeGate.currentViewEpoch(workspaceId)
        currentCoroutineContext().ensureActive()
        // The backend binds the exposed mounts and re-validates device directories
        // (a vanished source fails the shell start).
        // HANDLE_CREATION 准入 + spawn + 登记前 epoch/validate 复查全部由 gate.createHandle
        // 提供（MODEL_BASH + {bash}：bash 工具开关与结构变更都波及它，其他工具开关不动它）；
        // spawn 在封禁期被波及会收到 victim 取消，spawn 完到登记之间的窗口由 gate 兜底收尾。
        val handle = workspaceRepository.runtimeGate.createHandle(
            workspaceId = workspaceId,
            handleClass = WorkspaceHandleClass.MODEL_BASH,
            stableIds = setOf(BuiltinToolIds.BASH),
            expectedViewEpoch = viewEpoch,
            expectedToolEpochs = mapOf(BuiltinToolIds.BASH to toolEpoch),
            close = { s: LiveSession -> s.close() },
            awaitClosed = { s: LiveSession ->
                if (!s.pty.awaitClosed(SESSION_CLOSE_AWAIT_MS)) {
                    throw IllegalStateException(
                        "workspace bash session did not exit within ${SESSION_CLOSE_AWAIT_MS}ms"
                    )
                }
            },
            spawn = {
                val pty = workspaceRepository.openShell(workspaceId, me.rerere.workspace.WorkspaceShellRequest(
                    rows = 24,
                    columns = 80,
                    patchOptions = me.rerere.workspace.RootfsPatchOptionsSpec(nameservers = appContext.activeDnsServers()),
                ), filesScope = filesScope)
                LiveSession(workspaceId = workspaceId, key = key, pty = pty)
            },
        )
        val session = handle.value.also { it.gateToken = handle.token }
        try {
            session.write(STTY_DISABLE_ECHO)
            delay(PERSISTENT_BASH_INIT_SETTLE_MS)
            session.clearScrollback()
            currentCoroutineContext().ensureActive()
            synchronized(lifecycleLock) {
                // 登记后仍可能被并发 publish 退役/封禁：isHandleFresh=false 时不能把
                // 一条已被 gate 判死的 session 发布进活跃表
                if (lifecycleRevisions.getValue(workspaceId).get() != revision ||
                    workspaceRepository.runtimeGate.currentToolEpoch(workspaceId, BuiltinToolIds.BASH) != toolEpoch ||
                    workspaceRepository.runtimeGate.currentViewEpoch(workspaceId) != viewEpoch ||
                    !workspaceRepository.runtimeGate.isHandleFresh(handle.token)
                ) throw WorkspacePolicyCancellationException(workspaceId)
                sessions[key] = session
            }
            return session
        } catch (error: Throwable) {
            // 孤儿收尾也要有界等待真退出（releaseHandle 内部串行门幂等），失败 suppressed
            withContext(NonCancellable) {
                runCatching { retireSession(session) }.onFailure { error.addSuppressed(it) }
            }
            throw error
        }
    }

    private suspend fun runCommand(
        session: LiveSession,
        command: String,
        timeoutMs: Long,
        maxOutputChars: Int,
    ): String {
        val marker = persistentBashMarkers()
        val wrapped = wrapPersistentBashCommand(command, marker)
        session.clearScrollback()
        val waiter = session.beginCapture(marker.end)
        try {
            session.write("$wrapped\n")
            val completed = withTimeoutOrNull(timeoutMs) { waiter.await() } != null

            var snapshot = session.snapshot()
            var captured = commandOutputFromScrollback(snapshot.text, marker)
            if (captured == null && completed && !session.isEof) {
                // end marker 已出现但结尾的 exit code 可能还差几个字节, 短暂等待后重解析
                withTimeoutOrNull(MARKER_STATUS_GRACE_MS) {
                    while (captured == null && !session.isEof) {
                        delay(10)
                        snapshot = session.snapshot()
                        captured = commandOutputFromScrollback(snapshot.text, marker)
                    }
                }
            }
            captured?.let { return renderPersistentBashCaptured(it, maxOutputChars) }

            if (session.isEof) {
                // PTY EOF 时 waitFor 线程可能还没回填 exitCode, 留一点宽限
                withTimeoutOrNull(PTY_EXIT_GRACE_MS) {
                    while (session.pty.exitCode == null) delay(25)
                }
                val partial = partialOutputFromScrollback(
                    text = snapshot.text,
                    marker = marker,
                    fallback = snapshot.text,
                    fallbackTruncated = snapshot.truncated,
                )
                val rendered = renderPersistentBashShellExit(
                    renderPersistentBashCaptured(partial, maxOutputChars),
                    session.pty.exitCode,
                )
                reset(session)
                return listOf(rendered, SHELL_RESET_MESSAGE).filter { it.isNotEmpty() }.joinToString("\n")
            }

            val partial = renderPersistentBashCaptured(
                partialOutputFromScrollback(
                    text = snapshot.text,
                    marker = marker,
                    fallback = snapshot.text,
                    fallbackTruncated = snapshot.truncated,
                ),
                maxOutputChars,
            )
            reset(session)
            return renderPersistentBashTimeout(partial, timeoutMs)
        } catch (cancelled: CancellationException) {
            // A cancelled command can have partial side effects; never reuse its shell.
            runCatching { reset(session) }.onFailure { cancelled.addSuppressed(it) }
            throw cancelled
        } finally {
            session.endCapture()
        }
    }

    private fun reset(session: LiveSession) {
        dropSession(session)
    }

    private class CaptureRequest(
        val endMarker: String,
        val result: CompletableDeferred<Unit>,
    )

    private class LiveSession(
        val workspaceId: String,
        /** 会话表键（[persistentBashSessionKey]）：工作区 + 可选所属者。 */
        val key: String,
        val pty: me.rerere.workspace.WorkspacePty,
    ) {
        /** gate 登记的 handle token：victim 清理/releaseHandle 共用这个身份，
         *  正常关闭路径统一走 releaseHandle（close+awaitClosed 串行门）。 */
        var gateToken: WorkspaceHandleToken? = null
        private val lock = Any()
        private val closed = AtomicBoolean(false)
        private val scrollback = StringBuilder()
        private var truncated: Boolean = false
        private var eof: Boolean = false
        private var pendingCapture: CaptureRequest? = null

        init {
            thread(name = "workspace-persistent-bash-$workspaceId", isDaemon = true) {
                // 增量 UTF-8 解码: read chunk 边界可能切开多字节字符, 逐块 new String 会产生乱码
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                val buffer = ByteArray(PERSISTENT_BASH_READ_BUFFER_BYTES)
                val chars = CharBuffer.allocate(PERSISTENT_BASH_READ_BUFFER_BYTES)
                while (!closed.get()) {
                    val n = pty.read(buffer)
                    if (n <= 0) break
                    chars.clear()
                    decoder.decode(ByteBuffer.wrap(buffer, 0, n), chars, false)
                    chars.flip()
                    if (chars.remaining() <= 0) continue
                    synchronized(lock) {
                        appendLocked(chars.toString())
                        checkPendingLocked()
                    }
                }
                synchronized(lock) {
                    eof = true
                    checkPendingLocked()
                }
            }
        }

        val isEof: Boolean
            get() = synchronized(lock) { eof }

        suspend fun write(text: String) {
            pty.write(text.toByteArray(Charsets.UTF_8))
        }

        fun clearScrollback() {
            synchronized(lock) {
                scrollback.setLength(0)
                truncated = false
            }
        }

        fun snapshot(): Snapshot = synchronized(lock) {
            Snapshot(text = scrollback.toString(), truncated = truncated)
        }

        /**
         * 注册一次性的 end-marker 等待: reader 线程在 scrollback 中检测到 marker 或到达 EOF 时
         * complete 返回的 deferred, 调用方据此 await, 不再轮询 snapshot
         */
        fun beginCapture(endMarker: String): CompletableDeferred<Unit> {
            val deferred = CompletableDeferred<Unit>()
            synchronized(lock) {
                pendingCapture = CaptureRequest(endMarker, deferred)
                checkPendingLocked()
            }
            return deferred
        }

        fun endCapture() {
            synchronized(lock) {
                pendingCapture = null
            }
        }

        private fun checkPendingLocked() {
            val request = pendingCapture ?: return
            if (eof || scrollback.indexOf(request.endMarker) >= 0) {
                request.result.complete(Unit)
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) {
                eof = true
                pendingCapture?.result?.complete(Unit)
            }
            pty.close()
        }

        private fun appendLocked(chunk: String) {
            scrollback.append(chunk)
            val limit = PERSISTENT_BASH_MAX_OUTPUT_CHARS * 4
            if (scrollback.length > limit) {
                truncated = true
                scrollback.delete(0, scrollback.length - PERSISTENT_BASH_MAX_OUTPUT_CHARS * 2)
            }
        }

        data class Snapshot(
            val text: String,
            val truncated: Boolean,
        )
    }
}
