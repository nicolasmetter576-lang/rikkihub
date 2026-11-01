package me.rerere.rikkihub.ui.components.charts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle

@Composable
internal fun ChartTable(spec: ChartSpec, seriesNames: List<String>) {
    var pageIndex by rememberSaveable(spec) { mutableStateOf(0) }
    val page = spec.chartTablePage(pageIndex, seriesNames)
    val headers = if (spec.style == ChartStyle.Scatter) listOf(
        stringResource(R.string.chart_display_category),
        spec.xAxis.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chart_display_x),
        spec.yAxis.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chart_display_y),
    ) else listOf(spec.xAxis.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chart_display_category)) + seriesNames
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(headers, header = true)
            HorizontalDivider()
            // Page size <=50, plus a fixed viewport: no full-data scrolling/huge export bitmap.
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                page.rows.forEach { TableRow(it) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { pageIndex = page.pageIndex - 1 }, enabled = page.pageIndex > 0) {
                Text(stringResource(R.string.chart_display_previous_page))
            }
            Text(stringResource(R.string.chart_display_page, page.pageIndex + 1, page.pageCount),
                style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { pageIndex = page.pageIndex + 1 }, enabled = page.pageIndex + 1 < page.pageCount) {
                Text(stringResource(R.string.chart_display_next_page))
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    Row {
        cells.forEach { cell ->
            Text(cell.take(160), Modifier.width(144.dp).padding(8.dp),
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
