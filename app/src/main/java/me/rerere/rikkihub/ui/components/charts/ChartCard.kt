package me.rerere.rikkihub.ui.components.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.ui.components.ui.LocalExportContext
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.pages.chat.performConversationExport

/** Only affects chart presentation; no persisted setting or message mutation. */
enum class ChartDisplayMode { Chart, Table }

@Composable
fun ChartCard(spec: ChartSpec, modifier: Modifier = Modifier, renderOnly: Boolean = false) {
    val exportingConversation = renderOnly || LocalExportContext.current
    var mode by rememberSaveable(spec) { mutableStateOf(ChartDisplayMode.Chart) }
    var exporting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val success = stringResource(R.string.chart_display_export_success)
    val fallbackTitle = stringResource(R.string.chart_display_title)
    val seriesNames = spec.series.take(MAX_CHART_SERIES).mapIndexed { index, series ->
        series.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chart_display_series_name, index + 1)
    }
    val activeMode = if (exportingConversation) ChartDisplayMode.Chart else mode
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(spec.title?.takeIf { it.isNotBlank() }?.take(240) ?: fallbackTitle, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!exportingConversation) {
                Row {
                    TextButton(onClick = { mode = ChartDisplayMode.Chart }, enabled = activeMode != ChartDisplayMode.Chart) {
                        Text(stringResource(R.string.chart_display_mode_chart))
                    }
                    TextButton(onClick = { mode = ChartDisplayMode.Table }, enabled = activeMode != ChartDisplayMode.Table) {
                        Text(stringResource(R.string.chart_display_mode_table))
                    }
                }
            }
            if (activeMode == ChartDisplayMode.Chart) {
                spec.yAxis.title?.let { Text(it.take(160), style = MaterialTheme.typography.labelMedium, maxLines = 2) }
                ChartPlot(spec, Modifier.fillMaxWidth().height(240.dp))
                spec.xAxis.title?.let { Text(it.take(160), style = MaterialTheme.typography.labelMedium, maxLines = 2) }
                // One bounded line per series, also fully represented in standalone PNG exports.
                seriesNames.forEachIndexed { index, name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).background(Color(chartSeriesColor(spec.series[index].color, index))))
                        Text(name.take(160), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                ChartTable(spec, seriesNames)
            }
            if (!exportingConversation) {
                TextButton(enabled = !exporting, onClick = {
                    if (!exporting) {
                        val frozen = spec.freezeForChartExport()
                        val names = seriesNames.toList()
                        exporting = true
                        scope.launch {
                            try {
                                performConversationExport(
                                    export = { exportChartImage(context, frozen, names, fallbackTitle) },
                                    onSuccess = { toaster.show(success, type = ToastType.Success) },
                                    onFailure = { error ->
                                        toaster.show(context.getString(R.string.chart_display_export_failed,
                                            error.message?.take(160) ?: error.javaClass.simpleName), type = ToastType.Error)
                                    },
                                )
                            } finally { exporting = false }
                        }
                    }
                }) { Text(stringResource(R.string.chart_display_export_image)) }
            }
        }
    }
}
