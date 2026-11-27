package me.rerere.rikkihub.data.book.history

import com.github.difflib.DiffUtils
import com.github.difflib.algorithm.DiffAlgorithmListener
import kotlinx.serialization.Serializable

/** 一章的定稿锁：整章锁 [chapter]、段落锁 [passages]；[path] 是现在的路径（历史键丢了时按它接回）。 */
@Serializable
data class FileLocks(
    val path: String,
    val chapter: ChapterLock? = null,
    val passages: List<PassageLock> = emptyList(),
    /** 文件连锁一起进了回收站：回收站里的文件名；放回时锁恢复。 */
    val trashed: String? = null,
) {
    val isEmpty: Boolean get() = chapter == null && passages.isEmpty()
}

/** 整章锁：文件的全文必须是 [sha]（加锁时的永久版本）。 */
@Serializable
data class ChapterLock(val at: Long, val sha: String, val web: Boolean = false)

/**
 * 一处段落锁：连续几段非空行 [lines]（已归一，比较时去掉首尾空白），[before]、[after] 是紧挨着的非空行（锚点），
 * [row] 是上次所在的行号。[origin] 是加锁时的原文，跟随不改，供“收回”“恢复”。[dormantSince]：你删掉这段后暂停的时间。
 */
@Serializable
data class PassageLock(
    val id: String,
    val at: Long,
    val sha: String,
    val lines: List<String>,
    val origin: List<String>,
    val before: String? = null,
    val after: String? = null,
    val row: Int = 0,
    val web: Boolean = false,
    val dormantSince: Long? = null,
)

/**
 * 定稿锁的判定（纯函数，只处理文字）：定位、拒绝判定、手写跟随、事后放回。
 *
 * 文字先按 [normalize] 归一（去开头 BOM，换行统一成 LF）；一个非空行就是一段，比较时去掉行首行尾空白（含全角空格），
 * 空行不参与匹配。按行差异有上限（[MAX_DIFF_LINES]、[MAX_DIFF_MS]），超了就退回保守处理。
 */
object BookLocks {
    /** 按行定位每处段落锁；行号与归一后的文字一致（CRLF、BOM 不改变行数）。 */
    fun rangesIn(text: String, passages: List<PassageLock>): Map<String, IntRange> {
        val doc = Doc(text)
        val starts = ArrayList<Int>().apply {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }
        fun lineEnd(row: Int): Int {
            var end = if (row + 1 < starts.size) starts[row + 1] - 1 else text.length
            if (end > starts[row] && text[end - 1] == '\r') end--
            return end
        }
        return passages.mapNotNull { passage ->
            val rows = locate(passage, doc) ?: return@mapNotNull null
            if (rows.last >= starts.size) return@mapNotNull null
            passage.id to (starts[rows.first] until lineEnd(rows.last))
        }.toMap()
    }

    /** 新行算“由某个旧行改来”的相似度下限（`2 × LCS / 两行长度之和`）。 */
    const val SIMILAR = 0.5

    /** 锁外旧行有这么大比例的字出现在某个新行里，就算这一行并进了锁外的文字（`LCS / 锁外旧行长度`）。 */
    const val CONTAINS = 0.6

    /** 归属时在改动块前后各多看几个非空行。 */
    private const val WINDOW = 2
    const val MAX_DIFF_LINES = 5000
    const val MAX_DIFF_MS = 500L

    /** 字级归属的计算量上限（所有比较的两行长度乘积之和）。 */
    private const val MAX_ATTRIBUTION_WORK = 20_000_000L

    /** 跟到替换行时，锁最多变成原来几倍行数。 */
    private const val MAX_GROWTH = 2

    /** 放回自检时在块前后多看的行数（含空行与窗口）。 */
    private const val SEAM = 4 * WINDOW + 4

    /** 加锁至少要有这么多字（不计空白）。 */
    const val MIN_CHARS = 10

