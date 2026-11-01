package me.rerere.ai.provider.providers.antigravity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.util.json

/**
 * Antigravity 模型族判定（与 CPA 同一套字符串规则）：
 * - Claude：模型名含 `claude`，走 VALIDATED 工具模式与 Claude 思考签名规则；
 * - Gemini 族：含 gemini/flash/agent 且非 Claude（CPA `antigravityUsesReasoningReplayCache`），
 *   functionResponse 回合按 Antigravity 原生形态改写；
 * - 图像模型：含 `image`，requestType=image_gen；
 * - GPT-OSS：只收文字、无思考配置。
 */
object AntigravityModelFamily {
    fun isClaude(modelId: String): Boolean = modelId.lowercase().contains("claude")

    fun isGeminiFamily(modelId: String): Boolean {
        val lower = modelId.lowercase()
        if (lower.contains("claude")) return false
        return lower.contains("gemini") || lower.contains("flash") || lower.contains("agent")
    }

    fun isImage(modelId: String): Boolean = modelId.lowercase().contains("image")

    fun isGptOss(modelId: String): Boolean = modelId.lowercase().contains("gpt-oss")
}

/** fetchAvailableModels 里的一个模型。 */
data class AntigravityRemoteModel(
    val id: String,
    val displayName: String,
    val maxTokens: Long? = null,
    val maxOutputTokens: Long? = null,
    /** `quotaInfo.remainingFraction`（0..1），没有则 null。 */
    val remainingFraction: Double? = null,
    /** `quotaInfo.resetTime`（RFC 3339），没有则 null。 */
    val resetTime: String? = null,
)

data class AntigravityModelCatalog(
    val models: List<AntigravityRemoteModel>,
    val webSearchModelIds: Set<String> = emptySet(),
)

/** CPA `cmd/fetch_antigravity_models` 跳过的内部/实验模型。 */
private val INTERNAL_MODEL_IDS = setOf(
    "chat_20706",
    "chat_23310",
    "tab_flash_lite_preview",
    "tab_jump_flash_lite_preview",
    "gemini-2.5-flash-thinking",
    "gemini-2.5-pro",
)

internal fun isInternalAntigravityModel(modelId: String): Boolean =
    modelId in INTERNAL_MODEL_IDS || modelId.startsWith("chat_") || modelId.startsWith("tab_")

/** 解析 `POST v1internal:fetchAvailableModels` 的响应体。 */
fun parseAntigravityModelCatalog(body: String): AntigravityModelCatalog {
    val root = json.parseToJsonElement(body) as? JsonObject ?: return AntigravityModelCatalog(emptyList())
    val models = (root["models"] as? JsonObject).orEmpty().mapNotNull { (rawId, value) ->
        val id = rawId.trim()
        if (id.isEmpty() || isInternalAntigravityModel(id)) return@mapNotNull null
        val info = value as? JsonObject ?: JsonObject(emptyMap())
        val quota = info["quotaInfo"] as? JsonObject
        AntigravityRemoteModel(
            id = id,
            displayName = info["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id,
            maxTokens = info["maxTokens"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
            maxOutputTokens = info["maxOutputTokens"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
            remainingFraction = quota?.get("remainingFraction")?.jsonPrimitive?.doubleOrNull,
            resetTime = quota?.get("resetTime")?.jsonPrimitive?.contentOrNull,
        )
    }.sortedBy { it.id }
    val webSearch = (root["webSearchModelIds"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
        .toSet()
    return AntigravityModelCatalog(models, webSearch)
}

/** 远端模型 → rikkihub 模型（能力按模型族推断）。 */
fun AntigravityRemoteModel.toModel(): Model {
    val image = AntigravityModelFamily.isImage(id)
    val gptOss = AntigravityModelFamily.isGptOss(id)
    return Model(
        modelId = id,
        displayName = displayName,
        inputModalities = if (gptOss) listOf(Modality.TEXT) else listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = if (image) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT),
        abilities = when {
            image -> emptyList()
            gptOss -> listOf(ModelAbility.TOOL)
            else -> listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        },
    )
}
