package me.rerere.rikkihub.data.book.history

import com.github.difflib.DiffUtils
import com.github.difflib.algorithm.DiffAlgorithmListener

/**
 * 逐处审阅的引擎（书稿安全 S3）：AI 的一轮把一个文件从“改前”写成了“改后”，现在的稿子之后可能又被改过。
 * 把改前 → 改后按段切成块，从三份稿子算出每块现在的状态，按选择把块换回改前的样子。
 *
 * - 纯函数，不存中间状态：拒绝前后用同一套规则重算，状态只由三份稿子决定。
 * - 输入的 BOM、换行随意；输出归一后的 LF 文字（写回时由快照层按原文件补回 BOM 与换行）。
 * - 比较键是 [BookLocks.key]（去首尾空白），空行不参与比较：只改了缩进或空行的地方不出块。
 * - 位置来自“改后 → 现在”的差异和改动处的局部解释，不在现在的稿子里搜索；位置有二义就不动（“之后又改过”）。
 * - 写之前两层自检（拼接位置、对结果重算状态），不过就不写。
 */
internal object TurnReview {
    enum class State {
        /** 改后的样子还在原处：可以拒绝。 */
        PENDING,

        /** 已经是改前的样子。 */
        REVERTED,

        /** 改后的样子已经不在稿子里了（被删掉了）。 */
        GONE,

        /** 之后又改过，或者位置有二义：不能单独拒绝。 */
        CHANGED_LATER,
    }

    /**
     * 一块。[old]、[new] 是改前、改后的行（去掉首尾空行，块内空行保留），有一边为空时是新增或删除。
     * [rows] 是可拒绝时它在现在稿里的行（删除块是一条缝：空范围，first 是插回的位置）。[partner] 是移动绑定的另一块。
     */
    data class Piece(val old: List<String>, val new: List<String>, val state: State, val rows: IntRange?, val partner: Int?)

    /**
     * [whole]：只能整章还原（差异超限，或整章改写成了一块），[pieces] 只用来显示。
     * [layout]：只改了缩进或空行、不出块的地方有几处。[changesSince]：改后 → 现在改了几处（算不出时为 null）。
     */
    data class Review(val pieces: List<Piece>, val whole: Boolean, val layout: Int, val changesSince: Int?)

    /** 换回的结果：[text] 是要写的全文，为 null 表示什么都不写；[failed] 是没能换回的块。 */
    data class Revert(val text: String?, val failed: Set<Int>)

    fun review(before: String, after: String, current: String): Review {
        val b = Text(before)
        val a = Text(after)
        val c = Text(current)
        val blocks = chunk(b, a) ?: return Review(listOf(wholePiece(b, a)), whole = true, layout = 0, changesSince = changesSince(a, c))
        val eval = Eval(b, a, c, blocks)
        val pieces = blocks.mapIndexed { i, block ->
            Piece(
                old = b.span(block.os, block.oe),
                new = a.span(block.ns, block.ne),
                state = eval.state[i],
                rows = if (eval.state[i] == State.PENDING) eval.rows(i) else null,
                partner = block.partner.takeIf { it >= 0 },
            )
        }
        return Review(pieces, whole = false, layout = layoutChanges(b, a, blocks), changesSince = eval.changes)
    }

    /**
     * 把 [picks] 这几块换回改前的样子（移动绑定的另一块一起）。
     * [place] 只给测试用：把算出的位置（现在稿非空行下标）故意挪走，验证自检拦得住错放。
     */
    fun revert(before: String, after: String, current: String, picks: Set<Int>, place: (block: Int, start: Int) -> Int = { _, start -> start }): Revert {
        val b = Text(before)
        val a = Text(after)
        val blocks = chunk(b, a) ?: return Revert(null, picks)
        val eval = Eval(b, a, Text(current), blocks)
        for (i in picks) if (i in blocks.indices && eval.state[i] == State.PENDING) eval.start[i] = place(i, eval.start[i])
        val wanted = picks.filter { it in blocks.indices }.flatMap { listOfNotNull(it, blocks[it].partner.takeIf { p -> p >= 0 }) }.toSortedSet()
        val failed = (picks - wanted).toMutableSet()
        wanted.filterTo(failed) { eval.state[it] != State.PENDING }
        wanted.filterTo(failed) { blocks[it].partner in failed }
        val doing = wanted - failed
        if (doing.isEmpty()) return Revert(null, failed)
        attempt(eval, doing)?.let { return Revert(it, failed) }
        // 一起写自检不过：逐块退让，每次只换一块（连同绑定的另一块），写完重算、自检，过了才留下
        val units = doing.map { setOf(it) + listOfNotNull(blocks[it].partner.takeIf { p -> p >= 0 }) }.distinct()
        if (units.size == 1) return Revert(null, failed + doing)
        var text = eval.c
        var done = false
        for (unit in units) {
            val step = Eval(b, a, text, blocks)
            val written = unit.takeIf { u -> u.all { step.state[it] == State.PENDING } }?.let { attempt(step, it) }
            if (written == null) {
                failed += unit
            } else {
                text = Text(written)
                done = true
            }
        }
        return Revert(if (done) text.joined else null, failed)
    }

