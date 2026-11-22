package me.rerere.rikkihub.data.cli

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceHandleClass
import me.rerere.rikkihub.data.workspace.WorkspaceHandleToken
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.utils.activeDnsServers
import me.rerere.workspace.RootfsPatchOptionsSpec
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceSubprocess
import me.rerere.workspace.WorkspaceSubprocessRequest
import kotlin.uuid.Uuid

/** Everything needed to run (or reuse) the agent process of one conversation. */
data class CliLaunch(
    val conversationId: Uuid,
    val agent: CliAgent,
    val workspaceId: String,
    /** FILES directory the conversation is confined to; null = the whole workspace. */
    val filesScope: String?,
    /** Guest working directory. */
    val cwd: String,
    /** Assistant prompt for agents that take it as a file ([CliAgent.usesPromptFile]); its hash keys the process. */
    val promptFileText: String?,
)

/** Another conversation's CLI is running a turn; only one agent process is kept (memory). */
class CliBusyException(val conversationId: Uuid) : IllegalStateException("Another CLI conversation is running")

/** The agent's executable is missing in the workspace (exit 127). */
class CliNotInstalledException(val agent: CliAgent, detail: String) :
    IllegalStateException("${agent.executable} is not installed in this workspace: $detail")

/** How the user answered a CLI permission card. */
sealed interface CliApprovalDecision {
    data class Approve(val always: Boolean = false) : CliApprovalDecision
    data class Deny(val reason: String = "") : CliApprovalDecision
}

/** The live agent process as the UI sees it. */
data class CliProcessState(
    val conversationId: Uuid,
    val agentId: String,
    val agent: AcpAgentInfo?,
    val runtime: StateFlow<AcpRuntimeInfo>,
    val busy: Boolean,
)

/**
 * Owns the workspace CLI agent processes: at most one at a time ([withAgent] reuses it for the
 * same conversation and settings, retires an idle one for another conversation, refuses while
 * another conversation's turn runs). Processes are gate handles of class
 * [WorkspaceHandleClass.CLI_SESSION]: structural workspace changes close them; tool toggles do not.
 * Idle processes go after [IDLE_TIMEOUT_MS] or when the system trims memory; the next turn
 * resumes the saved ACP session.
 */
