package me.rerere.rikkihub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tavern.ActivationRecord
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptMarkers
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editing rules the web editor ports to TypeScript (`web-settings/web-ui/lib/tavern.ts`). Both sides run the
 * same file, so the two implementations cannot drift apart: `web-ui/tests/tavern.test.ts` reads it too.
 */
class TavernWebCasesTest {
    private val cases: List<JsonObject> = Json.parseToJsonElement(
        javaClass.getResource("/tavern/web-cases.json")!!.readText(),
    ).jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.intOrNullOf(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int

    /** [expected] 里出现的键逐个对：值为 null 表示键不存在或是 JSON null。 */
    private fun assertSubset(name: String, expected: JsonObject, actual: JsonObject) {
        for ((key, value) in expected) {
            val got = actual[key]
            if (value is JsonNull) assertTrue("$name: $key should be absent but was $got", got == null || got is JsonNull)
            else assertEquals("$name: $key", value, got)
        }
    }

    private fun pairs(layout: List<Pair<String, Boolean>>) = JsonArray(layout.map { (id, on) ->
        JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(on)))
    })

    @Test
    fun `the shared web cases run on the app side`() {
        assertTrue("cases file is not empty", cases.size >= 90)
        for (case in cases) {
            val name = "${case.str("kind")}: ${case.str("name")}"
            val expected = case["expected"]
            when (case.str("kind")) {
                "parseKeyList" -> assertEquals(name, case.strings("expected"), parseKeyList(case.str("text")))
                "formatKeyList" -> assertEquals(name, case.str("expected"), formatKeyList(case.strings("keys")))
                "normalizeFindRegex" -> assertEquals(name, case.str("expected"), normalizeFindRegex(case.str("input")))
                "splitRegexLiteral" -> {
                    val got = splitRegexLiteral(case.str("input"))
                    if (expected is JsonNull) assertEquals(name, null, got)
                    else assertEquals(name, case.strings("expected"), listOf(got!!.first, got.second))
                }
                "withPlacement" -> {
                    val entry = PromptEntry.fromJson(case.getValue("entry").jsonObject)
                    val placement = if (case.str("placement") == "in_chat") EntryPlacement.IN_CHAT else EntryPlacement.RELATIVE
                    val next = entry.withPlacement(placement, case.intOrNullOf("depth") ?: entry.depth, case.intOrNullOf("order") ?: entry.order)
                    assertSubset(name, expected!!.jsonObject, next.toJson())
                }
                "entryPlacement" -> {
                    val entry = PromptEntry.fromJson(case.getValue("entry").jsonObject)
                    assertEquals(name, case.str("expected"), entry.placement.name.lowercase())
                }
                "withTriggers" -> {
                    val entry = PromptEntry.fromJson(case.getValue("entry").jsonObject)
                    assertSubset(name, expected!!.jsonObject, entry.withTriggers(known(case.strings("selected"))).toJson())
                }
                "toggleTrigger" -> {
                    val entry = PromptEntry.fromJson(case.getValue("entry").jsonObject)
                    val type = known(listOf(case.str("type"))).single()
                    val next = entry.withTriggers(if (type in entry.triggers) entry.triggers - type else entry.triggers + type)
                    assertSubset(name, expected!!.jsonObject, next.toJson())
                }
                "wi.primaryKeys" -> assertSubset(name, expected!!.jsonObject, WorldInfoCodec.encodeEntry(entryOf(case).withPrimaryKeys(case.str("text"))))
                "wi.secondaryKeys" -> assertSubset(name, expected!!.jsonObject, WorldInfoCodec.encodeEntry(entryOf(case).withSecondaryKeys(case.str("text"))))
                "wi.probability" -> assertSubset(name, expected!!.jsonObject, WorldInfoCodec.encodeEntry(entryOf(case).withProbability(case.getValue("percent").jsonPrimitive.int)))
                "wi.triggers" -> assertSubset(name, expected!!.jsonObject, WorldInfoCodec.encodeEntry(entryOf(case).withTriggerSet(known(case.strings("selected")))))
                "wi.toggleTrigger" -> {
                    val entry = entryOf(case)
                    val type = known(listOf(case.str("type"))).single()
                    val next = entry.withTriggerSet(if (type in entry.triggerSet) entry.triggerSet - type else entry.triggerSet + type)
                    assertSubset(name, expected!!.jsonObject, WorldInfoCodec.encodeEntry(next))
                }
                "wi.title" -> assertEquals(name, case.str("expected"), entryOf(case).title)
                "wi.matches" -> assertEquals(name, case.getValue("expected").jsonPrimitive.booleanOrNull, entryOf(case).matchesQuery(case.str("query")))
                "layout" -> {
                    var preset = PromptPreset.fromJson(case.getValue("preset").jsonObject)
                    for (op in case.getValue("ops").jsonArray.map { it.jsonObject }) {
                        preset = when (op.str("op")) {
                            "move" -> preset.withEntryMoved(op.str("id"), op.getValue("delta").jsonPrimitive.int)
                            "moveTo" -> preset.withEntryMovedTo(op.str("id"), op.str("target"))
                            "enabled" -> preset.withEntryEnabled(op.str("id"), op.getValue("enabled").jsonPrimitive.booleanOrNull!!)
                            "remove" -> preset.withEntryRemoved(op.str("id"))
                            "add" -> preset.withEntryAdded(PromptEntry.fromJson(op.getValue("entry").jsonObject), op["before"]?.jsonPrimitive?.contentOrNull)
                            else -> error("unknown op ${op.str("op")}")
                        }
                    }
                    assertEquals(name, expected, pairs(preset.layout().entries.map { it.identifier to it.enabled }))
                }
                "textEntries" -> assertEquals(name, case.strings("expected"), PromptPreset.fromJson(case.getValue("preset").jsonObject).textEntries().map { it.identifier })
                "previewText" -> assertEquals(
                    name, case.str("expected"),
                    Assistant(promptPreset = PromptPreset.fromJson(case.getValue("preset").jsonObject)).promptPreviewText(),
                )
                "options" -> {
                    var preset = PromptPreset.fromJson(case.getValue("preset").jsonObject)
                    for (op in case.getValue("ops").jsonArray.map { it.jsonObject }) {
                        preset = when (op.str("op")) {
                            "contextSize" -> preset.withOptions { it.withContextSize(op.intOrNullOf("tokens")) }
                            else -> error("unknown op ${op.str("op")}")
                        }
                    }
                    val o = preset.options
                    val view = buildJsonObject {
                        put("contextSize", o.contextSize)
                        put("responseTokens", o.responseTokens)
                        put("squash", o.squashSystemMessages)
                        put("newChatPrompt", o.newChatPrompt)
                        put("wiFormat", o.wiFormat)
                        put("unlimited", o.contextUnlimited)
                    }
                    assertSubset(name, expected!!.jsonObject, JsonObject(preset.toJson() + view))
                }
                "regex.placement" -> {
                    val script = RegexScript.fromJson(case.getValue("script").jsonObject)
                    val next = script.withPlacement(case.getValue("point").jsonPrimitive.int, case.getValue("on").jsonPrimitive.booleanOrNull!!)
                    assertEquals(name, case.getValue("expected").jsonArray.map { it.jsonPrimitive.int }, next.placement)
                }
                "regex.presetScripts" -> {
                    var preset = PromptPreset.fromJson(case.getValue("preset").jsonObject)
                    if ("set" in case) {
                        val scripts = case.getValue("set").jsonArray.map { RegexScript.fromJson(it.jsonObject) }
                        preset = preset.withRegexScripts(scripts)
                        assertSubset(name, expected!!.jsonObject, preset.toJson())
                    } else {
                        assertEquals(name, case.strings("expected"), preset.regexScripts.map { it.id })
                    }
                }
                "variableDisplay" -> assertEquals(name, case.str("expected"), variableDisplay(case.getValue("value")))
                "activations" -> {
                    val got = ActivationRecord.decode(case.getValue("record"))
                    if (expected is JsonNull) assertEquals(name, null, got)
                    else {
                        val obj = expected!!.jsonObject
                        assertEquals(name, obj.getValue("more").jsonPrimitive.int, got!!.more)
                        assertEquals(name, obj.getValue("total").jsonPrimitive.int, got.total)
                        assertEquals(
                            name,
                            obj.getValue("entries").jsonArray.map { it.jsonObject },
                            got.entries.map {
                                buildJsonObject {
                                    put("bookId", it.bookId); put("bookName", it.bookName); put("uid", it.uid)
                                    put("title", it.title); put("position", it.position); put("order", it.order)
                                }
                            },
                        )
                    }
                }
                else -> error("unknown case kind ${case.str("kind")}")
            }
        }
    }

    private fun entryOf(case: JsonObject) = WorldInfoCodec.decodeEntry(case.getValue("entry").jsonObject)

    private fun known(wires: List<String>): Set<GenerationType> =
        wires.mapNotNull { wire -> GenerationType.entries.firstOrNull { it.wire == wire } }.toSet()
}
