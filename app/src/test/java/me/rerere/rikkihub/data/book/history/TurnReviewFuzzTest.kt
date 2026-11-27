package me.rerere.rikkihub.data.book.history

import me.rerere.rikkihub.data.book.history.TurnReview.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 逐处审阅引擎的随机对抗与性能（慢测试）。随机对抗沿用风险评审探针的生成器：每一行都带来源（AI 的第几行、你写的、换回的），
 * 显示“可拒绝”并写进去的，按来源真值核对写的位置；有一次写错就失败。
 */
class TurnReviewFuzzTest {
    private data class Line(val text: String, val source: String)

    private val pool = listOf(
        "雨下了一整夜，镇上的石板路泛着冷光。", "他推开院门，院子里积着一洼洼的水。", "远处传来几声狗叫，又很快安静下来。",
        "她把伞靠在门边，抖了抖袖口的水珠。", "灯芯噼啪响了一声，屋里暗了一瞬。", "第二天一早，镇口来了一辆马车。",
        "河面上起了雾，对岸的灯一盏一盏亮起来。", "老板娘端来一碗热汤，什么也没问。", "他把信折好塞进怀里，起身付了账。", "巷口的槐树下站着一个穿灰衣的人。",
    )
    private val short = listOf("“嗯。”", "“好。”", "* * *")

    private fun rewrite(line: String, k: Int): String {
        if (line.count { !it.isWhitespace() } < 6) {
            val next = short[(short.indexOf(line) + 1 + k).mod(short.size)]
            return if (next == line) "“行。”" else next
        }
        val text = line.trim()
        return text.dropLast(1) + "，" + "甲乙丙丁戊己庚辛壬癸"[k % 10] + "。"
    }

    private fun keys(text: String) = BookLocks.normalize(text).split('\n').map(BookLocks::key).filter { it.isNotEmpty() }

    /** 块的改后行在现在稿里原样、连续（来源是 AI 的那几行）的位置；删除块要求两侧 AI 邻行紧挨着。没有真值返回 -1。 */
    private fun truth(current: List<Line>, old: IntRange, new: IntRange, afterSize: Int): Int {
        if (!new.isEmpty()) {
            return (0..current.size - (new.last - new.first + 1)).firstOrNull { k -> new.all { r -> current[k + r - new.first].source == "A$r" } } ?: -1
        }
        val k1 = current.indexOfLast { it.source == "A${new.first - 1}" }
        val k2 = current.indexOfLast { it.source == "A${new.first}" }
        return when {
            new.first == 0 -> if (k2 == 0) 0 else -1
            new.first == afterSize -> if (k1 >= 0 && k1 == current.size - 1) k1 + 1 else -1
            else -> if (k1 >= 0 && k2 == k1 + 1) k2 else -1
        }
    }

    private var written = 0
    private var multi = 0

