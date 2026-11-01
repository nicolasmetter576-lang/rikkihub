package me.rerere.rikkihub.data.ai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class InteractionsToolProgressMetadataTest {
    private val provenance = buildJsonObject {
        put("interactions_signature", "provider-signature")
        put("interactions_step_index", 3)
        put("interactions_content_index", 0)
        put("interactions_exchange_key", "round-one")
    }

    @Test fun `progress replaces UI fields but preserves provider provenance and cannot forge it`() {
        val original = UIMessagePart.Tool("call", "subagent", "{}", metadata = JsonObject(provenance + ("old_ui" to JsonPrimitive("old"))))
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(original)))
        val progress = buildJsonObject {
            put("status", "running"); put("title", "worker")
            put("interactions_signature", "forged"); put("interactions_step_index", 99)
            put("interactions_exchange_key", "fake-round")
        }
        val result = updateToolPartMetadataAt(messages, 0, 0, "call", progress)
        val updated = result.single().parts.single() as UIMessagePart.Tool
        assertEquals(JsonObject(provenance + mapOf("status" to JsonPrimitive("running"), "title" to JsonPrimitive("worker"))), updated.metadata)
        assertEquals(original.toolCallId, updated.toolCallId)
        assertEquals(original.toolName, updated.toolName)
        assertEquals(original.input, updated.input)
        assertEquals("old", original.metadata!!.getValue("old_ui").jsonPrimitive.content)
    }

    @Test fun `progress cannot invent Interactions metadata on a non Interactions call`() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call", "tool", "{}"))))
        val result = updateToolPartMetadataAt(messages, 0, 0, "call", JsonObject(provenance + ("status" to JsonPrimitive("ok"))))
        assertEquals(buildJsonObject { put("status", "ok") }, (result.single().parts.single() as UIMessagePart.Tool).metadata)
    }

    @Test fun `parallel duplicate ids update only chosen part and id mismatch never repairs dispatch`() {
        val first = UIMessagePart.Tool("same", "first", "{}", metadata = provenance)
        val second = UIMessagePart.Tool("same", "second", "{}", metadata = provenance)
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, second)))
        val result = updateToolPartMetadataAt(messages, 0, 1, "same", buildJsonObject { put("status", "running") })
        assertEquals(first, result.single().parts.first())
        assertEquals("running", result.single().parts[1].metadata!!.getValue("status").jsonPrimitive.content)
        assertSame(messages, updateToolPartMetadataAt(messages, 0, 1, "other", provenance))
    }
}
