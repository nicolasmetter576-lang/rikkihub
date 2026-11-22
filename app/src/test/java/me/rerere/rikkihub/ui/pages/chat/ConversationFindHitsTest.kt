package me.rerere.rikkihub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFindHitsTest {
    private fun node(role: MessageRole, text: String, title: String? = null, vararg others: String) = MessageNode(
        messages = listOf(UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)), title = title)) +
            others.map { UIMessage(role = role, parts = listOf(UIMessagePart.Text(it))) },
    )

    @Test
    fun `every occurrence counts, case-insensitive, titles first, tags never match`() {
        val nodes = listOf(
            node(MessageRole.USER, "Cat and cat"),
            node(MessageRole.ASSISTANT, "no match here\n<rk-title>cat</rk-title>", title = "Cat talk"),
            node(MessageRole.ASSISTANT, "x".repeat(99) + "cat"),
        )
        val hits = conversationFindHits(nodes, "cat")
        assertEquals(listOf(0, 0, 1, 2), hits.map { it.nodeIndex })
        assertEquals(0f, hits[2].fraction)
        assertEquals(99f / 102f, hits[3].fraction, 0.001f)
    }

    @Test
    fun `only the selected branch is searched`() {
        val nodes = listOf(node(MessageRole.ASSISTANT, "selected", null, "other branch has needle"))
        assertEquals(0, conversationFindHits(nodes, "needle").size)
    }
}
