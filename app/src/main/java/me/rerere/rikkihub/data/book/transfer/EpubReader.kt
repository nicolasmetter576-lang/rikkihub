package me.rerere.rikkihub.data.book.transfer

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile

class EpubException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { NOT_EPUB, DRM, TOO_LARGE }
}

/** 一章：[markdown] 里的图片写成 `![](images/文件名)`。 */
data class EpubChapter(val title: String, val markdown: String, val volume: String?)

data class EpubBook(
    val title: String,
    val author: String,
    val language: String,
    val chapters: List<EpubChapter>,
    /** 文件名 → 包内条目。 */
    val images: Map<String, String>,
    val cover: String?,
)

/** 读 EPUB：书脊定顺序，nav / ncx 目录定章，没有目录按标题切。 */
class EpubReader(private val file: File) : AutoCloseable {
    private val zip = try {
        ZipFile(file)
    } catch (_: Exception) {
        throw EpubException(EpubException.Reason.NOT_EPUB)
    }
    private val names: Map<String, String>

    init {
        val entries = zip.entries().toList()
        if (entries.size > MAX_ENTRIES || entries.sumOf { maxOf(0L, it.size) } > MAX_TOTAL) {
            zip.close()
            throw EpubException(EpubException.Reason.TOO_LARGE)
        }
        names = entries.filterNot { it.isDirectory }.associate { it.name.lowercase() to it.name }
    }

    private var budget = MAX_TOTAL

    private class ManifestItem(val path: String, val type: String, val properties: String)

    private class TocEntry(val title: String, val path: String, val anchor: String?, val volume: String?)

