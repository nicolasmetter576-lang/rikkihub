package me.rerere.rikkihub.data.workspace

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceSubprocessRequest
import java.io.InputStream
import java.util.TimeZone

private const val TAG = "WorkspaceEnvSetup"
private const val LOG_LINES = 200
private const val READER_DRAIN_MS = 5_000L
private const val PREF_MIRROR = "workspace_env_mirror"

/** One workspace's setup run as the App sees it; a dismissed or replaced run disappears. */
data class WorkspaceEnvSetupState(
    val workspaceId: String,
    val status: Status,
    val items: Set<WorkspaceEnvItem>,
    val mirror: WorkspaceEnvMirror,
    /** Last `::step` id the script printed. */
    val step: String? = null,
    val log: List<String> = emptyList(),
    val error: String? = null,
) {
    enum class Status { WAITING_ROOTFS, RUNNING, SUCCEEDED, FAILED }

    val active: Boolean get() = status == Status.WAITING_ROOTFS || status == Status.RUNNING
}

class WorkspaceEnvSetupAlreadyRunningException : IllegalStateException("Environment setup already running")

/**
 * Service-level one-click environment setup: runs [WorkspaceEnvSetup.setupScript] in the workspace
 * rootfs and keeps going when the page is left. The process must be held here: a terminal session
 * takes its background jobs with it when it closes. [installRootfsThenSetup] chains a rootfs install
 * first (new-workspace checkbox); a rootfs install started elsewhere stops a running setup through
 * [cancelRunning].
 */
