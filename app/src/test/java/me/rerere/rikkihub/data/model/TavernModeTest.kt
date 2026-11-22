package me.rerere.rikkihub.data.model

import kotlinx.serialization.json.JsonObject
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.InjectionPosition
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptMarkers
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 酒馆模式开关：关时的精简布局（plainLayout）和升级时的自动判断（hasTavernCustomization / inferTavernMode）。 */
class TavernModeTest {
    private fun preset(main: String = "You are helpful.") = AssistantPresets.withSystemPrompt(main)
    private fun ids(p: PromptPreset) = p.layout().entries.map { it.identifier }
    private fun text(id: String, content: String = "x", inChat: Boolean = false) = PromptEntry(
        identifier = id, name = id, role = PromptRole.USER, content = content, systemPrompt = false, marker = false,
        injectionPosition = if (inChat) InjectionPosition.ABSOLUTE else null, injectionDepth = if (inChat) 2 else null,
    )

    @Test fun newAssistantsStartWithTavernModeOff() {
        assertFalse(Assistant().tavernMode)
    }

    @Test fun plainLayoutKeepsUserEntriesAndFixedWorldInfoContextAndHistoryMarkers() {
        val p = preset()
            .withEntryAdded(text("style"))
            .withEntryAdded(text("late", inChat = true), beforeIdentifier = "jailbreak")
            .withEntryEnabled("style", false)
            .withEntryUpdated("jailbreak") { it.copy(content = "PHI") }
        val plain = p.plainLayout()
        assertEquals(listOf(PromptMarkers.WORLD_INFO_BEFORE, "main", "style", "late", PromptMarkers.RIKKIHUB_CONTEXT,
            PromptMarkers.WORLD_INFO_AFTER, PromptMarkers.DIALOGUE_EXAMPLES, PromptMarkers.CHAT_HISTORY), ids(plain))
        // 文本条目开关照旧，固定标记总是开
        assertEquals(listOf(true, true, false, true, true, true, true, true), plain.layout().entries.map { it.enabled })
        // 条目对象原样（内容、位置），选项回到默认
        assertEquals("You are helpful.", plain.prompts.first { it.identifier == "main" }.content)
        assertEquals(InjectionPosition.ABSOLUTE, plain.prompts.first { it.identifier == "late" }.injectionPosition)
        assertEquals(AssistantPresets.OPTIONS, plain.copy(options = plain.options).options)
        assertFalse(plain.prompts.any { it.identifier == "jailbreak" })
    }

    @Test fun plainLayoutUsesDefaultOptionsButKeepsRegexScripts() {
        val script = RegexScript(id = "r1", scriptName = "r", findRegex = "/a/g", replaceString = "b")
        val p = preset().copy(options = AssistantPresets.OPTIONS.copy(contextSize = 8000, squashSystemMessages = false))
            .withRegexScripts(listOf(script))
        val plain = p.plainLayout()
        assertEquals(AssistantPresets.OPTIONS, plain.options)
        assertEquals(listOf("r1"), plain.regexScripts.map { it.id })
    }

    @Test fun plainLayoutFollowsTheUsersOrderEvenWhenEntriesSitBetweenMarkers() {
        // 酒馆模式下把一个条目挪到了聊天历史之后：精简模式里它仍然在系统块里（排在记忆说明之前）
        val p = preset().withEntryAdded(text("after")).let { it.withEntryMovedTo("after", "jailbreak") }
        assertEquals("after", ids(p).last())
        assertEquals(listOf(PromptMarkers.WORLD_INFO_BEFORE, "main", "after", PromptMarkers.RIKKIHUB_CONTEXT,
            PromptMarkers.WORLD_INFO_AFTER, PromptMarkers.DIALOGUE_EXAMPLES, PromptMarkers.CHAT_HISTORY), ids(p.plainLayout()))
    }

