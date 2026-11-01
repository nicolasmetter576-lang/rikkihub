package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成式 wire 一致性 oracle（取代旧 FuzzToolNameTest 的「只断言不出
 * 现 N+N」弱条件）。
 *
 * 方法：从一份明确的期望调用列表 [Call] 出发，把同一份调用编码成多种
 * **等价合法** wire 形态（name 首发/重发/累计快照/真分片；id 首发/重发/
 * 空白/迟到；index 显式/单元素缺省），然后对最终 UIMessage 做**精确**
 * 断言——调用数、每个 part 的 toolCallId/toolName/input、part 顺序全部
 * 相等。不支持/非法形态另列 [rejections] 套件断言明确拒绝。
 *
 * 「合法等价形态」的判定只覆盖当前协议契约声明支持的编码；不支持的
 * 歧义形态（缺 index 并行、终态多义名等）不指望解码器猜对，在拒绝
 * 套件里断言 [ToolStreamProtocolException]。
 */
class ToolCallWireConformanceTest {
    private val model = Model(modelId = "m")

    /** 期望的逻辑工具调用：wire 语义上的最终状态。 */
    private data class Call(
        /** wire 上真实非空 id；null = 全程无真实 id（synthetic 显示 id）。 */
        val wireId: String?,
        val index: Int,
        val name: String,
        val args: String,
    )

    private enum class NameEncoding { FIRST_ONLY, RESEND_EVERY, CUMULATIVE, FRAGMENTS, LATE }
    private enum class IdEncoding { FIRST_ONLY, RESEND_EVERY, BLANK_AFTER_FIRST, ALL_BLANK, LATE }
    private enum class IndexEncoding { EXPLICIT, ABSENT_AFTER_FIRST, ALWAYS_ABSENT }

    /**
     * 把 [call] 编码成一个或多个 wire chunk（name/args 各自分片策略）。
     * 返回的元素顺序即 wire chunk 顺序。
     */
    private fun encodeCall(
        call: Call,
        nameEncoding: NameEncoding,
        idEncoding: IdEncoding,
        indexEncoding: IndexEncoding,
        chunkCount: Int,
    ): List<JsonArray> {
        val nameFrames: List<String?> = when (nameEncoding) {
            // 合法分片：按注册表可确定的边界切（首片 + 尾片）
            NameEncoding.FIRST_ONLY -> listOf(call.name, null)
            NameEncoding.RESEND_EVERY -> List(chunkCount) { call.name }
            NameEncoding.CUMULATIVE -> listOf(
                call.name.take(1.coerceAtLeast(call.name.length / 2)),
                call.name,
            )
            NameEncoding.FRAGMENTS -> {
                val mid = call.name.length / 2
                listOf(call.name.substring(0, mid), call.name.substring(mid))
            }
            NameEncoding.LATE -> listOf(null, call.name)
        }
        val argPieces = splitArgs(call.args, chunkCount)
        val frames = mutableListOf<JsonArray>()
        val elements = maxOf(nameFrames.size, argPieces.size)
        for (i in 0 until elements) {
            val emitIndex = when (indexEncoding) {
                IndexEncoding.EXPLICIT -> true
                IndexEncoding.ABSENT_AFTER_FIRST -> i == 0
                IndexEncoding.ALWAYS_ABSENT -> false
            }
            val emitId = when (idEncoding) {
                IdEncoding.FIRST_ONLY -> i == 0 && call.wireId != null
                IdEncoding.RESEND_EVERY -> call.wireId != null
                IdEncoding.BLANK_AFTER_FIRST -> i == 0 || call.wireId != null
                IdEncoding.ALL_BLANK -> true
                IdEncoding.LATE -> i == elements - 1 || call.wireId == null
            }
            val idValue = when (idEncoding) {
                IdEncoding.BLANK_AFTER_FIRST -> if (i == 0) call.wireId else ""
                IdEncoding.ALL_BLANK -> ""
                else -> call.wireId
            }
            frames += buildJsonArray {
                addJsonObject {
                    if (emitIndex) put("index", call.index)
                    if (emitId) put("id", idValue ?: "")
                    put("type", "function")
                    put("function", buildJsonObject {
                        nameFrames.getOrNull(i)?.let { put("name", it) }
                        argPieces.getOrNull(i)?.let { put("arguments", it) }
                    })
                }
            }
        }
        return frames
    }

    private fun splitArgs(args: String, count: Int): List<String> {
        if (count <= 1) return listOf(args)
        val mid = args.length / 2
        return listOf(args.substring(0, mid), args.substring(mid))
    }

    private fun toolCallsEvent(toolCalls: JsonArray): SseEvent = SseEvent(
        data = buildJsonObject {
            put("choices", buildJsonArray {
                addJsonObject {
                    put("delta", buildJsonObject { put("tool_calls", toolCalls) })
                }
            })
        }.toString(),
    )

