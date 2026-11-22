package me.rerere.rikkihub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class RawJsonMemoTest {
    @Test
    fun reusesTheDecodedInstanceWhileTheRawStringIsUnchanged() {
        var calls = 0
        val memo = RawJsonMemo { raw -> calls++; listOf(raw) }
        val first = memo.get("[1]")
        assertSame(first, memo.get("[1]"))
        assertSame(first, memo.get(String(charArrayOf('[', '1', ']')))) // 内容相同但不是同一个 String 实例
        assertEquals(1, calls)
    }

    @Test
    fun decodesAgainWhenTheRawStringChanges() {
        var calls = 0
        val memo = RawJsonMemo { raw -> calls++; listOf(raw) }
        val first = memo.get("a")
        val second = memo.get("b")
        assertNotSame(first, second)
        assertEquals(listOf("b"), second)
        assertEquals(2, calls)
    }

    @Test
    fun doesNotCacheAFailedDecode() {
        var calls = 0
        val memo = RawJsonMemo<String> { raw -> calls++; check(raw != "bad") { "boom" }; raw }
        runCatching { memo.get("bad") }
        runCatching { memo.get("bad") }
        assertEquals(2, calls)
        assertEquals("ok", memo.get("ok"))
    }
}
