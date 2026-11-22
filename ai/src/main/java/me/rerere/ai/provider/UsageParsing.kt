package me.rerere.ai.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.UsageAccumulator
import me.rerere.ai.core.UsageUpdate

/** Invalid/negative counts are unreported, not a command to clear earlier state. */
internal fun JsonObject.tokenCount(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

internal fun UsageUpdate.reportedOrNull(): UsageUpdate? = takeIf {
    promptTokens != null || completionTokens != null || cachedTokens != null || totalTokens != null
}

/** Non-streaming uses the very same request normalization as the streaming handler. */
internal fun UsageUpdate?.accumulateUsage(): UsageAccumulator = UsageAccumulator().also { accumulator ->
    this?.let(accumulator::update)
}
