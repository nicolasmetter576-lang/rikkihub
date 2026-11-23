package me.rerere.rikkihub.data.book.transfer

import me.rerere.rikkihub.data.book.bookWordCount
import java.io.File
import java.io.InputStream

/** 预览里的一章；[source] 是拆分结果里的下标，改标题、合并都按它记。 */
data class ImportRow(val source: Int, val title: String, val words: Int, val volume: String?, val issues: Set<SplitIssue>)

/** 用户在预览里的改动，按 [ImportRow.source]。 */
data class ImportEdits(val titles: Map<Int, String> = emptyMap(), val merged: Set<Int> = emptySet())

/** 一个待导入的 TXT 或 EPUB 文件（按内容认，不看扩展名）。用完要关：EPUB 的图片在导入时才读。 */
class BookImportSource(private val file: File, val name: String) : AutoCloseable {
    private val reader: EpubReader? = if (isZip(file)) EpubReader(file) else null
    val isText: Boolean get() = reader == null
    private var decoded: TextDecoding.Decoded? = null

    /** 认出来或指定的编码；EPUB 为 null。 */
    val charset: String? get() = decoded?.charset

    private val epub: BookImport by lazy { BookImporter.fromEpub(checkNotNull(reader).read(), reader) }

    /** 按编码和拆章方式得到整本书；EPUB 不看这两项。 */
    fun load(charset: String? = null, rule: SplitRule = SplitRule.Auto): BookImport {
        if (reader != null) return epub
        if (file.length() > BookImporter.MAX_TXT_BYTES) throw BookImportException(BookImportException.Reason.TOO_LARGE)
        val text = TextDecoding.decode(file.readBytes(), charset)
            ?: throw BookImportException(BookImportException.Reason.UNKNOWN_ENCODING)
        decoded = text
        return BookImporter.fromText(name, text.text, ChapterSplitter.split(text.text, rule))
    }

    override fun close() {
        runCatching { reader?.close() }
    }

    companion object {
        const val MAX_FILE_BYTES = 200L * 1024 * 1024

        private fun isZip(file: File): Boolean = file.inputStream().use { input ->
            listOf(0x50, 0x4B, 3, 4).all { input.read() == it }
        }

        /** 把上传或选中的文件存成本地临时文件，超过上限就停。 */
        fun copy(input: InputStream, target: File) {
            target.parentFile?.mkdirs()
            try {
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        if (size > MAX_FILE_BYTES) throw BookImportException(BookImportException.Reason.TOO_LARGE)
                        out.write(buffer, 0, n)
                    }
                }
            } catch (failure: Exception) {
                target.delete()
                throw failure
            }
        }
    }
}

/** 应用预览里的改动。并掉的那章若是一卷的开头，卷交给下一章。 */
fun BookImport.edited(edits: ImportEdits): BookImport {
    val result = ArrayList<ImportChapter>()
    var pendingVolume: String? = null
    chapters.forEachIndexed { index, chapter ->
        if (index in edits.merged && result.isNotEmpty()) {
            val previous = result.removeAt(result.lastIndex)
            result += previous.copy(
                title = previous.title.ifEmpty { chapter.title },
                content = previous.content.trimEnd('\n') + "\n\n" + chapter.content,
            )
            if (chapter.volume != null) pendingVolume = chapter.volume
        } else {
            val title = edits.titles[index] ?: chapter.title
            val content = if (title == chapter.title) chapter.content else chapter.content.withTitle(chapter.title, title)
            result += chapter.copy(title = title, content = content, volume = chapter.volume ?: pendingVolume)
            pendingVolume = null
        }
    }
    return BookImport(title, author, language, extension, result, images, cover)
}

/** 正文第一行就是旧标题时一起改，免得导出时两个标题都在。 */
private fun String.withTitle(old: String, new: String): String {
    val start = indexOfFirst { !it.isWhitespace() && it != '#' }
    return if (old.isNotEmpty() && new.isNotEmpty() && start >= 0 && startsWith(old, start) && (start + old.length == length || this[start + old.length] == '\n' || this[start + old.length] == '\r')) {
        replaceRange(start, start + old.length, new)
    } else this
}

fun BookImport.rows(edits: ImportEdits): List<ImportRow> {
    val sources = chapters.indices.filter { it == 0 || it !in edits.merged }
    val kept = edited(edits).chapters
    val words = kept.map { bookWordCount(it.content) }
    // 章号只有 TXT 的标题才有；过长过短两种都看
    val issues = splitIssues(kept.map { SplitPart(it.title, 0, 0, it.number, it.volume) }, words)
    return kept.indices.map { ImportRow(sources[it], kept[it].title, words[it], kept[it].volume, issues[it]) }
}
