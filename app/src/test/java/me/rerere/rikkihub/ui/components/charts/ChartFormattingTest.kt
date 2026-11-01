package me.rerere.rikkihub.ui.components.charts

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ChartFormattingTest {
    @Test fun `fixed grouped integer and percent formats are supported`() {
        assertEquals("12.35", formatChartNumber(12.345, ".2f", Locale.US))
        assertEquals("1,234", formatChartNumber(1234.0, ",d", Locale.US))
        assertEquals("12.5%", formatChartNumber(0.125, ".1%", Locale.US))
        assertEquals("1,234.50", formatChartNumber(1234.5, ",.2f", Locale.US))
    }
    @Test fun `automatic formatting suppresses negative zero`() {
        assertEquals("0", formatChartNumber(-0.0, locale = Locale.US))
        assertEquals("0.00", formatChartNumber(-0.0, ".2f", Locale.US))
    }
    @Test fun `finite extremes use bounded scientific notation`() {
        assertEquals("1.000e+100", formatChartNumber(1e100, locale = Locale.US))
        assertEquals("1.000e-100", formatChartNumber(1e-100, locale = Locale.US))
        assertTrue(formatChartNumber(1e100, ",.6f", Locale.US).length < 32)
        assertTrue(formatChartNumber(1e100, ".6%", Locale.US).endsWith("%"))
    }
    @Test fun `derived padded ticks below input minimum have labels`() {
        assertEquals("9.000e-101", formatChartNumber(9e-101, locale = Locale.US))
        assertFalse(buildAxisScale(listOf(1e-100)).ticks(null).any { it.label == "—" })
    }
    @Test fun `NaN infinity and percent overflow cannot become misleading labels`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertEquals("—", formatChartNumber(it, locale = Locale.US))
        }
        assertEquals("—", formatChartNumber(Double.MAX_VALUE, ".2%", Locale.US))
    }
    @Test fun `unknown format and excessive precision stay bounded`() {
        assertEquals("12.5", formatChartNumber(12.5, "%Y", Locale.US))
        assertEquals("12.5", formatChartNumber(12.5, ".999999f", Locale.US))
        assertTrue(formatChartNumber(12.5, ".99f", Locale.US).length <= 64)
        assertEquals("12.5", formatChartNumber(12.5, "x".repeat(10000), Locale.US))
    }
    @Test fun `locale is an explicit testable formatting dependency`() {
        assertEquals("12,50", formatChartNumber(12.5, ".2f", Locale.GERMANY))
        assertEquals("12.50", formatChartNumber(12.5, ".2f", Locale.US))
    }
}
