package me.rerere.rikkihub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.*
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.applyBuiltinOverride
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Offline real provider -> stream handler -> GenerationLoop -> actual chart tool -> persisted parts. */
class ChartGenerationLoopTest {
    @get:Rule val temp = TemporaryFolder()
    private val input = """{"style":"line","series":[{"values":[1,2]}]}"""
    private inner class Harness(scripts: List<ScriptedAttempt>) {
        val directory = temp.newFolder()
        val provider = ScriptedProvider(ArrayDeque(scripts))
        val model = testChatModel()
        val settings = testSettings(model)
        val loop = testGenerationLoop(provider, FakeContext(directory))
        suspend fun run(tools: List<Tool>, messages: List<UIMessage> = listOf(UIMessage.user("plot"))) =
            loop.generateText(settings, model, messages, assistant = testAssistant(), tools = tools, maxSteps = 10).toList()
    }
    private fun List<GenerationChunk>.messages() = filterIsInstance<GenerationChunk.Messages>().last().messages
    private fun List<GenerationChunk>.tools() = messages().flatMap { it.getTools() }
    private fun List<GenerationChunk>.end() = filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
    private fun renamed() = buildChartDisplayTool().applyBuiltinOverride(mapOf(
        BuiltinToolIds.CHART_DISPLAY to ToolOverride(name = "plot_trend")))

