package me.rerere.rikkihub.data.ai.tavern

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.splitRegexLiteral
import me.rerere.tavern.core.JsRegexSyntaxException
import me.rerere.tavern.macro.MacroEnvNames
import me.rerere.tavern.regex.RegexParams
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.regex.SubstituteFindRegex
import me.rerere.tavern.variable.VariableScope
import me.rerere.tavern.variable.Variables

/**
 * 正则在三个时机的入口（照上游：存储时改存储，显示时只改显示，提示词时只改发出去的文本）：
 * - 用户输入：发送时，宏求值之前（[TavernInputProcessor]）；
 * - AI 输出与推理：生成结束时（[TavernRegexOutputTransformer]）；
 * - 仅显示：渲染时，不动存储（[display]）。
 * 提示词时的「仅提示词」脚本在 [PreparedTurn.build]。
 */
class TavernRegexes(private val runtime: TavernRuntime) {

    /** 渲染消息文本：只跑 `markdownOnly` 脚本，按消息离末尾的距离（[depth]，末条为 0）过滤。 */
    suspend fun display(
        text: String,
        role: MessageRole,
        depth: Int?,
        settings: Settings,
        assistant: Assistant?,
        isReasoning: Boolean = false,
        character: ActiveCharacter? = null,
    ): String {
        val scripts = RegexScripts.of(settings, assistant, character).filter { it.markdownOnly && !it.disabled }
        if (scripts.isEmpty() || text.isEmpty()) return text
        val placement = placementFor(role, isReasoning) ?: return text
        return runtime.regexEngine.getRegexedString(
            scripts, text, placement, hostFor(settings, assistant, character),
            RegexParams(isMarkdown = true, depth = depth),
        )
    }

    /** 渲染前先问一句：有没有需要跑的显示脚本（没有就别去创建协程）。 */
    fun hasDisplayScripts(settings: Settings, assistant: Assistant?, character: ActiveCharacter? = null): Boolean =
        RegexScripts.of(settings, assistant, character).any { it.markdownOnly && !it.disabled }

    /**
     * 查找串能不能编译。带「替换前先代换」的脚本，查找串里的宏要运行时才展开，这里没法判断，返回 [RegexCheck.Skipped]。
     * 不合法时尽量带上引擎的错误信息。
     */
    suspend fun checkFindRegex(findRegex: String, substituteRegex: Int): RegexCheck {
        if (findRegex.isBlank()) return RegexCheck.Empty
        if (substituteRegex != SubstituteFindRegex.NONE) return RegexCheck.Skipped
        val factory = runtime.regexFactory
        // 写成 /模式/标志 就按字面编译：酒馆对标志不合法的写法会退回「整串当普通模式」，不报错，
        // 但对写这个的人来说几乎一定是写错了，所以这里直接报引擎的错误
        val (source, flags) = splitRegexLiteral(findRegex) ?: (findRegex to "")
        return try {
            factory.compile(source, flags)
            RegexCheck.Ok
        } catch (e: JsRegexSyntaxException) {
            RegexCheck.Invalid(e.message)
        }
    }

    /**
     * 拿一段示例文本试跑一个脚本，结果和真实使用时一致（同一个引擎、同一套宏环境）。
     * 脚本被禁用也照跑，这是给用户看效果的；深度、作用点这些过滤条件不参与。
     */
    suspend fun preview(script: RegexScript, sample: String, settings: Settings, assistant: Assistant?, character: ActiveCharacter? = null): String =
        runtime.regexEngine.runRegexScript(script.copy(disabled = false), sample, hostFor(settings, assistant, character))

    internal fun hostFor(settings: Settings, assistant: Assistant?, character: ActiveCharacter? = null) = MacroSession(
        engine = runtime.macroEngine,
        names = MacroEnvNames.solo(
            RikkihubMacros.userName(settings),
            character?.name ?: assistant?.let(RikkihubMacros::charName) ?: "assistant",
        ),
        variables = Variables(global = VariableScope(settings.tavern.globalVariables)),
        model = "",
        ownMacros = emptyMap(),
    ).regexHost

    internal fun placementFor(role: MessageRole, isReasoning: Boolean): Int? = when {
        isReasoning -> RegexPlacement.REASONING
        role == MessageRole.USER -> RegexPlacement.USER_INPUT
        role == MessageRole.ASSISTANT -> RegexPlacement.AI_OUTPUT
        else -> null
    }
}

/** 正则查找串的检查结果（编辑界面标红用）。 */
sealed interface RegexCheck {
    data object Empty : RegexCheck
    data object Ok : RegexCheck

    /** 查找串带宏（先代换），要到运行时才知道最终的模式。 */
    data object Skipped : RegexCheck
    data class Invalid(val message: String?) : RegexCheck
}

/**
 * 生成结束时作用在 AI 输出（和推理）上的正则：改存储。取代旧的助手正则输出转换器。
 * 流式过程中每个 chunk 也会走 `visualTransform` 让界面看到处理后的样子；`markdownOnly`、`promptOnly` 的脚本不在这里跑。
 */
class TavernRegexOutputTransformer(private val runtime: TavernRuntime) : OutputMessageTransformer {
    private val regexes = TavernRegexes(runtime)

    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        // 有本回合的准备结果就用它的脚本表和宏环境（含角色自带的脚本、角色名）
        val scripts = (ctx.turn?.regexScripts ?: RegexScripts.of(ctx.settings, ctx.assistant))
            .filter { !it.disabled && !it.markdownOnly && !it.promptOnly }
        if (scripts.isEmpty()) return messages
        val host = ctx.turn?.macros?.regexHost ?: regexes.hostFor(ctx.settings, ctx.assistant)
        return messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) return@map message
            message.copy(parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> part.copy(text = apply(scripts, part.text, RegexPlacement.AI_OUTPUT, host))
                    is UIMessagePart.Reasoning -> part.copy(reasoning = apply(scripts, part.reasoning, RegexPlacement.REASONING, host))
                    else -> part
                }
            })
        }
    }

    private suspend fun apply(scripts: List<RegexScript>, text: String, placement: Int, host: me.rerere.tavern.regex.RegexMacroHost) =
        runtime.regexEngine.getRegexedString(scripts, text, placement, host)
}
