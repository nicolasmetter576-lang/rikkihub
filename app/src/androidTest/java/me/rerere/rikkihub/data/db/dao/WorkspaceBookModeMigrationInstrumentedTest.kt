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

/** `workspaces.book_mode` / `book_prompt_language`（v47 → v48 AutoMigration）：已有工作区都不是作品模式，其余列不变。 */
@RunWith(AndroidJUnit4::class)
class WorkspaceBookModeMigrationInstrumentedTest {
    private val testDbName = "workspace-book-mode-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun existingWorkspacesKeepTheirMode() {
        helper.createDatabase(testDbName, 47).use { db ->
            for ((id, harness) in listOf("normal" to 0, "harness" to 1)) {
                val values = ContentValues().apply {
                    put("id", id)
                    put("name", id)
                    put("root", id)
                    put("shell_status", "READY")
                    put("created_at", 1L)
                    put("updated_at", 1L)
                    put("tool_enablement", """{"subagent":false}""")
                    put("harness_minimal", harness)
                    put("first_message_file_snapshot_depth", 3)
                }
                db.insert("workspaces", SQLiteDatabase.CONFLICT_ABORT, values)
            }
        }
        helper.runMigrationsAndValidate(testDbName, 48, true).use { db ->
            db.query(
                "SELECT harness_minimal, tool_enablement, first_message_file_snapshot_depth, book_mode, book_prompt_language FROM workspaces ORDER BY id"
            ).use { c ->
                for (harness in listOf(1, 0)) {
                    assertTrue(c.moveToNext())
                    assertEquals(harness, c.getInt(0))
                    assertEquals("""{"subagent":false}""", c.getString(1))
                    assertEquals(3, c.getInt(2))
                    assertEquals(0, c.getInt(3))
                    assertEquals("zh", c.getString(4))
                }
            }
        }
    }
}
