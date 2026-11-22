package me.rerere.rikkihub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.AssemblyOptions
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptMarkers
import me.rerere.tavern.prompt.PromptOrderEntry
import me.rerere.tavern.prompt.PromptOrderList
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoSettings

/*
 * 酒馆兼容层在 Settings 里的数据（2.33.0）。`:tavern` 的类型自带无损 JSON 编解码（保留未知字段与键顺序），
 * 所以这里不另起一套 @Serializable 镜像，而是用下面这些序列化器直接落成酒馆原格式的 JSON。
 */

/** 把一个自带 JSON 编解码的类型落成它的 JSON 对象。描述符沿用 [JsonObject] 的，只能配 JSON 格式用。 */
abstract class JsonObjectBackedSerializer<T>(
    private val encode: (T) -> JsonObject,
    private val decode: (JsonObject) -> T,
) : KSerializer<T> {
    private val delegate = JsonObject.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeSerializableValue(delegate, encode(value))
    override fun deserialize(decoder: Decoder): T = decode(decoder.decodeSerializableValue(delegate))
}

object PromptEntrySerializer : JsonObjectBackedSerializer<PromptEntry>({ it.toJson() }, { PromptEntry.fromJson(it) })
object PromptPresetSerializer : JsonObjectBackedSerializer<PromptPreset>({ it.toJson() }, { PromptPreset.fromJson(it) })
object RegexScriptSerializer : JsonObjectBackedSerializer<RegexScript>({ it.toJson() }, { RegexScript.fromJson(it) })
object WorldInfoEntrySerializer : JsonObjectBackedSerializer<WorldInfoEntry>(
    { WorldInfoCodec.encodeEntry(it) }, { WorldInfoCodec.decodeEntry(it) },
)

/**
 * 全局酒馆设置。
 *
 * @property worldInfo 世界书全局设置（扫描深度、预算、递归……），字段与上游 `world_info_*` 一致
 * @property authorsNote 作者注释的全局默认值：会话里没写的字段回落到它（酒馆的 `extension_settings.note.default*`）
 * @property globalVariables 全局变量（`{{setglobalvar}}`），值按酒馆的存法：对象存 JSON 字符串，标量存原值
 * @property regexScripts 全局作用域的正则脚本（先于助手作用域执行）
 * @property regexAllowed 允许运行的预设/卡内正则的来源（如 `assistant:<id>`、`character:<id>`），导入后首次使用前要用户点头
 */
@Serializable
data class TavernSettings(
    val worldInfo: WorldInfoSettings = WorldInfoSettings(),
    val authorsNote: AuthorsNoteDefaults = AuthorsNoteDefaults(),
    val globalVariables: JsonObject = JsonObject(emptyMap()),
    val regexScripts: List<@Serializable(with = RegexScriptSerializer::class) RegexScript> = emptyList(),
    val regexAllowed: Set<String> = emptySet(),
    /** 用户设定的描述（酒馆的 persona description）；名字用用户昵称。进提示词里 `personaDescription` 标记所在的位置，宏 `{{persona}}` 也读它。 */
    val personaDescription: String = "",
)

/**
 * 作者注释的全局默认值。字段含义同会话里的 `note_*`：[position] 是 `extension_prompt_types`（0 提示词之后、1 聊天内、2 提示词之前），
 * [role] 是 `extension_prompt_roles`（0 系统、1 用户、2 助手），[interval] 按用户消息数计。
 */
@Serializable
data class AuthorsNoteDefaults(
    val prompt: String = "",
    val interval: Int = 1,
    val depth: Int = 4,
    val position: Int = 1,
    val role: Int = 0,
)

/** 提示词库条目里 rikkihub 自有信息（`extra.rikkihub`）：是否可选用、相对条目的锚点。 */
object LibraryEntryMeta {
    const val EXTRA_KEY = "rikkihub"
    const val BEFORE_SYSTEM = "before_system"
    const val AFTER_SYSTEM = "after_system"
    const val BEFORE_HISTORY = "before_history"
}

