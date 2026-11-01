package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH
import me.rerere.rikkihub.data.workspace.RootfsInstallAlreadyRunningException
import me.rerere.rikkihub.data.workspace.RootfsInstallState
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import me.rerere.rikkihub.web.CodedApiException
import io.ktor.server.request.receiveText
import kotlin.uuid.Uuid
import me.rerere.rikkihub.web.ApiException
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.TooManyRequestsException
import me.rerere.rikkihub.web.dto.CreateWorkspaceRequest
import me.rerere.rikkihub.web.dto.MoveWorkspaceFileRequest
import me.rerere.rikkihub.web.dto.MoveWorkspaceFileResponse
import me.rerere.rikkihub.web.dto.TransferWorkspaceFilesRequest
import me.rerere.rikkihub.web.dto.TransferWorkspaceFilesResponse
import me.rerere.rikkihub.web.dto.WorkspaceTransferItemDto
import me.rerere.rikkihub.data.repository.WorkspaceTransferWorkspaceNotFoundException
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceTransferMode
import me.rerere.rikkihub.web.dto.WorkspaceBatchDeleteFailureDto
import me.rerere.rikkihub.web.dto.WorkspaceBatchDeleteResponse
import me.rerere.rikkihub.web.dto.ErrorResponse
import me.rerere.rikkihub.web.dto.WorkspaceFileContentDto
import me.rerere.rikkihub.web.dto.WorkspaceFileListResponse
import me.rerere.rikkihub.web.dto.WorkspaceTerminalSessionDto
import me.rerere.rikkihub.web.dto.toDto
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.workspace.WorkspaceStorageArea
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val MAX_UPLOAD_FILE_SIZE_BYTES = 32 * 1024 * 1024
private val CONTROL_CHARS_REGEX = Regex("[\\u0000-\\u001F\\u007F]")
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
    "dockerfile", "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
    "diff", "patch", "srt", "vtt",
)
private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

