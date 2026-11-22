package me.rerere.tavern.card

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

class CardFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 解析结果：规范化后的卡，加上来源格式（`v1`、`v2`、`v3`、`pygmalion`）。 */
data class ParsedCard(val card: CharacterCard, val sourceFormat: String)

/**
 * 角色卡 JSON 的解析与编码。
 *
 * 读：V1（平铺字段）、V2（`chara_card_v2`）、V3（`chara_card_v3`）、Pygmalion 的 `char_name` 格式都变成 V3 形状；
 * 字段含义照酒馆的导入（`ST:src/endpoints/characters.js` 的 `readFromV2`、`convertToV2`、`charaFormatData`）：
 * V1 的 `creatorcomment` 成为 `creator_notes`，字符串形式的 `tags` 按逗号拆开，字符串形式的 `alternate_greetings` 包成数组，
 * V1 缺的扩展字段补酒馆的默认值（`talkativeness` 0.5、`fav` false、`world` 空、`depth_prompt` 深度 4 角色 system）。
 * 酒馆导出的卡会在顶层重复一份 V1 字段（`name`、`description` ... `chat`、`avatar`），它们和 `data` 里的值会各改各的，
 * 读进来时一律丢弃，以 `data` 为准；其余未知的顶层键保留在 [CharacterCard.extra]。
 */
object CardCodec {
    private val json = Json { prettyPrint = false }

