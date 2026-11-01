package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val treeImporter = WorkspaceTreeImporter(fileSystem)
    private val archiveExporter = WorkspaceArchiveExporter(fileSystem)
    private val archiveExtractor = WorkspaceArchiveExtractor(fileSystem)
    private val batchDeleter = WorkspaceBatchDeleter(fileSystem)
    internal val fileTransfer = WorkspaceFileTransfer(fileSystem)

    fun agentBindMounts(exposedTargets: Set<String>): List<WorkspaceBindMount> =
        bindMounts.filter { mount ->
            val target = mount.target.trimEnd('/')
            when (target) {
                // One existing exposeSkills switch controls BOTH physical trees. A standalone
                // builtin target cannot bypass a disabled /skills exposure (including harness).
                "/skills", "/builtin_skills" -> "/skills" in exposedTargets
                else -> target !in OPTIONAL_AGENT_MOUNT_TARGETS || target in exposedTargets
            }
        }.also { mounts ->
            mounts.filter { it.target.trimEnd('/') == "/builtin_skills" }.forEach { mount ->
                require(mount.source.canonicalFile == mount.source.absoluteFile.normalize() && mount.source.isDirectory) {
                    "Builtin skill mount source is unavailable or unsafe"
                }
            }
        }

    private fun sortedBindMounts(mounts: List<WorkspaceBindMount> = bindMounts): List<WorkspaceBindMount> =
        mounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = isUsableRootfs(linuxDir(root))

    /** Rootfs 内执行命令实际使用的 shell（/bin/bash 或 /bin/sh） */
    fun rootfsShell(root: String): String = rootfsShell(linuxDir(root))

    /** Rootfs 的 shell 与发行版；rootfs 未安装时为 null */
    fun rootfsEnvironment(root: String): RootfsEnvironment? =
        linuxDir(root).takeIf(::isUsableRootfs)?.let(::readRootfsEnvironment)

    fun deleteWorkspace(root: String): Boolean = WorkspaceFileMutations.withLocks(
        listOf(filesDir(root), linuxDir(root), tempDir(root)),
    ) { workspaceDir(root).deleteRecursively() }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun stat(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspacePathInfo = fileSystem.stat(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    /** LF-normalized editor snapshot; LINUX is read-only at this API boundary. */
    fun readTextSnapshot(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceTextSnapshot = fileSystem.readTextSnapshot(areaDir(root, area), path)

    /** Revision-aware atomic save is FILES-only. */
    fun writeTextWithRevision(root: String, path: String, text: String, expectedRevision: String): WorkspaceTextWriteResult =
        fileSystem.writeTextWithRevision(filesDir(root), path, text, expectedRevision)

    fun mkdir(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry = fileSystem.mkdir(areaDir(root, area), path)

    /** FILES only; requires the workspace and parent directory to already exist. */
    fun createFile(
        root: String,
        path: String,
        text: String = "",
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.createFile(filesDir(root), path, text, charset)

    /** FILES only; unlike mkdir, never adopts an existing directory or creates parents. */
    fun createDirectory(root: String, path: String): WorkspaceFileEntry =
        fileSystem.createDirectory(filesDir(root), path)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    /**
     * 整文件夹导入: [treeRoot] 是上层对 ACTION_OPEN_DOCUMENT_TREE 返回 uri 的抽象
     * (WorkspaceImportNode), 保留根目录与全部层级, 同名根目录整体 " (n)" 重命名,
     * 失败/取消时删掉本次新建的根目录.
     */
    suspend fun importFolder(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        treeRoot: WorkspaceImportNode,
    ): WorkspaceImportResult =
        treeImporter.import(areaDir(root, area), destinationPath, treeRoot)

    /**
     * 把选中的若干文件/目录打包成 ZIP 流式写入 [outputStream] (由本函数关闭).
     * 空选择/根目录/越界路径/符号链接一律拒绝, 细节见 [WorkspaceArchiveExporter].
     */
    suspend fun exportArchive(
        root: String,
        paths: List<String>,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ): WorkspaceArchiveResult =
        archiveExporter.exportZip(areaDir(root, area), paths, outputStream)

    /**
     * 解压 [path] 指向的压缩包到它所在的目录 (单个顶层条目直接放, 多个包进以压缩包命名的文件夹,
     * 重名加 " (n)"); 先解到工作区 tmp 再整体移入, 失败/取消不留半成品. 见 [WorkspaceArchiveExtractor].
     */
    suspend fun extractArchive(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        password: CharArray? = null,
        nameEncoding: String? = null,
        onProgress: (WorkspaceExtractProgress) -> Unit = {},
    ): WorkspaceExtractResult =
        archiveExtractor.extract(areaDir(root, area), tempDir(root), path, password, nameEncoding, onProgress)

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun resolveFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): File {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(
        root: String,
        path: String,
        mounts: List<WorkspaceBindMount> = bindMounts,
        deviceMounts: List<WorkspaceDeviceMount> = emptyList(),
        /** [WorkspaceFilesScope]: only `/workspace/<scope>` resolves under `/workspace`. */
        filesScope: String? = null,
    ): RootfsLocation {
        require('\u0000' !in path && path.trim().startsWith("/")) { "Invalid rootfs path" }
        require(path.split('/').none { it == ".." }) { "Path escapes workspace root: parent traversal is not allowed" }
        val trimmed = "/" + path.trim().split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

        // /device 是保留命名空间: 未配置 / 未知 alias / source 失效都明确报错,
        // 绝不回落到 linuxDir 里的某个物理路径
        val deviceRoot = WorkspaceDeviceDirectoryGuard.DEVICE_GUEST_ROOT
        if (trimmed == deviceRoot || trimmed.startsWith("$deviceRoot/")) {
            val deviceBindings = deviceMounts.map { it.toBindMount() } +
                mounts.filter { it.required && it.target.startsWith("$deviceRoot/") }
            require(deviceBindings.isNotEmpty()) {
                "No device directories are configured for this workspace: $path"
            }
            require(trimmed != deviceRoot) {
                "Specify a device directory alias: $deviceRoot/<alias>"
            }
            val mount = deviceBindings.firstOrNull { m ->
                trimmed == m.target || trimmed.startsWith("${m.target}/")
            } ?: error("Unknown device directory: $path")
            val source = mount.source.canonicalFile
            require(source == mount.source.absoluteFile.normalize()) { "Device directory source changed: ${mount.target}" }
            require(source.isDirectory) {
                "Device directory is not connected: ${mount.target}"
            }
            val rel = trimmed.removePrefix(mount.target).trimStart('/')
            // canonical 解析仍在 source 内做, 越界/软链由 resolveRootfsFile 的
            // fileSystem.resolve 防线拦截
            return RootfsLocation(source, rel)
        }

        sortedBindMounts(mounts).forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            if (filesScope != null) {
                val relative = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/')
                // Same message whether or not the outside path exists.
                require(WorkspaceFilesScope.contains(filesScope, relative)) { WorkspaceFilesScope.outsideMessage(filesScope) }
                return RootfsLocation(
                    rootDir = WorkspaceFilesScope.resolveSource(filesDir(root), filesScope),
                    relativePath = relative.removePrefix(filesScope).trimStart('/'),
                )
            }
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(
        root: String,
        path: String,
        mounts: List<WorkspaceBindMount> = bindMounts,
        filesScope: String? = null,
    ): Long = resolveRootfsFile(root, path, mounts, filesScope).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        mounts: List<WorkspaceBindMount> = bindMounts,
        filesScope: String? = null,
    ) {
        val file = resolveRootfsFile(root, path, mounts, filesScope)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun rootfsStat(
        root: String,
        path: String,
        mounts: List<WorkspaceBindMount> = bindMounts,
        filesScope: String? = null,
    ): WorkspacePathInfo {
        val location = resolveRootfsPath(root, path, mounts, filesScope = filesScope)
        val file = fileSystem.resolve(location.rootDir, location.relativePath)
        return WorkspacePathInfo(
            path = path,
            exists = file.exists(),
            isDirectory = file.isDirectory,
            isFile = file.isFile,
            sizeBytes = if (file.isFile) file.length() else 0L,
            updatedAt = if (file.exists()) file.lastModified() else 0L,
        )
    }

    fun listRootfs(
        root: String,
        path: String,
        mounts: List<WorkspaceBindMount> = bindMounts,
        filesScope: String? = null,
    ): List<WorkspaceFileEntry> {
        val location = resolveRootfsPath(root, path, mounts, filesScope = filesScope)
        val file = fileSystem.resolve(location.rootDir, location.relativePath)
        require(file.exists()) { "The path $path does not exist. Please provide a valid path." }
        require(file.isDirectory) { "Path is not a directory: $path" }
        val prefix = path.trimEnd('/').ifBlank { "" }
        return fileSystem.list(location.rootDir, location.relativePath).map { entry ->
            val absolute = if (prefix.isEmpty() || prefix == "/") {
                "/${entry.name}"
            } else {
                "$prefix/${entry.name}"
            }
            entry.copy(path = absolute)
        }
    }

    fun writeRootfsText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean,
        charset: Charset = StandardCharsets.UTF_8,
        mounts: List<WorkspaceBindMount> = bindMounts,
        filesScope: String? = null,
    ): WorkspaceFileEntry {
        if (!overwrite) {
            // The ordinary rootfs resolver trims and normalizes guest paths. Do
            // not let that rewrite an exclusive creation leaf or erase traversal.
            if (!path.startsWith('/')) {
                throw WorkspaceFileOperationException(
                    WorkspaceFileOperationException.INVALID_PATH,
                    "Creation requires an absolute guest path",
                )
            }
            workspaceCreationSegments(path.removePrefix("/"))
        }
        val location = resolveRootfsPath(root, path, mounts, filesScope = filesScope)
        val relative = location.relativePath.ifBlank {
            error("Refusing to write workspace root: $path")
        }
        return fileSystem.writeText(location.rootDir, relative, text, overwrite, charset)
            .copy(path = path, name = path.trimEnd('/').substringAfterLast('/').ifBlank { "/" })
    }

    /**
     * Host file behind a guest [path]; goes through [resolveRootfsPath], so traversal, files outside a
     * [filesScope] and links leaving the backing directory are refused. Does not check that it exists.
     */
    fun resolveRootfsFile(
        root: String,
        path: String,
        mounts: List<WorkspaceBindMount> = bindMounts,
        filesScope: String? = null,
    ): File {
        val location = resolveRootfsPath(root, path, mounts, filesScope = filesScope)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    /**
     * 多选批量删除: 全部路径先整体校验 (根/越界/符号链接拒绝, 祖先去重) 后逐项
     * 串行删除, 单项失败不影响其余; 目录递归删除且不跟随符号链接.
     * 详见 [WorkspaceBatchDeleter].
     */
    suspend fun deleteFiles(
        root: String,
        paths: List<String>,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceDeleteResult =
        batchDeleter.deleteBatch(areaDir(root, area), paths)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun copyFile(root: String, source: String, target: String): WorkspaceFileEntry =
        fileSystem.copy(filesDir(root), source, target)

    /** Move or copy FILES entries into a FILES folder of [targetRoot] (may equal [sourceRoot]); see [WorkspaceFileTransfer]. */
    suspend fun transferFiles(
        sourceRoot: String,
        paths: List<String>,
        targetRoot: String,
        targetDir: String,
        mode: WorkspaceTransferMode,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceTransferResult =
        fileTransfer.transferBatch(filesDir(sourceRoot), paths, filesDir(targetRoot), targetDir, mode, onProgress)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        exposedTargets: Set<String> = OPTIONAL_AGENT_MOUNT_TARGETS,
        shellCompatibilityMode: Boolean = false,
        /** 已连接设备目录; 传给 shell 后端作为 required bind mount, 默认空不回归旧行为 */
        deviceMounts: List<WorkspaceDeviceMount> = emptyList(),
        /** [WorkspaceFilesScope]; [cwd] stays FILES-relative and must be inside it (blank = the scope). */
        filesScope: String? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val effectiveCwd = if (filesScope == null) cwd else {
            val relative = WorkspaceFilesScope.normalize(cwd) ?: filesScope
            require(WorkspaceFilesScope.contains(filesScope, relative)) { WorkspaceFilesScope.outsideMessage(filesScope) }
            WorkspaceFilesScope.resolveSource(filesDir(root), filesScope)
            relative
        }
        val workingDir = fileSystem.resolve(filesDir(root), effectiveCwd)
        require(workingDir.exists()) { "Working directory does not exist: $effectiveCwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $effectiveCwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = effectiveCwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = agentBindMounts(exposedTargets) +
                    deviceMounts.map { it.toBindMount() },
                deviceMounts = deviceMounts,
                shellCompatibilityMode = shellCompatibilityMode,
                filesScope = filesScope,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX) && root != "." && root != "..") {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX) || root == "." || root == "..") continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        /** Rootfs 是否可用，以 /bin/sh 存在为准 */
        fun isUsableRootfs(linuxDir: File): Boolean =
            linuxDir.isDirectory && linuxDir.hasRootfsEntry("bin/sh")

        /** Rootfs 内使用的 shell：优先 bash，没有时（如 Alpine minirootfs）回退到 /bin/sh */
        fun rootfsShell(linuxDir: File): String =
            if (linuxDir.hasRootfsEntry("bin/bash")) "/bin/bash" else "/bin/sh"

        // Alpine 等发行版的 /bin/sh 是指向 /bin/busybox 的绝对路径软链，在宿主机上解析不到目标，
        // 只有 proot 内才能正确解析，所以软链本身存在即视为存在
        private fun File.hasRootfsEntry(path: String): Boolean {
            val entry = File(this, path)
            return entry.isFile || java.nio.file.Files.isSymbolicLink(entry.toPath())
        }

        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        /** 可由工作区开关单独暴露给 agent 的全局目录 */
        val OPTIONAL_AGENT_MOUNT_TARGETS = setOf("/skills", "/builtin_skills", "/upload", "/tool_outputs", "/subagent_runs")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