fun Route.workspaceRoutes(
    workspaceRepository: WorkspaceRepository,
    terminalManager: WebWorkspaceTerminalManager,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager,
    settingsStore: SettingsStore,
    // 归档临时文件目录, 默认 JVM java.io.tmpdir (Android 上 = 应用 cacheDir)
    archiveTempDir: java.io.File = defaultWorkspaceArchiveTempDir(),
    // 设备目录直连 (可选, 默认 null 不挂 device 路由 -> 404): 由调用方注入,
    // 本路由层不做全局单例回查
    deviceService: me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService? = null,
    deviceStorage: me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage? = null,
    // Web 安装 Rootfs（App/Web 共用的服务级任务）；null 时不挂 rootfs 路由
    rootfsInstallService: me.rerere.rikkihub.data.workspace.WorkspaceRootfsInstallService? = null,
) {
    // (workspaceId, area) 粒度互斥: 序列化单删/批删/归档
    val fileOpLocks = WorkspaceFileOperationLocks()
    // 归档是重 IO, 全局并发许可, 超额立即 429 不排队
    val archivePermits = WorkspaceArchivePermits()
    route("/workspaces") {
        get {
            call.respond(workspaceRepository.list().map { it.toDto() })
        }

        post {
            val request = call.receive<CreateWorkspaceRequest>()
            val workspace = try {
                workspaceRepository.create(
                    name = request.name.orEmpty(),
                    harnessMinimal = request.harnessMinimal,
                )
            } catch (error: IllegalArgumentException) {
                throw BadRequestException(error.message ?: "Invalid workspace name")
            }
            call.respond(HttpStatusCode.Created, workspace.toDto())
        }

        get("/{id}") {
            val workspace = requireWorkspace(workspaceRepository, call.parameters["id"])
            call.respond(workspace.toDto())
        }

        put("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val body = call.receive<JsonObject>()
            val patch = parseWorkspaceUpdate(body)
            if (patch.hasSubagentModelId && patch.subagentModelId != null) {
                requireToolCapableChatModelId(settingsStore.settingsFlow.value, patch.subagentModelId)
            }
            // 单次原子写入: 普通字段 + expose + compatibility + toolApprovals/enablement
            // 增量 JSON 全部在一个 latest.copy transform 内, 由 applyConfigPatch 统一
            // 走 Room 事务 + gate 发布 (取消受影响在途工具/关闭相关 session)。
            // 路由层不再逐字段独立写库、也不再自行 close bash/终端会话。
            val updated = (try {
                workspaceRepository.applyConfigPatch(id) { workspace ->
                    workspace.copy(
                        name = if (patch.hasName) {
                            patch.name?.trim()?.ifBlank { workspace.name } ?: workspace.name
                        } else {
                            workspace.name
                        },
                        subagentModelId = if (patch.hasSubagentModelId) {
                            patch.subagentModelId
                        } else {
                            workspace.subagentModelId
                        },
                        subagentAllowContext = if (patch.hasSubagentAllowContext) {
                            patch.subagentAllowContext ?: workspace.subagentAllowContext
                        } else {
                            workspace.subagentAllowContext
                        },
                        shellCompatibilityMode = if (patch.hasShellCompatibilityMode) {
                            patch.shellCompatibilityMode ?: workspace.shellCompatibilityMode
                        } else {
                            workspace.shellCompatibilityMode
                        },
                        firstMessageFileSnapshot = patch.firstMessageFileSnapshot ?: workspace.firstMessageFileSnapshot,
                        firstMessageFileSnapshotDepth = patch.firstMessageFileSnapshotDepth ?: workspace.firstMessageFileSnapshotDepth,
                        exposeUpload = patch.exposedMounts["/upload"] ?: workspace.exposeUpload,
                        exposeSkills = patch.exposedMounts["/skills"] ?: workspace.exposeSkills,
                        exposeToolOutputs = patch.exposedMounts["/tool_outputs"]
                            ?: workspace.exposeToolOutputs,
                        toolApprovals = if (patch.toolApprovals.isNotEmpty()) {
                            JsonInstant.encodeToString(
                                workspace.toolApprovalOverrides() + patch.toolApprovals
                            )
                        } else {
                            workspace.toolApprovals
                        },
                        toolEnablement = when {
                            patch.resetToolEnablement -> WorkspaceToolEnablement.validatedJson(emptyMap())
                            patch.hasToolEnablement -> WorkspaceToolEnablement.mergedJson(
                                workspace.toolEnablement, patch.toolEnablement
                            )
                            else -> workspace.toolEnablement
                        },
                    ).also { next ->
                        if (patch.hasName && next.name != workspace.name &&
                            workspaceRepository.isNameTaken(next.name, excludeId = id)
                        ) {
                            throw IllegalArgumentException("Workspace name already exists: ${next.name}")
                        }
                    }
                }
            } catch (error: IllegalArgumentException) {
                // 含 tool_enablement 存储损坏时 mergedJson 的 fail-closed 拒绝
                throw BadRequestException(error.message ?: "Invalid workspace update")
            }) ?: throw NotFoundException("Workspace not found")
            call.respond(updated.toDto())
        }

        delete("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            terminalManager.closeWorkspace(id)
            persistentBashSessionManager.closeWorkspace(id)
            val deleted = workspaceRepository.delete(id)
            if (!deleted) throw NotFoundException("Workspace not found")
            call.respond(HttpStatusCode.NoContent)
        }

        if (rootfsInstallService != null) {
            // Web 也能装/重装 rootfs。状态与 App 工作区页共用同一个服务级任务。
            get("/{id}/rootfs") {
                val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
                requireWorkspace(workspaceRepository, id)
                call.respond(RootfsInstallStatusDto(rootfsInstallService.state(id), defaultRootfsUrl()))
            }
            post("/{id}/rootfs") {
                val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
                requireWorkspace(workspaceRepository, id)
                val url = parseRootfsInstallUrl(call.receiveText()) ?: defaultRootfsUrl()
                rootfsInstallService.dismiss(id)
                val state = try {
                    rootfsInstallService.start(id, url)
                } catch (_: RootfsInstallAlreadyRunningException) {
                    throw CodedApiException(HttpStatusCode.Conflict, "ROOTFS_INSTALLING", "Rootfs install already running")
                }
                call.respond(HttpStatusCode.Accepted, RootfsInstallStatusDto(state, defaultRootfsUrl()))
            }
            delete("/{id}/rootfs") {
                val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
                requireWorkspace(workspaceRepository, id)
                rootfsInstallService.cancel(id)
                rootfsInstallService.dismiss(id)
                call.respond(RootfsInstallStatusDto(rootfsInstallService.state(id), defaultRootfsUrl()))
            }
        }

        get("/{id}/files") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val path = call.request.queryParameters["path"].orEmpty()
            val entries = try {
                workspaceRepository.listFiles(id, area, path)
            } catch (error: IllegalArgumentException) {
                throw BadRequestException(error.message ?: "Invalid path")
            }
            call.respond(
                WorkspaceFileListResponse(
                    area = area.name,
                    path = path,
                    entries = entries.map { it.toDto() },
                )
            )
        }

        post("/{id}/files/mkdir") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val request = readWorkspaceDirectoryRequest(call)
            if (request.exclusive && area != WorkspaceStorageArea.FILES) {
                throw BadRequestException("Linux files are read-only")
            }
            val entry = workspaceFileApiOperation {
                if (request.exclusive) workspaceRepository.createDirectory(id, request.path)
                else workspaceRepository.mkdir(id, area, request.path)
            }
            call.respond(HttpStatusCode.Created, entry.toDto())
        }

        get("/{id}/files/content") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val path = call.request.queryParameters["path"]?.trim().orEmpty()
            if (path.isBlank()) throw BadRequestException("Missing file path")
            val snapshot = workspaceFileApiOperation {
                workspaceRepository.readTextSnapshotForPreview(id, area, path)
            }
            call.respond(
                WorkspaceFileContentDto(
                    path = path,
                    area = area.name,
                    content = snapshot.content,
                    editable = area == WorkspaceStorageArea.FILES,
                    revision = snapshot.revision,
                )
            )
        }

        put("/{id}/files/content") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            if (area != WorkspaceStorageArea.FILES) {
                throw BadRequestException("Linux files are read-only")
            }
            // Do not silently rewrite a requested creation name (including whitespace).
            val path = call.request.queryParameters["path"].orEmpty()
            if (path.isBlank()) throw BadRequestException("Missing file path")
            val request = readWorkspaceFileWriteRequest(call)
            val entry = workspaceFileApiOperation {
                if (request.expectedRevision != null) {
                    val saved = workspaceRepository.writeTextWithRevision(id, path, request.content, request.expectedRevision)
                    saved.entry.toDto().copy(revision = saved.revision)
                } else {
                    // Legacy clients/tools retain the explicit old overwrite contract.
                    workspaceRepository.writeText(id, path, request.content, request.overwrite).toDto()
                }
            }
            call.respond(entry)
        }

        get("/{id}/files/download") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val path = call.request.queryParameters["path"]?.trim().orEmpty()
            if (path.isBlank()) throw BadRequestException("Missing file path")
            // 先校验存在且为文件再写响应头, 否则错误只会在 body 输出流里才暴露
            try {
                workspaceRepository.fileSize(id, area, path)
            } catch (error: IllegalArgumentException) {
                throw BadRequestException(error.message ?: "Unable to download file")
            }
            val fileName = path.substringAfterLast('/').ifBlank { "file" }
            val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
            call.response.header("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
            call.response.header("Content-Type", guessContentType(fileName))
            call.respondOutputStream {
                // Streamed from the backend; it closes its stream, not Ktor's.
                val sink = object : java.io.FilterOutputStream(this) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                    override fun close() = flush()
                }
                workspaceRepository.exportFile(id = id, area = area, path = path, outputStream = sink)
            }
        }

        // 聊天里模型写的 Markdown 图片（/workspace/out.png、/tmp/plot.png）：按模型看到的路径与「仅此目录」取图
        get("/{id}/rootfs-file") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val request = parseRootfsImageRequest(
                path = call.request.queryParameters["path"],
                scope = call.request.queryParameters["scope"],
            )
            // 范围外、越界、不存在一律同一个 404，不泄露路径是否存在
            val info = try {
                workspaceRepository.statRootfs(id, request.path, request.filesScope)
            } catch (_: IllegalArgumentException) {
                throw NotFoundException("File not found")
            }
            if (!info.isFile) throw NotFoundException("File not found")
            if (info.sizeBytes > MAX_ROOTFS_IMAGE_BYTES) throw BadRequestException("Image is too large to show")

            val etag = rootfsImageEtag(info.sizeBytes, info.updatedAt)
            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.CacheControl, "private, no-cache")
            // 仅供 <img> 使用：即使直接打开也不执行任何内容
            call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
            if (request.forceAttachment) {
                call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.toString())
            }
            if (rootfsImageEtagMatches(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
            call.respondOutputStream(ContentType.parse(request.contentType)) {
                val sink = object : java.io.FilterOutputStream(this) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                    override fun close() = flush()
                }
                workspaceRepository.exportRootfsFile(id = id, path = request.path, outputStream = sink, filesScope = request.filesScope)
            }
        }

        post("/{id}/files/upload") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val destination = call.request.queryParameters["path"].orEmpty()
            val multipart = call.receiveMultipart()
            var uploaded: me.rerere.workspace.WorkspaceFileEntry? = null
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        val originalFileName = part.originalFileName?.takeIf { it.isNotBlank() } ?: "file"
                        val fileName = sanitizeDisplayName(originalFileName)
                        // 直传 InputStream, 不整段进内存; 超限由 SizeLimitedInputStream 在流式拷贝中断掉
                        val rawInput = PushbackInputStream(part.provider().toInputStream(), 1)
                        val first = rawInput.read()
                        if (first < 0) throw BadRequestException("Uploaded file is empty")
                        rawInput.unread(first)
                        uploaded = workspaceRepository.importFile(
                            id = id,
                            area = area,
                            destinationPath = destination,
                            fileName = fileName,
                            inputStream = SizeLimitedInputStream(rawInput, MAX_UPLOAD_FILE_SIZE_BYTES),
                        )
                    }
                } finally {
                    part.release()
                }
            }
            val entry = uploaded ?: throw BadRequestException("No files uploaded")
            call.respond(HttpStatusCode.Created, entry.toDto())
        }

        post("/{id}/files/move") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            if (area != WorkspaceStorageArea.FILES) {
                throw BadRequestException("Linux files are read-only")
            }
            val request = call.receive<MoveWorkspaceFileRequest>()
            val source = request.source.trim()
            val target = request.target.trim()
            if (source.isBlank() || target.isBlank()) throw BadRequestException("Missing source or target path")
            try {
                workspaceRepository.moveFile(id, source, target, request.overwrite)
            } catch (error: IllegalArgumentException) {
                throw BadRequestException(error.message ?: "Unable to move file")
            }
            call.respond(MoveWorkspaceFileResponse(source = source, target = target))
        }

        // 移动/复制到某个工作区（可为本工作区）的 FILES 文件夹：保留原名、重名自动改名、逐项结果。
        // 整次请求的错误（目标工作区/文件夹不存在等）走 HTTP 状态码；单项失败放在 items 里。
        post("/{id}/files/transfer") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val workspace = requireWorkspace(workspaceRepository, id)
            val request = call.receive<TransferWorkspaceFilesRequest>()
            val mode = when (request.mode) {
                "move" -> WorkspaceTransferMode.MOVE
                "copy" -> WorkspaceTransferMode.COPY
                else -> throw BadRequestException("mode must be \"move\" or \"copy\"")
            }
            val paths = validateTransferPaths(request.paths)
            val targetId = request.targetWorkspaceId?.trim()?.takeIf { it.isNotEmpty() } ?: id
            val target = if (targetId == id) workspace else requireWorkspace(workspaceRepository, targetId)
            val targetDir = request.targetDir.trim()
            val sanitize = { message: String? ->
                sanitizeWorkspaceHostPaths(sanitizeWorkspaceHostPaths(message, workspace.root), target.root)
            }
            val result = try {
                workspaceRepository.transferFiles(id, paths, targetId, targetDir, mode)
            } catch (error: CancellationException) {
                throw error
            } catch (error: WorkspaceTransferWorkspaceNotFoundException) {
                throw NotFoundException("Workspace not found")
            } catch (error: WorkspaceFileOperationException) {
                throw when (error.code) {
                    WorkspaceFileOperationException.PARENT_NOT_FOUND ->
                        CodedApiException(HttpStatusCode.NotFound, "TRANSFER_TARGET_NOT_FOUND", "Destination folder not found")
                    WorkspaceFileOperationException.PARENT_NOT_DIRECTORY ->
                        CodedApiException(HttpStatusCode.BadRequest, "TRANSFER_TARGET_NOT_DIRECTORY", "Destination is not a folder")
                    else -> BadRequestException(sanitize(error.message).ifBlank { "Unable to transfer files" })
                }
            } catch (error: IllegalArgumentException) {
                throw BadRequestException(sanitize(error.message).ifBlank { "Unable to transfer files" })
            } catch (error: Throwable) {
                if (error is ApiException) throw error
                throw RuntimeException("Workspace transfer failed")
            }
            call.respond(
                TransferWorkspaceFilesResponse(
                    targetWorkspaceId = targetId,
                    targetDir = targetDir.trim('/'),
                    items = result.items.map { item ->
                        WorkspaceTransferItemDto(
                            source = item.source,
                            ok = item.ok,
                            target = item.target,
                            isDirectory = item.isDirectory,
                            renamed = item.renamed,
                            skippedLinks = item.skippedLinks,
                            errorCode = item.errorCode,
                            error = item.error?.let { sanitize(it) },
                        )
                    },
                ),
            )
        }

        // 解压 FILES 区的压缩包到它所在目录；密码/编码可选，错误码见 workspaceFileApiError。
        post("/{id}/files/extract") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            requireWorkspace(workspaceRepository, id)
            val request = call.receive<ExtractWorkspaceArchiveRequest>()
            val path = request.path.trim()
            if (path.isBlank()) throw BadRequestException("Missing path")
            val result = workspaceFileApiOperation {
                workspaceRepository.extractArchive(
                    id = id,
                    path = path,
                    password = request.password?.takeIf { it.isNotEmpty() }?.toCharArray(),
                    nameEncoding = request.nameEncoding,
                )
            }
            call.respond(result)
        }

        delete("/{id}/files") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val workspace = requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val path = call.request.queryParameters["path"]?.trim().orEmpty()
            if (path.isBlank()) throw BadRequestException("Missing file path")
            val recursive = call.request.queryParameters["recursive"]?.toBooleanStrictOrNull() ?: false
            val deleted = fileOpLocks.withLock(id, area.name) {
                try {
                    workspaceRepository.deleteFile(id, area, path, recursive)
                } catch (error: IllegalArgumentException) {
                    throw BadRequestException(
                        sanitizeWorkspaceHostPaths(error.message, workspace.root)
                            .ifBlank { "Unable to delete file" }
                    )
                }
            }
            if (!deleted) throw NotFoundException("File not found")
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/files/batch-delete") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val workspace = requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val paths = parseWorkspaceSelectionOrRespond(call) ?: return@post
            val result = fileOpLocks.withLock(id, area.name) {
                try {
                    // 模块层先把全部选中项验完 (存在/越界/根/符号链接) 再动手,
                    // 任一无效则一项都不删, 直接 400
                    workspaceRepository.deleteFiles(id, area, paths)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    throw BadRequestException(
                        sanitizeWorkspaceHostPaths(error.message, workspace.root)
                            .ifBlank { "Unable to delete selection" }
                    )
                } catch (error: IllegalStateException) {
                    if (error.message?.contains("not found", ignoreCase = true) == true) {
                        throw NotFoundException("Workspace not found")
                    }
                    // 未知 IO 错误消息可能含宿主绝对路径, 不落全局 cause.message 兜底
                    throw RuntimeException("Workspace batch delete failed")
                } catch (error: Throwable) {
                    if (error is ApiException) throw error
                    throw RuntimeException("Workspace batch delete failed")
                }
            }
            call.respond(
                WorkspaceBatchDeleteResponse(
                    deletedPaths = result.deletedPaths,
                    failures = result.failures.map {
                        WorkspaceBatchDeleteFailureDto(
                            path = it.path,
                            error = sanitizeWorkspaceHostPaths(it.error, workspace.root),
                        )
                    },
                )
            )
        }

        post("/{id}/files/archive") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val workspace = requireWorkspace(workspaceRepository, id)
            val area = parseArea(call.request.queryParameters["area"])
            val paths = parseWorkspaceSelectionOrRespond(call) ?: return@post
            // 并发许可在锁前拿, 等锁排队不占归档名额
            if (!archivePermits.tryAcquire()) {
                throw TooManyRequestsException("Too many concurrent archive exports")
            }
            try {
                fileOpLocks.withLock(id, area.name) {
                    // 预检: 选中项存在性/条目数/源总大小, 在动盘前先拒绝
                    measureWorkspaceSelection(
                        relPaths = paths,
                        listDir = { rel ->
                            workspaceRepository.listFiles(id, area, rel)
                        },
                        fileSize = { rel ->
                            workspaceRepository.fileSize(id, area, rel)
                        },
                    )
                    // 先在临时文件里完整生成 ZIP, 成功后才响应;
                    // 绝不 respondOutputStream 先发 200 再跑可能失败的归档
                    val tmp = writeArchiveToTempFile(archiveTempDir) { out ->
                        workspaceRepository.exportArchive(id, area, paths, out)
                    }
                    try {
                        val zipName = sanitizeDisplayName(workspace.name)
                            .ifBlank { "workspace" } + ".zip"
                        val encoded = URLEncoder.encode(zipName, StandardCharsets.UTF_8)
                            .replace("+", "%20")
                        call.response.header(
                            "Content-Disposition",
                            "attachment; filename*=UTF-8''$encoded"
                        )
                        // LocalFileContent 的写出在 respond 管线内完成;
                        // finally 才回收临时文件, 失败/取消也一样走这里
                        call.respond(LocalFileContent(tmp, ContentType.Application.Zip))
                    } finally {
                        tmp.delete()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 未知 IO/500 绝不能落 StatusPages 的 cause.message 兜底
                // (可能含宿主绝对路径) —— 统一走映射函数, 400/413 保具体提示,
                // 其余一律脱敏后的通用文案; ApiException 保留原状态码重抛
                val mapped = workspaceBatchError(error, workspace.root)
                if (error is ApiException) throw error
                call.respond(mapped.status, ErrorResponse(mapped.message, mapped.status.value))
            } finally {
                archivePermits.release()
            }
        }

        post("/{id}/terminal/sessions") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing workspace id")
            val cols = call.request.queryParameters["cols"]?.toIntOrNull() ?: 80
            val rows = call.request.queryParameters["rows"]?.toIntOrNull() ?: 24
            val session = try {
                // createSession 内部已校验 workspace 存在/shell ready, 不再重复 getById
                terminalManager.createSession(id, cols, rows)
            } catch (error: IllegalArgumentException) {
                throw BadRequestException(error.message ?: "Unable to start terminal")
            } catch (error: IllegalStateException) {
                throw BadRequestException(error.message ?: "Unable to start terminal")
            }
            call.respond(
                HttpStatusCode.Created,
                WorkspaceTerminalSessionDto(
                    id = session.id,
                    workspaceId = session.workspaceId,
                    createdAt = session.createdAt,
                    cols = session.cols,
                    rows = session.rows,
                )
            )
        }

        webSocket("/{id}/terminal/sessions/{sessionId}") {
            val workspaceId = call.parameters["id"] ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing workspace id"))
                return@webSocket
            }
            val sessionId = call.parameters["sessionId"] ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing session id"))
                return@webSocket
            }
            val live = try {
                terminalManager.get(sessionId)
            } catch (_: NotFoundException) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Terminal session not found"))
                return@webSocket
            }
            if (live.workspaceId != workspaceId) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Workspace mismatch"))
                return@webSocket
            }
            terminalManager.markAttached(sessionId)

            try {
                coroutineScope {
                    val writer = launch {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> live.pty.write(frame.readBytes())
                                is Frame.Text -> handleControlFrame(live, frame.readText())
                                is Frame.Close -> break
                                else -> Unit
                            }
                        }
                    }
                    val reader = launch(Dispatchers.IO) {
                        val buffer = ByteArray(4096)
                        while (isActive) {
                            val read = live.pty.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            send(Frame.Binary(true, buffer.copyOf(read)))
                        }
                        // PTY EOF: 主动正常关闭 WS, 并停掉 incoming 消费循环,
                        // 否则客户端不关连接的话这条 WS 会一直挂着
                        runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "Shell exited")) }
                        writer.cancel()
                    }
                    writer.join()
                    // 先关 PTY 解除 reader 里的阻塞 read, 再 cancel, 否则 coroutineScope 永不返回
                    runCatching { live.pty.close() }
                    reader.cancel()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, error.message ?: "Terminal error"))
            } finally {
                terminalManager.close(sessionId)
            }
        }
    }

    // 设备目录直连: /workspaces/{id}/device/* —— 顶层挂载, 嵌在 /workspaces
    // 里会叠加出 /workspaces/workspaces 前缀。默认 null 时完全不挂, 走全局 404。
    if (deviceService != null && deviceStorage != null) {
        workspaceDeviceRoutes(workspaceRepository, deviceService, deviceStorage)
    }
}

