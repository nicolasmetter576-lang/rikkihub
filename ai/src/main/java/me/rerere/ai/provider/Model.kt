package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    val ccDisguise: Boolean = false,
    // 上下文窗口大小（token），输入区的用量环和统计详情按它计算占比
    val contextLength: Int = DEFAULT_MODEL_CONTEXT_LENGTH,
    // 价格（每百万 token，货币由设置里的统计货币决定），只用于统计页估算花费
    val pricing: ModelPricing? = null,
)

/**
 * 按空格分隔的关键词筛选模型：忽略大小写，每个关键词都要命中模型 ID 或显示名称的一部分，
 * 关键词之间不要求顺序。没有关键词时返回原列表。
 */
fun List<Model>.filterByKeywords(query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return this
    return filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

/**
 * 每百万 token 的价格。未填的项不计入已知花费/覆盖率，不代表免费；
 * [cachedInput] 未填时缓存命中按 [input] 计。
 * 输入 token 已包含缓存命中的部分，输出已包含思考 token。
 */
@Serializable
data class ModelPricing(
    val input: Double? = null,
    val output: Double? = null,
    val cachedInput: Double? = null,
) {
    val isEmpty: Boolean get() = input == null && output == null && cachedInput == null

    /** 有明确价格的计费 token；未填价格不是免费，明确的 0 价格仍算已知。 */
    fun pricedTokens(promptTokens: Long, completionTokens: Long, cachedTokens: Long): Long {
        val cached = cachedTokens.coerceIn(0, promptTokens.coerceAtLeast(0))
        return (if (input != null) (promptTokens - cached).coerceAtLeast(0) else 0) +
            (if (cachedInput != null || input != null) cached else 0) +
            (if (output != null) completionTokens.coerceAtLeast(0) else 0)
    }

    /** 按这份价格算一笔用量的花费 */
    fun cost(promptTokens: Long, completionTokens: Long, cachedTokens: Long): Double {
        val cached = cachedTokens.coerceIn(0, promptTokens.coerceAtLeast(0))
        val uncached = (promptTokens - cached).coerceAtLeast(0)
        val inputPrice = input ?: 0.0
        return (uncached * inputPrice + cached * (cachedInput ?: inputPrice) + completionTokens * (output ?: 0.0)) / 1_000_000.0
    }
}

const val DEFAULT_MODEL_CONTEXT_LENGTH = 1_000_000

/** 上下文窗口的上限（界面与创造助手的校验用） */
const val MAX_MODEL_CONTEXT_LENGTH = 100_000_000

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



