package me.rerere.rikkihub.data.model

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernEditingTest {
    private fun ids(p: PromptPreset) = p.layout().entries.map { it.identifier }
    private fun withTwo() = AssistantPresets.withSystemPrompt("P")
        .withEntryAdded(newPromptTextEntry("a").copy(identifier = "a"))
        .withEntryAdded(newPromptTextEntry("b").copy(identifier = "b"))

    // ---- 拖动排序

    @Test fun movedToTakesTheTargetsSlotWhenDraggingDownAndUp() {
        val p = withTwo()
        assertEquals(listOf("worldInfoBefore", "main", "a", "b", "personaDescription"), ids(p).take(5))
        assertEquals(listOf("worldInfoBefore", "a", "b", "main", "personaDescription"), ids(p.withEntryMovedTo("main", "b")).take(5))
        assertEquals(listOf("b", "worldInfoBefore", "main", "a", "personaDescription"), ids(p.withEntryMovedTo("b", "worldInfoBefore")).take(5))
    }

    @Test fun movedToIgnoresSelfAndUnknownIdentifiers() {
        val p = withTwo()
        assertEquals(ids(p), ids(p.withEntryMovedTo("a", "a")))
        assertEquals(ids(p), ids(p.withEntryMovedTo("a", "nope")))
        assertEquals(ids(p), ids(p.withEntryMovedTo("nope", "a")))
    }

    @Test fun movedToKeepsEnabledFlags() {
        val p = withTwo().withEntryEnabled("a", false).withEntryMovedTo("a", "main")
        assertFalse(p.layout().entries.first { it.identifier == "a" }.enabled)
        assertTrue(p.layout().entries.first { it.identifier == "main" }.enabled)
    }

    // ---- 落位

    @Test fun placementDefaultsToRelativeWithTavernDefaults() {
        val e = newPromptTextEntry("x")
        assertEquals(EntryPlacement.RELATIVE, e.placement)
        assertEquals(4, e.depth)
        assertEquals(100, e.order)
    }

    @Test fun inChatWritesAbsolutePositionDepthAndOrder() {
        val e = newPromptTextEntry("x").withPlacement(EntryPlacement.IN_CHAT, depth = 2, order = 50)
        assertEquals(EntryPlacement.IN_CHAT, e.placement)
        assertEquals(1, e.injectionPosition)
        assertEquals(2, e.depth)
        assertEquals(50, e.order)
        val json = e.toJson()
        assertEquals(JsonPrimitive(1), json["injection_position"])
        assertEquals(JsonPrimitive(2), json["injection_depth"])
        assertEquals(JsonPrimitive(50), json["injection_order"])
    }

    @Test fun switchingBackToRelativeKeepsDepthAndOrderForNextTime() {
        val inChat = newPromptTextEntry("x").withPlacement(EntryPlacement.IN_CHAT, depth = 7, order = 3)
        val relative = inChat.withPlacement(EntryPlacement.RELATIVE)
        assertEquals(EntryPlacement.RELATIVE, relative.placement)
        assertEquals(0, relative.injectionPosition)
        val again = relative.withPlacement(EntryPlacement.IN_CHAT)
        assertEquals(7, again.depth)
        assertEquals(3, again.order)
    }

    @Test fun negativeDepthIsClampedToZero() {
        assertEquals(0, newPromptTextEntry("x").withPlacement(EntryPlacement.IN_CHAT, depth = -3).depth)
    }

    // ---- 触发类型

    @Test fun noTriggersMeansEverything() {
        val e = newPromptTextEntry("x")
        assertTrue(e.triggers.isEmpty())
        assertEquals(emptyList<String>(), e.withTriggers(emptySet()).injectionTrigger)
    }

    @Test fun selectedTriggersAreWrittenInAFixedOrder() {
        val e = newPromptTextEntry("x").withTriggers(setOf(GenerationType.REGENERATE, GenerationType.NORMAL))
        assertEquals(listOf("normal", "regenerate"), e.injectionTrigger)
        assertEquals(setOf(GenerationType.NORMAL, GenerationType.REGENERATE), e.triggers)
    }

    @Test fun selectingEveryTypeStoresAsAll() {
        val e = newPromptTextEntry("x").withTriggers(GenerationType.entries.toSet())
        assertEquals(emptyList<String>(), e.injectionTrigger)
    }

    @Test fun unknownTriggerNamesFromOtherVersionsSurviveEdits() {
        val e = newPromptTextEntry("x").copy(injectionTrigger = listOf("normal", "future-kind"))
        val edited = e.withTriggers(setOf(GenerationType.SWIPE))
        assertEquals(listOf("swipe", "future-kind"), edited.injectionTrigger)
        assertEquals(setOf(GenerationType.SWIPE), edited.triggers)
    }

    // ---- 新建条目

    @Test fun newLibraryEntryIsARelativeCustomEntryAfterTheSystemPrompt() {
        val e = newLibraryEntry("Style")
        assertEquals("Style", e.name)
        assertEquals(false, e.systemPrompt)
        assertEquals(false, e.marker)
        assertEquals(EntryPlacement.RELATIVE, e.placement)
        assertEquals(LibraryEntryMeta.AFTER_SYSTEM, e.libraryAnchor)
        assertTrue(e.libraryEnabled)
        assertTrue(java.util.UUID.fromString(e.identifier).toString() == e.identifier)
    }

    @Test fun newTextEntriesGetDistinctIdentifiers() {
        assertTrue(newPromptTextEntry("a").identifier != newPromptTextEntry("a").identifier)
    }

    // ---- 组装选项

    @Test fun contextSizeNullMeansUnlimitedAndTheDefaultIsUnlimited() {
        val o = AssistantPresets.OPTIONS
        assertTrue(o.contextUnlimited)
        val limited = o.withContextSize(32_000)
        assertFalse(limited.contextUnlimited)
        assertEquals(32_000, limited.contextSize)
        assertTrue(limited.withContextSize(null).contextUnlimited)
        assertEquals(1, o.withContextSize(0).contextSize)
    }

    @Test fun editedOptionsSurviveARoundTripThroughJson() {
        val edited = AssistantPresets.withSystemPrompt("P").withOptions {
            it.withContextSize(16_000).copy(responseTokens = 1200, squashSystemMessages = false, newChatPrompt = "[New]", wiFormat = "<wi>{0}</wi>")
        }
        val back = PromptPreset.fromJson(edited.toJson())
        assertEquals(16_000, back.options.contextSize)
        assertEquals(1200, back.options.responseTokens)
        assertFalse(back.options.squashSystemMessages)
        assertEquals("[New]", back.options.newChatPrompt)
        assertEquals("<wi>{0}</wi>", back.options.wiFormat)
    }

    // ---- 正则脚本

    @Test fun placementFlagsAreSortedAndIdempotent() {
        val s = RegexScript(id = "1", placement = listOf(RegexPlacement.REASONING))
            .withPlacement(RegexPlacement.USER_INPUT, true)
            .withPlacement(RegexPlacement.USER_INPUT, true)
            .withPlacement(RegexPlacement.WORLD_INFO, true)
        assertEquals(listOf(1, 5, 6), s.placement)
        assertTrue(s.affects(RegexPlacement.WORLD_INFO))
        val off = s.withPlacement(RegexPlacement.REASONING, false)
        assertEquals(listOf(1, 5), off.placement)
        assertFalse(off.affects(RegexPlacement.REASONING))
    }

    @Test fun newRegexScriptWorksOnOutputAndReasoning() {
        val s = newRegexScript("s")
        assertEquals(listOf(RegexPlacement.AI_OUTPUT, RegexPlacement.REASONING), s.placement)
        assertEquals("", s.findRegex)
        assertFalse(s.disabled)
    }

    @Test fun bareFindStringsBecomeGlobalLiteralsAndLiteralsStay() {
        assertEquals("", normalizeFindRegex("   "))
        assertEquals("/a+/g", normalizeFindRegex("a+"))
        assertEquals("/a+/gi", normalizeFindRegex(" /a+/gi "))
        assertEquals("/a/b/g", normalizeFindRegex("a/b"))
        assertEquals("/^/foo/g", normalizeFindRegex("^/foo"))
    }

    @Test fun literalSplitReturnsPatternAndFlags() {
        assertEquals("a+" to "gi", splitRegexLiteral("/a+/gi"))
        assertEquals("a/b" to "", splitRegexLiteral("/a/b/"))
        assertNull(splitRegexLiteral("a+"))
        assertNull(splitRegexLiteral("/"))
        assertNull(splitRegexLiteral("/a/zz"))
    }
}
