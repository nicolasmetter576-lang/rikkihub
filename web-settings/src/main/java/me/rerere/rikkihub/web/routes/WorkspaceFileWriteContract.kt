package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkihub.web.ApiException
import me.rerere.rikkihub.web.FileOperationApiException
import me.rerere.rikkihub.web.dto.CreateWorkspaceDirectoryRequest
import me.rerere.rikkihub.web.dto.WriteWorkspaceFileRequest
import me.rerere.workspace.WorkspaceFileAlreadyExistsException
import me.rerere.workspace.WorkspaceArchiveException
import me.rerere.workspace.WorkspaceFileOperationException
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

internal const val MAX_FILE_WRITE_CONTENT_BYTES = 2 * 1024 * 1024
// A valid JSON string may encode each byte as a six-byte escape. Bound the transport
// as well as decoded content, rather than relying on Content-Length from the client.
internal const val MAX_FILE_WRITE_BODY_BYTES = MAX_FILE_WRITE_CONTENT_BYTES * 6L + 1024

internal suspend fun readWorkspaceFileWriteRequest(call: ApplicationCall): WriteWorkspaceFileRequest =
    parseWorkspaceFileWriteRequest(
        call.receiveChannel().readRemaining(MAX_FILE_WRITE_BODY_BYTES + 1).readByteArray()
    )

