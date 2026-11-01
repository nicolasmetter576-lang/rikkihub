package me.rerere.rikkihub.data.db.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `workspace_device_mounts`（v30 → v31 AutoMigration）仪器测试。
 *
 * 覆盖：
 * - 迁移后表结构/FK-CASCADE/UNIQUE(workspace_id, alias) 生效；
 * - 旧 v30 库升级后 mount 表为空且父表数据完好；
 * - 父表定向 UPDATE/事务 merge 保子记录；REPLACE 父行级联删子记录（回归
 *   [WorkspaceStorageInstrumentedTest] 同契约，现在针对真实子表）；
 * - 删除父工作区级联删连接；服务侧 delete 只删配置不碰宿主目录由 JVM/领域
 *   测试负责，不在本测试范围。
 *
 * 需真机/模拟器（`./gradlew :app:connectedDebugAndroidTest`），不在 JVM 单测。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceDeviceMountMigrationInstrumentedTest {

    private val testDbName = "workspace-device-mount-migration-test"

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

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun openInMemoryDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build().also {
        database = it
        it.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
    }

    private fun insertWorkspace(id: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("name", id)
            put("root", id)
            put("shell_status", "DISABLED")
            put("created_at", 1L)
            put("updated_at", 1L)
            put("tool_approvals", "{}")
            put("tool_enablement", "{}")
            put("harness_minimal", 0)
            put("expose_upload", 1)
            put("expose_skills", 1)
            put("expose_tool_outputs", 1)
            put("subagent_allow_context", 0)
            put("shell_compatibility_mode", 0)
        }
        database.openHelper.writableDatabase
            .insert("workspaces", SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun mountCount(workspaceId: String): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM workspace_device_mounts WHERE workspace_id = ?",
            arrayOf(workspaceId),
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun migrate30to31CreatesMountTable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(testDbName)
        helper.createDatabase(testDbName, 30).use { db ->
            val values = ContentValues().apply {
                put("id", "w30")
                put("name", "w30")
                put("root", "w30")
                put("shell_status", "DISABLED")
                put("created_at", 1L)
                put("updated_at", 1L)
                put("tool_approvals", "{}")
                put("tool_enablement", "{}")
                put("harness_minimal", 0)
                put("expose_upload", 1)
                put("expose_skills", 1)
                put("expose_tool_outputs", 1)
                put("subagent_allow_context", 0)
                put("shell_compatibility_mode", 0)
            }
            db.insert("workspaces", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        helper.runMigrationsAndValidate(testDbName, 31, true).use { db ->
            db.query("SELECT COUNT(*) FROM workspace_device_mounts").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT name FROM workspaces WHERE id = 'w30'").use { c ->
                assertTrue(c.moveToFirst())
            }
        }
    }

    @Test
    fun mountInsertAndCascadeDeleteFollowWorkspaceParent() = runBlocking {
        openInMemoryDb()
        val dao = database.workspaceDao()
        val mountDao = database.workspaceDeviceMountDao()
        dao.insert(
            WorkspaceEntity(
                id = "w1", name = "w1", root = "w1",
                createdAt = 1L, updatedAt = 1L,
            )
        )
        val entity = me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity(
            id = "m1",
            workspaceId = "w1",
            alias = "photos",
            sourceRelativePath = "DCIM/Camera",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        mountDao.insert(entity)
        assertEquals(1, mountCount("w1"))

        // 定向父表更新不删子记录
        assertEquals(1, dao.updateName("w1", "renamed", 2L))
        assertEquals(1, mountCount("w1"))

        // 删除父工作区 → 级联删配置
        dao.deleteById("w1")
        assertEquals(0, mountCount("w1"))
    }

    @Test
    fun mountAliasUniqueWithinWorkspace() = runBlocking {
        openInMemoryDb()
        insertWorkspace("w1")
        val mountDao = database.workspaceDeviceMountDao()
        mountDao.insert(
            me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity(
                id = "m1", workspaceId = "w1", alias = "docs",
                sourceRelativePath = "Documents", enabled = true,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        try {
            mountDao.insert(
                me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity(
                    id = "m2", workspaceId = "w1", alias = "docs",
                    sourceRelativePath = "Download", enabled = true,
                    createdAt = 1L, updatedAt = 1L,
                )
            )
            fail("duplicate alias in one workspace must abort")
        } catch (expected: Exception) {
        }
        assertEquals(1, mountCount("w1"))
    }

    @Test
    fun replaceOnParentWouldCascadeDeleteMounts() = runBlocking {
        // 反例：INSERT OR REPLACE 父行会 delete+insert，把真实子表记录级联清掉。
        // 服务侧只允许 ABORT/定向 UPDATE（见 WorkspaceDeviceMountDAO 契约）。
        openInMemoryDb()
        insertWorkspace("w1")
        val mountDao = database.workspaceDeviceMountDao()
        mountDao.insert(
            me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity(
                id = "m1", workspaceId = "w1", alias = "docs",
                sourceRelativePath = "Documents", enabled = true,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        val values = ContentValues().apply {
            put("id", "w1"); put("name", "replaced"); put("root", "w1")
            put("shell_status", "DISABLED"); put("created_at", 1L); put("updated_at", 2L)
            put("tool_approvals", "{}"); put("tool_enablement", "{}")
            put("harness_minimal", 0); put("expose_upload", 1); put("expose_skills", 1)
            put("expose_tool_outputs", 1); put("subagent_allow_context", 0)
            put("shell_compatibility_mode", 0)
        }
        database.openHelper.writableDatabase
            .insert("workspaces", SQLiteDatabase.CONFLICT_REPLACE, values)
        assertEquals(0, mountCount("w1"))
        assertNotNull(database.workspaceDao().getById("w1"))
    }
}
