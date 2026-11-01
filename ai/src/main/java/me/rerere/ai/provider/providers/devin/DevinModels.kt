package me.rerere.ai.provider.providers.devin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.util.json

// =============================================================================
// Devin 模型目录 + chat_model_uid 解析
// Ported from CLIProxyAPI internal/runtime/executor/helps/devin_models.go
// =============================================================================

@Serializable
data class DevinCatalogThinking(
    val levels: List<String> = emptyList(),
)

@Serializable
data class DevinCatalogModel(
    val id: String,
    @SerialName("object") val obj: String = "",
    val type: String = "",
    @SerialName("owned_by") val ownedBy: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("context_length") val contextLength: Int = 0,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int = 0,
    val supportedInputModalities: List<String> = emptyList(),
    val supportedOutputModalities: List<String> = emptyList(),
    val thinking: DevinCatalogThinking? = null,
)

@Serializable
private data class DevinCatalogEnvelope(
    val devin: List<DevinCatalogModel> = emptyList(),
)

/** 内嵌的 devin_models.json 目录 (与 CPA 内嵌快照一致) */
object DevinModelCatalog {
    val models: List<DevinCatalogModel> by lazy {
        runCatching {
            json.decodeFromString<DevinCatalogEnvelope>(DEVIN_MODELS_JSON).devin
        }.getOrDefault(emptyList())
    }

    /**
     * 等价 registry.LookupDevinModel: 接受 "devin/model" 或裸 "model" id, 大小写不敏感.
     */
    fun lookup(modelId: String): DevinCatalogModel? {
        val clean = modelId.trim().lowercase().removePrefix("devin/")
        if (clean.isEmpty()) return null
        return models.firstOrNull { it.id.lowercase().removePrefix("devin/") == clean }
    }
}

/** 已知的 model uid 后缀 */
private val knownDevinSuffixes = listOf(
    "-none",
    "-low",
    "-medium",
    "-high",
    "-xhigh",
    "-max",
    "-fast",
    "-slow",
    "-priority",
    "-low-priority",
    "-medium-priority",
    "-high-priority",
    "-xhigh-priority",
    "-max-priority",
    "-low-fast",
    "-medium-fast",
    "-high-fast",
    "-xhigh-fast",
    "-max-fast",
    "-none-fast",
    "-thinking-1m",
    "-thinking",
    "-max-1m",
    "-none-1m",
    "_none",
    "_minimal",
    "_low",
    "_medium",
    "_high",
    "_xhigh",
    "_max",
    "_thinking",
)

/** 无法动态推断的特殊私有上游别名 */
private val specialDevinAliases = mapOf(
    "claude-haiku-4-5" to "MODEL_PRIVATE_11",
    "gpt-4-1" to "MODEL_CHAT_GPT_4_1_2025_04_14",
)

/** model 名是否已带已知 Devin effort 后缀 */
fun hasDevinEffortSuffix(model: String): Boolean {
    val lower = model.trim().lowercase()
    return knownDevinSuffixes.any { lower.endsWith(it) }
}

/** 把数字 budget 或松散 effort 字符串归一化为标准 Devin effort */
fun normalizeDevinThinkingLevel(level: String, budgetTokens: Int): String {
    val normalized = level.trim().lowercase()
    when (normalized) {
        "minimal", "low", "medium", "high", "xhigh", "max", "fast" -> return normalized
        "none", "off", "disabled" -> return "none"
        "auto", "adaptive" -> return "high"
    }

    if (budgetTokens > 0) {
        return when {
            budgetTokens <= 4096 -> "low"
            budgetTokens <= 16384 -> "medium"
            budgetTokens <= 32768 -> "high"
            else -> "max"
        }
    }
    return ""
}

/** 等价 CPA thinking.ParseSuffix 的结果 (model(value) 括号后缀) */
internal data class DevinSuffixResult(
    val modelName: String,
    val hasSuffix: Boolean,
    val rawSuffix: String = "",
)

/** 提取 model-name(value) 后缀; 只提取不解释内容. 等价 thinking.ParseSuffix. */
internal fun parseDevinThinkingSuffix(model: String): DevinSuffixResult {
    val lastOpen = model.lastIndexOf('(')
    if (lastOpen == -1 || !model.endsWith(")")) {
        return DevinSuffixResult(modelName = model, hasSuffix = false)
    }
    return DevinSuffixResult(
        modelName = model.substring(0, lastOpen),
        hasSuffix = true,
        rawSuffix = model.substring(lastOpen + 1, model.length - 1),
    )
}