    /** 测试用：每块的改前、改后非空行范围（非空行下标）与移动绑定；只能整章还原时为 null。 */
    fun ranges(before: String, after: String): List<Triple<IntRange, IntRange, Int?>>? =
        chunk(Text(before), Text(after))?.map { Triple(it.os until it.oe, it.ns until it.ne, it.partner.takeIf { p -> p >= 0 }) }

    // ---- 稿子与块 -------------------------------------------------------------------------------

    private const val SHORT_CHARS = 6
    private const val MAX_REGION_BLOCKS = 500
    private const val REWRITE_MIN_LINES = 20

    /** 一份归一后的稿子：全部行、非空行的行号 [pos] 与键 [keys]。 */
    private class Text(raw: String) {
        val lines: List<String> = BookLocks.normalize(raw).split('\n')
        val pos: IntArray = lines.indices.filter { BookLocks.key(lines[it]).isNotEmpty() }.toIntArray()
        val keys: List<String> = pos.map { BookLocks.key(lines[it]) }
        val joined: String get() = lines.joinToString("\n")

        /** 全行下标 → 它前面有几个非空行。 */
        fun nonBlankBefore(): IntArray {
            val counts = IntArray(lines.size + 1)
            for (i in lines.indices) counts[i + 1] = counts[i] + if (BookLocks.key(lines[i]).isEmpty()) 0 else 1
            return counts
        }

        /** 非空行 [from, to) 覆盖的全部行（含中间的空行）。 */
        fun span(from: Int, to: Int): List<String> = if (from >= to) emptyList() else lines.subList(pos[from], pos[to - 1] + 1)
    }

    /** 一块：非空行下标 [os, oe) → [ns, ne)；全行下标 [fos, foe) → [fns, fne)（插入、删除块含按段分到的边缘空行）。 */
    private class Block(val os: Int, val oe: Int, val ns: Int, val ne: Int, val fos: Int, val foe: Int, val fns: Int, val fne: Int) {
        var partner = -1
        val isDelete get() = ne == ns
        val isInsert get() = oe == os
        val isRewrite get() = !isDelete && !isInsert
    }

    private class Timeout : RuntimeException(null, null, false, false)

    private fun Long.check() {
        if (System.nanoTime() > this) throw Timeout()
    }

    private fun deadline() = System.nanoTime() + BookLocks.MAX_DIFF_MS * 1_000_000

    /** 切块；超过差异上限（或整章改写成了一块）返回 null：只能整章还原。 */
    private fun chunk(b: Text, a: Text): List<Block>? {
        if (b.keys.size > BookLocks.MAX_DIFF_LINES || a.keys.size > BookLocks.MAX_DIFF_LINES) return null
        val deadline = deadline()
        val blocks = try {
            sameCount(b, a, deadline) ?: myers(b, a, deadline)
        } catch (_: Timeout) {
            return null
        } ?: return null
        val longest = maxOf(b.keys.size, a.keys.size)
        val single = blocks.singleOrNull()
        if (single != null && single.isRewrite && longest >= REWRITE_MIN_LINES && (b.keys.size - (single.oe - single.os)) * 10 < longest) return null
        bindMoves(b, a, blocks)
        return blocks
    }

    /**
     * 同行数快路径（整章逐段润色）：非空行数相同，逐对相似、相邻两对配对核对通过、改了的那一对原文不在别处、
     * 也不比错开一行的配对更不像 → 直接逐行配对。任一条不满足返回 null（走差异）。
     */
    private fun sameCount(b: Text, a: Text, deadline: Long): List<Block>? {
        val x = b.keys
        val y = a.keys
        if (x.size != y.size || x.isEmpty()) return null
        val befores = x.toHashSet()
        val afters = y.toHashSet()
        val same = IntArray(x.size)
        for (i in x.indices) {
            if (x[i] == y[i]) {
                same[i] = length(x[i])
                continue
            }
            if (x[i] in afters || y[i] in befores) return null
            deadline.check()
            same[i] = lcs(x[i], y[i])
            if (ratio(same[i], x[i], y[i]) < BookLocks.SIMILAR) return null
        }
        for (i in 0 until x.size - 1) {
            deadline.check()
            if (!adjacentOk(x, y, same, i, 0, 0)) return null
        }
        return x.indices.filter { x[it] != y[it] }.map { Block(it, it + 1, it, it + 1, b.pos[it], b.pos[it] + 1, a.pos[it], a.pos[it] + 1) }
    }

