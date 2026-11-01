package me.rerere.rikkihub.data.ai.tools.local

import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest
import kotlin.math.abs

enum class ChartStyle { Line, Bar, Scatter }
enum class ChartScaleType { Linear, Log }

data class ChartAxis(
    val title: String? = null,
    val data: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val scale: ChartScaleType = ChartScaleType.Linear,
    val format: String? = null,
)
data class ChartPoint(val x: Double, val y: Double)
data class ChartSeries(
    val name: String? = null,
    val color: String? = null,
    val values: List<Double> = emptyList(),
    val points: List<ChartPoint> = emptyList(),
)
data class ChartSpec(
    val style: ChartStyle,
    val title: String? = null,
    val xAxis: ChartAxis = ChartAxis(),
    val yAxis: ChartAxis = ChartAxis(),
    val series: List<ChartSeries>,
) {
    val categoryCount: Int
        get() = maxOf(xAxis.data.size, series.maxOfOrNull { it.values.size } ?: 0)

    fun categoryLabel(index: Int): String = xAxis.data.getOrNull(index) ?: (index + 1).toString()

    companion object {
        /** Strict, all-or-nothing parser. Strings are plain text, never markup or executable content. */
        fun fromJson(element: JsonElement): ChartSpec? = try {
            requireChartInputBudget(element)
            parseChart(element)
        } catch (_: IllegalArgumentException) {
            null
        }

        /**
         * Success metadata is UI provenance only, NOT an execution/approval authority or a signature.
         * Do not use tool names: historical overrides survive renaming, unmarked MCP lookalikes do not.
         * Validate input again, including its size, before trusting the success-to-input association.
         */
        fun fromTool(tool: UIMessagePart.Tool): ChartSpec? {
            if (!tool.isExecuted || (tool.approvalState != ToolApprovalState.Auto &&
                        tool.approvalState != ToolApprovalState.Approved)) return null
            // Fast reject unrelated/error results before looking at their potentially huge inputs.
            val marker = (tool.output.firstOrNull() as? UIMessagePart.Text)?.metadata ?: return null
            if (marker[CHART_SUCCESS_KEY] != JsonPrimitive(CHART_SUCCESS_VERSION)) return null
            val hash = marker[CHART_INPUT_HASH_KEY] as? JsonPrimitive ?: return null
            if (!hash.isString || !chartHash.matches(hash.content)) return null
            if (!boundedRawChartInput(tool.input)) return null
            val input = try { Json.parseToJsonElement(tool.input) } catch (_: IllegalArgumentException) { return null }
            val spec = fromJson(input) ?: return null
            if (hash.content != chartInputHash(input)) return null
            return spec
        }
    }
}

internal const val CHART_MAX_SERIES = 12
internal const val CHART_MAX_POINTS = 2000
internal const val CHART_MAX_TOTAL_POINTS = 24000
internal const val CHART_MAX_INPUT_BYTES = 1024 * 1024
internal const val CHART_SUCCESS_KEY = "rikkihub_chart_display_success"
internal const val CHART_SUCCESS_VERSION = "v1"
internal const val CHART_INPUT_HASH_KEY = "rikkihub_chart_display_input_sha256"
internal const val CHART_FORMAT_PATTERN = "^(,[d]|,?\\.[0-6][f%])$"
private const val CHART_JSON_MAX_DEPTH = 6
private val chartHash = Regex("^[0-9a-f]{64}$")
private val chartColor = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
private val chartFormat = Regex(CHART_FORMAT_PATTERN)
private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/** String-aware structural bound BEFORE the recursive JSON decoder sees historical raw input. */
private fun boundedRawChartInput(raw: String): Boolean {
    if (raw.length > CHART_MAX_INPUT_BYTES ||
        raw.toByteArray(Charsets.UTF_8).size > CHART_MAX_INPUT_BYTES) return false
    var nesting = 0
    var quoted = false
    var escaped = false
    for (char in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else when (char) {
                '\\' -> escaped = true
                '"' -> quoted = false
            }
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> {
                nesting++
                // JsonElement root is depth zero; the deepest container can be at depth six.
                if (nesting > CHART_JSON_MAX_DEPTH + 1) return false
            }
            '}', ']' -> { nesting--; if (nesting < 0) return false }
        }
    }
    return nesting == 0 && !quoted
}

/** Bound before serializing/hashing; reject deep/oversized unknown structures without large allocations. */
private fun requireChartInputBudget(element: JsonElement) {
    var bytes = 0
    fun add(size: Int) { bytes += size; require(bytes <= CHART_MAX_INPUT_BYTES) }
    fun visit(value: JsonElement, depth: Int) {
        require(depth <= CHART_JSON_MAX_DEPTH)
        when (value) {
            is JsonObject -> {
                add(2 + (value.size - 1).coerceAtLeast(0))
                value.forEach { (key, child) ->
                    require(key.length <= CHART_MAX_INPUT_BYTES)
                    add(JsonPrimitive(key).toString().toByteArray(Charsets.UTF_8).size + 1)
                    visit(child, depth + 1)
                }
            }
            is JsonArray -> { add(2 + (value.size - 1).coerceAtLeast(0)); value.forEach { visit(it, depth + 1) } }
            is JsonPrimitive -> {
                require(value.content.length <= CHART_MAX_INPUT_BYTES)
                add(value.toString().toByteArray(Charsets.UTF_8).size)
            }
        }
    }
    visit(element, 0)
}

