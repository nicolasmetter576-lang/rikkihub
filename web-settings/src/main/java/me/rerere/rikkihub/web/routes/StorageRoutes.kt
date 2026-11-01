package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.entity.ManagedFileEntity
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.dto.StorageCleanupRequest
import me.rerere.rikkihub.web.dto.StorageCleanupResponse
import me.rerere.rikkihub.web.dto.StorageDeleteResult
import me.rerere.rikkihub.web.dto.StorageFileDto
import me.rerere.rikkihub.web.dto.StorageFileListResponse
import me.rerere.rikkihub.web.dto.StorageLeaseRenewRequest
import me.rerere.rikkihub.web.dto.StorageLeaseRenewResponse
import java.io.File
import java.net.URLEncoder

/**
 * 全局聊天附件（managed_files/upload）浏览与草稿租约路由。
 *
 * 本轮范围：列表 + 续租 + 强制下载/预览 + DELETE + cleanup。
 * 删除语义：受保护 → 409 并带 protectionReasons；未知/越界 id → 404（不泄露宿主路径）；
 * cleanup 的 ids 与 olderThanDays 取交集（两个条件都给则同时满足才删），
 * olderThanDays 仅白名单 {7,14,30}，ids ≤500，候选上限 2000。
 *
 * 安全模型：
 * - 只认 managed id 查表 + canonical 归属校验，路径参数永远进不了这里；
 * - 保护判定 fail-closed：任一引用源抛异常按受保护处理；
 * - 下载强制 attachment + application/octet-stream + nosniff，杜绝脚本型内容被渲染。
 */
