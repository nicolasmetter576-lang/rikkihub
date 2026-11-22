package me.rerere.tavern.variable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * JavaScript 值语义的最小子集：酒馆的变量与宏大量依赖 JS 的隐式类型转换
 * （`Number('007')`、`isNaN(Number(x))`、`JSON.parse` 失败后沿用旧值……），照搬才能和真酒馆一致。
 *
 * 值模型（Kotlin `Any?`）：[Undefined]、`null`（JS null）、`Boolean`、`Double`、`String`、
 * `MutableList<Any?>`（数组）、`MutableMap<String, Any?>`（对象，保持插入顺序）。
 */
object Undefined {
    override fun toString() = "undefined"
}

object Js {
    // ---- 空白与 trim（ECMAScript WhiteSpace + LineTerminator） ----
    fun isWhitespace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    fun trim(s: String): String = trimStart(trimEnd(s))
    fun trimStart(s: String): String {
        var i = 0
        while (i < s.length && isWhitespace(s[i])) i++
        return s.substring(i)
    }

    fun trimEnd(s: String): String {
        var j = s.length
        while (j > 0 && isWhitespace(s[j - 1])) j--
        return s.substring(0, j)
    }

    // ---- ToNumber ----
    private val decimalLiteral = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?$")

    /** StringToNumber：`Number('...')`。 */
    fun stringToNumber(raw: String): Double {
        val s = trim(raw)
        if (s.isEmpty()) return 0.0
        when (s) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (s.length > 2 && s[0] == '0') {
            val radix = when (s[1]) { 'x', 'X' -> 16; 'o', 'O' -> 8; 'b', 'B' -> 2; else -> 0 }
            if (radix != 0) {
                val digits = s.substring(2)
                if (digits.all { Character.digit(it, radix) >= 0 && it.code < 128 }) {
                    return java.math.BigInteger(digits, radix).toDouble()
                }
                return Double.NaN
            }
        }
        if (!decimalLiteral.matches(s)) return Double.NaN
        return s.toDouble()
    }

    /** ToNumber(value)。 */
    fun toNumber(v: Any?): Double = when (v) {
        null -> 0.0
        Undefined -> Double.NaN
        is Boolean -> if (v) 1.0 else 0.0
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is String -> stringToNumber(v)
        is List<*> -> stringToNumber(toStr(v))
        is Map<*, *> -> Double.NaN
        else -> Double.NaN
    }

