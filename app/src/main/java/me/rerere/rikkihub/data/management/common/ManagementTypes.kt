package me.rerere.rikkihub.data.management.common

import kotlinx.serialization.Serializable

/**
 * 八类管理对象域。management 核心实现 Settings 侧五个域
 * （QUICK_MESSAGE / MODE_INJECTION / WORLD_BOOK / ASSISTANT /
 * MODEL_AND_PROMPT_DEFAULTS）；SUBAGENT_PRESET / USER_SKILL / CONVERSATION
 * 由 adapters 按同一契约实现。
 */
enum class ManagementDomain {
    QUICK_MESSAGE,
    MODE_INJECTION,
    WORLD_BOOK,
    ASSISTANT,
    MODEL_AND_PROMPT_DEFAULTS,
    SUBAGENT_PRESET,
    USER_SKILL,
    CONVERSATION,
}

/** 管理目标引用。id 为字符串（适配 Room 主键/文件名等非 UUID 目标）。 */
@Serializable
data class ObjectRef(val domain: ManagementDomain, val id: String) {
    override fun toString(): String = "${domain.name}:$id"
}

/**
 * opaque 版本令牌：storageEpoch + objectGeneration + revision。
 * 对外（模型/UI/提案 payload）只以 [encode] 字符串出现，不暴露凭据 hash；
 * 内部比较用结构化字段。
 */
@Serializable
data class VersionToken(
    val epoch: Long,
    val generation: Long,
    val revision: Long,
) {
    fun encode(): String = "$epoch:$generation:$revision"

    companion object {
        fun decode(s: String): VersionToken? {
            val parts = s.split(':')
            if (parts.size != 3) return null
            val e = parts[0].toLongOrNull() ?: return null
            val g = parts[1].toLongOrNull() ?: return null
            val r = parts[2].toLongOrNull() ?: return null
            return VersionToken(e, g, r)
        }
    }
}

/** read-set 断言项：对象级或集合级（含“没有其他引用”这类谓词断言）。 */
@Serializable
sealed interface ReadSetEntry {
    /** 指定对象的版本断言。 */
    @Serializable
    data class Object(val ref: ObjectRef, val expected: VersionToken) : ReadSetEntry

    /**
     * 集合/谓词修订断言。collectionId 约定（冻结，adapters 复用）：
     *  - "settings.quickMessages" / "settings.modeInjections" / "settings.lorebooks"
     *  - "settings.assistants" / "settings.assistantTags" / "settings.providers"
     *  - "settings.assistants[].quickMessageIds" 等引用集合
     *  - "settings.defaults.modelSlots" / "settings.defaults.prompts"
     *  - "room.conversations" / "room.conversation.<id>.nodes|meta"
     *  - "files.skills.catalog" / "files.skill.<name>.manifest"
     */
    @Serializable
    data class Collection(val collectionId: String, val expected: VersionToken) : ReadSetEntry
}

/** 提案读集：执行前最终核验的全部版本断言。 */
@Serializable
data class ReadSet(val entries: List<ReadSetEntry>)

enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * 提案差异行：结构化字段级差异。secret=true 的行绝不携带原文，
 * 只携带 hash/长度标记（原文进回收 payload，见 recovery/RecycleStore）。
 */
@Serializable
data class DiffLine(
    val path: String,
    val before: String? = null,
    val after: String? = null,
    val secret: Boolean = false,
)

/** 提案保留说明：回收材料是否完整、保留期、不可逆原因。 */
@Serializable
data class RetentionNote(
    /** 删除/覆盖类提案保留天数（回收材料；非删除类为 0）。 */
    val recycleDays: Int = 0,
    /** 恢复材料不完整（如 cipher 不可用）——不允许宣称完全 Undo。 */
    val recoveryMaterialsIncomplete: Boolean = false,
    /** 完全不可逆（无 inverse 且无回收材料）时的原因说明 key。 */
    val irreversibleReason: String? = null,
)