    @Test fun `renamed actual chart tool executes only once and has no premature progress marker`() = runBlocking {
        val h = Harness(listOf(toolStepScript("chart", "plot_trend", input), textStepScript("done")))
        val actual = renamed()
        var calls = 0
        val chunks = h.run(listOf(actual.copy(execute = { args -> calls++; actual.execute(args) })))
        assertEquals(1, calls)
        assertEquals(2, h.provider.streamAttempts.size)
        // Capture the actual provider request, not a separately manufactured schema.
        val requested = h.provider.streamParams.first().tools.single()
        assertEquals("plot_trend", requested.name)
        val requestSchema = (requested.parameters() as InputSchema.Obj).properties
        fun simpleWire(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    assertTrue(element.keys.intersect(setOf("if", "then", "else", "oneOf", "anyOf", "allOf", "const")).isEmpty())
                    element.values.forEach(::simpleWire)
                }
                is JsonArray -> element.forEach(::simpleWire)
                else -> Unit
            }
        }
        simpleWire(requestSchema)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end())
        assertEquals(2, chunks.filterIsInstance<GenerationChunk.StepFinished>().size)
        val chart = chunks.tools().single()
        assertEquals("plot_trend", chart.toolName)
        assertEquals(input, chart.input)
        assertNull(chart.metadata)
        assertNotNull(ChartSpec.fromTool(chart))
        chunks.filterIsInstance<GenerationChunk.Messages>().flatMap { it.messages }.flatMap { it.getTools() }
            .filterNot { it.isExecuted }.forEach { assertNull(ChartSpec.fromTool(it)) }
        assertTrue(h.directory.listFiles().orEmpty().isEmpty()) // no chart file/image output
    }

    @Test fun `strict approval pauses then approved resume executes actual renamed chart`() = runBlocking {
        val h = Harness(listOf(toolStepScript("chart", "plot_trend", input), textStepScript("approved")))
        val actual = renamed()
        var calls = 0
        val tool = actual.copy(needsApproval = { true }, execute = { args -> calls++; actual.execute(args) })
        val paused = h.run(listOf(tool))
        assertEquals(GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL, paused.end())
        assertEquals(0, calls)
        val pending = paused.tools().single()
        assertEquals(ToolApprovalState.Pending, pending.approvalState)
        assertFalse(pending.isExecuted)
        assertNull(ChartSpec.fromTool(pending))
        val approvedMessages = paused.messages().map { message -> message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(approvalState = ToolApprovalState.Approved) else part
        }) }
        val resumed = h.run(listOf(tool), approvedMessages)
        assertEquals(1, calls)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, resumed.end())
        assertNotNull(ChartSpec.fromTool(resumed.tools().single()))
    }

    @Test fun `denied resume returns executed denial without chart marker or body execution`() = runBlocking {
        val h = Harness(listOf(textStepScript("denied")))
        var calls = 0
        val actual = renamed()
        val tool = actual.copy(needsApproval = { true }, execute = { args -> calls++; actual.execute(args) })
        val denied = UIMessagePart.Tool("chart", tool.name, input, approvalState = ToolApprovalState.Denied("not allowed"))
        val chunks = h.run(listOf(tool), listOf(UIMessage.user("plot"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(denied))))
        assertEquals(0, calls)
        val result = chunks.tools().single()
        assertTrue(result.isExecuted)
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("denied"))
        assertNull((result.output.single() as UIMessagePart.Text).metadata)
        assertNull(ChartSpec.fromTool(result))
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end())
    }

    @Test fun `invalid chart is rejected without marker then valid correction runs next step`() = runBlocking {
        val bad = input.replace("[1,2]", "[\"1\",2]")
        val h = Harness(listOf(toolStepScript("bad", "plot_trend", bad),
            toolStepScript("good", "plot_trend", input), textStepScript("corrected")))
        val chunks = h.run(listOf(renamed()))
        val parts = chunks.tools()
        assertEquals(2, parts.size)
        assertNull(ChartSpec.fromTool(parts[0]))
        assertTrue((parts[0].output.single() as UIMessagePart.Text).text.contains("INVALID_CHART"))
        assertNull((parts[0].output.single() as UIMessagePart.Text).metadata)
        assertNotNull(ChartSpec.fromTool(parts[1]))
        assertEquals(3, chunks.filterIsInstance<GenerationChunk.StepFinished>().size)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end())
    }

    @Test fun `old wire name is not fuzzy dispatched to current renamed tool`() = runBlocking {
        val h = Harness(listOf(toolStepScript("old", "chart_display", input), textStepScript("unavailable")))
        var calls = 0
        val actual = renamed()
        val chunks = h.run(listOf(actual.copy(execute = { args -> calls++; actual.execute(args) })))
        assertEquals(0, calls)
        assertNull(ChartSpec.fromTool(chunks.tools().single()))
        assertTrue((chunks.tools().single().output.single() as UIMessagePart.Text).text.contains("not found"))
    }

    @Test fun `execution exception produces loop error with no success marker`() = runBlocking {
        val h = Harness(listOf(toolStepScript("failure", "plot_trend", input), textStepScript("error")))
        val chunks = h.run(listOf(renamed().copy(execute = { throw IllegalStateException("test failure") })))
        val result = chunks.tools().single()
        assertTrue(result.isExecuted)
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("test failure"))
        assertNull((result.output.single() as UIMessagePart.Text).metadata)
        assertNull(ChartSpec.fromTool(result))
    }

    @Test fun `execution cancellation propagates without generation end or success marker`() = runBlocking {
        val h = Harness(listOf(toolStepScript("cancelled", "plot_trend", input)))
        val chunks = mutableListOf<GenerationChunk>()
        try {
            h.loop.generateText(h.settings, h.model, listOf(UIMessage.user("plot")), assistant = testAssistant(),
                tools = listOf(renamed().copy(execute = { throw CancellationException("test cancel") })))
                .toList(chunks)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(chunks.filterIsInstance<GenerationChunk.GenerationEnd>().isEmpty())
        chunks.filterIsInstance<GenerationChunk.Messages>().flatMap { it.messages }.flatMap { it.getTools() }
            .forEach { assertNull(ChartSpec.fromTool(it)); assertFalse(it.isExecuted) }
    }

    @Test fun `existing truncation preserves first Text success metadata and original chart input`() = runBlocking {
        val h = Harness(listOf(toolStepScript("truncate", "plot_trend", input), textStepScript("done")))
        val actual = renamed()
        val padded = actual.copy(execute = { args ->
            val success = actual.execute(args).single() as UIMessagePart.Text
            listOf(success.copy(text = success.text + "x".repeat(40 * 1024)))
        })
        // Fixed-caller legacy truncation requires shell readback. This echo fixture only enables
        // that existing branch; the real chart tool itself always returns a tiny result.
        val chunks = h.run(listOf(padded, echoTool(BuiltinToolIds.WORKSPACE_SHELL)))
        val chart = chunks.tools().single()
        assertTrue((chart.output.single() as UIMessagePart.Text).text.length < 10 * 1024)
        assertEquals(input, chart.input)
        assertNotNull((chart.output.single() as UIMessagePart.Text).metadata?.get(CHART_SUCCESS_KEY))
        assertNotNull(ChartSpec.fromTool(chart))
    }
}
