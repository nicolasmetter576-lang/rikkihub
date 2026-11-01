package me.rerere.rikkihub.ui.components.charts

import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle
import java.util.Locale

internal const val CHART_TABLE_PAGE_SIZE = 50
internal data class ChartTablePage(
    val pageIndex: Int,
    val pageCount: Int,
    val totalRows: Int,
    val rows: List<List<String>>,
)

/** Project only one page, never build a 24,000-row composition or intermediate formatted table. */
internal fun ChartSpec.chartTablePage(
    requestedPage: Int, seriesNames: List<String>, locale: Locale = Locale.getDefault(),
): ChartTablePage {
    val boundedSeries = series.take(MAX_CHART_SERIES)
    val scatter = style == ChartStyle.Scatter
    val total = if (scatter) boundedSeries.sumOf { minOf(it.points.size, MAX_CHART_ROWS) }
        else categoryCount.coerceIn(0, MAX_CHART_ROWS)
    val pages = maxOf(1, (total + CHART_TABLE_PAGE_SIZE - 1) / CHART_TABLE_PAGE_SIZE)
    val page = requestedPage.coerceIn(0, pages - 1)
    val start = page * CHART_TABLE_PAGE_SIZE
    val end = minOf(total, start + CHART_TABLE_PAGE_SIZE)
    val rows = if (scatter) buildList {
        var offset = 0
        boundedSeries.forEachIndexed { seriesIndex, s ->
            val count = minOf(s.points.size, MAX_CHART_ROWS)
            val from = maxOf(0, start - offset)
            val to = minOf(count, end - offset)
            for (index in from until maxOf(from, to)) {
                val p = s.points[index]
                add(listOf((seriesNames.getOrNull(seriesIndex) ?: s.name.orEmpty()).take(160),
                    formatChartNumber(p.x, xAxis.format, locale), formatChartNumber(p.y, yAxis.format, locale)))
            }
            offset += count
        }
    } else (start until end).map { index ->
        listOf(categoryLabel(index).take(160)) + boundedSeries.map { s ->
            s.values.getOrNull(index)?.let { formatChartNumber(it, yAxis.format, locale) } ?: "—"
        }
    }
    return ChartTablePage(page, pages, total, rows)
}
