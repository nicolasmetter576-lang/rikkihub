package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 设备目录 (mount.source) 的直接文件访问层。
 *
 * 契约:
 * - 文件标识 = mount + 相对路径; 返回的 [WorkspaceFileEntry.path] 始终相对 mount.source
 * - 所有操作先重新校验 mount.source 仍是可访问目录 (连接可能已被拔/权限被收,
 *   权限本身的获取由 app gate 负责, 这里只检查当前文件系统可见性)
 * - 排他新建 (createFile / createDirectory / overwrite=false) 要求父目录已存在;
 *   普通覆盖写允许创建缺失的父目录, 但绝不自动创建 source 本身
 * - 批删/ZIP 复用 [WorkspaceBatchDeleter] / [WorkspaceArchiveExporter]:
 *   逐组件符号链接拒绝, 不跟随链接逃出 source
 * - 禁止删除 source 根本身
 *
 * 语义: 普通文件 IO 直写原文件, 没有 COW / upper / commit / discard;
 * 不声称对恶意 shell 的 OS 级隔离。
 */
class WorkspaceDeviceFileAccess(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val treeImporter = WorkspaceTreeImporter(fileSystem)
    private val archiveExporter = WorkspaceArchiveExporter(fileSystem)
    private val batchDeleter = WorkspaceBatchDeleter(fileSystem)

    fun list(
        mount: WorkspaceDeviceMount,
        path: String = "",
    ): List<WorkspaceFileEntry> = fileSystem.list(readableSource(mount), path)

    fun stat(
        mount: WorkspaceDeviceMount,
        path: String,
    ): WorkspacePathInfo = fileSystem.stat(readableSource(mount), path)

    fun readText(
        mount: WorkspaceDeviceMount,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(readableSource(mount), path, charset)

    fun readTextSnapshot(mount: WorkspaceDeviceMount, path: String): WorkspaceTextSnapshot =
        fileSystem.readTextSnapshot(readableSource(mount), path)

    fun writeTextWithRevision(mount: WorkspaceDeviceMount, path: String, text: String, expectedRevision: String): WorkspaceTextWriteResult =
        fileSystem.writeTextWithRevision(writableSource(mount), path, text, expectedRevision)

    /** Recheck the source for every call; app authorization remains the app gate's job. */
    fun createFile(
        mount: WorkspaceDeviceMount,
        path: String,
        text: String = "",
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.createFile(writableSource(mount), path, text, charset)

    /** Exclusive one-directory creation; retains legacy mkdir for imports. */
    fun createDirectory(mount: WorkspaceDeviceMount, path: String): WorkspaceFileEntry =
        fileSystem.createDirectory(writableSource(mount), path)

    fun writeText(
        mount: WorkspaceDeviceMount,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry =
        fileSystem.writeText(writableSource(mount), path, text, overwrite, charset)

    fun mkdir(
        mount: WorkspaceDeviceMount,
        path: String,
    ): WorkspaceFileEntry {
        require(path.isNotBlank() && path != ".") { "Refusing to recreate device root" }
        val root = writableSource(mount)
        return WorkspaceFileMutations.withLock(root) {
            val directory = fileSystem.resolve(root, path)
            // Never adopt an existing directory as an import-owned temporary root.
            require(directory.mkdirs()) { "Directory already exists or could not be created" }
            WorkspaceFileEntry(
                path = directory.relativeTo(root).path.replace(File.separatorChar, '/'),
                name = directory.name,
                isDirectory = true,
                sizeBytes = 0L,
                updatedAt = directory.lastModified(),
            )
        }
    }

    fun move(
        mount: WorkspaceDeviceMount,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry = fileSystem.move(writableSource(mount), source, target, overwrite)

    fun delete(
        mount: WorkspaceDeviceMount,
        path: String,
        recursive: Boolean = false,
    ): Boolean {
        require(path.isNotBlank() && path != "." && path != "/") {
            "Refusing to delete the device directory root"
        }
        return fileSystem.delete(writableSource(mount), path, recursive)
    }

    suspend fun deleteBatch(
        mount: WorkspaceDeviceMount,
        paths: List<String>,
    ): WorkspaceDeleteResult = batchDeleter.deleteBatch(writableSource(mount), paths)

    fun importFile(
        mount: WorkspaceDeviceMount,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(writableSource(mount), targetPath, inputStream)
    }

    suspend fun importFolder(
        mount: WorkspaceDeviceMount,
        destinationPath: String,
        treeRoot: WorkspaceImportNode,
    ): WorkspaceImportResult =
        treeImporter.import(writableSource(mount), destinationPath, treeRoot)

    fun exportFile(
        mount: WorkspaceDeviceMount,
        path: String,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(readableSource(mount), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    suspend fun exportZip(
        mount: WorkspaceDeviceMount,
        paths: List<String>,
        outputStream: OutputStream,
    ): WorkspaceArchiveResult =
        archiveExporter.exportZip(readableSource(mount), paths, outputStream)

    /** 每次操作重查: 连接着的 source 必须仍是可读目录 */
    private fun readableSource(mount: WorkspaceDeviceMount): File {
        val source = mount.source.canonicalFile
        require(source == mount.source.absoluteFile.normalize()) { "Device directory source changed: ${mount.alias}" }
        require(source.isDirectory) {
            "Device directory is not connected: ${mount.alias}"
        }
        require(source.canRead()) {
            "Device directory is not readable: ${mount.alias}"
        }
        return source
    }

    private fun writableSource(mount: WorkspaceDeviceMount): File {
        val source = readableSource(mount)
        require(source.canWrite()) {
            "Device directory is not writable: ${mount.alias}"
        }
        return source
    }
}