fun Route.storageRoutes(
    filesManager: FilesManager,
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
    chatService: ChatService,
) {
    route("/storage") {
        val evaluator = StorageProtectionEvaluator(
            dbHasReference = { url, relativePath ->
                conversationRepo.hasStorageFileReference(url, relativePath)
            },
            sessionRefUrls = { chatService.referencedFileUrlsSnapshot() },
            settingsRefUrls = { collectSettingsFileRefs(settingsStore.settingsFlow.value) },
            isRetained = { relativePath -> conversationRepo.isRetained(relativePath) },
        )
        val deleter = StorageDeleter(
            evaluator = evaluator,
            registry = filesManager.attachmentLeases,
            resolveUpload = { id -> filesManager.resolveUploadEntity(id) },
            deleteRow = { id -> filesManager.deleteManagedRow(id) },
        )

        // GET /api/storage/files?offset&limit&query&olderThanDays
        get("/files") {
            val (offset, limit) = sanitizeStoragePageParams(
                call.request.queryParameters["offset"]?.toIntOrNull(),
                call.request.queryParameters["limit"]?.toIntOrNull(),
            )
            val query = call.request.queryParameters["query"]
            val olderThanDays = call.request.queryParameters["olderThanDays"]
                ?.toIntOrNull()
                ?.also { if (it < 0) throw BadRequestException("olderThanDays must be >= 0") }

            val now = System.currentTimeMillis()
            // upload 登记全集（DAO 按 createdAt DESC）；内存过滤+分页，limit 有界保护响应体。
            val all = filesManager.list(FileFolders.UPLOAD)
            val filtered = filterStorageFiles(all, query, olderThanDays, now)
            val pageEntities = filtered.drop(offset).take(limit)

            val files = pageEntities.map { entity ->
                val file = filesManager.resolveUploadFile(entity)
                val existsOnDisk = file?.exists() == true
                // 与消息中持久化的形态保持一致：file:// + absolutePath
                // （android.net.Uri.toString 产物），不是 JDK File.toURI 的 file:/ 形态
                val canonicalUrl = "file://" +
                    (file?.absolutePath ?: "${filesManager.filesDir().absolutePath}/${entity.relativePath}")
                val reasons = evaluator.evaluateProtection(
                    StorageProtectionFacts(
                        relativePath = entity.relativePath,
                        canonicalFileUrl = canonicalUrl,
                        createdAt = entity.createdAt,
                        leaseExpiresAt = filesManager.attachmentLeases.leaseExpiry(entity.relativePath),
                        nowMillis = now,
                    )
                )
                StorageFileDto(
                    id = entity.id,
                    displayName = entity.displayName,
                    mimeType = entity.mimeType,
                    sizeBytes = entity.sizeBytes,
                    createdAt = entity.createdAt,
                    existsOnDisk = existsOnDisk,
                    isProtected = reasons.isNotEmpty(),
                    protectionReasons = reasons
                        .sortedBy { it.ordinal }
                        .map { it.wireName },
                    leaseExpiresAt = filesManager.attachmentLeases.leaseExpiry(entity.relativePath),
                )
            }

            call.respond(
                StorageFileListResponse(
                    files = files,
                    total = filtered.size,
                    totalSizeBytes = filtered.sumOf { it.sizeBytes },
                    overallTotal = all.size,
                    overallSizeBytes = all.sumOf { it.sizeBytes },
                    offset = offset,
                    limit = limit,
                    hasMore = offset + files.size < filtered.size,
                )
            )
        }

        // POST /api/storage/leases {urls:[...]}
        post("/leases") {
            val request = runCatching { call.receive<StorageLeaseRenewRequest>() }
                .getOrElse { throw BadRequestException("Invalid request body") }
            if (request.urls.size > STORAGE_MAX_LEASE_URLS) {
                throw BadRequestException("Too many urls: max $STORAGE_MAX_LEASE_URLS")
            }
            if (request.urls.isEmpty()) {
                throw BadRequestException("urls must not be empty")
            }
            val result = filesManager.renewUploadLeases(request.urls)
            call.respond(
                StorageLeaseRenewResponse(
                    renewed = result.renewedIds.size,
                    rejected = result.rejected,
                    leaseTtlMillis = result.leaseTtlMillis,
                )
            )
        }

        // POST /api/storage/cleanup {ids?, olderThanDays?} —— 条件取交集
        post("/cleanup") {
            val request = runCatching { call.receive<StorageCleanupRequest>() }
                .getOrElse { throw BadRequestException("Invalid request body") }
            val ids = request.ids
            val olderThanDays = request.olderThanDays
            if (ids.isEmpty() && olderThanDays == null) {
                throw BadRequestException("Provide ids and/or olderThanDays")
            }
            if (olderThanDays != null && olderThanDays !in STORAGE_CLEANUP_ALLOWED_DAYS) {
                throw BadRequestException(
                    "olderThanDays must be one of ${STORAGE_CLEANUP_ALLOWED_DAYS.sorted()}"
                )
            }
            if (ids.size > STORAGE_CLEANUP_MAX_IDS) {
                throw BadRequestException("Too many ids: max $STORAGE_CLEANUP_MAX_IDS")
            }

            val now = System.currentTimeMillis()
            // 候选 = ids 解析出的实体 ∩ olderThanDays 过滤。 ids 解析失败项记为失败项。
            val byId = mutableListOf<Pair<Long, Pair<ManagedFileEntity, File>?>>()
            ids.distinct().forEach { id ->
                val resolved = try {
                    filesManager.resolveUploadEntity(id)
                } catch (_: Exception) {
                    null
                }
                byId += id to resolved
            }

            val unknownIds = byId.filter { it.second == null }.map { it.first }
            var candidates = byId.mapNotNull { it.second?.first }
            if (ids.isEmpty()) {
                // 未指定 ids：候选 = upload 中满足 olderThanDays 的全部实体
                candidates = filesManager.list(FileFolders.UPLOAD)
            }
            if (olderThanDays != null) {
                candidates = filterStorageFiles(candidates, null, olderThanDays, now)
            }

            val truncated = candidates.size > STORAGE_CLEANUP_MAX_CANDIDATES
            val items = mutableListOf<StorageDeleteResult>()
            unknownIds.forEach { id ->
                items += StorageDeleteResult(
                    status = "failed",
                    id = id,
                    error = "unknown_id",
                )
            }
            var deletedBytes = 0L
            candidates.take(STORAGE_CLEANUP_MAX_CANDIDATES).forEach { entity ->
                // 协程取消时立即停：不继续遍历剩余候选。
                coroutineContext.ensureActive()
                // 逐项走完整管线：每次重查 DB/session/settings，失败 fail-closed。
                val resolvedFile = try {
                    filesManager.resolveUploadFile(entity)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val outcome = if (resolvedFile == null) {
                    StorageDeleteOutcome.Failed("unsafe_path")
                } else {
                    deleter.deleteResolved(entity, resolvedFile)
                }
                items += when (outcome) {
                    is StorageDeleteOutcome.Protected -> StorageDeleteResult(
                        status = "skipped",
                        id = entity.id,
                        reasons = outcome.reasons,
                    )

                    is StorageDeleteOutcome.Deleted -> {
                        deletedBytes += outcome.bytesFreed
                        StorageDeleteResult(
                            status = "deleted",
                            id = entity.id,
                            bytesFreed = outcome.bytesFreed,
                            alreadyMissing = outcome.alreadyMissing,
                            error = if (!outcome.rowRemoved) "row_cleanup_pending" else null,
                        )
                    }

                    is StorageDeleteOutcome.Failed -> StorageDeleteResult(
                        status = "failed",
                        id = entity.id,
                        error = outcome.error,
                    )
                }
            }

            call.respond(
                StorageCleanupResponse(
                    scanned = items.size,
                    deletedBytes = deletedBytes,
                    items = items,
                    truncated = truncated,
                )
            )
        }

        route("/files/{id}") {
            // DELETE /api/storage/files/{id} —— 带保护的逐项删除
            delete {
                val idParam = call.pathParameters["id"] ?: throw BadRequestException("Missing file id")
                val id = idParam.toLongOrNull() ?: throw BadRequestException("Invalid file id")
                when (val resolved = deleter.resolve(id)) {
                    is StorageDeleter.Resolve.NotFound -> throw NotFoundException("File not found")
                    is StorageDeleter.Resolve.Found -> {
                        when (val outcome = deleter.deleteResolved(resolved.entity, resolved.file)) {
                            is StorageDeleteOutcome.Protected -> call.respond(
                                HttpStatusCode.Conflict,
                                StorageDeleteResult(
                                    status = "skipped",
                                    id = id,
                                    reasons = outcome.reasons,
                                )
                            )

                            is StorageDeleteOutcome.Deleted -> call.respond(
                                HttpStatusCode.OK,
                                StorageDeleteResult(
                                    status = "deleted",
                                    id = id,
                                    bytesFreed = outcome.bytesFreed,
                                    alreadyMissing = outcome.alreadyMissing,
                                    error = if (!outcome.rowRemoved) "row_cleanup_pending" else null,
                                )
                            )

                            is StorageDeleteOutcome.Failed -> call.respond(
                                HttpStatusCode.InternalServerError,
                                StorageDeleteResult(
                                    status = "failed",
                                    id = id,
                                    error = outcome.error,
                                )
                            )
                        }
                    }
                }
            }

            // GET /api/storage/files/{id}/preview —— 仅白名单位图图片，nosniff
            get("/preview") {
                val (entity, file) = call.resolveStorageTarget(filesManager)
                if (!file.exists() || !file.isFile) {
                    throw NotFoundException("File not found on disk")
                }
                val contentType = when (entity.mimeType.lowercase()) {
                    "image/png" -> ContentType.Image.PNG
                    "image/jpeg", "image/jpg" -> ContentType.Image.JPEG
                    "image/gif" -> ContentType.Image.GIF
                    "image/webp" -> ContentType("image", "webp")
                    // svg/html 等可执行内容不给预览通道，只允许走 download
                    else -> throw NotFoundException("Preview not available for this file type")
                }
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respond(LocalFileContent(file, contentType))
            }

            // GET /api/storage/files/{id}/download —— 强制 attachment 下载
            get("/download") {
                val (entity, file) = call.resolveStorageTarget(filesManager)
                if (!file.exists() || !file.isFile) {
                    throw NotFoundException("File not found on disk")
                }
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(
                    "Content-Disposition",
                    buildAttachmentDisposition(entity.displayName),
                )
                call.respond(
                    LocalFileContent(file, ContentType.Application.OctetStream)
                )
            }
        }
    }
}

/**
 * path 参数仅接受 managed id → 查表 → canonical 归属校验。
 * id 不存在/非 upload/解析出 upload 目录以外：一律 404（不区分“不存在”与“不允许”，
 * 避免目录探测）。
 */
private suspend fun io.ktor.server.routing.RoutingCall.resolveStorageTarget(
    filesManager: FilesManager,
): Pair<ManagedFileEntity, File> {
    val idParam = pathParameters["id"] ?: throw BadRequestException("Missing file id")
    val id = idParam.toLongOrNull() ?: throw BadRequestException("Invalid file id")
    return filesManager.resolveUploadEntity(id)
        ?: throw NotFoundException("File not found")
}

/** RFC 5987 filename* + ASCII 兜底文件名，杜绝响应头注入。 */
private fun buildAttachmentDisposition(displayName: String): String {
    val fallback = displayName
        .map { ch -> if (ch.code in 32..126 && ch != '"' && ch != '\\') ch else '_' }
        .joinToString("")
        .ifBlank { "download" }
    val encoded = URLEncoder.encode(displayName, Charsets.UTF_8)
        .replace("+", "%20")
    return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
}
