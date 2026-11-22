package me.rerere.rikkihub.data.ai.tavern

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.TavernSettings
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernInputAndRegexTest {
    private val runtime = TavernRuntime()
    private val input = TavernInputProcessor(runtime)
    private val regexes = TavernRegexes(runtime)

    @After fun close() = runtime.close()

    private val model = Model(modelId = "m1")
    private val settings = Settings(
        displaySetting = Settings().displaySetting.copy(userNickname = "Alice"),
    )

    private fun script(
        id: String, find: String, replace: String, vararg placement: Int,
        markdownOnly: Boolean = false, promptOnly: Boolean = false, runOnEdit: Boolean = false, disabled: Boolean = false,
    ) = RegexScript(
        id = id, scriptName = id, findRegex = find, replaceString = replace, placement = placement.toList(),
        markdownOnly = markdownOnly, promptOnly = promptOnly, runOnEdit = runOnEdit, disabled = disabled,
    )

    private fun assistantWith(vararg scripts: RegexScript) = Assistant(
        name = "Bot",
        promptPreset = AssistantPresets.withSystemPrompt("P").withRegexScripts(scripts.toList()),
    )

    private fun send(text: String, assistant: Assistant, settings: Settings = this.settings, metadata: JsonObject = JsonObject(emptyMap())) = runBlocking {
        input.processSend(
            parts = listOf(UIMessagePart.Text(text)), assistant = assistant, settings = settings, model = model,
            conversationId = null, metadata = metadata, history = emptyList(),
            ownMacros = mapOf("nickname" to { "Ally" }),
        )
    }

    private fun ProcessedInput.text() = (parts.single() as UIMessagePart.Text).text

    // ------------------------------------------------------------ 发送

    @Test fun macrosInTheTypedTextAreEvaluatedAndStored() {
        assertEquals("I am Alice, talking to Bot (Ally)", send("I am {{user}}, talking to {{char}} ({{nickname}})", assistantWith()).text())
    }

    @Test fun regexRunsBeforeMacros() {
        val a = assistantWith(script("r", "/@name/g", "{{user}}", RegexPlacement.USER_INPUT))
        assertEquals("hi Alice", send("hi @name", a).text())
    }

    @Test fun onlyUnflaggedUserInputScriptsRunAtSendTime() {
        val a = assistantWith(
            script("display", "/x/g", "D", RegexPlacement.USER_INPUT, markdownOnly = true),
            script("prompt", "/x/g", "P", RegexPlacement.USER_INPUT, promptOnly = true),
            script("ai", "/x/g", "A", RegexPlacement.AI_OUTPUT),
            script("off", "/x/g", "O", RegexPlacement.USER_INPUT, disabled = true),
            script("on", "/x/g", "U", RegexPlacement.USER_INPUT),
        )
        assertEquals("U", send("x", a).text())
    }

    @Test fun nonTextPartsAreLeftAlone() = runBlocking {
        val image = UIMessagePart.Image("https://example.com/a.png")
        val out = input.processSend(
            parts = listOf(UIMessagePart.Text("{{user}}"), image), assistant = assistantWith(), settings = settings, model = model,
            conversationId = null, metadata = JsonObject(emptyMap()), history = emptyList(), ownMacros = emptyMap(),
        )
        assertEquals(listOf("Alice"), out.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertTrue(out.parts.contains(image))
    }

    @Test fun variableWritesAreReturnedForSaving() {
        val out = send("{{setvar::n::3}}{{setglobalvar::g::9}}done", assistantWith())
        assertEquals("done", out.text())
        val vars = TavernConversationState.from(out.metadata!!).variables
        assertEquals("3", (vars["n"] as JsonPrimitive).content)
        assertEquals("9", (out.globalVariables!!["g"] as JsonPrimitive).content)
    }

    @Test fun nothingToSaveWhenNoVariableChanged() {
        val out = send("plain {{user}}", assistantWith())
        assertNull(out.metadata)
        assertNull(out.globalVariables)
    }

    @Test fun existingConversationVariablesAreVisibleToTheTypedText() {
        val metadata = TavernConversationState(variables = JsonObject(mapOf("mood" to JsonPrimitive("calm")))).toMetadata()
        assertEquals("mood=calm", send("mood={{getvar::mood}}", assistantWith(), metadata = metadata).text())
    }

    @Test fun editingOnlyRunsRunOnEditScriptsAndDoesNotEvaluateMacros() = runBlocking {
        val a = assistantWith(
            script("keep", "/a/g", "1", RegexPlacement.USER_INPUT),
            script("edit", "/b/g", "2", RegexPlacement.USER_INPUT, runOnEdit = true),
        )
        val edited = input.processEdit(listOf(UIMessagePart.Text("ab {{user}}")), a, settings)
        assertEquals("a2 {{user}}", (edited.single() as UIMessagePart.Text).text)
        val untouched = input.processEdit(listOf(UIMessagePart.Text("ab")), assistantWith(), settings)
        assertEquals("ab", (untouched.single() as UIMessagePart.Text).text)
    }

    // ------------------------------------------------------------ 输出与显示

    private fun ctx(assistant: Assistant, settings: Settings = this.settings) =
        TransformerContext(context = me.rerere.rikkihub.data.ai.FakeContext(java.nio.file.Files.createTempDirectory("tavern").toFile()), model = model, assistant = assistant, settings = settings)

    @Test fun outputRegexRewritesAssistantTextAndReasoningOnly() = runBlocking {
        val a = assistantWith(
            script("t", "/cat/g", "dog", RegexPlacement.AI_OUTPUT),
            script("r", "/think/g", "ponder", RegexPlacement.REASONING),
            script("u", "/cat/g", "wolf", RegexPlacement.USER_INPUT),
            script("d", "/cat/g", "fox", RegexPlacement.AI_OUTPUT, markdownOnly = true),
        )
        val transformer = TavernRegexOutputTransformer(runtime)
        val messages = listOf(
            UIMessage.user("cat"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("think cat"), UIMessagePart.Text("a cat"))),
        )
        val out = transformer.visualTransform(ctx(a), messages)
        assertEquals("cat", out[0].toText())
        assertEquals("a dog", (out[1].parts[1] as UIMessagePart.Text).text)
        assertEquals("ponder cat", (out[1].parts[0] as UIMessagePart.Reasoning).reasoning)
    }

    @Test fun outputRegexWithoutScriptsReturnsTheSameList() = runBlocking {
        val messages = listOf(UIMessage.assistant("x"))
        assertTrue(TavernRegexOutputTransformer(runtime).visualTransform(ctx(assistantWith()), messages) === messages)
    }

    @Test fun globalScriptsApplyBeforeTheAssistantsAtOutput() = runBlocking {
        val global = script("g", "/a/g", "b", RegexPlacement.AI_OUTPUT)
        val settings = settings.copy(tavern = TavernSettings(regexScripts = listOf(global)))
        val a = assistantWith(script("l", "/b/g", "c", RegexPlacement.AI_OUTPUT))
        val out = TavernRegexOutputTransformer(runtime).visualTransform(ctx(a, settings), listOf(UIMessage.assistant("a")))
        assertEquals("c", out.single().toText())
    }

    @Test fun displayRegexOnlyRunsMarkdownOnlyScripts() = runBlocking {
        val a = assistantWith(
            script("d", "/secret/g", "***", RegexPlacement.AI_OUTPUT, markdownOnly = true),
            script("stored", "/secret/g", "STORED", RegexPlacement.AI_OUTPUT),
        )
        assertTrue(regexes.hasDisplayScripts(settings, a))
        assertEquals("a ***", regexes.display("a secret", MessageRole.ASSISTANT, null, settings, a))
        assertEquals("a secret", regexes.display("a secret", MessageRole.USER, null, settings, a))
        assertEquals("a secret", regexes.display("a secret", MessageRole.ASSISTANT, null, settings, assistantWith()))
    }

    @Test fun displayRegexFiltersByDepth() = runBlocking {
        val s = script("d", "/x/g", "y", RegexPlacement.AI_OUTPUT, markdownOnly = true).copy(minDepth = 0, maxDepth = 0)
        val a = assistantWith(s)
        assertEquals("y", regexes.display("x", MessageRole.ASSISTANT, 0, settings, a))
        assertEquals("x", regexes.display("x", MessageRole.ASSISTANT, 3, settings, a))
    }

    @Test fun displayWorksWithoutAnAssistantUsingOnlyGlobalScripts() = runBlocking {
        val settings = settings.copy(tavern = TavernSettings(regexScripts = listOf(
            script("g", "/hi/g", "HELLO", RegexPlacement.AI_OUTPUT, markdownOnly = true),
        )))
        assertEquals("HELLO", regexes.display("hi", MessageRole.ASSISTANT, null, settings, null))
    }

    @Test fun replacementStringsSupportMatchAndNamedGroups() = runBlocking {
        val a = assistantWith(
            script("g", "/(?<word>\\w+)@x/g", "<\$<word>> at x [{{match}}]", RegexPlacement.AI_OUTPUT, markdownOnly = true),
        )
        assertEquals("<bob> at x [bob@x]", regexes.display("bob@x", MessageRole.ASSISTANT, null, settings, a))
    }
}

