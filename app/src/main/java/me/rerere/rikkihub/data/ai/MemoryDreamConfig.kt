package me.rerere.rikkihub.data.ai

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_MEMORY_DREAM_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_MEMORY_FULL_REVIEW_PROMPT
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * 「做梦」（对话停下来后在后台整理记忆库）的全局设置，`Settings.memoryDream`。
 *
 * 只作用于开了记忆的助手；助手自己的「自动做梦」开关（`Assistant.autoConsolidateMemory`）和这里的
 * [autoDream] 都开着才会自动整理，否则只记下待整理的轮数，等用户在记忆页手动「立即做梦」。
 */
@Serializable
data class MemoryDreamConfig(
    /** 总开关：关掉后所有助手都只在手动时整理 */
    val autoDream: Boolean = true,
    /** 一轮对话结束后空闲多久开始整理（分钟） */
    val idleMinutes: Int = DEFAULT_IDLE_MINUTES,
    /** 攒够这么多轮没整理就不再等空闲 */
    val maxPendingTurns: Int = DEFAULT_MAX_PENDING_TURNS,
    /** 离开对话（会话被回收）时立即整理 */
    val dreamOnLeave: Boolean = true,
    /** 应用切到后台时立即整理：之后空闲计时不一定还能走完 */
    val dreamOnBackground: Boolean = true,
    /** 应用启动时补上次进程被杀前没来得及整理的对话 */
    val catchUpOnStartup: Boolean = true,
    /** 整理用的模型；null 时用支持工具的快速模型，快速模型不支持工具时用助手的对话模型 */
    val modelId: Uuid? = null,
    /** 一次整理里模型最多调用几步工具 */
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** 整理的 system prompt；空时用默认值，`{memory_format}` 会展开成写入格式规则 */
    val prompt: String? = null,
    /** 手动「立即做梦」时追加的全库整理要求；空时用默认值 */
    val fullReviewPrompt: String? = null,
) {
    val idleDelay: Duration get() = idleMinutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES).minutes

    val effectiveMaxPendingTurns: Int get() = maxPendingTurns.coerceIn(MIN_PENDING_TURNS, MAX_PENDING_TURNS)

    val effectiveMaxSteps: Int get() = maxSteps.coerceIn(MIN_STEPS, MAX_STEPS)

    val effectivePrompt: String get() = prompt?.takeIf { it.isNotBlank() } ?: DEFAULT_MEMORY_DREAM_PROMPT

    val effectiveFullReviewPrompt: String
        get() = fullReviewPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_MEMORY_FULL_REVIEW_PROMPT

    companion object {
        const val DEFAULT_IDLE_MINUTES = 2
        const val MIN_IDLE_MINUTES = 1
        const val MAX_IDLE_MINUTES = 60
        const val DEFAULT_MAX_PENDING_TURNS = 6
        const val MIN_PENDING_TURNS = 1
        const val MAX_PENDING_TURNS = 20
        const val DEFAULT_MAX_STEPS = 32
        const val MIN_STEPS = 4
        const val MAX_STEPS = 64
        const val MAX_PROMPT_CHARS = 32_000
    }
}
