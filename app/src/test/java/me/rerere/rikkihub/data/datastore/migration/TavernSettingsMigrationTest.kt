package me.rerere.rikkihub.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.withEntryUpdated
import me.rerere.rikkihub.legacy2320.LegacyAssistant as Assistant
import me.rerere.rikkihub.legacy2320.LegacyAffectScope as AssistantAffectScope
import me.rerere.rikkihub.legacy2320.LegacyAssistantRegex as AssistantRegex
import me.rerere.rikkihub.legacy2320.LegacyInjectionPosition as InjectionPosition
import me.rerere.rikkihub.legacy2320.LegacyLorebook as Lorebook
import me.rerere.rikkihub.legacy2320.LegacyPromptInjection as PromptInjection
import me.rerere.rikkihub.legacy2320.LegacySystemPromptMode as SystemPromptMode
import me.rerere.rikkihub.legacy2320.LegacySystemPromptSection as SystemPromptSection
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoPosition
import me.rerere.tavern.worldinfo.WorldInfoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 设置结构迁移 V5（旧助手提示词/模式注入/世界书/助手正则 → 酒馆格式）。
 * 输入都用**真实的旧模型对象**经 `JsonInstant` 编码得到，保证迁移读的是真实落盘的形状（键名、枚举写法）。
 */
class TavernSettingsMigrationTest {
    private fun assistantJson(assistant: Assistant): JsonObject =
        JsonInstant.encodeToJsonElement(Assistant.serializer(), assistant).jsonObject

    private fun migrated(assistant: Assistant): JsonObject = TavernSettingsMigration.migrateAssistant(assistantJson(assistant))

    private fun presetOf(assistant: JsonObject): PromptPreset = PromptPreset.fromJson(assistant.getValue("promptPreset").jsonObject)

    // ---------------------------------------------------------------- 助手

    @Test fun wholePromptBecomesTheMainEntryInTheDefaultLayout() {
        val out = migrated(Assistant(name = "A", systemPrompt = "You are helpful."))
        val preset = presetOf(out)

        val layout = preset.layout()
        assertEquals(
            listOf("worldInfoBefore", "main", "personaDescription", "charDescription", "charPersonality", "scenario", "rikkihubContext", "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak"),
            layout.entries.map { it.identifier },
        )
        assertTrue(layout.entries.all { it.enabled })
        val main = layout.entries.first { it.identifier == "main" }
        assertEquals("You are helpful.", main.content)
        assertEquals(PromptRole.SYSTEM, main.role)
        assertEquals(false, main.marker)
        assertTrue(layout.entries.first { it.identifier == "chatHistory" }.marker == true)
    }

