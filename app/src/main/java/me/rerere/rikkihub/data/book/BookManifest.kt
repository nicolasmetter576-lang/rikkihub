package me.rerere.rikkihub.data.book

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.uuid.Uuid

enum class ChapterStatus(val id: String) {
    DRAFT("draft"),
    REVISING("revising"),
    FINAL("final");

    companion object {
        fun of(id: String?): ChapterStatus? = entries.firstOrNull { it.id == id }
    }
}

sealed interface BookItem {
    val id: String
    /** 不认识的字段，写回时原样保留。 */
    val extra: JsonObject
}

data class BookVolume(
    override val id: String,
    val title: String,
    override val extra: JsonObject = EMPTY,
) : BookItem

/** [path] 相对工作区文件根目录；[title] 为 null 时界面取正文标题或文件名。 */
data class BookChapter(
    override val id: String,
    val path: String,
    val title: String? = null,
    val status: ChapterStatus = ChapterStatus.DRAFT,
    val synopsis: String = "",
    val targetWords: Int = 0,
    override val extra: JsonObject = EMPTY,
) : BookItem

private val EMPTY = JsonObject(emptyMap())

class BookManifestException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason {
        /** `book.json` 不是合格的作品结构；不覆盖，由用户决定重建。 */
        CORRUPT,
        /** 由更新版本的 App 写出，只读。 */
        NEWER_VERSION,
    }
}

/**
 * 作品结构（`.book/book.json`）。顺序就是 [items] 的顺序，卷是夹在中间的分隔项；正文文件不记任何标记。
 * 字数、哈希不在这里（见 [BookIndex]），否则每写一章清单都要变一次。
 */