class TavernConversationStateNoteTest {
    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))
    private val defaults = me.rerere.rikkihub.data.model.AuthorsNoteDefaults(prompt = "be brief", interval = 2, depth = 3, position = 1, role = 1)

    @Test fun missingNoteKeysFallBackToTheGlobalDefaults() {
        val fields = TavernConversationState.readNoteFields(JsonObject(emptyMap()), defaults)
        assertEquals("be brief", fields.prompt)
        assertEquals(2, fields.interval)
        assertEquals(3, fields.depth)
        assertEquals(me.rerere.tavern.core.PromptRole.USER, fields.role)
        val note = TavernConversationState.from(JsonObject(emptyMap()), defaults).authorsNote
        assertEquals("be brief", note?.prompt)
    }

    @Test fun anEmptyOwnNoteOverridesANonEmptyDefault() {
        val metadata = TavernConversationState.writeNoteFields(
            JsonObject(emptyMap()),
            TavernConversationState.readNoteFields(JsonObject(emptyMap()), defaults).copy(prompt = ""),
        )
        assertNull(TavernConversationState.from(metadata, defaults).authorsNote)
    }

    @Test fun writtenFieldsRoundTripAndLeaveOtherKeysAlone() {
        val before = obj("variables" to obj("mood" to JsonPrimitive("calm")), "foreign" to JsonPrimitive(1))
        val fields = TavernConversationState.NoteFields("remember the sea", interval = 4, depth = 0, position = 0, role = me.rerere.tavern.core.PromptRole.ASSISTANT)
        val after = TavernConversationState.writeNoteFields(before, fields)
        assertEquals(fields, TavernConversationState.readNoteFields(after, defaults))
        assertEquals(before["variables"], after["variables"])
        assertEquals(before["foreign"], after["foreign"])
        assertTrue(TavernConversationState.hasOwnNote(after))
        assertFalse(TavernConversationState.hasOwnNote(before))
    }

    @Test fun clearingGoesBackToFollowingTheDefaults() {
        val own = TavernConversationState.writeNoteFields(JsonObject(emptyMap()), TavernConversationState.NoteFields("mine"))
        val cleared = TavernConversationState.clearNoteFields(own)
        assertFalse(TavernConversationState.hasOwnNote(cleared))
        assertEquals("be brief", TavernConversationState.readNoteFields(cleared, defaults).prompt)
    }

    @Test fun savingTheStateAfterATurnKeepsTheNoteKeys() {
        val own = TavernConversationState.writeNoteFields(JsonObject(emptyMap()), TavernConversationState.NoteFields("mine", interval = 3))
        val state = TavernConversationState.from(own, defaults).copy(variables = obj("k" to JsonPrimitive("v")))
        val written = state.toMetadata()
        assertEquals("mine", TavernConversationState.readNoteFields(written, defaults).prompt)
        assertEquals(3, TavernConversationState.readNoteFields(written, defaults).interval)
        assertEquals(obj("k" to JsonPrimitive("v")), written["variables"])
    }

    @Test fun invalidPositionsFallBackToTheDefaultPosition() {
        val bad = obj("note_position" to JsonPrimitive(9), "note_prompt" to JsonPrimitive("x"))
        assertEquals(1, TavernConversationState.readNoteFields(bad, defaults).position)
    }

    @Test fun writingVariablesDropsTheKeyWhenEmpty() {
        val metadata = obj("variables" to obj("a" to JsonPrimitive(1)), "other" to JsonPrimitive(true))
        val emptied = TavernConversationState.writeVariables(metadata, JsonObject(emptyMap()))
        assertFalse("variables" in emptied)
        assertEquals(JsonPrimitive(true), emptied["other"])
    }
}
