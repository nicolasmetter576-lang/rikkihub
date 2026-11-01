package me.rerere.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

/** 「移动到 / 复制到」一个文件夹 */
enum class WorkspaceTransferMode { MOVE, COPY }

/** 单个选中项的移动/复制结果 */
@kotlinx.serialization.Serializable
data class WorkspaceTransferItemResult(
    /** 调用方给的源路径（相对源 FILES 根，已去掉首尾斜杠） */
    val source: String,
    /** 落点路径（相对目标 FILES 根）；失败为 null */
    val target: String? = null,
    val isDirectory: Boolean = false,
    /** 目标文件夹里已有同名项，落点改成了 "name (n)" */
    val renamed: Boolean = false,
    /** 拷贝时跳过的符号链接与特殊文件数（只在复制或移动退回拷贝时可能非 0） */
    val skippedLinks: Int = 0,
    /** 失败时的 [WorkspaceFileOperationException.code]；未知错误为 FILE_IO_ERROR */
    val errorCode: String? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = target != null
}

@kotlinx.serialization.Serializable
data class WorkspaceTransferResult(val items: List<WorkspaceTransferItemResult>) {
    val succeeded: List<WorkspaceTransferItemResult> get() = items.filter { it.ok }
    val failures: List<WorkspaceTransferItemResult> get() = items.filter { !it.ok }
}

/** 进度：每项开始时报一次，拷贝中每 1 MiB 报一次 */
data class WorkspaceTransferProgress(
    /** 当前项序号（从 0 起），也等于此前已处理完的项数 */
    val itemIndex: Int,
    val itemCount: Int,
    val name: String,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
)

/**
 * 把 FILES 区的文件或文件夹放进某个文件夹（可以是另一个工作区的），保留原名；
 * 目标处已有同名项时自动改名为 "name (n)"（[WorkspaceNames.fitConflictName]），从不覆盖。
 *
 * - 两个存储根同时加锁（[WorkspaceFileMutations.withSuspendingLocks]，排序后加，不死锁），每项各加一次，
 *   整批不一起持锁：复制大目录时不会一直挡住两边的其它文件操作
 * - 存储区根、越界路径、显式选中的符号链接（末端或中间组件）拒绝；目录不能放进自己或自己的子目录；
 *   移动的落点就是原文件夹时拒绝（原地不动），复制允许（生成副本）
 * - 移动：同分区一次改名；改名失败才退回「拷贝 + 删源」
 * - 拷贝：不跟随符号链接，链接与特殊文件跳过并计数；64KB 分块，块间可取消；
 *   拷贝前按文件总大小检查目标分区可用空间；失败或取消时删掉本项在目标端的半成品
 * - 批量：逐项处理，单项失败记入结果、不影响其余；取消抛 CancellationException（已完成项保留）
 */
