package me.rerere.rikkihub.ui.components.charts

import me.rerere.rikkihub.data.ai.tools.local.ChartScaleType
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

internal const val MAX_CHART_SERIES = 12
internal const val MAX_CHART_ROWS = 2000
internal const val MAX_AXIS_TICKS = 6
internal const val MAX_CATEGORY_TICKS = 8
private const val MAX_NUMBER = 1e100

/** Independently protects drawing from invalid public model constructors, not only JSON input. */
internal fun safeChartNumber(value: Double): Boolean =
    value.isFinite() && abs(value) <= MAX_NUMBER && (value == 0.0 || abs(value) >= 1e-100)

internal data class ChartTick(val fraction: Double, val label: String)
internal data class ChartCoordinate(val x: Double, val y: Double)
internal data class ChartBar(val left: Double, val right: Double, val top: Double, val bottom: Double)
internal data class ChartLineSegment(val start: ChartCoordinate, val end: ChartCoordinate)
internal data class ProjectedSeries(
    val color: Int,
    /** Only visible markers; out-of-range scatter/line points are never moved onto borders. */
    val points: List<ChartCoordinate?>,
    val bars: List<ChartBar>,
    val lineSegments: List<ChartLineSegment>,
)
internal data class ChartGeometry(
    val style: ChartStyle,
    val xTicks: List<ChartTick>,
    val yTicks: List<ChartTick>,
    val series: List<ProjectedSeries>,
)

/** Finite Double scene coordinates. Clipping happens before any conversion to drawing Floats. */
internal class ChartAxisScale internal constructor(val min: Double, val max: Double, val log: Boolean) {
    private val lo = if (log) log10(min) else min
    private val hi = if (log) log10(max) else max
    private val divisor = maxOf(abs(lo), abs(hi), 1e-100)
    fun fraction(value: Double): Double {
        if (!safeChartNumber(value) || (log && value <= 0.0)) return 0.5
        val v = if (log) log10(value) else value
        val span = (hi - lo) / divisor
        if (span <= 0.0 || !span.isFinite()) return 0.5
        val result = ((v - lo) / divisor) / span
        // Numeric input/bounds are independently bounded to 1e100; intermediates fit Double.
        return if (result.isFinite()) result else 0.5
    }
    fun valueAt(fraction: Double): Double {
        val f = fraction.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5
        val v = lo * (1.0 - f) + hi * f
        return if (log) 10.0.pow(v).coerceIn(1e-100, MAX_NUMBER) else v
    }
    fun ticks(format: String?): List<ChartTick> = (0 until MAX_AXIS_TICKS).map { index ->
        val fraction = index.toDouble() / (MAX_AXIS_TICKS - 1)
        ChartTick(fraction, formatChartNumber(valueAt(fraction), format))
    }
}

internal fun buildAxisScale(
    values: List<Double>, userMin: Double? = null, userMax: Double? = null,
    log: Boolean = false, includeZero: Boolean = false,
): ChartAxisScale {
    val valid = values.take(MAX_CHART_SERIES * MAX_CHART_ROWS)
        .filter { safeChartNumber(it) && (!log || it > 0.0) }
    fun bound(v: Double?) = v?.takeIf { safeChartNumber(it) && (!log || it > 0.0) }
    var low = bound(userMin) ?: valid.minOrNull() ?: if (log) 1.0 else 0.0
    var high = bound(userMax) ?: valid.maxOrNull() ?: if (log) 10.0 else 1.0
    if (includeZero && !log) {
        low = minOf(low, 0.0)
        high = maxOf(high, 0.0)
    }
    if (low > high) {
        low = valid.minOrNull() ?: if (log) 1.0 else 0.0
        high = valid.maxOrNull() ?: if (log) 10.0 else 1.0
        if (includeZero && !log) { low = minOf(low, 0.0); high = maxOf(high, 0.0) }
    }
    if (low == high || (log && log10(low) == log10(high))) {
        if (log) {
            val exponent = log10(low)
            low = 10.0.pow((exponent - 0.5).coerceAtLeast(-100.0))
            high = 10.0.pow((exponent + 0.5).coerceAtMost(100.0))
        } else {
            val pad = if (low == 0.0) 1.0 else abs(low) * 0.1
            high = (high + pad).coerceAtMost(MAX_NUMBER)
            low = (low - pad).coerceAtLeast(-MAX_NUMBER)
        }
    }
    return ChartAxisScale(low, high, log)
}