    @Test fun legacyKeysAreRemovedAndOtherFieldsKept() {
        val assistant = Assistant(
            name = "Keep me", systemPrompt = "p", temperature = 0.7f, enableMemory = true,
            modeInjectionIds = setOf(Uuid.parse("00000000-0000-0000-0000-000000000001")),
            lorebookIds = setOf(Uuid.parse("00000000-0000-0000-0000-000000000002")),
        )
        val out = migrated(assistant)
        for (gone in listOf("systemPrompt", "systemPromptMode", "promptSections", "regexes", "modeInjectionIds")) {
            assertFalse("$gone should be removed", gone in out)
        }
        assertEquals("Keep me", out.getValue("name").jsonPrimitive.content)
        assertTrue(out.getValue("enableMemory").jsonPrimitive.boolean)
        assertEquals(listOf("00000000-0000-0000-0000-000000000001"), out.getValue("libraryEntryIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("00000000-0000-0000-0000-000000000002"), out.getValue("lorebookIds").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun migratedPresetUsesTheLegacyEquivalentOptions() {
        val options = presetOf(migrated(Assistant(systemPrompt = "p"))).options
        assertTrue("旧版只发一条系统消息", options.squashSystemMessages)
        assertEquals("旧版没有「新聊天」提示", "", options.newChatPrompt)
        assertEquals("上下文只受模型能装下的限制", 1_000_000, options.contextSize)
    }

    @Test fun sectionsBecomeOneEntryEachWithTheirOrderAndSwitches() {
        val a = SystemPromptSection(Uuid.parse("00000000-0000-0000-0000-0000000000a1"), "Persona", "You are Seraphina.")
        val b = SystemPromptSection(Uuid.parse("00000000-0000-0000-0000-0000000000a2"), "Off", "kept but off", enabled = false)
        val c = SystemPromptSection(Uuid.parse("00000000-0000-0000-0000-0000000000a3"), "Style", "Short answers.")
        val out = migrated(Assistant(
            systemPrompt = "You are Seraphina.\n\nShort answers.", systemPromptMode = SystemPromptMode.SECTIONS,
            promptSections = listOf(a, b, c),
        ))
        val layout = presetOf(out).layout()
        assertEquals(
            listOf("worldInfoBefore", a.id.toString(), b.id.toString(), c.id.toString(), "personaDescription", "charDescription", "charPersonality", "scenario", "rikkihubContext", "worldInfoAfter",
                "dialogueExamples", "chatHistory", "jailbreak"),
            layout.entries.map { it.identifier },
        )
        assertEquals(listOf("Persona", "Off", "Style"), layout.entries.filter { it.identifier in setOf(a.id.toString(), b.id.toString(), c.id.toString()) }.map { it.name })
        assertEquals(false, layout.entries.first { it.identifier == b.id.toString() }.enabled)
        assertTrue(layout.entries.none { it.identifier == "main" })
        assertEquals("Short answers.", layout.entries.first { it.identifier == c.id.toString() }.content)
    }

    @Test fun anAssistantWithoutAnyPromptStillGetsAnEmptyMain() {
        val layout = presetOf(migrated(Assistant(systemPrompt = ""))).layout()
        assertEquals("", layout.entries.first { it.identifier == "main" }.content)
    }

    @Test fun migrationIsIdempotent() {
        val once = migrated(Assistant(systemPrompt = "p", regexes = listOf(AssistantRegex(Uuid.random(), findRegex = "a", replaceString = "b", affectingScope = setOf(AssistantAffectScope.USER)))))
        assertEquals(once, TavernSettingsMigration.migrateAssistant(once))
    }

    @Test fun nonObjectAssistantEntriesPassThrough() {
        val array = JsonArray(listOf(JsonPrimitive("not an assistant")))
        assertEquals(array, TavernSettingsMigration.migrateAssistants(array))
    }

    // ---------------------------------------------------------------- 助手正则

    private fun scriptsOf(assistant: JsonObject): List<RegexScript> =
        presetOf(assistant).extra["extensions"]!!.jsonObject["regex_scripts"]!!.jsonArray.map { RegexScript.fromJson(it.jsonObject) }

    @Test fun assistantRegexesBecomePresetScopedScripts() {
        val user = AssistantRegex(Uuid.parse("00000000-0000-0000-0000-0000000000b1"), "user rule", true, "colou?r", "color", setOf(AssistantAffectScope.USER))
        val ai = AssistantRegex(Uuid.parse("00000000-0000-0000-0000-0000000000b2"), "ai rule", true, "(?<word>\\w+)@x", "\${word} at x", setOf(AssistantAffectScope.ASSISTANT), visualOnly = true)
        val off = AssistantRegex(Uuid.parse("00000000-0000-0000-0000-0000000000b3"), "off", false, "z", "y", setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT))
        val scripts = scriptsOf(migrated(Assistant(systemPrompt = "p", regexes = listOf(user, ai, off))))

        assertEquals(listOf("user rule", "ai rule", "off"), scripts.map { it.scriptName })
        assertEquals(listOf(listOf(RegexPlacement.USER_INPUT), listOf(RegexPlacement.AI_OUTPUT, RegexPlacement.REASONING),
            listOf(RegexPlacement.USER_INPUT, RegexPlacement.AI_OUTPUT, RegexPlacement.REASONING)), scripts.map { it.placement })
        assertEquals("/colou?r/g", scripts[0].findRegex)
        assertEquals(listOf(false, true, false), scripts.map { it.markdownOnly })
        assertEquals(listOf(false, false, true), scripts.map { it.disabled })
        assertEquals("旧版编辑用户消息时会重跑用户正则", listOf(true, false, true), scripts.map { it.runOnEdit })
        assertEquals("名字组的引用换成 JS 写法", "\$<word> at x", scripts[1].replaceString)
        assertEquals("00000000-0000-0000-0000-0000000000b1", scripts[0].id)
    }

    @Test fun customEntriesAreNotMarkedAsBuiltInSystemPrompts() {
        // 酒馆里 system_prompt=true 是内置条目（main、jailbreak……），组装器只认它们的标识符；自定义条目必须是 false
        val sections = listOf(SystemPromptSection(Uuid.random(), "A", "one"), SystemPromptSection(Uuid.random(), "B", "two"))
        val layout = presetOf(migrated(Assistant(promptSections = sections, systemPromptMode = SystemPromptMode.SECTIONS))).layout()
        val custom = layout.entries.filter { it.identifier in sections.map { s -> s.id.toString() } }
        assertEquals(2, custom.size)
        assertTrue(custom.all { it.systemPrompt == false && it.marker == false })
        val main = presetOf(migrated(Assistant(systemPrompt = "p"))).layout().entries.first { it.identifier == "main" }
        assertEquals(true, main.systemPrompt)
    }

    @Test fun savedSinglePlaceholdersBecomeDoubleInEveryPromptText() {
        val section = SystemPromptSection(Uuid.random(), "A", "Hi {user}, I am {CHAR}. {unknown} {{user}}")
        val out = presetOf(migrated(Assistant(promptSections = listOf(section), systemPromptMode = SystemPromptMode.SECTIONS)))
        assertEquals("Hi {{user}}, I am {{char}}. {unknown} {{user}}", out.layout().entries.first { it.identifier == section.id.toString() }.content)
        val whole = presetOf(migrated(Assistant(systemPrompt = "Today {cur_date} on {model_id}")))
        assertEquals("Today {{cur_date}} on {{model_id}}", whole.layout().entries.first { it.identifier == "main" }.content)
        val library = TavernSettingsMigration.modeInjectionToLibraryEntry(
            JsonInstant.encodeToJsonElement(PromptInjection.serializer(), PromptInjection.ModeInjection(content = "for {user}")).jsonObject
        )
        assertEquals("for {{user}}", library.content)
        val entry = TavernSettingsMigration.regexInjectionToEntry(
            JsonInstant.encodeToJsonElement(PromptInjection.serializer(), PromptInjection.RegexInjection(content = "{char} lore", constantActive = true)).jsonObject, uid = 1,
        )
        assertEquals("{{char}} lore", entry.content)
    }

    @Test fun noRegexesMeansNoExtensionsBlock() {
        assertNull(presetOf(migrated(Assistant(systemPrompt = "p"))).extra["extensions"])
    }

    // ---------------------------------------------------------------- 提示词库（原模式注入）

    private fun libraryEntry(injection: PromptInjection.ModeInjection) = TavernSettingsMigration.modeInjectionToLibraryEntry(
        JsonInstant.encodeToJsonElement(PromptInjection.serializer(), injection).jsonObject
    )

    private fun rikkihub(entry: me.rerere.tavern.prompt.PromptEntry) = entry.extra[TavernSettingsMigration.EXTRA_KEY]!!.jsonObject

    @Test fun modeInjectionsMapToLibraryEntriesByPosition() {
        val id = Uuid.parse("00000000-0000-0000-0000-0000000000c1")
        fun mode(position: InjectionPosition, role: MessageRole = MessageRole.USER, depth: Int = 4, priority: Int = 0, enabled: Boolean = true) =
            PromptInjection.ModeInjection(id = id, name = "m", enabled = enabled, priority = priority, position = position, content = "text", injectDepth = depth, role = role)

        val before = libraryEntry(mode(InjectionPosition.BEFORE_SYSTEM_PROMPT, role = MessageRole.ASSISTANT))
        assertEquals(PromptRole.SYSTEM, before.role)
        assertEquals(0, before.injectionPosition)
        assertEquals(TavernSettingsMigration.Anchor.BEFORE_SYSTEM, rikkihub(before)["anchor"]!!.jsonPrimitive.content)

        val after = libraryEntry(mode(InjectionPosition.AFTER_SYSTEM_PROMPT))
        assertEquals(PromptRole.SYSTEM, after.role)
        assertEquals(TavernSettingsMigration.Anchor.AFTER_SYSTEM, rikkihub(after)["anchor"]!!.jsonPrimitive.content)

        val top = libraryEntry(mode(InjectionPosition.TOP_OF_CHAT, role = MessageRole.ASSISTANT))
        assertEquals("对话开头保留自己的角色", PromptRole.ASSISTANT, top.role)
        assertEquals(TavernSettingsMigration.Anchor.BEFORE_HISTORY, rikkihub(top)["anchor"]!!.jsonPrimitive.content)

        val bottom = libraryEntry(mode(InjectionPosition.BOTTOM_OF_CHAT))
        assertEquals(1, bottom.injectionPosition)
        assertEquals("对话末尾 = 深度 1", 1, bottom.injectionDepth)
        assertEquals(PromptRole.USER, bottom.role)
        assertFalse("anchor" in rikkihub(bottom))

        val depth = libraryEntry(mode(InjectionPosition.AT_DEPTH, role = MessageRole.ASSISTANT, depth = 7, priority = 5))
        assertEquals(1, depth.injectionPosition)
        assertEquals(7, depth.injectionDepth)
        assertEquals(5, depth.injectionOrder)
        assertEquals(PromptRole.ASSISTANT, depth.role)

        val disabled = libraryEntry(mode(InjectionPosition.AFTER_SYSTEM_PROMPT, enabled = false))
        assertFalse(rikkihub(disabled)["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("id 当作 identifier，助手/会话里已有的引用不用改", id.toString(), disabled.identifier)
    }

    @Test fun libraryArraySurvivesAPresetEntryRoundTrip() {
        val injections = listOf(
            PromptInjection.ModeInjection(id = Uuid.random(), name = "a", content = "x"),
            PromptInjection.ModeInjection(id = Uuid.random(), name = "b", content = "y", position = InjectionPosition.AT_DEPTH, injectDepth = 3),
        )
        val array = JsonInstant.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PromptInjection.serializer()), injections).jsonArray
        val migrated = TavernSettingsMigration.migrateModeInjections(array)
        assertEquals(2, migrated.size)
        migrated.forEach { assertEquals(it, me.rerere.tavern.prompt.PromptEntry.fromJson(it.jsonObject).toJson()) }
    }

    // ---------------------------------------------------------------- 世界书

    private fun regexInjection(
        position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT, keywords: List<String> = listOf("dragon"),
        useRegex: Boolean = false, caseSensitive: Boolean = false, scanDepth: Int = 4, constant: Boolean = false,
        priority: Int = 0, role: MessageRole = MessageRole.USER, depth: Int = 4, enabled: Boolean = true,
    ) = PromptInjection.RegexInjection(
        id = Uuid.random(), name = "entry", enabled = enabled, priority = priority, position = position, content = "lore",
        injectDepth = depth, role = role, keywords = keywords, useRegex = useRegex, caseSensitive = caseSensitive,
        scanDepth = scanDepth, constantActive = constant,
    )

    private fun entryOf(injection: PromptInjection.RegexInjection) = TavernSettingsMigration.regexInjectionToEntry(
        JsonInstant.encodeToJsonElement(PromptInjection.serializer(), injection).jsonObject, uid = 3,
    )

    @Test fun lorebookEntriesMapFieldByField() {
        val e = entryOf(regexInjection(keywords = listOf("dragon", "wyrm"), caseSensitive = true, scanDepth = 6, priority = 40))
        assertEquals(3, e.uid)
        assertEquals(listOf("dragon", "wyrm"), e.key)
        assertEquals("entry", e.comment)
        assertEquals("lore", e.content)
        assertEquals(40, e.order)
        assertEquals(6, e.scanDepth)
        assertEquals(true, e.caseSensitive)
        assertFalse(e.constant)
        assertFalse(e.disable)

        assertTrue(entryOf(regexInjection(constant = true)).constant)
        assertTrue(entryOf(regexInjection(enabled = false)).disable)
    }

    @Test fun regexKeywordsUseTheSillyTavernSlashSyntax() {
        assertEquals(listOf("/Bob-\\d+/i"), entryOf(regexInjection(keywords = listOf("Bob-\\d+"), useRegex = true)).key)
        assertEquals(listOf("/Bob-\\d+/"), entryOf(regexInjection(keywords = listOf("Bob-\\d+"), useRegex = true, caseSensitive = true)).key)
        assertEquals("空关键词丢掉", listOf("a"), entryOf(regexInjection(keywords = listOf("", "a"))).key)
    }

    @Test fun lorebookPositionsFollowTheMigrationTable() {
        assertEquals(WorldInfoPosition.BEFORE, entryOf(regexInjection(InjectionPosition.BEFORE_SYSTEM_PROMPT)).position)
        assertEquals(WorldInfoPosition.AFTER, entryOf(regexInjection(InjectionPosition.AFTER_SYSTEM_PROMPT)).position)
        assertEquals(WorldInfoPosition.AFTER, entryOf(regexInjection(InjectionPosition.TOP_OF_CHAT)).position)

        val bottom = entryOf(regexInjection(InjectionPosition.BOTTOM_OF_CHAT, role = MessageRole.ASSISTANT))
        assertEquals(WorldInfoPosition.AT_DEPTH, bottom.position)
        assertEquals(1, bottom.depth)
        assertEquals("酒馆角色：0 系统 / 1 用户 / 2 助手", 2, bottom.role)

        val depth = entryOf(regexInjection(InjectionPosition.AT_DEPTH, depth = 5, role = MessageRole.USER))
        assertEquals(WorldInfoPosition.AT_DEPTH, depth.position)
        assertEquals(5, depth.depth)
        assertEquals(1, depth.role)
    }

    @Test fun migratedLorebooksDecodeWithTheTavernCodecAndKeepBookFields() {
        val book = Lorebook(id = Uuid.parse("00000000-0000-0000-0000-0000000000d1"), name = "Eldoria", description = "d", enabled = false,
            entries = listOf(regexInjection(), regexInjection(InjectionPosition.AT_DEPTH, keywords = listOf("x"))))
        val array = JsonInstant.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Lorebook.serializer()), listOf(book)).jsonArray
        val out = TavernSettingsMigration.migrateLorebooks(array).single().jsonObject