    /** 差异切块；改写块两边行数相同、逐对配对核对都过才逐行拆开。 */
    private fun myers(b: Text, a: Text, deadline: Long): List<Block>? {
        val hunks = ChapterDiff.hunks(b.lines, a.lines, BookLocks::key, deadline) ?: return null
        val bi = b.nonBlankBefore()
        val ai = a.nonBlankBefore()
        val out = ArrayList<Block>()
        for (hunk in hunks) {
            val os = bi[hunk.old.first]
            val oe = bi[hunk.old.last + 1]
            val ns = ai[hunk.new.first]
            val ne = ai[hunk.new.last + 1]
            if (b.keys.subList(os, oe) == a.keys.subList(ns, ne)) continue
            val n = oe - os
            if (n > 1 && n == ne - ns && pairable(b.keys, a.keys, os, ns, n, deadline)) {
                for (i in 0 until n) {
                    if (b.keys[os + i] == a.keys[ns + i]) continue
                    out += Block(os + i, os + i + 1, ns + i, ns + i + 1, b.pos[os + i], b.pos[os + i] + 1, a.pos[ns + i], a.pos[ns + i] + 1)
                }
            } else {
                out += Block(os, oe, ns, ne, hunk.old.first, hunk.old.last + 1, hunk.new.first, hunk.new.last + 1)
            }
        }
        return out
    }

    private fun pairable(x: List<String>, y: List<String>, os: Int, ns: Int, n: Int, deadline: Long): Boolean {
        val same = IntArray(n)
        for (i in 0 until n) {
            deadline.check()
            same[i] = lcs(x[os + i], y[ns + i])
            if (ratio(same[i], x[os + i], y[ns + i]) < BookLocks.SIMILAR) return false
        }
        for (i in 0 until n - 1) {
            deadline.check()
            if (!adjacentOk(x, y, same, i, os, ns)) return false
        }
        return true
    }

    /**
     * 第 i 对与第 i+1 对：错位核对（第 i 对不比错开一行的两种配对更不像）与配对核对（两对拼起来的 LCS 与各自 LCS 之和相差不超过 2 个字，
     * 否则有句子流到了相邻段）。[same] 是各对自己的 LCS，下标从 0 起，[os]/[ns] 是这一串在两边的起点。
     */
    private fun adjacentOk(x: List<String>, y: List<String>, same: IntArray, i: Int, os: Int, ns: Int): Boolean {
        val x0 = x[os + i]
        val x1 = x[os + i + 1]
        val y0 = y[ns + i]
        val y1 = y[ns + i + 1]
        if (x0 == y0 && x1 == y1) return true
        if (x0 != y0) {
            val own = ratio(same[i], x0, y0)
            if (!similarAtMost(x1, y0, own) || !similarAtMost(x0, y1, own)) return false
        }
        return lcs(x0 + x1, y0 + y1) - same[i] - same[i + 1] <= 2
    }

    /** 移动绑定：删除块与插入块的文字完全相同、不是短句、在改前改后里各只出现一次 → 两块只能一起拒绝。 */
    private fun bindMoves(b: Text, a: Text, blocks: List<Block>) {
        for (gone in blocks) {
            if (!gone.isDelete || gone.partner >= 0) continue
            val text = b.keys.subList(gone.os, gone.oe)
            if (text.sumOf(::visible) < SHORT_CHARS || occurrences(b.keys, text) != 1 || occurrences(a.keys, text) != 1) continue
            val arrived = blocks.indexOfFirst { it.isInsert && it.partner < 0 && a.keys.subList(it.ns, it.ne) == text }
            if (arrived < 0) continue
            gone.partner = arrived
            blocks[arrived].partner = blocks.indexOf(gone)
        }
    }

    // ---- 状态 -----------------------------------------------------------------------------------

    /** “改后 → 现在”差异里的一处：改后非空行 [oldFirst, oldEnd) → 现在 [newFirst, newEnd)。按对象认同一处。 */
    private class Change(val oldFirst: Int, val oldEnd: Int, val newFirst: Int, val newEnd: Int) {
        val delta get() = (newEnd - newFirst) - (oldEnd - oldFirst)
        val isEmpty get() = oldEnd == oldFirst && newEnd == newFirst
    }

    /** 非空行键的差异，先去公共前后缀；超限返回 null。 */
    private fun diff(x: List<String>, y: List<String>): List<Change>? {
        val prefix = 0
        val left = x
        val right = y
        if (left.isEmpty() && right.isEmpty()) return emptyList()
        if (left.size > BookLocks.MAX_DIFF_LINES || right.size > BookLocks.MAX_DIFF_LINES) return null
        val deadline = deadline()
        val listener = object : DiffAlgorithmListener {
            override fun diffStart() {}
            override fun diffStep(value: Int, max: Int) = deadline.check()
            override fun diffEnd() {}
        }
        val deltas = try {
            DiffUtils.diff(left, right, listener).deltas
        } catch (_: Timeout) {
            return null
        }
        return deltas.sortedBy { it.source.position }.map { delta ->
            val from = prefix + delta.source.position
            val to = prefix + delta.target.position
            Change(from, from + delta.source.size(), to, to + delta.target.size())
        }
    }

