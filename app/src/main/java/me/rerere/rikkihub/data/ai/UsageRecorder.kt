package me.rerere.rikkihub.data.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageAccumulator
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.db.dao.UsageRecordDAO
import me.rerere.rikkihub.data.db.entity.UsageRecordEntity
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

private const val TAG = "UsageRecorder"

/** 用量从哪类请求来，按名称存进 usage_record.source（改名会让旧记录对不上，只能加不能改） */
enum class UsageSource {
    CHAT,
    COMPRESSION,
    TITLE,
    SUGGESTION,
    TRANSLATION,
    MEMORY,
    OCR,
    POLISH,
    TUIQIAO,
    ZAIZAO,
    SUBAGENT,
    KEEPALIVE,
    QUBAGU,
    CLI,
}

/** 记录用量时附带的归属信息 */
data class UsageTag(
    val source: UsageSource,
    val assistantId: Uuid? = null,
    val conversationId: Uuid? = null,
)

/**
 * 多账号提供商在请求里选定账号后写进来，记录器据此把这次用量记到该账号名下（额度反推用）。
 * 不在记录器包装的调用里时没有这个元素，[reportUsageAccount] 什么也不做。
 */
class UsageAccountSlot : AbstractCoroutineContextElement(Key) {
    @Volatile
    var accountKey: String? = null

    @Volatile
    internal var quotaReadings: List<QuotaReading> = emptyList()

    companion object Key : CoroutineContext.Key<UsageAccountSlot>
}

/** 提供商选定（或换了）账号时调用；[accountKey] 形如 `devin:<账号 id>` */
suspend fun reportUsageAccount(accountKey: String) {
    currentCoroutineContext()[UsageAccountSlot]?.accountKey = accountKey
}

/**
 * 把每次模型请求的 usage 写进 usage_record。
 *
 * 发起请求的地方用 [wrap] 包一层 provider，每次 generateText / streamText 结束时记一行；
 * 重试的每次尝试都是独立的请求，各记各的。流式请求按 [UsageAccumulator] 合并同一请求内的
 * 用量快照（与消息上的统计同一口径：保留明确的 0 和提供商报的 total），中途取消或出错时
 * 已经产生的用量也会记下。写库在 [scope] 里异步进行，失败只打日志，不影响请求本身。
 */
class UsageRecorder(
    private val dao: UsageRecordDAO,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 记了一条带账号的用量后回调（额度刷新用） */
    @Volatile
    var onAccountUsage: ((String) -> Unit)? = null

    @Volatile
    internal var onQuotaReadings: ((List<QuotaReading>) -> Unit)? = null

    /** 提供商消费上限：请求发出前拦截、记完用量后判定 */
    @Volatile
    var spendGuard: ProviderSpendGuard? = null

    fun <T : ProviderSetting> wrap(provider: Provider<T>, tag: UsageTag): Provider<T> =
        UsageRecordingProvider(provider, tag)

    suspend fun recordCliUsage(
        agentId: String,
        agentName: String,
        modelName: String?,
        conversationId: Uuid,
        usage: TokenUsage,
    ) {
        val now = clock()
        val record = UsageRecordEntity(
            createdAt = now,
            startedAt = now,
            usageComplete = true,
            source = UsageSource.CLI.name,
            providerId = "cli:$agentId",
            modelId = null,
            modelName = modelName?.takeIf { it.isNotBlank() } ?: agentName,
            conversationId = conversationId.toString(),
            promptTokens = usage.promptTokens.toLong(),
            completionTokens = usage.completionTokens.toLong(),
            cachedTokens = usage.cachedTokens.toLong(),
            totalTokens = usage.totalTokens.toLong(),
        )
        runCatching { dao.insert(record) }
            .onFailure { Log.w(TAG, "Failed to record CLI usage", it) }
    }

    internal fun record(
        tag: UsageTag,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        usage: TokenUsage,
        accountKey: String?,
        startedAt: Long = clock(),
        usageComplete: Boolean = true,
        quotaReadings: List<QuotaReading> = emptyList(),
    ) {
        val record = UsageRecordEntity(
            createdAt = clock(),
            startedAt = startedAt,
            usageComplete = usageComplete,
            source = tag.source.name,
            providerId = providerSetting.id.toString(),
            modelId = params.model.id.toString(),
            modelName = params.model.modelId,
            assistantId = tag.assistantId?.toString(),
            conversationId = tag.conversationId?.toString(),
            accountKey = accountKey,
            promptTokens = usage.promptTokens.toLong(),
            completionTokens = usage.completionTokens.toLong(),
            cachedTokens = usage.cachedTokens.toLong(),
            totalTokens = usage.totalTokens.toLong(),
        )
        // Room 的 suspend 写入自己会切到后台线程
        scope.launch {
            runCatching { dao.insert(record) }
                .onSuccess {
                    accountKey?.let { key -> onAccountUsage?.invoke(key) }
                    if (quotaReadings.isNotEmpty()) onQuotaReadings?.invoke(quotaReadings)
                    runCatching { spendGuard?.evaluate(providerSetting) }
                        .onFailure { Log.w(TAG, "Failed to evaluate spend limit", it) }
                }
                .onFailure { Log.w(TAG, "Failed to record usage", it) }
        }
    }

    private inner class UsageRecordingProvider<T : ProviderSetting>(
        private val delegate: Provider<T>,
        private val tag: UsageTag,
    ) : Provider<T> by delegate {
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            spendGuard?.check(providerSetting)
            val slot = UsageAccountSlot()
            val startedAt = clock()
            val result = withContext(slot) { delegate.generateText(providerSetting, messages, params) }
            (result.usage ?: TokenUsage().takeIf { slot.quotaReadings.isNotEmpty() })?.let {
                record(tag, providerSetting, params, it, slot.accountKey, startedAt,
                    result.hasCompleteUsage == true, slot.quotaReadings)
            }
            return result
        }

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            spendGuard?.check(providerSetting)
            return flow {
                val slot = UsageAccountSlot()
                val startedAt = clock()
                val usage = UsageAccumulator()
                try {
                    val upstream = withContext(slot) { delegate.streamText(providerSetting, messages, params) }
                        .flowOn(slot)
                    upstream.collect { chunk ->
                        if (chunk is StreamChunk.Usage) usage.update(chunk.update)
                        emit(chunk)
                    }
                } finally {
                    (usage.snapshot ?: TokenUsage().takeIf { slot.quotaReadings.isNotEmpty() })?.let {
                        record(tag, providerSetting, params, it, slot.accountKey, startedAt,
                            usage.hasCompleteUsage, slot.quotaReadings)
                    }
                }
            }
        }
    }
}

/** 没有记录器（单测、预览）时原样返回 */
fun <T : ProviderSetting> UsageRecorder?.wrapOrSelf(provider: Provider<T>, tag: UsageTag): Provider<T> =
    this?.wrap(provider, tag) ?: provider
