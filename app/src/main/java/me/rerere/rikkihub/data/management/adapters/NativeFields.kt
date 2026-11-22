package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One field of an object as the creator assistant sees it: the SillyTavern-native name, a type, and a
 * note. The same list drives strict validation of what the model writes, the `creator_describe`
 * field lines, and the completeness test (every key the native object can hold has a field here).
 *
 * [type] is `string`, `boolean`, `integer`, `array<string>`, `array<integer>` or `object`, with
 * `|null` appended when null is allowed. [min]/[max] bound an integer, a string's length or an
 * array's item count; [values] restricts a string (or the text of an integer / array item).
 */
internal class NativeField(
    val name: String,
    val type: String,
    val note: String = "",
    val min: Long? = null,
    val max: Long? = null,
    val values: Set<String>? = null,
) {
    val nullable: Boolean get() = type.endsWith("|null")
    val base: String get() = type.removeSuffix("|null")
}

internal object NativeFields {
    const val MAX_TEXT = 64 * 1024L
    private const val MAX_ITEM = 4096

    /** Checks every field present in [json] against [fields]; `id` is allowed and ignored. */
    fun validate(label: String, json: JsonObject, fields: List<NativeField>) {
        val byName = fields.associateBy { it.name }
        json.forEach { (name, value) ->
            if (name == "id") return@forEach
            val field = byName[name] ?: rejectForbidden("$label: unknown field '$name'; fields: ${fields.map { it.name }}")
            check(label, field, value)
        }
    }

    private fun check(label: String, f: NativeField, value: JsonElement) {
        val where = "$label.${f.name}"
        if (value is JsonNull) {
            require(f.nullable) { "$where must not be null (type ${f.type})" }
            return
        }
        when (f.base) {
            "string" -> {
                val p = value as? JsonPrimitive
                require(p != null && p.isString) { "$where must be a string" }
                require(p.content.length <= (f.max ?: MAX_TEXT)) { "$where must be at most ${f.max ?: MAX_TEXT} characters" }
                f.values?.let { require(p.content in it) { "$where must be one of ${it.toList()}" } }
            }
            "boolean" -> {
                val p = value as? JsonPrimitive
                require(p != null && !p.isString && p.booleanOrNull != null) { "$where must be true or false" }
            }
            "integer" -> integer(where, f, value)
            "array<string>" -> {
                val a = value as? JsonArray
                require(a != null) { "$where must be an array of strings" }
                require(a.size <= (f.max ?: 1024)) { "$where holds at most ${f.max ?: 1024} items" }
                a.forEach { item ->
                    val p = item as? JsonPrimitive
                    require(p != null && p.isString) { "$where must be an array of strings" }
                    require(p.content.length <= MAX_ITEM) { "$where: each item at most $MAX_ITEM characters" }
                    f.values?.let { require(p.content in it) { "$where items must be one of ${it.toList()}" } }
                }
            }
            "array<integer>" -> {
                val a = value as? JsonArray
                require(a != null) { "$where must be an array of integers" }
                require(a.size <= (f.max ?: 64)) { "$where holds at most ${f.max ?: 64} items" }
                a.forEach { item ->
                    val p = item as? JsonPrimitive
                    require(p != null && !p.isString && p.longOrNull != null) { "$where must be an array of integers" }
                    f.values?.let { require(p.content in it) { "$where items must be one of ${it.toList()}" } }
                }
            }
            "object" -> require(value is JsonObject) { "$where must be an object" }
            else -> error("Unknown field type ${f.type}")
        }
    }

    private fun integer(where: String, f: NativeField, value: JsonElement) {
        val p = value as? JsonPrimitive
        val n = p?.takeIf { !it.isString }?.longOrNull
        require(n != null) { "$where must be an integer" }
        val lo = f.min ?: Int.MIN_VALUE.toLong()
        val hi = f.max ?: Int.MAX_VALUE.toLong()
        require(n in lo..hi) { "$where must be $lo..$hi" }
        f.values?.let { require(n.toString() in it) { "$where must be one of ${it.toList()}" } }
    }

    /** String values that name a [NativeField.values] choice are written the stored way (`USER` → `user`); everything else passes through. */
    fun normalize(fields: List<NativeField>, json: Map<String, JsonElement>): Map<String, JsonElement> {
        val byName = fields.filter { it.values != null && (it.base == "string" || it.base == "array<string>") }.associateBy { it.name }
        fun canon(f: NativeField, e: JsonElement): JsonElement {
            val p = e as? JsonPrimitive ?: return e
            if (!p.isString) return e
            val choice = f.values?.firstOrNull { it.equals(p.content.trim(), ignoreCase = true) } ?: return e
            return JsonPrimitive(choice)
        }
        return json.mapValues { (key, value) ->
            val f = byName[key] ?: return@mapValues value
            if (value is JsonArray) JsonArray(value.map { canon(f, it) }) else canon(f, value)
        }
    }

    /** "name: type = default — note" per field, in declaration order. */
    fun lines(fields: List<NativeField>, defaults: JsonObject): List<String> = fields.map { f ->
        val default = defaults[f.name]?.toString()?.takeIf { it.length <= 48 }
        buildString {
            append(f.name).append(": ").append(f.type)
            if (default != null) append(" = ").append(default)
            if (f.note.isNotBlank()) append(" — ").append(f.note)
        }
    }
}
