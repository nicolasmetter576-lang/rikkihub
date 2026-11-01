package me.rerere.rikkihub.data.sync

import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.ai.tools.isToolEnabled
import me.rerere.rikkihub.data.ai.tools.toolEnablementOverrides
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 备份 artifacts 的 JVM 序列化/边界契约测试（T0~T3 支撑）：
 *
 * - `settings.json` artifact：`Settings` 序列化 round-trip 与旧备份缺省字段解码；
 * - DB 快照 artifact 中 `workspaces.tool_enablement` 的序列化契约：
 *   迁移默认值 `"{}"`（缺键启用映射）经备份 JSON 编解码往返不漂移，
 *   显式禁用映射 round-trip 保持；
 * - 文件行（file rows）payload：`payload/files/<folder>/<rel>` 只允许
 *   upload/skills/fonts 三个受管目录，路径逃逸一律拒绝；
 * - 快照旁挂文件清理：未完全 checkpoint 的 WAL 必须显式拒绝。
 *
 * `BackupManager` 本身需要 Android Context/Room，只能设备测试；
 * 本文件固定其可离线验证的序列化与路径契约。
 */
class BackupSerializationContractTest {

    @get:Rule
    val temporary = TemporaryFolder()

    // ---------- settings.json artifact ----------

    @Test
    fun `settings artifact round trips including tool overrides`() {
        val original = Settings(
            webServerEnabled = true,
            toolOverrides = mapOf(
                BuiltinToolIds.WORKSPACE_SHELL to ToolOverride(
                    name = "custom_shell",
                    description = "d",
                ),
            ),
        )
        val encoded = JsonInstant.encodeToString(original)
        // 恢复路径先过迁移器再解码：迁移器对已是当前格式的 JSON 必须是无损透传
        val migrated = SettingsJsonMigrator.migrate(encoded)
        val decoded = JsonInstant.decodeFromString<Settings>(migrated)
        assertEquals(original.toolOverrides, decoded.toolOverrides)
        assertTrue(decoded.webServerEnabled)
        assertEquals(original.providers.size, decoded.providers.size)
    }

    @Test
    fun `legacy settings artifact without fork fields decodes to defaults`() {
        // 上游旧备份：没有 toolOverrides / workspace 相关 fork 字段
        val decoded = JsonInstant.decodeFromString<Settings>(
            SettingsJsonMigrator.migrate("""{"webServerEnabled":true}"""),
        )
        assertTrue(decoded.webServerEnabled)
        assertEquals(emptyMap<String, ToolOverride>(), decoded.toolOverrides)
        assertTrue(decoded.assistants.isNotEmpty())
    }

    // ---------- workspaces.tool_enablement 序列化契约（DB 快照内容） ----------

    private fun entity(toolEnablement: String) = WorkspaceEntity(
        id = "w1",
        name = "w1",
        root = "w1",
        createdAt = 1L,
        updatedAt = 1L,
        toolEnablement = toolEnablement,
    )

    @Test
    fun `migration default enables every configurable tool`() {
        // AutoMigration(29,30) 写入的列默认值就是 "{}"；恢复出来的行原样携带该值
        val restored = entity("{}")
        assertEquals(emptyMap<String, Boolean>(), restored.toolEnablementOverrides())
        for (id in WorkspaceToolEnablement.configurableIds) {
            assertTrue("$id must default to enabled in restored rows", restored.isToolEnabled(id))
        }
    }

    @Test
    fun `full enablement mapping serializes and parses back identically`() {
        val all = WorkspaceToolEnablement.configurableIds.associateWith { true }
        val disabledShell = all + (BuiltinToolIds.WORKSPACE_SHELL to false)
        val stored = WorkspaceToolEnablement.validatedJson(disabledShell)
        // 模拟备份快照→恢复后原样读回：parse(validatedJson(x)) == x
        assertEquals(disabledShell, WorkspaceToolEnablement.parse(stored))
        val restored = entity(stored)
        assertFalse(restored.isToolEnabled(BuiltinToolIds.WORKSPACE_SHELL))
        assertTrue(restored.isToolEnabled(BuiltinToolIds.BASH))
    }

