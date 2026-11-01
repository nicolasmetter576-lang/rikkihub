package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.service.ConversationSession
import me.rerere.rikkihub.service.rollbackProtocolFailedAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * 协议失败 attempt 回滚契约测试。
 *
 * 协议错误 ([ToolStreamProtocolException]) 由 [GenerationLoop] 逐 attempt
 * 分类为 [GenerationAttemptProtocolException]: 不重试、不发 StepFinished/
 * GenerationEnd, descriptor 携带 baseMessage; 权威消费侧 (list / Conversation /
 * ConversationSession) 按 id 回滚尾消息。测试只走离线 ScriptedProvider, 不触网。
 */
class GenerationLoopProtocolRollbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private inner class Harness(attempts: List<ScriptedAttempt>) {
        val context = FakeContext(tempFolder.newFolder())
        val provider = ScriptedProvider(attempts = ArrayDeque(attempts))
        val model: Model = testChatModel()
        val settings: Settings = testSettings(model)
        val loop: GenerationLoop = testGenerationLoop(provider, context)
    }

    private fun protocolError(code: String = "duplicate_tool_name") =
        ToolStreamProtocolException(code, "protocol violation: $code")

    private suspend fun Harness.collectExpectingProtocolFailure(
        conversationId: Uuid? = null,
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<me.rerere.ai.core.Tool> = emptyList(),
    ): Pair<List<GenerationChunk>, GenerationAttemptProtocolException> {
        val chunks = mutableListOf<GenerationChunk>()
        val failure = try {
            loop.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = testAssistant(),
                tools = tools,
                conversationId = conversationId,
            ).toList(chunks)
            // throw 表达式类型为 Nothing, 使 try 整体推断为
            // GenerationAttemptProtocolException; fail() 返回 Unit 会把
            // failure 推断成 Any 造成编译错误, 语义与 fail 相同。
            throw AssertionError("expected GenerationAttemptProtocolException")
        } catch (e: GenerationAttemptProtocolException) {
            e
        }
        return chunks to failure
    }

    @Test
    fun `protocol error fails attempt closed with descriptor and no retry or events`() = runBlocking {
        val conversationId = Uuid.random()
        val protocol = protocolError()
        val harness = Harness(
            listOf(
                ScriptedAttempt(
                    listOf(
                        StreamChunk.TextStart(id = "t"),
                        StreamChunk.TextDelta(id = "t", text = "half"),
                        StreamChunk.ToolCallStart(id = "c", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "c", inputDelta = "{}"),
                    ),
                    errorAfter = protocol,
                )
            )
        )

        val (chunks, failure) = harness.collectExpectingProtocolFailure(
            conversationId = conversationId,
            tools = listOf(echoTool()),
        )

        // 协议错误不走网络重试 (autoRetry 默认开启, 仍只调一次)
        assertEquals(1, harness.provider.streamAttempts.size)
        // descriptor 内容: 任务归属 + attempt 标识 + attempt 前完整尾消息
        assertSame(protocol, failure.protocolError)
        assertSame(protocol, failure.cause)
        assertEquals("duplicate_tool_name", failure.protocolCode)
        assertEquals(conversationId, failure.conversationId)
        assertEquals(0, failure.attemptIndex)
        assertEquals(failure.assistantMessageId, failure.baseMessage.id)
        assertEquals(MessageRole.ASSISTANT, failure.baseMessage.role)
        assertTrue(failure.baseMessage.parts.isEmpty())
        // 协议失败 attempt 不是成功步: 不允许出现终态/步事件
        assertTrue(chunks.filterIsInstance<GenerationChunk.GenerationEnd>().isEmpty())
        assertTrue(chunks.filterIsInstance<GenerationChunk.StepFinished>().isEmpty())
    }

    @Test
    fun `rollback keeps previously succeeded steps and executed tool parts`() = runBlocking {
        val writes = mutableListOf<String>()
        val echo = echoTool().copy(execute = { args ->
            writes += args.toString()
            listOf(UIMessagePart.Text("""{"ok":true}"""))
        })
        val harness = Harness(
            listOf(
                // step1 成功: tool call 完整执行
                toolStepScript("c1", "echo_tool", inputJson = """{"n":1}"""),
                // step2 attempt: 半截工具 part 已建, 随后协议失败
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "c2", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "c2", inputDelta = """{"n":""""),
                    ),
                    errorAfter = protocolError(),
                ),
            )
        )

        val (_, failure) = harness.collectExpectingProtocolFailure(tools = listOf(echo))

        // baseMessage = step1 完成态: 已执行工具与输出原样保留
        val baseTools = failure.baseMessage.getTools()
        assertEquals(1, baseTools.size)
        assertEquals("c1", baseTools.single().toolCallId)
        assertTrue(baseTools.single().isExecuted)
        // 协议失败 attempt 的半截工具从未执行: 只有一次真实副作用
        assertEquals(1, writes.size)

        // 权威态里发布了半截尾消息 -> 回滚只剩 base 内容
        val halfBuilt = failure.baseMessage.copy(
            parts = failure.baseMessage.parts + UIMessagePart.Tool(
                toolCallId = "c2",
                toolName = "echo_tool",
                input = """{"n":"""",
            )
        )
        val published = listOf(UIMessage.user("hi"), halfBuilt)
        val restored = published.rollbackProtocolFailedAttempt(failure)
        assertEquals(published.size, restored.size)
        assertSame(failure.baseMessage, restored.last())
        assertEquals(1, restored.last().getTools().size)
    }

    @Test
    fun `authoritative rollback converges for any delivered prefix including dropped events`() = runBlocking {
        val harness = Harness(
            listOf(
                ScriptedAttempt(
                    listOf(
                        StreamChunk.TextStart(id = "t"),
                        StreamChunk.TextDelta(id = "t", text = "partial"),
                        StreamChunk.ToolCallStart(id = "c", toolName = "echo_tool"),
                    ),
                    errorAfter = protocolError(),
                )
            )
        )
        val initial = listOf(UIMessage.user("hi"))
        val (chunks, failure) = harness.collectExpectingProtocolFailure(messages = initial)

        // 最坏情况: flowOn channel 丢弃了全部在途事件, 权威态从未发布目标消息
        // -> 清理不得凭空创建消息
        assertSame(initial, initial.rollbackProtocolFailedAttempt(failure))

        // 消费端实际见过哪一版快照不确定 (channel 丢多少是调度决定的):
        // 任一已发布快照都必须收敛回同一 baseMessage
        val delivered = chunks.filterIsInstance<GenerationChunk.Messages>().map { it.messages }
        delivered.forEach { snapshot ->
            val restored = snapshot.rollbackProtocolFailedAttempt(failure)
            assertSame(failure.baseMessage, restored.last())
            assertEquals(snapshot.size, restored.size) // 不增删消息
        }
    }

    @Test
    fun `resume after rollback starts clean without half built parts`() = runBlocking {
        val harness = Harness(
            listOf(
                toolStepScript("c1", "echo_tool", inputJson = "{}"),
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "c2", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "c2", inputDelta = "{}"),
                    ),
                    errorAfter = protocolError(),
                ),
                // 回滚后的续跑脚本
                textStepScript("resumed answer"),
            )
        )

        val (chunks, failure) = harness.collectExpectingProtocolFailure(tools = listOf(echoTool()))
        val authoritative = (chunks.filterIsInstance<GenerationChunk.Messages>()
            .lastOrNull()?.messages
            ?: listOf(UIMessage.user("hi"), failure.baseMessage))
            .rollbackProtocolFailedAttempt(failure)
        assertEquals(failure.baseMessage, authoritative.last())

        val resumed = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = authoritative,
            assistant = testAssistant(),
            tools = listOf(echoTool()),
        ).toList()

        val transcript = resumed.filterIsInstance<GenerationChunk.Messages>().last().messages
        val assistant = transcript.last()
        assertEquals(failure.baseMessage.id, assistant.id)
        assertTrue(assistant.toText().contains("resumed answer"))
        // 半截 c2 不存在, 不会被执行; 只剩 step1 的 c1
        assertEquals(listOf("c1"), assistant.getTools().map { it.toolCallId })
        assertEquals(
            listOf(GenerationChunk.GenerationEnd.Reason.COMPLETED),
            resumed.filterIsInstance<GenerationChunk.GenerationEnd>().map { it.reason }
        )
    }

    @Test
    fun `protocol failure produces no tool side effects`() = runBlocking {
        val target = File(tempFolder.newFolder(), "should-not-exist.txt")
        val harness = Harness(
            listOf(
                // 协议错误前已收到完整工具调用 —— 但 attempt 失败即不执行
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "c", toolName = "write_file"),
                        StreamChunk.ToolCallDelta(
                            id = "c",
                            inputDelta = """{"path":"${target.absolutePath}","content":"x"}""",
                        ),
                        StreamChunk.ToolCallEnd(id = "c"),
                    ),
                    errorAfter = protocolError(),
                ),
            )
        )

        val (chunks, _) = harness.collectExpectingProtocolFailure(
            tools = listOf(fileWritingTool("write_file")),
        )

        assertFalse(target.exists())
        // 交付过的快照里该 part 也从未被执行
        chunks.filterIsInstance<GenerationChunk.Messages>().forEach { snapshot ->
            snapshot.messages.last().getTools().forEach { assertFalse(it.isExecuted) }
        }
    }

    @Test
    fun `network error still retries and later protocol failure reports its attempt index`() =
        runBlocking {
            val harness = Harness(
                listOf(
                    ScriptedAttempt(
                        listOf(StreamChunk.TextDelta(id = "t", text = "junk")),
                        errorAfter = IOException("connection reset"),
                    ),
                    ScriptedAttempt(
                        listOf(StreamChunk.ToolCallStart(id = "c", toolName = "echo_tool")),
                        errorAfter = protocolError("missing_index"),
                    ),
                )
            )

            val (_, failure) = harness.collectExpectingProtocolFailure(
                tools = listOf(echoTool()),
            )

            // 第一次是 IOException -> 走原网络重试契约, 协议错误本身不重试
            assertEquals(2, harness.provider.streamAttempts.size)
            assertEquals(1, failure.attemptIndex)
            assertEquals("missing_index", failure.protocolCode)
            // 失败 attempt 的半截文本不进入 base
            assertTrue(failure.baseMessage.parts.isEmpty())
        }

    @Test
    fun `non protocol stream error propagates raw and cancellation is not wrapped`() = runBlocking {
        val illegal = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = IllegalStateException("boom")))
        )
        try {
            illegal.loop.generateText(
                settings = illegal.settings,
                model = illegal.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
            ).toList()
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected !is GenerationAttemptProtocolException)
            assertEquals("boom", expected.message)
        }

        val cancelled = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = CancellationException("stop")))
        )
        try {
            cancelled.loop.generateText(
                settings = cancelled.settings,
                model = cancelled.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
            ).toList()
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // 取消路径原契约: 直接传播, 不包装不清理
        }
    }

    @Test
    fun `base message preserves prior parts including tool metadata`() = runBlocking {
        // 续写场景: 入参尾部已是 assistant 消息 (上轮已执行工具 + metadata)
        val priorTool = UIMessagePart.Tool(
            toolCallId = "c0",
            toolName = "echo_tool",
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"ok":true}""")),
            metadata = JsonObject(mapOf("progress" to JsonPrimitive(1))),
        )
        val tail = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(priorTool),
        )
        val harness = Harness(
            listOf(
                ScriptedAttempt(
                    listOf(StreamChunk.TextDelta(id = "t", text = "dropped")),
                    errorAfter = protocolError(),
                )
            )
        )

        val (_, failure) = harness.collectExpectingProtocolFailure(
            messages = listOf(UIMessage.user("hi"), tail),
        )

        // base 就是入参尾部消息本体: 已执行 parts 与 metadata 完整保留
        assertSame(tail, failure.baseMessage)
        assertEquals(tail.parts, failure.baseMessage.parts)
        assertEquals(
            JsonObject(mapOf("progress" to JsonPrimitive(1))),
            failure.baseMessage.getTools().single().metadata,
        )
    }

    @Test
    fun `conversation restore only touches matching id and is idempotent`() {
        // descriptor 契约: assistantMessageId 必须等于 baseMessage.id (生产上
        // baseMessage 就是失败 attempt 的那条尾消息)。独立随机 id 会让恢复
        // 永远落空, fixture 不再携带语义。
        val base = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val failure = GenerationAttemptProtocolException(
            protocolError = protocolError(),
            conversationId = Uuid.random(),
            assistantMessageId = base.id,
            attemptIndex = 0,
            baseMessage = base,
        )
        val otherNode = UIMessage.user("keep").toMessageNode()
        val halfBuilt = failure.baseMessage.copy(
            parts = listOf(UIMessagePart.Tool("c", "echo_tool", "{}"))
        )
        // 同 id 旧候选可能在非选中槽位: 一并恢复
        val node = failure.baseMessage.toMessageNode().let {
            it.copy(messages = it.messages + halfBuilt, selectIndex = 1)
        }
        val conversation = Conversation.ofId(
            Uuid.random(),
            messages = listOf(otherNode, node),
        )

        val restored = conversation.restoreProtocolFailedAttempt(failure)
        assertEquals(2, restored.messageNodes.size)
        assertSame(otherNode, restored.messageNodes[0]) // 其他节点完全不动
        assertEquals(1, restored.messageNodes[1].selectIndex) // selectIndex 保持
        assertTrue(restored.messageNodes[1].messages.all { it == failure.baseMessage })

        // 幂等: 已是 base 的会话再恢复返回原实例
        assertSame(restored, restored.restoreProtocolFailedAttempt(failure))
        // 目标从未发布: no-op, 不创建消息
        val unknown = Conversation.ofId(
            Uuid.random(),
            messages = listOf(UIMessage.user("only").toMessageNode()),
        )
        assertSame(unknown, unknown.restoreProtocolFailedAttempt(failure))
    }

    @Test
    fun `session rollback stays effective after successor job installed and is idempotent`() =
        runBlocking {
            val conversationId = Uuid.random()
            val base = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("ok")))
            val halfBuilt = base.copy(
                parts = base.parts + UIMessagePart.Tool("c2", "echo_tool", "{}")
            )
            val initial = Conversation.ofId(
                id = conversationId,
                messages = listOf(
                    UIMessage.user("hi").toMessageNode(),
                    halfBuilt.toMessageNode(),
                ),
            )
            val session = ConversationSession(
                id = conversationId,
                initial = initial,
                // 不挂到 runBlocking: idle check / job 完成回调不阻塞测试结束
                scope = CoroutineScope(Job()),
                onIdle = {},
            )
            try {
                val failure = GenerationAttemptProtocolException(
                    protocolError = protocolError(),
                    conversationId = conversationId,
                    assistantMessageId = base.id,
                    attemptIndex = 0,
                    baseMessage = base,
                )

                // 会话 id 不匹配 -> 拒绝清理 (串会话防护, 与 job 状态无关)
                val wrongConversation = GenerationAttemptProtocolException(
                    protocolError = protocolError(),
                    conversationId = Uuid.random(),
                    assistantMessageId = base.id,
                    attemptIndex = 0,
                    baseMessage = base,
                )
                assertFalse(session.rollbackProtocolFailedAttempt(wrongConversation))
                assertSame(initial, session.state.value)

                // 无 owner 门控: 失败任务的清理跑在 NonCancellable 收尾里, 后继
                // 任务的 setJob 可能先落地; 此时回滚必须仍然生效 —— 否则半截
                // Auto/Approved 工具 part 会被后继生成步继续执行。对后继的安全
                // 由排序不变量保证 (后继先 join 前任再读状态), 不靠门控。
                session.state.value = initial // 重新发布半截态
                val predecessor = Job()
                session.setJob(predecessor)
                val successor = Job()
                session.setJob(successor) // 后继接管槽位, 前任清理尚未完成
                assertTrue(session.rollbackProtocolFailedAttempt(failure))
                assertEquals(base, session.state.value.currentMessages.last())
                // 幂等: 已恢复返回 false, 调用方跳过重复落库
                assertFalse(session.rollbackProtocolFailedAttempt(failure))
            } finally {
                session.cleanup()
            }
        }

    @Test
    fun `session rollback does not fabricate message that was never published`() = runBlocking {
        val conversationId = Uuid.random()
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation.ofId(
                id = conversationId,
                messages = listOf(UIMessage.user("hi").toMessageNode()),
            ),
            scope = CoroutineScope(Job()),
            onIdle = {},
        )
        val failure = GenerationAttemptProtocolException(
            protocolError = protocolError(),
            conversationId = conversationId,
            assistantMessageId = Uuid.random(), // 会话里根本没有这条消息
            attemptIndex = 0,
            baseMessage = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
        )
        assertFalse(session.rollbackProtocolFailedAttempt(failure))
        assertEquals(1, session.state.value.messageNodes.size)
    }
}
