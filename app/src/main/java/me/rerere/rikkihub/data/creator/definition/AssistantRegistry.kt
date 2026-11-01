package me.rerere.rikkihub.data.creator.definition

import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * 保留助手 ID 被管理链（提案）当作普通对象引用。
 * 只声明"保留不可用"，不透露任何创造 schema 细节。
 */
class ReservedAssistantIdException(
    val id: Uuid,
) : IllegalStateException("Assistant id $id is reserved and cannot be managed as an ordinary assistant")

/**
 * 保留助手身份登记（2.8.0）。
 *
 * 创造助手是 `Settings.assistants` 的固定成员，可以像普通助手一样被选择和配置；
 * 这里的"普通"只针对**管理链**：创造助手提出的提案不能读取/修改创造助手自身，
 * 也不能管理创造助手的会话（[requireOrdinaryAssistantId] / [sanitizeOrdinaryAssistants]）。
 */
class AssistantRegistry {

    companion object {
        /** 全部保留助手 ID。当前仅创造助手；新增保留身份在此登记。 */
        val RESERVED_ASSISTANT_IDS: Set<Uuid> = setOf(CreatorAssistantDefinition.ID)

        fun isReservedAssistantId(id: Uuid): Boolean = id in RESERVED_ASSISTANT_IDS

        fun isReservedAssistantId(id: String): Boolean =
            runCatching { Uuid.parse(id) }.getOrNull()?.let { isReservedAssistantId(it) } == true
    }

    /** 管理链的统一拒绝点：保留 ID 不能作为提案目标。 */
    fun requireOrdinaryAssistantId(id: Uuid) {
        if (isReservedAssistantId(id)) throw ReservedAssistantIdException(id)
    }

    /** 管理链可见的助手（剔除保留 ID；幂等、纯函数）。 */
    fun sanitizeOrdinaryAssistants(assistants: List<Assistant>): List<Assistant> =
        assistants.filterNot { isReservedAssistantId(it.id) }

    fun sanitizeOrdinaryAssistants(settings: Settings): Settings =
        settings.copy(assistants = sanitizeOrdinaryAssistants(settings.assistants))

    /** 按 ID 解析管理链可见的助手；保留 ID/未知 ID → null。 */
    fun resolveOrdinaryAssistant(settings: Settings, id: Uuid): Assistant? {
        if (isReservedAssistantId(id)) return null
        return sanitizeOrdinaryAssistants(settings.assistants).find { it.id == id }
    }
}
