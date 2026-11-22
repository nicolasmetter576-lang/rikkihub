package me.rerere.tavern.prompt

import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.core.WorldInfoPromptResult

/**
 * 组装输入的历史条目（最旧在前）。rikkihub 的消息可能带图片/工具调用，`:tavern` 不感知——
 * [text] 是接入层准备好的纯文本（已套好深度正则等），[tokens] 为 null 时按 ST 的消息开销计数。
 */
data class HistoryItem(
    val role: PromptRole,
    val name: String? = null,
    val text: String,
    val tokens: Int? = null,
)

/** 组装输出：要么是新生成的消息，要么映射回输入历史的一条。 */
sealed interface AssembledItem {
    /** ST `Message` 的落地形态。 */
    data class Generated(
        val role: PromptRole,
        val content: String,
        val name: String? = null,
        val identifier: String,
    ) : AssembledItem

    /** 对应输入 `history[originalIndex]`，接入层按下标映射回真实消息。 */
    data class History(val originalIndex: Int) : AssembledItem
}

/** 组装结果：有序消息 + Claude 预填（[assistantPrefillSupported] 才可能有值）。 */
data class AssemblyResult(
    val items: List<AssembledItem>,
    /** ST `generate_data.assistant_prefill`：仅 Claude 类来源产出，continue_prefill 时并入续写消息而不再单独产出。 */
    val assistantPrefill: String? = null,
)

/** 外部依赖收口。组装本身无环境副作用——宏求值走 [substitute]，分词走 [countTokens]。 */
fun interface TokenCounterFn {
    fun countTokens(text: String): Int
}

interface PromptHost {
    /** ST `substituteParams`：提示词文本里的宏求值（由接入层把 `MacroEngine` 注入进来）。 */
    fun substitute(text: String): String

    /** ST `tokenHandler.countAsync` 的文本计数（cl100k 等），消息开销由组装器自己加。 */
    fun countTokens(text: String): Int

    /**
     * 历史内插入位置的修正回调。[index] 是一块深度注入将要落到 [items]（不含这块自己，最旧在前）里的下标，
     * 返回修正后的下标。rikkihub 的提供商约束（避开 USER→带工具的 ASSISTANT 之间）由接入层实现；默认恒等。
     */
    fun adjustHistoryInsertIndex(index: Int, items: List<HistoryProbe>): Int = index
}

/**
 * [PromptHost.adjustHistoryInsertIndex] 看到的一条历史。[originalIndex] 指回 [AssemblyInput.history]，
 * 已经插入的注入块为 -1。
 */
data class HistoryProbe(val role: PromptRole, val originalIndex: Int)

/** ST `persona_description_positions`（power-user.js）。 */
object PersonaDescriptionPosition {
    const val IN_PROMPT = 0
    const val TOP_AN = 2
    const val BOTTOM_AN = 3
    const val AT_DEPTH = 4
    const val NONE = 9
}

/** ST `extension_prompt_types`（script.js）。 */
object ExtensionPromptType {
    const val NONE = -1
    const val IN_PROMPT = 0
    const val IN_CHAT = 1
    const val BEFORE_PROMPT = 2
}

/**
 * 一条 extension prompt（上游 `extension_prompts[key]`）。
 * [filter] 对应上游的 `prompt.filter()`；null 视为通过。
 */
data class ExtensionPromptInput(
    val value: String,
    val position: Int,
    val depth: Int? = null,
    val role: PromptRole? = null,
    val filter: (() -> Boolean)? = null,
)

