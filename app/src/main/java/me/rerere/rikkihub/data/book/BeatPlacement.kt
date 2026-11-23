package me.rerere.rikkihub.data.book

/** 一处插入；[offset] 是前面几处都插好之后的位置。 */
data class BeatInsertion(val beatId: String, val offset: Int, val text: String, /** 记进笔记的那段（不含两头的空行）。 */ val block: String)

/** 节拍稿放进正文：段落格式跟着正文走，接在前一个节拍后面，能替换的前提是原来那段一字未动（纯函数）。 */
object BeatPlacement {
    private const val INDENT = "　　"

    /** 正文的段落习惯。 */
    data class Style(val separator: String, val indent: String)

    /** 从 [samples]（本章、上一章）里看：段间有没有空行、段首有没有缩进；看不出来按文件类型。 */
    fun styleOf(path: String, samples: List<String>): Style {
        val markdown = path.substringAfterLast('.').lowercase() in setOf("md", "markdown")
        val sample = samples.firstOrNull { text -> text.lineSequence().count { isProse(it) } >= MIN_SAMPLE }
            ?: return Style(if (markdown) "\n\n" else "\n", "")
        val lines = sample.lines().map { it.trimEnd('\r') }
        val prose = lines.withIndex().filter { isProse(it.value) }
        val adjacent = prose.zipWithNext().count { (a, b) -> b.index == a.index + 1 }
        val indented = prose.count { it.value.startsWith(INDENT) }
        return Style(
            separator = if (adjacent * 2 >= prose.size - 1) "\n" else "\n\n",
            indent = if (indented * 2 >= prose.size) INDENT else "",
        )
    }

    private const val MIN_SAMPLE = 3

    private fun isProse(line: String): Boolean = line.isNotBlank() && !line.startsWith("#")

    fun format(text: String, style: Style): String =
        text.lines().map { it.trim { c -> c.isWhitespace() || c == '　' } }.filter { it.isNotEmpty() }.joinToString(style.separator) { style.indent + it }

    enum class Presence { NONE, ONCE, SEVERAL }

    fun presence(text: String, block: String): Presence {
        if (block.isEmpty()) return Presence.NONE
        val first = text.indexOf(block)
        return when {
            first < 0 -> Presence.NONE
            text.indexOf(block, first + 1) < 0 -> Presence.ONCE
            else -> Presence.SEVERAL
        }
    }

    /** 还没写进正文、有采用稿的节拍。 */
    fun pending(notes: ChapterNotes): List<Beat> = notes.beats.filter { it.inserted.isEmpty() && it.chosenDraft != null }

    /**
     * 把 [beats] 依次放进 [text]。[cursor] 不为 null = 从光标处起连着放；否则各自接在前面最近的、正文里找得到的节拍之后，
     * 没有就放在后面最近的节拍之前，都没有放在章末。
     */
    fun place(text: String, notes: ChapterNotes, beats: List<Beat>, style: Style, cursor: Int? = null): List<BeatInsertion> {
        var current = text
        var at = cursor?.coerceIn(0, text.length)
        val blocks = notes.beats.associateTo(HashMap()) { it.id to it.inserted }
        return beats.mapNotNull { beat ->
            val block = format(beat.chosenDraft?.text.orEmpty(), style)
            if (block.isEmpty()) return@mapNotNull null
            val offset = at ?: offsetFor(current, notes, blocks, beat)
            val before = current.substring(0, offset)
            val after = current.substring(offset)
            val lead = if (before.isBlank()) "" else missing(before.takeLastWhile { it == '\n' || it == '\r' }.count { it == '\n' }, style.separator)
            val tail = if (after.isBlank()) (if (after.isEmpty()) "\n" else "") else missing(after.takeWhile { it == '\n' || it == '\r' }.count { it == '\n' }, style.separator)
            val inserted = lead + block + tail
            current = before + inserted + after
            blocks[beat.id] = block
            if (at != null) at = offset + lead.length + block.length
            BeatInsertion(beat.id, offset, inserted, block)
        }
    }

    private fun missing(newlines: Int, separator: String): String = "\n".repeat((separator.length - newlines).coerceAtLeast(0))

    private fun offsetFor(text: String, notes: ChapterNotes, blocks: Map<String, String>, beat: Beat): Int {
        val index = notes.beats.indexOfFirst { it.id == beat.id }
        notes.beats.take(index).asReversed().forEach { earlier ->
            val block = blocks[earlier.id].orEmpty()
            if (presence(text, block) == Presence.ONCE) return text.indexOf(block) + block.length
        }
        notes.beats.drop(index + 1).forEach { later ->
            val block = blocks[later.id].orEmpty()
            if (presence(text, block) == Presence.ONCE) return text.indexOf(block)
        }
        // 章末：在结尾的空行之前接上
        return text.trimEnd().length
    }

    /** 采用稿换过、而原来那段在正文里一字不差且只有一处：返回（区间，新文字）。只是排版不同不算换过。 */
    fun replacement(text: String, beat: Beat, style: Style): Pair<IntRange, String>? {
        val chosen = beat.chosenDraft?.text.orEmpty()
        val block = format(chosen, style)
        val old = located(text, beat, style)
        if (block.isEmpty() || old == null || format(old, PLAIN) == format(chosen, PLAIN)) return null
        val start = text.indexOf(old)
        return start until start + old.length to block
    }

    /**
     * 写进过正文的节拍现在在正文里的那段：记下的原文，或（撤销过替换）它的另一稿；要一字不差且只有一处。
     * 另一稿按现在的段落习惯或写进去时的排版找：章节写长了，习惯可能已经变了。
     */
    fun located(text: String, beat: Beat, style: Style): String? {
        if (beat.inserted.isEmpty()) return null
        when (presence(text, beat.inserted)) {
            Presence.ONCE -> return beat.inserted
            Presence.SEVERAL -> return null
            Presence.NONE -> {}
        }
        val styles = listOf(style, shapeOf(beat.inserted, style)).distinct()
        return beat.drafts.asReversed().flatMap { draft -> styles.map { format(draft.text, it) } }.firstOrNull { presence(text, it) == Presence.ONCE }
    }

    private val PLAIN = Style("\n", "")

    /** 写进去的那段自己的排版；只有一段时看不出段间空行，按 [fallback]。 */
    private fun shapeOf(block: String, fallback: Style) = Style(
        separator = when {
            "\n\n" in block -> "\n\n"
            '\n' in block -> "\n"
            else -> fallback.separator
        },
        indent = if (block.startsWith(INDENT)) INDENT else "",
    )

    /** 正文里不是由节拍写进去的部分。 */
    fun ownText(text: String, notes: ChapterNotes): String {
        var rest = text
        notes.beats.forEach { if (presence(rest, it.inserted) == Presence.ONCE) rest = rest.replaceFirst(it.inserted, "") }
        return rest.lines().map { it.trimEnd() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
