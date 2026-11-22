package me.rerere.rikkihub.data.character

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.tavern.prompt.PromptPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PresetPortingTest {
    private fun fixture(name: String) =
        Json.parseToJsonElement(PresetPortingTest::class.java.getResource("/presets/$name")!!.readText()).jsonObject

    private val ferry = PresetPorting.import(fixture("night-ferry.json"), "Night Ferry.json")

    @Test
    fun theSamplingParametersBecomeTypedAssistantFields() {
        val a = ferry.assistant
        assertEquals("Night Ferry", a.name)
        assertTrue("an imported preset starts in SillyTavern mode", a.tavernMode)
        assertEquals(0.9f, a.temperature)
        assertEquals(0.95f, a.topP)
        assertEquals(40, a.topK)
        assertEquals(0.3f, a.frequencyPenalty)
        assertEquals(1.1f, a.repetitionPenalty)
        assertEquals(0.05f, a.minP)
        assertEquals(42L, a.seed)
        assertEquals(700, a.maxTokens)
        assertFalse(a.streamOutput)
        // inactive values are not carried over
        assertNull(a.presencePenalty)   // 0
        assertNull(a.topA)              // 0
    }

    @Test
    fun neutralValuesAreNotImported() {
        val json = JsonObject(fixture("night-ferry.json") + mapOf(
            "top_k" to JsonPrimitive(0), "frequency_penalty" to JsonPrimitive(0), "repetition_penalty" to JsonPrimitive(1),
            "min_p" to JsonPrimitive(0), "seed" to JsonPrimitive(-1), "openai_max_tokens" to JsonPrimitive(0),
        ))
        val a = PresetPorting.import(json, "x.json").assistant
        assertNull(a.topK); assertNull(a.frequencyPenalty); assertNull(a.repetitionPenalty); assertNull(a.minP); assertNull(a.seed); assertNull(a.maxTokens)
    }

    @Test
    fun aFilledUpOutputLimitFollowsTheModelAndStillExportsAsWritten() {
        val json = JsonObject(fixture("night-ferry.json") + mapOf("openai_max_tokens" to JsonPrimitive(65535)))
        val imported = PresetPorting.import(json, "x.json")
        assertNull(imported.assistant.maxTokens)
        assertFalse("openai_max_tokens" in imported.report.samplingImported)
        assertEquals(JsonPrimitive(65535), PresetPorting.export(imported.assistant)["openai_max_tokens"])
        // just below the threshold is a real cap and is kept
        val capped = JsonObject(fixture("night-ferry.json") + mapOf("openai_max_tokens" to JsonPrimitive(32767)))
        assertEquals(32767, PresetPorting.import(capped, "x.json").assistant.maxTokens)
    }

    @Test
    fun connectionFieldsAreDroppedAndReported() {
        val report = ferry.report
        for (k in listOf("chat_completion_source", "openai_model", "claude_model", "custom_url", "reverse_proxy", "proxy_password", "api_url_scale", "n", "logit_bias")) {
            assertTrue("$k reported", k in report.connectionKeysSkipped)
        }
        val stored = ferry.assistant.promptPreset.toJson()
        for (k in listOf("proxy_password", "reverse_proxy", "custom_url", "openai_model", "chat_completion_source")) assertFalse("$k stored", k in stored)
        // keys the tavern writes but rikkihub has no use for stay as they were
        assertEquals(JsonPrimitive(true), stored["show_thoughts"])
        assertEquals(fixture("night-ferry.json")["vendor_extra_flag"], stored["vendor_extra_flag"])
    }

    @Test
    fun theOldGlobalOrderIsCopiedToTheCurrentOneAndTheMarkersRikkihubNeedsAreAdded() {
        val layout = ferry.assistant.promptPreset.layout().entries.map { it.identifier }
        assertEquals(
            listOf("main", "worldInfoBefore", "ferryLog", "personaDescription", "charDescription", "charPersonality", "scenario",
                "rikkihubContext", "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak"),
            layout,
        )
        assertEquals(32768, ferry.assistant.promptPreset.options.contextSize)
        assertEquals(2, ferry.assistant.promptPreset.options.namesBehavior.code)
        assertTrue(ferry.assistant.promptPreset.options.squashSystemMessages)
    }

    @Test
    fun theBundledRegexScriptsComeInDisabled() {
        assertEquals(1, ferry.report.regexScriptsDisabled)
        val script = ferry.assistant.promptPreset.regexScripts.single()
        assertTrue(script.disabled)
        assertEquals("Quiet the foghorn", script.scriptName)
    }

    @Test
    fun theUpstreamDefaultPresetImportsWithItsOwnOrder() {
        val imported = PresetPorting.import(fixture("upstream-default.json"), "Default.json")
        val layout = imported.assistant.promptPreset.layout().entries
        // the tavern's own default order, plus the one marker rikkihub adds
        val expected = PromptPreset.defaultOrder.map { it.identifier }
        assertEquals(expected.filter { it != "enhanceDefinitions" || true }.size + 1, layout.size)
        assertTrue(layout.any { it.identifier == "rikkihubContext" })
        assertEquals(expected, layout.map { it.identifier }.filter { it != "rikkihubContext" })
        assertTrue(imported.report.connectionKeysSkipped.size > 10)
        assertEquals("Default", imported.assistant.name)
    }

    @Test
    fun exportPutsTheSamplingParametersBackAndLeavesRikkihubsMarkerOut() {
        val out = PresetPorting.export(ferry.assistant)
        assertEquals(JsonPrimitive(40), out["top_k"])
        assertEquals(0.9, (out["temperature"] as JsonPrimitive).content.toDouble(), 0.0001)
        assertEquals(JsonPrimitive(42L), out["seed"])
        assertEquals(JsonPrimitive(700), out["openai_max_tokens"])
        assertEquals(JsonPrimitive(false), out["stream_openai"])
        val text = out.toString()
        assertFalse(text, "rikkihubContext" in text)
        assertFalse("proxy_password" in text)
        assertEquals(JsonPrimitive(32768), out["openai_max_context"])
    }

    @Test
    fun importExportImportKeepsThePromptsAndTheirOrder() {
        val again = PresetPorting.import(PresetPorting.export(ferry.assistant), "Night Ferry.json")
        assertEquals(
            ferry.assistant.promptPreset.layout().entries.map { it.identifier to it.enabled },
            again.assistant.promptPreset.layout().entries.map { it.identifier to it.enabled },
        )
        assertEquals(ferry.assistant.promptPreset.prompts.map { it.content.orEmpty() }.sorted(), again.assistant.promptPreset.prompts.map { it.content.orEmpty() }.sorted())
        assertEquals(ferry.assistant.copy(id = again.assistant.id, promptPreset = again.assistant.promptPreset).topK, again.assistant.topK)
        assertEquals(40, again.assistant.topK)
        assertEquals(42L, again.assistant.seed)
    }

    @Test
    fun exportingTheImportedUpstreamPresetGivesBackTheOriginalMinusTheConnectionFields() {
        val original = fixture("upstream-default.json")
        val imported = PresetPorting.import(original, "Default.json")
        val out = PresetPorting.export(imported.assistant)
        fun norm(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
            is JsonObject -> e.mapValues { norm(it.value) }
            is kotlinx.serialization.json.JsonArray -> e.map(::norm)
            is JsonPrimitive -> if (e.isString) e.content else e.content.toDoubleOrNull() ?: e.content
            else -> null
        }
        val skipped = imported.report.connectionKeysSkipped.toSet()
        // everything the tavern wrote, except the connection keys, comes out with the same value
        for ((k, v) in original) {
            if (k in skipped) continue
            assertEquals("key $k", norm(v), norm(out[k] ?: error("lost key $k")))
        }
        // and nothing but the stream flag is new
        val added = out.keys - original.keys
        assertEquals(setOf("stream_openai").filter { "stream_openai" !in original }.toSet(), added)
    }

    @Test
    fun filesThatAreNotPresetsAreRejectedWithAMessage() {
        for (json in listOf("""{"name":"a card"}""", """{"prompts":[],"prompt_order":[]}""", """{"prompts":{},"prompt_order":[]}""")) {
            try {
                PresetPorting.import(Json.parseToJsonElement(json).jsonObject, "x.json")
                fail("should reject $json")
            } catch (e: PresetFormatException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun theNameComesFromTheFileOrTheNameKey() {
        val noName = PresetPorting.import(fixture("night-ferry.json"), "").assistant.name
        assertEquals("Imported preset", noName)
        val named = PresetPorting.import(JsonObject(fixture("night-ferry.json") + ("name" to JsonPrimitive("Lantern"))), "x.json").assistant.name
        assertEquals("Lantern", named)
    }
}
