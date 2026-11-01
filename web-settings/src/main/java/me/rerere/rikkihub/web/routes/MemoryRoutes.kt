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
import me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkihub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.model.groupByDirectory
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
    /** 助手开着记忆；关着时不能做梦 */
    val enabled: Boolean,
    /** 助手和做梦设置都开着自动整理 */
    val automatic: Boolean,
    val running: Boolean,
    val pendingTurns: Int,
    val lastFinishedAt: Long? = null,
    val lastError: String? = null,
    val lastChangedPaths: List<String> = emptyList(),
    val lastDeletedPaths: List<String> = emptyList(),
)

@Serializable
data class MemoryFilesResponse(
    val scope: String,
    val maxBytes: Int,
    val files: List<MemoryFileSummaryDto>,
    /** 没接上调度器（比如测试里）时为 null */
    val dream: MemoryDreamStatusDto? = null,
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

/**
 * 助手记忆库的文件。范围与 App 相同：已保存的 `useGlobalMemory` 开着用全局记忆库，否则用该助手自己的；
 * 路径都在这个范围内解析，借一个助手的地址只能碰到它自己的记忆库。
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
            call.respond(
                MemoryFilesResponse(
                    scope = memoryScopeLabel(memoryId),
                    maxBytes = MemoryFile.MAX_BYTES,
                    files = files.map { it.toSummaryDto() },
                    dream = scheduler?.statusOf(memoryId)?.first()?.toDto(assistant, settings),
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
    }
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

internal fun MemoryConsolidationStatus.toDto(assistant: Assistant, settings: Settings) = MemoryDreamStatusDto(
    enabled = assistant.enableMemory,
    automatic = assistant.autoConsolidateMemory && settings.memoryDream.autoDream,
    running = running,
    pendingTurns = pendingTurns,
    lastFinishedAt = lastRun?.finishedAt,
    lastError = lastRun?.error,
    lastChangedPaths = lastRun?.changedPaths.orEmpty(),
    lastDeletedPaths = lastRun?.deletedPaths.orEmpty(),
)
