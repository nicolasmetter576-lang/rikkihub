package me.rerere.rikkihub.web.routes

import androidx.core.net.toUri
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.files.FileManagerDeviceAccess
import me.rerere.rikkihub.data.files.FileManagerRoot
import me.rerere.rikkihub.data.files.FileManagerService
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.ForbiddenException
import me.rerere.rikkihub.web.dto.UploadFilesResponseDto
import me.rerere.rikkihub.web.dto.UploadedFileDto
import me.rerere.rikkihub.web.guessWebFileMime
import me.rerere.workspace.WorkspaceDeleteResult
import me.rerere.workspace.WorkspaceExtractResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceTransferMode
import me.rerere.workspace.WorkspaceTransferResult
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class FileManagerVolumeDto(val id: String, val label: String, val primary: Boolean)

@Serializable
data class FileManagerRootsDto(
    /** READY / PERMISSION_REQUIRED (granted in the App only) / PASSWORD_REQUIRED (web access password is off). */
    val deviceAccess: String,
    val volumes: List<FileManagerVolumeDto>,
)

@Serializable
data class FileManagerListDto(val entries: List<WorkspaceFileEntry>)

@Serializable
data class FileManagerTextDto(val content: String, val revision: String)

@Serializable
data class FileManagerRevisionDto(val revision: String)

private const val MAX_UPLOAD_PARTS = 64
private const val MAX_ATTACH_FILES = 32

/**
 * The file manager over HTTP (`/api/file-manager`): the same [FileManagerService] as the App.
 *
 * `root` is `library` or `device:<volumeId>`. Phone storage is only served while the web access
 * password is on: without it anyone on the network could read and change the whole phone.
 */
