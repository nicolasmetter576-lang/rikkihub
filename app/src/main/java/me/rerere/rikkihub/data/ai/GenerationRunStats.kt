package me.rerere.rikkihub.data.ai

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge

/**
 * 一次 [GenerationLoop.generateText] 运行的事件驱动统计器。
 *
 * 同一个 assistant UIMessage 会承载多次模型生成, 因此 step 数与 token 总量
 * 只能从 [GenerationChunk.StepFinished] / [GenerationChunk.GenerationEnd]
 * 事件累计, 不能由消息条数或 `message.usage` 反推:
 * - step 数: 每个成功的 provider 步一次 StepFinished (网络重试不算额外步)
 * - token: 各 StepFinished.usage 累加; 任何一步未上报 usage 也不复用旧值,
 *   全程无 usage 时 [totalTokens] 为 null (不虚报 0)
 *
 * 该统计与任务是否达成无关: `COMPLETED` 只表示最后一步是纯文本自然结束。
 *
 * 线程模型: [onChunk] 经 [GenerationEventObserver] 在生成协程 (Dispatchers.IO)
 * 内调用, 而 [snapshot]/各 getter 可能被其他线程 (日志 IO、UI、测试) 读取。
 * 内部状态以单个不可变 [State] + synchronized 写入保证原子更新,
 * `volatile` 读使读取端永远拿到内部一致的快照, stepCount/totalTokens/
 * endReason 不会发生撕裂。需要多字段一致视图时用 [snapshot]。
 */
class GenerationRunStats {

    /** 内部一致性状态。读取端拿到的一份 State 必然对应同一次 onChunk 的结果。 */
    private data class State(
        val stepCount: Int = 0,
        val sawUsage: Boolean = false,
        val totalTokens: Long = 0,
        val endReason: GenerationChunk.GenerationEnd.Reason? = null,
        val lastFinishReason: String? = null,
    )

    @Volatile
    private var state = State()
    private val lock = Any()

    /** 已完成的逻辑生成步数 */
    val stepCount: Int get() = state.stepCount

    /** 各步 token 之和; 全程没有任何 usage 上报时为 null */
    val totalTokens: Long? get() = if (state.sawUsage) state.totalTokens else null

    /** 至少一个 StepFinished 携带了非空 usage */
    val sawUsageReport: Boolean get() = state.sawUsage

    /** 正常循环退出的终态; 流尚未结束时为 null */
    val endReason: GenerationChunk.GenerationEnd.Reason? get() = state.endReason

    /** 最近一步的 finishReason (provider 原文), 便于诊断 */
    val lastFinishReason: String? get() = state.lastFinishReason

    /** 一份内部一致的多字段快照 (单字段 getter 逐次读取可能跨越更新)。 */
    fun snapshot(): Snapshot = state.let {
        Snapshot(
            stepCount = it.stepCount,
            totalTokens = if (it.sawUsage) it.totalTokens else null,
            sawUsageReport = it.sawUsage,
            endReason = it.endReason,
            lastFinishReason = it.lastFinishReason,
        )
    }

    /** 与单字段 getter 语义一致的不可变快照。 */
    data class Snapshot(
        val stepCount: Int,
        val totalTokens: Long?,
        val sawUsageReport: Boolean,
        val endReason: GenerationChunk.GenerationEnd.Reason?,
        val lastFinishReason: String?,
    )

    fun onChunk(chunk: GenerationChunk) {
        when (chunk) {
            is GenerationChunk.StepFinished -> {
                val stepTotal = chunk.usage?.let { usage ->
                    (usage.totalTokens.toLong().takeIf { it > 0 }
                        ?: (usage.promptTokens.toLong() + usage.completionTokens.toLong()))
                        .coerceAtLeast(0)
                }
                synchronized(lock) {
                    state = state.copy(
                        stepCount = state.stepCount + 1,
                        sawUsage = state.sawUsage || (stepTotal != null),
                        totalTokens = state.totalTokens + (stepTotal ?: 0),
                        lastFinishReason = chunk.finishReason,
                    )
                }
            }

            is GenerationChunk.GenerationEnd -> synchronized(lock) {
                state = state.copy(endReason = chunk.reason)
            }

            is GenerationChunk.Messages -> {}
        }
    }
}

