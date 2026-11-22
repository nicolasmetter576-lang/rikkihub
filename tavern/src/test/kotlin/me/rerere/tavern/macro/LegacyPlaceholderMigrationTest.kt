package me.rerere.tavern.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPlaceholderMigrationTest {
    @Test
    fun migratesOwnedKeys() {
        assertEquals("{{cur_date}}", LegacyPlaceholderMigration.migrate("{cur_date}"))
        assertEquals("{{model_id}}", LegacyPlaceholderMigration.migrate("{model_id}"))
        assertEquals("{{nickname}}", LegacyPlaceholderMigration.migrate("{nickname}"))
    }

    @Test
    fun caseInsensitiveToLowercase() {
        assertEquals("{{cur_date}}", LegacyPlaceholderMigration.migrate("{CUR_DATE}"))
        assertEquals("{{model_name}}", LegacyPlaceholderMigration.migrate("{Model_Name}"))
    }

    @Test
    fun leavesDoubleBraceAlone() {
        assertEquals("{{cur_date}}", LegacyPlaceholderMigration.migrate("{{cur_date}}"))
        assertEquals("{{CUR_DATE}}", LegacyPlaceholderMigration.migrate("{{CUR_DATE}}"))
    }

    @Test
    fun leavesUnrelatedBracesAlone() {
        assertEquals("{other}", LegacyPlaceholderMigration.migrate("{other}"))
        assertEquals("{cur_date", LegacyPlaceholderMigration.migrate("{cur_date"))
        assertEquals("{\"a\":1}", LegacyPlaceholderMigration.migrate("{\"a\":1}"))
        assertEquals("{userx}", LegacyPlaceholderMigration.migrate("{userx}"))
    }

    @Test
    fun mixedContent() {
        val input = "Hello {user}, today is {cur_date}. {{char}} says {unknown}."
        assertEquals("Hello {{user}}, today is {{cur_date}}. {{char}} says {unknown}.",
            LegacyPlaceholderMigration.migrate(input))
    }

    @Test
    fun needsMigration() {
        assertTrue(LegacyPlaceholderMigration.needsMigration("a {model_id} b"))
        assertFalse(LegacyPlaceholderMigration.needsMigration("a {{model_id}} b"))
        assertFalse(LegacyPlaceholderMigration.needsMigration("plain"))
    }
}
