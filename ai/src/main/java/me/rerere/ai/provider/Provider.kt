package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

@Serializable
data class TextGenerationResult(
    val id: String,
    val model: String,
    val message: UIMessage,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /**
     * 会话身份请求头（X-Session-ID 等）。无手工 session 头时，缺省在
     * params 构建时生成一次，随 copy/重试/重放保持稳定。显式 null 不自动
     * 注入；手工 session 头原样转发。会话内调用显式传 conversationId，
     * 仍保持原有专用头优先级（不因上游默认身份而覆盖独立请求的手工配置）。
     */
    private val defaultSessionId: String? = defaultTextSessionId(customHeaders),
    val sessionId: String? = defaultSessionId,
    /**
     * 工作区禁用工具的最终 wire 保护策略。由各协议在
     * 构建 + mergeCustomBody（Claude 含 CC disguise）之后的最终请求体上
     * 检查工具 schema / 选择器名字；命中禁用名抛
     * [WorkspaceToolPolicyConflictException]，不修改请求。
     * null = 无策略，保持现有自由 customBody/choice 行为；由调用方装配。
     */
    val workspaceToolPolicy: WorkspaceToolWirePolicy? = null,
    /**
     * 本请求生效的 wire 工具改名表：原 wire 名 → 发出名。
     *
     * 装配层按 provider 要求把保留名（如 Devin 上游的 `bash`）改名为安全
     * 别名后填入：[tools] 里的声明名已是发出名，本表供协议层对**历史消息
     * 中记录的工具调用名**做同一映射（调用名与结果名随历史回传，必须与
     * 声明名同源，否则上游按未注册名校验）。非该 provider 的请求恒为空。
     */
    val toolWireRenames: Map<String, String> = emptyMap(),
) {
    /**
     * The default identity is a copy/codec-stable provenance marker, not a second
     * request identity. Only that generated default yields to manual headers;
     * explicit conversation IDs (and explicit null) keep their original meaning.
     */
    fun resolvedSessionId(provider: ProviderSetting): String? =
        if (sessionId == defaultSessionId &&
            (customHeaders + provider.customHeaders).any { it.isSessionHeader() }
        ) null else sessionId
}

private fun CustomHeader.isSessionHeader(): Boolean =
    name.equals("X-Session-ID", ignoreCase = true) ||
        name.equals("x-opencode-session", ignoreCase = true)

/** Keep standalone manual session headers as-is; otherwise allocate one request identity. */
fun defaultTextSessionId(customHeaders: List<CustomHeader> = emptyList()): String? =
    if (customHeaders.any { it.isSessionHeader() }) null else Uuid.random().toString()

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
