package me.rerere.ai.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialToolInputTest {
    private fun partial(input: String) = UIMessagePart.Tool("id", "t", input).inputAsPartialJson() as JsonObject

    @Test
    fun `every prefix of an arguments object parses and text only grows`() {
        val text = "第一章 \"引号\" 与\\反斜杠\n换行\t制表 🔥 结尾"
        val full = """{"path":"/workspace/write/a.txt","text":${kotlinx.serialization.json.Json.encodeToString(text)},"n":12,"ok":true,"none":null}"""
        var previous = ""
        for (end in 0..full.length) {
            val parsed = partial(full.substring(0, end))
            val current = parsed["text"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("text must grow at $end: '$previous' -> '$current'", current.startsWith(previous))
            previous = current
        }
        assertEquals(text, previous)
        assertEquals("/workspace/write/a.txt", partial(full)["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dangling key colon comma escape and unicode escape are dropped`() {
        assertEquals("", partial("""{"path":"/a","te""")["te"]?.jsonPrimitive?.content.orEmpty())
        assertEquals("/a", partial("""{"path":"/a","te""")["path"]!!.jsonPrimitive.content)
        assertEquals("/a", partial("""{"path":"/a",""")["path"]!!.jsonPrimitive.content)
        assertEquals("ab", partial("""{"text":"ab\""")["text"]!!.jsonPrimitive.content)
        assertEquals("ab", partial("""{"text":"ab\u00""")["text"]!!.jsonPrimitive.content)
        assertEquals("é", partial("""{"text":"é""")["text"]!!.jsonPrimitive.content)
        assertTrue(partial("""{"path":"/a","text":""").keys == setOf("path"))
        assertEquals("", partial("""{"path":"/a","text":"""")["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank and unparsable input give an empty object`() {
        assertTrue(partial("").isEmpty())
        assertTrue(partial("not json").isEmpty())
    }
}
