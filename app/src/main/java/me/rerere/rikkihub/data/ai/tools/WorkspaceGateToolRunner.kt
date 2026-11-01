package me.rerere.rikkihub.data.ai.tools

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.workspace.WorkspaceEpochs
import me.rerere.rikkihub.data.workspace.WorkspaceHandleClass
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException

/**
 * [WorkspaceToolRunner] 的 WorkspaceRuntimeGate 适配实现（T1 守卫 ↔ T0 准入桥）。
 *
 * - PARENT 工具调用：完整走 [WorkspaceRuntimeGate.withOperation]——准入登记
 *   （validate 在 admissionMutex 内跑最新 DB 读）、per-tool epoch 比对、
 *   封禁等待、victim 取消全部由 gate 提供；拒绝时 gate 抛
 *   [WorkspaceUnavailableException]，这里映射为
 *   [WorkspaceToolUnavailableException]（GATE_DENIED），守卫再转成
 *   status=unavailable 普通工具结果。
 * - SUBAGENT_CHILD 同样登记：关闭 child 文件/shell 工具时取消准确的 child 调用，
 *   不必关闭仍启用的 subagent。gate 允许嵌套操作，但拒绝 validate/publish 锁内重入。
 */
class WorkspaceGateToolRunner(
    private val gate: WorkspaceRuntimeGate,
) : WorkspaceToolRunner {

    override suspend fun run(
        admission: WorkspaceToolCallAdmission,
        validate: suspend () -> Unit,
        operation: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        return try {
            gate.withOperation(
                workspaceId = admission.identity.workspaceId,
                kind = if (admission.caller == WorkspaceToolCaller.SUBAGENT_CHILD) {
                    WorkspaceOperationKind.CHILD
                } else WorkspaceOperationKind.TOOL,
                stableIds = setOf(admission.identity.stableId),
                handleClass = if (admission.caller == WorkspaceToolCaller.SUBAGENT_CHILD) {
                    WorkspaceHandleClass.CHILD
                } else WorkspaceHandleClass.MODEL_EXECUTION,
                // per-tool epoch：装配时经 activationEpoch 捕获的吊销代数，
                // disable→enable 后旧实例在此被拒（STABLE_ID_REVOKED）
                expectedToolEpochs = mapOf(admission.identity.stableId to admission.expectedToolEpoch),
                waitIfSealed = true,
                validate = {
                    // admissionMutex 内的最新 DB 读；守卫的不可用异常原样透传（保 reason）
                    validate()
                    true
                },
            ) { _ ->
                operation()
            }
        } catch (denied: WorkspaceUnavailableException) {
            throw WorkspaceToolUnavailableException(
                identity = admission.identity,
                reason = WorkspaceToolUnavailableException.Reason.GATE_DENIED,
                message = denied.message,
            )
        }
        // WorkspacePolicyCancellationException（victim 取消）与其余异常原样上抛，
        // 守卫按类型分类：policy cancel → outcome_unknown；真 CE → 传播。
    }

    override fun currentToolEpoch(workspaceId: String, stableId: String): Long =
        gate.currentToolEpoch(workspaceId, stableId)

    override fun currentEpochs(workspaceId: String): WorkspaceEpochs =
        gate.currentEpochs(workspaceId)

    // 进程级激活 nonce：身份签发与守卫比对同源；跨进程旧 pending 必然失配
    override val activationSession: String? get() = gate.activationSession

    override fun isWorkspaceBlocked(workspaceId: String): Boolean =
        gate.snapshot(workspaceId).let { it.closed || it.sealed || it.quarantined }
}
