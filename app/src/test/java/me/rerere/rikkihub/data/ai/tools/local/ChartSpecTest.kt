package me.rerere.rikkihub.data.ai.tools.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ChartSpecTest {
    private fun parse(text: String) = ChartSpec.fromJson(Json.parseToJsonElement(text))
    private fun line(extra: String = "", values: String = "[1,2]") =
        """{"style":"line","series":[{"values":$values}]$extra}"""
    private fun scatter(axes: String = "", points: String = "[{\"x\":1,\"y\":2}]") =
        """{"style":"scatter","series":[{"points":$points}]$axes}"""
    private fun invalid(vararg inputs: String) = inputs.forEach { assertNull(it, parse(it)) }

    @Test fun `line model category labels and numeric defaults are frozen`() {
        val spec = parse(line(",\"title\":\"Trend\",\"x_axis\":{\"title\":\"Month\",\"data\":[\"Jan\",\"Feb\"]}"))!!
        assertEquals(ChartStyle.Line, spec.style)
        assertEquals("Trend", spec.title)
        assertEquals(2, spec.categoryCount)
        assertEquals("Jan", spec.categoryLabel(0))
        assertEquals("3", spec.categoryLabel(2))
        assertEquals(ChartAxis(), spec.yAxis)
        assertEquals(listOf(1.0, 2.0), spec.series.single().values)
        assertEquals(emptyList<ChartPoint>(), spec.series.single().points)
        assertEquals("1", parse(line())!!.categoryLabel(0))
    }

    @Test fun `bar accepts negatives zero and hex colors without numeric axes`() {
        val spec = parse("""{"style":"bar","series":[{"name":"A","color":"#F80","values":[-1,0,2]}]}""")!!
        assertEquals(ChartStyle.Bar, spec.style)
        assertEquals("#F80", spec.series.single().color)
        assertEquals(3, spec.categoryCount)
        assertNotNull(parse("""{"style":"line","series":[{"color":"#a0B1c2","values":[1]}]}"""))
    }

    @Test fun `scatter accepts numeric axes and keeps all points`() {
        val spec = parse(scatter(",\"x_axis\":{\"min\":0,\"max\":3,\"format\":\".1f\"},\"y_axis\":{\"scale\":\"log\",\"min\":1,\"max\":10}"))!!
        assertEquals(ChartStyle.Scatter, spec.style)
        assertEquals(listOf(ChartPoint(1.0, 2.0)), spec.series.single().points)
        assertEquals(0, spec.categoryCount)
        assertEquals(ChartScaleType.Log, spec.yAxis.scale)
    }

    @Test fun `root shape missing wrong literal types nulls and unknown fields fail`() {
        invalid("[]", "null", "{}", """{"style":"LINE","series":[{"values":[1]}]}""",
            line(",\"title\":null"), line(",\"title\":123"), line(",\"x_axis\":[]"),
            line(",\"y_axis\":null"), line(",\"extra\":true"),
            """{"style":true,"series":[{"values":[1]}]}""", """{"style":"line","series":{}}""")
    }

    @Test fun `all or nothing rejects a bad series value point and unknown nested field`() {
        invalid("""{"style":"line","series":[{"values":[1]},{"values":["2"]}]}""",
            line(values = "[1,\"bad\",2]"), scatter(points = "[{\"x\":1,\"y\":2},{\"x\":3}]"),
            scatter(points = "[{\"x\":1,\"y\":2,\"z\":3}]"),
            """{"style":"line","series":[{"values":[1],"unknown":0}]}""",
            line(",\"y_axis\":{\"unknown\":0}"))
    }

    @Test fun `styles forbid the other data shape even when empty`() {
        invalid("""{"style":"line","series":[{"values":[1],"points":[]}]}""",
            """{"style":"bar","series":[{"points":[{"x":1,"y":2}]}]}""",
            """{"style":"scatter","series":[{"points":[{"x":1,"y":2}],"values":[]}]}""")
    }

    @Test fun `empty series data and mismatched category lengths fail`() {
        invalid("""{"style":"line","series":[]}""", line(values = "[]"), scatter(points = "[]"),
            line(",\"x_axis\":{\"data\":[]}"), line(",\"x_axis\":{\"data\":[\"one\"]}"),
            line(",\"x_axis\":{\"data\":[1,2]}"),
            """{"style":"line","series":[{"values":[1,2]},{"values":[3]}]}""")
    }

    @Test fun `numeric literals finite magnitude and underflow rules are enforced everywhere`() {
        listOf("\"1\"", "true", "null", "1e101", "-1e101", "1e-101", "-1e-101", "1e-9999", "1e9999").forEach { n ->
            invalid(line(values = "[$n]"), scatter(points = "[{\"x\":$n,\"y\":1}]"),
                line(",\"y_axis\":{\"min\":$n}"))
        }
        listOf("0", "-0", "1e100", "-1e100", "1e-100", "-1e-100").forEach { n ->
            assertNotNull(n, parse(line(values = "[$n]")))
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { n ->
            val input = buildJsonObject {
                put("style", "line")
                put("series", buildJsonArray { add(buildJsonObject { put("values", buildJsonArray { add(JsonPrimitive(n)) }) }) })
            }
            assertNull(ChartSpec.fromJson(input))
        }
        // Leniently constructed primitives must still be actual JSON numeric literals.
        val input = JsonObject(mapOf("style" to JsonPrimitive("line"), "series" to JsonArray(listOf(
            JsonObject(mapOf("values" to JsonArray(listOf(JsonUnquotedLiteral("+1")))))))))
        assertNull(ChartSpec.fromJson(input))
    }

    @Test fun `log scales need positive data and both positive bounds`() {
        assertNotNull(parse(line(",\"y_axis\":{\"scale\":\"log\"}")))
        invalid(line(",\"y_axis\":{\"scale\":\"log\"}", "[0,1]"),
            line(",\"y_axis\":{\"scale\":\"log\"}", "[-1,1]"),
            line(",\"y_axis\":{\"scale\":\"log\",\"min\":0}"),
            line(",\"y_axis\":{\"scale\":\"log\",\"max\":-1}"),
            scatter(",\"x_axis\":{\"scale\":\"log\"}", "[{\"x\":0,\"y\":1}]"),
            scatter(",\"y_axis\":{\"scale\":\"log\"}", "[{\"x\":1,\"y\":-1}]"))
    }

    @Test fun `bar baseline forbids bounds and logarithmic axes`() {
        listOf("x_axis", "y_axis").forEach { axis ->
            listOf("\"min\":0", "\"max\":3", "\"scale\":\"log\"").forEach { field ->
                invalid("""{"style":"bar","series":[{"values":[1,2]}],"$axis":{$field}}""")
            }
        }
    }

    @Test fun `category axes forbid bounds log and numeric axes forbid data even empty`() {
        invalid(line(",\"x_axis\":{\"min\":0}"), line(",\"x_axis\":{\"max\":3}"),
            line(",\"x_axis\":{\"scale\":\"log\"}"), line(",\"y_axis\":{\"data\":[]}"),
            scatter(",\"x_axis\":{\"data\":[]}"), scatter(",\"y_axis\":{\"data\":[\"a\"]}"),
            line(",\"y_axis\":{\"scale\":\"other\"}"), line(",\"y_axis\":{\"scale\":null}"))
    }

    @Test fun `explicit and effective one sided range conflicts fail across all series`() {
        invalid(line(",\"y_axis\":{\"min\":2,\"max\":2}"), line(",\"y_axis\":{\"min\":3,\"max\":2}"),
            line(",\"y_axis\":{\"min\":2}"), line(",\"y_axis\":{\"max\":1}"),
            scatter(",\"x_axis\":{\"min\":1}"), scatter(",\"y_axis\":{\"max\":2}"))
        assertNotNull(parse(line(",\"y_axis\":{\"min\":0}")))
        assertNotNull(parse(line(",\"y_axis\":{\"max\":3}")))
        assertNotNull(parse(line(",\"y_axis\":{\"min\":5,\"max\":6}"))) // explicit clipping
        assertNotNull(parse("""{"style":"line","y_axis":{"min":2},"series":[{"values":[1]},{"values":[3]}]}"""))
        assertNotNull(parse(line(values = "[2,2]"))) // auto-range constant data
    }

    @Test fun `formats are bounded numeric patterns not arbitrary or date formatters`() {
        listOf(".0f", ".6f", ".0%", ".6%", ",.1f", ",.2%", ",d").forEach { format ->
            assertNotNull(format, parse(line(",\"y_axis\":{\"format\":\"$format\"}")))
        }
        listOf("", ".7f", ".100000f", "%Y-%m", "%s", "f", "d", ",f", "%.2f", ".-1f").forEach { format ->
            invalid(line(",\"y_axis\":{\"format\":\"$format\"}"))
        }
    }

    @Test fun `text and color caps are strict with no coercion`() {
        assertNotNull(parse(line(",\"title\":\"${"t".repeat(256)}\"")))
        invalid(line(",\"title\":\"${"t".repeat(257)}\""),
            line(",\"x_axis\":{\"title\":\"${"t".repeat(257)}\"}"),
            """{"style":"line","series":[{"name":"${"n".repeat(129)}","values":[1]}]}""",
            line(",\"x_axis\":{\"data\":[\"${"a".repeat(129)}\",\"b\"]}"))
        listOf("red", "#12", "#1234", "#12345678", "#GGG", "url(x)").forEach { color ->
            invalid("""{"style":"line","series":[{"color":"$color","values":[1]}]}""")
        }
        invalid("""{"style":"line","series":[{"name":1,"values":[1]}]}""",
            """{"style":"line","series":[{"color":null,"values":[1]}]}""")
    }

    @Test fun `series point label and total caps accept edges and reject overflow`() {
        val values = List(2000) { JsonPrimitive(1) }
        val series = JsonObject(mapOf("values" to JsonArray(values)))
        val full = JsonObject(mapOf("style" to JsonPrimitive("line"), "series" to JsonArray(List(12) { series })))
        assertEquals(24000, ChartSpec.fromJson(full)!!.series.sumOf { it.values.size })
        assertNull(ChartSpec.fromJson(JsonObject(full + ("series" to JsonArray(List(13) { series })))))
        assertNull(parse(line(values = List(2001) { "1" }.joinToString(",", "[", "]"))))
        val labels = JsonArray(List(2000) { JsonPrimitive("L") })
        assertEquals(2000, ChartSpec.fromJson(JsonObject(full + ("x_axis" to JsonObject(mapOf("data" to labels)))))!!.categoryCount)
        assertNull(ChartSpec.fromJson(JsonObject(full + ("x_axis" to JsonObject(mapOf("data" to JsonArray(List(2001) { JsonPrimitive("L") })))))))
        val point = "{\"x\":1,\"y\":2}"
        assertNotNull(parse(scatter(points = List(2000) { point }.joinToString(",", "[", "]"))))
        assertNull(parse(scatter(points = List(2001) { point }.joinToString(",", "[", "]"))))
    }

    @Test fun `encoded UTF8 input cap rejects otherwise valid many long names and points`() {
        val point = buildJsonObject { put("x", 1.234567890123456e90); put("y", 1.234567890123456e90) }
        val series = buildJsonObject { put("points", JsonArray(List(2000) { point })) }
        val input = buildJsonObject { put("style", "scatter"); put("series", JsonArray(List(12) { series })) }
        assertTrue(input.toString().toByteArray().size > CHART_MAX_INPUT_BYTES)
        assertNull(ChartSpec.fromJson(input))
        var nested: JsonElement = JsonPrimitive(1)
        repeat(100) { nested = JsonArray(listOf(nested)) }
        assertNull(ChartSpec.fromJson(JsonObject(mapOf("unknown" to nested))))
    }
}
