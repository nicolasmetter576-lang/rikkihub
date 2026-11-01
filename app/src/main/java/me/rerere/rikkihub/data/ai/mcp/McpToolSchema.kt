package me.rerere.rikkihub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.core.InputSchema
import java.net.URLDecoder

private const val REF = "\$ref"

/**
 * 单个工具 Schema 允许展开的 `$ref` 次数上限。
 * 钻石形 `$defs`（每层引用下一层两次）会指数膨胀，必须限制总展开量。
 */
private const val MAX_REF_EXPANSIONS = 512

/** `$ref` 链式跳转（A ref B、B ref C …）的最大深度，防止病态定义拖垮转换。 */
private const val MAX_REF_DEPTH = 64

/**
 * 将 MCP 工具的 inputSchema 转成内部 [InputSchema]。
 *
 * [InputSchema.Obj] 不携带 `$defs`，且部分 provider（如 Gemini）不接受 `$ref`，
 * 因此在这里把文档内引用（`#/...`）内联展开，避免发出悬空引用导致整个请求被拒绝。
 * 外部 URL、URI anchor（`#anchor`）等无法解析的引用一律退化为空对象（不限制类型），
 * 不做任何网络解析；展开超过 [MAX_REF_EXPANSIONS] / [MAX_REF_DEPTH] 时同样明确退化，
 * 不在被处理的 Schema 节点留下悬空引用；同名属性和字面量数据不属于引用关键字。
 *
 * 这不是 JSON Schema 校验器：展开是保守的 best-effort，`$ref` 同级关键字覆盖被引用
 * 定义（与现有内部 Schema 的合并行为一致），循环引用在回到自身的位置只保留 `type`。
 */
internal fun ToolSchema.toSchema(): InputSchema {
    val properties = properties ?: JsonObject(emptyMap())
    val root = JsonObject(buildMap {
        // fork SDK 接受任意 JSON Schema，根 type 可能缺省或是数组（如 ["object","null"]）
        put("type", type ?: JsonPrimitive("object"))
        put("properties", properties)
        required?.let { put("required", JsonArray(it.map(::JsonPrimitive))) }
        defs?.let { put("\$defs", it) }
    })
    val budget = RefBudget()
    return InputSchema.Obj(
        properties = inlineSchemaMap(properties, root, emptySet(), 0, budget),
        required = required,
    )
}

private class RefBudget(var remaining: Int = MAX_REF_EXPANSIONS) {
    /** 消耗一次展开额度；额度耗尽返回 false。 */
    fun consume(): Boolean {
        if (remaining <= 0) return false
        remaining--
        return true
    }
}

// 值为子 Schema（或子 Schema 数组）的关键字
private val SCHEMA_KEYWORDS = setOf(
    "items", "prefixItems", "additionalItems", "unevaluatedItems", "contains",
    "additionalProperties", "unevaluatedProperties", "propertyNames",
    "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "contentSchema",
)

// 值为「名称 -> 子 Schema」映射的关键字
private val SCHEMA_MAP_KEYWORDS = setOf(
    "properties", "patternProperties", "dependentSchemas", "\$defs", "definitions",
)

// 值为「名称 -> 子 Schema 或属性名数组」的 legacy 关键字（draft-07 dependencies）：
// Schema 值照常展开；值为字符串数组时表示属性依赖，是数据而非 Schema，原样保留。
private val SCHEMA_MIXED_MAP_KEYWORDS = setOf("dependencies")

private fun inlineSchemaMap(
    schemas: JsonObject,
    root: JsonObject,
    resolving: Set<String>,
    depth: Int,
    budget: RefBudget,
): JsonObject =
    JsonObject(schemas.mapValues { inlineSchema(it.value, root, resolving, depth, budget) })

private fun inlineMixedSchemaMap(
    schemas: JsonObject,
    root: JsonObject,
    resolving: Set<String>,
    depth: Int,
    budget: RefBudget,
): JsonObject =
    JsonObject(schemas.mapValues { (_, value) ->
        if (value is JsonObject) inlineSchema(value, root, resolving, depth, budget) else value
    })

/**
 * 只沿承载子 Schema 的关键字向下遍历；`enum`、`const`、`default`、`examples` 等字面量数据
 * 即使含有 `$ref` 字段也原样保留，不做广义递归扫描。
 */
