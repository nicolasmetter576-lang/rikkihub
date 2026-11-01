package me.rerere.ai.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChunkHandlerTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `text lifecycle should create and update assistant message`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "hel"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "lo"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals("hello", messages.last().toText())
        assertEquals(model.id, messages.last().modelId)
        assertNotNull(messages.last().finishedAt)
    }

    @Test
    fun `reasoning tool and usage events should preserve semantic order`() {
        var messages = listOf(UIMessage.user("use a tool"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ReasoningStart("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ReasoningDelta("reasoning-1", "think"))
        messages = handler.handle(messages, StreamChunk.ReasoningEnd("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-1"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallDelta(
                id = "call-1",
                toolNameDelta = "search",
                inputDelta = "{\"q\":\"test\"}",
            ),
        )
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call-1"))
        messages = handler.handle(messages,
            StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 5)),
        )

        val assistant = messages.last()
        val reasoning = assistant.parts[0] as UIMessagePart.Reasoning
        val tool = assistant.parts[1] as UIMessagePart.Tool
        assertEquals("think", reasoning.reasoning)
        assertNotNull(reasoning.finishedAt)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":\"test\"}", tool.input)
        assertEquals(15, assistant.usage?.totalTokens)
    }

    @Test
    fun `interleaved text chunks should be merged by id`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "A"))
        messages = handler.handle(messages, StreamChunk.TextStart("text-2"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-2", "B"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "C"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-2"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))

        val textParts = messages.last().parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("AC", "B"), textParts.map { it.text })
    }

    @Test
    fun `image snapshots should replace previous image data`() {
        var messages = listOf(UIMessage.user("draw an image"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ImageStart("image-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-2"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "final"))
        messages = handler.handle(messages, StreamChunk.ImageEnd("image-1"))

        val image = messages.last().parts.single() as UIMessagePart.Image
        assertEquals("data:image/png;base64,final", image.url)
    }

    @Test
    fun `non streaming result should keep image data url intact`() {
        val messages = listOf(UIMessage.user("draw an image"))
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/jpeg;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/jpeg;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `non streaming result appended to assistant turn should not merge images`() {
        val messages = listOf(
            UIMessage.user("draw an image"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("sure"))),
        )
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/png;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/png;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `tool call start reusing id of executed part appends new part`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        // 第一轮：call_1 完成并已被执行（带 output）
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "echo_tool"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{\"a\":1}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))
        val executedParts = messages.last().parts.map { part ->
            if (part is UIMessagePart.Tool) {
                part.copy(output = listOf(UIMessagePart.Text("done-1")))
            } else part
        }
        messages = messages.dropLast(1) + messages.last().copy(parts = executedParts)
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "tool_calls"))

        // 跨 response 复用同一 toolCallId：绝不能把新 delta 拼进已执行 part
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "echo_tool"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{\"a\":2}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("echo_tool", tools[0].toolName)
        assertEquals("{\"a\":1}", tools[0].input)
        assertEquals(listOf(UIMessagePart.Text("done-1")), tools[0].output)
        assertEquals("{\"a\":2}", tools[1].input)
        assertTrue(tools[1].output.isEmpty())
    }

    @Test
    fun `tool call delta for id of executed part is dropped not merged`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        val executed = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "echo_tool",
            input = "{\"a\":1}",
            output = listOf(UIMessagePart.Text("done-1")),
        )
        messages = messages.dropLast(1) + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(executed),
        )

        // 裸 Delta 命中已执行 part：delta 无法构成完整调用，丢弃但绝不污染旧 call
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "x", "{\"a\":2}"))

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("echo_tool", tool.toolName)
        assertEquals("{\"a\":1}", tool.input)
        assertEquals(listOf(UIMessagePart.Text("done-1")), tool.output)
    }

    @Test
    fun `tool call start reusing id of unfinished part reuses it`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "sea"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "rch", "{\"a\":"))
        // 未结束就再来一次 Start（同一 call 的重复开始）：复用 part。
        // Start 自身不携带 input，已累积的输入不能丢——input 逐字完整
        // 由所有 delta 拼接而成；名字采用 Start 的更长快照。
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "_web", "\"b\":2}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("{\"a\":\"b\":2}", tool.input)
    }

    @Test
    fun `repeated start restating shorter name keeps accumulated input and name`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search_web"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{\"q\":"))
        // Start 重发旧名的前缀：是同一 call 的重述，名字不退化、input 不重置
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "\"x\"}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("{\"q\":\"x\"}", tool.input)
    }

    @Test
    fun `start after end on same unfinished call keeps accumulated input`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        // 真实解码器形态：tool 流中被插入一段文本，closeTools 发出 ToolCallEnd，
        // 下一 chunk 同一 toolCallId 重新 open → Start(同 id) + 续 input delta。
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "echo"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{\"a\":"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))
        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "thinking"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "1}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("echo", tool.toolName)
        // End 只清易变缓冲，未执行 part 的已确认 input 逐字保留并继续拼接
        assertEquals("{\"a\":1}", tool.input)
    }

    @Test
    fun `blank name repeated start on same id does not duplicate part or name`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))
        // 名字重发解析为空 delta 后，重开 Start 带空名：不拼重、不另起 part
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", ""))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("search", tool.toolName)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `tool call start with incompatible name on unfinished same id appends new part`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "read_file"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{\"p\":1}"))
        // 同 id 但名字互不兼容（非前缀关系）：复用 id 的全新调用，另起 part
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "write_file"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{\"p\":2}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].toolName)
        assertEquals("{\"p\":1}", tools[0].input)
        assertEquals("write_file", tools[1].toolName)
        assertEquals("{\"p\":2}", tools[1].input)
    }

    @Test
    fun `reopened call seeds name buffer from accepted snapshot name`() {
        // A1 回归：End 清空名称缓冲后，同 id 重开 Start 携带更长的累计快照，
        // 下一条 Delta 必须建立在刚接受的 nextName 上——旧实现补种
        // existing.toolName（旧名），Delta 之后名称回退到 "work"。
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "work"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart("call_1", "workspace_write_file"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", "", "{}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("workspace_write_file", tool.toolName)
        assertEquals("{}", tool.input)
    }

    @Test
    fun `tool call id assignment rewrites unfinished part in place`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)

        // 双 ID 路径：streamKey 路由 + synthetic 显示 id，真实 id 迟到就地补绑
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "search", toolCallId = "synthetic-1"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallDelta(id = "chat-tool-1", toolNameDelta = "_web", inputDelta = "{\"q\":"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallIdAssigned(id = "chat-tool-1", toolCallId = "real-1"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallDelta(id = "chat-tool-1", inputDelta = "1}"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("chat-tool-1"))

        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("real-1", tool.toolCallId)
        assertEquals("search_web", tool.toolName)
        assertEquals("{\"q\":1}", tool.input)
    }

    @Test
    fun `tool call id assignment on unknown key fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c1"))
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallIdAssigned(id = "chat-tool-9", toolCallId = "x"))
        }
    }

    @Test
    fun `tool call id assignment after end fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "synthetic-1"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("chat-tool-1"))
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallIdAssigned(id = "chat-tool-1", toolCallId = "real-1"))
        }
    }

    @Test
    fun `tool call id assignment on executed part fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "synthetic-1"))
        val executedParts = messages.last().parts.map { part ->
            if (part is UIMessagePart.Tool) {
                part.copy(output = listOf(UIMessagePart.Text("done")))
            } else part
        }
        messages = messages.dropLast(1) + messages.last().copy(parts = executedParts)
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallIdAssigned(id = "chat-tool-1", toolCallId = "real-1"))
        }
    }

    @Test
    fun `tool call id assignment with blank external id fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "synthetic-1"))
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallIdAssigned(id = "chat-tool-1", toolCallId = "  "))
        }
    }

    @Test
    fun `strict start restating different external id fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c1"))
        // 同 streamKey 重述 Start 但外部 id 不同：身份冲突，不默默新开 part
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c2"))
        }
        // 原 part 未被污染
        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("c1", tool.toolCallId)
    }

    @Test
    fun `strict start with incompatible name fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "read_file", toolCallId = "c1"))
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "write_file", toolCallId = "c1"))
        }
    }

    @Test
    fun `strict start after end on same stream key fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c1"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("chat-tool-1"))
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c1"))
        }
    }

    @Test
    fun `strict start on stream key of executed part fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c1"))
        val executedParts = messages.last().parts.map { part ->
            if (part is UIMessagePart.Tool) {
                part.copy(output = listOf(UIMessagePart.Text("done")))
            } else part
        }
        messages = messages.dropLast(1) + messages.last().copy(parts = executedParts)
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c1"))
        }
    }

    @Test
    fun `strict start with blank external id fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = ""))
        }
    }

    @Test
    fun `strict delta after end fails closed`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "echo", toolCallId = "c1"))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("chat-tool-1"))
        assertThrows(ToolStreamProtocolException::class.java) {
            handler.handle(messages,
                StreamChunk.ToolCallDelta(id = "chat-tool-1", inputDelta = "{}"))
        }
        // 已结束 part 原样保留
        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("echo", tool.toolName)
    }

    @Test
    fun `strict path does not rescan external id history`() {
        var messages = listOf(UIMessage.user("go"))
        val handler = StreamChunkHandler(model)
        // 先有一个 legacy part 的 toolCallId 恰好等于后来的 streamKey
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-9", toolName = "echo")) // legacy part
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("chat-tool-9"))
        // strict part 占用另一个 streamKey；其 Delta 绝不误路由到 legacy part
        messages = handler.handle(messages,
            StreamChunk.ToolCallStart(id = "chat-tool-1", toolName = "write_file", toolCallId = "real-1"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallDelta(id = "chat-tool-1", inputDelta = "{}"))
        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("echo", tools[0].toolName)
        assertEquals("write_file", tools[1].toolName)
        assertEquals("{}", tools[1].input)
    }

    @Test
    fun `server tool lifecycle should merge streamed input result and metadata`() {
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ServerToolStart(
            id = "srv-1",
            toolName = "web_search",
            metadata = buildJsonObject { put("call", "raw") },
        ))
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "{\"query\":"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "\"Kotlin\"}"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputEnd("srv-1"))
        messages = handler.handle(messages, StreamChunk.ServerToolEnd(
            id = "srv-1",
            output = buildJsonObject { put("result", "docs") },
            status = ServerToolStatus.COMPLETED,
            metadata = buildJsonObject { put("result", "raw") },
        ))

        val tool = messages.last().parts.single() as UIMessagePart.ServerTool
        assertEquals("web_search", tool.toolName)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals("docs", tool.output?.jsonObject?.get("result")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals("raw", tool.metadata?.get("call")?.jsonPrimitive?.content)
        assertEquals("raw", tool.metadata?.get("result")?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming next step keeps its text separate from the previous step`() {
        val messages = listOf(
            UIMessage.user("search then answer"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(toolCallId = "call-1", toolName = "search", input = "{}",
                        output = listOf(UIMessagePart.Text("result"))),
                    UIMessagePart.Text("before"),
                ),
            ),
        )
        val result = TextGenerationResult(
            id = "resp-2",
            model = model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("after"))),
        )

        val nextStep = messages.handleTextGenerationResult(result, model, newStep = true)
        assertEquals(
            listOf("before", "after"),
            nextStep.last().parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )

        // Provider-internal continuation of the same reply (Claude pause_turn) still joins the text.
        val continuation = messages.handleTextGenerationResult(result, model)
        assertEquals(
            listOf("beforeafter"),
            continuation.last().parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
    }
}
