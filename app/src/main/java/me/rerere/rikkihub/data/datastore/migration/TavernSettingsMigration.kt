package me.rerere.rikkihub.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.inferTavernMode
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.macro.LegacyPlaceholderMigration
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptMarkers
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoPosition
import me.rerere.tavern.worldinfo.WorldInfoSettings

/**
 * 设置结构迁移 V5（2.33.0）：旧的助手提示词/模式注入/世界书/助手正则 → 酒馆格式。
 * 全是 JSON 到 JSON 的纯函数，DataStore 迁移（逐键）与备份恢复（[SettingsJsonMigrator]，整份 settings.json）共用。
 *
 * 目标形状（均为酒馆原格式，可以无损导出）：
 * - 助手：`promptPreset`（酒馆 OpenAI 预设 JSON：提示词 + 全局顺序 + 组装选项 + `extensions.regex_scripts`），
 *   `libraryEntryIds`（原 `modeInjectionIds`）；去掉 `systemPrompt`、`systemPromptMode`、`promptSections`、`regexes`、`modeInjectionIds`。
 * - 提示词库：原模式注入，每条是一个 `PromptEntry`；rikkihub 自有信息放在 `extra.rikkihub`（`enabled`、`anchor`）。
 * - 世界书：条目换成酒馆的 `WorldInfoEntry`（`WorldInfoCodec` 的格式）。
 *
 * 默认布局保证请求与 2.32.0 相同（基准见 `LegacyRequestBaseline`）；有意的差异写在迁移报告里。
 */
internal object TavernSettingsMigration {
    /** 提示词库条目里 rikkihub 自有信息的键与锚点，定义在 [LibraryEntryMeta]。 */
    const val EXTRA_KEY = LibraryEntryMeta.EXTRA_KEY

    object Anchor {
        const val BEFORE_SYSTEM = LibraryEntryMeta.BEFORE_SYSTEM
        const val AFTER_SYSTEM = LibraryEntryMeta.AFTER_SYSTEM
        const val BEFORE_HISTORY = LibraryEntryMeta.BEFORE_HISTORY
    }

    /** 已保存提示词里 rikkihub 自有的 `{key}` 占位符一次性改成 `{{key}}`；之后宏引擎只认双花括号。 */
    private fun placeholders(text: String): String = LegacyPlaceholderMigration.migrate(text)

    // ------------------------------------------------------------------ 助手

    /** `assistants` 数组里的每个助手：生成 `promptPreset`/`libraryEntryIds`，去掉旧键。已迁移过的原样保留。 */
    fun migrateAssistants(assistants: JsonArray): JsonArray = JsonArray(assistants.map { element ->
        (element as? JsonObject)?.let(::migrateAssistant) ?: element
    })

    /** V6：每个助手的预设补上角色卡与用户设定的标记（已有的不动，没有预设的助手原样保留）。 */
    fun addCardMarkers(assistants: JsonArray): JsonArray = JsonArray(assistants.map { element ->
        val assistant = element as? JsonObject ?: return@map element
        val presetObject = assistant["promptPreset"] as? JsonObject ?: return@map element
        val updated = runCatching { AssistantPresets.withCardMarkers(PromptPreset.fromJson(presetObject)) }.getOrNull() ?: return@map element
        JsonObject(assistant + ("promptPreset" to updated.toJson()))
    })

