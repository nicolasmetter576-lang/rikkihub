package me.rerere.rikkihub.data.book.history

import me.rerere.rikkihub.data.book.history.BookLocks.Doc
import me.rerere.rikkihub.data.book.history.BookLocks.Follow
import me.rerere.rikkihub.data.book.history.BookLocks.IssueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 定稿锁的判定：定位、拒绝判定、手写跟随、事后放回（用例来自 S2 方案两轮评审的原型）。 */
class BookLocksTest {
    private val l = "　　画里的女人眉眼低垂，像是在看他，又像是在看很远的地方。"

    /** 空行分段的一章。 */
    private val spaced = listOf(
        "　　林默推开门，屋里一片漆黑。", "", "　　“谁？”", "",
        "　　他没有回答，只是把手里的灯举高了一些，光落在墙上那幅旧画上。", "", "　　“是我。”", "",
        l, "", "　　“嗯。”", "", "　　他把灯放下，转身离开。",
    ).joinToString("\n")

    private val lt = "画里的女人眉眼低垂，像是在看他，又像是在看很远的地方。"

    /** 一行一段、段间不空行的一章。 */
    private val tight = listOf(
        "林默推开门，屋里一片漆黑。", "“谁？”", "他没有回答，只是把手里的灯举高了一些，光落在墙上那幅旧画上。", "“是我。”",
        lt, "“嗯。”", "他把灯放下，转身离开。",
    ).joinToString("\n")

    /** 在 [text] 里把含 [lines] 的那几行锁上。 */
    private fun lock(text: String, vararg lines: String): PassageLock {
        val doc = Doc(text)
        val rows = lines.map { line -> doc.keys.indexOf(BookLocks.key(line)).also { require(it >= 0) { line } } }
        return BookLocks.passageAt(doc, rows.min()..rows.max(), "p", 0, "sha", web = false)
    }

    private fun issues(old: String, new: String, passage: PassageLock) = BookLocks.issues(Doc(old), Doc(new), listOf(passage))

    private fun putBack(before: String, now: String, passage: PassageLock) = BookLocks.putBack(Doc(before), Doc(now), passage)

    private fun followed(old: String, new: String, passage: PassageLock): PassageLock =
        (BookLocks.follow(Doc(old), Doc(new), passage) as Follow.Moved).passage

    // ---- 定位 ----

    @Test
    fun `a lock is found by whole lines, ignoring indentation, BOM and line endings`() {
        val passage = lock(spaced, l)
        assertNotNull(BookLocks.locate(passage, Doc("﻿" + spaced.replace("\n", "\r\n"))))
        assertNotNull(BookLocks.locate(passage, Doc(spaced.replace(l, lt))))
        assertNull(BookLocks.locate(passage, Doc(spaced.replace("眉眼", "眉目"))))
    }

    @Test
    fun `repeated text is told apart by its neighbours, then by position`() {
        val text = "甲说了一句很长很长的话。\n他点了点头，没有说话。\n乙也说了一句很长的话。\n他点了点头，没有说话。\n丙最后开口了。"
        val second = BookLocks.passageAt(Doc(text), 3..3, "p", 0, "sha", false)
        assertEquals(3..3, BookLocks.locate(second, Doc(text)))
        // 锚点被改了：退回离原位置最近的那处
        assertEquals(3..3, BookLocks.locate(second, Doc(text.replace("乙也说了", "乙改口说了").replace("丙最后", "丙终于"))))
    }

    // ---- 非手写的覆盖写：拒不拒 ----

    @Test
    fun `moving a locked paragraph or reindenting it is not touching it`() {
        val passage = lock(spaced, l)
        assertTrue(issues(spaced, spaced.replace("　　", ""), passage).isEmpty())
        val moved = spaced.replace(l + "\n\n", "") + "\n\n" + l
        assertTrue(issues(spaced, moved, passage).isEmpty())
        // 锁外改了：不碰
        assertTrue(issues(spaced, spaced.replace("屋里一片漆黑", "屋里黑得什么都看不见"), passage).isEmpty())
    }

    @Test
    fun `changing, splitting or merging a locked paragraph is refused with what changed`() {
        val passage = lock(tight, lt)
        assertEquals(IssueKind.CHANGED, issues(tight, tight.replace("眉眼", "眉目"), passage).single().kind)
        assertEquals(IssueKind.SPLIT, issues(tight, tight.replace("像是在看他，", "像是在看他，\n"), passage).single().kind)
        assertEquals(IssueKind.MERGED, issues(tight, tight.replace("的地方。\n“嗯。”", "的地方。“嗯。”"), passage).single().kind)
        assertEquals(IssueKind.CHANGED, issues(tight, tight.replace(lt + "\n", ""), passage).single().kind)
    }

