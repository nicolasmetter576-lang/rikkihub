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
 * `conversationentity.tavern_metadata`（v40 → v41 AutoMigration）。
 * 升级前已有的会话得到空对象 `{}`（列默认值），其余列原样保留。
 */
@RunWith(AndroidJUnit4::class)
class ConversationTavernMetadataMigrationInstrumentedTest {
    private val testDbName = "conversation-tavern-metadata-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun existingConversationsGetAnEmptyObject() {
        helper.createDatabase(testDbName, 40).use { db ->
            val values = ContentValues().apply {
                put("id", "c40")
                put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                put("title", "old")
                put("nodes", "[]")
                put("create_at", 1L)
                put("update_at", 1L)
                put("workspace_cwd", "/workspace/project")
            }
            db.insert("conversationentity", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        helper.runMigrationsAndValidate(testDbName, 41, true).use { db ->
            db.query("SELECT workspace_cwd, tavern_metadata FROM conversationentity WHERE id = 'c40'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("/workspace/project", c.getString(0))
                assertEquals("{}", c.getString(1))
            }
        }
    }
}
