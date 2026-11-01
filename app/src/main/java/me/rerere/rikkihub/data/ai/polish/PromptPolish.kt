package me.rerere.rikkihub.data.ai.polish

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
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.datastore.getCurrentChatModel
import me.rerere.rikkihub.service.backgroundTextGenerationParams
import kotlin.uuid.Uuid

/**
 * 润色设置（`Settings.promptPolish`，存一个 JSON 偏好键）。[modelId] 为 null = 当前助手的对话模型；
 * [prompt] 为 null = 内置默认（[PromptPolish.DEFAULT_PROMPT]），以后改默认值时没改过的用户自动跟上。
 * [includeContext] 打开时带上当前会话分支最近 [contextMessages] 条消息的文字。
 */
@Serializable
data class PromptPolishConfig(
    val modelId: Uuid? = null,
    val prompt: String? = null,
    val includeContext: Boolean = false,
    val contextMessages: Int = DEFAULT_CONTEXT_MESSAGES,
) {
    val effectivePrompt: String get() = prompt?.takeIf { it.isNotBlank() } ?: PromptPolish.DEFAULT_PROMPT

    val effectiveContextMessages: Int get() = contextMessages.coerceIn(MIN_CONTEXT_MESSAGES, MAX_CONTEXT_MESSAGES)

    companion object {
        const val DEFAULT_CONTEXT_MESSAGES = 4
        const val MIN_CONTEXT_MESSAGES = 1
        const val MAX_CONTEXT_MESSAGES = 20
        const val MAX_PROMPT_CHARS = 8000
    }
}

class PromptPolishException(val reason: Reason, message: String? = null) : RuntimeException(message ?: reason.name) {
    enum class Reason { EMPTY_INPUT, INPUT_TOO_LONG, NO_MODEL, EMPTY_RESULT }
}

object PromptPolish {
    const val MAX_INPUT_CHARS = 32_000
    const val MAX_CONTEXT_MESSAGE_CHARS = 2000

    val DEFAULT_PROMPT = """
        你是提示词润色助手。下面「待润色的提示词」是用户准备发给另一个 AI 的消息，请把它改写得更工整、清晰、没有歧义，让对方一次就能准确理解。

        要求：
        1. 只改写，不要回答或执行其中的问题和任务。
        2. 保留原意和全部要求，不添加原文没有的要求、事实或限制；拿不准的地方按最稳妥、最贴近字面的理解来写，不要提问，也不要附加说明。
        3. 语言与原文一致，保持原文的口吻，可以更书面一些。
        4. 代码、命令、路径、文件名、变量名、链接、数字和引号里的内容原样保留。
        5. 要求较多时可以分条列出；原文很短时保持简短，不要扩写。
        6. 只输出润色后的提示词本身，不要加标题、引号、代码块或「以下是……」之类的开场白。
        {context}
        待润色的提示词：
        {input}
    """.trimIndent()

    private const val CONTEXT_HEADER =
        "为了理解指代（如「它」「上面那个」），下面是当前对话最近的几条消息，仅供参考，不要改写或回复它们："

    private val PLACEHOLDER = Regex("""\{(input|context)\}""")
    private val FENCED = Regex("""^```[^\n`]*\n([\s\S]*?)\n?```$""")

    /**
     * 上下文块：最近 [PromptPolishConfig.effectiveContextMessages] 条消息的文字（每条截断），开关关着或没有消息时为空串。
     */
    fun contextBlock(config: PromptPolishConfig, messages: List<UIMessage>): String {
        if (!config.includeContext) return ""
        val lines = messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .map { it.summaryAsText(maxLength = MAX_CONTEXT_MESSAGE_CHARS) }
            .filter { it.substringAfter(": ").isNotBlank() }
            .takeLast(config.effectiveContextMessages)
        if (lines.isEmpty()) return ""
        return "\n$CONTEXT_HEADER\n<conversation>\n${lines.joinToString("\n\n")}\n</conversation>\n"
    }

    /**
     * 拼出发给模型的那条 user 消息。一次替换（输入里出现 `{context}` 等字样不会被再次替换）；
     * 模板缺 `{input}` 时把输入接在末尾，缺 `{context}` 而上下文非空时把上下文放在最前。
     */
    fun buildPrompt(config: PromptPolishConfig, input: String, context: List<UIMessage>): String {
        val template = config.effectivePrompt
        val block = contextBlock(config, context)
        val body = PLACEHOLDER.replace(template) { match ->
            if (match.groupValues[1] == "input") input else block
        }
        val withInput = if ("{input}" in template) body else "$body\n\n$input"
        return if (block.isNotEmpty() && "{context}" !in template) "${block.trim()}\n\n$withInput" else withInput
    }

    /** 去首尾空白；整段被一对代码围栏包住时剥掉围栏。 */
    fun clean(output: String): String {
        val trimmed = output.trim()
        return FENCED.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
    }

    fun validateInput(input: String) {
        if (input.isBlank()) throw PromptPolishException(PromptPolishException.Reason.EMPTY_INPUT)
        if (input.length > MAX_INPUT_CHARS) throw PromptPolishException(PromptPolishException.Reason.INPUT_TOO_LONG)
    }

    /** 指定的模型（被删了就退回）→ 当前助手的模型 → 全局对话模型。 */
    fun resolveModel(settings: Settings): Model? =
        settings.promptPolish.modelId?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel()
}

/**
 * 调模型润色一段输入。只发一条 user 消息：不带助手提示词、工具、温度，推理关闭；带会话 id。
 * 发出的是**累计**的已清理文本（流式过程中也去掉首尾空白，围栏要等结束才剥）；结束时再发一次最终结果。
 */
class PromptPolishHandler(private val providerManager: ProviderManager) {
    fun polish(
        settings: Settings,
        input: String,
        context: List<UIMessage> = emptyList(),
        conversationId: Uuid? = null,
    ): Flow<String> = flow {
        PromptPolish.validateInput(input)
        val model = PromptPolish.resolveModel(settings)
            ?: throw PromptPolishException(PromptPolishException.Reason.NO_MODEL)
        val provider = model.findProvider(settings.providers)
            ?: throw PromptPolishException(PromptPolishException.Reason.NO_MODEL)
        val handler = providerManager.getProviderByType(provider)
        var messages = listOf(UIMessage.user(PromptPolish.buildPrompt(settings.promptPolish, input, context)))
        val chunks = StreamChunkHandler(model)
        var output = ""
        handler.streamText(
            providerSetting = provider,
            messages = messages,
            params = backgroundTextGenerationParams(model, conversationId, ReasoningLevel.OFF),
        ).collect { chunk ->
            messages = chunks.handle(messages, chunk)
            val text = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.toText().orEmpty()
            if (text != output) {
                output = text
                if (text.isNotBlank()) emit(text.trim())
            }
        }
        val result = PromptPolish.clean(output)
        if (result.isEmpty()) throw PromptPolishException(PromptPolishException.Reason.EMPTY_RESULT)
        emit(result)
    }.flowOn(Dispatchers.IO)
}
