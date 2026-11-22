package me.rerere.tavern.prompt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.tavern.core.PromptRole

/** ST:public/scripts/PromptManager.js:36-39 INJECTION_POSITION。 */
object InjectionPosition {
    const val RELATIVE = 0
    const val ABSOLUTE = 1
}

/** ST:public/scripts/PromptManager.js:30-31。 */
const val DEFAULT_INJECTION_DEPTH = 4
const val DEFAULT_INJECTION_ORDER = 100

/**
 * 提示词标记（identifier）。前 12 个是酒馆自带的，[RIKKIHUB_CONTEXT] 是 rikkihub 自有的：
 * 接入层给一段文本，放在布局里这个标记所在的位置，作为 system 消息；文本为空则不产出。
 */
object PromptMarkers {
    const val MAIN = "main"
    const val NSFW = "nsfw"
    const val JAILBREAK = "jailbreak"
    const val ENHANCE_DEFINITIONS = "enhanceDefinitions"
    const val CHAR_DESCRIPTION = "charDescription"
    const val CHAR_PERSONALITY = "charPersonality"
    const val SCENARIO = "scenario"
    const val PERSONA_DESCRIPTION = "personaDescription"
    const val DIALOGUE_EXAMPLES = "dialogueExamples"
    const val CHAT_HISTORY = "chatHistory"
    const val WORLD_INFO_BEFORE = "worldInfoBefore"
    const val WORLD_INFO_AFTER = "worldInfoAfter"
    const val RIKKIHUB_CONTEXT = "rikkihubContext"
}

/**
 * 酒馆预设里的一条提示词（ST:public/scripts/PromptManager.js:80-195 的 Prompt 与预设 JSON 的 `prompts[]` 元素）。
 *
 * - 字段名与上游一致（snake_case 在 JSON 里，这里用驼峰属性）；类型对不上或未知的键放进 [extra]，导入导出无损。
 * - [enabled] 不属于预设 JSON 里的提示词对象：在上游它存在于 `prompt_order[].order[]` 里，
 *   这里把“布局条目 = 提示词 + 是否启用”合成一条，由 [PromptPreset.layout] 填入；序列化时不输出。
 *   （若旧预设的 prompts 里自带 `enabled` 字段，它按未知键进 [extra] 原样保留。）
 * - [keyOrder] 记录解码时的键顺序，编码时按原顺序输出。
 */
data class PromptEntry(
    val identifier: String,
    val name: String? = null,
    val role: PromptRole? = null,
    val content: String? = null,
    val systemPrompt: Boolean? = null,
    val marker: Boolean? = null,
    val injectionPosition: Int? = null,
    val injectionDepth: Int? = null,
    val injectionOrder: Int? = null,
    val injectionTrigger: List<String>? = null,
    val forbidOverrides: Boolean? = null,
    val enabled: Boolean = true,
    val extra: JsonObject = EMPTY_OBJECT,
    val keyOrder: List<String> = emptyList(),
) {
    fun toJson(): JsonObject {
        val typed = LinkedHashMap<String, JsonElement?>()
        typed["identifier"] = JsonPrimitive(identifier)
        typed["name"] = name?.let { JsonPrimitive(it) }
        typed["role"] = role?.let { JsonPrimitive(it.wire) }
        typed["content"] = content?.let { JsonPrimitive(it) }
        typed["system_prompt"] = systemPrompt?.let { JsonPrimitive(it) }
        typed["marker"] = marker?.let { JsonPrimitive(it) }
        typed["injection_position"] = injectionPosition?.let { JsonPrimitive(it) }
        typed["injection_depth"] = injectionDepth?.let { JsonPrimitive(it) }
        typed["injection_order"] = injectionOrder?.let { JsonPrimitive(it) }
        typed["injection_trigger"] = injectionTrigger?.let { list -> JsonArray(list.map { JsonPrimitive(it) }) }
        typed["forbid_overrides"] = forbidOverrides?.let { JsonPrimitive(it) }
        return encodeObject(keyOrder, typed, extra)
    }

    companion object {
        fun fromJson(obj: JsonObject, enabled: Boolean = true): PromptEntry {
            val r = JsonFieldReader(obj)
            val identifier = r.string("identifier") ?: ""
            return PromptEntry(
                identifier = identifier,
                name = r.string("name"),
                role = r.typed("role") { e ->
                    val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                    PromptRole.entries.firstOrNull { it.wire == s }
                },
                content = r.string("content"),
                systemPrompt = r.bool("system_prompt"),
                marker = r.bool("marker"),
                injectionPosition = r.int("injection_position"),
                injectionDepth = r.int("injection_depth"),
                injectionOrder = r.int("injection_order"),
                injectionTrigger = r.typed("injection_trigger") { e ->
                    val arr = e as? JsonArray ?: return@typed null
                    val strings = arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
                    if (strings.any { it == null }) null else strings.filterNotNull()
                },
                forbidOverrides = r.bool("forbid_overrides"),
                enabled = enabled,
                extra = r.rest(),
                keyOrder = r.keys,
            )
        }
    }
}

/**
 * 有序提示词布局：列表顺序就是发送顺序，每条自带 [PromptEntry.enabled]。
 * 对应上游“当前角色的 prompt_order 解析到 prompts 后的结果”。
 */
data class PromptLayout(val entries: List<PromptEntry>) {
    fun has(identifier: String): Boolean = entries.any { it.identifier == identifier }
}