/** 库条目是否可选用（旧「模式注入」的总开关）；缺省为可用。 */
val PromptEntry.libraryEnabled: Boolean
    get() = (extra[LibraryEntryMeta.EXTRA_KEY] as? JsonObject)?.get("enabled")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true

/** 相对库条目的锚点（[LibraryEntryMeta]）；绝对（聊天内）条目没有。 */
val PromptEntry.libraryAnchor: String?
    get() = ((extra[LibraryEntryMeta.EXTRA_KEY] as? JsonObject)?.get("anchor") as? JsonPrimitive)?.contentOrNull

fun PromptEntry.withLibraryMeta(enabled: Boolean = libraryEnabled, anchor: String? = libraryAnchor): PromptEntry = copy(
    extra = JsonObject(extra + (LibraryEntryMeta.EXTRA_KEY to buildJsonObject {
        put("enabled", enabled)
        if (anchor != null) put("anchor", anchor)
    }))
)

/**
 * 助手预设的统一构造。迁移旧数据与新建助手走同一处，默认布局与组装选项只有一份。
 *
 * 默认布局：`worldInfoBefore, <提示词条目>, rikkihubContext, worldInfoAfter, dialogueExamples, chatHistory, jailbreak`。
 * 选项与 2.32.0 的请求等价：一条合并的系统消息、不加「新聊天」提示、上下文只受模型能装下的限制。
 */
object AssistantPresets {
    /** 预设的上下文长度不小于它就视为不设限：历史不按上下文截断，也不逐条分词。 */
    const val UNLIMITED_CONTEXT = 1_000_000

    val OPTIONS = AssemblyOptions(
        contextSize = UNLIMITED_CONTEXT,
        responseTokens = 4096,
        newChatPrompt = "",
        squashSystemMessages = true,
    )

    fun mainEntry(content: String) = PromptEntry(
        identifier = PromptMarkers.MAIN, name = "Main Prompt", role = PromptRole.SYSTEM,
        content = content, systemPrompt = true, marker = false,
    )

    private fun marker(id: String, name: String) = PromptEntry(identifier = id, name = name, systemPrompt = true, marker = true)

    /** 角色卡与用户设定的四个标记，没有角色时它们是空的、不产出消息。顺序照酒馆的默认顺序。 */
    private val CARD_MARKERS = listOf(
        PromptMarkers.PERSONA_DESCRIPTION to "Persona Description",
        PromptMarkers.CHAR_DESCRIPTION to "Char Description",
        PromptMarkers.CHAR_PERSONALITY to "Char Personality",
        PromptMarkers.SCENARIO to "Scenario",
    )

    /**
     * 给预设补上 `rikkihubContext`（记忆、工具说明、并行提示放的地方；酒馆预设里没有它，缺了这些内容就进不了请求）：
     * 加在世界书后块之前，没有它就加在示例对话、聊天历史之前。已有的不动。
     */
    fun withContextMarker(preset: PromptPreset): PromptPreset {
        val globalId = PromptPreset.GLOBAL_ORDER_ID.toString()
        val prompts = if (preset.prompts.any { it.identifier == PromptMarkers.RIKKIHUB_CONTEXT }) preset.prompts
        else preset.prompts + marker(PromptMarkers.RIKKIHUB_CONTEXT, "rikkihub Context")
        val orders = preset.promptOrder.map { list ->
            if (list.characterIdString != globalId || list.order.isEmpty() || list.order.any { it.identifier == PromptMarkers.RIKKIHUB_CONTEXT }) return@map list
            val order = list.order.toMutableList()
            val at = listOf(PromptMarkers.WORLD_INFO_AFTER, PromptMarkers.DIALOGUE_EXAMPLES, PromptMarkers.CHAT_HISTORY)
                .firstNotNullOfOrNull { anchor -> order.indexOfFirst { it.identifier == anchor }.takeIf { it >= 0 } } ?: order.size
            order.add(at, PromptOrderEntry(PromptMarkers.RIKKIHUB_CONTEXT, true))
            list.copy(order = order)
        }
        return if (prompts === preset.prompts && orders == preset.promptOrder) preset else preset.copy(prompts = prompts, promptOrder = orders)
    }

