package me.rerere.ai.claudelife

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Instant

/**
 * 一次真实 provider 请求的完整构建输入。在 GenerationLoop 调用 provider 之前捕获：
 * - [messages]：交给 provider 的最终消息（含 system、经全部输入 transformer 处理），重放原样使用；
 * - [params]：model、温度、topP、maxTokens、工具、推理强度、自定义 headers/body、sessionId；
 * - [providerSetting]：捕获时解析到的 provider（仅作标识/日志；重放时按最新设置重新解析认证）；
 * - [contextMessages]：构建前的会话层消息快照，只用于上下文有效性核对，不参与重放。
 *
 * 只驻内存，不持久化。内含完整 prompt，禁止写日志。
 */
data class CacheWarmRequest(
    val providerSetting: ProviderSetting,
    val messages: List<UIMessage>,
    val params: TextGenerationParams,
    val contextMessages: List<UIMessage>,
)

/** 一次重放的结果（完整结果 / 失败即停 / 用量只写日志）。 */
sealed interface CacheWarmReplayResult {
    data class Succeeded(val usage: TokenUsage? = null) : CacheWarmReplayResult
    data class Failed(val reason: String) : CacheWarmReplayResult
}

/**
 * 会话级缓存保活器：每个会话一个实例，同一时刻最多一个活动任务。
 *
 * 时间语义（全部取 [WarmTicker.nowMs]，生产为 elapsedRealtime）：
 * - 真实请求开始侧 [start] 即建立任务，取消旧任务及在途重放；
 * - 首次计划 = 开始侧时刻 + 间隔；成功重放后 = 本次重放开始侧时刻 + 间隔；
 * - 截止 = 最近一次真实请求开始侧时刻 + 上限，重放不延长；
 * - 唤醒晚于计划超过 15 秒 → 结束；重放失败 → 结束；
 * - 上下文不再适用 → 结束。因失败或上下文变化结束后，同一用户生成轮次内的后续请求不再
 *   重建任务，须等下一次用户发起请求（新轮次号，见 [newGeneration]）；
 * - 运行中照常保活，不看生成状态。
 *
 * 并发：状态读写经 [lock] 串行；注入回调（isCurrent/replay/log/onTaskEnded）一律锁外调用。
 */