    private fun changesSince(a: Text, c: Text): Int? = diff(a.keys, c.keys)?.size

    /**
     * 一份现在稿上每块的状态与位置：
     * 1. 没被“改后 → 现在”的改动碰到的块：改写块（不是短句）可拒绝；插入、删除、短句块还要两侧边界干净（不挨着改动，或挨着的改动区解释得通）。
     * 2. 改动区：先在被整个盖住、且现在稿那段里改后或改前的样子合计只出现一次的改写块边界切开；剩下的改动区连同挨着的块（没被碰到的改写块当锚点，
     *    不并进来）整体解释成“每块取改后或改前的样子、中间的行不变”，唯一才算数；区尾可以多出你接着写的行，区里最后一块可以已经不在稿中。
     * 3. 现在 ≠ 改后时，可拒绝的块的窗口（前后各一个非空邻行 + 块）在改后稿里只能出现一次、在现在稿里不超过一次。
     */
    private class Eval(val b: Text, val a: Text, val c: Text, val blocks: List<Block>) {
        val identity = a.keys == c.keys
        val state = Array(blocks.size) { State.CHANGED_LATER }

        /** 可拒绝的块在现在稿里的非空行起点。 */
        val start = IntArray(blocks.size) { -1 }
        var changes: Int? = 0
            private set

        private val cut = BooleanArray(blocks.size)
        private var work = ArrayList<Change>()
        private var all: List<Change> = emptyList()

        init {
            if (identity) {
                blocks.forEachIndexed { i, block ->
                    state[i] = State.PENDING
                    start[i] = block.ns
                }
            } else {
                val changes = diff(a.keys, c.keys)
                this.changes = changes?.size
                if (changes != null) evaluate(changes)
            }
        }

        fun isShort(block: Block) = block.ne - block.ns == 1 && visible(a.keys[block.ns]) < SHORT_CHARS

        /** 可拒绝的块在现在稿里的行（全行下标）；删除块是插回处的空范围。 */
        fun rows(i: Int): IntRange {
            val block = blocks[i]
            val p0 = start[i]
            if (!block.isDelete) return c.pos[p0]..c.pos[p0 + block.ne - block.ns - 1]
            val at = seam(block, p0)
            return at until at
        }

        /** 删除块插回的位置（全行下标）：前一个非空邻行之后，与改后稿相同的偏移处，不越过后一个非空行。 */
        fun seam(block: Block, p0: Int): Int {
            val limit = if (p0 < c.keys.size) c.pos[p0] else c.lines.size
            return when {
                block.ns > 0 && p0 > 0 -> minOf(c.pos[p0 - 1] + (block.fns - a.pos[block.ns - 1]), limit)
                p0 > 0 -> c.pos[p0 - 1] + 1
                else -> minOf(block.fns, limit)
            }
        }

        private fun evaluate(changes: List<Change>) {
            work = ArrayList(changes)
            val reverted = ArrayList<Change>()
            split(reverted)
            val order = compareBy<Change>({ it.oldFirst }, { it.oldEnd })
            all = (work + reverted).sortedWith(order)
            work.sortWith(order)
            val n = blocks.size
            val parent = IntArray(n + work.size) { it }
            fun find(x: Int): Int {
                var y = x
                while (parent[y] != y) {
                    parent[y] = parent[parent[y]]
                    y = parent[y]
                }
                return y
            }
            val inRegion = BooleanArray(n)
            for (i in 0 until n) {
                val block = blocks[i]
                if (cut[i] || block.isRewrite && !isShort(block) && !touched(block)) continue
                work.forEachIndexed { h, change ->
                    if (block.ns <= change.oldEnd && change.oldFirst <= block.ne) {
                        parent[find(i)] = find(n + h)
                        inRegion[i] = true
                    }
                }
            }
            for (i in 0 until n) {
                val block = blocks[i]
                if (cut[i] || touched(block)) continue
                if (block.isRewrite && !isShort(block) || !inRegion[i]) {
                    state[i] = State.PENDING
                    start[i] = startOf(block.ns, emptySet())
                }
            }
            val regions = (0 until n).filter { inRegion[it] }.groupBy { find(it) }
            val hunks = work.indices.groupBy({ find(n + it) }, { work[it] })
            for ((root, ids) in regions) explain(ids, hunks[root].orEmpty())
            unique()
        }

