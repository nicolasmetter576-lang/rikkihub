package me.rerere.rikkihub.web.routes

import android.content.Context
import androidx.core.net.toUri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import me.rerere.rikkihub.data.db.entity.ManagedFileEntity
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.files.saveUploadFromBytes
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.respondManagedWebFile
import me.rerere.rikkihub.web.resolveManagedWebFile
import me.rerere.rikkihub.web.dto.UploadFilesResponseDto
import me.rerere.rikkihub.web.dto.UploadedFileDto
import java.io.ByteArrayOutputStream
import java.io.File

private const val MAX_UPLOAD_FILE_SIZE_BYTES = 20 * 1024 * 1024 // 20 MB
private const val MAX_UPLOAD_PARTS = 32 // bounded multipart: parts beyond this are rejected

fun Route.filesRoutes(
    filesManager: FilesManager,
    context: Context
) {
    route("/files") {
        // POST /api/files/upload - Upload files
        post("/upload") {
            val multipart = call.receiveMultipart()
            val uploadedFiles = mutableListOf<UploadedFileDto>()
            var partCount = 0

            while (true) {
                val part = multipart.readPart() ?: break
                partCount += 1
                try {
                    if (partCount > MAX_UPLOAD_PARTS) {
                        throw BadRequestException("Too many upload parts (max $MAX_UPLOAD_PARTS)")
                    }
                    when (part) {
                        is PartData.FileItem -> {
                            val originalFileName = part.originalFileName
                                ?.takeIf { it.isNotBlank() }
                                ?: "file"
                            val displayName = sanitizeDisplayName(originalFileName)
                            val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                            val bytes = readPartBytes(part, MAX_UPLOAD_FILE_SIZE_BYTES)
                            if (bytes.isEmpty()) {
                                throw BadRequestException("Uploaded file is empty")
                            }

                            val entity = filesManager.saveUploadFromBytes(
                                bytes = bytes,
                                displayName = displayName,
                                mimeType = mimeType,
                            )
                            uploadedFiles.add(entity.toUploadedFileDto(filesManager))
                        }

                        else -> {
                            // Ignore non-file fields.
                        }
                    }
                } finally {
                    part.dispose()
                }
            }

            if (uploadedFiles.isEmpty()) {
                throw BadRequestException("No files uploaded")
            }

            call.respond(
                status = HttpStatusCode.Created,
                message = UploadFilesResponseDto(files = uploadedFiles)
            )
        }

        // DELETE /api/files/{id} - Delete managed upload file
        delete("/{id}") {
            val idParam = call.pathParameters["id"]
                ?: throw BadRequestException("Missing file id")
            val id = idParam.toLongOrNull()
                ?: throw BadRequestException("Invalid file id")

            // Managed-upload only, TOCTOU-safe: the canonical ownership check
            // and the delete run inside one locked critical section
            // (FilesManager.deleteManagedUpload) — a row rewritten between
            // resolve and delete can no longer slip an arbitrary path past it.
            val deleted = filesManager.deleteManagedUpload(id)
            if (!deleted) {
                throw NotFoundException("File not found")
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        // GET /api/files/id/{id} - Get managed upload file by ID
        get("/id/{id}") {
            val idParam = call.pathParameters["id"]
                ?: throw BadRequestException("Missing file id")
            val id = idParam.toLongOrNull()
                ?: throw BadRequestException("Invalid file id")

            // Managed-upload only (DB row + canonical subtree check).
            val (entity, file) = filesManager.resolveUploadEntity(id)
                ?: throw NotFoundException("File not found")

            call.respondManagedWebFile(file, entity.mimeType)
        }

        // GET /api/files/path/{...} - Get file by relative path.
        // Admission is restricted to flat, managed resource subtrees
        // (upload/images/fonts + subagent_runs/tool_outputs logs); see
        // WebFileAccessPolicy. Arbitrary filesDir access is denied.
        get("/path/{path...}") {
            val relativePath = call.pathParameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing file path")

            val (normalizedPath, file) = resolveManagedWebFile(context.filesDir, relativePath)

            // Use managed file MIME type first because on-disk file names are UUID-only.
            val managedFileMime = filesManager.getByRelativePath(normalizedPath)?.mimeType
            call.respondManagedWebFile(file, managedFileMime)
        }
    }
}

// GET /api/assets/{...} - Get file from app assets
fun Route.assetsRoutes(context: Context) {
    route("/assets") {
        get("/{path...}") {
            val relativePath = call.pathParameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing asset path")

            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                throw BadRequestException("Invalid asset path")
            }

            val contentType = when (relativePath.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "png" -> ContentType.Image.PNG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                "svg" -> ContentType.Image.SVG
                "json" -> ContentType.Application.Json
                "html" -> ContentType.Text.Html
                "txt" -> ContentType.Text.Plain
                else -> ContentType.Application.OctetStream
            }

            try {
                val inputStream = context.assets.open(relativePath)
                call.response.header("Content-Type", contentType.toString())
                call.respondOutputStream {
                    inputStream.use { it.copyTo(this) }
                }
            } catch (_: Exception) {
                throw NotFoundException("Asset not found: $relativePath")
            }
        }
    }
}

private suspend fun readPartBytes(part: PartData.FileItem, maxBytes: Int): ByteArray {
    val input = part.provider()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0

    while (true) {
        val read = input.readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break

        totalBytes += read
        if (totalBytes > maxBytes) {
            throw BadRequestException("File too large: max ${maxBytes / (1024 * 1024)} MB")
        }

        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u001F\\u007F]")

private fun sanitizeDisplayName(fileName: String): String {
    val normalized = fileName.substringAfterLast('/').substringAfterLast('\\')
    return normalized
        .replace(CONTROL_CHAR_REGEX, "")
        .ifBlank { "file" }
}

private fun ManagedFileEntity.toUploadedFileDto(filesManager: FilesManager) = UploadedFileDto(
    id = id,
    url = filesManager.getFile(this).toUri().toString(),
    fileName = displayName,
    mime = mimeType,
    size = sizeBytes,
)
