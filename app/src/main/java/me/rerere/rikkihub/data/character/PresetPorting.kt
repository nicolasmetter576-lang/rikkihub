package me.rerere.rikkihub.data.character

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.tavern.prompt.PromptMarkers
import me.rerere.tavern.prompt.PromptOrderEntry
import me.rerere.tavern.prompt.PromptOrderList
import me.rerere.tavern.prompt.PromptPreset

class PresetFormatException(message: String) : Exception(message)

/** 导入报告：导入了什么、什么没导入。 */
data class PresetImportReport(
    val promptCount: Int,
    /** 作为助手正则导入、且先停用的脚本数（用户看过再开）。 */
    val regexScriptsDisabled: Int,
    /** 导入成助手类型化字段的采样参数键。 */
    val samplingImported: List<String>,
    /** 连接类字段（接口地址、密钥、模型名、`n`、`logit_bias` ...），不导入。 */
    val connectionKeysSkipped: List<String>,
)

class PresetImport(val assistant: Assistant, val report: PresetImportReport)

/**
 * 酒馆（OpenAI / Chat Completion）预设与助手的互转。预设 JSON 本身就是助手的提示词（`promptPreset`），
 * 所以导入基本是“收下来”：补 rikkihub 的上下文标记和角色卡标记，采样参数提到助手字段，连接类字段丢掉并写进报告，
 * 预设自带的正则脚本先停用。导出把这些放回预设 JSON，未知字段原样带出。
 */
object PresetPorting {
    private const val MODEL_MAX_OUTPUT_TOKENS = 32_768

    private val SAMPLING_KEYS = listOf(
        "temperature", "top_p", "top_k", "frequency_penalty", "presence_penalty", "repetition_penalty", "min_p", "top_a", "seed",
        "openai_max_tokens", "stream_openai",
    )

    private val CONNECTION_EXACT = setOf(
        "chat_completion_source", "reverse_proxy", "proxy_password", "custom_url", "api_url_scale", "n", "logit_bias",
        "bypass_status_check", "show_external_models",
    )

    private fun isConnectionKey(key: String): Boolean {
        val k = key.lowercase()
        return k in CONNECTION_EXACT || k.endsWith("_model") || k.endsWith("_url") || k.contains("api_key") || k.contains("password") ||
            k.contains("secret") || k.startsWith("custom_include") || k.startsWith("custom_exclude")
    }

    fun isPreset(json: JsonObject): Boolean = json["prompts"] is JsonArray && json["prompt_order"] is JsonArray

