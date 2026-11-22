package me.rerere.rikkihub.data.db.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v41 → v42：新表 `characters` 和 `conversationentity.character_id`。
 * 升级前的会话得到空串（没有角色），其余列原样保留；新表可以直接写读。
 */
@RunWith(AndroidJUnit4::class)
class CharacterMigrationInstrumentedTest {
    private val testDbName = "character-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun existingConversationsHaveNoCharacterAndTheNewTableWorks() {
        helper.createDatabase(testDbName, 41).use { db ->
            val values = ContentValues().apply {
                put("id", "c41")
                put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                put("title", "old chat")
                put("nodes", "[]")
                put("create_at", 1L)
                put("update_at", 1L)
                put("tavern_metadata", "{\"note_prompt\":\"keep\"}")
            }
            db.insert("conversationentity", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        helper.runMigrationsAndValidate(testDbName, 42, true).use { db ->
            db.query("SELECT title, tavern_metadata, character_id FROM conversationentity WHERE id = 'c41'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("old chat", c.getString(0))
                assertEquals("{\"note_prompt\":\"keep\"}", c.getString(1))
                assertEquals("", c.getString(2))
            }
            val card = ContentValues().apply {
                put("id", "char-1")
                put("name", "Ines Okafor")
                put("card_json", "{}")
                put("created_at", 5L)
                put("updated_at", 6L)
            }
            db.insert("characters", SQLiteDatabase.CONFLICT_ABORT, card)
            db.query("SELECT avatar_path, fav, tags, local_settings, revision FROM characters WHERE id = 'char-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("", c.getString(0))
                assertEquals(0, c.getInt(1))
                assertEquals("[]", c.getString(2))
                assertEquals("{}", c.getString(3))
                assertEquals(0L, c.getLong(4))
            }
        }
    }
}
