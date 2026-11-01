package me.rerere.rikkihub.data.ai.tools

import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tool_enablement 纯模型测试: 缺省启用 / 七稳定 ID / 损坏 fail-closed / 增量 merge.
 */
class WorkspaceToolEnablementTest {

    private fun entity(toolEnablement: String = "{}") = WorkspaceEntity(
        id = "w1",
        name = "w1",
        root = "w1",
        createdAt = 1L,
        updatedAt = 1L,
        toolEnablement = toolEnablement,
    )

    @Test
    fun stableIdsAreExactlySevenAcrossBothModes() {
        assertEquals(
            setOf(
                "workspace_read_file", "workspace_write_file", "workspace_edit_file",
                "workspace_shell", "subagent",
            ),
            WorkspaceToolEnablement.normalIds.toSet(),
        )
        assertEquals(
            setOf("bash", "str_replace_editor", "subagent"),
            WorkspaceToolEnablement.minimalIds.toSet(),
        )
        assertEquals(7, WorkspaceToolEnablement.configurableIds.size)
        assertEquals(WorkspaceToolEnablement.normalIds, WorkspaceToolEnablement.idsFor(false))
        assertEquals(WorkspaceToolEnablement.minimalIds, WorkspaceToolEnablement.idsFor(true))
    }

    @Test
    fun defaultsEnableEveryTool() {
        // 缺省迁移语义: 新列默认 "{}", 缺键即启用
        for (id in WorkspaceToolEnablement.configurableIds) {
            assertTrue("$id should default to enabled", entity().isToolEnabled(id))
            assertTrue(entity("{}").isToolEnabled(id))
        }
        assertEquals(emptyMap<String, Boolean>(), entity().toolEnablementOverrides())
    }

    @Test
    fun blankOrNullStorageIsCorruptNotDefault() {
        // 存储约定是非空 JSON 对象; null/blank 一律判损坏, 不容忍空串全开
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceToolEnablement.parse(null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceToolEnablement.parse("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceToolEnablement.parse("   ")
        }
        assertNull(WorkspaceToolEnablement.tryParse(null))
        assertNull(WorkspaceToolEnablement.tryParse(""))
        // 判定入口对 blank 存储 fail-closed
        assertFalse(entity("").isToolEnabled("bash"))
        assertFalse(entity("  ").isToolEnabled("workspace_shell"))
    }

    @Test
    fun unknownStableIdIsNeverEnabled() {
        // 可配置集合之外的 ID (即使是存在的内建工具) 一律 false, 不缺键全开
        assertFalse(entity().isToolEnabled("search_web"))
        assertFalse(entity().isToolEnabled("memory_tool"))
        assertFalse(entity().isToolEnabled("use_skill"))
        assertFalse(entity("""{"bash":false}""").isToolEnabled("eval_javascript"))
    }

    @Test
    fun explicitFalseDisablesAndTrueReenables() {
        val disabled = entity("""{"workspace_shell":false}""")
        assertFalse(disabled.isToolEnabled("workspace_shell"))
        assertTrue(disabled.isToolEnabled("workspace_read_file"))
        val enabled = entity("""{"bash":true}""")
        assertTrue(enabled.isToolEnabled("bash"))
    }

    @Test
    fun corruptConfigsFailClosed() {
        // 非 JSON
        assertNull(WorkspaceToolEnablement.tryParse("not-json"))
        assertFalse(entity("not-json").isToolEnabled("bash"))
        // 非对象
        assertNull(WorkspaceToolEnablement.tryParse("true"))
        assertNull(WorkspaceToolEnablement.tryParse("[]"))
        assertFalse(entity("[true]").isToolEnabled("bash"))
        // 未知键 (含范围外工具如 search_web/memory_tool)
        assertNull(WorkspaceToolEnablement.tryParse("""{"search_web":false}"""))
        assertFalse(entity("""{"search_web":false}""").isToolEnabled("bash"))
        assertFalse(entity("""{"search_web":false}""").isToolEnabled("subagent"))
        // 非 boolean 值
        assertNull(WorkspaceToolEnablement.tryParse("""{"bash":1}"""))
        assertNull(WorkspaceToolEnablement.tryParse("""{"bash":"false"}"""))
        assertNull(WorkspaceToolEnablement.tryParse("""{"bash":null}"""))
        assertFalse(entity("""{"bash":null}""").isToolEnabled("bash"))
        // 严格解析路径对损坏输入抛错而不是静默放行
        assertThrows(IllegalArgumentException::class.java) {
            entity("""{"bash":null}""").toolEnablementOverrides()
        }
    }

    @Test
    fun mergedJsonKeepsUntouchedKeys() {
        val merged = WorkspaceToolEnablement.mergedJson(
            """{"bash":false,"subagent":true}""",
            mapOf("workspace_shell" to false),
        )
        assertEquals(
            mapOf("bash" to false, "subagent" to true, "workspace_shell" to false),
            WorkspaceToolEnablement.parse(merged),
        )
    }

    @Test
    fun mergedJsonRejectsUnknownPatchKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceToolEnablement.mergedJson("{}", mapOf("eval_javascript" to false))
        }
    }

    @Test
    fun mergedJsonRejectsCorruptExisting() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceToolEnablement.mergedJson("{broken", mapOf("bash" to false))
        }
    }

    @Test
    fun validatedJsonEnforcesStableIds() {
        assertEquals("{}", WorkspaceToolEnablement.validatedJson(emptyMap()))
        val json = WorkspaceToolEnablement.validatedJson(
            mapOf("workspace_shell" to false, "bash" to true),
        )
        assertEquals(
            mapOf("workspace_shell" to false, "bash" to true),
            WorkspaceToolEnablement.parse(json),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceToolEnablement.validatedJson(mapOf("not_a_tool" to false))
        }
    }
}
