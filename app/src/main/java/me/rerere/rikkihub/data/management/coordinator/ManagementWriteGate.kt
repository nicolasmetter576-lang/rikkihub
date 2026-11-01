package me.rerere.rikkihub.data.management.coordinator

enum class WriteGateRejection {
    /** mutation 底座未通过独立验收（SettingsMutation/receipt 未就绪）。 */
    MUTATION_BASE_UNAVAILABLE,

    INTERNAL,
}

sealed interface WriteGateStatus {
    data object Enabled : WriteGateStatus

    data class Disabled(val reasons: List<WriteGateRejection>) : WriteGateStatus
}

/**
 * 管理写能力 gate：管理启动屏障（fence 恢复 + 回执对账）完成前关闭。
 * gate 只在 `apply` 准入检查；不通过的提案可创建/批准，但认领被拒。
 */
interface ManagementWriteGate {
    fun status(): WriteGateStatus
}

/** 生产 gate：`mutationAccepted` 由启动屏障完成后置位（[me.rerere.rikkihub.data.management.runtime.ManagementReadiness]）。 */
class EvidenceBasedWriteGate(
    private val mutationAccepted: () -> Boolean = { false },
) : ManagementWriteGate {
    override fun status(): WriteGateStatus =
        if (mutationAccepted()) WriteGateStatus.Enabled
        else WriteGateStatus.Disabled(listOf(WriteGateRejection.MUTATION_BASE_UNAVAILABLE))
}