/**
 * 用 rikkihub 的 [ReasoningLevel] 解析模型 id → 上游 chat_model_uid.
 * - OFF → effort "none"
 * - AUTO → 交给目录默认 effort (不强制 "high")
 * - 其余 → 对应 effort 字符串
 */
fun resolveDevinChatModelUid(rawModel: String, reasoningLevel: ReasoningLevel): String {
    val level = when (reasoningLevel) {
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.AUTO -> ""
        else -> reasoningLevel.effort
    }
    return resolveDevinChatModelUid(rawModel, level, 0)
}

/**
 * 把模型标识解析为合法的上游 Devin chat_model_uid.
 * 优先使用 devin_models.json 目录元数据, 自动把请求的 effort 钳制到支持的级别,
 * 为 thinking 模型应用合理默认 effort, 非 thinking 模型保持裸名.
 */
fun resolveDevinChatModelUid(
    rawModel: String,
    thinkingLevel: String = "",
    budgetTokens: Int = 0,
): String {
    val model = rawModel.trim()
    if (model.isEmpty()) return "swe-2-high"

    // 1. 去掉 devin/ 前缀 (大小写不敏感)
    var cleanModel = model
    if (cleanModel.lowercase().startsWith("devin/")) {
        cleanModel = cleanModel.substring(6)
    }

    // 2. 已带确切 effort 后缀时直接使用
    if (hasDevinEffortSuffix(cleanModel)) {
        return cleanModel
    }

    // 3. 剥离 CPA 冒号或括号后缀 (后缀覆盖 body, 遵循 CPA 惯例)
    var level = thinkingLevel
    val parsedSuffix = parseDevinThinkingSuffix(cleanModel)
    var baseModel = parsedSuffix.modelName.trim()
    if (parsedSuffix.hasSuffix) {
        level = parsedSuffix.rawSuffix
    } else {
        val colonIdx = cleanModel.lastIndexOf(':')
        if (colonIdx != -1) {
            baseModel = cleanModel.substring(0, colonIdx).trim()
            level = cleanModel.substring(colonIdx + 1).trim()
        }
    }

    // 4. 归一化请求的 effort
    val effort = normalizeDevinThinkingLevel(level, budgetTokens)
    val lowerBase = baseModel.lowercase()
    var canonicalBase = lowerBase.replace('.', '-')

    // 5. 特殊私有上游别名
    specialDevinAliases[canonicalBase]?.let { return it }
    if (canonicalBase == "claude-sonnet-4-5" || canonicalBase.contains("sonnet-4-5")) {
        return if (effort.isNotEmpty() && effort != "none") "MODEL_PRIVATE_3" else "MODEL_PRIVATE_2"
    }
    if (canonicalBase == "gemini-3-flash") {
        canonicalBase = "gemini-3-8-flash"
    }

    // 5b. 枚举型 UID: effort 变体以大写后缀区分
    val normalizedUnder = canonicalBase.replace('-', '_')
    when (normalizedUnder) {
        "model_gpt_5_2" -> {
            val eff = clampDevinEffort(effort, listOf("none", "low", "medium", "high", "xhigh"), "low")
            return "MODEL_GPT_5_2_" + eff.uppercase()
        }
        "model_google_gemini_3_0_flash" -> {
            val eff = clampDevinEffort(effort, listOf("minimal", "low", "medium", "high"), "high")
            return "MODEL_GOOGLE_GEMINI_3_0_FLASH_" + eff.uppercase()
        }
        "model_claude_4_5_opus" -> {
            return if (effort.isNotEmpty() && effort != "none") {
                "MODEL_CLAUDE_4_5_OPUS_THINKING"
            } else {
                "MODEL_CLAUDE_4_5_OPUS"
            }
        }
    }
    // 其余枚举型 UID (MODEL_PRIVATE_11 等) 没有 effort 变体; 上游只认大写, 不能沿用上面的小写化
    if (normalizedUnder.startsWith("model_")) {
        return normalizedUnder.uppercase()
    }

    // 6. 从 devin_models.json 目录查找模型元数据
    var modelInfo = DevinModelCatalog.lookup(canonicalBase)
    if (modelInfo == null && canonicalBase != lowerBase) {
        modelInfo = DevinModelCatalog.lookup(lowerBase)
    }
    val allowedLevels = modelInfo?.thinking?.levels.orEmpty()

    // 7. 特殊基础模型: 默认裸名, 仅特定变体带后缀
    when (canonicalBase) {
        "swe-1-7" -> return if (effort == "medium") "swe-1-7-medium" else "swe-1-7"
        // 本地: 远端目录里 lightning 只有裸 UID, 与 swe-1-7 同样默认裸名 (上游会默认加 -medium)
        "swe-1-7-lightning" ->
            return if (effort == "medium") "swe-1-7-lightning-medium" else "swe-1-7-lightning"
        "swe-1-6" -> return if (effort == "fast") "swe-1-6-fast" else "swe-1-6"
        "glm-5-2" -> return when (effort) {
            "none" -> "glm-5-2-none"
            "max" -> "glm-5-2-max"
            else -> "glm-5-2"
        }
        "glm-5-2-1m" -> return when (effort) {
            "none" -> "glm-5-2-none-1m"
            "max" -> "glm-5-2-max-1m"
            else -> "glm-5-2-1m"
        }
        "claude-opus-4-6", "claude-sonnet-4-6" ->
            return if (effort.isNotEmpty() && effort != "none") "$canonicalBase-thinking" else canonicalBase
        "claude-opus-4-6-1m" ->
            return if (effort.isNotEmpty() && effort != "none") "claude-opus-4-6-thinking-1m" else canonicalBase
        "claude-sonnet-4-6-1m" ->
            return if (effort.isNotEmpty() && effort != "none") "claude-sonnet-4-6-thinking-1m" else canonicalBase
    }

    // 8. 目录中无 thinking levels 的模型按裸名处理
    if (allowedLevels.isEmpty()) {
        return canonicalBase
    }

    // 9. thinking 模型: 定默认 effort 并钳制
    val defaultEffort = selectDefaultDevinEffort(canonicalBase, allowedLevels)
    val clamped = clampDevinEffort(effort, allowedLevels, defaultEffort)
    return "$canonicalBase-$clamped"
}