private fun inlineSchema(
    schema: JsonElement,
    root: JsonObject,
    resolving: Set<String>,
    depth: Int,
    budget: RefBudget,
): JsonElement {
    if (schema !is JsonObject) return schema
    val ref = (schema[REF] as? JsonPrimitive)?.takeIf { it.isString }?.content
    // 非字符串 $ref（数字/对象/布尔）不是合法引用：同样移除，避免把非法 $ref 发给 provider 导致整请求 400
    val inlined = (if (schema.containsKey(REF)) schema - REF else schema).mapValues { (key, value) ->
        when {
            key in SCHEMA_KEYWORDS && value is JsonArray ->
                JsonArray(value.map { inlineSchema(it, root, resolving, depth, budget) })

            key in SCHEMA_KEYWORDS -> inlineSchema(value, root, resolving, depth, budget)
            key in SCHEMA_MAP_KEYWORDS && value is JsonObject ->
                inlineSchemaMap(value, root, resolving, depth, budget)

            key in SCHEMA_MIXED_MAP_KEYWORDS && value is JsonObject ->
                inlineMixedSchemaMap(value, root, resolving, depth, budget)

            else -> value
        }
    }
    if (ref == null) return JsonObject(inlined)

    val target = root.resolvePointer(ref)
    // false 是永不匹配的 Schema；不能让同级 not/allOf 覆盖成可匹配约束。
    if (target is JsonPrimitive && !target.isString && target.booleanOrNull == false) {
        return if (inlined.isEmpty()) JsonPrimitive(false) else JsonObject(
            mapOf("allOf" to JsonArray(listOf(JsonPrimitive(false), JsonObject(inlined))))
        )
    }
    val expanded = when {
        // 无法解析的引用（外部 URL、anchor、坏指针、指针不存在）退化为不限制类型
        target == null -> emptyMap()
        // 额度耗尽：明确退化为空对象，绝不把没展开的 $ref 留成悬空引用
        depth >= MAX_REF_DEPTH || !budget.consume() -> emptyMap()
        // 循环引用无法完全展开，在回到自身的位置只保留类型
        ref in resolving -> (target as? JsonObject)?.filterKeys { it == "type" } ?: emptyMap()
        target is JsonObject -> inlineSchema(target, root, resolving + ref, depth + 1, budget) as JsonObject
        // true 是无约束 Schema；其他非对象目标不是有效定义。
        else -> emptyMap()
    }
    // 与 $ref 同级的关键字（description、default 等）覆盖被引用的定义
    return JsonObject(expanded + inlined)
}

/**
 * 解析 `$ref` 的文档内 JSON Pointer（RFC 6901 fragment 形式：`#` 或 `#/...`）。
 *
 * 只接受严格指针语法：`#anchor` 这类 URI fragment anchor 不是指针，返回 null；
 * 其余（外部 URL、非 `#` 开头）同样返回 null。指向布尔 Schema 的引用会返回
 * JsonPrimitive(true/false)，由调用方处理。
 */
private fun JsonObject.resolvePointer(ref: String): JsonElement? {
    if (!ref.startsWith("#")) return null
    // RFC 6901 §6：fragment 是整体 percent 编码的 JSON Pointer，先整体解码再判定/分隔
    val pointer = percentDecode(ref.substring(1)) ?: return null
    if (pointer.isEmpty()) return this // "#" 指向根
    if (!pointer.startsWith("/")) return null // "#anchor" 是 URI fragment，不是 JSON Pointer
    // 解码后按 '/' 分隔（%2F 解码出的 '/' 是分隔符；token 内字面斜杠用 ~1）
    val rawTokens = pointer.drop(1).split("/")
    if (rawTokens.any { Regex("~(?![01])").containsMatchIn(it) }) return null
    val tokens = rawTokens.map { it.replace("~1", "/").replace("~0", "~") }
    return tokens.fold<String, JsonElement?>(this) { current, token ->
        when (current) {
            is JsonObject -> current[token]
            // RFC 6901 数组下标不允许符号或前导零。
            is JsonArray -> token.takeIf { Regex("0|[1-9][0-9]*").matches(it) }
                ?.toIntOrNull()?.let(current::getOrNull)
            else -> null
        }
    }
}

/** URI fragment 不把 + 当空格；保留 Unicode 码点，非法百分号序列不解析为别的定义。 */
private fun percentDecode(text: String): String? = try {
    URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8.name())
} catch (_: IllegalArgumentException) {
    null
}
