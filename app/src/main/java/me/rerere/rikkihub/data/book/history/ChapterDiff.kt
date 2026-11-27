package me.rerere.rikkihub.data.book.history

import com.github.difflib.DiffUtils
import com.github.difflib.algorithm.DiffAlgorithmListener
import com.github.difflib.patch.AbstractDelta
import com.github.difflib.patch.DeltaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 两稿按段（行）比较：先统一换行、去掉行尾空白再比；改过的段两边段数相同时逐段做字级高亮。
 * 单段超过 [MAX_WORD_DIFF_CHARS] 字或全章字级比较量太大时只给段级；几乎整章都改了时 [rewritten] 为 true，界面给整章对照。
 */
@Serializable
data class ChapterDiff(val blocks: List<Block>, val rewritten: Boolean) {
    @Serializable
    sealed interface Block {
        /** 没变的段（界面只露头尾几段）。 */
        @Serializable
        @SerialName("same")
        data class Same(val lines: List<String>) : Block

        /** 一处改动：旧稿的 [old] 变成了现在的 [new]；[index] 用来“取回这段”。[spans] 是逐段字级高亮，两边段数不同时为 null。 */
        @Serializable
        @SerialName("changed")
        data class Changed(val index: Int, val old: List<String>, val new: List<String>, val spans: List<List<Span>>? = null) : Block
    }

    /** 一段里的一截字：没变、删掉（旧稿有）、加上（现在有）。 */
    @Serializable
    data class Span(val text: String, val kind: Kind)

    @Serializable
    enum class Kind {
        @SerialName("same") SAME,
        @SerialName("removed") REMOVED,
        @SerialName("added") ADDED,
    }