class CacheWarmer(
    private val ticker: WarmTicker,
    private val scope: CoroutineScope,
    private val config: () -> CacheKeepAliveConfig,
    private val replay: suspend (CacheWarmRequest) -> CacheWarmReplayResult,
    private val log: (String) -> Unit = {},
    private val onTaskEnded: () -> Unit = {},
) {
    private val lock = Any()
    private var run: Run? = null
    private var generationCounter = 0L

    /**
     * 轮次封锁水位（单调）：所有 ≤ [blockedThrough] 的轮次号都被拒建。
     * 写入一律 `maxOf`——迟到的旧 observer / 旧 run 收尾不得回退水位覆盖更新的封锁；
     * 读写全部在 [lock] 内。
     */
    private var blockedThrough = Long.MIN_VALUE
    private var closed = false

    private val _status = MutableStateFlow<CacheWarmStatus>(CacheWarmStatus.Inactive())

    /** 三态状态，供 App / Web 输入框显示。 */
    val status: StateFlow<CacheWarmStatus> = _status.asStateFlow()

    /** 是否有活动任务（会话据此暂缓空闲回收）。 */
    val isActive: Boolean get() = synchronized(lock) { run != null }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    private class Run(
        val request: CacheWarmRequest,
        val generation: Long,
        val isCurrent: () -> String?,
        /** 真实请求开始侧时刻：上限起点。 */
        val startedAtMs: Long,
        /** 排期锚点：真实请求开始侧，或最近一次成功重放的开始侧。 */
        var anchorMs: Long,
        var nextWarmAtMs: Long = 0L,
        var refreshing: Boolean = false,
        /** 每次按新配置重排都会换一个排期循环；旧循环凭 epoch 不符自行退出。 */
        var epoch: Int = 0,
        var job: Job? = null,
    )

    /** 每次用户发起的生成（发送 / 重新生成 / 审批后继续）领取一个新轮次号。 */
    fun newGeneration(): Long = synchronized(lock) { ++generationCounter }

    /**
     * 真实请求开始侧调用：先取消旧任务及其在途重放，再以本次构建输入
     * 与开始侧时刻建立新任务。非 suspend、轻量，可在生成协程内同步调用。
     *
     * 建任务前先在锁外跑一次 [isCurrent]（observer 开始侧比内存 epoch）——
     * 快照已过期时绝不重放旧 prompt/旧工具参数，拒绝并封当前轮次。
     * 迟到者治理（审计修复）：
     * - 「当前轮次首次发现过期」仍先丢弃旧 run 再判定，旧任务不得在新基线旁继续存在；
     * - 但「轮次号已过时」（generation < counter 被新轮次超越，或该轮次已被 [blockedThrough]
     *   封过/复封）的迟到 start 只跳过自身，**不摘也不取消**当前 run、不改写其 status——否则
     *   锁外 precheck 与 stop/start 排序穿插时，旧 observer 会把刚建的新任务顶掉。此判定与
     *   本次锁外 precheck 的结果无关：迟到的 stale observer 同样只跳过自身（同步单调封锁其
     *   轮次），绝不能因为 staleReason != null 走丢弃路径摘掉新 run。
     */
    fun start(request: CacheWarmRequest, generation: Long, isCurrent: () -> String?) {
        val now = ticker.nowMs()
        val cfg = config()
        val staleReason = checkCurrent(isCurrent)
        var cancelJob: Job? = null
        var message: String
        var ended = false
        synchronized(lock) {
            // 封锁判定必须在水位更新之前取：stale 会先封本轮，读在后会把
            // 「本轮首次 stale」误判成「已封轮次」搞反两条路径。
            val wasBlocked = generation <= blockedThrough
            if (staleReason != null) {
                // 单调封锁：旧 observer 迟到时不得回退水位
                blockedThrough = maxOf(blockedThrough, generation)
            }
            val superseded = generation < generationCounter
            // 已封/被超越的迟到 start 只跳过自身，与 staleReason 无关——旧轮次锁外
            // precheck 恢复后即使判出过期，也不得摘/取消新 run。唯一走「丢弃旧 run」
            // 权威路径的是当前轮次首次发现过期（staleReason != null 且未超越、未先封）。
            val keepCurrentRun = superseded || wasBlocked
            val old = if (keepCurrentRun) null else run
            if (!keepCurrentRun) {
                run = null
                cancelJob = old?.job
            }
            val skipReason = when {
                keepCurrentRun -> if (superseded) {
                    "superseded by a newer generation"
                } else {
                    "stopped earlier in this generation; waiting for the next user request"
                }
                closed -> "session closed"
                !cfg.enabled -> "disabled"
                cfg.validate() != null -> "invalid config (${cfg.validate()})"
                staleReason != null -> "context changed: $staleReason"
                else -> cacheWarmIncompatibility(request.params)?.let { "parameter incompatible: $it" }
            }
            if (skipReason != null) {
                // 保留中的新任务时不动 status（Scheduled/Refreshing 仍如实反映当前 run）
                if (!keepCurrentRun || run == null) {
                    _status.value = CacheWarmStatus.Inactive(skipReason)
                }
                message = "skip start: $skipReason"
                ended = old != null
            } else {
                val newRun = Run(
                    request = request,
                    generation = generation,
                    isCurrent = isCurrent,
                    startedAtMs = now,
                    anchorMs = now,
                )
                run = newRun
                val stopReason = planNextLocked(newRun, now, cfg)
                if (stopReason != null) {
                    run = null
                    _status.value = CacheWarmStatus.Inactive(stopReason)
                    message = "skip start: $stopReason"
                    ended = old != null
                } else {
                    newRun.job = scope.launch { loop(newRun, newRun.epoch) }
                    message = "start model=${request.params.model.modelId} " +
                        "next=+${(newRun.nextWarmAtMs - now) / 1000}s " +
                        "deadline=+${cfg.maxDurationMs / 1000}s" +
                        if (old != null) " (replaced previous task)" else ""
                }
            }
        }
        cancelJob?.cancel()
        log(message)
        if (ended) onTaskEnded()
    }

    /**
     * 结束当前任务并取消在途重放。[blockGeneration] 为 true 时同一轮次内不再重建。
     *
     * 审计修复（TOCTOU）：run 摘取、轮次封锁与 status 必须在**同一次**
     * synchronized 内完成——旧实现「锁外取 run → 判空 → 再次加锁」之间插入的
     * start 会让第二次加锁只置 blocked/status，新 run 活着进 Inactive 继续重放。
     * 反之先取到旧 run 再走 [end] 时 run 已被新 start 替换，`run !== r` 白跑，
     * 本轮次根本没被封。现在封锁以调用时刻的 [generationCounter] 为准
     * （含 run.generation 已落后的场景），job 取消与 [onTaskEnded] 一律锁外。
     * 无 run：blocking 仍封当前轮次，non-blocking 为纯 no-op。
     */
    fun stop(reason: String, blockGeneration: Boolean = false) {
        var job: Job? = null
        var message: String? = null
        var ended = false
        synchronized(lock) {
            val r = run
            if (r == null) {
                if (!blockGeneration) return
                blockedThrough = maxOf(blockedThrough, generationCounter)
                _status.value = CacheWarmStatus.Inactive(reason)
                message = "stop without task reason=$reason (generation blocked)"
            } else {
                run = null
                if (blockGeneration) {
                    blockedThrough = maxOf(blockedThrough, generationCounter)
                }
                _status.value = CacheWarmStatus.Inactive(reason)
                job = r.job
                ended = true
                message = "stop reason=$reason"
            }
        }
        job?.cancel()
        message?.let { log(it) }
        if (ended) onTaskEnded()
    }

    /** 会话销毁：停止任务，并拒绝之后的 [start]。 */
    fun close() {
        synchronized(lock) { closed = true }
        stop("session closed")
    }

    /**
     * 上下文有效性复核：设置变化、会话结构性修改、删除等操作后立即调用；
     * 每次到点重放前也会调用。不再适用 → 结束任务并阻断本轮次。
     */
    fun revalidate() {
        val r = synchronized(lock) { run } ?: return
        val reason = checkCurrent(r.isCurrent) ?: return
        end(r, "context changed: $reason", blockGeneration = true, cancel = true)
    }

    /**
     * 全局配置变化：关闭 → 立即结束并取消在途重放；间隔/上限变化 → 按原
     * 锚点（真实请求开始侧 / 最近成功重放开始侧）与原上限起点立即重算，遵守上限与
     * 15 秒迟到规则。不把配置修改当成新请求，也不恢复已结束的任务。
     */
    fun onConfigChanged() {
        val cfg = config()
        val now = ticker.nowMs()
        var endReason: String? = null
        var target: Run? = null
        var oldJob: Job? = null
        synchronized(lock) {
            val r = run ?: return
            target = r
            when {
                !cfg.enabled -> endReason = "disabled"
                cfg.validate() != null -> endReason = "invalid config (${cfg.validate()})"
                r.refreshing -> Unit // 在途重放完成后按最新配置重算
                else -> {
                    endReason = planNextLocked(r, now, cfg)
                    if (endReason == null) {
                        oldJob = r.job
                        r.epoch += 1
                        val epoch = r.epoch
                        r.job = scope.launch { loop(r, epoch) }
                    }
                }
            }
        }
        oldJob?.cancel()
        val r = target ?: return
        val reason = endReason
        if (reason != null) {
            end(r, reason, blockGeneration = false, cancel = true)
        } else if (!r.refreshing) {
            log("reschedule next=+${(r.nextWarmAtMs - now) / 1000}s (config changed)")
        }
    }

    /**
     * 按 [r].anchorMs 计算下一次计划（锁内调用）。返回结束原因；null 表示已排期并更新状态。
     */
    private fun planNextLocked(r: Run, now: Long, cfg: CacheKeepAliveConfig): String? {
        val next = r.anchorMs + cfg.intervalMs
        val deadline = r.startedAtMs + cfg.maxDurationMs
        if (next > deadline || now >= deadline) return "max duration reached"
        if (now > next + CACHE_KEEP_ALIVE_LATE_TOLERANCE_MS) return "late more than 15s"
        r.nextWarmAtMs = next
        _status.value = CacheWarmStatus.Scheduled(next)
        return null
    }

    private suspend fun loop(r: Run, epoch: Int) {
        fun owned(): Boolean = run === r && r.epoch == epoch
        while (true) {
            val wait = synchronized(lock) {
                if (!owned()) return
                r.nextWarmAtMs - ticker.nowMs()
            }
            if (wait > 0) ticker.delayMs(wait)

            val cfg = config()
            val now = ticker.nowMs()
            val precheck = synchronized(lock) {
                if (!owned()) return
                when {
                    !cfg.enabled -> "disabled"
                    now > r.nextWarmAtMs + CACHE_KEEP_ALIVE_LATE_TOLERANCE_MS -> "late more than 15s"
                    else -> null
                }
            }
            if (precheck != null) {
                end(r, precheck, blockGeneration = false, cancel = false, epoch = epoch)
                return
            }
            checkCurrent(r.isCurrent)?.let {
                end(r, "context changed: $it", blockGeneration = true, cancel = false, epoch = epoch)
                return
            }

            val replayStartMs: Long
            synchronized(lock) {
                if (!owned()) return
                r.refreshing = true
                replayStartMs = ticker.nowMs()
                _status.value = CacheWarmStatus.Refreshing
            }
            log("replay start")
            // isCurrent 通过之后才到真正发请求：stop/close/start 若在这段窗口取消了
            // 本协程，绝不能把旧基线重放出去。
            currentCoroutineContext().ensureActive()
            val result = try {
                replay(r.request)
            } catch (e: CancellationException) {
                // 本任务被取消（新请求 / 关闭 / 会话销毁）→ 照常传播；
                // 非本任务取消引起的 CE（如内部超时）按失败处理，避免僵尸任务。
                if (!currentCoroutineContext().isActive) throw e
                CacheWarmReplayResult.Failed("cancelled (${e.javaClass.simpleName})")
            } catch (t: Throwable) {
                CacheWarmReplayResult.Failed(t.describe())
            }

            when (result) {
                is CacheWarmReplayResult.Failed -> {
                    end(r, "replay failed: ${result.reason}", blockGeneration = true, cancel = false, epoch = epoch)
                    return
                }

                is CacheWarmReplayResult.Succeeded -> {
                    log("replay ok ${result.usage.describe()}")
                    val stopReason = synchronized(lock) {
                        if (!owned()) return
                        r.refreshing = false
                        r.anchorMs = replayStartMs
                        val latest = config()
                        when {
                            !latest.enabled -> "disabled"
                            latest.validate() != null -> "invalid config (${latest.validate()})"
                            else -> planNextLocked(r, ticker.nowMs(), latest)
                        }
                    }
                    if (stopReason != null) {
                        end(r, stopReason, blockGeneration = false, cancel = false, epoch = epoch)
                        return
                    }
                }
            }
        }
    }

    private fun checkCurrent(isCurrent: () -> String?): String? = try {
        isCurrent()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        "context check failed (${t.javaClass.simpleName})"
    }

    /**
     * 结束 [r]（仅当它仍是当前任务）。[epoch] 非 null 表示调用来自排期循环：
     * 循环已被按新配置重排替换时不得结束任务（旧循环的判断基于旧计划）。
     */
    private fun end(r: Run, reason: String, blockGeneration: Boolean, cancel: Boolean, epoch: Int? = null) {
        val job = synchronized(lock) {
            if (run !== r) return
            if (epoch != null && r.epoch != epoch) return
            run = null
            if (blockGeneration) blockedThrough = maxOf(blockedThrough, r.generation)
            _status.value = CacheWarmStatus.Inactive(reason)
            r.job
        }
        if (cancel) job?.cancel()
        log("stop reason=$reason")
        onTaskEnded()
    }
}

