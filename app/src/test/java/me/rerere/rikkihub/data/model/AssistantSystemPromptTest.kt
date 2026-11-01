package me.rerere.rikkihub.data.model

import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantSystemPromptTest {
    private val a = SystemPromptSection(title = "A", content = "alpha")
    private val b = SystemPromptSection(title = "B", content = "beta", enabled = false)
    private val c = SystemPromptSection(title = "C", content = "gamma")

    private fun Assistant.consistent() = assertEquals(composeSystemPromptSections(promptSections), systemPrompt)
    private fun Assistant.contents() = promptSections.map { it.content }

    @Test fun `default assistant composes exactly its whole prompt`() {
        val assistant = Assistant(systemPrompt = "  keep spacing \n")
        assertEquals(SystemPromptMode.WHOLE, assistant.systemPromptMode)
        assertEquals("  keep spacing \n", assistant.composedSystemPrompt())
        assertEquals("  keep spacing \n", assistant.effectiveSystemPrompt(null))
    }

    @Test fun `with sections the prompt is their join in either view`() {
        val assistant = Assistant(promptSections = listOf(c, b, SystemPromptSection(content = " \n"), a))
        assertEquals("gamma\n\nalpha", assistant.composedSystemPrompt())
        assertEquals("gamma\n\nalpha", assistant.copy(systemPromptMode = SystemPromptMode.SECTIONS).composedSystemPrompt())
        assertEquals("", assistant.copy(promptSections = listOf(b)).composedSystemPrompt())
    }

    @Test fun `conversation override replaces the prompt only when allowed and non blank`() {
        val whole = Assistant(systemPrompt = "w", allowConversationSystemPrompt = true)
        val sections = whole.withPromptSections(listOf(a))
        assertEquals("o", whole.effectiveSystemPrompt("o"))
        assertEquals("o", sections.effectiveSystemPrompt("o"))
        assertEquals("alpha", sections.effectiveSystemPrompt("  "))
        assertEquals("alpha", sections.copy(allowConversationSystemPrompt = false).effectiveSystemPrompt("o"))
    }

    @Test fun `switching to sections seeds the whole prompt only when there are no sections`() {
        val seeded = Assistant(systemPrompt = "persona").switchSystemPromptMode(SystemPromptMode.SECTIONS, "Original")
        assertEquals(SystemPromptMode.SECTIONS, seeded.systemPromptMode)
        assertEquals(listOf("Original" to "persona"), seeded.promptSections.map { it.title to it.content })
        seeded.consistent()

        val existing = Assistant().withPromptSections(listOf(a)).switchSystemPromptMode(SystemPromptMode.SECTIONS, "Original")
        assertEquals(listOf(a), existing.promptSections)

        val empty = Assistant().switchSystemPromptMode(SystemPromptMode.SECTIONS, "Original")
        assertEquals(emptyList<SystemPromptSection>(), empty.promptSections)

        val back = seeded.switchSystemPromptMode(SystemPromptMode.WHOLE, "Original")
        assertEquals(seeded.promptSections, back.promptSections)
        assertEquals("persona", back.composedSystemPrompt())
        assertSame(back, back.switchSystemPromptMode(SystemPromptMode.WHOLE, "x"))
    }

    @Test fun `editing sections rewrites the whole prompt and deleting all empties it`() {
        val two = Assistant(systemPrompt = "old").withPromptSections(listOf(a, c))
        assertEquals("alpha\n\ngamma", two.systemPrompt)
        val off = two.withPromptSections(listOf(a, c.copy(enabled = false)))
        assertEquals("alpha", off.systemPrompt)
        assertEquals("", two.withPromptSections(emptyList()).systemPrompt)
    }

    @Test fun `editing inside one section of the whole view changes only that section`() {
        val assistant = Assistant().withPromptSections(listOf(a, b, c))
        val edited = assistant.withWholeSystemPrompt("alpha!\n\ngamma")
        assertEquals(listOf("alpha!", "beta", "gamma"), edited.contents())
        assertEquals(listOf(a.id, b.id, c.id), edited.promptSections.map { it.id })
        edited.consistent()
        assertEquals(listOf("alpha", "beta", "gamma tail"), assistant.withWholeSystemPrompt("alpha\n\ngamma tail").contents())
        assertEquals(listOf("head alpha", "beta", "gamma"), assistant.withWholeSystemPrompt("head alpha\n\ngamma").contents())
    }

    @Test fun `typing at a boundary goes to the section before it, after the blank line to the one after`() {
        val segments = listOf(SystemPromptSegment(a.id, "A"), SystemPromptSegment(c.id, "C"))
        assertEquals(listOf("Ax", "C"), remapSystemPromptSegments(segments, "Ax\n\nC").segments.map { it.text })
        assertEquals(listOf("A", "xC"), remapSystemPromptSegments(segments, "A\n\nxC").segments.map { it.text })
    }

    @Test fun `breaking a blank line merges the neighbours into the first`() {
        val segments = listOf(SystemPromptSegment(a.id, "A"), SystemPromptSegment(c.id, "C"))
        val joined = remapSystemPromptSegments(segments, "A\nC")
        assertEquals(listOf(SystemPromptSegment(a.id, "A\nC")), joined.segments)
        assertEquals(setOf(c.id), joined.cleared)
        val inside = remapSystemPromptSegments(segments, "A\nx\nC")
        assertEquals(listOf(SystemPromptSegment(a.id, "A\nx\nC")), inside.segments)
    }

    @Test fun `deleting a whole section with its blank line keeps the next section's identity`() {
        val three = listOf(SystemPromptSegment(a.id, "A"), SystemPromptSegment(b.id, "B"), SystemPromptSegment(c.id, "C"))
        val middle = remapSystemPromptSegments(three, "A\n\nC")
        assertEquals(listOf(SystemPromptSegment(a.id, "A"), SystemPromptSegment(c.id, "C")), middle.segments)
        assertEquals(setOf(b.id), middle.cleared)
        val first = remapSystemPromptSegments(three, "B\n\nC")
        assertEquals(listOf(b.id, c.id), first.segments.map { it.sectionId })
        assertEquals(setOf(a.id), first.cleared)
        val last = remapSystemPromptSegments(three, "A\n\nB")
        assertEquals(listOf(a.id, b.id), last.segments.map { it.sectionId })
        assertEquals(setOf(c.id), last.cleared)
    }

    @Test fun `emptying a section keeps its place so later typing goes back into it`() {
        val segments = listOf(SystemPromptSegment(a.id, "A"), SystemPromptSegment(c.id, "C"))
        val emptied = remapSystemPromptSegments(segments, "\n\nC")
        assertEquals(listOf(SystemPromptSegment(a.id, ""), SystemPromptSegment(c.id, "C")), emptied.segments)
        val retyped = remapSystemPromptSegments(emptied.segments, "Z\n\nC")
        assertEquals(listOf(SystemPromptSegment(a.id, "Z"), SystemPromptSegment(c.id, "C")), retyped.segments)
    }

    @Test fun `remapped segments always join back to the new text`() {
        val segments = listOf(SystemPromptSegment(a.id, "one\n\ntwo"), SystemPromptSegment(b.id, "three"), SystemPromptSegment(c.id, "four"))
        val old = segments.joinToString("\n\n") { it.text }
        val edits = listOf("", "x", old.reversed(), old.replace("\n", ""), old + "\n\n", "\n\n" + old,
            old.substring(0, 5) + old.substring(9), old.replace("three", "3\n\n3"), "one\n\ntwo\n\nthree")
        for (edit in edits) {
            val remap = remapSystemPromptSegments(segments, edit)
            assertEquals(edit, remap.segments.joinToString("\n\n") { it.text })
            assertTrue(remap.segments.map { it.sectionId }.none { it in remap.cleared })
        }
    }

    @Test fun `whole view with no visible section writes into the first enabled one or a new section`() {
        val blank = SystemPromptSection(content = "  ")
        val hidden = Assistant().withPromptSections(listOf(b, blank))
        val typed = hidden.withWholeSystemPrompt("new text")
        assertEquals(listOf("beta", "new text"), typed.contents())
        assertEquals("new text", typed.systemPrompt)
        val allOff = Assistant().withPromptSections(listOf(b))
        val appended = allOff.withWholeSystemPrompt("fresh")
        assertEquals(2, appended.promptSections.size)
        assertEquals("fresh", appended.promptSections.last().content)
        appended.consistent()
    }

    @Test fun `whole prompt without sections stays a plain string`() {
        val plain = Assistant(systemPrompt = "p")
        assertEquals(plain.copy(systemPrompt = "x"), plain.withWholeSystemPrompt("x"))
    }

    @Test fun `reconcile follows whichever side the writer changed`() {
        val before = Assistant(systemPrompt = "x").withPromptSections(listOf(a, c))
        // sections changed → whole recomputed
        val sectionsEdit = before.copy(promptSections = listOf(c, a)).reconcileSystemPrompt(before)
        assertEquals("gamma\n\nalpha", sectionsEdit.systemPrompt)
        // only whole changed → lands in the matching section
        val wholeEdit = before.copy(systemPrompt = "alpha\n\ngamma!").reconcileSystemPrompt(before)
        assertEquals(listOf("alpha", "gamma!"), wholeEdit.contents())
        // both changed: sections win
        val both = before.copy(systemPrompt = "ignored", promptSections = listOf(a)).reconcileSystemPrompt(before)
        assertEquals("alpha", both.systemPrompt)
        // removing the split together with a new whole keeps that whole
        val unsplit = before.copy(systemPrompt = "plain", promptSections = emptyList()).reconcileSystemPrompt(before)
        assertEquals("plain", unsplit.systemPrompt)
        // removing the split alone empties the prompt (what the sections view shows)
        assertEquals("", before.copy(promptSections = emptyList()).reconcileSystemPrompt(before).systemPrompt)
        // new assistant with sections; untouched assistant unchanged
        assertEquals("alpha", Assistant(promptSections = listOf(a)).reconcileSystemPrompt(null).systemPrompt)
        assertSame(before, before.reconcileSystemPrompt(before))
        val plain = Assistant(systemPrompt = "p")
        assertSame(plain, plain.reconcileSystemPrompt(null))
    }

    @Test fun `legacy whole mode with draft sections keeps sending the whole prompt`() {
        val legacy = Assistant(systemPrompt = "persona", systemPromptMode = SystemPromptMode.WHOLE, promptSections = listOf(a, c))
        val normalized = legacy.normalizedSystemPrompt()
        assertEquals("persona", normalized.composedSystemPrompt())
        assertEquals(listOf("persona", "alpha", "gamma"), normalized.contents())
        assertEquals(listOf(true, false, false), normalized.promptSections.map { it.enabled })
        normalized.consistent()
        assertEquals(normalized, legacy.normalizedSystemPrompt())
        assertSame(normalized, normalized.normalizedSystemPrompt())
    }

    @Test fun `legacy sections mode keeps sending sections and keeps the old whole text switched off`() {
        val legacy = Assistant(systemPrompt = "old whole", systemPromptMode = SystemPromptMode.SECTIONS, promptSections = listOf(a, b))
        val normalized = legacy.normalizedSystemPrompt()
        assertEquals("alpha", normalized.composedSystemPrompt())
        assertEquals(listOf("alpha", "beta", "old whole"), normalized.contents())
        assertEquals(false, normalized.promptSections.last().enabled)
        normalized.consistent()
        assertEquals(normalized, legacy.normalizedSystemPrompt())
        // the seeded copy already holds the whole text: nothing to keep
        val seeded = Assistant(systemPrompt = "alpha", systemPromptMode = SystemPromptMode.SECTIONS, promptSections = listOf(a, c))
        assertEquals(listOf("alpha", "gamma"), seeded.normalizedSystemPrompt().contents())
        val other = Assistant(id = Uuid.random(), systemPrompt = "persona", promptSections = listOf(a))
        assertNotEquals(normalized.promptSections.last().id, other.normalizedSystemPrompt().promptSections.first().id)
    }

    @Test fun `stored json without the new keys decodes to whole mode and round trips`() {
        val old = JsonInstant.decodeFromString(Assistant.serializer(), """{"name":"x","systemPrompt":"p"}""")
        assertEquals(SystemPromptMode.WHOLE, old.systemPromptMode)
        assertEquals(emptyList<SystemPromptSection>(), old.promptSections)

        val sections = old.copy(systemPromptMode = SystemPromptMode.SECTIONS, promptSections = listOf(a, b))
        val encoded = JsonInstant.encodeToString(Assistant.serializer(), sections)
        assertEquals(true, encoded.contains("\"systemPromptMode\":\"sections\""))
        assertEquals(sections, JsonInstant.decodeFromString(Assistant.serializer(), encoded))
    }
}
