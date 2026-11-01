package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The workspace runtime: PRoot, rootfs installation, interactive PTYs and file
 * operations on app-private storage. Device directories are re-validated against
 * primary shared storage on every use.
 */
class LocalWorkspaceBackend(
    private val manager: WorkspaceManager,
    private val installer: RootfsInstaller,
    private val nativeLibraryDir: File,
    private val deviceGuard: WorkspaceDeviceDirectoryGuard?,
    private val patcher: RootfsPatcher = RootfsPatcher(),
    /**
     * Note file bound at `/device/<alias>` for unavailable device directories (null = not bound).
     * A file, not a directory: anything written below the alias fails with ENOTDIR instead of
     * landing in the rootfs (PRoot's fake root would bypass a read-only directory we own).
     */
    private val unavailableDeviceNote: File? = null,
) : WorkspaceBackend {

    override fun ensureWorkspace(root: String) { manager.ensureWorkspace(root) }
    override fun hasRootfs(root: String): Boolean = manager.hasRootfs(root)
    override fun rootfsEnvironment(root: String): RootfsEnvironment? = manager.rootfsEnvironment(root)
    override fun workspaceExists(root: String): Boolean = manager.workspaceDir(root).isDirectory
    override fun deleteWorkspace(root: String): Boolean = manager.deleteWorkspace(root)
    override fun cleanupAllTempDirs() = manager.cleanupAllTempDirs()

    override fun listFiles(root: String, path: String, area: WorkspaceStorageArea) = manager.listFiles(root, path, area)
    override fun stat(root: String, path: String, area: WorkspaceStorageArea) = manager.stat(root, path, area)
    override fun readText(root: String, path: String) = manager.readText(root, path)
    override fun readTextSnapshot(root: String, path: String, area: WorkspaceStorageArea) = manager.readTextSnapshot(root, path, area)
    override fun writeTextWithRevision(root: String, path: String, text: String, expectedRevision: String) =
        manager.writeTextWithRevision(root, path, text, expectedRevision)
    override fun mkdir(root: String, path: String, area: WorkspaceStorageArea) = manager.mkdir(root, path, area)
    override fun createFile(root: String, path: String, text: String) = manager.createFile(root, path, text)
    override fun createDirectory(root: String, path: String) = manager.createDirectory(root, path)
    override fun writeText(root: String, path: String, text: String, overwrite: Boolean) =
        manager.writeText(root, path, text, overwrite)
    override fun importFile(root: String, destinationPath: String, area: WorkspaceStorageArea, fileName: String, inputStream: InputStream) =
        manager.importFile(root, destinationPath, area, fileName, inputStream)
    override suspend fun importFolder(root: String, destinationPath: String, area: WorkspaceStorageArea, treeRoot: WorkspaceImportNode) =
        manager.importFolder(root, destinationPath, area, treeRoot)
    override suspend fun exportArchive(root: String, paths: List<String>, area: WorkspaceStorageArea, outputStream: OutputStream) =
        manager.exportArchive(root, paths, area, outputStream)
    override suspend fun extractArchive(
        root: String,
        path: String,
        area: WorkspaceStorageArea,
        password: CharArray?,
        nameEncoding: String?,
        onProgress: (WorkspaceExtractProgress) -> Unit,
    ) = manager.extractArchive(root, path, area, password, nameEncoding, onProgress)
    override fun fileSize(root: String, path: String, area: WorkspaceStorageArea) = manager.fileSize(root, path, area)
    override fun exportFile(root: String, path: String, area: WorkspaceStorageArea, outputStream: OutputStream) =
        manager.exportFile(root, path, area, outputStream)

    private fun bindMounts(mounts: WorkspaceMountSelection): List<WorkspaceBindMount> =
        manager.agentBindMounts(mounts.exposedTargets) + deviceMounts(mounts).map { it.toBindMount() }

    private fun deviceMounts(mounts: WorkspaceMountSelection): List<WorkspaceDeviceMount> {
        if (mounts.deviceMounts.isEmpty() && mounts.unavailableDeviceAliases.isEmpty()) return emptyList()
        val unavailable = mounts.unavailableDeviceAliases.toMutableList()
        val resolved = mounts.deviceMounts.mapNotNull { spec ->
            val source = deviceGuard?.let { guard -> runCatching { guard.resolveSource(spec.sourcePath) }.getOrNull() }
            if (source == null) {
                unavailable += spec.alias
                null
            } else {
                WorkspaceDeviceMount(spec.id, spec.alias, source)
            }
        }
        deviceGuard?.validateConnections(resolved)
        val placeholder = unavailableDeviceNote?.takeIf { unavailable.isNotEmpty() }?.let(::ensureUnavailableNote)
            ?: return resolved
        return resolved + unavailable.distinct()
            .filter { alias -> resolved.none { it.alias == alias } }
            .map { alias -> WorkspaceDeviceMount("unavailable:$alias", alias, placeholder) }
    }

    override fun rootfsFileSize(root: String, path: String, mounts: WorkspaceMountSelection) =
        manager.rootfsFileSize(root, path, bindMounts(mounts), mounts.filesScope)
    override fun exportRootfsFile(root: String, path: String, outputStream: OutputStream, mounts: WorkspaceMountSelection) =
        manager.exportRootfsFile(root, path, outputStream, bindMounts(mounts), mounts.filesScope)
    override fun rootfsStat(root: String, path: String, mounts: WorkspaceMountSelection) =
        manager.rootfsStat(root, path, bindMounts(mounts), mounts.filesScope)
    override fun resolveRootfsFile(root: String, path: String, mounts: WorkspaceMountSelection) =
        manager.resolveRootfsFile(root, path, bindMounts(mounts), mounts.filesScope)
    override fun listRootfs(root: String, path: String, mounts: WorkspaceMountSelection) =
        manager.listRootfs(root, path, bindMounts(mounts), mounts.filesScope)
    override fun writeRootfsText(root: String, path: String, text: String, overwrite: Boolean, mounts: WorkspaceMountSelection) =
        manager.writeRootfsText(root, path, text, overwrite, mounts = bindMounts(mounts), filesScope = mounts.filesScope)

    override fun deleteFile(root: String, path: String, recursive: Boolean, area: WorkspaceStorageArea) =
        manager.deleteFile(root, path, recursive, area)
    override suspend fun deleteFiles(root: String, paths: List<String>, area: WorkspaceStorageArea) =
        manager.deleteFiles(root, paths, area)
    override fun moveFile(root: String, source: String, target: String, overwrite: Boolean) =
        manager.moveFile(root, source, target, overwrite)
    override fun copyFile(root: String, source: String, target: String) = manager.copyFile(root, source, target)
    override suspend fun transferFiles(
        sourceRoot: String,
        paths: List<String>,
        targetRoot: String,
        targetDir: String,
        mode: WorkspaceTransferMode,
        onProgress: (WorkspaceTransferProgress) -> Unit,
    ) = manager.transferFiles(sourceRoot, paths, targetRoot, targetDir, mode, onProgress)
    override fun glob(root: String, pattern: String, path: String) = manager.glob(root, pattern, path)
    override fun grep(root: String, query: String, path: String, regex: Boolean, ignoreCase: Boolean, includeGlob: String?) =
        manager.grep(root, query, path, regex, ignoreCase, includeGlob)

    override fun executeCommand(
        root: String, command: String, cwd: String, timeoutMillis: Long, stdin: ByteArray?,
        mounts: WorkspaceMountSelection, shellCompatibilityMode: Boolean,
    ): WorkspaceCommandResult = manager.executeCommand(
        root = root, command = command, cwd = cwd, timeoutMillis = timeoutMillis, stdin = stdin,
        exposedTargets = mounts.exposedTargets, shellCompatibilityMode = shellCompatibilityMode,
        deviceMounts = deviceMounts(mounts), filesScope = mounts.filesScope,
    )

    override fun installRootfs(root: String, url: String, onProgress: (RootfsInstallProgress) -> Unit) =
        installer.install(root, url, onProgress)

    override fun openShell(root: String, request: WorkspaceShellRequest): WorkspacePty {
        manager.ensureWorkspace(root)
        return WorkspaceInteractiveShell.start(
            nativeLibraryDir = nativeLibraryDir,
            filesDir = manager.filesDir(root),
            linuxDir = manager.linuxDir(root),
            tempDir = manager.tempDir(root),
            bindMounts = bindMounts(request.mounts),
            rows = request.rows.coerceIn(1, 500),
            columns = request.columns.coerceIn(1, 1000),
            extraEnv = request.extraEnv,
            patcher = patcher,
            patchOptions = RootfsPatchOptions(nameservers = request.patchOptions.nameservers.take(8)),
            shellCompatibilityMode = request.shellCompatibilityMode,
            filesScope = request.mounts.filesScope,
        )
    }


    private companion object {
        const val UNAVAILABLE_TEXT =
            "This device directory is currently unavailable: all-files access is not granted " +
                "or the connected folder no longer exists.\n" +
                "Fix it in RikkiHub > Workspace > Device directories.\n"

        fun ensureUnavailableNote(note: File): File {
            synchronized(this) {
                if (!note.isFile || note.readText() != UNAVAILABLE_TEXT) {
                    note.parentFile?.mkdirs()
                    note.delete()
                    note.writeText(UNAVAILABLE_TEXT)
                }
            }
            return note
        }
    }
}
