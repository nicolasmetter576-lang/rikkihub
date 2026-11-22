package me.rerere.rikkihub.data.ai.tavern

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.CharacterLocalSettings
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.TavernSettings
import me.rerere.tavern.card.CardCodec
import me.rerere.tavern.card.CharacterBooks
import me.rerere.tavern.card.CharacterCard
import me.rerere.tavern.core.TavernRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class CharacterTurnTest {
    private val runtime = TavernRuntime()
    private val preparation = TurnPreparation(runtime)
    private val model = Model(modelId = "m1", displayName = "M One")

    @After fun close() = runtime.close()

    private fun fixtureCard(name: String = "v2-harbor-cartographer.json") =
        CardCodec.parse(CharacterTurnTest::class.java.getResource("/cards/$name")!!.readText()).card

    private fun record(card: CharacterCard, local: CharacterLocalSettings = CharacterLocalSettings()) =
        CharacterRecord(Uuid.random(), card, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, local = local)

    private fun active(card: CharacterCard, settings: Settings = Settings(), books: Boolean = true): Pair<ActiveCharacter, Settings> {
        val book = card.characterBook?.let { CharacterBooks.toWorldInfo(it) }
        val lore = book?.let { Lorebook(name = it.name ?: "book", entries = it.entries, extra = it.extra) }
        val record = record(card, CharacterLocalSettings(embeddedBookId = lore?.id.takeIf { books }))
        val withBook = if (lore != null && books) settings.copy(lorebooks = settings.lorebooks + lore) else settings
        return ActiveCharacter.of(record, withBook) to withBook
    }

    private val assistant = Assistant(name = "Bot", promptPreset = AssistantPresets.withSystemPrompt("You are a helpful guide."), tavernMode = true)

    private fun build(
        messages: List<UIMessage>,
        character: ActiveCharacter?,
        settings: Settings,
        assistant: Assistant = this.assistant,
    ) = runBlocking {
        val request = TurnRequest(
            assistant = assistant, settings = settings, model = model, conversationId = null,
            metadata = JsonObject(emptyMap()), conversationLibraryEntryIds = emptySet(), conversationLorebookIds = emptySet(),
            customSystemPrompt = null, messages = messages, userName = "Alice",
            charName = RikkihubMacros.charName(assistant, character), character = character,
            random = TavernRandom { 0.0 },
        )
        val turn = preparation.prepare(request)
        turn to turn.build(messages, "")
    }

    private fun texts(messages: List<UIMessage>) = messages.map { "${it.role.name.lowercase()}:${it.toText()}" }

    @Test
    fun theCardFieldsGoIntoTheRequestWithNamesFilledIn() {
        val (character, settings) = active(fixtureCard(), books = false)
        val (_, request) = build(listOf(UIMessage.user("Hello")), character, settings)
        val all = texts(request).joinToString("\n")
        assertTrue(all, "Ines Okafor draws the coastline from memory" in all)          // description, {{char}} resolved
        assertTrue("exacting, warm once trusted" in all)                                  // personality
        assertTrue("Alice brings Ines Okafor a chart with a river that does not exist." in all) // scenario with both names
        assertFalse("{{char}}" in all || "{{user}}" in all)
        // example dialogue is sent too
        assertTrue("Useless charts are the honest ones." in all)
    }

    @Test
    fun withoutACharacterTheRequestHasNoCardText() {
        val (_, request) = build(listOf(UIMessage.user("Hello")), null, Settings())
        assertEquals(listOf("system:You are a helpful guide.", "user:Hello"), texts(request))
    }

    @Test
    fun theCardSystemPromptReplacesTheMainPromptAndPostHistoryReplacesTheJailbreak() {
        val (character, settings) = active(fixtureCard(), books = false)
        val (_, request) = build(listOf(UIMessage.user("Hello")), character, settings)
        val all = texts(request)
        assertTrue(all.any { "You are Ines Okafor. Stay in character and keep replies under six sentences." in it })
        assertFalse(all.any { "You are a helpful guide." in it })
        // the post-history instruction comes after the chat
        assertTrue(all.last().endsWith("Never mention that you are drawing from a book."))
    }

    @Test
    fun theDepthPromptIsInsertedIntoTheChatAtItsDepth() {
        val (character, settings) = active(fixtureCard(), books = false)
        val chat = listOf("one", "two", "three", "four", "five").mapIndexed { i, t -> if (i % 2 == 0) UIMessage.user(t) else UIMessage.assistant(t) }
        val (_, request) = build(chat, character, settings)
        val bodies = request.map { it.toText() }
        val at = bodies.indexOfFirst { it.contains("is checking a chart while she talks") }
        assertTrue(texts(request).toString(), at >= 0)
        // depth 3 counts from the end of the chat: three chat messages (three, four, five) come after it
        assertEquals(listOf("three", "four", "five"), bodies.drop(at + 1).take(3))
        assertEquals("two", bodies[at - 1])
    }

    @Test
    fun thePersonaDescriptionIsSentEvenWithoutACharacter() {
        val settings = Settings(tavern = TavernSettings(personaDescription = "{{user}} is a retired navigator."))
        val (turn, request) = build(listOf(UIMessage.user("Hello")), null, settings)
        assertTrue(texts(request).toString(), texts(request).any { "Alice is a retired navigator." in it })
        assertNotNull(turn)
    }

    @Test
    fun theCharacterBookActivatesOnAKeywordAndShowsUpInTheRecord() {
        val (character, settings) = active(fixtureCard())
        val (turn, request) = build(listOf(UIMessage.user("Tell me about the saltmarsh please")), character, settings)
        assertTrue(texts(request).toString(), texts(request).any { "The Saltmarsh floods twice a year" in it })
        val activation = turn.activations.single { it.entry.comment == "Saltmarsh" }
        assertEquals("Coastline Notes", activation.bookName)
        // the constant (disabled) entry stays out, and a message without keywords activates nothing
        val (_, quiet) = build(listOf(UIMessage.user("Good morning")), character, settings)
        assertFalse(texts(quiet).any { "Saltmarsh" in it })
    }

    @Test
    fun aDisabledCharacterBookIsLeftOut() {
        val (character, settings) = active(fixtureCard())
        val off = settings.copy(lorebooks = settings.lorebooks.map { it.copy(enabled = false) })
        val resolved = ActiveCharacter.of(character.record, off)
        assertTrue(resolved.books.isEmpty())
        val (_, request) = build(listOf(UIMessage.user("saltmarsh")), resolved, off)
        assertFalse(texts(request).any { "Saltmarsh floods" in it })
    }

    @Test
    fun cardRegexScriptsOnlyRunOnceTheUserHasAllowedThem() {
        val script = JsonObject(mapOf(
            "id" to JsonPrimitive("s1"), "scriptName" to JsonPrimitive("swap"), "findRegex" to JsonPrimitive("/river/g"),
            "replaceString" to JsonPrimitive("RIVERBED"), "placement" to JsonArray(listOf(JsonPrimitive(1))),
            "promptOnly" to JsonPrimitive(true), "disabled" to JsonPrimitive(false),
        ))
        val card = fixtureCard().withExtension("regex_scripts", JsonArray(listOf(script)))
        val (denied, settings) = active(card, books = false)
        assertFalse(denied.regexAllowed)
        assertTrue(denied.regexScripts.isEmpty())
        val (_, before) = build(listOf(UIMessage.user("the river is wide")), denied, settings)
        assertTrue(texts(before).any { "the river is wide" in it })
        val allowedSettings = settings.copy(tavern = settings.tavern.copy(regexAllowed = setOf(ActiveCharacter.regexSource(denied.id))))
        val allowed = ActiveCharacter.of(denied.record, allowedSettings)
        assertEquals(1, allowed.regexScripts.size)
        val (_, after) = build(listOf(UIMessage.user("the river is wide")), allowed, allowedSettings)
        assertTrue(texts(after).toString(), texts(after).any { "the RIVERBED is wide" in it })
        assertFalse(texts(after).any { "the river is wide" in it })
    }

    @Test
    fun theScriptOrderIsGlobalThenCharacterThenAssistant() {
        val card = fixtureCard()
        val (character, _) = active(card, books = false)
        val allowed = ActiveCharacter(character.record, emptyList(), regexAllowed = true)
        val global = me.rerere.tavern.regex.RegexScript(id = "g", scriptName = "global")
        val preset = AssistantPresets.create(
            listOf(AssistantPresets.mainEntry("x")), listOf(me.rerere.tavern.regex.RegexScript(id = "a", scriptName = "assistant")),
        )
        val scripts = RegexScripts.of(Settings(tavern = TavernSettings(regexScripts = listOf(global))), preset, allowed)
        assertEquals(listOf("global", "Bracket asides", "assistant"), scripts.map { it.scriptName })
    }

    @Test
    fun charNameIsTheCharactersNameWhenBound() {
        val (character, _) = active(fixtureCard(), books = false)
        assertEquals("Ines Okafor", RikkihubMacros.charName(assistant, character))
        assertEquals("Bot", RikkihubMacros.charName(assistant, null))
    }

    @Test
    fun theDefaultLayoutHasTheCardMarkersAndExistingPresetsGetThemOnce() {
        val ids = AssistantPresets.withSystemPrompt("x").layout().entries.map { it.identifier }
        assertEquals(listOf("worldInfoBefore", "main", "personaDescription", "charDescription", "charPersonality", "scenario",
            "rikkihubContext", "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak"), ids)
        // an older preset without them: markers go in before rikkihubContext, and a second pass changes nothing
        val old = AssistantPresets.withSystemPrompt("x")
        val stripped = old.copy(
            prompts = old.prompts.filter { it.identifier !in setOf("personaDescription", "charDescription", "charPersonality", "scenario") },
            promptOrder = old.promptOrder.map { l -> l.copy(order = l.order.filter { it.identifier !in setOf("personaDescription", "charDescription", "charPersonality", "scenario") }) },
        )
        val fixed = AssistantPresets.withCardMarkers(stripped)
        assertEquals(ids, fixed.layout().entries.map { it.identifier })
        assertTrue(AssistantPresets.withCardMarkers(fixed) === fixed)
    }

    @Test
    fun withTavernModeOffTheCardIsNotUsed() {
        val (character, settings) = active(fixtureCard())
        val (turn, request) = build(
            listOf(UIMessage.user("Tell me about the saltmarsh please")), character, settings, assistant.copy(tavernMode = false),
        )
        // 卡字段、示例对话、卡的系统提示覆盖、历史之后的指令、深度提示、角色书都不用
        assertEquals(listOf("system:You are a helpful guide.", "user:Tell me about the saltmarsh please"), texts(request))
        assertTrue(turn.activations.isEmpty())
    }
}
