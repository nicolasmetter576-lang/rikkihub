package me.rerere.rikkihub.data.book

import me.rerere.rikkihub.data.book.BeatPlacement.Presence
import me.rerere.rikkihub.data.book.BeatPlacement.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatPlacementTest {
    private val md = Style("\n\n", "")

    private fun beat(id: String, text: String, inserted: String = "") =
        Beat(id, "节拍$id", drafts = listOf(BeatDraft("d$id", text)), chosen = "d$id", inserted = inserted)

    private fun notes(vararg beats: Beat) = ChapterNotes(beats.toList())

    private fun apply(text: String, insertions: List<BeatInsertion>): String =
        insertions.fold(text) { current, it -> current.substring(0, it.offset) + it.text + current.substring(it.offset) }

    private fun placed(text: String, notes: ChapterNotes, cursor: Int? = null, style: Style = md) =
        apply(text, BeatPlacement.place(text, notes, BeatPlacement.pending(notes), style, cursor))

    @Test
    fun `paragraph habits come from the text`() {
        assertEquals(Style("\n\n", ""), BeatPlacement.styleOf("a.md", listOf("# t\n\n甲\n\n乙\n\n丙\n")))
        assertEquals(Style("\n", "　　"), BeatPlacement.styleOf("a.md", listOf("　　甲\n　　乙\n　　丙\n")))
        assertEquals(Style("\n\n", "　　"), BeatPlacement.styleOf("a.txt", listOf("", "　　甲\r\n\r\n　　乙\r\n\r\n　　丙")))
        assertEquals(Style("\n\n", ""), BeatPlacement.styleOf("a.md", listOf("甲")))
        assertEquals(Style("\n", ""), BeatPlacement.styleOf("a.txt", emptyList()))
    }

    @Test
    fun `drafts are reformatted to the habit`() {
        assertEquals("　　甲\n　　乙", BeatPlacement.format("甲\n\n  乙  \n\n", Style("\n", "　　")))
        assertEquals("甲\n\n乙", BeatPlacement.format("　　甲\n　　乙", md))
    }

    @Test
    fun `beats are added to the end in order with the right gaps`() {
        val notes = notes(beat("1", "甲"), beat("2", "乙"))
        assertEquals("# 章\n\n甲\n\n乙\n", placed("# 章\n", notes))
        assertEquals("甲\n\n乙\n", placed("", notes))
        assertEquals("开头。\n\n甲\n\n乙\n\n\n", placed("开头。\n\n\n", notes))
    }

    @Test
    fun `a beat goes after the one before it, or before the one after it`() {
        val text = "序。\n\n甲段。\n\n尾声。\n"
        assertEquals("序。\n\n甲段。\n\n乙段。\n\n尾声。\n", placed(text, notes(beat("1", "甲段。", "甲段。"), beat("2", "乙段。"))))
        assertEquals("序。\n\n零段。\n\n甲段。\n\n尾声。\n", placed(text, notes(beat("0", "零段。"), beat("1", "甲段。", "甲段。"))))
        // 前一个节拍的原文被改过就找更前面的
        val edited = notes(beat("1", "甲段。", "甲段。"), beat("2", "x", "被改掉的"), beat("3", "丙段。"))
        assertEquals("序。\n\n甲段。\n\n丙段。\n\n尾声。\n", placed(text, edited))
    }

    @Test
    fun `at the cursor beats stay together`() {
        val text = "上文。\n\n下文。"
        assertEquals("上文。\n\n甲\n\n乙\n\n下文。", placed(text, notes(beat("1", "甲"), beat("2", "乙")), cursor = 3))
        assertEquals("上\n\n甲\n\n文。\n\n下文。", placed(text, notes(beat("1", "甲")), cursor = 1))
        assertEquals("甲\n\n上文。\n\n下文。", placed(text, notes(beat("1", "甲")), cursor = 0))
    }

    @Test
    fun `only untouched unique passages can be replaced`() {
        val changed = beat("1", "新段。", inserted = "旧段。")
        val (range, text) = BeatPlacement.replacement("前。\n\n旧段。\n\n后。", changed, md)!!
        assertEquals("旧段。", "前。\n\n旧段。\n\n后。".substring(range))
        assertEquals("新段。", text)
        assertNull(BeatPlacement.replacement("前。\n\n旧段改。\n", changed, md))
        assertNull(BeatPlacement.replacement("旧段。旧段。", changed, md))
        assertNull(BeatPlacement.replacement("旧段。", beat("1", "旧段。", "旧段。"), md))
        assertEquals(listOf(Presence.NONE, Presence.ONCE, Presence.SEVERAL), listOf("x", "旧段。", "旧段。旧段。").map { BeatPlacement.presence(it, "旧段。") })
        assertEquals(Presence.SEVERAL, BeatPlacement.presence("啊啊啊", "啊啊"))
    }

    @Test
    fun `undoing a replacement leaves the other draft replaceable again`() {
        val beat = Beat("1", "甲", drafts = listOf(BeatDraft("a", "一稿。"), BeatDraft("b", "二稿。")), chosen = "b", inserted = "二稿。")
        assertEquals("一稿。", BeatPlacement.located("前。\n\n一稿。\n", beat, md))
        assertEquals("二稿。", BeatPlacement.replacement("前。\n\n一稿。\n", beat, md)!!.second)
        assertNull(BeatPlacement.located("前。\n", beat, md))
        assertNull(BeatPlacement.located("一稿。", beat.copy(inserted = ""), md))
    }

    @Test
    fun `a chapter that changed its paragraph habit still knows what was put there`() {
        val indented = Style("\n\n", "　　")
        val text = "# 章\n\n　　开头。\n\n一段。\n\n又一段。\n"
        // 写进去时不缩进，后来正文多了缩进的段落，现在的习惯是缩进
        val same = Beat("1", "甲", drafts = listOf(BeatDraft("a", "一段。\n又一段。")), chosen = "a", inserted = "一段。\n\n又一段。")
        assertEquals("一段。\n\n又一段。", BeatPlacement.located(text, same, indented))
        assertNull(BeatPlacement.replacement(text, same, indented))
        // 撤销过替换：记下的是删掉的那稿，正文里是现在这稿的旧排版
        val undone = same.copy(inserted = "删掉的稿。")
        assertEquals("一段。\n\n又一段。", BeatPlacement.located(text, undone, indented))
        assertNull(BeatPlacement.replacement(text, undone, indented))
        // 换了稿：照现在的习惯排
        val other = same.copy(drafts = same.drafts + BeatDraft("b", "新稿。"), chosen = "b")
        assertEquals("　　新稿。", BeatPlacement.replacement(text, other, indented)!!.second)
    }

    @Test
    fun `what the author wrote is what beats did not put there`() {
        val notes = notes(beat("1", "甲段。", "甲段。"), beat("2", "乙", "改没了"))
        assertEquals("# 章\n\n开头。\n\n结尾。", BeatPlacement.ownText("# 章\n\n开头。\n\n甲段。\n\n结尾。\n", notes))
        assertEquals("", BeatPlacement.ownText("甲段。\n", notes))
    }

    // ---- 稿件 --------------------------------------------------------------------------------

    @Test
    fun `five drafts at most, the newest is adopted`() {
        var notes = ChapterNotes(listOf(Beat("b", "甲")))
        (1..7).forEach { notes = notes.addDraft("b", BeatDraft("d$it", "稿$it")) }
        assertEquals((3..7).map { "d$it" }, notes.beats.single().drafts.map { it.id })
        assertEquals("d7", notes.beats.single().chosen)
        notes = notes.removeDraft("b", "d7")
        assertEquals("d6", notes.beats.single().chosen)
        notes = notes.removeDraft("b", "d3")
        assertEquals("d6", notes.beats.single().chosen)
        listOf("d4", "d5", "d6").forEach { notes = notes.removeDraft("b", it) }
        assertNull(notes.beats.single().chosen)
    }

    @Test
    fun `changing an earlier draft outdates the later ones only`() {
        var notes = ChapterNotes(listOf(Beat("1", "甲"), Beat("2", "乙"), Beat("3", "丙")))
        for (id in listOf("1", "2", "3")) notes = notes.addDraft(id, BeatDraft("a$id", "稿$id", basis = notes.basisOf(id)))
        assertTrue(notes.beats.none(notes::outdated))
        notes = notes.addDraft("2", BeatDraft("b2", "换了", basis = notes.basisOf("2")))
        assertEquals(listOf(false, false, true), notes.beats.map(notes::outdated))
        // 换回原来那稿就不再提示
        notes = notes.updateBeat("2") { it.copy(chosen = "a2") }
        assertFalse(notes.outdated(notes.beats[2]))
    }
}