    companion object {
        const val MAX_WORD_DIFF_CHARS = 2000
        private const val MAX_WORD_DIFF_TOTAL = 200_000
        private const val REWRITE_MIN_LINES = 20

        fun of(old: String, new: String): ChapterDiff {
            val oldLines = lines(old)
            val newLines = lines(new)
            val blocks = ArrayList<Block>()
            var cursor = 0
            var budget = MAX_WORD_DIFF_TOTAL
            hunks(oldLines, newLines).forEachIndexed { index, hunk ->
                if (hunk.old.first > cursor) blocks += Block.Same(oldLines.subList(cursor, hunk.old.first).map(::display))
                val removed = oldLines.slice(hunk.old).map(::display)
                val added = newLines.slice(hunk.new).map(::display)
                val words = (hunk.change && removed.size == added.size).takeIf { it }?.let {
                    removed.zip(added).map { (a, b) ->
                        if (a.length > MAX_WORD_DIFF_CHARS || b.length > MAX_WORD_DIFF_CHARS || budget < a.length + b.length) return@let null
                        budget -= a.length + b.length
                        words(a, b)
                    }
                }
                blocks += Block.Changed(index, removed, added, words)
                cursor = hunk.old.last + 1
            }
            if (cursor < oldLines.size) blocks += Block.Same(oldLines.subList(cursor, oldLines.size).map(::display))
            val same = blocks.filterIsInstance<Block.Same>().sumOf { it.lines.size }
            val rewritten = maxOf(oldLines.size, newLines.size) >= REWRITE_MIN_LINES && same * 10 < maxOf(oldLines.size, newLines.size)
            return ChapterDiff(blocks, rewritten)
        }

        /**
         * 把 [current] 里第 [index] 处改动换回 [old] 里的样子（整段换，不动别处；其余行原样保留换行与行尾空白）。
         * [index] 来自 `of(old, current)`；返回 null 表示没有这一处（稿子已经变了）。
         */
        fun takeBack(old: String, current: String, index: Int): String? {
            val oldLines = lines(old)
            val newLines = lines(current)
            val hunk = hunks(oldLines, newLines).getOrNull(index) ?: return null
            val restored = newLines.subList(0, hunk.new.first) + oldLines.slice(hunk.old) + newLines.subList(hunk.new.last + 1, newLines.size)
            return restored.joinToString("\n")
        }

        /** 一处改动：旧稿 [old] 这几行换成了现在的 [new] 这几行（插入或删除时一边为空，空的那边的 first 是位置）；[change] = 两边都有。 */
        internal class Hunk(val old: IntRange, val new: IntRange, val change: Boolean)

        private fun hunks(oldLines: List<String>, newLines: List<String>): List<Hunk> =
            split(oldLines, newLines, DiffUtils.diff(oldLines.map(::key), newLines.map(::key)).deltas)

        /**
         * 同 [hunks]，行按 [key] 比较（逐处审阅用 [BookLocks.key]）；算到 [deadline]（`System.nanoTime()`）还没算完返回 null。
         */
        internal fun hunks(oldLines: List<String>, newLines: List<String>, key: (String) -> String, deadline: Long): List<Hunk>? {
            val listener = object : DiffAlgorithmListener {
                override fun diffStart() {}
                override fun diffStep(value: Int, max: Int) {
                    if (System.nanoTime() > deadline) throw Timeout()
                }
                override fun diffEnd() {}
            }
            val deltas = try {
                DiffUtils.diff(oldLines.map(key), newLines.map(key), listener).deltas
            } catch (_: Timeout) {
                return null
            }
            return split(oldLines, newLines, deltas)
        }

        private class Timeout : RuntimeException(null, null, false, false)

        /** 改动按段切开：连着插入或删除的几段各成一处，能分别取回；两边都有的（改写）保持一整处。 */
        private fun split(oldLines: List<String>, newLines: List<String>, deltas: List<AbstractDelta<String>>): List<Hunk> =
            deltas.sortedBy { it.source.position }.flatMap { delta ->
                val source = delta.source.position until delta.source.position + delta.source.size()
                val target = delta.target.position until delta.target.position + delta.target.size()
                when {
                    source.isEmpty() -> paragraphs(newLines, target).map { Hunk(source, it, change = false) }
                    target.isEmpty() -> paragraphs(oldLines, source).map { Hunk(it, target, change = false) }
                    else -> listOf(Hunk(source, target, delta.type == DeltaType.CHANGE))
                }
            }

        /**
         * 把连着的几行按段切开。空行跟着哪一段取决于这串行怎么开头：以空行开头的，空行归后一段；以文字开头的，归前一段。
         * 这样单独去掉或放回其中一段，前后的空行仍然一个不多一个不少。只有空行的一截并进前一段。
         */
        private fun paragraphs(lines: List<String>, range: IntRange): List<IntRange> {
            fun blank(i: Int) = key(lines[i]).isEmpty()
            val leadingBlank = blank(range.first)
            val cuts = (range.first + 1..range.last).filter { i -> if (leadingBlank) blank(i) && !blank(i - 1) else !blank(i) && blank(i - 1) }
            val pieces = (listOf(range.first) + cuts + (range.last + 1)).zipWithNext { a, b -> a until b }
            return pieces.fold(emptyList<IntRange>()) { kept, piece ->
                if (kept.isNotEmpty() && piece.all(::blank)) kept.dropLast(1) + listOf(kept.last().first..piece.last) else kept + listOf(piece)
            }
        }

        /** 按 `\n` 切，保留 `\r`，拼回去与原文逐字节一致。 */
        private fun lines(text: String): List<String> = text.split('\n')

        private fun key(line: String) = line.removeSuffix("\r").trimEnd()

        private fun display(line: String) = line.removeSuffix("\r")

        internal fun words(old: String, new: String): List<Span> {
            val spans = ArrayList<Span>()
            fun add(text: String, kind: Kind) {
                if (text.isEmpty()) return
                val last = spans.lastOrNull()
                if (last != null && last.kind == kind) spans[spans.lastIndex] = Span(last.text + text, kind) else spans += Span(text, kind)
            }
            val a = old.codePoints().toArray().map { String(Character.toChars(it)) }
            val b = new.codePoints().toArray().map { String(Character.toChars(it)) }
            var cursor = 0
            for (delta in DiffUtils.diff(a, b).deltas.sortedBy { it.source.position }) {
                add(a.subList(cursor, delta.source.position).joinToString(""), Kind.SAME)
                add(delta.source.lines.joinToString(""), Kind.REMOVED)
                add(delta.target.lines.joinToString(""), Kind.ADDED)
                cursor = delta.source.position + delta.source.size()
            }
            add(a.subList(cursor, a.size).joinToString(""), Kind.SAME)
            return spans
        }
    }
}
