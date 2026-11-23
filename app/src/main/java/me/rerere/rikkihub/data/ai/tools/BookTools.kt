package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookChapterEntry
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterStatus
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceManager

private const val CONTENTS_LIMIT = 200
private const val READ_MAX_CHARS = 20_000
private const val READ_PART_CHARS = 2_000
private const val READ_MAX_CHAPTERS = 20
private const val SEARCH_DEFAULT = 30
private const val SEARCH_LIMIT = 200
private const val SEARCH_CONTEXT = 40
private const val MAX_OPERATIONS = 200

/** 作品模式的四个作品工具：直接读写书稿，不需要 Rootfs。 */
internal fun createBookTools(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    store: BookStore,
): List<Tool> {
    fun tool(id: String, parameters: JsonObject, required: List<String>, run: suspend (JsonObject) -> String) = Tool(
        name = id,
        description = BuiltinToolCatalog.require(id).defaultDescription,
        parameters = { InputSchema.Obj(properties = parameters, required = required) },
        needsApproval = { needsApproval(id) },
        workspaceIdentity = workspaceToolIdentity(workspace, id),
        execute = { args -> listOf(UIMessagePart.Text(run(args.jsonObject))) },
    )
    return listOf(
        tool(
            BuiltinToolIds.BOOK_CONTENTS,
            buildJsonObject {
                property("from", "integer", "First chapter number to list. Defaults to 1.")
                property("to", "integer", "Last chapter number to list. Defaults to the end.")
                property("synopses", "boolean", "Whether to include synopses. Defaults to true.")
            },
            emptyList(),
        ) { args ->
            bookContentsText(
                store.open(workspace.id), args.int("from"), args.int("to"),
                synopses = args["synopses"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        },
        tool(
            BuiltinToolIds.BOOK_READ,
            buildJsonObject {
                property("chapter", "integer", "Chapter number as shown in the contents")
                property("to", "integer", "Read through to this chapter number (optional)")
                putJsonObject("part") {
                    put("type", "string")
                    put("enum", buildJsonArray { add("all"); add("start"); add("end") })
                    put("description", "all (default), start: only the opening, end: only the ending")
                }
                property("max_chars", "integer", "Maximum characters to return. Defaults to $READ_MAX_CHARS, or $READ_PART_CHARS for start / end.")
                property("offset", "integer", "Skip this many characters, to continue a cut result. Defaults to 0.")
            },
            listOf("chapter"),
        ) { args -> readChapters(store, workspace.id, args) },
        tool(
            BuiltinToolIds.BOOK_SEARCH,
            buildJsonObject {
                property("query", "string", "The exact text to find")
                property("case_sensitive", "boolean", "Whether case matters. Defaults to false.")
                property("from", "integer", "First chapter number to search")
                property("to", "integer", "Last chapter number to search")
                property("max_results", "integer", "Maximum number of hits. Defaults to $SEARCH_DEFAULT, at most $SEARCH_LIMIT.")
            },
            listOf("query"),
        ) { args -> searchChapters(store, workspace.id, args) },
        tool(
            BuiltinToolIds.BOOK_UPDATE,
            buildJsonObject {
                putJsonObject("operations") {
                    put("type", "array")
                    put("description", "Changes, applied in order")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("op") {
                                put("type", "string")
                                put("enum", buildJsonArray { listOf("add", "set", "move", "remove", "add_volume", "set_book").forEach { add(it) } })
                                put("description", "add, set, move, remove, add_volume or set_book")
                            }
                            property("chapter", "integer", "Chapter number (set, move, remove)")
                            property("path", "string", "File to add, e.g. /workspace/Chapter 12.md")
                            property("after", "integer", "Place behind this chapter number, 0 = beginning")
                            property("title", "string", "Chapter, volume or book title")
                            property("synopsis", "string", "One or two sentences")
                            property("status", "string", "draft, revising or final")
                            property("target_words", "integer", "Target length, 0 = none")
                            property("author", "string", "Author (set_book)")
                        }
                        put("required", buildJsonArray { add("op") })
                    }
                }
            },
            listOf("operations"),
        ) { args ->
            val operations = (args["operations"] as? JsonArray)?.map { it.jsonObject }
                ?: error("operations must be a list")
            require(operations.isNotEmpty() && operations.size <= MAX_OPERATIONS) { "operations must hold 1 to $MAX_OPERATIONS items" }
            val book = store.edit(workspace.id) { applyBookOperations(it, operations) }
            "Done: ${operations.size} change(s). The book has ${book.entries.size} chapters.\n" +
                bookContentsText(book, null, null, synopses = false)
        },
    )
}