class AcpSessionManager(
    context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    class Live internal constructor(
        val launch: CliLaunch,
        val session: AcpSession,
        internal val process: WorkspaceSubprocess,
        internal val promptFile: String?,
    ) {
        internal var token: WorkspaceHandleToken? = null
        @Volatile internal var busy = false
        @Volatile internal var lastUsedAt = System.currentTimeMillis()
    }

    private class PendingStart(
        val launch: CliLaunch,
        @Volatile var cancelled: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var live: Live? = null
    private var starting: PendingStart? = null
    private var idleJob: Job? = null
    private val pendingApprovals = java.util.concurrent.ConcurrentHashMap<Pair<Uuid, String>, CompletableDeferred<CliApprovalDecision>>()

    private val _state = MutableStateFlow<CliProcessState?>(null)
    val state: StateFlow<CliProcessState?> = _state.asStateFlow()

    init {
        // No legacy session closer: structural changes reach the process as a gate victim (its handle),
        // and a closer taking [mutex] could wait on a start that waits on the same sealed gate.
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) scope.launch { retireIdle(0L) }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                scope.launch { retireIdle(0L) }
            }
        })
    }

    /**
     * Runs [block] with the conversation's agent process, started and initialized if needed,
     * inside a gate GENERATION operation so workspace changes wait for (or cancel) the turn.
     */
    suspend fun <T> withAgent(launch: CliLaunch, block: suspend (Live) -> T): T {
        var pending: PendingStart? = null
        val reused = mutex.withLock {
            val existing = live
            if (existing != null && reusable(existing, launch)) {
                existing.busy = true
                publishState()
                return@withLock existing
            }
            if (existing != null) {
                if (existing.busy && existing.launch.conversationId != launch.conversationId) {
                    throw CliBusyException(existing.launch.conversationId)
                }
                retireLocked(existing)
            }
            starting?.let { active ->
                if (active.launch.conversationId != launch.conversationId) {
                    throw CliBusyException(active.launch.conversationId)
                }
                active.cancelled = true
            }
            pending = PendingStart(launch).also { starting = it }
            null
        }
        val current = reused ?: run {
            val started = try {
                start(launch)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (starting === pending) starting = null
                    }
                }
                throw error
            }
            val registered = mutex.withLock {
                if (starting === pending) starting = null
                if (pending!!.cancelled || live != null) {
                    false
                } else {
                    started.busy = true
                    live = started
                    publishState()
                    true
                }
            }
            if (!registered) {
                withContext(NonCancellable) { retireLocked(started) }
                throw CancellationException("CLI session closed while starting")
            }
            started
        }
        idleJob?.cancel()
        try {
            return workspaceRepository.runtimeGate.withOperation(
                workspaceId = launch.workspaceId,
                kind = WorkspaceOperationKind.GENERATION,
                handleClass = WorkspaceHandleClass.CLI_SESSION,
            ) { block(current) }
        } finally {
            current.busy = false
            current.lastUsedAt = System.currentTimeMillis()
            publishState()
            idleJob = scope.launch {
                delay(IDLE_TIMEOUT_MS)
                retireIdle(IDLE_TIMEOUT_MS)
            }
        }
    }

    /** The conversation's agent session while its process is alive. */
    fun liveSession(conversationId: Uuid): AcpSession? =
        live?.takeIf { it.launch.conversationId == conversationId && !it.session.isClosed }?.session

    fun isRunning(conversationId: Uuid): Boolean = live?.let { it.launch.conversationId == conversationId && it.busy } == true

    /** Registers the wait for the user's answer to a permission card; see [tryDeliverPermissionDecision]. */
    fun awaitApproval(conversationId: Uuid, toolCallId: String): CompletableDeferred<CliApprovalDecision> =
        CompletableDeferred<CliApprovalDecision>().also { pendingApprovals[conversationId to toolCallId] = it }

    fun dropApproval(conversationId: Uuid, toolCallId: String) {
        pendingApprovals.remove(conversationId to toolCallId)
    }

    /** Wakes the turn waiting on this card. False = no turn is waiting (process gone, app restarted). */
    fun tryDeliverPermissionDecision(conversationId: Uuid, toolCallId: String, decision: CliApprovalDecision): Boolean =
        pendingApprovals.remove(conversationId to toolCallId)?.complete(decision) == true

    /** Closes the conversation's process (restart, CLI switched off, conversation deleted). */
    suspend fun closeConversation(conversationId: Uuid) = mutex.withLock {
        starting?.takeIf { it.launch.conversationId == conversationId }?.cancelled = true
        live?.takeIf { it.launch.conversationId == conversationId }?.let { retireLocked(it) }
    }

    suspend fun closeWorkspace(workspaceId: String) = mutex.withLock {
        starting?.takeIf { it.launch.workspaceId == workspaceId }?.cancelled = true
        live?.takeIf { it.launch.workspaceId == workspaceId }?.let { retireLocked(it) }
    }

    private fun reusable(existing: Live, launch: CliLaunch): Boolean {
        val token = existing.token
        return existing.launch == launch && !existing.session.isClosed && existing.process.isAlive &&
            token != null && workspaceRepository.runtimeGate.isHandleFresh(token)
    }

    private suspend fun retireIdle(minIdleMs: Long) = mutex.withLock {
        val existing = live ?: return@withLock
        if (!existing.busy && System.currentTimeMillis() - existing.lastUsedAt >= minIdleMs) retireLocked(existing)
    }

    private suspend fun retireLocked(existing: Live) {
        if (live === existing) {
            live = null
            publishState()
        }
        withContext(NonCancellable) {
            val token = existing.token
            runCatching {
                if (token != null) {
                    workspaceRepository.runtimeGate.releaseHandle(token)
                } else {
                    existing.session.close()
                    existing.process.awaitClosed(CLOSE_AWAIT_MS)
                }
            }.onFailure { Log.w(TAG, "closing CLI process failed", it) }
            existing.promptFile?.let { path ->
                runCatching {
                    workspaceRepository.deleteFile(existing.launch.workspaceId, WorkspaceStorageArea.LINUX, path.removePrefix("/"), false)
                }
            }
        }
    }

    private suspend fun start(launch: CliLaunch): Live {
        val agent = launch.agent
        val promptFile = launch.promptFileText?.let { text ->
            "/tmp/rikki-cli-prompt-${launch.conversationId}.md".also { path ->
                workspaceRepository.writeRootfsText(launch.workspaceId, path, text, overwrite = true)
            }
        }
        val args = agent.args.map { it.replace(CliAgent.PROMPT_FILE_PLACEHOLDER, promptFile.orEmpty()) }
        val env = agent.envVars.filter { CliAgent.isAllowedEnvName(it.name) }.associate { it.name to it.value }
        val handle = workspaceRepository.runtimeGate.createHandle(
            workspaceId = launch.workspaceId,
            handleClass = WorkspaceHandleClass.CLI_SESSION,
            close = { it: Live -> it.session.close() },
            awaitClosed = { it: Live ->
                check(it.process.awaitClosed(CLOSE_AWAIT_MS)) { "CLI process did not exit within ${CLOSE_AWAIT_MS}ms" }
                it.session.awaitReaders()
            },
            spawn = {
                val process = workspaceRepository.openSubprocess(
                    launch.workspaceId,
                    WorkspaceSubprocessRequest(
                        command = listOf(agent.executable) + args,
                        cwd = launch.cwd,
                        env = env,
                        patchOptions = RootfsPatchOptionsSpec(nameservers = appContext.activeDnsServers()),
                    ),
                    filesScope = launch.filesScope,
                )
                Live(
                    launch = launch,
                    session = AcpSession(process.stdout, process.stdin, process.stderr, scope, process::close),
                    process = process,
                    promptFile = promptFile,
                )
            },
        )
        val started = handle.value.also { it.token = handle.token }
        try {
            started.session.initialize()
            env.keys.firstNotNullOfOrNull { agent.apiKeyAuth[it] }
                ?.takeIf { method -> started.session.agent.authMethods.any { it.id == method } }
                ?.let { started.session.authenticate(it) }
            return started
        } catch (error: Throwable) {
            val exit = withContext(NonCancellable) {
                started.process.awaitClosed(EXIT_PROBE_MS)
                started.process.exitCode
            }
            val stderr = started.session.stderrTail().trim().takeLast(400)
            withContext(NonCancellable) { retireLocked(started) }
            if (exit == 127) throw CliNotInstalledException(agent, stderr)
            if (error is AcpClosedException && stderr.isNotEmpty()) throw AcpClosedException("${error.message}: $stderr", error)
            throw error
        }
    }

    private fun publishState() {
        val current = live
        _state.value = current?.let {
            CliProcessState(
                conversationId = it.launch.conversationId,
                agentId = it.launch.agent.id,
                agent = runCatching { it.session.agent }.getOrNull(),
                runtime = it.session.runtime,
                busy = it.busy,
            )
        }
    }

    companion object {
        private const val TAG = "AcpSessionManager"
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        private const val CLOSE_AWAIT_MS = 4_000L
        private const val EXIT_PROBE_MS = 1_500L
    }
}
