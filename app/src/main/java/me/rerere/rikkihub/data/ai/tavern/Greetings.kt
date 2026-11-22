package me.rerere.rikkihub.data.ai.tavern

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.tavern.core.SystemTavernClock
import me.rerere.tavern.core.SystemTavernRandom
import me.rerere.tavern.core.TavernClock
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.macro.MacroEnvCharacter
import me.rerere.tavern.macro.MacroEnvNames
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.variable.VariableScope
import me.rerere.tavern.variable.Variables

/** 新会话的第一条消息：[node] 是开场白（多条备选是同一个节点的多个分支），[metadata] 是开场白里 `{{setvar}}` 写下的会话变量。 */
class BuiltGreeting(val node: MessageNode, val metadata: JsonObject?)

/**
 * 角色的开场白：`first_mes` 在前、`alternate_greetings` 在后，都作为第一个节点的分支（右滑切换）。
 * 顺序照酒馆：先过 AI 输出正则（改存储的那类脚本），再求宏；这里宏在建会话时求一次存下来，
 * 和用户输入一样不会在之后每次请求里再变（酒馆是显示时才求，rikkihub 的消息正文不重新求宏）。
 * 选中的第一条里的 `{{setvar}}` 写进会话变量，其余备选只求值、不留副作用。
 */
class GreetingBuilder(private val runtime: TavernRuntime) {
    suspend fun build(
        character: ActiveCharacter,
        assistant: Assistant,
        settings: Settings,
        model: Model,
        ownMacros: Map<String, () -> String>,
        clock: TavernClock = SystemTavernClock,
        random: TavernRandom = SystemTavernRandom,
    ): BuiltGreeting? {
        val greetings = character.record.card.greetings
        if (greetings.isEmpty()) return null
        val scripts = RegexScripts.of(settings, assistant, character).filter { !it.disabled && !it.markdownOnly && !it.promptOnly }
        val userName = RikkihubMacros.userName(settings)
        val charName = RikkihubMacros.charName(assistant, character)
        var firstVariables: JsonObject? = null

        suspend fun evaluate(text: String, keepVariables: Boolean): String {
            val local = VariableScope(JsonObject(emptyMap()))
            fun session(card: MacroEnvCharacter) = MacroSession(
                engine = runtime.macroEngine,
                names = MacroEnvNames.solo(userName, charName),
                variables = Variables(local, VariableScope(settings.tavern.globalVariables)),
                model = model.modelId,
                ownMacros = ownMacros,
                character = card,
                clock = clock,
                random = random,
            )
            val session = session(CardFields.compute(session(MacroEnvCharacter()), character, settings).macroEnv())
            val regexed = if (scripts.isEmpty()) text else runtime.regexEngine.getRegexedString(scripts, text, RegexPlacement.AI_OUTPUT, session.regexHost)
            val result = session.evaluate(regexed)
            if (keepVariables && local.dirty) firstVariables = local.toJson()
            return result
        }

        val messages = greetings.mapIndexed { index, text -> UIMessage.assistant(evaluate(text, keepVariables = index == 0)) }
        val metadata = firstVariables?.let { TavernConversationState.from(JsonObject(emptyMap())).copy(variables = it).toMetadata() }
        return BuiltGreeting(MessageNode(messages = messages, selectIndex = 0), metadata)
    }
}
