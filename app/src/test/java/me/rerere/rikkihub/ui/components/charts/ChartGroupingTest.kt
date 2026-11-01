package me.rerere.rikkihub.ui.components.charts

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.buildChartDisplayTool
import me.rerere.rikkihub.ui.components.message.MessagePartBlock
import me.rerere.rikkihub.ui.components.message.ThinkingStep
import me.rerere.rikkihub.ui.components.message.groupMessageParts
import me.rerere.rikkihub.ui.pages.chat.ImageExportOptions
import me.rerere.rikkihub.ui.pages.chat.buildConversationMarkdown
import me.rerere.rikkihub.ui.pages.chat.exportedMessageParts
import org.junit.Assert.*
import org.junit.Test

/** Actual tool execution supplies provenance; tests do not reimplement chart recognition. */
class ChartGroupingTest {
    private val input = """{"style":"line","title":"Chart title","series":[{"values":[1,2,3]}]}"""
    private fun chart(name: String = "historical_chart_alias"): UIMessagePart.Tool = runBlocking {
        UIMessagePart.Tool("chart-id", name, input,
            output = buildChartDisplayTool().execute(Json.parseToJsonElement(input)))
    }
    private fun ordinary(tool: UIMessagePart.Tool) {
        val block = listOf<UIMessagePart>(tool).groupMessageParts().single() as MessagePartBlock.ThinkingBlock
        assertEquals(listOf(ThinkingStep.ToolStep(tool)), block.steps)
    }
    @Test fun `successful renamed tool splits thinking at exact original chronology`() {
        val r1 = UIMessagePart.Reasoning("before")
        val regular = UIMessagePart.Tool("regular", "bash", "{}")
        val server = UIMessagePart.ServerTool("server", "web_search", status = ServerToolStatus.COMPLETED)
        val chart = chart()
        val r2 = UIMessagePart.Reasoning("after")
        val text = UIMessagePart.Text("answer")
        val parts = listOf(r1, regular, server, chart, r2, text)
        val blocks = parts.groupMessageParts()
        assertEquals(4, blocks.size)
        assertEquals(listOf(ThinkingStep.ReasoningStep(r1), ThinkingStep.ToolStep(regular), ThinkingStep.ServerToolStep(server)),
            (blocks[0] as MessagePartBlock.ThinkingBlock).steps)
        val projected = blocks[1] as MessagePartBlock.ChartBlock
        assertEquals(3, projected.index); assertSame(chart, projected.tool)
        assertEquals(ChartSpec.fromTool(chart), projected.spec)
        assertEquals(listOf(ThinkingStep.ReasoningStep(r2)), (blocks[2] as MessagePartBlock.ThinkingBlock).steps)
        assertEquals(MessagePartBlock.ContentBlock(text, 5), blocks[3])
        assertSame(chart, parts[3]); assertEquals("before", r1.reasoning)
    }
    @Test fun `adjacent chart executions each stay in place`() {
        val first = chart(); val second = chart().copy(toolCallId = "second")
        val blocks = listOf<UIMessagePart>(first, second).groupMessageParts()
        assertEquals(listOf(0, 1), blocks.map { (it as MessagePartBlock.ChartBlock).index })
        assertSame(first, (blocks[0] as MessagePartBlock.ChartBlock).tool)
        assertSame(second, (blocks[1] as MessagePartBlock.ChartBlock).tool)
    }
    @Test fun `approved executed charts render without consulting current registry identity`() {
        val tool = chart("not_the_current_api_name").copy(approvalState = ToolApprovalState.Approved)
        assertTrue(listOf<UIMessagePart>(tool).groupMessageParts().single() is MessagePartBlock.ChartBlock)
    }
    @Test fun `pending approved but unexecuted and denied tools remain approval thinking steps`() {
        val successful = chart()
        ordinary(successful.copy(approvalState = ToolApprovalState.Pending))
        ordinary(successful.copy(approvalState = ToolApprovalState.Denied("no")))
        ordinary(successful.copy(approvalState = ToolApprovalState.Answered("yes")))
        ordinary(successful.copy(output = emptyList(), approvalState = ToolApprovalState.Approved))
        ordinary(successful.copy(output = emptyList(), approvalState = ToolApprovalState.Pending))
    }
    @Test fun `error unmarked and MCP lookalike outputs retain old rendering`() {
        val tool = chart("chart_display")
        ordinary(tool.copy(output = listOf(UIMessagePart.Text("""{"error":"failed"}"""))))
        ordinary(tool.copy(output = listOf(UIMessagePart.Text("""{"success":true}"""))))
        ordinary(tool.copy(output = listOf(UIMessagePart.Text("opaque output"))))
    }
    @Test fun `mutated executed input cannot borrow a success marker`() {
        ordinary(chart().copy(input = input.replace("1,2,3", "3,2,1")))
        ordinary(chart().copy(input = "{bad json"))
    }
    @Test fun `marker on tool metadata rather than execution text cannot promote a chart`() {
        val tool = chart()
        ordinary(tool.copy(metadata = tool.output.first().metadata,
            output = listOf(UIMessagePart.Text("""{"success":true}"""))))
    }
    @Test fun `invalid chart tool execution remains a normal tool step`() = runBlocking {
        val badInput = """{"style":"line","series":[{"values":[]}]}"""
        ordinary(UIMessagePart.Tool("bad", "chart_display", badInput,
            output = buildChartDisplayTool().execute(Json.parseToJsonElement(badInput))))
    }
    @Test fun `reasoning exclusion runs before grouping and preserves chart plus ServerTool`() {
        val tool = chart()
        val reasoning = UIMessagePart.Reasoning("private")
        val server = UIMessagePart.ServerTool("server", "web_search", status = ServerToolStatus.COMPLETED)
        val parts = listOf(reasoning, tool, reasoning, server, UIMessagePart.Text("answer"))
        val projected = exportedMessageParts(parts, ImageExportOptions(includeReasoning = false))
        val blocks = projected.groupMessageParts()
        assertTrue(blocks[0] is MessagePartBlock.ChartBlock)
        assertEquals(0, (blocks[0] as MessagePartBlock.ChartBlock).index)
        assertEquals(listOf(ThinkingStep.ServerToolStep(server)), (blocks[1] as MessagePartBlock.ThinkingBlock).steps)
        assertEquals(5, parts.size); assertSame(reasoning, parts[0])
        assertSame(tool, projected[0])
    }
    @Test fun `ordinary markdown export retains chart tool input output identity and reasoning contract`() {
        val tool = chart()
        val output = buildConversationMarkdown("Title", "Date", listOf(UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("secret"), tool))), ImageExportOptions(includeReasoning = false))
        assertTrue(output.contains("**Tool**: `historical_chart_alias`"))
        assertTrue(output.contains("chart-id")); assertTrue(output.contains("Chart title"))
        assertTrue(output.contains("Chart displayed to the user")); assertFalse(output.contains("secret"))
    }
}
