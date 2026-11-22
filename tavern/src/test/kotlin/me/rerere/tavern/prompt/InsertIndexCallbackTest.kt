package me.rerere.tavern.prompt

import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.token.TokenCounter
import org.junit.Assert.assertEquals
import org.junit.Test

/** 深度注入落位后，宿主可以改它在历史里的位置（rikkihub 用它避开「用户→带工具的助手」之间）。 */
class InsertIndexCallbackTest {
    private fun layout() = PromptLayout(
        listOf(
            PromptEntry(identifier = "chatHistory", name = "chatHistory", systemPrompt = true, marker = true),
            PromptEntry(
                identifier = "late", role = PromptRole.SYSTEM, content = "LATE", systemPrompt = false,
                injectionPosition = InjectionPosition.ABSOLUTE, injectionDepth = 1,
            ),
        )
    )

    private val history = listOf(
        HistoryItem(PromptRole.USER, "u", "q1"),
        HistoryItem(PromptRole.ASSISTANT, "a", "a1"),
        HistoryItem(PromptRole.USER, "u", "q2"),
        HistoryItem(PromptRole.ASSISTANT, "a", "a2"),
    )

    private fun run(adjust: (Int, List<HistoryProbe>) -> Int): List<String> {
        val host = object : PromptHost {
            override fun substitute(text: String) = text
            override fun countTokens(text: String) = TokenCounter.count(text)
            override fun adjustHistoryInsertIndex(index: Int, items: List<HistoryProbe>) = adjust(index, items)
        }
        val input = AssemblyInput(layout = layout(), options = AssemblyOptions(newChatPrompt = ""), history = history)
        return PromptAssembler.assemble(input, host).items.map {
            when (it) {
                is AssembledItem.Generated -> it.content
                is AssembledItem.History -> "H${it.originalIndex}"
            }
        }
    }

    @Test
    fun defaultKeepsTheUpstreamPosition() {
        assertEquals(listOf("H0", "H1", "H2", "LATE", "H3"), run { index, _ -> index })
    }

    @Test
    fun hostSeesTheListWithoutTheBlockAndCanMoveIt() {
        var seen: List<HistoryProbe> = emptyList()
        var seenIndex = -1
        val out = run { index, items ->
            seen = items
            seenIndex = index
            index - 1
        }
        // 回调看到的是不含注入块的四条历史，下标指向块后面的那条（H3）
        assertEquals(listOf(0, 1, 2, 3), seen.map { it.originalIndex })
        assertEquals(3, seenIndex)
        assertEquals(listOf("H0", "H1", "LATE", "H2", "H3"), out)
    }

    @Test
    fun outOfRangeAnswerIsClamped() {
        assertEquals(listOf("H0", "H1", "H2", "H3", "LATE"), run { _, items -> items.size + 7 })
        assertEquals(listOf("LATE", "H0", "H1", "H2", "H3"), run { _, _ -> -5 })
    }
}
