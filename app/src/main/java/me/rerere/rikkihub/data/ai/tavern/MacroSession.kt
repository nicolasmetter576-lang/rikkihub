package me.rerere.rikkihub.data.ai.tavern

import me.rerere.tavern.core.ChatLine
import me.rerere.tavern.core.SystemTavernClock
import me.rerere.tavern.core.SystemTavernRandom
import me.rerere.tavern.core.TavernClock
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.macro.MacroEngine
import me.rerere.tavern.macro.MacroEnv
import me.rerere.tavern.macro.MacroEnvCharacter
import me.rerere.tavern.macro.MacroEnvNames
import me.rerere.tavern.regex.RegexMacroHost
import me.rerere.tavern.variable.Variables

/**
 * 一次求值的上下文：名字、变量、聊天、模型，以及 rikkihub 自有宏。
 * 宏的副作用（`{{setvar}}` 等）落在 [variables] 上，持久化由调用方在回合边界做。
 */
internal class MacroSession(
    private val engine: MacroEngine,
    val names: MacroEnvNames,
    val variables: Variables,
    private val model: String,
    private val ownMacros: Map<String, () -> String>,
    var chat: List<ChatLine> = emptyList(),
    private val character: MacroEnvCharacter = MacroEnvCharacter(),
    private val clock: TavernClock = SystemTavernClock,
    private val random: TavernRandom = SystemTavernRandom,
    private val chatId: String? = null,
    private val maxPromptTokens: Int = 0,
    private val maxContextTokens: Int = 0,
    private val maxResponseTokens: Int = 0,
) {
    /** 世界书 outlet 的内容，世界书激活之后才有。 */
    var outlets: Map<String, List<String>> = emptyMap()

    /** 当前输入框里的文字（`{{input}}`）。 */
    var input: String = ""

    fun env(
        content: String,
        namesOverride: MacroEnvNames = names,
        postProcess: (String) -> String = { it },
    ): MacroEnv = MacroEnv(
        content = content,
        names = namesOverride,
        character = character,
        model = model,
        postProcess = postProcess,
        ownMacros = ownMacros,
        variables = variables,
        chat = chat,
        input = input,
        isMobile = true,
        maxPromptTokens = maxPromptTokens,
        maxContextTokens = maxContextTokens,
        maxResponseTokens = maxResponseTokens,
        outletEntries = outlets,
        chatId = chatId,
        clock = clock,
        random = random,
    )

    fun evaluate(text: String): String = if (text.isEmpty()) text else engine.evaluate(text, env(text))

    /** 正则引擎用的求值入口：可临时换掉 `{{char}}`，可对每个宏的结果做后处理。 */
    val regexHost: RegexMacroHost = object : RegexMacroHost {
        override fun substitute(text: String, nameOverride: String?, postProcess: ((String) -> String)?): String {
            if (text.isEmpty()) return text
            val overridden = nameOverride?.let { names.copy(char = it) } ?: names
            return engine.evaluate(text, env(text, overridden, postProcess ?: { it }))
        }
    }
}