    fun read(): EpubBook {
        val container = xml("META-INF/container.xml") ?: throw EpubException(EpubException.Reason.NOT_EPUB)
        val opfPath = container.selectFirst("rootfile")?.attr("full-path")?.let { normalize("", it) }
            ?: throw EpubException(EpubException.Reason.NOT_EPUB)
        val opf = xml(opfPath) ?: throw EpubException(EpubException.Reason.NOT_EPUB)
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = opf.select("manifest > item").associate { item ->
            item.attr("id") to ManifestItem(normalize(base, item.attr("href")), item.attr("media-type"), item.attr("properties"))
        }
        val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }
            .filter { it.type.contains("html") }.map { it.path }
        if (encrypted(spine)) throw EpubException(EpubException.Reason.DRM)
        val images = LinkedHashMap<String, String>()
        val imagePaths = manifest.values.filter { it.type.startsWith("image/") && entry(it.path) != null }
        imagePaths.forEach { item ->
            val name = uniqueName(item.path.substringAfterLast('/'), images.keys)
            images[name] = item.path
        }
        val imageNames = images.entries.associate { (name, path) -> path to name }
        val coverId = opf.select("metadata > meta[name=cover]").firstOrNull()?.attr("content")
        val cover = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') } ?: coverId?.let { manifest[it] }
        val toc = toc(opf, manifest.values, spine)
        val chapters = chapters(spine, toc, imageNames)
        val used = chapters.flatMapTo(HashSet()) { chapter -> IMAGE.findAll(chapter.markdown).map { it.groupValues[1] }.toList() }
        val coverName = cover?.let { imageNames[it.path] }
        return EpubBook(
            title = opf.select("metadata > *").firstOrNull { it.tagName().substringAfter(':') == "title" }?.text().orEmpty(),
            author = opf.select("metadata > *").filter { it.tagName().substringAfter(':') == "creator" }.joinToString("、") { it.text() },
            language = opf.select("metadata > *").firstOrNull { it.tagName().substringAfter(':') == "language" }?.text().orEmpty(),
            chapters = chapters,
            images = images.filterKeys { it in used || it == coverName },
            cover = coverName,
        )
    }

    /** 图片内容，供导入写文件。 */
    fun image(path: String): ByteArray? = bytes(path)

    override fun close() = zip.close()

    // ---- 包内文件 ----------------------------------------------------------------------------

    private fun entry(path: String) = zip.getEntry(path) ?: names[path.lowercase()]?.let(zip::getEntry)

    private fun bytes(path: String): ByteArray? {
        val entry = entry(path) ?: return null
        return zip.getInputStream(entry).use { readBounded(it) }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val limit = minOf(MAX_ENTRY, budget)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw EpubException(EpubException.Reason.TOO_LARGE)
        }
        budget -= out.size()
        return out.toByteArray()
    }

    private fun xml(path: String): Document? = bytes(path)?.let { Jsoup.parse(it.inputStream(), null, "", Parser.xmlParser()) }

    private fun encrypted(spine: List<String>): Boolean {
        val encryption = xml("META-INF/encryption.xml") ?: return false
        val locked = encryption.allElements.filter { it.tagName().substringAfter(':') == "CipherReference" }
            .map { normalize("", it.attr("URI")).lowercase() }.toSet()
        // 只混淆字体的不算
        return spine.any { it.lowercase() in locked }
    }

    // ---- 目录 --------------------------------------------------------------------------------

    private fun toc(opf: Document, manifest: Collection<ManifestItem>, spine: List<String>): List<TocEntry> {
        val nav = manifest.firstOrNull { "nav" in it.properties.split(' ') }
        val fromNav = nav?.let { item ->
            xml(item.path)?.let { document ->
                val root = document.select("nav").firstOrNull { "toc" in it.attr("epub:type").split(' ') } ?: document.selectFirst("nav")
                root?.children()?.firstOrNull { it.tagName() == "ol" }?.let { navList(it, item.path.substringBeforeLast('/', ""), null) }
            }
        }.orEmpty()
        if (fromNav.any { it.path in spine }) return fromNav
        val ncx = manifest.firstOrNull { it.path.endsWith(".ncx", ignoreCase = true) || it.type.contains("dtbncx") } ?: return emptyList()
        val map = xml(ncx.path)?.allElements?.firstOrNull { it.tagName().equals("navMap", ignoreCase = true) } ?: return emptyList()
        return ncxPoints(map, ncx.path.substringBeforeLast('/', ""), null).filter { it.path in spine }
    }

    private fun navList(ol: Element, base: String, volume: String?): List<TocEntry> = ol.children().filter { it.tagName() == "li" }.flatMap { li ->
        val label = li.children().firstOrNull { it.tagName() == "a" || it.tagName() == "span" }
        val title = label?.text()?.trim().orEmpty()
        val children = li.children().firstOrNull { it.tagName() == "ol" }
        val own = label?.takeIf { it.tagName() == "a" && it.attr("href").isNotBlank() }
            ?.let { listOf(target(title, base, it.attr("href"), if (children == null) volume else null)) }.orEmpty()
        if (children == null) own else own.map { TocEntry(it.title, it.path, it.anchor, VOLUME_PAGE) } + navList(children, base, title)
    }

    private fun ncxPoints(parent: Element, base: String, volume: String?): List<TocEntry> =
        parent.children().filter { it.tagName().equals("navPoint", ignoreCase = true) }.flatMap { point ->
            val title = point.children().firstOrNull { it.tagName().equals("navLabel", ignoreCase = true) }?.text()?.trim().orEmpty()
            val src = point.children().firstOrNull { it.tagName().equals("content", ignoreCase = true) }?.attr("src").orEmpty()
            val nested = ncxPoints(point, base, title)
            val own = if (src.isBlank()) emptyList() else listOf(target(title, base, src, if (nested.isEmpty()) volume else VOLUME_PAGE))
            own + nested
        }

    private fun target(title: String, base: String, href: String, volume: String?) =
        TocEntry(title, normalize(base, href.substringBefore('#')), href.substringAfter('#', "").ifBlank { null }?.let(::decode), volume)

    // ---- 正文 --------------------------------------------------------------------------------

    private class Draft(val title: String, val volume: String?) {
        val text = StringBuilder()
    }

    private fun chapters(spine: List<String>, toc: List<TocEntry>, images: Map<String, String>): List<EpubChapter> {
        val drafts = ArrayList<Draft>()
        for (path in spine) {
            val document = bytes(path)?.let { Jsoup.parse(it.inputStream(), null, "") } ?: continue
            val here = toc.filter { it.path == path }
            val converter = HtmlToMarkdown(path.substringBeforeLast('/', ""), images)
            if (toc.isEmpty()) {
                val markdown = converter.convert(document.body(), emptyMap()).joinToString("") { it.second }
                val title = document.selectFirst("h1, h2, h3")?.text()?.trim()
                // 没有目录：有标题的文件算一章，没标题的接在前一章后面
                if (title != null || drafts.isEmpty()) drafts += Draft(title.orEmpty(), null)
                drafts.last().text.append(markdown).append("\n\n")
                continue
            }
            val anchors = here.filter { it.anchor != null && document.getElementById(it.anchor) != null }.associateBy { it.anchor!! }
            // 没有锚点（或锚点不存在）的目录项从文件开头算起
            here.filter { it.anchor == null || it.anchor !in anchors }.forEach { drafts += draft(it) }
            converter.convert(document.body(), anchors.mapValues { it.key }).forEach { (anchor, markdown) ->
                if (anchor != null) drafts += draft(anchors.getValue(anchor))
                if (markdown.isBlank()) return@forEach
                if (drafts.isEmpty()) drafts += Draft("", null)
                drafts.last().text.append(markdown).append("\n\n")
            }
        }
        return drafts.mapNotNull { draft ->
            val text = draft.text.toString().trim()
            // 封面页：没有标题、只有图
            if (draft.title.isEmpty() && IMAGE.replace(text, "").isBlank()) return@mapNotNull null
            // 卷首页只有卷名：不算一章；空白页也不算
            val bare = text.lineSequence().filter { it.isNotBlank() }.joinToString("") { it.trim('#', ' ', '*') }
            if (text.isEmpty() || (draft.volume == VOLUME_PAGE && bare.length <= draft.title.length + 2)) null
            else EpubChapter(draft.title, text + "\n", draft.volume.takeIf { it != VOLUME_PAGE })
        }
    }

    private fun draft(entry: TocEntry) = Draft(entry.title, entry.volume)

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_ENTRY = 32L * 1024 * 1024
        const val MAX_TOTAL = 400L * 1024 * 1024
        const val VOLUME_PAGE = "\u0000volume"
        val IMAGE = Regex("!\\[[^]]*]\\(images/([^)]+)\\)")

        fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

        fun normalize(base: String, href: String): String = resolveEpubPath(base, href)

        fun uniqueName(name: String, taken: Set<String>): String {
            val safe = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").ifBlank { "image" }
            if (safe !in taken) return safe
            val stem = safe.substringBeforeLast('.')
            val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            return generateSequence(2) { it + 1 }.map { "$stem-$it$extension" }.first { it !in taken }
        }
    }
}

