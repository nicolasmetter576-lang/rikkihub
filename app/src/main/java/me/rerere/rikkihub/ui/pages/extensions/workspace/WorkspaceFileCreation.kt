package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.io.Serializable
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.workspace.WorkspaceFileAlreadyExistsException
import me.rerere.workspace.WorkspaceFileNames
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceStorageArea

internal const val DEFAULT_NEW_FILE_NAME = "untitled"
internal const val DEFAULT_NEW_FILE_EXTENSION = "md"

/** Captured when the dialog opens, not when its confirm button is pressed. */
data class WorkspaceNewFileTarget(
    val workspaceId: String,
    val parentPath: String,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val mountId: String? = null,
) : Serializable {
    fun request(
        name: String = DEFAULT_NEW_FILE_NAME,
        extension: String = DEFAULT_NEW_FILE_EXTENSION,
    ): WorkspaceNewFileRequest {
        require(area == WorkspaceStorageArea.FILES) { "Creation is only available in FILES" }
        WorkspaceFileNames.creationName(name, extension) // Never trim or normalize either field.
        return WorkspaceNewFileRequest(this, name, extension)
    }
}

/** Both fields and the parent/identity are frozen before the ViewModel launches IO. */
data class WorkspaceNewFileRequest(
    val target: WorkspaceNewFileTarget,
    val name: String,
    val extension: String,
) {
    val isDirectory: Boolean get() = extension.isEmpty()
    val leafName: String get() = WorkspaceFileNames.creationName(name, extension)
    val path: String get() = if (target.parentPath.isEmpty()) leafName else "${target.parentPath}/$leafName"
}

enum class WorkspaceCreateError {
    INVALID_NAME, ALREADY_EXISTS, PARENT_MISSING, PERMISSION_DENIED,
    UNAVAILABLE, INVALID_PATH, FAILED, OPEN_FAILED, INTERRUPTED,
}

/** No exception messages are presented: they can contain host absolute paths. */
fun workspaceCreateError(error: Throwable): WorkspaceCreateError = when (error) {
    is WorkspaceFileAlreadyExistsException -> WorkspaceCreateError.ALREADY_EXISTS
    is java.nio.file.FileAlreadyExistsException -> WorkspaceCreateError.ALREADY_EXISTS
    is WorkspaceFileOperationException -> when (error.code) {
        WorkspaceFileOperationException.FILE_ALREADY_EXISTS -> WorkspaceCreateError.ALREADY_EXISTS
        WorkspaceFileOperationException.INVALID_FILE_NAME -> WorkspaceCreateError.INVALID_NAME
        WorkspaceFileOperationException.PARENT_NOT_FOUND -> WorkspaceCreateError.PARENT_MISSING
        else -> WorkspaceCreateError.INVALID_PATH
    }
    is AccessDeniedException, is SecurityException -> WorkspaceCreateError.PERMISSION_DENIED
    is NoSuchFileException -> WorkspaceCreateError.PARENT_MISSING
    is IllegalArgumentException -> WorkspaceCreateError.INVALID_PATH
    else -> WorkspaceCreateError.FAILED
}

data class WorkspaceCreatedFile(val token: Long, val request: WorkspaceNewFileRequest)
data class WorkspaceFileCreationState(
    val busy: Boolean = false,
    val pendingOpen: WorkspaceCreatedFile? = null,
    val error: WorkspaceCreateError? = null,
    val createdDirectoryPath: String? = null,
)

/**
 * Pure completion protocol shared by both Android browsers. Admission is synchronous
 * (before launch) so two taps cannot enqueue two creates. A StateFlow, not a lossy
 * channel, retains the open event while the UI is stopped. Consumption happens only
 * after the navigation/editor callback returns. The token makes stale collectors inert.
 * Persistence is ViewModel lifetime, not a claim of process-death delivery.
 */
class WorkspaceFileCreation {
    private val mutableState = MutableStateFlow(WorkspaceFileCreationState())
    val state = mutableState.asStateFlow()
    private var nextToken = 0L

    @Synchronized
    fun begin(request: WorkspaceNewFileRequest): Boolean {
        if (mutableState.value.busy || mutableState.value.pendingOpen != null) return false
        try {
            request.target.request(request.name, request.extension)
        } catch (_: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = WorkspaceCreateError.INVALID_NAME)
            return false
        }
        mutableState.value = WorkspaceFileCreationState(busy = true)
        return true
    }

    @Synchronized
    fun created(request: WorkspaceNewFileRequest) {
        mutableState.value = mutableState.value.copy(
            pendingOpen = if (request.isDirectory) null else WorkspaceCreatedFile(++nextToken, request),
            createdDirectoryPath = request.path.takeIf { request.isDirectory }, error = null,
        )
    }

    @Synchronized
    fun failed(error: WorkspaceCreateError) {
        mutableState.value = mutableState.value.copy(error = error)
    }

    @Synchronized
    fun finish() {
        mutableState.value = mutableState.value.copy(busy = false)
    }

    @Synchronized
    fun dismissError() {
        mutableState.value = mutableState.value.copy(error = null, createdDirectoryPath = null)
    }

    @Synchronized
    fun open(event: WorkspaceCreatedFile, open: (WorkspaceNewFileRequest) -> Unit) {
        if (mutableState.value.pendingOpen?.token != event.token) return
        try {
            open(event.request)
            mutableState.value = mutableState.value.copy(pendingOpen = null)
        } catch (error: CancellationException) {
            throw error // Not delivered; retain for the next foreground collector.
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                pendingOpen = null, error = WorkspaceCreateError.OPEN_FAILED,
            )
        }
    }

    @Synchronized
    fun openFailed(event: WorkspaceCreatedFile) {
        if (mutableState.value.pendingOpen?.token != event.token) return
        mutableState.value = mutableState.value.copy(
            pendingOpen = null, error = WorkspaceCreateError.OPEN_FAILED,
        )
    }
}
