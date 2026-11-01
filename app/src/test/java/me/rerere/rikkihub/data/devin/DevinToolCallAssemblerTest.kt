package me.rerere.rikkihub.data.devin

import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.providers.devin.DevinToolCallDelta
import me.rerere.ai.provider.providers.devin.buildDevinMessages
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DevinToolCallAssembler] + 真实 [StreamChunkHandler] 的离线回归.
 *
 * 覆盖 Devin 契约:
 * - `N → 空名 → N` 不能翻倍 (空名不清名称状态);
 * - 名称增量由共享 ToolNameResolver 决定 (纯片段拼接/累计快照/注册表消歧);
 * - 参数逐字追加不去重;
 * - 调用身份按 wire id 区分 (ChatToolCall 没有 index): 新 id 开新调用,
 *   已知 id 回到原调用, 不带 id 的片段接最近的调用;
 * - 迟到真实 id (最近调用尚无真实 id) 经 ToolCallIdAssigned 就地补绑, 不拆调用、
 *   不丢参数;
 * - arguments 为空时用 invalid_json_str;
 * - finish 幂等, End 前 flush 名称尾部.
 */
class DevinToolCallAssemblerTest {
    private val model = Model(modelId = "swe-2")

    private fun delta(
        id: String = "",
        name: String = "",
        arguments: String = "",
        invalidJsonStr: String = "",
    ) = DevinToolCallDelta(
        id = id,
        name = name,
        arguments = arguments,
        invalidJsonStr = invalidJsonStr,
    )

