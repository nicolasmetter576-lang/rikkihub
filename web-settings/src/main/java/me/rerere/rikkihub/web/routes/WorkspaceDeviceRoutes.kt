package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import me.rerere.rikkihub.web.TooManyRequestsException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceAvailability
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceConfigException
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException
import me.rerere.rikkihub.web.ApiException
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.ConflictException
import me.rerere.rikkihub.web.ForbiddenException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.dto.CreateDeviceMountRequest
import me.rerere.rikkihub.web.dto.ErrorResponse
import me.rerere.rikkihub.web.dto.MoveWorkspaceFileRequest
import me.rerere.rikkihub.web.dto.MoveWorkspaceFileResponse
import me.rerere.rikkihub.web.dto.WorkspaceBatchDeleteFailureDto
import me.rerere.rikkihub.web.dto.WorkspaceBatchDeleteResponse
import me.rerere.rikkihub.web.dto.WorkspaceDeviceAvailabilityDto
import me.rerere.rikkihub.web.dto.WorkspaceDeviceBrowseEntryDto
import me.rerere.rikkihub.web.dto.WorkspaceDeviceBrowseResponse
import me.rerere.rikkihub.web.dto.WorkspaceDeviceFileContentDto
import me.rerere.rikkihub.web.dto.WorkspaceDeviceFileListResponse
import me.rerere.rikkihub.web.dto.toDeviceDto
import me.rerere.rikkihub.web.dto.toDto
import me.rerere.workspace.WorkspaceDeviceFileAccess
import me.rerere.workspace.WorkspaceDeviceMount
import me.rerere.workspace.WorkspaceFileAlreadyExistsException
import me.rerere.workspace.WorkspaceFileOperationException
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 设备目录直连 REST: /workspaces/{id}/device/...（无 COW，直写宿主 source）。
 *
 * 边界：
 * - 本文件只挂受控路由；前端永远只见 alias + 卷根相对路径，任何宿主绝对路径
 *   在异常消息里被脱敏（[sanitizeDeviceHostPath]）。
 * - availability/list/browse/delete/disable 在授权被撤后仍可访问
 *   （配置意图不等于运行态可用）；文件读写依赖 resolveMounts 的运行态校验，
 *   由 service.withMountOperation 统一 gate（USER_FILE 类操作，busy 不被
 *   配置变更中断；活跃 source 失效/未激活由 domain 层明确抛错而非静默降级）。
 * - DELETE mounts/{mountId} 只删连接配置，绝不删宿主目录本体。
 */
private const val DEVICE_MAX_UPLOAD_BYTES = 32 * 1024 * 1024
private const val DEVICE_MAX_READ_TEXT_BYTES = 512 * 1024L

