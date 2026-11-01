package me.rerere.rikkihub.data.ai.tools.local

import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.ai.tools.*
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.event.AppEventBus
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ChartDisplayToolTest {
    @get:Rule val temp = TemporaryFolder()
    private val input = """{"style":"line","series":[{"name":"A","color":"#F80","values":[1,2]}],"x_axis":{"data":["a","b"]}}"""
    private suspend fun successfulPart(name: String = "chart_display") = UIMessagePart.Tool(
        toolCallId = "chart-1", toolName = name, input = input,
        output = buildChartDisplayTool().execute(Json.parseToJsonElement(input)),
    )

    @Test fun `actual tool schema exposes limits literal types closed nested shapes and simple wire keywords`() {
        val tool = buildChartDisplayTool()
        assertEquals(BuiltinToolIds.CHART_DISPLAY, tool.name)
        assertEquals(BuiltinToolCatalog.require(tool.name).defaultDescription, tool.description)
        assertFalse(tool.needsApproval(Json.parseToJsonElement(input)))
        assertNull(tool.workspaceIdentity)
        assertNull(tool.executeWithContext)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("style", "series"), schema.required)
        assertEquals(setOf("style", "title", "x_axis", "y_axis", "series"), schema.properties.keys)
        assertEquals(JsonArray(listOf("line", "bar", "scatter").map(::JsonPrimitive)), schema.properties["style"]!!.jsonObject["enum"])
        assertEquals(JsonPrimitive(256), schema.properties["title"]!!.jsonObject["maxLength"])
        val series = schema.properties["series"]!!.jsonObject
        assertEquals(JsonPrimitive(1), series["minItems"])
        assertEquals(JsonPrimitive(12), series["maxItems"])
        val item = series["items"]!!.jsonObject
        assertEquals(JsonPrimitive(false), item["additionalProperties"])
        assertFalse("required" in item) // Style-specific required field is checked by strict execution.
        val valueArray = item["properties"]!!.jsonObject["values"]!!.jsonObject
        assertEquals(JsonPrimitive(2000), valueArray["maxItems"])
        val number = valueArray["items"]!!.jsonObject
        assertEquals(JsonPrimitive("number"), number["type"])
        assertEquals(JsonPrimitive(1e100), number["maximum"])
        assertEquals(JsonPrimitive(-1e100), number["minimum"])
        val point = item["properties"]!!.jsonObject["points"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))), point["required"])
        assertEquals(JsonPrimitive(false), point["additionalProperties"])
        val y = schema.properties["y_axis"]!!.jsonObject
        assertFalse("data" in y["properties"]!!.jsonObject)
        assertEquals(JsonPrimitive(CHART_FORMAT_PATTERN), y["properties"]!!.jsonObject["format"]!!.jsonObject["pattern"])
        fun checkSimple(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    assertTrue(element.keys.intersect(setOf("if", "then", "else", "oneOf", "anyOf", "allOf", "const")).isEmpty())
                    element.values.forEach(::checkSimple)
                }
                is JsonArray -> element.forEach(::checkSimple)
                else -> Unit
            }
        }
        checkSimple(schema.properties)
        assertTrue(tool.description.contains("date formats are unsupported"))
        assertTrue(tool.description.contains("24000"))
    }

    @Test fun `success output is tiny text with versioned hash metadata and no copied data`() = runBlocking {
        val part = successfulPart()
        val text = part.output.single() as UIMessagePart.Text
        assertTrue(text.text.length < 100)
        assertTrue(text.text.contains("\"success\":true"))
        assertFalse(text.text.contains("values"))
        assertEquals(JsonPrimitive("v1"), text.metadata!![CHART_SUCCESS_KEY])
        assertEquals(64, text.metadata!![CHART_INPUT_HASH_KEY]!!.jsonPrimitive.content.length)
        assertEquals(ChartSpec.fromJson(Json.parseToJsonElement(input)), ChartSpec.fromTool(part))
    }

    @Test fun `invalid root series number and axis executions return error without marker`() = runBlocking {
        listOf("null", "[]", "{}", """{"style":"line","series":[{"values":["1"]}]}""",
            """{"style":"bar","y_axis":{"scale":"log"},"series":[{"values":[1]}]}""").forEach { bad ->
            val output = buildChartDisplayTool().execute(Json.parseToJsonElement(bad))
            val text = output.single() as UIMessagePart.Text
            assertTrue(text.text.contains("INVALID_CHART"))
            assertNull(text.metadata)
            assertNull(ChartSpec.fromTool(UIMessagePart.Tool("id", "chart_display", input, output)))
        }
    }

    @Test fun `marker requires executed auto or approved and rejects pending denied answered`() = runBlocking {
        val part = successfulPart()
        assertNotNull(ChartSpec.fromTool(part))
        assertNotNull(ChartSpec.fromTool(part.copy(approvalState = ToolApprovalState.Approved)))
        listOf(ToolApprovalState.Pending, ToolApprovalState.Denied("no"), ToolApprovalState.Answered("yes")).forEach { state ->
            assertNull(ChartSpec.fromTool(part.copy(approvalState = state)))
        }
        assertNull(ChartSpec.fromTool(part.copy(output = emptyList())))
    }

    @Test fun `historically renamed tool is recognized and unmarked MCP lookalikes are not`() = runBlocking {
        assertNotNull(ChartSpec.fromTool(successfulPart("old_chart_override")))
        val part = successfulPart()
        val plainSuccess = listOf(UIMessagePart.Text("""{"success":true}"""))
        assertNull(ChartSpec.fromTool(part.copy(output = plainSuccess)))
        assertNull(ChartSpec.fromTool(part.copy(output = plainSuccess, toolName = "mcp_chart_display")))
        val progressOnly = part.copy(output = plainSuccess, metadata = (part.output.single() as UIMessagePart.Text).metadata)
        assertNull(ChartSpec.fromTool(progressOnly)) // tool-level progress metadata never suffices
    }

    @Test fun `canonical hash recursively sorts objects preserves arrays and input edits invalidate success`() = runBlocking {
        val part = successfulPart()
        val reordered = """ { "x_axis": {"data":["a","b"]}, "series": [{"values":[1,2],"color":"#F80","name":"A"}], "style":"line" } """
        assertNotNull(ChartSpec.fromTool(part.copy(input = reordered)))
        assertNull(ChartSpec.fromTool(part.copy(input = input.replace("[1,2]", "[2,1]"))))
        assertNull(ChartSpec.fromTool(part.copy(input = input.replace("\"A\"", "\"B\""))))
        assertNull(ChartSpec.fromTool(part.copy(input = input.replace("\"line\"", "\"bar\""))))
        assertNull(ChartSpec.fromTool(part.copy(input = input.replace("[1,2]", "[\"1\",2]"))))
        assertNull(ChartSpec.fromTool(part.copy(input = "bad json")))
        val canonical = """{"series":[{"color":"#F80","name":"A","values":[1,2]}],"style":"line","x_axis":{"data":["a","b"]}}"""
        val expected = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        assertEquals(expected, chartInputHash(Json.parseToJsonElement(input)))
    }

    @Test fun `marker version literal types corrupted hash and oversized raw input are rejected`() = runBlocking {
        val part = successfulPart()
        val original = part.output.single() as UIMessagePart.Text
        listOf(JsonPrimitive(true), JsonPrimitive("v2"), JsonNull).forEach { version ->
            val changed = original.copy(metadata = JsonObject(original.metadata!! + (CHART_SUCCESS_KEY to version)))
            assertNull(ChartSpec.fromTool(part.copy(output = listOf(changed))))
        }
        assertNull(ChartSpec.fromTool(part.copy(output = listOf(original.copy(metadata = JsonObject(
            original.metadata!! + (CHART_INPUT_HASH_KEY to JsonPrimitive("0".repeat(64)))))))))
        assertNull(ChartSpec.fromTool(part.copy(input = " ".repeat(CHART_MAX_INPUT_BYTES) + input)))
    }

    @Test fun `unmarked nonchart and malformed marker tools skip deeply nested raw input`() {
        val deep = "[".repeat(30000) + "0" + "]".repeat(30000)
        val unrelated = UIMessagePart.Tool("mcp", "mcp_payload", deep,
            output = listOf(UIMessagePart.Text("ok")))
        assertNull(ChartSpec.fromTool(unrelated))
        assertNull(ChartSpec.fromTool(unrelated.copy(input = "not json")))
        assertNull(ChartSpec.fromTool(unrelated.copy(input = "x".repeat(CHART_MAX_INPUT_BYTES + 1))))
        val malformed = UIMessagePart.Text("ok", buildJsonObject {
            put(CHART_SUCCESS_KEY, CHART_SUCCESS_VERSION); put(CHART_INPUT_HASH_KEY, 123)
        })
        assertNull(ChartSpec.fromTool(unrelated.copy(output = listOf(malformed))))
    }

    @Test fun `marked deeply nested raw JSON is bounded before recursive decoding`() = runBlocking {
        val part = successfulPart()
        val deep = "[".repeat(30000) + "0" + "]".repeat(30000)
        assertNull(ChartSpec.fromTool(part.copy(input = deep)))
        assertNull(ChartSpec.fromTool(part.copy(input = """{"style":"line","series":$deep}""")))
        assertNull(ChartSpec.fromTool(part.copy(input = "[[[[[[[[0]]]]]]]]")))
        assertNull(ChartSpec.fromTool(part.copy(input = "}")))
        assertNull(ChartSpec.fromTool(part.copy(input = "\"unterminated")))
    }

    @Test fun `marked raw UTF8 budget counts bytes not UTF16 characters`() = runBlocking {
        val part = successfulPart()
        val large = """{"title":"${"界".repeat(400000)}","style":"line","series":[{"values":[1]}]}"""
        assertTrue(large.length < CHART_MAX_INPUT_BYTES)
        assertTrue(large.toByteArray(Charsets.UTF_8).size > CHART_MAX_INPUT_BYTES)
        assertNull(ChartSpec.fromTool(part.copy(input = large)))
    }

    @Test fun `raw depth scan ignores escaped quotes backslashes and structural braces in strings`() = runBlocking {
        val title = "[{\"quoted\"}]\\path".repeat(10)
        val element = JsonObject(Json.parseToJsonElement(input).jsonObject + ("title" to JsonPrimitive(title)))
        val raw = element.toString()
        val part = UIMessagePart.Tool("escapes", "historical_chart", raw,
            output = buildChartDisplayTool().execute(element))
        assertEquals(title, ChartSpec.fromTool(part)!!.title)
        // Different string escape spellings decode to the same parsed/canonical input.
        val quotedTitle = JsonPrimitive(title).toString()
        val unicodeEscaped = raw.replace(quotedTitle, quotedTitle.replace("[", "\\u005b"))
        assertEquals(title, ChartSpec.fromTool(part.copy(input = unicodeEscaped))?.title)
    }

    @Test fun `persisted message part codec preserves success metadata rename and chart input`() = runBlocking {
        val part = successfulPart("historical_alias")
        val encoded = JsonInstant.encodeToString<UIMessagePart>(part)
        val decoded = JsonInstant.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Tool
        assertEquals(part, decoded)
        assertEquals(ChartSpec.fromTool(part), ChartSpec.fromTool(decoded))
        assertNotNull(ChartSpec.fromTool(decoded))
    }

    @Test fun `option serialization default assistant and builtin override remain opt in`() = runBlocking {
        val encoded = JsonInstant.encodeToString<LocalToolOption>(LocalToolOption.ChartDisplay)
        assertTrue(encoded.contains("\"chart_display\""))
        assertEquals(LocalToolOption.ChartDisplay, JsonInstant.decodeFromString<LocalToolOption>(encoded))
        assertEquals(emptyList<LocalToolOption>(), Assistant().localTools)
        val spec = BuiltinToolCatalog.require(BuiltinToolIds.CHART_DISPLAY)
        assertEquals(BuiltinToolCategory.LOCAL, spec.category)
        assertEquals("chart_display", spec.id)
        val tool = buildChartDisplayTool().applyBuiltinOverride(mapOf(
            spec.id to ToolOverride(name = "draw_trend", description = "custom description")))
        assertEquals("draw_trend", tool.name)
        assertEquals("custom description", tool.description)
        assertNull(tool.workspaceIdentity)
        val result = tool.execute(Json.parseToJsonElement(input))
        assertNotNull(ChartSpec.fromTool(UIMessagePart.Tool("renamed", tool.name, input, result)))
    }

    @Test fun `LocalTools assembles only explicit chart option once and keeps lazy tool identity`() {
        // Existing test seam: allocate unused Android-backed services without starting DataStore/TTS.
        val context = FakeContext(temp.newFolder())
        val tools = LocalTools(context, AppEventBus(), detached(TTSManager::class.java), detached(SettingsStore::class.java))
        assertTrue(tools.getTools(emptyList()).isEmpty())
        assertTrue(tools.getTools(Assistant().localTools).isEmpty())
        assertEquals(listOf(BuiltinToolIds.GET_TIME_INFO), tools.getTools(listOf(LocalToolOption.TimeInfo)).map { it.name })
        assertEquals(listOf(BuiltinToolIds.CHART_DISPLAY), tools.getTools(listOf(LocalToolOption.ChartDisplay)).map { it.name })
        assertSame(tools.chartDisplayTool, tools.getTools(listOf(LocalToolOption.ChartDisplay)).single())
        assertEquals(1, tools.getTools(listOf(LocalToolOption.ChartDisplay, LocalToolOption.ChartDisplay)).size)
        assertEquals(listOf(BuiltinToolIds.GET_TIME_INFO, BuiltinToolIds.CHART_DISPLAY),
            tools.getTools(listOf(LocalToolOption.ChartDisplay, LocalToolOption.TimeInfo)).map { it.name })
    }

    @Test fun `cancelled execution propagates cancellation rather than emitting success metadata`() = runBlocking {
        var result: List<UIMessagePart>? = null
        val task = launch(start = CoroutineStart.LAZY) {
            result = buildChartDisplayTool().execute(Json.parseToJsonElement(input))
        }
        task.cancel()
        task.join()
        assertNull(result)
        assertTrue(task.isCancelled)
        // UNDISPATCHED enters the lambda even when its context is already cancelled,
        // so this additionally exercises the actual execute body's ensureActive guard.
        val job = Job().apply { cancel() }
        var entered = false
        val direct = launch(context = coroutineContext + job, start = CoroutineStart.UNDISPATCHED) {
            entered = true
            result = buildChartDisplayTool().execute(Json.parseToJsonElement(input))
        }
        direct.join()
        assertTrue(entered)
        assertTrue(direct.isCancelled)
        assertNull(result)
    }

    private fun <T : Any> detached(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return requireNotNull(type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)))
    }
}