    // ---- ToString ----
    /** Number::toString（ECMA-262 6.1.6.1.20）。 */
    fun numberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == 0.0) return "0"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d < 0) return "-" + numberToString(-d)
        var digits = ""
        var n = 0
        val exact = BigDecimal(d)
        for (p in 1..17) {
            val bd = exact.round(MathContext(p, RoundingMode.HALF_EVEN)).stripTrailingZeros()
            if (bd.toDouble() == d || p == 17) {
                digits = bd.unscaledValue().abs().toString()
                n = digits.length - bd.scale()
                break
            }
        }
        val k = digits.length
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val sign = if (e < 0) "-" else "+"
                val mant = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mant + "e" + sign + kotlin.math.abs(e)
            }
        }
    }

    /** 数组/对象 ToPrimitive 后的字符串（`String(x)`）。 */
    fun toStr(v: Any?): String = when (v) {
        null -> "null"
        Undefined -> "undefined"
        is Boolean -> v.toString()
        is Double -> numberToString(v)
        is Int -> v.toString()
        is Long -> v.toString()
        is String -> v
        is List<*> -> v.joinToString(",") { if (it == null || it === Undefined) "" else toStr(it) }
        is Map<*, *> -> "[object Object]"
        else -> v.toString()
    }

    /** 宏引擎 normalizeMacroResult 对非字符串的处理：null/undefined 为空串，对象与数组 JSON 化。 */
    fun normalize(v: Any?): String = when (v) {
        null, Undefined -> ""
        is List<*>, is Map<*, *> -> stringify(v) ?: ""
        else -> toStr(v)
    }

    /** 真值判断（`value || ''` 等）。 */
    fun truthy(v: Any?): Boolean = when (v) {
        null, Undefined -> false
        is Boolean -> v
        is Double -> !(v == 0.0 || v.isNaN())
        is Int -> v != 0
        is Long -> v != 0L
        is String -> v.isNotEmpty()
        else -> true
    }

    // ---- JSON ----
    /** JSON.parse；失败抛 [JsSyntaxError]。 */
    fun parse(text: String): Any? {
        val el = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw JsSyntaxError(e.message ?: "JSON.parse")
        }
        return fromJson(el)
    }

    fun fromJson(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.content == "true" -> true
            el.content == "false" -> false
            else -> stringToNumber(el.content)
        }
        is JsonArray -> el.mapTo(ArrayList()) { fromJson(it) }
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> el.forEach { (k, v) -> m[k] = fromJson(v) } }
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null, Undefined -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Double -> numberToJson(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { toJson(it) })
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        else -> JsonPrimitive(v.toString())
    }

    /** 整数值存成整数，其余存 Double（JSON 里不留 `5.0`）。 */
    fun numberToJson(d: Double): JsonElement =
        if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    /** JSON.stringify(value)；返回 null 表示 JS 里的 undefined（顶层）。 */
    fun stringify(v: Any?): String? {
        val sb = StringBuilder()
        return if (write(v, sb)) sb.toString() else null
    }

    private fun write(v: Any?, sb: StringBuilder): Boolean {
        when (v) {
            Undefined -> return false
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Double -> sb.append(if (v.isFinite()) numberToString(v) else "null")
            is Int, is Long -> sb.append(v.toString())
            is String -> quote(v, sb)
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    if (!write(x, sb)) sb.append("null")
                }
                sb.append(']')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in orderedKeys(v)) {
                    val mark = sb.length
                    if (!first) sb.append(',')
                    quote(k, sb)
                    sb.append(':')
                    if (write(x, sb)) first = false else sb.setLength(mark)
                }
                sb.append('}')
            }
            else -> return write(v.toString(), sb)
        }
        return true
    }

    /** 对象键顺序：数组下标形式的键先按数值升序，其余保持插入顺序（OrdinaryOwnPropertyKeys）。 */
    private fun orderedKeys(m: Map<*, *>): List<Pair<String, Any?>> {
        val entries = m.entries.map { it.key.toString() to it.value }
        val (idx, other) = entries.partition { isArrayIndex(it.first) }
        return idx.sortedBy { it.first.toLong() } + other
    }

    fun isArrayIndex(key: String): Boolean {
        if (key.isEmpty() || key.length > 10) return false
        if (key == "0") return true
        if (key[0] == '0' || !key.all { it in '0'..'9' }) return false
        return key.toLong() < 4294967295L
    }

    private fun quote(s: String, sb: StringBuilder) {
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
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    // ---- utils.js 的布尔字符串判断 ----
    /** ST:public/scripts/utils.js:1010 */
    fun isTrueBoolean(arg: String?): Boolean = arg?.let { trim(it).lowercase() } in setOf("on", "true", "1")

    /** ST:public/scripts/utils.js:1019 */
    fun isFalseBoolean(arg: String?): Boolean = arg?.let { trim(it).lowercase() } in setOf("off", "false", "0")

    /** ST:public/scripts/utils.js:522 getStringHash（murmur 变体，返回 2^53 以内的整数）。 */
    fun getStringHash(str: String, seed: Int = 0): Double {
        var h1 = 0xdeadbeef.toInt() xor seed
        var h2 = 0x41c6ce57 xor seed
        for (ch in str) {
            val c = ch.code
            h1 = (h1 xor c) * 2654435761L.toInt()
            h2 = (h2 xor c) * 1597334677
        }
        h1 = ((h1 xor (h1 ushr 16)) * 2246822507L.toInt()) xor ((h2 xor (h2 ushr 13)) * 3266489909L.toInt())
        h2 = ((h2 xor (h2 ushr 16)) * 2246822507L.toInt()) xor ((h1 xor (h1 ushr 13)) * 3266489909L.toInt())
        return 4294967296.0 * (2097151 and h2) + (h1.toLong() and 0xFFFFFFFFL).toDouble()
    }
}

class JsSyntaxError(message: String) : RuntimeException(message, null, false, false)