class WorkspaceFileTransfer(
    private val fileSystem: WorkspaceFileSystem = WorkspaceFileSystem(),
) {
    // JVM 测试缝：模拟改名失败、空间不足
    internal var mover: (Path, Path) -> Unit = { source, target -> Files.move(source, target) }
    internal var usableSpace: (File) -> Long = { it.usableSpace }

    suspend fun transferBatch(
        sourceRoot: File,
        paths: List<String>,
        targetRoot: File,
        targetDir: String,
        mode: WorkspaceTransferMode,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceTransferResult {
        coroutineContext.ensureActive()
        require(paths.isNotEmpty()) { "Nothing selected" }
        // 目标文件夹不存在 / 不是目录时一项都不动，整体报错
        resolveTargetDir(targetRoot.canonicalFile, targetDir)
        val items = dedupeSelection(paths)
        val results = ArrayList<WorkspaceTransferItemResult>(items.size)
        for ((index, path) in items.withIndex()) {
            coroutineContext.ensureActive()
            results += try {
                transfer(sourceRoot, path, targetRoot, targetDir, mode, index, items.size, onProgress)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                WorkspaceTransferItemResult(
                    source = path,
                    errorCode = (error as? WorkspaceFileOperationException)?.code
                        ?: WorkspaceFileOperationException.FILE_IO_ERROR,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
        return WorkspaceTransferResult(results)
    }

    /** 单项；失败抛 [WorkspaceFileOperationException]（或 IO 异常）。 */
    suspend fun transfer(
        sourceRoot: File,
        path: String,
        targetRoot: File,
        targetDir: String,
        mode: WorkspaceTransferMode,
        itemIndex: Int = 0,
        itemCount: Int = 1,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceTransferItemResult = WorkspaceFileMutations.withSuspendingLocks(listOf(sourceRoot, targetRoot)) {
        transferUnlocked(sourceRoot, path, targetRoot, targetDir, mode, itemIndex, itemCount, onProgress)
    }

    private suspend fun transferUnlocked(
        sourceRoot: File,
        path: String,
        targetRoot: File,
        targetDir: String,
        mode: WorkspaceTransferMode,
        itemIndex: Int,
        itemCount: Int,
        onProgress: (WorkspaceTransferProgress) -> Unit,
    ): WorkspaceTransferItemResult {
        coroutineContext.ensureActive()
        val srcRoot = sourceRoot.canonicalFile
        val dstRoot = targetRoot.canonicalFile
        val source = resolveSource(srcRoot, path)
        val dir = resolveTargetDir(dstRoot, targetDir)
        if (dir.path == source.path || dir.path.startsWith(source.path + File.separator)) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.TRANSFER_INTO_ITSELF,
                "Cannot put a folder into itself: $path",
            )
        }
        if (mode == WorkspaceTransferMode.MOVE && dir.path == source.parentFile?.path) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.TRANSFER_SAME_LOCATION,
                "Already in this folder: $path",
            )
        }
        val isDirectory = source.isDirectory
        val target = freeTarget(dir, source.name, isDirectory)
        onProgress(WorkspaceTransferProgress(itemIndex, itemCount, source.name))

        val copy = CopyState(itemIndex, itemCount, source.name, onProgress)
        when (mode) {
            WorkspaceTransferMode.MOVE -> {
                val renamed = try {
                    mover(source.toPath(), target.toPath())
                    true
                } catch (error: FileAlreadyExistsException) {
                    // 锁外的进程（终端）刚好占了这个名字
                    throw WorkspaceFileAlreadyExistsException(error)
                } catch (_: IOException) {
                    false
                }
                if (!renamed) {
                    copyTreeOrClean(source, target, dstRoot, copy)
                    if (!deleteNoFollow(source)) {
                        throw WorkspaceFileOperationException(
                            WorkspaceFileOperationException.FILE_IO_ERROR,
                            "Copied to ${relative(dstRoot, target)} but could not remove the original",
                        )
                    }
                }
            }

            WorkspaceTransferMode.COPY -> copyTreeOrClean(source, target, dstRoot, copy)
        }
        return WorkspaceTransferItemResult(
            source = normalize(path),
            target = relative(dstRoot, target),
            isDirectory = isDirectory,
            renamed = target.name != source.name,
            skippedLinks = copy.skipped,
        )
    }

    private fun resolveSource(srcRoot: File, path: String): File {
        val segments = normalize(path).split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.INVALID_PATH, "Invalid path: $path")
        }
        // 先逐组件 NOFOLLOW 查用户给的词法路径：选中链接一律拒绝（resolve 会把链接解析成目标，指向根外的还会被判成越界）
        var cursor = srcRoot
        for (segment in segments) {
            cursor = File(cursor, segment)
            if (Files.isSymbolicLink(cursor.toPath())) {
                throw WorkspaceFileOperationException(
                    WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED,
                    "Refusing to move or copy symbolic link: ${relative(srcRoot, cursor)}",
                )
            }
        }
        val source = try {
            fileSystem.resolve(srcRoot, path)
        } catch (error: IllegalArgumentException) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.INVALID_PATH, error.message ?: "Invalid path", error)
        }
        if (!source.exists()) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_NOT_FOUND, "Does not exist: $path")
        }
        if (!source.isDirectory && !source.isFile) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_NOT_FILE, "Not a file or folder: $path")
        }
        return source
    }

    private fun resolveTargetDir(dstRoot: File, targetDir: String): File {
        val dir = try {
            fileSystem.resolve(dstRoot, targetDir)
        } catch (error: IllegalArgumentException) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.INVALID_PATH, error.message ?: "Invalid path", error)
        }
        if (!dir.exists()) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.PARENT_NOT_FOUND, "Folder does not exist: $targetDir")
        }
        if (!dir.isDirectory) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.PARENT_NOT_DIRECTORY, "Not a folder: $targetDir")
        }
        return dir
    }

    /** 原名，或第一个空着的 "name (n)"；悬空链接也算占用 */
    private fun freeTarget(dir: File, name: String, isDirectory: Boolean): File {
        var candidate = File(dir, name)
        var n = 0
        while (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            n++
            if (n > MAX_NAME_ATTEMPTS) throw WorkspaceFileAlreadyExistsException()
            candidate = File(dir, WorkspaceNames.fitConflictName(name, n, isDirectory))
        }
        return candidate
    }

    private class CopyState(
        val itemIndex: Int,
        val itemCount: Int,
        val name: String,
        val onProgress: (WorkspaceTransferProgress) -> Unit,
    ) {
        var skipped = 0
        var total = 0L
        var done = 0L
        private var reported = 0L

        fun advance(bytes: Long) {
            done += bytes
            if (done - reported >= PROGRESS_STEP || done == total) {
                reported = done
                onProgress(WorkspaceTransferProgress(itemIndex, itemCount, name, done, total))
            }
        }
    }

    private suspend fun copyTreeOrClean(source: File, target: File, dstRoot: File, state: CopyState) {
        state.total = sizeOf(source.toPath())
        if (state.total + SPACE_RESERVE_BYTES > usableSpace(dstRoot)) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.NOT_ENOUGH_SPACE,
                "Not enough free space: ${state.total} bytes needed",
            )
        }
        try {
            copyNode(source.toPath(), target.toPath(), state)
        } catch (error: Throwable) {
            deleteNoFollow(target)
            throw error
        }
    }

    private suspend fun copyNode(source: Path, target: Path, state: CopyState) {
        coroutineContext.ensureActive()
        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
            attrs.isDirectory -> {
                Files.createDirectory(target)
                Files.newDirectoryStream(source).use { children ->
                    for (child in children) copyNode(child, target.resolve(child.fileName.toString()), state)
                }
                Files.setLastModifiedTime(target, attrs.lastModifiedTime())
            }

            attrs.isRegularFile -> {
                Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
                    Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            state.advance(read.toLong())
                        }
                    }
                }
                Files.setLastModifiedTime(target, attrs.lastModifiedTime())
            }

            else -> state.skipped++
        }
    }

    private fun sizeOf(path: Path): Long {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return when {
            attrs.isRegularFile -> attrs.size()
            attrs.isDirectory -> Files.newDirectoryStream(path).use { children -> children.sumOf { sizeOf(it) } }
            else -> 0L
        }
    }

    /** 不跟随链接的递归删除；链接只删链接本身 */
    private fun deleteNoFollow(file: File): Boolean {
        val path = file.toPath()
        return try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(path).use { children -> children.forEach { deleteNoFollow(it.toFile()) } }
            }
            Files.deleteIfExists(path)
            true
        } catch (_: DirectoryNotEmptyException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    private fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 1024L * 1024L
        /** 拷贝后至少给目标分区留这么多空间 */
        internal const val SPACE_RESERVE_BYTES = 8L * 1024L * 1024L
        private const val MAX_NAME_ATTEMPTS = 10_000

        internal fun normalize(path: String): String =
            path.replace('\\', '/').trim().trim('/')

        /** 去掉重复项和已被选中祖先覆盖的后代（词法比较，保持原顺序） */
        internal fun dedupeSelection(paths: List<String>): List<String> {
            val kept = mutableListOf<String>()
            for (raw in paths) {
                val path = normalize(raw)
                if (kept.any { it == path || path.startsWith("$it/") }) continue
                kept.removeAll { it.startsWith("$path/") }
                kept += path
            }
            return kept
        }
    }
}
