package me.rerere.rikkihub.ui.components.charts

import me.rerere.rikkihub.data.ai.tools.local.ChartAxis
import me.rerere.rikkihub.data.ai.tools.local.ChartPoint
import me.rerere.rikkihub.data.ai.tools.local.ChartScaleType
import me.rerere.rikkihub.data.ai.tools.local.ChartSeries
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle
import org.junit.Assert.*
import org.junit.Test

class ChartGeometryTest {
    private fun line(values: List<Double>, axis: ChartAxis = ChartAxis()) =
        ChartSpec(ChartStyle.Line, yAxis = axis, series = listOf(ChartSeries(values = values)))
    private fun assertBounded(geometry: ChartGeometry) {
        assertTrue(geometry.yTicks.size <= MAX_AXIS_TICKS)
        assertTrue(geometry.xTicks.size <= MAX_CATEGORY_TICKS)
        geometry.series.forEach { s ->
            s.points.filterNotNull().forEach { p ->
                assertTrue(p.x.isFinite() && p.x in 0.0..1.0)
                assertTrue(p.y.isFinite() && p.y in 0.0..1.0)
            }
            s.lineSegments.forEach { segment ->
                assertTrue(segment.start.isInChartViewport()); assertTrue(segment.end.isInChartViewport())
            }
            s.bars.forEach { b ->
                listOf(b.left, b.right, b.top, b.bottom).forEach { assertTrue(it.isFinite() && it in 0.0..1.0) }
                assertTrue(b.left <= b.right && b.top <= b.bottom)
            }
        }
    }
    @Test fun `line points and categories preserve order and endpoints`() {
        val g = line(listOf(-10.0, 0.0, 10.0)).buildChartGeometry()
        assertEquals(listOf(ChartCoordinate(0.0, 1.0), ChartCoordinate(0.5, 0.5), ChartCoordinate(1.0, 0.0)), g.series.single().points)
        assertEquals(listOf("1", "2", "3"), g.xTicks.map { it.label })
    }
    @Test fun `explicit clipping keeps all coordinates in viewport`() {
        val g = line(listOf(-1e100, 0.0, 1e100), ChartAxis(min = -1.0, max = 1.0)).buildChartGeometry()
        assertBounded(g)
        assertNull(g.series.single().points.first())
        assertNull(g.series.single().points.last())
        assertEquals(2, g.series.single().lineSegments.size)
    }
    @Test fun `huge opposite signed values do not overflow fractions`() {
        val scale = buildAxisScale(listOf(-1e100, 1e100))
        assertEquals(0.5, scale.fraction(0.0), 1e-10)
        assertBounded(line(listOf(-1e100, 0.0, 1e100)).buildChartGeometry())
    }
    @Test fun `tiny opposite signed values remain distinct`() {
        val scale = buildAxisScale(listOf(-1e-100, 1e-100))
        assertEquals(0.0, scale.fraction(-1e-100), 0.0)
        assertEquals(1.0, scale.fraction(1e-100), 0.0)
        assertEquals(0.5, scale.fraction(0.0), 1e-10)
    }
    @Test fun `constant zero positive negative and extremes have finite expanded ranges`() {
        listOf(0.0, 7.0, -7.0, 1e100, -1e100, 1e-100, -1e-100).forEach { value ->
            val scale = buildAxisScale(listOf(value, value))
            assertTrue(scale.min < scale.max)
            assertTrue(scale.min.isFinite() && scale.max.isFinite())
            assertTrue(scale.fraction(value) in 0.0..1.0)
            assertBounded(line(listOf(value, value)).buildChartGeometry())
        }
    }
    @Test fun `single category is centered`() {
        val point = line(listOf(2.0)).buildChartGeometry().series.single().points.single()!!
        assertEquals(0.5, point.x, 0.0)
    }
    @Test fun `log decades are evenly spaced`() {
        val scale = buildAxisScale(listOf(1.0, 10.0, 100.0), log = true)
        assertEquals(0.5, scale.fraction(10.0), 1e-10)
        assertEquals(10.0, scale.valueAt(0.5), 1e-10)
    }
    @Test fun `log ranges across 200 decades have only six ticks`() {
        val scale = buildAxisScale(listOf(1e-100, 1e100), log = true)
        assertEquals(MAX_AXIS_TICKS, scale.ticks(null).size)
        assertEquals(0.5, scale.fraction(1.0), 1e-10)
        assertTrue(scale.ticks(null).all { it.fraction.isFinite() && it.label.length <= 64 })
    }
    @Test fun `constant log extrema expand within numeric limits`() {
        listOf(1e-100, 1.0, 1e100).forEach { value ->
            val scale = buildAxisScale(listOf(value), log = true)
            assertTrue(scale.min > 0.0 && scale.max > scale.min)
            assertTrue(scale.min >= 1e-100 && scale.max <= 1e100)
            assertTrue(scale.fraction(value).isFinite())
        }
    }
    @Test fun `log bounds that round to the same exponent expand safely`() {
        val scale = buildAxisScale(listOf(1e100), userMin = Math.nextDown(1e100), userMax = 1e100, log = true)
        assertTrue(scale.min < scale.max)
        assertTrue(scale.valueAt(0.0) < scale.valueAt(1.0))
        assertTrue(scale.fraction(1e100).isFinite())
    }
    @Test fun `invalid public numeric values produce gaps not NaN or linked lines`() {
        val g = line(listOf(1.0, Double.NaN, Double.POSITIVE_INFINITY, 1e-200, 1e200, 2.0)).buildChartGeometry()
        assertEquals(listOf(null, null, null, null), g.series.single().points.subList(1, 5))
        assertBounded(g)
    }
    @Test fun `invalid and inverted user bounds fall back safely`() {
        listOf(ChartAxis(min = Double.NaN, max = Double.POSITIVE_INFINITY),
            ChartAxis(min = 10.0, max = -10.0), ChartAxis(min = 1e300), ChartAxis(max = -1e300)).forEach { axis ->
            assertBounded(line(listOf(-2.0, 2.0), axis).buildChartGeometry())
        }
    }
    @Test fun `invalid log public bounds and nonpositive points are safe`() {
        val spec = line(listOf(-3.0, 0.0, 10.0), ChartAxis(min = -1.0, max = 0.0, scale = ChartScaleType.Log))
        val g = spec.buildChartGeometry()
        assertNull(g.series.single().points[0]); assertNull(g.series.single().points[1])
        assertBounded(g)
    }
    @Test fun `scatter uses independent x and y scales`() {
        val g = ChartSpec(ChartStyle.Scatter, xAxis = ChartAxis(scale = ChartScaleType.Log),
            series = listOf(ChartSeries(points = listOf(ChartPoint(1.0, -5.0), ChartPoint(10.0, 0.0), ChartPoint(100.0, 5.0)))))
            .buildChartGeometry()
        assertEquals(ChartCoordinate(0.5, 0.5), g.series.single().points[1])
        assertBounded(g)
    }
    @Test fun `bar baseline is zero for mixed positive and negative data`() {
        val g = ChartSpec(ChartStyle.Bar, series = listOf(ChartSeries(values = listOf(-5.0, 5.0)))).buildChartGeometry()
        assertEquals(0.5, g.series.single().bars[0].top, 1e-10)
        assertEquals(0.5, g.series.single().bars[1].bottom, 1e-10)
        assertBounded(g)
    }
    @Test fun `public logarithmic bars defensively fall back to zero baseline`() {
        val g = ChartSpec(ChartStyle.Bar, yAxis = ChartAxis(scale = ChartScaleType.Log),
            series = listOf(ChartSeries(values = listOf(1.0, 2.0)))).buildChartGeometry()
        assertEquals(1.0, g.series.single().bars.first().bottom, 0.0)
        assertBounded(g)
    }
    @Test fun `grouped bars occupy separate ordered lanes`() {
        val g = ChartSpec(ChartStyle.Bar, series = listOf(ChartSeries(values = listOf(2.0)),
            ChartSeries(values = listOf(3.0)))).buildChartGeometry()
        assertTrue(g.series[0].bars.single().right <= g.series[1].bars.single().left)
        assertBounded(g)
    }
    @Test fun `data and tick loops have hard upper bounds`() {
        val g = ChartSpec(ChartStyle.Line, xAxis = ChartAxis(data = List(3000) { "label$it" }),
            series = List(20) { ChartSeries(values = List(3000) { it.toDouble() }) }).buildChartGeometry()
        assertEquals(MAX_CHART_SERIES, g.series.size)
        assertTrue(g.series.all { it.points.size == MAX_CHART_ROWS })
        assertEquals(MAX_CATEGORY_TICKS, g.xTicks.size)
        assertEquals("label1999", g.xTicks.last().label)
        assertBounded(g)
    }
    @Test fun `scatter outside explicit ranges is skipped rather than moved to the border`() {
        val g = ChartSpec(ChartStyle.Scatter, xAxis = ChartAxis(min = 0.0, max = 1.0),
            yAxis = ChartAxis(min = 0.0, max = 1.0), series = listOf(ChartSeries(points = listOf(
                ChartPoint(-1.0, 0.5), ChartPoint(2.0, 0.5), ChartPoint(0.5, -1.0),
                ChartPoint(0.5, 2.0), ChartPoint(0.5, 0.5), ChartPoint(0.0, 1.0),
            )))).buildChartGeometry()
        assertEquals(listOf(null, null, null, null), g.series.single().points.take(4))
        assertEquals(ChartCoordinate(0.5, 0.5), g.series.single().points[4])
        assertEquals(ChartCoordinate(0.0, 0.0), g.series.single().points[5])
        assertBounded(g)
    }
    @Test fun `crossing line is intersected not clamped pointwise`() {
        val g = line(listOf(-1.0, 2.0), ChartAxis(min = 0.0, max = 1.0)).buildChartGeometry()
        assertEquals(listOf(null, null), g.series.single().points)
        val segment = g.series.single().lineSegments.single()
        assertEquals(1.0 / 3, segment.start.x, 1e-10)
        assertEquals(1.0, segment.start.y, 1e-10)
        assertEquals(2.0 / 3, segment.end.x, 1e-10)
        assertEquals(0.0, segment.end.y, 1e-10)
    }
    @Test fun `extreme crossing retains exact viewport edges despite parameter rounding`() {
        val g = line(listOf(-1e100, 1e100), ChartAxis(min = -1.0, max = 1.0)).buildChartGeometry()
        val segment = g.series.single().lineSegments.single()
        assertEquals(0.5, segment.start.x, 1e-10)
        assertEquals(0.5, segment.end.x, 1e-10)
        assertEquals(1.0, segment.start.y, 0.0)
        assertEquals(0.0, segment.end.y, 0.0)
        assertBounded(g)
    }
    @Test fun `wholly outside and parallel lines are absent`() {
        assertNull(clipChartLineSegment(ChartCoordinate(-2.0, 0.2), ChartCoordinate(-1.0, 0.8)))
        assertNull(clipChartLineSegment(ChartCoordinate(0.0, 2.0), ChartCoordinate(1.0, 2.0)))
        assertNull(clipChartLineSegment(ChartCoordinate(Double.NaN, 0.0), ChartCoordinate(1.0, 1.0)))
    }
    @Test fun `line clips across all four bounds with reversal preserving chronology`() {
        val start = ChartCoordinate(-1.0, -1.0); val end = ChartCoordinate(2.0, 2.0)
        val segment = clipChartLineSegment(start, end)!!
        assertEquals(ChartCoordinate(0.0, 0.0), segment.start)
        assertEquals(ChartCoordinate(1.0, 1.0), segment.end)
        assertEquals(ChartLineSegment(segment.end, segment.start), clipChartLineSegment(end, start))
    }
    @Test fun `bar clipping intersects rectangle and rejects wholly outside bars`() {
        assertEquals(ChartBar(0.0, 0.7, 0.0, 1.0), clipChartBar(ChartBar(-0.2, 0.7, -10.0, 10.0)))
        assertNull(clipChartBar(ChartBar(1.2, 1.5, 0.0, 1.0)))
        assertNull(clipChartBar(ChartBar(0.2, 0.5, -10.0, -1.0)))
    }
    @Test fun `empty programmatic model is safe`() {
        val g = ChartSpec(ChartStyle.Line, series = emptyList()).buildChartGeometry()
        assertTrue(g.series.isEmpty()); assertTrue(g.xTicks.isEmpty()); assertBounded(g)
    }
    @Test fun `plot bounds remain finite and positive for invalid layouts`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 1f, 1080f, Float.MAX_VALUE).forEach { size ->
            val b = chartPlotBounds(size, size)
            assertTrue(b.width > 0f && b.height > 0f)
            assertTrue(b.width.isFinite() && b.height.isFinite())
        }
    }
    @Test fun `hex colors and palette fallback are shared without Compose types`() {
        assertEquals(0xFFAABBCC.toInt(), chartSeriesColor("#aBc", 0))
        assertEquals(0xFFABCDEF.toInt(), chartSeriesColor("#ABCDEF", 0))
        assertEquals(chartPalette[0], chartSeriesColor("red", 12))
        assertEquals(chartPalette[11], chartSeriesColor(null, -1))
    }
    @Test fun `freeze copies all export data collections`() {
        val values = mutableListOf(1.0, 2.0)
        val labels = mutableListOf("A", "B")
        val sourceSeries = mutableListOf(ChartSeries(values = values))
        val frozen = ChartSpec(ChartStyle.Line, xAxis = ChartAxis(data = labels), series = sourceSeries).freezeForChartExport()
        values[0] = 99.0; labels[0] = "changed"; sourceSeries.clear()
        assertEquals(listOf(1.0, 2.0), frozen.series.single().values)
        assertEquals(listOf("A", "B"), frozen.xAxis.data)
    }
}
