package me.rerere.rikkihub.data.book

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.book.transfer.BookExporter
import me.rerere.rikkihub.data.book.transfer.ExportOptions
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.pages.extensions.workspace.isReadableName
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceFileRevisionConflictException
import me.rerere.workspace.WorkspaceFileSystem
import me.rerere.workspace.WorkspaceTextSnapshot
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 作品工作区的数据层（App、Web、模型工具共用）。作品 = 整个工作区的 FILES 根目录，结构在 `.book/book.json`。
 *
 * 每个作品一把锁；结构总是读最新、改、带版本校验写回，Agent 或用户同时改文件也不会被盖掉。
 * 量文件（字数、哈希、标题）的结果缓存在 [cacheDir]，丢了只是重新量一遍。
 */
class BookStore(
    private val repository: WorkspaceRepository,
    private val cacheDir: File,
) {
    private val fileSystem = WorkspaceFileSystem(
        WorkspaceConfig(maxReadBytes = MAX_TEXT_BYTES, maxWriteBytes = MAX_TEXT_BYTES, maxListEntries = Int.MAX_VALUE),
    )
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** 结构被改过的工作区 id。 */
    val changes = _changes.asSharedFlow()

    /** 打开作品；还没有结构时按名字像章节的文件建一份。 */
    suspend fun open(workspaceId: String): Book = locked(workspaceId) { workspace, root ->
        val files = scan(workspaceId, root)
        assembleBook(workspaceId, loadManifest(workspace, root, files).first, files)
    }

    /** 改结构。[transform] 可能因冲突重跑，必须没有副作用；抛异常则不写。 */
    suspend fun edit(workspaceId: String, transform: (Book) -> BookManifest): Book = locked(workspaceId) { workspace, root ->
        val files = scan(workspaceId, root)
        var result: BookManifest? = null
        for (attempt in 1..WRITE_ATTEMPTS) {
            val (manifest, revision) = loadManifest(workspace, root, files)
            val next = transform(assembleBook(workspaceId, manifest, files))
            if (next == manifest) {
                result = manifest
                break
            }
            try {
                fileSystem.writeTextWithRevision(root, MANIFEST_PATH, next.encode(), revision)
                result = next
                break
            } catch (conflict: WorkspaceFileRevisionConflictException) {
                if (attempt == WRITE_ATTEMPTS) throw conflict
            }
        }
        _changes.tryEmit(workspaceId)
        assembleBook(workspaceId, checkNotNull(result), files)
    }

    /** 结构文件坏了：改名留底，重新建一份。 */
    suspend fun rebuild(workspaceId: String): Book {
        locked(workspaceId) { _, root ->
            val manifest = File(root, MANIFEST_PATH)
            if (manifest.exists()) fileSystem.move(root, MANIFEST_PATH, "$BOOK_DIR/book-${System.currentTimeMillis()}.broken.json")
        }
        _changes.tryEmit(workspaceId)
        return open(workspaceId)
    }

    /** 不存在的章节文件由用户指到另一个文件上。 */
    suspend fun relink(workspaceId: String, chapterId: String, path: String): Book = edit(workspaceId) { book ->
        require(path in book.unlisted) { "Not an unlisted file: $path" }
        book.manifest.updateChapter(chapterId) { it.copy(path = path) }
    }

    suspend fun read(workspaceId: String, path: String): WorkspaceTextSnapshot =
        repository.withFilesRoot(workspaceId) { root -> fileSystem.readTextSnapshot(root, path) }

    /** 在作品根目录上做一段文件操作（导入导出用）。 */
    suspend fun <T> withRoot(workspaceId: String, block: suspend (File) -> T): T = repository.withFilesRoot(workspaceId, block)

    /** 根目录下的二进制文件（图片）；不存在、越界或太大返回 null。 */
    suspend fun readBytes(workspaceId: String, path: String): ByteArray? = repository.withFilesRoot(workspaceId) { root ->
        val base = root.canonicalFile
        val file = File(base, path).canonicalFile
        if (!file.path.startsWith(base.path + File.separator) || !file.isFile || file.length() > MAX_IMAGE_BYTES) null else file.readBytes()
    }

    fun exporter(workspaceId: String) = BookExporter(
        readText = { path -> read(workspaceId, path).content },
        readImage = { path -> readBytes(workspaceId, path) },
    )

    /** 导出成作品里的一个文件（同名覆盖，先写临时文件）。这个文件不编入目录。 */
    suspend fun exportInto(book: Book, options: ExportOptions, path: String) {
        val staged = File.createTempFile("export", ".tmp", cacheDir.apply { mkdirs() })
        try {
            withContext(Dispatchers.IO) { staged.outputStream().use { exporter(book.workspaceId).export(book, options, it) } }
            repository.withFilesRoot(book.workspaceId) { root ->
                fileSystem.mkdir(root, path.substringBeforeLast('/'))
                fileSystem.replaceWith(root, path, staged)
            }
            edit(book.workspaceId) { it.manifest.copy(ignored = it.manifest.ignored + path) }
        } finally {
            staged.delete()
        }
    }

    /** 版本没变时，在存储根锁里拿现在的内容做 [block]（加锁用，中间不会被别的写入插进来）；变了抛 [WorkspaceFileRevisionConflictException]。 */
    suspend fun <T> withRevision(workspaceId: String, path: String, expectedRevision: String, block: (ByteArray) -> T): T =
        repository.withFilesRoot(workspaceId) { root -> fileSystem.withRevision(root, path, expectedRevision, block) }

    /** 版本没变才写，返回新版本；变了抛 [WorkspaceFileRevisionConflictException]。 */
    suspend fun write(workspaceId: String, path: String, text: String, expectedRevision: String): String =
        repository.withFilesRoot(workspaceId) { root -> fileSystem.writeTextWithRevision(root, path, text, expectedRevision).revision }

    /** 新建章节文件并编入；[afterId] 见 [BookManifest.addChapters]。 */
    suspend fun createChapter(workspaceId: String, path: String, text: String, afterId: String?, title: String? = null): Book {
        repository.withFilesRoot(workspaceId) { root ->
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { fileSystem.mkdir(root, it) }
            fileSystem.createFile(root, path, text)
        }
        return edit(workspaceId) { book ->
            book.manifest.addChapters(listOf(BookChapter(BookManifest.newId(), path, title = title)), afterId)
        }
    }

    /** 章节文件改名（同目录），结构一起改。 */
    suspend fun renameChapterFile(workspaceId: String, chapterId: String, fileName: String): Book {
        val chapter = open(workspaceId).manifest.chapter(chapterId) ?: throw NoSuchElementException("Chapter not found")
        val folder = chapter.path.substringBeforeLast('/', "")
        val target = if (folder.isEmpty()) fileName else "$folder/$fileName"
        if (target == chapter.path) return open(workspaceId)
        repository.withFilesRoot(workspaceId) { root -> fileSystem.move(root, chapter.path, target) }
        return edit(workspaceId) { book -> book.manifest.updateChapter(chapterId) { it.copy(path = target) } }
    }

    /** 删一个不在目录里的文件（作品记录的文件由版本史的删除钩子移进回收站）。 */
    suspend fun deleteFile(workspaceId: String, path: String) {
        repository.withFilesRoot(workspaceId) { root -> fileSystem.delete(root, path) }
    }

    /** 删除章节：文件和目录项一起删。 */
    /** 逐个删：删掉的照样从目录里移除；删不了的（例如锁定的章节进不了回收站）留在目录里，最后一起报错。 */
    suspend fun deleteChapters(workspaceId: String, ids: Set<String>): Book {
        val entries = open(workspaceId).entries.filter { it.id in ids }
        val failed = LinkedHashMap<String, Exception>()
        repository.withFilesRoot(workspaceId) { root ->
            entries.filterNot { it.missing }.forEach { entry ->
                try {
                    fileSystem.delete(root, entry.path)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    failed[entry.id] = error
                }
            }
        }
        val book = edit(workspaceId) { book -> book.manifest.remove(ids - failed.keys) }
        // 锁挡住的原样抛（网页端回 423，App 按原因说明）；其他的合成一条
        failed.values.firstOrNull { it is me.rerere.workspace.WorkspaceLockedException }?.let { throw it }
        failed.values.firstOrNull()?.let { first ->
            throw IOException(entries.filter { it.id in failed }.joinToString("、") { it.path } + ": " + (first.message ?: first.javaClass.simpleName), first)
        }
        return book
    }

    // ---- 章节笔记（计划、反向大纲、节拍稿） --------------------------------------------------------

    private val _noteChanges = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    /** 笔记被改过的（工作区 id，章节 id）。 */
    val noteChanges = _noteChanges.asSharedFlow()

    suspend fun notes(workspaceId: String, chapterId: String): ChapterNotes = repository.withFilesRoot(workspaceId) { root ->
        readNotes(root, notesPath(chapterId))?.first ?: ChapterNotes()
    }

    /** 改一章的笔记；[transform] 可能因冲突重跑，必须没有副作用。坏了的笔记文件改名留底后从空的开始。 */
    suspend fun editNotes(workspaceId: String, chapterId: String, transform: (ChapterNotes) -> ChapterNotes): ChapterNotes {
        val path = notesPath(chapterId)
        val result = locked(workspaceId) { _, root ->
            var saved: ChapterNotes? = null
            for (attempt in 1..WRITE_ATTEMPTS) {
                val current = try {
                    readNotes(root, path)
                } catch (broken: ChapterNotesException) {
                    if (broken.reason != ChapterNotesException.Reason.CORRUPT) throw broken
                    fileSystem.move(root, path, path.removeSuffix(".json") + "-${System.currentTimeMillis()}.broken.json")
                    null
                }
                val next = transform(current?.first ?: ChapterNotes())
                if (next == (current?.first ?: ChapterNotes())) {
                    saved = next
                    break
                }
                try {
                    if (current == null) {
                        fileSystem.mkdir(root, NOTES_DIR)
                        fileSystem.createFile(root, path, next.encode())
                    } else fileSystem.writeTextWithRevision(root, path, next.encode(), current.second)
                    saved = next
                    break
                } catch (conflict: WorkspaceFileRevisionConflictException) {
                    if (attempt == WRITE_ATTEMPTS) throw conflict
                }
            }
            checkNotNull(saved)
        }
        _noteChanges.tryEmit(workspaceId to chapterId)
        return result
    }

    private fun readNotes(root: File, path: String): Pair<ChapterNotes, String>? {
        if (!File(root, path).exists()) return null
        val snapshot = try {
            fileSystem.readTextSnapshot(root, path)
        } catch (_: WorkspaceFileOperationException) {
            throw ChapterNotesException(ChapterNotesException.Reason.CORRUPT)
        }
        return ChapterNotes.decode(snapshot.content) to snapshot.revision
    }

    /** 章节 id 可能是手改进结构文件的，不能直接当文件名。 */
    private fun notesPath(chapterId: String): String {
        val name = if (SAFE_ID.matches(chapterId)) chapterId else ChapterNotes.fingerprint(listOf(chapterId))
        return "$NOTES_DIR/$name.json"
    }

    private suspend fun <T> locked(workspaceId: String, block: suspend (WorkspaceEntity, File) -> T): T {
        val workspace = repository.getById(workspaceId) ?: throw NoSuchElementException("Workspace not found: $workspaceId")
        return locks.getOrPut(workspaceId) { Mutex() }.withLock {
            repository.withFilesRoot(workspaceId) { root -> block(workspace, root) }
        }
    }

    private fun loadManifest(workspace: WorkspaceEntity, root: File, files: Map<String, BookFileInfo>): Pair<BookManifest, String> {
        if (!File(root, MANIFEST_PATH).exists()) {
            val manifest = initialManifest(workspace.name, workspace.bookPromptLanguage, files)
            fileSystem.mkdir(root, BOOK_DIR)
            fileSystem.createFile(root, MANIFEST_PATH, manifest.encode())
        }
        val snapshot = readManifestText(root)
        val manifest = BookManifest.decode(snapshot.content)
        val followed = followRenames(manifest, files, remembered(workspace.id))
        if (followed == manifest) return manifest to snapshot.revision
        val saved = fileSystem.writeTextWithRevision(root, MANIFEST_PATH, followed.encode(), snapshot.revision)
        return followed to saved.revision
    }

    private fun readManifestText(root: File): WorkspaceTextSnapshot = try {
        fileSystem.readTextSnapshot(root, MANIFEST_PATH)
    } catch (_: WorkspaceFileOperationException) {
        throw BookManifestException(BookManifestException.Reason.CORRUPT)
    }

    // ---- 文件扫描 ----------------------------------------------------------------------------

    /** 根目录下所有可写作的文本文件（跳过点目录和软链），并更新记住的文件信息。 */
    private fun scan(workspaceId: String, root: File): Map<String, BookFileInfo> {
        val known = remembered(workspaceId)
        val found = LinkedHashMap<String, BookFileInfo>()
        root.walkTopDown()
            .onEnter { it == root || (!it.name.startsWith(".") && it.name != "node_modules" && !java.nio.file.Files.isSymbolicLink(it.toPath())) }
            .filter { it.isFile && !it.name.startsWith(".") && isReadableName(it.name) && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .take(MAX_FILES)
            .forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val size = file.length()
                val modifiedAt = file.lastModified()
                found[path] = known[path]?.takeIf { it.size == size && it.modifiedAt == modifiedAt } ?: measure(path, file, size, modifiedAt)
            }
        // 没了的文件也记着：改名认回要用它的哈希
        if (found.any { known[it.key] != it.value }) remember(workspaceId, known + found)
        return found
    }

    private fun measure(path: String, file: File, size: Long, modifiedAt: Long): BookFileInfo {
        if (size > MAX_TEXT_BYTES) return BookFileInfo(size, modifiedAt, sha256 = "size:$size:$path", words = 0)
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        return BookFileInfo(
            size = size,
            modifiedAt = modifiedAt,
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            words = bookWordCount(text),
            heading = firstHeading(path, text),
        )
    }

    private val indexes = ConcurrentHashMap<String, Map<String, BookFileInfo>>()

    private fun indexFile(workspaceId: String) = File(cacheDir, "$workspaceId.json")

    /** 工作区删除后丢掉它的文件索引缓存。 */
    fun forget(workspaceId: String) {
        indexes.remove(workspaceId)
        indexFile(workspaceId).delete()
    }

    private fun remembered(workspaceId: String): Map<String, BookFileInfo> = indexes.getOrPut(workspaceId) {
        runCatching { JsonInstant.decodeFromString<Map<String, BookFileInfo>>(indexFile(workspaceId).readText()) }.getOrDefault(emptyMap())
    }

    private fun remember(workspaceId: String, files: Map<String, BookFileInfo>) {
        indexes[workspaceId] = files
        runCatching {
            cacheDir.mkdirs()
            indexFile(workspaceId).writeText(JsonInstant.encodeToString(files))
        }
    }

    companion object {
        const val BOOK_DIR = ".book"
        const val MANIFEST_PATH = "$BOOK_DIR/book.json"
        /** 平行稿（点子 11），[BOOK_DIR] 里唯一允许模型文件工具写的地方。 */
        const val BRANCHES_DIR = "$BOOK_DIR/branches"
        private const val NOTES_DIR = "$BOOK_DIR/chapters"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val MAX_TEXT_BYTES = 4L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_FILES = 20_000
        private const val WRITE_ATTEMPTS = 3
    }
}
