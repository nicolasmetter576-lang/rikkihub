package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses 兼容输入下 function_call 的身份解析。
 *
 * 兼容网关会把 `item_id` 省掉、只发 `call_id` 或 `output_index`，且
 * function_call item 的 `id` 规范上本就可选。账本用 item/call/output-index
 * 三条线索解析同一调用：线索互相矛盾、纯 index 未登记、完全缺身份一律
 * fail-closed；真实 item_id 只在账本还没有时补绑，fallback callId（=itemId）
 * 被迟到真 call_id 顶替仍按冲突拒绝。
 */
class ResponseApiToolIdentityFallbackTest {

    // ---------- call-only（item.id 缺失，call_id 是唯一身份） ----------

    @Test
    fun `call-only added followed by call-id deltas completes as one call`() {
        val chunks = decodeAll(listOf(
            functionCallItem("response.output_item.added",
                id = null, callId = "call_1", name = "search", arguments = "", outputIndex = 0),
            argsDelta(itemId = null, callId = "call_1", delta = "{\"q\":"),
            argsDelta(itemId = null, callId = "call_1", delta = "1}"),
            argsDone(itemId = null, callId = "call_1", arguments = "{\"q\":1}"),
            functionCallItem("response.output_item.done",
                id = null, callId = "call_1", name = "search", arguments = "{\"q\":1}", outputIndex = 0),
        ))

        // item.done 是同值重发 done：幂等，不重复 append 也不重复 End
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(
            "{\"q\":1}",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta },
        )
        assertEquals(listOf("call_1"), chunks.filterIsInstance<StreamChunk.ToolCallEnd>().map { it.id })