    @Test fun theDefaultLayoutHasNoTavernCustomization() {
        assertFalse(preset().hasTavernCustomization())
        assertFalse(preset().withEntryAdded(text("style")).withEntryAdded(text("late", inChat = true)).hasTavernCustomization())
        // 卡片标记、世界书标记、示例对话关掉或挪动，在没有角色、没有世界书时不改变请求
        assertFalse(preset().withEntryEnabled(PromptMarkers.CHAR_DESCRIPTION, false).withEntryEnabled(PromptMarkers.WORLD_INFO_AFTER, false).hasTavernCustomization())
        // 历史之后的指令开着但是空的、或关着但有内容
        assertFalse(preset().withEntryUpdated("jailbreak") { it.copy(content = "  ") }.hasTavernCustomization())
        assertFalse(preset().withEntryUpdated("jailbreak") { it.copy(content = "PHI") }.withEntryEnabled("jailbreak", false).hasTavernCustomization())
    }

    @Test fun tavernCustomizationsAreDetected() {
        assertTrue("改过选项", preset().copy(options = AssistantPresets.OPTIONS.copy(squashSystemMessages = false)).hasTavernCustomization())
        assertTrue("历史之后的指令有内容", preset().withEntryUpdated("jailbreak") { it.copy(content = "PHI") }.hasTavernCustomization())
        assertTrue("导入预设的辅助条目", preset().withEntryAdded(PromptEntry(identifier = "nsfw", name = "Auxiliary Prompt", content = "", systemPrompt = true, marker = false)).hasTavernCustomization())
        assertTrue("聊天历史关了", preset().withEntryEnabled(PromptMarkers.CHAT_HISTORY, false).hasTavernCustomization())
        assertTrue("记忆说明关了", preset().withEntryEnabled(PromptMarkers.RIKKIHUB_CONTEXT, false).hasTavernCustomization())
        assertTrue("文本条目挪到了聊天历史之后", preset().withEntryAdded(text("after")).withEntryMovedTo("after", "jailbreak").hasTavernCustomization())
        assertTrue("文本条目挪到了记忆说明之后", preset().withEntryAdded(text("mid")).withEntryMovedTo("mid", PromptMarkers.WORLD_INFO_AFTER).hasTavernCustomization())
        assertFalse("聊天内条目放在哪都一样", preset().withEntryAdded(text("late", inChat = true)).withEntryMovedTo("late", "jailbreak").hasTavernCustomization())
        assertTrue("有用户设定且标记开着", preset().hasTavernCustomization(personaDescription = "I am Alice"))
        assertFalse("用户设定标记关着", preset().withEntryEnabled(PromptMarkers.PERSONA_DESCRIPTION, false).hasTavernCustomization(personaDescription = "I am Alice"))
    }

    @Test fun inferTavernModeAlsoLooksAtLorebooksLibraryNotesAndConversations() {
        val plain = Assistant(promptPreset = preset())
        assertFalse(plain.inferTavernMode("", "", usedByTavernConversations = false))
        assertTrue(plain.copy(lorebookIds = setOf(Uuid.random())).inferTavernMode("", "", false))
        assertTrue(plain.copy(libraryEntryIds = setOf(Uuid.random())).inferTavernMode("", "", false))
        assertTrue(plain.copy(allowConversationPromptInjection = true).inferTavernMode("", "", false))
        assertTrue(plain.inferTavernMode("", "Be concise.", false))
        assertTrue(plain.inferTavernMode("", "", usedByTavernConversations = true))
        assertTrue(plain.inferTavernMode("I am Alice", "", false))
    }

    @Test fun serializedAssistantsWithoutTheKeyDecodeAsOff() {
        val json = me.rerere.rikkihub.utils.JsonInstant
        val encoded = json.encodeToJsonElement(Assistant.serializer(), Assistant(name = "a", tavernMode = true)) as JsonObject
        assertTrue(json.decodeFromJsonElement(Assistant.serializer(), encoded).tavernMode)
        assertFalse(json.decodeFromJsonElement(Assistant.serializer(), JsonObject(encoded - "tavernMode")).tavernMode)
    }
}