        assertEquals("Eldoria", out.getValue("name").jsonPrimitive.content)
        assertEquals("00000000-0000-0000-0000-0000000000d1", out.getValue("id").jsonPrimitive.content)
        assertFalse(out.getValue("enabled").jsonPrimitive.boolean)
        val entries = out.getValue("entries").jsonArray.map { WorldInfoCodec.decodeEntry(it.jsonObject) }
        assertEquals(listOf(0, 1), entries.map { it.uid })
        assertEquals(listOf(WorldInfoPosition.AFTER, WorldInfoPosition.AT_DEPTH), entries.map { it.position })
        // 酒馆条目的 JSON 往返无损
        out.getValue("entries").jsonArray.forEach { assertEquals(it, WorldInfoCodec.encodeEntry(WorldInfoCodec.decodeEntry(it.jsonObject))) }
        // 再迁一次不变
        assertEquals(out, TavernSettingsMigration.migrateLorebook(out))
    }

    // ---------------------------------------------------------------- 全局设置

    @Test fun tavernSettingsDefaultsDecodeAndDependOnLegacyLorebooks() {
        val fresh = TavernSettingsMigration.defaultTavernSettings(hadLegacyLorebooks = false)
        val legacy = TavernSettingsMigration.defaultTavernSettings(hadLegacyLorebooks = true)
        fun wi(o: JsonObject) = JsonInstant.decodeFromJsonElement(WorldInfoSettings.serializer(), o.getValue("worldInfo"))
        assertTrue("新用户用上游默认", wi(fresh).includeNames)
        assertFalse("有旧世界书的用户：旧版只匹配消息正文，不带名字", wi(legacy).includeNames)
        assertEquals(WorldInfoSettings(), wi(fresh))
        assertEquals(0, fresh.getValue("globalVariables").jsonObject.size)
        assertEquals(0, fresh.getValue("regexScripts").jsonArray.size)
    }

    @Test
    fun anAssistantAlreadyInTheNativeShapeGetsTheCardMarkersOnceAndTheRestIsUntouched() {
        val old = AssistantPresets.withSystemPrompt("You are helpful.")
        val without = old.copy(
            prompts = old.prompts.filter { it.identifier !in CARD_IDS },
            promptOrder = old.promptOrder.map { l -> l.copy(order = l.order.filter { it.identifier !in CARD_IDS }) },
        )
        val assistant = buildJsonObject {
            put("id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
            put("name", "Mine")
            put("promptPreset", without.toJson())
        }
        val migrated = TavernSettingsMigration.addCardMarkers(JsonArray(listOf(assistant)))
        val preset = PromptPreset.fromJson((migrated.single() as JsonObject)["promptPreset"] as JsonObject)
        assertEquals(
            listOf("worldInfoBefore", "main", "personaDescription", "charDescription", "charPersonality", "scenario",
                "rikkihubContext", "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak"),
            preset.layout().entries.map { it.identifier },
        )
        assertEquals("Mine", ((migrated.single() as JsonObject)["name"] as JsonPrimitive).content)
        // idempotent
        assertEquals(migrated, TavernSettingsMigration.addCardMarkers(migrated))
    }

    @Test
    fun anAssistantWithoutAPresetOrWithACustomOrderIsLeftAlone() {
        val bare = buildJsonObject { put("id", "x"); put("name", "legacy") }
        assertEquals(JsonArray(listOf(bare)), TavernSettingsMigration.addCardMarkers(JsonArray(listOf(bare))))
        // a preset whose global order was cleared uses the tavern default order, which already has the markers
        val empty = AssistantPresets.withSystemPrompt("x").let { p -> p.copy(promptOrder = p.promptOrder.map { it.copy(order = emptyList()) }) }
        val a = buildJsonObject { put("id", "y"); put("promptPreset", empty.toJson()) }
        val out = TavernSettingsMigration.addCardMarkers(JsonArray(listOf(a))).single() as JsonObject
        val preset = PromptPreset.fromJson(out["promptPreset"] as JsonObject)
        assertTrue(preset.promptOrder.all { it.order.isEmpty() })
    }

    // ------------------------------------------------------------ V7：酒馆模式开关

    private fun assistantJson(id: Uuid, preset: PromptPreset = AssistantPresets.withSystemPrompt("P"), block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("id", id.toString())
            put("name", "a")
            put("promptPreset", preset.toJson())
            block()
        }

    private fun tavernModes(out: JsonArray) = out.map { ((it as JsonObject)["tavernMode"] as JsonPrimitive).content.toBoolean() }

    @Test
    fun v7TurnsTavernModeOnOnlyForAssistantsThatUseTavernFeatures() {
        val plain = Uuid.random()
        val customized = Uuid.random()
        val withBooks = Uuid.random()
        val withCharacters = Uuid.random()
        val withLibrary = Uuid.random()
        val input = JsonArray(listOf(
            assistantJson(plain),
            assistantJson(customized, AssistantPresets.withSystemPrompt("P").withEntryUpdated("jailbreak") { it.copy(content = "PHI") }),
            assistantJson(withBooks) { put("lorebookIds", JsonArray(listOf(JsonPrimitive(Uuid.random().toString())))) },
            assistantJson(withCharacters),
            assistantJson(withLibrary) { put("libraryEntryIds", JsonArray(listOf(JsonPrimitive(Uuid.random().toString())))) },
        ))
        val out = TavernSettingsMigration.addTavernMode(input, tavern = null, tavernAssistantIds = setOf(withCharacters.toString()))
        assertEquals(listOf(false, true, true, true, true), tavernModes(out))
        // 已经有这个键的不动（幂等，也不盖掉用户后来的选择）
        assertEquals(out, TavernSettingsMigration.addTavernMode(out, tavern = null, tavernAssistantIds = emptySet()))
        val chosen = JsonArray(listOf(JsonObject((out[1] as JsonObject) + ("tavernMode" to JsonPrimitive(false)))))
        assertEquals(chosen, TavernSettingsMigration.addTavernMode(chosen, null, setOf(customized.toString())))
    }

    @Test
    fun v7LooksAtTheGlobalPersonaAndAuthorsNote() {
        val input = JsonArray(listOf(assistantJson(Uuid.random())))
        val persona = buildJsonObject { put("personaDescription", "I am Alice") }
        val note = buildJsonObject { put("authorsNote", buildJsonObject { put("prompt", "Be brief.") }) }
        assertEquals(listOf(false), tavernModes(TavernSettingsMigration.addTavernMode(input, buildJsonObject { put("personaDescription", "") }, emptySet())))
        assertEquals(listOf(true), tavernModes(TavernSettingsMigration.addTavernMode(input, persona, emptySet())))
        assertEquals(listOf(true), tavernModes(TavernSettingsMigration.addTavernMode(input, note, emptySet())))
    }

    @Test
    fun v7KeepsTheFullAssemblyForAnAssistantItCannotRead() {
        val broken = buildJsonObject { put("id", "not-a-uuid"); put("name", "x") }
        assertEquals(listOf(true), tavernModes(TavernSettingsMigration.addTavernMode(JsonArray(listOf(broken)), null, emptySet())))
    }

    @Test
    fun aRestoredBackupGetsTheSwitchFromTheSameRules() {
        val plain = Uuid.random()
        val withCharacters = Uuid.random()
        val settings = buildJsonObject {
            put("assistants", JsonArray(listOf(assistantJson(plain), assistantJson(withCharacters))))
        }
        val migrated = me.rerere.rikkihub.utils.JsonInstant.parseToJsonElement(
            SettingsJsonMigrator.migrate(settings.toString(), tavernAssistantIds = setOf(withCharacters.toString())),
        ) as JsonObject
        assertEquals(listOf(false, true), tavernModes(migrated["assistants"] as JsonArray))
    }

    private companion object {
        val CARD_IDS = setOf("personaDescription", "charDescription", "charPersonality", "scenario")
    }
}