/** 包内相对路径：先做 URL 解码，再消掉 `.` 和 `..`；越过包根的 `..` 丢弃。 */
internal fun resolveEpubPath(base: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
    val parts = ArrayList<String>()
    val joined = if (decoded.startsWith("/")) decoded else "$base/$decoded"
    joined.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}

/** 正文 HTML 转 Markdown：一段一行、段间空一行；只认标题、粗斜体、删除线、引用、分隔线、列表和图片。 */
internal class HtmlToMarkdown(private val base: String, private val images: Map<String, String>) {
    private class State(val anchors: Map<String, String>) {
        val pieces = ArrayList<Pair<String?, StringBuilder>>().apply { add(null to StringBuilder()) }
        val line = StringBuilder()
        var quote = 0
        val out get() = pieces.last().second
    }

    /** 按锚点切开：每一段是（它开头的锚点，Markdown）。 */
    fun convert(body: Element, anchors: Map<String, String>): List<Pair<String?, String>> {
        val state = State(anchors)
        visit(body, state)
        flush(state)
        return state.pieces.map { it.first to it.second.toString() }
    }

    private fun flush(state: State) {
        val text = state.line.toString().replace(Regex("[ \\t\\u00A0]+"), " ").lines().joinToString("\n") { it.trim() }.trim()
        state.line.clear()
        if (text.isEmpty()) return
        val prefix = "> ".repeat(state.quote)
        text.lines().filter { it.isNotBlank() }.forEach { state.out.append(prefix).append(it).append("\n\n") }
    }

    private fun visit(node: Node, state: State) {
        if (node is TextNode) {
            state.line.append(escape(node.wholeText.replace(Regex("\\s+"), " ")))
            return
        }
        val element = node as? Element ?: return
        val anchor = element.id().takeIf { it in state.anchors }
        if (anchor != null) {
            flush(state)
            state.pieces += anchor to StringBuilder()
        }
        val tag = element.tagName().lowercase()
        when (tag) {
            "script", "style", "head", "title", "svg" -> {
                if (tag == "svg") element.select("image").forEach { image(it.attr("xlink:href").ifBlank { it.attr("href") }, "", state) }
            }
            "br" -> state.line.append('\n')
            "hr" -> {
                flush(state)
                state.out.append("---\n\n")
            }
            "img" -> image(element.attr("src"), element.attr("alt"), state)
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush(state)
                children(element, state)
                val text = state.line.toString().replace(Regex("\\s+"), " ").trim().replace("**", "")
                state.line.clear()
                if (text.isNotEmpty()) state.out.append("#".repeat(tag[1].digitToInt())).append(' ').append(text).append("\n\n")
            }
            "b", "strong" -> wrap(element, "**", state)
            "i", "em" -> wrap(element, "*", state)
            "s", "del", "strike" -> wrap(element, "~~", state)
            "blockquote" -> {
                flush(state)
                state.quote++
                children(element, state)
                flush(state)
                state.quote--
            }
            "li" -> {
                flush(state)
                state.line.append("- ")
                children(element, state)
                flush(state)
            }
            in BLOCKS -> {
                flush(state)
                children(element, state)
                flush(state)
            }
            else -> children(element, state)
        }
    }

    private fun children(element: Element, state: State) = element.childNodes().forEach { visit(it, state) }

    private fun wrap(element: Element, mark: String, state: State) {
        // 里面有图片、换段或空白时不包，免得标记落单
        if (element.text().isBlank() || element.select("img, p, div, br").isNotEmpty()) {
            children(element, state)
            return
        }
        state.line.append(mark).append(escape(element.text().trim())).append(mark)
    }

    private fun image(src: String, alt: String, state: State) {
        val name = images[resolveEpubPath(base, src)] ?: return
        flush(state)
        state.out.append("![").append(alt.replace("]", "").replace("[", "")).append("](images/").append(name).append(")\n\n")
    }

    private fun escape(text: String): String = text.replace("*", "\\*").replace("~~", "\\~~")

    private companion object {
        val BLOCKS = setOf("p", "div", "section", "article", "body", "ul", "ol", "table", "tr", "dl", "dt", "dd", "figure", "figcaption", "header", "footer", "aside", "main", "pre", "center")
    }
}