    fun normalize(text: String): String = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')

    fun key(line: String): String = line.trim()

    /** 一份稿子：行、比较键、非空行的行号。 */
    class Doc(text: String) {
        val lines: List<String> = normalize(text).split('\n')
        val keys: List<String> = lines.map(::key)
        val nonBlank: List<Int> = keys.indices.filter { keys[it].isNotEmpty() }
        val text: String get() = lines.joinToString("\n")
    }

    // ---- 定位 ----------------------------------------------------------------------------------

    /** [passage] 在 [doc] 里的位置（首行到末行的行号，含中间的空行）；找不到为 null。 */
    fun locate(passage: PassageLock, doc: Doc): IntRange? {
        val found = occurrences(passage.lines, doc)
        if (found.isEmpty()) return null
        if (found.size == 1) return found.single()
        fun anchors(range: IntRange): Int {
            val at = doc.nonBlank.indexOf(range.first)
            val end = doc.nonBlank.indexOf(range.last)
            val before = doc.nonBlank.getOrNull(at - 1)?.let { doc.keys[it] }
            val after = doc.nonBlank.getOrNull(end + 1)?.let { doc.keys[it] }
            return (if (passage.before != null && before == key(passage.before)) 1 else 0) +
                (if (passage.after != null && after == key(passage.after)) 1 else 0)
        }
        return found.sortedWith(compareByDescending<IntRange> { anchors(it) }.thenBy { kotlin.math.abs(it.first - passage.row) }).first()
    }

    /** [lines] 作为连续非空行在 [doc] 里出现的所有位置。 */
    fun occurrences(lines: List<String>, doc: Doc): List<IntRange> {
        val wanted = lines.map(::key).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        val rows = doc.nonBlank
        return (0..rows.size - wanted.size).filter { start ->
            wanted.indices.all { doc.keys[rows[start + it]] == wanted[it] }
        }.map { start -> rows[start]..rows[start + wanted.size - 1] }
    }

    /** 在 [doc] 的 [range]（行号）上建一处锁：原文、锚点、位置。 */
    fun passageAt(doc: Doc, range: IntRange, id: String, at: Long, sha: String, web: Boolean): PassageLock {
        val lines = range.filter { doc.keys[it].isNotEmpty() }.map { doc.lines[it] }
        return PassageLock(id, at, sha, lines, lines, anchorBefore(doc, range.first), anchorAfter(doc, range.last), range.first, web)
    }

    /** 把 [range] 扩到整段（去掉首尾空行）；全是空行为 null。 */
    fun wholeParagraphs(doc: Doc, range: IntRange): IntRange? {
        val rows = range.filter { doc.keys.getOrNull(it)?.isNotEmpty() == true }
        return if (rows.isEmpty()) null else rows.first()..rows.last()
    }

    /** 归一后的 [text] 里 [start]..[end]（字符位置）跨过的行号。 */
    fun rowsOf(text: String, start: Int, end: Int): IntRange {
        fun row(offset: Int) = text.subSequence(0, offset.coerceIn(0, text.length)).count { it == '\n' }
        val last = if (end > start && text.getOrNull(end - 1) == '\n') end - 1 else end
        return row(start)..row(maxOf(start, last))
    }

    /** [doc] 里第 [rows] 行在文字里的字符范围（标记、跳转用）。 */
    fun offsetsOf(doc: Doc, rows: IntRange): IntRange {
        var offset = 0
        for (i in 0 until rows.first) offset += doc.lines[i].length + 1
        var end = offset
        for (i in rows) end += doc.lines[i].length + 1
        return offset until (end - 1).coerceAtLeast(offset)
    }

    fun charCount(lines: List<String>): Int = lines.sumOf { line -> line.count { !it.isWhitespace() } }

    private fun anchorBefore(doc: Doc, first: Int) = (first - 1 downTo 0).firstOrNull { doc.keys[it].isNotEmpty() }?.let { doc.lines[it] }

