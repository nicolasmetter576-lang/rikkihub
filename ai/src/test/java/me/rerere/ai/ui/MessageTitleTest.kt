package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTitleTest {
    @Test
    fun `title on the last line is parsed and stripped`() {
        val text = "正文第一段。\n\n<rk-title>天气查询</rk-title>\n"
        assertEquals("天气查询", text.messageTitleOrNull())
        assertEquals("正文第一段。", text.stripMessageTitle())
    }

    @Test
    fun `title at the end of the last text line also counts`() {
        val text = "好的。 <rk-title>问候</rk-title>"
        assertEquals("问候", text.messageTitleOrNull())
        assertEquals("好的。", text.stripMessageTitle())
    }

    @Test
    fun `a tag that is not on the last line is left alone`() {
        val text = "格式是 <rk-title>标题</rk-title>，写在最后。\n下一行"
        assertNull(text.messageTitleOrNull())
        assertEquals(text, text.stripMessageTitle())
        assertEquals(text, text.stripMessageTitle(streaming = true))
    }

    @Test
    fun `blank or multi-line titles are not titles`() {
        assertNull("x\n<rk-title>  </rk-title>".messageTitleOrNull())
        assertNull("x\n<rk-title>a\nb</rk-title>".messageTitleOrNull())
    }

    @Test
    fun `half-written tag is hidden only while streaming`() {
        for (tail in listOf("<", "<rk-ti", "<rk-title>", "<rk-title>部分标", "<rk-title>部分</rk-")) {
            val text = "回复正文\n$tail"
            assertEquals(tail, "回复正文", text.stripMessageTitle(streaming = true))
            assertEquals(tail, text, text.stripMessageTitle())
        }
    }

    @Test
    fun `other angle brackets on the last line survive streaming`() {
        assertEquals("a < b", "a < b".stripMessageTitle(streaming = true))
        assertEquals("<div>x</div>", "<div>x</div>".stripMessageTitle(streaming = true))
        assertEquals("x <rk-title>t</rk-title> y", "x <rk-title>t</rk-title> y".stripMessageTitle(streaming = true))
    }

    @Test
    fun `the last title across text parts wins and user messages are untouched`() {
        val reply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("先查一下\n<rk-title>旧</rk-title>"),
                UIMessagePart.Text("查到了\n<rk-title>新</rk-title>"),
            ),
        )
        assertEquals("新", reply.extractMessageTitle())
        assertEquals("先查一下\n查到了", reply.toText())
        assertEquals("先查一下\n<rk-title>旧</rk-title>\n查到了\n<rk-title>新</rk-title>", reply.toRawText())

        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi\n<rk-title>t</rk-title>")))
        assertNull(user.extractMessageTitle())
        assertEquals("hi\n<rk-title>t</rk-title>", user.toText())
    }
}
