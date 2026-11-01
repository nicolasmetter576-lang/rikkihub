package me.rerere.rikkihub.ui.components.charts

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

private val chartFormatPattern = Regex("^(,)?(?:\\.(\\d{1,2}))?([fd%])$")

/** Bounded labels, with scientific fallback rather than hundreds of digits at extreme magnitudes. */
internal fun formatChartNumber(value: Double, format: String? = null, locale: Locale = Locale.getDefault()): String {
    // Scales derive padded ticks smaller than the accepted input-data minimum (e.g. 9e-101).
    if (!value.isFinite()) return "—"
    val spec = format?.takeIf { it.length <= 16 }?.let { chartFormatPattern.matchEntire(it) }
    val percent = spec?.groupValues?.get(3) == "%"
    val number = (if (percent) value * 100.0 else value).let { if (it == 0.0) 0.0 else it }
    if (!number.isFinite()) return "—"
    val digits = if (spec?.groupValues?.get(3) == "d") 0
        else spec?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 8)
    val label = if (abs(number) >= 1e9 || (number != 0.0 && abs(number) < 1e-5)) {
        String.format(locale, "%.${digits ?: 3}e", number)
    } else {
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = spec?.groupValues?.get(1) == ","
            minimumFractionDigits = digits ?: 0
            maximumFractionDigits = digits ?: 5
        }.format(number)
    }
    return (label + if (percent) "%" else "").take(64)
}
