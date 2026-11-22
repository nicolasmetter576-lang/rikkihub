package me.rerere.tavern.prompt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * JS 字符串语义的小工具。Kotlin 的 trim()/正则在空白集合、`$` 锚点、代理对上都与 JS 不同，
 * 凡是上游用 `.trim()`、`/regex/` 的地方都走这里，避免差分时出现无谓分歧。
 */

/** ECMAScript WhiteSpace + LineTerminator（`String.prototype.trim` 的裁剪集）。 */
internal fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\u0009', '\u000B', '\u000C', ' ', ' ', '﻿',
    '\n', '\r', ' ', ' ',
    ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
    ' ', ' ', '　' -> true
    else -> false
}

internal fun String.jsTrim(): String {
    var start = 0
    var end = length
    while (start < end && isJsWhitespace(this[start])) start++
    while (end > start && isJsWhitespace(this[end - 1])) end--
    return substring(start, end)
}

internal fun String.jsTrimStart(): String {
    var start = 0
    while (start < length && isJsWhitespace(this[start])) start++
    return substring(start)
}

/**
 * 对应 `s.replace(/(^\n)|(\n$)/g, '')`：去掉开头的一个换行，再去掉结尾的一个换行（ST:public/scripts/world-info.js:5270）。
 */
internal fun String.stripOneLeadingAndTrailingNewline(): String {
    var s = this
    if (s.startsWith("\n")) s = s.substring(1)
    if (s.endsWith("\n")) s = s.substring(0, s.length - 1)
    return s
}

/** ST:public/scripts/PromptManager.js:1343 `isValidName`：`/^[a-zA-Z0-9_]{1,64}$/`（JS 的 `$` 不匹配结尾换行前）。 */
internal fun isValidApiName(name: String): Boolean =
    name.length in 1..64 && name.all { isNameChar(it) }

/** ST:public/scripts/PromptManager.js:1349 `sanitizeName`：按 UTF-16 码元替换，代理对变成两个下划线，与 JS 一致。 */
internal fun sanitizeApiName(name: String): String {
    val sb = StringBuilder(name.length)
    for (c in name) sb.append(if (isNameChar(c)) c else '_')
    return if (sb.length > 64) sb.substring(0, 64) else sb.toString()
}

private fun isNameChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/** ST:public/scripts/utils.js:756 `stringFormat(format, value)`：只替换 `{0}`，其余 `{n}` 原样保留。 */
internal fun stringFormat1(format: String, value: String): String =
    Regex("\\{(\\d+)\\}").replace(format) { m -> if (m.groupValues[1] == "0") value else m.value }

// ---- JSON 读写小工具：类型对得上的字段进类型化属性，对不上的原样留在 extra，保证无损往返 ----

internal class JsonFieldReader(private val obj: JsonObject) {
    private val consumed = HashSet<String>()

    fun <T> typed(key: String, convert: (JsonElement) -> T?): T? {
        val v = obj[key] ?: return null
        val r = convert(v) ?: return null
        consumed.add(key)
        return r
    }

    fun string(key: String): String? = typed(key) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    fun bool(key: String): Boolean? = typed(key) { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull }
    fun int(key: String): Int? = typed(key) { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull }

    fun rest(): JsonObject = JsonObject(obj.filterKeys { it !in consumed })
    val keys: List<String> get() = obj.keys.toList()
}

/**
 * 按“原始键顺序 -> 类型化字段的规范顺序 -> extra 剩余键”输出。
 * 类型化值为 null 时回退到 extra 里的同名原值（比如解码时类型对不上的字段）。
 */
internal fun encodeObject(
    keyOrder: List<String>,
    typed: LinkedHashMap<String, JsonElement?>,
    extra: JsonObject,
): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    fun emit(k: String) {
        val v = typed[k] ?: extra[k]
        if (v != null) out[k] = v
    }
    for (k in keyOrder) emit(k)
    for (k in typed.keys) if (k !in out) emit(k)
    for ((k, v) in extra) if (k !in out) out[k] = v
    return JsonObject(out)
}

internal val EMPTY_OBJECT = JsonObject(emptyMap())
internal val JSON_NULL: JsonElement = JsonNull