        /** 在被改动区整个盖住的改写块边界切开。 */
        private fun split(reverted: MutableList<Change>) {
            var h = 0
            while (h < work.size) {
                val change = work[h]
                val hit = blocks.indices.firstNotNullOfOrNull { i -> cutAt(i, change) }
                if (hit == null) {
                    h++
                    continue
                }
                val (i, p, len, asAfter) = hit
                val block = blocks[i]
                work.removeAt(h)
                val left = Change(change.oldFirst, block.ns, change.newFirst, p)
                val right = Change(block.ne, change.oldEnd, p + len, change.newEnd)
                if (!right.isEmpty) work.add(h, right)
                if (!left.isEmpty) work.add(h, left)
                cut[i] = true
                if (asAfter) {
                    state[i] = State.PENDING
                    start[i] = p
                } else {
                    state[i] = State.REVERTED
                    reverted += Change(block.ns, block.ne, p, p + len)
                }
            }
        }

        private data class Cut(val block: Int, val at: Int, val length: Int, val asAfter: Boolean)

        private fun cutAt(i: Int, change: Change): Cut? {
            val block = blocks[i]
            if (cut[i] || !block.isRewrite || isShort(block) || change.oldFirst > block.ns || block.ne > change.oldEnd) return null
            val segment = c.keys.subList(change.newFirst, change.newEnd)
            val after = a.keys.subList(block.ns, block.ne)
            val before = b.keys.subList(block.os, block.oe)
            val inAfter = occurrences(segment, after)
            val inBefore = occurrences(segment, before)
            if (inAfter + inBefore != 1) return null
            val form = if (inAfter == 1) after else before
            val at = change.newFirst + indexOf(segment, form)
            // 按改前样子切开时，段里别处还有与改后样子相似的行：那是你改过的 AI 段，不切
            if (inAfter == 0) {
                val elsewhere = (change.newFirst until change.newEnd).any { x ->
                    x !in at until at + form.size && after.any { BookLocks.similarity(c.keys[x], it) >= BookLocks.SIMILAR }
                }
                if (elsewhere) return null
            }
            return Cut(i, at, form.size, asAfter = inAfter == 1)
        }

        private fun touched(block: Block): Boolean = work.any { change ->
            if (!block.isDelete) {
                change.oldFirst < block.ne && change.oldEnd > block.ns ||
                    change.oldFirst == change.oldEnd && change.oldFirst > block.ns && change.oldFirst < block.ne
            } else {
                change.oldFirst < block.ns && change.oldEnd > block.ns || change.oldFirst == change.oldEnd && change.oldFirst == block.ns
            }
        }

        /** 改后稿第 [row] 行之前的缝在现在稿里的位置；[region] 里在这条缝上的插入算在缝之后。 */
        private fun startOf(row: Int, region: Set<Change>): Int =
            row + all.filter { it.oldEnd <= row && (it.oldFirst < row || it !in region) }.sumOf { it.delta }

        private fun endOf(row: Int, region: Set<Change>): Int =
            row + all.filter { it.oldEnd <= row && (it.oldFirst < row || it in region) }.sumOf { it.delta }

        private enum class Mode { EXACT, TAIL, GONE }

        /** 一步解释：块取 [state] 的样子，占现在稿 [length] 行；没变的行 [state] 为 null。 */
        private data class Step(val state: State?, val length: Int)

