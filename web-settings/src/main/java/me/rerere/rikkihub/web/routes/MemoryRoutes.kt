package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.MAX_BACKFILL_CONVERSATIONS
import me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkihub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.model.groupByDirectory
import me.rerere.rikkihub.data.repository.MemoryConflictStrategy
import me.rerere.rikkihub.data.repository.MemoryMigrationConflict
import me.rerere.rikkihub.data.repository.MemoryMigrationPreview
import me.rerere.rikkihub.data.repository.MemoryMigrationResult
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.MemoryWriteResult
import me.rerere.rikkihub.data.repository.memoryId
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import kotlin.uuid.Uuid

internal const val MEMORY_SCOPE_ASSISTANT = "assistant"
internal const val MEMORY_SCOPE_GLOBAL = "global"
internal const val MEMORY_VERSION_CONFLICT = "MEMORY_VERSION_CONFLICT"
internal const val MEMORY_DREAM_RUNNING = "MEMORY_DREAM_RUNNING"
private const val DAY_MS = 24L * 60 * 60 * 1000

@Serializable
data class MemoryFileSummaryDto(
    val path: String,
    val title: String,
    val description: String,
    val version: String,
    val size: Int,
    val updatedAt: Long,
)

@Serializable
data class MemoryFileDto(
    val path: String,
    val title: String,
    val description: String,
    val aliases: List<String>,
    val version: String,
    val updatedAt: Long,
    val content: String,
)

@Serializable
data class MemoryDreamStatusDto(
    /** 助手开着记忆（或全局库已有文件可清理）；关着时不能做梦 */
    val enabled: Boolean,
    /** 助手和做梦设置都开着自动整理 */
    val automatic: Boolean,
    val running: Boolean,
    val pendingTurns: Int,
    val lastFinishedAt: Long? = null,
    val lastError: String? = null,
    val lastChangedPaths: List<String> = emptyList(),
    val lastDeletedPaths: List<String> = emptyList(),
    val canBackfill: Boolean = enabled,
)

@Serializable
data class GlobalMemoryImportableAssistantDto(
    val id: String,
    val name: String,
    val fileCount: Int,
)

@Serializable
data class MemoryFilesResponse(
    val scope: String,
    val maxBytes: Int,
    val files: List<MemoryFileSummaryDto>,
    /** 没接上调度器（比如测试里）时为 null */
    val dream: MemoryDreamStatusDto? = null,
    val privateFileCount: Int? = null,
    val globalFileCount: Int? = null,
    val globalAssistantCount: Int? = null,
    val globalAssistantNames: List<String>? = null,
    val importableAssistants: List<GlobalMemoryImportableAssistantDto>? = null,
)

@Serializable
data class MemoryDreamStartedResponse(val memoryId: String)

@Serializable
data class MemoryFileWriteRequest(
    val path: String? = null,
    val content: String? = null,
    /** "new" 新建；否则是开始编辑时的版本，对不上返回 409 */
    val ifVersion: String? = null,
)

@Serializable
data class MemoryMigrationConflictDto(
    val path: String,
    val title: String,
    val sourceBytes: Int,
    val targetBytes: Int,
    val mergedBytes: Int,
    val mergeExceedsLimit: Boolean,
)

@Serializable
data class MemoryMigrationPreviewDto(
    val sourceCount: Int,
    val newPaths: List<String>,
    val identicalPaths: List<String>,
    val conflicts: List<MemoryMigrationConflictDto>,
)

@Serializable
data class MemoryMigrationRequest(
    val strategy: String = "merge",
    val syncMarks: Boolean = true,
)

@Serializable
data class MemoryMigrationResultDto(
    val copied: Int,
    val merged: Int,
    val overwritten: Int,
    val skipped: Int,
    val failedOversized: List<String> = emptyList(),
)

@Serializable
data class MemoryBackfillCountDto(
    val candidates: Int,
    val maxPerRun: Int,
)

@Serializable
data class MemoryBackfillRequest(
    val days: Int = 30,
)

/**
 * 助手记忆库与独立全局记忆库的文件与迁移路由。
 *
 * 写入一律带版本：新建传 "new"，修改传读到的版本。后台做梦随时可能改文件，不核对版本的话会把它的修改覆盖掉。
 */
