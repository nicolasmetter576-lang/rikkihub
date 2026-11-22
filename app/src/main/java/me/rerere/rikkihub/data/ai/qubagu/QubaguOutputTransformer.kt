package me.rerere.rikkihub.data.ai.qubagu

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.stripMessageTitle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import kotlin.time.Duration.Companion.seconds

private const val TAG = "Qubagu"

/**
 * 生成结束时清理回复（去八股总开关开着时，所有助手）：改存储，改动记录写在文字 part 上（见 [QubaguRecord]）。
 * 流式过程中不动，免得替换词每个 chunk 换一次。排在酒馆正则之后，处理的是正则改写过的文字。
 *
 * 自动模式且开了 AI 精修时，在这一步里同步等模型重写命中的句子，这样正文只变一次；
 * 超时或出错就留着本地规则的结果。
 */
class QubaguOutputTransformer(
    private val engine: Qubagu,
    private val refiner: QubaguRefiner,
) : OutputMessageTransformer {
    override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val config = ctx.settings.qubagu
        if (!config.enabled) return messages
        val recent = ctx.history.filter { it.role == MessageRole.ASSISTANT }.takeLast(RECENT_REPLIES).joinToString("\n") { it.toText() }
        return messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) return@map message
            message.copy(parts = message.parts.map { part ->
                if (part !is UIMessagePart.Text || part.text.isBlank()) return@map part
                val local = try {
                    // 末行的每轮标题不参与：结尾套话按正文末尾找；去掉标题后的文字是原文的前缀，改动位置照样对得上
                    engine.clean(part.text.stripMessageTitle(), config, recent)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "clean failed", e)
                    return@map part
                }
                if (local.edits.isEmpty()) return@map part
                val record = if (config.refine && config.mode == QubaguMode.AUTO) refined(ctx, part.text, local) else local
                part.withQubaguRecord(part.text, record)
            })
        }
    }

    private suspend fun refined(ctx: TransformerContext, original: String, local: QubaguRecord): QubaguRecord = try {
        withTimeoutOrNull(REFINE_TIMEOUT) {
            refiner.refine(original, local, ctx.settings, fallback = ctx.model, assistantId = ctx.assistant.id)
        } ?: local.also { Log.w(TAG, "refine timed out") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: QubaguRefineException) {
        local
    } catch (e: Exception) {
        Log.w(TAG, "refine failed", e)
        local
    }

    companion object {
        /** 挑替换词时要避开的范围：最近几条回复。 */
        const val RECENT_REPLIES = 6
        val REFINE_TIMEOUT = 40.seconds
    }
}
