package me.rerere.rikkihub.data.book.history

import me.rerere.rikkihub.data.book.history.TurnReview.State
import me.rerere.rikkihub.data.book.history.TurnReview.State.CHANGED_LATER
import me.rerere.rikkihub.data.book.history.TurnReview.State.GONE
import me.rerere.rikkihub.data.book.history.TurnReview.State.PENDING
import me.rerere.rikkihub.data.book.history.TurnReview.State.REVERTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐处审阅引擎：用例来自设计评审时的原型与探针。
 * 每个测试方法里的用例都跑完再一起报告，哪几例不对一眼能看出。
 */
class TurnReviewTest {
    private class Cases {
        val failures = ArrayList<String>()

        /**
         * 拒绝 [picks] 后的结果：[expected] 为 null 表示不该写（或写了也和现在一样）；[exact] 逐字核对（含空行），否则只比非空行。
         * [states] 不为 null 时核对拒绝前的块状态。
         */
        fun case(name: String, before: String, after: String, current: String, picks: Set<Int>, expected: String?, states: List<State>? = null, exact: Boolean = true) {
            if (states != null) {
                val actual = TurnReview.review(before, after, current).pieces.map { it.state }
                if (actual != states) failures += "$name：状态 $actual，应为 $states"
            }
            if (picks.isEmpty() && expected != null) return
            val result = TurnReview.revert(before, after, current, picks)
            if (expected == null) {
                if (result.text != null && keys(result.text) != keys(current)) failures += "$name：不该写，却写成了 ${show(result.text)}"
                return
            }
            val want = BookLocks.normalize(expected)
            val ok = result.text != null && result.failed.isEmpty() && if (exact) result.text == want else keys(result.text) == keys(want)
            if (!ok) failures += "$name：应为 ${show(want)}，实际 ${result.text?.let(::show)}（失败 ${result.failed}，状态 ${TurnReview.review(before, after, current).pieces.map { it.state }}）"
        }

        fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
            if (!ok) failures += "$name ${detail()}"
        }