fun Route.workspaceDeviceRoutes(
    workspaceRepository: WorkspaceRepository,
    deviceService: WorkspaceDeviceDirectoryService,
    deviceStorage: WorkspaceDeviceStorage,
    archiveTempDir: java.io.File = defaultWorkspaceArchiveTempDir(),
) {
    // 复用 workspace 模块文件层; 每次调用重检 source 可访问性, 无状态
    val deviceFiles = WorkspaceDeviceFileAccess()
    // 与 WorkspaceBatchOps 同一套限额/并发许可 (设备侧独立实例, 上限一致)
    val archivePermits = WorkspaceArchivePermits()

    route("/workspaces/{id}/device") {
        // -------------------------------------------------- 可用性 / 浏览

        get("/availability") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            call.respond(deviceAvailabilityDto(deviceStorage.availability()))
        }

        get("/browse") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val path = call.request.queryParameters["path"].orEmpty()
            val entries = runDeviceOp {
                // 空 path = 主卷根; 只允许受控相对路径 (service 内 guard 校验)
                deviceService.browse(path)
            }
            call.respond(
                WorkspaceDeviceBrowseResponse(
                    path = path,
                    entries = entries.map {
                        WorkspaceDeviceBrowseEntryDto(it.name, it.relativePath)
                    },
                )
            )
        }

        // -------------------------------------------------- 连接 CRUD

        get("/mounts") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val dtos = runDeviceOp {
                workspaceRepository.runtimeGate.withSnapshot(id) {
                    val mounts = deviceService.list(id)
                    val activeIds = deviceService.resolveMounts(id).mapTo(HashSet()) { it.id }
                    val token = deviceService.viewToken(id)
                    mounts.map { it.toDeviceDto(active = it.id in activeIds, viewToken = token) }
                }
            }
            call.respond(dtos)
        }

        post("/mounts") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val request = call.receive<CreateDeviceMountRequest>()
            val alias = request.alias.trim()
            if (!me.rerere.workspace.WorkspaceDeviceDirectoryGuard.ALIAS_REGEX.matches(alias)) {
                throw BadRequestException("Invalid device directory alias")
            }
            val entity = runDeviceOp {
                deviceService.create(
                    workspaceId = id,
                    alias = alias,
                    sourceRelativePath = request.sourceRelativePath,
                )
            }
            call.respond(HttpStatusCode.Created, entity.toDeviceDto(active = true))
        }

        patch("/mounts/{mountId}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val mountId = call.parameters["mountId"]
                ?: throw BadRequestException("Missing device mount id")
            requireDeviceWorkspace(workspaceRepository, id)
            val patch = parseDeviceMountUpdate(call.receive<JsonObject>())
            val entity = runDeviceOp {
                deviceService.update(
                    workspaceId = id,
                    mountId = mountId,
                    alias = if (patch.hasAlias) patch.alias else null,
                    sourceRelativePath = if (patch.hasSourceRelativePath) {
                        patch.sourceRelativePath
                    } else {
                        null
                    },
                    enabled = if (patch.hasEnabled) patch.enabled else null,
                )
            } ?: throw NotFoundException("Device mount not found")
            call.respond(entity.toDeviceDto(active = false))
        }

        delete("/mounts/{mountId}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val mountId = call.parameters["mountId"]
                ?: throw BadRequestException("Missing device mount id")
            requireDeviceWorkspace(workspaceRepository, id)
            val deleted = runDeviceOp { deviceService.delete(id, mountId) }
            if (!deleted) throw NotFoundException("Device mount not found")
            call.respond(HttpStatusCode.NoContent)
        }

        // -------------------------------------------------- 设备文件读写

        get("/files") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val path = call.request.queryParameters["path"].orEmpty()
            val entries = deviceFileOp(deviceService, id, mountRef) { mount ->
                deviceFiles.list(mount, path)
            }
            call.respond(
                WorkspaceDeviceFileListResponse(
                    mount = mountRef,
                    path = path,
                    entries = entries.map { it.toDto() },
                )
            )
        }

        get("/files/content") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val path = call.request.queryParameters["path"]?.trim().orEmpty()
            if (path.isBlank()) throw BadRequestException("Missing file path")
            val snapshot = deviceFileOp(deviceService, id, mountRef) { mount ->
                val info = deviceFiles.stat(mount, path)
                if (!info.exists || info.isDirectory) {
                    throw BadRequestException("Path is not a readable file")
                }
                if (info.sizeBytes > DEVICE_MAX_READ_TEXT_BYTES) {
                    throw BadRequestException("File too large to preview")
                }
                deviceFiles.readTextSnapshot(mount, path)
            }
            call.respond(
                WorkspaceDeviceFileContentDto(
                    mount = mountRef,
                    path = path,
                    content = snapshot.content,
                    revision = snapshot.revision,
                )
            )
        }

        put("/files/content") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val path = call.request.queryParameters["path"].orEmpty()
            if (path.isBlank()) throw BadRequestException("Missing file path")
            val request = readWorkspaceFileWriteRequest(call)
            val entry = deviceFileOp(deviceService, id, mountRef) { mount ->
                if (request.expectedRevision != null) {
                    val saved = deviceFiles.writeTextWithRevision(mount, path, request.content, request.expectedRevision)
                    saved.entry.toDto().copy(revision = saved.revision)
                } else {
                    deviceFiles.writeText(mount, path, request.content, request.overwrite).toDto()
                }
            }
            call.respond(entry)
        }

        get("/files/download") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val path = call.request.queryParameters["path"]?.trim().orEmpty()
            if (path.isBlank()) throw BadRequestException("Missing file path")
            val fileName = path.substringAfterLast('/').ifBlank { "file" }
            val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20")
            // 先把响应头定好再在 gate 操作内流式写出; 校验失败在 headers 前抛
            call.response.header(
                "Content-Disposition",
                "attachment; filename*=UTF-8''$encodedName"
            )
            call.response.header("Content-Type", "application/octet-stream")
            runDeviceOp {
                deviceService.withMountOperation(id, mountRef) { mount ->
                    call.respondOutputStream {
                        try {
                            deviceFiles.exportFile(mount, path, this)
                        } catch (error: IllegalArgumentException) {
                            throw IllegalStateException(
                                sanitizeDeviceHostPath(error.message, mount).ifBlank {
                                    "Unable to download file"
                                }
                            )
                        }
                    }
                }
            }
        }

        post("/files/upload") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val destination = call.request.queryParameters["path"].orEmpty()
            val multipart = call.receiveMultipart()
            var uploaded: me.rerere.workspace.WorkspaceFileEntry? = null
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        val originalFileName = part.originalFileName
                            ?.takeIf { it.isNotBlank() } ?: "file"
                        val fileName = sanitizeDeviceDisplayName(originalFileName)
                        val rawInput = PushbackInputStream(part.provider().toInputStream(), 1)
                        val first = rawInput.read()
                        if (first < 0) throw BadRequestException("Uploaded file is empty")
                        rawInput.unread(first)
                        uploaded = deviceFileOp(deviceService, id, mountRef) { mount ->
                            deviceFiles.importFile(
                                mount = mount,
                                destinationPath = destination,
                                fileName = fileName,
                                inputStream = DeviceSizeLimitedInputStream(
                                    rawInput, DEVICE_MAX_UPLOAD_BYTES
                                ),
                            )
                        }
                    }
                } finally {
                    part.release()
                }
            }
            val entry = uploaded ?: throw BadRequestException("No files uploaded")
            call.respond(HttpStatusCode.Created, entry.toDto())
        }

        post("/files/mkdir") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val request = readWorkspaceDirectoryRequest(call)
            val entry = deviceFileOp(deviceService, id, mountRef) { mount ->
                if (request.exclusive) deviceFiles.createDirectory(mount, request.path)
                else deviceFiles.mkdir(mount, request.path)
            }
            call.respond(HttpStatusCode.Created, entry.toDto())
        }

        post("/files/move") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val request = call.receive<MoveWorkspaceFileRequest>()
            // 词法归一化走与批量选择同一套规则: .. 折叠 / 根拒绝 / 空段折叠
            val source = normalizeDeviceSelectionPath(request.source)
            val target = normalizeDeviceSelectionPath(request.target)
            deviceFileOp(deviceService, id, mountRef) { mount ->
                deviceFiles.move(mount, source, target, request.overwrite)
            }
            call.respond(MoveWorkspaceFileResponse(source = source, target = target))
        }

        delete("/files") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            // 归一化会拒 ""/"/"/纯 ".." → 永远无法删到 source 根本身
            val path = normalizeDeviceSelectionPath(
                call.request.queryParameters["path"].orEmpty()
            )
            val recursive = call.request.queryParameters["recursive"]
                ?.toBooleanStrictOrNull() ?: false
            val deleted = deviceFileOp(deviceService, id, mountRef) { mount ->
                deviceFiles.delete(mount, path, recursive)
            }
            if (!deleted) throw NotFoundException("File not found")
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * 文件夹导入的最小受控端点: POST /files/import?mount=&path=
         * body = 单文件原始字节 (application/octet-stream); path 是 mount 内
         * 目标相对路径 (前端文件夹导入按序逐文件调用, 相对目录来自受控的
         * webkitRelativePath 解析, 不信任 multipart filename)。
         * 路径经同一套归一化 + 根拒绝; 字节流硬限 DEVICE_MAX_UPLOAD_BYTES。
         */
        post("/files/import") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val target = normalizeDeviceSelectionPath(
                call.request.queryParameters["path"].orEmpty()
            )
            val rawInput = call.receiveChannel().toInputStream()
            val entry = deviceFileOp(deviceService, id, mountRef) { mount ->
                deviceFiles.importFile(
                    mount = mount,
                    destinationPath = "",
                    // importFile(destination, fileName) 里 fileName 已是 basename 语义;
                    // 这里直接传入完整目标相对路径, 与写文件同一路径校验链
                    fileName = target,
                    inputStream = DeviceSizeLimitedInputStream(
                        rawInput, DEVICE_MAX_UPLOAD_BYTES
                    ),
                )
            }
            call.respond(HttpStatusCode.Created, entry.toDto())
        }

        /**
         * ZIP 下载: POST /files/archive?mount= body {paths}
         * 与 workspace archive 同一套校验/限额/临时文件管线, 但源是设备 mount。
         * 临时文件先完整生成再 LocalFileContent 响应; 失败/取消 finally 回收。
         */
        post("/files/archive") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val paths = try {
                val body = readWorkspaceSelectionBody(call)
                normalizeWorkspaceSelectionPaths(extractWorkspaceSelectionPaths(body))
            } catch (error: WorkspaceSelectionLimitException) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse(error.message ?: "Request too large", 413)
                )
                return@post
            }
            if (!archivePermits.tryAcquire()) {
                throw TooManyRequestsException("Too many concurrent archive exports")
            }
            try {
                var zipName = "device.zip"
                val tmp = runDeviceOp {
                    deviceService.withMountOperation(id, mountRef) { mount ->
                        zipName = sanitizeDeviceDisplayName(mount.alias) + ".zip"
                        try {
                            // 预检: 选中项存在性/条目数/源总大小, 动盘前拒绝
                            measureWorkspaceSelection(
                                relPaths = paths,
                                listDir = { rel -> deviceFiles.list(mount, rel) },
                                fileSize = { rel ->
                                    val info = deviceFiles.stat(mount, rel)
                                    if (!info.exists || info.isDirectory) {
                                        throw IllegalArgumentException("Not a file")
                                    }
                                    info.sizeBytes
                                },
                            )
                            writeArchiveToTempFile(archiveTempDir) { out ->
                                deviceFiles.exportZip(mount, paths, out)
                            }
                        } catch (error: ApiException) {
                            throw error
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: IllegalArgumentException) {
                            throw BadRequestException(
                                sanitizeDeviceHostPath(error.message, mount).ifBlank {
                                    "Invalid device file path"
                                }
                            )
                        }
                    }
                }
                try {
                    val encoded = URLEncoder.encode(zipName, StandardCharsets.UTF_8)
                        .replace("+", "%20")
                    call.response.header(
                        "Content-Disposition",
                        "attachment; filename*=UTF-8''$encoded"
                    )
                    call.response.header("X-Content-Type-Options", "nosniff")
                    call.respond(LocalFileContent(tmp, io.ktor.http.ContentType.Application.Zip))
                } finally {
                    tmp.delete()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: WorkspaceSelectionLimitException) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse(
                        sanitizeDeviceHostPath(error.message, mount = null)
                            .ifBlank { "Selection too large" },
                        413,
                    )
                )
            } catch (error: WorkspaceArchiveOutputLimitException) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("Archive too large", 413)
                )
            } finally {
                archivePermits.release()
            }
        }

        post("/files/batch-delete") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireDeviceWorkspace(workspaceRepository, id)
            val mountRef = requireDeviceMountRef(call)
            val paths = try {
                val body = readWorkspaceSelectionBody(call)
                normalizeWorkspaceSelectionPaths(extractWorkspaceSelectionPaths(body))
            } catch (error: WorkspaceSelectionLimitException) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse(error.message ?: "Request too large", 413)
                )
                return@post
            }
            var mountRefPath = ""
            val result = deviceFileOp(deviceService, id, mountRef) { mount ->
                mountRefPath = mount.source.path
                deviceFiles.deleteBatch(mount, paths)
            }
            call.respond(
                WorkspaceBatchDeleteResponse(
                    deletedPaths = result.deletedPaths,
                    failures = result.failures.map {
                        WorkspaceBatchDeleteFailureDto(
                            path = it.path,
                            error = sanitizeDeviceHostPath(
                                it.error,
                                mountPath = mountRefPath,
                                mount = null,
                            ),
                        )
                    },
                )
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 内部辅助                                                            */
/* ------------------------------------------------------------------ */

private suspend fun requireDeviceWorkspace(
    workspaceRepository: WorkspaceRepository,
    id: String?,
): me.rerere.rikkihub.data.db.entity.WorkspaceEntity {
    if (id.isNullOrBlank()) throw BadRequestException("Missing workspace id")
    return workspaceRepository.getById(id) ?: throw NotFoundException("Workspace not found")
}

internal fun deviceAvailabilityDto(
    availability: WorkspaceDeviceAvailability,
): WorkspaceDeviceAvailabilityDto = WorkspaceDeviceAvailabilityDto(
    status = availability.name,
    canBrowse = availability == WorkspaceDeviceAvailability.READY,
    // 授权动作只能由 App 发起, Web 侧只提示 needsPermission
    needsPermission = availability == WorkspaceDeviceAvailability.PERMISSION_REQUIRED,
)

/** mount 查询参数: 支持 mountId 或 alias (服务侧解析), 缺省 400 */
private fun requireDeviceMountRef(call: ApplicationCall): String {
    val ref = call.request.queryParameters["mount"]?.trim().orEmpty()
    if (ref.isBlank()) throw BadRequestException("Missing device mount reference")
    return ref
}

/**
 * 单条设备相对路径词法归一化 (复用批量选择的同一套规则):
 * `\\`→`/`, 空段/`.` 折叠, 中间 `..` 折叠; 弹出根之上的 `..`、NUL、
 * 归一后为空 (即 source 根本身) 全部 BadRequest —— 因此 move/delete/
 * import/archive 都不可能把 source 根当成目标。
 */
internal fun normalizeDeviceSelectionPath(raw: String): String =
    normalizeWorkspaceSelectionPaths(listOf(raw)).first()

internal data class DeviceMountUpdatePatch(
    val hasAlias: Boolean,
    val alias: String?,
    val hasSourceRelativePath: Boolean,
    val sourceRelativePath: String?,
    val hasEnabled: Boolean,
    val enabled: Boolean?,
)

/** PATCH body 解析: 三个字段至少一个存在; 显式 null → 400 (无"清除"语义) */
internal fun parseDeviceMountUpdate(body: JsonObject): DeviceMountUpdatePatch {
    if ("alias" !in body && "sourceRelativePath" !in body && "enabled" !in body) {
        throw BadRequestException("No device mount fields to update")
    }
    val hasAlias = "alias" in body
    val alias = if (hasAlias) {
        val raw = body["alias"] as? JsonPrimitive
            ?: throw BadRequestException("alias must be a string")
        raw.contentOrNull?.trim()
            ?: throw BadRequestException("alias must be a string")
    } else {
        null
    }
    if (hasAlias && !me.rerere.workspace.WorkspaceDeviceDirectoryGuard.ALIAS_REGEX.matches(alias!!)) {
        throw BadRequestException("Invalid device directory alias")
    }
    val hasSource = "sourceRelativePath" in body
    val source = if (hasSource) {
        val raw = body["sourceRelativePath"] as? JsonPrimitive
            ?: throw BadRequestException("sourceRelativePath must be a string")
        // 空串 = 连接整个主共享存储（卷根）
        raw.contentOrNull?.trim()
            ?: throw BadRequestException("sourceRelativePath must be a string")
    } else {
        null
    }
    val hasEnabled = "enabled" in body
    val enabled = if (hasEnabled) {
        val raw = body["enabled"] as? JsonPrimitive
            ?: throw BadRequestException("enabled must be a boolean")
        raw.booleanOrNull ?: throw BadRequestException("enabled must be a boolean")
    } else {
        null
    }
    return DeviceMountUpdatePatch(
        hasAlias = hasAlias,
        alias = alias,
        hasSourceRelativePath = hasSource,
        sourceRelativePath = source,
        hasEnabled = hasEnabled,
        enabled = enabled,
    )
}

/**
 * 设备操作统一错误映射:
 * - WorkspaceDeviceConfigException → 语义细分: not found→404 / permission→403 / 其余→400
 * - WorkspaceUnavailableException (gate 拒绝: sealed/closed/quarantine) → 409 busy
 * - IllegalStateException (含 join timeout/cleanup/未激活或 source 失效) → 409
 * - IllegalArgumentException (路径/校验) → 400, 消息脱敏宿主路径
 */
internal fun deviceErrorStatus(error: Throwable): HttpStatusCode = when (error) {
    is ApiException -> error.status
    is WorkspaceFileAlreadyExistsException -> HttpStatusCode.Conflict
    is WorkspaceFileOperationException, is IOException -> workspaceFileApiError(error).status
    is WorkspaceSelectionLimitException,
    is WorkspaceArchiveOutputLimitException -> HttpStatusCode.PayloadTooLarge
    is WorkspaceDeviceConfigException -> when {
        error.message?.contains("not found", ignoreCase = true) == true ->
            HttpStatusCode.NotFound
        error.message?.contains("permission", ignoreCase = true) == true ->
            HttpStatusCode.Forbidden
        else -> HttpStatusCode.BadRequest
    }
    is WorkspaceUnavailableException -> HttpStatusCode.Conflict
    is IllegalStateException -> HttpStatusCode.Conflict
    is IllegalArgumentException -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.InternalServerError
}

private fun throwDeviceError(error: Throwable): Nothing {
    // Keep machine-readable file codes; don't rebuild a typed API exception as a generic one.
    if (error is ApiException) throw error
    if (error is WorkspaceFileAlreadyExistsException || error is WorkspaceFileOperationException || error is IOException) {
        throw workspaceFileApiError(error)
    }
    val status = deviceErrorStatus(error)
    val message = sanitizeDeviceHostPath(error.message, mount = null)
    when (status) {
        HttpStatusCode.PayloadTooLarge -> throw me.rerere.rikkihub.web.PayloadTooLargeException(
            message.ifBlank { "Request too large" })
        HttpStatusCode.BadRequest -> throw BadRequestException(
            message.ifBlank { "Invalid device directory request" })
        HttpStatusCode.Forbidden -> throw ForbiddenException(
            message.ifBlank { "Device directory access denied" })
        HttpStatusCode.NotFound -> throw NotFoundException(
            message.ifBlank { "Device mount not found" })
        HttpStatusCode.Conflict -> throw ConflictException(
            message.ifBlank { "Device directory is busy or unavailable" })
        else -> if (error is ApiException) throw error
        else throw RuntimeException("Device directory operation failed")
    }
}

private suspend fun <T> runDeviceOp(block: suspend () -> T): T {
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throwDeviceError(error)
    }
}

