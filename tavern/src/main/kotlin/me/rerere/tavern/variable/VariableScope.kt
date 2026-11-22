package me.rerere.tavern.variable

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 一组变量（聊天变量或全局变量）。语义照搬 ST:public/scripts/variables.js:22-232
 * （getLocalVariable/setLocalVariable/addLocalVariable/…；全局版本逻辑相同）。
 *
 * 存法与酒馆一致：标量存原值（字符串或数字），对象/数组存成 JSON 字符串。值用 [JsonElement]；
 * 本类是显式的可变容器，调用方在求值前后用 [fromJson]/[toJson] 与持久化层交换，不使用任何全局状态。
 *
 * 偏离：JS 里 `variables[name]` 对 `constructor`、`__proto__` 之类原型链上的名字会命中 Object.prototype，
 * 这里当作普通键（不存在）。
 */
class VariableScope(initial: Map<String, JsonElement> = emptyMap()) {
    private val map = LinkedHashMap<String, Any?>().also { m -> initial.forEach { (k, v) -> m[k] = Js.fromJson(v) } }

    /** 自创建/上次 [markClean] 以来是否被写过（调用方用来决定是否持久化）。 */
    var dirty: Boolean = false
        private set

    fun markClean() {
        dirty = false
    }

    fun toJson(): JsonObject = JsonObject(map.entries.associate { (k, v) -> k to Js.toJson(v) })

    val names: Set<String> get() = map.keys.toSet()

    // ST:variables.js:422 existsLocalVariable
    fun has(name: String): Boolean = map.containsKey(name)

    private fun raw(name: String): Any? = if (map.containsKey(name)) map[name] else Undefined

    /**
     * getLocalVariable / getGlobalVariable。返回 JS 值：数值字符串变成 Double，空串/非数字保持字符串。
     * [index] 对应 `args.index`（setvarkey/getvarkey 的键或下标）。
     */
    fun get(name: String, index: String? = null): Any? {
        var v: Any? = raw(name)
        if (index != null) {
            try {
                v = Js.parse(Js.toStr(v))
                val numIndex = Js.stringToNumber(index)
                v = if (numIndex.isNaN()) member(v, index) else member(v, Js.numberToString(numIndex))
                if (v == null || v is List<*> || v is Map<*, *>) v = Js.stringify(v)
            } catch (_: JsSyntaxError) {
                // that didn't work
            } catch (_: JsTypeError) {
                // that didn't work（v 保持已解析的值）
            }
        }
        val isEmptyString = v is String && Js.trim(v).isEmpty()
        return if (isEmptyString || Js.toNumber(v).isNaN()) (if (Js.truthy(v)) v else "") else Js.toNumber(v)
    }

    /** 取成员：对象按键，数组/字符串按下标；null 抛 TypeError，基本类型返回 undefined。 */
    private fun member(v: Any?, key: String): Any? = when (v) {
        null, Undefined -> throw JsTypeError()
        is Map<*, *> -> if (v.containsKey(key)) v[key] else Undefined
        is List<*> -> if (Js.isArrayIndex(key) && key.toLong() < v.size) v[key.toInt()] else if (key == "length") v.size.toDouble() else Undefined
        is String -> if (Js.isArrayIndex(key) && key.toLong() < v.length) v[key.toInt()].toString() else if (key == "length") v.length.toDouble() else Undefined
        else -> Undefined
    }

    /** 同 [get]，但结果按宏引擎的规则转成字符串（`normalize`）。 */
    fun getString(name: String, index: String? = null): String = Js.normalize(get(name, index))

    /** setLocalVariable / setGlobalVariable。返回 value。 */
    fun set(name: String, value: Any?, index: String? = null): Any? {
        if (name.isEmpty()) throw IllegalArgumentException("Variable name cannot be empty or undefined.")
        if (index != null) {
            try {
                val existing = map[name] // JS: variables[name] ?? 'null'
                val numIndex = Js.stringToNumber(index)
                var parsed: Any? = Js.parse(if (existing == null) "null" else Js.toStr(existing))
                if (numIndex.isNaN()) {
                    if (parsed == null) parsed = LinkedHashMap<String, Any?>()
                    assignMember(parsed, index, value)
                } else {
                    if (parsed == null) parsed = ArrayList<Any?>()
                    assignMember(parsed, Js.numberToString(numIndex), value)
                }
                map[name] = Js.stringify(parsed)
                dirty = true
            } catch (_: JsSyntaxError) {
                // that didn't work
            } catch (_: JsTypeError) {
                // that didn't work
            }
        } else {
            map[name] = value
            dirty = true
        }
        return value
    }

    /** 对解析出的 JSON 值赋成员；严格模式下对基本类型赋值抛 TypeError。 */
    @Suppress("UNCHECKED_CAST")
    private fun assignMember(target: Any?, key: String, value: Any?) {
        when (target) {
            is MutableMap<*, *> -> (target as MutableMap<String, Any?>)[key] = value
            is MutableList<*> -> {
                val list = target as MutableList<Any?>
                if (Js.isArrayIndex(key)) {
                    val i = key.toLong()
                    if (i > MAX_ARRAY_GROWTH) throw JsTypeError() // 偏离：JS 会得到稀疏数组，这里拒绝巨大下标
                    while (list.size <= i) list.add(null)
                    list[i.toInt()] = value
                }
                // 非下标键只是数组对象上的属性，JSON.stringify 不会输出，等于无操作
            }
            else -> throw JsTypeError()
        }
    }

    /** deleteLocalVariable / deleteGlobalVariable（不存在时只告警，返回空串）。 */
    fun del(name: String): String {
        if (!has(name)) return ""
        map.remove(name)
        dirty = true
        return ""
    }

    /** addLocalVariable / addGlobalVariable。返回 JS 值（数字、字符串或数组）。 */
    fun add(name: String, value: Any?): Any? {
        val got = get(name)
        val currentValue: Any? = if (Js.truthy(got)) got else 0.0
        try {
            val parsed = Js.parse(Js.toStr(currentValue))
            if (parsed is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                (parsed as MutableList<Any?>).add(value)
                set(name, Js.stringify(parsed))
                return parsed
            }
        } catch (_: JsSyntaxError) {
            // ignore non-array values
        }
        val increment = Js.toNumber(value)
        if (increment.isNaN() || Js.toNumber(currentValue).isNaN()) {
            val stringValue = (if (Js.truthy(currentValue)) Js.toStr(currentValue) else "") + Js.toStr(value)
            set(name, stringValue)
            return stringValue
        }
        val newValue = Js.toNumber(currentValue) + increment
        if (newValue.isNaN()) return ""
        set(name, newValue)
        return newValue
    }

    fun inc(name: String): Any? = add(name, 1.0)
    fun dec(name: String): Any? = add(name, -1.0)

    private class JsTypeError : RuntimeException(null, null, false, false)

    companion object {
        private const val MAX_ARRAY_GROWTH = 10_000_000L

        fun fromJson(obj: JsonObject?): VariableScope = VariableScope(obj ?: JsonObject(emptyMap()))
    }
}

/** 聊天变量与全局变量，对应 `SillyTavern.getContext().variables.{local,global}`。 */
class Variables(val local: VariableScope = VariableScope(), val global: VariableScope = VariableScope())