    private fun anchorAfter(doc: Doc, last: Int) = (last + 1 until doc.lines.size).firstOrNull { doc.keys[it].isNotEmpty() }?.let { doc.lines[it] }

    private fun moved(passage: PassageLock, doc: Doc, range: IntRange): PassageLock {
        val lines = range.filter { doc.keys[it].isNotEmpty() }.map { doc.lines[it] }
        return passage.copy(lines = lines, before = anchorBefore(doc, range.first), after = anchorAfter(doc, range.last), row = range.first, dormantSince = null)
    }

    // ---- 非手写的覆盖写：拒不拒 ------------------------------------------------------------------

    /** 一处被碰到的段落锁，以及差在哪。 */
    data class Issue(val passage: PassageLock, val kind: IssueKind)

    enum class IssueKind {
        /** 被改动或删除了。 */
        CHANGED,

        /** 被拆成了几段。 */
        SPLIT,

        /** 和别的段合并了。 */
        MERGED,

        /** 本章有几处相同的文字，其中一处是锁定的，少了一处。 */
        DUPLICATE,
    }

    /**
     * 从 [old] 换成 [new] 会碰到哪些段落锁（暂停的不算；[old] 里本来就找不到的不算，由调用方另行提醒）。
     * 挪动位置不算碰到，原文还在就行。
     */
    fun issues(old: Doc, new: Doc, passages: List<PassageLock>): List<Issue> = passages.filter { it.dormantSince == null }.mapNotNull { passage ->
        val before = occurrences(passage.lines, old).size
        if (before == 0) return@mapNotNull null
        val after = occurrences(passage.lines, new).size
        when {
            after >= before -> null
            after > 0 -> Issue(passage, IssueKind.DUPLICATE)
            else -> Issue(passage, kindOf(passage, new))
        }
    }

    /** [old] 里找不到的段落锁（之前被改坏、还没处理）。 */
    fun lost(old: Doc, passages: List<PassageLock>): List<PassageLock> =
        passages.filter { it.dormantSince == null && occurrences(it.lines, old).isEmpty() }

    private fun kindOf(passage: PassageLock, new: Doc): IssueKind {
        val wanted = passage.lines.map(::key).filter { it.isNotEmpty() }
        val keys = new.nonBlank.map { new.keys[it] }
        val split = wanted.any { line ->
            keys.indices.any { start ->
                var joined = ""
                var end = start
                while (end < keys.size && joined.length < line.length) joined += keys[end++]
                end - start >= 2 && joined == line
            }
        }
        if (split) return IssueKind.SPLIT
        val merged = wanted.any { line -> keys.any { it.length > line.length && it.contains(line) } }
        return if (merged) IssueKind.MERGED else IssueKind.CHANGED
    }

    // ---- 手写的覆盖写：锁跟着走 ------------------------------------------------------------------

    /** 跟随的结果。 */
    sealed interface Follow {
        /** 锁跟到了新位置（可能换了文字）。 */
        data class Moved(val passage: PassageLock) : Follow

        /** 锁定段被删了，或者认不出来：暂停这处锁。 */
        data object Dormant : Follow

        /** 旧稿里本来就找不到：不动。 */
        data object Unknown : Follow
    }

