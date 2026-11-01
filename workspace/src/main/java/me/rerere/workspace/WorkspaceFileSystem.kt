package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val rootCanonical = root.canonicalFile
        val dir = resolvePath(rootCanonical, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(rootCanonical) }
    }

    fun stat(root: File, path: String): WorkspacePathInfo {
        val rootCanonical = root.canonicalFile
        val file = resolvePath(rootCanonical, path)
        return WorkspacePathInfo(
            path = if (path.isBlank() || path == ".") "." else file.relativePath(rootCanonical),
            exists = file.exists(),
            isDirectory = file.isDirectory,
            isFile = file.isFile,
            sizeBytes = if (file.isFile) file.length() else 0L,
            updatedAt = if (file.exists()) file.lastModified() else 0L,
        )
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root.canonicalFile, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    // Internal deterministic JVM failure seam, never a public save fallback.
    internal var textSaveIo: WorkspaceTextSaveIo = WorkspaceTextSaveIo()

    fun readTextSnapshot(root: File, path: String): WorkspaceTextSnapshot = textOperation {
        WorkspaceFileMutations.withLock(root) {
            val bytes = readBoundedWorkspaceBytes(resolvePath(root.canonicalFile, path), config.maxReadBytes)
            WorkspaceTextSnapshot(WorkspaceSourceText(bytes).content, workspaceRevision(bytes))
        }
    }

    /** CAS saves only existing files. Legacy writeText intentionally remains non-CAS. */
    fun writeTextWithRevision(root: File, path: String, text: String, expectedRevision: String): WorkspaceTextWriteResult {
        validateWorkspaceRevision(expectedRevision)
        return textOperation {
            WorkspaceFileMutations.withLock(root) {
                val rootCanonical = root.canonicalFile
                val file = resolvePath(rootCanonical, path)
                fun currentBytes(): ByteArray {
                    val current = resolvePath(rootCanonical, path)
                    if (current != file || !current.isFile) throw WorkspaceFileRevisionConflictException()
                    val bytes = try {
                        // A successful prior save may exceed the snapshot read limit.
                        // Keep CAS reads bounded, but permit editing that same open buffer.
                        readBoundedWorkspaceBytes(current, maxOf(config.maxReadBytes, config.maxWriteBytes))
                    } catch (error: WorkspaceFileOperationException) {
                        if (error.code == WorkspaceFileOperationException.FILE_NOT_FOUND ||
                            error.code == WorkspaceFileOperationException.FILE_NOT_FILE ||
                            error.code == WorkspaceFileOperationException.CONTENT_TOO_LARGE) {
                            throw WorkspaceFileRevisionConflictException()
                        }
                        throw error
                    } catch (_: java.nio.file.NoSuchFileException) {
                        throw WorkspaceFileRevisionConflictException()
                    } catch (_: java.io.FileNotFoundException) {
                        if (!current.isFile) throw WorkspaceFileRevisionConflictException()
                        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_IO_ERROR, "File operation failed")
                    }
                    if (workspaceRevision(bytes) != expectedRevision) throw WorkspaceFileRevisionConflictException()
                    return bytes
                }
                val original = WorkspaceSourceText(currentBytes())
                if (text == original.content) {
                    // Do not rewrite, chmod, touch mtime or change the inode on no-op.
                    WorkspaceTextWriteResult(file.toEntry(rootCanonical), expectedRevision)
                } else {
                    val bytes = original.encode(text, config.maxWriteBytes)
                    atomicWorkspaceTextSave(file.toPath(), bytes, textSaveIo) { currentBytes() }
                    WorkspaceTextWriteResult(file.toEntry(rootCanonical), workspaceRevision(bytes))
                }
            }
        }
    }

    /** New text APIs expose stable errors, never caller paths or native IO messages. */
    private fun <T> textOperation(block: () -> T): T = try {
        block()
    } catch (error: WorkspaceFileOperationException) {
        throw error
    } catch (_: java.nio.file.NoSuchFileException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_NOT_FOUND, "File does not exist")
    } catch (_: java.nio.file.AccessDeniedException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_PERMISSION_DENIED, "File access denied")
    } catch (_: java.io.IOException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_IO_ERROR, "File operation failed")
    } catch (_: SecurityException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_PERMISSION_DENIED, "File access denied")
    } catch (_: IllegalArgumentException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.INVALID_PATH, "Invalid file path")
    }

    fun mkdir(root: File, path: String): WorkspaceFileEntry = WorkspaceFileMutations.withLock(root) {
        mkdirUnlocked(root, path)
    }

    private fun mkdirUnlocked(root: File, path: String): WorkspaceFileEntry {
        require(path.isNotBlank() && path != ".") { "Refusing to recreate workspace root" }
        val rootCanonical = root.canonicalFile
        val dir = resolvePath(rootCanonical, path)
        require(!dir.exists() || dir.isDirectory) { "Path is not a directory: $path" }
        if (!dir.exists()) {
            require(dir.mkdirs()) { "Failed to create directory: $path" }
        }
        return dir.toEntry(rootCanonical)
    }

    /**
     * Exclusively create a text file in an existing directory. The root may be
     * canonicalized (e.g. Android's /data/user alias), but no relative parent or
     * leaf is canonicalized or followed. Paths are strictly relative and literal.
     *
     * CREATE_NEW is the claim, not an exists()-then-write check. Every occupied
     * leaf, including a dangling link or directory, is a conflict. Missing parents
     * are never created. This is not an FD-relative sandbox: hostile replacement
     * of a checked parent directory still has a TOCTOU window.
     *
     * On a write/close/metadata failure after the claim, keep the possibly partial
     * file. Unlinking by pathname could delete another writer's replacement. The
     * caller must refresh before retrying; failure does not promise zero effects.
     */
    fun createFile(
        root: File,
        path: String,
        text: String = "",
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = WorkspaceFileMutations.withLock(root) {
        createFileUnlocked(root, path, text, charset)
    }

    private fun createFileUnlocked(root: File, path: String, text: String, charset: Charset): WorkspaceFileEntry {
        val segments = workspaceCreationSegments(path)
        val bytes = text.toByteArray(charset)
        if (bytes.size > config.maxWriteBytes) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.CONTENT_TOO_LARGE,
                "Content is too large to write",
            )
        }
        val target = resolveCreationTarget(root, segments)
        writeNewWorkspaceFile(target, bytes)
        return creationEntry(target, segments)
    }

    /**
     * Exclusively create ONE empty directory; never merge with an existing one.
     * Same literal leaf/parent/link contract as [createFile], without mkdir chains.
     * Files.createDirectory is the atomic claim. Legacy [mkdir] is unchanged.
     * Checked parents can still be replaced concurrently (not an OS sandbox).
     */
    fun createDirectory(root: File, path: String): WorkspaceFileEntry = WorkspaceFileMutations.withLock(root) {
        createDirectoryUnlocked(root, path)
    }

    private fun createDirectoryUnlocked(root: File, path: String): WorkspaceFileEntry {
        val segments = workspaceCreationSegments(path)
        val target = resolveCreationTarget(root, segments)
        try {
            Files.createDirectory(target)
        } catch (error: FileAlreadyExistsException) {
            throw WorkspaceFileAlreadyExistsException(error)
        }
        // No cleanup if metadata retrieval fails: the pathname may have changed.
        return creationEntry(target, segments)
    }

    private fun resolveCreationTarget(root: File, segments: List<String>): Path {
        var parent = root.canonicalFile.toPath()
        requireCreationParent(parent)
        for (segment in segments.dropLast(1)) {
            parent = parent.resolve(segment)
            requireCreationParent(parent)
        }
        // Deliberately retain the lexical leaf; resolvePath() would follow links.
        return parent.resolve(segments.last())
    }

    private fun creationEntry(target: Path, segments: List<String>): WorkspaceFileEntry {
        val attributes = Files.readAttributes(
            target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        return WorkspaceFileEntry(
            path = segments.joinToString("/"),
            name = segments.last(),
            isDirectory = attributes.isDirectory,
            sizeBytes = if (attributes.isRegularFile) attributes.size() else 0L,
            updatedAt = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun requireCreationParent(parent: Path) {
        val attributes = try {
            Files.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: NoSuchFileException) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.PARENT_NOT_FOUND,
                "Parent directory does not exist",
                error,
            )
        }
        if (attributes.isSymbolicLink) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED,
                "Symbolic link parent directories are not allowed",
            )
        }
        if (!attributes.isDirectory) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.PARENT_NOT_DIRECTORY,
                "Parent path is not a directory",
            )
        }
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = WorkspaceFileMutations.withLock(root) {
        writeTextUnlocked(root, path, text, overwrite, charset)
    }

    private fun writeTextUnlocked(root: File, path: String, text: String, overwrite: Boolean, charset: Charset): WorkspaceFileEntry {
        if (!overwrite) return createFileUnlocked(root, path, text, charset)
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val rootCanonical = root.canonicalFile
        val file = resolvePath(rootCanonical, path)
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(rootCanonical)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry = WorkspaceFileMutations.withLock(root) {
        importBytesUnlocked(root, path, inputStream)
    }

    private fun importBytesUnlocked(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val rootCanonical = root.canonicalFile
        val file = resolvePath(rootCanonical, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        try {
            inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (error: Throwable) {
            // 流式导入中途失败 (如超限) 时清掉半成品, 不留下截断文件
            target.delete()
            throw error
        }
        return target.toEntry(rootCanonical)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean = WorkspaceFileMutations.withLock(root) {
        deleteUnlocked(root, path, recursive)
    }

    private fun deleteUnlocked(root: File, path: String, recursive: Boolean): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root.canonicalFile, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry = WorkspaceFileMutations.withLock(root) {
        moveUnlocked(root, source, target, overwrite)
    }

    /** Copies a file or tree to a NEW path (never overwrites); links/special files are skipped. */
    fun copy(root: File, source: String, target: String): WorkspaceFileEntry = WorkspaceFileMutations.withLock(root) {
        require(source.isNotBlank() && source != ".") { "Refusing to copy workspace root" }
        val rootCanonical = root.canonicalFile
        val sourceFile = resolvePath(rootCanonical, source)
        val targetFile = resolvePath(rootCanonical, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) throw WorkspaceFileAlreadyExistsException()
        require(!targetFile.toPath().startsWith(sourceFile.toPath())) { "Cannot copy a directory into itself" }
        val parent = requireNotNull(targetFile.parentFile) { "Invalid target" }
        require(parent.isDirectory) { "Target parent does not exist: $target" }
        val base = sourceFile.toPath()
        Files.walk(base).use { stream ->
            stream.forEach { path ->
                val dest = targetFile.toPath().resolve(base.relativize(path).toString())
                when {
                    Files.isSymbolicLink(path) -> Unit
                    Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(dest)
                    Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ->
                        Files.copy(path, dest, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
        targetFile.toEntry(rootCanonical)
    }

    private fun moveUnlocked(root: File, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val rootCanonical = root.canonicalFile
        val sourceFile = resolvePath(rootCanonical, source)
        val targetFile = resolvePath(rootCanonical, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        // 移到自身或自身子目录: overwrite 会先删掉源, copy + delete 回退会连目标一起删
        require(!targetFile.toPath().startsWith(sourceFile.toPath())) { "Cannot move $source into itself" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        val moved = runCatching {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.isSuccess
        if (!moved) {
            // 原子移动不可用 (文件系统不支持或目标异常) 时退回 copy + delete
            if (sourceFile.isDirectory) {
                require(
                    sourceFile.copyRecursively(targetFile, overwrite = true) &&
                        sourceFile.deleteRecursively()
                ) { "Failed to move $source to $target" }
            } else {
                sourceFile.copyTo(targetFile, overwrite = true)
                require(sourceFile.delete()) { "Failed to move $source to $target" }
            }
        }
        return targetFile.toEntry(rootCanonical)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val rootCanonical = root.canonicalFile
        val start = resolvePath(rootCanonical, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { matcher.matches(rootCanonical.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(rootCanonical) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val rootCanonical = root.canonicalFile
        val start = resolvePath(rootCanonical, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            val iterator = paths.iterator()
            // 达到上限后停止遍历, 不再继续展开剩余目录树
            while (iterator.hasNext() && results.size < config.maxSearchResults) {
                val candidate = iterator.next()
                if (!Files.isRegularFile(candidate)) continue
                val file = candidate.toFile()
                if (file.name.startsWith(".l2s.")) continue
                if (includeMatcher != null &&
                    !includeMatcher.matches(rootCanonical.toPath().relativize(candidate).normalizeForMatch())
                ) {
                    continue
                }
                if (file.length() > config.maxReadBytes) continue
                file.useLines(StandardCharsets.UTF_8) { lines ->
                    lines.forEachIndexed { index, line ->
                        if (results.size >= config.maxSearchResults) return@useLines
                        if (matcher.containsMatchIn(line)) {
                            results += WorkspaceSearchMatch(
                                path = file.relativePath(rootCanonical),
                                line = index + 1,
                                text = line,
                            )
                        }
                    }
                }
            }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    /** [root] 必须已是 canonical 形式, 由调用方在循环外算一次 */
    private fun resolvePath(rootCanonical: File, path: String): File {
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val target = if (normalized == ".") {
            rootCanonical
        } else {
            File(rootCanonical, normalized).canonicalFile
        }
        val rootPath = rootCanonical.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root.canonicalFile, path)

    private fun File.toEntry(rootCanonical: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(rootCanonical),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    /**
     * resolvePath 返回的已是 canonical 路径, 其父目录沿用词法路径即可,
     * 不需要再对每个 entry 做一次 canonicalFile (每次都要解析整条父链)
     */
    private fun File.relativePath(rootCanonical: File): String {
        val parent = parentFile ?: rootCanonical
        return File(parent, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}

/**
 * Claim and write through one descriptor. The opener seam is internal for JVM
 * failure tests; production always uses CREATE_NEW + WRITE + NOFOLLOW_LINKS.
 * Intentionally no pathname-based cleanup, even if writing or closing fails.
 */
internal fun writeNewWorkspaceFile(
    path: Path,
    bytes: ByteArray,
    openChannel: (Path) -> SeekableByteChannel = { target ->
        Files.newByteChannel(
            target,
            setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
    },
) {
    val channel = try {
        openChannel(path)
    } catch (error: FileAlreadyExistsException) {
        throw WorkspaceFileAlreadyExistsException(error)
    }
    channel.use {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) it.write(buffer)
    }
}