private fun Throwable.describe(): String {
    val text = message?.lineSequence()?.firstOrNull()?.take(200)
    return if (text.isNullOrBlank()) javaClass.simpleName else "${javaClass.simpleName}: $text"
}

private fun TokenUsage?.describe(): String = if (this == null) {
    "usage=unavailable"
} else {
    "usage prompt=$promptTokens completion=$completionTokens cached=$cachedTokens total=$totalTokens"
}

/**
 * 内容核对：[current] 必须仍以 [baseline] 为前缀。
 * - 除最后一条外，逐条比较 id、角色与 parts（忽略推理段的时间戳）；
 * - 最后一条只比较 id 与角色：同一 assistant 消息在工具循环里会继续追加 part，
 *   属于「在原基线后追加」，不等同于修改前文；
 * - [current] 在基线之后多出的消息同样视为追加。
 */
fun cacheWarmPrefixIntact(baseline: List<UIMessage>, current: List<UIMessage>): Boolean {
    if (current.size < baseline.size) return false
    for (index in baseline.indices) {
        val base = baseline[index]
        val now = current[index]
        if (base.id != now.id || base.role != now.role) return false
        if (index == baseline.lastIndex) break
        if (base.parts === now.parts) continue
        if (base.parts.size != now.parts.size) return false
        for (partIndex in base.parts.indices) {
            if (base.parts[partIndex].forWarmComparison() != now.parts[partIndex].forWarmComparison()) {
                return false
            }
        }
    }
    return true
}

private val WARM_COMPARE_INSTANT = Instant.fromEpochMilliseconds(0)

private fun UIMessagePart.forWarmComparison(): UIMessagePart = when (this) {
    is UIMessagePart.Reasoning -> copy(createdAt = WARM_COMPARE_INSTANT, finishedAt = null)
    else -> this
}