    /** 你把 [old] 改成了 [new]，[passage] 跟到哪里。[now] 是暂停开始的时间。 */
    fun follow(old: Doc, new: Doc, passage: PassageLock): Follow {
        val before = occurrences(passage.lines, old).size
        val found = locate(passage, new)
        // 原文还在、也没少一处：跟过去（包括剪切粘贴挪了位置）
        if (found != null && occurrences(passage.lines, new).size >= before) return Follow.Moved(moved(passage, new, found))
        val range = locate(passage, old) ?: return Follow.Unknown
        val hunks = diff(old.keys, new.keys) ?: return Follow.Dormant
        val locked = range.filter { old.keys[it].isNotEmpty() }.toSet()
        val touching = hunks.filter { hunk -> locked.any { it in hunk.old } }
        if (touching.isEmpty()) {
            val mapped = map(range.first, hunks)..map(range.last, hunks)
            return Follow.Moved(moved(passage, new, mapped))
        }
        val block = Block.of(old, new, touching) ?: return Follow.Dormant
        val attribution = attribute(old, new, block) ?: return Follow.Dormant
        // 没被改到的锁定行原样跟过去
        val kept = locked.filter { row -> touching.none { row in it.old } }.map { map(it, hunks) }
        val derived = attribution.filterValues { it in locked }.keys
        val rows = (kept + derived).sorted()
        if (rows.isEmpty()) {
            // 锁定段一行都认不出来：跟到替换上来的、不是由锁外旧行改来的新行；没有就暂停
            val candidates = block.newRows.filter { it !in attribution }
            if (candidates.isEmpty()) return Follow.Dormant
            val limit = maxOf(1, locked.size * MAX_GROWTH)
            val chosen = candidates.take(limit)
            return Follow.Moved(moved(passage, new, chosen.first()..chosen.last()))
        }
        // 夹在锁定行中间的新增行（不是由任何旧行改来的）跟着进锁；由锁外旧行改来的不进
        val first = rows.first()
        val last = rows.last()
        val inside = new.nonBlank.filter { it in first..last }
        if (inside.any { row -> attribution[row]?.let { it !in locked } == true }) {
            // 锁定行之间夹着锁外的段：锁只能是连续的，取包含最多锁定行的一截
            val runs = inside.fold(mutableListOf(mutableListOf<Int>())) { acc, row ->
                if (attribution[row]?.let { it !in locked } == true) acc.add(mutableListOf()) else acc.last().add(row)
                acc
            }.filter { it.isNotEmpty() }
            val best = runs.maxBy { run -> run.count { it in derived || it in kept } }
            return Follow.Moved(moved(passage, new, best.first()..best.last()))
        }
        return Follow.Moved(moved(passage, new, first..last))
    }

    // ---- 保留这次修改（之后又改过） -------------------------------------------------------------

    /**
     * “保留这次修改”遇到之后又改过：把 [current] 里的锁定段（放回后的原文）换成 [changed] 里它被改成的样子，别处一个字不动。
     * 跟随的基准是 AI 改之前那一版 [before]（加锁后你手写过，锁已经跟着走了，不能用加锁时的版本）。
     * 现在的稿子里找不到锁定段、跟不过去、自检不过返回 null（调用方提示先打开历史比较）。
     */
    fun keepIn(before: Doc, changed: Doc, current: Doc, passage: PassageLock): String? {
        val range = locate(passage, current) ?: return null
        val moved = follow(before, changed, passage) as? Follow.Moved ?: return null
        val target = locate(moved.passage, changed) ?: return null
        val lines = current.lines.subList(0, range.first) + changed.lines.subList(target.first, target.last + 1) +
            current.lines.subList(range.last + 1, current.lines.size)
        val result = Doc(lines.joinToString("\n"))
        // 自检：锁定段以外一行不差；原锁定行在结果里比换之前少一处（同样的文字合法地出现在别处时不误判）
        val tail = current.lines.size - range.last - 1
        if (result.lines.subList(0, range.first) != current.lines.subList(0, range.first)) return null
        if (result.lines.takeLast(tail) != current.lines.takeLast(tail)) return null
        if (occurrences(passage.lines, result).size != occurrences(passage.lines, current).size - 1) return null
        return lines.joinToString("\n")
    }

    // ---- 事后放回 ------------------------------------------------------------------------------

