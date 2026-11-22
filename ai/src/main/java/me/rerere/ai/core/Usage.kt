package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

/**
 * One request's reported fields, not a delta to add. Null means unreported; zero is an explicit
 * correction. [totalTokens] is ONLY an upstream total, never a locally computed p+c.
 * [partial] describes known incompleteness in the current snapshot (e.g. a folded Claude pass).
 * This transport type is deliberately separate from persisted [TokenUsage].
 */
@Serializable
data class UsageUpdate(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    val totalTokens: Int? = null,
    val partial: Boolean = false,
) {
    /** For callers that already have a fully materialized snapshot, not for wire decoders. */
    constructor(usage: TokenUsage, partial: Boolean = false) : this(
        usage.promptTokens, usage.completionTokens, usage.cachedTokens, usage.totalTokens, partial,
    )
}

/** Fixed-size state for exactly one attempt. Repeated snapshots replace; successful steps sum. */
class UsageAccumulator {
    private var promptTokens: Int? = null
    private var completionTokens: Int? = null
    private var cachedTokens: Int? = null
    private var totalTokens: Int? = null
    private var partial = false

    val snapshot: TokenUsage?
        get() {
            if (promptTokens == null && completionTokens == null && cachedTokens == null && totalTokens == null) {
                return null
            }
            return TokenUsage(
                promptTokens = promptTokens ?: 0,
                completionTokens = completionTokens ?: 0,
                cachedTokens = cachedTokens ?: 0,
                totalTokens = totalTokens ?: ((promptTokens ?: 0) + (completionTokens ?: 0)),
            )
        }

    /** Input/output were reported and cache is a consistent subset; total remains independent. */
    val hasCompleteUsage: Boolean
        get() = promptTokens != null && completionTokens != null && !partial &&
            (cachedTokens ?: 0) <= (promptTokens ?: 0)

    fun update(update: UsageUpdate) {
        var reported = false
        update.promptTokens?.takeIf { it >= 0 }?.let { promptTokens = it; reported = true }
        update.completionTokens?.takeIf { it >= 0 }?.let { completionTokens = it; reported = true }
        update.cachedTokens?.takeIf { it >= 0 }?.let { cachedTokens = it; reported = true }
        update.totalTokens?.takeIf { it >= 0 }?.let { totalTokens = it; reported = true }
        // A later complete folded snapshot may replace an earlier partial preview. Empty/invalid
        // reports are no-ops, except an explicit partial marker for a pass with no usage at all.
        if (reported || update.partial) partial = update.partial
    }
}

/**
 * 累加多次请求的 usage。每次请求都按完整上下文计费，所以 prompt 也直接相加。
 */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}