    @Test
    fun `incremental merge on restored row preserves untouched keys`() {
        val before = WorkspaceToolEnablement.validatedJson(
            mapOf(BuiltinToolIds.SUBAGENT to false),
        )
        val merged = WorkspaceToolEnablement.mergedJson(
            before,
            mapOf(BuiltinToolIds.WORKSPACE_SHELL to false),
        )
        assertEquals(
            mapOf(
                BuiltinToolIds.SUBAGENT to false,
                BuiltinToolIds.WORKSPACE_SHELL to false,
            ),
            WorkspaceToolEnablement.parse(merged),
        )
    }

    @Test
    fun `corrupt enablement in restored row fails closed not silently enabled`() {
        // 备份里手工篡改过的值不能静默放行：严格解码抛错，isToolEnabled 全拒
        for (bad in listOf("not-json", "[]", """{"bash":1}""", """{"search_web":false}""")) {
            assertNull(WorkspaceToolEnablement.tryParse(bad))
            val restored = entity(bad)
            assertFalse(restored.isToolEnabled(BuiltinToolIds.BASH))
            assertFalse(restored.isToolEnabled(BuiltinToolIds.SUBAGENT))
        }
    }

    // ---------- payload/files 行边界 ----------

    @Test
    fun `backup file rows stay inside their managed folder`() {
        val filesDir = File(temporary.root, "files")
        val uploadRow = PendingRestore.resolveInside(filesDir, "${FileFolders.UPLOAD}/photo.png")
        assertTrue(uploadRow.path.startsWith(filesDir.canonicalPath + File.separator))
        // skills 是唯一允许嵌套相对路径的目录
        val nestedSkill = PendingRestore.resolveInside(
            filesDir,
            "${FileFolders.SKILLS}/a/b/SKILL.md",
        )
        assertEquals(
            File(filesDir, "${FileFolders.SKILLS}/a/b/SKILL.md").canonicalPath,
            nestedSkill.path,
        )
    }

    @Test
    fun `backup file rows reject traversal and absolute paths`() {
        val filesDir = File(temporary.root, "files")
        for (rel in listOf(
            "../escape",
            "${FileFolders.UPLOAD}/../databases/rikka_hub",
            "/absolute",
            "a\\..\\b",
            "${FileFolders.SKILLS}/./x",
            "",
        )) {
            assertThrows("must reject: $rel", IllegalArgumentException::class.java) {
                PendingRestore.resolveInside(filesDir, rel)
            }
        }
    }

    @Test
    fun `restore target namespacing keeps database and files apart`() {
        // payload 下的 database/* 与 files/* 是两套独立命名空间；
        // files/ 前缀行绝不能落到 databaseFile 侧（resolveInside 只解析 files 子树）
        val filesDir = File(temporary.root, "files").apply { mkdirs() }
        val target = PendingRestore.resolveInside(filesDir, "upload/x")
        assertFalse(target.path.contains("databases"))
    }

    // ---------- 快照旁挂文件 ----------

    @Test
    fun `removeSidecars fails when WAL is not fully checkpointed`() {
        val db = File(temporary.root, DatabaseBackup.ARCHIVE_DATABASE)
        db.writeText("db")
        File(db.path + "-wal").writeText("uncommitted")
        assertThrows(IllegalStateException::class.java) {
            DatabaseBackup.removeSidecars(db)
        }
    }

    @Test
    fun `removeSidecars deletes empty sidecars`() {
        val db = File(temporary.root, DatabaseBackup.ARCHIVE_DATABASE)
        db.writeText("db")
        File(db.path + "-wal").writeBytes(ByteArray(0))
        File(db.path + "-shm").writeText("shm")
        DatabaseBackup.removeSidecars(db)
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
    }
}