/**
 * 批量选中请求的统一入口: 有界读 body → 严格 schema → 词法归一化去重.
 * 413 限额直接在这里回包返回 null; 400 schema/路径错走 BadRequestException → StatusPages.
 */
private suspend fun parseWorkspaceSelectionOrRespond(call: ApplicationCall): List<String>? {
    return try {
        val body = readWorkspaceSelectionBody(call)
        normalizeWorkspaceSelectionPaths(extractWorkspaceSelectionPaths(body))
    } catch (error: WorkspaceSelectionLimitException) {
        call.respond(
            HttpStatusCode.PayloadTooLarge,
            ErrorResponse(error.message ?: "Request too large", 413)
        )
        null
    }
}

private suspend fun requireWorkspace(
    workspaceRepository: WorkspaceRepository,
    id: String?,
): me.rerere.rikkihub.data.db.entity.WorkspaceEntity {
    if (id.isNullOrBlank()) throw BadRequestException("Missing workspace id")
    return workspaceRepository.getById(id) ?: throw NotFoundException("Workspace not found")
}

private fun parseArea(raw: String?): WorkspaceStorageArea {
    if (raw.isNullOrBlank()) return WorkspaceStorageArea.FILES
    return runCatching { WorkspaceStorageArea.valueOf(raw.uppercase()) }
        .getOrElse { throw BadRequestException("Invalid storage area") }
}

