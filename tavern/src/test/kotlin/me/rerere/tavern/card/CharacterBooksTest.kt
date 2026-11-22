package me.rerere.tavern.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBooksTest {
    private val cardBook = CardSupport.parse("v2-harbor-cartographer.json").characterBook!!
    private val book = CharacterBooks.toWorldInfo(cardBook)

    @Test
    fun entriesComeInUidOrderWithMissingIdsTakenFromTheIndex() {
        assertEquals(listOf(0, 2, 3), book.entries.map { it.uid })
        assertEquals("Coastline Notes", book.name)
        assertEquals(cardBook, book.extra["originalData"])
    }

    @Test
    fun anEntryWithNoExtensionsGetsTheTavernDefaults() {
        val e = book.entries.first { it.uid == 0 }
        assertEquals(listOf("saltmarsh", "marsh"), e.key)
        assertEquals(0, e.position) // before_char
        assertFalse(e.selective)
        assertFalse(e.constant)
        assertFalse(e.disable)
        assertTrue(e.addMemo) // the comment is non-empty
        assertEquals(10, e.order)
        assertEquals(100, e.probability)
        assertTrue(e.useProbability)
        assertEquals(4, e.depth)
        assertEquals(100, e.groupWeight)
        assertEquals(0, e.role)
        assertNull(e.scanDepth)
        assertNull(e.caseSensitive)
        assertEquals(0, e.displayIndex)
    }

    @Test
    fun extensionKeysLandOnTheTavernFields() {
        val e = book.entries.first { it.uid == 3 }
        assertEquals(listOf("oak", "gall"), e.keysecondary)
        assertTrue(e.selective)
        assertEquals(4, e.position)
        assertEquals(2, e.depth)
        assertEquals(1, e.role)
        assertEquals(80, e.probability)
        assertEquals(3, e.selectiveLogic)
        assertEquals("ink", e.group)
        assertTrue(e.groupOverride)
        assertEquals(150, e.groupWeight)
        assertEquals(3, e.scanDepth)
        assertEquals(true, e.caseSensitive)
        assertEquals(false, e.matchWholeWords)
        assertEquals(2, e.sticky)
        assertEquals(1, e.cooldown)
        assertEquals(0, e.delay)
        assertTrue(e.excludeRecursion)
        assertTrue(e.preventRecursion)
        assertEquals(1, e.delayUntilRecursion)
        assertEquals(7, e.displayIndex)
        assertEquals("ink-1", e.automationId)
        assertEquals(listOf("normal"), e.triggers)
        assertTrue(e.ignoreBudget)
        assertTrue(e.matchCreatorNotes)
        assertFalse(e.addMemo) // empty comment
        // the card's own extensions object is kept whole, including keys the tavern does not know
        val ext = e.extra["extensions"] as JsonObject
        assertEquals(JsonPrimitive("kept"), ext["vendor_flag"])
    }

    @Test
    fun aDisabledConstantEntryWithoutIdUsesItsIndex() {
        val e = book.entries.first { it.uid == 2 }
        assertTrue(e.disable)
        assertTrue(e.constant)
        assertEquals(1, e.position) // after_char
        assertEquals(2, e.displayIndex)
        assertEquals(listOf("lighthouse"), e.key)
    }

    @Test
    fun theBookConvertsBackKeepingTheBookLevelFields() {
        val back = CharacterBooks.fromWorldInfo(book, "Renamed")
        assertEquals(JsonPrimitive("Renamed"), back["name"])
        assertEquals(JsonPrimitive(6), back["scan_depth"])
        assertEquals(JsonPrimitive(300), back["token_budget"])
        assertEquals(cardBook["extensions"], back["extensions"])
        val entries = (back["entries"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf(0, 2, 3), entries.map { (it["id"] as JsonPrimitive).content.toInt() })
        val ink = entries.first { (it["id"] as JsonPrimitive).content == "3" }
        assertEquals(JsonPrimitive("after_char"), ink["position"])
        assertEquals(JsonPrimitive(true), ink["use_regex"])
        val ext = ink["extensions"] as JsonObject
        assertEquals(JsonPrimitive(4), ext["position"])
        assertEquals(JsonPrimitive("kept"), ext["vendor_flag"])
        assertEquals(JsonPrimitive("ink-1"), ext["automation_id"])
        val off = entries.first { (it["id"] as JsonPrimitive).content == "2" }
        assertEquals(JsonPrimitive(false), off["enabled"])
        assertEquals(JsonPrimitive("after_char"), off["position"])
    }

    @Test
    fun convertingThereAndBackIsStable() {
        val again = CharacterBooks.toWorldInfo(CharacterBooks.fromWorldInfo(book, "Coastline Notes"))
        // the card's own `extensions` object is rebuilt with every key spelled out, so compare the typed fields;
        // the tavern writes an unset use_group_scoring as false, so a null comes back as false
        fun typed(b: me.rerere.tavern.worldinfo.WorldInfoBook) =
            b.entries.map { it.copy(extra = JsonObject(emptyMap()), layout = null, useGroupScoring = it.useGroupScoring ?: false) }
        assertEquals(typed(book), typed(again))
    }

    @Test
    fun aBookWithNoEntriesOrOddIdsDoesNotCrash() {
        assertTrue(CharacterBooks.toWorldInfo(JsonObject(emptyMap())).entries.isEmpty())
        val odd = CardCodec.parse("""{"name":"N","spec":"chara_card_v2","spec_version":"2.0","data":{"name":"N","character_book":{"entries":[{"id":"7","keys":["a"],"content":"x","enabled":true},{"id":"7","keys":["b"],"content":"y","enabled":true},{"keys":["c"],"content":"z","enabled":true}]}}}""").card
        val b = CharacterBooks.toWorldInfo(odd.characterBook!!)
        // a repeated id keeps the later entry; a missing id takes its index
        assertEquals(listOf(2, 7), b.entries.map { it.uid })
        assertEquals(listOf("b"), b.entries.first { it.uid == 7 }.key)
    }
}
