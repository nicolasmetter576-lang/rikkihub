package me.rerere.rikkihub.data.ai.tools.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds

internal fun buildChartDisplayTool(): Tool = Tool(
    name = BuiltinToolIds.CHART_DISPLAY,
    description = BuiltinToolCatalog.require(BuiltinToolIds.CHART_DISPLAY).defaultDescription,
    parameters = {
        // Keep the wire schema compatible with legacy Gemini's simple Schema subset.
        // Strict execution owns cross-property constraints, nonzero magnitude, equal lengths,
        // effective bounds, total count and encoded size; no conditional/combinator keywords.
        InputSchema.Obj(
            properties = buildJsonObject {
                put("style", enumSchema("line", "bar", "scatter"))
                put("title", textSchema(256))
                put("x_axis", axisSchema(xAxis = true))
                put("y_axis", axisSchema(xAxis = false))
                put("series", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", CHART_MAX_SERIES)
                    put("description", "Line/bar require values only; scatter requires points only. All category series must have equal lengths, aligned with x_axis.data if present. At most 24000 total values/points.")
                    put("items", seriesSchema())
                })
            },
            required = listOf("style", "series"),
        )
    },
    execute = { input ->
        currentCoroutineContext().ensureActive()
        val spec = ChartSpec.fromJson(input)
        if (spec == null) {
            listOf(UIMessagePart.Text("""{"error":"INVALID_CHART","message":"Invalid chart: check shape, numeric ranges, axes and size limits."}"""))
        } else {
            val hash = chartInputHash(input)
            currentCoroutineContext().ensureActive()
            // Single tiny result; data stays in INPUT. First Text metadata survives existing truncation.
            // No progress metadata: failed/cancelled calls cannot acquire a success marker.
            listOf(UIMessagePart.Text(
                """{"success":true,"message":"Chart displayed to the user."}""",
                metadata = buildJsonObject {
                    put(CHART_SUCCESS_KEY, CHART_SUCCESS_VERSION)
                    put(CHART_INPUT_HASH_KEY, hash)
                },
            ))
        }
    },
)

private fun textSchema(limit: Int): JsonObject = buildJsonObject {
    put("type", "string"); put("maxLength", limit)
    put("description", "Plain text only; never interpreted as HTML, SVG, code, a file path or a URL.")
}
private fun enumSchema(vararg values: String): JsonObject = buildJsonObject {
    put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
}
private fun numberSchema(): JsonObject = buildJsonObject {
    put("type", "number"); put("minimum", -1e100); put("maximum", 1e100)
    put("description", "Finite JSON number, not a string; zero or absolute magnitude >= 1e-100. Must be positive on a log axis.")
}
private fun axisSchema(xAxis: Boolean): JsonObject = buildJsonObject {
    put("type", "object"); put("additionalProperties", false)
    put("description", if (xAxis)
        "Categorical for line/bar: no bounds or log. Numeric for scatter: no data labels. Bar axes are linear with no bounds."
    else "Numeric Y axis: no data labels. Bar axes are linear with no bounds. Log data and bounds must be positive.")
    put("properties", buildJsonObject {
        put("title", textSchema(256))
        put("scale", enumSchema("linear", "log"))
        put("format", buildJsonObject {
            put("type", "string"); put("maxLength", 5); put("pattern", CHART_FORMAT_PATTERN)
            put("description", "Bounded numeric formats only: .0f through .6f, .0% through .6%, optional comma grouping, or ,d. Date/strftime formats are unsupported.")
        })
        if (xAxis) put("data", buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", CHART_MAX_POINTS)
            put("description", "Category labels for line/bar only, exactly aligned with each series' values. Forbidden for scatter.")
            put("items", textSchema(128))
        })
        put("min", numberSchema())
        put("max", numberSchema())
    })
}
private fun seriesSchema(): JsonObject = buildJsonObject {
    put("type", "object"); put("additionalProperties", false)
    put("description", "Require exactly values (line/bar) or points (scatter), never both. No unknown fields.")
    put("properties", buildJsonObject {
        put("name", textSchema(128))
        put("color", buildJsonObject {
            put("type", "string"); put("maxLength", 7)
            put("pattern", "^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
        })
        put("values", buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", CHART_MAX_POINTS)
            put("description", "Required for line/bar only, forbidden for scatter.")
            put("items", numberSchema())
        })
        put("points", buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", CHART_MAX_POINTS)
            put("description", "Required for scatter only, forbidden for line/bar.")
            put("items", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("required", buildJsonArray { add("x"); add("y") })
                put("properties", buildJsonObject { put("x", numberSchema()); put("y", numberSchema()) })
            })
        })
    })
}