private fun sanitizeDisplayName(fileName: String): String {
    val normalized = fileName.substringAfterLast('/').substringAfterLast('\\')
    return normalized
        .replace(CONTROL_CHARS_REGEX, "")
        .ifBlank { "file" }
}

private class SizeLimitedInputStream(
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

private fun guessContentType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTENSIONS -> when (ext) {
            "png" -> ContentType.Image.PNG.toString()
            "jpg", "jpeg" -> ContentType.Image.JPEG.toString()
            "gif" -> ContentType.Image.GIF.toString()
            "svg" -> ContentType.Image.SVG.toString()
            "webp" -> ContentType("image", "webp").toString()
            else -> ContentType.Image.Any.toString()
        }
        ext in TEXT_EXTENSIONS -> ContentType.Text.Plain.toString()
        else -> ContentType.Application.OctetStream.toString()
    }
}

private fun handleControlFrame(
    live: WebWorkspaceTerminalManager.LiveSession,
    text: String,
) {
    runCatching {
        val json = Json.parseToJsonElement(text).jsonObject
        if (json["type"]?.jsonPrimitive?.content != "resize") return
        val cols = json["cols"]?.jsonPrimitive?.intOrNull ?: return
        val rows = json["rows"]?.jsonPrimitive?.intOrNull ?: return
        live.pty.resize(rows.coerceIn(8, 120), cols.coerceIn(20, 300))
    }
}

