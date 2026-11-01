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
 * `conversationentity.workspace_cwd_scoped`（v34 → v35 AutoMigration）。
 * 升级前已有的会话默认开启限定（列默认 1），其余列原样保留。
 */
@RunWith(AndroidJUnit4::class)
class ConversationCwdScopedMigrationInstrumentedTest {
    private val testDbName = "conversation-cwd-scoped-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun existingConversationsDefaultToScoped() {
        helper.createDatabase(testDbName, 34).use { db ->
            val values = ContentValues().apply {
                put("id", "c34")
                put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                put("title", "old")
                put("nodes", "[]")
                put("create_at", 1L)
                put("update_at", 1L)
                put("workspace_cwd", "/workspace/project")
            }
            db.insert("conversationentity", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        helper.runMigrationsAndValidate(testDbName, 35, true).use { db ->
            db.query("SELECT workspace_cwd, workspace_cwd_scoped FROM conversationentity WHERE id = 'c34'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("/workspace/project", c.getString(0))
                assertEquals(1, c.getInt(1))
            }
        }
    }
}
