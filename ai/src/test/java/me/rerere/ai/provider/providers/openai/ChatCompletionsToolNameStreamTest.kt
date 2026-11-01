package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat Completions 工具名 wire 形态的边界测试。
 *
 * 线上事故：部分实现对同一 tool_call 后续 chunk 重发完整 `function.name`，
 * 解码器把它当增量，StreamChunkHandler 追加后得到 `name+name`（工具找不到）。
 * 这里固定协议边界的解析语义：重发→单份、累计名→差值、真分片→原样追加，
 * 且不触碰 arguments 与 StreamChunkHandler 的纯 delta 契约。
 */
class ChatCompletionsToolNameStreamTest {
    private val model = Model(modelId = "test-model")

    private fun toolCallEvent(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
        emitIdField: Boolean = id != null,
    ): SseEvent = SseEvent(
        data = buildJsonObject {
            put("choices", buildJsonArray {
                addJsonObject {
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            addJsonObject {
                                put("index", index)
                                if (emitIdField) put("id", id ?: "")
                                put("type", "function")
                                put("function", buildJsonObject {
                                    name?.let { put("name", it) }
                                    arguments?.let { put("arguments", it) }
                                })
                            }
                        })
                    })
                }
            })
        }.toString(),
    )

    private fun finishEvent(): SseEvent = SseEvent(
        data = buildJsonObject {
            put("choices", buildJsonArray {
                addJsonObject {
                    put("delta", buildJsonObject {})
                    put("finish_reason", "tool_calls")
                }
            })
        }.toString(),
    )

    private fun decode(
        events: List<SseEvent>,
        knownToolNames: Set<String> = emptySet(),
    ): UIMessage {
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
        return messages.last()
    }

    private fun decodeChunks(
        events: List<SseEvent>,
        knownToolNames: Set<String> = emptySet(),
    ): List<StreamChunk> {
        val decoder = ChatCompletionsStreamDecoder(knownToolNames)
        return buildList {
            events.forEach { event -> addAll(decoder.accept(event).chunks) }
            addAll(decoder.onClosed())
        }
    }

    private fun UIMessage.tools() = parts.filterIsInstance<UIMessagePart.Tool>()

    @Test
    fun `resent full name must collapse to single name`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search_web", arguments = ""),
                // 同一 tool id 后续 chunk 重发完整 name（线上事故形态），附带参数 delta
                toolCallEvent(0, name = "search_web", arguments = """{"q":"kotlin"}"""),
                toolCallEvent(0, arguments = "!"), // 纯参数 delta 不受名称解析影响
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )

        val tool = message.tools().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("""{"q":"kotlin"}!""", tool.input)
    }

    @Test
    fun `name fragments against empty registry pass through literally`() {
        // 空注册表下出现工具调用：首个非空 wire 名必然零候选。契约修正后
        // 不再硬失败——literal 名逐字透传给执行端报 not found 反馈模型
        // （resolver 只在有工具时实例化，纯文本响应不受注册表影响）。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search_web", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = emptySet(),
        )
        val tool = message.tools().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `resent full name under empty registry collapses via literal track`() {
        // 空注册表下同一 call 重发完整名：首帧即候选全灭进入逐字透传；
        // 续帧 w 以 literal 为前缀、literal+w 不可能落在空注册表上且 w
        // 未注册，legacy resolveToolNameDelta 同款规则取 w.removePrefix
        // (literal)="" —— 重发折叠为空增量，finish 兜底发射字面名
        // search_web 一次，绝不翻倍成 search_websearch_web。未注册名仍
        // 交给执行端报 not found 反馈模型自我纠正。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search_web", arguments = ""),
                toolCallEvent(0, name = "search_web", arguments = """{"q":"kotlin"}"""),
                finishEvent(),
            ),
            knownToolNames = emptySet(),
        )

        val tool = message.tools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search_web", tool.toolName)
        assertEquals("""{"q":"kotlin"}""", tool.input)
    }

    @Test
    fun `cumulative name snapshot resolves to suffix only`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search", arguments = ""),
                toolCallEvent(0, name = "search_web", arguments = """{"q":1}"""),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tool = message.tools().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("""{"q":1}""", tool.input)
    }

    @Test
    fun `true name delta fragments still concatenate`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search", arguments = ""),
                toolCallEvent(0, name = "_web", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tool = message.tools().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `registry disambiguates foo plus foo into foofoo`() {
        // 合法分片 foo+foo 拼出注册名 foofoo；注册表证明这是 delta 而非重发
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "foo", arguments = ""),
                toolCallEvent(0, name = "foo", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("foofoo"),
        )
        assertEquals("foofoo", message.tools().single().toolName)
    }

    @Test
    fun `both interpretations registered is terminal ambiguity error`() {
        // {foo, foofoo} + foo→foo：终态两个完整候选均合法，协议层无法判定
        // 是「重发 foo」还是「分片 foo+foo」——不再猜短名，fail-closed。
        // ⚠ 契约变更：旧保守丢弃策略改为 ToolStreamProtocolException。
        assertThrows(ToolStreamProtocolException::class.java) {
            decode(
                listOf(
                    toolCallEvent(0, id = "call_1", name = "foo", arguments = ""),
                    toolCallEvent(0, name = "foo", arguments = "{}"),
                    finishEvent(),
                ),
                knownToolNames = setOf("foo", "foofoo"),
            )
        }
    }

    @Test
    fun `later fragment can disambiguate overlapping registry names`() {
        // {foo, foofoobar} + foo→foo→bar：第二片时确有歧义，第三片到来后
        // 唯一完整合法解释收敛为 foofoobar —— 有界候选保留到可消歧。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "foo", arguments = ""),
                toolCallEvent(0, name = "foo", arguments = ""),
                toolCallEvent(0, name = "bar", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("foo", "foofoobar"),
        )
        val tool = message.tools().single()
        assertEquals("foofoobar", tool.toolName)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `overlapping registry name converges to unique finished candidate`() {
        // {foo, foofoobar} + foo→foo 终态：foofoo 只是前缀不是完整候选，
        // 唯一完整解释仍是 foo —— 保留（不是 error）。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "foo", arguments = ""),
                toolCallEvent(0, name = "foo", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("foo", "foofoobar"),
        )
        assertEquals("foo", message.tools().single().toolName)
    }

    @Test
    fun `stale shorter snapshot after full name passes through literally`() {
        // 完整名收齐后又来一个更短的旧快照：候选全灭进入 literal 透传，
        // 逐字轨道收束出 workspace_write_fileworkspace_（未注册、不可执行，
        // 执行端报 not found），而不是硬失败、也不截回已注册名。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "workspace_write_file", arguments = ""),
                toolCallEvent(0, name = "workspace_", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("workspace_write_file"),
        )
        val tool = message.tools().single()
        assertEquals("workspace_write_fileworkspace_", tool.toolName)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `registered doubled-looking name emits whole and is never halved`() {
        // wire 真发来一个注册表中的「翻倍形态」全名：resolver 按完整注册名
        // 原样发射，绝不把原始 N+N 自动截半（也不猜）。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "echoecho", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("echo", "echoecho"),
        )
        assertEquals("echoecho", message.tools().single().toolName)
    }

    @Test
    fun `strict prefix registry grows name through intermediate states`() {
        // 审查回归：只注册 foofoobar 时，foo+foo 构成 foofoo 中间态，
        // 不是完整注册名但也是注册名前缀 → 必须按 delta 继续累积，
        // 收齐 bar 后得到完整名；旧实现要求中间态恰为注册名，会吞掉
        // 第二片退化成 foobar。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "foo", arguments = ""),
                toolCallEvent(0, name = "foo", arguments = ""),
                toolCallEvent(0, name = "bar", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("foofoobar"),
        )
        val tool = message.tools().single()
        assertEquals("foofoobar", tool.toolName)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `strict prefix disambiguation does not undo short name ambiguity policy`() {
        // {foo, foofoobar} 下 foo+foo：delta 候选 foofoo 是注册名前缀，
        // 但 wire 名 foo 本身是完整注册名 —— 歧义成立，保持保守短名策略。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "foo", arguments = ""),
                toolCallEvent(0, name = "foo", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("foo", "foofoobar"),
        )
        assertEquals("foo", message.tools().single().toolName)
    }

    @Test
    fun `prefix growth across more than two fragments`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "a", arguments = ""),
                toolCallEvent(0, name = "a", arguments = ""),
                toolCallEvent(0, name = "a", arguments = ""),
                toolCallEvent(0, name = "b", arguments = ""),
                finishEvent(),
            ),
            knownToolNames = setOf("aaab"),
        )
        assertEquals("aaab", message.tools().single().toolName)
    }

    @Test
    fun `repeated legitimate argument fragments are never deduped`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "echo", arguments = """{"a":"ab"""),
                // 真实 JSON 片段完全可以与前一片段逐字符相同，严禁去重
                toolCallEvent(0, arguments = """{"a":"ab"""),
                toolCallEvent(0, arguments = """"}"""),
                finishEvent(),
            ),
            knownToolNames = setOf("echo"),
        )
        // 三个 wire 片段按 authoring 原样是 {"a":"ab + {"a":"ab + "} 的纯拼接
        // (raw string 无法在末尾携带引号, 故片段比预期少一个结尾 '"'),
        // 断言取真实逐字拼接结果, 证明同字面片段绝不消重。
        assertEquals("""{"a":"ab{"a":"ab"}""", message.tools().single().input)
    }

    @Test
    fun `interleaved parallel calls keep separate name state`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_0", name = "search", arguments = ""),
                toolCallEvent(1, id = "call_1", name = "search_web", arguments = ""),
                // index0 累计重发；index1 的缓冲互不影响
                toolCallEvent(0, name = "search_web", arguments = """{"q":0}"""),
                toolCallEvent(1, name = "search_web", arguments = """{"q":1}"""),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tools = message.tools()
        assertEquals(2, tools.size)
        assertEquals(listOf("call_0", "call_1"), tools.map { it.toolCallId })
        assertEquals(listOf("search_web", "search_web"), tools.map { it.toolName })
        assertEquals(listOf("""{"q":0}""", """{"q":1}"""), tools.map { it.input })
    }

    @Test
    fun `consecutive calls sharing a name under different ids do not bleed`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search_web", arguments = """{"q":1}"""),
                toolCallEvent(1, id = "call_2", name = "search_web", arguments = """{"q":2}"""),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tools = message.tools()
        assertEquals(listOf("call_1", "call_2"), tools.map { it.toolCallId })
        assertTrue(tools.all { it.toolName == "search_web" })
        assertEquals(listOf("""{"q":1}""", """{"q":2}"""), tools.map { it.input })
    }

    @Test
    fun `first chunk may carry empty name and resolve it later`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", arguments = ""),
                toolCallEvent(0, name = "search_web", arguments = """{"q":1}"""),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        assertEquals("search_web", message.tools().single().toolName)
    }

    @Test
    fun `late real id after synthetic id updates existing part in place`() {
        // 同 index 无 id 的首 chunk 得到 synthetic 显示 id；真实 id 迟到是
        // 同一调用的身份补全——ToolCallIdAssigned 就地改写 part，不另起。
        // ⚠ 契约变更：旧行为是分裂为第二个 part。
        val message = decode(
            listOf(
                toolCallEvent(0, name = "sea", arguments = ""),
                toolCallEvent(0, id = "real-1", name = "rch", arguments = "{}"),
                finishEvent(),
            ),
            knownToolNames = setOf("search"),
        )
        val tools = message.tools()
        assertEquals(1, tools.size)
        assertEquals("real-1", tools[0].toolCallId)
        assertEquals("search", tools[0].toolName)
        assertEquals("{}", tools[0].input)
    }

    @Test
    fun `whitespace only id falls back to index mapping`() {
        // 纯空白 id 与空串同语义：不覆盖 index→调用的路由。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search_web", arguments = ""),
                toolCallEvent(0, id = "   ", emitIdField = true, arguments = """{"q":"""),
                toolCallEvent(0, arguments = "1}"),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tool = message.tools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search_web", tool.toolName)
        assertEquals("""{"q":1}""", tool.input)
    }

    @Test
    fun `null id falls back like absent`() {
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search_web", arguments = ""),
                // wire 显式写 id:null —— 缺失语义
                SseEvent(
                    data = buildJsonObject {
                        put("choices", buildJsonArray {
                            addJsonObject {
                                put("delta", buildJsonObject {
                                    put("tool_calls", buildJsonArray {
                                        addJsonObject {
                                            put("index", 0)
                                            put("id", null as String?)
                                            put("type", "function")
                                            put("function", buildJsonObject {
                                                put("arguments", "{}")
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    }.toString(),
                ),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tool = message.tools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `conflicting non-empty ids on same index fail closed`() {
        assertThrows(ToolStreamProtocolException::class.java) {
            decode(
                listOf(
                    toolCallEvent(0, id = "call_1", name = "search_web", arguments = ""),
                    toolCallEvent(0, id = "call_2", name = "search_web", arguments = "{}"),
                    finishEvent(),
                ),
                knownToolNames = setOf("search_web"),
            )
        }
    }

    @Test
    fun `same id across two open indexes fails closed`() {
        // 账本契约（规则 2）：同一真实 id 被另一个 index 的进行中调用占用时，
        // 收编会把两个逻辑调用的名称/参数拼进同一 part（曾得到
        // search_webread_file 这类脏名）——必须 fail-closed，绝不混合执行。
        // ⚠ 契约变更：旧行为是把新 index 收编进既有调用。
        assertThrows(ToolStreamProtocolException::class.java) {
            decode(
                listOf(
                    toolCallEvent(0, id = "call_1", name = "search_web", arguments = ""),
                    toolCallEvent(1, id = "call_1", name = "read_file", arguments = "{}"),
                    finishEvent(),
                ),
                knownToolNames = setOf("search_web", "read_file"),
            )
        }
    }

    @Test
    fun `missing index with multiple in-flight calls fails closed`() {
        // index0/1 两调用进行中时来一个无 index 无 id 的块：旧实现的
        // fallbackIndex=0 会污染 index0 映射——现在明确拒绝。
        assertThrows(ToolStreamProtocolException::class.java) {
            decode(
                listOf(
                    toolCallEvent(0, id = "call_0", name = "search_web", arguments = ""),
                    toolCallEvent(1, id = "call_1", name = "read_file", arguments = ""),
                    // 无 index 无 id
                    SseEvent(
                        data = buildJsonObject {
                            put("choices", buildJsonArray {
                                addJsonObject {
                                    put("delta", buildJsonObject {
                                        put("tool_calls", buildJsonArray {
                                            addJsonObject {
                                                put("type", "function")
                                                put("function", buildJsonObject {
                                                    put("arguments", "{}")
                                                })
                                            }
                                        })
                                    })
                                }
                            })
                        }.toString(),
                    ),
                    finishEvent(),
                ),
                knownToolNames = setOf("search_web", "read_file"),
            )
        }
    }

    @Test
    fun `non streaming snapshot does not repollute executed tool`() {
        val executed = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "search_web",
            input = """{"q":"kotlin"}""",
            output = listOf(UIMessagePart.Text("result")),
        )
        val messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed)),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    // 新一轮响应复用同 id 的完整快照：绝不能污染已执行 call
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        input = """{"q":"ktor"}""",
                    ),
                ),
            ),
        )

        val tools = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("search_web", tools[0].toolName)
        assertEquals("""{"q":"kotlin"}""", tools[0].input)
        assertEquals(listOf(UIMessagePart.Text("result")), tools[0].output)
        assertEquals("""{"q":"ktor"}""", tools[1].input)
    }

    @Test
    fun `non streaming snapshot updates unfinished call instead of appending`() {
        val pending = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "sea",
            input = """{"q":""",
        )
        val messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        input = """{"q":"kotlin"}""",
                    ),
                ),
            ),
        )

        val tools = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        // 快照语义是替换不是拼接
        assertEquals("search_web", tools[0].toolName)
        assertEquals("""{"q":"kotlin"}""", tools[0].input)
    }

    @Test
    fun `non streaming identical snapshot replay is idempotent`() {
        // 相同快照重放：命中未执行同 id part 且快照兼容，原地替换（等效无变化），
        // 不产生第二个 part。
        val pending = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "search_web",
            input = """{"q":"kotlin"}""",
        )
        val messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(pending.copy()),
            ),
        )

        val tools = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("search_web", tools[0].toolName)
        assertEquals("""{"q":"kotlin"}""", tools[0].input)
    }

    @Test
    fun `non streaming compatible snapshot grows unfinished call`() {
        // 同 id 快照逐字段前缀增长（name 与 input 都更长）：仍是同一个 call
        // 的更新快照，替换更新而不是追加。
        val pending = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "search",
            input = """{"q":""",
        )
        val messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        input = """{"q":"kotlin"}""",
                    ),
                ),
            ),
        )

        val tools = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("search_web", tools[0].toolName)
        assertEquals("""{"q":"kotlin"}""", tools[0].input)
    }

    @Test
    fun `non streaming incompatible same id snapshot appends as new call`() {
        // provider 复用 id 发全新调用（name/input 互不兼容，非前缀关系）：
        // 不能替换旧未执行 call，追加为新 part。这是 pause_turn 续跑时
        // 跨响应同 id 冲突的保护边界。
        val pending = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "read_file",
            input = """{"path":"a"}""",
        )
        val messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "write_file",
                        input = """{"path":"b"}""",
                    ),
                ),
            ),
        )

        val tools = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].toolName)
        assertEquals("""{"path":"a"}""", tools[0].input)
        assertEquals("write_file", tools[1].toolName)
        assertEquals("""{"path":"b"}""", tools[1].input)
    }

    @Test
    fun `non streaming snapshot targets last unfinished part among duplicate ids`() {
        // 消息里若已存在两个同 id 未执行 part（历史脏数据），快照只更新
        // 最后一个（当前 call），不回写较旧的重复 part。
        val older = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "read_file",
            input = """{"path":"a"}""",
        )
        val newer = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "sea",
            input = """{"q":""",
        )
        val messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(older, newer)),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        input = """{"q":"x"}""",
                    ),
                ),
            ),
        )

        val tools = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].toolName)
        assertEquals("""{"path":"a"}""", tools[0].input)
        assertEquals("search_web", tools[1].toolName)
        assertEquals("""{"q":"x"}""", tools[1].input)
    }

    @Test
    fun `non streaming blank id tool never merges into old tool`() {
        val oldTool = UIMessagePart.Tool(
            toolCallId = "call_old",
            toolName = "read_file",
            input = """{"path":"a"}""",
            output = listOf(UIMessagePart.Text("done")),
        )
        val messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(oldTool)),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "",
                        toolName = "write_file",
                        input = """{"path":"b"}""",
                    ),
                ),
            ),
        )

        val tools = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].toolName)
        assertEquals("""{"path":"a"}""", tools[0].input)
        assertEquals("write_file", tools[1].toolName)
        assertEquals("", tools[1].toolCallId)
    }

    @Test
    fun `blank id on continuation chunk must not double tool name`() {
        // 线上事故残留形态：部分实现在后续 chunk 写 \"id\":\"\"（空串而非省略）。
        // 空串被收下后会覆盖 index→真实 id 的映射并把名称累计器键换成 \"\"，
        // 名称解析与 part 身份脱钩，重发的完整名被当成 delta 追加出 name+name。
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "workspace_write_file", arguments = ""),
                toolCallEvent(0, id = "", emitIdField = true, name = "workspace_write_file", arguments = """{"path":"""),
                toolCallEvent(0, id = "", emitIdField = true, arguments = """"a.txt"}"""),
                finishEvent(),
            ),
            knownToolNames = setOf("workspace_write_file"),
        )
        val tool = message.tools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("workspace_write_file", tool.toolName)
        assertEquals("""{"path":"a.txt"}""", tool.input)
    }

    @Test
    fun `blank id must not poison index mapping for later chunks`() {
        // 空 id chunk 不得覆盖 index→id 映射：其后省略 id 的 chunk 仍要路由回真 id
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "search_web", arguments = ""),
                toolCallEvent(0, id = "", emitIdField = true, arguments = """{"q":"""),
                toolCallEvent(0, name = "search_web", arguments = "1}"), // id 省略应回退到 call_1
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tool = message.tools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search_web", tool.toolName)
        assertEquals("""{"q":1}""", tool.input)
    }

    @Test
    fun `parallel calls with all blank ids keep separate parts`() {
        // 全空 id 的并行调用：空串不能把两个 call 折叠进同一 part（名字/参数互相污染）
        val message = decode(
            listOf(
                toolCallEvent(0, id = "", emitIdField = true, name = "search_web", arguments = ""),
                toolCallEvent(1, id = "", emitIdField = true, name = "search_web", arguments = ""),
                toolCallEvent(0, arguments = """{"q":0}"""),
                toolCallEvent(1, arguments = """{"q":1}"""),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web"),
        )
        val tools = message.tools()
        assertEquals(2, tools.size)
        assertTrue(tools.all { it.toolName == "search_web" })
        assertEquals(listOf("""{"q":0}""", """{"q":1}"""), tools.map { it.input })
    }

    @Test
    fun `interleaved content keeps same tool call open`() {
        // ⚠ 契约变更（A4）：tool delta 之间插入 content 文本时，工具调用
        // 生命周期与显示分段解耦——不再先 ToolCallEnd 再 ToolCallStart 重开，
        // 单 part 保持开放到 Finish，名称/参数持续累积不丢前缀。
        val mixedEvent = SseEvent(
            data = buildJsonObject {
                put("choices", buildJsonArray {
                    addJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "wait")
                            put("tool_calls", buildJsonArray {
                                addJsonObject {
                                    put("index", 0)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", "echo") // 重发完整名
                                        put("arguments", "\"}\"")
                                    })
                                }
                            })
                        })
                    }
                })
            }.toString(),
        )
        val chunks = decodeChunks(
            listOf(
                toolCallEvent(0, id = "call_1", name = "echo", arguments = "{\"a\":"),
                mixedEvent,
                finishEvent(),
            ),
            knownToolNames = setOf("echo"),
        )
        // 同一逻辑调用只 Start 一次、只在收尾 End 一次
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
        assertTrue(chunks.indexOfFirst { it is StreamChunk.ToolCallEnd } >
            chunks.indexOfFirst { it is StreamChunk.TextStart })

        val decoder = ChatCompletionsStreamDecoder(setOf("echo"))
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("go"))
        listOf(
            toolCallEvent(0, id = "call_1", name = "echo", arguments = "{\"a\":"),
            mixedEvent,
            finishEvent(),
        ).forEach { event ->
            decoder.accept(event).chunks.forEach { messages = handler.handle(messages, it) }
        }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }
        val tool = messages.last().tools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("echo", tool.toolName)
        assertEquals("{\"a\":\"}\"", tool.input)
        assertEquals("wait", messages.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `interleaved text before full name arrives keeps single call`() {
        // F2：名称尚未收齐时插入 text，后续片段仍属于同一逻辑调用——
        // 旧实现会拆出 part1(name=workspace_)+part2(name=write_file)。
        val mixedEvent = SseEvent(
            data = buildJsonObject {
                put("choices", buildJsonArray {
                    addJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "working")
                            put("tool_calls", buildJsonArray {
                                addJsonObject {
                                    put("index", 0)
                                    put("id", "call_1")
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", "workspace_write_file")
                                        put("arguments", "\"a.txt\"}")
                                    })
                                }
                            })
                        })
                    }
                })
            }.toString(),
        )
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "workspace_", arguments = "{\"path\":"),
                mixedEvent,
                finishEvent(),
            ),
            knownToolNames = setOf("workspace_write_file"),
        )
        val tools = message.tools()
        assertEquals(1, tools.size)
        assertEquals("call_1", tools[0].toolCallId)
        assertEquals("workspace_write_file", tools[0].toolName)
        assertEquals("{\"path\":\"a.txt\"}", tools[0].input)
        assertEquals("working", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `interleaved reasoning does not close tool calls`() {
        val reasoningEvent = SseEvent(
            data = buildJsonObject {
                put("choices", buildJsonArray {
                    addJsonObject {
                        put("delta", buildJsonObject {
                            put("reasoning_content", "thinking...")
                        })
                    }
                })
            }.toString(),
        )
        val chunks = decodeChunks(
            listOf(
                toolCallEvent(0, id = "call_1", name = "echo", arguments = "{\"a\":"),
                reasoningEvent,
                toolCallEvent(0, name = "echo", arguments = "1}"),
                finishEvent(),
            ),
            knownToolNames = setOf("echo"),
        )
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })

        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "echo", arguments = "{\"a\":"),
                reasoningEvent,
                toolCallEvent(0, name = "echo", arguments = "1}"),
                finishEvent(),
            ),
            knownToolNames = setOf("echo"),
        )
        val tool = message.tools().single()
        assertEquals("echo", tool.toolName)
        assertEquals("{\"a\":1}", tool.input)
        assertEquals("thinking...", message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
    }

    @Test
    fun `interleaved image does not close tool calls`() {
        val imageEvent = SseEvent(
            data = buildJsonObject {
                put("choices", buildJsonArray {
                    addJsonObject {
                        put("delta", buildJsonObject {
                            put("images", buildJsonArray {
                                addJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:image/png;base64,QUJD")
                                    })
                                }
                            })
                        })
                    }
                })
            }.toString(),
        )
        val chunks = decodeChunks(
            listOf(
                toolCallEvent(0, id = "call_1", name = "echo", arguments = "{\"a\":"),
                imageEvent,
                toolCallEvent(0, name = "echo", arguments = "1}"),
                finishEvent(),
            ),
            knownToolNames = setOf("echo"),
        )
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })

        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_1", name = "echo", arguments = "{\"a\":"),
                imageEvent,
                toolCallEvent(0, name = "echo", arguments = "1}"),
                finishEvent(),
            ),
            knownToolNames = setOf("echo"),
        )
        val tool = message.tools().single()
        assertEquals("{\"a\":1}", tool.input)
        assertEquals(1, message.parts.filterIsInstance<UIMessagePart.Image>().size)
    }

    @Test
    fun `parallel tools stay open across interleaved content`() {
        val textEvent = SseEvent(
            data = buildJsonObject {
                put("choices", buildJsonArray {
                    addJsonObject {
                        put("delta", buildJsonObject { put("content", "hold on") })
                    }
                })
            }.toString(),
        )
        val message = decode(
            listOf(
                toolCallEvent(0, id = "call_0", name = "search_web", arguments = ""),
                toolCallEvent(1, id = "call_1", name = "read_file", arguments = ""),
                textEvent,
                toolCallEvent(0, arguments = "{\"q\":0}"),
                toolCallEvent(1, arguments = "{\"p\":1}"),
                finishEvent(),
            ),
            knownToolNames = setOf("search_web", "read_file"),
        )
        val tools = message.tools()
        assertEquals(2, tools.size)
        assertEquals(listOf("call_0", "call_1"), tools.map { it.toolCallId })
        assertEquals(listOf("search_web", "read_file"), tools.map { it.toolName })
        assertEquals(listOf("{\"q\":0}", "{\"p\":1}"), tools.map { it.input })
    }

    @Test
    fun `non streaming usage still accumulates`() {
        val messages = listOf(UIMessage.user("go"))
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))),
            usage = TokenUsage(promptTokens = 3, completionTokens = 2, totalTokens = 5),
        )
        val updated = messages.handleTextGenerationResult(result, model).last()
        assertEquals(5, updated.usage?.totalTokens)
        assertEquals("hi", updated.toText())
    }

    @Test
    fun `handler pure delta contract unchanged for plain chunk sequences`() {
        // StreamChunkHandler 语义回归锚点：纯 delta 事件序列直接喂 handler 时行为不变
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("go"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("c1", "search_web"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("c1", "", """{"q":"""))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("c1", "", """"x"}"""))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("c1"))

        val tool = messages.last().tools().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("""{"q":"x"}""", tool.input)
    }
}