        private fun explain(ids: List<Int>, hunks: List<Change>) {
            if (ids.size > MAX_REGION_BLOCKS) return mark(ids)
            val region = hunks.toHashSet()
            val rs = minOf(ids.minOf { blocks[it].ns }, hunks.minOfOrNull { it.oldFirst } ?: Int.MAX_VALUE)
            val re = maxOf(ids.maxOf { blocks[it].ne }, hunks.maxOfOrNull { it.oldEnd } ?: Int.MIN_VALUE)
            val cs = startOf(rs, region)
            val span = c.keys.subList(cs, maxOf(cs, endOf(re, region)))
            val order = ids.sortedWith(compareBy({ blocks[it].ns }, { if (blocks[it].isDelete) 0 else 1 }))
            // 解释序列：块的序号（≥ 0），或没变的行 -(行号 + 1)
            val items = ArrayList<Int>()
            var row = rs
            while (true) {
                order.filterTo(items) { blocks[it].isDelete && blocks[it].ns == row }
                if (row == re || row >= a.keys.size) break
                val owner = order.lastOrNull { !blocks[it].isDelete && row >= blocks[it].ns && row < blocks[it].ne }
                if (owner == null) items += -(row + 1) else if (row == blocks[owner].ns) items += owner
                row++
            }
            val last = order.last()
            val lastBlock = blocks[last]

            fun steps(item: Int, p: Int, mode: Mode): List<Step> {
                if (item < 0) return if (p < span.size && span[p] == a.keys[-item - 1]) listOf(Step(null, 1)) else emptyList()
                val block = blocks[item]
                if (item == last && mode == Mode.GONE) return listOf(Step(State.GONE, 0))
                val after = a.keys.subList(block.ns, block.ne)
                val before = b.keys.subList(block.os, block.oe)
                val tail = item == last && mode == Mode.TAIL
                return buildList {
                    if (fits(span, p, after) && !(tail && after.isEmpty())) add(Step(State.PENDING, after.size))
                    if (fits(span, p, before) && !(tail && before.isEmpty())) add(Step(State.REVERTED, before.size))
                }
            }

            fun accepted(q: Int, mode: Mode): Boolean = when (mode) {
                Mode.EXACT -> q == span.size
                Mode.TAIL -> q < span.size
                Mode.GONE -> (q until span.size).all { x ->
                    (lastBlock.ns until lastBlock.ne).all { r -> BookLocks.similarity(span[x], a.keys[r]) < BookLocks.SIMILAR }
                }
            }

            /** 这一级的唯一解：块 → （状态，在这段里的起点）；没有解返回空表，有二义返回 null。 */
            fun solve(mode: Mode): Map<Int, Pair<State, Int>>? {
                val forward = ArrayList<HashMap<Int, Int>>(items.size + 1)
                forward += hashMapOf(0 to 1)
                for (item in items) {
                    val next = HashMap<Int, Int>()
                    for ((p, count) in forward.last()) for (step in steps(item, p, mode)) next.merge(p + step.length, count) { x, y -> minOf(2, x + y) }
                    if (next.isEmpty()) return emptyMap()
                    forward += next
                }
                // 同一级里先取多出的行最少的解（[Mode.EXACT] 没有多出的行）
                val end = forward.last().keys.filter { accepted(it, mode) }.maxOrNull() ?: return emptyMap()
                if (forward.last().getValue(end) > 1) return null
                val chosen = HashMap<Int, Pair<State, Int>>()
                var p = end
                for (i in items.indices.reversed()) {
                    val (from, step) = forward[i].keys.firstNotNullOf { q -> steps(items[i], q, mode).firstOrNull { q + it.length == p }?.let { q to it } }
                    if (items[i] >= 0) chosen[items[i]] = step.state!! to from
                    p = from
                }
                return chosen
            }

            val levels = buildList {
                add(Mode.EXACT)
                if (!lastBlock.isDelete && !isShort(lastBlock)) add(Mode.TAIL)
                if (!lastBlock.isDelete) add(Mode.GONE)
            }
            for (mode in levels) {
                val solution = solve(mode) ?: return mark(ids)
                if (solution.isEmpty()) continue
                // 区尾放宽时最后一块取改前样子，多出的行却更像它的改后样子：那是你改过的 AI 段（旁边粘回了原文），不是换回后接着写
                if (mode == Mode.TAIL && solution.getValue(last).first == State.REVERTED) {
                    val (_, offset) = solution.getValue(last)
                    val extra = span.subList(offset + lastBlock.oe - lastBlock.os + (items.size - 1 - items.lastIndexOf(last)), span.size)
                    val likeAfter = extra.any { line ->
                        val toAfter = (lastBlock.ns until lastBlock.ne).maxOf { BookLocks.similarity(line, a.keys[it]) }
                        val toBefore = (lastBlock.os until lastBlock.oe).maxOf { BookLocks.similarity(line, b.keys[it]) }
                        toAfter >= BookLocks.SIMILAR && toAfter > toBefore
                    }
                    if (likeAfter) return mark(ids)
                }
                order.forEachIndexed { x, id ->
                    val (chosen, offset) = solution.getValue(id)
                    val block = blocks[id]
                    // 插入、删除、短句块紧挨着已不在稿中的区尾块：边界不干净
                    val nearGone = mode == Mode.GONE && x == order.size - 2 && (block.isDelete || isShort(block))
                    when {
                        chosen == State.PENDING && nearGone -> state[id] = State.CHANGED_LATER
                        chosen == State.PENDING -> {
                            state[id] = State.PENDING
                            start[id] = cs + offset
                        }
                        else -> state[id] = chosen
                    }
                }
                return
            }
            mark(ids)
        }

        private fun mark(ids: List<Int>) = ids.forEach { state[it] = State.CHANGED_LATER }

        /** 窗口在改后稿里只出现一次、在现在稿里不超过一次；改写块的邻行变过时，紧挨着的行不能是它的同一句话。 */
        private fun unique() {
            val afterWindows = HashMap<Int, Map<String, Int>>()
            val currentWindows = HashMap<Int, Map<String, Int>>()
            for (i in blocks.indices) {
                if (state[i] != State.PENDING) continue
                val block = blocks[i]
                val p0 = start[i]
                val p1 = p0 + block.ne - block.ns
                val inAfter = window(a.keys, block.ns, block.ne)
                val inCurrent = window(c.keys, p0, p1)
                val countAfter = afterWindows.getOrPut(inAfter.size) { windows(a.keys, inAfter.size) }[inAfter.joinToString(SEPARATOR)] ?: 0
                val countCurrent = currentWindows.getOrPut(inCurrent.size) { windows(c.keys, inCurrent.size) }[inCurrent.joinToString(SEPARATOR)] ?: 0
                // 改写块邻行变过、紧挨着的那一行又和它的文字一模一样：哪一份是 AI 写的分不出，换错一份就写错了
                // （新增块去掉哪一份结果都一样，不算）
                val twin = inCurrent != inAfter && block.isRewrite &&
                    (p0 > 0 && c.keys[p0 - 1] == a.keys[block.ns] || p1 < c.keys.size && c.keys[p1] == a.keys[block.ne - 1])
                if (countAfter != 1 || countCurrent > 1 || twin) {
                    state[i] = State.CHANGED_LATER
                    start[i] = -1
                }
            }
        }

