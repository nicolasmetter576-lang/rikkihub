package me.rerere.tavern.regex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** ST:public/scripts/extensions/regex/engine.js:101-111 regex_placement。 */
object RegexPlacement {
    const val MD_DISPLAY = 0 // 已废弃
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3
    const val WORLD_INFO = 5
    const val REASONING = 6
}

/** ST:public/scripts/extensions/regex/engine.js:117-121 substitute_find_regex。 */
object SubstituteFindRegex {
    const val NONE = 0
    const val RAW = 1
    const val ESCAPED = 2
}

/**
 * 酒馆正则脚本，字段名与上游 RegexScriptData 一致（ST:public/scripts/char-data.js:88-102）。
 * 未知字段放进 [extra]，导入导出无损。
 */
data class RegexScript(
    val id: String = "",
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val trimStrings: List<String> = emptyList(),
    val placement: List<Int> = emptyList(),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val runOnEdit: Boolean = false,
    val substituteRegex: Int = SubstituteFindRegex.NONE,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject = buildJsonObject {
        extra.forEach { (k, v) -> put(k, v) }
        put("id", id)
        put("scriptName", scriptName)
        put("findRegex", findRegex)
        put("replaceString", replaceString)
        put("trimStrings", JsonArray(trimStrings.map { JsonPrimitive(it) }))
        put("placement", JsonArray(placement.map { JsonPrimitive(it) }))
        put("disabled", disabled)
        put("markdownOnly", markdownOnly)
        put("promptOnly", promptOnly)
        put("runOnEdit", runOnEdit)
        put("substituteRegex", substituteRegex)
        put("minDepth", minDepth?.let { JsonPrimitive(it) } ?: JsonNull)
        put("maxDepth", maxDepth?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        private val KNOWN = setOf(
            "id", "scriptName", "findRegex", "replaceString", "trimStrings", "placement", "disabled",
            "markdownOnly", "promptOnly", "runOnEdit", "substituteRegex", "minDepth", "maxDepth",
        )

        /** 宽松解码：酒馆的数据来自各种卡和旧版本，缺字段、类型不对都按默认值处理。 */
        fun fromJson(obj: JsonObject): RegexScript {
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            fun bool(k: String) = (obj[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false
            fun int(k: String): Int? = (obj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                ?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
            fun strList(k: String) = (obj[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            return RegexScript(
                id = str("id"),
                scriptName = str("scriptName"),
                findRegex = str("findRegex"),
                replaceString = str("replaceString"),
                trimStrings = strList("trimStrings"),
                placement = (obj["placement"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.doubleOrNull?.toInt() } }
                    ?: emptyList(),
                disabled = bool("disabled"),
                markdownOnly = bool("markdownOnly"),
                promptOnly = bool("promptOnly"),
                runOnEdit = bool("runOnEdit"),
                substituteRegex = int("substituteRegex") ?: SubstituteFindRegex.NONE,
                minDepth = int("minDepth"),
                maxDepth = int("maxDepth"),
                extra = JsonObject(obj.filterKeys { it !in KNOWN }),
            )
        }
    }
}
