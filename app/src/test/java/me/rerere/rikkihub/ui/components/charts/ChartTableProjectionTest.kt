package me.rerere.rikkihub.ui.components.charts

import me.rerere.rikkihub.data.ai.tools.local.ChartAxis
import me.rerere.rikkihub.data.ai.tools.local.ChartPoint
import me.rerere.rikkihub.data.ai.tools.local.ChartSeries
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ChartTableProjectionTest {
    private fun categories(count: Int) = ChartSpec(ChartStyle.Line,
        xAxis = ChartAxis(data = List(count) { "C$it" }),
        series = listOf(ChartSeries(values = List(count) { it.toDouble() }),
            ChartSeries(values = List(count) { -it.toDouble() })))
    @Test fun `category page has labels and every series value in order`() {
        val page = categories(52).chartTablePage(1, listOf("A", "B"), Locale.US)
        assertEquals(2, page.pageCount)
        assertEquals(52, page.totalRows)
        assertEquals(listOf(listOf("C50", "50", "-50"), listOf("C51", "51", "-51")), page.rows)
    }
    @Test fun `at most fifty rows compose for any page`() {
        val spec = categories(2000)
        (0 until 40).forEach { assertEquals(50, spec.chartTablePage(it, emptyList()).rows.size) }
    }
    @Test fun `all category rows are reachable exactly once across pages`() {
        val spec = categories(117)
        val rows = (0 until 3).flatMap { spec.chartTablePage(it, emptyList(), Locale.US).rows }
        assertEquals(List(117) { "C$it" }, rows.map { it.first() })
        assertEquals(117, rows.distinct().size)
    }
    @Test fun `invalid page requests clamp to first or last`() {
        assertEquals(0, categories(51).chartTablePage(Int.MIN_VALUE, emptyList()).pageIndex)
        assertEquals(1, categories(51).chartTablePage(Int.MAX_VALUE, emptyList()).pageIndex)
    }
    @Test fun `scatter flattening spans a series boundary without skipped data`() {
        val spec = ChartSpec(ChartStyle.Scatter, series = listOf(
            ChartSeries(points = List(49) { ChartPoint(it.toDouble(), -it.toDouble()) }),
            ChartSeries(points = List(3) { ChartPoint(100.0 + it, 200.0 + it) })))
        val first = spec.chartTablePage(0, listOf("one", "two"), Locale.US)
        val second = spec.chartTablePage(1, listOf("one", "two"), Locale.US)
        assertEquals(listOf("two", "100", "200"), first.rows.last())
        assertEquals(listOf(listOf("two", "101", "201"), listOf("two", "102", "202")), second.rows)
    }
    @Test fun `maximum scatter chart remains paged with all data accessible`() {
        val spec = ChartSpec(ChartStyle.Scatter, series = List(12) { series ->
            ChartSeries(name = "S$series", points = List(2000) { ChartPoint(it.toDouble(), series.toDouble()) })
        })
        val first = spec.chartTablePage(0, emptyList(), Locale.US)
        assertEquals(24000, first.totalRows); assertEquals(480, first.pageCount)
        val last = spec.chartTablePage(479, emptyList(), Locale.US)
        assertEquals(listOf("S11", "1999", "11"), last.rows.last())
        assertEquals(24000, (0 until first.pageCount).sumOf { spec.chartTablePage(it, emptyList()).rows.size })
    }
    @Test fun `uneven public series retain missing values and category fallbacks`() {
        val spec = ChartSpec(ChartStyle.Bar, series = listOf(ChartSeries(values = listOf(1.0)),
            ChartSeries(values = listOf(2.0, 3.0))))
        assertEquals(listOf(listOf("1", "1", "2"), listOf("2", "—", "3")),
            spec.chartTablePage(0, emptyList(), Locale.US).rows)
    }
    @Test fun `table uses axis formats and bounded plain text`() {
        val spec = ChartSpec(ChartStyle.Scatter, xAxis = ChartAxis(format = ".2f"), yAxis = ChartAxis(format = ".0%"),
            series = listOf(ChartSeries(points = listOf(ChartPoint(1.25, 0.5)))))
        assertEquals(listOf("name", "1.25", "50%"), spec.chartTablePage(0, listOf("name"), Locale.US).rows.single())
    }
    @Test fun `empty and oversized public data projections are bounded`() {
        val empty = ChartSpec(ChartStyle.Scatter, series = emptyList()).chartTablePage(10, emptyList())
        assertEquals(1, empty.pageCount); assertEquals(0, empty.pageIndex); assertTrue(empty.rows.isEmpty())
        val oversized = categories(3000).chartTablePage(999, emptyList())
        assertEquals(2000, oversized.totalRows); assertEquals(40, oversized.pageCount)
    }
}
