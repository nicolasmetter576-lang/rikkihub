package me.rerere.rikkihub.data.book

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

private val EMPTY = JsonObject(emptyMap())

/** 节拍的一稿。[basis] 是写它时前面已采用的正文的指纹，变了就提示「前文变了」。 */
data class BeatDraft(
    val id: String,
    val text: String,
    val model: String = "",
    /** 写这一稿时附的要求。 */
    val note: String = "",
    val at: Long = 0,
    val basis: String = "",
    val extra: JsonObject = EMPTY,
)

/** 计划里的一个节拍，也是节拍写作的单位。 */
data class Beat(
    val id: String,
    val text: String,
    val words: Int = 0,
    val drafts: List<BeatDraft> = emptyList(),
    /** 采用的那一稿。 */
    val chosen: String? = null,
    val locked: Boolean = false,
    /** 已写进正文的原文；空 = 没写过。 */
    val inserted: String = "",
    val extra: JsonObject = EMPTY,
) {
    val chosenDraft: BeatDraft? get() = drafts.firstOrNull { it.id == chosen }
}

/** 从成稿里提取的情节点；[quote] 一定是当时正文里的原文，空 = 没能定位。 */
data class PlotPoint(val id: String, val text: String, val quote: String, val extra: JsonObject = EMPTY)

/** 反向大纲。[hash] 是提取时章节文件的 SHA-256，和现在的不一样就是过期。 */
data class ChapterOutline(
    val hash: String,
    val at: Long,
    val synopsis: String,
    val points: List<PlotPoint>,
    val extra: JsonObject = EMPTY,
)

enum class BeatState(val id: String) {
    WRITTEN("written"),
    CHANGED("changed"),
    MISSING("missing");

    companion object {
        fun of(id: String?): BeatState? = entries.firstOrNull { it.id == id }
    }
}

data class BeatMatch(val beatId: String, val state: BeatState, val pointIds: List<String>, val note: String, val extra: JsonObject = EMPTY)

/** 计划和成稿的对照。[basis] 是当时节拍和情节点的指纹，见 [ChapterNotes.comparisonBasis]。 */
data class OutlineComparison(val basis: String, val at: Long, val matches: List<BeatMatch>, val extra: JsonObject = EMPTY)

class ChapterNotesException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { CORRUPT, NEWER_VERSION }
}

