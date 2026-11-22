package me.rerere.rikkihub.data.model

import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldInfoEditingTest {
    // ---- 关键词文字

    @Test fun plainKeysSplitOnCommasAndDropBlanks() {
        assertEquals(listOf("a", "b c", "d"), parseKeyList("a, b c ,, d ,"))
        assertEquals(emptyList<String>(), parseKeyList("  , , "))
        assertEquals(emptyList<String>(), parseKeyList(""))
    }

    @Test fun regexKeysMayContainCommas() {
        assertEquals(listOf("/a,b/i", "z"), parseKeyList("/a,b/i, z"))
        assertEquals(listOf("x", "/a,b/", "/c|d/gi"), parseKeyList("x, /a,b/, /c|d/gi"))
    }

    @Test fun anUnclosedSlashIsJustAWord() {
        assertEquals(listOf("/unclosed", "a"), parseKeyList("/unclosed, a"))
        assertEquals(listOf("foo/bar", "baz"), parseKeyList("foo/bar, baz"))
    }

    @Test fun aSlashInsideTheBodyDoesNotEndTheKeyEarly() {
        assertEquals(listOf("/a/b/g", "k"), parseKeyList("/a/b/g, k"))
    }

    @Test fun formatThenParseIsStable() {
        val keys = listOf("alpha", "/b,c/i", "delta epsilon")
        assertEquals(keys, parseKeyList(formatKeyList(keys)))
    }

    // ---- 条目读写

    @Test fun titleFallsBackToTheFirstKey() {
        assertEquals("Note", WorldInfoEntry(comment = "Note", key = listOf("k")).title)
        assertEquals("k", WorldInfoEntry(comment = "  ", key = listOf("k", "j")).title)
        assertEquals("", WorldInfoEntry().title)
    }

    @Test fun searchLooksAtCommentKeysAndContentIgnoringCase() {
        val e = WorldInfoEntry(comment = "Dragon lair", key = listOf("wyrm"), keysecondary = listOf("Cave"), content = "Hoard of gold")
        assertTrue(e.matchesQuery("dragon"))
        assertTrue(e.matchesQuery("WYRM"))
        assertTrue(e.matchesQuery("cave"))
        assertTrue(e.matchesQuery("gold"))
        assertTrue(e.matchesQuery("  "))
        assertFalse(e.matchesQuery("elf"))
    }

    @Test fun secondaryKeysTurnSelectiveOn() {
        val off = WorldInfoEntry(selective = false)
        val withKeys = off.withSecondaryKeys("x, y")
        assertEquals(listOf("x", "y"), withKeys.keysecondary)
        assertTrue(withKeys.selective)
        val cleared = withKeys.withSecondaryKeys("")
        assertTrue(cleared.keysecondary.isEmpty())
        assertTrue("clearing the keys leaves the flag alone", cleared.selective)
    }

    @Test fun probabilityIsClampedAndTurnedOn() {
        val e = WorldInfoEntry(useProbability = false).withProbability(140)
        assertEquals(100, e.probability)
        assertTrue(e.useProbability)
        assertEquals(0, e.withProbability(-5).probability)
    }

    @Test fun triggerSetFollowsTheSameRulesAsPromptEntries() {
        val e = WorldInfoEntry()
        assertTrue(e.triggerSet.isEmpty())
        val some = e.withTriggerSet(setOf(GenerationType.REGENERATE, GenerationType.NORMAL))
        assertEquals(listOf("normal", "regenerate"), some.triggers)
        assertEquals(emptyList<String>(), some.withTriggerSet(GenerationType.entries.toSet()).triggers)
        val foreign = WorldInfoEntry(triggers = listOf("normal", "future-kind")).withTriggerSet(setOf(GenerationType.SWIPE))
        assertEquals(listOf("swipe", "future-kind"), foreign.triggers)
    }

    @Test fun triStateRoundTrips() {
        assertEquals(TriState.INHERIT, (null as Boolean?).triState)
        assertEquals(TriState.ON, true.triState)
        assertEquals(TriState.OFF, false.triState)
        assertNull(TriState.INHERIT.toBoolean())
        assertEquals(true, TriState.ON.toBoolean())
        assertEquals(false, TriState.OFF.toBoolean())
    }

    // ---- 书

    @Test fun newEntriesGetTheNextFreeUid() {
        val empty = Lorebook(name = "b")
        val (one, first) = empty.withNewEntry()
        assertEquals(0, first.uid)
        val (two, second) = one.withNewEntry()
        assertEquals(1, second.uid)
        val gap = two.withoutEntry(0).withNewEntry()
        assertEquals("a removed low uid is not reused while higher ones exist", 2, gap.second.uid)
        assertEquals(2, gap.first.entries.size)
    }

    @Test fun updatingAnEntryKeepsItsUidAndTouchesOnlyThatEntry() {
        val (book, _) = Lorebook(name = "b").withNewEntry().first.withNewEntry()
        val updated = book.withEntryUpdated(1) { it.copy(uid = 99, comment = "changed") }
        assertEquals(listOf(0, 1), updated.entries.map { it.uid })
        assertEquals(listOf("", "changed"), updated.entries.map { it.comment })
        assertEquals(book, book.withEntryUpdated(7) { it.copy(comment = "x") })
    }

    @Test fun enablingEverythingFlipsDisableOnAllEntries() {
        val (book, _) = Lorebook(name = "b").withNewEntry().first.withNewEntry()
        val off = book.withAllEntriesEnabled(false)
        assertTrue(off.entries.all { it.disable })
        assertTrue(off.withAllEntriesEnabled(true).entries.none { it.disable })
    }

    @Test fun editedEntriesStillEncodeAsSillyTavernWorldInfo() {
        val book = Lorebook(name = "b").withNewEntry().first
            .withEntryUpdated(0) { it.withPrimaryKeys("a, /b,c/i").withSecondaryKeys("z").withProbability(40).copy(comment = "c", content = "text") }
        val json = me.rerere.rikkihub.data.export.LorebookSerializer.exportSillyTavern(book)
        val decoded = WorldInfoCodec.decodeBook(json).entries.single()
        assertEquals(listOf("a", "/b,c/i"), decoded.key)
        assertEquals(listOf("z"), decoded.keysecondary)
        assertEquals(40, decoded.probability)
        assertEquals("text", decoded.content)
    }
}