internal val chartPalette = listOf(
    0xFF4A7FD6.toInt(), 0xFFE2774A.toInt(), 0xFF4FAE7B.toInt(), 0xFF9B6FD1.toInt(),
    0xFFD9A93A.toInt(), 0xFFD65C8A.toInt(), 0xFF3FA7B5.toInt(), 0xFFB0694E.toInt(),
    0xFF7D9A3E.toInt(), 0xFF5F7896.toInt(), 0xFFE0605F.toInt(), 0xFF8C8C8C.toInt(),
)
internal fun chartSeriesColor(text: String?, index: Int): Int {
    if (text != null && Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$").matches(text)) {
        val raw = text.drop(1)
        val hex = if (raw.length == 3) raw.map { "$it$it" }.joinToString("") else raw
        return (0xFF000000L or hex.toLong(16)).toInt()
    }
    return chartPalette[Math.floorMod(index, chartPalette.size)]
}

internal fun ChartSpec.buildChartGeometry(): ChartGeometry {
    val boundedSeries = series.take(MAX_CHART_SERIES)
    val scatter = style == ChartStyle.Scatter
    // Public constructors can bypass backend's prohibition of logarithmic bars.
    val yLog = yAxis.scale == ChartScaleType.Log && style != ChartStyle.Bar
    val yValues = boundedSeries.flatMap { s ->
        if (scatter) s.points.take(MAX_CHART_ROWS).map { it.y } else s.values.take(MAX_CHART_ROWS)
    }
    val yScale = buildAxisScale(yValues, yAxis.min, yAxis.max, yLog, style == ChartStyle.Bar)
    val xScale = if (scatter) buildAxisScale(
        boundedSeries.flatMap { it.points.take(MAX_CHART_ROWS).map { p -> p.x } },
        xAxis.min, xAxis.max, xAxis.scale == ChartScaleType.Log,
    ) else null
    val categories = categoryCount.coerceIn(0, MAX_CHART_ROWS)
    fun categoryX(index: Int): Double = when {
        style == ChartStyle.Bar -> (index + 0.5) / categories.coerceAtLeast(1)
        categories <= 1 -> 0.5
        else -> index.toDouble() / (categories - 1)
    }
    val xTicks = if (xScale != null) xScale.ticks(xAxis.format) else {
        val count = minOf(categories, MAX_CATEGORY_TICKS)
        (0 until count).map { tick ->
            val index = if (count <= 1) 0 else tick * (categories - 1) / (count - 1)
            ChartTick(categoryX(index), categoryLabel(index).take(80))
        }
    }
    val projected = boundedSeries.mapIndexed { seriesIndex, s ->
        val color = chartSeriesColor(s.color, seriesIndex)
        val rawPoints = if (scatter) s.points.take(MAX_CHART_ROWS).map { p ->
            if (!safeChartNumber(p.x) || !safeChartNumber(p.y) ||
                (xAxis.scale == ChartScaleType.Log && p.x <= 0.0) || (yLog && p.y <= 0.0)) null
            else ChartCoordinate(xScale!!.fraction(p.x), 1.0 - yScale.fraction(p.y))
        } else s.values.take(MAX_CHART_ROWS).mapIndexed { index, value ->
            if (!safeChartNumber(value) || (yLog && value <= 0.0)) null
            else ChartCoordinate(categoryX(index), 1.0 - yScale.fraction(value))
        }
        val points = rawPoints.map { point -> point?.takeIf { it.isInChartViewport() } }
        val segments = if (style == ChartStyle.Line) rawPoints.zipWithNext().mapNotNull { (start, end) ->
            if (start == null || end == null) null else clipChartLineSegment(start, end)
        } else emptyList()
        val bars = if (style == ChartStyle.Bar) rawPoints.mapIndexedNotNull { index, point ->
            point ?: return@mapIndexedNotNull null
            val width = 0.8 / categories.coerceAtLeast(1) / boundedSeries.size.coerceAtLeast(1)
            val left = (index + 0.1) / categories.coerceAtLeast(1) + seriesIndex * width
            val baseline = 1.0 - yScale.fraction(0.0)
            clipChartBar(ChartBar(left, left + width, minOf(point.y, baseline), maxOf(point.y, baseline)))
        } else emptyList()
        ProjectedSeries(color, points, bars, segments)
    }
    return ChartGeometry(style, xTicks, yScale.ticks(yAxis.format), projected)
}

