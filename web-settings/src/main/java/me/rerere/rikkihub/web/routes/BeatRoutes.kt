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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.data.ai.book.BeatProposal
import me.rerere.rikkihub.data.ai.book.BeatWriter
import me.rerere.rikkihub.data.ai.book.BookConfig
import me.rerere.rikkihub.data.book.BeatPlacement
import me.rerere.rikkihub.data.book.BookChapterEntry
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.ChapterNotes
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import kotlin.uuid.Uuid

@Serializable
data class BeatProposalDto(val text: String, val words: Int = 0)

@Serializable
data class BeatWritingDto(
    val running: Boolean = false,
    val splitting: Boolean = false,
    val current: String? = null,
    val text: String = "",
    val queue: List<String> = emptyList(),
    val proposal: List<BeatProposalDto>? = null,
    val error: String? = null,
    val aiError: String? = null,
)

/** [beats] 为空 = 把还没有稿的都写出来。 */
@Serializable
data class BeatWriteRequest(val beats: List<String> = emptyList(), val note: String = "", val modelId: String? = null)

@Serializable
data class BeatProposalRequest(val beats: List<BeatProposalDto>, val replace: Boolean = true)

@Serializable
data class BeatOpRequest(val op: String, val beat: String, val draft: String? = null)

/** [beats] 为空 = 所有还没写进正文的；[cursor] 为空 = 按顺序放。 */
@Serializable
data class BeatPlaceRequest(val text: String, val beats: List<String> = emptyList(), val cursor: Int? = null)

@Serializable
data class BeatInsertionDto(val beat: String, val offset: Int, val text: String, val block: String)

@Serializable
data class BeatInsertionsDto(val insertions: List<BeatInsertionDto>)

@Serializable
data class BeatReplacementDto(val from: Int, val to: Int, val text: String)

/** [found]：写进过的那段还在正文里；[replacement]：采用稿换过、可以替换。 */
@Serializable
data class BeatPresenceDto(val found: Boolean, val replacement: BeatReplacementDto? = null)

@Serializable
data class BeatPresencesDto(val beats: Map<String, BeatPresenceDto>)

@Serializable
data class BeatInsertedRequest(val blocks: Map<String, String>)

@Serializable
data class BookConfigDto(val modelId: String? = null, val modelMissing: Boolean, val reasoningLevel: String, val temperature: Float? = null)

/**
 * 节拍写作：
 *   GET  /api/workspaces/{id}/book/notes/{chapter}/writing       -> 正在写什么、写到哪
 *   POST …/write {beats?, note?, modelId?} · …/split · …/writing/stop · …/writing/dismiss
 *   POST …/proposal {beats, replace}        -> 采用拆好的节拍
 *   POST …/beat {op: choose|lock|unlock|removeDraft|forget, beat, draft?}
 *   POST …/place {text, beats?, cursor?}    -> 每处插入（位置是前面几处插好之后的）
 *   POST …/presence {text}                  -> 每个写进过正文的节拍现在怎样
 *   POST …/inserted {blocks}                -> 记下写进正文的原文
 */
