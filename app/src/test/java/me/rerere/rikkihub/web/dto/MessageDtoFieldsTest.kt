package me.rerere.rikkihub.web.dto

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
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
            generationTimeMs = 900,
            lastUsage = TokenUsage(promptTokens = 150, completionTokens = 20),
            stepCount = 2,
            isContextCheckpoint = true,
        )
        val dto = message.toDto()
        assertEquals(message.modelSnapshot, dto.modelSnapshot)
        assertEquals(900L, dto.generationTimeMs)
        assertEquals(message.lastUsage, dto.lastUsage)
        assertEquals(2, dto.stepCount)
        assertTrue(dto.isContextCheckpoint)
        val encoded = json.encodeToString(MessageDto.serializer(), dto)
        assertTrue(encoded.contains("\"lastUsage\""))
        assertTrue(encoded.contains("\"isContextCheckpoint\":true"))
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
