package me.rerere.rikkihub.data.book

/** 大纲文件里的一段；[chapterId] 是本地对上的章节，null = 对不上，由用户指派。 */
data class OutlineSection(val title: String, val beats: List<String>, val chapterId: String?)

/** 大纲文件 → 每章的计划节拍（纯本地）：先按标题里的章号，再按章名。 */
internal object OutlineFile {
    private val HEADING = Regex("^#{1,6}\\s+(.+?)\\s*#*$")
    private val CHAPTER_LINE = Regex("^(?:[-*+]\\s+)?(?:\\*\\*)?((?:第\\s*[0-9０-９零〇○◯一二三四五六七八九十百千万两廿卅]+\\s*[章节回折幕话]|(?:chapter|ch\\.?)\\s*\\d+)[^：:]*)(?:[：:]\\s*(.*))?$", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("^(?:[-*+•·]|\\d+[.、)）]|[（(]\\d+[)）])\\s*(?:\\[[ xX]]\\s*)?")
    private val NUMBER = Regex("第\\s*([0-9０-９零〇○◯一二三四五六七八九十百千万两廿卅]+)\\s*[章节回折幕话]|(?:chapter|ch\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)

    fun parse(text: String, book: Book): List<OutlineSection> {
        val byNumber = HashMap<Int, String?>()
        val byName = HashMap<String, String?>()
        for (entry in book.entries) {
            (number(entry.title) ?: number(entry.path.substringAfterLast('/')))?.let { byNumber.merge(it, entry.id) { _, _ -> "" } }
            byName.merge(name(entry.title), entry.id) { _, _ -> "" }
        }
        // 章名里没有章号的书，按顺序号对
        val hasNumbers = byNumber.isNotEmpty()
        fun match(title: String): String? {
            name(title).takeIf { it.isNotEmpty() }?.let { byName[it] }?.takeIf { it.isNotEmpty() }?.let { return it }
            val number = number(title) ?: return null
            return if (hasNumbers) byNumber[number]?.takeIf { it.isNotEmpty() } else book.entryByNumber(number)?.id
        }

        val sections = ArrayList<OutlineSection>()
        var title: String? = null
        val beats = ArrayList<String>()
        fun flush() {
            title?.let { if (beats.isNotEmpty()) sections += OutlineSection(it, beats.toList(), match(it)) }
            beats.clear()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim { it.isWhitespace() || it == '　' || it == '﻿' }
            if (line.isEmpty()) continue
            val heading = HEADING.matchEntire(line)?.groupValues?.get(1)
            val chapter = CHAPTER_LINE.matchEntire(heading ?: line)
            when {
                chapter != null && (heading != null || chapter.groupValues[1].length <= MAX_TITLE) -> {
                    flush()
                    // 标题里的冒号后面是章名，正文行里的冒号后面是内容
                    title = if (heading != null) heading else chapter.groupValues[1].trim(' ', '*')
                    if (heading == null) chapter.groupValues[2].trim(' ', '*').takeIf { it.isNotEmpty() }?.let { beats += it }
                }
                heading != null -> {
                    flush()
                    title = heading
                }
                title != null -> BULLET.replaceFirst(line, "").trim().takeIf { it.isNotEmpty() }?.let { beats += it }
            }
        }
        flush()
        return sections
    }

    private fun number(text: String): Int? {
        val match = NUMBER.find(text) ?: return null
        return ChapterNumbers.parse(match.groupValues[1].ifEmpty { match.groupValues[2] })
    }

    /** 去掉章号、空白和标点后的章名。 */
    private fun name(title: String): String = NUMBER.replace(title, "").filter { it.isLetterOrDigit() }.lowercase()

    private const val MAX_TITLE = 40
}
