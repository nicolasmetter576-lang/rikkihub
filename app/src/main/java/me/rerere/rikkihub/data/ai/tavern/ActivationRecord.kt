package me.rerere.rikkihub.data.ai.tavern

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** 记录里的一条：本回合激活了哪本书的哪个条目、落在哪个位置。 */
data class RecordedActivation(
    val bookId: String,
    val bookName: String,
    val uid: Int,
    val title: String,
    val position: Int,
    val order: Int,
)

/** 一条回复的世界书激活记录；[more] 是因为条数上限没有存下的条数。 */
data class ActivationSummary(val entries: List<RecordedActivation>, val more: Int) {
    val total: Int get() = entries.size + more
}

/**
 * 世界书激活记录：每回合激活了哪些条目，存在这条回复第一个文字 part 的 `metadata` 里（随消息走，删分支、重新生成都不用另外清理）。
 * 生成过程中消息每个 chunk 都会被循环重建，所以由 [attach] 在每个 chunk 上幂等地补上。
 */
object ActivationRecord {
    const val KEY = "tavernActivations"
    const val MAX_ENTRIES = 64

    fun encode(activations: List<WorldInfoActivation>): JsonObject? {
        if (activations.isEmpty()) return null
        val kept = activations.take(MAX_ENTRIES)
        return buildJsonObject {
            put("v", 1)
            put("more", activations.size - kept.size)
            put("entries", JsonArray(kept.map { a ->
                buildJsonObject {
                    put("book", a.bookId)
                    put("bookName", a.bookName)
                    put("uid", a.entry.uid)
                    put("title", a.entry.comment)
                    put("position", a.entry.position)
                    put("order", a.entry.order)
                }
            }))
        }
    }

    fun decode(record: JsonElement?): ActivationSummary? {
        val obj = record as? JsonObject ?: return null
        val entries = (obj["entries"] as? JsonArray)?.mapNotNull { element ->
            val e = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (e[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun int(key: String) = (e[key] as? JsonPrimitive)?.intOrNull ?: 0
            RecordedActivation(text("book"), text("bookName"), int("uid"), text("title"), int("position"), int("order"))
        } ?: return null
        return ActivationSummary(entries, ((obj["more"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0))
    }

    /** 这条消息带的激活记录；没有（或不是回复）返回 null。 */
    fun of(message: UIMessage): ActivationSummary? =
        message.parts.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull { decode(it.metadata?.get(KEY)) }

    /**
     * 给本次生成的回复补上记录：最后一条消息是助手消息、不是生成开始前就有的（[knownIds]）、已经有文字 part，
     * 才写到第一个文字 part 上。已经有同样记录就原样返回同一个列表，免得每个 chunk 都重建。
     */
    fun attach(messages: List<UIMessage>, knownIds: Set<Uuid>, record: JsonObject?): List<UIMessage> {
        if (record == null) return messages
        val last = messages.lastOrNull() ?: return messages
        if (last.role != MessageRole.ASSISTANT || last.id in knownIds) return messages
        val index = last.parts.indexOfFirst { it is UIMessagePart.Text }
        if (index < 0) return messages
        val part = last.parts[index] as UIMessagePart.Text
        if (part.metadata?.get(KEY) == record) return messages
        val metadata = JsonObject((part.metadata ?: JsonObject(emptyMap())) + (KEY to record))
        val parts = last.parts.toMutableList().also { it[index] = part.copy(metadata = metadata) }
        return messages.dropLast(1) + last.copy(parts = parts)
    }
}