/**
 * 设备文件操作: service.withMountOperation 解析 mount (alias 或 mountId, 只接
 * resolveMounts 运行态校验通过的连接) 并在 USER_FILE gate 下执行; 文件层
 * IllegalArgumentException 在块内脱敏后转 400 (经 IllegalStateException→409
 * 之前先包成 BadRequestException 不丢状态), source 失效由 domain 层直接抛。
 */
private suspend fun <T> io.ktor.server.routing.RoutingContext.deviceFileOp(
    deviceService: WorkspaceDeviceDirectoryService,
    workspaceId: String,
    mountRef: String,
    block: suspend (WorkspaceDeviceMount) -> T,
): T = runDeviceOp {
    deviceService.withMountOperation(
        workspaceId, mountRef, expectedViewToken = call.request.queryParameters["view"],
    ) { mount ->
        try {
            block(mount)
        } catch (error: ApiException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: WorkspaceFileAlreadyExistsException) {
            throw workspaceFileApiError(error)
        } catch (error: WorkspaceFileOperationException) {
            throw workspaceFileApiError(error)
        } catch (error: IOException) {
            throw workspaceFileApiError(error)
        } catch (error: IllegalArgumentException) {
            throw BadRequestException(
                sanitizeDeviceHostPath(error.message, mount).ifBlank {
                    "Invalid device file path"
                }
            )
        }
    }
}

