package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleStreamDecoderTest {
    private fun decoder() = GoogleStreamDecoder(responseId = "resp", model = "gemini-test")

    @Test
    fun `identical function call resend is idempotent`() {
        val decoder = decoder()
        val call = functionCallPart(id = "call-1", name = "search", args = """{"q":"a"}""")
        val chunks = decodeAll(decoder, listOf(
            frame(call),
            frame(call),
            frame(parts = emptyList(), finishReason = "STOP"),
        ))

        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallDelta })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
        assertEquals("call-1", chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().id)
        assertEquals(
            """{"q":"a"}""",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().inputDelta,
        )
        assertEquals(1, chunks.count { it is StreamChunk.Finish })
    }

    @Test
    fun `absent then blank ids allocate distinct synthetic ids`() {
        val decoder = decoder()
        val chunks = decodeAll(decoder, listOf(
            frame(functionCallPart(id = null, name = "toolA", args = """{"a":1}""")),
            frame(functionCallPart(id = "", name = "toolB", args = """{"b":2}""")),
            frame(parts = emptyList(), finishReason = "STOP"),
        ))

        val starts = chunks.filterIsInstance<StreamChunk.ToolCallStart>()
        // 单一 allocator：absent 与 blank 都归一化到同一分配器，两个调用不能碰撞合并
        assertEquals(listOf("resp:tool-1", "resp:tool-2"), starts.map { it.id })
        assertEquals(listOf("toolA", "toolB"), starts.map { it.toolName })

        val deltas = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        assertEquals("""{"a":1}""", deltas.single { it.id == "resp:tool-1" }.inputDelta)
        assertEquals("""{"b":2}""", deltas.single { it.id == "resp:tool-2" }.inputDelta)
    }

    @Test
    fun `same id different args replaces pending snapshot and emits once at finish`() {
        val decoder = decoder()
        val chunks = decodeAll(decoder, listOf(
            frame(functionCallPart(id = "call-1", name = "search", args = """{"p":1}""")),
            frame(functionCallPart(id = "call-1", name = "search", args = """{"q":2}""")),
            frame(parts = emptyList(), finishReason = "STOP"),
        ))

        // 最终发射的是最后一次完整快照，不是两份拼接也不是字符串差分
        assertEquals(
            listOf("""{"q":2}"""),
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.inputDelta },
        )
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
    }

    @Test
    fun `two absent id calls stay distinct and are never deduplicated`() {
        val decoder = decoder()
        val call = functionCallPart(id = null, name = "save", args = """{"v":1}""")
        val chunks = decodeAll(decoder, listOf(
            frame(call),
            frame(call),
            frame(parts = emptyList(), finishReason = "STOP"),
        ))

        // 无 id 的两次相同调用可能是用户真实需要的两次操作，不能按 name+args 消重
        val starts = chunks.filterIsInstance<StreamChunk.ToolCallStart>()
        assertEquals(2, starts.size)
        assertEquals(2, starts.map { it.id }.toSet().size)
    }

    @Test
    fun `same id with conflicting name fails closed`() {
        val decoder = decoder()
        decoder.accept(frame(functionCallPart(id = "call-1", name = "search", args = """{"q":1}""")))

        val exception = assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(frame(functionCallPart(id = "call-1", name = "delete", args = """{"q":1}""")))
        }
        assertEquals("conflicting_tool_name", exception.code)

        // 协议错误中止后不能再把 pending 快照 flush 成成功
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `interleaved text between calls does not flush pending args early`() {
        val decoder = decoder()
        val chunks = decodeAll(decoder, listOf(
            frame(functionCallPart(id = "call-1", name = "a", args = """{"x":1}""")),
            frame(textPart("working")),
            frame(functionCallPart(id = "call-2", name = "b", args = """{"y":2}""")),
            frame(parts = emptyList(), finishReason = "STOP"),
        ))

        // 工具保持开放到 Finish：End 只在最后，Delta 与对应 End 相邻发射
        val endIndexes = chunks.mapIndexedNotNull { index, chunk ->
            (chunk as? StreamChunk.ToolCallEnd)?.let { index }
        }
        assertEquals(2, endIndexes.size)
        assertTrue(endIndexes.all { it > chunks.indexOfLast { it is StreamChunk.TextDelta } })

        val textIndex = chunks.indexOfFirst { it is StreamChunk.TextDelta }
        val deltaIndexes = chunks.mapIndexedNotNull { index, chunk ->
            (chunk as? StreamChunk.ToolCallDelta)?.let { index }
        }
        // 参数显示推迟到 finish：交错 text 不能提前 flush 参数
        assertTrue(deltaIndexes.all { it > textIndex })
        assertEquals(
            listOf("""{"x":1}""", """{"y":2}"""),
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
                .sortedBy { it.id }
                .map { it.inputDelta },
        )
    }

    @Test
    fun `multiple tools and interleaved text and image keep wire order`() {
        val decoder = decoder()
        val chunks = decodeAll(decoder, listOf(
            frame(functionCallPart(id = "call-1", name = "a", args = """{"x":1}""")),
            frame(textPart("t1")),
            frame(functionCallPart(id = "call-2", name = "b", args = """{"y":2}""")),
            frame(imagePart("aW1hZ2U=")),
            frame(parts = emptyList(), finishReason = "STOP"),
        ))

        val handler = StreamChunkHandler(Model(modelId = "gemini-test"))
        val messages = chunks.fold(listOf(UIMessage.user("hi"))) { acc, chunk ->
            handler.handle(acc, chunk)
        }
        val parts = messages.last().parts

        // part 顺序保持 wire 顺序：Tool, Text, Tool, Image
        assertEquals(
            listOf("Tool", "Text", "Tool", "Image"),
            parts.map { it::class.simpleName },
        )
        val tools = parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf("call-1", "call-2"), tools.map { it.toolCallId })
        assertEquals("""{"x":1}""", tools[0].input)
        assertEquals("""{"y":2}""", tools[1].input)
        assertEquals("t1", parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(
            "data:image/png;base64,aW1hZ2U=",
            parts.filterIsInstance<UIMessagePart.Image>().single().url,
        )
        assertEquals(1, chunks.count { it is StreamChunk.Finish })
    }

    @Test
    fun `server tool call and result pairing preserved while client tool stays open`() {
        val decoder = decoder()
        val chunks = decodeAll(decoder, listOf(
            frame(
                buildJsonObject {
                    put("toolCall", buildJsonObject {
                        put("toolType", "GOOGLE_SEARCH_WEB")
                        put("id", "server-1")
                        put("args", buildJsonObject { put("query", "kotlin") })
                    })
                },
                functionCallPart(id = "call-1", name = "save", args = """{"v":1}"""),
            ),
            frame(buildJsonObject {
                put("toolResponse", buildJsonObject {
                    put("toolType", "GOOGLE_SEARCH_WEB")
                    put("id", "server-1")
                    put("response", buildJsonObject { put("result", "ok") })
                })
            }),
            frame(parts = emptyList(), finishReason = "STOP"),
        ))

        val serverStart = chunks.filterIsInstance<StreamChunk.ServerToolStart>().single()
        val serverEnd = chunks.filterIsInstance<StreamChunk.ServerToolEnd>().single()
        assertEquals("server-1", serverStart.id)
        assertEquals("server-1", serverEnd.id)
        assertEquals(ServerToolStatus.COMPLETED, serverEnd.status)

        // 服务端工具交错不提前关闭客户端调用
        val toolStartIndex = chunks.indexOfFirst { it is StreamChunk.ToolCallStart }
        val toolEndIndex = chunks.indexOfFirst { it is StreamChunk.ToolCallEnd }
        assertTrue(toolStartIndex < chunks.indexOfFirst { it is StreamChunk.ServerToolEnd })
        assertTrue(toolEndIndex > chunks.indexOfFirst { it is StreamChunk.ServerToolEnd })
    }

    @Test
    fun `args stay pending until finish and absent args produce no delta`() {
        val decoder = decoder()
        val earlyChunks = decoder.accept(
            frame(functionCallPart(id = "call-1", name = "noop")),
        ).chunks
        assertTrue(earlyChunks.none { it is StreamChunk.ToolCallDelta })

        val finishChunks = decoder.onClosed()
        assertEquals(listOf(StreamChunk.ToolCallEnd("call-1")), finishChunks.filterIsInstance<StreamChunk.ToolCallEnd>())
        assertTrue(finishChunks.none { it is StreamChunk.ToolCallDelta })
    }

    @Test
    fun `explicit wire id colliding with synthetic id is rejected not merged`() {
        val decoder = decoder()
        // 第一块无 id：allocator 分出确定性合成 id，拿到 Start.id
        val first = decoder.accept(
            frame(functionCallPart(id = null, name = "search", args = """{"q":"orig"}""")),
        ).chunks
        val syntheticId = first.filterIsInstance<StreamChunk.ToolCallStart>().single().id
        assertEquals("resp:tool-1", syntheticId)

        // 显式 wire id 命中该合成 id：identity collision，必须拒绝 ——
        // 不能当作 resend 合并，更不能让 pending args 被覆盖。
        val exception = assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(
                frame(functionCallPart(id = syntheticId, name = "search", args = """{"q":"evil"}""")),
            )
        }
        assertEquals("tool_call_identity_collision", exception.code)

        // 协议错误中止后绝不再 flush：被覆盖风险下的 args（含原值）都不发射
        assertTrue(decoder.onClosed().isEmpty())
        assertTrue(first.none { it is StreamChunk.ToolCallDelta })
    }

    @Test
    fun `wire id equal to pending call merges as resend`() {
        val decoder = decoder()
        // 只用流内 chunks：args 等 finish 才发射，onClosed 会引入合法 flush
        val chunks = listOf(
            frame(functionCallPart(id = "call-1", name = "search", args = """{"p":1}""")),
            frame(functionCallPart(id = "call-1", name = "search", args = """{"p":1}""")),
        ).flatMap { decoder.accept(it).chunks }

        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertTrue(chunks.none { it is StreamChunk.ToolCallDelta })
        assertTrue(chunks.none { it is StreamChunk.ToolCallEnd })
    }

    private fun decodeAll(decoder: GoogleStreamDecoder, events: List<SseEvent>): List<StreamChunk> =
        events.flatMap { decoder.accept(it).chunks } + decoder.onClosed()

    private fun frame(vararg parts: JsonObject, finishReason: String? = null): SseEvent =
        frame(parts.toList(), finishReason)

    private fun frame(parts: List<JsonObject>, finishReason: String? = null): SseEvent = SseEvent(
        data = json.encodeToString(buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    put("content", buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { parts.forEach { add(it) } }
                    })
                    finishReason?.let { put("finishReason", it) }
                    put("index", 0)
                }
            }
        }),
    )

    private fun functionCallPart(id: String?, name: String, args: String? = null): JsonObject =
        buildJsonObject {
            put("functionCall", buildJsonObject {
                id?.let { put("id", it) }
                put("name", name)
                args?.let { put("args", json.parseToJsonElement(it)) }
            })
        }

    private fun textPart(text: String): JsonObject = buildJsonObject { put("text", text) }

    private fun imagePart(base64: String): JsonObject = buildJsonObject {
        put("inlineData", buildJsonObject {
            put("mimeType", "image/png")
            put("data", base64)
        })
    }
}
