package me.rerere.rikkihub.web.dto

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.local.buildChartDisplayTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Server-side chart projection for the web; provenance comes from real tool execution. */
class ChartWebDtoTest {
    private val input =
        """{"style":"bar","title":"Sales","x_axis":{"data":["Q1","Q2"]},"y_axis":{"format":",.1f"},""" +
            """"series":[{"name":"2025","color":"#ff0000","values":[1.50,-2]}]}"""

    private fun executed(input: String = this.input, name: String = "chart_display") = runBlocking {
        UIMessagePart.Tool("call-1", name, input, output = buildChartDisplayTool().execute(Json.parseToJsonElement(input)))
    }

    private fun message(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `a successful chart call is projected with its part index and normalized spec`() {
        val charts = message(UIMessagePart.Text("before"), executed()).toDto().charts!!
        assertEquals(1, charts.single().partIndex)
        val spec = charts.single().spec
        assertEquals("bar", spec.style)
        assertEquals("Sales", spec.title)
        assertEquals(listOf("Q1", "Q2"), spec.xAxis.data)
        assertEquals(",.1f", spec.yAxis.format)
        assertEquals("linear", spec.yAxis.scale)
        assertEquals(listOf(1.5, -2.0), spec.series.single().values)
        assertEquals("#ff0000", spec.series.single().color)
    }

    @Test
    fun `renamed historical calls still count but unmarked unexecuted rejected or tampered ones do not`() {
        assertEquals(1, message(executed(name = "old_alias")).toDto().charts?.size)

        val ok = executed()
        val tampered = ok.copy(input = input.replace("1.50", "9"))
        val unexecuted = UIMessagePart.Tool("call-2", "chart_display", input)
        val rejected = ok.copy(approvalState = ToolApprovalState.Denied("no"))
        val plain = UIMessagePart.Tool("call-3", "bash", "{}", output = listOf(UIMessagePart.Text("done")))
        listOf(tampered, unexecuted, rejected, plain).forEach { part ->
            assertNull(part.toString(), message(part).toDto().charts)
        }
    }

    @Test
    fun `messages without charts keep the field null and repeated projection is stable`() {
        assertNull(message(UIMessagePart.Text("hi")).toDto().charts)
        val part = executed()
        val first = WebChartProjection.project(part)
        assertEquals(first, WebChartProjection.project(part))
    }
}
