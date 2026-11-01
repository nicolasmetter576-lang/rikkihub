package me.rerere.rikkihub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.WorkspaceToolWirePolicy
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolMode
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatToolSnapshot 组装件测试：精确 wireName→Tool 映射、重名显式异常、
 * 禁用别名表与冲突校验（不经 ChatToolFactory 构造，避开 Android 依赖）。
 */
class ChatToolSnapshotTest {

    private fun tool(name: String) = Tool(
        name = name,
        description = "d",
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private fun workspace(
        toolEnablement: String = "{}",
        shellStatus: String = WorkspaceShellStatus.READY.name,
        harnessMinimal: Boolean = false,
    ) = WorkspaceEntity(
        id = "w1",
        name = "w1",
        root = "w1",
        shellStatus = shellStatus,
        createdAt = 1L,
        updatedAt = 1L,
        toolEnablement = toolEnablement,
        harnessMinimal = harnessMinimal,
    )

    // ---------- buildToolBindings ----------

    @Test
    fun bindingsMapExactWireNamesToTools() {
        val wsTool = tool("renamed_shell").copy(
            workspaceIdentity = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_SHELL),
        )
        val plain = tool("eval_javascript")
        val bindings = buildToolBindings(listOf(wsTool, plain))
        assertEquals(2, bindings.size)
        // identity 挂在 Tool 上随 copy 保留，映射按最终 wire 名
        assertEquals(
            BuiltinToolIds.WORKSPACE_SHELL,
            bindings["renamed_shell"]!!.workspaceIdentity!!.stableId,
        )
        assertNull(bindings["eval_javascript"]!!.workspaceIdentity)
    }

