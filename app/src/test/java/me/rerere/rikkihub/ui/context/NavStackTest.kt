package me.rerere.rikkihub.ui.context

import org.junit.Assert.assertEquals
import org.junit.Test

class NavStackTest {
    @Test
    fun `pop removes the top entry`() {
        val stack = mutableListOf("chat", "settings", "providers")
        stack.popKeepingLast()
        assertEquals(listOf("chat", "settings"), stack)
    }

    @Test
    fun `pop never empties the stack`() {
        // 同一帧里连按两次返回：第二次回调时栈里只剩一项，不能再弹
        val stack = mutableListOf("chat", "settings")
        repeat(3) { stack.popKeepingLast() }
        assertEquals(listOf("chat"), stack)
    }

    @Test
    fun `pop on a single entry keeps it`() {
        val stack = mutableListOf("chat")
        stack.popKeepingLast()
        assertEquals(listOf("chat"), stack)
    }
}
