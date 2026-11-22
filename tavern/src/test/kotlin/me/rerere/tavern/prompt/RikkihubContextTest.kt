package me.rerere.tavern.prompt

import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.token.TokenCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `rikkihubContext` 是 rikkihub 自有的标记（上游没有，所以没有 golden）：接入层给一段成品文本，
 * 放在布局里这个标记所在的位置，作为 system 消息；文本为空或标记没启用则不产出。
 */
class RikkihubContextTest {
    private val ctxId = PromptMarkers.RIKKIHUB_CONTEXT

    /** 宏会把 {{char}} 换成 CHAR；用来证明 rikkihubContext 的文本没有再被求值。 */
    private val host = object : PromptHost {
        override fun substitute(text: String): String = text.replace("{{char}}", "CHAR")
        override fun countTokens(text: String): Int = TokenCounter.count(text)
    }

    private fun main() = PromptEntry(identifier = "main", role = PromptRole.SYSTEM, content = "MAIN", systemPrompt = true)
    private fun marker(id: String, enabled: Boolean = true) =
        PromptEntry(identifier = id, name = id, systemPrompt = true, marker = true, enabled = enabled)

    private fun assemble(layout: List<PromptEntry>, text: String, squash: Boolean = false): List<String> {
        val input = AssemblyInput(
            layout = PromptLayout(layout),
            options = AssemblyOptions(squashSystemMessages = squash),
            charName = "Seraphina",
            userName = "User",
            history = listOf(
                HistoryItem(PromptRole.USER, "User", "hello"),
                HistoryItem(PromptRole.ASSISTANT, "Seraphina", "hi"),
            ),
            rikkihubContext = text,
        )
        return PromptAssembler.assemble(input, host).items.map {
            when (it) {
                is AssembledItem.Generated -> "${it.role}:${it.content}"
                is AssembledItem.History -> "H${it.originalIndex}"
            }
        }
    }

    @Test
    fun appearsWhereTheMarkerSits() {
        val before = assemble(listOf(main(), marker(ctxId), marker("chatHistory")), "MEM: likes tea")
        assertTrue(before.toString(), before.indexOf("SYSTEM:MEM: likes tea") in 1 until before.indexOf("H0"))

        val after = assemble(listOf(main(), marker("chatHistory"), marker(ctxId)), "MEM: likes tea")
        assertEquals(listOf("H0", "H1", "SYSTEM:MEM: likes tea"), after.takeLast(3))
    }

    @Test
    fun emptyTextProducesNothing() {
        val withEmpty = assemble(listOf(main(), marker(ctxId), marker("chatHistory")), "")
        val without = assemble(listOf(main(), marker("chatHistory")), "")
        assertEquals(without, withEmpty)
        assertFalse(withEmpty.any { it.startsWith("SYSTEM:") && it.length == "SYSTEM:".length })
    }

    @Test
    fun disabledMarkerProducesNothing() {
        val disabled = assemble(listOf(main(), marker(ctxId, enabled = false), marker("chatHistory")), "MEM")
        assertFalse(disabled.toString(), disabled.any { it.contains("MEM") })
    }

    /**
     * 布局里没有这个标记（比如从酒馆导入的预设）：沿用上游对“布局里缺少的标记”的做法——追加在末尾，不丢内容。
     * 这不是 rikkihub 想要的位置（记忆/工具说明应在 main 之后、历史之前）：接入层创建/导入预设时要把标记补进布局。
     */
    @Test
    fun missingMarkerIsAppendedAtTheEndNotDropped() {
        val missing = assemble(listOf(main(), marker("chatHistory")), "MEM")
        assertEquals(listOf("H0", "H1", "SYSTEM:MEM"), missing.takeLast(3))
    }

    @Test
    fun textIsNotEvaluatedAgain() {
        val out = assemble(listOf(main(), marker(ctxId), marker("chatHistory")), "{{char}} remembers tea")
        assertTrue(out.toString(), out.contains("SYSTEM:{{char}} remembers tea"))
    }

    @Test
    fun squashMergesItWithAdjacentSystemMessages() {
        val out = assemble(listOf(main(), marker(ctxId), marker("chatHistory")), "MEM", squash = true)
        assertEquals("SYSTEM:MAIN\nMEM", out.first())
    }
}
