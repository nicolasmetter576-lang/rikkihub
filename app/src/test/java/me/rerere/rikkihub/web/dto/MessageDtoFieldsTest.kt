package me.rerere.rikkihub.web.dto

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.MessageStopReason
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网页端拿到的消息字段（模型快照、多步统计、检查点）与发送请求里的文件夹。 */
class MessageDtoFieldsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `message dto carries snapshot, step stats and checkpoint flag`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("done")),
            modelSnapshot = ModelSnapshot(modelId = "m", displayName = "Model M"),
            usage = TokenUsage(promptTokens = 250, completionTokens = 30),
            usageComplete = true,
            stopReason = MessageStopReason.MAX_TOKENS,
            generationTimeMs = 900,
            lastUsage = TokenUsage(promptTokens = 150, completionTokens = 20),
            stepCount = 2,
            isContextCheckpoint = true,
        )
        val dto = message.toDto()
        assertEquals(message.modelSnapshot, dto.modelSnapshot)
        assertEquals(900L, dto.generationTimeMs)
        assertEquals(message.lastUsage, dto.lastUsage)
        assertEquals(true, dto.usageComplete)
        assertEquals(MessageStopReason.MAX_TOKENS, dto.stopReason)
        assertEquals(2, dto.stepCount)
        assertTrue(dto.isContextCheckpoint)
        val encoded = json.encodeToString(MessageDto.serializer(), dto)
        assertTrue(encoded.contains("\"lastUsage\""))
        assertTrue(encoded.contains("\"usageComplete\":true"))
        assertTrue(encoded.contains("\"stopReason\":\"max_tokens\""))
        assertTrue(encoded.contains("\"isContextCheckpoint\":true"))
    }

    @Test
    fun `usage completeness survives message JSON and DTO without inventing legacy values`() {
        val oldJson = """{"role":"assistant","parts":[],"createdAt":"2026-11-02T10:00:00","usage":{"promptTokens":100,"completionTokens":50,"totalTokens":180}}"""
        val oldMessage = json.decodeFromString(UIMessage.serializer(), oldJson)
        assertNull(oldMessage.usageComplete)
        assertNull(oldMessage.finishedAt)
        assertNull(oldMessage.toDto().usageComplete)
        for (complete in listOf(null, false, true)) {
            val message = oldMessage.copy(usageComplete = complete, lastUsage = null)
            val saved = json.encodeToString(UIMessage.serializer(), message)
            val restored = json.decodeFromString(UIMessage.serializer(), saved)
            assertEquals(complete, restored.usageComplete)
            val dto = restored.toDto()
            assertEquals("ASSISTANT", dto.role) // DTO role names differ from persisted AI enum values.
            val wire = json.encodeToString(MessageDto.serializer(), dto)
            assertEquals(complete, json.decodeFromString(MessageDto.serializer(), wire).usageComplete)
            assertNull(dto.lastUsage)
            assertNull(dto.finishedAt)
        }
        val oldDto = """{"id":"old","role":"ASSISTANT","parts":[],"createdAt":"2026-11-02T10:00:00"}"""
        assertNull(json.decodeFromString(MessageDto.serializer(), oldDto).usageComplete)
    }

    @Test
    fun `send request folder id is optional`() {
        val withFolder = json.decodeFromString(
            SendMessageRequest.serializer(),
            """{"parts":[{"type":"text","text":"hi"}],"folderId":"0f8fad5b-d9cb-469f-a165-70867728950e"}""",
        )
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", withFolder.folderId)
        val without = json.decodeFromString(SendMessageRequest.serializer(), """{"parts":[]}""")
        assertNull(without.folderId)
        assertFalse(without.parts.isNotEmpty())
    }
}