        private fun window(keys: List<String>, from: Int, to: Int): List<String> =
            keys.subList((from - 1).coerceAtLeast(0), (to + 1).coerceAtMost(keys.size))

        private fun windows(keys: List<String>, length: Int): Map<String, Int> {
            val counts = HashMap<String, Int>()
            for (i in 0..keys.size - length) counts.merge(keys.subList(i, i + length).joinToString(SEPARATOR), 1, Int::plus)
            return counts
        }
    }

    private const val SEPARATOR = "\u0001"

    // ---- 换回 -----------------------------------------------------------------------------------

    /** 现在稿 [from, to) 换成 [lines]（[from] == [to] 时是插入）；同一位置先插入、再换，同类按改前的先后。 */
    private class Edit(val from: Int, val to: Int, val lines: List<String>, val order: Int)

    /** 换回 [units] 并做两层自检；不过返回 null。 */
    private fun attempt(eval: Eval, units: Set<Int>): String? {
        val edits = units.map { edit(eval, it) }
        val sorted = edits.sortedWith(compareBy({ it.from }, { if (it.from == it.to) 0 else 1 }, { it.order }))
        // 位置层：按位置顺序拼出来，与倒着就地替换的结果逐行相同，且换回的位置互不重叠
        val built = ArrayList<String>()
        var at = 0
        for (edit in sorted) {
            if (edit.from < at || edit.to > eval.c.lines.size) return null
            built += eval.c.lines.subList(at, edit.from)
            built += edit.lines
            at = edit.to
        }
        built += eval.c.lines.subList(at, eval.c.lines.size)
        val spliced = eval.c.lines.toMutableList()
        for (edit in sorted.asReversed()) {
            spliced.subList(edit.from, edit.to).clear()
            spliced.addAll(edit.from, edit.lines)
        }
        if (built != spliced) return null
        val text = built.joinToString("\n")
        return text.takeIf { semantic(eval, Text(text), units) }
    }

    private fun edit(eval: Eval, i: Int): Edit {
        val (b, a, c) = Triple(eval.b, eval.a, eval.c)
        val block = eval.blocks[i]
        val p0 = eval.start[i]
        if (block.isDelete) {
            val limit = if (p0 < c.keys.size) c.pos[p0] else c.lines.size
            val at = eval.seam(block, p0)
            // 现在稿里这条缝两侧不隔空行（你用无空行排版）：只插非空行
            val tight = p0 > 0 && p0 < c.keys.size && limit == c.pos[p0 - 1] + 1
            val lines = (if (tight) b.span(block.os, block.oe) else b.lines.subList(block.fos, block.foe)).toMutableList()
            val (now, then) = when {
                p0 > 0 && block.ns > 0 -> c.lines[c.pos[p0 - 1]] to a.lines[a.pos[block.ns - 1]]
                p0 < c.keys.size && block.ns < a.keys.size -> c.lines[c.pos[p0]] to a.lines[a.pos[block.ns]]
                else -> null to null
            }
            if (now != null && then != null && lead(now) != lead(then)) {
                for (x in lines.indices) if (BookLocks.key(lines[x]).isNotEmpty()) lines[x] = lead(now) + lines[x].trimStart()
            }
            return Edit(at, at, lines, block.os)
        }
        var from = c.pos[p0]
        var to = c.pos[p0 + block.ne - block.ns - 1] + 1
        if (block.isInsert) {
            // 连同它在改后稿里带的边缘空行一起删，只删现在稿里确实是空行的
            val leading = a.pos[block.ns] - block.fns
            val trailing = block.fne - (a.pos[block.ne - 1] + 1)
            var n = 0
            while (n < leading && from > 0 && blank(c.lines[from - 1])) {
                from--
                n++
            }
            n = 0
            while (n < trailing && to < c.lines.size && blank(c.lines[to])) {
                to++
                n++
            }
            return Edit(from, to, emptyList(), block.os)
        }
        val lines = b.span(block.os, block.oe).toMutableList()
        if (block.oe - block.os == block.ne - block.ns) {
            // 行首空白只在排版变了时套用：现在稿那一行与改后稿对应行的行首空白不同（你事后重排过）
            val rows = lines.indices.filter { BookLocks.key(lines[it]).isNotEmpty() }
            rows.forEachIndexed { k, x ->
                val now = lead(c.lines[c.pos[p0 + k]])
                if (now != lead(a.lines[a.pos[block.ns + k]])) lines[x] = now + lines[x].trimStart()
            }
        }
        return Edit(from, to, lines, block.os)
    }

