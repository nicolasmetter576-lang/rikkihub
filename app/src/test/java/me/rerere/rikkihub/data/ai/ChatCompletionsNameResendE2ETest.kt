package me.rerere.rikkihub.data.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端到端协议链路回归 (离线, loopback HttpServer):
 * 真实 HTTP/SSE → 真实 [me.rerere.ai.provider.providers.openai.ChatCompletionsAPI]
 * → 内部 [ChatCompletionsStreamDecoder] (internal, 不可被 app 测试直接引用)
 * → 真实 StreamChunkHandler → 真实 [GenerationLoop] 调度 → 真实工具执行 → 真实文件。
 *
 * 边界说明: decoder 是 ai 模块 internal, 本类通过真实 Provider+ServerSocket
 * 驱动整条链路而不穿透 internal 边界 (不为此扩大 public API)。
 */
class ChatCompletionsNameResendE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 单次请求排队返回一份 SSE 体的最小 OpenAI 兼容端点 */
    private class FakeOpenAIEndpoint : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val responses = ConcurrentLinkedQueue<String>()
        val requestCount: Int get() = hits.size
        private val hits = ConcurrentLinkedQueue<String>()

        init {
            server.createContext("/v1/chat/completions") { exchange ->
                try {
                    hits += exchange.requestBody.readBytes().decodeToString()
                    val body = responses.poll()
                        ?: """{"error":{"message":"no scripted response left"}}"""
                    val status = if (body.startsWith("{")) 500 else 200
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        val port: Int get() = server.address.port
        /** 收到的原始请求体（顺序），用于断言下一轮回传的 tool_call 字段。 */
        val requestBodies: List<String> get() = hits.toList()

        fun enqueue(sseBody: String) {
            responses += sseBody
        }

        override fun close() = server.stop(0)
    }

    private fun sseToolCall(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ): String = buildJsonObject {
        put("choices", buildJsonArray {
            addJsonObject {
                put("delta", buildJsonObject {
                    put("tool_calls", buildJsonArray {
                        addJsonObject {
                            put("index", index)
                            id?.let { put("id", it) }
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
    }.toString()

    private fun sseText(text: String, finishReason: String = "stop"): String = buildJsonObject {
        put("choices", buildJsonArray {
            addJsonObject {
                put("delta", buildJsonObject { put("content", text) })
                put("finish_reason", finishReason)
            }
        })
    }.toString()

    private fun sseFinish(finishReason: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            addJsonObject {
                put("delta", buildJsonObject {})
                put("finish_reason", finishReason)
            }
        })
    }.toString()

    private fun sse(vararg events: String): String =
        events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    /** 与请求构造一致的 wire 工具声明（schema 按生产编码形状：required=null 被略）。 */
    private fun wireToolDecl(name: String, parameters: JsonElement = EMPTY_SCHEMA): JsonElement =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", "tool $name")
                put("parameters", parameters)
            })
        }

    private fun countingEcho(name: String, executions: CopyOnWriteArrayList<String>): Tool =
        echoTool(name).copy(
            execute = { args ->
                executions += "$name:$args"
                listOf(UIMessagePart.Text("""{"ok":true,"input":$args}"""))
            },
        )

    private fun providerWith(port: Int, model: Model): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(
                apiKey = "test",
                baseUrl = "http://127.0.0.1:$port/v1",
                models = listOf(model),
            )
        )
    )

    private fun lastTools(chunks: List<GenerationChunk>): List<UIMessagePart.Tool> =
        chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last().getTools()

    private fun lastMessages(chunks: List<GenerationChunk>): List<UIMessage> =
        chunks.filterIsInstance<GenerationChunk.Messages>().last().messages

    /** 沿 cause 链找第一个匹配类型的异常（可能被包装层包裹）。 */
    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var cursor: Throwable? = this
        while (cursor != null) {
            if (cursor is T) return cursor
            cursor = cursor.cause
        }
        return null
    }

    /** 收集 flow，允许中途抛错但保留已收到的 chunks 供断言。 */
    private suspend fun collectUntilError(
        flow: kotlinx.coroutines.flow.Flow<GenerationChunk>,
    ): Pair<List<GenerationChunk>, Throwable?> {
        val chunks = mutableListOf<GenerationChunk>()
        return try {
            flow.toList(chunks)
            chunks to null
        } catch (e: Throwable) {
            chunks to e
        }
    }

    private fun UIMessage.approvePendingTools(): UIMessage = copy(
        parts = parts.map { part ->
            if (part is UIMessagePart.Tool && part.isPending) {
                part.copy(approvalState = ToolApprovalState.Approved)
            } else part
        }
    )

    /**
     * 真实链路的 GenerationLoop: 不做任何 provider 覆盖, 使用
     * [me.rerere.ai.provider.ProviderManager] 默认注册的真实 OpenAIProvider
     * (内部走真实 ChatCompletionsAPI + internal decoder)。
     */
    private fun realOpenAiLoop(context: FakeContext): GenerationLoop = GenerationLoop(
        context = context,
        providerManager = me.rerere.ai.provider.ProviderManager(okhttp3.OkHttpClient(), context),
        json = me.rerere.rikkihub.utils.JsonInstant,
    ).apply {
        statusStrings = StatusStringResolver { resId, args -> "status:$resId" }
    }

    /**
     * 线上事故回放: 同一 tool_call 后续 chunk 重发完整 `function.name`。
     * 若旧行为回归, toolName 翻倍 → "not found" 且文件不会写出。
     */
    @Test
    fun `resent full tool name over real wire dispatches correct tool and writes file`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            val target = File(tempFolder.newFolder("out"), "e2e.txt")
            val payload = "written via real decoder+loop"
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "workspace_write_file", arguments = ""),
                    // 同一 tool_call 重发完整名 + 参数 delta (事故 wire 形态)
                    sseToolCall(0, name = "workspace_write_file", arguments = """{"path":"${target.absolutePath.replace("\\", "\\\\")}""""),
                    sseToolCall(0, name = "workspace_write_file", arguments = ""","content":"$payload"}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val settings = Settings(
                providers = listOf(
                    ProviderSetting.OpenAI(
                        apiKey = "test",
                        baseUrl = "http://127.0.0.1:${endpoint.port}/v1",
                        models = listOf(model),
                    )
                )
            )
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user("write it")),
                assistant = testAssistant(),
                tools = listOf(fileWritingTool()),
                maxSteps = 10,
            ).toList()

            // 两次真实 HTTP 请求 (tool 步 + 文本步)
            assertEquals(2, endpoint.requestCount)
            val tools = chunks.filterIsInstance<GenerationChunk.Messages>()
                .last().messages.last().getTools()
            // 名字没有翻倍: 唯一 tool part 名恰为注册名
            assertEquals(listOf("workspace_write_file"), tools.map { it.toolName })
            assertTrue(tools.single().isExecuted)
            // 真实副作用断言
            assertTrue(target.isFile)
            assertEquals(payload, target.readText())
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.COMPLETED,
                chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
            )
        }
    }

    /**
     * 同 id 跨 response 复用 (流式): 第二轮里的调用不得污染已执行 part。
     *
     * 契约由两端共同保证: ai 侧 StreamChunkHandler 给复用 id 的新调用开新 part
     * (`!isExecuted` 限定); app 侧 GenerationLoop 执行回写按 part 下标定位,
     * 新输出/metadata 只进新 part, 旧 call 的 input/name/output 原样保留,
     * 两次调用各自真实执行一次且输出独立。
     */
    @Test
    fun `reused tool call id across responses does not repollute executed part`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = """{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            // 第二轮 provider 复用相同 toolCallId, 名称重发完整名
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = ""),
                    sseToolCall(0, name = "echo_tool", arguments = """{"a":2}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val settings = Settings(
                providers = listOf(
                    ProviderSetting.OpenAI(
                        apiKey = "test",
                        baseUrl = "http://127.0.0.1:${endpoint.port}/v1",
                        models = listOf(model),
                    )
                )
            )
            val executions = java.util.concurrent.CopyOnWriteArrayList<String>()
            var publishSeq = 0
            val countingEcho = echoTool().copy(
                executeWithContext = { args, ctx ->
                    executions += args.toString()
                    ctx.publishMetadata(
                        buildJsonObject { put("seq", ++publishSeq) }
                    )
                    listOf(UIMessagePart.Text("""{"echoed":$args}"""))
                }
            )
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user("go")),
                assistant = testAssistant(),
                tools = listOf(countingEcho),
                maxSteps = 10,
            ).toList()

            assertEquals(3, endpoint.requestCount)
            // 两次调用各自真实执行一次, 参数互不错位
            assertEquals(listOf("""{"a":1}""", """{"a":2}"""), executions)
            val tools = chunks.filterIsInstance<GenerationChunk.Messages>()
                .last().messages.last().getTools()
            // 流式 channel: 同 id 两轮各自独立 part, 名称都为单份
            assertEquals(listOf("call_1", "call_1"), tools.map { it.toolCallId })
            assertTrue(tools.all { it.toolName == "echo_tool" })
            assertEquals(listOf("""{"a":1}""", """{"a":2}"""), tools.map { it.input })
            assertTrue(tools.all { it.isExecuted })
            // 旧 call 的输出保持第一轮的回显, 不被第二轮覆盖; 第二轮输出独立
            assertEquals("""{"echoed":{"a":1}}""",
                (tools[0].output.single() as UIMessagePart.Text).text)
            assertEquals("""{"echoed":{"a":2}}""",
                (tools[1].output.single() as UIMessagePart.Text).text)
            // 实时 metadata 也只落在各自的 part 上 (seq=1/seq=2), 不跨 part 覆盖
            assertEquals(1, tools[0].metadata?.get("seq")?.jsonPrimitive?.intOrNull)
            assertEquals(2, tools[1].metadata?.get("seq")?.jsonPrimitive?.intOrNull)
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.COMPLETED,
                chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
            )
        }
    }

    /**
     * 显式空 id 的 continuation（事故网关形态：`id:""` 而非省略字段）：
     * 空 id 必须按缺失语义处理，调用只执行一次，文件内容正确；
     * 下一轮请求回传的 tool_call 必须使用真实 wire call id，name/arguments 完整。
     */
    @Test
    fun `explicit blank id continuation executes once and writes file`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            val target = File(tempFolder.newFolder("out"), "blank-id.txt")
            val payload = "written via explicit blank id continuation"
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "workspace_write_file", arguments = ""),
                    // 空串 id 不得覆盖 index→id 映射，完整名重发不得翻倍
                    sseToolCall(0, id = "", name = "workspace_write_file", arguments = """{"path":"${target.absolutePath.replace("\\", "\\\\")}""""),
                    sseToolCall(0, id = "", arguments = ""","content":"$payload"}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = AtomicInteger(0)
            val tool = fileWritingTool().copy(
                execute = { args -> executions.incrementAndGet(); fileWritingTool().execute(args) },
            )
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = providerWith(endpoint.port, model),
                model = model,
                messages = listOf(UIMessage.user("write it")),
                assistant = testAssistant(),
                tools = listOf(tool),
                maxSteps = 10,
            ).toList()

            assertEquals(2, endpoint.requestCount)
            val tools = lastTools(chunks)
            assertEquals(listOf("call_1"), tools.map { it.toolCallId })
            assertEquals(listOf("workspace_write_file"), tools.map { it.toolName })
            assertEquals(1, executions.get())
            assertTrue(target.isFile)
            assertEquals(payload, target.readText())

            // 第二轮请求的历史回传：assistant tool_calls 与 tool 结果都带真实 id
            val second = Json.parseToJsonElement(endpoint.requestBodies[1]).jsonObject
            val historyToolCall = second["messages"]!!.jsonArray
                .map { it.jsonObject }
                .first { it.containsKey("tool_calls") }["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals("call_1", historyToolCall["id"]!!.jsonPrimitive.content)
            val fn = historyToolCall["function"]!!.jsonObject
            assertEquals("workspace_write_file", fn["name"]!!.jsonPrimitive.content)
            assertEquals(
                Json.parseToJsonElement("""{"path":"${target.absolutePath.replace("\\", "\\\\")}","content":"$payload"}"""),
                Json.parseToJsonElement(fn["arguments"]!!.jsonPrimitive.content),
            )
            val toolResultMsg = second["messages"]!!.jsonArray
                .map { it.jsonObject }
                .single { it["role"]?.jsonPrimitive?.content == "tool" }
            assertEquals("call_1", toolResultMsg["tool_call_id"]!!.jsonPrimitive.content)
            // 上游契约：tool 结果消息不再携带 name（严格服务会拒绝多余字段）
            assertFalse("tool result message must not contain name", toolResultMsg.containsKey("name"))

            assertEquals(
                GenerationChunk.GenerationEnd.Reason.COMPLETED,
                chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
            )
        }
    }

    /**
     * 注册表接线锚点：注册表仅 {foofoo}，wire foo→foo 的唯一合法解释是分片
     * 拼出 foofoo。已知名表若未正确接线（空集合退化为重发语义）名字恒为 foo，
     * 本用例必然失败——它证明最终 wire 工具表真的喂给了名称消歧。
     */
    @Test
    fun `registry dependent fragmented name executes the resolved tool`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "foo", arguments = ""),
                    sseToolCall(0, name = "foo", arguments = """{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = providerWith(endpoint.port, model),
                model = model,
                messages = listOf(UIMessage.user("go")),
                assistant = testAssistant(),
                tools = listOf(countingEcho("foofoo", executions), countingEcho("bar", executions)),
                maxSteps = 10,
            ).toList()

            assertEquals(listOf("foofoo:{\"a\":1}"), executions)
            val tools = lastTools(chunks)
            assertEquals(listOf("foofoo"), tools.map { it.toolName })
            assertTrue(tools.single().isExecuted)
        }
    }

    /**
     * 有界候选消歧（依赖 core 侧 resolver）：注册 {foo, foofoobar}，
     * wire foo→foo→bar 终态只剩 foofoobar 一个完整候选，必须消歧成功并执行
     * foofoobar 一次；不允许停在中间态 foobar。
     */
    @Test
    fun `registry dependent fragmented name resolves foofoobar`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "foo", arguments = ""),
                    sseToolCall(0, name = "foo", arguments = ""),
                    sseToolCall(0, name = "bar", arguments = """{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = providerWith(endpoint.port, model),
                model = model,
                messages = listOf(UIMessage.user("go")),
                assistant = testAssistant(),
                tools = listOf(countingEcho("foo", executions), countingEcho("foofoobar", executions)),
                maxSteps = 10,
            ).toList()

            assertEquals(listOf("foofoobar:{\"a\":1}"), executions)
            assertEquals(listOf("foofoobar"), lastTools(chunks).map { it.toolName })
        }
    }

    /**
     * 终态歧义 fail-closed（依赖 typed 错误）：注册 {foo, foofoo}，
     * wire foo→foo 既是重发 foo 也是分片 foofoo，协议内不可判定——必须以
     * typed 协议错误终止，不执行任何工具、不产出成功终态。回滚后半成品
     * part 的清理断言归回滚测试，这里不假设可见 part 数量。
     */
    @Test
    fun `ambiguous terminal name fails closed without side effects`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "foo", arguments = ""),
                    sseToolCall(0, name = "foo", arguments = """{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val loop = realOpenAiLoop(context)
            val (chunks, error) = collectUntilError(
                loop.generateText(
                    settings = providerWith(endpoint.port, model),
                    model = model,
                    messages = listOf(UIMessage.user("go")),
                    assistant = testAssistant(),
                    tools = listOf(countingEcho("foo", executions), countingEcho("foofoo", executions)),
                    maxSteps = 10,
                )
            )

            val protocolError = error?.findCause<ToolStreamProtocolException>()
            assertTrue("expected ToolStreamProtocolException, got $error", protocolError != null)
            assertTrue(protocolError!!.code.isNotBlank())
            assertTrue("no tool may execute on ambiguous terminal name", executions.isEmpty())
            assertFalse(chunks.any { it is GenerationChunk.GenerationEnd })
        }
    }

    /**
     * 未注册工具名（幻觉/沿用 ToolOverride 改名前的旧名）逐字透传契约：
     * 候选全灭不再是协议错误——wire 名 literal 拼进 part 交给执行端，
     * `tools.find` 落空生成 "Tool X not found" 错误输出反馈模型，随后
     * 脚本文本步照常完成。无协议异常、不执行任何已注册工具、零文件副作用。
     */
    @Test
    fun `unregistered tool name reports not found and lets model continue`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            val target = File(tempFolder.newFolder("out"), "ghost.txt")
            endpoint.enqueue(
                sse(
                    // ghost_tool 不在注册表（echo_tool + workspace_write_file）
                    sseToolCall(0, id = "call_1", name = "ghost_tool", arguments = """{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val writeExecutions = AtomicInteger(0)
            val fileTool = fileWritingTool().copy(
                execute = { args ->
                    writeExecutions.incrementAndGet()
                    fileWritingTool().execute(args)
                },
            )
            val loop = realOpenAiLoop(context)
            val (chunks, error) = collectUntilError(
                loop.generateText(
                    settings = providerWith(endpoint.port, model),
                    model = model,
                    messages = listOf(UIMessage.user("go")),
                    assistant = testAssistant(),
                    tools = listOf(countingEcho("echo_tool", executions), fileTool),
                    maxSteps = 10,
                )
            )

            // 无协议异常：零候选名透传不终止 attempt
            assertTrue("no protocol error expected, got $error", error == null)
            // 工具步 + 文本步两轮真实请求
            assertEquals(2, endpoint.requestCount)
            val tools = lastTools(chunks)
            // literal 名原样进入 part 并被"执行"——执行端未找到工具，
            // 输出是 "Tool ghost_tool not found" 错误反馈给模型自我纠正
            assertEquals(listOf("ghost_tool"), tools.map { it.toolName })
            assertTrue(tools.single().isExecuted)
            val outputText = tools.single().output
                .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            assertTrue(outputText.contains("not found"))
            assertTrue(outputText.contains("ghost_tool"))
            // 结构化反馈：列出本轮可调用的工具名，不带异常类名/堆栈
            val notFound = Json.parseToJsonElement(outputText).jsonObject
            assertEquals(
                setOf("echo_tool", fileTool.name),
                notFound["available_tools"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            assertFalse(outputText, outputText.contains("IllegalStateException"))
            assertFalse(outputText, outputText.contains("\tat "))
            // 未注册名不派发到任何已注册工具，零文件副作用
            assertTrue(executions.isEmpty())
            assertEquals(0, writeExecutions.get())
            assertFalse(target.exists())
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.COMPLETED,
                chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason,
            )
        }
    }

    /**
     * ToolOverride 重命名后的真实注册表接线：实际 API 工具名是覆盖后的
     * writewrite，wire 分片 write→write 只能靠已知名表消歧为 writewrite 执行。
     */
    @Test
    fun `overridden tool name wires actual registry into name disambiguation`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "write", arguments = ""),
                    sseToolCall(0, name = "write", arguments = """{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = providerWith(endpoint.port, model),
                model = model,
                messages = listOf(UIMessage.user("go")),
                assistant = testAssistant(),
                // ChatToolFactory 覆盖后的实际工具表形态：名字已是 writewrite
                tools = listOf(countingEcho("writewrite", executions)),
                maxSteps = 10,
            ).toList()

            assertEquals(listOf("writewrite:{\"a\":1}"), executions)
            assertEquals(listOf("writewrite"), lastTools(chunks).map { it.toolName })
        }
    }

    /**
     * 审批契约：needsApproval 工具在批准前零副作用（不执行、无文件、
     * AWAITING_APPROVAL 终态）；把 pending part 标记 Approved 续跑后恰执行一次。
     */
    @Test
    fun `approval required tool executes zero times before approval and exactly once on resume`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            val target = File(tempFolder.newFolder("out"), "approval.txt")
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "workspace_write_file",
                        arguments = """{"path":"${target.absolutePath.replace("\\", "\\\\")}","content":"x"}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = AtomicInteger(0)
            val tool = fileWritingTool().copy(
                needsApproval = { true },
                execute = { args -> executions.incrementAndGet(); fileWritingTool().execute(args) },
            )
            val loop = realOpenAiLoop(context)
            val settings = providerWith(endpoint.port, model)

            // 第一轮：生成停在内审批等待
            val first = loop.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user("write it")),
                assistant = testAssistant(),
                tools = listOf(tool),
                maxSteps = 10,
            ).toList()

            assertEquals(1, endpoint.requestCount)
            assertEquals(0, executions.get())
            assertFalse(target.exists())
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL,
                first.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason,
            )
            val pending = lastTools(first).single()
            assertTrue(pending.isPending)

            // 用户批准后续跑：同一调用恰执行一次，不新增第二轮工具调用
            val resumed = loop.generateText(
                settings = settings,
                model = model,
                messages = lastMessages(first).map { it.approvePendingTools() },
                assistant = testAssistant(),
                tools = listOf(tool),
                maxSteps = 10,
            ).toList()

            assertEquals(1, executions.get())
            assertTrue(target.isFile)
            assertEquals(2, endpoint.requestCount) // 执行后只多一轮文本响应
            val finalTools = lastTools(resumed)
            assertEquals(1, finalTools.size)
            assertTrue(finalTools.single().isExecuted)
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.COMPLETED,
                resumed.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason,
            )
        }
    }

    /**
     * 契约（依赖 core 侧 streamKey/真实 id 绑定）：首帧无 id 得到合成身份，
     * 真实 id 迟到必须就地绑定同一调用——单 part、执行一次、下一轮回传真实 id。
     */
    @Test
    fun `late real id round trips into next request`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, name = "echo_tool", arguments = """{"a":"""),
                    sseToolCall(0, id = "real-1", arguments = "1}"),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = providerWith(endpoint.port, model),
                model = model,
                messages = listOf(UIMessage.user("go")),
                assistant = testAssistant(),
                tools = listOf(countingEcho("echo_tool", executions)),
                maxSteps = 10,
            ).toList()

            assertEquals(1, executions.size) // 同一调用执行一次
            val tools = lastTools(chunks)
            assertEquals(1, tools.size)
            assertEquals("real-1", tools.single().toolCallId)
            assertEquals("echo_tool", tools.single().toolName)
            assertEquals("""{"a":1}""", tools.single().input)

            val second = Json.parseToJsonElement(endpoint.requestBodies[1]).jsonObject
            val historyToolCall = second["messages"]!!.jsonArray
                .map { it.jsonObject }
                .first { it.containsKey("tool_calls") }["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals("real-1", historyToolCall["id"]!!.jsonPrimitive.content)
        }
    }

    /**
     * 预检 E2E：customBody 注入未注册工具时整个请求在发 HTTP 前以
     * typed 协议/配置错误拒绝——零请求、零副作用、无成功终态。
     */
    @Test
    fun `preflight rejects unknown tool before send`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val assistant = testAssistant().copy(
                customBodies = listOf(
                    CustomBody("tools", buildJsonArray { add(wireToolDecl("rogue_tool")) }),
                )
            )
            val loop = realOpenAiLoop(context)
            val (chunks, error) = collectUntilError(
                loop.generateText(
                    settings = providerWith(endpoint.port, model),
                    model = model,
                    messages = listOf(UIMessage.user("go")),
                    assistant = assistant,
                    tools = listOf(countingEcho("echo_tool", executions)),
                    maxSteps = 10,
                )
            )

            val protocolError = error?.findCause<ToolStreamProtocolException>()
            assertTrue("expected ToolStreamProtocolException, got $error", protocolError != null)
            assertEquals("unknown_tool", protocolError!!.code)
            assertEquals(0, endpoint.requestCount)
            assertTrue(executions.isEmpty())
            assertFalse(chunks.any { it is GenerationChunk.GenerationEnd })
        }
    }

    /**
     * 放行侧 E2E：customBody 把 wire 工具表删减为子集后请求照常发出，
     * 服务端只见子集，注册的 echo 工具仍被正确分派执行一次。
     */
    @Test
    fun `custom body narrowed tool subset still executes through real chain`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = """{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))

            val context = FakeContext(tempFolder.newFolder())
            val model: Model = testChatModel()
            val executions = CopyOnWriteArrayList<String>()
            val assistant = testAssistant().copy(
                customBodies = listOf(
                    CustomBody("tools", buildJsonArray { add(wireToolDecl("echo_tool")) }),
                )
            )
            val loop = realOpenAiLoop(context)
            val chunks = loop.generateText(
                settings = providerWith(endpoint.port, model),
                model = model,
                messages = listOf(UIMessage.user("go")),
                assistant = assistant,
                tools = listOf(countingEcho("echo_tool", executions), countingEcho("other_tool", executions)),
                maxSteps = 10,
            ).toList()

            assertEquals(2, endpoint.requestCount)
            val first = Json.parseToJsonElement(endpoint.requestBodies[0]).jsonObject
            assertEquals(
                listOf("echo_tool"),
                first["tools"]!!.jsonArray.map {
                    it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                },
            )
            assertEquals(listOf("echo_tool:{\"a\":1}"), executions)
            assertTrue(lastTools(chunks).single().isExecuted)
        }
    }

    private suspend fun runEcho(
        endpoint: FakeOpenAIEndpoint,
        tool: Tool,
    ): List<GenerationChunk> {
        val model: Model = testChatModel()
        return realOpenAiLoop(FakeContext(tempFolder.newFolder())).generateText(
            settings = providerWith(endpoint.port, model),
            model = model,
            messages = listOf(UIMessage.user("go")),
            assistant = testAssistant(),
            tools = listOf(tool),
            maxSteps = 10,
        ).toList()
    }

    /** 第二轮请求回传的 assistant tool_calls 与 tool 结果消息的 id 列表。 */
    private fun historyToolCallIds(requestBody: String): Pair<List<String>, List<String>> {
        val history = Json.parseToJsonElement(requestBody).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
        val calls = history.first { it.containsKey("tool_calls") }["tool_calls"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val results = history.filter { it["role"]?.jsonPrimitive?.content == "tool" }
            .map { it["tool_call_id"]!!.jsonPrimitive.content }
        return calls to results
    }

    /**
     * 线上事故回放：模型把两次调用的参数拼进一个调用（`{A}{B}`）。
     * 拆回两个独立调用：各执行一次、各自回写，下一轮历史回传两个 tool_call。
     */
    @Test
    fun `concatenated arguments in one call are split into independent calls`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = """{"a":1}{"a":2}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))
            val executions = CopyOnWriteArrayList<String>()

            val chunks = runEcho(endpoint, countingEcho("echo_tool", executions))

            assertEquals(listOf("""echo_tool:{"a":1}""", """echo_tool:{"a":2}"""), executions)
            val tools = lastTools(chunks)
            assertEquals(listOf("call_1", "call_1_2"), tools.map { it.toolCallId })
            assertEquals(listOf("""{"a":1}""", """{"a":2}"""), tools.map { it.input })
            assertTrue(tools.all { it.isExecuted })
            assertEquals(
                listOf("call_1", "call_1_2") to listOf("call_1", "call_1_2"),
                historyToolCallIds(endpoint.requestBodies[1]),
            )
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.COMPLETED,
                chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
            )
        }
    }

    /**
     * wire 形态：第二个调用复用 index 0、不带 id、重发完整名。账本按协议续接
     * 到第一个调用（名称被视作完整重发），参数拼成 `{A}{B}`；执行前拆回。
     */
    @Test
    fun `second call reusing index without id is recovered by splitting`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = """{"a":"""),
                    sseToolCall(0, arguments = "1}"),
                    sseToolCall(0, name = "echo_tool", arguments = """{"a":2}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))
            val executions = CopyOnWriteArrayList<String>()

            val chunks = runEcho(endpoint, countingEcho("echo_tool", executions))

            assertEquals(listOf("""echo_tool:{"a":1}""", """echo_tool:{"a":2}"""), executions)
            assertEquals(listOf("echo_tool", "echo_tool"), lastTools(chunks).map { it.toolName })
        }
    }

    /** 重复对象（重发造成）只执行一次，不另起调用。 */
    @Test
    fun `identical concatenated objects execute once`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = """{"a":1}{"a":1}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))
            val executions = CopyOnWriteArrayList<String>()

            val chunks = runEcho(endpoint, countingEcho("echo_tool", executions))

            assertEquals(listOf("""echo_tool:{"a":1}"""), executions)
            val tool = lastTools(chunks).single()
            assertEquals("call_1", tool.toolCallId)
            assertEquals("""{"a":1}""", tool.input)
        }
    }

    /**
     * 拆分在审批判定之前：按参数判定的审批策略看到每段真实参数。未拆时
     * `inputAsJson()` 对拼接参数退化为 `{}`，`a==2` 的调用会绕过审批。
     */
    @Test
    fun `split calls are approved individually by their own arguments`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = """{"a":1}{"a":2}"""),
                    sseFinish("tool_calls"),
                )
            )
            val executions = CopyOnWriteArrayList<String>()
            val gated = countingEcho("echo_tool", executions).copy(
                needsApproval = { args -> args.jsonObject["a"]?.jsonPrimitive?.intOrNull == 2 },
            )

            val chunks = runEcho(endpoint, gated)

            val tools = lastTools(chunks)
            assertEquals(listOf("call_1", "call_1_2"), tools.map { it.toolCallId })
            assertEquals(
                listOf(ToolApprovalState.Auto, ToolApprovalState.Pending),
                tools.map { it.approvalState },
            )
            assertTrue(executions.isEmpty())
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL,
                chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
            )
        }
    }

    /**
     * 名字不在本轮表里的调用即使参数是 `{A}{B}` 也不拆：拆开只会把一次
     * not found 变成两次（线上 `subagentworkspace_read_file` 被拆成两个坏调用）。
     */
    @Test
    fun `unregistered name with concatenated arguments stays one call`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "ghost_tool", arguments = """{"a":1}{"a":2}"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))
            val executions = CopyOnWriteArrayList<String>()

            val chunks = runEcho(endpoint, countingEcho("echo_tool", executions))

            assertTrue(executions.isEmpty())
            val tool = lastTools(chunks).single()
            assertEquals("call_1", tool.toolCallId)
            val output = (tool.output.single() as UIMessagePart.Text).text
            assertTrue(output, output.contains("not found"))
        }
    }

    /** 无法严格切分的非法参数：回灌可操作错误（无堆栈），不执行，生成继续。 */
    @Test
    fun `unsplittable invalid arguments return actionable error without stack trace`() = runBlocking {
        FakeOpenAIEndpoint().use { endpoint ->
            endpoint.enqueue(
                sse(
                    sseToolCall(0, id = "call_1", name = "echo_tool", arguments = """{"a":1}{"a":"""),
                    sseFinish("tool_calls"),
                )
            )
            endpoint.enqueue(sse(sseText("done")))
            val executions = CopyOnWriteArrayList<String>()

            val chunks = runEcho(endpoint, countingEcho("echo_tool", executions))

            assertTrue(executions.isEmpty())
            val tool = lastTools(chunks).single()
            val output = (tool.output.single() as UIMessagePart.Text).text
            val error = Json.parseToJsonElement(output).jsonObject
            assertEquals("invalid_arguments", error["error"]!!.jsonPrimitive.content)
            val message = error["message"]!!.jsonPrimitive.content
            assertTrue(message, message.contains("separate tool call"))
            assertFalse(message, message.contains("JSON input"))
            assertFalse(output, output.contains("\tat "))
            assertEquals(
                GenerationChunk.GenerationEnd.Reason.COMPLETED,
                chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
            )
        }
    }

    private companion object {
        // 与 ai 侧 `json` 编码 InputSchema.Obj(properties={}) 一致的形状
        // （type 判别子 + properties；required=null 被 explicitNulls=false 略去）。
        val EMPTY_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
    }
}
