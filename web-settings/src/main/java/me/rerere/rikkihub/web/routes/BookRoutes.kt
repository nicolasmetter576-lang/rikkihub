package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.data.ai.book.BeatWriter
import me.rerere.rikkihub.data.ai.book.BookOutliner
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookManifestException
import me.rerere.rikkihub.data.book.BookReplace
import me.rerere.rikkihub.data.book.BookReplaceQuery
import me.rerere.rikkihub.data.book.BookReplaceTarget
import me.rerere.rikkihub.data.book.BookReplacer
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterStatus
import me.rerere.rikkihub.data.book.ReplacePair
import me.rerere.rikkihub.data.book.transfer.BookImporter
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import java.io.File

/**
 * 作品模式工作区的作品（App 的「作品」页同一套引擎）。改结构发操作而不是整份清单，App / Web / Agent 同时改不会互相盖掉。
 *
 *   GET  /api/workspaces/{id}/book            -> BookDto
 *   POST /api/workspaces/{id}/book/edit       {operations: [BookEditDto]} -> BookDto
 *   POST /api/workspaces/{id}/book/chapters   {path, afterId?, text?} -> BookDto（新建文件并编入）
 *   POST /api/workspaces/{id}/book/delete     {ids} -> BookDto（连文件一起删）
 *   POST /api/workspaces/{id}/book/relink     {id, path} -> BookDto
 *   POST /api/workspaces/{id}/book/rebuild    -> BookDto（坏了的结构文件留底重建）
 *   POST /api/workspaces/{id}/book/replace/preview {pairs, ignoreCase, includeUnlisted?} -> 命中列表
 *   POST /api/workspaces/{id}/book/replace/apply   {…, files: [{path, revision, starts}]} -> 结果（含 undoId）
 *   GET  /api/workspaces/{id}/book/replace/history -> 最近的替换
 *   POST /api/workspaces/{id}/book/replace/undo    {id} -> {restored, changedSince, failed}
 *
 * 结构文件读不了：409 BOOK_CORRUPT / BOOK_NEWER_VERSION。
 */