internal fun isWorkspaceTextFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in TEXT_EXTENSIONS
}

internal fun isWorkspaceImageFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXTENSIONS
}

internal data class WorkspaceUpdatePatch(
    val hasName: Boolean,
    val name: String?,
    val hasSubagentModelId: Boolean,
    val subagentModelId: String?,
    val hasSubagentAllowContext: Boolean,
    val subagentAllowContext: Boolean?,
    val hasShellCompatibilityMode: Boolean,
    val shellCompatibilityMode: Boolean?,
    // null = 不变
    val firstMessageFileSnapshot: Boolean? = null,
    // null = 不变；1..WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH
    val firstMessageFileSnapshotDepth: Int? = null,
    // 暴露挂载点开关: key 是 rootfs 内挂载目标 ("/upload" 等)
    val exposedMounts: Map<String, Boolean>,
    // 工具审批覆盖: 增量 merge 进现有 overrides, 未带的键保持原值
    val toolApprovals: Map<String, Boolean>,
    // 工具启用覆盖 (稳定内建 ID -> enabled): 增量 merge, 缺键保留; 损坏配置修复走 reset 标记
    val hasToolEnablement: Boolean,
    val toolEnablement: Map<String, Boolean>,
    // true = 显式重置 tool_enablement 为 "{}" (全部启用); 与 toolEnablement 互斥
    val resetToolEnablement: Boolean,
)

