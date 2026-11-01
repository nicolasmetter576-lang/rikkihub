package me.rerere.rikkihub.claudelife

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.claudelife.CACHE_WARM_LOG_CATEGORY
import me.rerere.ai.claudelife.CacheKeepAliveConfig
import me.rerere.ai.claudelife.CacheWarmReplayResult
import me.rerere.ai.claudelife.CacheWarmRequest
import me.rerere.ai.claudelife.CacheWarmer
import me.rerere.ai.claudelife.WarmTicker
import me.rerere.ai.claudelife.forCacheWarmReplay
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import me.rerere.common.android.Logging
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider

private const val TAG = "ClaudeLife"

/**
 * 缓存保活（claudelife）的 :app 侧 owner（Koin single）。
 *
 * 职责：
 *  - 全局配置读取（`Settings.cacheKeepAliveConfig`）与变化分发：
 *    关闭 → 各会话 warmer 立即停止；间隔/上限变化 → 按原锚点重算；其他设置变化 →
 *    上下文复核（会话模型/provider 变更）。
 *  - 为每个会话创建独立 [CacheWarmer]；会话自持，本类只维护弱登记表用于广播。
 *  - 重放：按最新设置重新解析 provider（认证），经 provider 的
 *    `streamText` 流式适配器重新构建 payload/headers，`maxTokens=1`，单次调用
 *    （不经 GenerationLoop 的网络重试），等待流结束；不执行工具、不写消息。
 *  - 用量只写 `cache_warm` 日志。
 *
 * 全部运行态只驻内存，进程死即全停，不持久化。
 */
class ClaudeLifeManager(
    appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    private val ticker: WarmTicker = productionWarmTicker()

    /** warmer 计时协程域：appScope 的 SupervisorJob 子域。 */
    private val scope = CoroutineScope(
        appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job])
    )

    private val warmers = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<CacheWarmer, Boolean>()
    )

    @Volatile
    private var started = false

    /** 当前全局配置（App / Web 共用 settings 中同一份）。 */
    fun config(): CacheKeepAliveConfig = settingsStore.settingsFlow.value.cacheKeepAliveConfig

    /** 单调时钟毫秒（与 [me.rerere.ai.claudelife.CacheWarmStatus.Scheduled.nextWarmAtMs] 同一时钟）。 */
    fun nowMs(): Long = ticker.nowMs()

    /** 全局配置流（Web / App 页面观察用）。 */
    val configFlow: Flow<CacheKeepAliveConfig>
        get() = settingsStore.settingsFlow.map { it.cacheKeepAliveConfig }.distinctUntilChanged()

    /**
     * 为一个会话创建 warmer。[onTaskEnded] 在任务结束时回调（会话据此恢复
     * 正常空闲回收）。调用方负责在会话销毁时 [CacheWarmer.close]。
     */
    fun createWarmer(sessionLabel: String, onTaskEnded: () -> Unit = {}): CacheWarmer {
        val warmer = CacheWarmer(
            ticker = ticker,
            scope = scope,
            config = ::config,
            replay = { request -> replay(request) },
            log = { message -> log("session=$sessionLabel $message") },
            onTaskEnded = onTaskEnded,
        )
        synchronized(warmers) { warmers.add(warmer) }
        return warmer
    }

    /**
     * 幂等启动：settingsFlow 装载后订阅变化。配置变化 → onConfigChanged；
     * 其余设置变化 → revalidate（会话模型/provider 被改、删除等使旧基线失效）。
     */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            settingsStore.settingsFlow.first { !it.init }
            var previous: Settings? = null
            settingsStore.settingsFlow.collect { settings ->
                if (settings.init) return@collect
                val old = previous
                previous = settings
                if (old == null) return@collect
                try {
                    val snapshot = synchronized(warmers) { warmers.toList() }
                    if (old.cacheKeepAliveConfig != settings.cacheKeepAliveConfig) {
                        log("config changed enabled=${settings.cacheKeepAliveConfig.enabled} " +
                            "interval=${settings.cacheKeepAliveConfig.intervalSeconds}s " +
                            "max=${settings.cacheKeepAliveConfig.maxDurationMinutes}min")
                        snapshot.forEach { it.onConfigChanged() }
                    }
                    if (old.providers !== settings.providers || old.assistants !== settings.assistants ||
                        old.chatModelId != settings.chatModelId
                    ) {
                        snapshot.forEach { it.revalidate() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "settings dispatch failed", t)
                }
            }
        }
    }

    /**
     * 单次重放：从最新设置重新解析 provider（认证/baseUrl/customHeaders
     * 等随 provider 当前配置），provider 适配器重新构建 payload 与 headers；只把
     * `maxTokens` 改为 1，由适配器映射成协议字段。等待流完整结束才算成功。
     */
    internal suspend fun replay(request: CacheWarmRequest): CacheWarmReplayResult {
        val settings = settingsStore.settingsFlow.value
        val provider = resolveReplayProvider(request.params.model, request.providerSetting, settings)
            ?: return CacheWarmReplayResult.Failed("provider not found")
        if (!provider.enabled) return CacheWarmReplayResult.Failed("provider disabled")
        val impl = try {
            providerManager.getProviderByType(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return CacheWarmReplayResult.Failed("provider unavailable (${t.javaClass.simpleName})")
        }
        // 与 GenerationLoop（flowOn(IO)）一致：provider 可能在调用线程做阻塞 IO（取 token、读附件）
        return withContext(Dispatchers.IO) {
            runCacheWarmReplay(impl, provider, request, onNote = ::log)
        }
    }

    private fun log(message: String) {
        val line = "$CACHE_WARM_LOG_CATEGORY $message"
        Log.i(TAG, line)
        Logging.log(TAG, line)
    }
}

/**
 * 单次重放执行（纯逻辑，可注入假 provider 单测）：
 * - 只把 `maxTokens` 改为 1（[forCacheWarmReplay]），其余构建输入原样；
 * - 走 provider 的 `streamText` 流式适配器，不做应用层重试，
 *   一直收集到流结束才判定成功；
 * - 流中的消息/工具调用一律丢弃（不执行工具、不写消息），只累计 usage。
 */
internal suspend fun runCacheWarmReplay(
    impl: Provider<ProviderSetting>,
    provider: ProviderSetting,
    request: CacheWarmRequest,
    onNote: (String) -> Unit = {},
): CacheWarmReplayResult {
    val params = request.params.forCacheWarmReplay()
    var usage: TokenUsage? = null
    var finished = false
    return try {
        impl.streamText(
            providerSetting = provider,
            messages = request.messages,
            params = params,
        ).collect { chunk ->
            when (chunk) {
                is StreamChunk.Usage -> usage = usage.merge(chunk.usage)
                is StreamChunk.Finish -> finished = true
                else -> Unit
            }
        }
        if (!finished) onNote("replay stream ended without finish event")
        CacheWarmReplayResult.Succeeded(usage)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        val text = t.message?.lineSequence()?.firstOrNull()?.take(200)
        CacheWarmReplayResult.Failed(
            if (text.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $text"
        )
    }
}

/**
 * 重放时的 provider 重新解析：按模型 id 在最新设置中找回模型与 provider
 * （含 providerOverwrite）；找不到时退回捕获时的 provider 设置。
 */
internal fun resolveReplayProvider(
    model: Model,
    captured: ProviderSetting,
    settings: Settings,
): ProviderSetting? {
    val latestModel = settings.findModelById(model.id) ?: return captured
    return latestModel.findProvider(settings.providers) ?: captured
}
