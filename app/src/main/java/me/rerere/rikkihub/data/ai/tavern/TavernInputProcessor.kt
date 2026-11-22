package me.rerere.rikkihub.data.ai.tavern

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.tavern.core.SystemTavernClock
import me.rerere.tavern.core.SystemTavernRandom
import me.rerere.tavern.core.TavernClock
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.macro.MacroEnvCharacter
import me.rerere.tavern.macro.MacroEnvNames
import me.rerere.tavern.regex.RegexParams
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.variable.VariableScope
import me.rerere.tavern.variable.Variables
import kotlin.uuid.Uuid

/** 发送时处理后的用户输入，以及宏求值之后要保存的变量。 */
class ProcessedInput(
    val parts: List<UIMessagePart>,
    /** 变量被改过时的新会话状态（`tavern_metadata`），否则为 null。 */
    val metadata: JsonObject?,
    /** 全局变量被改过时的新值，否则为 null。 */
    val globalVariables: JsonObject?,
)

/**
 * 用户输入进入聊天之前的处理（`ST:public/script.js` 发送路径）：先跑 USER_INPUT 正则，再求宏，结果存进消息。
 * 存下来的是求值后的文本：之后每次请求都原样使用，`{{cur_date}}` 不会随日期变化而改写历史，提示词缓存也稳定。
 */
class TavernInputProcessor(private val runtime: TavernRuntime) {

    /** 发送：正则 + 宏。 */
    suspend fun processSend(
        parts: List<UIMessagePart>,
        assistant: Assistant,
        settings: Settings,
        model: Model,
        conversationId: Uuid?,
        metadata: JsonObject,
        history: List<UIMessage>,
        ownMacros: Map<String, () -> String>,
        clock: TavernClock = SystemTavernClock,
        random: TavernRandom = SystemTavernRandom,
        character: ActiveCharacter? = null,
    ): ProcessedInput {
        val scripts = RegexScripts.of(settings, assistant, character)
        val state = TavernConversationState.from(metadata)
        val local = VariableScope(state.variables)
        val global = VariableScope(settings.tavern.globalVariables)
        val userName = RikkihubMacros.userName(settings)
        val charName = RikkihubMacros.charName(assistant, character)
        fun session(card: MacroEnvCharacter) = MacroSession(
            engine = runtime.macroEngine,
            names = MacroEnvNames.solo(userName, charName),
            variables = Variables(local, global),
            model = model.modelId,
            ownMacros = ownMacros,
            chat = TurnPreparation.chatLines(history, userName, charName),
            character = card,
            clock = clock,
            random = random,
            chatId = conversationId?.toString(),
        )
        val session = session(CardFields.compute(session(MacroEnvCharacter()), character, settings).macroEnv())
        val processed = parts.map { part ->
            if (part !is UIMessagePart.Text) return@map part
            session.input = part.text
            val regexed = runtime.regexEngine.getRegexedString(scripts, part.text, RegexPlacement.USER_INPUT, session.regexHost)
            part.copy(text = session.evaluate(regexed))
        }
        return ProcessedInput(
            parts = processed,
            metadata = if (local.dirty) state.copy(variables = local.toJson()).toMetadata() else null,
            globalVariables = if (global.dirty) global.toJson() else null,
        )
    }

    /** 编辑已发送的消息：只跑标了「编辑时运行」的脚本，不再求宏（宏在发送时求过一次了）。 */
    suspend fun processEdit(
        parts: List<UIMessagePart>,
        assistant: Assistant,
        settings: Settings,
        character: ActiveCharacter? = null,
    ): List<UIMessagePart> {
        val scripts = RegexScripts.of(settings, assistant, character)
        if (scripts.none { it.runOnEdit && !it.disabled }) return parts
        val host = TavernRegexes(runtime).hostFor(settings, assistant, character)
        return parts.map { part ->
            if (part !is UIMessagePart.Text) part
            else part.copy(
                text = runtime.regexEngine.getRegexedString(
                    scripts, part.text, RegexPlacement.USER_INPUT, host, RegexParams(isEdit = true),
                )
            )
        }
    }
}