fun Route.memoryRoutes(
    settingsStore: SettingsStore,
    memoryRepo: MemoryRepository,
    scheduler: MemoryConsolidationScheduler? = null,
) {
    route("/assistants/{id}/memories") {
        get {
            val settings = settingsStore.settingsFlow.value
            val assistant = memoryAssistantOf(call.parameters["id"], settings.assistants)
            val memoryId = assistant.memoryId
            val files = memoryRepo.getFiles(memoryId).groupByDirectory().flatMap { it.second }
            val privateCount = if (memoryId == MemoryRepository.GLOBAL_MEMORY_ID) {
                memoryRepo.getFiles(assistant.id.toString()).size
            } else {
                files.size
            }
            val globalCount = if (memoryId == MemoryRepository.GLOBAL_MEMORY_ID) {
                files.size
            } else {
                memoryRepo.getFiles(MemoryRepository.GLOBAL_MEMORY_ID).size
            }
            val globalAssistantCount = settings.assistants.count { it.enableMemory && it.useGlobalMemory }
            call.respond(
                MemoryFilesResponse(
                    scope = memoryScopeLabel(memoryId),
                    maxBytes = MemoryFile.MAX_BYTES,
                    files = files.map { it.toSummaryDto() },
                    dream = scheduler?.statusOf(memoryId)?.first()?.toDto(assistant, settings),
                    privateFileCount = privateCount,
                    globalFileCount = globalCount,
                    globalAssistantCount = globalAssistantCount,
                )
            )
        }

        get("/file") {
            val memoryId = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants).memoryId
            val path = requireMemoryPath(call.request.queryParameters["path"])
            val file = memoryRepo.getFile(memoryId, path) ?: throw NotFoundException("Memory file not found")
            call.respond(file.toDto())
        }

        put("/file") {
            val memoryId = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants).memoryId
            val body = call.receive<MemoryFileWriteRequest>()
            val path = requireMemoryPath(body.path)
            val content = body.content ?: throw BadRequestException("content is required")
            val ifVersion = body.ifVersion?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("ifVersion is required (\"${MemoryRepository.NEW_FILE_VERSION}\" for a new file)")
            val result = memoryRepo.writeFile(memoryId, path, content, ifVersion)
            val written = result.orThrow(isNew = ifVersion == MemoryRepository.NEW_FILE_VERSION)
                ?: throw NotFoundException("Memory file not found")
            call.respond(if (ifVersion == MemoryRepository.NEW_FILE_VERSION) HttpStatusCode.Created else HttpStatusCode.OK, written.toDto())
        }

        delete("/file") {
            val memoryId = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants).memoryId
            val path = requireMemoryPath(call.request.queryParameters["path"])
            val ifVersion = call.request.queryParameters["ifVersion"]?.takeIf { it.isNotBlank() }
            memoryRepo.deleteFile(memoryId, path, ifVersion).orThrow(isNew = false)
            call.respond(HttpStatusCode.NoContent)
        }

        // 立即做梦：整理所有待整理的对话，再把整个记忆库清理一遍；结果看列表里的 dream 状态
        post("/dream") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            val assistant = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants)
            if (!assistant.enableMemory) throw BadRequestException("Memory is off for this assistant")
            scheduler.organize(assistant.memoryId)
            call.respond(HttpStatusCode.Accepted, MemoryDreamStartedResponse(memoryId = assistant.memoryId))
        }

        delete("/dream") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            val assistant = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants)
            scheduler.cancel(assistant.memoryId)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/backfill/count") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            val assistant = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants)
            if (!assistant.enableMemory) throw BadRequestException("Memory is off for this assistant")
            val days = parseBackfillDays(call.request.queryParameters["days"])
            val sinceMs = System.currentTimeMillis() - days * DAY_MS
            val count = scheduler.countBackfillCandidates(assistant.memoryId, sinceMs)
            call.respond(MemoryBackfillCountDto(candidates = count, maxPerRun = MAX_BACKFILL_CONVERSATIONS))
        }

        post("/backfill") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            val assistant = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants)
            if (!assistant.enableMemory) throw BadRequestException("Memory is off for this assistant")
            val body = runCatching { call.receive<MemoryBackfillRequest>() }.getOrDefault(MemoryBackfillRequest())
            val days = requireBackfillDays(body.days)
            scheduler.backfill(assistant.memoryId, System.currentTimeMillis() - days * DAY_MS)
            call.respond(HttpStatusCode.Accepted, MemoryDreamStartedResponse(memoryId = assistant.memoryId))
        }

        get("/migrate-to-global/preview") {
            val assistant = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants)
            val preview = memoryRepo.previewMigration(
                fromMemoryId = assistant.id.toString(),
                toMemoryId = MemoryRepository.GLOBAL_MEMORY_ID,
            )
            call.respond(preview.toDto())
        }

        post("/migrate-to-global") {
            val assistant = memoryAssistantOf(call.parameters["id"], settingsStore.settingsFlow.value.assistants)
            val body = runCatching { call.receive<MemoryMigrationRequest>() }.getOrDefault(MemoryMigrationRequest())
            val result = executeMigrationToGlobal(assistant, body, memoryRepo, scheduler)
            call.respond(HttpStatusCode.OK, result.toDto())
        }
    }

    route("/memories/global") {
        get {
            val settings = settingsStore.settingsFlow.value
            val memoryId = MemoryRepository.GLOBAL_MEMORY_ID
            val files = memoryRepo.getFiles(memoryId).groupByDirectory().flatMap { it.second }
            val globalAssistants = settings.assistants.filter { it.enableMemory && it.useGlobalMemory }
            val countsByAssistant = memoryRepo.getFileCountsByAssistantFlow().first()
            val importable = settings.assistants
                .mapNotNull { assistant ->
                    val count = countsByAssistant[assistant.id.toString()] ?: 0
                    if (count > 0) {
                        GlobalMemoryImportableAssistantDto(
                            id = assistant.id.toString(),
                            name = assistant.name,
                            fileCount = count,
                        )
                    } else {
                        null
                    }
                }
                .sortedByDescending { it.fileCount }
            val dreamDto = scheduler?.statusOf(memoryId)?.first()?.let { status ->
                status.toGlobalDto(
                    enabled = globalAssistants.isNotEmpty() || files.isNotEmpty(),
                    canBackfill = globalAssistants.isNotEmpty(),
                    automatic = settings.memoryDream.autoDream && globalAssistants.any { it.autoConsolidateMemory },
                )
            }
            call.respond(
                MemoryFilesResponse(
                    scope = MEMORY_SCOPE_GLOBAL,
                    maxBytes = MemoryFile.MAX_BYTES,
                    files = files.map { it.toSummaryDto() },
                    dream = dreamDto,
                    globalFileCount = files.size,
                    globalAssistantCount = globalAssistants.size,
                    globalAssistantNames = globalAssistants.map { it.name },
                    importableAssistants = importable,
                )
            )
        }

        get("/file") {
            val path = requireMemoryPath(call.request.queryParameters["path"])
            val file = memoryRepo.getFile(MemoryRepository.GLOBAL_MEMORY_ID, path)
                ?: throw NotFoundException("Memory file not found")
            call.respond(file.toDto())
        }

        put("/file") {
            val body = call.receive<MemoryFileWriteRequest>()
            val path = requireMemoryPath(body.path)
            val content = body.content ?: throw BadRequestException("content is required")
            val ifVersion = body.ifVersion?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("ifVersion is required (\"${MemoryRepository.NEW_FILE_VERSION}\" for a new file)")
            val result = memoryRepo.writeFile(MemoryRepository.GLOBAL_MEMORY_ID, path, content, ifVersion)
            val written = result.orThrow(isNew = ifVersion == MemoryRepository.NEW_FILE_VERSION)
                ?: throw NotFoundException("Memory file not found")
            call.respond(if (ifVersion == MemoryRepository.NEW_FILE_VERSION) HttpStatusCode.Created else HttpStatusCode.OK, written.toDto())
        }

        delete("/file") {
            val path = requireMemoryPath(call.request.queryParameters["path"])
            val ifVersion = call.request.queryParameters["ifVersion"]?.takeIf { it.isNotBlank() }
            memoryRepo.deleteFile(MemoryRepository.GLOBAL_MEMORY_ID, path, ifVersion).orThrow(isNew = false)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/dream") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            val settings = settingsStore.settingsFlow.value
            val hasAssistants = settings.assistants.any { it.enableMemory && it.useGlobalMemory }
            val hasFiles = memoryRepo.getFiles(MemoryRepository.GLOBAL_MEMORY_ID).isNotEmpty()
            if (!hasAssistants && !hasFiles) {
                throw BadRequestException("Global memory has no files or active assistants to organize")
            }
            scheduler.organize(MemoryRepository.GLOBAL_MEMORY_ID)
            call.respond(HttpStatusCode.Accepted, MemoryDreamStartedResponse(memoryId = MemoryRepository.GLOBAL_MEMORY_ID))
        }

        delete("/dream") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            scheduler.cancel(MemoryRepository.GLOBAL_MEMORY_ID)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/backfill/count") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            val days = parseBackfillDays(call.request.queryParameters["days"])
            val count = scheduler.countBackfillCandidates(
                MemoryRepository.GLOBAL_MEMORY_ID,
                System.currentTimeMillis() - days * DAY_MS,
            )
            call.respond(MemoryBackfillCountDto(candidates = count, maxPerRun = MAX_BACKFILL_CONVERSATIONS))
        }

        post("/backfill") {
            val scheduler = scheduler ?: throw NotFoundException("Dreaming is not available")
            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.enableMemory && it.useGlobalMemory }) {
                throw BadRequestException("No assistant has global memory enabled")
            }
            val body = runCatching { call.receive<MemoryBackfillRequest>() }.getOrDefault(MemoryBackfillRequest())
            val days = requireBackfillDays(body.days)
            scheduler.backfill(MemoryRepository.GLOBAL_MEMORY_ID, System.currentTimeMillis() - days * DAY_MS)
            call.respond(HttpStatusCode.Accepted, MemoryDreamStartedResponse(memoryId = MemoryRepository.GLOBAL_MEMORY_ID))
        }

        get("/import/{assistantId}/preview") {
            val assistant = memoryAssistantOf(call.parameters["assistantId"], settingsStore.settingsFlow.value.assistants)
            val preview = memoryRepo.previewMigration(
                fromMemoryId = assistant.id.toString(),
                toMemoryId = MemoryRepository.GLOBAL_MEMORY_ID,
            )
            call.respond(preview.toDto())
        }

        post("/import/{assistantId}") {
            val assistant = memoryAssistantOf(call.parameters["assistantId"], settingsStore.settingsFlow.value.assistants)
            val body = runCatching { call.receive<MemoryMigrationRequest>() }.getOrDefault(MemoryMigrationRequest())
            val result = executeMigrationToGlobal(assistant, body, memoryRepo, scheduler)
            call.respond(HttpStatusCode.OK, result.toDto())
        }
    }
}