@Serializable
data class BookItemDto(
    val type: String,
    val id: String,
    val title: String,
    val number: Int? = null,
    val path: String? = null,
    /** 用户起的章名；null = [title] 来自正文标题或文件名。 */
    val customTitle: String? = null,
    val status: String? = null,
    val synopsis: String? = null,
    val targetWords: Int? = null,
    val words: Int? = null,
    val missing: Boolean? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class BookDto(
    val title: String,
    val author: String,
    val words: Int,
    val items: List<BookItemDto>,
    val unlisted: List<String>,
)

@Serializable
data class BookEditDto(
    val op: String,
    val id: String? = null,
    val ids: List<String>? = null,
    val paths: List<String>? = null,
    val order: List<String>? = null,
    val title: String? = null,
    val author: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val targetWords: Int? = null,
    val afterId: String? = null,
    val volumeId: String? = null,
)

@Serializable
data class BookEditRequest(val operations: List<BookEditDto>)

@Serializable
data class BookCreateChapterRequest(val path: String, val afterId: String? = null, val text: String = "")

@Serializable
data class BookIdsRequest(val ids: List<String>)

@Serializable
data class BookRelinkRequest(val id: String, val path: String)

@Serializable
data class ReplacePairDto(val find: String, val replace: String)

@Serializable
data class BookReplaceTargetDto(val path: String, val revision: String, val starts: List<Int>)

@Serializable
data class BookReplaceRequest(
    val pairs: List<ReplacePairDto>,
    val ignoreCase: Boolean = true,
    val includeUnlisted: Boolean = false,
    val files: List<BookReplaceTargetDto> = emptyList(),
)

@Serializable
data class ReplaceHitDto(val start: Int, val pair: Int, val line: Int, val before: String, val match: String, val after: String)

@Serializable
data class BookReplaceFileDto(val path: String, val revision: String, val hits: List<ReplaceHitDto>)

@Serializable
data class BookReplacePreviewDto(val files: List<BookReplaceFileDto>, val unreadable: List<String>)

@Serializable
data class BookReplaceResultDto(
    val undoId: String? = null,
    val replaced: Int,
    val changed: List<String>,
    val conflicts: List<String>,
    val failed: List<String>,
)

@Serializable
data class BookUndoEntryDto(val id: String, val time: Long, val summary: String, val replaced: Int, val files: Int)

@Serializable
data class BookUndoHistoryDto(val entries: List<BookUndoEntryDto>)

@Serializable
data class BookUndoRequest(val id: String)

@Serializable
data class BookUndoResultDto(val restored: List<String>, val changedSince: List<String>, val failed: List<String>)

private fun replaceQuery(book: Book, request: BookReplaceRequest): BookReplaceQuery {
    val pairs = request.pairs.map { ReplacePair(it.find, it.replace) }
    if (pairs.isEmpty() || pairs.size > BookReplace.MAX_PAIRS) throw BadRequestException("Give 1 to ${BookReplace.MAX_PAIRS} pairs")
    if (pairs.any { it.find.isEmpty() || '\n' in it.find || '\n' in it.replace }) throw BadRequestException("Invalid pair")
    val paths = book.entries.filterNot { it.missing }.map { it.path } + if (request.includeUnlisted) book.unlisted else emptyList()
    return BookReplaceQuery(pairs, request.ignoreCase, paths)
}

internal fun Book.toDto(): BookDto {
    val entries = entries.associateBy { it.id }
    return BookDto(
        title = manifest.title,
        author = manifest.author,
        words = words,
        items = manifest.items.map { item ->
            when (item) {
                is BookVolume -> BookItemDto(type = "volume", id = item.id, title = item.title)
                is BookChapter -> {
                    val entry = entries.getValue(item.id)
                    BookItemDto(
                        type = "chapter",
                        id = item.id,
                        title = entry.title,
                        number = entry.number,
                        path = item.path,
                        customTitle = item.title,
                        status = item.status.id,
                        synopsis = item.synopsis,
                        targetWords = item.targetWords,
                        words = entry.words,
                        missing = entry.missing,
                        updatedAt = entry.file?.modifiedAt,
                    )
                }
            }
        },
        unlisted = unlisted,
    )
}

/** 操作按顺序应用；不认识的操作或缺字段是 400，整批不生效。 */
internal fun applyBookEdits(book: Book, edits: List<BookEditDto>): BookManifest = edits.fold(book.manifest) { manifest, edit ->
    fun <T> need(value: T?, field: String): T = value ?: throw BadRequestException("Missing '$field' for ${edit.op}")
    when (edit.op) {
        "add" -> {
            val paths = need(edit.paths, "paths")
            val known = book.unlisted.toSet() + manifest.ignored
            paths.firstOrNull { it !in known }?.let { throw BadRequestException("Not a text file of this book: $it") }
            manifest.addChapters(paths.map { BookChapter(BookManifest.newId(), it) }, edit.afterId)
        }
        "addVolume" -> manifest.addVolume(BookVolume(BookManifest.newId(), need(edit.title, "title").trim()), edit.afterId)
        "renameVolume" -> manifest.updateVolume(need(edit.id, "id"), need(edit.title, "title").trim())
        "updateChapter" -> manifest.updateChapter(need(edit.id, "id")) { chapter ->
            chapter.copy(
                title = if (edit.title == null) chapter.title else edit.title.trim().ifBlank { null },
                synopsis = edit.synopsis?.trim() ?: chapter.synopsis,
                status = edit.status?.let { ChapterStatus.of(it) ?: throw BadRequestException("Invalid 'status'") } ?: chapter.status,
                targetWords = edit.targetWords?.coerceAtLeast(0) ?: chapter.targetWords,
            )
        }
        "setStatus" -> {
            val status = ChapterStatus.of(need(edit.status, "status")) ?: throw BadRequestException("Invalid 'status'")
            need(edit.ids, "ids").fold(manifest) { current, id -> current.updateChapter(id) { it.copy(status = status) } }
        }
        "remove" -> manifest.remove(need(edit.ids, "ids").toSet())
        "reorder" -> manifest.reorder(need(edit.order, "order"))
        "moveToVolume" -> manifest.moveToVolume(need(edit.ids, "ids").toSet(), edit.volumeId)
        "ignore" -> manifest.copy(ignored = manifest.ignored + need(edit.paths, "paths"))
        "info" -> manifest.copy(title = edit.title?.trim() ?: manifest.title, author = edit.author?.trim() ?: manifest.author)
        else -> throw BadRequestException("Unknown operation '${edit.op}'")
    }
}

internal suspend fun bookWorkspaceId(call: ApplicationCall, repository: WorkspaceRepository): String {
    val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
    val workspace = repository.getById(id) ?: throw NotFoundException("Workspace not found")
    if (!workspace.bookMode) throw CodedApiException(HttpStatusCode.Conflict, "NOT_A_BOOK", "This workspace is not in book mode")
    return id
}

internal suspend fun <T> bookApi(block: suspend () -> T): T = try {
    block()
} catch (broken: BookManifestException) {
    throw CodedApiException(HttpStatusCode.Conflict, "BOOK_${broken.reason.name}", "The book structure cannot be read")
}

/** 作品路由要用的服务，一起传。 */
class WebBookServices(val store: BookStore, val replacer: BookReplacer, val importer: BookImporter, val outliner: BookOutliner, val writer: BeatWriter, val tempDir: File)

fun Route.bookRoutes(repository: WorkspaceRepository, services: WebBookServices) {
    val store = services.store
    val replacer = services.replacer
    route("/workspaces/{id}/book") {
        get {
            val id = bookWorkspaceId(call, repository)
            call.respond(bookApi { store.open(id) }.toDto())
        }
        post("/edit") {
            val id = bookWorkspaceId(call, repository)
            val request = JsonInstant.decodeFromJsonElement(BookEditRequest.serializer(), call.receive<JsonObject>())
            call.respond(bookApi { store.edit(id) { applyBookEdits(it, request.operations) } }.toDto())
        }
        post("/chapters") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookCreateChapterRequest>()
            val book = bookApi { workspaceFileApiOperation { store.createChapter(id, request.path, request.text, request.afterId) } }
            call.respond(HttpStatusCode.Created, book.toDto())
        }
        post("/delete") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookIdsRequest>()
            call.respond(bookApi { workspaceFileApiOperation { store.deleteChapters(id, request.ids.toSet()) } }.toDto())
        }
        post("/relink") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookRelinkRequest>()
            val book = try {
                bookApi { store.relink(id, request.id, request.path) }
            } catch (invalid: IllegalArgumentException) {
                throw BadRequestException(invalid.message ?: "Invalid file")
            }
            call.respond(book.toDto())
        }
        post("/rebuild") {
            val id = bookWorkspaceId(call, repository)
            call.respond(bookApi { store.rebuild(id) }.toDto())
        }
        bookTransferRoutes(repository, services)
        bookOutlineRoutes(repository, services)
        beatRoutes(repository, services)
        route("/replace") {
            post("/preview") {
                val id = bookWorkspaceId(call, repository)
                val request = call.receive<BookReplaceRequest>()
                val preview = replacer.preview(id, replaceQuery(bookApi { store.open(id) }, request))
                call.respond(BookReplacePreviewDto(
                    files = preview.files.map { file ->
                        BookReplaceFileDto(file.path, file.revision, file.hits.map { ReplaceHitDto(it.start, it.pair, it.line, it.before, it.match, it.after) })
                    },
                    unreadable = preview.unreadable,
                ))
            }
            post("/apply") {
                val id = bookWorkspaceId(call, repository)
                val request = call.receive<BookReplaceRequest>()
                val query = replaceQuery(bookApi { store.open(id) }, request)
                val unknown = request.files.firstOrNull { it.path !in query.paths }
                if (unknown != null) throw BadRequestException("Not a text file of this book: ${unknown.path}")
                val result = replacer.apply(id, query, request.files.map { BookReplaceTarget(it.path, it.revision, it.starts.toSet()) })
                call.respond(BookReplaceResultDto(result.undoId, result.replaced, result.changed, result.conflicts, result.failed))
            }
            get("/history") {
                val id = bookWorkspaceId(call, repository)
                call.respond(BookUndoHistoryDto(replacer.history(id).map { BookUndoEntryDto(it.id, it.time, it.summary, it.replaced, it.files.size) }))
            }
            post("/undo") {
                val id = bookWorkspaceId(call, repository)
                val request = call.receive<BookUndoRequest>()
                val undone = try {
                    replacer.undo(id, request.id)
                } catch (_: NoSuchElementException) {
                    throw NotFoundException("Nothing to undo")
                }
                call.respond(BookUndoResultDto(undone.restored, undone.changedSince, undone.failed))
            }
        }
    }
}