fun Route.fileManagerRoutes(
    service: FileManagerService,
    filesManager: FilesManager,
    settingsStore: SettingsStore,
) {
    fun deviceAllowed(): Boolean = settingsStore.settingsFlow.value.let {
        it.webServerJwtEnabled && it.webServerAccessPassword.isNotBlank()
    }

    fun parseRoot(raw: String?): FileManagerRoot {
        val root = parseFileManagerRoot(raw ?: throw BadRequestException("Missing root"))
        if (root is FileManagerRoot.Device && !deviceAllowed()) {
            throw ForbiddenException("Phone storage needs the web access password")
        }
        return root
    }

    route("/file-manager") {
        get("/roots") {
            val access = when {
                !deviceAllowed() -> "PASSWORD_REQUIRED"
                service.deviceAccess() == FileManagerDeviceAccess.READY -> "READY"
                else -> "PERMISSION_REQUIRED"
            }
            call.respond(FileManagerRootsDto(
                deviceAccess = access,
                volumes = service.volumes().map { FileManagerVolumeDto(it.id, it.label, it.primary) },
            ))
        }

        get("/list") {
            val root = parseRoot(call.request.queryParameters["root"])
            val path = call.request.queryParameters["path"].orEmpty()
            call.respond(FileManagerListDto(fileOperation { service.list(root, path) }))
        }

        post("/mkdir") {
            val body = readWorkspaceSelectionBody(call)
            val entry: WorkspaceFileEntry = fileOperation { service.createFolder(parseRoot(body.string("root")), body.requireString("path")) }
            call.respond(HttpStatusCode.Created, entry)
        }

        post("/create-file") {
            val body = readWorkspaceSelectionBody(call)
            val entry: WorkspaceFileEntry = fileOperation { service.createFile(parseRoot(body.string("root")), body.requireString("path")) }
            call.respond(HttpStatusCode.Created, entry)
        }

        post("/rename") {
            val body = readWorkspaceSelectionBody(call)
            val entry: WorkspaceFileEntry = fileOperation {
                service.rename(parseRoot(body.string("root")), body.requireString("path"), body.requireString("name"))
            }
            call.respond(entry)
        }

        post("/delete") {
            val body = readWorkspaceSelectionBody(call)
            val root = parseRoot(body.string("root"))
            val result: WorkspaceDeleteResult = fileOperation { service.delete(root, body.paths()) }
            call.respond(result)
        }

        post("/transfer") {
            val body = readWorkspaceSelectionBody(call)
            val mode = when (body.string("mode")) {
                "copy" -> WorkspaceTransferMode.COPY
                "move" -> WorkspaceTransferMode.MOVE
                else -> throw BadRequestException("mode must be copy or move")
            }
            val result: WorkspaceTransferResult = fileOperation {
                service.transfer(parseRoot(body.string("from")), body.paths(), parseRoot(body.string("to")), body.string("targetDir").orEmpty(), mode)
            }
            call.respond(result)
        }

        post("/zip") {
            val body = readWorkspaceSelectionBody(call)
            val entry: WorkspaceFileEntry = fileOperation {
                service.zip(parseRoot(body.string("root")), body.string("dir").orEmpty(), body.paths(), body.requireString("name"))
            }
            call.respond(HttpStatusCode.Created, entry)
        }

        post("/extract") {
            val body = readWorkspaceSelectionBody(call)
            val result: WorkspaceExtractResult = fileOperation {
                service.extract(
                    parseRoot(body.string("root")),
                    body.requireString("path"),
                    body.string("password")?.takeIf { it.isNotEmpty() }?.toCharArray(),
                    body.string("nameEncoding"),
                )
            }
            call.respond(result)
        }

        get("/text") {
            val root = parseRoot(call.request.queryParameters["root"])
            val path = call.request.queryParameters["path"] ?: throw BadRequestException("Missing path")
            val snapshot = fileOperation { service.readText(root, path) }
            call.respond(FileManagerTextDto(snapshot.content, snapshot.revision))
        }

        put("/text") {
            val body = readWorkspaceSelectionBody(call)
            val result = fileOperation {
                service.writeText(parseRoot(body.string("root")), body.requireString("path"), body.requireString("content"), body.requireString("revision"))
            }
            call.respond(FileManagerRevisionDto(result.revision))
        }

        // Streams one file; `download=1` asks the browser to save it instead of showing it.
        get("/file") {
            val root = parseRoot(call.request.queryParameters["root"])
            val path = call.request.queryParameters["path"] ?: throw BadRequestException("Missing path")
            val file = fileOperation { requireFile(service.file(root, path), path) }
            val disposition = if (call.request.queryParameters["download"] == "1") "attachment" else "inline"
            call.response.header("Content-Disposition", "$disposition; filename*=UTF-8''${encodeName(file.name)}")
            // Shown inline from the app origin: never let a served file run script.
            call.response.header("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; sandbox")
            call.respondOutputStream(io.ktor.http.ContentType.parse(guessWebFileMime(file.name))) {
                file.inputStream().use { it.copyTo(this) }
            }
        }

        // Several items as one zip download (folders included), built on the fly.
        post("/download-zip") {
            val body = readWorkspaceSelectionBody(call)
            val root = parseRoot(body.string("root"))
            // Named as listed: a workspace folder's files area is named after the workspace, not its own folder.
            val files = fileOperation { body.paths().map { it.trimEnd('/').substringAfterLast('/') to service.file(root, it) } }
            files.forEach { (_, file) -> if (!file.exists()) throw CodedApiException(HttpStatusCode.NotFound, WorkspaceFileOperationException.FILE_NOT_FOUND, "Not found") }
            call.response.header("Content-Disposition", "attachment; filename*=UTF-8''${encodeName(body.string("name") ?: "files.zip")}")
            call.respondOutputStream(io.ktor.http.ContentType.Application.Zip) {
                ZipOutputStream(this).use { zip ->
                    for ((name, file) in files) addToZip(zip, file, name)
                }
            }
        }

        post("/upload") {
            val root = parseRoot(call.request.queryParameters["root"])
            val dir = call.request.queryParameters["path"].orEmpty()
            val multipart = call.receiveMultipart()
            val uploaded = mutableListOf<WorkspaceFileEntry>()
            var parts = 0
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (++parts > MAX_UPLOAD_PARTS) throw BadRequestException("Too many upload parts (max $MAX_UPLOAD_PARTS)")
                    if (part is PartData.FileItem) {
                        val name = part.originalFileName?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "file"
                        uploaded += fileOperation { service.import(root, dir, name, part.provider().toInputStream()) }
                    }
                } finally {
                    part.dispose()
                }
            }
            if (uploaded.isEmpty()) throw BadRequestException("No files uploaded")
            call.respond(HttpStatusCode.Created, FileManagerListDto(uploaded))
        }

        // Turns files into chat attachments (hard link or copy into uploads), same as the App picker.
        post("/attach") {
            val body = readWorkspaceSelectionBody(call)
            val root = parseRoot(body.string("root"))
            val paths = body.paths()
            if (paths.size > MAX_ATTACH_FILES) throw BadRequestException("Too many files (max $MAX_ATTACH_FILES)")
            val files = fileOperation { paths.map { requireFile(service.file(root, it), it) } }
            val result = filesManager.createChatPartsFromLocalFiles(files)
            call.respond(FileManagerAttachDto(
                files = result.attachments.map { attachment ->
                    UploadedFileDto(
                        id = attachment.file.id,
                        url = filesManager.getFile(attachment.file).toUri().toString(),
                        fileName = attachment.file.displayName,
                        mime = attachment.file.mimeType,
                        size = attachment.file.sizeBytes,
                    )
                },
                unsupported = result.unsupported,
                failed = result.failed,
            ))
        }
    }
}

