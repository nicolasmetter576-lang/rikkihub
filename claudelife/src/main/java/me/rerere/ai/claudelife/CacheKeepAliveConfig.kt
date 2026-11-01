package me.rerere.ai.claudelife

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.TextGenerationParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 默认保活间隔（秒）：4 分 30 秒。 */
const val CACHE_KEEP_ALIVE_DEFAULT_INTERVAL_SECONDS: Int = 270

/** 默认保活上限（分钟）：自最近一次真实请求开始侧起算。 */
const val CACHE_KEEP_ALIVE_DEFAULT_MAX_DURATION_MINUTES: Int = 30

/** 唤醒时刻晚于本次计划保活时刻超过该值 → 结束当前任务。 */
const val CACHE_KEEP_ALIVE_LATE_TOLERANCE_MS: Long = 15_000L

/** 重放统一最小输出；具体协议字段由各 provider 适配器映射。 */
const val CACHE_WARM_REPLAY_MAX_TOKENS: Int = 1

/**
 * 重放不做应用层重试。重放不经过 GenerationLoop 的网络重试循环，
 * 由 [CacheWarmer] 单次调用 provider 流式适配器；失败即停。
 */
const val CACHE_WARM_REPLAY_MAX_RETRIES: Int = 0

/** 日志分类标识：所有保活日志行以此开头。 */
const val CACHE_WARM_LOG_CATEGORY: String = "cache_warm"

/**
 * 全局缓存保活配置。随普通全局设置持久化（DataStore
 * `cache_keep_alive_config`），App / Web 共用同一份；不再写入各模型。
 * 活动任务、基线与计时状态**不**在这里，也不持久化。
 */
@Serializable
data class CacheKeepAliveConfig(
    val enabled: Boolean = false,
    val intervalSeconds: Int = CACHE_KEEP_ALIVE_DEFAULT_INTERVAL_SECONDS,
    val maxDurationMinutes: Int = CACHE_KEEP_ALIVE_DEFAULT_MAX_DURATION_MINUTES,
) {
    val intervalMs: Long get() = intervalSeconds.toLong() * 1_000L
    val maxDurationMs: Long get() = maxDurationMinutes.toLong() * 60_000L
}

enum class CacheKeepAliveConfigError {
    /** 间隔必须是正整数秒。 */
    INTERVAL_NOT_POSITIVE,

    /** 上限必须是正整数分钟。 */
    MAX_DURATION_NOT_POSITIVE,

    /** 换算后的上限必须大于间隔。 */
    MAX_DURATION_NOT_GREATER_THAN_INTERVAL,
}

/** 输入校验；null 表示合法。不设人为的窄上限。 */
fun CacheKeepAliveConfig.validate(): CacheKeepAliveConfigError? = when {
    intervalSeconds <= 0 -> CacheKeepAliveConfigError.INTERVAL_NOT_POSITIVE
    maxDurationMinutes <= 0 -> CacheKeepAliveConfigError.MAX_DURATION_NOT_POSITIVE
    maxDurationMs <= intervalMs -> CacheKeepAliveConfigError.MAX_DURATION_NOT_GREATER_THAN_INTERVAL
    else -> null
}

/** 解析用户输入的正整数（去首尾空白）；非数字、0、负数、溢出一律 null。 */
fun parseCacheKeepAlivePositiveInt(text: String): Int? =
    text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 }

/**
 * 保活任务对外状态。时间字段是 [WarmTicker.nowMs] 的单调时钟值
 * （生产为 elapsedRealtime），UI 以同一时钟计算剩余时间；Web 在序列化时换算为墙钟。
 */
sealed interface CacheWarmStatus {
    /** 全局关闭 / 没有任务 / 任务已结束：UI 统一显示「未启用」。 */
    data class Inactive(val reason: String = REASON_NO_TASK) : CacheWarmStatus

    /** 已排期：下一次重放计划时刻（单调时钟毫秒）。 */
    data class Scheduled(val nextWarmAtMs: Long) : CacheWarmStatus

    /** 正在重放。 */
    data object Refreshing : CacheWarmStatus

    companion object {
        const val REASON_NO_TASK: String = "no task"
    }
}

/**
 * 输入框倒计时文本：`🔥 m:ss`，秒数向上取整、不小于 0，分钟不封顶。
 * 例：270_000 → `🔥 4:30`；269_001 → `🔥 4:30`；269_000 → `🔥 4:29`。
 */
fun formatCacheWarmCountdown(remainingMs: Long): String {
    val totalSeconds = ((remainingMs.coerceAtLeast(0L) + 999L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "\uD83D\uDD25 $minutes:${seconds.toString().padStart(2, '0')}"
}

/** 重放请求参数：只改最小输出，其余构建输入原样保留。 */
fun TextGenerationParams.forCacheWarmReplay(): TextGenerationParams =
    copy(maxTokens = CACHE_WARM_REPLAY_MAX_TOKENS)

private val OUTPUT_LIMIT_BODY_KEYS = setOf(
    "max_tokens",
    "max_output_tokens",
    "max_completion_tokens",
    "maxOutputTokens",
)

/**
 * 明确的请求参数兼容检查：只看无法与 `maxTokens=1` 配合的参数组合，
 * 不按协议类型或缓存声明筛选。返回 null 表示兼容；否则返回写入日志的原因。
 *
 * 1. customBody 注入 budget thinking（`thinking.type=="enabled"` 或含
 *    `budget_tokens`）：budget 须小于 max_tokens，且改变缓存 key。
 * 2. customBody 覆盖输出上限键：会压过适配器写入的 1，重放不再是最小输出。
 */
fun cacheWarmIncompatibility(params: TextGenerationParams): String? {
    for (body in params.customBody) {
        incompatibleCustomBody(body)?.let { return it }
    }
    return null
}

private fun incompatibleCustomBody(body: CustomBody): String? {
    val key = body.key.trim()
    val value = body.value
    if (key in OUTPUT_LIMIT_BODY_KEYS) {
        return "customBody overrides output token limit ($key)"
    }
    if (key == "generationConfig" && value is JsonObject && value.containsKey("maxOutputTokens")) {
        return "customBody overrides output token limit (generationConfig.maxOutputTokens)"
    }
    if (key == "generation_config" && value is JsonObject && value.containsKey("max_output_tokens")) {
        return "customBody overrides output token limit (generation_config.max_output_tokens)"
    }
    if (key == "thinking" && value is JsonObject) {
        val type = runCatching { value["type"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (type == "enabled" || value.containsKey("budget_tokens")) {
            return "budget thinking (customBody thinking) cannot be replayed with maxTokens=1"
        }
    }
    return null
}