private fun JsonObject.only(vararg keys: String) { require(this.keys.all { it in keys }) }
private fun JsonObject.text(key: String, limit: Int): String? {
    val value = this[key] ?: return null
    require(value is JsonPrimitive && value.isString && value.content.length <= limit)
    return value.content
}
private fun chartNumber(value: JsonElement): Double {
    require(value is JsonPrimitive && !value.isString && value.content.length <= 64 &&
        jsonNumber.matches(value.content))
    val number = value.doubleOrNull ?: throw IllegalArgumentException("Expected number")
    require(number.isFinite() && abs(number) <= 1e100)
    // A tiny JSON number must not silently underflow to zero in Double conversion.
    require(if (number == 0.0) value.content.toBigDecimal().signum() == 0 else abs(number) >= 1e-100)
    return number
}

private fun parseAxis(element: JsonElement?, category: Boolean, bar: Boolean): ChartAxis {
    if (element == null) return ChartAxis()
    require(element is JsonObject)
    element.only("title", "data", "min", "max", "scale", "format")
    val scale = when (element.text("scale", 6)) {
        null, "linear" -> ChartScaleType.Linear
        "log" -> ChartScaleType.Log
        else -> throw IllegalArgumentException("Unsupported scale")
    }
    require(!(category || bar) || (scale == ChartScaleType.Linear &&
        "min" !in element && "max" !in element))
    val min = element["min"]?.let(::chartNumber)
    val max = element["max"]?.let(::chartNumber)
    require(min == null || max == null || min < max)
    require(scale != ChartScaleType.Log || ((min == null || min > 0) && (max == null || max > 0)))
    val labels = element["data"]?.let { data ->
        require(category && data is JsonArray && data.size in 1..CHART_MAX_POINTS)
        data.map { label ->
            require(label is JsonPrimitive && label.isString && label.content.length <= 128)
            label.content
        }
    }.orEmpty()
    val format = element.text("format", 5)
    require(format == null || chartFormat.matches(format))
    return ChartAxis(element.text("title", 256), labels, min, max, scale, format)
}

private fun parseChart(element: JsonElement): ChartSpec {
    require(element is JsonObject)
    element.only("style", "title", "x_axis", "y_axis", "series")
    val style = when (element.text("style", 7)) {
        "line" -> ChartStyle.Line
        "bar" -> ChartStyle.Bar
        "scatter" -> ChartStyle.Scatter
        else -> throw IllegalArgumentException("Unsupported chart style")
    }
    val scatter = style == ChartStyle.Scatter
    val xAxis = parseAxis(element["x_axis"], !scatter, style == ChartStyle.Bar)
    val yAxis = parseAxis(element["y_axis"], false, style == ChartStyle.Bar)
    val items = element["series"]
    require(items is JsonArray && items.size in 1..CHART_MAX_SERIES)
    var total = 0
    val series = items.map { item ->
        require(item is JsonObject)
        item.only("name", "color", if (scatter) "points" else "values")
        val color = item.text("color", 7)
        require(color == null || chartColor.matches(color))
        val data = item[if (scatter) "points" else "values"]
        require(data is JsonArray && data.size in 1..CHART_MAX_POINTS)
        total += data.size
        require(total <= CHART_MAX_TOTAL_POINTS)
        if (scatter) ChartSeries(item.text("name", 128), color, points = data.map { point ->
            require(point is JsonObject)
            point.only("x", "y")
            ChartPoint(chartNumber(requireNotNull(point["x"])), chartNumber(requireNotNull(point["y"])))
        }) else ChartSeries(item.text("name", 128), color, values = data.map(::chartNumber))
    }
    if (!scatter) {
        val count = series.first().values.size
        require(series.all { it.values.size == count })
        require(xAxis.data.isEmpty() || xAxis.data.size == count)
    }
    fun checkRange(axis: ChartAxis, values: List<Double>) {
        require(axis.scale != ChartScaleType.Log || values.all { it > 0 })
        // Explicit two-sided clipping is allowed; one-sided bounds cannot invert the effective range.
        if (axis.min != null && axis.max == null) require(axis.min < values.max())
        if (axis.max != null && axis.min == null) require(values.min() < axis.max)
    }
    if (scatter) checkRange(xAxis, series.flatMap { it.points.map(ChartPoint::x) })
    checkRange(yAxis, series.flatMap { s -> if (scatter) s.points.map(ChartPoint::y) else s.values })
    return ChartSpec(style, element.text("title", 256), xAxis, yAxis, series)
}

/** Hash parsed executed input, not the lossy/default-filled model; preserve literals and array order. */
internal fun chartInputHash(input: JsonElement): String {
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical(input).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
}
