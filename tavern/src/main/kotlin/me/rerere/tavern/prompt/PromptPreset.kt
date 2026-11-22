package me.rerere.tavern.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** ST:public/scripts/openai.js:206-211 character_names_behavior。 */
enum class NamesBehavior(val code: Int) {
    NONE(-1),
    DEFAULT(0),
    COMPLETION(1),
    CONTENT(2);

    companion object {
        fun fromCode(code: Int?): NamesBehavior? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 酒馆预设里与组装相关的字段（挑自 `default/content/presets/openai/Default.json` 的 62 个字段）加上几个运行时开关。
 *
 * 预设字段（编码进预设 JSON）：[contextSize]=`openai_max_context`、[responseTokens]=`openai_max_tokens`、
 * `names_behavior send_if_empty impersonation_prompt new_chat_prompt new_example_chat_prompt continue_nudge_prompt
 * wi_format scenario_format personality_format assistant_prefill assistant_impersonation squash_system_messages
 * continue_prefill continue_postfix`。
 *
 * 非预设字段（酒馆里分别来自 power_user 和聊天来源，不写进预设）：[pinExamples]、[stripExamples]、[assistantPrefillSupported]。
 * 群聊相关的 `new_group_chat_prompt`、`group_nudge_prompt` 不做，留在预设的 extra 里原样保存。
 */
data class AssemblyOptions(
    val contextSize: Int = 4095,
    val responseTokens: Int = 300,
    val namesBehavior: NamesBehavior = NamesBehavior.DEFAULT,
    val sendIfEmpty: String = "",
    val impersonationPrompt: String = DEFAULT_IMPERSONATION_PROMPT,
    val newChatPrompt: String = DEFAULT_NEW_CHAT_PROMPT,
    val newExampleChatPrompt: String = DEFAULT_NEW_EXAMPLE_CHAT_PROMPT,
    val continueNudgePrompt: String = DEFAULT_CONTINUE_NUDGE_PROMPT,
    val wiFormat: String = "{0}",
    val scenarioFormat: String = "{{scenario}}",
    val personalityFormat: String = "{{personality}}",
    val assistantPrefill: String = "",
    val assistantImpersonation: String = "",
    val squashSystemMessages: Boolean = false,
    val continuePrefill: Boolean = false,
    val continuePostfix: String = " ",
    /** power_user.pin_examples：示例对话先于聊天历史占预算。 */
    val pinExamples: Boolean = false,
    /** power_user.strip_examples：完全不发示例对话。 */
    val stripExamples: Boolean = false,
    /** 对应 `chat_completion_source == claude`：只有它才会产出 [AssemblyResult.assistantPrefill]，continue_prefill 才会把预填前缀并进被续写的消息。 */
    val assistantPrefillSupported: Boolean = false,
) {
    companion object {
        // ST:public/scripts/openai.js:105-115
        const val DEFAULT_IMPERSONATION_PROMPT =
            "[Write your next reply from the point of view of {{user}}, using the chat history so far as a guideline for the writing style of {{user}}. Don't write as {{char}} or system. Don't describe actions of {{char}}.]"
        const val DEFAULT_NEW_CHAT_PROMPT = "[Start a new Chat]"
        const val DEFAULT_NEW_EXAMPLE_CHAT_PROMPT = "[Example Chat]"
        const val DEFAULT_CONTINUE_NUDGE_PROMPT = "[Continue your last message without repeating its original content.]"
    }
}

/** 预设里一条 `prompt_order[].order[]`：提示词引用 + 是否启用。 */
data class PromptOrderEntry(
    val identifier: String,
    val enabled: Boolean? = null,
    val extra: JsonObject = EMPTY_OBJECT,
    val keyOrder: List<String> = emptyList(),
) {
    fun toJson(): JsonObject {
        val typed = LinkedHashMap<String, JsonElement?>()
        typed["identifier"] = JsonPrimitive(identifier)
        typed["enabled"] = enabled?.let { JsonPrimitive(it) }
        return encodeObject(keyOrder, typed, extra)
    }

    companion object {
        fun fromJson(obj: JsonObject): PromptOrderEntry {
            val r = JsonFieldReader(obj)
            return PromptOrderEntry(
                identifier = r.string("identifier") ?: "",
                enabled = r.bool("enabled"),
                extra = r.rest(),
                keyOrder = r.keys,
            )
        }
    }
}

/**
 * 预设里的 `prompt_order[]` 元素：`character_id` 对应一份顺序。
 * [characterId] 原样保存（上游既有数字也可能有字符串），比较时按字符串。
 */
data class PromptOrderList(
    val characterId: JsonElement,
    val order: List<PromptOrderEntry>,
    val extra: JsonObject = EMPTY_OBJECT,
    val keyOrder: List<String> = emptyList(),
) {
    val characterIdString: String get() = (characterId as? JsonPrimitive)?.contentOrNull ?: characterId.toString()

    fun toJson(): JsonObject {
        val typed = LinkedHashMap<String, JsonElement?>()
        typed["character_id"] = characterId
        typed["order"] = JsonArray(order.map { it.toJson() })
        return encodeObject(keyOrder, typed, extra)
    }

    companion object {
        fun fromJson(obj: JsonObject): PromptOrderList {
            val r = JsonFieldReader(obj)
            val id = obj["character_id"] ?: JsonPrimitive("")
            val orderArr = obj["order"] as? JsonArray
            val order = orderArr?.mapNotNull { (it as? JsonObject)?.let(PromptOrderEntry::fromJson) } ?: emptyList()
            // character_id 与 order 当作已消费；order 里若有非对象元素则整体按未知字段保留（极少见）
            val lossless = orderArr != null && orderArr.all { it is JsonObject }
            val rest = LinkedHashMap<String, JsonElement>()
            for ((k, v) in obj) {
                if (k == "character_id") continue
                if (k == "order" && lossless) continue
                rest[k] = v
            }
            return PromptOrderList(id, if (lossless) order else emptyList(), JsonObject(rest), r.keys)
        }
    }
}

/**
 * 酒馆 OpenAI 聊天补全预设（`default/content/presets/openai/` 下的 .json 文件）的无损模型。
 *
 * - [prompts] 与 [promptOrder]：提示词管理器的数据。运行时实际使用的顺序是 `character_id` 等于
 *   [GLOBAL_ORDER_ID]（100001，见 ST:public/scripts/openai.js:698 的 dummyId）的那份；
 *   缺失或为空时酒馆会补上默认顺序（ST:public/scripts/PromptManager.js:1014），[layout] 照做。
 *   注意工作包里写的 100000 是 PromptManager 类的默认 dummyId，被 openai.js 的配置覆盖，不是实际用的那份。
 * - [options]：组装相关字段（见 [AssemblyOptions]）。
 * - [extra]：其余字段（采样参数、各家模型名、代理……）原样保留。
 */
data class PromptPreset(
    val prompts: List<PromptEntry>,
    val promptOrder: List<PromptOrderList>,
    val options: AssemblyOptions = AssemblyOptions(),
    val extra: JsonObject = EMPTY_OBJECT,
    val keyOrder: List<String> = emptyList(),
) {
    /**
     * 解析出当前生效的布局。
     * ST:public/scripts/PromptManager.js:1003-1030 `sanitizeServiceSettings` 与 :2087 默认顺序；缺失的默认提示词会补上（`checkForMissingPrompts`）；
     * 顺序里引用了不存在的提示词的条目丢弃。
     */
    fun layout(orderId: Int = GLOBAL_ORDER_ID): PromptLayout {
        // 偏离：这里不补默认提示词。上游 checkForMissingPrompts 只在预设加载（sanitizeServiceSettings）时跑，
        // getPromptCollection 直接用 serviceSettings.prompts——order 引用不存在的 identifier 就跳过。
        // 要"加载即补全"的调用方先用 defaultPrompts 补齐 prompts。
        val byId = LinkedHashMap<String, PromptEntry>()
        for (p in prompts) byId.putIfAbsent(p.identifier, p)
        val list = promptOrder.firstOrNull { it.characterIdString == orderId.toString() }?.order?.takeIf { it.isNotEmpty() }
            ?: defaultOrder
        val entries = list.mapNotNull { o ->
            val p = byId[o.identifier] ?: return@mapNotNull null
            // 上游 `entry.enabled` 为 falsy 即禁用（缺省视为禁用）
            p.copy(enabled = o.enabled == true)
        }
        return PromptLayout(entries)
    }

    fun toJson(): JsonObject {
        val typed = LinkedHashMap<String, JsonElement?>()
        // 解码时类型对不上的 prompts / prompt_order 整个留在 extra 里（fromJson），这里不能再用空数组盖掉它
        typed["prompts"] = if (extra.containsKey("prompts")) null else JsonArray(prompts.map { it.toJson() })
        typed["prompt_order"] = if (extra.containsKey("prompt_order")) null else JsonArray(promptOrder.map { it.toJson() })
        optionsToTyped(options, keyOrder, extra, typed)
        return encodeObject(keyOrder, typed, extra)
    }

    fun encodeToString(pretty: Boolean = true): String =
        (if (pretty) prettyJson else Json).encodeToString(JsonObject.serializer(), toJson())

    companion object {
        const val GLOBAL_ORDER_ID = 100001
        private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "    " }

        fun decode(text: String): PromptPreset = fromJson(Json.parseToJsonElement(text).jsonObject)

        fun fromJson(obj: JsonObject): PromptPreset {
            val promptsArr = obj["prompts"] as? JsonArray
            val orderArr = obj["prompt_order"] as? JsonArray
            // 非对象元素：整个数组按未知字段保留，避免丢数据
            val promptsOk = promptsArr != null && promptsArr.all { it is JsonObject }
            val orderOk = orderArr != null && orderArr.all { it is JsonObject }
            val consumed = HashSet<String>()
            if (promptsOk) consumed += "prompts"
            if (orderOk) consumed += "prompt_order"
            val (options, optionKeys) = optionsFromJson(obj)
            consumed += optionKeys
            val rest = JsonObject(obj.filterKeys { it !in consumed })
            return PromptPreset(
                prompts = if (promptsOk) promptsArr!!.map { PromptEntry.fromJson(it as JsonObject) } else emptyList(),
                promptOrder = if (orderOk) orderArr!!.map { PromptOrderList.fromJson(it as JsonObject) } else emptyList(),
                options = options,
                extra = rest,
                keyOrder = obj.keys.toList(),
            )
        }

        // ---- 组装选项 <-> 预设键 ----

        private fun optionsFromJson(obj: JsonObject): Pair<AssemblyOptions, Set<String>> {
            val r = JsonFieldReader(obj)
            var o = AssemblyOptions()
            r.int("openai_max_context")?.let { o = o.copy(contextSize = it) }
            r.int("openai_max_tokens")?.let { o = o.copy(responseTokens = it) }
            r.typed("names_behavior") { e -> NamesBehavior.fromCode((e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull) }
                ?.let { o = o.copy(namesBehavior = it) }
            r.string("send_if_empty")?.let { o = o.copy(sendIfEmpty = it) }
            r.string("impersonation_prompt")?.let { o = o.copy(impersonationPrompt = it) }
            r.string("new_chat_prompt")?.let { o = o.copy(newChatPrompt = it) }
            r.string("new_example_chat_prompt")?.let { o = o.copy(newExampleChatPrompt = it) }
            r.string("continue_nudge_prompt")?.let { o = o.copy(continueNudgePrompt = it) }
            r.string("wi_format")?.let { o = o.copy(wiFormat = it) }
            r.string("scenario_format")?.let { o = o.copy(scenarioFormat = it) }
            r.string("personality_format")?.let { o = o.copy(personalityFormat = it) }
            r.string("assistant_prefill")?.let { o = o.copy(assistantPrefill = it) }
            r.string("assistant_impersonation")?.let { o = o.copy(assistantImpersonation = it) }
            r.bool("squash_system_messages")?.let { o = o.copy(squashSystemMessages = it) }
            r.bool("continue_prefill")?.let { o = o.copy(continuePrefill = it) }
            r.string("continue_postfix")?.let { o = o.copy(continuePostfix = it) }
            val rest = r.rest()
            val consumed = obj.keys.filter { it !in rest.keys }.toSet()
            // prompts / prompt_order 不在这里消费
            return o to consumed
        }

        /**
         * 只输出“解码时就有（且类型正确）”或“与默认值不同”的选项键，这样缺字段的旧预设往返后不会凭空多出字段，
         * 类型对不上的字段（留在 extra 里）也不会被默认值覆盖。
         */
        private fun optionsToTyped(
            o: AssemblyOptions,
            keyOrder: List<String>,
            extra: JsonObject,
            typed: LinkedHashMap<String, JsonElement?>,
        ) {
            val d = AssemblyOptions()
            fun put(key: String, value: Any, default: Any, json: JsonElement) {
                val wasPresent = key in keyOrder && key !in extra
                if (wasPresent || value != default) typed[key] = json
            }
            put("openai_max_context", o.contextSize, d.contextSize, JsonPrimitive(o.contextSize))
            put("openai_max_tokens", o.responseTokens, d.responseTokens, JsonPrimitive(o.responseTokens))
            put("names_behavior", o.namesBehavior, d.namesBehavior, JsonPrimitive(o.namesBehavior.code))
            put("send_if_empty", o.sendIfEmpty, d.sendIfEmpty, JsonPrimitive(o.sendIfEmpty))
            put("impersonation_prompt", o.impersonationPrompt, d.impersonationPrompt, JsonPrimitive(o.impersonationPrompt))
            put("new_chat_prompt", o.newChatPrompt, d.newChatPrompt, JsonPrimitive(o.newChatPrompt))
            put("new_example_chat_prompt", o.newExampleChatPrompt, d.newExampleChatPrompt, JsonPrimitive(o.newExampleChatPrompt))
            put("continue_nudge_prompt", o.continueNudgePrompt, d.continueNudgePrompt, JsonPrimitive(o.continueNudgePrompt))
            put("wi_format", o.wiFormat, d.wiFormat, JsonPrimitive(o.wiFormat))
            put("scenario_format", o.scenarioFormat, d.scenarioFormat, JsonPrimitive(o.scenarioFormat))
            put("personality_format", o.personalityFormat, d.personalityFormat, JsonPrimitive(o.personalityFormat))
            put("assistant_prefill", o.assistantPrefill, d.assistantPrefill, JsonPrimitive(o.assistantPrefill))
            put("assistant_impersonation", o.assistantImpersonation, d.assistantImpersonation, JsonPrimitive(o.assistantImpersonation))
            put("squash_system_messages", o.squashSystemMessages, d.squashSystemMessages, JsonPrimitive(o.squashSystemMessages))
            put("continue_prefill", o.continuePrefill, d.continuePrefill, JsonPrimitive(o.continuePrefill))
            put("continue_postfix", o.continuePostfix, d.continuePostfix, JsonPrimitive(o.continuePostfix))
        }

        // ---- 默认提示词与默认顺序（ST:public/scripts/PromptManager.js:2000-2130） ----

        private fun marker(id: String, name: String) =
            PromptEntry(identifier = id, name = name, systemPrompt = true, marker = true)

        val defaultPrompts: List<PromptEntry> = listOf(
            PromptEntry(
                identifier = "main", name = "Main Prompt", systemPrompt = true, role = me.rerere.tavern.core.PromptRole.SYSTEM,
                content = "Write {{char}}'s next reply in a fictional chat between {{charIfNotGroup}} and {{user}}.",
            ),
            PromptEntry(identifier = "nsfw", name = "Auxiliary Prompt", systemPrompt = true, role = me.rerere.tavern.core.PromptRole.SYSTEM, content = ""),
            marker("dialogueExamples", "Chat Examples"),
            PromptEntry(identifier = "jailbreak", name = "Post-History Instructions", systemPrompt = true, role = me.rerere.tavern.core.PromptRole.SYSTEM, content = ""),
            marker("chatHistory", "Chat History"),
            marker("worldInfoAfter", "World Info (after)"),
            marker("worldInfoBefore", "World Info (before)"),
            PromptEntry(
                identifier = "enhanceDefinitions", name = "Enhance Definitions", role = me.rerere.tavern.core.PromptRole.SYSTEM,
                content = "If you have more knowledge of {{char}}, add to the character's lore and personality to enhance them but keep the Character Sheet's definitions absolute.",
                systemPrompt = true, marker = false,
            ),
            marker("charDescription", "Char Description"),
            marker("charPersonality", "Char Personality"),
            marker("scenario", "Scenario"),
            marker("personaDescription", "Persona Description"),
        )

        val defaultOrder: List<PromptOrderEntry> = listOf(
            "main" to true, "worldInfoBefore" to true, "personaDescription" to true, "charDescription" to true,
            "charPersonality" to true, "scenario" to true, "enhanceDefinitions" to false, "nsfw" to true,
            "worldInfoAfter" to true, "dialogueExamples" to true, "chatHistory" to true, "jailbreak" to true,
        ).map { (id, on) -> PromptOrderEntry(id, on) }
    }
}
