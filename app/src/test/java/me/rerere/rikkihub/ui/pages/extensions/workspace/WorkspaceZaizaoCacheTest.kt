package me.rerere.rikkihub.ui.pages.extensions.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.MessageRole
import me.rerere.rikkihub.data.ai.zaizao.Zaizao
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoException
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoPrepared
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoProgress
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoType
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 再造 sessions: lookup by trimmed text, rounds on a base, 换一版, stop/retry, failures, limits, eviction. */
class WorkspaceZaizaoCacheTest {
    private val source = "第一句话。第二句话。\n\n另一段。"
    private val calls = mutableListOf<ZaizaoPrepared>()
    private val remembered = mutableListOf<String>()

    /** Each request answers right away with 稿N. */
    private fun instant(@Suppress("UNUSED_PARAMETER") settings: Settings, prepared: ZaizaoPrepared): Flow<ZaizaoProgress> = flow {
        calls += prepared
        val text = "稿${calls.size}"
        emit(ZaizaoProgress("", "", text.take(1), "<result>${text.take(1)}"))
        emit(ZaizaoProgress("", "", text, "<result>$text</result>", done = true))
    }

    private fun cache(generate: (Settings, ZaizaoPrepared) -> Flow<ZaizaoProgress> = ::instant) =
        WorkspaceZaizaoCache(CoroutineScope(Dispatchers.Unconfined), { Settings() }, generate, { remembered += it })

    private fun WorkspaceZaizaoCache.open(text: String = source, start: Int = 0, end: Int = 5, file: String = "f"): WorkspaceZaizaoEntry {
        val range = Zaizao.range(text, start, end)
        return lookup(file, text, range) ?: create(file, "a.md", text, range, includeAfter = true, type = ZaizaoType.MODIFY)
    }

    @Test
    fun `same text selected again finds the kept session`() {
        val cache = cache()
        val entry = cache.open()
        assertEquals("第一句话。", entry.original)
        val again = cache.open(" $source", 0, 6)
        assertSame(entry, again)
        assertEquals(1 to 6, again.liveStart to again.liveEnd)
        assertNotSame(entry, cache.open(start = 5, end = 10))
        assertNotSame(entry, cache.open(file = "g"))
    }

    @Test
    fun `before the first round the session follows the editor, after it the first message stays fixed`() {
        val cache = cache()
        val entry = cache.open()
        val edited = "前言。$source"
        cache.open(edited, 3, 8)
        assertEquals(edited, entry.source)
        assertEquals(3, entry.start)
        assertEquals(null, cache.send(entry, "改短"))
        cache.open("再加。$edited", 6, 11)
        assertEquals(edited, entry.source) // the conversation keeps the document it started with
        assertEquals(6, entry.liveStart)
    }

    @Test
    fun `send needs a requirement and records it`() {
        val cache = cache()
        val entry = cache.open()
        entry.draftText = "  "
        assertEquals(ZaizaoException.Reason.EMPTY_REQUIREMENT, cache.send(entry, entry.draftText))
        assertTrue(entry.rounds.isEmpty())
        entry.draftText = " 铲子上有三斤大便 "
        entry.type = ZaizaoType.INCREMENT
        assertNull(cache.send(entry, entry.draftText))
        val round = entry.rounds.single()
        assertEquals(ZaizaoType.INCREMENT, round.type)
        assertEquals("铲子上有三斤大便", round.requirement)
        assertEquals("", entry.draftText)
        assertEquals(listOf("铲子上有三斤大便"), remembered)
        val draft = round.drafts.single()
        assertEquals(WorkspaceZaizaoStatus.Done, draft.status)
        assertEquals("稿1", draft.result)
        assertSame(draft, entry.base)
        assertEquals(1, calls.single().messages.size)
    }

