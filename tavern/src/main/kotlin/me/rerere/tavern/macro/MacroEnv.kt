package me.rerere.tavern.macro

import me.rerere.tavern.core.ChatLine
import me.rerere.tavern.core.TavernClock
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.variable.Js
import me.rerere.tavern.variable.Variables

/**
 * 宏求值环境。ST:public/scripts/macros/engine/MacroEnv.types.js + MacroEnvBuilder.js + substituteParams。
 * 上游从页面全局取的东西（`ctx.chat`、`chat_metadata.variables`、`extension_settings`、`oai_settings`、
 * `#send_textarea`、DOM 的 mesid、`chat_metadata.main_chat`/`pick_reroll_seed`）全部改为注入字段。
 */
data class MacroEnv(
    /** 正在被求值的完整原文（`{{pick}}` 拿它做确定性种子）。 */
    val content: String,
    val names: MacroEnvNames = MacroEnvNames(),
    val character: MacroEnvCharacter = MacroEnvCharacter(),
    /** `env.system.model`。 */
    val model: String = "",
    /**
     * `env.functions.original`：上游是一次性函数——第一次调用返回原文，之后返回 ''。
     * null 时 `{{original}}` 的调用方会抛错，引擎按内部错误把 `{{original}}` 原样输出。
     */
    val original: (() -> String)? = null,
    /** `env.functions.postProcess`：每个宏结果过一遍。 */
    val postProcess: (String) -> String = { it },
    /** `env.dynamicMacros`：本次求值可见的动态宏（键已归一到小写）。 */
    val dynamicMacros: Map<String, DynamicMacro> = emptyMap(),
    /**
     * rikkihub 自有宏（cur_date、model_id、model_name、locale、timezone、system_version、
     * device_info、battery_level、nickname，char/user 由内置宏负责）。并入动态宏解析路径，
     * 同样大小写不敏感、同样零参数。
     */
    val ownMacros: Map<String, () -> String> = emptyMap(),
    /** 变量作用域：聊天变量与全局变量（`ctx.variables.{local,global}` 的注入版）。 */
    val variables: Variables = Variables(),
    /** `ctx.chat`。 */
    val chat: List<ChatLine> = emptyList(),
    /** `#send_textarea` 的内容（`{{input}}`）。 */
    val input: String = "",
    val isMobile: Boolean = false,
    /** `state-macros.js` 里事件跟踪出来的 lastGenerationType；空串 = 还没生成过。 */
    val lastGenerationType: String = "",
    val maxPromptTokens: Int = 0,
    val maxContextTokens: Int = 0,
    val maxResponseTokens: Int = 0,
    /** `chat_metadata.lastInContextMessageId`（`{{firstIncludedMessageId}}`）。 */
    val firstIncludedMessageId: Int? = null,
    /** DOM 里第一个已渲染消息的 mesid（`{{firstDisplayedMessageId}}`）。 */
    val firstDisplayedMessageId: Int? = null,
    /** `extension_prompts` 里的世界书 outlet 内容：`outlet 名 -> 行`。 */
    val outletEntries: Map<String, List<String>> = emptyMap(),
    /** `chat_metadata.main_chat ?? getCurrentChatId()`，用来算 `{{pick}}` 的种子。 */
    val chatId: String? = null,
    /** `chat_metadata.pick_reroll_seed`（`{{pick}}` 的 reroll 因子）。 */
    val pickRerollSeed: String? = null,
    /**
     * `{{pick}}` 种子里的 chatId 哈希覆盖（ST:chat_metadata.chat_id_hash 是会话缓存）。
     * null 时按 `getStringHash(chatId ?: "")` 现算。
     */
    val chatIdHash: Double? = null,
    val clock: TavernClock = me.rerere.tavern.core.SystemTavernClock,
    val random: TavernRandom = me.rerere.tavern.core.SystemTavernRandom,
    /** 运行时告警收集（对应上游 logMacroRuntimeWarning/console.warn）。 */
    val warn: (String) -> Unit = {},
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** `env.contentHash` = getStringHash(content)，懒算值和上游一次性算值相同。 */
    val contentHash: Double get() = Js.getStringHash(content)
}

/** `env.names`。单人聊天由调用方按 `getGroupValue` 的 selected_group=null 分支填。 */
data class MacroEnvNames(
    val user: String = "",
    val char: String = "",
    val group: String = "",
    val groupNotMuted: String = "",
    val notChar: String = "",
) {
    companion object {
        /** ST:MacroEnvBuilder.js:189 getGroupValue 的 selected_group=null 分支。 */
        fun solo(user: String, char: String) = MacroEnvNames(
            user = user, char = char,
            group = char, groupNotMuted = char, notChar = user,
        )
    }
}

/** `env.character`（`getCharacterCardFieldsLazy` 的字段，1A 无角色时全空）。 */
data class MacroEnvCharacter(
    val charPrompt: String = "",
    val charInstruction: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val persona: String = "",
    val mesExamplesRaw: String = "",
    val charDepthPrompt: String = "",
    val creatorNotes: String = "",
    val version: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
)

/** `env.dynamicMacros` 的三种合法形态。 */
sealed interface DynamicMacro {
    /** 直接值，不允许参数。 */
    data class Value(val text: String) : DynamicMacro

    /** 处理函数（上游 fn 形态）。 */
    fun interface Fn : DynamicMacro {
        fun handle(call: MacroExecutionContext): Any?
    }

    /** 完整定义（上游 MacroDefinitionOptions 形态）。 */
    data class Options(val options: MacroDefinitionOptions) : DynamicMacro
}