private fun JsonObjectBuilder.property(name: String, type: String, description: String) = putJsonObject(name) {
    put("type", type)
    put("description", description)
}

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun guestPath(path: String) = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$path"

internal fun bookContentsText(book: Book, from: Int?, to: Int?, synopses: Boolean): String = buildString {
    val manifest = book.manifest
    append(manifest.title.ifBlank { "(untitled)" })
    if (manifest.author.isNotBlank()) append(" — ").append(manifest.author)
    appendLine()
    appendLine("${book.entries.size} chapters, ${book.words} words")
    val first = (from ?: 1).coerceAtLeast(1)
    val last = (to ?: (first + CONTENTS_LIMIT - 1)).coerceAtMost(book.entries.size)
    val shown = book.entries.filter { it.number in first..last }
    val volumes = manifest.items.filterIsInstance<BookVolume>().associateBy { it.id }
    var volume: String? = null
    var started = false
    shown.forEach { entry ->
        if (!started || entry.volumeId != volume) {
            entry.volumeId?.let { appendLine().appendLine("## ${volumes[it]?.title.orEmpty()}") }
            volume = entry.volumeId
            started = true
        }
        append("${entry.number}. ${entry.title} — ")
        append(if (entry.missing) "FILE MISSING" else "${entry.words} words")
        if (entry.chapter.targetWords > 0) append(" of ${entry.chapter.targetWords}")
        appendLine(", ${entry.chapter.status.id}, ${guestPath(entry.path)}")
        if (synopses && entry.chapter.synopsis.isNotBlank()) appendLine("   ${entry.chapter.synopsis.replace('\n', ' ')}")
    }
    if (shown.size < book.entries.size) appendLine("(chapters $first-$last of ${book.entries.size}; use from/to for others)")
    if (book.unlisted.isNotEmpty()) {
        appendLine().appendLine("Files not in the contents:")
        book.unlisted.take(CONTENTS_LIMIT).forEach { appendLine("- ${guestPath(it)}") }
        if (book.unlisted.size > CONTENTS_LIMIT) appendLine("(and ${book.unlisted.size - CONTENTS_LIMIT} more)")
    }
}.trimEnd()

private suspend fun readChapters(store: BookStore, workspaceId: String, args: JsonObject): String {
    val book = store.open(workspaceId)
    val number = args.int("chapter") ?: error("chapter is required")
    val last = (args.int("to") ?: number).coerceAtLeast(number)
    require(last - number < READ_MAX_CHAPTERS) { "Read at most $READ_MAX_CHAPTERS chapters at once" }
    val entries = (number..last).map { book.requireChapter(it) }
    val text = buildString {
        entries.forEach { entry ->
            if (isNotEmpty()) append("\n\n")
            append("=== ${entry.number}. ${entry.title} (${guestPath(entry.path)}) ===\n")
            append(if (entry.missing) "(the file of this chapter is missing)" else store.read(workspaceId, entry.path).content)
        }
    }
    val part = args.text("part") ?: "all"
    val limit = (args.int("max_chars") ?: if (part == "all") READ_MAX_CHARS else READ_PART_CHARS).coerceIn(1, READ_MAX_CHARS)
    return sliceProse(text, part, limit, (args.int("offset") ?: 0).coerceAtLeast(0))
}

/** 取正文的一段：不切断代理对，截断时写明还剩多少、从哪接着读。 */
internal fun sliceProse(text: String, part: String, limit: Int, offset: Int): String {
    fun boundary(index: Int): Int {
        val at = index.coerceIn(0, text.length)
        return if (at in 1 until text.length && text[at].isLowSurrogate() && text[at - 1].isHighSurrogate()) at - 1 else at
    }
    if (part == "end") {
        val start = boundary(text.length - limit)
        return if (start == 0) text else "(the last ${text.length - start} of ${text.length} characters)\n" + text.substring(start)
    }
    val start = boundary(offset)
    val end = boundary(start + limit)
    val body = text.substring(start, end)
    return when {
        end >= text.length -> body
        part == "start" -> "$body\n(the first ${end - start} of ${text.length} characters)"
        else -> "$body\n(cut at $end of ${text.length} characters; continue with offset=$end)"
    }
}