/**
 * 宿主绝对路径脱敏: 不回 <alias>/<relativePath> 之外的任何宿主路径。
 * 两种输入: mount 对象 (replace mount.source.path) 或裸 source 前缀字符串。
 */
internal fun sanitizeDeviceHostPath(
    message: String?,
    mount: WorkspaceDeviceMount? = null,
    mountPath: String? = null,
): String {
    if (message.isNullOrEmpty()) return ""
    var out = message
    val source = mount?.source?.path ?: mountPath
    if (!source.isNullOrBlank()) {
        out = out.replace(source, "<device>")
    }
    // 兜底: 剩余形似绝对路径的 token 一律折叠, 不保留任何宿主路径片段
    out = out.replace(Regex("(?<![\\w<])/(?:[\\w.@+\\-]+/)*[\\w.@+\\-]+"), "<path>")
    return out
}

private fun sanitizeDeviceDisplayName(fileName: String): String {
    val normalized = fileName.substringAfterLast('/').substringAfterLast('\\')
    return normalized
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .ifBlank { "file" }
}

private class DeviceSizeLimitedInputStream(
    private val input: InputStream,
    private val maxBytes: Int,
) : InputStream() {
    private var totalBytes = 0

    override fun read(): Int {
        val value = input.read()
        if (value >= 0) add(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = input.read(b, off, len)
        if (read > 0) add(read)
        return read
    }

    private fun add(bytes: Int) {
        totalBytes += bytes
        if (totalBytes > maxBytes) {
            throw BadRequestException("File too large: max ${maxBytes / (1024 * 1024)} MB")
        }
    }

    override fun close() = input.close()
}