private val EXPOSED_MOUNT_FIELD_TARGETS = mapOf(
    "exposeUpload" to "/upload",
    "exposeSkills" to "/skills",
    "exposeToolOutputs" to "/tool_outputs",
)

private fun parseRequiredBoolean(body: JsonObject, field: String): Boolean? {
    if (field !in body) return null
    return when (val raw = body[field]) {
        is JsonPrimitive -> raw.booleanOrNull
            ?: throw BadRequestException("$field must be a boolean")
        else -> throw BadRequestException("$field must be a boolean")
    }
}

internal fun parseWorkspaceUpdate(body: JsonObject): WorkspaceUpdatePatch {
    // Collection writes must go through the per-preset repository CAS API, never workspace PUT.
    if ("subagentPresets" in body || "subagent_presets" in body) {
        throw BadRequestException("Use the subagent-presets API to edit preset configuration")
    }
    if ("name" !in body && "subagentModelId" !in body && "subagentAllowContext" !in body &&
        "shellCompatibilityMode" !in body && "firstMessageFileSnapshot" !in body && "firstMessageFileSnapshotDepth" !in body && "toolApprovals" !in body &&
        "toolEnablement" !in body && "resetToolEnablement" !in body &&
        EXPOSED_MOUNT_FIELD_TARGETS.keys.none { it in body }
    ) {
        throw BadRequestException("No workspace fields to update")
    }
    val hasName = "name" in body
    val name = if (hasName) {
        when (val raw = body["name"]) {
            is JsonPrimitive -> raw.contentOrNull
                ?: throw BadRequestException("name must be a string")
            else -> throw BadRequestException("name must be a string")
        }
    } else {
        null
    }
    val hasSubagentModelId = "subagentModelId" in body
    val subagentModelId = if (hasSubagentModelId) {
        when (val raw = body["subagentModelId"]) {
            JsonNull -> null
            is JsonPrimitive -> raw.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            else -> throw BadRequestException("subagentModelId must be a string or null")
        }
    } else {
        null
    }
    val hasSubagentAllowContext = "subagentAllowContext" in body
    val subagentAllowContext = if (hasSubagentAllowContext) {
        when (val raw = body["subagentAllowContext"]) {
            is JsonPrimitive -> raw.booleanOrNull
                ?: throw BadRequestException("subagentAllowContext must be a boolean")
            else -> throw BadRequestException("subagentAllowContext must be a boolean")
        }
    } else {
        null
    }
    val hasShellCompatibilityMode = "shellCompatibilityMode" in body
    val shellCompatibilityMode = if (hasShellCompatibilityMode) {
        parseRequiredBoolean(body, "shellCompatibilityMode")
    } else {
        null
    }
    val firstMessageFileSnapshot = parseRequiredBoolean(body, "firstMessageFileSnapshot")
    val firstMessageFileSnapshotDepth = if ("firstMessageFileSnapshotDepth" in body) {
        val raw = body["firstMessageFileSnapshotDepth"] as? JsonPrimitive
        val depth = raw?.takeUnless { it.isString }?.intOrNull
        if (depth == null || depth !in 1..WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH) {
            throw BadRequestException("firstMessageFileSnapshotDepth must be an integer from 1 to $WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH")
        }
        depth
    } else {
        null
    }
    val exposedMounts = EXPOSED_MOUNT_FIELD_TARGETS.mapNotNull { (field, target) ->
        parseRequiredBoolean(body, field)?.let { target to it }
    }.toMap()
    val toolApprovals = mutableMapOf<String, Boolean>()
    if ("toolApprovals" in body) {
        val raw = body["toolApprovals"] as? JsonObject
            ?: throw BadRequestException("toolApprovals must be an object")
        raw.forEach { (toolName, value) ->
            if (toolName !in WorkspaceToolDefaultApprovals) {
                throw BadRequestException("Unknown workspace tool: $toolName")
            }
            val needsApproval = (value as? JsonPrimitive)?.booleanOrNull
                ?: throw BadRequestException("toolApprovals.$toolName must be a boolean")
            toolApprovals[toolName] = needsApproval
        }
    }
    val hasToolEnablement = "toolEnablement" in body
    val toolEnablement = mutableMapOf<String, Boolean>()
    if (hasToolEnablement) {
        val raw = body["toolEnablement"] as? JsonObject
            ?: throw BadRequestException("toolEnablement must be an object")
        raw.forEach { (toolId, value) ->
            if (toolId !in WorkspaceToolEnablement.configurableIds) {
                throw BadRequestException("Unknown workspace tool id: $toolId")
            }
            // 严格字面 boolean: 不接受字符串 "true"/数字 1/null/嵌套对象
            val prim = value as? JsonPrimitive
                ?: throw BadRequestException("toolEnablement.$toolId must be a boolean")
            if (prim.isString) {
                throw BadRequestException("toolEnablement.$toolId must be a boolean")
            }
            toolEnablement[toolId] = when (prim.content) {
                "true" -> true
                "false" -> false
                else -> throw BadRequestException("toolEnablement.$toolId must be a boolean")
            }
        }
    }
    val resetToolEnablement = parseRequiredBoolean(body, "resetToolEnablement") ?: false
    if (resetToolEnablement && hasToolEnablement) {
        throw BadRequestException("resetToolEnablement cannot be combined with toolEnablement")
    }
    return WorkspaceUpdatePatch(
        hasName = hasName,
        name = name,
        hasSubagentModelId = hasSubagentModelId,
        subagentModelId = subagentModelId,
        hasSubagentAllowContext = hasSubagentAllowContext,
        subagentAllowContext = subagentAllowContext,
        hasShellCompatibilityMode = hasShellCompatibilityMode,
        shellCompatibilityMode = shellCompatibilityMode,
        firstMessageFileSnapshot = firstMessageFileSnapshot,
        firstMessageFileSnapshotDepth = firstMessageFileSnapshotDepth,
        exposedMounts = exposedMounts,
        toolApprovals = toolApprovals,
        hasToolEnablement = hasToolEnablement,
        toolEnablement = toolEnablement,
        resetToolEnablement = resetToolEnablement,
    )
}

