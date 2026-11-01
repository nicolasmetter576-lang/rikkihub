package me.rerere.ai.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessagePartTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server tool round trip preserves provider payload`() {
        val part: UIMessagePart = UIMessagePart.ServerTool(
            toolCallId = "srvtoolu_123",
            toolName = "web_search",
            input = buildJsonObject { put("query", "Kotlin serialization") },
            output = buildJsonArray {
                add(buildJsonObject {
                    put("url", "https://example.com")
                    put("encrypted_content", "encrypted")
                })
            },
            status = ServerToolStatus.COMPLETED,
            metadata = buildJsonObject { put("provider", "claude") },
        )

        val encoded = json.encodeToString(part)
        val encodedObject = json.parseToJsonElement(encoded).jsonObject
        val restored = json.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.ServerTool

        assertEquals("server_tool", encodedObject["type"]?.jsonPrimitive?.content)
        assertEquals("completed", encodedObject["status"]?.jsonPrimitive?.content)
        assertEquals(part, restored)
        assertTrue(restored.isFinished)
    }

    @Test
    fun `server tool tracks in progress state`() {
        val part = UIMessagePart.ServerTool(
            toolCallId = "ws_123",
            toolName = "web_search",
            status = ServerToolStatus.IN_PROGRESS,
        )

        assertEquals(ServerToolStatus.IN_PROGRESS, part.status)
        assertFalse(part.isFinished)
    }

    @Test
    fun `tool timing survives a save and a load`() {
        val started = kotlin.time.Instant.parse("2026-10-26T12:00:00.250Z")
        val finished = kotlin.time.Instant.parse("2026-10-26T12:00:01.750Z")
        val part: UIMessagePart = UIMessagePart.Tool(
            toolCallId = "c1", toolName = "search", input = "{}",
            output = listOf(UIMessagePart.Text("ok")), startedAt = started, finishedAt = finished,
        )

        val restored = json.decodeFromString<UIMessagePart>(json.encodeToString(part)) as UIMessagePart.Tool

        assertEquals(started, restored.startedAt)
        assertEquals(finished, restored.finishedAt)
        assertEquals(part, restored)
    }

    @Test
    fun `tools saved before timing existed load without a start or finish`() {
        val restored = json.decodeFromString<UIMessagePart>(
            """{"type":"tool","toolCallId":"c1","toolName":"search","input":"{}","output":[{"type":"text","text":"ok"}]}"""
        ) as UIMessagePart.Tool

        assertEquals(null, restored.startedAt)
        assertEquals(null, restored.finishedAt)
        assertTrue(restored.isExecuted)
    }

    @Test
    fun `an untimed tool does not write timing keys`() {
        val encoded = json.encodeToString<UIMessagePart>(UIMessagePart.Tool("c1", "search", "{}"))
        assertFalse(encoded.contains("startedAt"))
        assertFalse(encoded.contains("finishedAt"))
    }

    @Test
    fun `merging streamed tool deltas keeps the timing of the first part`() {
        val started = kotlin.time.Instant.parse("2026-10-26T12:00:00Z")
        val first = UIMessagePart.Tool("c1", "sea", "{\"q", startedAt = started)
        val merged = first.merge(UIMessagePart.Tool("c1", "rch", "\":1}"))

        assertEquals("search", merged.toolName)
        assertEquals(started, merged.startedAt)
        assertEquals(null, merged.finishedAt)
    }
}
