package me.rerere.rikkihub.data.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 聊天变量与全局变量的查看/编辑规则。变量以 JSON 对象存放（`tavern_metadata.variables`、`Settings.tavern.globalVariables`），
 * 值按酒馆的存法：标量存原值，对象/数组存成 JSON 字符串。
 */

/** 变量值在列表里的文字：标量显示内容，null 显示为空，其余显示 JSON。 */
fun variableDisplay(value: JsonElement): String = when (value) {
    is JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

/**
 * 用户改了变量的文字后存成什么：原来是数字、新文字也是数字就保持数字（`{{addvar}}` 这类宏照样能算），
 * 原来是布尔、新文字是 true/false 就保持布尔；其余一律存成字符串。
 */
fun parseVariableValue(text: String, previous: JsonElement?): JsonElement {
    val prim = previous as? JsonPrimitive
    if (prim != null && !prim.isString && prim !is JsonNull) {
        val trimmed = text.trim()
        prim.content.let { old ->
            if (old == "true" || old == "false") {
                if (trimmed == "true") return JsonPrimitive(true)
                if (trimmed == "false") return JsonPrimitive(false)
            } else {
                trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
                trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return JsonPrimitive(it) }
            }
        }
    }
    return JsonPrimitive(text)
}

/** 设置（或新增）一个变量。名字去掉首尾空白，空名字不动。 */
fun JsonObject.withVariable(name: String, text: String): JsonObject {
    val key = name.trim()
    if (key.isEmpty()) return this
    return JsonObject(LinkedHashMap(this).apply { put(key, parseVariableValue(text, this[key])) })
}

fun JsonObject.withoutVariable(name: String): JsonObject = JsonObject(this - name)