    /**
     * 语义层：对结果重算状态，换回的块都应是“已换回 / 已不在稿中”，没选的块状态不变（不得放宽）；非空段按多重集合计数正好对上。
     * 现在 == 改后时改为核对“结果 == 用块坐标在改后稿上独立换回这些块的样子”，并且换回的块不再可拒绝。
     */
    private fun semantic(eval: Eval, result: Text, units: Set<Int>): Boolean {
        val blocks = eval.blocks
        val bag = HashMap<String, Int>()
        eval.c.keys.forEach { bag.merge(it, 1, Int::plus) }
        for (i in units) {
            eval.a.keys.subList(blocks[i].ns, blocks[i].ne).forEach { bag.merge(it, -1, Int::plus) }
            eval.b.keys.subList(blocks[i].os, blocks[i].oe).forEach { bag.merge(it, 1, Int::plus) }
        }
        result.keys.forEach { bag.merge(it, -1, Int::plus) }
        if (bag.values.any { it != 0 }) return false
        val again = Eval(eval.b, eval.a, result, blocks)
        if (eval.identity) {
            val direct = ArrayList<String>()
            var at = 0
            val sorted = units.sortedWith(compareBy({ blocks[it].ns }, { if (blocks[it].isDelete) 0 else 1 }, { blocks[it].os }))
            for (i in sorted) {
                direct += eval.a.keys.subList(at, blocks[i].ns)
                direct += eval.b.keys.subList(blocks[i].os, blocks[i].oe)
                at = blocks[i].ne
            }
            direct += eval.a.keys.subList(at, eval.a.keys.size)
            return direct == result.keys && units.none { again.state[it] == State.PENDING }
        }
        val back = setOf(State.REVERTED, State.GONE)
        return blocks.indices.all { i ->
            if (i in units) again.state[i] in back else again.state[i] == eval.state[i] || again.state[i] in back && eval.state[i] in back
        }
    }

    /** 只改了缩进或空行的地方：所有块按块坐标换回改后稿，与改前稿还不一样的处数。 */
    private fun layoutChanges(b: Text, a: Text, blocks: List<Block>): Int {
        val eval = Eval(b, a, a, blocks)
        val edits = blocks.indices.map { edit(eval, it) }.sortedWith(compareBy({ it.from }, { if (it.from == it.to) 0 else 1 }, { it.order }))
        val lines = ArrayList<String>()
        var at = 0
        for (edit in edits) {
            if (edit.from < at) return 0
            lines += a.lines.subList(at, edit.from)
            lines += edit.lines
            at = edit.to
        }
        lines += a.lines.subList(at, a.lines.size)
        val restored = lines.map { it.trimEnd() }
        val original = b.lines.map { it.trimEnd() }
        if (restored == original) return 0
        return ChapterDiff.hunks(restored, original, { it }, deadline())?.size ?: 0
    }

    private fun wholePiece(b: Text, a: Text) = Piece(b.span(0, b.keys.size), a.span(0, a.keys.size), State.CHANGED_LATER, null, null)

    // ---- 小工具 ---------------------------------------------------------------------------------

    private fun blank(line: String) = BookLocks.key(line).isEmpty()

    private fun lead(line: String) = line.substring(0, line.length - line.trimStart().length)

    private fun visible(line: String) = line.count { !it.isWhitespace() }

    private fun length(text: String) = text.codePointCount(0, text.length)

    private fun lcs(x: String, y: String) = BookLocks.lcs(x.codePoints().toArray(), y.codePoints().toArray())

    private fun ratio(lcs: Int, x: String, y: String): Double {
        val total = length(x) + length(y)
        return if (total == 0) 1.0 else 2.0 * lcs / total
    }

    /** 相似度不超过 [bound]？先用字的多重集合交集给上界，上界就不超过时不必算 LCS（结果相同）。 */
    private fun similarAtMost(x: String, y: String, bound: Double): Boolean {
        val counts = HashMap<Int, Int>()
        x.codePoints().forEach { counts.merge(it, 1, Int::plus) }
        var shared = 0
        y.codePoints().forEach { cp ->
            val left = counts[cp] ?: 0
            if (left > 0) {
                shared++
                counts[cp] = left - 1
            }
        }
        if (ratio(shared, x, y) <= bound) return true
        return ratio(lcs(x, y), x, y) <= bound
    }

    private fun fits(span: List<String>, p: Int, want: List<String>): Boolean =
        p + want.size <= span.size && want.indices.all { span[p + it] == want[it] }

    private fun occurrences(hay: List<String>, needle: List<String>): Int =
        if (needle.isEmpty()) 0 else (0..hay.size - needle.size).count { fits(hay, it, needle) }

    private fun indexOf(hay: List<String>, needle: List<String>): Int = (0..hay.size - needle.size).first { fits(hay, it, needle) }
}