/** 一章的计划、反向大纲、节拍草稿（`.book/chapters/<章节 id>.json`）。正文文件里不记任何标记。 */
data class ChapterNotes(
    val beats: List<Beat> = emptyList(),
    val outline: ChapterOutline? = null,
    val comparison: OutlineComparison? = null,
    val extra: JsonObject = EMPTY,
) {
    fun updateBeat(id: String, transform: (Beat) -> Beat): ChapterNotes = copy(beats = beats.map { if (it.id == id) transform(it) else it })

    /** 计划或情节点变了，原来的对照就不算数。 */
    val comparisonBasis: String
        get() = fingerprint(beats.map { it.id + "\u0000" + it.text } + "" + outline?.points.orEmpty().map { it.id + "\u0000" + it.text })

    val comparisonStale: Boolean get() = comparison != null && comparison.basis != comparisonBasis

    /** 写 [beatId] 时前面已采用的正文的指纹；和某一稿记下的不同 = 前文变了。 */
    fun basisOf(beatId: String): String = fingerprint(beats.takeWhile { it.id != beatId }.mapNotNull { it.chosenDraft?.text })

    fun outdated(beat: Beat): Boolean = beat.chosenDraft?.let { it.basis != basisOf(beat.id) } == true

    /** 新的一稿并采用它；超过 [MAX_DRAFTS] 时丢掉最早的。 */
    fun addDraft(beatId: String, draft: BeatDraft): ChapterNotes = updateBeat(beatId) { beat ->
        beat.copy(drafts = (beat.drafts + draft).takeLast(MAX_DRAFTS), chosen = draft.id)
    }

    /** 删一稿；删的是采用的那稿就改用它旁边的。 */
    fun removeDraft(beatId: String, draftId: String): ChapterNotes = updateBeat(beatId) { beat ->
        val index = beat.drafts.indexOfFirst { it.id == draftId }
        val rest = beat.drafts.filterNot { it.id == draftId }
        beat.copy(drafts = rest, chosen = if (beat.chosen == draftId) rest.getOrNull(index.coerceAtMost(rest.lastIndex))?.id else beat.chosen)
    }

    /** 把 [texts] 接到计划末尾；已有同样文字的不重复加。 */
    fun addBeats(texts: List<String>): ChapterNotes {
        val known = beats.mapTo(HashSet()) { it.text }
        val added = texts.map { it.trim() }.filter { it.isNotEmpty() && known.add(it) }
        return if (added.isEmpty()) this else copy(beats = beats + added.map { Beat(BookManifest.newId(), it) })
    }

    fun encode(): String = JSON.encodeToString(
        JsonElement.serializer(),
        obj(
            extra,
            "version" to JsonPrimitive(VERSION),
            "beats" to JsonArray(beats.map(::beatJson)),
            "outline" to outline?.let { o ->
                obj(
                    o.extra,
                    "hash" to JsonPrimitive(o.hash),
                    "at" to JsonPrimitive(o.at),
                    "synopsis" to JsonPrimitive(o.synopsis),
                    "points" to JsonArray(o.points.map {
                        obj(it.extra, "id" to JsonPrimitive(it.id), "text" to JsonPrimitive(it.text), "quote" to JsonPrimitive(it.quote))
                    }),
                )
            },
            "comparison" to comparison?.let { c ->
                obj(
                    c.extra,
                    "basis" to JsonPrimitive(c.basis),
                    "at" to JsonPrimitive(c.at),
                    "matches" to JsonArray(c.matches.map {
                        obj(
                            it.extra,
                            "beat" to JsonPrimitive(it.beatId),
                            "state" to JsonPrimitive(it.state.id),
                            "points" to JsonArray(it.pointIds.map(::JsonPrimitive)),
                            "note" to JsonPrimitive(it.note),
                        )
                    }),
                )
            },
        ),
    )

    private fun beatJson(beat: Beat): JsonObject = obj(
        beat.extra,
        "id" to JsonPrimitive(beat.id),
        "text" to JsonPrimitive(beat.text),
        "words" to JsonPrimitive(beat.words),
        "drafts" to JsonArray(beat.drafts.map {
            obj(
                it.extra,
                "id" to JsonPrimitive(it.id),
                "text" to JsonPrimitive(it.text),
                "model" to JsonPrimitive(it.model),
                "note" to JsonPrimitive(it.note),
                "at" to JsonPrimitive(it.at),
                "basis" to JsonPrimitive(it.basis),
            )
        }),
        "chosen" to beat.chosen?.let(::JsonPrimitive),
        "locked" to JsonPrimitive(beat.locked),
        "inserted" to JsonPrimitive(beat.inserted),
    )

    companion object {
        const val VERSION = 1
        const val MAX_DRAFTS = 5
        private val JSON = Json { prettyPrint = true }

        /** 认识的字段优先，[extra] 只补不认识的；`null` 值不写。 */
        private fun obj(extra: JsonObject, vararg fields: Pair<String, JsonElement?>): JsonObject {
            val map = LinkedHashMap<String, JsonElement>()
            fields.forEach { (key, value) -> if (value != null) map[key] = value }
            extra.forEach { (key, value) -> map.putIfAbsent(key, value) }
            return JsonObject(map)
        }

        fun fingerprint(parts: List<String>): String =
            MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray()).joinToString("") { "%02x".format(it) }

        fun decode(text: String): ChapterNotes {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw ChapterNotesException(ChapterNotesException.Reason.CORRUPT)
            if ((root["version"] as? JsonPrimitive)?.intOrNull?.let { it > VERSION } == true) {
                throw ChapterNotesException(ChapterNotesException.Reason.NEWER_VERSION)
            }
            val ids = HashSet<String>()
            fun id(item: JsonObject, key: String = "id") = item.string(key)?.takeIf { it.isNotBlank() && ids.add(it) } ?: BookManifest.newId()
            val beats = root.objects("beats").map { item ->
                val drafts = item.objects("drafts").map { draft ->
                    BeatDraft(
                        id = id(draft),
                        text = draft.string("text").orEmpty(),
                        model = draft.string("model").orEmpty(),
                        note = draft.string("note").orEmpty(),
                        at = draft.long("at"),
                        basis = draft.string("basis").orEmpty(),
                        extra = draft.without("id", "text", "model", "note", "at", "basis"),
                    )
                }
                Beat(
                    id = id(item),
                    text = item.string("text").orEmpty(),
                    words = item.long("words").coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    drafts = drafts,
                    chosen = item.string("chosen")?.takeIf { chosen -> drafts.any { it.id == chosen } },
                    locked = (item["locked"] as? JsonPrimitive)?.booleanOrNull == true,
                    inserted = item.string("inserted").orEmpty(),
                    extra = item.without("id", "text", "words", "drafts", "chosen", "locked", "inserted"),
                )
            }
            val outline = (root["outline"] as? JsonObject)?.let { item ->
                ChapterOutline(
                    hash = item.string("hash").orEmpty(),
                    at = item.long("at"),
                    synopsis = item.string("synopsis").orEmpty(),
                    points = item.objects("points").map { point ->
                        PlotPoint(id(point), point.string("text").orEmpty(), point.string("quote").orEmpty(), point.without("id", "text", "quote"))
                    },
                    extra = item.without("hash", "at", "synopsis", "points"),
                )
            }
            val comparison = (root["comparison"] as? JsonObject)?.let { item ->
                OutlineComparison(
                    basis = item.string("basis").orEmpty(),
                    at = item.long("at"),
                    matches = item.objects("matches").mapNotNull { match ->
                        BeatMatch(
                            beatId = match.string("beat") ?: return@mapNotNull null,
                            state = BeatState.of(match.string("state")) ?: return@mapNotNull null,
                            pointIds = (match["points"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                            note = match.string("note").orEmpty(),
                            extra = match.without("beat", "state", "points", "note"),
                        )
                    },
                    extra = item.without("basis", "at", "matches"),
                )
            }
            return ChapterNotes(beats, outline, comparison, root.without("version", "beats", "outline", "comparison"))
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0

        private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

        private fun JsonObject.without(vararg keys: String) = JsonObject(filterKeys { it !in keys })
    }
}