    /**
     * 命令行把 [before] 改成了 [now]：把 [passage] 放回原样，只动锁定的行，锁外的字一个不少。
     * 放不回（找不到、超出上限、自检不过）返回 null，由调用方改为提醒。
     */
    fun putBack(before: Doc, now: Doc, passage: PassageLock): String? {
        val range = locate(passage, before) ?: return null
        val hunks = diff(before.keys, now.keys) ?: return null
        val locked = range.filter { before.keys[it].isNotEmpty() }.toSet()
        val touching = hunks.filter { hunk -> locked.any { it in hunk.old } || hunk.old.isEmpty() && hunk.oldAt in range.first + 1..range.last }
        if (touching.isEmpty()) return null
        val block = Block.of(before, now, touching) ?: return null
        val attribution = attribute(before, now, block) ?: return null
        val outside = block.oldWindow.filter { it !in locked }
        // 没被改到的锁定行，和由锁定行改来、又没并进锁外文字的新行，都换成原文
        val untouched = locked.filter { row -> touching.none { row in it.old } }.map { map(it, hunks) }
        val replaced = HashSet<Int>(untouched)
        val keptMerged = ArrayList<Int>()
        for ((row, source) in attribution) {
            if (source !in locked) continue
            val mergedOutside = outside.any { containment(before.keys[it], now.keys[row]) >= CONTAINS }
            if (mergedOutside) keptMerged += row else replaced += row
        }
        // 原文放在哪：第一个被换掉或并进锁外文字的行；都没有就放在锁定段在 now 里映射到的位置
        val anchorRow = (replaced + keptMerged).minOrNull()
        val insertAt = anchorRow ?: insertionPoint(range, block, attribution, locked)
        val original = before.lines.subList(range.first, range.last + 1)
        // null = 原稿里锁定段后面有空行：接缝处已经有空行就不再加
        val result = ArrayList<String?>()
        fun addBlock() {
            val blankBefore = range.first > 0 && before.keys[range.first - 1].isEmpty()
            if (blankBefore && result.lastOrNull()?.let { key(it).isNotEmpty() } == true) result += ""
            result += original
            if (range.last + 1 < before.lines.size && before.keys[range.last + 1].isEmpty()) result += null
        }
        for (i in now.lines.indices) {
            if (i == insertAt) addBlock()
            if (i in replaced) continue
            result += now.lines[i]
        }
        if (insertAt >= now.lines.size) addBlock()
        val lines = result.filterIndexed { i, line ->
            line != null || result.getOrNull(i + 1)?.let { key(it).isNotEmpty() } == true
        }.map { it ?: "" }
        val text = tidyBlanks(lines, before, now).joinToString("\n")
        // 自检只看块附近：块外的行原样保留
        val nowFirst = (block.newRows.firstOrNull() ?: block.newStart)
        val nowLast = block.newRows.lastOrNull() ?: block.newStart
        val near = (nowFirst - SEAM)..(nowLast + SEAM)
        val resultNear = (nowFirst - SEAM)..(nowLast + original.size + SEAM)
        return text.takeIf { selfCheck(now, Doc(it), passage, replaced, before, outside, near, resultNear) }
    }

    /** 换掉行以后留下的空行：连续空行不超过原稿里最长的一串，结尾的空行和 now 一样多。 */
    private fun tidyBlanks(lines: List<String>, before: Doc, now: Doc): List<String> {
        fun longestRun(doc: Doc) = doc.keys.fold(0 to 0) { (best, run), k -> if (k.isEmpty()) maxOf(best, run + 1) to run + 1 else best to 0 }.first
        val maxRun = maxOf(1, longestRun(before), longestRun(now))
        val out = ArrayList<String>()
        var run = 0
        for (line in lines) {
            if (key(line).isEmpty()) {
                if (++run > maxRun) continue
            } else {
                run = 0
            }
            out += line
        }
        val trailing = now.keys.reversed().takeWhile { it.isEmpty() }.size
        while (out.size > 1 && out.reversed().takeWhile { key(it).isEmpty() }.size > trailing) out.removeAt(out.lastIndex)
        return out
    }

