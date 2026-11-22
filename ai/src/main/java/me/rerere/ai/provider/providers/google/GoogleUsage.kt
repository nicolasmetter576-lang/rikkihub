package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.UsageUpdate
import me.rerere.ai.provider.tokenCount

/** Preserve the separate output components when a later frame reports only one of them. */
internal class GoogleUsageNormalizer {
    private var candidates: Int? = null
    private var thoughts: Int? = null

    fun update(usage: JsonObject?): UsageUpdate? {
        if (usage == null) return null
        return merge(
            usage.tokenCount("promptTokenCount"),
            usage.tokenCount("candidatesTokenCount"),
            usage.tokenCount("thoughtsTokenCount"),
            usage.tokenCount("cachedContentTokenCount"),
            usage.tokenCount("totalTokenCount"),
        )
    }

    fun updateInteractions(usage: JsonObject?): UsageUpdate? {
        if (usage == null) return null
        return merge(
            usage.tokenCount("total_input_tokens"),
            usage.tokenCount("total_output_tokens"),
            usage.tokenCount("total_thought_tokens"),
            usage.tokenCount("total_cached_tokens"),
            usage.tokenCount("total_tokens"),
        )
    }

    private fun merge(prompt: Int?, candidate: Int?, thought: Int?, cached: Int?, total: Int?): UsageUpdate? {
        if (prompt == null && candidate == null && thought == null && cached == null && total == null) return null
        candidate?.let { candidates = it }
        thought?.let { thoughts = it }
        return UsageUpdate(
            promptTokens = prompt,
            completionTokens = if (candidates != null || thoughts != null) (candidates ?: 0) + (thoughts ?: 0) else null,
            cachedTokens = cached,
            // total may include tool-use prompt tokens: do not force it to equal p+c.
            totalTokens = total,
            partial = candidates == null,
        )
    }
}