data class BookManifest(
    val id: String,
    val title: String,
    val author: String = "",
    val language: String = "",
    val cover: String = "",
    val items: List<BookItem> = emptyList(),
    /** 用户说过不编入的文件，不再出现在「未编入」里。 */
    val ignored: Set<String> = emptySet(),
    val extra: JsonObject = EMPTY,
) {
    val chapters: List<BookChapter> get() = items.filterIsInstance<BookChapter>()

    fun chapter(id: String): BookChapter? = chapters.firstOrNull { it.id == id }

    fun updateChapter(id: String, transform: (BookChapter) -> BookChapter): BookManifest =
        copy(items = items.map { if (it is BookChapter && it.id == id) transform(it) else it })

    fun updateVolume(id: String, title: String): BookManifest =
        copy(items = items.map { if (it is BookVolume && it.id == id) it.copy(title = title) else it })

    /** 编入文件，接在 [afterId] 之后；[afterId] 为 null = 最后，[START] = 最前。已编入的忽略。 */
    fun addChapters(chapters: List<BookChapter>, afterId: String? = null): BookManifest {
        val known = this.chapters.mapTo(HashSet()) { it.path }
        val added = chapters.filter { known.add(it.path) }
        return copy(items = insert(items, added, afterId), ignored = ignored - added.map { it.path }.toSet())
    }

    fun addVolume(volume: BookVolume, afterId: String? = null): BookManifest =
        copy(items = insert(items, listOf(volume), afterId))

    /** 移出目录：文件不动。删卷只删分隔项，卷里的章节归到前一卷。 */
    fun remove(ids: Set<String>): BookManifest = copy(items = items.filterNot { it.id in ids })

    /** 把 [ids]（保持原有相对顺序）移到 [afterId] 之后；[START] = 最前。 */
    fun move(ids: Set<String>, afterId: String): BookManifest {
        val moved = items.filter { it.id in ids }
        val rest = items.filterNot { it.id in ids }
        // 目标本身也在移动的那批里：退到它前面最近的、没被移动的一项
        val anchor = if (afterId in ids) {
            items.take(items.indexOfFirst { it.id == afterId }).lastOrNull { it.id !in ids }?.id ?: START
        } else afterId
        return copy(items = insert(rest, moved, anchor))
    }

    /** 接到某一卷的最后；[volumeId] 为 null = 放到第一卷之前那些不分卷的章节后面。 */
    fun moveToVolume(ids: Set<String>, volumeId: String?): BookManifest {
        val rest = items.filterNot { it.id in ids }
        val start = if (volumeId == null) 0 else rest.indexOfFirst { it.id == volumeId } + 1
        val end = rest.drop(start).indexOfFirst { it is BookVolume }.let { if (it < 0) rest.size else start + it }
        return move(ids, rest.getOrNull(end - 1)?.id ?: START)
    }

    /** 按 [order] 重排；没提到的项留在原来的相对位置之后。 */
    fun reorder(order: List<String>): BookManifest {
        val rank = order.withIndex().associate { (index, id) -> id to index }
        return copy(items = items.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value })
    }

    fun encode(): String = JSON.encodeToString(JsonElement.serializer(), toJson())

    private fun toJson(): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "format" to JsonPrimitive(FORMAT),
            "version" to JsonPrimitive(VERSION),
            "id" to JsonPrimitive(id),
            "title" to JsonPrimitive(title),
            "author" to JsonPrimitive(author),
            "language" to JsonPrimitive(language),
            "cover" to JsonPrimitive(cover),
            "items" to JsonArray(items.map(::itemJson)),
            "ignored" to JsonArray(ignored.sorted().map(::JsonPrimitive)),
        ) + extra,
    )

    companion object {
        const val FORMAT = "rikkihub-book"
        const val VERSION = 1
        const val START = ""

        private val JSON = Json { prettyPrint = true }
        private val KEYS = setOf("format", "version", "id", "title", "author", "language", "cover", "items", "ignored")
        private val VOLUME_KEYS = setOf("type", "id", "title")
        private val CHAPTER_KEYS = setOf("type", "id", "path", "title", "status", "synopsis", "targetWords")

        fun newId(): String = Uuid.random().toString()

        private fun insert(items: List<BookItem>, added: List<BookItem>, afterId: String?): List<BookItem> {
            val at = when (afterId) {
                null -> items.size
                START -> 0
                else -> items.indexOfFirst { it.id == afterId }.let { if (it < 0) items.size else it + 1 }
            }
            return items.subList(0, at) + added + items.subList(at, items.size)
        }

        private fun itemJson(item: BookItem): JsonObject = JsonObject(
            when (item) {
                is BookVolume -> linkedMapOf<String, JsonElement>(
                    "type" to JsonPrimitive("volume"),
                    "id" to JsonPrimitive(item.id),
                    "title" to JsonPrimitive(item.title),
                )
                is BookChapter -> linkedMapOf<String, JsonElement>(
                    "type" to JsonPrimitive("chapter"),
                    "id" to JsonPrimitive(item.id),
                    "path" to JsonPrimitive(item.path),
                    "title" to JsonPrimitive(item.title),
                    "status" to JsonPrimitive(item.status.id),
                    "synopsis" to JsonPrimitive(item.synopsis),
                    "targetWords" to JsonPrimitive(item.targetWords),
                )
            } + item.extra,
        )

        fun decode(text: String): BookManifest {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw BookManifestException(BookManifestException.Reason.CORRUPT)
            if (root.string("format") != FORMAT) throw BookManifestException(BookManifestException.Reason.CORRUPT)
            val version = (root["version"] as? JsonPrimitive)?.intOrNull
                ?: throw BookManifestException(BookManifestException.Reason.CORRUPT)
            if (version > VERSION) throw BookManifestException(BookManifestException.Reason.NEWER_VERSION)
            val rawItems = root["items"] as? JsonArray ?: throw BookManifestException(BookManifestException.Reason.CORRUPT)
            val seenIds = HashSet<String>()
            val seenPaths = HashSet<String>()
            val items = rawItems.mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                // 手改出来的缺失或重复 id 换成新的，重复的路径只留第一个
                val id = item.string("id")?.takeIf { it.isNotBlank() && seenIds.add(it) } ?: newId().also(seenIds::add)
                when (item.string("type")) {
                    "volume" -> BookVolume(id, item.string("title").orEmpty(), item.without(VOLUME_KEYS))
                    "chapter" -> {
                        val path = item.string("path")?.trim('/')?.takeIf { it.isNotBlank() && seenPaths.add(it) } ?: return@mapNotNull null
                        BookChapter(
                            id = id,
                            path = path,
                            title = item.string("title")?.takeIf { it.isNotBlank() },
                            status = ChapterStatus.of(item.string("status")) ?: ChapterStatus.DRAFT,
                            synopsis = item.string("synopsis").orEmpty(),
                            targetWords = (item["targetWords"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0,
                            extra = item.without(CHAPTER_KEYS),
                        )
                    }
                    else -> null
                }
            }
            return BookManifest(
                id = root.string("id")?.takeIf { it.isNotBlank() } ?: newId(),
                title = root.string("title").orEmpty(),
                author = root.string("author").orEmpty(),
                language = root.string("language").orEmpty(),
                cover = root.string("cover").orEmpty(),
                items = items,
                ignored = (root["ignored"] as? JsonArray).orEmpty()
                    .mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.contentOrNull },
                extra = root.without(KEYS),
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.without(keys: Set<String>) = JsonObject(filterKeys { it !in keys })
    }
}
