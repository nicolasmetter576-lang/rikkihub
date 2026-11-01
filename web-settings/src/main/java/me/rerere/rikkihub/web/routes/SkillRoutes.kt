package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.files.InvalidSkillReason
import me.rerere.rikkihub.data.files.SkillFileEntry
import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.rikkihub.data.files.SkillImportException
import me.rerere.rikkihub.data.files.SkillImportLimits
import me.rerere.rikkihub.data.files.SkillImportService
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.files.SkillMetadata
import me.rerere.rikkihub.data.files.writeSkillFileTextAtomically
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.PayloadTooLargeException
import me.rerere.rikkihub.web.dto.ErrorResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest

internal const val SKILL_UPLOAD_MAX_BYTES = 16 * 1024 * 1024
internal const val SKILL_TEXT_MAX_BYTES = MAX_FILE_WRITE_CONTENT_BYTES
private const val SKILL_NAME_MAX_CHARS = 128
private const val SKILL_DESCRIPTION_MAX_CHARS = 1024

@Serializable
data class SkillDto(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val builtin: Boolean,
    /** A user skill shadowing an APK-shipped one of the same name. */
    val overridesBuiltin: Boolean,
    val enabledByAssistants: Int,
)

@Serializable
data class SkillFileDto(val path: String, val size: Long, val text: Boolean, val revision: String? = null)

@Serializable
data class SkillFilesResponse(
    val name: String,
    val builtin: Boolean,
    val readOnly: Boolean,
    val files: List<SkillFileDto>,
)

@Serializable
data class SkillFileContentResponse(val path: String, val content: String, val revision: String)

@Serializable
data class SkillCreateRequest(val name: String? = null, val description: String? = null)

@Serializable
data class SkillGitHubImportRequest(val url: String? = null, val overwrite: Boolean = false)

@Serializable
data class SkillImportResponse(val imported: List<String>)

@Serializable
data class SkillDeleteResponse(val revealedBuiltin: Boolean)

/** A folder under the user skills directory that cannot be read as a skill. [reason] is the lower-snake name of the app's reason. */
@Serializable
data class InvalidSkillDirDto(val dirName: String, val reason: String)

/** 409 for an import that would replace existing user skills; [names] lists them. */
@Serializable
data class SkillConflictResponse(val error: String, val code: Int, val errorCode: String, val names: List<String>)

private fun InvalidSkillReason.wireName(): String = when (this) {
    InvalidSkillReason.MissingSkillMd -> "missing_skill_md"
    InvalidSkillReason.MissingName -> "missing_name"
    InvalidSkillReason.MissingDescription -> "missing_description"
    InvalidSkillReason.ReadFailed -> "read_failed"
}

/**
 * 技能管理。规则与 App 相同：用户技能覆盖同名内置；只能改用户技能；SKILL.md 的
 * name 不可改、name/description 必填；文件列表跳过软链、不越出技能目录。网页另加：上传/解压上限、
 * 同名导入要显式 overwrite、文本写入按 SHA-256 版本比对（与工作区编辑同一契约）。
 */