internal fun parseWorkspaceFileWriteRequest(bytes: ByteArray): WriteWorkspaceFileRequest {
    if (bytes.size > MAX_FILE_WRITE_BODY_BYTES) throw fileRequestError(
        "FILE_TOO_LARGE", HttpStatusCode.PayloadTooLarge, "File write request is too large"
    )
    val body = try {
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    } catch (_: CharacterCodingException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw fileRequestError("FILE_INVALID_REQUEST", message = "Expected a UTF-8 JSON object")
    if (body.keys.any { it !in setOf("content", "overwrite", "expectedRevision") }) {
        throw fileRequestError("FILE_INVALID_REQUEST", message = "Unknown file write field")
    }
    val content = (body["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw fileRequestError("FILE_INVALID_REQUEST", message = "content must be a string")
    if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_WRITE_CONTENT_BYTES) {
        throw fileRequestError("FILE_TOO_LARGE", HttpStatusCode.PayloadTooLarge, "File content is too large")
    }
    val overwrite = if ("overwrite" !in body) true else {
        val value = body["overwrite"] as? JsonPrimitive
        value?.takeUnless { it.isString }?.booleanOrNull
            ?: throw fileRequestError("FILE_INVALID_REQUEST", message = "overwrite must be a boolean")
    }
    val expectedRevision = if ("expectedRevision" !in body) null else {
        (body["expectedRevision"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw fileRequestError("FILE_INVALID_REQUEST", message = "Invalid expectedRevision")
    }
    if (!overwrite && expectedRevision != null) {
        throw fileRequestError("FILE_INVALID_REQUEST", message = "Exclusive creation does not accept expectedRevision")
    }
    return WriteWorkspaceFileRequest(content, overwrite, expectedRevision)
}

internal const val MAX_DIRECTORY_BODY_BYTES = 16 * 1024L

internal suspend fun readWorkspaceDirectoryRequest(call: ApplicationCall): CreateWorkspaceDirectoryRequest =
    parseWorkspaceDirectoryRequest(
        call.receiveChannel().readRemaining(MAX_DIRECTORY_BODY_BYTES + 1).readByteArray()
    )

internal fun parseWorkspaceDirectoryRequest(bytes: ByteArray): CreateWorkspaceDirectoryRequest {
    if (bytes.size > MAX_DIRECTORY_BODY_BYTES) {
        throw fileRequestError("FILE_TOO_LARGE", HttpStatusCode.PayloadTooLarge, "Directory request is too large")
    }
    val body = try {
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    } catch (_: CharacterCodingException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw fileRequestError("FILE_INVALID_REQUEST", message = "Expected a UTF-8 JSON object")
    if (body.keys.any { it !in setOf("path", "exclusive") }) {
        throw fileRequestError("FILE_INVALID_REQUEST", message = "Unknown directory creation field")
    }
    val path = (body["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw fileRequestError("FILE_INVALID_REQUEST", message = "path must be a string")
    val exclusive = if ("exclusive" !in body) false else {
        val value = body["exclusive"] as? JsonPrimitive
        value?.takeUnless { it.isString }?.booleanOrNull
            ?: throw fileRequestError("FILE_INVALID_REQUEST", message = "exclusive must be a boolean")
    }
    return CreateWorkspaceDirectoryRequest(path, exclusive)
}

private fun fileRequestError(
    code: String,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
    message: String,
) = FileOperationApiException(status, code, message)

/** Never return an IO exception's message, cause or host path to a Web client. */
internal fun workspaceFileApiError(error: Throwable): ApiException = when (error) {
    is ApiException -> error
    is WorkspaceFileAlreadyExistsException, is FileAlreadyExistsException ->
        fileRequestError("FILE_ALREADY_EXISTS", HttpStatusCode.Conflict, "File already exists")
    is WorkspaceArchiveException -> when (error.code) {
        WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED ->
            fileRequestError(error.code, HttpStatusCode.UnprocessableEntity, "Archive is password protected")
        WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD ->
            fileRequestError(error.code, HttpStatusCode.UnprocessableEntity, "Wrong password")
        WorkspaceArchiveException.ARCHIVE_UNSUPPORTED ->
            fileRequestError(error.code, HttpStatusCode.UnsupportedMediaType, "Unsupported archive format")
        WorkspaceArchiveException.ARCHIVE_ENCODING_UNSUPPORTED ->
            fileRequestError(error.code, message = "Unsupported file name encoding")
        else -> fileRequestError(WorkspaceArchiveException.ARCHIVE_CORRUPT, message = "Archive is damaged or incomplete")
    }
    is WorkspaceFileOperationException -> when (error.code) {
        "FILE_REVISION_CONFLICT" ->
            fileRequestError("FILE_REVISION_CONFLICT", HttpStatusCode.Conflict, "File changed since it was opened; reload before saving")
        WorkspaceFileOperationException.FILE_ALREADY_EXISTS ->
            fileRequestError("FILE_ALREADY_EXISTS", HttpStatusCode.Conflict, "File already exists")
        WorkspaceFileOperationException.FILE_PERMISSION_DENIED ->
            fileRequestError("FILE_PERMISSION_DENIED", HttpStatusCode.Forbidden, "File access denied")
        "FILE_NOT_UTF8" -> fileRequestError("FILE_NOT_UTF8", message = "File is not valid UTF-8 text")
        "FILE_NOT_TEXT" -> fileRequestError("FILE_NOT_TEXT", message = "File is not editable text")
        "FILE_NOT_FOUND" -> fileRequestError("FILE_NOT_FOUND", HttpStatusCode.NotFound, "File not found")
        "FILE_NOT_FILE" -> fileRequestError("FILE_INVALID_PATH", message = "Path is not a file")
        "INVALID_REVISION" -> fileRequestError("FILE_INVALID_REQUEST", message = "Invalid expectedRevision")
        "FILE_IO_ERROR" -> fileRequestError("FILE_IO_ERROR", HttpStatusCode.InternalServerError, "File operation failed")
        "ATOMIC_SAVE_UNSUPPORTED" -> fileRequestError("FILE_ATOMIC_SAVE_UNSUPPORTED", HttpStatusCode.InternalServerError, "Atomic file replacement is not supported")
        WorkspaceFileOperationException.CONTENT_TOO_LARGE ->
            fileRequestError("FILE_TOO_LARGE", HttpStatusCode.PayloadTooLarge, "File content is too large")
        WorkspaceFileOperationException.PARENT_NOT_FOUND ->
            fileRequestError("FILE_PARENT_MISSING", message = "Parent directory is missing")
        WorkspaceFileOperationException.INVALID_FILE_NAME ->
            fileRequestError("FILE_INVALID_NAME", message = "Invalid file name")
        WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED ->
            fileRequestError("FILE_SYMLINK_NOT_ALLOWED", message = "Symbolic links are not allowed for creation")
        else -> fileRequestError("FILE_INVALID_PATH", message = "Invalid file path")
    }
    is AccessDeniedException, is SecurityException ->
        fileRequestError("FILE_PERMISSION_DENIED", HttpStatusCode.Forbidden, "File access denied")
    is NoSuchFileException -> fileRequestError("FILE_PARENT_MISSING", message = "Parent directory is missing")
    is IllegalArgumentException -> fileRequestError("FILE_INVALID_PATH", message = "Invalid file path or content")
    is IOException -> fileRequestError("FILE_IO_ERROR", HttpStatusCode.InternalServerError, "File operation failed")
    else -> fileRequestError("FILE_IO_ERROR", HttpStatusCode.InternalServerError, "File operation failed")
}

internal suspend fun <T> workspaceFileApiOperation(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw workspaceFileApiError(error)
}