class WorkspaceEnvSetupService(
    private val repository: WorkspaceRepository,
    private val rootfsInstallService: WorkspaceRootfsInstallService,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
) {
    private val _states = MutableStateFlow<Map<String, WorkspaceEnvSetupState>>(emptyMap())
    val states: StateFlow<Map<String, WorkspaceEnvSetupState>> = _states.asStateFlow()

    private val jobs = HashMap<String, Job>()

    /** The source picked last time, the default for the next setup. */
    var lastMirror: WorkspaceEnvMirror
        get() = prefs.getString(PREF_MIRROR, null)
            ?.let { name -> WorkspaceEnvMirror.entries.firstOrNull { it.name == name } }
            ?: WorkspaceEnvMirror.OFFICIAL
        set(value) = prefs.edit { putString(PREF_MIRROR, value.name) }

    /** Starts a setup on a READY workspace; throws [WorkspaceEnvSetupAlreadyRunningException] when one is in flight. */
    fun start(workspaceId: String, items: Set<WorkspaceEnvItem>, mirror: WorkspaceEnvMirror) {
        launchRun(workspaceId, items, mirror, WorkspaceEnvSetupState.Status.RUNNING) {
            runSetup(workspaceId, items, mirror)
        }
    }

    /** Installs the rootfs from [url], then runs the setup if the install ended with a READY rootfs. */
    fun installRootfsThenSetup(workspaceId: String, url: String, items: Set<WorkspaceEnvItem>, mirror: WorkspaceEnvMirror) {
        launchRun(workspaceId, items, mirror, WorkspaceEnvSetupState.Status.WAITING_ROOTFS) {
            rootfsInstallService.dismiss(workspaceId)
            rootfsInstallService.start(workspaceId, url)
            val installState = rootfsInstallService.states.first { it[workspaceId]?.installing != true }[workspaceId]
            // A failed install shows its own error; a cancelled one simply ends the chain.
            if (installState?.status == RootfsInstallState.STATUS_FAILED || !repository.rootfsReady(workspaceId)) {
                _states.update { it - workspaceId }
                return@launchRun
            }
            update(workspaceId) { it.copy(status = WorkspaceEnvSetupState.Status.RUNNING) }
            runSetup(workspaceId, items, mirror)
        }
    }

    /** Cancels the run; while it still waits for its rootfs, the install is cancelled too. */
    fun cancel(workspaceId: String): Boolean = synchronized(jobs) {
        val job = jobs[workspaceId]?.takeIf { it.isActive } ?: return false
        if (_states.value[workspaceId]?.status == WorkspaceEnvSetupState.Status.WAITING_ROOTFS) {
            rootfsInstallService.cancel(workspaceId)
        }
        job.cancel(CancellationException("Environment setup cancelled"))
        true
    }

    /** Stops a setup whose script is running (not one still waiting for its own rootfs install). */
    fun cancelRunning(workspaceId: String) {
        if (_states.value[workspaceId]?.status == WorkspaceEnvSetupState.Status.RUNNING) cancel(workspaceId)
    }

    /** Clears a finished run once the user has seen it. */
    fun dismiss(workspaceId: String) {
        _states.update { states -> if (states[workspaceId]?.active == true) states else states - workspaceId }
    }

    /** What the READY workspace has now (one short PRoot command). */
    suspend fun check(workspaceId: String): WorkspaceEnvStatus {
        val result = repository.executeCommand(workspaceId, WorkspaceEnvSetup.checkScript(), timeoutMillis = 30_000)
        return WorkspaceEnvSetup.parseCheck(result.stdout)
    }

    private fun launchRun(
        workspaceId: String,
        items: Set<WorkspaceEnvItem>,
        mirror: WorkspaceEnvMirror,
        status: WorkspaceEnvSetupState.Status,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        lastMirror = mirror
        val initial = WorkspaceEnvSetupState(workspaceId, status, WorkspaceEnvSetup.withRequirements(items), mirror)
        synchronized(jobs) {
            if (jobs[workspaceId]?.isActive == true) throw WorkspaceEnvSetupAlreadyRunningException()
            _states.update { it + (workspaceId to initial) }
            jobs[workspaceId] = scope.launch(Dispatchers.IO) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    _states.update { it - workspaceId }
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "environment setup failed for $workspaceId", error)
                    update(workspaceId) {
                        it.copy(status = WorkspaceEnvSetupState.Status.FAILED, error = error.message ?: error::class.java.simpleName)
                    }
                } finally {
                    synchronized(jobs) {
                        if (jobs[workspaceId] == coroutineContext[Job]) jobs.remove(workspaceId)
                    }
                }
            }
        }
    }

    private suspend fun runSetup(workspaceId: String, items: Set<WorkspaceEnvItem>, mirror: WorkspaceEnvMirror) {
        val script = WorkspaceEnvSetup.setupScript(items, mirror, TimeZone.getDefault().id)
        val process = repository.openSubprocess(
            workspaceId,
            WorkspaceSubprocessRequest(command = listOf("bash", "-c", script), cwd = "/root"),
        )
        // The readers block on the pipes, so they are not children of this job: cancelling must reach
        // the finally (close kills PRoot and its tracees) instead of waiting for them.
        val readers = listOf(process.stdout, process.stderr).map { stream ->
            scope.launch(Dispatchers.IO) { readLines(workspaceId, stream) }
        }
        try {
            runCatching { process.stdin.close() }
            val exitCode = process.awaitExit()
            // Pipes reach EOF once the script is gone; don't hang on a descendant that kept them open.
            withTimeoutOrNull(READER_DRAIN_MS) { readers.joinAll() }
            if (exitCode != 0) {
                val step = _states.value[workspaceId]?.step
                error("Exit code $exitCode" + (step?.let { " ($it)" }.orEmpty()))
            }
            update(workspaceId) { it.copy(status = WorkspaceEnvSetupState.Status.SUCCEEDED) }
        } finally {
            process.close()
        }
    }

    private fun readLines(workspaceId: String, stream: InputStream) {
        // Ends when the process exits or close() tears the pipes down.
        runCatching {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    // apt redraws progress with carriage returns; keep what the line finally shows
                    val line = raw.substringAfterLast('\r').trimEnd()
                    if (line.isEmpty()) return@forEach
                    val step = WorkspaceEnvSetup.stepOf(line)
                    update(workspaceId) { state ->
                        if (step != null) state.copy(step = step) else state.copy(log = (state.log + line).takeLast(LOG_LINES))
                    }
                }
            }
        }
    }

    private fun update(workspaceId: String, transform: (WorkspaceEnvSetupState) -> WorkspaceEnvSetupState) {
        _states.update { states ->
            val current = states[workspaceId] ?: return@update states
            states + (workspaceId to transform(current))
        }
    }
}
