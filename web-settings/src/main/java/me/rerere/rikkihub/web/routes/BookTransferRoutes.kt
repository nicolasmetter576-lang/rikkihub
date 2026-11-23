package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.book.transfer.BookImportException
import me.rerere.rikkihub.data.book.transfer.BookImportSource
import me.rerere.rikkihub.data.book.transfer.BookImporter
import me.rerere.rikkihub.data.book.transfer.EpubException
import me.rerere.rikkihub.data.book.transfer.ExportFormat
import me.rerere.rikkihub.data.book.transfer.ExportOptions
import me.rerere.rikkihub.data.book.transfer.ImportEdits
import me.rerere.rikkihub.data.book.transfer.SplitRule
import me.rerere.rikkihub.data.book.transfer.edited
import me.rerere.rikkihub.data.book.transfer.exportPlan
import me.rerere.rikkihub.data.book.transfer.rows
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import java.io.File
import java.net.URLEncoder
import kotlin.uuid.Uuid

@Serializable
data class BookImportOptionsDto(
    val charset: String? = null,
    /** auto / headings / blank / size / prefix */
    val rule: String = "auto",
    val count: Int = 0,
    val prefix: String = "",
    val titles: Map<Int, String> = emptyMap(),
    val merged: Set<Int> = emptySet(),
)

@Serializable
data class BookImportRowDto(val source: Int, val title: String, val words: Int, val volume: String? = null, val issues: List<String>)

@Serializable
data class BookImportPreviewDto(val isText: Boolean, val charset: String? = null, val rows: List<BookImportRowDto>)

private fun splitRule(options: BookImportOptionsDto): SplitRule = when (options.rule) {
    "auto" -> SplitRule.Auto
    "headings" -> SplitRule.Headings(options.count.coerceIn(1, 6))
    "blank" -> SplitRule.BlankLines(options.count.coerceAtLeast(1))
    "size" -> SplitRule.FixedSize(options.count.coerceAtLeast(100))
    "prefix" -> SplitRule.LinePrefix(options.prefix.ifBlank { throw BadRequestException("Missing 'prefix'") })
    else -> throw BadRequestException("Unknown rule '${options.rule}'")
}

/** 收下上传的文件交给 [block]，完了删掉。预览和导入都带着文件来，服务端不留状态。 */
private suspend fun <T> receiveImport(call: ApplicationCall, tempDir: File, block: suspend (BookImportSource, BookImportOptionsDto) -> T): T {
    val temp = File(tempDir, "book_import/${Uuid.random()}")
    var name: String? = null
    var options = BookImportOptionsDto()
    try {
        val multipart = call.receiveMultipart()
        while (true) {
            val part = multipart.readPart() ?: break
            try {
                when (part) {
                    is PartData.FormItem -> if (part.name == "options") {
                        options = runCatching { JsonInstant.decodeFromString<BookImportOptionsDto>(part.value) }
                            .getOrElse { throw BadRequestException("Invalid options") }
                    }
                    is PartData.FileItem -> if (name == null) {
                        name = part.originalFileName?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "book"
                        withContext(Dispatchers.IO) { part.provider().toInputStream().use { BookImportSource.copy(it, temp) } }
                    }
                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }
        val fileName = name ?: throw BadRequestException("No file uploaded")
        return withContext(Dispatchers.IO) { BookImportSource(temp, fileName).use { block(it, options) } }
    } catch (failure: BookImportException) {
        throw CodedApiException(HttpStatusCode.BadRequest, "IMPORT_${failure.reason.name}", "The file cannot be imported")
    } catch (failure: EpubException) {
        throw CodedApiException(HttpStatusCode.BadRequest, "IMPORT_${failure.reason.name}", "The EPUB cannot be imported")
    } finally {
        temp.delete()
    }
}

/**
 * 作品导入导出：
 *   POST /api/workspaces/{id}/book/import/preview  multipart(options, file) -> 拆出来的章节
 *   POST /api/workspaces/{id}/book/import          multipart(options, file) -> BookDto
 *   GET  /api/workspaces/{id}/book/export?format=txt|markdown|epub&volumeId&chapters&finalOnly&volumeTitles&indent&blankLines -> 文件
 */
internal fun Route.bookTransferRoutes(repository: WorkspaceRepository, services: WebBookServices) {
    val store = services.store
    route("/import") {
        post {
            val id = bookWorkspaceId(call, repository)
            val book = receiveImport(call, services.tempDir) { source, options ->
                val import = source.load(options.charset, splitRule(options)).edited(ImportEdits(options.titles, options.merged))
                bookApi { services.importer.import(id, import) }
            }
            call.respond(HttpStatusCode.Created, book.toDto())
        }
        post("/preview") {
            bookWorkspaceId(call, repository)
            call.respond(receiveImport(call, services.tempDir) { source, options ->
                val import = source.load(options.charset, splitRule(options))
                BookImportPreviewDto(
                    isText = source.isText,
                    charset = source.charset,
                    rows = import.rows(ImportEdits(options.titles, options.merged)).map { row ->
                        BookImportRowDto(row.source, row.title, row.words, row.volume, row.issues.map { it.name })
                    },
                )
            })
        }
    }
    get("/export") {
        val id = bookWorkspaceId(call, repository)
        val query = call.request.queryParameters
        fun flag(name: String, default: Boolean) = query[name]?.let { it == "true" } ?: default
        val format = ExportFormat.entries.firstOrNull { it.name.equals(query["format"], ignoreCase = true) } ?: throw BadRequestException("Invalid 'format'")
        val options = ExportOptions(
            format = format,
            volumeId = query["volumeId"],
            chapterIds = query["chapters"]?.split(',')?.toSet(),
            finalOnly = flag("finalOnly", false),
            volumeTitles = flag("volumeTitles", true),
            indent = flag("indent", true),
            blankLines = flag("blankLines", false),
        )
        val book = bookApi { store.open(id) }
        if (exportPlan(book, options).chapters.isEmpty()) throw BadRequestException("No chapters to export")
        // 先写完再发，中途失败浏览器不会得到半本书
        val temp = File(services.tempDir, "book_export/${Uuid.random()}").apply { parentFile?.mkdirs() }
        try {
            withContext(Dispatchers.IO) { temp.outputStream().use { store.exporter(id).export(book, options, it) } }
            val name = BookImporter.safeName(book.manifest.title).ifBlank { "book" } + "." + format.extension
            call.response.header("Content-Disposition", "attachment; filename*=UTF-8''${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}")
            call.respondOutputStream(ContentType.parse(format.mime), contentLength = temp.length()) {
                temp.inputStream().use { it.copyTo(this) }
            }
        } finally {
            temp.delete()
        }
    }
}
