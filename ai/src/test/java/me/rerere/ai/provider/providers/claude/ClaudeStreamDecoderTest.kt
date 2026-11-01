package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeStreamDecoderTest {
    @Test
    fun `initial input snapshot flushes once at block stop when no delta arrives`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("content_block_start", blockStart(0, toolUse(
                id = "call_1",
                name = "write",
                input = buildJsonObject { put("path", "a.txt") },
            ))),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
        ))

        assertEquals(
            listOf(
                StreamChunk.ToolCallStart("call_1", "write"),
                StreamChunk.ToolCallDelta("call_1", inputDelta = "{\"path\":\"a.txt\"}"),
                StreamChunk.ToolCallEnd("call_1"),
            ),
            chunks,
        )
    }

    @Test
    fun `empty input json delta does not drop the initial snapshot`() {
        // F1 回归锚点：空 partial_json 是 no-op，不能把 start 里的完整参数丢掉
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("content_block_start", blockStart(0, toolUse(
                id = "call_1",
                name = "write",
                input = buildJsonObject { put("path", "a.txt") },
            ))),
            sse("content_block_delta", deltaEvent(0, buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "")
            })),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
        ))

        assertEquals(
            listOf(
                StreamChunk.ToolCallStart("call_1", "write"),
                StreamChunk.ToolCallDelta("call_1", inputDelta = "{\"path\":\"a.txt\"}"),
                StreamChunk.ToolCallEnd("call_1"),
            ),
            chunks,
        )
    }

    @Test
    fun `missing partial json field does not drop the initial snapshot`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("content_block_start", blockStart(0, toolUse(
                id = "call_1",
                name = "write",
                input = buildJsonObject { put("path", "a.txt") },
            ))),
            sse("content_block_delta", deltaEvent(0, buildJsonObject {
                put("type", "input_json_delta")
            })),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
        ))

        assertEquals(
            "{\"path\":\"a.txt\"}",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().inputDelta,
        )
    }

    @Test
    fun `real delta takes over and the initial snapshot is not emitted`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("content_block_start", blockStart(0, toolUse(
                id = "call_1",
                name = "write",
                input = buildJsonObject { put("path", "a.txt") },
            ))),
            sse("content_block_delta", deltaEvent(0, buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "{\"path\":\"b.txt\"}")
            })),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
        ))

        assertEquals(
            listOf("{\"path\":\"b.txt\"}"),
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.inputDelta },
        )
    }

    @Test
    fun `raw deltas are emitted verbatim without deduplication`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("content_block_start", blockStart(0, toolUse(id = "call_1", name = "write"))),
            sse("content_block_delta", deltaEvent(0, buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "{\"a\":1}")
            })),
            // 合法重复片段必须原样保留，去重会破坏参数
            sse("content_block_delta", deltaEvent(0, buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "{\"a\":1}")
            })),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
        ))

        assertEquals(
            "{\"a\":1}{\"a\":1}",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
                .joinToString("") { it.inputDelta },
        )
    }

    @Test
    fun `initial only block flushes once at message stop`() {
        val decoder = ClaudeStreamDecoder()
        val result = decoder.accept(sse("content_block_start", blockStart(0, toolUse(
            id = "call_1",
            name = "write",
            input = buildJsonObject { put("path", "a.txt") },
        ))))
        assertEquals(listOf(StreamChunk.ToolCallStart("call_1", "write")), result.chunks)

        val stop = decoder.accept(sse("message_stop", buildJsonObject {}))
        assertTrue(stop.completed)
        assertEquals(
            listOf(
                StreamChunk.ToolCallDelta("call_1", inputDelta = "{\"path\":\"a.txt\"}"),
                StreamChunk.ToolCallEnd("call_1"),
            ),
            stop.chunks.filterIsInstance<StreamChunk.ToolCallDelta>() +
                stop.chunks.filterIsInstance<StreamChunk.ToolCallEnd>(),
        )
        assertEquals(1, stop.chunks.count { it is StreamChunk.Finish })
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `block stop and message stop do not double flush the snapshot`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("content_block_start", blockStart(0, toolUse(
                id = "call_1",
                name = "write",
                input = buildJsonObject { put("path", "a.txt") },
            ))),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
            sse("message_stop", buildJsonObject {}),
        ))

        assertEquals(1, chunks.count { it is StreamChunk.ToolCallDelta })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
        assertEquals(1, chunks.count { it is StreamChunk.Finish })
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `two blank or absent id blocks stay isolated`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("message_start", buildJsonObject {
                put("message", buildJsonObject {
                    put("id", "msg_1")
                    put("model", "claude-test")
                })
            }),
            // 空 id 和缺失 id 都是缺失语义，各自分配独立身份
            sse("content_block_start", blockStart(0, toolUse(
                id = "",
                name = "tool_a",
                input = buildJsonObject { put("a", 1) },
            ))),
            sse("content_block_start", blockStart(1, toolUse(
                id = null,
                name = "tool_b",
                input = buildJsonObject { put("b", 2) },
            ))),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
            sse("content_block_stop", buildJsonObject { put("index", 1) }),
            sse("message_stop", buildJsonObject {}),
        ))

        val starts = chunks.filterIsInstance<StreamChunk.ToolCallStart>()
        val deltas = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        assertEquals(2, starts.size)
        assertEquals(2, starts.map { it.id }.toSet().size)
        assertEquals("msg_1:block-0", starts[0].id)
        assertEquals("msg_1:block-1", starts[1].id)
        assertEquals("tool_a", starts[0].toolName)
        assertEquals("tool_b", starts[1].toolName)
        // 参数按各自身份发射，互不串
        assertEquals("{\"a\":1}", deltas.single { it.id == "msg_1:block-0" }.inputDelta)
        assertEquals("{\"b\":2}", deltas.single { it.id == "msg_1:block-1" }.inputDelta)
    }

    @Test
    fun `delta for unknown block index fails`() {
        val decoder = ClaudeStreamDecoder()
        assertThrows(IllegalStateException::class.java) {
            decoder.accept(sse("content_block_delta", deltaEvent(5, buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "{}")
            })))
        }
    }

    @Test
    fun `error event aborts without flushing a fake success`() {
        val decoder = ClaudeStreamDecoder()
        decoder.accept(sse("content_block_start", blockStart(0, toolUse(
            id = "call_1",
            name = "write",
            input = buildJsonObject { put("path", "a.txt") },
        ))))

        assertThrows(Throwable::class.java) {
            decoder.accept(SseEvent(event = "error", data = json.encodeToString(buildJsonObject {
                put("type", "error")
                put("error", buildJsonObject {
                    put("type", "api_error")
                    put("message", "overloaded")
                })
            })))
        }

        // 中止后不能再借 onClosed 把 initial 快照 flush 成成功调用
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `server tool input deltas stay verbatim and empty delta is a no-op`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            sse("content_block_start", blockStart(0, buildJsonObject {
                put("type", "server_tool_use")
                put("id", "srvtoolu_1")
                put("name", "web_search")
                put("input", buildJsonObject {})
            })),
            sse("content_block_delta", deltaEvent(0, buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "")
            })),
            sse("content_block_delta", deltaEvent(0, buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "{\"query\":\"Kotlin\"}")
            })),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
        ))

        assertEquals(
            "{\"query\":\"Kotlin\"}",
            chunks.filterIsInstance<StreamChunk.ServerToolInputDelta>()
                .joinToString("") { it.inputDelta },
        )
        assertEquals(1, chunks.count { it is StreamChunk.ServerToolInputEnd })
    }

    private fun decodeAll(decoder: ClaudeStreamDecoder, events: List<SseEvent>): List<StreamChunk> =
        events.flatMap { decoder.accept(it).chunks }

    private fun sse(event: String, payload: JsonObject): SseEvent =
        SseEvent(event = event, data = json.encodeToString(payload))

    private fun blockStart(index: Int, contentBlock: JsonObject): JsonObject = buildJsonObject {
        put("index", index)
        put("content_block", contentBlock)
    }

    private fun deltaEvent(index: Int, delta: JsonObject): JsonObject = buildJsonObject {
        put("index", index)
        put("delta", delta)
    }

    private fun toolUse(
        id: String? = "call_1",
        name: String,
        input: JsonObject = buildJsonObject {},
    ): JsonObject = buildJsonObject {
        put("type", "tool_use")
        if (id != null) put("id", id)
        put("name", name)
        put("input", input)
    }
}
