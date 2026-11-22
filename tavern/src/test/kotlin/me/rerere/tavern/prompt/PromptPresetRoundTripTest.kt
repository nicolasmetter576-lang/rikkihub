package me.rerere.tavern.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆预设 JSON 的解码/编码必须无损往返（预设导入在 1B，但编解码现在就在这个包里）：
 * 已知字段、未知字段、字段顺序、`prompt_order` 的多份列表、提示词的全部注入字段都要原样回来。
 */
class PromptPresetRoundTripTest {
    private val defaultText = javaClass.getResource("/preset/Default.json")!!.readText()
    private val defaultJson = Json.parseToJsonElement(defaultText).jsonObject

    private fun roundTrip(obj: JsonObject): JsonObject = PromptPreset.fromJson(obj).toJson()

    @Test
    fun defaultPresetRoundTripsLosslessly() {
        assertEquals(62, defaultJson.size)
        assertEquals(defaultJson, roundTrip(defaultJson))
        // 键顺序也保持
        assertEquals(defaultJson.keys.toList(), roundTrip(defaultJson).keys.toList())
    }

    @Test
    fun defaultPresetSurvivesTextEncoding() {
        val once = PromptPreset.decode(defaultText)
        val text = once.encodeToString()
        assertEquals(defaultJson, Json.parseToJsonElement(text).jsonObject)
        assertEquals(once.toJson(), PromptPreset.decode(text).toJson())
    }

    @Test
    fun defaultPresetLayoutMatchesTheGlobalOrder() {
        val preset = PromptPreset.decode(defaultText)
        val order = defaultJson["prompt_order"]!!.jsonArray
            .map { it.jsonObject }.first { it["character_id"].toString() == PromptPreset.GLOBAL_ORDER_ID.toString() }
        val expected = order["order"]!!.jsonArray.map { it.jsonObject }
            .map { it["identifier"]!!.let { v -> (v as JsonPrimitive).content } to ((it["enabled"] as JsonPrimitive).booleanOrNull == true) }
        val layout = preset.layout().entries.map { it.identifier to it.enabled }
        assertEquals(expected, layout)
    }

    /** 把 Default.json 的每个已知字段改成非默认值，再加一堆未知字段、嵌套结构、多份 prompt_order、提示词的全部字段。 */
    private fun fullFieldPreset(): JsonObject {
        fun bump(v: JsonElement): JsonElement = when {
            v is JsonPrimitive && v.isString -> JsonPrimitive(v.content + "~")
            v is JsonPrimitive && v.booleanOrNull != null -> JsonPrimitive(!v.booleanOrNull!!)
            v is JsonPrimitive && v.doubleOrNull != null -> JsonPrimitive(v.doubleOrNull!! + 1.5)
            else -> v
        }
        val changed = LinkedHashMap<String, JsonElement>()
        for ((k, v) in defaultJson) if (k != "prompts" && k != "prompt_order") changed[k] = bump(v)
        changed["future_scalar"] = JsonPrimitive("kept")
        changed["future_object"] = buildJsonObject { put("a", buildJsonArray { add(JsonPrimitive(1)); add(JsonNull) }); put("b", buildJsonObject { put("c", true) }) }
        changed["future_null"] = JsonNull
        val prompts = buildJsonArray {
            add(buildJsonObject {
                put("identifier", "main"); put("name", "Main Prompt"); put("role", "system"); put("content", "Write {{char}}'s reply.")
                put("system_prompt", true); put("marker", false)
                put("injection_position", 1); put("injection_depth", 3); put("injection_order", 42)
                put("injection_trigger", buildJsonArray { add(JsonPrimitive("normal")); add(JsonPrimitive("continue")) })
                put("forbid_overrides", true)
                put("future_prompt_field", buildJsonObject { put("x", 1) })
            })
            add(buildJsonObject { put("identifier", "chatHistory"); put("name", "Chat History"); put("system_prompt", true); put("marker", true) })
            add(buildJsonObject {
                put("identifier", "custom-1"); put("name", "Custom"); put("role", "assistant"); put("content", "")
                put("system_prompt", false); put("marker", false)
            })
        }
        val orders = buildJsonArray {
            add(buildJsonObject {
                put("character_id", 100000)
                put("order", buildJsonArray {
                    add(buildJsonObject { put("identifier", "main"); put("enabled", true) })
                    add(buildJsonObject { put("identifier", "chatHistory"); put("enabled", false) })
                })
            })
            add(buildJsonObject {
                put("character_id", 100001)
                put("order", buildJsonArray {
                    add(buildJsonObject { put("identifier", "custom-1"); put("enabled", true) })
                    add(buildJsonObject { put("identifier", "main"); put("enabled", true); put("future_order_field", "z") })
                    add(buildJsonObject { put("identifier", "chatHistory"); put("enabled", true) })
                })
            })
            add(buildJsonObject {
                put("character_id", "7")
                put("order", JsonArray(emptyList()))
            })
        }
        // 键顺序：prompts / prompt_order 夹在中间，不放在开头或结尾
        val result = LinkedHashMap<String, JsonElement>()
        val keys = changed.keys.toList()
        val mid = keys.size / 2
        keys.take(mid).forEach { result[it] = changed.getValue(it) }
        result["prompts"] = prompts
        keys.drop(mid).take(3).forEach { result[it] = changed.getValue(it) }
        result["prompt_order"] = orders
        keys.drop(mid + 3).forEach { result[it] = changed.getValue(it) }
        return JsonObject(result)
    }

    @Test
    fun fullFieldPresetRoundTripsLosslessly() {
        val obj = fullFieldPreset()
        val back = roundTrip(obj)
        assertEquals(obj, back)
        assertEquals(obj.keys.toList(), back.keys.toList())
        // 每个提示词对象内部的键顺序也保持
        val a = obj["prompts"]!!.jsonArray.map { it.jsonObject.keys.toList() }
        val b = back["prompts"]!!.jsonArray.map { it.jsonObject.keys.toList() }
        assertEquals(a, b)
    }

    @Test
    fun fullFieldPresetTypedViewsAreFilled() {
        val preset = PromptPreset.fromJson(fullFieldPreset())
        val main = preset.prompts.first { it.identifier == "main" }
        assertEquals(1, main.injectionPosition)
        assertEquals(3, main.injectionDepth)
        assertEquals(42, main.injectionOrder)
        assertEquals(listOf("normal", "continue"), main.injectionTrigger)
        assertEquals(true, main.forbidOverrides)
        assertTrue(main.extra.containsKey("future_prompt_field"))
        // 全局顺序里 chatHistory 启用、custom-1 在最前，禁用状态按 prompt_order 走
        val layout = preset.layout()
        assertEquals(listOf("custom-1", "main", "chatHistory"), layout.entries.map { it.identifier })
        assertEquals(listOf(true, true, true), layout.entries.map { it.enabled })
        // 100000 那份是另一个角色的顺序，不影响全局
        assertEquals(listOf("main", "chatHistory"), preset.layout(100000).entries.map { it.identifier })
        assertEquals(listOf(true, false), preset.layout(100000).entries.map { it.enabled })
    }

    @Test
    fun malformedPartsAreKeptAsUnknownFieldsInsteadOfDropped() {
        val obj = buildJsonObject {
            put("prompts", buildJsonArray { add(JsonPrimitive("not an object")) })
            put("prompt_order", JsonPrimitive("also wrong"))
            put("temperature", 0.5)
        }
        assertEquals(obj, roundTrip(obj))
    }
}
