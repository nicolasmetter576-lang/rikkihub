package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.UsageUpdate
import me.rerere.ai.provider.tokenCount

/** One physical Claude pass. Merge raw components before computing the normalized input. */
internal class ClaudeUsageNormalizer {
    private var input: Int? = null
    private var cacheRead: Int? = null
    private var cacheWrite: Int? = null
    private var output: Int? = null

    fun update(body: JsonObject): UsageUpdate? {
        val usage = body["usage"] as? JsonObject
            ?: (body["message"] as? JsonObject)?.get("usage") as? JsonObject
            ?: return null
        val nextInput = usage.tokenCount("input_tokens")
        val nextRead = usage.tokenCount("cache_read_input_tokens")
        val nextWrite = usage.tokenCount("cache_creation_input_tokens")
        val nextOutput = usage.tokenCount("output_tokens")
        if (nextInput == null && nextRead == null && nextWrite == null && nextOutput == null) return null
        nextInput?.let { input = it }
        nextRead?.let { cacheRead = it }
        nextWrite?.let { cacheWrite = it }
        nextOutput?.let { output = it }
        return UsageUpdate(
            promptTokens = if (input != null || cacheRead != null || cacheWrite != null) {
                (input ?: 0) + (cacheRead ?: 0) + (cacheWrite ?: 0)
            } else null,
            completionTokens = output,
            cachedTokens = cacheRead,
            // Claude does not report total. The shared accumulator derives it, without losing provenance.
            partial = input == null,
        )
    }
}
