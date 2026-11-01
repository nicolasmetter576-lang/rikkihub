package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/** Drives [WorkspacePairs.onInput] the way the editor's InputTransformation does: one user change, then the fix. */
class WorkspacePairsTest {
    private data class Editor(val text: String, val caret: Int, val boxes: List<WorkspacePairBox> = emptyList(), val anchor: Int = caret)

    /** The user replaces [from, to) with [typed]; the caret ends at [caretAfter] (default: after the typed text). */
    private fun Editor.input(typed: String, from: Int = minOf(anchor, caret), to: Int = maxOf(anchor, caret), caretAfter: Int = from + typed.length): Editor {
        val newText = text.replaceRange(from, to, typed)
        val change = WorkspacePairs.Change(from, from + typed.length, from, to)
        val outcome = WorkspacePairs.onInput(text, minOf(anchor, caret)..maxOf(anchor, caret), newText, caretAfter..caretAfter, change, boxes)
        val fix = outcome.fix ?: return Editor(newText, caretAfter, WorkspacePairs.validate(newText, caretAfter..caretAfter, outcome.boxes))
        val fixed = newText.replaceRange(fix.start, fix.end, fix.replacement)
        return Editor(fixed, fix.caret, WorkspacePairs.validate(fixed, fix.caret..fix.caret, outcome.boxes))
    }

    private fun Editor.type(chars: String): Editor = chars.fold(this) { editor, c -> editor.input(c.toString()) }
    private fun Editor.backspace() = input("", caret - 1, caret)
    private fun Editor.moveTo(at: Int) = copy(caret = at, anchor = at, boxes = WorkspacePairs.validate(text, at..at, boxes))

    @Test fun `opener closes, text fills the box, Enter jumps out`() {
        var e = Editor("他说：", 3).type("（")
        assertEquals(Editor("他说：（）", 4, listOf(WorkspacePairBox(3, 4))), e)
        e = e.type("好的")
        assertEquals(Editor("他说：（好的）", 6, listOf(WorkspacePairBox(3, 6))), e)
        e = e.type("\n")
        assertEquals(Editor("他说：（好的）", 7), e)
        // Outside a box Enter is a line break again.
        assertEquals("他说：（好的）\n", e.type("\n").text)
    }

    @Test fun `typing the closer steps over it and Backspace in an empty box removes both`() {
        assertEquals(Editor("「」", 2), Editor("", 0).type("「」"))
        assertEquals(Editor("", 0), Editor("", 0).type("《").backspace())
        // Backspace after the box filled up removes one character as usual.
        val filled = Editor("", 0).type("《书").backspace()
        assertEquals(Editor("《》", 1, listOf(WorkspacePairBox(0, 1))), filled)
    }

    @Test fun `nested boxes leave from the inside out`() {
        var e = Editor("", 0).type("「『")
        assertEquals(Editor("「『』」", 2, listOf(WorkspacePairBox(0, 3), WorkspacePairBox(1, 2))), e)
        e = e.type("书名\n")
        assertEquals(Editor("「『书名』」", 5, listOf(WorkspacePairBox(0, 5))), e)
        e = e.type("\n")
        assertEquals(Editor("「『书名』」", 6), e)
    }

    @Test fun `a selection gets wrapped`() {
        val e = Editor("说了abc。", caret = 5, anchor = 2).input("“")
        assertEquals(Editor("说了“abc”。", 6, listOf(WorkspacePairBox(2, 6))), e)
    }

    @Test fun `no closer before a word, and no straight quote after a letter`() {
        assertEquals(Editor("(abc", 1), Editor("abc", 0).type("("))
        assertEquals(Editor("「中文", 1), Editor("中文", 0).type("「"))
        assertEquals(Editor("don'", 4), Editor("don", 3).type("'"))
        assertEquals("say \"\"", Editor("say ", 4).type("\"").text)
        // Before punctuation, a closer or the line end it pairs.
        assertEquals("（），", Editor("，", 0).type("（").text)
        assertEquals("「（）」", Editor("「」", 1).type("（").text)
    }

    @Test fun `straight quotes step over their own closer`() {
        val e = Editor("", 0).type("\"hi\"")
        assertEquals(Editor("\"hi\"", 4), e)
    }

    @Test fun `an IME committing the pair itself gets the box only`() {
        val e = Editor("说", 1).input("“”", caretAfter = 2)
        assertEquals(Editor("说“”", 2, listOf(WorkspacePairBox(1, 2))), e)
        assertEquals(Editor("说“好”", 3, listOf(WorkspacePairBox(1, 3))), e.type("好"))
    }

    @Test fun `committing composed text together with an opener still pairs`() {
        val start = Editor("ab", 2)
        val e = start.input("ab(", from = 0, to = 2)
        assertEquals(Editor("ab()", 3, listOf(WorkspacePairBox(2, 3))), e)
    }

    @Test fun `moving the caret out drops the box`() {
        val e = Editor("前", 1).type("（字")
        assertEquals(listOf(WorkspacePairBox(1, 3)), e.boxes)
        assertEquals(emptyList<WorkspacePairBox>(), e.moveTo(0).boxes)
        assertEquals(emptyList<WorkspacePairBox>(), e.moveTo(4).boxes)
        assertEquals(listOf(WorkspacePairBox(1, 3)), e.moveTo(2).boxes)
    }

    @Test fun `boxes follow edits before them and vanish when a symbol is hit`() {
        val box = WorkspacePairBox(3, 6)
        assertEquals(WorkspacePairBox(5, 8), WorkspacePairs.map(box, WorkspacePairs.Change(0, 2, 0, 0)))
        assertEquals(WorkspacePairBox(3, 4), WorkspacePairs.map(box, WorkspacePairs.Change(4, 4, 4, 6)))
        assertEquals(box, WorkspacePairs.map(box, WorkspacePairs.Change(7, 9, 7, 7)))
        assertEquals(null, WorkspacePairs.map(box, WorkspacePairs.Change(2, 2, 2, 4)))
        assertEquals(null, WorkspacePairs.map(box, WorkspacePairs.Change(5, 5, 5, 7)))
    }
}
