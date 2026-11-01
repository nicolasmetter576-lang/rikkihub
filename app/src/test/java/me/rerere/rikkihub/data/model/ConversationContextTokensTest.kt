package me.rerere.rikkihub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid

/** 输入区上下文用量环取的数（移植上游 7cceda2 / 660b5d6 的用例，按位置判断检查点）。 */
class ConversationContextTokensTest {
    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    private fun reply(usage: TokenUsage? = null, lastUsage: TokenUsage? = null) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        usage = usage,
        lastUsage = lastUsage,
    )

    @Test
    fun `uses the last request of the last reply instead of accumulated usage`() {
        val conversation = conversation(
            UIMessage.user("hi"),
            reply(
                usage = TokenUsage(promptTokens = 15_000, completionTokens = 140),
                lastUsage = TokenUsage(promptTokens = 7_500, completionTokens = 100),
            ),
        )
        assertEquals(7_600, conversation.lastContextTokens())
    }

    @Test
    fun `falls back to usage for replies saved before last usage existed`() {
        assertEquals(7_100, conversation(reply(usage = TokenUsage(promptTokens = 7_000, completionTokens = 100))).lastContextTokens())
    }

    @Test
    fun `returns null when compressed after the last reply and counts again after the next one`() {
        val summary = UIMessage.user("summary").copy(isContextCheckpoint = true)
        assertNull(conversation(reply(usage = TokenUsage(promptTokens = 7_600)), summary).lastContextTokens())
        assertEquals(
            2_000,
            conversation(summary, UIMessage.user("hi"), reply(usage = TokenUsage(promptTokens = 2_000))).lastContextTokens(),
        )
    }

    @Test
    fun `returns null without any reply`() {
        assertNull(conversation(UIMessage.user("hi")).lastContextTokens())
    }

    private fun at(minute: Int) = LocalDateTime(2026, 10, 26, 10, minute, 0)

    @Test
    fun `a reply kept after the checkpoint was generated before it so its usage is out of date`() {
        val kept = reply(usage = TokenUsage(promptTokens = 90_000, completionTokens = 400)).copy(createdAt = at(1))
        val summary = UIMessage.user("summary").copy(isContextCheckpoint = true, createdAt = at(30))
        assertNull(conversation(UIMessage.user("q").copy(createdAt = at(0)), summary, kept).lastContextTokens())
    }

    @Test
    fun `a reply generated after the compression counts even when older replies were kept`() {
        val kept = reply(usage = TokenUsage(promptTokens = 90_000, completionTokens = 400)).copy(createdAt = at(1))
        val summary = UIMessage.user("summary").copy(isContextCheckpoint = true, createdAt = at(30))
        val fresh = reply(usage = TokenUsage(promptTokens = 2_000, completionTokens = 50)).copy(createdAt = at(40))
        assertEquals(2_050, conversation(summary, kept, UIMessage.user("next").copy(createdAt = at(35)), fresh).lastContextTokens())
    }
}
