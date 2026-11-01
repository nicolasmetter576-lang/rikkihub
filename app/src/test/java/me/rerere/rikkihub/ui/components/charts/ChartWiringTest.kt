package me.rerere.rikkihub.ui.components.charts

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Supplemental wiring guards, not substitutes for the production geometry/lifecycle tests. */
class ChartWiringTest {
    private fun source(path: String): String {
        val root = File("src/main/java/me/rerere/rikkihub").takeIf { it.isDirectory }
            ?: File("app/src/main/java/me/rerere/rikkihub")
        return File(root, path).readText()
    }
    @Test fun `both live and conversation image render parsed ChartBlock without reparsing tool name`() {
        val chat = source("ui/components/message/ChatMessage.kt")
        val export = source("ui/pages/chat/Export.kt")
        assertTrue(chat.contains("is MessagePartBlock.ChartBlock -> key(block.index)"))
        assertTrue(chat.contains("ChartCard(spec = block.spec)"))
        assertTrue(export.contains("is MessagePartBlock.ChartBlock ->"))
        assertTrue(export.contains("ChartCard(spec = block.spec, renderOnly = true)"))
        assertTrue(export.contains("LocalExportContext provides true"))
        assertTrue(export.contains("exportedMessageParts(message.parts, options)"))
    }
    @Test fun `conversation bitmap context forces chart mode and hides controls without duplicating the local`() {
        val card = source("ui/components/charts/ChartCard.kt")
        assertTrue(card.contains("import me.rerere.rikkihub.ui.components.ui.LocalExportContext"))
        assertFalse(card.contains("staticCompositionLocalOf"))
        assertTrue(card.contains("renderOnly || LocalExportContext.current"))
        assertTrue(card.contains("if (exportingConversation) ChartDisplayMode.Chart"))
        assertTrue(card.contains("if (!exportingConversation)"))
        assertTrue(card.contains("val frozen = spec.freezeForChartExport()"))
        assertTrue(card.contains("performConversationExport("))
    }
    @Test fun `standalone export is native bounded PNG with strict gallery and explicit sharing`() {
        val export = source("ui/components/charts/ChartImageExport.kt")
        assertTrue(export.contains("IMAGE_WIDTH = 1080")); assertTrue(export.contains("IMAGE_HEIGHT = 900"))
        assertTrue(export.contains("Bitmap.createBitmap(")); assertTrue(export.contains("Canvas(bitmap)"))
        assertTrue(export.contains("check(bitmap.compress(Bitmap.CompressFormat.PNG"))
        assertTrue(export.contains("throwOnFailure = true")); assertTrue(export.contains("type = \"image/png\""))
        assertTrue(export.contains("}.png\"")); assertTrue(export.contains("recycle = { it.recycle() }"))
        assertFalse(export.contains("BitmapComposer(")); assertFalse(export.contains("WebView("))
    }
    @Test fun `both renderers use clipped JVM scene and its bounded plot layout`() {
        listOf("ChartPlot.kt", "ChartImageExport.kt").forEach { file ->
            val code = source("ui/components/charts/$file")
            assertTrue(code.contains("spec.buildChartGeometry()"))
            assertTrue(code.contains("chartPlotBounds("))
            assertTrue(code.contains("series.lineSegments.forEach"))
            assertTrue(code.contains("series.bars.forEach"))
        }
    }
    @Test fun `table composes bounded production page with horizontal scrolling and fixed height`() {
        val table = source("ui/components/charts/ChartTable.kt")
        assertTrue(table.contains("spec.chartTablePage(pageIndex, seriesNames)"))
        assertTrue(table.contains("horizontalScroll(rememberScrollState())"))
        assertTrue(table.contains("heightIn(max = 280.dp)"))
        assertTrue(table.contains("R.string.chart_display_previous_page"))
        assertTrue(table.contains("R.string.chart_display_next_page"))
    }
}