    /**
     * 给预设补上角色卡和用户设定的标记（已有的不动）：加在 `rikkihubContext` 之前，没有它就加在世界书后块、示例对话、聊天历史之前。
     * 升级前的助手没有这几个标记，角色卡的描述、性格、场景和用户设定就进不了请求；没有角色时这几个标记什么也不产出。
     */
    fun withCardMarkers(preset: PromptPreset): PromptPreset {
        val globalId = PromptPreset.GLOBAL_ORDER_ID.toString()
        val prompts = preset.prompts.toMutableList()
        for ((id, name) in CARD_MARKERS) if (prompts.none { it.identifier == id }) prompts += marker(id, name)
        val orders = preset.promptOrder.map { list ->
            if (list.characterIdString != globalId || list.order.isEmpty()) return@map list
            val present = list.order.mapTo(HashSet()) { it.identifier }
            val missing = CARD_MARKERS.map { it.first }.filter { it !in present }
            if (missing.isEmpty()) return@map list
            val order = list.order.toMutableList()
            val at = listOf(PromptMarkers.RIKKIHUB_CONTEXT, PromptMarkers.WORLD_INFO_AFTER, PromptMarkers.DIALOGUE_EXAMPLES, PromptMarkers.CHAT_HISTORY)
                .firstNotNullOfOrNull { anchor -> order.indexOfFirst { it.identifier == anchor }.takeIf { it >= 0 } } ?: order.size
            order.addAll(at, missing.map { PromptOrderEntry(it, true) })
            list.copy(order = order)
        }
        return if (prompts.size == preset.prompts.size && orders == preset.promptOrder) preset
        else preset.copy(prompts = prompts, promptOrder = orders)
    }

    /** [textEntries] 是提示词文本条目（`enabled` 决定在顺序里开不开）；[regexScripts] 是助手作用域的正则。 */
    fun create(textEntries: List<PromptEntry>, regexScripts: List<RegexScript> = emptyList()): PromptPreset {
        val fixed = listOf(
            marker("worldInfoBefore", "World Info (before)"),
        ) + CARD_MARKERS.map { (id, name) -> marker(id, name) } + listOf(
            marker(PromptMarkers.RIKKIHUB_CONTEXT, "rikkihub Context"),
            marker("worldInfoAfter", "World Info (after)"),
            marker("dialogueExamples", "Chat Examples"),
            marker("chatHistory", "Chat History"),
            PromptEntry(
                identifier = "jailbreak", name = "Post-History Instructions", role = PromptRole.SYSTEM,
                content = "", systemPrompt = true, marker = false,
            ),
        )
        val order = buildList {
            add(PromptOrderEntry("worldInfoBefore", true))
            textEntries.forEach { add(PromptOrderEntry(it.identifier, it.enabled)) }
            CARD_MARKERS.forEach { add(PromptOrderEntry(it.first, true)) }
            add(PromptOrderEntry(PromptMarkers.RIKKIHUB_CONTEXT, true))
            add(PromptOrderEntry("worldInfoAfter", true))
            add(PromptOrderEntry("dialogueExamples", true))
            add(PromptOrderEntry("chatHistory", true))
            add(PromptOrderEntry("jailbreak", true))
        }
        val extra = if (regexScripts.isEmpty()) JsonObject(emptyMap()) else buildJsonObject {
            put("extensions", buildJsonObject { put("regex_scripts", JsonArray(regexScripts.map { it.toJson() })) })
        }
        return PromptPreset(
            prompts = textEntries + fixed,
            promptOrder = listOf(PromptOrderList(JsonPrimitive(PromptPreset.GLOBAL_ORDER_ID), order)),
            options = OPTIONS,
            extra = extra,
        )
    }

