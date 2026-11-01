package me.rerere.ai.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamChunk] sealed 层级的序列化 roundtrip：新增字段（ToolCallStart.
 * toolCallId）与新子类（ToolCallIdAssigned）的兼容性门槛用例——
 * `ignoreUnknownKeys` 只覆盖未知字段，不覆盖未知 sealed 子类，因此
 * 每个事件形态都必须能编码再原样解码。
 */
class StreamChunkSerializationTest {
    private fun roundtrip(chunk: StreamChunk): StreamChunk {
        val encoded = json.encodeToString(StreamChunk.serializer(), chunk)
        return json.decodeFromString(StreamChunk.serializer(), encoded)
    }

    @Test
    fun `tool call start with explicit tool call id roundtrips`() {
        val chunk = StreamChunk.ToolCallStart(
            id = "chat-tool-1",
            toolName = "search",
            metadata = buildJsonObject { put("k", "v") },
            toolCallId = "call_real",
        )
        val decoded = roundtrip(chunk)
        assertEquals(chunk, decoded)
        assertEquals("call_real", (decoded as StreamChunk.ToolCallStart).toolCallId)
    }

    @Test
    fun `tool call start without tool call id stays legacy`() {
        val chunk = StreamChunk.ToolCallStart(id = "call_1", toolName = "echo")
        val decoded = roundtrip(chunk) as StreamChunk.ToolCallStart
        assertEquals(chunk, decoded)
        assertEquals(null, decoded.toolCallId)
    }

    @Test
    fun `tool call id assigned roundtrips with stream key and real id`() {
        val chunk = StreamChunk.ToolCallIdAssigned(id = "chat-tool-1", toolCallId = "call_real")
        val decoded = roundtrip(chunk)
        assertEquals(chunk, decoded)
        assertEquals("chat-tool-1", (decoded as StreamChunk.ToolCallIdAssigned).id)
        assertEquals("call_real", decoded.toolCallId)
    }

    @Test
    fun `delta and end keep id as stream key and roundtrip`() {
        val delta = StreamChunk.ToolCallDelta(
            id = "chat-tool-1",
            toolNameDelta = "_web",
            inputDelta = """{"q":"x"}""",
        )
        val end = StreamChunk.ToolCallEnd(id = "chat-tool-1")
        assertEquals(delta, roundtrip(delta))
        assertEquals(end, roundtrip(end))
    }

    @Test
    fun `every stream chunk subtype roundtrips`() {
        val chunks: List<StreamChunk> = listOf(
            StreamChunk.TextStart("t1"),
            StreamChunk.TextDelta("t1", "hi"),
            StreamChunk.TextEnd("t1"),
            StreamChunk.ReasoningStart("r1"),
            StreamChunk.ReasoningDelta("r1", "think"),
            StreamChunk.ReasoningEnd("r1"),
            StreamChunk.ToolCallStart("s1", "search", toolCallId = "real"),
            StreamChunk.ToolCallIdAssigned("s1", "real"),
            StreamChunk.ToolCallDelta("s1", "_web", "{}"),
            StreamChunk.ToolCallEnd("s1"),
            StreamChunk.ServerToolStart("srv", "web_search"),
            StreamChunk.ServerToolInputDelta("srv", "{}"),
            StreamChunk.ServerToolInputEnd("srv"),
            StreamChunk.ServerToolEnd("srv", status = ServerToolStatus.COMPLETED),
            StreamChunk.ImageStart("i1"),
            StreamChunk.ImageDelta("i1", "QUJD"),
            StreamChunk.ImageSnapshot("i1", "QUJD"),
            StreamChunk.ImageEnd("i1"),
            StreamChunk.Annotations(
                listOf(UIMessageAnnotation.UrlCitation(title = "t", url = "u"))
            ),
            StreamChunk.Usage(TokenUsage(promptTokens = 1, completionTokens = 2)),
            StreamChunk.Finish("stop", "resp-1", "model-1"),
        )
        chunks.forEach { chunk ->
            assertEquals("${chunk::class.simpleName} roundtrip", chunk, roundtrip(chunk))
        }
    }

    @Test
    fun `encoded form carries stable serial names`() {
        val encoded = json.encodeToString(
            StreamChunk.serializer(),
            StreamChunk.ToolCallIdAssigned("k", "v"),
        )
        assertTrue(encoded.contains("tool_call_id_assigned"))
        val startEncoded = json.encodeToString(
            StreamChunk.serializer(),
            StreamChunk.ToolCallStart("k", "n", toolCallId = "v"),
        )
        assertTrue(startEncoded.contains("tool_call_start"))
        assertTrue(startEncoded.contains("toolCallId"))
    }
}
