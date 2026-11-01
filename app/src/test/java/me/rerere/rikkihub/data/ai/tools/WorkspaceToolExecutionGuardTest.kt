package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolIdentity
import me.rerere.ai.ui.WorkspaceToolMode
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableReason
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 双执行守卫 + schema 过滤测试。
 *
 * 全部纯 JVM:守卫 DB 验证经 `loadWorkspace` seam 注入 fake,gate 准入经
 * [WorkspaceToolRunner] seam 注入；repo 传 null 时守卫拒绝路径不触碰
 * 工具 body（守卫先于一切 side effect）。
 *
 * 结果语义：准入拒绝 → status=unavailable；gate victim 取消（调用方仍
 * active 的 CancellationException）→ status=outcome_unknown + started=true；
 * 调用方真实取消 → CE 原样传播。
 */
class WorkspaceToolExecutionGuardTest {

    private fun workspace(
        id: String = "w1",
        toolEnablement: String = "{}",
        shellStatus: String = WorkspaceShellStatus.READY.name,
        harnessMinimal: Boolean = false,
        root: String = id,
    ) = WorkspaceEntity(
        id = id,
        name = id,
        root = root,
        shellStatus = shellStatus,
        createdAt = 1L,
        updatedAt = 1L,
        toolEnablement = toolEnablement,
        harnessMinimal = harnessMinimal,
    )

    private fun identity(
        stableId: String = BuiltinToolIds.WORKSPACE_READ_FILE,
        workspaceId: String = "w1",
        mode: String = WorkspaceToolMode.NORMAL,
        viewFingerprint: String? = null,
        activationEpoch: Long = 0,
    ) = WorkspaceToolIdentity(
        workspaceId = workspaceId,
        stableId = stableId,
        mode = mode,
        viewFingerprint = viewFingerprint,
        activationEpoch = activationEpoch,
    )

    private fun dummyTool(name: String = "dummy"): Tool = Tool(
        name = name,
        description = "d",
        execute = { listOf(UIMessagePart.Text("ran")) },
    )

    private fun reasonOf(error: Throwable?): WorkspaceToolUnavailableException.Reason? =
        (error as? WorkspaceToolUnavailableException)?.reason