    /** 只有一段系统提示词的预设（新建助手、创造助手等）。 */
    fun withSystemPrompt(text: String): PromptPreset = create(listOf(mainEntry(text)))
}

/** 助手提示词里 `main` 条目的文本；没有就空。把助手当作“一段系统提示词”的地方（创造助手、子代理、摘要）用这个。 */
val Assistant.mainPromptText: String
    get() = promptPreset.prompts.firstOrNull { it.identifier == PromptMarkers.MAIN }?.content.orEmpty()

/** 把 `main` 条目的文本换成 [text]；没有 `main` 时按默认布局新建（助手作用域正则保留）。 */
fun Assistant.withMainPromptText(text: String): Assistant {
    val preset = promptPreset
    if (preset.prompts.none { it.identifier == PromptMarkers.MAIN }) {
        return copy(promptPreset = AssistantPresets.create(listOf(AssistantPresets.mainEntry(text)), preset.regexScripts))
    }
    return copy(promptPreset = preset.copy(
        prompts = preset.prompts.map { if (it.identifier == PromptMarkers.MAIN) it.copy(content = text) else it },
    ))
}

/**
 * 不走酒馆组装的调用方（记忆整理、工作区子代理）用的提示词文本：助手允许且会话给了非空白的覆盖时整个替换，
 * 否则取 `main` 条目。它们没有世界书、库条目和宏。
 */
fun Assistant.legacyMainPrompt(conversationSystemPrompt: String?): String =
    if (allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) conversationSystemPrompt else mainPromptText

/** 助手提示词文本条目里开启且非空白的内容，按顺序空一行合并；列表和卡片里的预览用。 */
fun Assistant.promptPreviewText(): String = promptPreset.textEntries()
    .filter { it.enabled && !it.content.isNullOrBlank() }
    .joinToString("\n\n") { it.content.orEmpty() }

/** 提示词库条目的 id（库条目的 identifier 就是原模式注入的 id）。 */
fun Settings.promptLibraryIds(): Set<kotlin.uuid.Uuid> =
    promptLibrary.mapNotNull { runCatching { kotlin.uuid.Uuid.parse(it.identifier) }.getOrNull() }.toSet()

/** 预设里的助手作用域正则（`extensions.regex_scripts`）；坏掉的脚本跳过。 */
val PromptPreset.regexScripts: List<RegexScript>
    get() = ((extra["extensions"] as? JsonObject)?.get("regex_scripts") as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.let { obj -> runCatching { RegexScript.fromJson(obj) }.getOrNull() } }
        .orEmpty()

/** 换掉预设里的助手作用域正则，其余不动；传空列表就去掉 `regex_scripts`（`extensions` 里别的键保留）。 */
fun PromptPreset.withRegexScripts(scripts: List<RegexScript>): PromptPreset {
    val extensions = (extra["extensions"] as? JsonObject) ?: JsonObject(emptyMap())
    val newExtensions = if (scripts.isEmpty()) JsonObject(extensions - "regex_scripts")
    else JsonObject(extensions + ("regex_scripts" to JsonArray(scripts.map { it.toJson() })))
    val newExtra = if (newExtensions.isEmpty()) JsonObject(extra - "extensions") else JsonObject(extra + ("extensions" to newExtensions))
    return copy(extra = newExtra)
}

/** 不算「提示词文本」的辅助条目：酒馆预设里的辅助提示词、后置指令、增强定义。 */
private val AUXILIARY_PROMPTS = setOf("nsfw", "jailbreak", "enhanceDefinitions")

/**
 * 助手提示词的文本条目：全局顺序里不是标记、不是辅助条目、位置是相对的那些，按顺序。
 * 旧的「整块 / 分块」就对应这些条目（迁移出来的助手：一个 `main`，或各分块一个）。
 */
fun PromptPreset.textEntries(): List<PromptEntry> = layout().entries.filter {
    it.marker != true && it.identifier !in AUXILIARY_PROMPTS && (it.injectionPosition ?: 0) == 0
}

