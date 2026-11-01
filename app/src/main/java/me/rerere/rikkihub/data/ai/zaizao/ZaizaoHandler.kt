package me.rerere.rikkihub.data.ai.zaizao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.datastore.getCurrentChatModel
import me.rerere.rikkihub.service.backgroundTextGenerationParams

/**
 * 一稿生成到目前为止的样子：思考内容、拆出的 plan 与 result、模型的完整回复（下一轮作为历史回传）。
 * [done] 为 true 的是最后一次，此时 [result] 一定非空。
 */
data class ZaizaoProgress(
    val reasoning: String,
    val plan: String,
    val result: String,
    val output: String,
    val done: Boolean = false,
)

/**
 * 调模型生成一稿。模型：再造设置里指定了且仍存在就用它，否则当前助手的对话模型（助手没指定就用全局对话模型）。
 * 不带助手系统提示词和任何工具；推理等级、温度、Top P、最大输出取再造设置。内容有变化就发一次 [ZaizaoProgress]。
 */
class ZaizaoHandler(private val providerManager: ProviderManager) {
    fun generate(settings: Settings, prepared: ZaizaoPrepared): Flow<ZaizaoProgress> = flow {
        val config = settings.zaizao
        val model = resolveModel(settings) ?: throw ZaizaoException(ZaizaoException.Reason.NO_MODEL)
        val provider = model.findProvider(settings.providers) ?: throw ZaizaoException(ZaizaoException.Reason.NO_MODEL)
        val handler = providerManager.getProviderByType(provider)
        var messages = prepared.messages
        val chunks = StreamChunkHandler(model)
        var last: ZaizaoProgress? = null
        handler.streamText(
            providerSetting = provider,
            messages = messages,
            params = params(model, config),
        ).collect { chunk ->
            messages = chunks.handle(messages, chunk)
            val progress = progressOf(messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }, finished = false)
            if (progress != last) {
                last = progress
                emit(progress)
            }
        }
        val final = progressOf(messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }, finished = true)
        if (final.result.isEmpty()) throw ZaizaoException(ZaizaoException.Reason.EMPTY_RESULT)
        emit(final.copy(done = true))
    }.flowOn(Dispatchers.IO)

    private fun progressOf(message: UIMessage?, finished: Boolean): ZaizaoProgress {
        val output = message?.toText().orEmpty()
        val reasoning = message?.parts?.filterIsInstance<UIMessagePart.Reasoning>()?.joinToString("\n") { it.reasoning }.orEmpty()
        val parsed = Zaizao.parse(output, finished)
        return ZaizaoProgress(reasoning.trim(), parsed.plan, parsed.result, output)
    }

    companion object {
        /** 指定的模型（被删了就退回）→ 当前助手的模型 → 全局对话模型。 */
        fun resolveModel(settings: Settings): Model? =
            settings.zaizao.modelId?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel()

        fun params(model: Model, config: ZaizaoConfig): TextGenerationParams =
            backgroundTextGenerationParams(model, reasoningLevel = config.reasoningLevel).copy(
                temperature = config.effectiveTemperature,
                topP = config.effectiveTopP,
                maxTokens = config.effectiveMaxTokens,
            )
    }
}