    private fun finishEvent(): SseEvent = SseEvent(
        data = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
    )

    private fun decodeParts(
        events: List<SseEvent>,
        knownToolNames: Set<String>,
    ): List<UIMessagePart.Tool> {
        val decoder = ChatCompletionsStreamDecoder(knownToolNames)
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("go"))
        events.forEach { event ->
            decoder.accept(event).chunks.forEach { chunk ->
                messages = handler.handle(messages, chunk)
            }
        }
        decoder.onClosed().forEach { chunk ->
            messages = handler.handle(messages, chunk)
        }
        return messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
    }

    /**
     * 单调用全等价编码矩阵：所有「声明支持」的编码形态都必须产出**同一**
     * 精确结果（单 part、wire id 一致、名称全名、参数逐字拼接）。
     * 任何静默的调用分裂、名称翻倍/截断、参数丢失都会让精确断言失败。
     */
    @Test
    fun `single call equivalent encodings all produce identical result`() {
        val call = Call(wireId = "call_A", index = 0, name = "search_web", args = """{"q":"x"}""")
        val registry = setOf("search_web", "read_file")
        var combinations = 0
        for (nameEnc in NameEncoding.entries) {
            for (idEnc in listOf(
                IdEncoding.FIRST_ONLY,
                IdEncoding.RESEND_EVERY,
                IdEncoding.BLANK_AFTER_FIRST,
            )) {
                for (indexEnc in listOf(IndexEncoding.EXPLICIT, IndexEncoding.ABSENT_AFTER_FIRST)) {
                    val frames = encodeCall(call, nameEnc, idEnc, indexEnc, chunkCount = 2)
                    val events = frames.map(::toolCallsEvent) + finishEvent()
                    val tools = decodeParts(events, registry)
                    assertEquals(
                        "name=$nameEnc id=$idEnc idx=$indexEnc must yield exactly one call",
                        1, tools.size,
                    )
                    assertEquals("call_A", tools.single().toolCallId)
                    assertEquals("search_web", tools.single().toolName)
                    assertEquals("""{"q":"x"}""", tools.single().input)
                    combinations++
                }
            }
        }
        assertTrue(combinations >= 10)
    }

    /** 全程无真实 id 的单调用：synthetic 显示 id 稳定非空、调用不分裂。 */
    @Test
    fun `single call without wire id keeps one part with synthetic display id`() {
        val call = Call(wireId = null, index = 0, name = "search_web", args = "{}")
        for (indexEnc in listOf(IndexEncoding.EXPLICIT, IndexEncoding.ALWAYS_ABSENT)) {
            val events = encodeCall(call, NameEncoding.RESEND_EVERY, IdEncoding.FIRST_ONLY, indexEnc, 2)
                .map(::toolCallsEvent) + finishEvent()
            val tools = decodeParts(events, setOf("search_web"))
            assertEquals(1, tools.size)
            assertTrue(tools.single().toolCallId.isNotBlank())
            assertEquals("search_web", tools.single().toolName)
            assertEquals("{}", tools.single().input)
        }
    }

    /** 迟到真实 id 是合法编码：part 就地补绑，最终 toolCallId 为真实 id。 */
    @Test
    fun `late arriving real id is a supported encoding`() {
        val call = Call(wireId = "real_9", index = 0, name = "echo", args = """{"a":1}""")
        for (indexEnc in listOf(IndexEncoding.EXPLICIT, IndexEncoding.ALWAYS_ABSENT)) {
            val events = encodeCall(call, NameEncoding.FRAGMENTS, IdEncoding.LATE, indexEnc, 2)
                .map(::toolCallsEvent) + finishEvent()
            val tools = decodeParts(events, setOf("echo"))
            assertEquals(1, tools.size)
            assertEquals("real_9", tools.single().toolCallId)
            assertEquals("echo", tools.single().toolName)
            assertEquals("""{"a":1}""", tools.single().input)
        }
    }

    /** 并行调用的合法交错编码：两调用名/id/参数严格各自归位。 */
    @Test
    fun `parallel calls interleaved keep exact per-call state`() {
        val a = Call(wireId = "call_0", index = 0, name = "search_web", args = """{"q":0}""")
        val b = Call(wireId = "call_1", index = 1, name = "read_file", args = """{"p":1}""")
        val registry = setOf("search_web", "read_file")
        val events = listOf(
            toolCallsEvent(encodeCall(a, NameEncoding.FIRST_ONLY, IdEncoding.FIRST_ONLY, IndexEncoding.EXPLICIT, 2)[0]),
            toolCallsEvent(encodeCall(b, NameEncoding.FIRST_ONLY, IdEncoding.FIRST_ONLY, IndexEncoding.EXPLICIT, 2)[0]),
            toolCallsEvent(encodeCall(a, NameEncoding.RESEND_EVERY, IdEncoding.BLANK_AFTER_FIRST, IndexEncoding.EXPLICIT, 2)[1]),
            toolCallsEvent(encodeCall(b, NameEncoding.RESEND_EVERY, IdEncoding.BLANK_AFTER_FIRST, IndexEncoding.EXPLICIT, 2)[1]),
            finishEvent(),
        )
        val tools = decodeParts(events, registry)
        assertEquals(2, tools.size)
        assertEquals(listOf("call_0", "call_1"), tools.map { it.toolCallId })
        assertEquals(listOf("search_web", "read_file"), tools.map { it.toolName })
        assertEquals(listOf("""{"q":0}""", """{"p":1}"""), tools.map { it.input })
    }

    /**
     * 声明不支持的 wire 形态必须明确拒绝（协议错误、不产出工具副作用），
     * 而不是猜出一个可能错误的调用去执行。
     */
    @Test
    fun `unsupported or ambiguous encodings fail closed`() {
        val registry = setOf("search_web", "read_file")

        // 同 index 换真实 id
        assertThrows(ToolStreamProtocolException::class.java) {
            decodeParts(
                listOf(
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("index", 0); put("id", "a"); put("type", "function")
                            put("function", buildJsonObject { put("name", "search_web") })
                        }
                    }),
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("index", 0); put("id", "b"); put("type", "function")
                            put("function", buildJsonObject { put("arguments", "{}") })
                        }
                    }),
                    finishEvent(),
                ),
                registry,
            )
        }

        // 同包两个无身份块（缺 index 并行 —— 协议内不可判定）
        assertThrows(ToolStreamProtocolException::class.java) {
            decodeParts(
                listOf(
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject { put("name", "search_web") })
                        }
                        addJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject { put("name", "read_file") })
                        }
                    }),
                    finishEvent(),
                ),
                registry,
            )
        }

        // 多调用进行中时插入无身份块
        assertThrows(ToolStreamProtocolException::class.java) {
            decodeParts(
                listOf(
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("index", 0); put("id", "a"); put("type", "function")
                            put("function", buildJsonObject { put("name", "search_web") })
                        }
                    }),
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("index", 1); put("id", "b"); put("type", "function")
                            put("function", buildJsonObject { put("name", "read_file") })
                        }
                    }),
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject { put("arguments", "{}") })
                        }
                    }),
                    finishEvent(),
                ),
                registry,
            )
        }

        // 终态歧义名 {search_web, search_webx} 下 search_web→search_webx 前缀双解
        assertThrows(ToolStreamProtocolException::class.java) {
            decodeParts(
                listOf(
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("index", 0); put("id", "a"); put("type", "function")
                            put("function", buildJsonObject { put("name", "foo"); put("arguments", "{}") })
                        }
                    }),
                    toolCallsEvent(buildJsonArray {
                        addJsonObject {
                            put("index", 0); put("type", "function")
                            put("function", buildJsonObject { put("name", "foo") })
                        }
                    }),
                    finishEvent(),
                ),
                setOf("foo", "foofoo"),
            )
        }
    }

    /** 真实参数里合法重复的片段绝不因「看着像重发」被去重。 */
    @Test
    fun `argument fragments are appended verbatim never deduplicated`() {
        val call = Call(wireId = "c", index = 0, name = "echo", args = """{"a":"abab"}""")
        // args 恰好切成两段相同字面量
        val duplicated = Call(call.wireId, call.index, call.name, """{"a":"ab"""")
        val events = listOf(
            toolCallsEvent(buildJsonArray {
                addJsonObject {
                    put("index", 0); put("id", "c"); put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "echo"); put("arguments", duplicated.args)
                    })
                }
            }),
            toolCallsEvent(buildJsonArray {
                addJsonObject {
                    put("index", 0); put("type", "function")
                    put("function", buildJsonObject { put("arguments", """{"a":"ab""") })
                }
            }),
            toolCallsEvent(buildJsonArray {
                addJsonObject {
                    put("index", 0); put("type", "function")
                    put("function", buildJsonObject { put("arguments", """ab"}""") })
                }
            }),
            finishEvent(),
        )
        val tools = decodeParts(events, setOf("echo"))
        // 三片段逐字拼接：{"a":"ab" + {"a":"ab + ab"} —— 重复字面量原样保留
        // （首片 raw string 实际内容是 {"a":"ab"，含尾引号）
        assertEquals("""{"a":"ab"{"a":"abab"}""", tools.single().input)
    }
}