// ---------------------------------------------------------------------------------------------
// 酒馆模式（Assistant.tavernMode）关时的精简布局。

/** 酒馆模式关时显示、发送的条目：主提示词和用户自己加的文本条目（不是标记，也不是酒馆预设的辅助条目、历史之后的指令）。 */
val PromptEntry.isPlainPromptEntry: Boolean
    get() = marker != true && identifier !in AUXILIARY_PROMPTS

/**
 * 酒馆模式关时实际发送的预设：世界书前块、主提示词和用户文本条目、记忆与工具说明、世界书后块、世界书示例、聊天历史。
 * 文本条目的顺序和开关照旧，固定标记总是开；提示词库随后按锚点或深度并入。
 * 组装选项用默认值（[AssistantPresets.OPTIONS]），助手作用域正则等其余字段（`extra`）保留。
 * 角色卡与用户设定、作者注释、历史之后的指令不生效；原来的布局和选项仍存在预设里，打开酒馆模式就回来。
 */
fun PromptPreset.plainLayout(): PromptPreset {
    val plain = layout().entries.filter { it.isPlainPromptEntry }
    val head = PromptEntry(identifier = PromptMarkers.WORLD_INFO_BEFORE, name = "World Info (before)", systemPrompt = true, marker = true)
    val tail = listOf(
        PromptEntry(identifier = PromptMarkers.RIKKIHUB_CONTEXT, name = "rikkihub Context", systemPrompt = true, marker = true),
        PromptEntry(identifier = PromptMarkers.WORLD_INFO_AFTER, name = "World Info (after)", systemPrompt = true, marker = true),
        PromptEntry(identifier = PromptMarkers.DIALOGUE_EXAMPLES, name = "Chat Examples", systemPrompt = true, marker = true),
        PromptEntry(identifier = PromptMarkers.CHAT_HISTORY, name = "Chat History", systemPrompt = true, marker = true),
    )
    val order = listOf(PromptOrderEntry(head.identifier, true)) +
        plain.map { PromptOrderEntry(it.identifier, it.enabled) } + tail.map { PromptOrderEntry(it.identifier, true) }
    return copy(
        // 条目对象原样保留（开关在顺序里），同一标识符只取第一个，和 layout() 一致
        prompts = listOf(head) + plain.map { entry -> prompts.first { it.identifier == entry.identifier } } + tail,
        promptOrder = listOf(PromptOrderList(JsonPrimitive(PromptPreset.GLOBAL_ORDER_ID), order)),
        options = AssistantPresets.OPTIONS,
    )
}

/** 酒馆模式关时布局里还在、但不显示的标记；其余标记只有角色、世界书或用户设定时才产出内容。 */
private val DEFAULT_FIXED_IDS = setOf(
    PromptMarkers.WORLD_INFO_BEFORE, PromptMarkers.PERSONA_DESCRIPTION, PromptMarkers.CHAR_DESCRIPTION,
    PromptMarkers.CHAR_PERSONALITY, PromptMarkers.SCENARIO, PromptMarkers.RIKKIHUB_CONTEXT, PromptMarkers.WORLD_INFO_AFTER,
    PromptMarkers.DIALOGUE_EXAMPLES, PromptMarkers.CHAT_HISTORY, PromptMarkers.JAILBREAK,
)

/**
 * 升级时判断已有助手要不要开酒馆模式：关掉后一次没有角色、没有世界书的对话会不会和现在发得不一样。
 * 会变的情况：组装选项改过、有默认布局以外的条目（导入的预设）、历史之后的指令有内容、记忆说明或聊天历史被关、
 * 文本条目被挪到记忆说明或聊天历史之后、[personaDescription] 不为空且用户设定标记开着。
 * 世界书、提示词库、角色会话和作者注释由调用方另外看（[inferTavernMode]）。
 */
