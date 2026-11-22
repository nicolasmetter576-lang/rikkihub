package me.rerere.rikkihub.ui.components.message

import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import java.time.Duration

/** Numeric projection shared by the compact line and its details; no viewing-time clock. */
internal data class NerdStats(
    val total: TokenUsage,
    val lastUsage: TokenUsage?,
    val usageComplete: Boolean?,
    val steps: Int,
    val contextTokens: Int?,
    val outputTokens: Int?,
    val durationMillis: Long?,
    val estimated: Boolean,
    val tps: Double?,
) {
    // Partial/legacy persisted totals cannot distinguish an unreported field from an explicit zero.
    fun reportedTokens(value: Int): Int? = value.takeIf { it > 0 || (it == 0 && usageComplete == true) }
}

internal fun UIMessage.nerdStats(usage: TokenUsage): NerdStats {
    val last = lastRequestUsage()
    val context = last?.takeIf { it.promptTokens >= 0 && it.completionTokens >= 0 }
        ?.let { it.promptTokens.toLong() + it.completionTokens }
        ?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    // Only old messages may estimate from creation/completion. Exact zero/negative never fall back.
    val finishedAt = finishedAt
    val estimatedMillis = if (usageComplete == null && generationTimeMs == null && finishedAt != null) {
        runCatching {
            Duration.between(createdAt.toJavaLocalDateTime(), finishedAt.toJavaLocalDateTime()).toMillis()
        }.getOrNull()?.takeIf { it > 0 }
    } else null
    val durationMillis = if (finishedAt != null) {
        (generationTimeMs ?: estimatedMillis)?.takeIf { it > 0 }
    } else null
    val outputTokens = usage.completionTokens.takeIf { it > 0 || (it == 0 && usageComplete == true) }
    val rate = if (durationMillis != null && usageComplete != false && outputTokens != null && outputTokens > 0) {
        outputTokens.toDouble() / durationMillis * 1000
    } else null
    return NerdStats(
        total = usage,
        lastUsage = last?.takeIf { context != null },
        usageComplete = usageComplete,
        steps = stepCount?.takeIf { it > 0 } ?: 1,
        contextTokens = context,
        outputTokens = outputTokens,
        durationMillis = durationMillis,
        estimated = estimatedMillis != null,
        tps = rate?.takeIf { it.isFinite() && it > 0 },
    )
}
