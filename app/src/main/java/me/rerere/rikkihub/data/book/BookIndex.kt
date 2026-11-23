package me.rerere.rikkihub.data.book

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceWritingFormat
import me.rerere.rikkihub.ui.pages.extensions.workspace.naturalNameOrder
import me.rerere.rikkihub.ui.pages.extensions.workspace.workspaceTextStats

/** 一个文本文件量出来的东西；[size] 与 [modifiedAt] 没变就不重读。 */
@Serializable
data class BookFileInfo(
    val size: Long,
    val modifiedAt: Long,
    val sha256: String,
    /** 字数：中日文为主的按字符（含标点），其余按词。 */
    val words: Int,
    val heading: String? = null,
)

/** 章节在界面和工具里的样子。[number] 从 1 起；[file] 为 null = 文件找不到。 */
data class BookChapterEntry(
    val chapter: BookChapter,
    val number: Int,
    val volumeId: String?,
    val title: String,
    val file: BookFileInfo?,
) {
    val id: String get() = chapter.id
    val path: String get() = chapter.path
    val missing: Boolean get() = file == null
    val words: Int get() = file?.words ?: 0
}

/** 打开的作品：结构 + 磁盘现状。[unlisted] 是还没编入、也没被忽略的文本文件。 */
data class Book(
    val workspaceId: String,
    val manifest: BookManifest,
    val entries: List<BookChapterEntry>,
    val unlisted: List<String>,
) {
    val words: Int get() = entries.sumOf { it.words }

    fun entry(id: String): BookChapterEntry? = entries.firstOrNull { it.id == id }

    fun entryByPath(path: String): BookChapterEntry? = entries.firstOrNull { it.path == path.trim('/') }

    fun entryByNumber(number: Int): BookChapterEntry? = entries.getOrNull(number - 1)
}

/** 结构和文件列表都变成界面要的样子（纯函数）。 */
internal fun assembleBook(workspaceId: String, manifest: BookManifest, files: Map<String, BookFileInfo>): Book {
    var volumeId: String? = null
    var number = 0
    val entries = manifest.items.mapNotNull { item ->
        when (item) {
            is BookVolume -> {
                volumeId = item.id
                null
            }
            is BookChapter -> {
                val file = files[item.path]
                BookChapterEntry(item, ++number, volumeId, chapterTitle(item, file), file)
            }
        }
    }
    val listed = entries.mapTo(HashSet()) { it.path }
    return Book(
        workspaceId = workspaceId,
        manifest = manifest,
        entries = entries,
        unlisted = files.keys.filter { it !in listed && it !in manifest.ignored }.sortedWith(naturalNameOrder),
    )
}

internal fun chapterTitle(chapter: BookChapter, file: BookFileInfo?): String =
    chapter.title ?: file?.heading ?: chapter.path.substringAfterLast('/').substringBeforeLast('.')

/**
 * 按内容哈希认回在别处改了名的章节：原路径没了，而没编入的文件里恰好只有一个内容相同，
 * 丢失的章节里也只有它用过这份内容。有歧义就不猜。
 */
internal fun followRenames(
    manifest: BookManifest,
    files: Map<String, BookFileInfo>,
    remembered: Map<String, BookFileInfo>,
): BookManifest {
    val missing = manifest.chapters.filter { it.path !in files }
    if (missing.isEmpty()) return manifest
    val listed = manifest.chapters.mapTo(HashSet()) { it.path }
    val candidates = files.entries.filter { it.key !in listed }.groupBy({ it.value.sha256 }, { it.key })
    val lost = missing.groupBy { remembered[it.path]?.sha256 }
    var result = manifest
    lost.forEach { (sha, chapters) ->
        val chapter = chapters.singleOrNull() ?: return@forEach
        val path = candidates[sha]?.singleOrNull() ?: return@forEach
        result = result.updateChapter(chapter.id) { it.copy(path = path) }
            .let { it.copy(ignored = it.ignored - path) }
    }
    return result
}

/** 第一次打开作品：只收名字像章节的文件，子文件夹变成卷；其余留给用户挑。 */
internal fun initialManifest(title: String, language: String, files: Map<String, BookFileInfo>): BookManifest {
    val chapters = files.keys.filter { looksLikeChapter(it.substringAfterLast('/')) }
    val (root, nested) = chapters.partition { '/' !in it }
    val items = buildList<BookItem> {
        root.sortedWith(naturalNameOrder).forEach { add(BookChapter(BookManifest.newId(), it)) }
        nested.groupBy { it.substringBeforeLast('/') }.toSortedMap(naturalNameOrder).forEach { (folder, paths) ->
            add(BookVolume(BookManifest.newId(), folder.substringAfterLast('/')))
            paths.sortedWith(naturalNameOrder).forEach { add(BookChapter(BookManifest.newId(), it)) }
        }
    }
    return BookManifest(id = BookManifest.newId(), title = title, language = language, items = items)
}

private const val CHAPTER_NUMBER = "[0-9０-９零〇○◯一二三四五六七八九十百千万两廿卅]+"
private val CHAPTER_NAME = Regex(
    "^(?:\\d+(?:[\\s._\\-—、　].*)?|(?:正文\\s*)?第\\s*$CHAPTER_NUMBER\\s*[章节回折幕话].*|(?:chapter|ch)[\\s._-]*\\d.*|(?:chapter|prologue|epilogue)\\b.*|" +
        "(?:序章|序幕|楔子|引子|尾声|终章|后记|番外).*)$",
    RegexOption.IGNORE_CASE,
)

internal fun looksLikeChapter(fileName: String): Boolean = CHAPTER_NAME.matches(fileName.substringBeforeLast('.').trim())

private val HEADING_LINE = Regex("^#{1,6}\\s+(.+?)\\s*#*\\s*$")
private const val HEADING_SEARCH_LINES = 20

/** Markdown 文件开头的第一个标题；纯文本没有标题。 */
internal fun firstHeading(path: String, text: String): String? {
    if (path.substringAfterLast('.').lowercase() !in setOf("md", "markdown")) return null
    return text.lineSequence().take(HEADING_SEARCH_LINES).firstNotNullOfOrNull { HEADING_LINE.matchEntire(it)?.groupValues?.get(1) }
        ?.takeIf { it.isNotBlank() }
}

internal fun bookWordCount(text: String): Int {
    val stats = workspaceTextStats(text)
    val cjk = text.codePoints().filter { WorkspaceWritingFormat.isCjk(it) }.count()
    return if (cjk * 2 >= stats.words) stats.chars else stats.words
}