internal suspend fun executeMigrationToGlobal(
    assistant: Assistant,
    request: MemoryMigrationRequest,
    memoryRepo: MemoryRepository,
    scheduler: MemoryConsolidationScheduler?,
): MemoryMigrationResult {
    if (scheduler?.isRunning(MemoryRepository.GLOBAL_MEMORY_ID) == true) {
        throw CodedApiException(
            HttpStatusCode.Conflict,
            MEMORY_DREAM_RUNNING,
            "Global memory is currently dreaming; wait for it to finish or stop it before migrating",
        )
    }
    val strategy = parseConflictStrategy(request.strategy)
    val result = memoryRepo.migrateFiles(
        fromMemoryId = assistant.id.toString(),
        toMemoryId = MemoryRepository.GLOBAL_MEMORY_ID,
        strategy = strategy,
    )
    if (request.syncMarks) {
        scheduler?.syncMarksToGlobal(assistant.id)
    }
    return result
}

internal fun parseConflictStrategy(raw: String?): MemoryConflictStrategy = when (raw?.trim()?.lowercase()) {
    null, "", "merge" -> MemoryConflictStrategy.MERGE
    "skip" -> MemoryConflictStrategy.SKIP
    "overwrite" -> MemoryConflictStrategy.OVERWRITE
    else -> throw BadRequestException("Invalid conflict strategy \"$raw\". Use merge, skip, or overwrite.")
}