internal fun selectDefaultDevinEffort(baseModel: String, levels: List<String>): String {
    if (baseModel.contains("swe-2")) {
        return "high"
    }
    val hasNone = "none" in levels
    val hasLow = "low" in levels
    val hasMedium = "medium" in levels
    val hasHigh = "high" in levels
    // Devin CLI 里的 OpenAI GPT-5.x 家族 (支持 none+low) 默认 low
    if (hasNone && hasLow && baseModel.startsWith("gpt-5")) {
        return "low"
    }
    // high thinking 模型 (deepseek/gemini/grok/glm/kimi/nemotron) 优先 high
    if (hasHigh && (
            baseModel.contains("gemini") || baseModel.contains("grok") ||
                baseModel.contains("glm") || baseModel.contains("deepseek") ||
                baseModel.contains("kimi") || baseModel.contains("nemotron")
            )
    ) {
        return "high"
    }
    if (hasMedium) return "medium"
    if (hasHigh) return "high"
    if (hasLow) return "low"
    return levels[0]
}

private val devinStandardLevelOrder = listOf("minimal", "low", "medium", "high", "xhigh", "max")

private fun devinLevelIndex(level: String): Int {
    val lower = level.trim().lowercase()
    return devinStandardLevelOrder.indexOf(lower)
}

internal fun clampDevinEffort(requested: String, allowed: List<String>, defaultEffort: String): String {
    if (requested.isEmpty()) return defaultEffort
    val reqLower = requested.trim().lowercase()
    for (a in allowed) {
        if (reqLower == a.trim().lowercase()) return a
    }
    if (reqLower == "none") return defaultEffort

    val reqIdx = devinLevelIndex(reqLower)
    if (reqIdx == -1) return defaultEffort

    var bestMatch = defaultEffort
    var bestDist = 999
    var bestIdx = -1
    for (a in allowed) {
        val aIdx = devinLevelIndex(a)
        if (aIdx == -1) continue
        var dist = reqIdx - aIdx
        if (dist < 0) dist = -dist
        if (dist < bestDist) {
            bestDist = dist
            bestMatch = a
            bestIdx = aIdx
        } else if (dist == bestDist && aIdx > bestIdx) {
            // 平局时偏向更高 effort (例如 glm-5-3 medium→high; swe-2 xhigh→max)
            bestMatch = a
            bestIdx = aIdx
        }
    }
    return bestMatch
}

