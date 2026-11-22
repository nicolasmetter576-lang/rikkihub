package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.google.StreamedArgumentsFallbackException
import me.rerere.ai.provider.providers.google.UNTERMINATED_STREAMED_ARGS_CODE
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.ChatToolSnapshot
import me.rerere.rikkihub.data.ai.tools.applyBuiltinOverrides
import me.rerere.rikkihub.data.ai.tools.local.PseudoIo
import me.rerere.rikkihub.data.ai.tools.local.PseudoIoNames
import me.rerere.rikkihub.data.ai.tools.local.buildPseudoIoTools
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkihub.data.datastore.NetworkSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** 伪工具读写：一步回复的落定、发请求前的改写、生成循环里的回合边界。 */
class PseudoIoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val names = PseudoIoNames(read = "read", write = "write")

    private fun call(id: String, name: String, input: String = "{}") =
        UIMessagePart.Tool(toolCallId = id, toolName = name, input = input)

    private fun writeCall(id: String, text: String) =
        call(id, "write", JsonObject(mapOf("content" to JsonPrimitive(text))).toString())

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun settled(vararg parts: UIMessagePart) = PseudoIo.settle(assistant(*parts), 0, names)

    private fun List<UIMessage>.wireTools() = last { it.role == MessageRole.ASSISTANT }.getTools()

    // ---- 落定 ----

    @Test
    fun `a write call becomes ordinary reply text`() {
        val result = settled(writeCall("w1", "Hello **there**"))
        val text = result.message.parts.single() as UIMessagePart.Text
        assertEquals("Hello **there**", text.text)
        assertEquals("Hello **there**", result.message.toText())
        assertFalse(result.retry)
    }

    @Test
    fun `a write cut off mid arguments keeps what arrived and is marked interrupted on the wire`() {
        val result = settled(call("w1", "write", """{"content":"half a sent"""))
        assertEquals("half a sent", result.message.toText())
        assertFalse(result.retry)
        val wire = PseudoIo.toWire(listOf(UIMessage.user("q"), result.message), names)
        val write = wire.wireTools().single()
        assertEquals("""{"content":"half a sent"}""", write.input)
        assertEquals(PseudoIo.INTERRUPTED, (write.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a write without content is answered with an error and retried`() {
        val result = settled(call("w1", "write", """{"text":"oops"}"""))
        val tool = result.message.parts.single() as UIMessagePart.Tool
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("content"))
        assertTrue(result.retry)
    }

    @Test
    fun `reading before replying is nudged once, then accepted as waiting`() {
        val first = settled(call("r1", "read"))
        assertTrue(first.retry)
        assertFalse(PseudoIo.awaitsUser(first.message))
        val second = PseudoIo.settle(first.message.copy(parts = first.message.parts + call("r2", "read")), 1, names)
        assertFalse(second.retry)
        assertTrue(PseudoIo.awaitsUser(second.message))
    }

    @Test
    fun `read after a reply waits for the user, a second read in the same response is ignored`() {
        val result = settled(writeCall("w1", "hi"), call("r1", "read"), call("r2", "read"))
        assertFalse(result.retry)
        assertTrue(PseudoIo.awaitsUser(result.message))
        val reads = result.message.getTools()
        assertEquals(PseudoIo.NO_INPUT, (reads[0].output.single() as UIMessagePart.Text).text)
        assertEquals(PseudoIo.DUPLICATE_READ, (reads[1].output.single() as UIMessagePart.Text).text)
        assertTrue(reads.all(PseudoIo::isRead))
    }

    @Test
    fun `parts of earlier steps and other tools are left alone`() {
        val earlier = writeCall("w0", "untouched")
        val other = call("b1", "bash", """{"command":"ls"}""")
        val result = PseudoIo.settle(assistant(earlier, other, writeCall("w1", "new")), 1, names)
        assertSame(earlier, result.message.parts[0])
        assertSame(other, result.message.parts[1])
        assertTrue(result.message.parts[2] is UIMessagePart.Text)
    }

    // ---- 改写 ----

    @Test
    fun `the next user message becomes the result of a read riding on the write`() {
        val reply = settled(writeCall("w1", "first reply")).message
        val second = UIMessage.user("second question")
        val history = listOf(UIMessage.user("first question"), reply, second)
        val wire = PseudoIo.toWire(history, names)

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), wire.map { it.role })
        val (write, read) = wire.wireTools()
        assertEquals("write", write.toolName)
        assertEquals("w1", write.toolCallId)
        assertEquals("""{"content":"first reply"}""", write.input)
        assertEquals(PseudoIo.DELIVERED, (write.output.single() as UIMessagePart.Text).text)
        assertEquals("read", read.toolName)
        assertEquals("{}", read.input)
        assertEquals(second.parts, read.output)
        // 同一步的两个调用编码成一条助手消息：补上的 read 不带步界标记
        assertFalse(read.startsStep)
    }

    @Test
    fun `rewriting is a pure function of the history, earlier turns stay byte identical as it grows`() {
        val turn1 = listOf(UIMessage.user("q1"), settled(writeCall("w1", "a1")).message, UIMessage.user("q2"))
        val longer = turn1 + settled(writeCall("w2", "a2")).message + UIMessage.user("q3")
        val first = PseudoIo.toWire(turn1, names)
        assertEquals(first, PseudoIo.toWire(turn1, names))
        assertEquals(first, PseudoIo.toWire(longer, names).take(first.size))
    }

    @Test
    fun `a read the model called itself is filled instead of adding another`() {
        val reply = settled(writeCall("w1", "hi"), call("r1", "read")).message
        val wire = PseudoIo.toWire(listOf(UIMessage.user("a"), reply, UIMessage.user("b")), names)
        val tools = wire.wireTools()
        assertEquals(listOf("w1", "r1"), tools.map { it.toolCallId })
        assertEquals("b", (tools[1].output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `user messages that cannot ride on a write stay ordinary messages`() {
        val plainReply = assistant(UIMessagePart.Text("no tool used"))
        val reply = settled(writeCall("w1", "hi")).message
        val injected = UIMessage.system("author's note").copy(isSynthetic = true)
        val audio = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Audio("file:///a.mp3")))
        listOf(
            "plain text reply" to listOf(UIMessage.user("a"), plainReply, UIMessage.user("b")),
            "injected message in between" to listOf(UIMessage.user("a"), reply, injected, UIMessage.user("b")),
            "audio attachment" to listOf(UIMessage.user("a"), reply, audio),
        ).forEach { (case, history) ->
            val wire = PseudoIo.toWire(history, names)
            assertEquals(case, history.size, wire.size)
            assertEquals(case, MessageRole.USER, wire.last().role)
            assertTrue(case, wire.flatMap { it.getTools() }.none { it.toolName == "read" })
        }
    }

    @Test
    fun `with the tools gone the history is an ordinary chat`() {
        val reply = settled(writeCall("w1", "hi"), call("r1", "read")).message
        val history = listOf(UIMessage.user("a"), reply, UIMessage.user("b"))
        val wire = PseudoIo.toWire(history, null)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), wire.map { it.role })
        assertTrue(wire[1].getTools().isEmpty())
        assertEquals("hi", wire[1].toText())
        // 没有标记的历史不重建
        val untouched = listOf(UIMessage.user("a"), assistant(UIMessagePart.Text("b")))
        assertSame(untouched, PseudoIo.toWire(untouched, names))
    }

    // ---- 步界标记 ----

    @Test
    fun `only the first call of a response is marked as a step start`() {
        fun done(id: String) = call(id, "bash").copy(output = listOf(UIMessagePart.Text("ok")))
        val step = assistant(done("a"), call("b", "bash"), call("c", "bash")).withStepStartMarked(1)
        assertEquals(listOf(false, true, false), step.getTools().map { it.startsStep })
        // 已执行的不标；这一步没有调用时原样返回
        val finished = assistant(done("a"))
        assertSame(finished, finished.withStepStartMarked(0))
    }

    @Test
    fun `the step marker of a write survives into the rewritten call`() {
        val step = assistant(writeCall("w1", "hi")).withStepStartMarked(0)
        val wire = PseudoIo.toWire(listOf(UIMessage.user("a"), PseudoIo.settle(step, 0, names).message), names)
        assertTrue(wire.wireTools().single().startsStep)
    }

    // ---- 生成循环 ----

    private fun writeStep(id: String, text: String, vararg alsoCall: Pair<String, String>) = ScriptedAttempt(
        buildList {
            add(StreamChunk.ToolCallStart(id = id, toolName = "write"))
            val json = JsonObject(mapOf("content" to JsonPrimitive(text))).toString()
            // 分两片到达：流式途中参数是半截 JSON
            add(StreamChunk.ToolCallDelta(id = id, inputDelta = json.take(json.length / 2)))
            add(StreamChunk.ToolCallDelta(id = id, inputDelta = json.drop(json.length / 2)))
            add(StreamChunk.ToolCallEnd(id = id))
            alsoCall.forEach { (callId, name) ->
                add(StreamChunk.ToolCallStart(id = callId, toolName = name))
                add(StreamChunk.ToolCallDelta(id = callId, inputDelta = "{}"))
                add(StreamChunk.ToolCallEnd(id = callId))
            }
            add(StreamChunk.Finish(finishReason = "tool_calls"))
        }
    )

    private fun run(
        provider: ScriptedProvider,
        messages: List<UIMessage>,
        extraTools: List<Tool> = emptyList(),
        settingsOverride: (Settings) -> Settings = { it },
    ) = testChatModel().let { model ->
        val tools = buildPseudoIoTools().applyBuiltinOverrides(emptyMap()) + extraTools
        val snapshot = ChatToolSnapshot(tools = tools, bindings = tools.associateBy { it.name }, pseudoIo = names)
        testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateTextObserved(
            settings = settingsOverride(testSettings(model)),
            model = model,
            messages = messages,
            assistant = testAssistant(),
            toolSnapshotProvider = { snapshot },
        )
    }

    private fun UIMessage.plainText() =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private suspend fun runConversation(
        provider: ScriptedProvider,
        extraTools: List<Tool> = emptyList(),
        settingsOverride: (Settings) -> Settings = { it },
    ): Pair<Conversation, List<GenerationChunk>> {
        val user = UIMessage.user("q")
        var conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(user.toMessageNode()),
        )
        val chunks = mutableListOf<GenerationChunk>()
        run(provider, listOf(user), extraTools, settingsOverride).collect { chunk ->
            chunks += chunk
            if (chunk is GenerationChunk.Messages) {
                conversation = conversation.updateCurrentMessages(chunk.messages)
            }
        }
        return conversation to chunks
    }

    private fun interruptedWriteAttempt(
        textPrefix: String,
        error: Throwable,
        withReasoning: Boolean = false,
        extraCall: Pair<String, String>? = null,
    ) = ScriptedAttempt(
        chunks = buildList {
            if (withReasoning) {
                add(StreamChunk.ReasoningStart(id = "r0"))
                add(StreamChunk.ReasoningDelta(id = "r0", text = "thinking..."))
            }
            if (extraCall != null) {
                add(StreamChunk.ToolCallStart(id = extraCall.first, toolName = extraCall.second))
                add(StreamChunk.ToolCallDelta(id = extraCall.first, inputDelta = """{"path":"/a"}"""))
            }
            if (textPrefix.isNotEmpty()) {
                add(StreamChunk.ToolCallStart(id = "w1", toolName = "write"))
                val escaped = JsonPrimitive(textPrefix).toString()
                // 不闭合 JSON 字符串，模拟流式工具参数写到一半被 STOP 打断
                add(StreamChunk.ToolCallDelta(id = "w1", inputDelta = """{"content":${escaped.dropLast(1)}"""))
            }
        },
        errorAfter = error,
    )

    private fun List<GenerationChunk>.finalMessages() = filterIsInstance<GenerationChunk.Messages>().last().messages

    @Test
    fun `one write is one request and ends the turn, the stream shows the reply as it arrives`() = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(listOf(writeStep("w1", "The answer is 42."))))
        val chunks = run(provider, listOf(UIMessage.user("question"))).toList()

        assertEquals(1, provider.streamMessages.size)
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason,
        )
        val reply = chunks.finalMessages().last()
        assertEquals("The answer is 42.", reply.toText())
        assertTrue(reply.getTools().isEmpty())
        // 途中没有任何一次把 write 当工具卡片发出去，半截参数已经显示成文字
        val previews = chunks.filterIsInstance<GenerationChunk.Messages>().map { it.messages.last() }
        assertTrue(previews.none { message -> message.getTools().any { it.toolName == "write" } })
        assertTrue(previews.any { it.toText().isNotEmpty() && it.toText() != "The answer is 42." })
    }

    @Test
    fun `the second turn sends the first as tool calls and keeps its prefix`() = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(listOf(writeStep("w1", "first"), writeStep("w2", "second"))))
        val turn1 = run(provider, listOf(UIMessage.user("q1"))).toList().finalMessages()
        run(provider, turn1 + UIMessage.user("q2")).toList()

        val (request1, request2) = provider.streamMessages.map { sent -> sent.filter { it.role != MessageRole.SYSTEM } }
        assertEquals(request1, request2.take(request1.size))
        assertEquals(listOf("write", "read"), request2.last().getTools().map { it.toolName })
        assertEquals("q2", (request2.last().getTools().last().output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a write next to another tool keeps the turn going until the model has seen the result`() = runBlocking {
        val provider = ScriptedProvider(
            ArrayDeque(listOf(writeStep("w1", "working on it", "e1" to "echo_tool"), writeStep("w2", "done")))
        )
        val chunks = run(provider, listOf(UIMessage.user("go")), extraTools = listOf(echoTool())).toList()
        assertEquals(2, provider.streamMessages.size)
        val reply = chunks.finalMessages().last()
        assertEquals(listOf("working on it", "done"), reply.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        // 两步各自成组：write+echo 是一步（当时就是这样发的），第二个 write 另起一步
        val wire = PseudoIo.toWire(chunks.finalMessages(), names).last()
        assertEquals(listOf("write", "echo_tool", "write"), wire.getTools().map { it.toolName })
        assertEquals(listOf(true, false, true), wire.getTools().map { it.startsStep })
    }

    @Test
    fun `stopping mid write leaves the visible half as text`() = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(listOf(writeStep("w1", "a long reply that gets interrupted"))))
        // 收到第一段可见文字就停（等同用户按停止：会话里留下的是最后发出的这一份）
        val seen = mutableListOf<UIMessage>()
        run(provider, listOf(UIMessage.user("q"))).takeWhile { chunk ->
            val last = (chunk as? GenerationChunk.Messages)?.messages?.last()
            if (last != null) seen += last
            last == null || last.toText().isEmpty()
        }.toList()
        val stopped = seen.last()
        assertTrue(stopped.toText().isNotEmpty())
        assertTrue(stopped.getTools().isEmpty())
        val wire = PseudoIo.toWire(listOf(UIMessage.user("q"), stopped, UIMessage.user("continue")), names)
        val (write, read) = wire.wireTools()
        assertEquals(PseudoIo.INTERRUPTED, (write.output.single() as UIMessagePart.Text).text)
        assertEquals("continue", (read.output.single() as UIMessagePart.Text).text)
        assertNull(wire.find { it.role == MessageRole.USER && it.toText() == "continue" })
    }

    // ---- Vertex 流式参数中断重发保留分支 ----

    @Test
    fun `unterminated streamed args mid write keeps settled half reply as previous branch on retry`() = runBlocking {
        val provider = ScriptedProvider(
            ArrayDeque(
                listOf(
                    interruptedWriteAttempt(
                        textPrefix = "半截正文内容",
                        error = StreamedArgumentsFallbackException("eof", code = UNTERMINATED_STREAMED_ARGS_CODE),
                        withReasoning = true,
                        extraCall = "e1" to "read",
                    ),
                    writeStep("w2", "完整重写正文", "r2" to "read"),
                )
            )
        )
        val (conversation, _) = runConversation(provider)
        assertEquals(2, provider.streamMessages.size)
        // 重试发出的请求不应把半截分支当作已完成上文带过去
        assertEquals(1, provider.streamMessages[1].count { it.role != MessageRole.SYSTEM })

        val assistantNode = conversation.messageNodes[1]
        assertEquals(2, assistantNode.messages.size)
        assertEquals(1, assistantNode.selectIndex)

        val (previousBranch, currentBranch) = assistantNode.messages
        assertNotEquals(previousBranch.id, currentBranch.id)
        assertEquals("半截正文内容", previousBranch.plainText())
        assertNotNull(previousBranch.finishedAt)
        assertTrue(previousBranch.getTools().isEmpty())
        assertNotNull(previousBranch.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)

        val wire = PseudoIo.toWire(listOf(UIMessage.user("q"), previousBranch, UIMessage.user("next")), names)
        val (wireWrite, wireRead) = wire.wireTools()
        assertEquals("""{"content":"半截正文内容"}""", wireWrite.input)
        assertEquals(PseudoIo.INTERRUPTED, (wireWrite.output.single() as UIMessagePart.Text).text)
        assertEquals("next", (wireRead.output.single() as UIMessagePart.Text).text)

        assertEquals("完整重写正文", currentBranch.plainText())
        assertNotNull(currentBranch.finishedAt)
        assertTrue(PseudoIo.awaitsUser(currentBranch))
    }

    @Test
    fun `unterminated streamed args on multi-step turn keeps executed step in both branches`() = runBlocking {
        val provider = ScriptedProvider(
            ArrayDeque(
                listOf(
                    toolStepScript("e0", "echo_tool", """{"v":1}"""),
                    interruptedWriteAttempt(
                        textPrefix = "半截总结",
                        error = StreamedArgumentsFallbackException("eof", code = UNTERMINATED_STREAMED_ARGS_CODE),
                    ),
                    writeStep("w2", "完整总结", "r2" to "read"),
                )
            )
        )
        val (conversation, _) = runConversation(provider, extraTools = listOf(echoTool()))
        assertEquals(3, provider.streamMessages.size)

        val assistantNode = conversation.messageNodes[1]
        assertEquals(2, assistantNode.messages.size)
        assertEquals(1, assistantNode.selectIndex)

        val (previousBranch, currentBranch) = assistantNode.messages
        assertNotEquals(previousBranch.id, currentBranch.id)
        assertEquals(listOf("echo_tool"), previousBranch.getTools().map { it.toolName })
        assertEquals("半截总结", previousBranch.plainText())
        assertTrue(previousBranch.getTools().single().isExecuted)

        assertEquals(listOf("echo_tool", "read"), currentBranch.getTools().map { it.toolName })
        assertEquals("完整总结", currentBranch.plainText())

        // 两步各自成组：echo_tool 是第 1 步起点，随后中断的 write 是第 2 步起点
        val wireTools = PseudoIo.toWire(listOf(UIMessage.user("q"), previousBranch), names).wireTools()
        assertEquals(listOf("echo_tool", "write"), wireTools.map { it.toolName })
        assertEquals(listOf(true, true), wireTools.map { it.startsStep })
    }

    @Test
    fun `any retried interruption with visible content keeps it as the previous branch`() = runBlocking {
        val cases = listOf(
            "non-pseudo tool with unterminated STOP" to
                interruptedWriteAttempt("", StreamedArgumentsFallbackException("eof", code = UNTERMINATED_STREAMED_ARGS_CODE), extraCall = "e1" to "echo_tool"),
            "malformed function call fallback mid write" to
                interruptedWriteAttempt("半截", StreamedArgumentsFallbackException("malformed", code = "google_incomplete_tool_call")),
            "null-code fallback mid write" to
                interruptedWriteAttempt("半截", StreamedArgumentsFallbackException("http400", code = null)),
            "plain IOException mid write" to
                interruptedWriteAttempt("半截", IOException("connection reset")),
        )
        for ((name, interrupted) in cases) {
            val provider = ScriptedProvider(ArrayDeque(listOf(interrupted, writeStep("w2", "retried"))))
            val (conversation, _) = runConversation(provider, extraTools = listOf(echoTool()))
            val assistantNode = conversation.messageNodes[1]
            assertEquals("$name should keep the interrupted attempt", 2, assistantNode.messages.size)
            assertEquals(name, 1, assistantNode.selectIndex)
            val (previousBranch, currentBranch) = assistantNode.messages
            assertEquals(name, "retried", currentBranch.plainText())
            assertNotNull(name, previousBranch.finishedAt)
            // 重试请求只带用户消息，不把中断的分支当上文
            assertEquals(name, 1, provider.streamMessages[1].count { it.role != MessageRole.SYSTEM })
            if (name.startsWith("non-pseudo")) {
                // 半截的普通调用写成 unavailable：不执行，也不会再被执行
                val echo = previousBranch.getTools().single()
                assertEquals("echo_tool", echo.toolName)
                assertTrue(echo.isExecuted)
                assertTrue((echo.output.single() as UIMessagePart.Text).text.contains(INTERRUPTED_TOOL_CALL_REASON))
            } else {
                assertEquals(name, "半截", previousBranch.plainText())
                assertTrue(name, previousBranch.getTools().isEmpty())
            }
        }
    }

    @Test
    fun `an interruption with nothing visible yet still retries in place`() = runBlocking {
        val emptyWrite = ScriptedAttempt(
            listOf(
                StreamChunk.ToolCallStart(id = "w1", toolName = "write"),
                StreamChunk.ToolCallDelta(id = "w1", inputDelta = """{"content":""}"""),
            ),
            errorAfter = StreamedArgumentsFallbackException("eof", code = UNTERMINATED_STREAMED_ARGS_CODE),
        )
        val reasoningOnly = ScriptedAttempt(
            listOf(StreamChunk.ReasoningStart(id = "r0"), StreamChunk.ReasoningDelta(id = "r0", text = "thinking...")),
            errorAfter = IOException("connection reset"),
        )
        for ((name, interrupted) in listOf("empty write" to emptyWrite, "reasoning only" to reasoningOnly)) {
            val provider = ScriptedProvider(ArrayDeque(listOf(interrupted, writeStep("w2", "retried"))))
            val (conversation, _) = runConversation(provider)
            val assistantNode = conversation.messageNodes[1]
            assertEquals("$name should not create an extra branch", 1, assistantNode.messages.size)
            assertEquals(name, "retried", assistantNode.messages.single().plainText())
        }
    }

    @Test
    fun `unterminated STOP with auto retry disabled does not emit extra branch`() = runBlocking {
        val provider = ScriptedProvider(
            ArrayDeque(
                listOf(
                    interruptedWriteAttempt(
                        textPrefix = "半截正文",
                        error = StreamedArgumentsFallbackException("eof", code = UNTERMINATED_STREAMED_ARGS_CODE),
                    )
                )
            )
        )
        val user = UIMessage.user("q")
        var conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(user.toMessageNode()),
        )
        val failure = runCatching {
            run(provider, listOf(user)) {
                it.copy(networkSetting = NetworkSetting(enableAutoRetry = false))
            }.collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    conversation = conversation.updateCurrentMessages(chunk.messages)
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is StreamedArgumentsFallbackException)
        assertEquals(1, provider.streamMessages.size)
        assertEquals(1, conversation.messageNodes[1].messages.size)
    }
}
