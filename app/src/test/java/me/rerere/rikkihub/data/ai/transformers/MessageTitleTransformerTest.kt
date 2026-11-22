package me.rerere.rikkihub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.db.fts.extractFtsText
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MessageTitleTransformerTest {
    private val settings = Settings(messageTitlePrompt = "CONVENTION")

    private fun ctx(enabled: Boolean) = TransformerContext(
        context = FakeContext(Files.createTempDirectory("title").toFile()),
        model = Model(modelId = "m1"),
        assistant = Assistant(enableMessageTitle = enabled),
        settings = settings,
    )

    private fun reply(vararg texts: String, title: String? = null) =
        UIMessage(role = MessageRole.ASSISTANT, parts = texts.map { UIMessagePart.Text(it) }, title = title)

    @Test
    fun `convention goes to the end of the first system message only when enabled`() = runBlocking {
        val messages = listOf(UIMessage.system("main"), UIMessage.user("hi"), UIMessage.system("later"))
        val on = MessageTitleConventionTransformer.transform(ctx(enabled = true), messages)
        assertEquals("main\n\nCONVENTION", on[0].toText())
        assertEquals("later", on[2].toText())
        assertEquals(messages, MessageTitleConventionTransformer.transform(ctx(enabled = false), messages))
    }

    @Test
    fun `convention becomes the system message when there is none`() = runBlocking {
        val out = MessageTitleConventionTransformer.transform(ctx(enabled = true), listOf(UIMessage.user("hi")))
        assertEquals(MessageRole.SYSTEM, out.first().role)
        assertEquals("CONVENTION", out.first().toText())
    }

    @Test
    fun `title is parsed at finish and the text keeps the tag`() = runBlocking {
        val raw = "回答\n<rk-title>天气</rk-title>"
        val out = MessageTitleTransformer.onGenerationFinish(ctx(enabled = false), listOf(reply(raw))).single()
        assertEquals("天气", out.title)
        assertEquals(raw, (out.parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a step without a tag keeps the title of an earlier step`() = runBlocking {
        val out = MessageTitleTransformer.onGenerationFinish(ctx(enabled = true), listOf(reply("调用工具前", title = "旧标题"))).single()
        assertEquals("旧标题", out.title)
        val none = MessageTitleTransformer.onGenerationFinish(ctx(enabled = true), listOf(reply("没写标题"))).single()
        assertNull(none.title)
    }

    @Test
    fun `search index holds the title but not the tag`() {
        val indexed = reply("正文内容\n<rk-title>天气</rk-title>", title = "改过的标题").extractFtsText()
        assertTrue(indexed.startsWith("改过的标题\n"))
        assertTrue(indexed.contains("正文内容"))
        assertTrue("<rk-title>" !in indexed)
    }
}
