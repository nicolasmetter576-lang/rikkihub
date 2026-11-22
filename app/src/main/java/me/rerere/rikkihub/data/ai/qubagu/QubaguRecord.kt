package me.rerere.rikkihub.data.ai.qubagu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.utils.JsonInstant

/**
 * 一处改动。[start] 是 [before] 在**原文**里的起点（UTF-16 下标），不随其他改动的应用或撤销变化。
 * [rule] 是规则 id，见 [QubaguRules]。
 */
@Serializable
data class QubaguEdit(
    @SerialName("s") val start: Int,
    @SerialName("b") val before: String,
    @SerialName("a") val after: String,
    @SerialName("r") val rule: String,
    @SerialName("on") val applied: Boolean = true,
) {
    val end: Int get() = start + before.length
}

/** 改动记录里的规则 id：规则包里的规则是 `<包 id>/<规则 id>`（[key]），另有碎句合并与 AI 精修两个固定值。 */
object QubaguRules {
    const val MERGE = "merge"
    const val AI = "ai"

    fun key(packId: String, ruleId: String): String = "$packId/$ruleId"
}

/**
 * 一段正文的改动记录：[edits] 按原文位置排序、互不重叠。只存改动，不存整份原文——
 * 当前正文是原文套用已应用改动的结果（[render]），原文可以由当前正文反推（[original]）。
 * 存在文字 part 的 `metadata` 里，随消息走，换分支、删消息都不用另外清理。
 * [hash] 是写入时正文的 `String.hashCode()`：正文之后被手动改过就对不上，记录作废（见 [of]）。
 */
@Serializable
data class QubaguRecord(
    @SerialName("v") val version: Int = 1,
    val edits: List<QubaguEdit> = emptyList(),
    @SerialName("h") val hash: Int = 0,
) {
    val appliedCount: Int get() = edits.count { it.applied }

    /** 原文套用已应用的改动。 */
    fun render(original: String): String = buildString {
        var cursor = 0
        for (edit in edits) {
            append(original, cursor, edit.start)
            append(if (edit.applied) edit.after else edit.before)
            cursor = edit.end
        }
        append(original, cursor, original.length)
    }

    /**
     * 由当前正文反推原文。正文和记录对不上（比如用户手动改过这条消息）时返回 null，这时记录作废。
     */
    fun original(current: String): String? {
        val out = StringBuilder()
        var cursor = 0
        var originalCursor = 0
        for (edit in edits) {
            val gap = edit.start - originalCursor
            if (gap < 0 || cursor + gap > current.length) return null
            out.append(current, cursor, cursor + gap)
            cursor += gap
            val shown = if (edit.applied) edit.after else edit.before
            if (!current.startsWith(shown, cursor)) return null
            out.append(edit.before)
            cursor += shown.length
            originalCursor = edit.end
        }
        out.append(current, cursor, current.length)
        return out.toString()
    }

    /** 每条改动在**当前正文**里的起点，和 [edits] 一一对应。 */
    fun currentStarts(): List<Int> {
        var shift = 0
        return edits.map { edit ->
            (edit.start + shift).also { if (edit.applied) shift += edit.after.length - edit.before.length }
        }
    }

    fun withApplied(index: Int, applied: Boolean): QubaguRecord =
        copy(edits = edits.mapIndexed { i, edit -> if (i == index) edit.copy(applied = applied) else edit })

    fun withAllApplied(applied: Boolean): QubaguRecord = copy(edits = edits.map { it.copy(applied = applied) })

    fun encode(): JsonElement = JsonInstant.encodeToJsonElement(this)

    companion object {
        const val KEY = "qubagu"

        fun decode(element: JsonElement?): QubaguRecord? =
            (element as? JsonObject)?.let { runCatching { JsonInstant.decodeFromJsonElement<QubaguRecord>(it) }.getOrNull() }

        /** 这段文字带的记录；没有、或正文在记录写入之后变过，返回 null。 */
        fun of(part: UIMessagePart.Text): QubaguRecord? = decode(part.metadata?.get(KEY))?.takeIf {
            it.edits.isNotEmpty() && it.hash == part.text.hashCode() && it.original(part.text) != null
        }
    }
}

/** 换上新的记录并按它重排正文；[record] 没有改动时去掉记录、正文回到原文。原记录已作废时原样返回。 */
fun UIMessagePart.Text.withQubagu(transform: (original: String, record: QubaguRecord) -> QubaguRecord): UIMessagePart.Text {
    val current = QubaguRecord.of(this) ?: return this
    val original = current.original(text) ?: return this
    return withQubaguRecord(original, transform(original, current))
}

/** 把 [record] 写到这段文字上，正文按 [original] 重排。 */
fun UIMessagePart.Text.withQubaguRecord(original: String, record: QubaguRecord): UIMessagePart.Text {
    val rendered = record.render(original)
    val rest = metadata.orEmpty() - QubaguRecord.KEY
    val next = if (record.edits.isEmpty()) rest else rest + (QubaguRecord.KEY to record.copy(hash = rendered.hashCode()).encode())
    return copy(text = rendered, metadata = if (next.isEmpty()) null else JsonObject(next))
}

/** 一条消息里带改动记录的文字段：[part] 是它在 `parts` 里的下标。 */
data class QubaguPartRecord(val part: Int, val record: QubaguRecord)

fun UIMessage.qubaguRecords(): List<QubaguPartRecord> = parts.mapIndexedNotNull { index, part ->
    (part as? UIMessagePart.Text)?.let(QubaguRecord::of)?.let { QubaguPartRecord(index, it) }
}
