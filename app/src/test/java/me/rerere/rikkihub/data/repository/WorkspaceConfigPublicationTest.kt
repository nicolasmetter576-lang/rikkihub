package me.rerere.rikkihub.data.repository

import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.workspace.WorkspaceChangeSpec
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workspaceConfigChangeSpec 的纯配置单测。
 *
 * 不触碰 DAO / gate / Room：只验证「current → next 两版实体」被归到哪类 spec：
 * - 结构差异（root/shellStatus/暴露挂载/shell_compatibility_mode/harness_minimal）
 *   → structural()：波及全部在册项、双 epoch bump；
 * - tool_enablement 有效开关差分 → toolPolicy(affected)：只 bump policy epoch，
 *   只吊销发生翻转的 stableId；
 * - 名称/审批/subagent 配置/等价 enablement JSON 等「策略外形不变」差异
 *   → toolPolicy(emptySet)：bump policy epoch 但不取消任何操作；
 * - 真正零差异 / 仅 lastAccessAt → null（gate 走 metadata 直通，不动 epoch）。
 *
 * 非法 next 配置（损坏 tool_enablement）必须在 spec 阶段抛错，发布层据此无 side effect。
 */
class WorkspaceConfigPublicationTest {

    private fun ws(
        id: String = "w1",
        name: String = id,
        root: String = id,
        shellStatus: String = WorkspaceShellStatus.READY.name,
        toolEnablement: String = "{}",
        toolApprovals: String = "{}",
        harnessMinimal: Boolean = false,
        exposeUpload: Boolean = true,
        exposeSkills: Boolean = true,
        exposeToolOutputs: Boolean = true,
        subagentModelId: String? = null,
        subagentAllowContext: Boolean = false,
        shellCompatibilityMode: Boolean = false,
        lastAccessAt: Long? = null,
        updatedAt: Long = 1L,
    ) = WorkspaceEntity(
        id = id,
        name = name,
        root = root,
        shellStatus = shellStatus,
        createdAt = 1L,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
        toolApprovals = toolApprovals,
        toolEnablement = toolEnablement,
        harnessMinimal = harnessMinimal,
        exposeUpload = exposeUpload,
        exposeSkills = exposeSkills,
        exposeToolOutputs = exposeToolOutputs,
        subagentModelId = subagentModelId,
        subagentAllowContext = subagentAllowContext,
        shellCompatibilityMode = shellCompatibilityMode,
    )

    private fun assertStructural(spec: WorkspaceChangeSpec?) {
        requireNotNull(spec) { "expected structural spec" }
        assertNull(spec.affectedStableIds)
        assertTrue(spec.bumpPolicyEpoch)
        assertTrue(spec.bumpViewEpoch)
    }

    private fun assertToolPolicy(spec: WorkspaceChangeSpec?, affected: Set<String>) {
        requireNotNull(spec) { "expected toolPolicy spec" }
        assertEquals(affected, spec.affectedStableIds)
        assertTrue(spec.bumpPolicyEpoch)
        assertFalse(spec.bumpViewEpoch)
    }

    // ------------------------------------------------- 结构差异 → structural

    @Test
    fun `shell status change is structural`() {
        assertStructural(
            workspaceConfigChangeSpec(
                ws(shellStatus = WorkspaceShellStatus.READY.name),
                ws(shellStatus = WorkspaceShellStatus.BROKEN.name),
            )
        )
    }

    @Test
    fun `exposed mount and compat mode changes are structural`() {
        assertStructural(workspaceConfigChangeSpec(ws(), ws(exposeUpload = false)))
        assertStructural(workspaceConfigChangeSpec(ws(), ws(exposeSkills = false)))
        // /tool_outputs 与 /subagent_runs 共用一个开关
        assertStructural(workspaceConfigChangeSpec(ws(), ws(exposeToolOutputs = false)))
        assertStructural(workspaceConfigChangeSpec(ws(), ws(shellCompatibilityMode = true)))
        // spec 函数自身对 root 差异也按结构处理（repo 层先用 require 拒绝身份变更）
        assertStructural(workspaceConfigChangeSpec(ws(), ws(root = "other")))
    }

    // ------------------------------------------------- enablement 差分 → toolPolicy(affected)