@Serializable
data class FileManagerAttachDto(
    val files: List<UploadedFileDto>,
    val unsupported: List<String>,
    val failed: List<String>,
)

internal fun parseFileManagerRoot(raw: String): FileManagerRoot = when {
    raw == "library" -> FileManagerRoot.Library
    raw.startsWith("device:") && raw.length > "device:".length -> FileManagerRoot.Device(raw.removePrefix("device:"))
    else -> throw BadRequestException("Invalid root: $raw")
}

/** Workspace file errors become coded HTTP errors the web UI translates; nothing else is caught. */
private suspend fun <T> fileOperation(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: WorkspaceFileOperationException) {
    val status = when (error.code) {
        WorkspaceFileOperationException.FILE_NOT_FOUND, WorkspaceFileOperationException.PARENT_NOT_FOUND -> HttpStatusCode.NotFound
        WorkspaceFileOperationException.FILE_ALREADY_EXISTS, WorkspaceFileOperationException.FILE_REVISION_CONFLICT -> HttpStatusCode.Conflict
        WorkspaceFileOperationException.FILE_PERMISSION_DENIED, FileManagerService.READ_ONLY_FOLDER -> HttpStatusCode.Forbidden
        WorkspaceFileOperationException.CONTENT_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        else -> HttpStatusCode.BadRequest
    }
    throw CodedApiException(status, error.code, error.message ?: error.code)
} catch (error: IllegalArgumentException) {
    throw BadRequestException(error.message ?: "Invalid request")
}

private fun requireFile(file: File, path: String): File {
    if (!file.isFile) throw CodedApiException(HttpStatusCode.NotFound, WorkspaceFileOperationException.FILE_NOT_FOUND, "Not a file: $path")
    return file
}

private fun addToZip(zip: ZipOutputStream, file: File, name: String) {
    if (java.nio.file.Files.isSymbolicLink(file.toPath())) return
    if (file.isDirectory) {
        zip.putNextEntry(ZipEntry("$name/"))
        zip.closeEntry()
        file.listFiles().orEmpty().forEach { addToZip(zip, it, "$name/${it.name}") }
    } else if (file.isFile) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}

private fun encodeName(name: String) = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.requireString(key: String): String = string(key) ?: throw BadRequestException("Missing $key")

private fun JsonObject.paths(): List<String> = extractWorkspaceSelectionPaths(JsonObject(mapOf("paths" to (this["paths"] ?: throw BadRequestException("Missing paths")))))