internal fun parseBackfillDays(raw: String?): Int {
    if (raw.isNullOrBlank()) return 30
    val days = raw.toIntOrNull() ?: throw BadRequestException("Invalid days parameter")
    return requireBackfillDays(days)
}

internal fun requireBackfillDays(days: Int): Int {
    if (days !in 1..365) throw BadRequestException("days must be between 1 and 365")
    return days
}

internal fun memoryScopeLabel(memoryId: String): String =
    if (memoryId == MemoryRepository.GLOBAL_MEMORY_ID) MEMORY_SCOPE_GLOBAL else MEMORY_SCOPE_ASSISTANT

internal fun memoryAssistantOf(rawId: String?, assistants: List<Assistant>): Assistant {
    val id = runCatching { Uuid.parse(rawId.orEmpty()) }.getOrNull()
        ?: throw BadRequestException("Invalid assistant id")
    return assistants.firstOrNull { it.id == id } ?: throw NotFoundException("Assistant not found")
}

internal fun requireMemoryPath(raw: String?): String =
    raw?.let(MemoryFile::normalizePath)
        ?: throw BadRequestException("Invalid path. Use an absolute path ending in .md, e.g. /topics/food.md")

/** 成功时返回写入后的文件（删除时为 null）；版本冲突 409，路径不存在 404，内容不合法 400 */
internal fun MemoryWriteResult.orThrow(isNew: Boolean): MemoryFile? = when (this) {
    is MemoryWriteResult.Success -> file
    is MemoryWriteResult.Rejected -> throw BadRequestException(reason)
    is MemoryWriteResult.Conflict -> when {
        isNew && current != null -> throw CodedApiException(
            HttpStatusCode.Conflict, MEMORY_VERSION_CONFLICT, "A memory already exists at this path"
        )
        current == null -> throw NotFoundException("Memory file not found")
        else -> throw CodedApiException(
            HttpStatusCode.Conflict, MEMORY_VERSION_CONFLICT, "The memory changed since it was loaded; reload and retry"
        )
    }
}