        val tool = singleTool(chunks)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":1}", tool.input)
    }

    @Test
    fun `call-only delta and done without any added still materialize the call`() {
        val chunks = decodeAll(listOf(
            argsDelta(itemId = null, callId = "call_1", delta = "{\"q\":1}"),
            argsDone(itemId = null, callId = "call_1", arguments = "{\"q\":1}"),
        ))

        assertEquals(
            listOf(StreamChunk.ToolCallStart("call_1", "")),
            chunks.filterIsInstance<StreamChunk.ToolCallStart>(),
        )
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })

        val tool = singleTool(chunks)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("{\"q\":1}", tool.input)
    }

    @Test
    fun `call-only output_item done alone completes the call with its snapshot`() {
        val chunks = decodeAll(listOf(
            functionCallItem("response.output_item.done",
                id = null, callId = "call_1", name = "search", arguments = "{\"q\":1}"),
        ))

        assertEquals(
            listOf("{\"q\":1}"),
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.inputDelta },
        )
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
        assertEquals("search", singleTool(chunks).toolName)
    }

    // ---------- output_index 作为已登记身份的线索 ----------

    @Test
    fun `output_index routes deltas and done to the added call`() {
        val chunks = decodeAll(listOf(
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "search", arguments = "", outputIndex = 2),
            argsDelta(itemId = null, callId = null, outputIndex = 2, delta = "{\"q\":"),
            argsDelta(itemId = null, callId = null, outputIndex = 2, delta = "1}"),
            argsDone(itemId = null, callId = null, outputIndex = 2, arguments = "{\"q\":1}"),
        ))

        val tool = singleTool(chunks)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":1}", tool.input)
    }

    @Test
    fun `output_index declared on a call-only added also routes later events`() {
        val chunks = decodeAll(listOf(
            functionCallItem("response.output_item.added",
                id = null, callId = "call_1", name = "search", arguments = "", outputIndex = 3),
            argsDelta(itemId = null, callId = null, outputIndex = 3, delta = "{\"q\":1}"),
            argsDone(itemId = null, callId = "call_1", outputIndex = null, arguments = "{\"q\":1}"),
        ))

        val tool = singleTool(chunks)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("{\"q\":1}", tool.input)
    }

    // ---------- 身份补全：delta 先到，added/done 补绑真实 item_id ----------

    @Test
    fun `call-only delta then added binds the real item id and name without a second start`() {
        val chunks = decodeAll(listOf(
            argsDelta(itemId = null, callId = "call_1", delta = "{\"q\":1}"),
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "search",
                arguments = "{\"other\":true}", outputIndex = 0),
            argsDone(itemId = "fc_1", callId = null, arguments = "{\"q\":1}"),
        ))

        // Start 只在首个 delta 发出一次；added 迟到的 name 以 delta 补发，不重发 Start
        assertEquals(
            listOf(StreamChunk.ToolCallStart("call_1", "")),
            chunks.filterIsInstance<StreamChunk.ToolCallStart>(),
        )
        assertEquals(
            listOf("search"),
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.toolNameDelta }
                .filter { it.isNotEmpty() },
        )
        // added 里的 arguments 不回放：delta 已经接管输入
        assertEquals(
            "{\"q\":1}",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta },
        )
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })

        val tool = singleTool(chunks)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":1}", tool.input)
    }

    @Test
    fun `item-id delta before added keeps the same ledger and fills the name once`() {
        val chunks = decodeAll(listOf(
            argsDelta(itemId = "fc_1", callId = null, delta = "{\"q\":1}"),
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = null, name = "search", arguments = ""),
            argsDone(itemId = "fc_1", callId = null, arguments = "{\"q\":1}"),
        ))

        // delta 先建 fallback 身份（callId = itemId），added 复用同一账本
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(
            "{\"q\":1}",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta },
        )
        assertEquals("search", singleTool(chunks).toolName)
    }

    @Test
    fun `late real call id replacing the item-id fallback still fails closed`() {
        val decoder = ResponseApiStreamDecoder()
        accept(decoder, argsDelta(itemId = "fc_1", callId = null, delta = "{\"q\":1}"))

        // fallback callId（=itemId）已随 Start 发射；done 换上不同真 call_id 保持原冲突契约
        val exception = assertThrows(ToolStreamProtocolException::class.java) {
            accept(decoder, functionCallItem("response.output_item.done",
                id = "fc_1", callId = "call_real", name = "a", arguments = "{\"q\":1}"))
        }
        assertEquals("conflicting_call_id", exception.code)
        assertTrue(decoder.onClosed().isEmpty())
    }

    // ---------- 并行调用 ----------

    @Test
    fun `parallel calls with mixed identity spellings stay separate`() {
        val chunks = decodeAll(listOf(
            functionCallItem("response.output_item.added",
                id = "fc_a", callId = "call_a", name = "a", arguments = "", outputIndex = 0),
            functionCallItem("response.output_item.added",
                id = null, callId = "call_b", name = "b", arguments = "", outputIndex = 1),
            argsDelta(itemId = null, callId = null, outputIndex = 0, delta = "{\"x\":1"),
            argsDelta(itemId = null, callId = "call_b", delta = "{\"y\":"),
            argsDelta(itemId = "fc_a", callId = null, delta = "}"),
            argsDelta(itemId = null, callId = "call_b", delta = "2}"),
            argsDone(itemId = null, callId = "call_b", arguments = "{\"y\":2}"),
            argsDone(itemId = "fc_a", callId = null, arguments = "{\"x\":1}"),
        ))

        assertEquals(2, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(2, chunks.count { it is StreamChunk.ToolCallEnd })

        val tools = tools(chunks)
        assertEquals(2, tools.size)
        assertEquals("call_a", tools[0].toolCallId)
        assertEquals("a", tools[0].toolName)
        assertEquals("{\"x\":1}", tools[0].input)
        assertEquals("call_b", tools[1].toolCallId)
        assertEquals("b", tools[1].toolName)
        assertEquals("{\"y\":2}", tools[1].input)
    }

    // ---------- 冲突与缺失身份 fail closed ----------

    @Test
    fun `item and call clues pointing at different calls fail closed`() {
        assertProtocolError("conflicting_item_identity",
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "a", arguments = "", outputIndex = 0),
            functionCallItem("response.output_item.added",
                id = "fc_2", callId = "call_2", name = "b", arguments = "", outputIndex = 1),
            argsDelta(itemId = "fc_1", callId = "call_2", delta = "x"),
        )
    }

    @Test
    fun `item and index clues pointing at different calls fail closed`() {
        assertProtocolError("conflicting_item_identity",
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "a", arguments = "", outputIndex = 0),
            functionCallItem("response.output_item.added",
                id = "fc_2", callId = "call_2", name = "b", arguments = "", outputIndex = 1),
            argsDelta(itemId = "fc_1", callId = null, outputIndex = 1, delta = "x"),
        )
    }

    @Test
    fun `different items sharing one call id are rejected`() {
        assertProtocolError("conflicting_call_id",
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "a", arguments = ""),
            argsDelta(itemId = "fc_2", callId = "call_1", delta = "x"),
        )
    }

    @Test
    fun `item id colliding with another call identity is rejected`() {
        // item_A 的 call_id 是 'item_B'：item_B 随后以自己的 id 出现时，
        // 有效 call 身份（回退 item_id = 'item_B'）已被占用，不得新建第二个账本
        assertProtocolError("conflicting_call_id",
            functionCallItem("response.output_item.added",
                id = "item_A", callId = "item_B", name = "a", arguments = ""),
            functionCallItem("response.output_item.added",
                id = "item_B", callId = null, name = "b", arguments = ""),
        )
    }

    @Test
    fun `known item or index keeps wire identity when item name equals another call id`() {
        // item_id and call_id are separate namespaces. A missing call_id is not a new fallback claim.
        for (itemInitiallyPresent in listOf(true, false)) {
            val chunks = decodeAll(listOf(
                functionCallItem("response.output_item.added", "item_A", "item_B", "a", "", 0),
                functionCallItem("response.output_item.added", if (itemInitiallyPresent) "item_B" else null,
                    "call_C", "b", "", 1),
                argsDelta("item_A", null, 0, "{}"),
                argsDelta("item_B", null, 1, "{}"),
                argsDone("item_A", null, 0, "{}"),
                argsDone("item_B", null, 1, "{}"),
                completedResponse(),
            ))
            val calls = tools(chunks)
            assertEquals(listOf("item_B", "call_C"), calls.map { it.toolCallId })
            assertEquals(listOf("a", "b"), calls.map { it.toolName })
            assertEquals(listOf("{}", "{}"), calls.map { it.input })
        }
    }

    @Test
    fun `call id colliding with an existing fallback identity is rejected`() {
        // 反序：item_B 先以 fallback 身份（callId = item_B）登记，item_A 再用
        // call_id='item_B' 加入，两个真实 item 共享同一 wire 调用身份
        assertProtocolError("conflicting_call_id",
            functionCallItem("response.output_item.added",
                id = "item_B", callId = null, name = "b", arguments = ""),
            functionCallItem("response.output_item.added",
                id = "item_A", callId = "item_B", name = "a", arguments = ""),
        )
    }

    @Test
    fun `fallback identity collision is also rejected on delta events`() {
        assertProtocolError("conflicting_call_id",
            functionCallItem("response.output_item.added",
                id = "item_A", callId = "item_B", name = "a", arguments = ""),
            argsDelta(itemId = "item_B", callId = null, delta = "x"),
        )
    }

    @Test
    fun `different output indexes claiming one call are rejected`() {
        assertProtocolError("conflicting_output_index",
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "a", arguments = "", outputIndex = 0),
            argsDelta(itemId = null, callId = "call_1", outputIndex = 1, delta = "x"),
        )
    }

    @Test
    fun `delta without any identity is rejected`() {
        assertProtocolError("missing_item_id",
            argsDelta(itemId = null, callId = null, delta = "{\"q\":1}"),
        )
    }

    @Test
    fun `blank item id is rejected even when a call id is present`() {
        assertProtocolError("missing_item_id",
            argsDelta(itemId = " ", callId = "call_1", delta = "{\"q\":1}"),
        )
    }

    @Test
    fun `unknown output index alone cannot create a call`() {
        assertProtocolError("missing_item_id",
            argsDelta(itemId = null, callId = null, outputIndex = 4, delta = "{\"q\":1}"),
        )
    }

    @Test
    fun `added without any identity is rejected`() {
        assertProtocolError("missing_item_id",
            functionCallItem("response.output_item.added",
                id = null, callId = null, name = "a", arguments = ""),
        )
    }

    @Test
    fun `negative output index is rejected`() {
        assertProtocolError("invalid_output_index",
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "a", arguments = "", outputIndex = -1),
        )
    }

    // ---------- 空 delta：no-op 但不吞身份错误 --------

    @Test
    fun `empty delta without identity is still rejected`() {
        assertProtocolError("missing_item_id",
            argsDelta(itemId = null, callId = null, delta = ""),
        )
    }

    @Test
    fun `empty delta with blank item id is still rejected`() {
        assertProtocolError("missing_item_id",
            argsDelta(itemId = " ", callId = "call_1", delta = ""),
        )
    }

    @Test
    fun `empty delta with negative output index is still rejected`() {
        assertProtocolError("invalid_output_index",
            argsDelta(itemId = null, callId = "call_1", outputIndex = -1, delta = ""),
        )
    }

    @Test
    fun `empty delta with a call id is a no-op`() {
        val chunks = decodeAll(listOf(
            argsDelta(itemId = null, callId = "call_1", delta = ""),
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "search", arguments = ""),
            argsDelta(itemId = "fc_1", callId = null, delta = "{\"q\":1}"),
            argsDone(itemId = "fc_1", callId = null, arguments = "{\"q\":1}"),
        ))

        // 合法空 delta 不物化调用：唯一的 Start 来自首个非空 delta，名字仍是 added 补的
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        val tool = singleTool(chunks)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":1}", tool.input)
    }

    @Test
    fun `empty delta on a registered output index is a no-op`() {
        val chunks = decodeAll(listOf(
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "search", arguments = "", outputIndex = 0),
            argsDelta(itemId = null, callId = null, outputIndex = 0, delta = ""),
            argsDelta(itemId = null, callId = null, outputIndex = 0, delta = "{\"q\":1}"),
            argsDone(itemId = null, callId = null, outputIndex = 0, arguments = "{\"q\":1}"),
        ))

        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals("{\"q\":1}", singleTool(chunks).input)
    }

    @Test
    fun `empty delta with an unknown item id does not materialize a call`() {
        val chunks = decodeAll(listOf(
            argsDelta(itemId = "fc_ghost", callId = null, delta = ""),
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "search", arguments = ""),
            argsDelta(itemId = "fc_1", callId = null, delta = "{\"q\":1}"),
            argsDone(itemId = "fc_1", callId = null, arguments = "{\"q\":1}"),
            completedResponse(),
        ))

        // fc_ghost 空 delta 没建账本：finish 只收尾真实调用，消息里只有一个工具 part
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
        assertEquals("{\"q\":1}", singleTool(chunks).input)
    }

    // ---------- 幂等、收尾与异常 ----------

    @Test
    fun `repeated done across identity spellings appends and ends once`() {
        val chunks = decodeAll(listOf(
            functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "search", arguments = "", outputIndex = 0),
            argsDone(itemId = "fc_1", callId = null, arguments = "{\"q\":1}"),
            argsDone(itemId = null, callId = "call_1", arguments = "{\"q\":1}"),
            functionCallItem("response.output_item.done",
                id = "fc_1", callId = "call_1", name = "search", arguments = "{\"q\":1}", outputIndex = 0),
        ))

        assertEquals(1, chunks.count { it is StreamChunk.ToolCallDelta })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
        assertEquals("{\"q\":1}", singleTool(chunks).input)
    }

    @Test
    fun `repeated done with conflicting arguments still fails across aliases`() {
        val decoder = ResponseApiStreamDecoder()
        accept(decoder, functionCallItem("response.output_item.added",
            id = "fc_1", callId = "call_1", name = "a", arguments = ""))
        accept(decoder, argsDone(itemId = "fc_1", callId = null, arguments = "{\"q\":1}"))

        assertThrows(ToolStreamProtocolException::class.java) {
            accept(decoder, argsDone(itemId = null, callId = "call_1", arguments = "{\"q\":2}"))
        }
    }

    @Test
    fun `onClosed flushes each ledger exactly once including call-only calls`() {
        val decoder = ResponseApiStreamDecoder()
        val streamed = buildList {
            addAll(accept(decoder, functionCallItem("response.output_item.added",
                id = "fc_1", callId = "call_1", name = "a", arguments = "{\"p\":1}")))
            addAll(accept(decoder, argsDelta(itemId = null, callId = "call_2", delta = "\"x\"")))
        }
        val closed = decoder.onClosed()

        // 正常收尾：两个账本（一个 item-bound、一个 call-only）各发一次 End；
        // call_1 只登记了 initial 快照，在 finish 一次性 flush
        val all = streamed + closed
        assertEquals(2, all.filterIsInstance<StreamChunk.ToolCallEnd>().size)
        assertEquals(
            setOf("call_1", "call_2"),
            all.filterIsInstance<StreamChunk.ToolCallEnd>().map { it.id }.toSet(),
        )
        assertEquals(
            "{\"p\":1}",
            closed.filterIsInstance<StreamChunk.ToolCallDelta>().single().inputDelta,
        )
        assertTrue(all.last() is StreamChunk.Finish)
        assertTrue(decoder.onClosed().isEmpty())

        val tools = tools(all)
        assertEquals(2, tools.size)
        assertEquals("{\"p\":1}", tools.single { it.toolCallId == "call_1" }.input)
        assertEquals("\"x\"", tools.single { it.toolCallId == "call_2" }.input)
    }

    @Test
    fun `protocol error aborts the stream and onClosed does not flush partial calls`() {
        val decoder = ResponseApiStreamDecoder()
        accept(decoder, functionCallItem("response.output_item.added",
            id = "fc_1", callId = "call_1", name = "a", arguments = "{\"p\":1}"))

        assertThrows(ToolStreamProtocolException::class.java) {
            accept(decoder, argsDelta(itemId = "fc_1", callId = "call_other", delta = "x"))
        }
        // abort 后 onClosed 不得把半成品调用 flush 成成功
        assertTrue(decoder.onClosed().isEmpty())
    }

    // ---------- helpers ----------

    @Test
    fun `malformed JSON aborts before onClosed can flush provisional calls`() {
        val decoder = ResponseApiStreamDecoder()
        accept(decoder, functionCallItem("response.output_item.added", null, "call_1", "a", "{}"))
        org.junit.Assert.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            decoder.accept(SseEvent(event = "response.function_call_arguments.delta", data = "{"))
        }
        assertTrue(decoder.onClosed().isEmpty())
    }

    private fun accept(decoder: ResponseApiStreamDecoder, payload: JsonObject): List<StreamChunk> =
        decoder.accept(SseEvent(
            event = payload["type"]?.jsonPrimitive?.content,
            data = json.encodeToString(payload),
        )).chunks

    private fun decodeAll(payloads: List<JsonObject>): List<StreamChunk> {
        val decoder = ResponseApiStreamDecoder()
        return payloads.flatMap { accept(decoder, it) }
    }

    private fun assertProtocolError(code: String, vararg payloads: JsonObject) {
        val decoder = ResponseApiStreamDecoder()
        var thrown: ToolStreamProtocolException? = null
        try {
            payloads.forEach { accept(decoder, it) }
        } catch (e: ToolStreamProtocolException) {
            thrown = e
        }
        assertEquals(code, thrown?.code)
        assertTrue(decoder.onClosed().isEmpty())
    }

    private fun tools(chunks: List<StreamChunk>): List<UIMessagePart.Tool> {
        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { acc, chunk ->
            handler.handle(acc, chunk)
        }
        return messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
    }

    private fun singleTool(chunks: List<StreamChunk>): UIMessagePart.Tool =
        tools(chunks).single()

    private fun functionCallItem(
        type: String,
        id: String?,
        callId: String?,
        name: String,
        arguments: String,
        outputIndex: Int? = null,
    ): JsonObject = buildJsonObject {
        put("type", type)
        outputIndex?.let { put("output_index", it) }
        put("item", buildJsonObject {
            put("type", "function_call")
            id?.let { put("id", it) }
            callId?.let { put("call_id", it) }
            put("name", name)
            put("arguments", arguments)
        })
    }

    private fun argsDelta(
        itemId: String?,
        callId: String?,
        outputIndex: Int? = null,
        delta: String,
    ): JsonObject = buildJsonObject {
        put("type", "response.function_call_arguments.delta")
        itemId?.let { put("item_id", it) }
        callId?.let { put("call_id", it) }
        outputIndex?.let { put("output_index", it) }
        put("delta", delta)
    }

    private fun argsDone(
        itemId: String?,
        callId: String?,
        outputIndex: Int? = null,
        arguments: String,
    ): JsonObject = buildJsonObject {
        put("type", "response.function_call_arguments.done")
        itemId?.let { put("item_id", it) }
        callId?.let { put("call_id", it) }
        outputIndex?.let { put("output_index", it) }
        put("arguments", arguments)
    }

    private fun completedResponse(): JsonObject = buildJsonObject {
        put("type", "response.completed")
        put("response", buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
        })
    }
}
