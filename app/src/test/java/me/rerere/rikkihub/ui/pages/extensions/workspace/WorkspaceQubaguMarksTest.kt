package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.rikkihub.data.ai.qubagu.QubaguAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文件八股标注的应用 / 撤销 / 忽略：每次只替换一处，其后的标注跟着平移，正文始终和标注对得上。 */
class WorkspaceQubaguMarksTest {
    private val text = "门开了。他喉结滚动。她心头一震。窗外下雨。"

    private fun annotation(sentence: String) = text.indexOf(sentence).let { QubaguAnnotation(it, it + sentence.length, sentence, emptyList(), emptyList(), emptyList()) }

    private val marks = WorkspaceQubaguMarks.of(listOf(annotation("他喉结滚动。"), annotation("她心头一震。"), annotation("窗外下雨。")))

    /** 把替换套到正文上，像编辑器那样。 */
    private fun String.with(edit: WorkspaceQubaguEdit) = replaceRange(edit.start, edit.end, edit.text)

    @Test
    fun applyReplacesOneSentenceAndShiftsTheMarksAfterIt() {
        val (edit, next) = marks.apply(0, "他沉默了很久很久。")
        val changed = text.with(edit)
        assertEquals("门开了。他沉默了很久很久。她心头一震。窗外下雨。", changed)
        assertTrue(next.matches(changed))
        assertFalse(marks.matches(changed))
        assertEquals("他沉默了很久很久。", next.marks[0].applied)
        assertEquals(changed.indexOf("她心头一震。"), next.marks[1].start)
        assertEquals(changed.indexOf("窗外下雨。"), next.marks[2].start)
    }

    @Test
    fun undoPutsTheSentenceBackAndCanBeAppliedAgain() {
        val (first, applied) = marks.apply(1, "她愣住。")
        val once = text.with(first)
        val (back, undone) = applied.undo(1)
        assertEquals(text, once.with(back))
        assertEquals(marks, undone)
        // 已应用的换一种写法再应用：替换的是当前的写法
        val (again, reapplied) = applied.apply(1, "她怔在那里，半天没动。")
        val twice = once.with(again)
        assertEquals("门开了。他喉结滚动。她怔在那里，半天没动。窗外下雨。", twice)
        assertTrue(reapplied.matches(twice))
        assertEquals(text, twice.with(reapplied.undo(1).first))
    }

    @Test
    fun applyingAnEmptyTextDeletesTheSentence() {
        val (edit, next) = marks.apply(0, "")
        val changed = text.with(edit)
        assertEquals("门开了。她心头一震。窗外下雨。", changed)
        assertTrue(next.matches(changed))
        assertEquals(next.marks[0].start, next.marks[0].end)
        assertEquals(text, changed.with(next.undo(0).first))
    }

    @Test
    fun marksAppliedOutOfOrderStayInStep() {
        var current = text
        var state = marks
        for ((index, rewrite) in listOf(2 to "雨停了。", 0 to "他不作声。", 1 to "她一愣。")) {
            val (edit, next) = state.apply(index, rewrite)
            current = current.with(edit)
            state = next
            assertTrue(current, state.matches(current))
        }
        assertEquals("门开了。他不作声。她一愣。雨停了。", current)
        for (index in listOf(1, 2, 0)) {
            val (edit, next) = state.undo(index)
            current = current.with(edit)
            state = next
        }
        assertEquals(text, current)
    }

    @Test
    fun ignoreDropsTheMarkAndLeavesTheText() {
        val (edit, applied) = marks.apply(0, "他不作声。")
        val ignored = applied.ignore(0)
        assertEquals(listOf("她心头一震。", "窗外下雨。"), ignored.marks.map { it.shown })
        assertTrue(ignored.matches(text.with(edit)))
    }

    @Test
    fun aTapFindsTheMarkUnderIt() {
        val first = marks.marks[0]
        assertEquals(0, marks.indexAt(first.start))
        assertEquals(0, marks.indexAt(first.end - 1))
        // 句末紧挨着下一句：算下一句；句末后面不是标注时算这句
        assertEquals(1, marks.indexAt(first.end))
        assertEquals(2, marks.indexAt(text.length))
        assertNull(marks.indexAt(0))
    }

    @Test
    fun handEditedTextNoLongerMatches() {
        assertTrue(marks.matches(text))
        assertFalse(marks.matches(text.replace("喉结", "胸口")))
        assertFalse(marks.matches(text.take(10)))
    }
}