fun PromptPreset.hasTavernCustomization(personaDescription: String = ""): Boolean {
    if (options != AssistantPresets.OPTIONS) return true
    val entries = layout().entries
    if (entries.any { !it.isPlainPromptEntry && it.identifier !in DEFAULT_FIXED_IDS }) return true
    fun entry(id: String) = entries.firstOrNull { it.identifier == id }
    entry(PromptMarkers.JAILBREAK)?.let { if (it.enabled && !it.content.isNullOrBlank()) return true }
    val context = entries.indexOfFirst { it.identifier == PromptMarkers.RIKKIHUB_CONTEXT }
    val history = entries.indexOfFirst { it.identifier == PromptMarkers.CHAT_HISTORY }
    if (context < 0 || history < 0 || !entries[context].enabled || !entries[history].enabled || history < context) return true
    val lastRelativeText = entries.indexOfLast { it.isPlainPromptEntry && (it.injectionPosition ?: 0) == 0 }
    if (lastRelativeText > context) return true
    if (personaDescription.isNotBlank() && entry(PromptMarkers.PERSONA_DESCRIPTION)?.enabled == true) return true
    return false
}

/**
 * 升级时已有助手的酒馆模式：预设有酒馆专属改动（[hasTavernCustomization]），或用着世界书 / 提示词库（含允许会话单独选），
 * 或全局作者注释有内容，或 [usedByTavernConversations]（有会话和角色聊、或写了作者注释）时开，其余关——关的那些在没有角色时请求和升级前一样。
 */
fun Assistant.inferTavernMode(personaDescription: String, globalAuthorsNote: String, usedByTavernConversations: Boolean): Boolean =
    usedByTavernConversations || globalAuthorsNote.isNotEmpty() || lorebookIds.isNotEmpty() || libraryEntryIds.isNotEmpty() ||
        allowConversationPromptInjection || promptPreset.hasTavernCustomization(personaDescription)

/**
 * 把提示词文本条目换成 [entries]：旧的从提示词和顺序里去掉，新的放回旧的第一个所在的位置
 * （没有旧的就放在 `worldInfoBefore` 之后）。其余条目、选项、正则不动。
 */
fun PromptPreset.withTextEntries(entries: List<PromptEntry>): PromptPreset {
    val oldIds = textEntries().map { it.identifier }.toSet()
    val globalId = PromptPreset.GLOBAL_ORDER_ID.toString()
    val lists = if (promptOrder.any { it.characterIdString == globalId }) promptOrder else
        promptOrder + PromptOrderList(JsonPrimitive(PromptPreset.GLOBAL_ORDER_ID), layout().entries.map { PromptOrderEntry(it.identifier, it.enabled) })
    val newOrderEntries = entries.map { PromptOrderEntry(it.identifier, it.enabled) }
    val updated = lists.map { list ->
        if (list.characterIdString != globalId) return@map list
        val firstOld = list.order.indexOfFirst { it.identifier in oldIds }
        val kept = list.order.filter { it.identifier !in oldIds }
        val at = if (firstOld >= 0) list.order.take(firstOld).count { it.identifier !in oldIds }
        else (kept.indexOfFirst { it.identifier == "worldInfoBefore" } + 1)
        list.copy(order = kept.take(at) + newOrderEntries + kept.drop(at))
    }
    return copy(prompts = prompts.filter { it.identifier !in oldIds } + entries, promptOrder = updated)
}

// ---------------------------------------------------------------------------------------------
// 布局编辑：对预设的「提示词 + 全局顺序」做增删改移。界面（Prompt Manager 布局编辑器）与 Web 共用这些纯函数。

private fun PromptPreset.globalOrder(): PromptOrderList {
    val id = PromptPreset.GLOBAL_ORDER_ID.toString()
    return promptOrder.firstOrNull { it.characterIdString == id }
        ?: PromptOrderList(JsonPrimitive(PromptPreset.GLOBAL_ORDER_ID), layout().entries.map { PromptOrderEntry(it.identifier, it.enabled) })
}

