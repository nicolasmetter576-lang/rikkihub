package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.ai.transformers.transformThinkTags
import me.rerere.rikkihub.service.awaitingToolBatchMessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock

/**
 * 生成循环的三个工具执行问题（移植自上游 AgentLoopTest / TransformNewPartsTest 的用例）：
 * 混合审批时免审批的调用在审批后一起执行、工具空输出不会被反复执行、
 * 输出转换只作用于本步新生成的内容。
 */
class GenerationLoopToolBatchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val executions = CopyOnWriteArrayList<String>()

    private fun recordingTool(
        name: String,
        needsApproval: Boolean = false,
        output: List<UIMessagePart> = listOf(UIMessagePart.Text("""{"ok":true}""")),
    ) = Tool(
        name = name,
        description = name,
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
        needsApproval = { needsApproval },
        execute = { _: JsonElement ->
            executions += name
            output
        },
    )

    private fun toolsStep(vararg calls: Pair<String, String>) = ScriptedAttempt(
        buildList {
            calls.forEach { (id, name) ->
                add(StreamChunk.ToolCallStart(id = id, toolName = name))
                add(StreamChunk.ToolCallDelta(id = id, inputDelta = "{}"))
                add(StreamChunk.ToolCallEnd(id = id))
            }
            add(StreamChunk.Finish(finishReason = "tool_calls"))
        }
    )

    private fun run(
        provider: ScriptedProvider,
        messages: List<UIMessage>,
        tools: List<Tool>,
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
    ): List<GenerationChunk> = runBlocking {
        val model = testChatModel()
        testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateText(
            settings = testSettings(model),
            model = model,
            messages = messages,
            assistant = testAssistant(),
            tools = tools,
            outputTransformers = outputTransformers,
        ).toList()
    }

    private fun List<GenerationChunk>.transcript() =
        filterIsInstance<GenerationChunk.Messages>().last().messages

    private fun List<GenerationChunk>.endReason() =
        filterIsInstance<GenerationChunk.GenerationEnd>().single().reason

    private fun UIMessage.tool(id: String) = getTools().single { it.toolCallId == id }

    private fun UIMessage.texts() = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    @Test
    fun `auto calls in a batch with a pending approval run after the approval`() {
        val auto = recordingTool("read_tool")
        val gated = recordingTool("write_tool", needsApproval = true)
        val first = run(
            ScriptedProvider(ArrayDeque(listOf(toolsStep("c1" to "read_tool", "c2" to "write_tool")))),
            listOf(UIMessage.user("go")),
            listOf(auto, gated),
        )
        assertEquals(GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL, first.endReason())
        assertTrue("Nothing in the batch runs before the approval", executions.isEmpty())
        val waiting = first.transcript().last()
        assertEquals(ToolApprovalState.Auto, waiting.tool("c1").approvalState)
        assertTrue(waiting.tool("c2").isPending)

        // 用户批准 c2 后继续
        val approved = waiting.copy(parts = waiting.parts.map { part ->
            if (part is UIMessagePart.Tool && part.toolCallId == "c2") {
                part.copy(approvalState = ToolApprovalState.Approved)
            } else part
        })
        val resumed = run(
            ScriptedProvider(ArrayDeque(listOf(textStepScript("done")))),
            first.transcript().dropLast(1) + approved,
            listOf(auto, gated),
        )
        assertEquals(listOf("read_tool", "write_tool"), executions)
        val reply = resumed.transcript().last()
        assertTrue(reply.tool("c1").isExecuted)
        assertTrue(reply.tool("c2").isExecuted)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, resumed.endReason())
    }

    @Test
    fun `resume keeps waiting while another call in the batch is still pending`() {
        val gated = recordingTool("write_tool", needsApproval = true)
        val reply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("c1", "write_tool", "{}", approvalState = ToolApprovalState.Approved),
                UIMessagePart.Tool("c2", "write_tool", "{}", approvalState = ToolApprovalState.Pending),
            ),
        )
        val provider = ScriptedProvider(ArrayDeque())
        val chunks = run(provider, listOf(UIMessage.user("go"), reply), listOf(gated))
        assertEquals(GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL, chunks.endReason())
        assertTrue(executions.isEmpty())
        assertTrue(provider.streamAttempts.isEmpty())
    }

    @Test
    fun `a tool returning no parts counts as executed and runs only once`() {
        val silent = recordingTool("silent_tool", output = emptyList())
        val chunks = run(
            ScriptedProvider(ArrayDeque(listOf(toolsStep("c1" to "silent_tool"), textStepScript("ok")))),
            listOf(UIMessage.user("go")),
            listOf(silent),
        )
        assertEquals(listOf("silent_tool"), executions)
        val tool = chunks.transcript().last().tool("c1")
        assertEquals(listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)), tool.output)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    /** 每个 Text 末尾加一个「!」，重复应用会叠加 */
    private object ExclaimTransformer : OutputMessageTransformer {
        override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>) =
            messages.map { message ->
                if (message.role != MessageRole.ASSISTANT) return@map message
                message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Text) part.copy(text = part.text + "!") else part
                })
            }
    }

    @Test
    fun `output transformers touch only the parts generated in this step`() {
        val history = listOf(
            UIMessage.user("earlier"),
            UIMessage.assistant("old reply"),
            UIMessage.user("go"),
        )
        val stepOne = ScriptedAttempt(
            textStepScript("checking", id = "t1").chunks.dropLast(1) +
                toolsStep("c1" to "read_tool").chunks
        )
        val chunks = run(
            ScriptedProvider(ArrayDeque(listOf(stepOne, textStepScript("answer", id = "t2")))),
            history,
            listOf(recordingTool("read_tool")),
            outputTransformers = listOf(ExclaimTransformer),
        )
        val transcript = chunks.transcript()
        assertEquals("History replies are left alone", listOf("old reply"), transcript[1].texts())
        assertEquals(listOf("checking!", "answer!"), transcript.last().texts())
        // 流式过程中发出的每一版也不会叠加
        chunks.filterIsInstance<GenerationChunk.Messages>().forEach { update ->
            assertEquals(listOf("old reply"), update.messages[1].texts())
            update.messages.last().texts().forEach { text -> assertFalse(text, text.endsWith("!!")) }
        }
    }

    @Test
    fun `think tags at the start of a later step are recognized`() {
        val stepOne = ScriptedAttempt(
            listOf(
                StreamChunk.ReasoningStart(id = "r1"),
                StreamChunk.ReasoningDelta(id = "r1", text = "first thoughts"),
                StreamChunk.ReasoningEnd(id = "r1"),
            ) + toolsStep("c1" to "read_tool").chunks
        )
        val chunks = run(
            ScriptedProvider(
                ArrayDeque(listOf(stepOne, textStepScript("<think>second thoughts</think>answer")))
            ),
            listOf(UIMessage.user("go")),
            listOf(recordingTool("read_tool")),
            outputTransformers = listOf(ThinkTagTransformer),
        )
        val reply = chunks.transcript().last()
        assertEquals(
            listOf("first thoughts", "second thoughts"),
            reply.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning },
        )
        assertEquals(listOf("answer"), reply.texts())
    }

    @Test
    fun `transformNewParts keeps earlier parts and the message id`() = runBlocking {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("step one"),
                UIMessagePart.Tool(toolCallId = "c1", toolName = "search", input = "{}"),
                UIMessagePart.Text("<think>t</think>step two"),
            ),
        )
        val transformed = message.transformNewParts(newPartsFrom = 2) {
            it.transformThinkTags(now = Clock.System.now(), generationFinished = true)
        }
        assertEquals(message.id, transformed.id)
        assertEquals(listOf("step one", "step two"), transformed.texts())
        assertEquals(message.parts.take(2), transformed.parts.take(2))
    }

    @Test
    fun `awaiting batch is only the last assistant message with pending or resumable calls`() {
        val waiting = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("c1", "read_tool", "{}"),
                UIMessagePart.Tool("c2", "write_tool", "{}", approvalState = ToolApprovalState.Pending),
            ),
        )
        assertEquals(waiting.id, awaitingToolBatchMessageId(listOf(UIMessage.user("go"), waiting)))

        val approved = waiting.copy(parts = listOf(
            UIMessagePart.Tool("c1", "read_tool", "{}"),
            UIMessagePart.Tool("c2", "write_tool", "{}", approvalState = ToolApprovalState.Approved),
        ))
        assertEquals(approved.id, awaitingToolBatchMessageId(listOf(approved)))

        // 只有被中断的 Auto 调用：不是在等审批
        val interrupted = waiting.copy(parts = listOf(UIMessagePart.Tool("c1", "read_tool", "{}")))
        assertNull(awaitingToolBatchMessageId(listOf(interrupted)))
        // 后面已经有新的用户消息
        assertNull(awaitingToolBatchMessageId(listOf(waiting, UIMessage.user("next"))))
    }
}
