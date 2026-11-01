package me.rerere.rikkihub.data.workspace

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage

private const val TAG = "RootfsInstallService"

private const val ROOTFS_URL_TEMPLATE =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-%s.tar.gz"

/**
 * Ubuntu base image for the ABI Android installed this APK's native libraries for: the first
 * supported ABI the APK ships (arm64-v8a or x86_64). PRoot and the rootfs must share it, so
 * x86_64 devices (emulators, ChromeOS) need the amd64 image.
 */
fun defaultRootfsUrl(supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()): String {
    val arch = when (supportedAbis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }) {
        "x86_64" -> "amd64"
        else -> "arm64"
    }
    return ROOTFS_URL_TEMPLATE.format(arch)
}

/** One workspace's install, as both the App page and the Web page see it. */
@Serializable
data class RootfsInstallState(
    val workspaceId: String,
    /** `installing` or `failed`; a finished install simply disappears (the workspace turns READY). */
    val status: String,
    val url: String,
    val stage: RootfsInstallStage? = null,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
    val error: String? = null,
) {
    val installing: Boolean get() = status == STATUS_INSTALLING

    fun toProgress(): RootfsInstallProgress? = stage?.let {
        RootfsInstallProgress(
            stage = it,
            bytesRead = bytesRead,
            totalBytes = totalBytes,
            entriesExtracted = entriesExtracted,
            currentEntry = currentEntry,
        )
    }

    companion object {
        const val STATUS_INSTALLING = "installing"
        const val STATUS_FAILED = "failed"
    }
}

class RootfsInstallAlreadyRunningException : IllegalStateException("Rootfs install already running")

/**
 * Service-level rootfs installs shared by the App workspace page and the Web API: the
 * download/extract keeps running when the page is left, both sides watch the same [states], and
 * either side can cancel. Before installing it closes the workspace's App terminals, Web
 * terminals and persistent bash ([closeSessions]); [WorkspaceRepository.installRootfs] already
 * marks the workspace INSTALLING, restores it on cancel and marks it BROKEN on failure.
 */
class WorkspaceRootfsInstallService(
    private val repository: WorkspaceRepository,
    private val scope: CoroutineScope,
    private val closeSessions: suspend (workspaceId: String, root: String) -> Unit,
) {
    private val _states = MutableStateFlow<Map<String, RootfsInstallState>>(emptyMap())
    val states: StateFlow<Map<String, RootfsInstallState>> = _states.asStateFlow()

    private val jobs = HashMap<String, Job>()

    fun state(workspaceId: String): RootfsInstallState? = _states.value[workspaceId]

    /** Starts an install; throws [RootfsInstallAlreadyRunningException] when one is in flight. */
    suspend fun start(workspaceId: String, url: String = defaultRootfsUrl()): RootfsInstallState {
        val workspace = repository.getById(workspaceId) ?: throw NoSuchElementException("Workspace not found: $workspaceId")
        val initial = RootfsInstallState(
            workspaceId = workspaceId,
            status = RootfsInstallState.STATUS_INSTALLING,
            url = url,
            stage = RootfsInstallStage.DOWNLOADING,
        )
        synchronized(jobs) {
            if (jobs[workspaceId]?.isActive == true) throw RootfsInstallAlreadyRunningException()
            _states.update { it + (workspaceId to initial) }
            jobs[workspaceId] = scope.launch(Dispatchers.IO) {
                try {
                    closeSessions(workspaceId, workspace.root)
                    repository.installRootfs(workspaceId, url) { progress ->
                        _states.update { states ->
                            val current = states[workspaceId] ?: return@update states
                            states + (workspaceId to current.copy(
                                stage = progress.stage,
                                bytesRead = progress.bytesRead,
                                totalBytes = progress.totalBytes,
                                entriesExtracted = progress.entriesExtracted,
                                currentEntry = progress.currentEntry,
                            ))
                        }
                    }
                    _states.update { it - workspaceId }
                } catch (cancelled: CancellationException) {
                    _states.update { it - workspaceId }
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "rootfs install failed for $workspaceId", error)
                    _states.update { states ->
                        states + (workspaceId to initial.copy(
                            status = RootfsInstallState.STATUS_FAILED,
                            stage = null,
                            error = error.message ?: error::class.java.simpleName,
                        ))
                    }
                } finally {
                    synchronized(jobs) {
                        if (jobs[workspaceId] == coroutineContext[Job]) jobs.remove(workspaceId)
                    }
                }
            }
        }
        return initial
    }

    /** Cancels a running install (the workspace returns to its previous shell status). */
    fun cancel(workspaceId: String): Boolean = synchronized(jobs) {
        val job = jobs[workspaceId]?.takeIf { it.isActive } ?: return false
        job.cancel(CancellationException("Rootfs install cancelled"))
        true
    }

    /** Clears a failed state once the user has seen it. */
    fun dismiss(workspaceId: String) {
        _states.update { states ->
            if (states[workspaceId]?.installing == true) states else states - workspaceId
        }
    }
}