private fun MemoryFile.toSummaryDto() = MemoryFileSummaryDto(
    path = path,
    title = title,
    description = description,
    version = version,
    size = content.toByteArray().size,
    updatedAt = updatedAt,
)

private fun MemoryFile.toDto() = MemoryFileDto(
    path = path,
    title = title,
    description = description,
    aliases = aliases,
    version = version,
    updatedAt = updatedAt,
    content = content,
)

private fun MemoryMigrationPreview.toDto() = MemoryMigrationPreviewDto(
    sourceCount = sourceCount,
    newPaths = newPaths,
    identicalPaths = identicalPaths,
    conflicts = conflicts.map { it.toDto() },
)

private fun MemoryMigrationConflict.toDto() = MemoryMigrationConflictDto(
    path = path,
    title = title,
    sourceBytes = sourceBytes,
    targetBytes = targetBytes,
    mergedBytes = mergedBytes,
    mergeExceedsLimit = mergeExceedsLimit,
)

private fun MemoryMigrationResult.toDto() = MemoryMigrationResultDto(
    copied = copied,
    merged = merged,
    overwritten = overwritten,
    skipped = skipped,
    failedOversized = failedOversized,
)

internal fun MemoryConsolidationStatus.toDto(assistant: Assistant, settings: Settings) = MemoryDreamStatusDto(
    enabled = assistant.enableMemory,
    automatic = assistant.autoConsolidateMemory && settings.memoryDream.autoDream,
    running = running,
    pendingTurns = pendingTurns,
    lastFinishedAt = lastRun?.finishedAt,
    lastError = lastRun?.error,
    lastChangedPaths = lastRun?.changedPaths.orEmpty(),
    lastDeletedPaths = lastRun?.deletedPaths.orEmpty(),
    canBackfill = assistant.enableMemory,
)

internal fun MemoryConsolidationStatus.toGlobalDto(
    enabled: Boolean,
    canBackfill: Boolean,
    automatic: Boolean,
) = MemoryDreamStatusDto(
    enabled = enabled,
    automatic = automatic,
    running = running,
    pendingTurns = pendingTurns,
    lastFinishedAt = lastRun?.finishedAt,
    lastError = lastRun?.error,
    lastChangedPaths = lastRun?.changedPaths.orEmpty(),
    lastDeletedPaths = lastRun?.deletedPaths.orEmpty(),
    canBackfill = canBackfill,
)
