package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.book.history.BookLocks
import me.rerere.rikkihub.data.book.history.FileLocks
import me.rerere.rikkihub.data.book.history.LockNotice
import me.rerere.rikkihub.data.book.history.LockRefusedException
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException

/*
 * 定稿锁（App 同一套引擎）。网页端能加锁、看锁和提醒、放回；解锁、关提醒、“保留这次修改”只在 App 里，
 * 免得工作区里的命令经本机接口解锁或抹掉提醒。网页端加的锁记“来自网页端”。
 *
 *   GET  /api/workspaces/{id}/book/locks             -> {locks: [BookFileLockDto], notices, broken}
 *   GET  /api/workspaces/{id}/book/locks?path=       -> BookFileLockDto（含段落在现在稿子里的位置与 revision）
 *   POST /api/workspaces/{id}/book/locks/chapter     {path, revision} -> BookFileLockDto
 *   POST /api/workspaces/{id}/book/locks/passages    {path, revision, start, end} -> BookFileLockDto
 *   POST /api/workspaces/{id}/book/locks/notices/put-back {id, path?, revision?} -> {done}（比较页上放回带比较时的版本号）
 *
 * 稿子在读之后变了：409 FILE_REVISION_CONFLICT；选中的文字还没保存：409 LOCK_TEXT_CHANGED；不能加锁：400 LOCK_TOO_SHORT / LOCK_OVERLAP / LOCK_EMPTY / LOCK_NOT_TRACKED / LOCK_TOO_LARGE。
 */

@Serializable
data class BookPassageLockDto(
    val id: String,
    /** 开头 20 字。 */
    val head: String,
    val web: Boolean,
    val paused: Boolean,
    /** 在 [BookFileLockDto.revision] 那一版稿子里的位置（UTF-16，含头不含尾）；找不到时为 null。 */
    val start: Int? = null,
    val end: Int? = null,
)

@Serializable
data class BookFileLockDto(
    val path: String,
    val chapter: Boolean,
    val chapterWeb: Boolean = false,
    val passages: List<BookPassageLockDto>,
    val revision: String? = null,
)

@Serializable
data class BookLocksDto(val locks: List<BookFileLockDto>, val notices: List<LockNotice>, val broken: Boolean)

@Serializable
data class BookLockChapterRequest(val path: String, val revision: String)

@Serializable
data class BookLockPassagesRequest(
    val path: String,
    val revision: String,
    val start: Int,
    val end: Int,
    /** 网页编辑器里选中的文字：和磁盘上那一段对不上（还没保存）就不加锁。 */
    val text: String? = null,
)

@Serializable
data class BookNoticeRequest(
    val id: String,
    /** 比较页上放回：比较时稿子的版本号（文件路径另给）；稿子变了 409。 */
    val path: String? = null,
    val revision: String? = null,
)

@Serializable
data class BookPutBackDto(val done: Boolean)

private fun FileLocks?.toDto(path: String, text: String? = null, revision: String? = null): BookFileLockDto {
    val passages = this?.passages.orEmpty()
    val ranges = text?.let { BookLocks.rangesIn(it, passages.filter { p -> p.dormantSince == null }) }.orEmpty()
    return BookFileLockDto(
        path = path,
        chapter = this?.chapter != null,
        chapterWeb = this?.chapter?.web == true,
        passages = passages.map { passage ->
            val range = ranges[passage.id]
            BookPassageLockDto(
                id = passage.id,
                head = passage.lines.firstOrNull().orEmpty().trim().take(20),
                web = passage.web,
                paused = passage.dormantSince != null,
                start = range?.first,
                end = range?.let { it.last + 1 },
            )
        },
        revision = revision,
    )
}

internal fun Route.bookLockRoutes(repository: WorkspaceRepository, services: WebBookServices) {
    val history = services.history
    val actions = services.historyActions
    val store = services.store
    // 拒绝加锁是 IllegalArgumentException 的子类：要在通用的文件错误映射之前接住
    suspend fun <T> lockApi(block: suspend () -> T): T = workspaceFileApiOperation {
        try {
            block()
        } catch (refused: LockRefusedException) {
            throw CodedApiException(HttpStatusCode.BadRequest, "LOCK_${refused.reason.name}", "Cannot lock this text")
        }
    }
    /** 这个文件现在的锁，连同段落在现在稿子里的位置。 */
    suspend fun fileLocks(id: String, path: String): BookFileLockDto {
        val snapshot = workspaceFileApiOperation { store.read(id, path) }
        return history.locksOf(id, path).toDto(path, snapshot.content, snapshot.revision)
    }
    route("/locks") {
        get {
            val id = bookWorkspaceId(call, repository)
            val path = call.request.queryParameters["path"]
            if (path != null) {
                call.respond(fileLocks(id, path))
            } else {
                val locks = history.locks(id).map { (file, locks) -> locks.toDto(file) }
                call.respond(BookLocksDto(locks, history.notices(id), history.locksBroken(id)))
            }
        }
        post("/chapter") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookLockChapterRequest>()
            lockApi { actions.lockChapter(id, request.path, request.revision, web = true) }
            call.respond(fileLocks(id, request.path))
        }
        post("/passages") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookLockPassagesRequest>()
            if (request.start < 0 || request.end < request.start) throw BadRequestException("Invalid range")
            // 网页编辑器给的是它那份文字里的位置：按归一后的文字换算
            lockApi {
                val text = store.read(id, request.path).content
                if (request.end > text.length) throw BadRequestException("Invalid range")
                if (request.text != null && text.substring(request.start, request.end) != request.text) {
                    throw CodedApiException(HttpStatusCode.Conflict, "LOCK_TEXT_CHANGED", "Save the file before locking")
                }
                val start = BookLocks.normalize(text.substring(0, request.start)).length
                val end = BookLocks.normalize(text.substring(0, request.end)).length
                actions.lockPassage(id, request.path, request.revision, start, end, web = true)
            }
            call.respond(fileLocks(id, request.path))
        }
        post("/notices/put-back") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookNoticeRequest>()
            call.respond(BookPutBackDto(workspaceFileApiOperation {
                val expected = request.revision?.let { revision ->
                    val current = store.read(id, request.path ?: throw BadRequestException("Missing 'path'"))
                    if (current.revision != revision) throw me.rerere.workspace.WorkspaceFileRevisionConflictException()
                    current.content
                }
                history.putBack(id, request.id, expected)
            }))
        }
    }
}