    /** 驱动 assembler + 真实 handler, 返回 (全部 chunk, 末尾消息) */
    private fun drive(
        registry: Set<String>,
        frames: List<List<DevinToolCallDelta>>,
        finishTimes: Int = 1,
    ): Pair<List<StreamChunk>, UIMessage> {
        val assembler = DevinToolCallAssembler(registry)
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("go"))
        val chunks = mutableListOf<StreamChunk>()
        for (frame in frames) {
            for (chunk in assembler.onDeltas(frame)) {
                chunks += chunk
                messages = handler.handle(messages, chunk)
            }
        }
        repeat(finishTimes) {
            for (chunk in assembler.finish()) {
                chunks += chunk
                messages = handler.handle(messages, chunk)
            }
        }
        return chunks to messages.last()
    }

    private fun UIMessage.tools() = parts.filterIsInstance<UIMessagePart.Tool>()

    // ====================== 名称语义 ======================

    @Test
    fun `blank name frame does not reset accumulated name`() {
        // 线上翻倍序列: N → 空名 → N. 空帧既不清累计名也不重新发射,
        // 最终必须是单份 N 而不是 N+N.
        val (chunks, message) = drive(
            registry = setOf("workspace_write_file"),
            frames = listOf(
                listOf(delta(id = "c1", name = "workspace_write_file", arguments = """{"path":""")),
                listOf(delta(id = "c1")),
                listOf(delta(id = "c1", name = "workspace_write_file", arguments = """"a.txt"}""")),
            ),
        )
        val tool = message.tools().single()
        assertEquals("workspace_write_file", tool.toolName)
        assertEquals("""{"path":"a.txt"}""", tool.input)
        // 名称增量在整个序列中只发出一次 (首帧), 重发的完整名不再追加
        val namePieces = chunks.map {
            when (it) {
                is StreamChunk.ToolCallStart -> it.toolName
                is StreamChunk.ToolCallDelta -> it.toolNameDelta
                else -> ""
            }
        }.joinToString("")
        assertEquals("workspace_write_file", namePieces)
    }

    @Test
    fun `blank name frame emits no chunks`() {
        val assembler = DevinToolCallAssembler(setOf("n"))
        assembler.onDeltas(listOf(delta(id = "c1", name = "n")))
        val chunks = assembler.onDeltas(listOf(delta(id = "c1")))
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `true name fragments concatenate verbatim`() {
        val (_, message) = drive(
            registry = setOf("search_web"),
            frames = listOf(
                listOf(delta(id = "c1", name = "search")),
                listOf(delta(id = "c1", name = "_web", arguments = "{}")),
            ),
        )
        assertEquals("search_web", message.tools().single().toolName)
    }

    @Test
    fun `cumulative name snapshot resolves to suffix only`() {
        val (_, message) = drive(
            registry = setOf("search_web"),
            frames = listOf(
                listOf(delta(id = "c1", name = "search")),
                listOf(delta(id = "c1", name = "search_web", arguments = "{}")),
            ),
        )
        assertEquals("search_web", message.tools().single().toolName)
    }

    @Test
    fun `registry resolves foo plus foo into foofoo`() {
        // 注册表证明是分片而非重发
        val (_, message) = drive(
            registry = setOf("foofoo"),
            frames = listOf(
                listOf(delta(id = "c1", name = "foo")),
                listOf(delta(id = "c1", name = "foo", arguments = "{}")),
            ),
        )
        assertEquals("foofoo", message.tools().single().toolName)
    }

    @Test
    fun `terminal ambiguous name fails closed`() {
        // {foo, foofoo} + foo→foo: 终态仍有两个完整候选, 按共享 resolver
        // 契约, 必须是协议错误而不是猜短名.
        assertThrows(ToolStreamProtocolException::class.java) {
            drive(
                registry = setOf("foo", "foofoo"),
                frames = listOf(
                    listOf(delta(id = "c1", name = "foo")),
                    listOf(delta(id = "c1", name = "foo", arguments = "{}")),
                ),
            )
        }
    }

    @Test
    fun `later fragment disambiguates to foofoobar`() {
        // {foo, foofoobar} + foo→foo→bar: 第二帧时歧义, 第三帧后唯一解释
        // 是 foofoobar, resolver 必须能回溯而不是提前丢分支.
        val (_, message) = drive(
            registry = setOf("foo", "foofoobar"),
            frames = listOf(
                listOf(delta(id = "c1", name = "foo")),
                listOf(delta(id = "c1", name = "foo")),
                listOf(delta(id = "c1", name = "bar", arguments = "{}")),
            ),
        )
        assertEquals("foofoobar", message.tools().single().toolName)
    }

    // ====================== 参数 ======================

    @Test
    fun `identical argument fragments are never deduplicated`() {
        val (_, message) = drive(
            registry = setOf("echo"),
            frames = listOf(
                listOf(delta(id = "c1", name = "echo", arguments = """{"a":"x"""")),
                // 合法重复片段必须原样追加
                listOf(delta(id = "c1", arguments = """{"a":"x"""")),
                listOf(delta(id = "c1", arguments = "}")),
            ),
        )
        assertEquals("""{"a":"x"{"a":"x"}""", message.tools().single().input)
    }

    @Test
    fun `first delta may carry arguments alongside start`() {
        // 首帧同时携带参数: 参数不得因 Start 分支而丢失
        val (_, message) = drive(
            registry = setOf("echo"),
            frames = listOf(
                listOf(delta(id = "c1", name = "echo", arguments = """{"a":""")),
                listOf(delta(id = "c1", arguments = "1}")),
            ),
        )
        assertEquals("""{"a":1}""", message.tools().single().input)
    }

    // ====================== 并行调用 (按 id 区分) ======================

    @Test
    fun `parallel calls to different tools stay separate`() {
        // 线上事故: 旧实现把 field 4 当 index, 两个调用落进同一槽位,
        // 名字拼成 subagentworkspace_read_file、参数拼成 {A}{B}.
        val (_, message) = drive(
            registry = setOf("subagent", "workspace_read_file"),
            frames = listOf(
                listOf(delta(id = "call_f3a8", name = "subagent", arguments = """{"task":"x"}""")),
                listOf(delta(id = "call_4ea7", name = "workspace_read_file", arguments = """{"path":"a"}""")),
            ),
        )
        val tools = message.tools()
        assertEquals(listOf("subagent", "workspace_read_file"), tools.map { it.toolName })
        assertEquals(listOf("call_f3a8", "call_4ea7"), tools.map { it.toolCallId })
        assertEquals(listOf("""{"task":"x"}""", """{"path":"a"}"""), tools.map { it.input })
    }

    @Test
    fun `new id opens a new call for the same tool`() {
        // 同一工具并行两次: 名字相同, 靠新 id 区分; 参数各归各的,
        // 不再拼成 {A}{B} 等执行端拆分.
        val (chunks, message) = drive(
            registry = setOf("workspace_read_file"),
            frames = listOf(
                listOf(delta(id = "r1", name = "workspace_read_file", arguments = """{"path":""")),
                listOf(delta(arguments = """"a"}""")),
                listOf(delta(id = "r2", name = "workspace_read_file", arguments = """{"path":""")),
                listOf(delta(arguments = """"b"}""")),
            ),
        )
        assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallIdAssigned>().isEmpty())
        val tools = message.tools()
        assertEquals(listOf("r1", "r2"), tools.map { it.toolCallId })
        assertEquals(listOf("workspace_read_file", "workspace_read_file"), tools.map { it.toolName })
        assertEquals(listOf("""{"path":"a"}""", """{"path":"b"}"""), tools.map { it.input })
        assertEquals(
            listOf("devin-tool-0", "devin-tool-1"),
            chunks.filterIsInstance<StreamChunk.ToolCallEnd>().map { it.id },
        )
    }

    @Test
    fun `known id routes back to its own call`() {
        val (_, message) = drive(
            registry = setOf("read_file", "write_file"),
            frames = listOf(
                listOf(
                    delta(id = "c0", name = "read", arguments = """{"p":"a"""),
                    delta(id = "c1", name = "write", arguments = """{"p":"b"""),
                ),
                listOf(
                    // c0 累计快照重发; c1 继续分片
                    delta(id = "c0", name = "read_file", arguments = "\"}"),
                    delta(id = "c1", name = "_file", arguments = "\"}"),
                ),
            ),
        )
        val tools = message.tools()
        assertEquals(2, tools.size)
        assertEquals(listOf("c0", "c1"), tools.map { it.toolCallId })
        assertEquals(listOf("read_file", "write_file"), tools.map { it.toolName })
        assertEquals(listOf("""{"p":"a"}""", """{"p":"b"}"""), tools.map { it.input })
    }

    @Test
    fun `too many calls in one response fails closed`() {
        val e = assertThrows(ToolStreamProtocolException::class.java) {
            drive(
                registry = setOf("n"),
                frames = (0..128).map { listOf(delta(id = "c$it", name = "n", arguments = "{}")) },
            )
        }
        assertEquals("devin_tool_calls_too_many", e.code)
    }

    // ====================== 参数来源 ======================

    @Test
    fun `invalid json str stands in for empty arguments`() {
        // 模型参数不是合法 JSON 时上游只发 field 4: 原文必须进 input,
        // 由执行端回灌 invalid_arguments, 而不是按空参数 {} 执行.
        val (_, message) = drive(
            registry = setOf("workspace_shell"),
            frames = listOf(
                listOf(delta(id = "c1", name = "workspace_shell", invalidJsonStr = """{"command":"ls""")),
            ),
        )
        assertEquals("""{"command":"ls""", message.tools().single().input)
    }

    @Test
    fun `arguments json wins over invalid json str`() {
        val (_, message) = drive(
            registry = setOf("n"),
            frames = listOf(
                listOf(delta(id = "c1", name = "n", arguments = "{}", invalidJsonStr = "raw")),
            ),
        )
        assertEquals("{}", message.tools().single().input)
    }

    // ====================== 身份 ======================

    @Test
    fun `start always carries explicit tool call id`() {
        val (chunks, message) = drive(
            registry = setOf("n"),
            frames = listOf(listOf(delta(name = "n", arguments = "{}"))), // 无 wire id → synthetic
        )
        val start = chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()
        // synthetic 只是临时显示身份, 稳定且非空
        assertEquals("devin-call-0", start.toolCallId)
        assertEquals(start.toolCallId, message.tools().single().toolCallId)
    }

    @Test
    fun `late real id binds in place with full input preserved`() {
        // 三帧: 无 id 首帧(带前半参数) → 名称重发 → 真实 id 迟到(带后半参数).
        // 必须单 part、参数完整、toolCallId 更新为真实值; 且经一次
        // ToolCallIdAssigned(streamKey → real) 而不是新 Start.
        val (chunks, message) = drive(
            registry = setOf("search"),
            frames = listOf(
                listOf(delta(name = "sea", arguments = """{"q":""")),
                listOf(delta(name = "search")),
                listOf(delta(id = "real-9", arguments = "1}")),
            ),
        )

        val assigned = chunks.filterIsInstance<StreamChunk.ToolCallIdAssigned>()
        assertEquals(1, assigned.size)
        assertEquals("devin-tool-0", assigned[0].id)
        assertEquals("real-9", assigned[0].toolCallId)
        // streamKey 全程稳定, 不随 id/outputId 变化
        assertTrue(
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
                .all { it.id == "devin-tool-0" },
        )
        assertEquals(
            listOf("devin-tool-0"),
            chunks.filterIsInstance<StreamChunk.ToolCallEnd>().map { it.id },
        )

        val tool = message.tools().single()
        assertEquals("real-9", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":1}""", tool.input)
    }

    @Test
    fun `late id binds only while the latest call has no real id`() {
        // 无 id 首调用拿到迟到 id r0 后, 下一个新 id rX 是新调用.
        val (_, message) = drive(
            registry = setOf("n", "m"),
            frames = listOf(
                listOf(delta(name = "n")),
                listOf(delta(id = "r0", arguments = "{}")),
                listOf(delta(id = "rX", name = "m", arguments = "{}")),
            ),
        )
        val tools = message.tools()
        assertEquals(listOf("r0", "rX"), tools.map { it.toolCallId })
        assertEquals(listOf("n", "m"), tools.map { it.toolName })
    }

    @Test
    fun `same real id resent is idempotent`() {
        val (chunks, message) = drive(
            registry = setOf("n"),
            frames = listOf(
                listOf(delta(id = "r1", name = "n")),
                listOf(delta(id = "r1", arguments = "{}")),
            ),
        )
        assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallIdAssigned>().isEmpty())
        val tool = message.tools().single()
        assertEquals("r1", tool.toolCallId)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `delta after finish fails closed`() {
        val assembler = DevinToolCallAssembler(setOf("n"))
        assembler.onDeltas(listOf(delta(id = "c1", name = "n")))
        assembler.finish()
        assertThrows(ToolStreamProtocolException::class.java) {
            assembler.onDeltas(listOf(delta(id = "c1", arguments = "{}")))
        }
    }

    // ====================== finish ======================

    @Test
    fun `finish is idempotent and ends each call once`() {
        val (chunks, message) = drive(
            registry = setOf("n"),
            frames = listOf(listOf(delta(id = "c1", name = "n", arguments = "{}"))),
            finishTimes = 2,
        )
        assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
        assertEquals(1, message.tools().size)
    }

    @Test
    fun `finish flushes resolver tail before end`() {
        // {foofoo} + foo→foo: 歧义 (foo 是完整名前缀, foofoo 是累计候选)
        // 推迟到 finish 收敛为唯一注册名 foofoo; 尾部 "foo" 必须经 Delta
        // 先于 End 发射 (而非塞进 End 或丢掉). resolver 只能确认候选∩R,
        // 不会 autocomplete 出注册表之外的字串.
        val assembler = DevinToolCallAssembler(setOf("foofoo"))
        val chunks = mutableListOf<StreamChunk>()
        chunks += assembler.onDeltas(listOf(delta(id = "c1", name = "foo")))
        chunks += assembler.onDeltas(listOf(delta(id = "c1", name = "foo")))
        chunks += assembler.finish()
        val endIndex = chunks.indexOfFirst { it is StreamChunk.ToolCallEnd }
        assertTrue(endIndex > 0)
        assertEquals(endIndex, chunks.lastIndex)
        // finish flush 的尾部增量就是 End 前一个 chunk, 值为延迟确认的 "foo"
        val tail = chunks[endIndex - 1] as StreamChunk.ToolCallDelta
        assertEquals("foo", tail.toolNameDelta)
        // End 之前流出的名称增量拼起来必须恰为完整注册名
        val namePieces = chunks.take(endIndex).map {
            when (it) {
                is StreamChunk.ToolCallStart -> it.toolName
                is StreamChunk.ToolCallDelta -> it.toolNameDelta
                else -> ""
            }
        }.joinToString("")
        assertEquals("foofoo", namePieces)
    }

    @Test
    fun `finish without any registered candidate passes literal through`() {
        // {foofoobar} + foo→foo: 终态候选 {foo, foofoo} 均不在注册表.
        // 契约修正后零候选不再硬失败——literal 逐字名 foofoo 在 End 前
        // flush 透传 (未注册不可执行, 执行端报 not found), 而不是凭空
        // 补出注册名尾巴、也不是协议错误。
        val (_, message) = drive(
            registry = setOf("foofoobar"),
            frames = listOf(
                listOf(delta(id = "c1", name = "foo")),
                listOf(delta(id = "c1", name = "foo", arguments = "{}")),
            ),
        )
        val tool = message.tools().single()
        assertEquals("foofoo", tool.toolName)
        assertEquals("{}", tool.input)
    }

    // ====================== DevinMessages 接线 ======================

    @Test
    fun `buildDevinMessages carries the actual tool table`() {
        val tool = Tool(
            name = "workspace_write_file",
            description = "write",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                    },
                )
            },
            execute = { emptyList() },
        )
        val bundle = buildDevinMessages(listOf(UIMessage.user("hi")), listOf(tool))
        assertEquals(listOf("workspace_write_file"), bundle.tools.map { it.name })
        assertTrue(bundle.tools[0].parameters.toString(Charsets.UTF_8).contains("\"path\""))
    }

    @Test
    fun `assembled call round trips real id into next request history`() {
        // assembler+handler 产出的 part 标记执行后回传: assistant f6 与
        // source=4 结果都必须携带真实 toolCallId, 而不是 synthetic/streamKey.
        // wire 直接发完整注册名: resolver 契约不补全未收到的字符, 只发前缀
        // "sea" 的话终态只会透传字面名 sea (未注册, 执行端报 not found)。
        val (_, message) = drive(
            registry = setOf("search"),
            frames = listOf(
                listOf(delta(name = "search", arguments = """{"q":""")),
                listOf(delta(id = "real-9", arguments = "1}")),
            ),
        )
        val tool = message.tools().single()
        assertNotEquals("devin-tool-0", tool.toolCallId)
        assertNotEquals("devin-call-0", tool.toolCallId)

        val executed = tool.copy(output = listOf(UIMessagePart.Text("done")))
        val history = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed))
        val bundle = buildDevinMessages(
            listOf(UIMessage.user("hi"), history),
            emptyList(),
        )
        val assistant = bundle.prompts.single { it.source == 2 }
        assertEquals("real-9", assistant.toolCalls.single().id)
        assertEquals("search", assistant.toolCalls.single().name)
        assertEquals("""{"q":1}""", assistant.toolCalls.single().arguments)
        val result = bundle.prompts.single { it.source == 4 }
        assertEquals("real-9", result.toolCallId)
        assertEquals("done", result.content)
    }

    @Test
    fun `parallel calls round trip as separate calls and results`() {
        val (_, message) = drive(
            registry = setOf("subagent", "workspace_read_file"),
            frames = listOf(
                listOf(delta(id = "call_a", name = "subagent", arguments = """{"task":"x"}""")),
                listOf(delta(id = "call_b", name = "workspace_read_file", arguments = """{"path":"a"}""")),
            ),
        )
        val executed = message.tools().mapIndexed { i, tool ->
            tool.copy(output = listOf(UIMessagePart.Text("out$i")))
        }
        val history = UIMessage(role = MessageRole.ASSISTANT, parts = executed)
        val bundle = buildDevinMessages(listOf(UIMessage.user("hi"), history), emptyList())
        val assistant = bundle.prompts.single { it.source == 2 }
        assertEquals(listOf("call_a", "call_b"), assistant.toolCalls.map { it.id })
        assertEquals(listOf("subagent", "workspace_read_file"), assistant.toolCalls.map { it.name })
        val results = bundle.prompts.filter { it.source == 4 }
        assertEquals(listOf("call_a", "call_b"), results.map { it.toolCallId })
        assertEquals(listOf("out0", "out1"), results.map { it.content })
    }
}
