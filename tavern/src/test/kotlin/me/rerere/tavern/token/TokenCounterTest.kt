package me.rerere.tavern.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCounterTest {
    @Test
    fun countsAscii() {
        assertEquals(0, TokenCounter.count(""))
        assertEquals(1, TokenCounter.count("hello"))
        assertEquals(4, TokenCounter.count("Hello, world!"))
    }

    @Test
    fun countsChinese() {
        assertTrue(TokenCounter.count("你好，世界") in 2..6)
    }
}
