package me.rerere.tavern.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.regex.RegexScript

object CardSpec {
    const val V2 = "chara_card_v2"
    const val V2_VERSION = "2.0"
    const val V3 = "chara_card_v3"
    const val V3_VERSION = "3.0"
}

/** 卡的 `extensions.depth_prompt`：在聊天历史里按深度插入的一段提示词。 */
data class DepthPrompt(
    val prompt: String = "",
    val depth: Int = DEFAULT_DEPTH,
    val role: PromptRole = PromptRole.SYSTEM,
) {
    companion object {
        const val DEFAULT_DEPTH = 4
    }
}

/**
 * 角色卡（规范化成 V3 的形状）：`spec`、`spec_version`、`data`，其余顶层键放 [extra]。
 *
 * [data] 是卡里 `data` 对象的原貌——已知字段、V3 新增字段、第三方工具塞进来的未知字段都在里面，键序不变，
 * 读写都不丢；类型化的读取口（[name]、[description] ...）只是视图，改字段用 [withData] / [withExtension]，
 * 每次返回新对象。
 */
class CharacterCard(
    val spec: String,
    val specVersion: String,
    val data: JsonObject,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    val name: String get() = str("name")
    val description: String get() = str("description")
    val personality: String get() = str("personality")
    val scenario: String get() = str("scenario")
    val firstMes: String get() = str("first_mes")
    val mesExample: String get() = str("mes_example")
    val creatorNotes: String get() = str("creator_notes")
    val systemPrompt: String get() = str("system_prompt")
    val postHistoryInstructions: String get() = str("post_history_instructions")
    val creator: String get() = str("creator")
    val characterVersion: String get() = str("character_version")
    val nickname: String get() = str("nickname")
    val alternateGreetings: List<String> get() = strings("alternate_greetings")
    val groupOnlyGreetings: List<String> get() = strings("group_only_greetings")
    val tags: List<String> get() = strings("tags")

    /** 卡内世界书（`character_book` 原样），没有或为 null 时返回 null。 */
    val characterBook: JsonObject? get() = data["character_book"] as? JsonObject

    /** `assets`（V3）：每项 `{type, uri, name, ext}`。 */
    val assets: List<JsonObject> get() = (data["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    val extensions: JsonObject get() = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())

    /** 酒馆的“话痨度”，只有群聊用得到，这里保存不使用。 */
    val talkativeness: Double get() = extensions["talkativeness"]?.let { (it as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() } } ?: DEFAULT_TALKATIVENESS

    val fav: Boolean get() = extensions["fav"]?.let { (it as? JsonPrimitive)?.let { p -> p.booleanOrNull ?: (p.contentOrNull == "true") } } ?: false

    /** `extensions.world`：酒馆里指向一本全局世界书的名字。 */
    val world: String get() = (extensions["world"] as? JsonPrimitive)?.contentOrNull ?: ""

    val depthPrompt: DepthPrompt
        get() {
            val o = extensions["depth_prompt"] as? JsonObject ?: return DepthPrompt()
            val depth = (o["depth"] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toDoubleOrNull()?.toInt() } ?: DepthPrompt.DEFAULT_DEPTH
            val role = when ((o["role"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "user" -> PromptRole.USER
                "assistant" -> PromptRole.ASSISTANT
                else -> PromptRole.SYSTEM
            }
            return DepthPrompt(prompt = (o["prompt"] as? JsonPrimitive)?.contentOrNull ?: "", depth = depth, role = role)
        }

    /** `extensions.regex_scripts`：卡自带的正则脚本（读不出的项跳过）。 */
    val regexScripts: List<RegexScript>
        get() = (extensions["regex_scripts"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> runCatching { RegexScript.fromJson(o) }.getOrNull() } } ?: emptyList()

    /** 全部问候语：`first_mes` 在前，后面是 `alternate_greetings`（空串跳过）。 */
    val greetings: List<String> get() = listOf(firstMes).plus(alternateGreetings).filter { it.isNotEmpty() }

    fun withData(key: String, value: JsonElement?): CharacterCard =
        CharacterCard(spec, specVersion, JsonObject(LinkedHashMap(data).also { if (value == null) it.remove(key) else it[key] = value }), extra)

    fun withData(fields: Map<String, JsonElement?>): CharacterCard =
        CharacterCard(spec, specVersion, JsonObject(LinkedHashMap(data).also { m -> fields.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v } }), extra)

    /** 改 `data.extensions` 里的一个键；null 删除该键。 */
    fun withExtension(key: String, value: JsonElement?): CharacterCard {
        val ext = LinkedHashMap(extensions)
        if (value == null) ext.remove(key) else ext[key] = value
        return withData("extensions", JsonObject(ext))
    }

    fun withExtra(extra: JsonObject): CharacterCard = CharacterCard(spec, specVersion, data, extra)

    /** 规范化后的 V3 JSON：`spec`、`spec_version`、`data`，再接其余顶层键。 */
    fun toJson(): JsonObject = CardCodec.encodeV3(this)

    override fun equals(other: Any?): Boolean = other is CharacterCard && toJson() == other.toJson()
    override fun hashCode(): Int = toJson().hashCode()
    override fun toString(): String = "CharacterCard(${name})"

    private fun str(key: String): String = (data[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: ""

    private fun strings(key: String): List<String> = when (val v = data[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
        else -> emptyList()
    }

    companion object {
        const val DEFAULT_TALKATIVENESS = 0.5

        /** 新建一张空卡。 */
        fun blank(name: String): CharacterCard =
            CardCodec.parse(JsonObject(mapOf("name" to JsonPrimitive(name))).toString()).card
    }
}