    @Test
    fun `disable single tool yields its stable id as victim set`() {
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(),
                ws(toolEnablement = """{"workspace_shell":false}"""),
            ),
            setOf(BuiltinToolIds.WORKSPACE_SHELL),
        )
    }

    @Test
    fun `reenable tool still yields it as affected`() {
        // disable → enable 不是 no-op：旧实例要因该 stableId 吊销代数 +1 而被拒
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(toolEnablement = """{"workspace_shell":false}"""),
                ws(),
            ),
            setOf(BuiltinToolIds.WORKSPACE_SHELL),
        )
    }

    @Test
    fun `multi tool diff reports exactly the flipped ids`() {
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(),
                ws(toolEnablement = """{"bash":false,"subagent":false}"""),
            ),
            setOf(BuiltinToolIds.BASH, BuiltinToolIds.SUBAGENT),
        )
    }

    @Test
    fun `unaffected disabled keys are kept out of victim set`() {
        // shell 保持禁用、subagent 从显式禁恢复为启用 → victim 只有 subagent
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(toolEnablement = """{"workspace_shell":false,"subagent":false}"""),
                ws(toolEnablement = """{"workspace_shell":false}"""),
            ),
            setOf(BuiltinToolIds.SUBAGENT),
        )
    }

    @Test
    fun `cross mode id diff still counts as policy change`() {
        // normal 工作区改 minimal 模式 ID：configurableIds 是并集，仍算受影响
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(),
                ws(toolEnablement = """{"bash":false}"""),
            ),
            setOf(BuiltinToolIds.BASH),
        )
    }

    // ------------------------------------------------- 等价/元数据差异 → toolPolicy(emptySet)

    @Test
    fun `explicit true key equivalent to missing is policy only no victims`() {
        // "{}" → {"bash":true}：有效开关没有翻转，但存储 JSON 变了：
        // 仍走 policy 通道 bump policy epoch（失效旧快照），victim 为空不取消任何操作
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(),
                ws(toolEnablement = """{"bash":true}"""),
            ),
            emptySet(),
        )
    }

    @Test
    fun `metadata fields produce empty affected set`() {
        assertToolPolicy(
            workspaceConfigChangeSpec(ws(), ws(name = "renamed")),
            emptySet(),
        )
        assertToolPolicy(
            workspaceConfigChangeSpec(ws(), ws(toolApprovals = """{"workspace_shell":true}""")),
            emptySet(),
        )
        assertToolPolicy(
            workspaceConfigChangeSpec(ws(), ws(subagentModelId = "m-1")),
            emptySet(),
        )
        // 首条消息文件列表只影响之后发送的消息，不取消在途工具、不重启 shell
        assertToolPolicy(
            workspaceConfigChangeSpec(ws(), ws().copy(firstMessageFileSnapshot = true)),
            emptySet(),
        )
        assertToolPolicy(
            workspaceConfigChangeSpec(ws(), ws().copy(firstMessageFileSnapshotDepth = 3)),
            emptySet(),
        )
        assertToolPolicy(
            workspaceConfigChangeSpec(ws(), ws(subagentAllowContext = true)),
            emptySet(),
        )
    }

    // ------------------------------------------------- preset publication and revocation union

    private fun preset() = WorkspaceSubagentPreset(
        key = "worker", name = "Worker", description = "Implement changes", systemPrompt = "Work carefully",
    )

    @Test
    fun `every preset JSON change invalidates requests without necessarily revoking executions`() {
        val role = preset()
        val before = ws().copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(role)))
        for (edited in listOf(
            role.copy(name = "Renamed"), role.copy(description = "New description"),
            role.copy(sortOrder = 3), role.copy(systemPrompt = "New execution instructions"),
        )) {
            assertToolPolicy(workspaceConfigChangeSpec(
                before, before.copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(edited))),
            ), emptySet())
        }
        assertToolPolicy(workspaceConfigChangeSpec(ws(), before), emptySet())
        // Even equivalent JSON formatting is a request policy change.
        assertToolPolicy(workspaceConfigChangeSpec(before, before.copy(subagentPresets = " \n" + before.subagentPresets)), emptySet())
    }

    @Test
    fun `enabled preset disable removal and context reduction revoke subagent`() {
        val role = preset()
        val before = ws().copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(role)))
        for (after in listOf(emptyList(), listOf(role.copy(enabled = false)), listOf(role.copy(acceptContext = false)))) {
            assertToolPolicy(workspaceConfigChangeSpec(
                before, before.copy(subagentPresets = WorkspaceSubagentPresets.encode(after)),
            ), setOf(BuiltinToolIds.SUBAGENT))
        }
        val disabled = before.copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(role.copy(enabled = false))))
        assertToolPolicy(workspaceConfigChangeSpec(disabled, disabled.copy(subagentPresets = "[]")), emptySet())
    }

    @Test
    fun `workspace context tightening revokes only for nonempty preset catalog`() {
        val before = ws(subagentAllowContext = true).copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(preset())))
        assertToolPolicy(workspaceConfigChangeSpec(before, before.copy(subagentAllowContext = false)), setOf(BuiltinToolIds.SUBAGENT))
        assertToolPolicy(workspaceConfigChangeSpec(ws(subagentAllowContext = true), ws()), emptySet())
        assertToolPolicy(workspaceConfigChangeSpec(before, before.copy(
            subagentAllowContext = false, toolEnablement = "{\"bash\":false}",
        )), setOf(BuiltinToolIds.SUBAGENT, BuiltinToolIds.BASH))
    }

    @Test
    fun `preset revocations union with all changed tool IDs`() {
        val before = ws().copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(preset())))
        val after = before.copy(subagentPresets = "[]", toolEnablement = "{\"bash\":false,\"workspace_shell\":false}")
        assertToolPolicy(workspaceConfigChangeSpec(before, after), setOf(
            BuiltinToolIds.SUBAGENT, BuiltinToolIds.BASH, BuiltinToolIds.WORKSPACE_SHELL,
        ))
    }

    @Test
    fun `corrupt preset repair revokes subagent but unchanged corruption survives metadata edits`() {
        val before = ws().copy(subagentPresets = "{broken-presets")
        assertToolPolicy(workspaceConfigChangeSpec(before, before.copy(subagentPresets = "[]")), setOf(BuiltinToolIds.SUBAGENT))
        assertToolPolicy(workspaceConfigChangeSpec(before, before.copy(name = "new-name")), emptySet())
        assertNull(workspaceConfigChangeSpec(before, before.copy(lastAccessAt = 9L)))
        assertTrue(runCatching {
            workspaceConfigChangeSpec(ws(), ws().copy(subagentPresets = "{broken-presets"))
        }.exceptionOrNull() is IllegalArgumentException)
    }

    // ------------------------------------------------- 零差异 → null

    @Test
    fun `identical entities produce null spec`() {
        assertNull(workspaceConfigChangeSpec(ws(), ws()))
        // 仅 updatedAt 差异（业务零变化）同样 null
        assertNull(workspaceConfigChangeSpec(ws(), ws(updatedAt = 99L)))
    }

    @Test
    fun `lastAccessAt only change produces null spec`() {
        // lastAccessAt 是纯元数据：不 bump epoch、不发事件
        assertNull(workspaceConfigChangeSpec(ws(), ws(lastAccessAt = 7L)))
    }

    // ------------------------------------------------- 损坏配置语义

    @Test
    fun `corrupt current plus repair invalidates every stable id`() {
        // 损坏配置下没有任何可用工具；修复为合法 "{}" 后全部翻转 → 波及全部 stableId
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(toolEnablement = "not-json"),
                ws(),
            ),
            WorkspaceToolEnablement.configurableIds,
        )
    }

    @Test
    fun `corrupt next throws instead of producing a spec`() {
        val error = runCatching {
            workspaceConfigChangeSpec(ws(), ws(toolEnablement = "{broken"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `corrupt current plus partial repair invalidates only revived ids`() {
        // 修复为 {"bash":false}：bash 在旧（全禁）新（显式禁）都为 off → 不翻转；
        // 其余 6 个 ID 从不可用翻回启用 → 全部进 victim 集
        val expected = WorkspaceToolEnablement.configurableIds - BuiltinToolIds.BASH
        assertToolPolicy(
            workspaceConfigChangeSpec(
                ws(toolEnablement = "not-json"),
                ws(toolEnablement = """{"bash":false}"""),
            ),
            expected,
        )
    }

    @Test
    fun `corrupt next throws even when only metadata changed`() {
        // 任何写出损坏 enablement 的 patch 都在 spec 阶段失败，连重命名也不放行
        val error = runCatching {
            workspaceConfigChangeSpec(ws(), ws(name = "renamed", toolEnablement = "{broken"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