internal fun Route.beatRoutes(repository: WorkspaceRepository, services: WebBookServices) {
    val store = services.store
    val writer = services.writer

    class Target(val workspaceId: String, val entry: BookChapterEntry, val previousText: String) {
        val key get() = BeatWriter.Key(workspaceId, entry.id)
    }

    suspend fun target(call: ApplicationCall): Target {
        val workspaceId = bookWorkspaceId(call, repository)
        val book = bookApi { store.open(workspaceId) }
        val entry = book.entry(call.parameters["chapter"].orEmpty()) ?: throw NotFoundException("Chapter not found")
        val previous = book.entries.getOrNull(entry.number - 2)?.takeUnless { it.missing }
        return Target(workspaceId, entry, previous?.let { runCatching { store.read(workspaceId, it.path).content }.getOrNull() }.orEmpty())
    }

    fun style(target: Target, text: String) = BeatPlacement.styleOf(target.entry.path, listOf(text, target.previousText))

    suspend fun Target.edit(store: BookStore, transform: (ChapterNotes) -> ChapterNotes): ChapterNotesDto =
        notesApi { store.editNotes(workspaceId, entry.id, transform) }.toDto(entry)

    fun started(ok: Boolean) {
        if (!ok) throw CodedApiException(HttpStatusCode.Conflict, "BEATS_BUSY", "Nothing to write, or already writing")
    }

    route("/notes/{chapter}") {
        get("/writing") {
            val state = writer.states.value[target(call).key]
            call.respond(
                if (state == null) BeatWritingDto()
                else BeatWritingDto(
                    running = state.running,
                    splitting = state.splitting,
                    current = state.current,
                    text = state.text,
                    queue = state.queue,
                    proposal = state.proposal?.map { BeatProposalDto(it.text, it.words) },
                    error = state.error,
                    aiError = state.aiError?.name,
                ),
            )
        }
        post("/write") {
            val target = target(call)
            val request = call.receive<BeatWriteRequest>()
            val modelId = request.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() ?: throw BadRequestException("Invalid 'modelId'") }
            started(notesApi { if (request.beats.isEmpty()) writer.writeAll(target.key) else writer.write(target.key, request.beats, request.note, modelId) })
            call.respond(HttpStatusCode.Accepted)
        }
        post("/split") {
            started(writer.split(target(call).key))
            call.respond(HttpStatusCode.Accepted)
        }
        post("/writing/stop") {
            writer.stop(target(call).key)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/writing/dismiss") {
            writer.dismiss(target(call).key)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/proposal") {
            val target = target(call)
            val request = call.receive<BeatProposalRequest>()
            val beats = request.beats.map { BeatProposal(it.text.trim(), it.words.coerceAtLeast(0)) }.filter { it.text.isNotEmpty() }
            notesApi { writer.adoptProposal(target.key, beats, request.replace) }
            call.respond(notesApi { store.notes(target.workspaceId, target.entry.id) }.toDto(target.entry))
        }
        post("/beat") {
            val target = target(call)
            val request = call.receive<BeatOpRequest>()
            fun draft() = request.draft ?: throw BadRequestException("Missing 'draft'")
            call.respond(target.edit(store) { notes ->
                when (request.op) {
                    "choose" -> notes.updateBeat(request.beat) { beat -> if (beat.drafts.any { it.id == draft() }) beat.copy(chosen = draft()) else beat }
                    "lock" -> notes.updateBeat(request.beat) { it.copy(locked = true) }
                    "unlock" -> notes.updateBeat(request.beat) { it.copy(locked = false) }
                    "removeDraft" -> notes.removeDraft(request.beat, draft())
                    "forget" -> notes.updateBeat(request.beat) { it.copy(inserted = "") }
                    else -> throw BadRequestException("Unknown operation '${request.op}'")
                }
            })
        }
        post("/place") {
            val target = target(call)
            val request = call.receive<BeatPlaceRequest>()
            val notes = notesApi { store.notes(target.workspaceId, target.entry.id) }
            val beats = BeatPlacement.pending(notes).filter { request.beats.isEmpty() || it.id in request.beats }
            val insertions = BeatPlacement.place(request.text, notes, beats, style(target, request.text), request.cursor)
            call.respond(BeatInsertionsDto(insertions.map { BeatInsertionDto(it.beatId, it.offset, it.text, it.block) }))
        }
        post("/presence") {
            val target = target(call)
            val text = call.receive<BeatPlaceRequest>().text
            val style = style(target, text)
            val notes = notesApi { store.notes(target.workspaceId, target.entry.id) }
            call.respond(BeatPresencesDto(notes.beats.filter { it.inserted.isNotEmpty() }.associate { beat ->
                beat.id to BeatPresenceDto(
                    found = BeatPlacement.located(text, beat, style) != null,
                    replacement = BeatPlacement.replacement(text, beat, style)?.let { (range, block) -> BeatReplacementDto(range.first, range.last + 1, block) },
                )
            }))
        }
        post("/inserted") {
            val target = target(call)
            val blocks = call.receive<BeatInsertedRequest>().blocks
            call.respond(target.edit(store) { notes -> blocks.entries.fold(notes) { current, (id, block) -> current.updateBeat(id) { it.copy(inserted = block) } } })
        }
    }
}

private fun projectBookConfig(settings: Settings): BookConfigDto = BookConfigDto(
    modelId = settings.book.modelId?.toString(),
    modelMissing = settings.book.modelId?.let { settings.findModelById(it) == null } == true,
    reasoningLevel = settings.book.reasoningLevel.wireName(),
    temperature = settings.book.temperature,
)

internal fun mergeBookConfig(config: BookConfig, body: JsonObject, settings: Settings): BookConfig {
    var result = config
    if ("modelId" in body) {
        val raw = (body["modelId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (raw == null && body["modelId"] != JsonNull) throw BadRequestException("'modelId' must be a string or null")
        val id = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() ?: throw BadRequestException("Invalid 'modelId'") }
        if (id != null && id != config.modelId && settings.findModelById(id)?.type != ModelType.CHAT) throw BadRequestException("Unknown chat model")
        result = result.copy(modelId = id)
    }
    body["reasoningLevel"]?.let { value ->
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        result = result.copy(reasoningLevel = ReasoningLevel.entries.firstOrNull { it.wireName() == raw } ?: throw BadRequestException("Invalid 'reasoningLevel'"))
    }
    if ("temperature" in body) {
        val value = body["temperature"]
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
        if (value != JsonNull && (number == null || number !in BookConfig.MIN_TEMPERATURE..BookConfig.MAX_TEMPERATURE)) {
            throw BadRequestException("'temperature' is out of range")
        }
        result = result.copy(temperature = number)
    }
    return result
}

/** GET/PUT /api/book/config：作品功能用的模型（PUT 是 merge-patch {modelId?, reasoningLevel?, temperature?}）。 */
fun Route.bookConfigRoutes(settingsStore: SettingsStore) {
    route("/book/config") {
        get { call.respond(projectBookConfig(settingsStore.settingsFlow.value)) }
        put {
            val body = call.receive<JsonObject>()
            settingsStore.settingsFlow.value.let { mergeBookConfig(it.book, body, it) }
            var result: Settings? = null
            settingsStore.update { settings -> settings.copy(book = mergeBookConfig(settings.book, body, settings)).also { result = it } }
            call.respond(projectBookConfig(result ?: settingsStore.settingsFlow.value))
        }
    }
}
