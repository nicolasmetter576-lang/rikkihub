package me.rerere.rikkihub.data.management.coordinator

import me.rerere.rikkihub.data.management.common.DiffLine
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSet
import me.rerere.rikkihub.data.management.common.RetentionNote
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.ports.HostIdAllocator
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.management.ports.ManagementReadPort
import me.rerere.rikkihub.data.management.ports.StoreOperation

/** 计划期上下文：读口 + 宿主 id 分配 + 当前 epoch。计划不写存储。 */
data class PlanContext(
    val read: ManagementReadPort,
    val nowMillis: Long,
    val storageEpoch: Long,
    val hostIds: HostIdAllocator,
)

/** 计划结果：read-set + 步骤 + diff + 风险 + 级联说明（不写任何存储）。 */
data class PlannedMutation(
    val readSet: ReadSet,
    val steps: List<StoreOperation>,
    val diffSummary: List<DiffLine>,
    val risk: RiskLevel,
    val retention: RetentionNote,
    val cascadeTargets: List<ObjectRef>,
    val requiresEnhancedConfirm: Boolean,
    /** 逻辑占位名 → 宿主分配 ID（冻结进提案 payload）。 */
    val hostAssignedIds: Map<String, String>,
)

/**
 * 域 adapter 契约（management 核心实现 Settings 侧五域，
 * adapters 以同一接口实现 subagent/skills/conversation 三域）。
 *
 * 纪律：plan() 是纯读+计算，绝不写存储；读数经 [PlanContext.read]
 * （锁内最新持久值镜像），不得用锁外旧快照；所有删除/绑定目标进 read-set。
 */
interface ManagementDomainAdapter {
    val domain: ManagementDomain

    /** 校验命令 + 读当前态 → 计划。校验失败抛 ProposalServiceError.DomainError。 */
    suspend fun plan(ctx: PlanContext, command: ManagementCommand): PlannedMutation

    /**
     * 逆命令（Undo/补偿）：返回可再次走完整提案流程的新命令；
     * 不可逆返回 null（retention 需声明 irreversibleReason）。
     * Undo 提案的 read-set 由 service 侧断言 currentToken == 原 afterToken。
     */
    suspend fun inverse(ctx: PlanContext, command: ManagementCommand): ManagementCommand?

    /** 该命令是否要求回收 payload（删除/覆盖 before-image）。 */
    fun needsRecyclePayload(command: ManagementCommand): Boolean

    /** 反序列化提案 payload 回本域命令（重启核对/Undo 用）。 */
    fun decodeCommand(json: String): ManagementCommand =
        throw UnsupportedOperationException("$domain adapter does not persist commands")

    /** 序列化步骤 op 为 JSON（持久化进提案）；不支持返回 null。 */
    fun encodeStep(op: StoreOperation): String? =
        me.rerere.rikkihub.data.management.ports.StoreOperationCodec.encode(op)

    /** 反序列化步骤 op（恢复执行/补偿用）。 */
    fun decodeStep(json: String): StoreOperation? =
        me.rerere.rikkihub.data.management.ports.StoreOperationCodec.decode(json)
}
