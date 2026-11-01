package me.rerere.rikkihub.data.db.dao

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real v31 → v32 AutoMigration; schema 32 must first be exported by KSP, never handwritten. */
@RunWith(AndroidJUnit4::class)
class WorkspaceSubagentPresetMigrationInstrumentedTest {
    private val testDbName = "workspace-subagent-preset-migration-test"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(context),
    )

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    @Test
    fun migrate31to32AddsEmptyPresetCatalogWithoutChangingExistingConfigurationOrMounts() {
        context.deleteDatabase(testDbName)
        helper.createDatabase(testDbName, 31).use { db ->
            db.execSQL("""
                INSERT INTO workspaces (
                    id, name, root, shell_status, created_at, updated_at, last_access_at,
                    tool_approvals, tool_enablement, harness_minimal,
                    expose_upload, expose_skills, expose_tool_outputs,
                    subagent_model_id, subagent_allow_context, shell_compatibility_mode
                ) VALUES (
                    'legacy', 'Legacy workspace', 'legacy-root', 'READY', 11, 22, 33,
                    '{"bash":true}', '{"bash":false,"subagent":false}', 1,
                    0, 0, 1, '00000000-0000-4000-8000-000000000001', 1, 1
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO workspace_device_mounts (
                    id, workspace_id, alias, source_relative_path, enabled, created_at, updated_at
                ) VALUES ('mount', 'legacy', 'docs', 'Documents', 1, 44, 55)
            """.trimIndent())
        }
        helper.runMigrationsAndValidate(testDbName, 32, true).use { db ->
            db.query("SELECT * FROM workspaces WHERE id = 'legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                assertEquals("[]", text("subagent_presets"))
                assertEquals("Legacy workspace", text("name"))
                assertEquals("legacy-root", text("root"))
                assertEquals("READY", text("shell_status"))
                assertEquals(11L, number("created_at"))
                assertEquals(22L, number("updated_at"))
                assertEquals(33L, number("last_access_at"))
                assertEquals("{\"bash\":true}", text("tool_approvals"))
                assertEquals("{\"bash\":false,\"subagent\":false}", text("tool_enablement"))
                assertEquals(1L, number("harness_minimal"))
                assertEquals(0L, number("expose_upload"))
                assertEquals(0L, number("expose_skills"))
                assertEquals(1L, number("expose_tool_outputs"))
                assertEquals("00000000-0000-4000-8000-000000000001", text("subagent_model_id"))
                assertEquals(1L, number("subagent_allow_context"))
                assertEquals(1L, number("shell_compatibility_mode"))
            }
            db.query("SELECT alias, source_relative_path, enabled, created_at, updated_at FROM workspace_device_mounts WHERE id = 'mount'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("docs", cursor.getString(0))
                assertEquals("Documents", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(44L, cursor.getLong(3))
                assertEquals(55L, cursor.getLong(4))
            }
            // SQL default also applies to inserts omitting the new column; no templates are auto-enabled.
            db.execSQL("""
                INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, tool_approvals)
                VALUES ('new', 'New workspace', 'new-root', 'DISABLED', 1, 1, '{}')
            """.trimIndent())
            db.query("SELECT subagent_presets FROM workspaces WHERE id = 'new'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
            }
        }
    }
}
