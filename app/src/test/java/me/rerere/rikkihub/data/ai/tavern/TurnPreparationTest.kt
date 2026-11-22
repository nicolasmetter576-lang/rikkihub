package me.rerere.rikkihub.data.ai.tavern

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.TavernSettings
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.withEntryAdded
import me.rerere.rikkihub.data.model.withEntryEnabled
import me.rerere.rikkihub.data.model.withEntryUpdated
import me.rerere.rikkihub.data.model.withOptions
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.prompt.InjectionPosition
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoPosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TurnPreparationTest {
    private val runtime = TavernRuntime()
    private val preparation = TurnPreparation(runtime)

    @After fun close() = runtime.close()

    private val model = Model(modelId = "m1", displayName = "M One")

    private fun user(text: String) = UIMessage.user(text)
    private fun assistant(text: String) = UIMessage.assistant(text)
    private fun toolAssistant() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text("calling"),
            UIMessagePart.Tool(toolCallId = "c1", toolName = "t", input = "{}", output = listOf(UIMessagePart.Text("ok"))),
        ),
    )

    private fun textEntry(id: String, content: String) = PromptEntry(
        identifier = id, name = id, role = PromptRole.SYSTEM, content = content, systemPrompt = false, marker = false,
    )

    /** 这里测的是完整的酒馆组装，助手默认开酒馆模式；关时的精简组装见文件末尾。 */
    private fun assistantWith(prompt: String = "", block: Assistant.() -> Assistant = { this }) =
        Assistant(name = "Bot", promptPreset = AssistantPresets.withSystemPrompt(prompt), tavernMode = true).block()

    private fun request(
        assistant: Assistant,
        messages: List<UIMessage>,
        settings: Settings = Settings(),
        metadata: JsonObject = JsonObject(emptyMap()),
        dryRun: Boolean = false,
        random: TavernRandom = TavernRandom { 0.0 },
        customSystemPrompt: String? = null,
        conversationLibrary: Set<Uuid> = emptySet(),
        conversationBooks: Set<Uuid> = emptySet(),
    ) = TurnRequest(
        assistant = assistant, settings = settings, model = model, conversationId = null, metadata = metadata,
        conversationLibraryEntryIds = conversationLibrary, conversationLorebookIds = conversationBooks,
        customSystemPrompt = customSystemPrompt, messages = messages, dryRun = dryRun,
        userName = "Alice", charName = "Bot", random = random,
        ownMacros = mapOf("nickname" to { "Ally" }),
    )

    private fun texts(messages: List<UIMessage>) = messages.map { "${it.role.name.lowercase()}${if (it.isSynthetic) "*" else ""}:${it.toText()}" }

    private fun build(
        assistant: Assistant,
        messages: List<UIMessage>,
        context: String = "",
        settings: Settings = Settings(),
        metadata: JsonObject = JsonObject(emptyMap()),
    ) = runBlocking {
        preparation.prepare(request(assistant, messages, settings, metadata)).build(messages, context)
    }

    // ------------------------------------------------------------ 历史原样、预设宏求值

    @Test fun historyIsSentVerbatimAndNeverMacroEvaluated() {
        val tricky = "Escape \\{ and \\} stay, <USER> stays, {{user}} stays, LaTeX \\{x\\}"
        val out = build(assistantWith("P"), listOf(user(tricky), assistant("{{char}} replies <BOT>")))
        assertEquals(listOf("system*:P", "user:$tricky", "assistant:{{char}} replies <BOT>"), texts(out))
    }

    @Test fun historyMessagesAreTheSameObjectsWhenNothingChanges() {
        val messages = listOf(user("hi"), assistant("hello"))
        val out = build(assistantWith("P"), messages)
        assertTrue(out.contains(messages[0]) && out.contains(messages[1]))
    }

    @Test fun presetTextIsMacroEvaluatedOnce() {
        val out = build(assistantWith("Hi {{user}}, I am {{char}} on {{model}} ({{nickname}}). {{unknown}}"), listOf(user("x")))
        assertEquals("system*:Hi Alice, I am Bot on m1 (Ally). {{unknown}}", texts(out).first())
    }

    @Test fun evaluationIsFrozenAcrossRequestsOfTheSameTurn() = runBlocking {
        var calls = 0
        val random = TavernRandom { (calls++ % 3) / 3.0 }
        val messages = listOf(user("x"))
        val turn = preparation.prepare(request(assistantWith("{{random::a::b::c}}"), messages, random = random))
        val first = turn.build(messages, "").first().toText()
        val callsAfterFirst = calls
        val second = turn.build(messages + assistant("y"), "").first().toText()
        assertEquals(first, second)
        assertEquals("no further random draws once frozen", callsAfterFirst, calls)
    }

    @Test fun rikkihubContextIsAppendedToTheSystemBlockAndNotEvaluated() {
        val out = build(assistantWith("P"), listOf(user("x")), context = "memory {{user}}\ntool prompt")
        assertEquals("system*:P\nmemory {{user}}\ntool prompt", texts(out).first())
    }

    @Test fun noPromptAndNoContextMeansNoSystemMessage() {
        assertEquals(listOf("user:x"), texts(build(assistantWith(""), listOf(user("x")))))
    }

    @Test fun conversationOverrideReplacesTheTextEntriesAndMigratesOldPlaceholders() = runBlocking {
        val assistant = assistantWith("assistant prompt") { copy(allowConversationSystemPrompt = true) }
        val messages = listOf(user("x"))
        val overridden = preparation.prepare(request(assistant, messages, customSystemPrompt = "for {user} only")).build(messages, "")
        assertEquals("system*:for Alice only", texts(overridden).first())
        val ignored = preparation.prepare(request(assistant.copy(allowConversationSystemPrompt = false), messages, customSystemPrompt = "x")).build(messages, "")
        assertEquals("system*:assistant prompt", texts(ignored).first())
    }

    // ------------------------------------------------------------ 变量

    @Test fun setvarPersistsInTheConversationStateAndTheNextTurnReadsIt() = runBlocking {
        val messages = listOf(user("x"))
        val first = preparation.prepare(request(assistantWith("{{setvar::mood::happy}}start"), messages))
        val state = first.newConversationState().toMetadata()
        assertEquals("happy", (state[TavernConversationState.VARIABLES] as JsonObject)["mood"]?.let { (it as JsonPrimitive).content })

        val second = preparation.prepare(request(assistantWith("mood={{getvar::mood}}"), messages, metadata = state))
        assertEquals("system*:mood=happy", texts(second.build(messages, "")).first())
    }

    @Test fun globalVariablesAreReportedWhenChanged() = runBlocking {
        val messages = listOf(user("x"))
        val turn = preparation.prepare(request(assistantWith("{{setglobalvar::g::1}}"), messages))
        assertTrue(turn.globalVariablesChanged)
        assertEquals("1", (turn.newGlobalVariables()["g"] as JsonPrimitive).content)
        val untouched = preparation.prepare(request(assistantWith("plain"), messages))
        assertFalse(untouched.globalVariablesChanged)
    }

    // ------------------------------------------------------------ 世界书

    private fun settingsWithBook(vararg entries: WorldInfoEntry): Pair<Settings, Lorebook> {
        val book = Lorebook(name = "Lore", entries = entries.toList())
        return Settings(lorebooks = listOf(book)) to book
    }

    private fun bound(book: Lorebook) = assistantWith("P") { copy(lorebookIds = setOf(book.id)) }

    @Test fun constantEntryAndKeywordEntryActivateAndReportTheirActivation() = runBlocking {
        val (settings, book) = settingsWithBook(
            WorldInfoEntry(uid = 1, content = "ALWAYS", constant = true, position = WorldInfoPosition.AFTER),
            WorldInfoEntry(uid = 2, key = listOf("dragon"), content = "DRAGON LORE", position = WorldInfoPosition.AFTER),
            WorldInfoEntry(uid = 3, key = listOf("unicorn"), content = "UNICORN LORE", position = WorldInfoPosition.AFTER),
        )
        val messages = listOf(user("I saw a dragon"))
        for (tavern in listOf(false, true)) {
            val turn = preparation.prepare(request(bound(book).copy(tavernMode = tavern), messages, settings))
            val system = turn.build(messages, "").first().toText()
            assertTrue(system, "ALWAYS" in system && "DRAGON LORE" in system && "UNICORN" !in system)
            assertEquals(setOf(1, 2), turn.activations.map { it.entry.uid }.toSet())
            assertEquals("Lore", turn.activations.first().bookName)
        }
    }

    @Test fun disabledBookAndUnboundBookDoNotActivate() = runBlocking {
        val entry = WorldInfoEntry(uid = 1, content = "LORE", constant = true, position = WorldInfoPosition.AFTER)
        val book = Lorebook(name = "Lore", entries = listOf(entry), enabled = false)
        val messages = listOf(user("x"))
        for (tavern in listOf(false, true)) {
            val off = preparation.prepare(request(bound(book).copy(tavernMode = tavern), messages, Settings(lorebooks = listOf(book))))
            assertTrue(off.activations.isEmpty())
            val enabledButUnbound = book.copy(enabled = true)
            val unbound = preparation.prepare(request(assistantWith("P").copy(tavernMode = tavern), messages, Settings(lorebooks = listOf(enabledButUnbound))))
            assertTrue(unbound.activations.isEmpty())
        }
    }

    @Test fun conversationBindingsReplaceTheAssistantsWhenAllowed() = runBlocking {
        val a = Lorebook(name = "A", entries = listOf(WorldInfoEntry(uid = 1, content = "FROM-A", constant = true, position = WorldInfoPosition.AFTER)))
        val b = Lorebook(name = "B", entries = listOf(WorldInfoEntry(uid = 1, content = "FROM-B", constant = true, position = WorldInfoPosition.AFTER)))
        val libA = libraryEntry("LIB-A")
        val libB = libraryEntry("LIB-B")
        val settings = Settings(lorebooks = listOf(a, b), promptLibrary = listOf(libA, libB))
        val messages = listOf(user("x"))
        for (tavern in listOf(false, true)) {
            val assistant = assistantWith("P") {
                copy(tavernMode = tavern, lorebookIds = setOf(a.id), libraryEntryIds = setOf(Uuid.parse(libA.identifier)),
                    allowConversationPromptInjection = true)
            }
            val conversationLibrary = setOf(Uuid.parse(libB.identifier))
            val text = preparation.prepare(request(assistant, messages, settings, conversationBooks = setOf(b.id), conversationLibrary = conversationLibrary))
                .build(messages, "").first().toText()
            assertTrue(text, "FROM-B" in text && "LIB-B" in text && "FROM-A" !in text && "LIB-A" !in text)
            val notAllowed = preparation.prepare(request(assistant.copy(allowConversationPromptInjection = false), messages, settings,
                conversationBooks = setOf(b.id), conversationLibrary = conversationLibrary)).build(messages, "").first().toText()
            assertTrue(notAllowed, "FROM-A" in notAllowed && "LIB-A" in notAllowed && "FROM-B" !in notAllowed && "LIB-B" !in notAllowed)
            val cleared = preparation.prepare(request(assistant, messages, settings)).build(messages, "").first().toText()
            assertEquals("P", cleared)
        }
    }

    @Test fun atDepthEntryLandsInsideTheChatAtThatDepth() {
        val (settings, book) = settingsWithBook(
            WorldInfoEntry(uid = 1, content = "DEEP", constant = true, position = WorldInfoPosition.AT_DEPTH, depth = 1, role = 1),
        )
        for (tavern in listOf(false, true)) {
            val out = build(bound(book).copy(tavernMode = tavern), listOf(user("a"), assistant("b"), user("c")), settings = settings)
            assertEquals(listOf("system*:P", "user:a", "assistant:b", "user*:DEEP", "user:c"), texts(out))
        }
    }

    @Test fun probabilityEntriesRollOnlyOutsideDryRun() = runBlocking {
        val (settings, book) = settingsWithBook(
            WorldInfoEntry(uid = 1, content = "MAYBE", constant = true, position = WorldInfoPosition.AFTER, useProbability = true, probability = 50),
        )
        val messages = listOf(user("x"))
        val live = preparation.prepare(request(bound(book), messages, settings, random = TavernRandom { 0.1 }))
        assertEquals(1, live.activations.size)
        val dry = preparation.prepare(request(bound(book), messages, settings, dryRun = true, random = TavernRandom { 0.1 }))
        assertTrue("a dry run never rolls the dice into activation", dry.activations.isEmpty())
    }

    @Test fun stickyStateIsCarriedInTheConversationMetadata() = runBlocking {
        val (settings, book) = settingsWithBook(
            WorldInfoEntry(uid = 1, key = listOf("dragon"), content = "STICKY", position = WorldInfoPosition.AFTER, sticky = 3),
        )
        val first = listOf(user("a dragon"))
        val t1 = preparation.prepare(request(bound(book), first, settings))
        val meta = t1.newConversationState().toMetadata()
        assertTrue("sticky state written", meta.containsKey(TavernConversationState.TIMED))
        // 下一回合没有再提到关键词，粘滞仍然让它生效
        val second = first + assistant("ok") + user("tell me more")
        val t2 = preparation.prepare(request(bound(book), second, settings, metadata = meta))
        assertEquals(1, t2.activations.size)
        // 没有这份状态时不会激活
        val cold = preparation.prepare(request(bound(book), second, settings))
        assertTrue(cold.activations.isEmpty())
    }

    // ------------------------------------------------------------ 提示词库条目

    private fun libraryEntry(
        content: String, anchor: String? = null, position: Int = InjectionPosition.RELATIVE,
        depth: Int? = null, order: Int = 100, role: PromptRole = PromptRole.SYSTEM, enabled: Boolean = true,
    ) = PromptEntry(
        identifier = Uuid.random().toString(), name = content, role = role, content = content,
        systemPrompt = false, marker = false, injectionPosition = position, injectionDepth = depth, injectionOrder = order,
    ).withLibraryMeta(enabled = enabled, anchor = anchor)

    @Test fun libraryEntriesLandAtTheirAnchors() {
        val before = libraryEntry("BEFORE {{user}}", LibraryEntryMeta.BEFORE_SYSTEM)
        val after = libraryEntry("AFTER", LibraryEntryMeta.AFTER_SYSTEM)
        val top = libraryEntry("TOP", LibraryEntryMeta.BEFORE_HISTORY, role = PromptRole.USER)
        val settings = Settings(promptLibrary = listOf(before, after, top))
        val assistant = assistantWith("PROMPT") { copy(libraryEntryIds = setOf(before, after, top).map { Uuid.parse(it.identifier) }.toSet()) }
        for (tavern in listOf(false, true)) {
            val out = build(assistant.copy(tavernMode = tavern), listOf(user("q")), context = "CTX", settings = settings)
            assertEquals(listOf("system*:BEFORE Alice\nPROMPT\nCTX\nAFTER", "user*:TOP", "user:q"), texts(out))
        }
    }

    @Test fun libraryEntriesNotSelectedOrSwitchedOffAreNotSent() {
        val on = libraryEntry("ON", LibraryEntryMeta.AFTER_SYSTEM)
        val off = libraryEntry("OFF", LibraryEntryMeta.AFTER_SYSTEM, enabled = false)
        val unbound = libraryEntry("UNBOUND", LibraryEntryMeta.AFTER_SYSTEM)
        val settings = Settings(promptLibrary = listOf(on, off, unbound))
        val assistant = assistantWith("P") { copy(libraryEntryIds = setOf(on, off).map { Uuid.parse(it.identifier) }.toSet()) }
        for (tavern in listOf(false, true)) {
            assertEquals("system*:P\nON", texts(build(assistant.copy(tavernMode = tavern), listOf(user("q")), settings = settings)).first())
        }
    }

    @Test fun absoluteLibraryEntriesAreInjectedByDepthAndOrder() {
        val d1 = libraryEntry("D1", position = InjectionPosition.ABSOLUTE, depth = 1, role = PromptRole.USER)
        val d2 = libraryEntry("D2", position = InjectionPosition.ABSOLUTE, depth = 2, role = PromptRole.ASSISTANT)
        val settings = Settings(promptLibrary = listOf(d1, d2))
        val assistant = assistantWith("P") { copy(libraryEntryIds = setOf(d1, d2).map { Uuid.parse(it.identifier) }.toSet()) }
        for (tavern in listOf(false, true)) {
            val out = build(assistant.copy(tavernMode = tavern), listOf(user("a"), assistant("b"), user("c")), settings = settings)
            assertEquals(listOf("system*:P", "user:a", "assistant*:D2", "assistant:b", "user*:D1", "user:c"), texts(out))
        }
    }

    @Test fun depthInjectionsAvoidTheGapBetweenAUserAndAToolCall() {
        val d = libraryEntry("D", position = InjectionPosition.ABSOLUTE, depth = 2, role = PromptRole.USER)
        val settings = Settings(promptLibrary = listOf(d))
        val assistant = assistantWith("P") { copy(libraryEntryIds = setOf(Uuid.parse(d.identifier))) }
        // 深度 2 的位置正好是 u2 与带工具调用的助手消息之间：要挪到 u2 之前
        val out = build(assistant, listOf(user("u1"), toolAssistant(), user("u2"), toolAssistant(), user("u3")), settings = settings)
        val kinds = out.map { if (it.isSynthetic) "inj" else it.role.name.lowercase() + (if (it.getTools().isNotEmpty()) "+tool" else "") }
        assertEquals(listOf("system", "user", "assistant+tool", "inj", "user", "assistant+tool", "user").map { if (it == "system") "inj" else it }, kinds)
    }

    // ------------------------------------------------------------ 仅提示词正则

    @Test fun promptOnlyRegexChangesTheRequestButNotTheStoredMessagesAndRespectsDepth() = runBlocking {
        val script = RegexScript(
            id = "r1", scriptName = "hide", findRegex = "/secret/g", replaceString = "[x]",
            placement = listOf(RegexPlacement.USER_INPUT), promptOnly = true, minDepth = 0, maxDepth = 1,
        )
        val assistant = Assistant(
            name = "Bot",
            promptPreset = AssistantPresets.withSystemPrompt("P").let { it.withRegexScripts(listOf(script)) },
        )
        val messages = listOf(user("secret one"), assistant("ok"), user("secret two"), assistant("fine"), user("secret three"))
        val out = preparation.prepare(request(assistant, messages)).build(messages, "")
        // 深度 0（末条）和 1 才改；更早的原样
        assertEquals(
            listOf("system*:P", "user:secret one", "assistant:ok", "user:secret two", "assistant:fine", "user:[x] three"),
            texts(out),
        )
        assertEquals("secret three", messages.last().toText())
    }

    @Test fun regexScriptsAreInTheGlobalThenAssistantOrder() = runBlocking {
        val global = RegexScript(id = "g", findRegex = "/a/g", replaceString = "b", placement = listOf(RegexPlacement.USER_INPUT), promptOnly = true)
        val local = RegexScript(id = "l", findRegex = "/b/g", replaceString = "c", placement = listOf(RegexPlacement.USER_INPUT), promptOnly = true)
        val settings = Settings(tavern = TavernSettings(regexScripts = listOf(global)))
        val assistant = Assistant(name = "Bot", promptPreset = AssistantPresets.withSystemPrompt("").withRegexScripts(listOf(local)))
        val messages = listOf(user("a"))
        // 全局先跑（a→b），助手的后跑（b→c）
        assertEquals("user:c", texts(preparation.prepare(request(assistant, messages, settings)).build(messages, "")).single())
    }

    // ------------------------------------------------------------ 生成类型与稳定性

    @Test fun generationTypeIsHonouredByWorldInfoTriggers() = runBlocking {
        val entry = WorldInfoEntry(uid = 1, content = "ONLY-REGEN", constant = true, position = WorldInfoPosition.AFTER, triggers = listOf("regenerate"))
        val book = Lorebook(name = "L", entries = listOf(entry))
        val settings = Settings(lorebooks = listOf(book))
        val messages = listOf(user("x"))
        fun count(type: GenerationType) = runBlocking {
            preparation.prepare(request(bound(book), messages, settings).let {
                TurnRequest(
                    it.assistant, it.settings, it.model, it.conversationId, it.metadata, it.conversationLibraryEntryIds,
                    it.conversationLorebookIds, it.customSystemPrompt, it.messages, type, it.dryRun, it.userName, it.charName,
                )
            }).activations.size
        }
        assertEquals(0, count(GenerationType.NORMAL))
        assertEquals(1, count(GenerationType.REGENERATE))
    }

    @Test fun twoBuildsWithTheSameInputAreIdentical() = runBlocking {
        val messages = listOf(user("x"), assistant("y"), user("z"))
        val turn = preparation.prepare(request(assistantWith("P {{user}}"), messages))
        assertEquals(texts(turn.build(messages, "ctx")), texts(turn.build(messages, "ctx")))
        assertNotEquals(texts(turn.build(messages, "ctx")), texts(turn.build(messages, "other")))
    }

    // ------------------------------------------------------------ 酒馆模式关

    @Test fun withTavernModeOffSelectedExtensionsApplyWithoutEnablingTavernOnlyContent() {
        val book = Lorebook(name = "L", entries = listOf(
            WorldInfoEntry(uid = 1, content = "LORE-BEFORE", constant = true, position = WorldInfoPosition.BEFORE),
            WorldInfoEntry(uid = 2, content = "LORE-AFTER", constant = true, position = WorldInfoPosition.AFTER),
            WorldInfoEntry(uid = 3, content = "Alice: EXAMPLE-TOP", constant = true, position = WorldInfoPosition.EM_TOP),
            WorldInfoEntry(uid = 4, content = "Bot: EXAMPLE-BOTTOM", constant = true, position = WorldInfoPosition.EM_BOTTOM),
            WorldInfoEntry(uid = 5, content = "AN-TOP", constant = true, position = WorldInfoPosition.AN_TOP),
            WorldInfoEntry(uid = 6, content = "AN-BOTTOM", constant = true, position = WorldInfoPosition.AN_BOTTOM),
        ))
        val lib = libraryEntry("LIB", LibraryEntryMeta.AFTER_SYSTEM)
        val settings = Settings(lorebooks = listOf(book), promptLibrary = listOf(lib), tavern = TavernSettings(personaDescription = "PERSONA"))
        val preset = AssistantPresets.withSystemPrompt("P")
            .withEntryAdded(textEntry("extra", "EXTRA"))
            .withEntryUpdated("jailbreak") { it.copy(content = "PHI") }
            .withOptions { it.copy(squashSystemMessages = false) }
        val assistant = Assistant(
            name = "Bot", promptPreset = preset, lorebookIds = setOf(book.id), libraryEntryIds = setOf(Uuid.parse(lib.identifier)),
        )
        val metadata = buildJsonObject { put("note_prompt", "NOTE"); put("note_interval", 1); put("note_depth", 0); put("note_position", 1) }
        val messages = listOf(user("q"))
        val turn = runBlocking { preparation.prepare(request(assistant, messages, settings, metadata)) }
        // 精简布局仍用默认合并选项，世界书与库条目生效，但不自动启用作者注释等酒馆功能
        val plain = texts(runBlocking { turn.build(messages, "CTX") })
        assertEquals("system*:LORE-BEFORE\nP\nEXTRA\nCTX\nLIB\nLORE-AFTER", plain.first())
        // 示例沿用既有组装器的 system 消息与 [Example Chat] 分隔，不改变酒馆的示例格式
        assertEquals(listOf("system*:[Example Chat]", "system*:EXAMPLE-TOP", "system*:[Example Chat]", "system*:EXAMPLE-BOTTOM", "user:q"), plain.drop(1))
        assertEquals(setOf(1, 2, 3, 4, 5, 6), turn.activations.map { it.entry.uid }.toSet())
        for (part in listOf("PERSONA", "NOTE", "PHI", "AN-TOP", "AN-BOTTOM")) assertFalse(plain.toString(), part in plain.toString())
        // 同一个助手打开酒馆模式，作者注释相关条目和其余酒馆设置才回来
        val full = texts(build(assistant.copy(tavernMode = true), messages, "CTX", settings, metadata)).joinToString("|")
        for (part in listOf("LORE-BEFORE", "LORE-AFTER", "LIB", "PERSONA", "NOTE", "PHI", "AN-TOP", "AN-BOTTOM")) assertTrue(full, part in full)
    }

    @Test fun withTavernModeOffTheConversationPromptStillReplacesTheEntries() {
        val assistant = Assistant(
            name = "Bot", promptPreset = AssistantPresets.withSystemPrompt("P").withEntryAdded(textEntry("extra", "EXTRA")),
            allowConversationSystemPrompt = true,
        )
        val messages = listOf(user("q"))
        val out = runBlocking { preparation.prepare(request(assistant, messages, customSystemPrompt = "OVERRIDE")).build(messages, "CTX") }
        assertEquals(listOf("system*:OVERRIDE\nCTX", "user:q"), texts(out))
    }

    @Test fun withTavernModeOffInChatEntriesAndTheirSwitchesStillWork() {
        val inChat = PromptEntry(
            identifier = "late", name = "late", role = PromptRole.USER, content = "LATE", systemPrompt = false, marker = false,
            injectionPosition = InjectionPosition.ABSOLUTE, injectionDepth = 1,
        )
        val preset = AssistantPresets.withSystemPrompt("P").withEntryAdded(inChat).withEntryAdded(textEntry("off", "OFF")).withEntryEnabled("off", false)
        val out = build(Assistant(name = "Bot", promptPreset = preset), listOf(user("a"), assistant("b"), user("c")))
        assertEquals(listOf("system*:P", "user:a", "assistant:b", "user*:LATE", "user:c"), texts(out))
    }
}
