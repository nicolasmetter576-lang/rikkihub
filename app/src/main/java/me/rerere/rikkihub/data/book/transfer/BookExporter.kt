package me.rerere.rikkihub.data.book.transfer

import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapterEntry
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterStatus
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mime: String) {
    TXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
    EPUB("epub", "application/epub+zip"),
}

data class ExportOptions(
    val format: ExportFormat,
    /** null = 全书。 */
    val volumeId: String? = null,
    /** 只要这些章；null = 不限。 */
    val chapterIds: Set<String>? = null,
    val finalOnly: Boolean = false,
    val volumeTitles: Boolean = true,
    /** 段首空两格（TXT、EPUB）。 */
    val indent: Boolean = true,
    /** 段间空一行（TXT）。 */
    val blankLines: Boolean = false,
)

/** 导出哪些章：[missing] 是找不到文件、导出不了的，要先告诉用户。 */
data class ExportPlan(val chapters: List<BookChapterEntry>, val missing: List<BookChapterEntry>)

internal fun exportPlan(book: Book, options: ExportOptions): ExportPlan {
    val chosen = book.entries.filter { entry ->
        (options.volumeId == null || entry.volumeId == options.volumeId) &&
            (options.chapterIds == null || entry.id in options.chapterIds) &&
            (!options.finalOnly || entry.chapter.status == ChapterStatus.FINAL)
    }
    return ExportPlan(chosen.filterNot { it.missing }, chosen.filter { it.missing })
}

/** 一个自然段或结构块；一行正文就是一段。 */
internal sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String, val quote: Boolean) : Block
    data class Image(val alt: String, val path: String) : Block
    data object Rule : Block
}

