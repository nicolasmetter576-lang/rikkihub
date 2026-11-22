package me.rerere.tavern.worldinfo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 全局世界书设置，键名和默认值照 `ST:public/scripts/world-info.js:69-82`（settings.json 里的 world_info_* 键）。
 */
@Serializable
data class WorldInfoSettings(
    /** 扫描深度：往回看多少条消息。 */
    @SerialName("world_info_depth") val depth: Int = 2,
    @SerialName("world_info_min_activations") val minActivations: Int = 0,
    /** 最少激活数的深度上限；0 表示只受聊天长度限制。 */
    @SerialName("world_info_min_activations_depth_max") val minActivationsDepthMax: Int = 0,
    /** 预算占上下文的百分比。 */
    @SerialName("world_info_budget") val budget: Int = 25,
    @SerialName("world_info_include_names") val includeNames: Boolean = true,
    @SerialName("world_info_recursive") val recursive: Boolean = false,
    /** 超预算时是否提醒用户；引擎只在结果里报告 `budgetOverflowed`，提醒由调用方做。 */
    @SerialName("world_info_overflow_alert") val overflowAlert: Boolean = false,
    @SerialName("world_info_case_sensitive") val caseSensitive: Boolean = false,
    @SerialName("world_info_match_whole_words") val matchWholeWords: Boolean = false,
    @SerialName("world_info_use_group_scoring") val useGroupScoring: Boolean = false,
    /** [WorldInfoInsertionStrategy]。 */
    @SerialName("world_info_character_strategy") val characterStrategy: Int = WorldInfoInsertionStrategy.CHARACTER_FIRST,
    /** 预算绝对上限；0 表示不限。 */
    @SerialName("world_info_budget_cap") val budgetCap: Int = 0,
    /** 最大递归步数；非 0 时最少激活数不生效。 */
    @SerialName("world_info_max_recursion_steps") val maxRecursionSteps: Int = 0,
) {
    /** ST:public/scripts/world-info.js:955 旧设置迁移：预算超过 100 当作旧值，重置为 25。 */
    fun normalized(): WorldInfoSettings = if (budget > 100) copy(budget = 25) else this
}
