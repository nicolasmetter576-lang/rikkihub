package me.rerere.rikkihub.data.ai.qubagu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import me.rerere.rikkihub.service.backgroundTextGenerationParams
import me.rerere.rikkihub.utils.JsonInstant
import kotlin.uuid.Uuid

class QubaguRefineException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { NO_MODEL, NOTHING_TO_REFINE, NO_REWRITES }
}

/** AI 精修里不碰模型的部分：拼请求、解析回复、把改写并进记录。 */
object QubaguRefine {
    const val MAX_TARGETS = 12

    /** 正文超过这个长度时不再附全文，只给待改写的句子。 */
    const val MAX_CONTEXT_CHARS = 12_000

    /** 默认的精修要求（用户可在设置里改）；输出格式由 [REWRITE_FORMAT] 另外固定，不在这里。 */
    val DEFAULT_PROMPT = """
        你是小说编辑，负责把正文里的套话（读者一眼就腻的陈词滥调）改掉。
        - 去掉标出的套话，换成贴合这段上下文的具体写法；想不出更好的，就用最平实的说法把意思说清楚。
        - 不改变事实、人物、动作的结果、叙述人称和时态；不添加原文没有的情节。
        - 不要引入新的套话，不要堆砌比喻和形容词。
        - 长度和原句相近，保持原文的语言，仍是一句完整的话，不要换行。
    """.trimIndent()

    private val REWRITE_FORMAT = """
        用户会给你一段正文，以及其中几句需要重写的句子，每句都标出了里面的套话。逐句重写，只重写列出的句子，不要动正文的其他部分。
        只输出一个 JSON 对象，键是句子编号，值是重写后的整句，例如 {"1":"……","2":"……"}。不要输出别的内容。
    """.trimIndent()

    private val ALTERNATIVES_FORMAT = """
        用户会给你一段上下文，以及其中一句需要重写的句子，标出了里面的套话。给出三种不同的重写，每种都是完整的一句话，只重写这一句。
        只输出一个 JSON 对象，键是 1、2、3，值是重写后的整句，例如 {"1":"……","2":"……","3":"……"}。不要输出别的内容。
    """.trimIndent()

    /** 文件里的 AI 建议附带的上下文：句子前后各这么多字。 */
    const val ALTERNATIVE_CONTEXT_CHARS = 1000

    /** 系统提示：精修要求 + 命中规则各自的改写要求（留空的不拼）+ 固定的输出格式。 */
    internal fun systemPrompt(config: QubaguConfig, rules: List<String>, format: String): String = buildString {
        append(config.effectiveRefinePrompt)
        val notes = rules.distinct().mapNotNull { key ->
            val rule = config.rule(key) ?: return@mapNotNull null
            rule.refinePrompt.trim().takeIf { it.isNotEmpty() }?.let { "- ${rule.name.ifBlank { rule.id }}：$it" }
        }
        if (notes.isNotEmpty()) append("\n\n【这些套话的改写要求】\n").append(notes.joinToString("\n"))
        append("\n\n").append(format)
    }

    fun messages(config: QubaguConfig, original: String, targets: List<QubaguRefineTarget>): List<UIMessage> {
        val request = buildString {
            if (original.length <= MAX_CONTEXT_CHARS) append("【正文】\n").append(original).append("\n\n")
            append("【需要重写的句子】")
            targets.forEachIndexed { index, target ->
                append("\n").append(index + 1).append(". 原句：").append(target.sentence)
                append("\n   套话：").append(target.phrases.joinToString("、") { "「$it」" })
            }
        }
        return listOf(UIMessage.system(systemPrompt(config, targets.flatMap { it.rules }, REWRITE_FORMAT)), UIMessage.user(request))
    }

    /** 工作区文件里一句八股的三种改写：带句子前后的上下文、句中的套话和命中规则的改写要求。 */
    fun alternativesMessages(config: QubaguConfig, text: String, annotation: QubaguAnnotation): List<UIMessage> {
        val from = (annotation.start - ALTERNATIVE_CONTEXT_CHARS).coerceAtLeast(0)
        val to = (annotation.end + ALTERNATIVE_CONTEXT_CHARS).coerceAtMost(text.length)
        val request = buildString {
            append("【上下文】\n").append(text, from, to).append("\n\n")
            append("【需要重写的句子】\n").append(annotation.original)
            if (annotation.phrases.isNotEmpty()) append("\n套话：").append(annotation.phrases.joinToString("、") { "「$it」" })
        }
        return listOf(UIMessage.system(systemPrompt(config, annotation.rules, ALTERNATIVES_FORMAT)), UIMessage.user(request))
    }

