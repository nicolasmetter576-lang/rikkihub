package me.rerere.rikkihub.data.model

import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.PromptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPresetEditingTest {
    private fun preset() = AssistantPresets.withSystemPrompt("You are helpful.")
    private fun ids(p: me.rerere.tavern.prompt.PromptPreset) = p.layout().entries.map { it.identifier }
    private fun text(id: String, content: String = "x") =
        PromptEntry(identifier = id, name = id, role = PromptRole.SYSTEM, content = content, systemPrompt = true, marker = false)

    @Test fun defaultLayoutHasTheExpectedShape() {
        assertEquals(
            listOf("worldInfoBefore", "main", "personaDescription", "charDescription", "charPersonality", "scenario", "rikkihubContext", "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak"),
            ids(preset()),
        )
    }

    @Test fun addingATextEntryPutsItAtTheEndOfThePromptBlock() {
        val p = preset().withEntryAdded(text("style", "Be brief."))
        assertEquals(listOf("worldInfoBefore", "main", "style", "personaDescription"), ids(p).take(4))
        assertEquals("Be brief.", p.textEntries().last().content)
    }

    @Test fun addingBeforeAGivenEntry() {
        val p = preset().withEntryAdded(text("first"), beforeIdentifier = "main")
        assertEquals(listOf("worldInfoBefore", "first", "main"), ids(p).take(3))
    }

    @Test fun duplicateIdentifiersAreRejected() {
        var failed = false
        try { preset().withEntryAdded(text("main")) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun enablingAndDisablingWritesTheOrderNotTheEntry() {
        val off = preset().withEntryEnabled("main", false)
        assertFalse(off.layout().entries.first { it.identifier == "main" }.enabled)
        val on = off.withEntryEnabled("main", true)
        assertTrue(on.layout().entries.first { it.identifier == "main" }.enabled)
    }

    @Test fun movingShiftsInTheGlobalOrderAndStopsAtTheEnds() {
        val p = preset().withEntryAdded(text("a")).withEntryAdded(text("b"))
        val moved = p.withEntryMoved("b", -1)
        assertEquals(listOf("worldInfoBefore", "main", "b", "a", "personaDescription"), ids(moved).take(5))
        assertEquals("已经在最前面，再往前挪不动", ids(moved), ids(moved.withEntryMoved("worldInfoBefore", -1)))
        assertEquals("worldInfoBefore", ids(moved.withEntryMoved("worldInfoBefore", -3)).first())
        assertEquals("jailbreak", ids(moved.withEntryMoved("jailbreak", 5)).last())
    }

    @Test fun markersCannotBeRemovedButTextEntriesCan() {
        val p = preset().withEntryAdded(text("a"))
        assertEquals(ids(p), ids(p.withEntryRemoved("chatHistory")))
        assertFalse("a" in ids(p.withEntryRemoved("a")))
        assertFalse(p.withEntryRemoved("a").prompts.any { it.identifier == "a" })
    }

    @Test fun updatingAnEntryKeepsItsIdentifier() {
        val p = preset().withEntryUpdated("main") { it.copy(content = "New", identifier = "hacked") }
        assertEquals("New", p.textEntries().single().content)
        assertEquals("main", p.textEntries().single().identifier)
    }

    @Test fun replacingTextEntriesKeepsMarkersAndTheirOrder() {
        val p = preset().withTextEntries(listOf(text("one"), text("two")))
        assertEquals(listOf("worldInfoBefore", "one", "two", "personaDescription", "charDescription", "charPersonality", "scenario", "rikkihubContext", "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak"), ids(p))
        assertEquals(listOf("one", "two"), p.textEntries().map { it.identifier })
    }

    @Test fun mainPromptTextRoundTrips() {
        val a = Assistant(promptPreset = AssistantPresets.withSystemPrompt("hello"))
        assertEquals("hello", a.mainPromptText)
        assertEquals("changed", a.withMainPromptText("changed").mainPromptText)
    }

    @Test fun regexScriptsAreStoredInTheExtensionsBlockAndCanBeCleared() {
        val script = me.rerere.tavern.regex.RegexScript(id = "r1", scriptName = "s", findRegex = "/a/g", replaceString = "b", placement = listOf(1))
        val p = preset().withRegexScripts(listOf(script))
        assertEquals(listOf("r1"), p.regexScripts.map { it.id })
        assertTrue(p.extra.containsKey("extensions"))
        assertFalse(p.withRegexScripts(emptyList()).extra.containsKey("extensions"))
    }
}