internal fun ChartCoordinate.isInChartViewport(): Boolean =
    x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0

/** Liang–Barsky clipping with exactly four bounds, no unbounded subdivision/iteration. */
internal fun clipChartLineSegment(start: ChartCoordinate, end: ChartCoordinate): ChartLineSegment? {
    if (!start.x.isFinite() || !start.y.isFinite() || !end.x.isFinite() || !end.y.isFinite()) return null
    val dx = end.x - start.x
    val dy = end.y - start.y
    if (!dx.isFinite() || !dy.isFinite()) return null
    var enter = 0.0
    var exit = 1.0
    var enterEdge = -1
    var exitEdge = -1
    val p = doubleArrayOf(-dx, dx, -dy, dy)
    val q = doubleArrayOf(start.x, 1.0 - start.x, start.y, 1.0 - start.y)
    for (index in 0..3) {
        if (p[index] == 0.0) {
            if (q[index] < 0.0) return null
        } else {
            val t = q[index] / p[index]
            if (p[index] < 0.0 && t > enter) { enter = t; enterEdge = index }
            if (p[index] > 0.0 && t < exit) { exit = t; exitEdge = index }
            if (enter > exit) return null
        }
    }
    fun at(t: Double, edge: Int): ChartCoordinate {
        // Retain the exact intersected edge. At extreme ranges enter/exit can round to the
        // same t; cancelling huge coordinates must not collapse a visible crossing to a dot.
        val x = when (edge) { 0 -> 0.0; 1 -> 1.0; else -> start.x + t * dx }
        val y = when (edge) { 2 -> 0.0; 3 -> 1.0; else -> start.y + t * dy }
        return ChartCoordinate(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
    }
    return ChartLineSegment(at(enter, enterEdge), at(exit, exitEdge))
}

internal fun clipChartBar(bar: ChartBar): ChartBar? {
    if (listOf(bar.left, bar.right, bar.top, bar.bottom).any { !it.isFinite() }) return null
    val left = maxOf(0.0, bar.left)
    val right = minOf(1.0, bar.right)
    val top = maxOf(0.0, bar.top)
    val bottom = minOf(1.0, bar.bottom)
    if (left > right || top > bottom) return null
    return ChartBar(left, right, top, bottom)
}

/** Shared fixed-margin plot layout for both Compose and the standalone Android PNG. */
internal data class ChartPlotBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    private fun safeFraction(value: Double) = value.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0.5f
    // Scene clipping is already complete. These guards only protect accidental invalid callers.
    fun x(fraction: Double): Float = left + safeFraction(fraction) * width
    fun y(fraction: Double): Float = top + safeFraction(fraction) * height
}
internal fun chartPlotBounds(width: Float, height: Float): ChartPlotBounds {
    val w = width.takeIf { it.isFinite() }?.coerceIn(1f, 4096f) ?: 1f
    val h = height.takeIf { it.isFinite() }?.coerceIn(1f, 4096f) ?: 1f
    return ChartPlotBounds(w * 0.2f, h * 0.05f, w * 0.96f, h * 0.82f)
}

/** Copy every collection at click time; mutable programmatic lists must not change an export. */
internal fun ChartSpec.freezeForChartExport(): ChartSpec = copy(
    xAxis = xAxis.copy(data = xAxis.data.take(MAX_CHART_ROWS).toList()),
    yAxis = yAxis.copy(data = yAxis.data.take(MAX_CHART_ROWS).toList()),
    series = series.take(MAX_CHART_SERIES).map { it.copy(
        values = it.values.take(MAX_CHART_ROWS).toList(), points = it.points.take(MAX_CHART_ROWS).toList(),
    ) },
)
