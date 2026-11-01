package me.rerere.rikkihub.data.devin

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.providers.devin.DevinApiException

@Serializable
data class DevinAccount(
    val id: String,
    val userName: String = "",
    val email: String = "",
    val userId: String = "",
    val orgId: String = "",
    val plan: String = "",
    val sessionToken: String,
    val enabled: Boolean = true,
    val status: DevinAccountStatus = DevinAccountStatus.ACTIVE,
    val dailyQuotaRemainingPercent: Long = 100,
    val weeklyQuotaRemainingPercent: Long = 100,
    /** epoch 秒, 0 = 未知 */
    val dailyQuotaResetAt: Long = 0,
    val weeklyQuotaResetAt: Long = 0,
)

@Serializable
enum class DevinAccountStatus {
    ACTIVE,
    INVALID,
}

@Serializable
internal data class DevinAccountState(
    val accounts: List<DevinAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)

/**
 * 能否用这个账户发请求: 只看开关与 token 是否有效.
 * 日/周配额只做展示, 不在本地拦截: Devin 有不计配额的免费模型, 是否拒绝交给上游判断.
 * 上游明确报配额耗尽后, 仓库只对该账户 + 该模型记冷却, 用于多账户时优先换号.
 */
internal fun DevinAccount.isUsable(): Boolean =
    enabled && status != DevinAccountStatus.INVALID

internal enum class DevinQuotaPeriod { DAILY, WEEKLY }

/**
 * 上游错误是否明确是账户日/周配额耗尽 (等价 CPA DevinUsageQuotaExhaustionPeriod):
 * 只认 429, 且消息同时含 "quota"、"exhausted" 和周期词; 两个周期都提到时按周算.
 * 月度 ACU、普通限流、容量不足都不算.
 */
internal fun devinQuotaExhaustionPeriod(error: Throwable): DevinQuotaPeriod? {
    if ((error as? DevinApiException)?.statusCode != 429) return null
    val lower = error.message.orEmpty().lowercase()
    if ("quota" !in lower || "exhausted" !in lower) return null
    return when {
        "weekly" in lower -> DevinQuotaPeriod.WEEKLY
        "daily" in lower -> DevinQuotaPeriod.DAILY
        else -> null
    }
}

/**
 * 配额耗尽后的冷却截止 (epoch 毫秒): 账户上记录的对应重置时间若在未来 8 天内就用它
 * (等价 CPA DevinQuotaRetryAfter 的合理性检查), 否则冷却 [DEVIN_QUOTA_FALLBACK_COOLDOWN_MILLIS].
 */
internal fun DevinAccount.quotaCooldownUntil(period: DevinQuotaPeriod, nowMillis: Long): Long {
    val resetAt = when (period) {
        DevinQuotaPeriod.DAILY -> dailyQuotaResetAt
        DevinQuotaPeriod.WEEKLY -> weeklyQuotaResetAt
    }
    val delay = resetAt * 1000 - nowMillis
    return if (resetAt > 0 && delay > 0 && delay <= DEVIN_QUOTA_RESET_MAX_DELAY_MILLIS) {
        resetAt * 1000
    } else {
        nowMillis + DEVIN_QUOTA_FALLBACK_COOLDOWN_MILLIS
    }
}

/** 冷却按模型记: 去掉 devin/ 前缀, 大小写不敏感 */
internal fun devinCooldownKey(modelId: String): String =
    modelId.trim().lowercase().removePrefix("devin/")

private const val DEVIN_QUOTA_RESET_MAX_DELAY_MILLIS = 8L * 24 * 60 * 60 * 1000
internal const val DEVIN_QUOTA_FALLBACK_COOLDOWN_MILLIS = 60L * 60 * 1000
