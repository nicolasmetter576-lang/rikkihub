package me.rerere.rikkihub.data.management.proposal

class IllegalProposalTransition(
    from: ProposalStatus,
    to: ProposalStatus,
) : IllegalStateException("Illegal proposal transition $from -> $to")

/**
 * 提案状态机唯一权威。所有状态写入必须经 [require]；
 * service/coordinator 在持久层事务内先读当前状态再调用，保持原子转移。
 */
object ProposalStateMachine {

    /** 非终态判定（含 NEEDS_RECOVERY——它不是终态，等待原生恢复动作）。 */
    fun isOpen(status: ProposalStatus): Boolean = !isTerminal(status)

    fun isTerminal(status: ProposalStatus): Boolean = when (status) {
        ProposalStatus.APPLIED, ProposalStatus.REJECTED, ProposalStatus.EXPIRED,
        ProposalStatus.CANCELLED, ProposalStatus.CONFLICT, ProposalStatus.FAILED -> true

        ProposalStatus.NEEDS_RECOVERY, ProposalStatus.DRAFT,
        ProposalStatus.PENDING_APPROVAL, ProposalStatus.APPROVED,
        ProposalStatus.APPLYING -> false
    }

    fun canTransition(from: ProposalStatus, to: ProposalStatus): Boolean =
        allowed[from]?.contains(to) == true

    fun require(from: ProposalStatus, to: ProposalStatus) {
        if (!canTransition(from, to)) throw IllegalProposalTransition(from, to)
    }

    private val allowed: Map<ProposalStatus, Set<ProposalStatus>> = mapOf(
        ProposalStatus.DRAFT to setOf(
            ProposalStatus.PENDING_APPROVAL,
            ProposalStatus.CANCELLED,
            ProposalStatus.EXPIRED,
        ),
        ProposalStatus.PENDING_APPROVAL to setOf(
            ProposalStatus.APPROVED,
            ProposalStatus.REJECTED,
            ProposalStatus.EXPIRED,
            ProposalStatus.CANCELLED,
        ),
        ProposalStatus.APPROVED to setOf(
            ProposalStatus.APPLYING,     // 仅经原子认领（claimApprovedForApply）
            ProposalStatus.CANCELLED,    // 未被认领前原子撤销
            ProposalStatus.EXPIRED,      // 批准自身期限到期
        ),
        ProposalStatus.APPLYING to setOf(
            ProposalStatus.APPLIED,
            ProposalStatus.FAILED,
            ProposalStatus.NEEDS_RECOVERY,
            ProposalStatus.CONFLICT,     // 执行前最终 read-set 核验失败
        ),
        // 恢复动作：需新的有限原生批准
        ProposalStatus.NEEDS_RECOVERY to setOf(
            ProposalStatus.APPLIED,      // RESUME_VERIFY：核对全部已完成
            ProposalStatus.FAILED,       // 补偿完成 / TERMINATE
            ProposalStatus.CANCELLED,    // 原生终结并放弃
            ProposalStatus.CONFLICT,     // 核对发现 after-token 已变
        ),
        // 终态无出边
        ProposalStatus.APPLIED to emptySet(),
        ProposalStatus.REJECTED to emptySet(),
        ProposalStatus.EXPIRED to emptySet(),
        ProposalStatus.CANCELLED to emptySet(),
        ProposalStatus.CONFLICT to emptySet(),
        ProposalStatus.FAILED to emptySet(),
    )
}
