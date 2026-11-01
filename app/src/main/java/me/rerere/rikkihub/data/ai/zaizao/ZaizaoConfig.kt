package me.rerere.rikkihub.data.ai.zaizao

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

/** 再造的类型：修改（局部改写）、增量（补充新内容）、重写（抽出事件与信息后换一种写法从零写）。每轮单独选。 */
@Serializable
enum class ZaizaoType { MODIFY, INCREMENT, REWRITE }

/**
 * 再造设置（`Settings.zaizao`，存一个 JSON 偏好键）。[modelId] 为 null = 当前助手的对话模型；温度、Top P、
 * 最大输出为 null = 模型默认。三个类型的提示词为 null = 内置默认（[ZaizaoPrompts]），以后改默认值时没改过的用户自动跟上。
 * [recentRequirements] 最新在前，最多 [MAX_RECENT] 条。文件类型与推敲共用（`TuiqiaoConfig.enabledFor`）。
 */
@Serializable
data class ZaizaoConfig(
    val modelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val includeAfter: Boolean = true,
    val defaultType: ZaizaoType = ZaizaoType.MODIFY,
    val modifyPrompt: String? = null,
    val incrementPrompt: String? = null,
    val rewritePrompt: String? = null,
    val recentRequirements: List<String> = emptyList(),
) {
    val effectiveTemperature: Float? get() = temperature?.takeIf { it.isFinite() }?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
    val effectiveTopP: Float? get() = topP?.takeIf { it.isFinite() }?.coerceIn(MIN_TOP_P, MAX_TOP_P)
    val effectiveMaxTokens: Int? get() = maxTokens?.takeIf { it > 0 }

    fun promptFor(type: ZaizaoType): String = when (type) {
        ZaizaoType.MODIFY -> modifyPrompt
        ZaizaoType.INCREMENT -> incrementPrompt
        ZaizaoType.REWRITE -> rewritePrompt
    }?.takeIf { it.isNotBlank() } ?: ZaizaoPrompts.defaultFor(type)

    fun withPrompt(type: ZaizaoType, prompt: String?): ZaizaoConfig = when (type) {
        ZaizaoType.MODIFY -> copy(modifyPrompt = prompt)
        ZaizaoType.INCREMENT -> copy(incrementPrompt = prompt)
        ZaizaoType.REWRITE -> copy(rewritePrompt = prompt)
    }

    /** 记下刚发出的一条要求：去两端空白，已有的挪到最前，最多保留 [MAX_RECENT] 条。 */
    fun remember(requirement: String): ZaizaoConfig {
        val text = requirement.trim()
        if (text.isEmpty()) return this
        return copy(recentRequirements = (listOf(text) + recentRequirements.filter { it != text }).take(MAX_RECENT))
    }

    fun forget(requirement: String): ZaizaoConfig = copy(recentRequirements = recentRequirements.filter { it != requirement })

    companion object {
        const val MAX_RECENT = 10
        const val MAX_REQUIREMENT_CHARS = 2000
        const val MAX_PROMPT_CHARS = 4000
        const val MIN_TEMPERATURE = 0f
        const val MAX_TEMPERATURE = 2f
        const val MIN_TOP_P = 0f
        const val MAX_TOP_P = 1f
    }
}
