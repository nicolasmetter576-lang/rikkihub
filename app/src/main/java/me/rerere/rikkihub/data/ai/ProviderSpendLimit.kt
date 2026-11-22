package me.rerere.rikkihub.data.ai

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.event.AppEvent
import me.rerere.rikkihub.data.event.AppEventBus
import java.util.Locale

/**
 * 一个提供商的消费上限。
 *
 * @param limit 上限（美元，按模型当前填写的价格估算；没填价格的模型不计）
 * @param since 从这一刻之后的用量开始累计（设置上限或解除限制的时间）
 * @param blocked 已经达到上限：该提供商的模型不再发请求，直到用户解除
 */
@Serializable
data class ProviderSpendLimit(
    val limit: Double,
    val since: Long,
    val blocked: Boolean = false,
)

class ProviderSpendLimitException(message: String) : IllegalStateException(message)

/**
 * 消费上限的执行处：挂在 [UsageRecorder] 上，所有记用量的请求（对话、子代理、标题、翻译……）
 * 发出前先 [check]，每记一条用量后 [evaluate]。
 */
class ProviderSpendGuard(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val stats: UsageStatsService,
    private val eventBus: AppEventBus,
) {
    fun check(provider: ProviderSetting) {
        val limit = settingsStore.settingsFlow.value.providerSpendLimits[provider.id] ?: return
        if (limit.blocked) throw ProviderSpendLimitException(message(provider.name, limit.limit))
    }

    suspend fun evaluate(provider: ProviderSetting) {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val limit = settings.providerSpendLimits[provider.id]?.takeIf { !it.blocked } ?: return
        if (stats.providerCost(settings.providers, provider.id.toString(), limit.since) < limit.limit) return
        var tripped = false
        settingsStore.update { latest ->
            val current = latest.providerSpendLimits[provider.id]
            // 判定期间用户改了上限或解除了限制：按新的来，下一条用量再判
            if (current != limit) return@update latest
            tripped = true
            latest.copy(providerSpendLimits = latest.providerSpendLimits + (provider.id to limit.copy(blocked = true)))
        }
        if (tripped) eventBus.emit(AppEvent.ProviderSpendLimitReached(message(provider.name, limit.limit)))
    }

    private fun message(providerName: String, limit: Double) = context.getString(
        R.string.provider_spend_limit_reached,
        providerName,
        formatSpendLimit(limit),
    )
}

fun formatSpendLimit(limit: Double): String = "$" + "%.2f".format(Locale.US, limit).trimEnd('0').trimEnd('.')
