package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceReaderProgressTest {
    @Test
    fun sliderValueToPageClampsRoundsAndHandlesEdges() {
        assertEquals(0, sliderValueToPage(3f, 0))
        assertEquals(0, sliderValueToPage(3f, 1))
        assertEquals(0, sliderValueToPage(Float.NaN, 10))
        assertEquals(0, sliderValueToPage(Float.POSITIVE_INFINITY, 10))
        assertEquals(0, sliderValueToPage(-2f, 10))
        assertEquals(0, sliderValueToPage(0.4f, 10))
        assertEquals(1, sliderValueToPage(0.6f, 10))
        assertEquals(9, sliderValueToPage(15f, 10))
    }

    @Test
    fun pageAndScrollPercentagesHandleSingleAndMultiPage() {
        assertEquals(100, readerPagePercent(0, 1))
        assertEquals(100, readerPagePercent(0, 0))
        assertEquals(25, readerPagePercent(11, 48))
        assertEquals(100, readerPagePercent(47, 48))
        assertEquals(25, readerPagePercent(0, 4))

        assertEquals(0, readerScrollPercent(Float.NaN))
        assertEquals(0, readerScrollPercent(-0.2f))
        assertEquals(43, readerScrollPercent(0.426f))
        assertEquals(100, readerScrollPercent(1.2f))
    }

    @Test
    fun charOffsetAnchorsMapBackToPagesAndLines() {
        val ranges = listOf(0 until 100, 100 until 240, 240 until 400)
        assertEquals(0, pageForCharOffset(emptyList(), 50))
        assertEquals(0, pageForCharOffset(ranges, -10))
        assertEquals(0, pageForCharOffset(ranges, 0))
        assertEquals(0, pageForCharOffset(ranges, 99))
        assertEquals(1, pageForCharOffset(ranges, 100))
        assertEquals(1, pageForCharOffset(ranges, 239))
        assertEquals(2, pageForCharOffset(ranges, 240))
        assertEquals(2, pageForCharOffset(ranges, Int.MAX_VALUE))

        assertEquals(1, lineIndexForCharOffset(ranges, 150))
        assertEquals(2, lineIndexForCharOffset(ranges, Int.MAX_VALUE))
    }

    @Test
    fun verticalScrollFractionReachesZeroTopOneHundredBottomAndOneScreenIsComplete() {
        val weights = listOf(20, 40, 20, 40, 80)
        val cum = cumulativeWeights(weights)
        val total = weights.sum()

        // Whole chapter fits inside 0..500 viewport -> 100%.
        val singleScreen = listOf(
            VisibleReaderItem(0, 0, 50),
            VisibleReaderItem(1, 50, 80),
            VisibleReaderItem(2, 130, 50),
            VisibleReaderItem(3, 180, 80),
            VisibleReaderItem(4, 260, 100),
        )
        assertEquals(1f, verticalScrollFraction(singleScreen, 0, 500, weights, cum, total), 1e-4f)

        // At top of scrollable chapter (items below viewport remain).
        val atTop = listOf(
            VisibleReaderItem(0, 0, 100),
            VisibleReaderItem(1, 100, 200),
        )
        assertEquals(0f, verticalScrollFraction(atTop, 0, 200, weights, cum, total), 1e-4f)

        // Mid-chapter is strictly between 0 and 1 and increases monotonically as we scroll down.
        val mid1 = verticalScrollFraction(
            visibleItems = listOf(
                VisibleReaderItem(1, -100, 200),
                VisibleReaderItem(2, 100, 100),
            ),
            viewportStartPx = 0,
            viewportEndPx = 200,
            lineWeights = weights,
            cumStarts = cum,
            totalWeight = total,
        )
        val mid2 = verticalScrollFraction(
            visibleItems = listOf(
                VisibleReaderItem(2, -50, 100),
                VisibleReaderItem(3, 50, 200),
            ),
            viewportStartPx = 0,
            viewportEndPx = 200,
            lineWeights = weights,
            cumStarts = cum,
            totalWeight = total,
        )
        assertTrue(mid1 in 0.01f..0.99f)
        assertTrue(mid2 > mid1 && mid2 < 1f)

        // Reaching rock bottom (plus trailing non-content chapter footer at index 5) hits 1f cleanly.
        val atBottomWithFooter = listOf(
            VisibleReaderItem(3, -100, 200),
            VisibleReaderItem(4, 100, 100),
            VisibleReaderItem(5, 200, 120),
        )
        assertEquals(1f, verticalScrollFraction(atBottomWithFooter, 0, 320, weights, cum, total), 1e-4f)
    }

    @Test
    fun verticalScrollTargetMapsFractionsIncludingGiantSingleLine() {
        val weights = listOf(100, 100, 100, 100)
        val cum = cumulativeWeights(weights)
        assertEquals(0 to 0f, verticalScrollTarget(0f, weights, cum, 400, 100f))
        assertEquals(3 to 1f, verticalScrollTarget(1f, weights, cum, 400, 100f))

        // maxStart = 400 - 100 = 300; fraction 0.5 -> weight 150 -> item 1, sub-fraction 0.5
        val mid = verticalScrollTarget(0.5f, weights, cum, 400, 100f)
        assertEquals(1, mid.first)
        assertEquals(0.5f, mid.second, 1e-4f)

        // Single huge 1000-char line with 200 visible chars: scrubbing 0.5 lands at ~0.4 of that line.
        val singleHuge = listOf(1000)
        val hugeMid = verticalScrollTarget(0.5f, singleHuge, cumulativeWeights(singleHuge), 1000, 200f)
        assertEquals(0, hugeMid.first)
        assertEquals(0.4f, hugeMid.second, 1e-4f)
    }

    @Test
    fun buildReaderDocumentPreservesLineSlicesAndBlankLines() {
        val doc = buildReaderDocument(
            content = "## 标题\n第一行 “对话”\n\n结尾",
            markdown = true,
            style = TextStyle(fontSize = 16.sp),
            quoted = Color.Red,
        )
        assertEquals(listOf("标题", "第一行 “对话”", "", "结尾"), doc.lineRanges.map { doc.text.text.substring(it) })

        val emptyDoc = buildReaderDocument("", markdown = false, style = TextStyle(fontSize = 16.sp), quoted = Color.Unspecified)
        assertEquals(listOf(""), emptyDoc.lineRanges.map { emptyDoc.text.text.substring(it) })
    }

    @Test
    fun scrollModeLinesKeepTheirLastCharacterAndStyles() {
        val doc = buildReaderDocument(
            content = "## 标题\n第一段。\n\n“对话”\nx",
            markdown = true,
            style = TextStyle(fontSize = 16.sp),
            quoted = Color.Red,
        )
        assertEquals(listOf("标题", "第一段。", "", "“对话”", "x"), doc.lineRanges.indices.map { doc.line(it).text })
        assertTrue(doc.line(0).spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.end == 2 })
        assertTrue(doc.line(3).spanStyles.any { it.item.color == Color.Red && it.start == 0 && it.end == 4 })
        assertEquals("", buildReaderDocument("", markdown = false, style = TextStyle(), quoted = Color.Unspecified).line(0).text)
    }
}
