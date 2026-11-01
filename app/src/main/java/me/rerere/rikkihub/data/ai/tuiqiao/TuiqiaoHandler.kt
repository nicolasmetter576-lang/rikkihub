package me.rerere.rikkihub.data.ai.tuiqiao

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.datastore.getCurrentChatModel

/** 一次推敲请求。[start]/[end] 是 [source] 里的 UTF-16 偏移；[mode] 为 null 时自动判定。 */
data class TuiqiaoRequest(
    val fileName: String,
    val source: String,
    val start: Int,
    val end: Int,
    val mode: TuiqiaoMode? = null,
)

/**
 * 一个候选位来源：常规提示词（[role] 为 null，出 [count] 条）或一个推敲角色（出 1 条）。
 * [key] 在一次推敲内唯一：常规为 [DEFAULT_KEY]，角色为角色 id。
 */
data class TuiqiaoSlot(
    val key: String,
    val role: TuiqiaoRole?,
    val count: Int,
    val prompt: String,
    val markup: Tuiqiao.MarkupPolicy,
) {
    companion object {
        const val DEFAULT_KEY = "default"
    }
}

/** 规整后的请求：实际替换的区间（两端空白已去掉）、档位、格式档和各候选位（角色在前）。 */
data class TuiqiaoPrepared(
    val start: Int,
    val end: Int,
    val original: String,
    val mode: TuiqiaoMode,
    val format: TuiqiaoFormat,
    val slots: List<TuiqiaoSlot>,
) {
    /** 只重跑 [key] 这一个候选位；不存在时为 null。 */
    fun only(key: String): TuiqiaoPrepared? = slots.filter { it.key == key }.takeIf { it.isNotEmpty() }?.let { copy(slots = it) }
}

class TuiqiaoException(val reason: Reason, message: String? = null) : RuntimeException(message ?: reason.name) {
    enum class Reason { EMPTY_SELECTION, SELECTION_TOO_LONG, NO_MODEL, NO_CANDIDATES, MARKUP_BROKEN }
}

object TuiqiaoPreparer {
    fun prepare(config: TuiqiaoConfig, request: TuiqiaoRequest, setting: String = ""): TuiqiaoPrepared {
        val range = Tuiqiao.normalizeRange(request.source, request.start, request.end)
            ?: throw TuiqiaoException(TuiqiaoException.Reason.EMPTY_SELECTION)
        if (range.last + 1 - range.first > Tuiqiao.MAX_SELECTION_CHARS) {
            throw TuiqiaoException(TuiqiaoException.Reason.SELECTION_TOO_LONG)
        }
        val context = Tuiqiao.context(request.source, range)
        val mode = request.mode ?: Tuiqiao.classify(context.selection)
        val format = TuiqiaoFormat.of(request.fileName)
        val roles = config.rolesFor(mode)
        val defaultCount = config.effectiveCandidateCount - roles.size
        val plain = format == TuiqiaoFormat.PLAIN
        val slots = roles.map { role ->
            TuiqiaoSlot(
                key = role.id.toString(),
                role = role,
                count = 1,
                prompt = TuiqiaoPrompts.buildRole(config, role, request.fileName, context, setting, format),
                markup = if (plain) Tuiqiao.MarkupPolicy.NONE else Tuiqiao.MarkupPolicy.LENIENT,
            )
        } + listOfNotNull(
            if (defaultCount <= 0) null else TuiqiaoSlot(
                key = TuiqiaoSlot.DEFAULT_KEY,
                role = null,
                count = defaultCount,
                prompt = TuiqiaoPrompts.build(config, mode, request.fileName, context, setting, format, defaultCount),
                markup = if (plain) Tuiqiao.MarkupPolicy.NONE else Tuiqiao.MarkupPolicy.STRICT,
            )
        )
        return TuiqiaoPrepared(
            start = range.first,
            end = range.last + 1,
            original = context.selection,
            mode = mode,
            format = format,
            slots = slots,
        )
    }
}

/** 推敲的流式事件：某个候选位出了一条候选，或某个候选位失败（其余候选位不受影响）。 */
sealed interface TuiqiaoEvent {
    val slot: String