/**
 * 组装输入。
 *
 * 「预先求值」约定（哪些文本要求调用方先套好，哪些由组装器调 [PromptHost.substitute]）：
 * - 卡字段 [description]/[personality]/[scenario]/[persona]/[mesExamples]/[charDepthPrompt]：
 *   上游在 `getCharacterCardFields` 里做 `baseChatReplace`（代名字宏），接入层给已替换好的值；
 *   组装器不会再对它们求值。
 * - 预设/设置文本（`main`/`jailbreak`/`nsfw` 等条目内容、`new_chat_prompt`、`scenario_format` 包装结果、
 *   `impersonation_prompt`、`continue_nudge_prompt`、`send_if_empty`、作者注释、`quietPrompt`、`bias`、
 *   extension prompt 的 value）：由组装器调 [PromptHost.substitute] 求值，与上游 `preparePrompt`/`getExtensionPrompt` 一致。
 * - [history].text：**组装器会求值**（上游 `populateChatHistory` 对每条历史消息过 `preparePrompt`，golden 里
 *   `Hi {{char}}!` → `Hi Seraphina!` 证实）。未知宏原样保留，但 `{{user}}` 这类已知宏会被替换——聊天里原样写着
 *   宏名的内容（比如讨论模板语法）也会被换掉，这是酒馆的行为；接入层要原样保留的文本得自己转义。
 * - [worldInfo] / [rikkihubContext]：视为成品文本，不再求值（世界书在组装之前已由世界书引擎求值；
 *   rikkihubContext 里的记忆/工具说明可能原样含 `{{…}}`）。
 */
data class AssemblyInput(
    val layout: PromptLayout,
    val options: AssemblyOptions = AssemblyOptions(),
    /** ST `type`：normal/continue/impersonate/swipe/regenerate/quiet。 */
    val generationType: me.rerere.tavern.core.GenerationType = me.rerere.tavern.core.GenerationType.NORMAL,
    // ---- 卡字段（见上方约定）----
    val charName: String = "",
    val userName: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val persona: String = "",
    /** 用户设定的落点（persona_description_positions）：IN_PROMPT 时进 `personaDescription` 标记；TOP_AN/BOTTOM_AN 并入作者注释；AT_DEPTH 进历史深度注入。 */
    val personaPosition: Int = PersonaDescriptionPosition.IN_PROMPT,
    val personaDepth: Int = 0,
    val personaRole: PromptRole = PromptRole.SYSTEM,
    /** 示例对话原文（未切分，已替换好宏）。 */
    val mesExamples: String = "",
    /** 卡深度提示词（charDepthPrompt）与深度/角色。 */
    val charDepthPrompt: String = "",
    val depthPromptDepth: Int = 4,
    val depthPromptRole: PromptRole = PromptRole.SYSTEM,
    /** 历史（最旧在前）。swipe 生成时接入层应已去掉最后一条（上游 `coreChat.pop()`）。 */
    val history: List<HistoryItem> = emptyList(),
    /** 世界书产出（`core/WorldInfoPromptResult`）。 */
    val worldInfo: WorldInfoPromptResult = WorldInfoPromptResult(),
    /** 作者注释配置与文本；null 视为没有作者注释。 */
    val authorsNote: AuthorsNote? = null,
    /** 附加 extension prompts（key 会原样进 `extension_prompts` 键空间，参与排序与去重）。 */
    val extraExtensionPrompts: Map<String, ExtensionPromptInput> = emptyMap(),
    /** quiet 生成的指令文本（进 controlPrompts 最末尾）。 */
    val quietPrompt: String = "",
    /** ST `promptBias`：非空（trim 后）时作为 `bias` 提示词追加。 */
    val bias: String = "",
    /** rikkihub 自有段（记忆/工具说明等），放在 `rikkihubContext` 标记处作为 system 消息；为空不产出。 */
    val rikkihubContext: String = "",
    /** 1B 用：卡 `system_prompt` 覆盖 `main`（条目 forbidOverrides 时不覆盖，原文经 `{{original}}` 传给 substitute）。 */
    val systemPromptOverride: String? = null,
    /** 1B 用：卡 `post_history_instructions` 覆盖 `jailbreak`。 */
    val jailbreakPromptOverride: String? = null,
)