/** 作品导出：TXT、合并的 Markdown、EPUB 3（带 ncx，老阅读器也能用）。 */
class BookExporter(
    /** 章节正文。 */
    private val readText: suspend (path: String) -> String,
    /** 章节引用的图片、封面；没有返回 null。 */
    private val readImage: suspend (path: String) -> ByteArray?,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun export(book: Book, options: ExportOptions, output: OutputStream) {
        val chapters = exportPlan(book, options).chapters
        when (options.format) {
            ExportFormat.TXT -> output.write(txt(book, chapters, options).toByteArray())
            ExportFormat.MARKDOWN -> output.write(markdown(book, chapters, options).toByteArray())
            ExportFormat.EPUB -> epub(book, chapters, options, output)
        }
    }

    private fun volumeTitle(book: Book, id: String?): String? =
        book.manifest.items.filterIsInstance<BookVolume>().firstOrNull { it.id == id }?.title

    private suspend fun body(entry: BookChapterEntry): String = stripTitle(readText(entry.path), entry.title)

    private suspend fun txt(book: Book, chapters: List<BookChapterEntry>, options: ExportOptions): String = buildString {
        append(book.manifest.title).append('\n')
        if (book.manifest.author.isNotBlank()) append(book.manifest.author).append('\n')
        var volume: String? = null
        for (entry in chapters) {
            val title = volumeTitle(book, entry.volumeId)
            if (options.volumeTitles && title != null && entry.volumeId != volume) append("\n\n").append(title).append('\n')
            volume = entry.volumeId
            append("\n\n").append(entry.title).append("\n\n")
            val paragraphs = blocks(entry.path, body(entry)).mapNotNull { block ->
                when (block) {
                    is Block.Heading -> plain(block.text)
                    is Block.Paragraph -> {
                        val text = if (isMarkdown(entry.path)) plain(block.text) else block.text
                        if (options.indent) "　　" + text.trimStart(' ', '　', '\t') else text
                    }
                    is Block.Image -> null
                    Block.Rule -> "＊　＊　＊"
                }
            }
            append(paragraphs.joinToString(if (options.blankLines) "\n\n" else "\n")).append('\n')
        }
    }

    private suspend fun markdown(book: Book, chapters: List<BookChapterEntry>, options: ExportOptions): String = buildString {
        append("# ").append(book.manifest.title).append('\n')
        if (book.manifest.author.isNotBlank()) append('\n').append(book.manifest.author).append('\n')
        var volume: String? = null
        for (entry in chapters) {
            val title = volumeTitle(book, entry.volumeId)
            if (options.volumeTitles && title != null && entry.volumeId != volume) append("\n# ").append(title).append('\n')
            volume = entry.volumeId
            append("\n## ").append(entry.title).append("\n\n")
            val text = body(entry)
            // 纯文本里一行就是一段：补空行，Markdown 才不会并段
            append(if (isMarkdown(entry.path)) text.trim() else text.lines().filter { it.isNotBlank() }.joinToString("\n\n") { escapeMarkdown(it.trim()) })
            append('\n')
        }
    }

    // ---- EPUB --------------------------------------------------------------------------------

    private class Page(val file: String, val title: String, val volume: String?, val xhtml: String)

    private suspend fun epub(book: Book, chapters: List<BookChapterEntry>, options: ExportOptions, output: OutputStream) {
        val images = LinkedHashMap<String, Pair<String, ByteArray>>()
        suspend fun imageName(path: String): String? {
            images[path]?.let { return it.first }
            val type = path.substringAfterLast('.', "").lowercase()
            if (type !in IMAGE_TYPES) return null
            val bytes = readImage(path) ?: return null
            val name = "image${images.size + 1}.$type"
            images[path] = name to bytes
            return name
        }
        val cover = book.manifest.cover.takeIf { it.isNotBlank() }?.let { imageName(it) }
        val pages = ArrayList<Page>()
        var volume: String? = null
        for ((index, entry) in chapters.withIndex()) {
            val volumeName = volumeTitle(book, entry.volumeId).takeIf { options.volumeTitles }
            if (volumeName != null && entry.volumeId != volume) {
                pages += Page("volume${pages.size + 1}.xhtml", volumeName, null, page(volumeName, "<h1 class=\"volume\">${xml(volumeName)}</h1>"))
            }
            volume = entry.volumeId
            val folder = entry.path.substringBeforeLast('/', "")
            val content = buildString {
                append("<h2>").append(xml(entry.title)).append("</h2>\n")
                for (block in blocks(entry.path, body(entry))) when (block) {
                    is Block.Heading -> append("<h${(block.level + 1).coerceIn(3, 6)}>").append(inline(block.text)).append("</h${(block.level + 1).coerceIn(3, 6)}>\n")
                    is Block.Paragraph -> {
                        val text = if (isMarkdown(entry.path)) inline(block.text.trim { it == ' ' || it == '　' || it == '\t' })
                        else xml(block.text.trim { it == ' ' || it == '　' || it == '\t' })
                        if (block.quote) append("<blockquote><p>").append(text).append("</p></blockquote>\n") else append("<p>").append(text).append("</p>\n")
                    }
                    is Block.Image -> imageName(resolveEpubPath(folder, block.path))?.let {
                        append("<p class=\"image\"><img src=\"../images/").append(it).append("\" alt=\"").append(xml(block.alt)).append("\"/></p>\n")
                    }
                    Block.Rule -> append("<hr/>\n")
                }
            }
            pages += Page("chapter%04d.xhtml".format(java.util.Locale.ROOT, index + 1), entry.title, volumeName, page(entry.title, content))
        }
        val id = "urn:uuid:${book.manifest.id}"
        val language = book.manifest.language.ifBlank { "zh" }
        ZipOutputStream(output).apply {
            stored("mimetype", "application/epub+zip")
            text("META-INF/container.xml", CONTAINER)
            text("OEBPS/style.css", if (options.indent) CSS + "p{text-indent:2em}\n.image,blockquote p{text-indent:0}\n" else CSS)
            text("OEBPS/content.opf", opf(book, id, language, pages, images.values.map { it.first }, cover))
            text("OEBPS/nav.xhtml", nav(book, language, pages))
            text("OEBPS/toc.ncx", ncx(book, id, pages))
            pages.forEach { text("OEBPS/text/${it.file}", it.xhtml) }
            if (cover != null) text("OEBPS/text/cover.xhtml", page(book.manifest.title, "<p class=\"image\"><img src=\"../images/$cover\" alt=\"\"/></p>"))
            images.values.forEach { (name, bytes) -> entry("OEBPS/images/$name", bytes) }
            finish()
        }
    }

    private fun opf(book: Book, id: String, language: String, pages: List<Page>, images: List<String>, cover: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("<dc:identifier id=\"id\">").append(xml(id)).append("</dc:identifier>\n")
        append("<dc:title>").append(xml(book.manifest.title.ifBlank { "Untitled" })).append("</dc:title>\n")
        if (book.manifest.author.isNotBlank()) append("<dc:creator>").append(xml(book.manifest.author)).append("</dc:creator>\n")
        append("<dc:language>").append(xml(language)).append("</dc:language>\n")
        append("<meta property=\"dcterms:modified\">").append(now().truncatedTo(ChronoUnit.SECONDS)).append("</meta>\n")
        if (cover != null) append("<meta name=\"cover\" content=\"img-").append(cover.replace('.', '-')).append("\"/>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
        if (cover != null) append("<item id=\"cover-page\" href=\"text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
        pages.forEachIndexed { index, page -> append("<item id=\"p$index\" href=\"text/${page.file}\" media-type=\"application/xhtml+xml\"/>\n") }
        images.forEach { name ->
            append("<item id=\"img-${name.replace('.', '-')}\" href=\"images/$name\" media-type=\"${IMAGE_TYPES.getValue(name.substringAfterLast('.'))}\"")
            if (name == cover) append(" properties=\"cover-image\"")
            append("/>\n")
        }
        append("</manifest>\n<spine toc=\"ncx\">\n")
        if (cover != null) append("<itemref idref=\"cover-page\"/>\n")
        pages.indices.forEach { append("<itemref idref=\"p$it\"/>\n") }
        append("</spine>\n</package>\n")
    }

    private fun nav(book: Book, language: String, pages: List<Page>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"").append(xml(language)).append("\">\n")
        append("<head><title>").append(xml(book.manifest.title)).append("</title></head>\n<body><nav epub:type=\"toc\"><ol>\n")
        grouped(pages).forEach { (head, children) ->
            append("<li><a href=\"text/${head.file}\">").append(xml(head.title)).append("</a>")
            if (children.isNotEmpty()) {
                append("<ol>\n")
                children.forEach { append("<li><a href=\"text/${it.file}\">").append(xml(it.title)).append("</a></li>\n") }
                append("</ol>")
            }
            append("</li>\n")
        }
        append("</ol></nav></body></html>\n")
    }

    private fun ncx(book: Book, id: String, pages: List<Page>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
        append("<head><meta name=\"dtb:uid\" content=\"").append(xml(id)).append("\"/></head>\n")
        append("<docTitle><text>").append(xml(book.manifest.title)).append("</text></docTitle>\n<navMap>\n")
        var order = 0
        fun open(page: Page) {
            order++
            append("<navPoint id=\"n$order\" playOrder=\"$order\"><navLabel><text>").append(xml(page.title))
                .append("</text></navLabel><content src=\"text/${page.file}\"/>")
        }
        grouped(pages).forEach { (head, children) ->
            open(head)
            children.forEach {
                open(it)
                append("</navPoint>\n")
            }
            append("</navPoint>\n")
        }
        append("</navMap>\n</ncx>\n")
    }

    /** 卷页带着它的章；没有卷的章自己一项。 */
    private fun grouped(pages: List<Page>): List<Pair<Page, List<Page>>> {
        val result = ArrayList<Pair<Page, MutableList<Page>>>()
        pages.forEach { page ->
            if (page.volume != null && result.lastOrNull()?.first?.title == page.volume && result.last().first.file.startsWith("volume")) result.last().second += page
            else result += page to ArrayList()
        }
        return result
    }

    private fun page(title: String, body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head><title>${xml(title)}</title>" +
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"../style.css\"/></head>\n<body>\n$body</body>\n</html>\n"

    private fun ZipOutputStream.stored(name: String, text: String) {
        val bytes = text.toByteArray()
        putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        })
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.text(name: String, text: String) = entry(name, text.toByteArray())

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    internal companion object {
        private val IMAGE_TYPES = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "svg" to "image/svg+xml", "webp" to "image/webp",
        )
        private const val CONTAINER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>\n"
        private const val CSS = "body{line-height:1.7}\nh1,h2{text-align:center}\n.volume{margin-top:30%}\np{margin:0.4em 0}\n.image{text-align:center}\nimg{max-width:100%}\n"

        private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
        private val RULE = Regex("^(?:-{3,}|\\*{3,}|_{3,}|(?:\\* ){2,}\\*)$")
        private val IMAGE = Regex("^!\\[([^]]*)]\\(([^)\\s]+)[^)]*\\)$")

        fun isMarkdown(path: String): Boolean = path.substringAfterLast('.').lowercase() in setOf("md", "markdown")

        /** 正文第一行就是章名时去掉，免得标题出现两次。 */
        fun stripTitle(text: String, title: String): String {
            val body = text.removePrefix("﻿").replace("\r\n", "\n")
            val lines = body.lines()
            val first = lines.indexOfFirst { it.isNotBlank() }
            if (first < 0) return body
            val heading = lines[first].trim { it.isWhitespace() || it == '　' }.let { line -> HEADING.matchEntire(line)?.groupValues?.get(2) ?: line }
            return if (heading == title.trim()) lines.drop(first + 1).joinToString("\n") else body
        }

        fun blocks(path: String, text: String): List<Block> {
            val markdown = isMarkdown(path)
            return text.replace("\r\n", "\n").lines().mapNotNull { raw ->
                val line = raw.trim { it.isWhitespace() || it == '　' }
                when {
                    line.isEmpty() -> null
                    !markdown -> Block.Paragraph(raw.trimEnd(), quote = false)
                    else -> HEADING.matchEntire(line)?.let { Block.Heading(it.groupValues[1].length, it.groupValues[2]) }
                        ?: Block.Rule.takeIf { RULE.matches(line) }
                        ?: IMAGE.matchEntire(line)?.let { Block.Image(it.groupValues[1], it.groupValues[2]) }
                        ?: if (line.startsWith(">")) Block.Paragraph(line.trimStart('>', ' '), quote = true) else Block.Paragraph(raw.trimEnd(), quote = false)
                }
            }
        }

        private val INLINE = Regex("\\\\([\\\\*_~`>#\\[\\]!-])|\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*|\\*(?=\\S)([^*]+?)(?<=\\S)\\*|~~(?=\\S)(.+?)(?<=\\S)~~")

        /** 行内只认粗体、斜体、删除线和反斜杠转义；其余原样转义。 */
        fun inline(text: String): String = render(text, ::xml) { tag, inner -> "<$tag>$inner</$tag>" }

        /** 去掉行内标记，只留文字。 */
        fun plain(text: String): String = render(text, { it }) { _, inner -> inner }

        private fun render(text: String, literal: (String) -> String, wrap: (String, String) -> String): String {
            val out = StringBuilder()
            var at = 0
            for (match in INLINE.findAll(text)) {
                out.append(literal(text.substring(at, match.range.first)))
                val (escaped, bold, italic, deleted) = match.destructured
                out.append(
                    when {
                        escaped.isNotEmpty() -> literal(escaped)
                        bold.isNotEmpty() -> wrap("strong", render(bold, literal, wrap))
                        italic.isNotEmpty() -> wrap("em", render(italic, literal, wrap))
                        else -> wrap("del", render(deleted, literal, wrap))
                    },
                )
                at = match.range.last + 1
            }
            return out.append(literal(text.substring(at))).toString()
        }

        private fun escapeMarkdown(line: String): String {
            val escaped = line.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("~~", "\\~~").replace("`", "\\`")
            return if (escaped.startsWith("#") || escaped.startsWith(">") || escaped.startsWith("- ") || RULE.matches(line)) "\\" + escaped else escaped
        }

        /** XML 转义，并去掉 XML 不允许的控制字符。 */
        fun xml(text: String): String = buildString {
            var index = 0
            while (index < text.length) {
                val cp = text.codePointAt(index)
                index += Character.charCount(cp)
                when {
                    cp == '&'.code -> append("&amp;")
                    cp == '<'.code -> append("&lt;")
                    cp == '>'.code -> append("&gt;")
                    cp == '"'.code -> append("&quot;")
                    cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF -> appendCodePoint(cp)
                }
            }
        }
    }
}
