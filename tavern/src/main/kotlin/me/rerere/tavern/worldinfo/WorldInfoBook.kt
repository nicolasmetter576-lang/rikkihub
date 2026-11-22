package me.rerere.tavern.worldinfo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 酒馆世界书文件：`entries`（键为 uid 的对象）加上其余顶层字段。
 * 未知的顶层字段（`extensions`、`originalData` 等）进 [extra]；[keyOrder] 记录解码时的顶层键顺序。
 */
data class WorldInfoBook(
    /** 按 `Object.keys(data.entries)` 的顺序（数字键升序）。 */
    val entries: List<WorldInfoEntry> = emptyList(),
    val name: String? = null,
    val description: String? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val keyOrder: List<String>? = null,
) {
    fun toJson(): JsonObject = WorldInfoCodec.encodeBook(this)

    companion object {
        fun fromJson(element: JsonElement): WorldInfoBook = WorldInfoCodec.decodeBook(element)
        fun fromJson(text: String): WorldInfoBook = WorldInfoCodec.decodeBook(text)
    }
}
