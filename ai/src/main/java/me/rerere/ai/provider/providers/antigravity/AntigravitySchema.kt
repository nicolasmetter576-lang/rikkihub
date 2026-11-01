package me.rerere.ai.provider.providers.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 工具参数 schema 清理，移植自 CPA `util.CleanJSONSchemaForAntigravityTool`
 * （`internal/util/gemini_schema.go`）。只处理工具声明里的 schema，绝不碰历史里的
 * 调用参数（CPA 曾因整包清理改坏历史参数）。
 *
 * 与 CPA 相同的规则：内联本地 `$ref`、`const`→enum、enum 全部移进描述后删除、
 * 约束类关键字移进描述后删除、`not`/条件分支/`allOf` 合并、`anyOf`/`oneOf` 取最强分支、
 * 类型数组取一个类型并标 `nullable`、删除不支持的关键字与 `x-*` 扩展、`required` 只留
 * 存在的属性、有 `properties` 的节点补 `type:object`、数组缺 `items` 补字符串项；
 * Claude（VALIDATED 模式）再加占位：空对象加必填 `reason`，有属性无必填加必填 `_`
 * （CPA 把工具 schema 包一层再清理，所以根节点也按嵌套节点处理）。
 * 非 Claude 目标删除 `title`。
 */
object AntigravitySchema {
    const val PLACEHOLDER_REASON = "reason"
    const val PLACEHOLDER_UNDERSCORE = "_"
    private const val PLACEHOLDER_REASON_DESCRIPTION = "Brief explanation of why you are calling this tool"

    private val CONSTRAINT_KEYWORDS = listOf(
        "minLength", "maxLength", "exclusiveMinimum", "exclusiveMaximum",
        "pattern", "minItems", "maxItems", "uniqueItems", "contains", "format",
        "default", "examples", "minimum", "maximum", "multipleOf",
    )

    private val UNSUPPORTED_KEYWORDS = setOf(
        "\$schema", "\$defs", "definitions", "const", "\$ref", "\$id", "id", "additionalProperties",
        "\$anchor", "\$vocabulary", "\$dynamicRef", "\$dynamicAnchor",
        "propertyNames", "patternProperties",
        "dependentRequired", "dependentSchemas", "dependencies",
        "if", "then", "else",
        "\$comment", "enumDescriptions", "enumTitles", "prefill", "deprecated", "encrypted",
        "additionalItems", "unevaluatedProperties", "unevaluatedItems", "contentSchema",
        "not", "prefixItems",
    )

    /** 清理一份工具参数 schema。[claude] = 目标是 Claude 模型（VALIDATED 模式）。 */
    fun cleanToolSchema(schema: JsonElement, claude: Boolean): JsonObject {
        val root = schema as? JsonObject ?: return emptyObjectSchema(claude)
        val inlined = inlineLocalRefs(root, root, mutableSetOf()) as? JsonObject ?: root
        var cleaned = cleanNode(inlined, claude)
        if (cleaned["type"] == null) cleaned = cleaned.with("type", JsonPrimitive("object"))
        return if (claude) addPlaceholders(cleaned) else cleaned
    }

