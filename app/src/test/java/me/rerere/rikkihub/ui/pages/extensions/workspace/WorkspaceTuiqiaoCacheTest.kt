package me.rerere.rikkihub.ui.pages.extensions.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoCandidate
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoEvent
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPrepared
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPreparer
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoRequest
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 推敲 results kept across hiding the sheet: lookup by trimmed text, replacement, eviction, background finish. */
class WorkspaceTuiqiaoCacheTest {
    private val source = "第一句话。第二句话。\n\n另一段。"
    private var requests = 0

    /** Each request answers one candidate per slot right away. */
    private fun instant(prepared: TuiqiaoPrepared): Flow<TuiqiaoEvent> = flow {
        requests++
        prepared.slots.forEach { emit(TuiqiaoEvent.Candidate(it.key, TuiqiaoCandidate("改写$requests", "平实"))) }
    }

    private fun prepare(text: String, start: Int, end: Int): TuiqiaoPrepared =
        TuiqiaoPreparer.prepare(TuiqiaoConfig(), TuiqiaoRequest("a.md", text, start, end))

    private fun cache(events: (TuiqiaoPrepared) -> Flow<TuiqiaoEvent> = ::instant) =
        WorkspaceTuiqiaoCache(CoroutineScope(Dispatchers.Unconfined), events)

    @Test
    fun `same text selected again finds the kept entry without a new request`() {
        val cache = cache()
        val entry = cache.start("f", "a.md", prepare(source, 0, 5))
        assertEquals(1, requests)
        assertEquals(WorkspaceTuiqiaoStatus.Done, entry.status)
        // Selection with surrounding whitespace is trimmed the same way a request is.
        val again = cache.lookup("f", " $source", 0, 6)
        assertSame(entry, again)
        assertEquals(1, requests)
        assertEquals(1 to 6, again!!.session.start to again.session.end)
        assertEquals("第一句话。", again.session.original)
    }

    @Test
    fun `different text or file misses`() {
        val cache = cache()
        cache.start("f", "a.md", prepare(source, 0, 5))
        assertNull(cache.lookup("f", source, 5, 10))
        assertNull(cache.lookup("g", source, 0, 5))
        assertNull(cache.lookup("f", source, 0, 0))
    }

    @Test
    fun `starting again for the same text replaces the entry`() {
        val cache = cache()
        val first = cache.start("f", "a.md", prepare(source, 0, 5))
        val second = cache.start("f", "a.md", prepare(source, 0, 5))
        assertNotSame(first, second)
        assertSame(second, cache.lookup("f", source, 0, 5))
        assertEquals("改写2", second.items.single().candidate.text)
        assertEquals(1, cache.size)
    }

    @Test
    fun `removed entry is gone and its request cancelled`() {
        val gate = CompletableDeferred<Unit>()
        val cache = cache { prepared -> flow { gate.await(); emit(TuiqiaoEvent.Candidate(prepared.slots[0].key, TuiqiaoCandidate("x", ""))) } }
        val entry = cache.start("f", "a.md", prepare(source, 0, 5))
        assertEquals(WorkspaceTuiqiaoStatus.Loading, entry.status)
        cache.remove(entry)
        assertNull(cache.lookup("f", source, 0, 5))
        assertTrue(entry.jobs.isEmpty())
        gate.complete(Unit)
        assertTrue(entry.items.isEmpty())
    }

    @Test
    fun `a request still running when the sheet is hidden finishes into the entry`() {
        val gate = CompletableDeferred<Unit>()
        val cache = cache { prepared -> flow { gate.await(); emit(TuiqiaoEvent.Candidate(prepared.slots[0].key, TuiqiaoCandidate("迟到的", ""))) } }
        cache.start("f", "a.md", prepare(source, 0, 5))
        gate.complete(Unit)
        val entry = cache.lookup("f", source, 0, 5)!!
        assertEquals("迟到的", entry.items.single().candidate.text)
        assertEquals(WorkspaceTuiqiaoStatus.Done, entry.status)
    }

    @Test
    fun `failed slot retries alone`() {
        var fail = true
        val cache = cache { prepared ->
            flow {
                val key = prepared.slots.single().key
                if (fail) emit(TuiqiaoEvent.SlotFailed(key, null, "boom"))
                else emit(TuiqiaoEvent.Candidate(key, TuiqiaoCandidate("好了", "")))
            }
        }
        val entry = cache.start("f", "a.md", prepare(source, 0, 5))
        assertTrue(entry.status is WorkspaceTuiqiaoStatus.Failed)
        fail = false
        cache.retrySlot(entry, TuiqiaoSlot.DEFAULT_KEY)
        assertEquals("好了", entry.items.single().candidate.text)
        assertEquals(WorkspaceTuiqiaoStatus.Done, entry.status)
    }

    @Test
    fun `least recently used entries are evicted per file and overall`() {
        val cache = cache()
        val texts = (0 until WorkspaceTuiqiaoCache.PER_FILE + 2).map { "第${it}句。" }
        val doc = texts.joinToString("")
        var offset = 0
        val ranges = texts.map { val r = offset to offset + it.length; offset += it.length; r }
        ranges.forEachIndexed { index, (s, e) ->
            cache.start("f", "a.md", prepare(doc, s, e))
            // Touch the first just before the file fills up: the next two evict the 2nd and 3rd instead.
            if (index == WorkspaceTuiqiaoCache.PER_FILE - 1) assertTrue(cache.lookup("f", doc, ranges[0].first, ranges[0].second) != null)
        }
        assertEquals(WorkspaceTuiqiaoCache.PER_FILE, cache.size)
        assertTrue(cache.lookup("f", doc, ranges[0].first, ranges[0].second) != null)
        assertNull(cache.lookup("f", doc, ranges[1].first, ranges[1].second))
        assertNull(cache.lookup("f", doc, ranges[2].first, ranges[2].second))

        repeat(WorkspaceTuiqiaoCache.TOTAL) { file -> cache.start("file$file", "a.md", prepare(doc, ranges[0].first, ranges[0].second)) }
        assertEquals(WorkspaceTuiqiaoCache.TOTAL, cache.size)
        assertNull(cache.lookup("f", doc, ranges[3].first, ranges[3].second))
    }
}
