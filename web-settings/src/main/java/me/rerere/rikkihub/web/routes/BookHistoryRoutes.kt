package me.rerere.rikkihub.web.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.book.history.BookHistoryActions
import me.rerere.rikkihub.data.book.history.ChapterDiff
import me.rerere.rikkihub.data.book.history.HistoryEntry
import me.rerere.rikkihub.data.book.history.HistorySource
import me.rerere.rikkihub.data.book.history.TrashedFile
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException

/*
 * 作品的版本历史与回收站（App 同一套引擎）。网页端能看、比较、还原、取回、放回；
 * 删一版、清空历史、清空回收站只在 App 里（防工作区里的命令调本机接口）。
 *
 *   GET  /api/workspaces/{id}/book/history?path=     -> BookHistoryDto（新的在前）
 *   GET  /api/workspaces/{id}/book/history/compare?path=&sha= -> BookHistoryCompareDto
 *   POST /api/workspaces/{id}/book/history/restore   {path, sha, revision} -> {revision}
 *   POST /api/workspaces/{id}/book/history/take-back {path, sha, index, revision} -> {revision}
 *   POST /api/workspaces/{id}/book/history/checkpoint {path, text}（编辑器应用推敲之类前）
 *   GET  /api/workspaces/{id}/book/trash             -> {items}
 *   POST /api/workspaces/{id}/book/trash/restore     {key, name, path} -> {path}
 *
 * 稿子在比较之后变了：409 FILE_REVISION_CONFLICT；版本内容丢了：410 HISTORY_VERSION_MISSING。
 */
@Serializable
data class BookHistoryDto(
    val entries: List<HistoryEntry>,
    val currentWriter: HistorySource? = null,
    val paused: Boolean,
    /** 会话 id → 标题（找得到的）。 */
    val conversations: Map<String, String>,
)

@Serializable
data class BookHistoryCompareDto(val old: String, val revision: String, val diff: ChapterDiff)

@Serializable
data class BookHistoryRestoreRequest(val path: String, val sha: String, val revision: String, val index: Int? = null)

@Serializable
data class BookHistoryRevisionDto(val revision: String)

@Serializable
data class BookHistoryCheckpointRequest(val path: String, val text: String)

@Serializable
data class BookTrashDto(val items: List<TrashedFile>)

@Serializable
data class BookTrashRestoreRequest(val key: String, val name: String, val path: String)

@Serializable
data class BookTrashRestoredDto(val path: String)

internal fun Route.bookHistoryRoutes(repository: WorkspaceRepository, services: WebBookServices) {
    val history = services.history
    val actions = services.historyActions
    suspend fun <T> versionApi(block: suspend () -> T): T = try {
        workspaceFileApiOperation(block)
    } catch (_: BookHistoryActions.VersionMissingException) {
        throw CodedApiException(io.ktor.http.HttpStatusCode.Gone, "HISTORY_VERSION_MISSING", "This version is missing or damaged")
    }
    route("/history") {
        get {
            val id = bookWorkspaceId(call, repository)
            val path = call.request.queryParameters["path"] ?: throw BadRequestException("Missing 'path'")
            val entries = history.entries(id, path).asReversed()
            val titles = entries.mapNotNull { (it.source as? HistorySource.Ai)?.conversationId }.distinct()
                .mapNotNull { conversation -> services.conversationTitle(conversation)?.let { conversation to it } }.toMap()
            call.respond(BookHistoryDto(entries, history.currentWriter(id, path), history.paused(id), titles))
        }
        get("/compare") {
            val id = bookWorkspaceId(call, repository)
            val path = call.request.queryParameters["path"] ?: throw BadRequestException("Missing 'path'")
            val sha = call.request.queryParameters["sha"] ?: throw BadRequestException("Missing 'sha'")
            val comparison = versionApi { actions.compare(id, path, sha) }
            call.respond(BookHistoryCompareDto(comparison.old, comparison.current.revision, comparison.diff))
        }
        post("/restore") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookHistoryRestoreRequest>()
            call.respond(BookHistoryRevisionDto(versionApi { actions.restore(id, request.path, request.sha, request.revision) }))
        }
        post("/take-back") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookHistoryRestoreRequest>()
            val index = request.index ?: throw BadRequestException("Missing 'index'")
            call.respond(BookHistoryRevisionDto(versionApi { actions.takeBack(id, request.path, request.sha, index, request.revision) }))
        }
        post("/checkpoint") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookHistoryCheckpointRequest>()
            history.checkpoint(id, request.path, request.text)
            call.respond(mapOf("ok" to true))
        }
    }
    route("/trash") {
        get {
            val id = bookWorkspaceId(call, repository)
            call.respond(BookTrashDto(history.trashed(id)))
        }
        post("/restore") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookTrashRestoreRequest>()
            val path = bookApi { actions.restoreTrashed(id, request.key, request.name, request.path) } ?: throw NotFoundException("Not in the trash")
            call.respond(BookTrashRestoredDto(path))
        }
    }
}