    @Test
    fun `later rounds replay the chain to the base and 换一版 lists earlier drafts`() {
        val cache = cache()
        val entry = cache.open()
        cache.send(entry, "甲")
        cache.send(entry, "乙")
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), calls[1].messages.map { it.role })
        assertEquals("<result>稿1</result>", calls[1].messages[1].toText())

        val first = entry.rounds[0]
        cache.another(entry, first)
        val reroll = first.drafts[1]
        assertEquals("稿3", reroll.result)
        assertEquals(1, calls[2].messages.size) // same base as round ① (the original)
        assertTrue("<draft>\n稿1\n</draft>" in calls[2].messages.single().toText())
        assertEquals(2, remembered.size) // 换一版 is not a new requirement
        assertSame(reroll, entry.base) // the newest draft

        entry.pinnedBase = entry.rounds[1].drafts[0]
        cache.send(entry, "丙")
        val third = entry.rounds[2]
        assertSame(entry.rounds[1].drafts[0], third.base)
        assertNull(entry.pinnedBase)
        assertEquals(5, calls[3].messages.size) // ① 稿1 → ② 稿2 → ③
        assertEquals("<result>稿2</result>", calls[3].messages[3].toText())
    }

    @Test
    fun `stop keeps what was written and retry writes the draft again`() {
        val gate = CompletableDeferred<Unit>()
        var slow = true
        val cache = cache { settings, prepared ->
            if (!slow) instant(settings, prepared) else flow {
                emit(ZaizaoProgress("想", "", "半句", "<result>半句"))
                gate.await()
            }
        }
        val entry = cache.open()
        cache.send(entry, "甲")
        val draft = entry.rounds.single().drafts.single()
        assertTrue(entry.busy)
        assertSame(draft, entry.base) // the draft being written is what the next round builds on
        assertEquals(null, cache.send(entry, "乙")) // ignored while writing
        assertEquals(1, entry.rounds.size)
        cache.stop(entry)
        assertEquals(WorkspaceZaizaoStatus.Stopped, draft.status)
        assertEquals("半句", draft.result)
        assertFalse(entry.busy)
        assertSame(draft, entry.base) // a stopped draft with text can be continued from

        slow = false
        cache.retry(entry, draft)
        assertEquals(WorkspaceZaizaoStatus.Done, draft.status)
        assertEquals(1, entry.rounds.single().drafts.size)
    }

    @Test
    fun `a failed draft reports why and is not a base`() {
        val cache = cache { _, _ -> flow { throw ZaizaoException(ZaizaoException.Reason.NO_MODEL) } }
        val entry = cache.open()
        cache.send(entry, "甲")
        val draft = entry.rounds.single().drafts.single()
        assertEquals(WorkspaceZaizaoStatus.Failed(ZaizaoException.Reason.NO_MODEL, null), draft.status)
        assertNull(entry.base)
        val crashing = cache { _, _ -> flow { error("boom") } }
        val other = crashing.open()
        crashing.send(other, "甲")
        assertEquals(WorkspaceZaizaoStatus.Failed(null, "boom"), other.rounds.single().drafts.single().status)
    }

    @Test
    fun `the chain is capped`() {
        val cache = cache()
        val entry = cache.open()
        repeat(Zaizao.MAX_ROUNDS) { assertNull(cache.send(entry, "第${it}轮")) }
        assertEquals(ZaizaoException.Reason.TOO_MANY_ROUNDS, cache.send(entry, "再来"))
        assertEquals(Zaizao.MAX_ROUNDS, entry.rounds.size)
        // A base earlier on the chain leaves room again.
        entry.pinnedBase = entry.rounds[0].drafts[0]
        assertNull(cache.send(entry, "从头"))
    }

    @Test
    fun `old sessions are evicted and cancelled`() {
        val gate = CompletableDeferred<Unit>()
        val cache = cache { _, _ -> flow { gate.await() } }
        val texts = (0..WorkspaceZaizaoCache.PER_FILE).map { "第${it}句。" }
        val all = texts.joinToString("")
        var offset = 0
        val entries = texts.map { text ->
            cache.open(all, offset, offset + text.length).also { offset += text.length }
        }
        cache.send(entries[1], "甲")
        assertEquals(WorkspaceZaizaoCache.PER_FILE, cache.size)
        assertNull(cache.find("f", entries[0].original))
        // The least recently used one goes next, and its running draft is cancelled.
        val draft = entries[1].rounds.single().drafts.single()
        cache.open(all + "新的一句。", all.length, all.length + 5)
        assertNull(cache.find("f", entries[1].original))
        assertTrue(draft.job!!.isCancelled)
    }
}
