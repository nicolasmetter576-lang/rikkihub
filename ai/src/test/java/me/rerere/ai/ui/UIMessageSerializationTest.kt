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
            usageComplete = true,
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
        assertNull(decoded.usageComplete)
        assertFalse(decoded.isContextCheckpoint)
        // 旧消息的 usage 只保留了最后一步，正好就是最后一次请求的 usage
        assertEquals(TokenUsage(promptTokens = 10, completionTokens = 2), decoded.lastRequestUsage())
        assertEquals(12, decoded.contextTokens())
    }

    @Test
    fun `known missing current usage survives serialization without legacy fallback`() {
        val message = UIMessage.assistant("partial").copy(
            usage = TokenUsage(250, 30, totalTokens = 280),
            lastUsage = null,
            usageComplete = false,
            stepCount = 2,
        )
        val decoded = json.decodeFromString<UIMessage>(json.encodeToString(message))
        assertEquals(message, decoded)
        assertNull(decoded.lastRequestUsage())
        assertNull(decoded.contextTokens())
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

    @Test
    fun `checkpoint metadata and empty clear checkpoint round trip cleanly`() {
        val startId = Uuid.random()
        val endId = Uuid.random()
        val supersededId = Uuid.random()
        val summaryMsg = UIMessage.user("Summary").copy(
            isContextCheckpoint = true,
            checkpointMeta = ContextCheckpointMetadata(
                kind = ContextCheckpointKind.OVERFLOW_RETRY,
                templateId = "code",
                fromOriginal = true,
                chunked = true,
                requestCount = 3,
                carriedCount = 2,
                rangeStartNodeId = startId,
                rangeEndNodeId = endId,
                summarizedCount = 8,
                supersededCheckpointNodeId = supersededId,
                usage = TokenUsage(promptTokens = 100, completionTokens = 25, totalTokens = 125),
            ),
        )
        val decodedSummary = json.decodeFromString(UIMessage.serializer(), json.encodeToString(UIMessage.serializer(), summaryMsg))
        assertEquals(ContextCheckpointKind.OVERFLOW_RETRY, decodedSummary.checkpointMeta?.kind)
        assertEquals("code", decodedSummary.checkpointMeta?.templateId)
        assertTrue(decodedSummary.checkpointMeta?.fromOriginal == true)
        assertTrue(decodedSummary.checkpointMeta?.chunked == true)
        assertEquals(startId, decodedSummary.checkpointMeta?.rangeStartNodeId)
        assertEquals(endId, decodedSummary.checkpointMeta?.rangeEndNodeId)
        assertEquals(supersededId, decodedSummary.checkpointMeta?.supersededCheckpointNodeId)
        assertEquals(125, decodedSummary.checkpointMeta?.usage?.totalTokens)
        assertFalse(decodedSummary.isClearContextCheckpoint)

        val clearMsg = UIMessage(
            role = MessageRole.USER,
            parts = emptyList(),
            isContextCheckpoint = true,
            checkpointMeta = ContextCheckpointMetadata(kind = ContextCheckpointKind.CLEAR, summarizedCount = 4),
        )
        val decodedClear = json.decodeFromString(UIMessage.serializer(), json.encodeToString(UIMessage.serializer(), clearMsg))
        assertTrue(decodedClear.isContextCheckpoint)
        assertTrue(decodedClear.isClearContextCheckpoint)
        assertEquals(ContextCheckpointKind.CLEAR, decodedClear.checkpointMeta?.kind)
        assertTrue(decodedClear.parts.isEmpty())

        val legacyMsg = UIMessage.user("Legacy summary").copy(isContextCheckpoint = true)
        val decodedLegacy = json.decodeFromString(UIMessage.serializer(), json.encodeToString(UIMessage.serializer(), legacyMsg))
        assertNull(decodedLegacy.checkpointMeta)
        assertFalse(decodedLegacy.isClearContextCheckpoint)
    }

    @Test
    fun `stopReason round trips with stable wire names and defaults to null on legacy messages`() {
        val expectedWire = mapOf(
            MessageStopReason.MAX_TOKENS to "max_tokens",
            MessageStopReason.CONTENT_FILTER to "content_filter",
            MessageStopReason.STEP_LIMIT to "step_limit",
            MessageStopReason.INCOMPLETE to "incomplete",
        )
        for ((reason, wire) in expectedWire) {
            val msg = UIMessage.assistant("cut off").copy(stopReason = reason)
            val encoded = json.encodeToString(msg)
            assertTrue("expected wire name $wire in $encoded", encoded.contains("\"stopReason\":\"$wire\""))
            assertEquals(reason, json.decodeFromString<UIMessage>(encoded).stopReason)
        }

        val legacy = """{"id":"${Uuid.random()}","role":"assistant","parts":[{"type":"text","text":"old"}],"createdAt":"2026-10-01T10:00:00"}"""
        assertNull(json.decodeFromString<UIMessage>(legacy).stopReason)
    }

    @Test
    fun `classifyProviderFinishReason enforces strict whitelist across providers`() {
        for (raw in listOf("length", "LENGTH", " max_tokens ", "max_output_tokens", "incomplete:max_output_tokens", "model_context_window_exceeded")) {
            assertEquals(raw, MessageStopReason.MAX_TOKENS, classifyProviderFinishReason(raw))
        }
        for (raw in listOf("content_filter", "incomplete:content_filter", "refusal", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION", "MODEL_ARMOR")) {
            assertEquals(raw, MessageStopReason.CONTENT_FILTER, classifyProviderFinishReason(raw))
        }
        assertEquals(MessageStopReason.STEP_LIMIT, classifyProviderFinishReason("max_turn_requests"))
        for (raw in listOf("incomplete", "pause_turn", "incomplete:other_reason")) {
            assertEquals(raw, MessageStopReason.INCOMPLETE, classifyProviderFinishReason(raw))
        }
        for (raw in listOf(null, "", "   ", "stop", "STOP", "end_turn", "completed", "tool_calls", "tool_use", "cancelled", "function_call", "custom_gateway_ok")) {
            assertNull("normal/unknown reason should stay null: $raw", classifyProviderFinishReason(raw))
        }
    }
}
