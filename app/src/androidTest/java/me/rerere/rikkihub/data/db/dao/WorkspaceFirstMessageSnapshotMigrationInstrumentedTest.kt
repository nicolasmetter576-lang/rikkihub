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
 * 2.13.0：`workspaces.first_message_file_snapshot`（v35 → v36 AutoMigration）。
 * 已有工作区默认关闭（列默认 0），其余列（含 tool_enablement）原样保留。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceFirstMessageSnapshotMigrationInstrumentedTest {
    private val testDbName = "workspace-first-message-snapshot-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun existingWorkspacesDefaultToOff() {
        helper.createDatabase(testDbName, 35).use { db ->
            val values = ContentValues().apply {
                put("id", "w35")
                put("name", "old")
                put("root", "w35")
                put("shell_status", "READY")
                put("created_at", 1L)
                put("updated_at", 1L)
                put("tool_enablement", "{}")
            }
            db.insert("workspaces", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        helper.runMigrationsAndValidate(testDbName, 36, true).use { db ->
            db.query("SELECT tool_enablement, first_message_file_snapshot FROM workspaces WHERE id = 'w35'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("{}", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
        }
    }
}