// =============================================================================
// 内嵌模型目录 (CLIProxyAPI internal/registry/models/devin_models.json 快照, 上游 bc06434,
// 末尾追加上游 WithDevinBuiltins 注入的 swe-1-6-slow)
// =============================================================================

internal const val DEVIN_MODELS_JSON = """
{
  "devin": [
    {
      "id": "claude-opus-5-5",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Opus 5.5",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "claude-fable-5-1",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Fable 5.1",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "claude-sonnet-5",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Sonnet 5",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "gemini-3-8-flash",
      "object": "model",
      "type": "devin",
      "owned_by": "google",
      "display_name": "Gemini 3.8 Flash",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high"
        ]
      }
    },
    {
      "id": "gpt-6-astra",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-6 Astra",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "gpt-6-sol",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-6 Sol",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "gpt-6-luna",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-6 Luna",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "glm-5-2",
      "object": "model",
      "type": "devin",
      "owned_by": "zhipu",
      "display_name": "GLM-5.2",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "max"
        ]
      }
    },
    {
      "id": "kimi-k3",
      "object": "model",
      "type": "devin",
      "owned_by": "moonshot",
      "display_name": "Kimi K3",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "high",
          "max"
        ]
      }
    },
    {
      "id": "glm-5-3",
      "object": "model",
      "type": "devin",
      "owned_by": "zhipu",
      "display_name": "GLM-5.3",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "high",
          "max"
        ]
      }
    },
    {
      "id": "swe-1-7-lightning",
      "object": "model",
      "type": "devin",
      "owned_by": "cognition",
      "display_name": "SWE-1.7 Lightning",
      "context_length": 202752,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "medium"
        ]
      }
    },
    {
      "id": "swe-2",
      "object": "model",
      "type": "devin",
      "owned_by": "cognition",
      "display_name": "SWE-2",
      "context_length": 262000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "medium",
          "high",
          "max"
        ]
      }
    },
    {
      "id": "claude-opus-4-7",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Opus 4.7",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "claude-opus-4-8",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Opus 4.8",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "claude-opus-5",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Opus 5",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "claude-5-fable",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Fable 5",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "gemini-3-5-flash",
      "object": "model",
      "type": "devin",
      "owned_by": "google",
      "display_name": "Gemini 3.5 Flash",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "minimal",
          "low",
          "medium",
          "high"
        ]
      }
    },
    {
      "id": "gemini-3-6-flash",
      "object": "model",
      "type": "devin",
      "owned_by": "google",
      "display_name": "Gemini 3.6 Flash",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "minimal",
          "low",
          "medium",
          "high"
        ]
      }
    },
    {
      "id": "gemini-3-7-flash",
      "object": "model",
      "type": "devin",
      "owned_by": "google",
      "display_name": "Gemini 3.7 Flash",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high"
        ]
      }
    },
    {
      "id": "gpt-5-6-sol",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.6 Sol",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "gpt-5-6-terra",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.6 Terra",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "gpt-5-6-luna",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.6 Luna",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "glm-5-2-1m",
      "object": "model",
      "type": "devin",
      "owned_by": "zhipu",
      "display_name": "GLM-5.2 High 1M",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "max"
        ]
      }
    },
    {
      "id": "grok-4-5",
      "object": "model",
      "type": "devin",
      "owned_by": "xai",
      "display_name": "Grok 4.5",
      "context_length": 500000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high"
        ]
      }
    },
    {
      "id": "grok-4-6",
      "object": "model",
      "type": "devin",
      "owned_by": "xai",
      "display_name": "Grok 4.6",
      "context_length": 500000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh"
        ]
      }
    },
    {
      "id": "grok-4-7",
      "object": "model",
      "type": "devin",
      "owned_by": "xai",
      "display_name": "Grok 4.7",
      "context_length": 500000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh"
        ]
      }
    },
    {
      "id": "inkling",
      "object": "model",
      "type": "devin",
      "owned_by": "devin",
      "display_name": "Inkling",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh",
          "max"
        ]
      }
    },
    {
      "id": "glm-5-3-flash",
      "object": "model",
      "type": "devin",
      "owned_by": "zhipu",
      "display_name": "GLM-5.3 Flash",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "high",
          "max"
        ]
      }
    },
    {
      "id": "deepseek-v4-flash",
      "object": "model",
      "type": "devin",
      "owned_by": "deepseek",
      "display_name": "DeepSeek V4 Flash",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "high",
          "max"
        ]
      }
    },
    {
      "id": "deepseek-v4-1-flash",
      "object": "model",
      "type": "devin",
      "owned_by": "deepseek",
      "display_name": "DeepSeek V4.1 Flash",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "high",
          "max"
        ]
      }
    },
    {
      "id": "swe-1-7",
      "object": "model",
      "type": "devin",
      "owned_by": "cognition",
      "display_name": "SWE-1.7",
      "context_length": 262000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "medium"
        ]
      }
    },
    {
      "id": "claude-opus-4-6",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Opus 4.6",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "claude-opus-4-6-1m",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Opus 4.6 1M",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "gpt-5-4",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.4",
      "context_length": 272000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh"
        ]
      }
    },
    {
      "id": "gpt-5-5",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.5",
      "context_length": 272000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh"
        ]
      }
    },
    {
      "id": "gpt-5-4-mini",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.4 Mini",
      "context_length": 400000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh"
        ]
      }
    },
    {
      "id": "claude-sonnet-4-6",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Sonnet 4.6",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "claude-sonnet-4-6-1m",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Sonnet 4.6 1M",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_GPT_5_2",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.2",
      "context_length": 384000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "low",
          "medium",
          "high",
          "xhigh"
        ]
      }
    },
    {
      "id": "MODEL_CLAUDE_4_5_OPUS",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Opus 4.5",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "high"
        ]
      }
    },
    {
      "id": "MODEL_PRIVATE_11",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Haiku 4.5",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_PRIVATE_2",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Sonnet 4.5",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_PRIVATE_3",
      "object": "model",
      "type": "devin",
      "owned_by": "anthropic",
      "display_name": "Claude Sonnet 4.5",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_CHAT_GPT_4_1_2025_04_14",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-4.1",
      "context_length": 1047576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_PRIVATE_12",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.1",
      "context_length": 272000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_PRIVATE_13",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.1",
      "context_length": 272000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_PRIVATE_14",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.1",
      "context_length": 272000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "MODEL_PRIVATE_15",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.1",
      "context_length": 272000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "gpt-5-3-codex",
      "object": "model",
      "type": "devin",
      "owned_by": "openai",
      "display_name": "GPT-5.3-Codex",
      "context_length": 400000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "medium",
          "high",
          "xhigh"
        ]
      }
    },
    {
      "id": "kimi-k2-6",
      "object": "model",
      "type": "devin",
      "owned_by": "moonshot",
      "display_name": "Kimi K2.6",
      "context_length": 262144,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "kimi-k2-7",
      "object": "model",
      "type": "devin",
      "owned_by": "moonshot",
      "display_name": "Kimi K2.7",
      "context_length": 262144,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "nemotron-3-ultra",
      "object": "model",
      "type": "devin",
      "owned_by": "nvidia",
      "display_name": "Nemotron 3 Ultra",
      "context_length": 1000000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "none",
          "medium",
          "high"
        ]
      }
    },
    {
      "id": "swe-1-6",
      "object": "model",
      "type": "devin",
      "owned_by": "cognition",
      "display_name": "SWE-1.6",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    },
    {
      "id": "gemini-3-1-pro",
      "object": "model",
      "type": "devin",
      "owned_by": "google",
      "display_name": "Gemini 3.1 Pro",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "low",
          "high"
        ]
      }
    },
    {
      "id": "MODEL_GOOGLE_GEMINI_3_0_FLASH",
      "object": "model",
      "type": "devin",
      "owned_by": "google",
      "display_name": "Gemini 3 Flash",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "minimal",
          "low",
          "medium",
          "high"
        ]
      }
    },
    {
      "id": "deepseek-v4-pro",
      "object": "model",
      "type": "devin",
      "owned_by": "deepseek",
      "display_name": "DeepSeek V4 Pro",
      "context_length": 1048576,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text"
      ],
      "supportedOutputModalities": [
        "text"
      ],
      "thinking": {
        "levels": [
          "high",
          "max"
        ]
      }
    },
    {
      "id": "swe-1-6-slow",
      "object": "model",
      "type": "devin",
      "owned_by": "cognition",
      "display_name": "SWE-1.6 Slow",
      "context_length": 200000,
      "max_completion_tokens": 64000,
      "supportedInputModalities": [
        "text",
        "image"
      ],
      "supportedOutputModalities": [
        "text"
      ]
    }
  ]
}
"""