    data class Candidate(override val slot: String, val candidate: TuiqiaoCandidate) : TuiqiaoEvent
    data class SlotFailed(
        override val slot: String,
        val reason: TuiqiaoException.Reason?,
        val message: String?,
    ) : TuiqiaoEvent
}

/**
 * 各候选位并行调模型、流式解析。模型：角色指定了且仍存在就用它，否则当前助手的对话模型
 * （助手没指定就用全局对话模型）。只发一条 user 消息：不带助手提示词、工具、温度，推理关闭。
 * 候选逐个发出；与原文相同、重复、标记不合格的丢弃；某个候选位一条都没有时发 [TuiqiaoEvent.SlotFailed]。
 */
class TuiqiaoHandler(private val providerManager: ProviderManager) {
    fun events(settings: Settings, prepared: TuiqiaoPrepared): Flow<TuiqiaoEvent> = channelFlow {
        for (slot in prepared.slots) {
            launch {
                try {
                    runSlot(settings, prepared, slot) { send(TuiqiaoEvent.Candidate(slot.key, it)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: TuiqiaoException) {
                    send(TuiqiaoEvent.SlotFailed(slot.key, e.reason, null))
                } catch (e: Exception) {
                    send(TuiqiaoEvent.SlotFailed(slot.key, null, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun runSlot(
        settings: Settings,
        prepared: TuiqiaoPrepared,
        slot: TuiqiaoSlot,
        emit: suspend (TuiqiaoCandidate) -> Unit,
    ) {
        val model = resolveModel(settings, slot.role) ?: throw TuiqiaoException(TuiqiaoException.Reason.NO_MODEL)
        val provider = model.findProvider(settings.providers) ?: throw TuiqiaoException(TuiqiaoException.Reason.NO_MODEL)
        val handler = providerManager.getProviderByType(provider)
        val parser = Tuiqiao.StreamParser()
        val collector = TuiqiaoCollector(prepared.original, slot.count, settings.tuiqiao.explanation, slot.markup,
            keepTone = slot.role == null)
        var output = ""
        var messages = listOf(UIMessage.user(slot.prompt))
        val chunks = StreamChunkHandler(model)
        handler.streamText(
            providerSetting = provider,
            messages = messages,
            params = TextGenerationParams(model = model),
        ).collect { chunk ->
            messages = chunks.handle(messages, chunk)
            output = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.toText().orEmpty()
            collector.offer(parser.feed(output)).forEach { emit(it) }
        }
        collector.offer(parser.finish(output)).forEach { emit(it) }
        collector.failure()?.let { throw TuiqiaoException(it) }
    }

    companion object {
        /** 角色指定的模型（被删了就退回）→ 当前助手的模型 → 全局对话模型。 */
        fun resolveModel(settings: Settings, role: TuiqiaoRole?): Model? =
            role?.modelId?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel()
    }
}

/**
 * Filters parsed candidates: drops ones equal to the original, duplicates and ones whose Markdown
 * markup fails [markup], stops at [count], blanks `why` when explanations are off and `tone` for roles.
 */
class TuiqiaoCollector(
    private val original: String,
    private val count: Int,
    private val explanation: TuiqiaoExplanation,
    private val markup: Tuiqiao.MarkupPolicy = Tuiqiao.MarkupPolicy.STRICT,
    private val keepTone: Boolean = true,
) {
    private val seen = mutableSetOf<String>()
    private var markupRejected = 0

    fun offer(found: List<TuiqiaoCandidate>): List<TuiqiaoCandidate> = buildList {
        for (candidate in found) {
            if (seen.size >= count) break
            when (Tuiqiao.reject(original, candidate, seen, markup)) {
                null -> {
                    seen += Tuiqiao.squash(candidate.text)
                    add(candidate.copy(
                        why = if (explanation == TuiqiaoExplanation.OFF) "" else candidate.why,
                        tone = if (keepTone) candidate.tone else "",
                    ))
                }
                Tuiqiao.Rejection.MARKUP -> markupRejected++
                else -> Unit
            }
        }
    }

    /** Why nothing could be shown, once the output is complete; null when at least one was accepted. */
    fun failure(): TuiqiaoException.Reason? = when {
        seen.isNotEmpty() -> null
        markupRejected > 0 -> TuiqiaoException.Reason.MARKUP_BROKEN
        else -> TuiqiaoException.Reason.NO_CANDIDATES
    }
}
