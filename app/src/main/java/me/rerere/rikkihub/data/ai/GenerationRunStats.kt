package me.rerere.rikkihub.data.ai

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
                // Provider normalization is the only fallback point; an explicit zero is valid.
                val stepTotal = chunk.usage?.totalTokens?.toLong()?.coerceAtLeast(0)
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
 * 子代理日志 / 结果 JSON 里 `completion_reason` 的可序列化取值。
 * 与 [GenerationChunk.GenerationEnd.Reason] 一一对应, 取 snake_case。
 */
val GenerationChunk.GenerationEnd.Reason.subagentCompletionReason: String
    get() = when (this) {
        GenerationChunk.GenerationEnd.Reason.COMPLETED -> "completed"
        GenerationChunk.GenerationEnd.Reason.STEP_LIMIT -> "step_limit"
        GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL -> "awaiting_approval"
    }
