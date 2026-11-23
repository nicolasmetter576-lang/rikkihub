package me.rerere.rikkihub.data.book.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookItem
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.BookVolume
import java.io.File

class BookImportException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { TOO_LARGE, UNKNOWN_ENCODING, NO_TEXT }
}

/** 要导入的一章；[content] 原样写进文件。 */
data class ImportChapter(
    val title: String,
    val content: String,
    /** 不为 null 且与前一章不同 = 新起一卷。 */
    val volume: String? = null,
    val number: Int? = null,
)

/** 准备好、等用户确认的导入。[images]：要写进 `images/` 的文件名 → 内容来源。 */
class BookImport(
    val title: String,
    val author: String,
    val language: String,
    val extension: String,
    val chapters: List<ImportChapter>,
    val images: Map<String, () -> ByteArray?> = emptyMap(),
    val cover: String? = null,
)

/** 把导入好的一本书写进作品工作区：先写临时文件夹，全部成功再就位；中途失败不留半本。 */
class BookImporter(private val store: BookStore) {
    suspend fun import(workspaceId: String, import: BookImport): Book = withContext(Dispatchers.IO) {
        val book = store.open(workspaceId)
        val names = fileNames(import, offset = book.entries.size)
        val moved = ArrayList<File>()
        try {
            val imageDir = store.withRoot(workspaceId) { root -> write(root, import, names, moved) }
            store.edit(workspaceId) { current -> merge(current, import, names, book.entries.isEmpty(), imageDir) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { moved.forEach { it.delete() } }
            throw failure
        }
    }

    /** 写好所有文件，返回图片放在哪个文件夹。 */
    private fun write(root: File, import: BookImport, names: List<String>, moved: MutableList<File>): String {
        val staging = File(root, "${BookStore.BOOK_DIR}/import-${System.currentTimeMillis()}")
        try {
            check(staging.mkdirs()) { "Cannot create a folder in the workspace" }
            names.firstOrNull { File(root, it).exists() }?.let { error("File already exists: $it") }
            val images = import.images.mapNotNull { (name, load) -> load()?.let { name to it } }
            // 正文里引用的图片名准备阶段就定了：撞上已有文件时换一个新文件夹
            val imageDir = if (images.any { File(root, "$IMAGE_DIR/${it.first}").exists() }) {
                generateSequence(2) { it + 1 }.map { "$IMAGE_DIR-$it" }.first { !File(root, it).exists() }
            } else IMAGE_DIR
            import.chapters.forEachIndexed { index, chapter ->
                File(staging, names[index]).writeText(
                    if (imageDir == IMAGE_DIR) chapter.content else chapter.content.replace("]($IMAGE_DIR/", "]($imageDir/"),
                )
            }
            images.forEach { (name, bytes) -> File(staging, "images").apply { mkdirs() }.resolve(name).writeBytes(bytes) }
            names.forEach { place(File(staging, it), File(root, it), moved) }
            images.forEach { (name, _) -> place(File(staging, "images/$name"), File(root, "$imageDir/$name"), moved) }
            return imageDir
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun place(from: File, to: File, moved: MutableList<File>) {
        to.parentFile?.mkdirs()
        check(from.renameTo(to)) { "Cannot write ${to.name}" }
        moved += to
    }

    private fun merge(book: Book, import: BookImport, names: List<String>, empty: Boolean, imageDir: String): BookManifest {
        val items = ArrayList<BookItem>()
        var volume: String? = null
        import.chapters.forEachIndexed { index, chapter ->
            if (chapter.volume != null && chapter.volume != volume) {
                items += BookVolume(BookManifest.newId(), chapter.volume)
                volume = chapter.volume
            }
            items += BookChapter(BookManifest.newId(), names[index], title = chapter.title.ifBlank { null })
        }
        val manifest = book.manifest.copy(items = book.manifest.items + items)
        if (!empty) return manifest
        return manifest.copy(
            title = import.title.ifBlank { manifest.title },
            author = import.author.ifBlank { manifest.author },
            language = import.language.ifBlank { manifest.language },
            cover = import.cover?.let { "$imageDir/$it" } ?: manifest.cover,
        )
    }

    companion object {
        const val MAX_TXT_BYTES = 20L * 1024 * 1024
        const val IMAGE_DIR = "images"
        private const val MAX_NAME_BYTES = 180

        /** `0001 标题.txt`：补零，字母序就是书序；重名加后缀。 */
        internal fun fileNames(import: BookImport, offset: Int): List<String> {
            val width = maxOf(4, (offset + import.chapters.size).toString().length)
            return import.chapters.mapIndexed { index, chapter ->
                val number = (offset + index + 1).toString().padStart(width, '0')
                val title = safeName(chapter.title)
                (if (title.isEmpty()) number else "$number $title") + "." + import.extension
            }
        }

        internal fun safeName(title: String): String {
            val cleaned = title.map { if (it.isISOControl() || it in "/\\:*?\"<>|") ' ' else it }.joinToString("")
                .replace(Regex("\\s+"), " ").trim().trimEnd('.')
            val out = StringBuilder()
            var bytes = 0
            var index = 0
            while (index < cleaned.length) {
                val cp = cleaned.codePointAt(index)
                // 落单的代理字符不能进文件名
                if (cp in 0xD800..0xDFFF) {
                    index++
                    continue
                }
                val text = String(Character.toChars(cp))
                bytes += text.toByteArray().size
                if (bytes > MAX_NAME_BYTES) break
                out.append(text)
                index += text.length
            }
            return out.toString().trim()
        }

        /** 拆好的 TXT → 可导入的章。 */
        fun fromText(fileName: String, text: String, parts: List<SplitPart>): BookImport {
            val chapters = parts.map { part ->
                ImportChapter(part.title, text.substring(part.start, part.end).trim('\n', '\r', ' ', '\uFEFF') + "\n", part.volume, part.number)
            }
            if (chapters.isEmpty()) throw BookImportException(BookImportException.Reason.NO_TEXT)
            return BookImport(fileName.substringBeforeLast('.'), "", "", "txt", chapters)
        }

        /** 读好的 EPUB → 可导入的书；[reader] 在导入完成前要保持打开。 */
        fun fromEpub(book: EpubBook, reader: EpubReader): BookImport {
            val chapters = book.chapters.map { chapter ->
                // 正文自带同名标题就不重复写
                val heading = chapter.markdown.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')?.trim()
                val content = if (chapter.title.isBlank() || heading == chapter.title) chapter.markdown else "# ${chapter.title}\n\n${chapter.markdown}"
                ImportChapter(chapter.title, content, chapter.volume)
            }
            if (chapters.isEmpty()) throw BookImportException(BookImportException.Reason.NO_TEXT)
            return BookImport(book.title, book.author, book.language.substringBefore('-'), "md", chapters, book.images.mapValues { (_, path) -> { reader.image(path) } }, book.cover)
        }
    }
}