    @Test
    fun `deleting the locked one of two identical paragraphs is refused as a duplicate`() {
        val text = "甲说了一句很长很长的话。\n他点了点头，没有说话。\n乙也说了一句很长的话。\n他点了点头，没有说话。\n丙最后开口了。"
        val second = BookLocks.passageAt(Doc(text), 3..3, "p", 0, "sha", false)
        val without = text.lines().filterIndexed { i, _ -> i != 3 }.joinToString("\n")
        assertEquals(IssueKind.DUPLICATE, issues(text, without, second).single().kind)
    }

    @Test
    fun `a lock already missing from the old text does not refuse, it is reported as lost`() {
        val passage = lock(tight, lt)
        val broken = tight.replace("眉眼", "眉目")
        assertTrue(issues(broken, broken.replace("林默", "陈默"), passage).isEmpty())
        assertEquals(listOf(passage), BookLocks.lost(Doc(broken), listOf(passage)))
        assertTrue(issues(broken, broken, passage.copy(dormantSince = 1)).isEmpty())
    }

    // ---- 手写跟随 ----

    @Test
    fun `cut and paste elsewhere keeps the lock on the moved paragraph`() {
        val passage = lock(spaced, l)
        val moved = spaced.replace(l + "\n\n", "") + "\n\n" + l
        val after = followed(spaced, moved, passage)
        assertEquals(listOf(l), after.lines)
        assertEquals(Doc(moved).lines.lastIndex, after.row)
        assertEquals("　　他把灯放下，转身离开。", after.before)
    }

    @Test
    fun `cutting a locked paragraph puts its lock to sleep`() {
        val passage = lock(spaced, l)
        assertEquals(Follow.Dormant, BookLocks.follow(Doc(spaced), Doc(spaced.replace(l + "\n\n", "")), passage))
    }

    @Test
    fun `editing a word inside the lock follows the new words without growing`() {
        val passage = lock(tight, lt)
        val after = followed(tight, tight.replace("眉眼", "眉目"), passage)
        assertEquals(listOf(lt.replace("眉眼", "眉目")), after.lines)
        assertEquals(listOf(lt), after.origin)
    }

    @Test
    fun `editing the lock together with its neighbours does not pull them into the lock`() {
        val passage = lock(tight, lt)
        val edited = tight
            .replace("“是我。”", "“是我，别怕。”")
            .replace(lt, lt.replace("眉眼", "眉目") + "\n他忽然想起了什么，回头看了一眼窗外。")
        val after = followed(tight, edited, passage)
        assertEquals(listOf(lt.replace("眉眼", "眉目")), after.lines)
    }

    @Test
    fun `deleting the lock while editing the next paragraph does not move the lock onto it`() {
        val passage = lock(spaced, l)
        val edited = spaced.replace(l + "\n\n", "").replace("“嗯。”", "“嗯，进来吧。”")
        assertEquals(Follow.Dormant, BookLocks.follow(Doc(spaced), Doc(edited), passage))
    }

    @Test
    fun `rewriting the lock beyond recognition follows the replacement, at most twice as long`() {
        val passage = lock(spaced, l)
        val rewritten = spaced.replace(l, "　　墙上挂着一幅没人记得来历的画。\n\n　　画中人的目光始终落在门口。\n\n　　第三句完全不同的新内容出现在这里。")
        val after = followed(spaced, rewritten, passage)
        assertEquals(listOf("　　墙上挂着一幅没人记得来历的画。", "　　画中人的目光始终落在门口。"), after.lines)
    }

    // ---- 事后放回 ----

    @Test
    fun `a whole-chapter polish puts back only the locked line and keeps every other edit`() {
        val passage = lock(tight, lt)
        val polished = listOf(
            "林默推开房门，屋内漆黑一片。", "“谁在那儿？”", "他没作声，只把灯举高些，光照在墙上那幅旧画上。", "“是我呀。”",
            "画中女子眉眼低垂，似在看他，又似望向远方。", "“嗯。”", "他放下灯，转身走了。",
        ).joinToString("\n")
        val result = putBack(tight, polished, passage)!!
        assertEquals(polished.replace("画中女子眉眼低垂，似在看他，又似望向远方。", lt), result)
    }

    @Test
    fun `one changed word or an added sentence inside the locked line is put back`() {
        val passage = lock(tight, lt)
        assertEquals(tight, putBack(tight, tight.replace("眉眼", "眉目"), passage))
        assertEquals(tight, putBack(tight, tight.replace("很远的地方。", "很远的地方。他忽然明白她是谁了。"), passage))
    }

    @Test
    fun `a deleted lock comes back without undoing the edit to the next paragraph`() {
        val passage = lock(spaced, l)
        val now = spaced.replace(l + "\n\n", "").replace("“嗯。”", "“嗯，进来吧。”")
        val result = putBack(spaced, now, passage)!!
        assertEquals(spaced.replace("“嗯。”", "“嗯，进来吧。”"), result)
    }

