package me.rerere.tavern.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.tavern.worldinfo.WI_DEFAULT_DEPTH
import me.rerere.tavern.worldinfo.WI_DEFAULT_WEIGHT
import me.rerere.tavern.worldinfo.WorldInfoBook
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoLogic
import me.rerere.tavern.worldinfo.WorldInfoPosition
import me.rerere.tavern.worldinfo.isJsTruthy

/**
 * 卡内世界书（`character_book`）与世界书文件之间的转换，照 `ST:public/scripts/world-info.js` 的
 * `convertCharacterBook` 和 `ST:src/endpoints/characters.js` 的 `convertWorldInfoToCharacterBook`。
 *
 * 转进来：每条卡内条目变成一条酒馆条目，卡内 `extensions` 里能对上的键（`position`、`depth`、`probability` ...）落到酒馆字段，
 * `extensions` 整体也保留在条目的 `extra` 里，原书整体放进书的 `extra["originalData"]`。
 */
object CharacterBooks {
    /** 卡内世界书 → 世界书文件；条目按 `id`（缺省取下标）升序。 */
    fun toWorldInfo(characterBook: JsonObject): WorldInfoBook {
        val src = (characterBook["entries"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val byUid = LinkedHashMap<Int, WorldInfoEntry>()
        src.forEachIndexed { index, entry ->
            val uid = idOf(entry, index)
            byUid[uid] = WorldInfoCodec.decodeEntry(convertEntry(entry, uid, index), uid)
        }
        val entries = byUid.entries.sortedBy { it.key }.map { it.value }
        val name = (characterBook["name"] as? JsonPrimitive)?.contentOrNull
        val description = (characterBook["description"] as? JsonPrimitive)?.contentOrNull
        return WorldInfoBook(
            entries = entries,
            name = name,
            description = description,
            extra = JsonObject(mapOf("originalData" to characterBook)),
        )
    }

    private fun idOf(entry: JsonObject, index: Int): Int {
        val id = entry["id"] as? JsonPrimitive ?: return index
        if (id is JsonNull) return index
        return id.contentOrNull?.toDoubleOrNull()?.toInt() ?: index
    }

    private fun convertEntry(e: JsonObject, uid: Int, index: Int): JsonObject {
        val ext = e["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        fun e2(k: String): JsonElement? = ext[k]?.takeIf { it !is JsonNull }
        fun truthy(el: JsonElement?): Boolean = el.isJsTruthy()
        val position = e2("position") ?: JsonPrimitive(
            if ((e["position"] as? JsonPrimitive)?.contentOrNull == "before_char") WorldInfoPosition.BEFORE else WorldInfoPosition.AFTER,
        )
        val comment = (e["comment"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: ""
        val out = LinkedHashMap<String, JsonElement>()
        out["uid"] = JsonPrimitive(uid)
        out["key"] = e["keys"] ?: JsonArray(emptyList())
        out["keysecondary"] = (e["secondary_keys"]?.takeIf { truthy(it) }) ?: JsonArray(emptyList())
        out["comment"] = JsonPrimitive(comment)
        out["content"] = e["content"] ?: JsonPrimitive("")
        out["constant"] = JsonPrimitive(truthy(e["constant"]))
        out["selective"] = JsonPrimitive(truthy(e["selective"]))
        out["order"] = e["insertion_order"] ?: JsonPrimitive(100)
        out["position"] = position
        out["excludeRecursion"] = e2("exclude_recursion") ?: JsonPrimitive(false)
        out["preventRecursion"] = e2("prevent_recursion") ?: JsonPrimitive(false)
        out["delayUntilRecursion"] = e2("delay_until_recursion") ?: JsonPrimitive(false)
        out["disable"] = JsonPrimitive(!truthy(e["enabled"]))
        out["addMemo"] = JsonPrimitive(comment.isNotEmpty())
        out["displayIndex"] = e2("display_index") ?: JsonPrimitive(index)
        out["probability"] = e2("probability") ?: JsonPrimitive(100)
        out["useProbability"] = e2("useProbability") ?: JsonPrimitive(true)
        out["depth"] = e2("depth") ?: JsonPrimitive(WI_DEFAULT_DEPTH)
        out["selectiveLogic"] = e2("selectiveLogic") ?: JsonPrimitive(WorldInfoLogic.AND_ANY)
        out["outletName"] = e2("outlet_name") ?: JsonPrimitive("")
        out["group"] = e2("group") ?: JsonPrimitive("")
        out["groupOverride"] = e2("group_override") ?: JsonPrimitive(false)
        out["groupWeight"] = e2("group_weight") ?: JsonPrimitive(WI_DEFAULT_WEIGHT)
        out["scanDepth"] = e2("scan_depth") ?: JsonNull
        out["caseSensitive"] = e2("case_sensitive") ?: JsonNull
        out["matchWholeWords"] = e2("match_whole_words") ?: JsonNull
        out["useGroupScoring"] = e2("use_group_scoring") ?: JsonNull
        out["automationId"] = e2("automation_id") ?: JsonPrimitive("")
        out["role"] = e2("role") ?: JsonPrimitive(0)
        out["vectorized"] = e2("vectorized") ?: JsonPrimitive(false)
        out["sticky"] = e2("sticky") ?: JsonNull
        out["cooldown"] = e2("cooldown") ?: JsonNull
        out["delay"] = e2("delay") ?: JsonNull
        out["matchPersonaDescription"] = e2("match_persona_description") ?: JsonPrimitive(false)
        out["matchCharacterDescription"] = e2("match_character_description") ?: JsonPrimitive(false)
        out["matchCharacterPersonality"] = e2("match_character_personality") ?: JsonPrimitive(false)
        out["matchCharacterDepthPrompt"] = e2("match_character_depth_prompt") ?: JsonPrimitive(false)
        out["matchScenario"] = e2("match_scenario") ?: JsonPrimitive(false)
        out["matchCreatorNotes"] = e2("match_creator_notes") ?: JsonPrimitive(false)
        out["extensions"] = e["extensions"] ?: JsonObject(emptyMap())
        out["triggers"] = e2("triggers")?.takeIf { truthy(it) } ?: JsonArray(emptyList())
        out["ignoreBudget"] = e2("ignore_budget") ?: JsonPrimitive(false)
        return JsonObject(out)
    }

    /**
     * 世界书 → 卡内世界书。书里留着 `originalData`（从卡里转进来时存的原书）就以它为底，只换 `name` 和 `entries`，
     * 书级的 `scan_depth`、`token_budget`、`recursive_scanning`、`extensions` 不丢。
     */
    fun fromWorldInfo(book: WorldInfoBook, name: String): JsonObject {
        val base = LinkedHashMap<String, JsonElement>((book.extra["originalData"] as? JsonObject) ?: JsonObject(emptyMap()))
        base["name"] = JsonPrimitive(name)
        base["entries"] = JsonArray(book.entries.map { toCardEntry(it) })
        if (!base.containsKey("description") && !book.description.isNullOrEmpty()) base["description"] = JsonPrimitive(book.description)
        return JsonObject(base)
    }

    private fun toCardEntry(e: WorldInfoEntry): JsonObject {
        fun nullable(v: Int?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
        fun nullableB(v: Boolean?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
        val ext = LinkedHashMap<String, JsonElement>((e.extra["extensions"] as? JsonObject) ?: JsonObject(emptyMap()))
        ext["position"] = JsonPrimitive(e.position)
        ext["exclude_recursion"] = JsonPrimitive(e.excludeRecursion)
        ext["display_index"] = nullable(e.displayIndex)
        ext["probability"] = JsonPrimitive(e.probability)
        ext["useProbability"] = JsonPrimitive(e.useProbability)
        ext["depth"] = JsonPrimitive(e.depth)
        ext["selectiveLogic"] = JsonPrimitive(e.selectiveLogic)
        ext["outlet_name"] = JsonPrimitive(e.outletName)
        ext["group"] = JsonPrimitive(e.group)
        ext["group_override"] = JsonPrimitive(e.groupOverride)
        ext["group_weight"] = JsonPrimitive(e.groupWeight)
        ext["prevent_recursion"] = JsonPrimitive(e.preventRecursion)
        ext["delay_until_recursion"] = JsonPrimitive(e.delayUntilRecursion)
        ext["scan_depth"] = nullable(e.scanDepth)
        ext["match_whole_words"] = nullableB(e.matchWholeWords)
        ext["use_group_scoring"] = JsonPrimitive(e.useGroupScoring ?: false)
        ext["case_sensitive"] = nullableB(e.caseSensitive)
        ext["automation_id"] = JsonPrimitive(e.automationId)
        ext["role"] = nullable(e.role ?: 0)
        ext["vectorized"] = JsonPrimitive(e.vectorized)
        ext["sticky"] = nullable(e.sticky)
        ext["cooldown"] = nullable(e.cooldown)
        ext["delay"] = nullable(e.delay)
        ext["match_persona_description"] = JsonPrimitive(e.matchPersonaDescription)
        ext["match_character_description"] = JsonPrimitive(e.matchCharacterDescription)
        ext["match_character_personality"] = JsonPrimitive(e.matchCharacterPersonality)
        ext["match_character_depth_prompt"] = JsonPrimitive(e.matchCharacterDepthPrompt)
        ext["match_scenario"] = JsonPrimitive(e.matchScenario)
        ext["match_creator_notes"] = JsonPrimitive(e.matchCreatorNotes)
        ext["triggers"] = JsonArray(e.triggers.map { JsonPrimitive(it) })
        ext["ignore_budget"] = JsonPrimitive(e.ignoreBudget)
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(e.uid)
        out["keys"] = JsonArray(e.key.map { JsonPrimitive(it) })
        out["secondary_keys"] = JsonArray(e.keysecondary.map { JsonPrimitive(it) })
        out["comment"] = JsonPrimitive(e.comment)
        out["content"] = JsonPrimitive(e.content)
        out["constant"] = JsonPrimitive(e.constant)
        out["selective"] = JsonPrimitive(e.selective)
        out["insertion_order"] = JsonPrimitive(e.order)
        out["enabled"] = JsonPrimitive(!e.disable)
        out["position"] = JsonPrimitive(if (e.position == 0) "before_char" else "after_char")
        out["use_regex"] = JsonPrimitive(true)
        out["extensions"] = JsonObject(ext)
        return JsonObject(out)
    }
}
