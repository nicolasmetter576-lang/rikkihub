package me.rerere.rikkihub.data.ai.tavern

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.tavern.card.CardCodec
import me.rerere.tavern.core.TavernRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class GreetingBuilderTest {
    private val runtime = TavernRuntime()
    private val builder = GreetingBuilder(runtime)

    @After fun close() = runtime.close()

    private val assistant = Assistant(name = "Bot", promptPreset = AssistantPresets.withSystemPrompt("x"))
    private val settings = Settings(displaySetting = DisplaySetting(userNickname = "Alice"))

    private fun character(json: String, allowed: Boolean = false) = ActiveCharacter(
        CharacterRecord(Uuid.random(), CardCodec.parse(json).card, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
        books = emptyList(), regexAllowed = allowed,
    )

    private fun build(c: ActiveCharacter, s: Settings = settings) =
        runBlocking { builder.build(c, assistant, s, Model(modelId = "m"), emptyMap(), random = TavernRandom { 0.0 }) }

    @Test
    fun firstMessageThenAlternatesBecomeTheBranchesOfOneNode() {
        val fixture = GreetingBuilderTest::class.java.getResource("/cards/v2-harbor-cartographer.json")!!.readText()
        val built = build(character(fixture))!!
        val texts = built.node.messages.map { it.toText() }
        assertEquals(3, texts.size)
        assertEquals("That river is wrong, Alice. Sit down and I will show you where it really runs.", texts[0])
        assertEquals("*Ines Okafor unrolls a sheet across the table.* Another lost traveller, Alice?", texts[1])
        assertEquals("Mind the ink, Alice. It is still wet.", texts[2])
        assertEquals(0, built.node.selectIndex)
        assertTrue(built.node.messages.all { it.role == me.rerere.ai.core.MessageRole.ASSISTANT })
    }

    @Test
    fun anEmptyFirstMessageStartsFromTheAlternates() {
        val built = build(character("""{"name":"N","first_mes":"","alternate_greetings":["a","b"]}"""))!!
        assertEquals(listOf("a", "b"), built.node.messages.map { it.toText() })
    }

    @Test
    fun noGreetingsMeansNoNode() {
        assertNull(build(character("""{"name":"N"}""")))
    }

    @Test
    fun outputRegexRunsBeforeMacrosButOnlyOnceAllowedAndNeverForDisplayOnlyScripts() {
        val script = { name: String, markdownOnly: Boolean -> JsonObject(mapOf(
            "id" to JsonPrimitive(name), "scriptName" to JsonPrimitive(name), "findRegex" to JsonPrimitive("/hello/g"),
            "replaceString" to JsonPrimitive("hi {{user}}"), "placement" to JsonArray(listOf(JsonPrimitive(2))),
            "markdownOnly" to JsonPrimitive(markdownOnly),
        )) }
        val json = { scripts: List<JsonObject> ->
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"N","first_mes":"hello there","extensions":{"regex_scripts":${JsonArray(scripts)}}}}"""
        }
        // not allowed yet: the text is untouched
        assertEquals("hello there", build(character(json(listOf(script("store", false))), allowed = false))!!.node.messages[0].toText())
        // allowed: regex first, then the macro inside the replacement is evaluated
        assertEquals("hi Alice there", build(character(json(listOf(script("store", false))), allowed = true))!!.node.messages[0].toText())
        // a display-only script is not applied to the stored text
        assertEquals("hello there", build(character(json(listOf(script("show", true))), allowed = true))!!.node.messages[0].toText())
    }

    @Test
    fun setvarInTheSelectedGreetingBecomesAChatVariableAndInOthersLeavesNoTrace() {
        val built = build(character("""{"name":"N","first_mes":"{{setvar::mood::calm}}Hi","alternate_greetings":["{{setvar::mood::angry}}Grr"]}"""))!!
        assertEquals(listOf("Hi", "Grr"), built.node.messages.map { it.toText() })
        val vars = TavernConversationState.from(built.metadata!!).variables
        assertEquals(JsonPrimitive("calm"), vars["mood"])
        assertNotNull(built.metadata)
        assertNull(build(character("""{"name":"N","first_mes":"plain"}"""))!!.metadata)
    }

    @Test
    fun cardFieldMacrosInTheGreetingAreFilledIn() {
        val built = build(character("""{"name":"Nova","description":"a courier","first_mes":"I am {{char}}, {{description}}. Hello {{user}}."}"""))!!
        assertEquals("I am Nova, a courier. Hello Alice.", built.node.messages[0].toText())
    }
}
