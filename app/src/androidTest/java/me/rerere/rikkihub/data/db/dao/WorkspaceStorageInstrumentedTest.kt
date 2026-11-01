package me.rerere.rikkihub.data.db.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 Room/SQLite 仪器测试, 覆盖 JVM fake DAO 无法证明的部分:
 * - `workspaces` 父表定向 UPDATE / 事务 merge 在 CASCADE 子表存在时保子记录
 *   (而 INSERT OR REPLACE 会把父行 delete+insert, 级联删掉子记录);
 * - INSERT ... ABORT 对重复主键报错且不覆盖;
 * - `updateInTransaction` 拒绝身份/创建期字段修改;
 * - tool_enablement 损坏配置 fail-closed;
 * - v29 → v30 迁移: 新列默认 '{}' 且原配置全部保留.
 *
 * 需要真机/模拟器运行 (`./gradlew :app:connectedDebugAndroidTest`), 不在 JVM 单测覆盖范围.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceStorageInstrumentedTest {

    private val testDbName = "workspace-storage-test"

    // 用应用真实 SQLite factory（Requery + libsimple 扩展），不是 framework
    // SQLite：schema DDL/校验在 fork 的 OpenHelper 链路上跑。AutoMigration
    // 由 runMigrationsAndValidate 内部自动收集（Room getAutoMigrations），
    // specs 列表只承载手写 AutoMigrationSpec，这里为空。
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ),
    )

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkspaceDAO

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    /** 打开应用真实 factory 链路的 in-memory 库 (与 AppDatabaseFactory 同 OpenHelper) */
    private fun openInMemoryDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build().also {
        database = it
        dao = it.workspaceDao()
        // 防御: 若 OpenHelper 未按 Room onConfigure 打开 FK, 显式打开, 保证
        // 后续断言的是 FK-CASCADE 语义而不是「FK 根本没生效」
        it.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        it.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `test_workspace_children` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `workspace_id` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                FOREIGN KEY(`workspace_id`) REFERENCES `workspaces`(`id`) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun insertChild(workspaceId: String, id: String, value: String = "v") {
        val values = ContentValues().apply {
            put("id", id)
            put("workspace_id", workspaceId)
            put("value", value)
        }
        database.openHelper.writableDatabase
            .insert("test_workspace_children", SQLiteDatabase.CONFLICT_NONE, values)
    }

    private fun childCount(workspaceId: String): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM test_workspace_children WHERE workspace_id = ?",
            arrayOf(workspaceId),
        ).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private fun workspace(id: String, name: String = id) = WorkspaceEntity(
        id = id,
        name = name,
        root = id,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun insertAbortsOnDuplicateAndDoesNotReplaceRow() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        insertChild("w1", "c1")
        try {
            dao.insert(workspace("w1", name = "other"))
            fail("INSERT must abort on duplicate primary key")
        } catch (expected: Exception) {
            // SQLiteConstraintException (具体类型随 OpenHelper 实现)
        }
        // 原行未被 REPLACE: 名字不变, CASCADE 子记录还在
        assertEquals("w1", dao.getById("w1")!!.name)
        assertEquals(1, childCount("w1"))
    }

    @Test
    fun directedColumnUpdatesPreserveCascadeChildren() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        insertChild("w1", "c1")
        insertChild("w1", "c2")

        assertEquals(1, dao.updateName("w1", "renamed", 2L))
        assertEquals(1, dao.updateExposeUpload("w1", false, 3L))
        assertEquals(1, dao.updateExposeSkills("w1", false, 4L))
        assertEquals(1, dao.updateExposeToolOutputs("w1", false, 5L))
        assertEquals(1, dao.updateSubagentModelId("w1", "m", 6L))
        assertEquals(1, dao.updateSubagentAllowContext("w1", true, 7L))
        assertEquals(1, dao.setShellCompatibilityMode("w1", true, 8L))
        assertEquals(1, dao.updateShellStatus("w1", "READY", 9L))

        val row = dao.getById("w1")!!
        assertEquals("renamed", row.name)
        assertFalse(row.exposeUpload)
        assertFalse(row.exposeSkills)
        assertFalse(row.exposeToolOutputs)
        assertEquals("m", row.subagentModelId)
        assertTrue(row.subagentAllowContext)
        assertTrue(row.shellCompatibilityMode)
        assertEquals("READY", row.shellStatus)
        // 普通配置变更不删子记录
        assertEquals(2, childCount("w1"))
    }

    @Test
    fun transactionUpdatesAndJsonMergesPreserveCascadeChildren() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        insertChild("w1", "c1")

        assertNotNull(
            dao.updateInTransaction("w1") { it.copy(name = "tx", exposeUpload = false) }
        )
        assertTrue(dao.mergeToolApprovals("w1", mapOf("bash" to true, "subagent" to false), 10L))
        assertTrue(dao.mergeToolEnablement("w1", mapOf("workspace_shell" to false), 11L))

        val row = dao.getById("w1")!!
        assertEquals("tx", row.name)
        assertFalse(row.exposeUpload)
        assertEquals(mapOf("bash" to true, "subagent" to false), row.toolApprovalOverrides())
        assertEquals(
            mapOf("workspace_shell" to false),
            me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement.parse(row.toolEnablement),
        )
        assertEquals(1, childCount("w1"))
    }

    @Test
    fun updateInTransactionNoDiffOnlyTouchesUpdatedAt() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        insertChild("w1", "c1")
        dao.mergeToolApprovals("w1", mapOf("bash" to true), 5L)
        val before = dao.getById("w1")!!
        assertNotNull(dao.updateInTransaction("w1") { it })
        val row = dao.getById("w1")!!
        assertEquals(before.name, row.name)
        assertEquals(before.toolApprovals, row.toolApprovals)
        assertEquals(before.exposeUpload, row.exposeUpload)
        assertTrue(row.updatedAt != before.updatedAt)
        assertEquals(1, childCount("w1"))
    }

    @Test
    fun updateInTransactionWritesExplicitNulls() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1").copy(subagentModelId = "m1"))
        dao.updateLastAccessAt("w1", 42L, 6L)
        val updated = dao.updateInTransaction("w1") {
            it.copy(subagentModelId = null, lastAccessAt = null)
        }
        assertNotNull(updated)
        val row = dao.getById("w1")!!
        assertNull(row.subagentModelId)
        assertNull(row.lastAccessAt)
    }

    @Test
    fun updateInTransactionRejectsCorruptToolEnablementValue() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        try {
            dao.updateInTransaction("w1") { it.copy(toolEnablement = "{broken") }
            fail("corrupt tool_enablement write must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals("{}", dao.getById("w1")!!.toolEnablement)
    }

    @Test
    fun presetTargetedUpdatesAndTransactionMergePreserveChildrenAndMetadata() = runBlocking {
        openInMemoryDb()
        val original = workspace("w1").copy(toolEnablement = "{\"bash\":false}", toolApprovals = "{\"bash\":true}")
        dao.insert(original)
        insertChild("w1", "c1")
        val role = WorkspaceSubagentPreset(key = "worker", name = "Worker", description = "Implement changes", systemPrompt = "Work carefully")
        val json = WorkspaceSubagentPresets.encode(listOf(role))
        assertEquals(1, dao.updateSubagentPresetsJson("w1", json, 2L))
        assertEquals(1, childCount("w1"))
        val changedJson = WorkspaceSubagentPresets.encode(listOf(role.copy(name = "Renamed", version = 2L)))
        val saved = dao.updateInTransaction("w1") { it.copy(subagentPresets = changedJson) }!!
        assertEquals(original.copy(subagentPresets = changedJson, updatedAt = saved.updatedAt), dao.getById("w1"))
        assertEquals(1, childCount("w1"))
    }

    @Test
    fun changedCorruptPresetsRollbackButUnchangedCorruptionDoesNotBlockMetadata() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        insertChild("w1", "c1")
        val before = dao.getById("w1")
        val rejected = runCatching {
            dao.updateInTransaction("w1") { it.copy(name = "must-not-write", subagentPresets = "{broken") }
        }.exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        assertEquals(before, dao.getById("w1"))
        // Simulate pre-existing disk corruption, then verify unrelated transaction writes preserve it.
        dao.updateSubagentPresetsJson("w1", "{broken-presets", 2L)
        dao.updateInTransaction("w1") { it.copy(name = "Renamed", shellCompatibilityMode = true) }
        assertEquals("{broken-presets", dao.getById("w1")!!.subagentPresets)
        assertEquals("Renamed", dao.getById("w1")!!.name)
        assertTrue(dao.getById("w1")!!.shellCompatibilityMode)
        assertEquals(1, childCount("w1"))
    }

    @Test
    fun updateInTransactionRejectsIdentityFieldChanges() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        try {
            dao.updateInTransaction("w1") { it.copy(root = "other-root") }
            fail("updateInTransaction must reject identity field changes")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals("w1", dao.getById("w1")!!.root)
    }

    @Test
    fun insertOrReplaceWouldCascadeDeleteChildren() = runBlocking {
        // 反例验证: 证明子表 FK-CASCADE 真实生效, 且旧 upsert(REPLACE) 路径
        // 确实危险 —— 普通整行 REPLACE 会静默清掉子记录
        openInMemoryDb()
        dao.insert(workspace("w1"))
        insertChild("w1", "c1")
        val row = dao.getById("w1")!!
        val values = ContentValues().apply {
            put("id", row.id)
            put("name", "replaced")
            put("root", row.root)
            put("shell_status", row.shellStatus)
            put("created_at", row.createdAt)
            put("updated_at", row.updatedAt)
            put("tool_approvals", row.toolApprovals)
            put("tool_enablement", row.toolEnablement)
        }
        database.openHelper.writableDatabase
            .insert("workspaces", SQLiteDatabase.CONFLICT_REPLACE, values)
        assertEquals(0, childCount("w1"))
    }

    @Test
    fun deleteParentCascadesChildren() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        insertChild("w1", "c1")
        assertEquals(1, dao.deleteById("w1"))
        assertEquals(0, childCount("w1"))
    }

    @Test
    fun mergeToolEnablementFailsClosedOnCorruptStoredJson() = runBlocking {
        openInMemoryDb()
        dao.insert(workspace("w1"))
        // 直接写坏存储值, 模拟损坏
        assertEquals(1, dao.updateToolEnablementJson("w1", "not-json", 2L))
        try {
            dao.mergeToolEnablement("w1", mapOf("bash" to false), 3L)
            fail("corrupt tool_enablement must reject merge")
        } catch (expected: IllegalArgumentException) {
        }
        // 事务回滚: 坏值原样保留未被覆盖
        assertEquals("not-json", dao.getById("w1")!!.toolEnablement)
        try {
            dao.mergeToolEnablement("w1", mapOf("not_a_tool" to false), 4L)
            fail("unknown tool id must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun migrate29To30AddsToolEnablementWithDefaultAndPreservesConfig() {
        val db29 = helper.createDatabase(testDbName, 29)
        val values = ContentValues().apply {
            put("id", "w1")
            put("name", "migrated")
            put("root", "w1-root")
            put("shell_status", "READY")
            put("created_at", 111L)
            put("updated_at", 222L)
            put("tool_approvals", """{"workspace_shell":false}""")
            put("harness_minimal", 0)
            put("expose_upload", 0)
            put("expose_skills", 1)
            put("expose_tool_outputs", 1)
            put("subagent_model_id", "model-x")
            put("subagent_allow_context", 1)
            put("shell_compatibility_mode", 1)
        }
        db29.insert("workspaces", SQLiteDatabase.CONFLICT_NONE, values)
        db29.close()

        // AutoMigration(29,30) 由 RoomOpenHelper 自动收集, 无需手动传入
        val db30 = helper.runMigrationsAndValidate(testDbName, 30, true)

        db30.query(
            "SELECT tool_enablement, tool_approvals, name, expose_upload, " +
                "subagent_model_id, subagent_allow_context, shell_compatibility_mode " +
                "FROM workspaces WHERE id = ?",
            arrayOf("w1"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
            assertEquals("""{"workspace_shell":false}""", cursor.getString(1))
            assertEquals("migrated", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals("model-x", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
            assertEquals(1, cursor.getInt(6))
        }
        db30.close()
    }
}