    private fun emptyObjectSchema(claude: Boolean): JsonObject {
        val base = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))
        return if (claude) addPlaceholders(base) else base
    }

    // ---- $ref ----

    private fun inlineLocalRefs(root: JsonObject, value: JsonElement, active: MutableSet<String>): JsonElement =
        when (value) {
            is JsonArray -> JsonArray(value.map { inlineLocalRefs(root, it, active) })
            is JsonObject -> {
                val ref = (value["\$ref"] as? JsonPrimitive)?.contentOrNull
                val target = ref?.takeIf { it.startsWith("#/") }?.let { resolvePointer(root, it) }
                when {
                    ref == null || target == null ->
                        JsonObject(value.mapValues { (_, v) -> inlineLocalRefs(root, v, active) })

                    ref in active -> cyclicRefFallback(value, target, ref)

                    else -> {
                        active += ref
                        val resolved = inlineLocalRefs(root, target, active)
                        active -= ref
                        if (resolved is JsonObject) {
                            val merged = LinkedHashMap<String, JsonElement>(resolved)
                            value.forEach { (k, v) ->
                                if (k != "\$ref") merged[k] = inlineLocalRefs(root, v, active)
                            }
                            JsonObject(merged)
                        } else {
                            JsonObject(value.mapValues { (_, v) -> inlineLocalRefs(root, v, active) })
                        }
                    }
                }
            }

            else -> value
        }

    private fun resolvePointer(root: JsonObject, ref: String): JsonElement? {
        var current: JsonElement = root
        for (raw in ref.removePrefix("#/").split('/')) {
            val token = raw.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JsonObject -> current[token] ?: return null
                is JsonArray -> current.getOrNull(token.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun cyclicRefFallback(node: JsonObject, target: JsonElement, ref: String): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        (target as? JsonObject)?.let { t ->
            listOf("type", "nullable", "description").forEach { key -> t[key]?.let { out[key] = it } }
        }
        node.forEach { (k, v) -> if (k != "\$ref") out[k] = v }
        val hint = "See: ${refName(ref)}"
        out["description"] = JsonPrimitive(mergeHint(out["description"].stringOrEmpty(), hint))
        return JsonObject(out)
    }

    private fun refName(ref: String): String {
        val index = ref.lastIndexOf('/')
        val name = if (index >= 0 && index + 1 < ref.length) ref.substring(index + 1) else ref
        return name.replace("~1", "/").replace("~0", "~")
    }

    // ---- 节点清理 ----

    private fun cleanNode(input: JsonObject, claude: Boolean): JsonObject {
        val node = LinkedHashMap<String, JsonElement>(input)
        val hints = mutableListOf<String>()

        // 未解析的 $ref（非本地）：删掉并留提示
        (node["\$ref"] as? JsonPrimitive)?.contentOrNull?.let { hints += "See: ${refName(it)}" }

        // const → enum
        node["const"]?.let { constValue -> if (node["enum"] == null) node["enum"] = JsonArray(listOf(constValue)) }

        // enum：值转字符串，1..10 个写进描述，然后全部删除（Antigravity 不强制函数参数 enum）
        (node["enum"] as? JsonArray)?.let { values ->
            val strings = values.map { it.stringValue() }
            if (strings.size in 1..10) hints += "Allowed: " + strings.joinToString(", ")
            node.remove("enum")
        }

        if ((node["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) {
            hints += "No extra properties allowed"
        }

        CONSTRAINT_KEYWORDS.forEach { key ->
            node[key]?.let { value -> hints += "$key: ${value.stringValue()}" }
        }
        node["not"]?.let { hints += "not: $it" }

        // if/then/else：分支里的属性补进本节点
        listOf("then", "else").forEach { key ->
            ((node[key] as? JsonObject)?.get("properties") as? JsonObject)?.let { branchProps ->
                node["properties"] = mergeMissing(node["properties"] as? JsonObject, branchProps)
            }
        }

        // allOf：属性与 required 合并，其他键缺的补上
        (node.remove("allOf") as? JsonArray)?.forEach { branch ->
            val b = branch as? JsonObject ?: return@forEach
            (b["properties"] as? JsonObject)?.let { node["properties"] = mergeMissing(node["properties"] as? JsonObject, it) }
            (b["required"] as? JsonArray)?.let { req ->
                val merged = (node["required"].stringList() + req.map { it.stringValue() }).distinct()
                node["required"] = JsonArray(merged.map(::JsonPrimitive))
            }
            b.forEach { (k, v) -> if (k != "properties" && k != "required" && node[k] == null) node[k] = v }
        }

        // anyOf / oneOf
        for (unionKey in listOf("anyOf", "oneOf")) {
            val items = (node[unionKey] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: continue
            node.remove(unionKey)
            if (items.isEmpty()) continue
            val hasNull = items.any { it.typeName() == "null" }
            if (node["properties"] is JsonObject) {
                items.forEach { item ->
                    (item["properties"] as? JsonObject)?.let {
                        node["properties"] = mergeMissing(node["properties"] as? JsonObject, it)
                    }
                }
                if (hasNull) node["nullable"] = JsonPrimitive(true)
                if (node["type"] == null) node["type"] = JsonPrimitive("object")
                continue
            }
            val (bestIndex, types) = selectBest(items)
            val selected = LinkedHashMap<String, JsonElement>(items[bestIndex])
            if (hasNull && items[bestIndex].typeName() != "null") selected["nullable"] = JsonPrimitive(true)
            val parentDescription = node["description"].stringOrEmpty()
            if (parentDescription.isNotEmpty()) {
                selected["description"] = JsonPrimitive(
                    selected["description"].stringOrEmpty().let { own ->
                        if (own.isEmpty()) parentDescription else mergeHint(parentDescription, own)
                    }
                )
            }
            if (types.size > 1) {
                selected["description"] = JsonPrimitive(
                    mergeHint(selected["description"].stringOrEmpty(), "Accepts: " + types.joinToString(" | "))
                )
            }
            // CPA 用所选分支整体替换父节点（只保留父描述），这里同样
            return cleanNode(JsonObject(selected), claude).withHints(hints)
        }

        // 类型数组
        (node["type"] as? JsonArray)?.let { typeArray ->
            val names = typeArray.map { it.stringValue() }
            val hasNull = "null" in names
            val nonNull = names.filter { it.isNotEmpty() && it != "null" }
            val chosen = when {
                nonNull.isEmpty() -> "string"
                node["items"] != null && "array" in nonNull -> "array"
                node["properties"] != null && "object" in nonNull -> "object"
                else -> nonNull.first()
            }
            node["type"] = JsonPrimitive(chosen)
            if (chosen != "array") node.remove("items")
            if (nonNull.size > 1) hints += "Accepts: " + nonNull.joinToString(" | ")
            if (hasNull) {
                node["nullable"] = JsonPrimitive(true)
                hints += "(nullable)"
            }
        }

        // 删除不支持的关键字、约束、扩展字段
        (UNSUPPORTED_KEYWORDS + CONSTRAINT_KEYWORDS).forEach { node.remove(it) }
        node.keys.filter { it.startsWith("x-") }.forEach { node.remove(it) }
        if (!claude) node.remove("title")

        // 递归：properties 是名映射，值才是 schema
        (node["properties"] as? JsonObject)?.let { props ->
            node["properties"] = JsonObject(props.mapValues { (_, v) ->
                (v as? JsonObject)?.let { cleanNode(it, claude) } ?: JsonObject(mapOf("type" to JsonPrimitive("string")))
            })
            if (node["type"].let { it == null || it.stringValue() != "object" }) node["type"] = JsonPrimitive("object")
        }

        // items
        when (val items = node["items"]) {
            is JsonObject -> node["items"] = cleanNode(items, claude)
            is JsonArray -> node["items"] = (items.firstOrNull() as? JsonObject)?.let { cleanNode(it, claude) }
                ?: JsonObject(mapOf("type" to JsonPrimitive("string")))
            is JsonPrimitive -> node["items"] = JsonObject(mapOf("type" to JsonPrimitive("string")))
            else -> Unit
        }
        val typeName = (node["type"] as? JsonPrimitive)?.contentOrNull
        if (node["items"] != null && typeName == null) node["type"] = JsonPrimitive("array")
        if (node["items"] != null && typeName != null && typeName != "array") node.remove("items")
        if (node["type"].stringValue() == "array" && node["items"] == null) {
            node["items"] = JsonObject(mapOf("type" to JsonPrimitive("string")))
        }

        // required 只留存在的属性
        val props = node["properties"] as? JsonObject
        val required = node["required"].stringList()
        if (node["required"] != null) {
            val valid = if (props == null) emptyList() else required.filter { it in props }.distinct()
            if (valid.isEmpty()) node.remove("required") else node["required"] = JsonArray(valid.map(::JsonPrimitive))
        }

        return JsonObject(node).withHints(hints)
    }

    private fun selectBest(items: List<JsonObject>): Pair<Int, List<String>> {
        var bestScore = -1
        var bestIndex = 0
        val types = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            var t = item.typeName()
            val score = when {
                t == "object" || item["properties"] != null -> 3.also { if (t.isEmpty()) t = "object" }
                t == "array" || item["items"] != null -> 2.also { if (t.isEmpty()) t = "array" }
                t.isNotEmpty() && t != "null" -> 1
                else -> 0
            }
            if (t.isNotEmpty()) types += t
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex to types
    }

    // ---- Claude VALIDATED 占位 ----

    private fun addPlaceholders(node: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(node)
        (out["properties"] as? JsonObject)?.let { props ->
            out["properties"] = JsonObject(props.mapValues { (_, v) -> (v as? JsonObject)?.let(::addPlaceholders) ?: v })
        }
        (out["items"] as? JsonObject)?.let { out["items"] = addPlaceholders(it) }
        if (out["type"].stringValue() != "object") return JsonObject(out)

        val props = out["properties"] as? JsonObject
        if (props == null || props.isEmpty()) {
            out["properties"] = JsonObject(
                mapOf(
                    PLACEHOLDER_REASON to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(PLACEHOLDER_REASON_DESCRIPTION),
                        )
                    )
                )
            )
            out["required"] = JsonArray(listOf(JsonPrimitive(PLACEHOLDER_REASON)))
        } else if (out["required"].stringList().isEmpty()) {
            if (PLACEHOLDER_UNDERSCORE !in props) {
                out["properties"] = JsonObject(
                    props + (PLACEHOLDER_UNDERSCORE to JsonObject(mapOf("type" to JsonPrimitive("boolean"))))
                )
            }
            out["required"] = JsonArray(listOf(JsonPrimitive(PLACEHOLDER_UNDERSCORE)))
        }
        return JsonObject(out)
    }

    /**
     * 去掉模型按占位填进参数里的 `reason`/`_`：只在原 schema 对应节点没有这个属性时删，
     * 工具拿到的参数与它声明的一致。
     */
    fun stripPlaceholderArgs(args: JsonElement, originalSchema: JsonElement?): JsonElement {
        val obj = args as? JsonObject ?: return args
        val schema = originalSchema as? JsonObject
        val declared = (schema?.get("properties") as? JsonObject).orEmpty()
        val out = LinkedHashMap<String, JsonElement>()
        obj.forEach { (key, value) ->
            if ((key == PLACEHOLDER_REASON || key == PLACEHOLDER_UNDERSCORE) && key !in declared) return@forEach
            out[key] = declared[key]?.let { child ->
                when (value) {
                    is JsonObject -> stripPlaceholderArgs(value, child)
                    is JsonArray -> JsonArray(value.map { stripPlaceholderArgs(it, (child as? JsonObject)?.get("items")) })
                    else -> value
                }
            } ?: value
        }
        return JsonObject(out)
    }

    // ---- 小工具 ----

    private fun mergeMissing(target: JsonObject?, incoming: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(target.orEmpty())
        incoming.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }

    private fun JsonObject.withHints(hints: List<String>): JsonObject {
        if (hints.isEmpty()) return this
        var description = this["description"].stringOrEmpty()
        hints.forEach { description = mergeHint(description, it) }
        return with("description", JsonPrimitive(description))
    }

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>(this).apply { put(key, value) })

    /** 同 CPA `mergeHint`：已有同样提示时不重复追加。 */
    internal fun mergeHint(existing: String, hint: String): String {
        if (existing.isEmpty()) return hint
        if (existing == hint || existing.startsWith("$hint (") || existing.contains("($hint)")) return existing
        return "$existing ($hint)"
    }

    private fun JsonObject.typeName(): String = this["type"].stringValue()

    private fun JsonElement?.stringOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonElement?.stringValue(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }

    private fun JsonElement?.stringList(): List<String> =
        (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
