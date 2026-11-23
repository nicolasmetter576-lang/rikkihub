package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.book.OutlineProgress
import me.rerere.rikkihub.data.ai.book.OutlineTask
import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatState
import me.rerere.rikkihub.data.book.BookChapterEntry
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.ChapterNotes
import me.rerere.rikkihub.data.book.ChapterNotesException
import me.rerere.rikkihub.data.book.OutlineFile
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.OutlineRow
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException

@Serializable
data class OutlineChapterDto(
    val id: String,
    val number: Int,
    val title: String,
    val path: String,
    val beats: Int,
    val points: Int,
    val extracted: Boolean,
    val stale: Boolean,
    val pending: Boolean,
    val comparePending: Boolean,
    val changed: Int,
    val missing: Int,
)

/** [current] 为空 = 已停下，原因在 [error] 或 [aiError]。 */
@Serializable
data class OutlineProgressDto(val total: Int, val done: Int, val current: String? = null, val task: String? = null, val error: String? = null, val aiError: String? = null)

@Serializable
data class BookOutlineDto(val chapters: List<OutlineChapterDto>, val progress: OutlineProgressDto? = null)

@Serializable
data class BeatDraftDto(val id: String, val text: String, val model: String, val note: String, val at: Long, val outdated: Boolean)

@Serializable
data class BeatDto(
    val id: String,
    val text: String,
    val words: Int,
    val drafts: List<BeatDraftDto>,
    val chosen: String? = null,
    val locked: Boolean,
    val inserted: String,
)

@Serializable
data class PlotPointDto(val id: String, val text: String, val quote: String)

@Serializable
data class BeatMatchDto(val beat: String, val state: String, val points: List<String>, val note: String)

@Serializable
data class ChapterNotesDto(
    val beats: List<BeatDto>,
    val extracted: Boolean,
    val stale: Boolean,
    val synopsis: String,
    val points: List<PlotPointDto>,
    val compared: Boolean,
    val compareStale: Boolean,
    val matches: List<BeatMatchDto>,
)

@Serializable
data class BeatEditDto(val id: String? = null, val text: String, val words: Int = 0)

@Serializable
data class BeatsRequest(val beats: List<BeatEditDto>)

@Serializable
data class OutlineTaskDto(val type: String, val id: String)

@Serializable
data class OutlineRunRequest(val tasks: List<OutlineTaskDto>)

@Serializable
data class OutlineFileRequest(val path: String)

@Serializable
data class OutlineSectionDto(val title: String, val beats: List<String>, val chapterId: String? = null)

@Serializable
data class OutlineSectionsDto(val sections: List<OutlineSectionDto>)

internal fun ChapterNotes.toDto(entry: BookChapterEntry): ChapterNotesDto {
    val row = OutlineRow(entry, this)
    return ChapterNotesDto(
        beats = beats.map { beat ->
            BeatDto(
                id = beat.id,
                text = beat.text,
                words = beat.words,
                drafts = beat.drafts.map { BeatDraftDto(it.id, it.text, it.model, it.note, it.at, outdated = it.basis != basisOf(beat.id)) },
                chosen = beat.chosen,
                locked = beat.locked,
                inserted = beat.inserted,
            )
        },
        extracted = row.extracted,
        stale = row.stale,
        synopsis = outline?.synopsis.orEmpty(),
        points = outline?.points.orEmpty().map { PlotPointDto(it.id, it.text, it.quote) },
        compared = comparison != null,
        compareStale = comparisonStale,
        matches = comparison?.matches.orEmpty().map { BeatMatchDto(it.beatId, it.state.id, it.pointIds, it.note) },
    )
}

private fun OutlineProgress.toDto() = OutlineProgressDto(
    total = total,
    done = done,
    current = current?.chapterId,
    task = when (current) {
        is OutlineTask.Extract -> "extract"
        is OutlineTask.Compare -> "compare"
        null -> null
    },
    error = error,
    aiError = aiError?.name,
)

internal suspend fun <T> notesApi(block: suspend () -> T): T = try {
    bookApi(block)
} catch (broken: ChapterNotesException) {
    throw CodedApiException(HttpStatusCode.Conflict, "NOTES_${broken.reason.name}", "The chapter notes cannot be read")
}

/**
 * 反向大纲：
 *   GET  /api/workspaces/{id}/book/outline               -> 每章的状态 + 正在跑的任务
 *   POST /api/workspaces/{id}/book/outline/run {tasks}   -> 排队执行；正在跑时 409
 *   POST /api/workspaces/{id}/book/outline/stop
 *   POST /api/workspaces/{id}/book/outline/file {path}   -> 大纲文件拆成的各章节拍（不写）
 *   POST /api/workspaces/{id}/book/outline/import {sections} -> 接到各章计划末尾
 *   GET  /api/workspaces/{id}/book/notes/{chapter}
 *   PUT  /api/workspaces/{id}/book/notes/{chapter}/beats {beats}  -> 整份计划（顺序、文字、字数）；已有节拍的草稿保留
 *   POST /api/workspaces/{id}/book/notes/{chapter}/plan-from-points
 */
