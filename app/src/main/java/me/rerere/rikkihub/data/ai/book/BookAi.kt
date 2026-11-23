package me.rerere.rikkihub.data.ai.book

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.ai.UsageRecorder
import me.rerere.rikkihub.data.ai.UsageSource
import me.rerere.rikkihub.data.ai.UsageTag
import me.rerere.rikkihub.data.ai.wrapOrSelf
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.datastore.getCurrentChatModel
import me.rerere.rikkihub.service.backgroundTextGenerationParams
import kotlin.uuid.Uuid

/** 作品设置（`Settings.book`，存一个 JSON 偏好键）。[modelId] 为 null = 当前助手的对话模型；温度为 null = 模型默认。 */
@Serializable
data class BookConfig(
    val modelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val temperature: Float? = null,
) {
    val effectiveTemperature: Float? get() = temperature?.takeIf { it.isFinite() }?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)

    companion object {
        const val MIN_TEMPERATURE = 0f
        const val MAX_TEMPERATURE = 2f
    }
}

class BookAiException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { NO_MODEL, EMPTY_RESULT }
}

/** 作品功能调模型的唯一出口：不带助手提示词和工具，推理、温度取作品设置，用量记在「作品」名下。 */
class BookAi(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder? = null,
) {
    /** 逐步发出到目前为止的完整回复；没有正文时抛 [BookAiException]。[modelId] 指定则优先用它。 */
    fun stream(settings: Settings, messages: List<UIMessage>, modelId: Uuid? = null): Flow<String> = flow {
        val model = resolveModel(settings, modelId) ?: throw BookAiException(BookAiException.Reason.NO_MODEL)
        val provider = model.findProvider(settings.providers) ?: throw BookAiException(BookAiException.Reason.NO_MODEL)
        val handler = usageRecorder.wrapOrSelf(providerManager.getProviderByType(provider), UsageTag(UsageSource.BOOK))
        var current = messages
        var last = ""
        val chunks = StreamChunkHandler(model)
        handler.streamText(
            providerSetting = provider,
            messages = messages,
            params = backgroundTextGenerationParams(model, reasoningLevel = settings.book.reasoningLevel)
                .copy(temperature = settings.book.effectiveTemperature),
        ).collect { chunk ->
            current = chunks.handle(current, chunk)
            val text = current.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.toText().orEmpty()
            if (text != last) {
                last = text
                emit(text)
            }
        }
        if (last.isBlank()) throw BookAiException(BookAiException.Reason.EMPTY_RESULT)
    }.flowOn(Dispatchers.IO)

    suspend fun complete(settings: Settings, messages: List<UIMessage>, modelId: Uuid? = null): String {
        var text = ""
        stream(settings, messages, modelId).collect { text = it }
        return text
    }

    companion object {
        /** 单独指定的（被删了就退回）→ 作品设置里的 → 当前助手的模型 → 全局对话模型。 */
        fun resolveModel(settings: Settings, modelId: Uuid? = null): Model? =
            modelId?.let { settings.findModelById(it) }
                ?: settings.book.modelId?.let { settings.findModelById(it) }
                ?: settings.getCurrentChatModel()
    }
}