private suspend fun searchChapters(store: BookStore, workspaceId: String, args: JsonObject): String {
    val query = args.text("query")?.takeIf { it.isNotBlank() } ?: error("query is required")
    val ignoreCase = args["case_sensitive"]?.jsonPrimitive?.booleanOrNull != true
    val limit = (args.int("max_results") ?: SEARCH_DEFAULT).coerceIn(1, SEARCH_LIMIT)
    val book = store.open(workspaceId)
    val range = (args.int("from") ?: 1)..(args.int("to") ?: book.entries.size)
    val lines = ArrayList<String>()
    var total = 0
    val chapters = LinkedHashSet<Int>()
    book.entries.filter { it.number in range && !it.missing }.forEach { entry ->
        store.read(workspaceId, entry.path).content.lineSequence().forEachIndexed { index, line ->
            var at = line.indexOf(query, 0, ignoreCase)
            while (at >= 0) {
                total++
                chapters += entry.number
                if (lines.size < limit) lines += "${entry.number}. ${entry.title}, line ${index + 1}: ${excerpt(line, at, query.length)}"
                at = line.indexOf(query, at + query.length, ignoreCase)
            }
        }
    }
    if (total == 0) return "No match for \"$query\"."
    return "$total match(es) in ${chapters.size} chapter(s)" +
        (if (total > lines.size) ", showing the first ${lines.size}" else "") + ":\n" + lines.joinToString("\n")
}

internal fun excerpt(line: String, start: Int, length: Int, context: Int = SEARCH_CONTEXT): String {
    var from = (start - context).coerceAtLeast(0)
    var to = (start + length + context).coerceAtMost(line.length)
    if (from > 0 && line[from].isLowSurrogate()) from--
    if (to < line.length && line[to].isLowSurrogate()) to++
    return (if (from > 0) "…" else "") + line.substring(from, to).trim() + (if (to < line.length) "…" else "")
}

private fun Book.requireChapter(number: Int): BookChapterEntry =
    entryByNumber(number) ?: error("There is no chapter $number; the book has ${entries.size} chapters")

/** 章号一律指 [book] 的章号（调用之前）；任何一步不合格整批不生效。 */
internal fun applyBookOperations(book: Book, operations: List<JsonObject>): BookManifest {
    fun chapterId(op: JsonObject): String = book.requireChapter(op.int("chapter") ?: error("chapter is required")).id
    fun anchor(op: JsonObject): String? = op.int("after")?.let { if (it == 0) BookManifest.START else book.requireChapter(it).id }
    fun status(op: JsonObject): ChapterStatus? = op.text("status")?.let {
        ChapterStatus.of(it) ?: error("status must be draft, revising or final")
    }
    val files = book.entries.filterNot { it.missing }.map { it.path } + book.unlisted + book.manifest.ignored
    // 同一批里先后 add 到同一位置时保持书写顺序
    val lastAdded = HashMap<String?, String>()
    return operations.fold(book.manifest) { manifest, op ->
        when (val name = op.text("op")) {
            "add" -> {
                val path = op.text("path")?.removePrefix(WorkspaceManager.ROOTFS_WORKSPACE_DIR)?.trim('/')
                    ?.takeIf { it.isNotBlank() } ?: error("path is required")
                require(path in files) { "No such text file in the book: ${guestPath(path)}" }
                require(manifest.chapters.none { it.path == path }) { "Already in the contents: ${guestPath(path)}" }
                val key = anchor(op)
                val chapter = BookChapter(
                    id = BookManifest.newId(),
                    path = path,
                    title = op.text("title")?.takeIf { it.isNotBlank() },
                    status = status(op) ?: ChapterStatus.DRAFT,
                    synopsis = op.text("synopsis").orEmpty(),
                    targetWords = op.int("target_words")?.coerceAtLeast(0) ?: 0,
                )
                manifest.addChapters(listOf(chapter), lastAdded[key] ?: key).also { lastAdded[key] = chapter.id }
            }
            "set" -> manifest.updateChapter(chapterId(op)) { chapter ->
                chapter.copy(
                    title = if ("title" in op) op.text("title")?.takeIf { it.isNotBlank() } else chapter.title,
                    status = status(op) ?: chapter.status,
                    synopsis = op.text("synopsis") ?: chapter.synopsis,
                    targetWords = op.int("target_words")?.coerceAtLeast(0) ?: chapter.targetWords,
                )
            }
            "move" -> manifest.move(setOf(chapterId(op)), anchor(op) ?: error("after is required"))
            "remove" -> manifest.remove(setOf(chapterId(op)))
            "add_volume" -> {
                val volume = BookVolume(BookManifest.newId(), op.text("title")?.takeIf { it.isNotBlank() } ?: error("title is required"))
                val key = anchor(op)
                manifest.addVolume(volume, lastAdded[key] ?: key).also { lastAdded[key] = volume.id }
            }
            "set_book" -> manifest.copy(
                title = op.text("title") ?: manifest.title,
                author = op.text("author") ?: manifest.author,
            )
            else -> error("Unknown op: $name")
        }
    }
}
