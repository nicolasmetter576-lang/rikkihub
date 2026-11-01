package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class UIMessageSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `synthetic marker is not serialized`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertFalse(encoded.contains("isSynthetic"))
        assertFalse(decoded.isSynthetic)
    }

    @Test
    fun `new message fields round trip`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hi")),
            modelId = Uuid.random(),
            modelSnapshot = ModelSnapshot(modelId = "gpt-x", displayName = "GPT X"),
            usage = TokenUsage(promptTokens = 250, completionTokens = 30),
            generationTimeMs = 1200,
            lastUsage = TokenUsage(promptTokens = 150, completionTokens = 20),
            stepCount = 2,
            isContextCheckpoint = true,
        )
        assertEquals(message, json.decodeFromString<UIMessage>(json.encodeToString(message)))
    }

    @Test
    fun `messages saved before the new fields decode with defaults`() {
        val legacy = """
            {"id":"${Uuid.random()}","role":"assistant","parts":[{"type":"text","text":"old"}],
             "createdAt":"2026-10-01T10:00:00","usage":{"promptTokens":10,"completionTokens":2}}
        """.trimIndent()
        val decoded = json.decodeFromString<UIMessage>(legacy)
        assertNull(decoded.modelSnapshot)
        assertNull(decoded.generationTimeMs)
        assertNull(decoded.lastUsage)
        assertNull(decoded.stepCount)
        assertFalse(decoded.isContextCheckpoint)
        // 旧消息的 usage 只保留了最后一步，正好就是最后一次请求的 usage
        assertEquals(TokenUsage(promptTokens = 10, completionTokens = 2), decoded.lastRequestUsage())
        assertEquals(12, decoded.contextTokens())
    }

    @Test
    fun `snapshot model is only built when both id and snapshot exist`() {
        val id = Uuid.random()
        val message = UIMessage.assistant("x").copy(
            modelId = id,
            modelSnapshot = ModelSnapshot(modelId = "claude-x", displayName = "Claude X"),
        )
        val model = message.snapshotModel()!!
        assertEquals(id, model.id)
        assertEquals("claude-x", model.modelId)
        assertEquals("Claude X", model.displayName)
        assertNull(message.copy(modelId = null).snapshotModel())
        assertNull(message.copy(modelSnapshot = null).snapshotModel())
    }
}