    @Test
    fun duplicateWireNameThrows() {
        assertThrows(DuplicateToolWireNameException::class.java) {
            buildToolBindings(listOf(tool("bash"), tool("bash")))
        }
        // 改名撞名同样冲突（workspace 工具 rename 后撞上 local 工具）
        val renamed = tool("get_time_info").copy(
            workspaceIdentity = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_SHELL),
        )
        assertThrows(DuplicateToolWireNameException::class.java) {
            buildToolBindings(listOf(tool("get_time_info"), renamed))
        }
    }

    // ---------- forbiddenWorkspaceToolNames ----------

    @Test
    fun forbiddenNamesCoverDisabledAndInactiveMode() {
        // normal 模式、workspace_shell 被禁：禁用别名 = workspace_shell；
        // 非本模式（harness_minimal）的 bash/str_replace_editor 也一律进禁用面
        val forbidden = forbiddenWorkspaceToolNames(
            workspace(toolEnablement = """{"workspace_shell":false}"""),
            emptyMap(),
        )
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, forbidden[BuiltinToolIds.WORKSPACE_SHELL])
        assertEquals(BuiltinToolIds.BASH, forbidden[BuiltinToolIds.BASH])
        assertEquals(BuiltinToolIds.STR_REPLACE_EDITOR, forbidden[BuiltinToolIds.STR_REPLACE_EDITOR])
        // 启用的工具不在禁用面
        assertTrue(BuiltinToolIds.WORKSPACE_READ_FILE !in forbidden.keys)
        // subagent 默认启用，不进禁用面
        assertTrue(BuiltinToolIds.SUBAGENT !in forbidden.keys)
    }

    @Test
    fun forbiddenNamesResolveOverrides() {
        // 禁用工具被改名后，稳定原名与 Override 生效名**同时**进禁用面：
        // customBody/choice 可用任一形态把禁用工具写回 wire，不能用 associate
        // 只记一个别名、更不能让覆盖把另一个 ID 的禁用名悄悄顶掉。
        val overrides = mapOf(
            BuiltinToolIds.WORKSPACE_SHELL to ToolOverride(name = "run_cmd"),
        )
        val forbidden = forbiddenWorkspaceToolNames(
            workspace(toolEnablement = """{"workspace_shell":false}"""),
            overrides,
        )
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, forbidden["run_cmd"])
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, forbidden[BuiltinToolIds.WORKSPACE_SHELL])
    }

    @Test
    fun duplicateDisabledAliasThrows() {
        // 两个不同 stableId 的 Override 解析出同一 wire 名 → 显式冲突报错，
        // 不静默覆盖隐藏其中一个禁用面。
        val overrides = mapOf(
            BuiltinToolIds.WORKSPACE_SHELL to ToolOverride(name = "shared_alias"),
            BuiltinToolIds.BASH to ToolOverride(name = "shared_alias"),
        )
        assertThrows(ForbiddenWorkspaceToolNameException::class.java) {
            forbiddenWorkspaceToolNames(
                workspace(toolEnablement = """{"workspace_shell":false}"""),
                overrides,
            )
        }
    }

    @Test
    fun workspaceSliceAppliesOverrideExactlyOnce() {
        // Override 名恰为另一 builtin 的稳定 ID：切片内只允许应用一次 Override——
        // get_time_info 这个名字不得再被 get_time_info 的 spec 二次改名重写。
        val overrides = mapOf(
            BuiltinToolIds.WORKSPACE_SHELL to ToolOverride(name = BuiltinToolIds.GET_TIME_INFO),
            BuiltinToolIds.GET_TIME_INFO to ToolOverride(name = "renamed_clock"),
        )
        val snap = createWorkspaceToolSnapshot(
            workspace = workspace(),
            workspaceRepository = null,
            subagentHost = null,
            includeSubagent = false,
            toolRunner = null,
            loadWorkspace = { workspace() },
            overrides = overrides,
        )
        val renamed = snap.tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.WORKSPACE_SHELL }
        assertEquals(
            "切片工具的 wire 名只能是本工具 Override 的结果，不得二次套用别 spec",
            BuiltinToolIds.GET_TIME_INFO,
            renamed.name,
        )
        // capabilities 的 wire 名与最终 tools 同源（stableId→发出名）
        assertEquals(
            BuiltinToolIds.GET_TIME_INFO,
            snap.capabilities.availableToolNames[BuiltinToolIds.WORKSPACE_SHELL],
        )
    }

    @Test
    fun minimalModeForbidsNormalTools() {
        val forbidden = forbiddenWorkspaceToolNames(
            workspace(harnessMinimal = true),
            emptyMap(),
        )
        for (id in WorkspaceToolEnablement.normalIds) {
            // subagent 在两个模式都可配置且默认启用，不是「模式外」禁用名
            if (id == BuiltinToolIds.SUBAGENT) continue
            assertTrue("$id must be forbidden in minimal mode", id in forbidden.keys)
        }
        // minimal 模式启用的不在禁用面
        assertTrue(BuiltinToolIds.BASH !in forbidden.keys)
        assertTrue(BuiltinToolIds.STR_REPLACE_EDITOR !in forbidden.keys)
        assertTrue(BuiltinToolIds.SUBAGENT !in forbidden.keys)
    }

    @Test
    fun nonReadyOrCorruptConfigForbidsEverything() {
        val nonReady = forbiddenWorkspaceToolNames(
            workspace(shellStatus = WorkspaceShellStatus.BROKEN.name),
            emptyMap(),
        )
        assertEquals(WorkspaceToolEnablement.configurableIds, nonReady.values.toSet())

        val corrupt = forbiddenWorkspaceToolNames(
            workspace(toolEnablement = "{broken"),
            emptyMap(),
        )
        assertEquals("损坏配置 fail-closed：全部 ID 进禁用面", WorkspaceToolEnablement.configurableIds, corrupt.values.toSet())
    }

    // ---------- checkForbiddenNameConflicts ----------

    @Test
    fun enabledToolClashingForbiddenAliasThrows() {
        // 另一个来源的工具叫 workspace_shell，而 workspace_shell 被禁用 → 显式冲突
        val forbidden = forbiddenWorkspaceToolNames(
            workspace(toolEnablement = """{"workspace_shell":false}"""),
            emptyMap(),
        )
        val error = assertThrows(ForbiddenWorkspaceToolNameException::class.java) {
            checkForbiddenNameConflicts(listOf(tool("workspace_shell")), forbidden)
        }
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, error.stableId)
    }

    @Test
    fun overrideRenamedEnabledToolClashingForbiddenAliasThrows() {
        // bash 禁用（minimal 才有 bash, normal 模式下 bash 属非本模式禁用面）;
        // 用户把 read_file 改名成 "bash" → 启用别名撞禁用别名, 必须显式冲突
        val forbidden = forbiddenWorkspaceToolNames(workspace(), emptyMap())
        val renamed = tool("bash").copy(
            workspaceIdentity = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_READ_FILE),
        )
        assertThrows(ForbiddenWorkspaceToolNameException::class.java) {
            checkForbiddenNameConflicts(listOf(renamed), forbidden)
        }
    }

    @Test
    fun enabledWorkspaceToolNeverConflicts() {
        // 工具名==自身启用 ID：启用面不含 forbidden 键, 不误报
        val forbidden = forbiddenWorkspaceToolNames(workspace(), emptyMap())
        val shell = tool(BuiltinToolIds.WORKSPACE_SHELL).copy(
            workspaceIdentity = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_SHELL),
        )
        checkForbiddenNameConflicts(listOf(shell), forbidden) // 不抛
    }

    @Test
    fun nullOrEmptyForbiddenIsNoOp() {
        checkForbiddenNameConflicts(listOf(tool("bash")), null)
        checkForbiddenNameConflicts(listOf(tool("bash")), emptyMap())
    }

    // ---------- 快照形状约束 ----------

    @Test
    fun snapshotCarriesExactBindings() {
        val tools = listOf(tool("a"), tool("b"))
        val snapshot = ChatToolSnapshot(
            tools = tools,
            bindings = buildToolBindings(tools),
            forbiddenWorkspaceToolNames = null,
        )
        assertEquals(tools, snapshot.tools)
        assertEquals(setOf("a", "b"), snapshot.bindings.keys)
        assertNull(snapshot.forbiddenWorkspaceToolNames)
        assertNull(snapshot.wirePolicy)
    }

    @Test
    fun wirePolicyDerivesFromForbiddenNames() {
        val forbidden = forbiddenWorkspaceToolNames(
            workspace(toolEnablement = """{"workspace_shell":false}"""),
            emptyMap(),
        )
        val snapshot = ChatToolSnapshot(
            tools = emptyList(),
            bindings = emptyMap(),
            forbiddenWorkspaceToolNames = forbidden.keys.toSet(),
            wirePolicy = WorkspaceToolWirePolicy(forbiddenWireNames = forbidden.keys),
        )
        // 同源：snapshot 兼容字段与 wirePolicy 内容一致
        assertEquals(snapshot.forbiddenWorkspaceToolNames, snapshot.wirePolicy!!.forbiddenWireNames)
        // 禁用的 workspace_shell 在 policy 里；启用的不在
        assertTrue(BuiltinToolIds.WORKSPACE_SHELL in snapshot.wirePolicy!!.forbiddenWireNames)
        assertTrue(BuiltinToolIds.WORKSPACE_READ_FILE !in snapshot.wirePolicy!!.forbiddenWireNames)
        // enforce 命中时抛正式冲突异常（非静默裁剪）
        org.junit.Assert.assertThrows(me.rerere.ai.provider.WorkspaceToolPolicyConflictException::class.java) {
            snapshot.wirePolicy!!.enforceToolNames(listOf(BuiltinToolIds.WORKSPACE_SHELL), "test")
        }
    }

    @Test
    fun minimalIdentityCarriesHarnessMode() {
        val id = workspaceToolIdentity(
            workspace(harnessMinimal = true),
            BuiltinToolIds.BASH,
        )
        assertEquals(WorkspaceToolMode.HARNESS_MINIMAL, id.mode)
        assertEquals("root:w1:mode:harness_minimal", id.viewFingerprint)
    }

    @Test
    fun applyBuiltinOverrideKeepsWorkspaceIdentity() {
        val identity = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_SHELL)
        val overridden = tool(BuiltinToolIds.WORKSPACE_SHELL)
            .copy(workspaceIdentity = identity)
            .applyBuiltinOverride(
                mapOf(BuiltinToolIds.WORKSPACE_SHELL to ToolOverride(name = "run_cmd")),
            )
        assertEquals("run_cmd", overridden.name)
        // 身份随 copy 保留,不因 Override 丢失/反查
        assertEquals(identity, overridden.workspaceIdentity)
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, overridden.workspaceIdentity!!.stableId)
    }
}