    /** 模型回复里的改写：句子编号（从 1 起）→ 改写后的句子。解析不了返回空表。 */
    fun parse(output: String): Map<Int, String> {
        val body = THINK_BLOCK.replace(output, "")
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val parsed = runCatching { JsonInstant.parseToJsonElement(body.substring(start, end + 1)) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        return buildMap {
            for ((key, value) in parsed) {
                val number = key.trim().toIntOrNull() ?: continue
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
                put(number, text)
            }
        }
    }

    /** 改写能不能用：有内容、还是一句话、长度不离谱、没有保留被标出的套话。 */
    fun accepts(target: QubaguRefineTarget, rewrite: String): Boolean =
        rewrite.isNotBlank() && '\n' !in rewrite && rewrite != target.sentence &&
            rewrite.length <= target.sentence.length * 2 + 20 && target.phrases.none { it in rewrite }

    /**
     * 把能用的改写并进记录：每句成为一条覆盖整句的改动（已应用），句内原有的改动被它取代。
     * 没通过 [accepts] 的句子保持原样。
     */
    fun apply(original: String, record: QubaguRecord, targets: List<QubaguRefineTarget>, rewrites: Map<Int, String>): QubaguRecord {
        var edits = record.edits
        targets.forEachIndexed { index, target ->
            val rewrite = rewrites[index + 1]?.trim() ?: return@forEachIndexed
            if (!accepts(target, rewrite)) return@forEachIndexed
            edits = edits.filterNot { it.start >= target.start && it.end <= target.end } +
                QubaguEdit(target.start, original.substring(target.start, target.end), rewrite, QubaguRules.AI)
        }
        return record.copy(edits = edits.sortedBy { it.start })
    }

    private val THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
}

/**
 * AI 精修：本地规则找到的套话所在的句子，交给模型结合上下文重写。只发一条系统提示和一条用户消息，
 * 不带助手提示词和工具，推理关闭。模型：去八股设置里指定了且仍存在就用它，否则用 [fallback]（当前回复的模型）。
 */
class QubaguRefiner(
    private val engine: Qubagu,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder? = null,
) {
    /**
     * 返回并入了改写的记录。没有可精修的句子、没有模型、模型一句能用的都没给时抛 [QubaguRefineException]；
     * 请求本身的错误原样抛出。
     */
    suspend fun refine(
        original: String,
        record: QubaguRecord,
        settings: Settings,
        fallback: Model?,
        assistantId: Uuid? = null,
        conversationId: Uuid? = null,
    ): QubaguRecord {
        val config = settings.qubagu
        val targets = engine.refineTargets(original, record, config).take(QubaguRefine.MAX_TARGETS)
        if (targets.isEmpty()) throw QubaguRefineException(QubaguRefineException.Reason.NOTHING_TO_REFINE)
        val output = ask(QubaguRefine.messages(config, original, targets), settings, fallback, UsageTag(UsageSource.QUBAGU, assistantId, conversationId))
        val refined = QubaguRefine.apply(original, record, targets, QubaguRefine.parse(output))
        if (refined == record) throw QubaguRefineException(QubaguRefineException.Reason.NO_REWRITES)
        return refined
    }

    /**
     * 工作区文件里一句八股的三种 AI 改写（通过 [QubaguRefine.accepts] 的，最多三条）。[text] 是整个文件；
     * 模型同 [refine]，[fallback] 由调用方给（文件里一般是当前的对话模型）。一条能用的都没有时抛 [QubaguRefineException]。
     */
    suspend fun suggest(text: String, annotation: QubaguAnnotation, settings: Settings, fallback: Model?): List<String> {
        val target = QubaguRefineTarget(annotation.start, annotation.end, annotation.original, annotation.phrases, annotation.rules)
        val output = ask(QubaguRefine.alternativesMessages(settings.qubagu, text, annotation), settings, fallback, UsageTag(UsageSource.QUBAGU, null, null))
        val rewrites = QubaguRefine.parse(output).filterKeys { it in 1..Qubagu.MAX_SUGGESTIONS }.toSortedMap().values.map { it.trim() }.filter { QubaguRefine.accepts(target, it) }.distinct()
        if (rewrites.isEmpty()) throw QubaguRefineException(QubaguRefineException.Reason.NO_REWRITES)
        return rewrites.take(Qubagu.MAX_SUGGESTIONS)
    }

    /** 用去八股设置里的模型（没设或已删就用 [fallback]）发一次请求，推理关，返回回复的文字。 */
    private suspend fun ask(messages: List<UIMessage>, settings: Settings, fallback: Model?, tag: UsageTag): String {
        val model = settings.qubagu.refineModelId?.let { settings.findModelById(it) } ?: fallback
            ?: throw QubaguRefineException(QubaguRefineException.Reason.NO_MODEL)
        val provider = model.findProvider(settings.providers) ?: throw QubaguRefineException(QubaguRefineException.Reason.NO_MODEL)
        val handler = usageRecorder.wrapOrSelf(providerManager.getProviderByType(provider), tag)
        var conversation = messages
        val chunks = StreamChunkHandler(model)
        handler.streamText(
            providerSetting = provider,
            messages = messages,
            params = backgroundTextGenerationParams(model, reasoningLevel = ReasoningLevel.OFF),
        ).collect { conversation = chunks.handle(conversation, it) }
        return conversation.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.toText().orEmpty()
    }
}
