package me.rerere.rikkihub.data.datastore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.utils.JsonInstant

/**
 * Field-scoped optimistic concurrency for legacy whole-snapshot callers.
 *
 * The caller's exact base snapshot is recovered by its read revision, so the
 * changed units are measured (base → next), never guessed against the latest
 * value. Every changed unit must still equal its base value in the latest
 * durable state, otherwise the write is rejected as a conflict:
 *
 * - keyed object lists (every element is an object with a unique string `id`)
 *   compare per element; a reorder additionally requires the latest id order to
 *   still equal the base order;
 * - every other top-level field compares as one value.
 *
 * Units the caller did not touch keep their latest value, so two disjoint edits
 * made from the same snapshot (e.g. two text fields committing on dispose) both
 * land, while two edits of the same object/field never silently overwrite.
 */
internal object SettingsFieldMerge {
    fun merge(base: Settings, next: Settings, latest: Settings): Settings {
        val b = encode(base)
        val n = encode(next)
        val l = encode(latest)
        val result = l.toMutableMap()
        for (key in b.keys + n.keys) {
            val bv = b[key] ?: JsonNull
            val nv = n[key] ?: JsonNull
            if (bv == nv) continue
            val lv = l[key] ?: JsonNull
            result[key] = if (isKeyedList(bv) && isKeyedList(nv) && isKeyedList(lv)) {
                mergeKeyedList(key, bv as JsonArray, nv as JsonArray, lv as JsonArray)
            } else {
                if (lv != bv) throw MutationConflictException("Settings field changed concurrently: $key")
                nv
            }
        }
        return JsonInstant.decodeFromJsonElement(Settings.serializer(), JsonObject(result))
            .copy(readRevision = latest.readRevision, readEpoch = latest.readEpoch)
    }

    private fun encode(settings: Settings): JsonObject =
        JsonInstant.encodeToJsonElement(Settings.serializer(), settings) as JsonObject

    private fun idOf(element: JsonElement): String? =
        ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isKeyedList(element: JsonElement): Boolean {
        if (element !is JsonArray) return false
        val ids = element.map { idOf(it) ?: return false }
        return ids.toSet().size == ids.size
    }

    private fun mergeKeyedList(field: String, base: JsonArray, next: JsonArray, latest: JsonArray): JsonArray {
        val baseById = base.associateBy { idOf(it)!! }
        val nextById = next.associateBy { idOf(it)!! }
        val latestById = latest.associateBy { idOf(it)!! }
        val merged = latestById.toMutableMap()
        for (id in baseById.keys + nextById.keys) {
            val bv = baseById[id]
            val nv = nextById[id]
            if (bv == nv) continue
            if (latestById[id] != bv) {
                throw MutationConflictException("Settings item changed concurrently: $field/$id")
            }
            if (nv == null) merged.remove(id) else merged[id] = nv
        }
        val baseOrder = base.map { idOf(it)!! }
        val nextOrder = next.map { idOf(it)!! }
        val reordered = nextOrder.filter { it in baseById } != baseOrder.filter { it in nextById }
        val order: List<String> = if (reordered) {
            if (latest.map { idOf(it)!! } != baseOrder) {
                throw MutationConflictException("Settings order changed concurrently: $field")
            }
            nextOrder
        } else {
            // Keep the latest order; items the caller added follow their predecessor in next.
            val out = latest.map { idOf(it)!! }.filter { it in merged }.toMutableList()
            nextOrder.forEachIndexed { index, id ->
                if (id !in out && id in merged) {
                    val anchor = nextOrder.subList(0, index).lastOrNull { it in out }
                    out.add(anchor?.let { out.indexOf(it) + 1 } ?: 0, id)
                }
            }
            out
        }
        return JsonArray(order.mapNotNull { merged[it] })
    }

    /** Test helper: the exact units that differ between two snapshots. */
    fun changedFields(base: Settings, next: Settings): Set<String> {
        val b = encode(base)
        val n = encode(next)
        return (b.keys + n.keys).filter { b[it] != n[it] }.toSet()
    }
}

/**
 * 把一次编辑（[base] → [edited]）改到的顶层字段套到 [latest] 上，没改到的字段保持最新值。
 *
 * 用于页面整份提交一个对象（如助手详情页）的场景：快速输入时页面手里的 [base] 可能比
 * [latest] 旧，按字段合并既不会把别处刚写入的其他字段改回旧值，也不会因为冲突丢掉这次输入。
 * 同一字段两边都改过时，以这次编辑为准。
 */
internal fun <T> mergeObjectEdits(serializer: KSerializer<T>, base: T, edited: T, latest: T): T {
    if (base == edited) return latest
    val b = JsonInstant.encodeToJsonElement(serializer, base) as JsonObject
    val e = JsonInstant.encodeToJsonElement(serializer, edited) as JsonObject
    val l = JsonInstant.encodeToJsonElement(serializer, latest) as JsonObject
    val result = l.toMutableMap()
    for (key in b.keys + e.keys) {
        val bv = b[key] ?: JsonNull
        val ev = e[key] ?: JsonNull
        if (bv == ev) continue
        if (key in e) result[key] = ev else result.remove(key)
    }
    return JsonInstant.decodeFromJsonElement(serializer, JsonObject(result))
}