    @Test
    fun `a lock merged with the next paragraph comes back without losing the merged words`() {
        val passage = lock(tight, lt)
        val merged = tight.replace("的地方。\n“嗯。”", "的地方。她轻声说：“嗯。”")
        val result = putBack(tight, merged, passage)!!
        assertTrue(result, result.lines().contains(lt))
        assertTrue(result, result.contains("她轻声说：“嗯。”"))
    }

    @Test
    fun `a paragraph inserted inside a multi-paragraph lock moves after it, blank lines stay as they were`() {
        val passage = lock(spaced, "　　“是我。”", l)
        val now = spaced.replace("　　“是我。”\n\n", "　　“是我。”\n\n　　插进来的一段新文字，不该留在锁里。\n\n")
        val result = putBack(spaced, now, passage)!!
        assertEquals(spaced.replace(l + "\n\n", l + "\n\n　　插进来的一段新文字，不该留在锁里。\n\n"), result)
    }

    @Test
    fun `a lock that cannot be found in the earlier text is not put back`() {
        val passage = lock(tight, lt)
        val broken = tight.replace("眉眼", "眉目")
        assertNull(putBack(broken, broken.replace("林默", "陈默"), passage))
    }

    @Test
    fun `huge rewrites give up quickly instead of holding the lock`() {
        val big = (1..60_000).joinToString("\n") { "第${it}行原文内容。" }
        val rewritten = (1..60_000).joinToString("\n") { "改写后的第${it}行。" }
        val passage = BookLocks.passageAt(Doc(big), 30_000..30_000, "p", 0, "sha", false)
        val started = System.nanoTime()
        assertEquals(Follow.Dormant, BookLocks.follow(Doc(big), Doc(rewritten), passage))
        val small = "第30000行原文内容。\n" + (1..60_000).joinToString("\n") { "新$it" }
        assertNull(BookLocks.putBack(Doc("只有\n一行\n第30000行原文内容。"), Doc(small), passage))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `whole paragraphs and the character count ignore blank lines and spaces`() {
        val doc = Doc("a\n\n　　十个字以上的一段正文内容\n\nb")
        assertEquals(2..2, BookLocks.wholeParagraphs(doc, 1..3))
        assertNull(BookLocks.wholeParagraphs(doc, 1..1))
        assertEquals(12, BookLocks.charCount(listOf("　　十个字以上的一段正文内容")))
        assertFalse(BookLocks.charCount(listOf("嗯。")) >= BookLocks.MIN_CHARS)
    }

    // ---- 保留这次修改，但之后又改过：只把锁定段换成被改成的样子 ----

    /** AI 把 [spaced] 的锁定段改成了 [changed]，系统放回了原文；之后你在别处又改了几处（[current]）。 */
    private fun keep(changed: String, current: String, passage: PassageLock) = BookLocks.keepIn(Doc(spaced), Doc(changed), Doc(current), passage)

    private val later = spaced.replace("林默推开门", "林默轻轻推开门").replace("转身离开。", "转身离开，没有回头。")

    @Test
    fun `keeping a small edit swaps only the locked passage and keeps later edits elsewhere`() {
        val passage = lock(spaced, l)
        val changed = spaced.replace("眉眼低垂", "眉目低垂")
        assertEquals(later.replace("眉眼低垂", "眉目低垂"), keep(changed, later, passage))
    }

    @Test
    fun `keeping a rewritten passage brings the whole new paragraph`() {
        val passage = lock(spaced, l)
        val rewritten = "　　画中人垂着眼，目光落在他身上，又仿佛越过了他。"
        val changed = spaced.replace(l, rewritten)
        assertEquals(later.replace(l, rewritten), keep(changed, later, passage))
    }

    @Test
    fun `keeping a split passage brings both paragraphs with the blank line between them`() {
        val passage = lock(spaced, l)
        val split = "　　画里的女人眉眼低垂，像是在看他。\n\n　　又像是在看很远的地方。"
        val changed = spaced.replace(l, split)
        assertEquals(later.replace(l, split), keep(changed, later, passage))
    }

    @Test
    fun `keeping refuses when the locked text appears nowhere or the change can't be followed`() {
        val passage = lock(spaced, l)
        // 现在的稿子里找不到锁定原文（没放回）
        assertNull(keep(spaced.replace("眉眼低垂", "眉目低垂"), later.replace(l, "　　别的。"), passage))
        // 被改成的样子里锁定段整个删了：跟不过去
        assertNull(keep(spaced.replace("$l\n\n", ""), later, passage))
    }

    @Test
    fun `keeping touches only the locked copy when the same text also appears elsewhere`() {
        val passage = lock(spaced, l)
        val changed = spaced.replace("眉眼低垂", "眉目低垂")
        // 你之后在开头又贴了一份同样的文字：只换锁住的那一份
        val current = "$l\n\n$later"
        assertEquals("$l\n\n" + later.replace("眉眼低垂", "眉目低垂"), keep(changed, current, passage))
    }
}
