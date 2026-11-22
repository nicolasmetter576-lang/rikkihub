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
 * v43 → v44：`conversationentity.cli_session`（工作区 CLI 会话）。升级前的会话得到空串
 * （普通对话），其余列原样保留；单列写只动这一列并推进 revision。
 */
@RunWith(AndroidJUnit4::class)
class CliSessionMigrationInstrumentedTest {
    private val testDbName = "cli-session-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun existingConversationsAreOrdinaryChatsAndTheColumnIsWritable() {
        helper.createDatabase(testDbName, 43).use { db ->
            val values = ContentValues().apply {
                put("id", "c43")
                put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                put("title", "old chat")
                put("nodes", "[]")
                put("create_at", 1L)
                put("update_at", 1L)
                put("tavern_metadata", "{\"note_prompt\":\"keep\"}")
                put("character_id", "char-1")
                put("revision", 7L)
            }
            db.insert("conversationentity", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        helper.runMigrationsAndValidate(testDbName, 44, true).use { db ->
            db.query("SELECT title, tavern_metadata, character_id, cli_session, revision FROM conversationentity WHERE id = 'c43'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("old chat", c.getString(0))
                assertEquals("{\"note_prompt\":\"keep\"}", c.getString(1))
                assertEquals("char-1", c.getString(2))
                assertEquals("", c.getString(3))
                assertEquals(7L, c.getLong(4))
            }
            // The DAO's single-column write (ConversationDAO.updateCliSession).
            db.execSQL(
                "UPDATE conversationentity SET cli_session = ?, revision=revision+1 WHERE id = ? AND deleted_at IS NULL",
                arrayOf("{\"agentId\":\"builtin:agy\"}", "c43"),
            )
            db.query("SELECT title, cli_session, revision FROM conversationentity WHERE id = 'c43'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("old chat", c.getString(0))
                assertEquals("{\"agentId\":\"builtin:agy\"}", c.getString(1))
                assertEquals(8L, c.getLong(2))
            }
        }
    }
}
