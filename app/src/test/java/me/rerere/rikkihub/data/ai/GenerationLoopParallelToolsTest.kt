package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolConcurrency
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工具调用并行执行：保序分段、上限、逐个回写、进度合流、失败隔离、
 * 限流退避。工具都用固定 tools（无 snapshot provider），调度表即签发表。
 */
class GenerationLoopParallelToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private inner class Harness(
        attempts: List<ScriptedAttempt>,
        execution: ToolExecutionConfig = ToolExecutionConfig(),
    ) {
        val provider = ScriptedProvider(attempts = ArrayDeque(attempts))
        val model: Model = testChatModel()
        val settings: Settings = testSettings(model).copy(toolExecution = execution)
        val loop: GenerationLoop = testGenerationLoop(provider, FakeContext(tempFolder.newFolder()))

        suspend fun run(
            tools: List<Tool>,
            rateLimitRetry: RateLimitRetryPolicy? = null,
            onChunk: (GenerationChunk) -> Unit = {},
        ): List<GenerationChunk> = loop.generateTextObserved(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user("go")),
            assistant = testAssistant(),
            tools = tools,
            rateLimitRetry = rateLimitRetry,
        ).toList().onEach(onChunk)
    }

    /** 一步里发出多个工具调用：id 为 `c<序号>`，参数原样。 */
    private fun multiToolStep(vararg calls: Pair<String, String>): ScriptedAttempt = ScriptedAttempt(
        buildList {
            calls.forEachIndexed { index, (name, input) ->
                val id = "c$index"
                add(StreamChunk.ToolCallStart(id = id, toolName = name))
                add(StreamChunk.ToolCallDelta(id = id, inputDelta = input))
                add(StreamChunk.ToolCallEnd(id = id))
            }
            add(StreamChunk.Finish(finishReason = "tool_calls"))
        }
    )

    private fun tool(
        name: String,
        concurrency: ToolConcurrency,
        body: suspend (JsonElement) -> String = { "ok" },
    ) = Tool(
        name = name,
        description = name,
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
        concurrency = concurrency,
        execute = { args -> listOf(UIMessagePart.Text(body(args))) },
    )

    private fun List<GenerationChunk>.lastTools(): List<UIMessagePart.Tool> =
        filterIsInstance<GenerationChunk.Messages>().last().messages.last().getTools()

    private fun UIMessagePart.Tool.text() = output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    /** 并发计数：记录同时在跑的最大数。 */
    private class Concurrency {
        private val running = AtomicInteger(0)
        val max = AtomicInteger(0)
        suspend fun <T> track(block: suspend () -> T): T {
            val now = running.incrementAndGet()
            max.accumulateAndGet(now, ::maxOf)
            try {
                return block()
            } finally {
                running.decrementAndGet()
            }
        }
    }

    @Test
    fun `parallel calls in one response overlap`() = runBlocking {
        val started = AtomicInteger(0)
        val bothStarted = CompletableDeferred<Unit>()
        val reader = tool("reader", ToolConcurrency.Parallel) {
            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
            // 逐个执行时第一个调用永远等不到第二个开始
            withTimeout(5_000) { bothStarted.await() }
            "read"
        }
        val chunks = Harness(
            listOf(multiToolStep("reader" to "{}", "reader" to "{}"), textStepScript("done"))
        ).run(listOf(reader))

        val tools = chunks.lastTools()
        assertEquals(listOf("c0", "c1"), tools.map { it.toolCallId })
        assertTrue(tools.all { it.isExecuted && it.text() == "read" })
    }

    @Test
    fun `limit caps concurrent calls`() = runBlocking {
        val counter = Concurrency()
        val reader = tool("reader", ToolConcurrency.Parallel) {
            counter.track { delay(40); "read" }
        }
        val calls = Array(12) { "reader" to "{}" }
        val chunks = Harness(
            listOf(multiToolStep(*calls), textStepScript("done")),
            execution = ToolExecutionConfig(maxParallel = 3),
        ).run(listOf(reader))

        assertEquals(12, chunks.lastTools().count { it.isExecuted })
        assertTrue("max concurrent ${counter.max.get()}", counter.max.get() in 2..3)
    }

    @Test
    fun `exclusive call is a barrier that keeps order`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val reader = tool("reader", ToolConcurrency.Parallel) { args ->
            val tag = args.jsonObject["tag"]!!.jsonPrimitive.content
            events += "start:$tag"
            delay(30)
            events += "end:$tag"
            tag
        }
        val writer = tool("writer", ToolConcurrency.Exclusive) {
            events += "start:w"
            delay(10)
            events += "end:w"
            "w"
        }
        val chunks = Harness(
            listOf(
                multiToolStep(
                    "reader" to """{"tag":"a"}""",
                    "reader" to """{"tag":"b"}""",
                    "writer" to "{}",
                    "reader" to """{"tag":"c"}""",
                ),
                textStepScript("done"),
            )
        ).run(listOf(reader, writer))

        val startW = events.indexOf("start:w")
        assertTrue(events.indexOf("end:a") < startW && events.indexOf("end:b") < startW)
        assertTrue(events.indexOf("end:w") < events.indexOf("start:c"))
        // a、b 同段并行：b 在 a 结束前已开始
        assertTrue(events.indexOf("start:b") < events.indexOf("end:a"))
        assertEquals(listOf("a", "b", "w", "c"), chunks.lastTools().map { it.text() })
    }

    @Test
    fun `parallel switched off runs one at a time`() = runBlocking {
        val counter = Concurrency()
        val reader = tool("reader", ToolConcurrency.Parallel) { counter.track { delay(20); "read" } }
        Harness(
            listOf(multiToolStep("reader" to "{}", "reader" to "{}", "reader" to "{}"), textStepScript("done")),
            execution = ToolExecutionConfig(parallel = false),
        ).run(listOf(reader))
        assertEquals(1, counter.max.get())
    }

    @Test
    fun `exclusive tools never overlap`() = runBlocking {
        val counter = Concurrency()
        val writer = tool("writer", ToolConcurrency.Exclusive) { counter.track { delay(20); "w" } }
        Harness(
            listOf(multiToolStep("writer" to "{}", "writer" to "{}"), textStepScript("done"))
        ).run(listOf(writer))
        assertEquals(1, counter.max.get())
    }

    @Test
    fun `results are written back as each call finishes`() = runBlocking {
        val releaseSlow = CompletableDeferred<Unit>()
        val fast = tool("fast", ToolConcurrency.Parallel) { "fast" }
        val slow = tool("slow", ToolConcurrency.Parallel) {
            // 只有快的结果已单独回写并发出后才放行；整批完成才回写时会超时
            withTimeout(5_000) { releaseSlow.await() }
            "slow"
        }
        val harness = Harness(listOf(multiToolStep("slow" to "{}", "fast" to "{}"), textStepScript("done")))
        val chunks = mutableListOf<GenerationChunk>()
        harness.loop.generateTextObserved(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("go")),
            assistant = testAssistant(),
            tools = listOf(fast, slow),
        ).collect { chunk ->
            chunks += chunk
            if (chunk is GenerationChunk.Messages) {
                val tools = chunk.messages.last().getTools()
                if (tools.size == 2 && tools[1].isExecuted && !tools[0].isExecuted) releaseSlow.complete(Unit)
            }
        }
        assertTrue(releaseSlow.isCompleted)
        assertEquals(listOf("slow", "fast"), chunks.lastTools().map { it.text() })
    }

    @Test
    fun `concurrent progress is coalesced and both parts keep their latest metadata`() = runBlocking {
        val progress = { name: String ->
            Tool(
                name = name,
                description = name,
                parameters = { null },
                concurrency = ToolConcurrency.Parallel,
                execute = { error("uses executeWithContext") },
                executeWithContext = { _, context ->
                    repeat(50) { i -> context.publishMetadata(buildJsonObject { put("step", i) }) }
                    delay(50)
                    listOf(UIMessagePart.Text(name))
                },
            )
        }
        val chunks = Harness(
            listOf(multiToolStep("p1" to "{}", "p2" to "{}"), textStepScript("done"))
        ).run(listOf(progress("p1"), progress("p2")))

        val messageChunks = chunks.filterIsInstance<GenerationChunk.Messages>().size
        // 100 次进度发布不应换来 100 次整表发送
        assertTrue("messages chunks $messageChunks", messageChunks < 60)
        val tools = chunks.lastTools()
        assertEquals(listOf("p1", "p2"), tools.map { it.text() })
        tools.forEach { assertEquals("49", it.metadata?.get("step")?.jsonPrimitive?.contentOrNull) }
    }

    @Test
    fun `one failing call does not affect its siblings`() = runBlocking {
        val ok = tool("ok", ToolConcurrency.Parallel) { delay(20); "fine" }
        val bad = tool("bad", ToolConcurrency.Parallel) { error("boom") }
        val tools = Harness(
            listOf(multiToolStep("bad" to "{}", "ok" to "{}"), textStepScript("done"))
        ).run(listOf(ok, bad)).lastTools()

        assertTrue(tools[0].text().contains("boom"))
        assertEquals("fine", tools[1].text())
    }

    @Test
    fun `parallel when predicate splits by arguments`() = runBlocking {
        val counter = Concurrency()
        val editor = tool(
            "editor",
            ToolConcurrency.ParallelWhen { it.jsonObject["command"]?.jsonPrimitive?.content == "view" },
        ) { counter.track { delay(30); "e" } }
        Harness(
            listOf(
                multiToolStep(
                    "editor" to """{"command":"view"}""",
                    "editor" to """{"command":"view"}""",
                ),
                multiToolStep(
                    "editor" to """{"command":"str_replace"}""",
                    "editor" to """{"command":"str_replace"}""",
                ),
                textStepScript("done"),
            )
        ).run(listOf(editor))
        // 第一步两个 view 重叠；第二步编辑命令独占，不再提高峰值之外的并发
        assertEquals(2, counter.max.get())
    }

    @Test
    fun `parallel prompt is added only with two parallel tools`() = runBlocking {
        val a = tool("a", ToolConcurrency.Parallel)
        val b = tool("b", ToolConcurrency.Parallel)
        val w = tool("w", ToolConcurrency.Exclusive)
        val withTwo = Harness(listOf(textStepScript("done")))
        withTwo.run(listOf(a, b))
        val system = withTwo.provider.streamMessages.single().first().toText()
        assertTrue(system.contains(PARALLEL_TOOL_CALLS_PROMPT))
        assertFalse(system.contains(PARALLEL_SUBAGENTS_PROMPT))

        val withOne = Harness(listOf(textStepScript("done")))
        withOne.run(listOf(a, w))
        assertFalse(withOne.provider.streamMessages.single().first().toText().contains(PARALLEL_TOOL_CALLS_PROMPT))

        val off = Harness(listOf(textStepScript("done")), execution = ToolExecutionConfig(parallel = false))
        off.run(listOf(a, b))
        assertFalse(off.provider.streamMessages.single().first().toText().contains(PARALLEL_TOOL_CALLS_PROMPT))
    }

    @Test
    fun `rate limited stream is retried only with a policy`() = runBlocking {
        val limited = HttpException("slow down", statusCode = 429, retryAfterMillis = 10)
        val policy = RateLimitRetryPolicy(maxRetries = 2, baseDelayMillis = 10, maxDelayMillis = 50)

        val retried = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = limited), textStepScript("after retry"))
        )
        val chunks = retried.run(emptyList(), rateLimitRetry = policy)
        assertEquals(2, retried.provider.streamAttempts.size)
        val text = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last().toText()
        assertEquals("after retry", text)

        val noPolicy = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = limited), textStepScript("unused"))
        )
        try {
            noPolicy.run(emptyList())
            fail("expected the 429 to surface without a retry policy")
        } catch (expected: HttpException) {
            assertEquals(429, expected.statusCode)
        }
        assertEquals(1, noPolicy.provider.streamAttempts.size)
    }

    @Test
    fun `rate limit retries stop after the policy limit`() = runBlocking {
        val limited = HttpException("busy", statusCode = 503)
        val harness = Harness(List(3) { ScriptedAttempt(emptyList(), errorAfter = limited) })
        try {
            harness.run(emptyList(), rateLimitRetry = RateLimitRetryPolicy(maxRetries = 2, baseDelayMillis = 1))
            fail("expected failure after retries")
        } catch (expected: HttpException) {
            assertEquals(503, expected.statusCode)
        }
        assertEquals(3, harness.provider.streamAttempts.size)
    }

    @Test
    fun `rate limited status ignores other errors`() {
        assertNull(rateLimitedStatus(HttpException("bad", statusCode = 400)))
        assertNull(rateLimitedStatus(IllegalStateException("x")))
        assertEquals(429, rateLimitedStatus(RuntimeException(HttpException("x", statusCode = 429)))?.statusCode)
        assertEquals(
            503,
            rateLimitedStatus(me.rerere.ai.provider.providers.devin.DevinApiException(503, "x"))?.statusCode,
        )
    }

    @Test
    fun `tool output file names do not collide after sanitizing`() {
        assertEquals("call_1.txt", toolOutputFileName("call_1"))
        val a = toolOutputFileName("Bash:0#abc")
        val b = toolOutputFileName("Bash#0:abc")
        assertTrue(a.startsWith("Bash0abc_") && b.startsWith("Bash0abc_"))
        assertFalse(a == b)
    }
}
