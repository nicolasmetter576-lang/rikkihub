package me.rerere.rikkihub.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle

/** Native Compose Canvas; all coordinates and ticks come from the JVM/bitmap-shared projection. */
@Composable
internal fun ChartPlot(spec: ChartSpec, modifier: Modifier = Modifier) {
    val geometry = remember(spec) { spec.buildChartGeometry() }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val plot = chartPlotBounds(size.width, size.height)
        fun label(text: String, x: Float, y: Float, maxWidth: Float, centered: Boolean = false) {
            val layout = measurer.measure(text.take(80), labelStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxWidth.toInt().coerceIn(1, 4096)))
            val left = (if (centered) x - layout.size.width / 2f else x)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(left, y.coerceAtLeast(0f)))
        }
        geometry.yTicks.forEach { tick ->
            val y = plot.y(1.0 - tick.fraction)
            drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
            label(tick.label, 0f, y - labelStyle.fontSize.toPx() / 2, plot.left - 4.dp.toPx())
        }
        geometry.xTicks.forEach { tick ->
            val x = plot.x(tick.fraction)
            label(tick.label, x, plot.bottom + 4.dp.toPx(),
                size.width / geometry.xTicks.size.coerceAtLeast(1), centered = true)
        }
        drawLine(axisColor, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), 1.dp.toPx())
        drawLine(axisColor, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1.dp.toPx())
        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            geometry.series.forEach { series ->
                val color = Color(series.color)
                when (geometry.style) {
                    ChartStyle.Bar -> series.bars.forEach { bar ->
                        drawRect(color, topLeft = Offset(plot.x(bar.left), plot.y(bar.top)),
                            size = Size((plot.x(bar.right) - plot.x(bar.left)).coerceAtLeast(0f),
                                (plot.y(bar.bottom) - plot.y(bar.top)).coerceAtLeast(0f)))
                    }
                    ChartStyle.Scatter -> series.points.forEach { point ->
                        if (point != null) drawCircle(color, 3.dp.toPx(), Offset(plot.x(point.x), plot.y(point.y)))
                    }
                    ChartStyle.Line -> {
                        series.lineSegments.forEach { segment ->
                            drawLine(color, Offset(plot.x(segment.start.x), plot.y(segment.start.y)),
                                Offset(plot.x(segment.end.x), plot.y(segment.end.y)), 2.dp.toPx())
                        }
                        // Only original in-range points get markers, never clipping intersections.
                        if (series.points.size <= 100) series.points.forEach { point ->
                            if (point != null) drawCircle(color, 2.5.dp.toPx(), Offset(plot.x(point.x), plot.y(point.y)))
                        }
                    }
                }
            }
        }
    }
}