/**
 * 一次 provider 生成 attempt 内多个 `StreamChunk.Usage` 的步级归并。
 *
 * prompt/completion/cached 沿用 [TokenUsage.merge] 的"正值覆盖, 0 保留旧值"
 * (分片累计口径); `totalTokens` 是**快照口径**, 语义为「attempt 内最新的
 * 有效上报」, **不是历史 max**——provider 先报 105 再修正为 20 时取 20。
 * `TokenUsage` 没有"total 是否独立上报"字段, 只能按数值关系判定:
 *
 * 1. `other.totalTokens > 0 && != other.p + other.c`: 独立上报的 total
 *    (total-only 分片、含 cache/推理等额外口径、或不一致的修正快照)。
 *    取 `other.totalTokens`——最新独立快照, 允许向下修正。
 * 2. `other` 的 p/c 齐全 (p>0 且 c>0): 完整快照。此时 `other.total` 只会是
 *    缺失 (TokenUsage 默认 0) 或等于 p+c 的派生值 (p+c 不同的已进入 1),
 *    二者是同一逻辑上报, total 取新快照的 p+c——完整 p/c 新快照允许向下
 *    修正, 也覆盖更早的独立 total。
 * 3. 其余 (`other` 全零或只是部分分片): `other` 没有带来**新**的独立
 *    total 口径, 不能把在案独立 total (result.total != result.p+result.c
 *    可判定, 与 p+c 相等时二者无差别) 归零或抹去; 无在案独立 total 时回退
 *    到累计 p+c。这样 partial p/c 分片 (p=50,c=0,total 按 p+c 构造 = 50 →
 *    p=0,c=15,total = 15) 按累计得 65 而非 15, 也保留 `total > p+c` 的
 *    已上报口径。
 *
 * 已知歧义边界: `total == p+c` 无法区分「decoder 构造的派生值」与「恰好
 * 相等的独立上报」, 一律当派生值处理 (规则 2/3), 在案独立 total 不被部分
 * 分片改写; 完整 p/c 快照则无论其 total 字段缺失或相等都视为完整快照。
 * 全局 [TokenUsage.merge] 不变, message.usage 路径不受影响。
 */
internal fun mergeStepUsage(current: TokenUsage?, other: TokenUsage): TokenUsage {
    val merged = current.merge(other)
    val mergedDerivedTotal = merged.promptTokens + merged.completionTokens
    // current.total != p+c ⇒ 由前序「独立 total」分支写入, 在案独立上报;
    // total == p+c 时保留与否数值无差别, 无需单独标记。
    val reportedTotal = current
        ?.takeIf { it.totalTokens != it.promptTokens + it.completionTokens }
        ?.totalTokens
    val total = when {
        other.totalTokens > 0 && other.totalTokens != other.promptTokens + other.completionTokens ->
            other.totalTokens
        other.promptTokens > 0 && other.completionTokens > 0 ->
            other.promptTokens + other.completionTokens
        else -> reportedTotal ?: mergedDerivedTotal
    }
    return merged.copy(totalTokens = total)
}

/**
 * 子代理日志 / 结果 JSON 里 `completion_reason` 的可序列化取值。
 * 与 [GenerationChunk.GenerationEnd.Reason] 一一对应, 取 snake_case。
 */
val GenerationChunk.GenerationEnd.Reason.subagentCompletionReason: String
    get() = when (this) {
        GenerationChunk.GenerationEnd.Reason.COMPLETED -> "completed"
        GenerationChunk.GenerationEnd.Reason.STEP_LIMIT -> "step_limit"
        GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL -> "awaiting_approval"
    }
