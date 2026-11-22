package me.rerere.tavern.worldinfo

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 世界书里有几处行为依赖 JavaScript 的字符串/JSON 细节（trim 的空白集合、`\W` 的范围、
 * 对象键顺序、`JSON.stringify` 的数字与转义格式、`getStringHash`）。条目哈希是计时效果的键，
 * 要和真酒馆写进聊天元数据里的值一致，所以这里按 JS 的规则重做一遍，不依赖 Kotlin/JDK 的近似行为。
 */

/** JS `\s` / `String.prototype.trim` 的空白集合（ECMAScript WhiteSpace + LineTerminator）。 */
internal fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ',
    ' ', ' ', ' ', ' ', '　', '﻿',
    -> true

    else -> c in ' '..' '
}

/** JS `String.prototype.trim`。 */
internal fun jsTrim(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isJsWhitespace(s[start])) start++
    while (end > start && isJsWhitespace(s[end - 1])) end--
    return if (start == 0 && end == s.length) s else s.substring(start, end)
}

/** JS `\w`（不带 u 标志）：`[A-Za-z0-9_]`。 */
internal fun isJsWordChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/** `str.split(/\s+/).length > 1`：去掉首尾空白后是否还含有空白。 */
internal fun hasInnerJsWhitespaceSplit(s: String): Boolean {
    // `split(/\s+/)` 在存在任意空白串时产出至少两段（首尾空白会产出空段，也算两段）。
    for (c in s) if (isJsWhitespace(c)) return true
    return false
}

/**
 * `ST:public/scripts/utils.js:522` getStringHash(str, seed = 0)。
 * 返回值与 JS 一样是不超过 2^53 的非负整数。
 */
internal fun stringHash(str: String, seed: Int = 0): Long {
    var h1 = 0xdeadbeef.toInt() xor seed
    var h2 = 0x41c6ce57 xor seed
    for (ch in str) {
        val c = ch.code
        h1 = (h1 xor c) * 2654435761L.toInt()
        h2 = (h2 xor c) * 1597334677
    }
    h1 = ((h1 xor (h1 ushr 16)) * 2246822507L.toInt()) xor ((h2 xor (h2 ushr 13)) * 3266489909L.toInt())
    h2 = ((h2 xor (h2 ushr 16)) * 2246822507L.toInt()) xor ((h1 xor (h1 ushr 13)) * 3266489909L.toInt())
    return ((h2 and 2097151).toLong() shl 32) + (h1.toLong() and 0xFFFFFFFFL)
}

/** JS 对象自有键的遍历顺序：数组下标形式的键按数值升序在前，其余按插入顺序。 */
internal fun jsKeyOrder(keys: Collection<String>): List<String> {
    var hasIndex = false
    for (k in keys) if (isArrayIndexKey(k)) {
        hasIndex = true
        break
    }
    if (!hasIndex) return keys.toList()
    val indices = keys.filter { isArrayIndexKey(it) }.sortedBy { it.toLong() }
    val others = keys.filter { !isArrayIndexKey(it) }
    return indices + others
}

private fun isArrayIndexKey(k: String): Boolean {
    if (k.isEmpty() || k.length > 10) return false
    if (k == "0") return true
    if (k[0] !in '1'..'9') return false
    for (c in k) if (c !in '0'..'9') return false
    return k.toLong() < 4294967295L
}

/** `Number.prototype.toString()`（十进制）。 */
internal fun jsNumberToString(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d == 0.0) return "0"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    val sign = if (d < 0) "-" else ""
    val bd = BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
    val digits = bd.unscaledValue().toString()
    val k = digits.length
    val n = k - bd.scale()
    val body = when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val e = n - 1
            val exp = (if (e < 0) "-" else "+") + Math.abs(e)
            if (k == 1) digits + "e" + exp else digits[0] + "." + digits.substring(1) + "e" + exp
        }
    }
    return sign + body
}

/** `JSON.stringify(value)`（无缩进）。 */
internal fun jsStringify(e: JsonElement): String = StringBuilder().also { jsStringify(e, it) }.toString()

internal fun jsStringify(e: JsonElement, sb: StringBuilder) {
    when (e) {
        is JsonNull -> sb.append("null")
        is JsonPrimitive -> when {
            e.isString -> jsQuote(e.content, sb)
            e.content == "true" || e.content == "false" -> sb.append(e.content)
            else -> sb.append(jsNumberToString(e.content.toDouble()))
        }

        is JsonArray -> {
            sb.append('[')
            e.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                jsStringify(v, sb)
            }
            sb.append(']')
        }

        is JsonObject -> {
            sb.append('{')
            var first = true
            for (k in jsKeyOrder(e.keys)) {
                if (!first) sb.append(',')
                first = false
                jsQuote(k, sb)
                sb.append(':')
                jsStringify(e.getValue(k), sb)
            }
            sb.append('}')
        }
    }
}

/** `JSON.stringify(string)`：良构输出（孤立代理项转 \uXXXX，U+2028/2029 不转义）。 */
internal fun jsQuote(s: String, sb: StringBuilder) {
    sb.append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
            Character.isHighSurrogate(c) -> {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    sb.append(c).append(s[i + 1])
                    i++
                } else {
                    sb.append("\\u").append(String.format("%04x", c.code))
                }
            }

            Character.isLowSurrogate(c) -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
        i++
    }
    sb.append('"')
}

/** JS 的 ToBoolean（真值判断）。 */
internal fun JsonElement?.isJsTruthy(): Boolean = when (this) {
    null, is JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        content == "true" -> true
        content == "false" -> false
        else -> content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false
    }

    is JsonArray, is JsonObject -> true
}

/** JS 的 ToNumber（只处理标量；数组/对象按 NaN）。 */
internal fun JsonElement?.toJsNumber(): Double = when (this) {
    null -> Double.NaN
    is JsonNull -> 0.0
    is JsonPrimitive -> when {
        isString -> {
            val t = jsTrim(content)
            if (t.isEmpty()) 0.0 else t.toDoubleOrNull() ?: Double.NaN
        }

        content == "true" -> 1.0
        content == "false" -> 0.0
        else -> content.toDoubleOrNull() ?: Double.NaN
    }

    else -> Double.NaN
}
