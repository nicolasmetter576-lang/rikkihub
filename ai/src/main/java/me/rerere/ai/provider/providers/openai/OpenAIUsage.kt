package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.UsageUpdate
import me.rerere.ai.provider.reportedOrNull
import me.rerere.ai.provider.tokenCount

internal fun parseChatCompletionsUsage(usage: JsonObject?): UsageUpdate? {
    if (usage == null) return null
    return UsageUpdate(
        promptTokens = usage.tokenCount("prompt_tokens"),
        // reasoning_tokens is already included in completion_tokens; never add it twice.
        completionTokens = usage.tokenCount("completion_tokens"),
        totalTokens = usage.tokenCount("total_tokens"),
        cachedTokens = (usage["prompt_tokens_details"] as? JsonObject)?.tokenCount("cached_tokens")
            ?: usage.tokenCount("cached_tokens")
            ?: usage.tokenCount("prompt_cache_hit_tokens"),
    ).reportedOrNull()
}

internal fun parseResponsesUsage(usage: JsonObject?): UsageUpdate? {
    if (usage == null) return null
    return UsageUpdate(
        promptTokens = usage.tokenCount("input_tokens"),
        completionTokens = usage.tokenCount("output_tokens"),
        totalTokens = usage.tokenCount("total_tokens"),
        cachedTokens = (usage["input_tokens_details"] as? JsonObject)?.tokenCount("cached_tokens"),
    ).reportedOrNull()
}