    fun import(json: JsonObject, fileName: String): PresetImport {
        if (!isPreset(json)) throw PresetFormatException("This file is not a SillyTavern chat completion preset (it needs prompts and prompt_order)")
        val skipped = json.keys.filter(::isConnectionKey)
        val kept = JsonObject(json.filterKeys { it !in skipped })
        val decoded = PromptPreset.fromJson(kept)
        if (decoded.prompts.isEmpty()) throw PresetFormatException("The preset has no prompts")

        var preset = ensureGlobalOrder(decoded)
        preset = AssistantPresets.withCardMarkers(AssistantPresets.withContextMarker(preset))
        val scripts = preset.regexScripts
        if (scripts.isNotEmpty()) preset = preset.withRegexScripts(scripts.map { it.copy(disabled = true) })

        fun number(key: String): Double? = (json[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val imported = ArrayList<String>()
        fun f(key: String, active: (Double) -> Boolean): Float? = number(key)?.takeIf(active)?.also { imported += key }?.toFloat()
        val temperature = f("temperature") { true }
        val topP = f("top_p") { true }
        val topK = number("top_k")?.takeIf { it >= 1 }?.also { imported += "top_k" }?.toInt()
        val frequency = f("frequency_penalty") { it != 0.0 }
        val presence = f("presence_penalty") { it != 0.0 }
        val repetition = f("repetition_penalty") { it != 1.0 && it > 0 }
        val minP = f("min_p") { it > 0.0 }
        val topA = f("top_a") { it > 0.0 }
        val seed = number("seed")?.takeIf { it >= 0 }?.also { imported += "seed" }?.toLong()
        // 填满的输出上限（≥32768）在酒馆里是「用模型的最大输出」的写法，照搬会让上限更小的模型（Claude 64k 等）报 400：
        // 留空跟随模型。预设里的原值还在（openai_max_tokens 随预设保存），导出时原样写回
        val maxTokens = number("openai_max_tokens")?.takeIf { it >= 1 && it < MODEL_MAX_OUTPUT_TOKENS }
            ?.also { imported += "openai_max_tokens" }?.toInt()
        val stream = (json["stream_openai"] as? JsonPrimitive)?.booleanOrNull?.also { imported += "stream_openai" }

        val name = ((json["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.').trim().ifEmpty { "Imported preset" })
        val assistant = Assistant(
            name = name,
            promptPreset = preset,
            temperature = temperature, topP = topP, maxTokens = maxTokens,
            topK = topK, frequencyPenalty = frequency, presencePenalty = presence, repetitionPenalty = repetition,
            minP = minP, topA = topA, seed = seed,
            streamOutput = stream ?: true,
            // 酒馆预设本来就是给完整组装用的
            tavernMode = true,
        )
        return PresetImport(
            assistant,
            PresetImportReport(
                promptCount = preset.prompts.size,
                regexScriptsDisabled = scripts.size,
                samplingImported = imported,
                connectionKeysSkipped = skipped,
            ),
        )
    }

    /** 全局顺序在 `character_id` 100001 那一份：没有就拿 100000（老版本预设），再没有就拿第一份，复制成 100001。 */
    private fun ensureGlobalOrder(preset: PromptPreset): PromptPreset {
        val globalId = PromptPreset.GLOBAL_ORDER_ID.toString()
        if (preset.promptOrder.any { it.characterIdString == globalId && it.order.isNotEmpty() }) return preset
        val source = preset.promptOrder.firstOrNull { it.characterIdString == "100000" && it.order.isNotEmpty() }
            ?: preset.promptOrder.firstOrNull { it.order.isNotEmpty() }
            ?: return preset.copy(promptOrder = preset.promptOrder.filter { it.characterIdString != globalId } + PromptOrderList(
                JsonPrimitive(PromptPreset.GLOBAL_ORDER_ID), PromptPreset.defaultOrder,
            ))
        val copy = PromptOrderList(JsonPrimitive(PromptPreset.GLOBAL_ORDER_ID), source.order.map { PromptOrderEntry(it.identifier, it.enabled, it.extra, it.keyOrder) })
        return preset.copy(promptOrder = preset.promptOrder.filter { it.characterIdString != globalId } + copy)
    }

    /** 助手 → 酒馆预设 JSON：去掉 rikkihub 自己的上下文标记，采样参数放回预设键，其余原样。 */
    fun export(assistant: Assistant): JsonObject {
        val preset = assistant.promptPreset
        val stripped = preset.copy(
            prompts = preset.prompts.filter { it.identifier != PromptMarkers.RIKKIHUB_CONTEXT },
            promptOrder = preset.promptOrder.map { list -> list.copy(order = list.order.filter { it.identifier != PromptMarkers.RIKKIHUB_CONTEXT }) },
        )
        val out = LinkedHashMap<String, JsonElement>(stripped.toJson())
        fun put(key: String, value: Number?) { if (value != null) out[key] = JsonPrimitive(value) }
        put("temperature", assistant.temperature)
        put("top_p", assistant.topP)
        put("top_k", assistant.topK)
        put("frequency_penalty", assistant.frequencyPenalty)
        put("presence_penalty", assistant.presencePenalty)
        put("repetition_penalty", assistant.repetitionPenalty)
        put("min_p", assistant.minP)
        put("top_a", assistant.topA)
        put("seed", assistant.seed)
        if (assistant.maxTokens != null) out["openai_max_tokens"] = JsonPrimitive(assistant.maxTokens)
        out["stream_openai"] = JsonPrimitive(assistant.streamOutput)
        return JsonObject(out)
    }
}
