package me.rerere.rikkihub.data.ai.book

import me.rerere.ai.core.MessageRole
import me.rerere.rikkihub.data.ai.tools.BookPromptLanguage
import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatPromptsTest {
    private fun beat(id: String, draft: String? = null) =
        Beat(id, "节拍$id", 800, drafts = listOfNotNull(draft?.let { BeatDraft("d$id", it) }), chosen = draft?.let { "d$id" })

    private fun context(vararg beats: Beat) =
        BeatContext("长夜", listOf("1. 起风：离山。"), "上一章最后一句。", "第二章", "雨夜来信。", 3000, "", beats.toList())

    private fun texts(context: BeatContext, id: String, note: String = "") =
        BeatPrompts.write(BookPromptLanguage.ZH, context, id, note).map { it.role to it.toText() }

    @Test
    fun `each request starts with everything the one before it sent`() {
        val first = texts(context(beat("1"), beat("2"), beat("3")), "1")
        val second = texts(context(beat("1", "甲文"), beat("2"), beat("3")), "2")
        val third = texts(context(beat("1", "甲文"), beat("2", "乙文"), beat("3")), "3")
        assertEquals(first, second.take(first.size))
        assertEquals(second, third.take(second.size))
        assertEquals(MessageRole.ASSISTANT to "甲文", second[2])
        // 不出现连续两条同一方的消息
        assertTrue(third.zipWithNext().none { (a, b) -> a.first == b.first })
    }

    @Test
    fun `the requirement comes last and later drafts are not shown`() {
        val messages = texts(context(beat("1", "甲文"), beat("2", "旧乙文"), beat("3", "丙文")), "2", note = "短一点")
        assertTrue(messages.last().second.endsWith("这次的要求：短一点"))
        val all = messages.joinToString { it.second }
        assertFalse(all.contains("丙文") || all.contains("旧乙文"))
        assertTrue(all.contains("3. 节拍3（约 800 字）"))
    }

    @Test
    fun `an earlier beat without text is skipped`() {
        val messages = texts(context(beat("1"), beat("2", "乙文"), beat("3")), "3")
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), messages.map { it.first })
        assertTrue(messages[1].second.contains("现在写第 2 个节拍") && messages[3].second.contains("现在写第 3 个节拍"))
    }

    @Test
    fun `empty sections are left out`() {
        val bare = BeatContext("", emptyList(), "", "第一章", "", 0, "", listOf(beat("1")))
        val text = BeatPrompts.write(BookPromptLanguage.EN, bare, "1", "").last().toText()
        assertFalse(text.contains("Earlier chapters") || text.contains("## Book") || text.contains("Already written"))
    }

    @Test
    fun `proposals keep what can be read`() {
        val output = "好的：\n{\"text\":\"等船\",\"words\":800}\n{\"text\":\"\"}\n{\"text\":\"来信\",\"words\":\"1200\"}\n{\"text\":\"北上\",\"words\":-5}\n{\"tex"
        assertEquals(
            listOf(BeatProposal("等船", 800), BeatProposal("来信", 1200), BeatProposal("北上", 0)),
            BeatWriter.parseProposal(output),
        )
    }

    @Test
    fun `the tail starts at a paragraph unless that would lose most of it`() {
        assertEquals("短", BeatWriter.tail("短\n", 10))
        assertEquals("乙乙乙乙乙乙乙", BeatWriter.tail("甲甲甲甲甲甲\n乙乙乙乙乙乙乙", 10))
        assertEquals("甲甲甲甲甲甲甲甲\n乙", BeatWriter.tail("甲".repeat(30) + "\n乙", 10))
    }
}
