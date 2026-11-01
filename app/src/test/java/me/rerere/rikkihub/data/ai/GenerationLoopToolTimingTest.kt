package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolConcurrency
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** 工具调用的执行起止时间：只给真正运行的调用计时，开始时间在执行期间就能被界面读到。 */
class GenerationLoopToolTimingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun tool(
        name: String,
        concurrency: ToolConcurrency = ToolConcurrency.Exclusive,
        body: suspend (JsonElement) -> String = { "ok" },
    ) = Tool(
        name = name,
        description = name,
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
        concurrency = concurrency,
        execute = { args -> listOf(UIMessagePart.Text(body(args))) },
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

    private fun generate(
        provider: ScriptedProvider,
        messages: List<UIMessage>,
        tools: List<Tool>,
    ): kotlinx.coroutines.flow.Flow<GenerationChunk> {
        val model = testChatModel()
        return testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateText(
            settings = testSettings(model),
            model = model,
            messages = messages,
            assistant = testAssistant(),
            tools = tools,
        )
    }

    private fun List<GenerationChunk>.lastTools(): List<UIMessagePart.Tool> =
        filterIsInstance<GenerationChunk.Messages>().last().messages.last().getTools()

    @Test
    fun `a run call gets a start and a finish around its own execution`() = runBlocking {
        val slow = tool("slow") { delay(80); "done" }
        val chunks = generate(
            ScriptedProvider(ArrayDeque(listOf(toolsStep("c1" to "slow"), textStepScript("ok")))),
            listOf(UIMessage.user("go")),
            listOf(slow),
        ).toList()

        val timed = chunks.lastTools().single()
        val started = checkNotNull(timed.startedAt) { "start time missing" }
        val finished = checkNotNull(timed.finishedAt) { "finish time missing" }
        assertTrue("finish is not before start", finished >= started)
        assertTrue("ran about 80 ms, got ${finished - started}", finished - started >= 60.milliseconds)
    }

    @Test
    fun `the start time is already in the messages while the call is still running`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val blocked = tool("blocked") { withTimeout(5_000) { release.await() }; "done" }
        val sawRunning = CompletableDeferred<UIMessagePart.Tool>()

        val job = launch {
            generate(
                ScriptedProvider(ArrayDeque(listOf(toolsStep("c1" to "blocked"), textStepScript("ok")))),
                listOf(UIMessage.user("go")),
                listOf(blocked),
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    chunk.messages.lastOrNull()?.getTools()
                        ?.firstOrNull { it.startedAt != null && !it.isExecuted }
                        ?.let { sawRunning.complete(it) }
                }
            }
        }
        val running = withTimeout(5_000) { sawRunning.await() }
        assertNull("not finished yet", running.finishedAt)
        assertTrue(running.output.isEmpty())
        release.complete(Unit)
        job.join()
    }

    @Test
    fun `parallel calls are timed separately`() = runBlocking {
        val bothStarted = CompletableDeferred<Unit>()
        var count = 0
        val quick = tool("quick", ToolConcurrency.Parallel) {
            if (++count == 2) bothStarted.complete(Unit)
            withTimeout(5_000) { bothStarted.await() }
            "q"
        }
        val slow = tool("slow", ToolConcurrency.Parallel) {
            if (++count == 2) bothStarted.complete(Unit)
            withTimeout(5_000) { bothStarted.await() }
            delay(120)
            "s"
        }
        val chunks = generate(
            ScriptedProvider(ArrayDeque(listOf(toolsStep("c1" to "quick", "c2" to "slow"), textStepScript("ok")))),
            listOf(UIMessage.user("go")),
            listOf(quick, slow),
        ).toList()

        val byId = chunks.lastTools().associateBy { it.toolCallId }
        val quickMs = (byId.getValue("c1").finishedAt!! - byId.getValue("c1").startedAt!!).inWholeMilliseconds
        val slowMs = (byId.getValue("c2").finishedAt!! - byId.getValue("c2").startedAt!!).inWholeMilliseconds
        assertTrue("quick $quickMs ms, slow $slowMs ms", slowMs >= 100 && slowMs > quickMs)
    }

    @Test
    fun `denied and answered calls are not timed`() = runBlocking {
        val reply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("c1", "write", "{}", approvalState = ToolApprovalState.Denied("no")),
                UIMessagePart.Tool("c2", "ask_user", "{}", approvalState = ToolApprovalState.Answered("blue")),
            ),
        )
        val chunks = generate(
            ScriptedProvider(ArrayDeque(listOf(textStepScript("ok")))),
            listOf(UIMessage.user("go"), reply),
            listOf(tool("write")),
        ).toList()

        val tools = chunks.lastTools()
        assertEquals(listOf("c1", "c2"), tools.map { it.toolCallId })
        assertTrue(tools.all { it.isExecuted })
        assertTrue(tools.all { it.startedAt == null && it.finishedAt == null })
    }

    @Test
    fun `a call that is not even dispatched is not timed`() = runBlocking {
        val chunks = generate(
            ScriptedProvider(ArrayDeque(listOf(toolsStep("c1" to "ghost"), textStepScript("ok")))),
            listOf(UIMessage.user("go")),
            listOf(tool("real")),
        ).toList()

        val ghost = chunks.lastTools().single()
        assertTrue(ghost.isExecuted)
        assertNull(ghost.startedAt)
        assertNull(ghost.finishedAt)
    }

    @Test
    fun `a call that throws still gets its run time`() = runBlocking {
        val broken = tool("broken") { delay(30); error("boom") }
        val chunks = generate(
            ScriptedProvider(ArrayDeque(listOf(toolsStep("c1" to "broken"), textStepScript("ok")))),
            listOf(UIMessage.user("go")),
            listOf(broken),
        ).toList()

        val failed = chunks.lastTools().single()
        assertTrue(failed.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("boom"))
        assertNotNull(failed.startedAt)
        assertNotNull(failed.finishedAt)
    }

    // ---- 纯函数 ----

    private val t0 = Instant.parse("2026-10-26T12:00:00Z")

    private fun reply(vararg tools: UIMessagePart.Tool) = UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList())

    @Test
    fun `marking the start writes it on the right part and clears an old finish`() {
        val messages = listOf(
            UIMessage.user("go"),
            reply(
                UIMessagePart.Tool("a", "t", "{}"),
                UIMessagePart.Tool("b", "t", "{}", startedAt = t0.minus(10_000.milliseconds), finishedAt = t0.minus(5_000.milliseconds)),
            ),
        )

        val marked = markToolStartedAt(messages, messageIndex = 1, partIndex = 1, expectedToolCallId = "b", startedAt = t0)

        val tools = marked[1].getTools()
        assertNull(tools[0].startedAt)
        assertEquals(t0, tools[1].startedAt)
        assertNull(tools[1].finishedAt)
        assertSame(messages[0], marked[0])
    }

    @Test
    fun `marking the start leaves the messages alone when the index or call id does not match`() {
        val messages = listOf(UIMessage.user("go"), reply(UIMessagePart.Tool("a", "t", "{}")))

        assertSame(messages, markToolStartedAt(messages, 1, 0, "other", t0))
        assertSame(messages, markToolStartedAt(messages, 1, 5, "a", t0))
        assertSame(messages, markToolStartedAt(messages, 9, 0, "a", t0))
        assertSame(messages, markToolStartedAt(messages, 0, 0, "a", t0))
    }

    @Test
    fun `timedSince only times calls that actually started`() {
        val untouched = UIMessagePart.Tool("a", "t", "{}")
        assertSame(untouched, untouched.timedSince(null))
        val timed = untouched.timedSince(t0)
        assertEquals(t0, timed.startedAt)
        assertNotNull(timed.finishedAt)
    }
}