    /** V2 规范里的 `data` 键，缺哪个就补空值。 */
    private val V2_KEYS = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
        "system_prompt", "post_history_instructions", "alternate_greetings", "tags", "creator",
        "character_version", "extensions",
    )

    /** V3 新增、V2 规范里没有的 `data` 键；写 V2 时去掉。 */
    private val V3_ONLY_KEYS = setOf(
        "assets", "nickname", "creator_notes_multilingual", "source", "group_only_greetings",
        "creation_date", "modification_date",
    )

    /** 酒馆把 V1 字段镜像到顶层并加了运行时字段；这些顶层键不当作“未知键”保留。 */
    private val TOP_LEVEL_MIRRORS = setOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example", "creatorcomment",
        "creator_notes", "talkativeness", "fav", "tags", "creator", "chat", "avatar", "json_data", "shallow",
        "system_prompt", "post_history_instructions", "alternate_greetings", "character_version", "character_book",
        "extensions",
    )

    private const val CREATOR_NOTES_PLACEHOLDER = "Creator's notes go here."

    fun parse(text: String): ParsedCard {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw CardFormatException("The file is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw CardFormatException("The file is not valid JSON", e)
        }
        val root = element as? JsonObject ?: throw CardFormatException("A character card must be a JSON object")
        return parse(root)
    }

    fun parse(root: JsonObject): ParsedCard {
        val spec = (root["spec"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val data = root["data"] as? JsonObject
        return when {
            spec != null && data != null -> {
                val format = if (spec == CardSpec.V2) "v2" else "v3"
                ParsedCard(fromSpecObject(root, data), format)
            }
            // 没有 spec，或有 spec 却没有 data：字段还在顶层（酒馆同样当 V1 读）
            root["name"] is JsonPrimitive -> ParsedCard(fromV1(root), "v1")
            root["char_name"] is JsonPrimitive -> ParsedCard(fromPygmalion(root), "pygmalion")
            else -> throw CardFormatException("This file is not a character card (no name field)")
        }
    }

    private fun fromSpecObject(root: JsonObject, data: JsonObject): CharacterCard {
        val out = LinkedHashMap<String, JsonElement>(data)
        // 顶层的 name 兜底：data.name 缺失时用它
        if (out["name"] !is JsonPrimitive) (root["name"] as? JsonPrimitive)?.let { out["name"] = it }
        for (key in listOf("description", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
            "system_prompt", "post_history_instructions", "creator", "character_version")) {
            val v = out[key]
            if (v == null || v is JsonNull) out[key] = JsonPrimitive("")
            else if (v is JsonPrimitive && !v.isString) out[key] = JsonPrimitive(v.content)
        }
        out["alternate_greetings"] = stringList(out["alternate_greetings"], splitCommas = false)
        out["tags"] = stringList(out["tags"], splitCommas = true)
        if (out["extensions"] !is JsonObject) out["extensions"] = JsonObject(emptyMap())
        if (out["character_book"] is JsonNull) out.remove("character_book")
        if (out["group_only_greetings"] != null) out["group_only_greetings"] = stringList(out["group_only_greetings"], splitCommas = false)
        return CharacterCard(CardSpec.V3, CardSpec.V3_VERSION, JsonObject(out), topLevelExtra(root))
    }

    private fun fromV1(root: JsonObject): CharacterCard {
        fun str(vararg keys: String): String {
            for (k in keys) (root[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { return it.content }
            return ""
        }
        // the tavern strips the placeholder only from `creator_notes` (before choosing between the two keys)
        val creatorNotes = (root["creator_notes"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }?.replace(CREATOR_NOTES_PLACEHOLDER, "")
        val notes = (root["creatorcomment"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: creatorNotes ?: ""
        val data = LinkedHashMap<String, JsonElement>()
        data["name"] = JsonPrimitive(str("name"))
        data["description"] = JsonPrimitive(str("description"))
        data["personality"] = JsonPrimitive(str("personality"))
        data["scenario"] = JsonPrimitive(str("scenario"))
        data["first_mes"] = JsonPrimitive(str("first_mes"))
        data["mes_example"] = JsonPrimitive(str("mes_example"))
        data["creator_notes"] = JsonPrimitive(notes)
        data["system_prompt"] = JsonPrimitive(str("system_prompt"))
        data["post_history_instructions"] = JsonPrimitive(str("post_history_instructions"))
        data["alternate_greetings"] = stringList(root["alternate_greetings"], splitCommas = false)
        data["tags"] = stringList(root["tags"], splitCommas = true)
        data["creator"] = JsonPrimitive(str("creator"))
        data["character_version"] = JsonPrimitive(str("character_version"))
        val ext = LinkedHashMap<String, JsonElement>()
        ext["talkativeness"] = (root["talkativeness"] as? JsonPrimitive)?.let { p -> p.doubleOrNull?.let { JsonPrimitive(it) } ?: p.contentOrNull?.toDoubleOrNull()?.let { JsonPrimitive(it) } }
            ?: JsonPrimitive(CharacterCard.DEFAULT_TALKATIVENESS)
        ext["fav"] = JsonPrimitive((root["fav"] as? JsonPrimitive)?.contentOrNull == "true")
        ext["world"] = JsonPrimitive("")
        ext["depth_prompt"] = JsonObject(mapOf(
            "prompt" to JsonPrimitive(""),
            "depth" to JsonPrimitive(DepthPrompt.DEFAULT_DEPTH),
            "role" to JsonPrimitive("system"),
        ))
        data["extensions"] = JsonObject(ext)
        return CharacterCard(CardSpec.V3, CardSpec.V3_VERSION, JsonObject(data), topLevelExtra(root))
    }

    private fun fromPygmalion(root: JsonObject): CharacterCard {
        fun str(k: String) = (root[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        val mapped = JsonObject(mapOf(
            "name" to JsonPrimitive(str("char_name")),
            "description" to JsonPrimitive(str("char_persona")),
            "first_mes" to JsonPrimitive(str("char_greeting")),
            "mes_example" to JsonPrimitive(str("example_dialogue")),
            "scenario" to JsonPrimitive(str("world_scenario")),
            "creatorcomment" to JsonPrimitive(str("creatorcomment").ifEmpty { str("creator_notes") }),
            "creator" to JsonPrimitive(str("creator")),
            "tags" to (root["tags"] ?: JsonPrimitive("")),
            "talkativeness" to (root["talkativeness"] ?: JsonPrimitive(CharacterCard.DEFAULT_TALKATIVENESS)),
        ))
        return fromV1(mapped)
    }

    private fun topLevelExtra(root: JsonObject): JsonObject =
        JsonObject(root.filterKeys { it != "spec" && it != "spec_version" && it != "data" && it !in TOP_LEVEL_MIRRORS })

    /** 字符串/数组/缺省统一成字符串数组；[splitCommas] 时单个字符串按逗号拆开（标签）。 */
    private fun stringList(el: JsonElement?, splitCommas: Boolean): JsonArray = when (el) {
        is JsonArray -> JsonArray(el.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.let { p -> JsonPrimitive(p.content) } })
        is JsonPrimitive -> if (el is JsonNull || el.content.isEmpty()) JsonArray(emptyList())
        else if (splitCommas) JsonArray(el.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) })
        else JsonArray(listOf(JsonPrimitive(el.content)))
        else -> JsonArray(emptyList())
    }

    /** V3 JSON：缺的必填键补空值，其余原样。 */
    fun encodeV3(card: CharacterCard): JsonObject {
        val data = LinkedHashMap<String, JsonElement>(card.data)
        fillRequired(data)
        if (data["group_only_greetings"] == null) data["group_only_greetings"] = JsonArray(emptyList())
        return envelope(CardSpec.V3, CardSpec.V3_VERSION, data, card.extra)
    }

    /** V2 JSON：去掉 V3 才有的键；放进 PNG 的 `chara` 块，给只认 V2 的软件读。 */
    fun encodeV2(card: CharacterCard): JsonObject {
        val data = LinkedHashMap<String, JsonElement>(card.data)
        V3_ONLY_KEYS.forEach { data.remove(it) }
        fillRequired(data)
        return envelope(CardSpec.V2, CardSpec.V2_VERSION, data, card.extra)
    }

    private fun fillRequired(data: MutableMap<String, JsonElement>) {
        for (key in V2_KEYS) {
            if (key in data) continue
            data[key] = when (key) {
                "alternate_greetings", "tags" -> JsonArray(emptyList())
                "extensions" -> JsonObject(emptyMap())
                else -> JsonPrimitive("")
            }
        }
    }

    private fun envelope(spec: String, version: String, data: Map<String, JsonElement>, extra: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["spec"] = JsonPrimitive(spec)
        out["spec_version"] = JsonPrimitive(version)
        out["data"] = JsonObject(data)
        extra.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }
}
