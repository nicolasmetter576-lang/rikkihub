package me.rerere.rikkihub.data.db.dao

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.data.db.entity.SubagentPresetLibraryEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Room 33 instrumentation acceptance, COMPILE-ONLY for the offline-core task. These cases require
 * a device/emulator to execute; adding/compiling them does NOT establish a passing migration run.
 * Schema 33 and the 32 -> 33 AutoMigration must be generated/exported by KSP, never hand-written.
 *
 * Unlike SubagentPresetRepositoryTest's in-memory snapshot rollback simulation, the last case
 * exercises actual Room nested DAO transactions inside ONE outer SQLite transaction. It proves
 * persistence rollback, not runtime-gate cancellation (that is covered by the offline suite).
 */
@RunWith(AndroidJUnit4::class)
class SubagentPresetLibraryMigrationInstrumentedTest {
    private val testDbName = "subagent-preset-library-migration-test"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: AppDatabase? = null

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(context),
    )

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(testDbName)
    }

    @Test
    fun migrate32to33PreservesValidAndCorruptPresetBytesConfigurationAndDeviceMountsWithoutAssignments() {
        context.deleteDatabase(testDbName)
        // Deliberate noncanonical whitespace, Unicode and omitted default fields: migration must
        // not parse/re-encode even healthy JSON, and must not silently repair corrupt JSON.
        val valid = """[
            { "id": "00000000-0000-4000-8000-000000000010", "key": "legacy_worker",
              "name": "Legacy é worker", "description": "Keep exactly", "systemPrompt": "Read only" }
        ]"""
        val corrupt = "  { corrupt preset JSON: é\n\t"
        val cases = linkedMapOf("valid" to valid, "corrupt" to corrupt)
        helper.createDatabase(testDbName, 32).use { db ->
            for ((id, rawPresets) in cases) {
                db.execSQL("""
                    INSERT INTO workspaces (
                        id, name, root, shell_status, created_at, updated_at, last_access_at,
                        tool_approvals, tool_enablement, harness_minimal,
                        expose_upload, expose_skills, expose_tool_outputs,
                        subagent_model_id, subagent_allow_context, subagent_presets, shell_compatibility_mode
                    ) VALUES (?, ?, ?, 'READY', 11, 22, 33,
                        '{"bash":true}', '{"bash":false,"subagent":false}', 1,
                        0, 0, 1, '00000000-0000-4000-8000-000000000001', 1, ?, 1)
                """.trimIndent(), arrayOf(id, "Legacy $id", "$id-root", rawPresets))
                db.execSQL("""
                    INSERT INTO workspace_device_mounts (
                        id, workspace_id, alias, source_relative_path, enabled, created_at, updated_at
                    ) VALUES (?, ?, 'docs', 'Documents', 1, 44, 55)
                """.trimIndent(), arrayOf("mount-$id", id))
            }
        }
        helper.runMigrationsAndValidate(testDbName, 33, true).use { db ->
            for ((id, rawPresets) in cases) {
                db.query("SELECT * FROM workspaces WHERE id = ?", arrayOf(id)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                    fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                    assertEquals(rawPresets, text("subagent_presets"))
                    assertArrayEquals(rawPresets.toByteArray(Charsets.UTF_8),
                        text("subagent_presets").toByteArray(Charsets.UTF_8))
                    assertEquals("Legacy $id", text("name"))
                    assertEquals("$id-root", text("root"))
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
                    assertFalse(cursor.moveToNext())
                }
                db.query("SELECT CAST(subagent_presets AS BLOB) FROM workspaces WHERE id = ?", arrayOf(id)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertArrayEquals(rawPresets.toByteArray(Charsets.UTF_8), cursor.getBlob(0))
                }
                db.query("SELECT workspace_id, alias, source_relative_path, enabled, created_at, updated_at " +
                    "FROM workspace_device_mounts WHERE id = ?", arrayOf("mount-$id")).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(id, cursor.getString(0))
                    assertEquals("docs", cursor.getString(1))
                    assertEquals("Documents", cursor.getString(2))
                    assertEquals(1, cursor.getInt(3))
                    assertEquals(44L, cursor.getLong(4))
                    assertEquals(55L, cursor.getLong(5))
                    assertFalse(cursor.moveToNext())
                }
            }
            db.query("SELECT COUNT(*) FROM subagent_preset_library").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0)) // Initialization belongs to the repository, never migration.
            }
            db.execSQL("""
                INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, tool_approvals)
                VALUES ('new', 'New workspace', 'new-root', 'DISABLED', 1, 1, '{}')
            """.trimIndent())
            db.query("SELECT subagent_presets FROM workspaces WHERE id = 'new'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0)) // No built-in source IDs or automatic assignment.
            }
            db.query("SELECT COUNT(*) FROM subagent_preset_library").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrateEmpty32to33CreatesEmptyTablesAndSqlSingletonDefaultsWithoutSeedingBuiltins() {
        context.deleteDatabase(testDbName)
        helper.createDatabase(testDbName, 32).close()
        helper.runMigrationsAndValidate(testDbName, 33, true).use { db ->
            for (table in listOf("workspaces", "workspace_device_mounts", "subagent_preset_library")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            // SQL defaults create a healthy EMPTY singleton; templates are not a SQL default.
            db.execSQL("INSERT INTO subagent_preset_library (id) VALUES (1)")
            db.query("SELECT id, presets, revision FROM subagent_preset_library").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("[]", cursor.getString(1))
                assertEquals(1L, cursor.getLong(2))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM workspaces").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private class RollbackSignal : IllegalStateException("Abort outer Room transaction after library CAS")

    @Test
    fun realRoomOuterTransactionRollsBackBothWorkspaceProjectionsAndCanonicalCas() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .build().also { database = it }
        val workspaces = db.workspaceDao()
        val library = db.subagentPresetLibraryDao()
        val source = WorkspaceSubagentPreset(key = "worker", name = "Worker",
            description = "Implement changes", systemPrompt = "Initial instructions")
        val originalLibrary = SubagentPresetLibraryEntity(presets = WorkspaceSubagentPresets.encode(listOf(source), 64))
        val originals = (1..2).map { index ->
            val id = UUID.randomUUID().toString()
            val projection = source.copy(id = UUID.randomUUID().toString(), libraryPresetId = source.id)
            WorkspaceEntity(id = id, name = "w$index", root = id, createdAt = 1, updatedAt = 1,
                subagentPresets = WorkspaceSubagentPresets.encode(listOf(projection)),
                toolApprovals = "{\"bash\":true}", toolEnablement = "{\"bash\":false}")
        }
        originals.forEach { workspaces.insert(it) }
        library.initialize(originalLibrary)
        // IGNORE initialization cannot replace an already-initialized empty or nonempty singleton.
        library.initialize(SubagentPresetLibraryEntity(presets = "[]", revision = 99))
        assertEquals(originalLibrary, library.read())
        val error = runCatching {
            db.withTransaction {
                for (row in originals) {
                    // These default DAO @Transaction methods nest into, rather than commit ahead of,
                    // the outer transaction. This is the production projector's write shape.
                    workspaces.updateInTransaction(row.id) { latest ->
                        val changed = WorkspaceSubagentPresets.parse(latest.subagentPresets).map {
                            it.copy(systemPrompt = "Not committed", version = 2, executionRevision = 2)
                        }
                        latest.copy(subagentPresets = WorkspaceSubagentPresets.encode(changed))
                    }
                }
                val nextLibrary = WorkspaceSubagentPresets.encode(
                    listOf(source.copy(systemPrompt = "Not committed", version = 2, executionRevision = 2)), 64)
                assertEquals(1, library.compareAndSet(nextLibrary, 2, originalLibrary.revision))
                assertEquals(2L, library.read()!!.revision)
                for (row in originals) {
                    assertEquals("Not committed", WorkspaceSubagentPresets.parse(workspaces.getById(row.id)!!.subagentPresets).single().systemPrompt)
                }
                throw RollbackSignal()
            }
        }.exceptionOrNull()
        assertTrue(error is RollbackSignal)
        assertEquals(originalLibrary, library.read())
        originals.forEach { assertEquals(it, workspaces.getById(it.id)) }
        // Failed CAS must not mutate the singleton, and a successful later CAS can reuse revision 1.
        assertEquals(0, library.compareAndSet("[]", 3, 2))
        assertEquals(originalLibrary, library.read())
        assertEquals(1, library.compareAndSet("[]", 2, 1))
        assertEquals(SubagentPresetLibraryEntity(presets = "[]", revision = 2), library.read())
        assertNull(workspaces.getById(UUID.randomUUID().toString()))
    }
}