    /** 锁定段整个被删时插回的位置：块里第一个“由锁定段之后的旧行改来”的新行之前，否则块的末尾。 */
    private fun insertionPoint(range: IntRange, block: Block, attribution: Map<Int, Int>, locked: Set<Int>): Int {
        val after = block.newRows.firstOrNull { row -> attribution[row]?.let { it > range.last } == true }
        if (after != null) return after
        val beforeRows = block.newRows.filter { row -> attribution[row]?.let { it < range.first } == true }
        if (beforeRows.isNotEmpty()) return beforeRows.last() + 1
        return block.newStart
    }

    /** [outside]：改动块附近的锁外旧行（块外的行原样保留，不用查）。 */
    private fun selfCheck(
        now: Doc, result: Doc, passage: PassageLock, replaced: Set<Int>, before: Doc, outside: List<Int>, near: IntRange, resultNear: IntRange,
    ): Boolean {
        if (locate(passage, result) == null) return false
        // 除了被换回原文的行，now 里其余各行的字一个不少
        val have = chars(result.keys)
        val need = chars(now.keys.filterIndexed { i, _ -> i !in replaced })
        if (need.any { (c, n) -> (have[c] ?: 0) < n }) return false
        // 块附近的锁外段落：在 now 里还认得出来的，放回后也要认得出来
        return outside.all { row ->
            val line = before.keys[row]
            val inNow = now.nonBlank.any { it in near && containment(line, now.keys[it]) >= CONTAINS }
            !inNow || result.nonBlank.any { it in resultNear && containment(line, result.keys[it]) >= CONTAINS }
        }
    }

