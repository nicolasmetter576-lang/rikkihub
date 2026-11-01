package me.rerere.workspace

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * Mount selection for rootfs paths and command execution: exposed host trees by target
 * plus enabled device directories (re-validated by the backend before binding). Aliases in
 * [unavailableDeviceAliases] (permission missing / source gone) are bound to an explanatory
 * note file so `/device/<alias>` never silently falls back to the rootfs.
 */
@Serializable
data class WorkspaceMountSelection(
    val exposedTargets: Set<String> = WorkspaceManager.OPTIONAL_AGENT_MOUNT_TARGETS,
    val deviceMounts: List<WorkspaceDeviceMountSpec> = emptyList(),
    val unavailableDeviceAliases: List<String> = emptyList(),
    /** FILES-relative directory the agent is confined to ([WorkspaceFilesScope]); null = whole FILES area. */
    val filesScope: String? = null,
)

/** A user-approved device directory: alias + absolute shared-storage path. */
@Serializable
data class WorkspaceDeviceMountSpec(val id: String, val alias: String, val sourcePath: String)

/** Interactive bash inside the workspace rootfs (persistent bash, App/Web terminals). */
@Serializable
data class WorkspaceShellRequest(
    val rows: Int = 24,
    val columns: Int = 80,
    val mounts: WorkspaceMountSelection = WorkspaceMountSelection(),
    val extraEnv: Map<String, String> = emptyMap(),
    val shellCompatibilityMode: Boolean = false,
    val patchOptions: RootfsPatchOptionsSpec = RootfsPatchOptionsSpec(),
)

/** Serializable mirror of [RootfsPatchOptions] fields that callers may choose. */
@Serializable
data class RootfsPatchOptionsSpec(
    val nameservers: List<String> = emptyList(),
)

/** A running interactive shell inside the workspace rootfs. */
interface WorkspacePty : AutoCloseable {
    val exitCode: Int?
    suspend fun write(bytes: ByteArray)
    fun available(): Int
    fun read(buffer: ByteArray): Int
    fun resize(rows: Int, columns: Int)
    suspend fun awaitExit(): Int
    suspend fun awaitClosed(timeoutMillis: Long = 5_000L): Boolean
}

/** Everything the app needs from the workspace runtime ([LocalWorkspaceBackend] = [WorkspaceManager] + PRoot). */
interface WorkspaceBackend {
    fun ensureWorkspace(root: String)
    fun hasRootfs(root: String): Boolean
    /** Shell and distribution of the installed rootfs; null when no rootfs is installed. */
    fun rootfsEnvironment(root: String): RootfsEnvironment? = null
    /** True when the workspace directory exists in the runtime (does not create it). */
    fun workspaceExists(root: String): Boolean
    fun deleteWorkspace(root: String): Boolean
    fun cleanupAllTempDirs()

    fun listFiles(root: String, path: String = "", area: WorkspaceStorageArea = WorkspaceStorageArea.FILES): List<WorkspaceFileEntry>
    fun stat(root: String, path: String, area: WorkspaceStorageArea = WorkspaceStorageArea.FILES): WorkspacePathInfo
    fun readText(root: String, path: String): String
    fun readTextSnapshot(root: String, path: String, area: WorkspaceStorageArea = WorkspaceStorageArea.FILES): WorkspaceTextSnapshot
    fun writeTextWithRevision(root: String, path: String, text: String, expectedRevision: String): WorkspaceTextWriteResult
    fun mkdir(root: String, path: String, area: WorkspaceStorageArea = WorkspaceStorageArea.FILES): WorkspaceFileEntry
    fun createFile(root: String, path: String, text: String = ""): WorkspaceFileEntry
    fun createDirectory(root: String, path: String): WorkspaceFileEntry
    fun writeText(root: String, path: String, text: String, overwrite: Boolean = true): WorkspaceFileEntry
    fun importFile(root: String, destinationPath: String, area: WorkspaceStorageArea, fileName: String, inputStream: InputStream): WorkspaceFileEntry
    suspend fun importFolder(root: String, destinationPath: String, area: WorkspaceStorageArea, treeRoot: WorkspaceImportNode): WorkspaceImportResult
    suspend fun exportArchive(root: String, paths: List<String>, area: WorkspaceStorageArea, outputStream: OutputStream): WorkspaceArchiveResult
    suspend fun extractArchive(
        root: String,
        path: String,
        area: WorkspaceStorageArea,
        password: CharArray?,
        nameEncoding: String?,
        onProgress: (WorkspaceExtractProgress) -> Unit,
    ): WorkspaceExtractResult
    fun fileSize(root: String, path: String, area: WorkspaceStorageArea = WorkspaceStorageArea.FILES): Long
    fun exportFile(root: String, path: String, area: WorkspaceStorageArea, outputStream: OutputStream)

    fun rootfsFileSize(root: String, path: String, mounts: WorkspaceMountSelection): Long
    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream, mounts: WorkspaceMountSelection)
    fun rootfsStat(root: String, path: String, mounts: WorkspaceMountSelection): WorkspacePathInfo
    /** Host file behind a guest path, for backends whose files live on this device; null when there is none. */
    fun resolveRootfsFile(root: String, path: String, mounts: WorkspaceMountSelection): java.io.File? = null
    fun listRootfs(root: String, path: String, mounts: WorkspaceMountSelection): List<WorkspaceFileEntry>
    fun writeRootfsText(root: String, path: String, text: String, overwrite: Boolean, mounts: WorkspaceMountSelection): WorkspaceFileEntry

    fun deleteFile(root: String, path: String, recursive: Boolean = false, area: WorkspaceStorageArea = WorkspaceStorageArea.FILES): Boolean
    suspend fun deleteFiles(root: String, paths: List<String>, area: WorkspaceStorageArea = WorkspaceStorageArea.FILES): WorkspaceDeleteResult
    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry
    /** FILES only; target must not exist. */
    fun copyFile(root: String, source: String, target: String): WorkspaceFileEntry
    /**
     * FILES only: move or copy [paths] into folder [targetDir] of [targetRoot] (may be [sourceRoot]),
     * keeping names and renaming to "name (n)" on a clash; per-item results, never overwrites.
     */
    suspend fun transferFiles(
        sourceRoot: String,
        paths: List<String>,
        targetRoot: String,
        targetDir: String,
        mode: WorkspaceTransferMode,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceTransferResult
    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry>
    fun grep(root: String, query: String, path: String = "", regex: Boolean = false, ignoreCase: Boolean = true,
             includeGlob: String? = null): List<WorkspaceSearchMatch>

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        mounts: WorkspaceMountSelection = WorkspaceMountSelection(),
        shellCompatibilityMode: Boolean = false,
    ): WorkspaceCommandResult

    fun installRootfs(root: String, url: String, onProgress: (RootfsInstallProgress) -> Unit = {})

    fun openShell(root: String, request: WorkspaceShellRequest): WorkspacePty
}
