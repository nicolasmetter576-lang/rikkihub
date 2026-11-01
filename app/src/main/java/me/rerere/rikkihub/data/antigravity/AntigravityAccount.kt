package me.rerere.rikkihub.data.antigravity

import kotlinx.serialization.Serializable

@Serializable
data class AntigravityAccount(
    /** `antigravity:<邮箱>`，同邮箱重新登录覆盖旧记录。 */
    val id: String,
    val email: String,
    /** Cloud Code 项目（loadCodeAssist/onboardUser 得到），生成请求必须带。 */
    val projectId: String = "",
    val accessToken: String,
    val refreshToken: String,
    /** access token 到期时刻（epoch 毫秒）。 */
    val expiresAt: Long,
    val enabled: Boolean = true,
    val status: AntigravityTokenStatus = AntigravityTokenStatus.AVAILABLE,
    /** 429 冷却到期时刻（epoch 毫秒），0 = 不在冷却。 */
    val cooldownUntil: Long = 0,
    val quotas: List<AntigravityModelQuota> = emptyList(),
    val quotaUpdatedAt: Long = 0,
)

/** fetchAvailableModels 的 `quotaInfo`（服务端给才有）。 */
@Serializable
data class AntigravityModelQuota(
    val modelId: String,
    val displayName: String = modelId,
    /** 剩余比例 0..1。 */
    val remainingFraction: Double,
    /** 重置时刻（epoch 毫秒），0 = 未知。 */
    val resetAt: Long = 0,
)

@Serializable
enum class AntigravityTokenStatus {
    AVAILABLE,
    EXPIRED,
    INVALID,
}

@Serializable
internal data class AntigravityAccountState(
    val accounts: List<AntigravityAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)

/**
 * 能否接这次请求：启用、未作废、不在冷却；给了 [modelId] 时，该模型额度已用完且还没到重置时间的跳过。
 */
internal fun AntigravityAccount.isAvailable(
    modelId: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (!enabled || status == AntigravityTokenStatus.INVALID) return false
    if (cooldownUntil > nowMillis) return false
    if (modelId != null) {
        val quota = quotas.firstOrNull { it.modelId == modelId }
        if (quota != null && quota.remainingFraction <= 0.0 && (quota.resetAt == 0L || quota.resetAt > nowMillis)) {
            return false
        }
    }
    return true
}

internal fun selectAntigravityAccountIndex(
    accounts: List<AntigravityAccount>,
    startIndex: Int,
    modelId: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable(modelId, nowMillis)) return index
    }
    return null
}