private fun PromptPreset.withGlobalOrder(order: List<PromptOrderEntry>): PromptPreset {
    val id = PromptPreset.GLOBAL_ORDER_ID.toString()
    val current = globalOrder()
    val updated = current.copy(order = order)
    return copy(promptOrder = if (promptOrder.any { it.characterIdString == id }) promptOrder.map { if (it.characterIdString == id) updated else it } else promptOrder + updated)
}

/** 改一个条目的内容（开关不在这里，见 [withEntryEnabled]）。标识符不能改。 */
fun PromptPreset.withEntryUpdated(identifier: String, transform: (PromptEntry) -> PromptEntry): PromptPreset =
    copy(prompts = prompts.map { if (it.identifier == identifier) transform(it).copy(identifier = identifier) else it })

/** 开关一个条目：开关存在全局顺序里，不在条目对象里。 */
fun PromptPreset.withEntryEnabled(identifier: String, enabled: Boolean): PromptPreset =
    withGlobalOrder(globalOrder().order.map { if (it.identifier == identifier) it.copy(enabled = enabled) else it })

/** 在全局顺序里把条目挪 [delta] 位（正数往后）；到头了就不动。 */
fun PromptPreset.withEntryMoved(identifier: String, delta: Int): PromptPreset {
    val order = globalOrder().order.toMutableList()
    val from = order.indexOfFirst { it.identifier == identifier }
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, order.lastIndex)
    if (to == from) return this
    order.add(to, order.removeAt(from))
    return withGlobalOrder(order)
}

/**
 * 把条目挪到 [targetIdentifier] 现在的位置（拖动排序用：拖到谁的位置，就占谁的位置，其余顺延）。
 * 任何一个标识符不在顺序里，或两者相同，都不动。
 */
fun PromptPreset.withEntryMovedTo(identifier: String, targetIdentifier: String): PromptPreset {
    if (identifier == targetIdentifier) return this
    val order = globalOrder().order.toMutableList()
    val from = order.indexOfFirst { it.identifier == identifier }
    val to = order.indexOfFirst { it.identifier == targetIdentifier }
    if (from < 0 || to < 0) return this
    order.add(to, order.removeAt(from))
    return withGlobalOrder(order)
}

/** 删除条目：标记（世界书、聊天历史……）是布局的骨架，不能删。 */
fun PromptPreset.withEntryRemoved(identifier: String): PromptPreset {
    if (prompts.firstOrNull { it.identifier == identifier }?.marker == true) return this
    return copy(prompts = prompts.filter { it.identifier != identifier })
        .withGlobalOrder(globalOrder().order.filter { it.identifier != identifier })
}

/** 提示词块之后的第一个自动填充标记：角色卡与用户设定的标记，或没有它们时的 `rikkihubContext`。 */
private val AFTER_PROMPT_BLOCK = listOf(
    PromptMarkers.PERSONA_DESCRIPTION, PromptMarkers.CHAR_DESCRIPTION, PromptMarkers.CHAR_PERSONALITY, PromptMarkers.SCENARIO,
    PromptMarkers.RIKKIHUB_CONTEXT,
)

/** 新增一个文本条目，放在 [beforeIdentifier] 之前（缺省放在提示词块的末尾：第一个自动填充标记之前）。 */
fun PromptPreset.withEntryAdded(entry: PromptEntry, beforeIdentifier: String? = null): PromptPreset {
    require(prompts.none { it.identifier == entry.identifier }) { "duplicate identifier ${entry.identifier}" }
    val order = globalOrder().order.toMutableList()
    val at = if (beforeIdentifier != null) {
        order.indexOfFirst { it.identifier == beforeIdentifier }.takeIf { it >= 0 } ?: order.size
    } else {
        order.indexOfFirst { it.identifier in AFTER_PROMPT_BLOCK }.takeIf { it >= 0 } ?: order.size
    }
    order.add(at, PromptOrderEntry(entry.identifier, entry.enabled))
    return copy(prompts = prompts + entry).withGlobalOrder(order)
}