internal fun Route.bookOutlineRoutes(repository: WorkspaceRepository, services: WebBookServices) {
    val store = services.store
    val outliner = services.outliner

    suspend fun chapter(call: ApplicationCall, workspaceId: String): BookChapterEntry {
        val id = call.parameters["chapter"] ?: throw BadRequestException("Missing chapter")
        return bookApi { store.open(workspaceId) }.entry(id) ?: throw NotFoundException("Chapter not found")
    }

    route("/outline") {
        get {
            val id = bookWorkspaceId(call, repository)
            val book = bookApi { store.open(id) }
            val chapters = book.entries.filterNot { it.missing }.map { entry ->
                val row = OutlineRow(entry, runCatching { store.notes(id, entry.id) }.getOrDefault(ChapterNotes()))
                OutlineChapterDto(
                    id = entry.id,
                    number = entry.number,
                    title = entry.title,
                    path = entry.path,
                    beats = row.notes.beats.size,
                    points = row.notes.outline?.points.orEmpty().size,
                    extracted = row.extracted,
                    stale = row.stale,
                    pending = row.pending,
                    comparePending = row.comparePending,
                    changed = row.count(BeatState.CHANGED),
                    missing = row.count(BeatState.MISSING),
                )
            }
            call.respond(BookOutlineDto(chapters, outliner.progress.value[id]?.toDto()))
        }
        post("/run") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<OutlineRunRequest>()
            val book = bookApi { store.open(id) }
            val tasks = request.tasks.map { task ->
                if (book.entry(task.id) == null) throw NotFoundException("Chapter not found")
                when (task.type) {
                    "extract" -> OutlineTask.Extract(task.id)
                    "compare" -> OutlineTask.Compare(task.id)
                    else -> throw BadRequestException("Unknown task '${task.type}'")
                }
            }
            if (tasks.isEmpty()) throw BadRequestException("No tasks")
            if (!outliner.start(id, tasks)) throw CodedApiException(HttpStatusCode.Conflict, "OUTLINE_RUNNING", "Already running")
            call.respond(HttpStatusCode.Accepted)
        }
        post("/stop") {
            val id = bookWorkspaceId(call, repository)
            outliner.stop(id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/file") {
            val id = bookWorkspaceId(call, repository)
            val path = call.receive<OutlineFileRequest>().path
            val book = bookApi { store.open(id) }
            if (path !in book.unlisted && path !in book.manifest.ignored) throw BadRequestException("Not a text file of this book: $path")
            val text = workspaceFileApiOperation { store.read(id, path) }.content
            call.respond(OutlineSectionsDto(OutlineFile.parse(text, book).map { OutlineSectionDto(it.title, it.beats, it.chapterId) }))
        }
        post("/import") {
            val id = bookWorkspaceId(call, repository)
            val sections = call.receive<OutlineSectionsDto>().sections.filter { it.chapterId != null }.groupBy { it.chapterId!! }
            val book = bookApi { store.open(id) }
            if (sections.keys.any { book.entry(it) == null }) throw NotFoundException("Chapter not found")
            notesApi { sections.forEach { (chapterId, parts) -> store.editNotes(id, chapterId) { it.addBeats(parts.flatMap { part -> part.beats }) } } }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/notes/{chapter}") {
        get {
            val id = bookWorkspaceId(call, repository)
            val entry = chapter(call, id)
            call.respond(notesApi { store.notes(id, entry.id) }.toDto(entry))
        }
        put("/beats") {
            val id = bookWorkspaceId(call, repository)
            val entry = chapter(call, id)
            val beats = call.receive<BeatsRequest>().beats
            if (beats.any { it.text.isBlank() }) throw BadRequestException("A beat needs text")
            val notes = notesApi {
                store.editNotes(id, entry.id) { current ->
                    val known = current.beats.associateBy { it.id }
                    current.copy(beats = beats.distinctBy { it.id ?: it }.map { item ->
                        (item.id?.let(known::get) ?: Beat(BookManifest.newId(), "")).copy(text = item.text.trim(), words = item.words.coerceAtLeast(0))
                    })
                }
            }
            call.respond(notes.toDto(entry))
        }
        post("/plan-from-points") {
            val id = bookWorkspaceId(call, repository)
            val entry = chapter(call, id)
            call.respond(notesApi { store.editNotes(id, entry.id) { notes -> notes.addBeats(notes.outline?.points.orEmpty().map { it.text }) } }.toDto(entry))
        }
    }
}