fun Route.skillRoutes(skillManager: SkillManager, assistants: () -> List<Assistant>) {
    val importService = SkillImportService(skillManager)
    // One writer at a time: version check + atomic replace must not interleave between tabs.
    val writeLock = Mutex()

    // Outside /skills/{name}, so no skill name can ever collide with it.
    route("/skill-invalid-dirs") {
        get {
            val dirs = withContext(Dispatchers.IO) { skillManager.listInvalidSkillDirs() }
            call.respond(dirs.map { InvalidSkillDirDto(it.dirName, it.reason.wireName()) })
        }

        delete("/{dirName}") {
            val dirName = call.parameters["dirName"]?.takeIf { it.isNotEmpty() }
                ?: throw BadRequestException("Missing folder name")
            val deleted = writeLock.withLock {
                withContext(Dispatchers.IO) { skillManager.deleteInvalidSkillDir(dirName) }
            }
            if (!deleted) {
                throw CodedApiException(HttpStatusCode.Conflict, "SKILL_DIR_CHANGED", "Folder changed or is gone; reload and retry")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/skills") {
        get {
            val skills = withContext(Dispatchers.IO) { skillManager.listSkills() to skillManager.builtinSkillNames() }
            val assistants = assistants()
            call.respond(skills.first.sortedBy { it.name.lowercase() }.map { skill ->
                SkillDto(
                    name = skill.name,
                    description = skill.description,
                    compatibility = skill.compatibility,
                    builtin = skill.isBuiltin,
                    overridesBuiltin = !skill.isBuiltin && skill.name in skills.second,
                    enabledByAssistants = assistants.count { skill.name in it.enabledSkills },
                )
            })
        }

        post {
            val request = call.receive<SkillCreateRequest>()
            val name = validateNewSkillName(request.name)
            val description = request.description?.trim().orEmpty()
            if (description.isEmpty() || description.length > SKILL_DESCRIPTION_MAX_CHARS) {
                throw BadRequestException("Description must be 1..$SKILL_DESCRIPTION_MAX_CHARS characters")
            }
            writeLock.withLock {
                withContext(Dispatchers.IO) {
                    if (skillManager.findSkill(name) != null) {
                        throw CodedApiException(HttpStatusCode.Conflict, SkillImportException.CODE_EXISTS, "A skill named $name already exists")
                    }
                    skillManager.saveSkill(name, newSkillTemplate(name, description))
                        ?: throw CodedApiException(HttpStatusCode.InternalServerError, SkillImportException.CODE_SAVE_FAILED, "Failed to save skill")
                }
            }
            call.respond(HttpStatusCode.Created, SkillImportResponse(listOf(name)))
        }

        post("/import") {
            val overwrite = call.request.queryParameters["overwrite"] == "true"
            val (fileName, bytes) = readSingleUpload(call)
            runImport(call) {
                writeLock.withLock {
                    withContext(Dispatchers.IO) { importService.importFile(fileName, bytes, SkillImportLimits.WEB, overwrite) }
                }
            }
        }

        post("/import-github") {
            val request = call.receive<SkillGitHubImportRequest>()
            val url = request.url?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 }
                ?: throw BadRequestException("Missing GitHub URL")
            runImport(call) {
                writeLock.withLock {
                    withContext(Dispatchers.IO) {
                        listOf(importService.importGitHub(url, SkillImportLimits.WEB, request.overwrite))
                    }
                }
            }
        }

        route("/{name}") {
            delete {
                val skill = requireSkill(skillManager, call.parameters["name"])
                requireWritable(skillManager, skill)
                val revealed = writeLock.withLock {
                    withContext(Dispatchers.IO) {
                        if (!skillManager.deleteSkill(skill.name, skill)) {
                            throw CodedApiException(HttpStatusCode.Conflict, "SKILL_CHANGED", "Skill changed; reload and retry")
                        }
                        skillManager.findSkill(skill.name)?.isBuiltin == true
                    }
                }
                call.respond(SkillDeleteResponse(revealed))
            }

            post("/override") {
                val skill = requireSkill(skillManager, call.parameters["name"])
                if (!skill.isBuiltin) throw BadRequestException("Only builtin skills can be copied for editing")
                writeLock.withLock {
                    withContext(Dispatchers.IO) { skillManager.copyBuiltinToUser(skill.name) }
                } ?: throw CodedApiException(HttpStatusCode.Conflict, "SKILL_CHANGED", "Skill changed; reload and retry")
                call.respond(HttpStatusCode.Created, SkillImportResponse(listOf(skill.name)))
            }

            get("/files") {
                val skill = requireSkill(skillManager, call.parameters["name"])
                val files = withContext(Dispatchers.IO) { skillManager.listSkillFiles(skill) }
                    ?: throw CodedApiException(HttpStatusCode.Conflict, "SKILL_CHANGED", "Skill changed; reload and retry")
                call.respond(
                    SkillFilesResponse(
                        name = skill.name,
                        builtin = skill.isBuiltin,
                        readOnly = !skillManager.canModify(skill),
                        files = withContext(Dispatchers.IO) { files.map { it.toDto() } },
                    )
                )
            }

            get("/files/content") {
                val skill = requireSkill(skillManager, call.parameters["name"])
                val file = requireExistingFile(skillManager, skill, call.request.queryParameters["path"])
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val text = decodeSkillText(bytes)
                    ?: throw CodedApiException(HttpStatusCode.UnsupportedMediaType, "SKILL_FILE_BINARY", "Not a UTF-8 text file")
                call.respond(SkillFileContentResponse(relativePathOf(skill, file), text, sha256Hex(bytes)))
            }

            put("/files/content") {
                val skill = requireSkill(skillManager, call.parameters["name"])
                requireWritable(skillManager, skill)
                val path = normalizeSkillPath(call.request.queryParameters["path"])
                val request = readWorkspaceFileWriteRequest(call)
                if (path == "SKILL.md") validateSkillMarkdown(request.content, skill.name)
                val revision = writeLock.withLock {
                    withContext(Dispatchers.IO) {
                        val target = skillManager.resolveSkillFile(skill, path)
                            ?: throw CodedApiException(HttpStatusCode.Conflict, "SKILL_CHANGED", "Skill changed; reload and retry")
                        if (Files.isSymbolicLink(target.toPath())) throw BadRequestException("Refusing to write through a link")
                        val exists = target.exists()
                        when {
                            !request.overwrite && exists -> throw CodedApiException(HttpStatusCode.Conflict, "FILE_EXISTS", "File already exists")
                            exists && target.isDirectory -> throw BadRequestException("Path is a directory")
                            request.expectedRevision != null &&
                                (!exists || sha256Hex(target.readBytes()) != request.expectedRevision) ->
                                throw CodedApiException(HttpStatusCode.Conflict, "FILE_REVISION_MISMATCH", "File changed; reload and retry")
                        }
                        target.parentFile?.mkdirs()
                        if (!writeSkillFileTextAtomically(target, request.content)) {
                            throw CodedApiException(HttpStatusCode.InternalServerError, SkillImportException.CODE_SAVE_FAILED, "Failed to save file")
                        }
                        sha256Hex(request.content.toByteArray(Charsets.UTF_8))
                    }
                }
                call.respond(SkillFileContentResponse(path, request.content, revision))
            }

            delete("/files") {
                val skill = requireSkill(skillManager, call.parameters["name"])
                requireWritable(skillManager, skill)
                val path = normalizeSkillPath(call.request.queryParameters["path"])
                if (path == "SKILL.md") throw BadRequestException("SKILL.md cannot be deleted")
                writeLock.withLock {
                    withContext(Dispatchers.IO) {
                        requireExistingFile(skillManager, skill, path)
                        if (!skillManager.deleteSkillFile(skill.name, path, skill)) {
                            throw CodedApiException(HttpStatusCode.Conflict, "SKILL_CHANGED", "Skill changed; reload and retry")
                        }
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/files/download") {
                val skill = requireSkill(skillManager, call.parameters["name"])
                val file = requireExistingFile(skillManager, skill, call.request.queryParameters["path"])
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString(),
                )
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondBytes(bytes, ContentType.Application.OctetStream)
            }
        }
    }
}

private suspend fun runImport(call: RoutingCall, block: suspend () -> List<String>) {
    try {
        call.respond(HttpStatusCode.Created, SkillImportResponse(block()))
    } catch (e: SkillImportException) {
        if (e.code == SkillImportException.CODE_EXISTS) {
            call.respond(
                HttpStatusCode.Conflict,
                SkillConflictResponse(e.message ?: e.code, HttpStatusCode.Conflict.value, e.code, e.names),
            )
            return
        }
        val status = when (e.code) {
            SkillImportException.CODE_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
            SkillImportException.CODE_GITHUB_RATE_LIMIT -> HttpStatusCode.TooManyRequests
            SkillImportException.CODE_GITHUB_FAILED -> HttpStatusCode.BadGateway
            SkillImportException.CODE_SAVE_FAILED -> HttpStatusCode.InternalServerError
            else -> HttpStatusCode.BadRequest
        }
        call.respond(status, ErrorResponse(e.message ?: e.code, status.value, e.code))
    }
}

private suspend fun readSingleUpload(call: RoutingCall): Pair<String, ByteArray> {
    val multipart = call.receiveMultipart()
    var result: Pair<String, ByteArray>? = null
    while (true) {
        val part = multipart.readPart() ?: break
        try {
            if (part is PartData.FileItem && result == null) {
                val name = part.originalFileName?.takeIf { it.isNotBlank() } ?: "SKILL.md"
                result = name to readBounded(part.provider().toInputStream(), SKILL_UPLOAD_MAX_BYTES)
            }
        } finally {
            part.release()
        }
    }
    val upload = result ?: throw BadRequestException("No file uploaded")
    if (upload.second.isEmpty()) throw BadRequestException("Uploaded file is empty")
    return upload
}

private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    input.use {
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw PayloadTooLargeException("Upload exceeds ${maxBytes / (1024 * 1024)} MiB")
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}

private fun requireSkill(skillManager: SkillManager, rawName: String?): SkillMetadata {
    val name = rawName?.takeIf { it.isNotBlank() && it.length <= 256 } ?: throw BadRequestException("Invalid skill name")
    return skillManager.findSkill(name) ?: throw NotFoundException("Skill not found")
}

private fun requireWritable(skillManager: SkillManager, skill: SkillMetadata) {
    if (skill.isBuiltin) {
        throw CodedApiException(HttpStatusCode.Forbidden, "SKILL_READ_ONLY", "Builtin skills are read-only; copy it to edit")
    }
    if (!skillManager.canModify(skill)) {
        throw CodedApiException(HttpStatusCode.Conflict, "SKILL_CHANGED", "Skill changed; reload and retry")
    }
}

private fun requireExistingFile(skillManager: SkillManager, skill: SkillMetadata, rawPath: String?): File {
    val path = normalizeSkillPath(rawPath)
    val file = skillManager.resolveSkillFile(skill, path)?.takeIf { it.isFile } ?: throw NotFoundException("File not found")
    // Same visibility as the listing: never serve or touch a file reached through a link.
    val listed = skillManager.listSkillFiles(skill)?.any { it.file.canonicalFile == file.canonicalFile } == true
    if (!listed) throw NotFoundException("File not found")
    return file
}

/** Relative, '/'-separated, no empty or dot segments; containment is rechecked on resolve. */
internal fun normalizeSkillPath(raw: String?): String {
    val parts = raw?.replace('\\', '/')?.split('/')?.filter { it.isNotEmpty() }
    if (parts.isNullOrEmpty() || raw.startsWith("/") || raw.startsWith("\\") ||
        parts.any { it == "." || it == ".." } || raw.length > 1024 ||
        parts.any { part -> part.any { it.isISOControl() } }
    ) {
        throw BadRequestException("Invalid file path")
    }
    return parts.joinToString("/")
}

internal fun validateNewSkillName(raw: String?): String {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty() || name.length > SKILL_NAME_MAX_CHARS || name.startsWith(".") ||
        name.any { it == '/' || it == '\\' || it.isISOControl() }
    ) {
        throw BadRequestException("Skill name must be 1..$SKILL_NAME_MAX_CHARS characters without / \\ or a leading dot")
    }
    return name
}

/** YAML accepts JSON double-quoted strings, so names/descriptions need no other escaping. */
internal fun newSkillTemplate(name: String, description: String): String =
    "---\nname: ${JsonPrimitive(name)}\ndescription: ${JsonPrimitive(description)}\n---\n\n# $name\n\n"

/** Same rule as the app's detail page: the name is fixed and the description required. */
internal fun validateSkillMarkdown(content: String, skillName: String) {
    val frontmatter = SkillFrontmatterParser.parse(content)
    if (frontmatter["name"] != skillName) {
        throw CodedApiException(HttpStatusCode.BadRequest, "SKILL_NAME_CHANGED", "The name field must stay \"$skillName\"")
    }
    if (frontmatter["description"].isNullOrBlank()) {
        throw CodedApiException(HttpStatusCode.BadRequest, "SKILL_DESCRIPTION_MISSING", "The description field is required")
    }
}

/** Strict UTF-8 without NUL and within the editor limit; null for anything else. */
internal fun decodeSkillText(bytes: ByteArray): String? {
    if (bytes.size > SKILL_TEXT_MAX_BYTES || bytes.any { it == 0.toByte() }) return null
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun SkillFileEntry.toDto(): SkillFileDto {
    if (size > SKILL_TEXT_MAX_BYTES) return SkillFileDto(path, size, text = false)
    val bytes = file.readBytes()
    val text = decodeSkillText(bytes) != null
    return SkillFileDto(path, size, text, if (text) sha256Hex(bytes) else null)
}

private fun relativePathOf(skill: SkillMetadata, file: File): String =
    file.canonicalFile.relativeTo(skill.skillDir.canonicalFile).invariantSeparatorsPath