    private fun statusOf(parts: List<UIMessagePart>): String? =
        parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()
            ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it.text).jsonObject["status"]?.jsonPrimitive?.content }

    private fun resultJson(parts: List<UIMessagePart>) =
        kotlinx.serialization.json.Json.parseToJsonElement(
            (parts.single() as UIMessagePart.Text).text,
        ).jsonObject

    // ---------- requireWorkspaceToolAdmissible: DB 验证逐项 ----------

    @Test
    fun admissiblePassesForReadyEnabledTool() {
        val id = identity()
        requireWorkspaceToolAdmissible(id, workspace()) // 不抛即通过
        requireWorkspaceToolAdmissible(
            id.copy(viewFingerprint = "root:w1:mode:normal"),
            workspace(),
        )
    }

    @Test
    fun missingWorkspaceIsRefused() {
        val error = runCatching {
            requireWorkspaceToolAdmissible(identity(), null)
        }.exceptionOrNull()
        assertEquals(
            WorkspaceToolUnavailableException.Reason.WORKSPACE_MISSING,
            reasonOf(error),
        )
    }

    @Test
    fun nonReadyWorkspaceIsRefused() {
        for (status in listOf(
            WorkspaceShellStatus.DISABLED,
            WorkspaceShellStatus.INSTALLING,
            WorkspaceShellStatus.BROKEN,
        )) {
            val error = runCatching {
                requireWorkspaceToolAdmissible(identity(), workspace(shellStatus = status.name))
            }.exceptionOrNull()
            assertEquals(
                "$status must refuse",
                WorkspaceToolUnavailableException.Reason.NOT_READY,
                reasonOf(error),
            )
        }
    }

    @Test
    fun wrongModeToolIsRefused() {
        // normal 工作区不应执行 harness_minimal 身份的工具
        val error = runCatching {
            requireWorkspaceToolAdmissible(
                identity(stableId = BuiltinToolIds.BASH, mode = WorkspaceToolMode.HARNESS_MINIMAL),
                workspace(harnessMinimal = false),
            )
        }.exceptionOrNull()
        assertEquals(
            WorkspaceToolUnavailableException.Reason.MODE_MISMATCH,
            reasonOf(error),
        )
        // 反向同理：minimal 工作区拒绝 normal 身份
        val reverse = runCatching {
            requireWorkspaceToolAdmissible(identity(), workspace(harnessMinimal = true))
        }.exceptionOrNull()
        assertEquals(
            WorkspaceToolUnavailableException.Reason.MODE_MISMATCH,
            reasonOf(reverse),
        )
    }

    @Test
    fun corruptEnablementFailsClosed() {
        val error = runCatching {
            requireWorkspaceToolAdmissible(identity(), workspace(toolEnablement = "not-json"))
        }.exceptionOrNull()
        assertEquals(
            WorkspaceToolUnavailableException.Reason.CONFIG_INVALID,
            reasonOf(error),
        )
    }

    @Test
    fun disabledToolIsRefused() {
        val error = runCatching {
            requireWorkspaceToolAdmissible(
                identity(),
                workspace(toolEnablement = """{"workspace_read_file":false}"""),
            )
        }.exceptionOrNull()
        assertEquals(
            WorkspaceToolUnavailableException.Reason.TOOL_DISABLED,
            reasonOf(error),
        )
    }

    @Test
    fun staleViewFingerprintIsRefused() {
        val error = runCatching {
            requireWorkspaceToolAdmissible(
                identity(viewFingerprint = "root:other:mode:normal"),
                workspace(),
            )
        }.exceptionOrNull()
        assertEquals(
            WorkspaceToolUnavailableException.Reason.VIEW_STALE,
            reasonOf(error),
        )
    }

    // ---------- withWorkspaceExecutionGuard: 双入口守卫先于 body ----------

    @Test
    fun guardRejectsDisabledToolBeforeBody() = runBlocking {
        var bodyRan = false
        val guarded = dummyTool()
            .copy(execute = { bodyRan = true; emptyList() })
            .withWorkspaceExecutionGuard(
                identity = identity(),
                loadWorkspace = { workspace(toolEnablement = """{"workspace_read_file":false}""") },
                toolRunner = null,
                caller = WorkspaceToolCaller.PARENT,
            )
        val out = guarded.execute(buildJsonObject {})
        assertEquals("unavailable", statusOf(out))
        assertEquals(
            "TOOL_DISABLED",
            resultJson(out)["reason"]?.jsonPrimitive?.content,
        )
        assertFalse("守卫必须先于 body 拒绝", bodyRan)
        Unit
    }

    @Test
    fun guardRejectsViaExecuteWithContextToo() = runBlocking {
        var bodyRan = false
        val tool = dummyTool().copy(
            executeWithContext = { _, _ -> bodyRan = true; emptyList() },
        ).withWorkspaceExecutionGuard(
            identity = identity(),
            loadWorkspace = { workspace(toolEnablement = """{"workspace_read_file":false}""") },
            toolRunner = null,
            caller = WorkspaceToolCaller.PARENT,
        )
        val ctx = ToolExecutionContext("call-1") { }
        val out = tool.executeWithContext!!(buildJsonObject {}, ctx)
        assertEquals("unavailable", statusOf(out))
        assertFalse("executeWithContext 守卫必须先于 body/metadata 发布", bodyRan)
        Unit
    }

    @Test
    fun runnerSeamRunsValidateInsideAndReceivesAdmission() = runBlocking {
        var admissionSeen: WorkspaceToolCallAdmission? = null
        var validateRanBeforeBody = false
        var bodyRan = false
        val runner = object : WorkspaceToolRunner {
            override suspend fun run(
                admission: WorkspaceToolCallAdmission,
                validate: suspend () -> Unit,
                operation: suspend () -> List<UIMessagePart>,
            ): List<UIMessagePart> {
                admissionSeen = admission
                validate() // 模拟 admission lock 内最新 DB 读
                validateRanBeforeBody = !bodyRan
                return operation()
            }

            override fun currentToolEpoch(workspaceId: String, stableId: String): Long = 42L
        }
        // identity.activationEpoch 是签发值（装配时经 runner.currentToolEpoch 填充）
        val issued = identity(activationEpoch = 42L)
        assertEquals(42L, workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_READ_FILE, runner).activationEpoch)
        val guarded = dummyTool(name = "original_wire")
            .copy(execute = { bodyRan = true; listOf(UIMessagePart.Text("ok")) })
            .withWorkspaceExecutionGuard(
                identity = issued,
                loadWorkspace = { workspace() },
                toolRunner = runner,
                caller = WorkspaceToolCaller.SUBAGENT_CHILD,
            )
        val out = guarded.execute(buildJsonObject {})
        assertTrue(bodyRan)
        assertTrue("validate 必须先于 operation", validateRanBeforeBody)
        assertEquals("ok", (out.single() as UIMessagePart.Text).text)
        val admission = admissionSeen!!
        assertEquals(WorkspaceToolCaller.SUBAGENT_CHILD, admission.caller)
        assertEquals("original_wire", admission.wireName)
        assertEquals(BuiltinToolIds.WORKSPACE_READ_FILE, admission.identity.stableId)
        assertNull(admission.toolCallId)
        assertEquals(
            "expectedToolEpoch 必须等于签发时刻的 activationEpoch",
            42L,
            admission.expectedToolEpoch,
        )
        assertEquals(issued.activationEpoch, admission.expectedToolEpoch)
        Unit
    }

    @Test
    fun runnerDenialBecomesUnavailableResult() = runBlocking {
        val runner = WorkspaceToolRunner { admission, _, _ ->
            throw WorkspaceToolUnavailableException(
                admission.identity,
                WorkspaceToolUnavailableException.Reason.GATE_DENIED,
                "gate epoch mismatch",
            )
        }
        var bodyRan = false
        val tool = dummyTool()
            .copy(execute = { bodyRan = true; emptyList() })
            .withWorkspaceExecutionGuard(
                identity = identity(),
                loadWorkspace = { workspace() },
                toolRunner = runner,
                caller = WorkspaceToolCaller.PARENT,
            )
        val out = tool.execute(buildJsonObject {})
        assertEquals("unavailable", statusOf(out))
        assertEquals("GATE_DENIED", resultJson(out)["reason"]?.jsonPrimitive?.content)
        assertFalse("准入拒绝不得执行 body", bodyRan)
        Unit
    }

    @Test
    fun gateDenialInsideRunnerBecomesUnavailable() = runBlocking {
        // 适配层若原样抛 gate 的 WorkspaceUnavailableException,守卫同样归为 unavailable
        val runner = WorkspaceToolRunner { _, _, _ ->
            throw WorkspaceUnavailableException(
                "w1",
                WorkspaceUnavailableReason.STABLE_ID_REVOKED,
                "epoch mismatch",
            )
        }
        val tool = dummyTool().withWorkspaceExecutionGuard(
            identity = identity(),
            loadWorkspace = { workspace() },
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
        )
        val out = tool.execute(buildJsonObject {})
        assertEquals("unavailable", statusOf(out))
        assertEquals("GATE_DENIED", resultJson(out)["reason"]?.jsonPrimitive?.content)
        Unit
    }

    @Test
    fun gateCancellationBecomesOutcomeUnknown() = runBlocking {
        // 强类型政策取消：已开始执行被配置变更打断 → 普通结果 outcome_unknown
        val runner = WorkspaceToolRunner { _, _, _ ->
            throw WorkspacePolicyCancellationException("w1")
        }
        var bodyRan = false
        val tool = dummyTool()
            .copy(execute = { bodyRan = true; emptyList() })
            .withWorkspaceExecutionGuard(
                identity = identity(),
                loadWorkspace = { workspace() },
                toolRunner = runner,
                caller = WorkspaceToolCaller.PARENT,
            )
        val out = tool.execute(buildJsonObject {})
        assertEquals("outcome_unknown", statusOf(out))
        assertTrue(
            "已开始取消必须标记 started=true, 不冒充零执行",
            resultJson(out)["started"]?.jsonPrimitive?.content == "true",
        )
        Unit
    }

    @Test
    fun nonPolicyCancellationDoesNotBecomeOutcomeUnknown() = runBlocking {
        // 非策略 CE（非 WorkspacePolicyCancellationException）且调用方仍 active：
        // 不属于本守卫的分类（如操作内部取消），原样传播不吞为工具结果
        val runner = WorkspaceToolRunner { _, _, _ ->
            throw CancellationException("internal op cancel")
        }
        val tool = dummyTool().withWorkspaceExecutionGuard(
            identity = identity(),
            loadWorkspace = { workspace() },
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
        )
        val error = runCatching { tool.execute(buildJsonObject {}) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(error is WorkspacePolicyCancellationException)
        Unit
    }

    @Test
    fun externalCancellationPropagates() = runBlocking {
        // 调用方协程真正被取消：守卫 ensureActive 判别后原样传播 CE,不吞成工具结果。
        // 注意绝不能取消 runBlocking 自身的 Job（那会让 runBlocking 在块结束后
        // 再抛 JCE 失败整个测试）；取消必须作用在隔离的子 Job 范围内。
        val runner = WorkspaceToolRunner { _, _, _ ->
            throw CancellationException("external cancel")
        }
        val tool = dummyTool().withWorkspaceExecutionGuard(
            identity = identity(),
            loadWorkspace = { workspace() },
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
        )
        val callScope = kotlinx.coroutines.Job()
        val error = runCatching {
            kotlinx.coroutines.withContext(callScope) {
                callScope.cancel()
                tool.execute(buildJsonObject {})
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        Unit
    }

    @Test
    fun runnerGenericFailurePropagates() = runBlocking {
        // runner/body 的非 CE 非拒绝异常原样传播(交给 loop 记错误结果,不做吞并)
        val runner = WorkspaceToolRunner { _, _, _ -> error("boom") }
        val tool = dummyTool().withWorkspaceExecutionGuard(
            identity = identity(),
            loadWorkspace = { workspace() },
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
        )
        val error = runCatching { tool.execute(buildJsonObject {}) }.exceptionOrNull()
        assertEquals("boom", error?.message)
        Unit
    }

    @Test
    fun withoutToolApprovalKeepsGuard() = runBlocking {
        val guarded = dummyTool()
            .withWorkspaceExecutionGuard(
                identity = identity(),
                loadWorkspace = { workspace(toolEnablement = """{"workspace_read_file":false}""") },
                toolRunner = null,
                caller = WorkspaceToolCaller.PARENT,
            )
        val noApproval = withoutToolApproval(guarded)
        assertFalse(noApproval.needsApproval(buildJsonObject {}))
        val out = noApproval.execute(buildJsonObject {})
        assertEquals(
            "去审批 copy 不能移除守卫",
            "unavailable",
            statusOf(out),
        )
        Unit
    }

    // ---------- WorkspaceGateToolRunner: 真实 gate 适配（纯 JVM gate） ----------

    @Test
    fun gateRunnerAdmitsEnabledToolAndPassesEpoch() = runBlocking {
        val gate = me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate()
        val runner = WorkspaceGateToolRunner(gate)
        // 装配时刻读数：未变更=0
        assertEquals(0L, runner.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_READ_FILE))
        assertEquals(gate.activationSession, runner.activationSession)
        val identity = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_READ_FILE, runner)
        assertEquals(0L, identity.activationEpoch)
        assertEquals(gate.activationSession, identity.activationSession)
        var bodyRan = false
        val tool = dummyTool()
            .copy(execute = { bodyRan = true; emptyList() })
            .withWorkspaceExecutionGuard(
                identity = identity,
                loadWorkspace = { workspace() },
                toolRunner = runner,
                caller = WorkspaceToolCaller.PARENT,
            )
        tool.execute(buildJsonObject {})
        assertTrue("准入应透传 body 执行", bodyRan)
        Unit
    }

    @Test
    fun toolIdentityCarriesRunnerEpochAndSession() = runBlocking {
        // 同源：buildWorkspaceTools 装配的工具上 workspaceIdentity 必须与守卫
        // 的签发身份一致——带 runner 的 epoch+session，不是 createXxx 的裸 0/null。
        val gate = me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate()
        val runner = WorkspaceGateToolRunner(gate)
        var current = workspace()
        val tools = buildWorkspaceTools(
            workspace = current,
            workspaceRepository = null,
            subagentHost = null,
            includeSubagent = false,
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
            loadWorkspace = { current },
        )
        val shell = tools.single { it.name == BuiltinToolIds.WORKSPACE_SHELL }
        val issued = shell.workspaceIdentity!!
        assertEquals(0L, issued.activationEpoch)
        assertEquals(gate.activationSession, issued.activationSession)

        // 禁用→再开启：per-tool 吊销代数递增，旧 Tool 实例/旧签发身份不得复活
        gate.cancelAffected("w1", affectedStableIds = setOf(BuiltinToolIds.WORKSPACE_SHELL))
        assertEquals(1L, runner.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL))
        val out = shell.execute(buildJsonObject { put("command", JsonPrimitive("ls")) })
        assertEquals("unavailable", statusOf(out))
        assertEquals("GATE_DENIED", resultJson(out)["reason"]?.jsonPrimitive?.content)

        // 重开后装配的新实例 epoch=1 且同 session → 正常准入
        current = workspace()
        val rebuilt = buildWorkspaceTools(
            workspace = current,
            workspaceRepository = null,
            subagentHost = null,
            includeSubagent = false,
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
            loadWorkspace = { current },
        )
        val fresh = rebuilt.single { it.name == BuiltinToolIds.WORKSPACE_SHELL }
        assertEquals(1L, fresh.workspaceIdentity!!.activationEpoch)
        assertEquals(gate.activationSession, fresh.workspaceIdentity!!.activationSession)

        Unit
    }

    @Test
    fun foreignSessionIdentityIsDenied() = runBlocking {
        // 签发身份的 activationSession 与当前 runner 不同 → SESSION_MISMATCH，
        // 守卫先于 body 拒绝（如同属进程但 runner/gate 已换代的陈旧工具）。
        val gate = me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate()
        val runner = WorkspaceGateToolRunner(gate)
        var bodyRan = false
        val tool = dummyTool()
            .copy(execute = { bodyRan = true; emptyList() })
            .withWorkspaceExecutionGuard(
                identity = identity(activationEpoch = 0).let { issued ->
                    issued.copy(activationSession = "other-process-nonce")
                },
                loadWorkspace = { workspace() },
                toolRunner = runner,
                caller = WorkspaceToolCaller.PARENT,
            )
        val out = tool.execute(buildJsonObject {})
        assertEquals("unavailable", statusOf(out))
        assertEquals("SESSION_MISMATCH", resultJson(out)["reason"]?.jsonPrimitive?.content)
        assertFalse(bodyRan)
        Unit
    }

    @Test
    fun snapshotIsCurrentTracksGateState() = runBlocking {
        val gate = me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate()
        val runner = WorkspaceGateToolRunner(gate)
        val snap = createWorkspaceToolSnapshot(
            workspace = workspace(),
            workspaceRepository = null,
            subagentHost = null,
            includeSubagent = false,
            toolRunner = runner,
            loadWorkspace = { workspace() },
        )
        assertTrue(snap.isCurrent())
        // policy 变更 bump epoch → 快照过期，须整份重建
        gate.cancelAffected("w1", affectedStableIds = setOf(BuiltinToolIds.WORKSPACE_SHELL))
        assertFalse("policy bump 后快照必须失效", snap.isCurrent())
        // 重建的新快照捕获新 epoch → 又变 current
        val fresh = createWorkspaceToolSnapshot(
            workspace = workspace(),
            workspaceRepository = null,
            subagentHost = null,
            includeSubagent = false,
            toolRunner = runner,
            loadWorkspace = { workspace() },
        )
        assertTrue(fresh.isCurrent())
        // 工作区关闭（closed 态）→ 阻塞态变化，旧快照过期
        gate.closeWorkspace("w1")
        assertFalse("closed 后快照必须失效", fresh.isCurrent())
        Unit
    }

    @Test
    fun gateRunnerRejectsAfterToolEpochBump() = runBlocking {
        val gate = me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate()
        val runner = WorkspaceGateToolRunner(gate)
        val identity = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_READ_FILE, runner)
        val tool = dummyTool().withWorkspaceExecutionGuard(
            identity = identity,
            loadWorkspace = { workspace() },
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
        )
        // 策略变更（该 stableId 吊销代数+1）→ 旧实例执行被拒为 unavailable
        gate.cancelAffected("w1", affectedStableIds = setOf(BuiltinToolIds.WORKSPACE_READ_FILE))
        val out = tool.execute(buildJsonObject {})
        assertEquals("unavailable", statusOf(out))
        assertEquals("GATE_DENIED", resultJson(out)["reason"]?.jsonPrimitive?.content)
        Unit
    }

    @Test
    fun gateRunnerValidatesLatestDbInsideAdmission() = runBlocking {
        // gate 上线且 epoch 没变，但 DB 已禁用：锁内 validate 仍须拒绝
        val gate = me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate()
        val runner = WorkspaceGateToolRunner(gate)
        // 身份必须经 runner 签发（activationSession 同源），裸 helper 的
        // null session 会被守卫判为异会话拒绝。
        val tool = dummyTool().withWorkspaceExecutionGuard(
            identity = workspaceToolIdentity(
                workspace(), BuiltinToolIds.WORKSPACE_READ_FILE, runner,
            ),
            loadWorkspace = { workspace(toolEnablement = """{"workspace_read_file":false}""") },
            toolRunner = runner,
            caller = WorkspaceToolCaller.PARENT,
        )
        val out = tool.execute(buildJsonObject {})
        assertEquals("unavailable", statusOf(out))
        assertEquals("TOOL_DISABLED", resultJson(out)["reason"]?.jsonPrimitive?.content)
        Unit
    }

    @Test
    fun childCallerDoesNotReenterGate() = runBlocking {
        // SUBAGENT_CHILD 已在父 op 的 OpElement 里执行；适配器不再嵌套 withOperation，
        // 只做 validate+body，避免 WorkspaceGateReentrancyException。
        val gate = me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate()
        val runner = WorkspaceGateToolRunner(gate)
        var ranInsideOp = false
        var childBodyRan = false
        // 用 gate.withOperation 造一个父操作上下文（模拟 dispatch 后的父 run）
        gate.withOperation(
            workspaceId = "w1",
            kind = me.rerere.rikkihub.data.workspace.WorkspaceOperationKind.TOOL,
            stableIds = setOf(BuiltinToolIds.SUBAGENT),
        ) { _ ->
            ranInsideOp = true
            val child = dummyTool()
                .copy(execute = { childBodyRan = true; listOf(UIMessagePart.Text("child-ok")) })
                .withWorkspaceExecutionGuard(
                    identity = workspaceToolIdentity(
                        workspace(), BuiltinToolIds.WORKSPACE_READ_FILE, runner,
                    ),
                    loadWorkspace = { workspace() },
                    toolRunner = runner,
                    caller = WorkspaceToolCaller.SUBAGENT_CHILD,
                )
            val out = child.execute(buildJsonObject {})
            assertEquals("child-ok", (out.single() as UIMessagePart.Text).text)
        }
        assertTrue(ranInsideOp)
        assertTrue("child 执行不得触发重入拒绝", childBodyRan)
        Unit
    }

    // ---------- 超大文件错误文案：不推荐已禁用 shell ----------

    @Test
    fun oversizedFileMessageFollowsToolEnablement() {
        // normal + shell 启用 → 建议 shell 命令
        val withShell = oversizedFileReadMessage(workspace(), "/workspace/big.bin", 10L * 1024 * 1024)
        assertTrue(withShell.contains(BuiltinToolIds.WORKSPACE_SHELL))
        // normal + shell 禁用 → 不推荐 shell（该模式无编辑器工具）
        val noShell = oversizedFileReadMessage(
            workspace(toolEnablement = """{"workspace_shell":false}"""),
            "/workspace/big.bin",
            10L * 1024 * 1024,
        )
        assertFalse(noShell.contains(BuiltinToolIds.WORKSPACE_SHELL))
        assertTrue(noShell.contains("no shell") || noShell.contains("No shell"))
        // minimal + bash 禁用 + editor 启用 → 建议 str_replace_editor view_range
        val editorOnly = oversizedFileReadMessage(
            workspace(harnessMinimal = true, toolEnablement = """{"bash":false}"""),
            "/workspace/big.bin",
            10L * 1024 * 1024,
        )
        assertTrue(editorOnly.contains(BuiltinToolIds.STR_REPLACE_EDITOR))
        assertFalse(editorOnly.contains(BuiltinToolIds.BASH + " "))
        // 损坏配置 → 不推荐任何工具
        val corrupt = oversizedFileReadMessage(
            workspace(toolEnablement = "{broken"),
            "/workspace/big.bin",
            10L * 1024 * 1024,
        )
        assertFalse(corrupt.contains(BuiltinToolIds.WORKSPACE_SHELL))
        assertFalse(corrupt.contains(BuiltinToolIds.BASH))
    }

    // ---------- createWorkspaceToolSnapshot（装配统一入口） ----------

    @Test
    fun workspaceToolSnapshotCarriesCapabilitiesAndPolicy() = runBlocking {
        val snap = createWorkspaceToolSnapshot(
            workspace = workspace(),
            workspaceRepository = null,
            subagentHost = null,
            includeSubagent = false,
            toolRunner = null,
            loadWorkspace = { workspace() },
        )
        assertEquals(4, snap.tools.size)
        val caps = snap.capabilities
        assertEquals("w1", caps.workspaceId)
        assertFalse(caps.harnessMinimal)
        assertEquals(4, caps.availableToolNames.size)
        assertTrue(caps.hasTool(BuiltinToolIds.WORKSPACE_SHELL))
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, caps.readbackStableId)
        assertTrue(caps.exposesToolOutputs)
        // forbidden = 非本模式(bash/editor)+其它未配置=两者；subagent 虽启用但
        // includeSubagent=false 只是不装配, enablement 上仍启用 → 不进禁用面
        assertTrue(BuiltinToolIds.BASH in snap.forbidden.keys)
        assertTrue(BuiltinToolIds.SUBAGENT !in snap.forbidden.values)
        assertEquals(snap.forbidden.keys, snap.wirePolicy.forbiddenWireNames)
        assertEquals("w1", snap.wirePolicy.workspaceId)
        assertEquals("root:w1:mode:normal", snap.viewFingerprint)
        Unit
    }

    @Test
    fun workspaceToolSnapshotForNonReadyStillHasEmptyCaps() = runBlocking {
        val snap = createWorkspaceToolSnapshot(
            workspace = workspace(shellStatus = WorkspaceShellStatus.BROKEN.name),
            workspaceRepository = null,
            subagentHost = null,
            toolRunner = null,
            loadWorkspace = { null },
        )
        assertTrue(snap.tools.isEmpty())
        assertTrue("非 READY 也给空能力面（非 null）", snap.capabilities.availableToolNames.isEmpty())
        assertEquals(WorkspaceToolEnablement.configurableIds, snap.forbidden.values.toSet())
        Unit
    }

    // ---------- buildWorkspaceTools: schema 过滤 ----------

    private suspend fun build(
        entity: WorkspaceEntity,
        loadWorkspace: suspend (String) -> WorkspaceEntity? = { entity },
        toolRunner: WorkspaceToolRunner? = null,
        caller: WorkspaceToolCaller = WorkspaceToolCaller.PARENT,
        includeSubagent: Boolean = true,
    ) = buildWorkspaceTools(
        workspace = entity,
        workspaceRepository = null,
        persistentBashSessionManager = null,
        subagentHost = null,
        includeSubagent = includeSubagent,
        toolRunner = toolRunner,
        caller = caller,
        loadWorkspace = loadWorkspace,
    )

    @Test
    fun disabledToolIsAbsentFromSchema() = runBlocking {
        val tools = build(workspace(toolEnablement = """{"workspace_shell":false}"""))
        assertEquals(
            listOf(
                BuiltinToolIds.WORKSPACE_READ_FILE,
                BuiltinToolIds.WORKSPACE_WRITE_FILE,
                BuiltinToolIds.WORKSPACE_EDIT_FILE,
            ),
            tools.map { it.name },
        )
        // 每个候选都有稳定身份
        tools.forEach {
            assertEquals("w1", it.workspaceIdentity?.workspaceId)
            assertEquals(it.name, it.workspaceIdentity?.stableId)
            assertEquals(WorkspaceToolMode.NORMAL, it.workspaceIdentity?.mode)
            assertEquals("root:w1:mode:normal", it.workspaceIdentity?.viewFingerprint)
        }
        Unit
    }

    @Test
    fun allDisabledYieldsEmptySchema() = runBlocking {
        val allOff = WorkspaceToolEnablement.normalIds.joinToString(",", "{", "}") { "\"$it\":false" }
        val tools = build(workspace(toolEnablement = allOff))
        assertTrue(tools.isEmpty())
        Unit
    }

    @Test
    fun corruptEnablementYieldsEmptySchema() = runBlocking {
        val tools = build(workspace(toolEnablement = "{broken"))
        assertTrue("损坏配置 fail-closed, 不声明任何工具", tools.isEmpty())
        Unit
    }

    @Test
    fun nonReadyWorkspaceYieldsEmptySchema() = runBlocking {
        val tools = build(workspace(shellStatus = WorkspaceShellStatus.INSTALLING.name))
        assertTrue(tools.isEmpty())
        Unit
    }

    @Test
    fun minimalModeSelectsMinimalIds() = runBlocking {
        val noBash = workspace(
            harnessMinimal = true,
            toolEnablement = """{"str_replace_editor":false,"bash":false,"subagent":false}""",
        )
        assertTrue(build(noBash).isEmpty())

        val editorOnly = workspace(
            harnessMinimal = true,
            toolEnablement = """{"bash":false,"subagent":false}""",
        )
        val editorTools = build(editorOnly)
        assertEquals(listOf(BuiltinToolIds.STR_REPLACE_EDITOR), editorTools.map { it.name })
        assertEquals(WorkspaceToolMode.HARNESS_MINIMAL, editorTools.single().workspaceIdentity?.mode)
        Unit
    }

    @Test
    fun staleToolStillGuardedAtExecution() = runBlocking {
        // schema 时刻启用，执行时刻已禁用：旧 Tool 引用被守卫拦截为 unavailable
        var current = workspace()
        val tools = build(workspace(), loadWorkspace = { current })
        val shell = tools.single { it.name == BuiltinToolIds.WORKSPACE_SHELL }
        current = workspace(toolEnablement = """{"workspace_shell":false}""")
        val out = shell.execute(buildJsonObject { put("command", JsonPrimitive("ls")) })
        assertEquals("unavailable", statusOf(out))
        assertEquals("TOOL_DISABLED", resultJson(out)["reason"]?.jsonPrimitive?.content)
        Unit
    }

    @Test
    fun missingWorkspaceAtExecutionIsRefused() = runBlocking {
        val tools = build(workspace(), loadWorkspace = { null })
        val out = tools.first().execute(buildJsonObject { put("path", JsonPrimitive("/workspace/a")) })
        assertEquals("unavailable", statusOf(out))
        assertEquals("WORKSPACE_MISSING", resultJson(out)["reason"]?.jsonPrimitive?.content)
        Unit
    }

    // ---------- 审批收敛：OR 删除 ----------

    @Test
    fun pathOutsideWorkspaceDoesNotForceApproval() {
        val entity = workspace()
        // workspace_write_file 默认 needsApproval=false；旧 OR 分支会对 /workspace
        // 之外路径强制 Pending,现在必须只看用户配置
        assertFalse(
            resolveWorkspaceToolApproval(
                BuiltinToolIds.WORKSPACE_WRITE_FILE,
                entity.toolApprovalOverrides(),
            ),
        )
        // 用户显式开审批则依旧 Pending（与路径无关）
        val overridden = entity.copy(toolApprovals = """{"workspace_write_file":true}""")
        assertTrue(
            resolveWorkspaceToolApproval(
                BuiltinToolIds.WORKSPACE_WRITE_FILE,
                overridden.toolApprovalOverrides(),
            ),
        )
    }

    @Test
    fun editorApprovalIsConfigOnly() {
        assertFalse(
            resolveWorkspaceToolApproval(
                BuiltinToolIds.STR_REPLACE_EDITOR,
                emptyMap(),
            ),
        )
    }

    // ---------- 路径校验增强（既有 absolutePath 之外不加新白名单） ----------

    @Test
    fun dotDotAndNulPathsAreRejected() {
        val err1 = runCatching { requireAbsoluteRootfsPath("/workspace/../etc/passwd") }.exceptionOrNull()
        assertTrue(err1!!.message!!.contains(".."))
        // NUL 字节必须显式写在字面量里（之前丢失过 \u0000 转义导致 NPE）
        val err2 = runCatching { requireAbsoluteRootfsPath("/workspace/a\u0000b") }.exceptionOrNull()
        assertTrue(err2!!.message!!.contains("invalid character"))
        assertEquals("/workspace/x", requireAbsoluteRootfsPath("/workspace/x"))
        // 合法的深路径不受影响
        assertEquals("/opt/pkg/bin", requireAbsoluteRootfsPath("/opt/pkg/bin"))
    }

    // ---------- 结构指纹稳定性 ----------

    @Test
    fun fingerprintIsStructuralNotPolicy() {
        val base = workspace()
        val disabled = workspace(toolEnablement = """{"workspace_shell":false}""")
        assertEquals(
            "启用配置变化不作废结构指纹",
            workspaceToolViewFingerprint(base),
            workspaceToolViewFingerprint(disabled),
        )
        assertEquals("root:w1:mode:normal", workspaceToolViewFingerprint(base))
        assertEquals(
            "root:w1:mode:harness_minimal",
            workspaceToolViewFingerprint(workspace(harnessMinimal = true)),
        )
        assertTrue(
            workspaceToolViewFingerprint(workspace(root = "other")) != workspaceToolViewFingerprint(base),
        )
    }

    @Test
    fun identityMatchesOnlySameView() {
        val id = workspaceToolIdentity(workspace(), BuiltinToolIds.WORKSPACE_SHELL)
        assertTrue(id.matches(id))
        assertFalse(id.matches(id.copy(viewFingerprint = "root:other:mode:normal")))
        assertFalse(id.matches(id.copy(stableId = BuiltinToolIds.BASH)))
        assertFalse(id.matches(id.copy(mode = WorkspaceToolMode.HARNESS_MINIMAL)))
    }
}
