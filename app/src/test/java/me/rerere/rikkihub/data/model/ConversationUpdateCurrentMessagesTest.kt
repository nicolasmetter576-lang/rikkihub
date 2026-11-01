package me.rerere.rikkihub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/** 流式结果写回节点：按消息 id 定位，生成期间删改前面的消息不会写错位置。 */
class ConversationUpdateCurrentMessagesTest {
    private fun user(text: String) = UIMessage.user(text)
    private fun reply(text: String, id: Uuid = Uuid.random()) =
        UIMessage(id = id, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun conversationOf(vararg messages: UIMessage) =
        Conversation(assistantId = Uuid.random(), messageNodes = messages.map { it.toMessageNode() })

    private val Conversation.texts get() = currentMessages.map { it.toText() }
    private val Conversation.shape get() = messageNodes.map { node -> node.messages.map { it.toText() } }

    @Test
    fun `streaming appends a reply node, then rewrites it and keeps earlier nodes as they are`() {
        val u1 = user("u1")
        val a1 = reply("a1")
        val u2 = user("u2")
        val start = conversationOf(u1, a1, u2)
        val replyId = Uuid.random()

        val first = start.updateCurrentMessages(listOf(u1, a1, u2, reply("a", replyId)))
        val second = first.updateCurrentMessages(listOf(u1, a1, u2, reply("ab", replyId)))

        assertEquals(listOf("u1", "a1", "u2", "ab"), second.texts)
        assertEquals(4, second.messageNodes.size)
        assertSame(start.messageNodes[0], second.messageNodes[0])
        assertSame(first.messageNodes[3].id, second.messageNodes[3].id)
        // 没变的列表原样返回
        assertSame(second, second.updateCurrentMessages(second.currentMessages))
    }

    @Test
    fun `regenerating a reply in the middle adds a branch there and drops what came after`() {
        val u1 = user("u1")
        val a1 = reply("a1")
        val u2 = user("u2")
        val a2 = reply("a2")
        val start = conversationOf(u1, a1, u2, a2)

        val result = start.updateCurrentMessages(listOf(u1, reply("a1 again")))

        assertEquals(listOf(listOf("u1"), listOf("a1", "a1 again")), result.shape)
        assertEquals(listOf("u1", "a1 again"), result.texts)
    }

    @Test
    fun `deleting an earlier message while a reply streams does not bring it back`() {
        val u1 = user("u1")
        val a1 = reply("a1")
        val u2 = user("u2")
        val replyId = Uuid.random()
        val streaming = conversationOf(u1, a1, u2).updateCurrentMessages(listOf(u1, a1, u2, reply("a", replyId)))
        // 用户在生成中删掉了 a1
        val afterDelete = streaming.copy(messageNodes = streaming.messageNodes.filterNot { it.currentMessage.id == a1.id })

        val result = afterDelete.updateCurrentMessages(listOf(u1, a1, u2, reply("ab", replyId)))

        assertEquals(listOf(listOf("u1"), listOf("u2"), listOf("ab")), result.shape)
        // 回复还在原来的节点里
        assertEquals(streaming.messageNodes[3].id, result.messageNodes[2].id)
    }

    @Test
    fun `deleting the question being answered keeps the reply in its own node`() {
        val u1 = user("u1")
        val a1 = reply("a1")
        val u2 = user("u2")
        val replyId = Uuid.random()
        val streaming = conversationOf(u1, a1, u2).updateCurrentMessages(listOf(u1, a1, u2, reply("a", replyId)))
        val afterDelete = streaming.copy(messageNodes = streaming.messageNodes.filterNot { it.currentMessage.id == u2.id })

        val result = afterDelete.updateCurrentMessages(listOf(u1, a1, u2, reply("ab", replyId)))

        assertEquals(listOf(listOf("u1"), listOf("a1"), listOf("ab")), result.shape)
    }

    @Test
    fun `editing an earlier message while a reply streams keeps the edited branch selected`() {
        val u1 = user("u1")
        val a1 = reply("a1")
        val u2 = user("u2")
        val replyId = Uuid.random()
        val streaming = conversationOf(u1, a1, u2).updateCurrentMessages(listOf(u1, a1, u2, reply("a", replyId)))
        // 编辑 u1：同一节点多出一个分支并选中它
        val edited = streaming.copy(
            messageNodes = streaming.messageNodes.mapIndexed { index, node ->
                if (index == 0) node.copy(messages = node.messages + user("u1 edited"), selectIndex = 1) else node
            }
        )

        val result = edited.updateCurrentMessages(listOf(u1, a1, u2, reply("ab", replyId)))

        assertEquals(listOf("u1 edited", "a1", "u2", "ab"), result.texts)
        assertEquals(listOf("u1", "u1 edited"), result.shape[0])
    }

    @Test
    fun `a reply written into an empty conversation starts its first node`() {
        val result = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
            .updateCurrentMessages(listOf(user("hi"), reply("hello")))

        assertEquals(listOf(listOf("hi"), listOf("hello")), result.shape)
    }
}