    private fun chars(keys: List<String>): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (line in keys) line.codePoints().forEach { if (!Character.isWhitespace(it) && !Character.isSpaceChar(it)) counts.merge(it, 1, Int::plus) }
        return counts
    }

    // ---- 差异与归属 ------------------------------------------------------------------------------

    /** 一处改动：旧稿 [old] 这几行换成了新稿 [new] 这几行；一边为空时 [oldAt]/[newAt] 是位置。 */
    private class Hunk(val old: IntRange, val new: IntRange, val oldAt: Int, val newAt: Int)

    /** 按行差异，先去公共前后缀；任一边剩下超过 [MAX_DIFF_LINES] 行或算了超过 [MAX_DIFF_MS] 返回 null。 */
    private fun diff(a: List<String>, b: List<String>): List<Hunk>? {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val left = a.subList(prefix, a.size - suffix)
        val right = b.subList(prefix, b.size - suffix)
        if (left.size > MAX_DIFF_LINES || right.size > MAX_DIFF_LINES) return null
        if (left.isEmpty() && right.isEmpty()) return emptyList()
        val deadline = System.nanoTime() + MAX_DIFF_MS * 1_000_000
        val listener = object : DiffAlgorithmListener {
            override fun diffStart() {}
            override fun diffStep(value: Int, max: Int) {
                if (System.nanoTime() > deadline) throw DiffTimeout()
            }
            override fun diffEnd() {}
        }
        val deltas = try {
            DiffUtils.diff(left, right, listener).deltas
        } catch (_: DiffTimeout) {
            return null
        }
        return deltas.sortedBy { it.source.position }.map { delta ->
            val oldAt = prefix + delta.source.position
            val newAt = prefix + delta.target.position
            Hunk(oldAt until oldAt + delta.source.size(), newAt until newAt + delta.target.size(), oldAt, newAt)
        }
    }

    private class DiffTimeout : RuntimeException(null, null, false, false)

    /** 旧稿第 [row] 行（没被改动）在新稿里的行号。 */
    private fun map(row: Int, hunks: List<Hunk>): Int {
        var shift = 0
        for (hunk in hunks) {
            if (hunk.oldAt > row) break
            if (row in hunk.old) return hunk.newAt
            shift = (hunk.newAt + hunk.new.count()) - (hunk.oldAt + hunk.old.count())
        }
        return row + shift
    }

    /** 碰到锁定段的改动合成的一块，加上前后各 [WINDOW] 个非空行作归属范围。 */
    private class Block(val oldWindow: List<Int>, val newRows: List<Int>, val newStart: Int) {
        companion object {
            fun of(old: Doc, new: Doc, hunks: List<Hunk>): Block? {
                val oldFirst = hunks.minOf { it.oldAt }
                val oldLast = hunks.maxOf { if (it.old.isEmpty()) it.oldAt - 1 else it.old.last }
                val newFirst = hunks.minOf { it.newAt }
                val newLast = hunks.maxOf { if (it.new.isEmpty()) it.newAt - 1 else it.new.last }
                val oldRows = window(old, oldFirst, oldLast)
                val newRows = new.nonBlank.filter { it in newFirst..newLast }
                return Block(oldRows, newRows, newFirst)
            }

            private fun window(doc: Doc, first: Int, last: Int): List<Int> {
                val at = doc.nonBlank.indexOfFirst { it >= first }.let { if (it < 0) doc.nonBlank.size else it }
                val end = doc.nonBlank.indexOfLast { it <= last }
                val from = (at - WINDOW).coerceAtLeast(0)
                val to = (end + WINDOW).coerceAtMost(doc.nonBlank.lastIndex)
                return if (from > to) emptyList() else doc.nonBlank.subList(from, to + 1)
            }
        }
    }

    /** 块里每个新行是由哪个旧行（归属范围内）改来的：最像的那个，相似度 ≥ [SIMILAR]；认不出的不在结果里。超出计算量返回 null。 */
    private fun attribute(old: Doc, new: Doc, block: Block): Map<Int, Int>? {
        val work = block.newRows.sumOf { n -> block.oldWindow.sumOf { o -> new.keys[n].length.toLong() * old.keys[o].length } }
        if (work > MAX_ATTRIBUTION_WORK) return null
        val result = HashMap<Int, Int>()
        for (row in block.newRows) {
            var best = -1
            var bestScore = 0.0
            for (source in block.oldWindow) {
                val score = similarity(old.keys[source], new.keys[row])
                if (score > bestScore) {
                    best = source
                    bestScore = score
                }
            }
            if (best >= 0 && bestScore >= SIMILAR) result[row] = best
        }
        return result
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        return 2.0 * lcs(x, y) / (x.size + y.size)
    }

    /** [part] 的字有多大比例按顺序出现在 [line] 里。 */
    fun containment(part: String, line: String): Double {
        val x = part.codePoints().toArray()
        if (x.isEmpty()) return 0.0
        return lcs(x, line.codePoints().toArray()).toDouble() / x.size
    }

    internal fun lcs(x: IntArray, y: IntArray): Int {
        if (x.isEmpty() || y.isEmpty()) return 0
        var prev = IntArray(y.size + 1)
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            for (j in 1..y.size) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[y.size]
    }
}

/**
 * 定稿锁拒绝了这次写入（[me.rerere.workspace.WorkspaceLockedException] 的作品版本）：[path] 是作品里的相对路径，
 * [issues] 是碰到的段落锁和差在哪（段落锁时）。各调用方按 [reason] 给专门的说明。
 */
class BookLockedException(
    val path: String,
    val reason: Reason,
    val issues: List<BookLocks.Issue> = emptyList(),
) : me.rerere.workspace.WorkspaceLockedException("$path is locked (${reason.name.lowercase()})") {
    enum class Reason {
        /** 整章锁。 */
        CHAPTER,

        /** 这次写入会改掉段落锁。 */
        PASSAGES,

        /** 锁定的文件放不进回收站，没有删。 */
        TRASH,

        /** 锁定的文件不能移出这本书（要在 App 的作品里操作）。 */
        MOVE_OUT,
    }
}