    private fun fuzz(cases: Int, seed: Long, duplicates: Boolean): List<String> {
        val random = Random(seed)
        val dangers = ArrayList<String>()
        var fresh = 0
        repeat(cases) {
            val n = 4 + random.nextInt(4)
            val before = if (duplicates) {
                List(n) { if (random.nextInt(5) == 0) short[random.nextInt(short.size)] else pool[random.nextInt(pool.size)] }
            } else {
                pool.shuffled(random).take(n)
            }
            val after = before.toMutableList()
            repeat(1 + random.nextInt(3)) {
                when (val op = random.nextInt(4)) {
                    0 -> if (after.isNotEmpty()) random.nextInt(after.size).let { i -> after[i] = rewrite(after[i], random.nextInt(10)) }
                    1 -> {
                        val r = random.nextInt(10)
                        val line = if (r < 5 || !duplicates) "第${++fresh}处新加：风从河面上吹过来，带着潮气。" else if (r < 8) pool[random.nextInt(pool.size)] else short[random.nextInt(short.size)]
                        after.add(random.nextInt(after.size + 1), line)
                    }
                    else -> if (after.size > 1) {
                        val moved = after.removeAt(random.nextInt(after.size))
                        if (op == 3) after.add(random.nextInt(after.size + 1), moved)
                    }
                }
            }
            val separator = if (random.nextInt(10) < 7) "\n\n" else "\n"
            val bt = before.joinToString(separator)
            val at = after.joinToString(separator)
            val ranges = TurnReview.ranges(bt, at) ?: return@repeat
            val afterKeys = keys(at)
            val beforeKeys = keys(bt)
            val current = afterKeys.mapIndexed { i, line -> Line(line, "A$i") }.toMutableList()
            repeat(random.nextInt(4)) {
                when (val op = random.nextInt(6)) {
                    0 -> current.add(random.nextInt(current.size + 1), Line("（你写的第${++fresh}段，和别处都不一样。）", "U"))
                    1 -> if (duplicates) {
                        val source = if (random.nextBoolean()) beforeKeys else afterKeys
                        if (source.isNotEmpty()) current.add(random.nextInt(current.size + 1), Line(source[random.nextInt(source.size)], "U"))
                    }
                    2 -> if (current.size > 1) current.removeAt(random.nextInt(current.size))
                    3 -> if (current.isNotEmpty()) random.nextInt(current.size).let { i -> current[i] = Line(rewrite(current[i].text, 5 + random.nextInt(5)), "U") }
                    else -> if (op >= 4 && ranges.isNotEmpty()) {
                        // 先按来源真值拒过一块
                        val (old, new, partner) = ranges[random.nextInt(ranges.size)]
                        val k = truth(current, old, new, afterKeys.size)
                        if (k >= 0 && partner == null) {
                            repeat(new.count()) { current.removeAt(k) }
                            current.addAll(k, old.map { Line(beforeKeys[it], "R") })
                        }
                    }
                }
            }
            val ct = current.joinToString(separator) { it.text }
            val currentKeys = current.map { it.text }
            if (afterKeys == currentKeys) return@repeat
            val review = TurnReview.review(bt, at, ct)
            // 多选：可拒绝且有真值的块一起拒，写下的必须等于按真值逐块换回（没换回的块不算）
            val truths = review.pieces.indices.filter { review.pieces[it].state == State.PENDING && ranges[it].third == null }
                .associateWith { truth(current, ranges[it].first, ranges[it].second, afterKeys.size) }.filterValues { it >= 0 }
            if (truths.size > 1 && truths.values.toSet().size == truths.size) {
                val result = TurnReview.revert(bt, at, ct, truths.keys)
                if (result.text != null) {
                    var expected = currentKeys
                    for ((i, k) in truths.entries.filter { it.key !in result.failed }.sortedByDescending { it.value }) {
                        val (old, new) = ranges[i]
                        expected = expected.subList(0, k) + old.map { beforeKeys[it] } + expected.subList(k + new.count(), expected.size)
                    }
                    multi++
                    if (keys(result.text) != expected) dangers += "种子 $seed：多选 ${truths.keys}（失败 ${result.failed}）改前 $beforeKeys，改后 $afterKeys，现在 $currentKeys → ${keys(result.text)}"
                }
            }
            review.pieces.forEachIndexed { i, piece ->
                val (old, new, partner) = ranges[i]
                if (piece.state != State.PENDING || partner != null) return@forEachIndexed
                val written = TurnReview.revert(bt, at, ct, setOf(i)).text ?: return@forEachIndexed
                this.written++
                val got = keys(written)
                val k = truth(current, old, new, afterKeys.size)
                val wrong = if (k >= 0) {
                    got != currentKeys.subList(0, k) + old.map { beforeKeys[it] } + currentKeys.subList(k + new.count(), currentKeys.size)
                } else if (new.isEmpty()) {
                    // 没有真值的删除块：至少要插在两侧 AI 行之间
                    val p0 = BookLocks.Doc(ct).nonBlank.count { it < piece.rows!!.first }
                    val k1 = current.indices.filter { current[it].source.startsWith("A") && current[it].source.drop(1).toInt() < new.first }.maxOrNull() ?: -1
                    val k2 = current.indices.filter { current[it].source.startsWith("A") && current[it].source.drop(1).toInt() >= new.first }.minOrNull() ?: current.size
                    // 两侧的文字就是 AI 的两个邻行（来源上分得出、文字上分不出的，写下的结果对另一种读法是对的）也算对
                    val textual = (new.first == 0 || p0 > 0 && currentKeys[p0 - 1] == afterKeys[new.first - 1]) &&
                        (new.first == afterKeys.size || p0 < currentKeys.size && currentKeys[p0] == afterKeys[new.first])
                    !(k1 < p0 && p0 <= k2) && !textual
                } else {
                    false
                }
                if (wrong) dangers += "种子 $seed：改前 ${beforeKeys}，改后 $afterKeys，现在 ${current.map { it.source }} $currentKeys，块 $old → $new 真值 $k 位置 ${piece.rows}，拒块 $i → $got"
            }
        }
        return dangers
    }

    @Test
    fun `random edits never write the wrong place`() {
        val dangers = fuzz(2000, 20261126L, duplicates = true) + fuzz(2000, 20261127L, duplicates = false)
        assertTrue(dangers.take(5).joinToString("\n"), dangers.isEmpty())
        // 不能空转：确实写进去了足够多次
        assertTrue("只写了 $written 次", written > 1000)
        assertTrue("多选只写了 $multi 次", multi > 100)
    }

    @Test
    fun `full chapter polish stays fast`() {
        val random = Random(7)
        val alphabet = "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么，。"
        val big = List(3000) { String(CharArray(40 + random.nextInt(60)) { alphabet[random.nextInt(alphabet.length)] }) }
        fun polish(line: String) = line.substring(0, line.length / 3) + "细" + line.substring(line.length / 3, 2 * line.length / 3) + "雨" + line.substring(2 * line.length / 3)
        val head = "（开头补的一段。）"
        val tail = "（末尾补的一段。）"
        // 预热
        repeat(3) {
            val f = big.take(300)
            val at = f.joinToString("\n") { polish(it) }
            TurnReview.revert(f.joinToString("\n"), at, "$head\n$at\n$tail", setOf(0, 150))
        }
        for ((size, limitMs) in listOf(1000 to 1000L, 3000 to 3000L)) {
            val flat = big.take(size)
            val bt = flat.joinToString("\n")
            val at = flat.joinToString("\n") { polish(it) }
            val ct = "$head\n$at\n$tail"
            val started = System.nanoTime()
            val result = TurnReview.revert(bt, at, ct, setOf(0, size / 2, size - 1))
            val ms = (System.nanoTime() - started) / 1_000_000
            println("TurnReview：$size 段整章润色、首尾补段、拒 3 块 → $ms ms，失败 ${result.failed}")
            assertFalse(TurnReview.review(bt, at, ct).whole)
            assertTrue("$size 段用了 $ms ms", ms < limitMs)
            assertTrue(result.failed.isEmpty())
        }
    }
}
