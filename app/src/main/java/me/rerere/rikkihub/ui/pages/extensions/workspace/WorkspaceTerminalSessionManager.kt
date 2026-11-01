package me.rerere.rikkihub.ui.pages.extensions.workspace

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceGateCleanupException
import me.rerere.rikkihub.terminal.RemotePtyNative
import me.rerere.rikkihub.utils.activeDnsServers
import me.rerere.workspace.RootfsPatchOptionsSpec
import me.rerere.workspace.WorkspacePendingCloses
import me.rerere.workspace.WorkspacePty
import me.rerere.workspace.WorkspaceShellRequest
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 *
 * Terminology note: this manager is keyed by workspace **root** (the filesystem dir name,
 * which today equals the workspace id created by WorkspaceRepository.create).
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val workspaceRepository: WorkspaceRepository? = null,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()
    private val lifecycleRevisions = mutableMapOf<String, Long>()
    private val closingRoots = mutableSetOf<String>()
    // 已请求 finish、libtermux 唯一 waiter 尚未回调证明退出的 tab：suspend closeWorkspace
    // 的 join 对象来源 —— 从可见状态移除后绝不能丢掉它们
    private val pendingClosing = WorkspacePendingCloses<WorkspaceTerminalTab>()

    init {
        workspaceRepository?.sessionInvalidation?.register("app-terminal") { _, root ->
            closeWorkspace(root)
        }
    }

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = true, shellCompatibilityMode = shellCompatibilityMode)
    }

    internal fun createTab(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = false, shellCompatibilityMode = shellCompatibilityMode)
    }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // Remove it from observable state before finishing so the finish callback cannot put it
        // back into the UI while the selected TerminalView is being disposed.
        closedTab?.let { tab ->
            tab.client.terminalView = null
            pendingClosing.record(tab)
            requestStop(tab)
            appScope.launch {
                // drain：native waiter 回调证明退出后才把记录出 pendingClosing
                runCatching { tab.client.awaitFinished() }
                pendingClosing.complete(tab)
            }
        }
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     * Runs on Main but never blocks it: request finish, then suspend-await the
     * libtermux onSessionFinished callbacks (unique native waiter — no waitpid here).
     * A tab that does not report exit within TERMINAL_CLOSE_AWAIT_MS fails loud:
     * the publish/delete caller must not proceed as if the process was gone.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        // Wait for rootfs preparation to leave its IO section before callers delete or replace the
        // same files. CancellationException is deliberately rethrown by createTab().
        closingRoots += root
        lifecycleRevisions[root] = (lifecycleRevisions[root] ?: 0L) + 1L
        try {
            creationJobs[root]?.cancelAndJoin()
            val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            val closing = ((state?.tabs ?: emptyList()) +
                pendingClosing.pending().filter { it.root == root }).distinct()
            closing.forEach { tab ->
                tab.client.terminalView = null
                pendingClosing.record(tab)
                requestStop(tab)
            }
            val failures = mutableListOf<Throwable>()
            for (tab in closing) {
                runCatching {
                    if (!tab.client.awaitClosed(TERMINAL_CLOSE_AWAIT_MS)) {
                        throw IllegalStateException(
                            "terminal tab ${tab.id} did not exit within ${TERMINAL_CLOSE_AWAIT_MS}ms"
                        )
                    }
                }.onSuccess { pendingClosing.complete(tab) }
                    .onFailure { failures += it }
            }
            if (failures.isNotEmpty()) {
                throw WorkspaceGateCleanupException(
                    workspaceId = root,
                    pendingHandles = closing.size,
                    causes = failures,
                )
            }
        } finally {
            closingRoots -= root
        }
    }

    private fun launchCreateTab(root: String, onlyIfEmpty: Boolean, shellCompatibilityMode: Boolean) {
        if (root in creationJobs || root in closingRoots) return

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                createTab(root = root, onlyIfEmpty = onlyIfEmpty, shellCompatibilityMode = shellCompatibilityMode)
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(root: String, onlyIfEmpty: Boolean, shellCompatibilityMode: Boolean) = withContext(Dispatchers.Main.immediate) {
        if (root in closingRoots) return@withContext
        val revision = lifecycleRevisions[root] ?: 0L
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true) }

        // Resolve the workspace row so mounts come from repository state, not a hardcoded list.
        // root==workspace id in this app (create() uses id as root), but go through the repo
        // list/getById so a renamed/missing workspace fails with a clear status instead of
        // silently opening a bare rootfs session.
        val workspace = workspaceRepository?.let { repo ->
            repo.list().firstOrNull { it.root == root } ?: repo.getById(root)
        }
        if (workspaceRepository != null && workspace == null) {
            Log.w(TAG, "No workspace row for root $root; refusing terminal creation")
            updateState(root) {
                it.copy(
                    readiness = WorkspaceTerminalReadiness.Unavailable,
                    isCreating = false,
                )
            }
            return@withContext
        }

        val viewEpoch = workspace?.let { workspaceRepository?.runtimeGate?.currentViewEpoch(it.id) }
        val gateChanged: () -> Boolean = {
            workspace != null && workspaceRepository?.runtimeGate?.let { gate ->
                val status = gate.snapshot(workspace.id)
                status.sealed || status.closed || status.quarantined || gate.currentViewEpoch(workspace.id) != viewEpoch
            } == true
        }

        if (workspaceRepository == null || workspace == null) {
            updateState(root) { it.copy(readiness = WorkspaceTerminalReadiness.Unavailable, isCreating = false) }
            return@withContext
        }

        val prepared = if (initialState.readiness == WorkspaceTerminalReadiness.Ready) {
            true
        } else {
            try {
                workspaceRepository.rootfsReady(workspace.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to check rootfs for workspace $root", error)
                null
            }
        }

        if (prepared != true) {
            updateState(root) {
                it.copy(
                    // null = rootfs check failed: not the same as "rootfs not installed"
                    readiness = if (prepared == null) WorkspaceTerminalReadiness.Unavailable
                        else WorkspaceTerminalReadiness.NotInstalled,
                    isCreating = false,
                )
            }
            return@withContext
        }

        currentCoroutineContext().ensureActive()
        if (root in closingRoots || (lifecycleRevisions[root] ?: 0L) != revision || gateChanged()) {
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }
        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        val client = WorkspaceTerminalSessionClient(
            context = appContext,
            onTitleUpdated = { title -> updateTitle(root, tabId, title) },
            onFinished = { markFinished(root = root, tabId = tabId) },
        )

        // Mounts (incl. device directories) are resolved and validated by the repository at
        // open time; any failure refuses the session instead of opening a shell that silently
        // lost /device/<alias>.
        val pty = try {
            workspaceRepository.openShell(
                workspace.id,
                WorkspaceShellRequest(
                    patchOptions = RootfsPatchOptionsSpec(nameservers = appContext.activeDnsServers()),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to open shell for ${workspace.id}", error)
            updateState(root) {
                it.copy(readiness = WorkspaceTerminalReadiness.Unavailable, isCreating = false)
            }
            return@withContext
        }

        val created = runCatching {
            createWorkspaceTerminalSession(pty = pty, client = client, root = root, scope = appScope)
        }.onFailure { error ->
            Log.e(TAG, "Failed to create terminal for workspace $root", error)
        }.getOrNull()

        if (created == null) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { pty.close() } }
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }
        val (session, token) = created

        try {
            currentCoroutineContext().ensureActive()
            check(root !in closingRoots && (lifecycleRevisions[root] ?: 0L) == revision && !gateChanged()) {
                "Workspace terminal creation became stale"
            }
        } catch (failure: Throwable) {
            val orphan = WorkspaceTerminalTab(tabId, tabNumber, session, client, root, pty = pty, token = token)
            pendingClosing.record(orphan)
            requestStop(orphan)
            // 创建作废的孤儿也有界等待真退出才返回：不等就上报会让 delete/重装
            // 在进程还活着时动 rootfs。等待在 NonCancellable 内，永不死等。
            withContext(NonCancellable) {
                if (!client.awaitClosed(TERMINAL_CLOSE_AWAIT_MS)) {
                    failure.addSuppressed(
                        IllegalStateException(
                            "orphan terminal session did not exit within ${TERMINAL_CLOSE_AWAIT_MS}ms"
                        )
                    )
                } else {
                    pendingClosing.complete(orphan)
                }
            }
            throw failure
        }
        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
            root = root,
            pty = pty,
            token = token,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                selectedTabId = tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
    }

    /**
     * Kills the shell through its PTY session; the observed exit then unblocks libtermux's
     * waiter and the exit barrier.
     */
    private fun requestStop(tab: WorkspaceTerminalTab) {
        tab.session.finishIfRunning()
        appScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { tab.pty?.close() }
                .onFailure { Log.w(TAG, "Failed to close PTY for tab ${tab.id}", it) }
            // Unclaimed registration (view never attached): drop the fd. The exit watcher
            // started with the session signals the barrier once the shell exits.
            tab.token?.let { token -> runCatching { RemotePtyNative.forget(token) } }
        }
    }

    private fun updateTitle(root: String, tabId: Long, title: String?) {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(title = normalizedTitle) else tab
                },
            ))
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"
    }
}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val root: String = "",
    val title: String? = null,
    val finished: Boolean = false,
    /** The shell's PTY session; closing it kills the shell. */
    val pty: WorkspacePty? = null,
    val token: String? = null,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
    /**
     * Workspace row missing, shell start failed, or device source validation failed —
     * the manager refuses to spawn rather than open a shell with silently dropped mounts.
     */
    Unavailable,
}