    /**
     * V7（2.35.0）：给已有助手加上 `tavernMode`（已有这个键的不动），规则见 [inferTavernMode]。
     * 解不开的助手按开处理（保持升级前的完整组装）。
     *
     * @param tavern 全局酒馆设置（`personaDescription`、`authorsNote.prompt`），没有就当空
     * @param tavernAssistantIds 有会话和角色聊或写了作者注释的助手 id（从数据库查，查不到就空）
     */
    fun addTavernMode(assistants: JsonArray, tavern: JsonObject?, tavernAssistantIds: Set<String>): JsonArray {
        val persona = (tavern?.get("personaDescription") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val note = ((tavern?.get("authorsNote") as? JsonObject)?.get("prompt") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return JsonArray(assistants.map { element ->
            val obj = element as? JsonObject ?: return@map element
            if ("tavernMode" in obj) return@map element
            val on = runCatching {
                val assistant = JsonInstant.decodeFromJsonElement(Assistant.serializer(), obj)
                assistant.inferTavernMode(persona, note, assistant.id.toString() in tavernAssistantIds)
            }.getOrDefault(true)
            JsonObject(obj + ("tavernMode" to JsonPrimitive(on)))
        })
    }

    fun migrateAssistant(assistant: JsonObject): JsonObject {
        if ("promptPreset" in assistant) return assistant
        val preset = legacyAssistantPreset(assistant)
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in assistant) {
            when (key) {
                "systemPrompt", "systemPromptMode", "promptSections", "regexes", "modeInjectionIds" -> {}
                else -> out[key] = value
            }
        }
        out["promptPreset"] = preset.toJson()
        out["libraryEntryIds"] = assistant["modeInjectionIds"] as? JsonArray ?: JsonArray(emptyList())
        return JsonObject(out)
    }

    /** 旧助手的提示词与助手正则 → 一份酒馆预设。 */
    fun legacyAssistantPreset(assistant: JsonObject): PromptPreset {
        val systemPrompt = (assistant["systemPrompt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val sections = (assistant["promptSections"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        // 提示词文本条目：有分块时每块一个条目（顺序、开关、标题都保留），否则整块提示词是 main
        val textEntries: List<PromptEntry> = if (sections.isNotEmpty()) {
            sections.mapIndexed { index, section ->
                PromptEntry(
                    identifier = (section["id"] as? JsonPrimitive)?.contentOrNull ?: "section-$index",
                    name = (section["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    role = PromptRole.SYSTEM,
                    content = placeholders((section["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
                    // 酒馆里自定义条目的 system_prompt 是 false（true 是内置的 main/jailbreak 等，组装器只认它们的标识符）
                    systemPrompt = false,
                    marker = false,
                    enabled = (section["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                )
            }
        } else {
            listOf(
                PromptEntry(
                    identifier = PromptMarkers.MAIN,
                    name = "Main Prompt",
                    role = PromptRole.SYSTEM,
                    content = placeholders(systemPrompt),
                    systemPrompt = true,
                    marker = false,
                )
            )
        }

        val regexScripts = (assistant["regexes"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::legacyRegexToScript) }.orEmpty()
        return AssistantPresets.create(textEntries, regexScripts)
    }

    /**
     * 旧助手正则（`AssistantRegex`）→ 酒馆正则脚本。
     * 作用对象 USER → 用户输入；ASSISTANT → AI 输出 + 推理（旧版两处都会作用）；`visualOnly` → `markdownOnly`。
     * 旧查找串是裸的 Kotlin 正则，写成 `/模式/g`；替换串里的 `${名字}` 换成 JS 的 `$<名字>`。
     */
    fun legacyRegexToScript(regex: JsonObject): RegexScript {
        val scopes = (regex["affectingScope"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val placement = buildList {
            if (scopes.any { it.equals("user", ignoreCase = true) }) add(RegexPlacement.USER_INPUT)
            if (scopes.any { it.equals("assistant", ignoreCase = true) }) { add(RegexPlacement.AI_OUTPUT); add(RegexPlacement.REASONING) }
        }
        val find = (regex["findRegex"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val replace = (regex["replaceString"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return RegexScript(
            id = (regex["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            scriptName = (regex["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            findRegex = "/$find/g",
            replaceString = replace.replace(Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}"), "\\$<$1>"),
            placement = placement,
            disabled = (regex["enabled"] as? JsonPrimitive)?.booleanOrNull == false,
            markdownOnly = (regex["visualOnly"] as? JsonPrimitive)?.booleanOrNull == true,
            // 旧版编辑已发送的用户消息时会再跑一遍用户正则；酒馆只跑标了「编辑时运行」的脚本
            runOnEdit = RegexPlacement.USER_INPUT in placement,
        )
    }

    // ------------------------------------------------------------------ 提示词库（原模式注入）

    /** 模式注入数组 → 提示词库条目数组（`id` 当作 identifier，助手/会话里已有的引用不用改）。 */
    fun migrateModeInjections(injections: JsonArray): JsonArray = JsonArray(injections.mapNotNull { element ->
        (element as? JsonObject)?.let(::modeInjectionToLibraryEntry)?.toJson()
    })

    fun modeInjectionToLibraryEntry(injection: JsonObject): PromptEntry {
        fun text(key: String) = (injection[key] as? JsonPrimitive)?.contentOrNull
        val position = text("position")
        val role = if (text("role").equals("assistant", ignoreCase = true)) PromptRole.ASSISTANT else PromptRole.USER
        val priority = (injection["priority"] as? JsonPrimitive)?.intOrNull ?: 0
        val depth = (injection["injectDepth"] as? JsonPrimitive)?.intOrNull ?: 4
        val enabled = (injection["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

        // 系统提示词之前/之后的内容旧版是拼进系统消息文字的，角色不起作用 → 系统角色，被合并进系统消息
        val (promptRole, absolute, inChatDepth, anchor) = when (position) {
            "before_system_prompt" -> Placement(PromptRole.SYSTEM, false, null, Anchor.BEFORE_SYSTEM)
            "top_of_chat" -> Placement(role, false, null, Anchor.BEFORE_HISTORY)
            "bottom_of_chat" -> Placement(role, true, 1, null)
            "at_depth" -> Placement(role, true, depth, null)
            else -> Placement(PromptRole.SYSTEM, false, null, Anchor.AFTER_SYSTEM) // after_system_prompt 及未知值
        }
        return PromptEntry(
            identifier = text("id").orEmpty(),
            name = text("name").orEmpty(),
            role = promptRole,
            content = placeholders(text("content").orEmpty()),
            systemPrompt = false,
            marker = false,
            injectionPosition = if (absolute) 1 else 0,
            injectionDepth = inChatDepth,
            injectionOrder = priority,
            extra = buildJsonObject {
                put(EXTRA_KEY, buildJsonObject {
                    put("enabled", enabled)
                    if (anchor != null) put("anchor", anchor)
                })
            },
        )
    }

    private data class Placement(val role: PromptRole, val absolute: Boolean, val depth: Int?, val anchor: String?)

    // ------------------------------------------------------------------ 世界书

    /** 世界书数组：每本书的条目换成酒馆条目；书本身保留 `id/name/description/enabled`。 */
    fun migrateLorebooks(lorebooks: JsonArray): JsonArray = JsonArray(lorebooks.map { element ->
        (element as? JsonObject)?.let(::migrateLorebook) ?: element
    })

    fun migrateLorebook(book: JsonObject): JsonObject {
        val entries = book["entries"] as? JsonArray ?: return book
        // 已经是酒馆条目（有 uid 与 key）的不再转换
        if (entries.firstOrNull().let { it is JsonObject && "uid" in it && "key" in it }) return book
        val converted = entries.mapIndexedNotNull { index, entry ->
            (entry as? JsonObject)?.let { WorldInfoCodec.encodeEntry(regexInjectionToEntry(it, index)) }
        }
        return JsonObject(book + ("entries" to JsonArray(converted)))
    }

    /**
     * 旧世界书条目（`RegexInjection`）→ 酒馆条目。
     * 关键词：正则关键词写成 `/模式/i`（区分大小写时 `/模式/`）；`priority` → `order`；`name` → `comment`；
     * 位置：系统前 → 0，系统后/对话开头 → 1，对话末尾 → 在深度 1 注入，深度 → 在该深度注入（角色保留）。
     */
    fun regexInjectionToEntry(injection: JsonObject, uid: Int): WorldInfoEntry {
        fun text(key: String) = (injection[key] as? JsonPrimitive)?.contentOrNull
        fun bool(key: String, default: Boolean) = (injection[key] as? JsonPrimitive)?.booleanOrNull ?: default
        val caseSensitive = bool("caseSensitive", false)
        val useRegex = bool("useRegex", false)
        val keywords = (injection["keywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val keys = keywords.filter { it.isNotEmpty() }.map { if (useRegex) "/$it/${if (caseSensitive) "" else "i"}" else it }
        val role = if (text("role").equals("assistant", ignoreCase = true)) 2 else 1
        val depth = (injection["injectDepth"] as? JsonPrimitive)?.intOrNull ?: 4
        val (position, entryDepth, entryRole) = when (text("position")) {
            "before_system_prompt" -> Triple(WorldInfoPosition.BEFORE, depth, 0)
            "top_of_chat" -> Triple(WorldInfoPosition.AFTER, depth, 0)
            "bottom_of_chat" -> Triple(WorldInfoPosition.AT_DEPTH, 1, role)
            "at_depth" -> Triple(WorldInfoPosition.AT_DEPTH, depth, role)
            else -> Triple(WorldInfoPosition.AFTER, depth, 0)
        }
        return WorldInfoEntry(
            uid = uid,
            key = keys,
            comment = text("name").orEmpty(),
            content = placeholders(text("content").orEmpty()),
            constant = bool("constantActive", false),
            order = (injection["priority"] as? JsonPrimitive)?.intOrNull ?: 0,
            position = position,
            disable = !bool("enabled", true),
            depth = entryDepth,
            role = entryRole,
            scanDepth = (injection["scanDepth"] as? JsonPrimitive)?.intOrNull ?: 4,
            caseSensitive = caseSensitive,
        )
    }

    // ------------------------------------------------------------------ 全局酒馆设置

    /**
     * 全局酒馆设置的默认值。迁移的是有旧世界书的用户时，世界书扫描不带名字（旧版只匹配消息正文），
     * 其余用上游默认；新用户用上游默认。
     */
    fun defaultTavernSettings(hadLegacyLorebooks: Boolean): JsonObject = buildJsonObject {
        put("worldInfo", kotlinx.serialization.json.Json.encodeToJsonElement(
            WorldInfoSettings.serializer(),
            WorldInfoSettings(includeNames = !hadLegacyLorebooks),
        ))
        put("globalVariables", JsonObject(emptyMap()))
        put("regexScripts", JsonArray(emptyList()))
        put("regexAllowed", JsonArray(emptyList()))
    }
}
