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
 * 2.15.0：`workspaces.first_message_file_snapshot_depth`（v36 → v37 AutoMigration）。
 * 已有工作区层数为 1（与之前的一层列表一致），开关与其余列原样保留。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceSnapshotDepthMigrationInstrumentedTest {
    private val testDbName = "workspace-snapshot-depth-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun existingWorkspacesKeepOneLevel() {
        helper.createDatabase(testDbName, 36).use { db ->
            val values = ContentValues().apply {
                put("id", "w36")
                put("name", "old")
                put("root", "w36")
                put("shell_status", "READY")
                put("created_at", 1L)
                put("updated_at", 1L)
                put("tool_enablement", "{}")
                put("first_message_file_snapshot", 1)
            }
            db.insert("workspaces", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        helper.runMigrationsAndValidate(testDbName, 37, true).use { db ->
            db.query("SELECT tool_enablement, first_message_file_snapshot, first_message_file_snapshot_depth FROM workspaces WHERE id = 'w36'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("{}", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals(1, c.getInt(2))
            }
        }
    }
}