        fun done() = assertTrue("\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun cases(block: Cases.() -> Unit) = Cases().apply(block).done()

    private fun j(vararg lines: String) = lines.joinToString("\n")

    private fun j(lines: List<String>) = lines.joinToString("\n")

    private fun blocks(before: String, after: String) = TurnReview.review(before, after, after).pieces

    // ---- 风险探针用例 -----------------------------------------------------------------------

    private val p1 = "雨下了一整夜，镇上的石板路泛着冷光。"
    private val p2 = "他推开院门，院子里积着一洼洼的水。"
    private val p3 = "远处传来几声狗叫，又很快安静下来。"
    private val p4 = "她把伞靠在门边，抖了抖袖口的水珠。"
    private val p5 = "灯芯噼啪响了一声，屋里暗了一瞬。"
    private val p6 = "第二天一早，镇口来了一辆马车。"
    private val y = "他没说话。"
    private val x = "“嗯。”"
    private val sep = "* * *"

    @Test
    fun `round one cases still revert exactly the block`() = cases {
        val base = j(p1, "", p2, "", p3, "", x, "", p4, "", p5)
        val variants = listOf(
            "改写一段" to j(p1, "", "他推开木门。", "", p3, "", x, "", p4, "", p5),
            "删除一段" to j(p1, "", p2, "", p3, "", x, "", p5),
            "插入与已有段重复的短段" to j(p1, "", p2, "", x, "", p3, "", x, "", p4, "", p5),
            "改写后的新文字恰是别段子串" to j(p1, "", "狗叫声。", "", p3, "", x, "", p4, "", p5),
        )
        for ((name, after) in variants) {
            case("A $name / 现在 == 改后", base, after, after, setOf(0), base)
            case("A $name / 末尾补段", base, after, "$after\n\n（后来补的一段。）", setOf(0), "$base\n\n（后来补的一段。）")
            case("A $name / 开头补段", base, after, "（开头补的一段。）\n\n$after", setOf(0), "（开头补的一段。）\n\n$base")
        }
    }

    @Test
    fun `ambiguous windows, repeated inserts and deletes`() = cases {
        val b1Before = j(p1, "", sep, "", y, "", sep, "", p2, "", sep, "", x, "", sep, "", p3)
        val b1After = j(p1, "", sep, "", x, "", sep, "", p2, "", sep, "", x, "", sep, "", p3)
        case("B1 重复窗口，删掉一场后拒绝", b1Before, b1After, j(p1, "", sep, "", x, "", sep, "", p3), setOf(0), null, exact = false)
        case("B1′ 重复窗口，现在 == 改后", b1Before, b1After, b1After, setOf(0), b1Before)
        case("B1b 无空行重复窗口，删中间三段", j(p1, "“走吧。”", y, p2, p3, "“走吧。”", x, p4), j(p1, "“走吧。”", x, p2, p3, "“走吧。”", x, p4), j(p1, "“走吧。”", x, p4), setOf(0), null)
        case("B2 紧挨着重复插入", j(p1, "", x, "", p2), j(p1, "", x, "", x, "", p2), j(p1, "", x, "", x, "", p2), setOf(0), j(p1, "", x, "", p2))
        case("B2′ 无空行", j(p1, x, p2), j(p1, x, x, p2), j(p1, x, x, p2), setOf(0), j(p1, x, p2))
        val b3Before = j(p1, "", p6)
        val b3After = j(p1, "", p2, "", p3, "", p4, "", p6)
        case("B3-1 连续插入三段，只拒中间", b3Before, b3After, b3After, setOf(1), j(p1, "", p2, "", p4, "", p6))
        case("B3-2 重开再拒第三段", b3Before, b3After, j(p1, "", p2, "", p4, "", p6), setOf(2), j(p1, "", p2, "", p6))
        val b4Before = j(p1, "", p2, "", p3)
        val b4After = j(p1, "", p3)
        case("B4-1 删除块 + 你在原位置补写", b4Before, b4After, j(p1, "", "（用户新写的一段。）", "", p3), setOf(0), null)
        case("B4-2 删除块 + 你在 P1 下一行补写", b4Before, b4After, j(p1, "（用户新写的一段。）", "", p3), setOf(0), null)
        case("B4-3 删除块 + 你多加了一个空行（只比非空行，恒等）", b4Before, b4After, j(p1, "", "", p3), setOf(0), b4Before, exact = false)
        case(
            "B5 孤立 \\r", j(p1 + "\r" + p2, "", p3, "", p4, "", p5), j(p1, p2, "", "他推开木门。", "", p4, "", "灯芯响了一声。"),
            j(p1, p2, "", "他推开木门。", "", p4, "", "灯芯响了一声。"), setOf(0), j(p1, p2, "", p3, "", p4, "", "灯芯响了一声。"),
        )
        check("B5b \\r\\r 稿 1 块", blocks(j(p1 + "\r\r", "\r\r", p2 + "\r\r", "\r\r", p3), j(p1, "", p2, "", "远处传来狗叫。")).size == 1)
        check("B6 BOM 标题 1 块", blocks("﻿第一章　雨夜\n\n$p1\n\n$p2", "第一章　雨夜\n\n$p1\n\n他推开木门。").size == 1)
        case("B8 排版加全角缩进后拒绝", j(p1, "", p2, "", p3), j(p1, "", "他推开木门。", "", p3), j("　　$p1", "", "　　他推开木门。", "", "　　$p3"), setOf(0), j("　　$p1", "", "　　$p2", "", "　　$p3"))
        val b9Before = j(p1, "", p2, "", p3, "", p4, "", p5)
        val b9After = j(p1, "", "他推开木门。", "", p3, "", p4, "", p5)
        case("B9-1 块被剪切到文末", b9Before, b9After, j(p1, "", p3, "", p4, "", p5, "", "他推开木门。"), setOf(0), null)
        case("B9-2 远处一段挪到块前", b9Before, b9After, j(p1, "", p5, "", "他推开木门。", "", p3, "", p4), setOf(0), j(p1, "", p5, "", p2, "", p3, "", p4))
        val b10Before = j(p1, "", p2, "", p3)
        val b10After = j("第一段被改写了。", "", p2, "", "末段被改写了。")
        val b10Cur = j("题记。", "", "第一段被改写了。", "", p2, "", "末段被改写了。", "", "后记。")
        case("B10-1 首末段都改，你加了题记和后记，拒两块", b10Before, b10After, b10Cur, setOf(0, 1), j("题记。", "", p1, "", p2, "", p3, "", "后记。"))
        case("B10-2 只拒末段", b10Before, b10After, b10Cur, setOf(1), j("题记。", "", "第一段被改写了。", "", p2, "", p3, "", "后记。"))
        case("B10-3 只拒首段", b10Before, b10After, b10Cur, setOf(0), j("题记。", "", p1, "", p2, "", "末段被改写了。", "", "后记。"))
        case("B11 删除块已拒过，重开", j(p1, "", x, "", p2, "", x, "", p3), j(p1, "", x, "", p2, "", p3), j(p1, "", x, "", p2, "", x, "", p3), setOf(0), null)
        case("B11b 无空行重复插入已拒，重开", j(p1, x, p2, x, p3), j(p1, x, x, p2, x, p3), j(p1, x, p2, x, p3), setOf(0), null)
        val paras = (1..24).map { "第${it}段：他走过长街，雨水顺着屋檐一滴一滴落下来。" }
        val alt = (1..24).map { "第${it}段：他穿过长街，屋檐上的雨一滴滴砸下来。" }
        val polished = paras.indices.map { if (it in 4 until 9) alt[it] else paras[it] }
        val heavy = paras.indices.map { if (it % 12 != 0) alt[it] else paras[it] }
        check("B12 无空行润色 5 段 → 5 块", blocks(j(paras), j(polished)).size == 5)
        check("B12 无空行润色 22 段 → 22 块", blocks(j(paras), j(heavy)).size == 22)
    }

    @Test
    fun `a misplaced position is caught by the self check`() = cases {
        val before = j(p1, "", y, "", p2, "", x, "", p3)
        val after = j(p1, "", x, "", p2, "", x, "", p3)
        // 块 0 本该在现在稿第 1 个非空行，故意挪到另一个“嗯。”（第 3 个）
        check("B13 错放（D 路径）拦下", TurnReview.revert(before, after, "$after\n\n（补一段。）", setOf(0)) { _, _ -> 3 }.text == null)
        check("B13 错放（恒等路径）拦下", TurnReview.revert(before, after, after, setOf(0)) { _, _ -> 3 }.text == null)
        val misBefore = listOf("清晨，雨停了。", "他推开门，看见院子里积了一层水。", "远处传来狗叫声。", "“嗯。”", "她在厨房里煮粥。")
        val misAfter = listOf("清晨，雨停了。", "“嗯。”", "他推开门，看见院子里积了一层水。", "远处传来狗叫声。", "“嗯。”", "她在厨房里煮粥。")
        check("错放到另一个“嗯。”：拦下不写", TurnReview.revert(j(misBefore), j(misAfter), j(misAfter), setOf(0)) { _, _ -> 4 }.text == null)
        check("整体后移一行：拦下不写", TurnReview.revert(j(misBefore), j(misAfter), j(misAfter), setOf(0)) { _, start -> start + 1 }.text == null)
    }

    @Test
    fun `round two cases`() = cases {
        case(
            "N1 句子跨段挪动 → 不拆", j("他推开门。院子里很静。", "她在屋里等他，灯还亮着。"), j("他推开门。", "院子里很静，她在屋里等他，灯还亮着。"),
            j("他推开门。", "院子里很静，她在屋里等他，灯还亮着。"), setOf(0), j("他推开门。院子里很静。", "她在屋里等他，灯还亮着。"),
        )
        case(
            "N2 两段对调润色 → 不拆", j(p1, p2, p3, p6), j(p1, "远处几声狗叫，很快又静了。", "他推开院门，院里积着水。", p6),
            j(p1, "远处几声狗叫，很快又静了。", "他推开院门，院里积着水。", p6), setOf(0), j(p1, p2, p3, p6),
        )
        val n4Before = j(p1, "", p2)
        val n4After = j(p1, "", p2, "", p3)
        case("N4 AI 末尾续写，你接着写，拒 AI 那段", n4Before, n4After, j(p1, "", p2, "", p3, "", "（你接着写的。）"), setOf(0), j(p1, "", p2, "", "（你接着写的。）"))
        val n5After = j(p1, "", p2, "", p3, "", p4)
        case("N5-1 AI 续写两段 + 你接着写，只拒第二段", n4Before, n5After, j(p1, "", p2, "", p3, "", p4, "", "（你接着写的。）"), setOf(1), j(p1, "", p2, "", p3, "", "（你接着写的。）"))
        case("N5-2 重开再拒第一段", n4Before, n5After, j(p1, "", p2, "", p3, "", "（你接着写的。）"), setOf(0), j(p1, "", p2, "", "（你接着写的。）"))
        case("N6 AI 插两段，你改了第一段", j(p1, "", p6), j(p1, "", p2, "", p3, "", p6), j(p1, "", "他推开院门，院子里全是水。", "", p3, "", p6), setOf(0), null)
        case("N6′ 同上，拒第二段", j(p1, "", p6), j(p1, "", p2, "", p3, "", p6), j(p1, "", "他推开院门，院子里全是水。", "", p3, "", p6), setOf(1), null)
        case("N7 你删掉 AI 的“嗯。”后自己写了一句再写“嗯。”", n4Before, j(p1, "", p2, "", x), j(p1, "", p2, "", "他点点头。", "", x), setOf(0), null)
        check("N8 删段间空行 → 0 块", blocks(j(p1, "", p2, "", p3), j(p1, p2, "", p3)).isEmpty())
        val n9Before = j(sep, "", y, "", sep, "", p2, "", sep, "", x, "", sep)
        val n9After = j(sep, "", x, "", sep, "", p2, "", sep, "", x, "", sep)
        case("N9 短句窗口 + 别处小改", n9Before, n9After, j(sep, "", x, "", sep, "", "他推开院门，院里积着一洼洼的水。", "", sep, "", x, "", sep), setOf(0), null)
    }

    private fun lines12() = (1..12).flatMap { listOfNotNull("".takeIf { _ -> it > 1 }, "第${it}段：他走过长街，雨水顺着屋檐一滴一滴落下来。") }
    private fun polished12() = (1..12).flatMap { listOfNotNull("".takeIf { _ -> it > 1 }, "第${it}段：他穿过长街，屋檐上的雨一滴滴砸下来。") }
    private fun polishedIns12() = polished12().toMutableList().apply { add(8, ""); add(8, "（你在中间插的一段。）") }

    @Test
    fun `full chapter polish stays reviewable after your own edits`() = cases {
        val sp = lines12()
        val spPol = polished12()
        val spCur = spPol.toMutableList().apply { set(10, "第6段：他穿过长街，屋檐上的雨一滴滴砸下来，砸在青石上。") }
        case("V2-a 全章逐段润色后你改了第 6 段，拒第 2 段", j(sp), j(spPol), j(spCur), setOf(1), j(spCur.toMutableList().apply { set(2, sp[2]) }))
        check("V2-a 可拒绝 11 / 12", TurnReview.review(j(sp), j(spPol), j(spCur)).pieces.count { it.state == PENDING } == 11)
        val spIns = polishedIns12()
        case("V2-b 你在中间插了一段，拒第 2 段", j(sp), j(spPol), j(spIns), setOf(1), j(spIns.toMutableList().apply { set(2, sp[2]) }))
        check("V2-b 可拒绝 12 / 12", TurnReview.review(j(sp), j(spPol), j(spIns)).pieces.count { it.state == PENDING } == 12)
        case("V2-b2 拒紧挨着你插入段的第 4 段", j(sp), j(spPol), j(spIns), setOf(3), j(spIns.toMutableList().apply { set(6, sp[6]) }))
        case("V2-b3 拒紧挨着你插入段之后的第 5 段", j(sp), j(spPol), j(spIns), setOf(4), j(spIns.toMutableList().apply { set(10, sp[8]) }))
        case("V2-b4 一次拒第 2、第 4 段", j(sp), j(spPol), j(spIns), setOf(1, 3), j(spIns.toMutableList().apply { set(2, sp[2]); set(6, sp[6]) }))
        case(
            "X6-a 两个改写块中间夹着你补的段，拒前一段", j(p1, "", p2, "", p3, "", p5), j(p1, "", "他推开院门，院里积着一层薄水。", "", "远处几声狗叫，很快又静了。", "", p5),
            j(p1, "", "他推开院门，院里积着一层薄水。", "", "（你补的一句。）", "", "远处几声狗叫，很快又静了。", "", p5), setOf(0),
            j(p1, "", p2, "", "（你补的一句。）", "", "远处几声狗叫，很快又静了。", "", p5),
        )
        case("V2-d 你删掉 AI 末尾“嗯。”后重写“嗯。”并接着写", j(p1, "", p2), j(p1, "", p2, "", x), j(p1, "", p2, "", x, "", "他点点头，转身走了。"), setOf(0), null)
        case("V2-e 无空行稿改成段间空一行后拒绝", j(p1, p2, p3), j(p1, "他推开木门。", p3), j(p1, "", "他推开木门。", "", p3), setOf(0), j(p1, "", p2, "", p3))
        case("V2-g AI 改写末段，你接着写，拒绝改写", j(p1, "", p2), j(p1, "", "他推开木门。"), j(p1, "", "他推开木门。", "", "（你接着写的。）"), setOf(0), j(p1, "", p2, "", "（你接着写的。）"))
    }

    @Test
    fun `round three adversarial cases`() = cases {
        val a2 = "他推开院门，院里积着一层薄水。"
        case("X1-a 你在 AI 改写段前面粘回了原文，再拒绝", j(p1, "", p2, "", p3), j(p1, "", a2, "", p3), j(p1, "", p2, "", a2, "", p3), setOf(0), j(p1, "", p2, "", p2, "", p3))
        case("X1-b 粘在后面", j(p1, "", p2, "", p3), j(p1, "", a2, "", p3), j(p1, "", a2, "", p2, "", p3), setOf(0), j(p1, "", p2, "", p2, "", p3))
        case("X1-c 你在改写段前面写了一段新文字", j(p1, "", p2, "", p3), j(p1, "", a2, "", p3), j(p1, "", "（你新写的一段。）", "", a2, "", p3), setOf(0), j(p1, "", "（你新写的一段。）", "", p2, "", p3))
        val z = "他推开院门，院子里全是积水。"
        case(
            "X2 AI 把相邻两段改成同一句；你删掉其中一句，再拒剩下那句", j(p1, "他推开院门，院子里积着一洼洼的水。", "他推开院门，院子里积着一层薄薄的水。", p5), j(p1, z, z, p5),
            j(p1, z, p5), setOf(0), j(p1, "他推开院门，院子里积着一洼洼的水。", p5),
        )
        val x3After = j(p1, "", p2, "", p3, "", sep, "", p4)
        case("X3-a 分隔符一删一加不当成移动", j(p1, "", sep, "", p2, "", p3, "", p4), x3After, x3After, setOf(0), j(p1, "", sep, "", p2, "", p3, "", sep, "", p4), exact = false)
        val x3bAfter = j(p1, "", p2, "", x, "", p3, "", p4)
        case("X3-b 删了两个“嗯。”加了一个，拒移动", j(p1, "", x, "", p2, "", p3, "", x, "", p4), x3bAfter, x3bAfter, setOf(1), j(p1, "", p2, "", p3, "", x, "", p4), exact = false)
        val x4After = j(p1, "“嗯。”", "“走吧。”", "“行。”", "“是。”", p6)
        case("X4-a 短对白一删一插，只拒第一处", j(p1, "“好。”", "“嗯。”", "“走吧。”", "“行。”", p6), x4After, x4After, setOf(0), j(p1, "“好。”", "“嗯。”", "“走吧。”", "“行。”", "“是。”", p6))
        val tB = (1..6).map { "第${it}日，他沿着河岸走了很久，雨一直没停。" }
        val tA = tB.subList(1, 6) + "第七日，他沿着河岸走了很久，雨终于停了。"
        case("X4-b 句式相近：删第 1 段、末尾加一段；只拒第 1 处", j(tB), j(tA), j(tA), setOf(0), j(tB + tA[5]))
        val tA2 = (2..6).map { "第${it}日，他沿着河岸走了好久，雨还是没停。" } + "第七日，他沿着河岸走了很久，雨终于停了。"
        case("X4-c 同上且挪动的段都润了；拒第 1 处 = 整块换回", j(tB), j(tA2), j(tA2), setOf(0), j(tB))
        case("X5-a 短句插入后你改了后邻段 → 不动", j(p1, "", p3), j(p1, "", x, "", p3), j(p1, "", x, "", "远处传来几声狗叫。"), setOf(0), null)
        case("X5-b 你改的是隔一段的地方", j(p1, "", p3, "", p5), j(p1, "", x, "", p3, "", p5), j(p1, "", x, "", p3, "", "灯芯响了。"), setOf(0), j(p1, "", p3, "", "灯芯响了。"))
        case("X5-c 短句改写后你接着写 → 不动", j(p1, "", "他说。"), j(p1, "", x), j(p1, "", x, "", "（你接着写的。）"), setOf(0), null)
    }

    @Test
    fun `cutting change regions at rewrite blocks`() = cases {
        val a2 = "他推开院门，院里积着一层薄水。"
        val a3 = "远处几声狗叫，很快又静了。"
        val b = j(p1, "", p2, "", p3, "", p5)
        val af = j(p1, "", a2, "", a3, "", p5)
        case("C1 你改了 AI 改写段又在前面粘回原文", b, af, j(p1, "", p2, "", "他推开院门，院里积着一层薄薄的水。", "", a3, "", p5), setOf(0), null)
        check("C1 块 0 不标已换回", TurnReview.review(b, af, j(p1, "", p2, "", "他推开院门，院里积着一层薄薄的水。", "", a3, "", p5)).pieces[0].state == CHANGED_LATER)
        case("C1′ 粘在后面", b, af, j(p1, "", "他推开院门，院里积着一层薄薄的水。", "", p2, "", a3, "", p5), setOf(0), null)
        case("C2 改后段出现两份，旁边一段你也改了", b, af, j(p1, "", a2, "", a2, "", "远处几声狗叫。", "", p5), setOf(0), null)
        case("C3 段里 A(b)、B(b) 各一份", b, af, j(p1, "", p2, "", "（你写的一句。）", "", a2, "", "远处几声狗叫。", "", p5), setOf(0), null)
        case("C4 你把 AI 改写的两段对调并在中间加了一句，拒块1", b, af, j(p1, "", a3, "", "（你写的一句。）", "", a2, "", p5), setOf(1), null)
        case("C6 AI 把第 2 段改成第 3 段的原文、又改写第 3 段；你补一句后拒块1", b, j(p1, "", p3, "", a3, "", p5), j(p1, "", "（你补的一句。）", "", p3, "", a3, "", p5), setOf(1), j(p1, "", "（你补的一句。）", "", p3, "", p5))
        case("C7 你整段重写了 AI 改的第 2 段，拒第 3 段", b, af, j(p1, "", "（你重写的第二段。）", "", a3, "", p5), setOf(1), j(p1, "", "（你重写的第二段。）", "", p3, "", p5))
        case("C8 窗口 0 次：你改了块两侧的邻段", b, af, j("雨下了一整夜。", "", a2, "", "远处几声狗叫。", "", p5), setOf(0), j("雨下了一整夜。", "", p2, "", "远处几声狗叫。", "", p5))
        case("C9 窗口 0 次 + 短句插入 → 不动", j(p1, "", p3), j(p1, "", x, "", p3), j("雨下了一整夜。", "", x, "", "远处几声狗叫。"), setOf(0), null)
    }

    @Test
    fun `blank lines when putting blocks back`() = cases {
        case("E1 连续插入三段，只拒中间", j(p1, "", p6), j(p1, "", p2, "", p3, "", p4, "", p6), j(p1, "", p2, "", p3, "", p4, "", p6), setOf(1), j(p1, "", p2, "", p4, "", p6))
        case("E2 删除中间一段", j(p1, "", p2, "", p3), j(p1, "", p3), j(p1, "", p3), setOf(0), j(p1, "", p2, "", p3))
        case("E3 删除开头一段", j(p1, "", p2, "", p3), j(p2, "", p3), j(p2, "", p3), setOf(0), j(p1, "", p2, "", p3))
        case("E4 删除末尾一段", j(p1, "", p2), j(p1), j(p1), setOf(0), j(p1, "", p2))
        case("E5 AI 末尾续写，你接着写", j(p1, "", p2), j(p1, "", p2, "", p3), j(p1, "", p2, "", p3, "", "（你接着写的。）"), setOf(0), j(p1, "", p2, "", "（你接着写的。）"))
        case("E6 你把段间空行都删了，再拒 AI 插的段", j(p1, "", p3), j(p1, "", p2, "", p3), j(p1, p2, p3), setOf(0), j(p1, p3))
        case("E7 你把段间空行都删了，再拒 AI 的删除", j(p1, "", p2, "", p3), j(p1, "", p3), j(p1, p3), setOf(0), j(p1, p2, p3))
        val merged = "他推开院门，院子里积着水，远处的狗叫了几声。"
        case("E8 AI 把两段合成一段，两块都拒", j(p1, "", p2, "", p3, "", p5), j(p1, "", merged, "", p5), j(p1, "", merged, "", p5), setOf(0, 1), j(p1, "", p2, "", p3, "", p5))
        case("E9 你在段间多空了两行，再拒 AI 插的段", j(p1, "", p3), j(p1, "", p2, "", p3), j(p1, "", "", "", p2, "", p3), setOf(0), j(p1, "", "", "", p3))
        case("E10 无空行稿删除中间一行", j(p1, p2, p3), j(p1, p3), j(p1, p3), setOf(0), j(p1, p2, p3))
        case("E11 删除块，你在两侧各加了空行", j(p1, "", p2, "", p3), j(p1, "", p3), j(p1, "", "", "", p3), setOf(0), j(p1, "", p2, "", "", "", p3))
    }

    // ---- 可行性原型用例 -----------------------------------------------------------------------

    private val q1 = "清晨，雨停了。"
    private val q2 = "他推开门，看见院子里积了一层水。"
    private val q3 = "远处传来狗叫声。"
    private val qy = "“嗯。”"
    private val q4 = "她在厨房里煮粥。"
    private val q5 = "这一天就这样开始了。"
    private val tail = "（用户后来又在末尾补了一段。）"
    private val head = "（用户后来又在开头补了一段。）"
    private val ind = "　　"

    @Test
    fun `merge prototype cases, blank lines, line endings, first and last paragraphs`() = cases {
        val before = j(q1, q2, q3, qy, q4, q5)
        for ((name, after) in listOf(
            "改写一段" to j(q1, "他推开木门。", q3, qy, q4, q5),
            "删除一段" to j(q1, q2, q3, qy, q5),
            "插入与已有段重复的短段" to j(q1, q2, qy, q3, qy, q4, q5),
            "改写后的新文字恰是别段子串" to j(q1, "狗叫声。", q3, qy, q4, q5),
        )) {
            case("$name / 现在==改后", before, after, after, setOf(0), before)
            case("$name / 末尾补段", before, after, "$after\n$tail", setOf(0), "$before\n$tail")
            case("$name / 开头补段", before, after, "$head\n$after", setOf(0), "$head\n$before")
        }
        val md = listOf(q1, q2, q3, q4, q5).joinToString("\n\n")
        val mdAfter = listOf(q1, "他推开木门。", q3, "院墙上爬满了青苔。", q4, q5).joinToString("\n\n")
        val mdCur = mdAfter.replace(q5, "这一天就这样慢慢开始了。")
        case("空行稿 · 两块都拒绝", md, mdAfter, mdCur, setOf(0, 1), md.replace(q5, "这一天就这样慢慢开始了。"))
        case("空行稿 · 只拒绝插入", md, mdAfter, mdCur, setOf(1), listOf(q1, "他推开木门。", q3, q4, "这一天就这样慢慢开始了。").joinToString("\n\n"))
        val md3 = listOf(q1, q2, q3, q4).joinToString("\n\n\n")
        val md3After = listOf(q1, q2, "新的一段。", q3, q4).joinToString("\n\n\n")
        case("双空行 · 拒绝插入（现在==改后）", md3, md3After, md3After, setOf(0), md3)
        case("双空行 · 拒绝插入（开头补段）", md3, md3After, "$head\n\n\n$md3After", setOf(0), "$head\n\n\n$md3")
        val revertedMd = listOf(q1, q2, q3, "院墙上爬满了青苔。", q4, q5).joinToString("\n\n")
        case("CRLF+BOM · 拒绝改写", "﻿" + md.replace("\n", "\r\n"), "﻿" + mdAfter.replace("\n", "\r\n"), mdAfter, setOf(0), revertedMd)
        case("孤立 CR 稿 · 拒绝改写", md.replace("\n", "\r"), mdAfter.replace("\n", "\r"), mdAfter, setOf(0), revertedMd)
        case(
            "\\r\\r\\n 稿 · 拒绝改写", md.replace("\n", "\r\r\n"), mdAfter.replace("\n", "\r\r\n"), BookLocks.normalize(mdAfter.replace("\n", "\r\r\n")), setOf(0),
            BookLocks.normalize(revertedMd.replace("\n", "\r\r\n")),
        )
        val fl = j(q1, q2, q3)
        case("改写首段 · 末尾补段", fl, j("雨停了。", q2, q3), j("雨停了。", q2, q3, tail), setOf(0), j(q1, q2, q3, tail))
        case("改写末段 · 开头补段", fl, j(q1, q2, "狗叫。"), j(head, q1, q2, "狗叫。"), setOf(0), j(head, q1, q2, q3))
        case("删除首段", fl, j(q2, q3), j(q2, q3), setOf(0), fl)
        case("末尾追加一段 · 现在==改后", fl, j(q1, q2, q3, q4), j(q1, q2, q3, q4), setOf(0), fl)
        case("末尾追加一段 · 用户又在末尾补段", fl, j(q1, q2, q3, q4), j(q1, q2, q3, q4, tail), setOf(0), j(q1, q2, q3, tail))
    }

    @Test
    fun `blocks finished in several rounds, inserted runs, edited anchors, repeated short lines`() = cases {
        val adjB = listOf(q1, q2, q3, q4).joinToString("\n\n")
        val adjA = listOf(q1, "他推开木门。", "远处有狗叫。", q4).joinToString("\n\n")
        case("相邻两块同时拒绝 · 末尾补段", adjB, adjA, "$adjA\n\n$tail", setOf(0, 1), "$adjB\n\n$tail")
        val s1 = listOf(q1, q2, "远处有狗叫。", q4).joinToString("\n\n")
        case("第一次只拒绝块0", adjB, adjA, adjA, setOf(0), s1)
        case("第二次重开拒绝块1", adjB, adjA, s1, setOf(1), adjB, listOf(REVERTED, PENDING))
        val insB = listOf(q1, q2, q3).joinToString("\n\n")
        val insA = listOf(q1, q2, "新段一。", "新段二。", "新段三。", q3).joinToString("\n\n")
        val i1 = listOf(q1, q2, "新段一。", "新段三。", q3).joinToString("\n\n")
        case("只拒绝中间一段", insB, insA, insA, setOf(1), i1)
        case("第二次重开拒绝第一段", insB, insA, i1, setOf(0), listOf(q1, q2, "新段三。", q3).joinToString("\n\n"), listOf(PENDING, REVERTED, PENDING))
        case("三段一起拒绝 · 开头补段", insB, insA, "$head\n\n$insA", setOf(0, 1, 2), "$head\n\n$insB")
        val chB = listOf(q1, q2, q3, q4).joinToString("\n\n")
        val chA = listOf(q1, "他推开木门。", q3, q4).joinToString("\n\n")
        case("块内又被改过", chB, chA, chA.replace("他推开木门。", "他推开那扇木门。"), setOf(0), null, listOf(CHANGED_LATER))
        case("前锚点被改过 → 改写块仍可拒", chB, chA, chA.replace(q1, "清晨，雨终于停了。"), setOf(0), chB.replace(q1, "清晨，雨终于停了。"), listOf(PENDING))
        case("后锚点被改过 → 同上", chB, chA, chA.replace(q3, "远处传来几声狗叫。"), setOf(0), chB.replace(q3, "远处传来几声狗叫。"), listOf(PENDING))
        case("锚点外被改过 → 仍可拒绝", chB, chA, chA.replace(q4, "她在厨房里慢慢煮粥。"), setOf(0), chB.replace(q4, "她在厨房里慢慢煮粥。"), listOf(PENDING))
        case("删除块的缝里被插了东西", j(q1, q2, q3), j(q1, q3), j(q1, "用户新写。", q3), setOf(0), null, listOf(CHANGED_LATER))
        val dupB = j(q1, qy, q2, qy, q3, qy, q4)
        val dupA = j(q1, qy, q2, q3, qy, q4)
        case("删第二个嗯 · 用户改了远处", dupB, dupA, dupA.replace(q4, "她在厨房里慢慢煮粥。"), setOf(0), dupB.replace(q4, "她在厨房里慢慢煮粥。"), listOf(PENDING))
        case("删第二个嗯 · 用户删了第一个嗯", dupB, dupA, j(q1, q2, q3, qy, q4), setOf(0), j(q1, q2, qy, q3, qy, q4), listOf(PENDING))
        case("末尾加的嗯 · 用户删了第一个嗯", dupB, j(q1, qy, q2, qy, q3, qy, q4, qy), j(q1, q2, qy, q3, qy, q4, qy), setOf(0), j(q1, q2, qy, q3, qy, q4), listOf(PENDING))
        case("紧挨着插一个相同短段 · 现在==改后", j(q1, qy, q2), j(q1, qy, qy, q2), j(q1, qy, qy, q2), setOf(0), j(q1, qy, q2))
        case("紧挨着插一个相同短段 · 用户把 P1 改了", j(q1, qy, q2), j(q1, qy, qy, q2), j("清晨，雨终于停了。", qy, qy, q2), setOf(0), j("清晨，雨终于停了。", qy, q2))
    }

    @Test
    fun `continuations, gone blocks and the S4 direction`() = cases {
        val cont = listOf(q1, q2).joinToString("\n\n")
        val contA = listOf(q1, q2, "AI 续写的一段。").joinToString("\n\n")
        case("AI 末尾续写，用户接着写", cont, contA, "$contA\n\n$tail", setOf(0), "$cont\n\n$tail")
        case("续写拒绝完成后重开：已不在稿中", cont, contA, "$cont\n\n$tail", emptySet(), "$cont\n\n$tail", listOf(GONE))
        val midB = j(q1, q2, q3)
        case("AI 中间插一段，用户紧接着写", midB, j(q1, "AI 插的一段。", q2, q3), j(q1, "AI 插的一段。", "用户写的。", q2, q3), setOf(0), j(q1, "用户写的。", q2, q3))
        case(
            "空行稿：AI 中间插一段，用户紧接着写", listOf(q1, q2, q3).joinToString("\n\n"), listOf(q1, "AI 插的一段。", q2, q3).joinToString("\n\n"),
            listOf(q1, "AI 插的一段。", "用户写的。", q2, q3).joinToString("\n\n"), setOf(0), listOf(q1, "用户写的。", q2, q3).joinToString("\n\n"),
        )
        case("AI 删一段，用户在同一个缝写了一段", midB, j(q1, q3), j(q1, "用户写的。", q3), setOf(0), null, listOf(CHANGED_LATER))
        val fork = listOf(q1, q2, q3, q4).joinToString("\n\n")
        val branch = listOf(q1, "他拉开门，院里积着水。", q3, q4).joinToString("\n\n")
        case("S4 · 正文没动", branch, fork, fork, setOf(0), branch)
        case("S4 · 正文改过别处", branch, fork, fork.replace(q4, "她在厨房里慢慢煮粥。"), setOf(0), branch.replace(q4, "她在厨房里慢慢煮粥。"))
        case("S4 · 正文也改了同一段", branch, fork, fork.replace(q2, "他推开门，院子里一片水。"), setOf(0), null, listOf(CHANGED_LATER))
        case("AI 插的段被你整段换成别的话 → 已不在稿中", j(q1, q2), j(q1, "AI 新写的一段。", q2), j(q1, "完全无关的另一句。", q2), setOf(0), null, listOf(GONE))
    }

    @Test
    fun `scene separators, indentation and five polished lines`() = cases {
        val s = "* * *"
        val winB = j(q1, s, "“好。”", s, qy, s, q5)
        val winA = j(q1, s, qy, s, qy, s, q5)
        case("删掉后一场", winB, winA, j(q1, s, qy, s, q5), setOf(0), null, listOf(CHANGED_LATER))
        case("窗口在改后稿里重复 → 也不动", winB, winA, "$winA\n$tail", setOf(0), null, listOf(CHANGED_LATER))
        case("现在 == 改后（映射恒等，可拒绝）", winB, winA, winA, setOf(0), winB, listOf(PENDING))
        case("短句插入块紧挨着你写的行 → 之后又改过", j(q1, s, q2, s, qy, q3), j(q1, s, qy, q2, s, qy, q3), j(q1, s, qy, "用户写的。", q2, s, qy, q3), setOf(0), null, listOf(CHANGED_LATER))
        val indB = listOf(q1, q2, q3).joinToString("\n\n")
        case(
            "改写块 · 换回行带缩进", indB, listOf(q1, "他推开木门。", q3).joinToString("\n\n"), listOf(ind + q1, ind + "他推开木门。", ind + q3).joinToString("\n\n"), setOf(0),
            listOf(ind + q1, ind + q2, ind + q3).joinToString("\n\n"), listOf(PENDING),
        )
        case("删除块 · 插回行带缩进", j(q1, q2, q3), j(q1, q3), j(ind + q1, ind + q3), setOf(0), j(ind + q1, ind + q2, ind + q3), listOf(PENDING))
        case("改前有缩进、AI 去掉缩进改写、排版没动 → 回到带缩进", j(q1, ind + q2, q3), j(q1, "他推开木门。", q3), j(q1, "他推开木门。", q3), setOf(0), j(q1, ind + q2, q3))
        val fiveB = j(q1, q2, q3, q4, q5)
        val fiveA = j("清晨，雨刚停。", "他推开木门，院里积着水。", "远处有狗叫。", "她在灶前煮粥。", "一天就这样开始了。")
        check("五段润色切成 5 块", blocks(fiveB, fiveA).size == 5)
        val fiveR = j("清晨，雨刚停。", q2, "远处有狗叫。", q4, "一天就这样开始了。")
        case("拒绝第 2、4 段 · 现在==改后", fiveB, fiveA, fiveA, setOf(1, 3), fiveR)
        case("拒绝第 2、4 段 · 末尾补段", fiveB, fiveA, "$fiveA\n$tail", setOf(1, 3), "$fiveR\n$tail")
        case("第二次重开拒绝第 1 段", fiveB, fiveA, fiveR, setOf(0), fiveR.replace("清晨，雨刚停。", q1), listOf(PENDING, REVERTED, PENDING, REVERTED, PENDING))
        check("只改缩进 → 0 块", blocks(listOf(ind + q1, ind + q2, ind + q3).joinToString("\n\n"), listOf(q1, q2, q3).joinToString("\n\n")).isEmpty())
        val reviewed = TurnReview.review(listOf(ind + q1, ind + q2, ind + q3).joinToString("\n\n"), listOf(q1, q2, q3).joinToString("\n\n"), listOf(q1, q2, q3).joinToString("\n\n"))
        check("只改缩进 → 记成只改了排版", reviewed.layout > 0) { "layout=${reviewed.layout}" }
    }

    @Test
    fun `sentences moving across paragraphs, swaps, code blocks and short lines`() = cases {
        val n1B = j(q1, "甲说了一句话。乙听了点点头。", "丙走过来。", q5)
        val n1A = j(q1, "甲说了一句话。", "乙听了点点头。丙走过来。", q5)
        check("N1 句子挪到相邻段不逐行拆", blocks(n1B, n1A).size == 1)
        case("N1 整块拒绝", n1B, n1A, "$n1A\n$tail", setOf(0), "$n1B\n$tail")
        val a2 = "她把信放进抽屉，锁好。"
        val b2 = "窗外的雨又大了起来。"
        val n2B = j(q1, a2, b2, q5)
        val n2A = j(q1, b2, a2, q5)
        check("纯对调绑成一次移动", blocks(n2B, n2A).let { it.size == 2 && it[0].partner == 1 && it[1].partner == 0 })
        case("纯对调 · 全部拒绝", n2B, n2A, n2A, setOf(0, 1), n2B)
        case("纯对调 · 只选第 1 块 → 连同搭档换回", n2B, n2A, n2A, setOf(0), n2B)
        case("纯对调 · 只选第 2 块 → 连同搭档换回", n2B, n2A, "$n2A\n$tail", setOf(1), "$n2B\n$tail")
        val n2A2 = j(q1, "窗外的雨，又大了起来。", "她把信放进抽屉里，锁好。", q5)
        check("对调 + 小改不逐行拆", blocks(n2B, n2A2).size == 1)
        case("对调 + 小改 · 整块拒绝", n2B, n2A2, n2A2, setOf(0), n2B)
        val codeB = j(q1, "", "    int x = 1;", "    int y = 2;", "", q2)
        val codeA = j(q1, "", "这里原来是一段代码。", "", q2)
        case("两行代码 → 一句话（行数不同，不套用缩进）", codeB, codeA, "$codeA\n\n$tail", setOf(0), "$codeB\n\n$tail")
        case("一行代码 → 一句话（排版没变 → 保留 4 空格）", j(q1, "", "    int x = 1;", "", q2), j(q1, "", "一句话。", "", q2), j(q1, "", "一句话。", "", q2), setOf(0), j(q1, "", "    int x = 1;", "", q2))
        case("N7 你删了 AI 的嗯，写了一句再写自己的嗯", j(q1, q2), j(q1, qy, q2), j(q1, "我想了想。", qy, q2), setOf(0), null, listOf(CHANGED_LATER))
        val n6B = listOf(q1, q2).joinToString("\n\n")
        val n6A = listOf(q1, "AI 新写的第一段，说他推开了门。", "AI 新写的第二段，说雨还在下。", q2).joinToString("\n\n")
        case("N6 第一段被你改过 → 两段都不能拒", n6B, n6A, n6A.replace("说他推开了门", "说他轻轻推开了门"), setOf(1), null, listOf(CHANGED_LATER, CHANGED_LATER))
    }

    @Test
    fun `twelve polished paragraphs with and without blank lines`() = cases {
        val q = listOf(
            "清晨，雨停了，屋檐还在滴水。", "他推开门，看见院子里积了一层水。", "远处传来几声狗叫，又很快安静下来。", "她在厨房里煮粥，锅盖被蒸汽顶得轻轻作响。",
            "他走到井边，打了一桶水洗脸。", "井水很凉，他打了个寒颤。", "母亲在屋里喊他吃饭。", "饭桌上摆着咸菜和两个鸡蛋。",
            "他吃得很快，像是怕被谁抢走。", "母亲看着他，没有说话。", "吃完饭，他背起书包出了门。", "这一天就这样开始了。",
        )
        val r = listOf(
            "清晨时分，雨停了，屋檐还在滴水。", "他推开木门，看见院子里积了薄薄一层水。", "远处传来几声狗叫，随后又安静下来。", "她在厨房里熬粥，锅盖被蒸汽顶得轻轻作响。",
            "他走到井边，打上一桶凉水洗脸。", "井水冰凉，他不由得打了个寒颤。", "母亲在屋里喊他回来吃饭。", "饭桌上摆着一碟咸菜和两个煮鸡蛋。",
            "他吃得飞快，像是怕被谁抢走似的。", "母亲静静看着他，什么也没说。", "吃过饭，他背起书包出了门。", "这一天，就这样开始了。",
        )
        val u6 = "井水冰凉，他不由得打了个寒颤，清醒了许多。"
        val uin = "他想起昨天夜里听见的那阵敲门声。"
        for (sep in listOf("\n", "\n\n")) {
            val tag = if (sep == "\n") "无空行" else "空行分隔"
            val qb = q.joinToString(sep)
            val qa = r.joinToString(sep)
            check("$tag · 切成 12 块", blocks(qb, qa).size == 12)
            val cur6 = r.toMutableList().apply { set(5, u6) }
            val st6 = MutableList(12) { PENDING }.apply { set(5, CHANGED_LATER) }
            val r6 = cur6.toMutableList().apply { set(1, q[1]) }
            case("$tag · 你只改了第 6 段，拒绝第 2 段", qb, qa, cur6.joinToString(sep), setOf(1), r6.joinToString(sep), st6)
            val st6r = st6.toMutableList().apply { set(1, REVERTED) }
            case("$tag · 拒后重开再拒第 1、3 段", qb, qa, r6.joinToString(sep), setOf(0, 2), r6.toMutableList().apply { set(0, q[0]); set(2, q[2]) }.joinToString(sep), st6r)
            case("$tag · 拒后重开拒第 5、7 段", qb, qa, r6.joinToString(sep), setOf(4, 6), r6.toMutableList().apply { set(4, q[4]); set(6, q[6]) }.joinToString(sep), st6r)
            val ins = r.toMutableList().apply { add(7, uin) }
            val insR = ins.toMutableList().apply { set(1, q[1]) }
            case("$tag · 你在第 7、8 段之间插了一段，拒绝第 2 段", qb, qa, ins.joinToString(sep), setOf(1), insR.joinToString(sep), List(12) { PENDING })
            val sti = MutableList(12) { PENDING }.apply { set(1, REVERTED) }
            case("$tag · 拒后重开拒第 7、8 段（夹着你插的段）", qb, qa, insR.joinToString(sep), setOf(6, 7), insR.toMutableList().apply { set(6, q[6]); set(8, q[7]) }.joinToString(sep), sti)
            val withHead = head + sep + qa
            case("$tag · 开头补一行，拒绝第 1、2 段", qb, qa, withHead, setOf(0, 1), head + sep + r.toMutableList().apply { set(0, q[0]); set(1, q[1]) }.joinToString(sep), List(12) { PENDING })
            case("$tag · 首尾各补一段，拒绝末段", qb, qa, withHead + sep + tail, setOf(11), head + sep + r.toMutableList().apply { set(11, q[11]) }.joinToString(sep) + sep + tail, List(12) { PENDING })
        }
    }

    @Test
    fun `tail relaxation, region heads, moves and the same-count fast path`() = cases {
        val f5b = listOf(q1, q2, q3).joinToString("\n\n")
        val f5a = listOf(q1, q2, "远处隐约传来狗叫声。").joinToString("\n\n")
        case("F5 拒绝末段", f5b, f5a, "$f5a\n\n$tail", setOf(0), "$f5b\n\n$tail", listOf(PENDING))
        case("F5 拒后重开 → 已换回", f5b, f5a, "$f5b\n\n$tail", emptySet(), "$f5b\n\n$tail", listOf(REVERTED))
        case("F5 你接着写后又删了改写段一个字 → 之后又改过", f5b, f5a, f5a.replace("隐约", "隐") + "\n\n$tail", setOf(0), null, listOf(CHANGED_LATER))
        val f5c = listOf(q1, q2, "远处隐约传来狗叫声。", "AI 又续写了一段。").joinToString("\n\n")
        case("F5 改写末段 + 续写一段，你接着写，拒绝", f5b, f5c, "$f5c\n\n$tail", setOf(0), "$f5b\n\n$tail", listOf(PENDING))
        case("F5 拒后重开 → 已换回", f5b, f5c, "$f5b\n\n$tail", emptySet(), "$f5b\n\n$tail", listOf(REVERTED))
        case("改写块前面紧挨着你写的行 → 可拒", j(q1, q2, q3), j("雨停了，天还阴着。", q2, q3), j(head, "雨停了，天还阴着。", q2, q3), setOf(0), j(head, q1, q2, q3), listOf(PENDING))
        case("插入块前面紧挨着你写的行 → 之后又改过", j(q1, q2), j(q1, "AI 插的一段。", q2), j(q1, "我写的一句。", "AI 插的一段。", q2), setOf(0), null, listOf(CHANGED_LATER))
        val mvB = j(q1, q2, q3, q4, q5)
        val mvA = j(q1, q3, q4, q2, q5)
        check("隔两段的移动绑定", blocks(mvB, mvA).let { it.size == 2 && it[0].partner == 1 })
        case("隔两段的移动，你在末尾补了一段 → 两块都可拒", mvB, mvA, "$mvA\n$tail", setOf(0), "$mvB\n$tail", listOf(PENDING, PENDING))
        case("隔两段的移动，你改了删除缝紧挨着的一段 → 搭档一起不写", mvB, mvA, mvA.replace(q3, "远处传来几声狗叫。"), setOf(1), null, listOf(CHANGED_LATER, PENDING))
        case("隔两段的移动，你改了插入块紧挨着的一段 → 搭档一起不写", mvB, mvA, mvA.replace(q4, "她在厨房里慢慢煮粥。"), setOf(0), null, listOf(PENDING, CHANGED_LATER))
        case("移动后你把挪过去的那段改了 → 两块都不写", mvB, mvA, mvA.replace(q2, "他推开门，院子里一片水。"), setOf(0), null)
        check("文字各不相同：删一段 + 插一段 → 2 块", blocks(j(q1, q2, q3, q4, q5), j(q1, q3, q4, "院墙上爬满了青苔。", q5)).size == 2)
        val days = "一二三四五六".map { "第${it}天，他照旧去河边看水。" }
        check("句式相近：删“第二天”+ 末尾加“第六天” → 2 块", blocks(j(days.take(5)), j(days[0], days[2], days[3], days[4], days[5])).size == 2)
        check("正常润色一段 → 1 块", blocks(j(q1, q2, q3), j(q1, "他推开门，看见院子里积了一层雨水。", q3)).size == 1)
        case("改写块后面你接着写了一段", j(q1, q2, q3, q4), j(q1, "他推开木门。", q3, q4), j(q1, "他推开木门。", "我补的一句。", q3, q4), setOf(0), j(q1, q2, "我补的一句。", q3, q4), listOf(PENDING))
        val s = "* * *"
        case("场景分隔符夹着的改写块，你删了后一个分隔符", j(q1, s, q2, s, q3), j(q1, s, "他推开木门。", s, q3), j(q1, s, "他推开木门。", q3), setOf(0), j(q1, s, q2, q3), listOf(PENDING))
        val dl = listOf("“好。”他说。", "“行。”他说。", "“走。”他说。", "“停。”他说。", "“等。”他说。", "“来。”他说。")
        val dlb = j(dl.take(5))
        val dla = j(dl[0], dl[2], dl[3], dl[4], dl[5])
        check("短对白一删一插：2 块不绑定", blocks(dlb, dla).let { it.size == 2 && it.all { p -> p.partner == null } })
        case("只拒末尾加的那句", dlb, dla, dla, setOf(1), j(dl[0], dl[2], dl[3], dl[4]))
        case("只拒删掉的那句（你在末尾补了一段）", dlb, dla, "$dla\n$tail", setOf(0), j(dl + tail))
        val nums = "一二三四五六"
        val ndB = j((0 until 5).map { "第${nums[it]}日，晴，去河边。" })
        val ndA = j((0 until 6).filter { it != 1 }.map { "第${nums[it]}日，晴，去了河边。" })
        check("“第 N 日”一删一插且都润色 → 不逐行拆", blocks(ndB, ndA).size == 1)
        case("“第 N 日” · 整块拒绝", ndB, ndA, "$ndA\n$tail", setOf(0), "$ndB\n$tail")
        check("“第 N 日”逐行润色没有错位 → 3 块", blocks(j("第一日，晴，去河边。", "第二日，阴，在家读书。", "第三日，雨，去镇上。"), j("第一日，晴，去了河边。", "第二日，阴，在家里读书。", "第三日，雨，去了镇上。")).size == 3)
    }

    @Test
    fun `continued twice and you kept writing`() = cases {
        val c1 = "他走到门口，又回头看了一眼。"
        val c2 = "雨又下了起来，院子里一片白。"
        val cb = listOf(q1, q2).joinToString("\n\n")
        val ca = listOf(q1, q2, c1, c2).joinToString("\n\n")
        case("拒最后一段", cb, ca, "$ca\n\n$tail", setOf(1), listOf(q1, q2, c1, tail).joinToString("\n\n"), listOf(PENDING, PENDING))
        val reopened = TurnReview.review(cb, ca, listOf(q1, q2, c1, tail).joinToString("\n\n")).pieces.map { it.state }
        check("拒后重开：第 1 段可拒，第 2 段已换回或已不在稿中", reopened[0] == PENDING && reopened[1] in setOf(REVERTED, GONE)) { "实际 $reopened" }
        case("拒后重开再拒第 1 段", cb, ca, listOf(q1, q2, c1, tail).joinToString("\n\n"), setOf(0), listOf(q1, q2, tail).joinToString("\n\n"))
        val dup = "雨还在下，他一动不动地站着。"
        val dpa = listOf(q1, q2, dup, dup).joinToString("\n\n")
        case("续写的两段相同、你接着写 → 取多出行最少的解", cb, dpa, "$dpa\n\n$tail", emptySet(), "$dpa\n\n$tail", listOf(PENDING, PENDING))
        val sepB = j(q1, "* * *", q2, q3, q4)
        val sepA = j(q1, q2, q3, "* * *", q4)
        check("分隔符一删一加不绑", blocks(sepB, sepA).all { it.partner == null })
        case("分隔符：只拒加的那个", sepB, sepA, sepA, setOf(1), j(q1, q2, q3, q4))
        val rp = "他沉默了很久，什么也没说。"
        check("改前改后各出现两次的长句不绑", blocks(j(q1, rp, q2, q3, rp, q4), j(q1, q2, rp, q3, rp, q4)).all { it.partner == null })
    }

    @Test
    fun `whole chapter only when the diff is too big or everything became one block`() {
        val before = (1..30).joinToString("\n") { "第${it}行原来的字。" }
        val after = (1..25).joinToString("\n") { "完全不同的第${it}行，和原来没有一个字一样啊。" }
        val review = TurnReview.review(before, after, after)
        assertTrue(review.whole)
        assertEquals(TurnReview.Revert(null, setOf(0)), TurnReview.revert(before, after, after, setOf(0)))
        assertEquals(0, review.changesSince)
    }

    @Test
    fun `changes since the turn are counted for the whole chapter warning`() {
        val before = j(q1, q2, q3)
        val after = j(q1, "他推开木门。", q3)
        assertEquals(0, TurnReview.review(before, after, after).changesSince)
        assertEquals(2, TurnReview.review(before, after, j("雨停了。", "他推开木门。", q3, tail)).changesSince)
    }

    @Test
    fun `several picks where one cannot be written still write the others`() {
        // 块 0（第 1 段）你改过，块 1、2 照常：一起选时块 0 进失败，其余写进去
        val before = j(q1, q2, q3, q4)
        val after = j("清晨，雨刚停。", "他推开木门，院里积着水。", "远处有狗叫。", q4)
        val current = j("清晨，雨刚停了一会儿。", "他推开木门，院里积着水。", "远处有狗叫。", q4)
        val result = TurnReview.revert(before, after, current, setOf(0, 1, 2))
        assertEquals(setOf(0), result.failed)
        assertEquals(j("清晨，雨刚停了一会儿。", q2, q3, q4), result.text)
    }

    @Test
    fun `when picks written together fail the check, the ones that pass alone are still written`() {
        // 随机对抗里找到的：三块一起写自检不过（AI 调了段落顺序，一块的改前恰是另一块的改后），逐块退让后照写能写的
        val lamp = "灯芯噼啪响了一声，屋里暗了一瞬。"
        val umbrella = "她把伞靠在门边，抖了抖袖口的水珠。"
        val fog = "河面上起了雾，对岸的灯一盏一盏亮起来。"
        val cart = "第二天一早，镇口来了一辆马车。"
        val dog = "远处传来几声狗叫，又很快安静下来。"
        val before = listOf(umbrella, lamp, fog, lamp, cart, dog, dog).joinToString("\n\n")
        val after = listOf(fog, lamp, cart, lamp, umbrella, dog).joinToString("\n\n")
        val current = listOf(umbrella, lamp, cart, lamp, umbrella, dog).joinToString("\n\n")
        val picks = setOf(1, 2, 3)
        val result = TurnReview.revert(before, after, current, picks)
        val written = picks - result.failed
        assertTrue("应当写进去一部分：$result", result.text != null && written.isNotEmpty())
        val again = TurnReview.review(before, after, result.text!!).pieces
        assertTrue(written.all { again[it].state == REVERTED || again[it].state == GONE })
    }

    @Test
    fun `pieces carry rows in the current text for lock checks`() {
        val before = listOf(q1, q2, q3).joinToString("\n\n")
        val after = listOf(q1, "他推开木门。", q3).joinToString("\n\n")
        val current = listOf(head, q1, "他推开木门。", q3).joinToString("\n\n")
        assertEquals(4..4, TurnReview.review(before, after, current).pieces.single().rows)
        val deleted = TurnReview.review(j(q1, q2, q3), j(q1, q3), j(q1, q3)).pieces.single()
        assertEquals(1, deleted.rows!!.first)
        assertTrue(deleted.rows!!.isEmpty())
    }
}

private fun keys(text: String) = BookLocks.normalize(text).split('\n').map(BookLocks::key).filter { it.isNotEmpty() }

private fun show(text: String) = text.replace("\n", "⏎")
