package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConcatenatedJsonObjectsTest {

    @Test
    fun `splits two back-to-back objects (production failure shape)`() {
        val result = ConcatenatedJsonObjects.split(
            """{"path": "/workspace/文风/标准版.md"}{"path": "/workspace/文风/精简版.md"}"""
        )!!
        assertEquals(
            listOf(
                """{"path": "/workspace/文风/标准版.md"}""",
                """{"path": "/workspace/文风/精简版.md"}""",
            ),
            result.segments,
        )
        assertEquals(0, result.duplicatesDropped)
    }

    @Test
    fun `allows whitespace and a single comma between objects`() {
        assertEquals(
            listOf("""{"a":1}""", """{"a":2}""", """{"a":3}"""),
            ConcatenatedJsonObjects.split(" {\"a\":1}\n{\"a\":2} , {\"a\":3}\n")!!.segments,
        )
    }

    @Test
    fun `braces and escaped quotes inside strings do not split`() {
        val first = """{"content":"x}{y \"}{\" z","n":[{"k":"]"}]}"""
        val second = """{"content":"\\"}"""
        assertEquals(
            listOf(first, second),
            ConcatenatedJsonObjects.split(first + second)!!.segments,
        )
    }

    @Test
    fun `identical objects collapse to the first occurrence`() {
        val result = ConcatenatedJsonObjects.split("""{"p":1,"q":2}{"q":2,"p":1}{"p":3}""")!!
        assertEquals(listOf("""{"p":1,"q":2}""", """{"p":3}"""), result.segments)
        assertEquals(3, result.totalSegments)
        assertEquals(1, result.duplicatesDropped)
    }

    @Test
    fun `single valid object or blank input is not a split`() {
        assertNull(ConcatenatedJsonObjects.split("""{"a":{"b":1}}"""))
        assertNull(ConcatenatedJsonObjects.split(""))
        assertNull(ConcatenatedJsonObjects.split("   "))
    }

    @Test
    fun `malformed shapes are rejected rather than guessed`() {
        listOf(
            """{"a":1}{"a":2""",          // 第二段截断
            """{"a":1}{"a":2}x""",        // 尾部垃圾
            """{"a":1}[1,2]""",           // 顶层非对象
            """{"a":1},,{"a":2}""",       // 连续逗号
            """,{"a":1}{"a":2}""",        // 前导逗号
            """{"a":1}{"a":2},""",        // 尾随逗号
            """{"a":1}{"a":]}""",         // 段内非法
            """{"a":1]{"a":2}""",         // 括号错配
        ).forEach { input ->
            assertNull(input, ConcatenatedJsonObjects.split(input))
        }
    }

    @Test
    fun `more than MAX_SEGMENTS objects is rejected`() {
        val atLimit = (1..ConcatenatedJsonObjects.MAX_SEGMENTS).joinToString("") { """{"i":$it}""" }
        assertEquals(
            ConcatenatedJsonObjects.MAX_SEGMENTS,
            ConcatenatedJsonObjects.split(atLimit)!!.segments.size,
        )
        assertNull(ConcatenatedJsonObjects.split(atLimit + """{"i":0}"""))
    }
}