internal fun requireToolCapableChatModelId(settings: Settings, modelId: String) {
    val uuid = runCatching { Uuid.parse(modelId) }.getOrNull()
        ?: throw BadRequestException("Invalid subagentModelId")
    val model = settings.findModelById(uuid)
    if (model == null || model.type != ModelType.CHAT || ModelAbility.TOOL !in model.abilities) {
        throw BadRequestException("subagentModelId must be a chat model with tool ability")
    }
}

@kotlinx.serialization.Serializable
internal data class ExtractWorkspaceArchiveRequest(
    val path: String,
    val password: String? = null,
    val nameEncoding: String? = null,
)

@kotlinx.serialization.Serializable
internal data class RootfsInstallStatusDto(
    /** null = no install running and no failure to show */
    val state: RootfsInstallState?,
    val defaultUrl: String,
)

/** Body `{"url": "https://…"}` (optional; empty body or blank url → default image). */
internal fun parseRootfsInstallUrl(body: String): String? {
    if (body.isBlank()) return null
    val json = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: throw BadRequestException("Invalid payload")
    val raw = json["url"] ?: return null
    if (raw is JsonNull) return null
    val url = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        ?: throw BadRequestException("url must be a string")
    if (url.isEmpty()) return null
    if (!(url.startsWith("https://") || url.startsWith("http://"))) throw BadRequestException("url must be http(s)")
    return url
}

/** 与批量删除同一套上限；顺序保留，去重交给模块层 */
internal fun validateTransferPaths(paths: List<String>): List<String> {
    if (paths.isEmpty()) throw BadRequestException("paths must not be empty")
    if (paths.size > MAX_BATCH_PATH_COUNT) throw BadRequestException("Too many paths: ${paths.size}, max $MAX_BATCH_PATH_COUNT")
    var total = 0
    for (path in paths) {
        if (path.contains('\u0000')) throw BadRequestException("Path contains invalid character")
        if (path.length > MAX_BATCH_PATH_LENGTH) throw BadRequestException("Path too long: ${path.length} chars, max $MAX_BATCH_PATH_LENGTH")
        total += path.length
        if (total > MAX_BATCH_TOTAL_PATH_CHARS) throw BadRequestException("Request too large")
        if (path.isBlank()) throw BadRequestException("Empty path")
    }
    return paths
}
