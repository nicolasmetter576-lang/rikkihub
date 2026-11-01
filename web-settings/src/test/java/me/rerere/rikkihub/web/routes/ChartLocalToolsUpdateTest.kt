package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.*
import org.junit.Test

/** Calls the production raw-presence parser and latest-existing allowlist merge. */
class ChartLocalToolsUpdateTest {
    private fun body(vararg types: String) = buildJsonObject {
        put("localTools", JsonArray(types.map { buildJsonObject { put("type", it) } }))
    }

    @Test fun `assistant without localTools enables no local tool`() {
        val assistant = JsonInstant.decodeFromJsonElement<Assistant>(buildJsonObject { put("name", "legacy") })
        assertEquals(emptyList<LocalToolOption>(), assistant.localTools)
        assertFalse(assistant.localTools.contains(LocalToolOption.ChartDisplay))
    }

    @Test fun `chart option codec and assistant copy preserve original fields`() {
        val original = Assistant(name = "Chart", localTools = listOf(LocalToolOption.ChartDisplay),
            avatar = Avatar.Emoji("📊"), background = "file://existing.png", enabledSkills = setOf("existing"))
        val raw = JsonInstant.encodeToJsonElement(original).jsonObject
        assertEquals(body("chart_display")["localTools"], raw["localTools"])
        assertEquals(original, JsonInstant.decodeFromJsonElement<Assistant>(raw))
        assertEquals(original.localTools, original.copy(name = "Renamed").localTools)
    }

    @Test fun `all eight valid raw options are accepted`() {
        val types = arrayOf("javascript_engine", "time_info", "clipboard", "tts", "ask_user", "screen_time", "calendar", "chart_display")
        val (incoming, parsed) = parseAssistantUpdate(body(*types))
        assertEquals(8, parsed!!.size)
        assertEquals(incoming.localTools, parsed)
        assertEquals(LocalToolOption.ChartDisplay, parsed.last())
    }

    @Test fun `nine raw entries rejected before deduplication`() {
        val error = runCatching { parseAssistantUpdate(body(*Array(9) { "chart_display" })) }.exceptionOrNull()
        assertTrue(error is BadRequestException)
    }

    @Test fun `eight repeated chart entries allowed but deduped`() {
        assertEquals(listOf(LocalToolOption.ChartDisplay), parseLocalToolsField(body(*Array(8) { "chart_display" })))
    }

    @Test fun `chart plus existing options retains first-seen order`() {
        assertEquals(listOf(LocalToolOption.ChartDisplay, LocalToolOption.Tts, LocalToolOption.TimeInfo),
            parseLocalToolsField(body("chart_display", "tts", "chart_display", "time_info")))
    }

    @Test fun `missing field preserves latest chart configuration and private fields`() {
        val latest = Assistant(name = "before", localTools = listOf(LocalToolOption.ChartDisplay, LocalToolOption.Clipboard),
            avatar = Avatar.Emoji("📊"), background = "file://existing.png", enabledSkills = setOf("existing"))
        val (incoming, parsed) = parseAssistantUpdate(buildJsonObject { put("name", "after") })
        assertNull(parsed)
        val merged = mergeAssistant(latest, incoming, parsed)
        assertEquals(latest.localTools, merged.localTools)
        assertEquals(latest.avatar, merged.avatar)
        assertEquals(latest.background, merged.background)
        assertEquals(latest.enabledSkills, merged.enabledSkills)
        assertEquals("after", merged.name)
    }

    @Test fun `explicit empty clears chart without replacing assistant private fields`() {
        val latest = Assistant(localTools = listOf(LocalToolOption.ChartDisplay), enabledSkills = setOf("existing"))
        val (incoming, parsed) = parseAssistantUpdate(body())
        val merged = mergeAssistant(latest, incoming, parsed)
        assertEquals(emptyList<LocalToolOption>(), merged.localTools)
        assertEquals(latest.enabledSkills, merged.enabledSkills)
    }

    @Test fun `explicit chart turns on via production parse and merge`() {
        val latest = Assistant(localTools = emptyList())
        val (incoming, parsed) = parseAssistantUpdate(body("chart_display"))
        assertEquals(listOf(LocalToolOption.ChartDisplay), mergeAssistant(latest, incoming, parsed).localTools)
    }

    @Test fun `malformed literal options are 400 and do not mutate existing assistant`() {
        val latest = Assistant(localTools = listOf(LocalToolOption.ChartDisplay))
        val values = listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonObject(emptyMap()), JsonArray(emptyList()))
        for (value in values) {
            val raw = buildJsonObject { put("localTools", JsonArray(listOf(buildJsonObject { put("type", value) }))) }
            assertTrue("invalid type $value", runCatching { parseAssistantUpdate(raw) }.exceptionOrNull() is BadRequestException)
            assertEquals(listOf(LocalToolOption.ChartDisplay), latest.localTools)
        }
        for (value in listOf(JsonNull, JsonPrimitive("chart_display"), JsonObject(emptyMap()))) {
            assertTrue(runCatching { parseAssistantUpdate(buildJsonObject { put("localTools", value) }) }.exceptionOrNull() is BadRequestException)
        }
    }

    @Test fun `unknown and wrong-case options still rejected`() {
        for (type in listOf("Chart_Display", "chart", "chart_display_html", "workspace_chart")) {
            assertTrue(runCatching { parseAssistantUpdate(body(type)) }.exceptionOrNull() is BadRequestException)
        }
    }
}
