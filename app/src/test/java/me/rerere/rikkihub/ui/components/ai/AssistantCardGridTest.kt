package me.rerere.rikkihub.ui.components.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantCardGridTest {
    private fun item(name: String, wide: Boolean = false, note: String = "", enabled: Boolean = true) =
        Assistant(id = Uuid.random(), name = name, cardWide = wide, cardNote = note, enabled = enabled)

    @Test
    fun `empty list produces empty slots`() {
        assertEquals(emptyList<AssistantGridSlot>(), planAssistantGrid(emptyList()))
    }

    @Test
    fun `paired short cards stay half-width while trailing odd short card auto-expands`() {
        val a = item("A")
        val b = item("B")
        val c = item("C")
        val slots = planAssistantGrid(listOf(a, b, c))

        assertEquals(listOf(1, 1, 2), slots.map { it.span })
        assertEquals(listOf(false, false, true), slots.map { it.autoExpanded })
    }

    @Test
    fun `short card immediately before explicit wide card auto-expands to leave zero half-row gaps`() {
        val a = item("A")
        val b = item("B-long", wide = true)
        val c = item("C")
        val d = item("D")
        val e = item("E-long", wide = true)
        val f = item("F")
        val slots = planAssistantGrid(listOf(a, b, c, d, e, f))

        assertEquals(listOf(2, 2, 1, 1, 2, 2), slots.map { it.span })
        assertEquals(listOf(true, false, false, false, false, true), slots.map { it.autoExpanded })
    }

    @Test
    fun `rowHasNote aligns partner card height on the same row and ignores blank notes`() {
        val leftPlain = item("L1", note = "   ")
        val rightWithNote = item("R1", note = "memo")
        val leftEmpty = item("L2", note = "")
        val rightEmpty = item("R2", note = "")
        val slots = planAssistantGrid(listOf(leftPlain, rightWithNote, leftEmpty, rightEmpty))

        assertEquals(listOf(true, true, false, false), slots.map { it.rowHasNote })
    }

    @Test
    fun `showDisabledBadge affects subline alignment only when enabled`() {
        val disabledNoNote = item("Off", note = "", enabled = false)
        val activeNoNote = item("On", note = "", enabled = true)

        val drawerSlots = planAssistantGrid(listOf(disabledNoNote, activeNoNote), showDisabledBadge = false)
        assertEquals(listOf(false, false), drawerSlots.map { it.rowHasNote })

        val pageSlots = planAssistantGrid(listOf(disabledNoNote, activeNoNote), showDisabledBadge = true)
        assertEquals(listOf(true, true), pageSlots.map { it.rowHasNote })
    }

    @Test
    fun `frozenSpans locks card widths mid-drag until released`() {
        val a = item("A")
        val b = item("B-wide", wide = true)
        val c = item("C")
        val d = item("D")
        val before = planAssistantGrid(listOf(a, b, c, d))
        val frozen = before.associate { it.assistant.id to it.span }
        assertEquals(mapOf(a.id to 2, b.id to 2, c.id to 1, d.id to 1), frozen)

        // 用户拖动 C 越过 B 后，拖拽过程中保持各自宽度不跳变
        val midDrag = planAssistantGrid(listOf(c, a, b, d), frozenSpans = frozen)
        assertEquals(listOf(c.id, a.id, b.id, d.id), midDrag.map { it.assistant.id })
        assertEquals(listOf(1, 2, 2, 1), midDrag.map { it.span })

        // 松手解除锁定后自动按最终顺序重新凑对与补位
        val settled = planAssistantGrid(listOf(c, a, b, d), frozenSpans = null)
        assertEquals(listOf(1, 1, 2, 2), settled.map { it.span })
        assertEquals(listOf(false, false, false, true), settled.map { it.autoExpanded })
    }

    @Test
    fun `updateCardAppearance only edits target card note and cardWide`() {
        val a = item("A", wide = false, note = "old").copy(temperature = 0.7f)
        val b = item("B", wide = true, note = "keep")
        val updated = listOf(a, b).updateCardAppearance(a.id, note = "new note", cardWide = true)

        assertEquals("new note", updated[0].cardNote)
        assertTrue(updated[0].cardWide)
        assertEquals(0.7f, updated[0].temperature)
        assertEquals(b, updated[1])
    }

    @Test
    fun `Assistant serialization keeps card defaults on legacy JSON and round-trips new fields`() {
        val source = item("Test", wide = true, note = "我的助手")
        val encoded = JsonInstant.encodeToJsonElement(Assistant.serializer(), source).jsonObject
        val decoded = JsonInstant.decodeFromJsonElement(Assistant.serializer(), encoded)
        assertTrue(decoded.cardWide)
        assertEquals("我的助手", decoded.cardNote)

        val legacyJson = JsonObject(encoded - "cardNote" - "cardWide")
        val legacyDecoded = JsonInstant.decodeFromJsonElement(Assistant.serializer(), legacyJson)
        assertFalse(legacyDecoded.cardWide)
        assertEquals("", legacyDecoded.cardNote)
    }
}
