package me.rerere.rikkihub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.db.dao.MemoryDAO
import me.rerere.rikkihub.data.db.entity.MemoryFileEntity
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.model.mergeMemoryFiles

enum class MemoryConflictStrategy {
    SKIP,
    MERGE,
    OVERWRITE,
}

data class MemoryMigrationConflict(
    val path: String,
    val title: String,
    val sourceBytes: Int,
    val targetBytes: Int,
    val mergedBytes: Int,
    val mergeExceedsLimit: Boolean,
)

data class MemoryMigrationPreview(
    val sourceCount: Int,
    val newPaths: List<String>,
    val identicalPaths: List<String>,
    val conflicts: List<MemoryMigrationConflict>,
) {
    val hasActionableChanges: Boolean get() = newPaths.isNotEmpty() || conflicts.isNotEmpty()
}

data class MemoryMigrationResult(
    val copied: Int,
    val merged: Int,
    val overwritten: Int,
    val skipped: Int,
    val failedOversized: List<String> = emptyList(),
)

sealed interface MemoryWriteResult {
    /** [file] 为 null 表示文件已被删除 */
    data class Success(val file: MemoryFile?) : MemoryWriteResult

    /** 版本对不上；[current] 是最新内容，为 null 表示文件不存在 */
    data class Conflict(val current: MemoryFile?) : MemoryWriteResult

    data class Rejected(val reason: String) : MemoryWriteResult
}

/**
 * 记忆库：每个助手（或全局）一组 Markdown 文件。
 *
 * 写操作都带 ifVersion 做乐观锁：传 [NEW_FILE_VERSION] 表示新建，传版本号表示基于该版本修改，
 * 传 null 则不校验（用户在界面里直接编辑）。
 */
class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val NEW_FILE_VERSION = "new"
    }

    // 读取、校验版本、写入必须一起完成，否则两个写入方会互相覆盖
    private val writeLock = Mutex()

    fun getFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryDAO.getFilesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toMemoryFile() } }

    fun getFileCountFlow(assistantId: String): Flow<Int> =
        memoryDAO.getFileCountFlow(assistantId)

    fun getFileCountsByAssistantFlow(): Flow<Map<String, Int>> =
        memoryDAO.getFileCountsByAssistantFlow().map { rows -> rows.associate { it.assistantId to it.count } }

    suspend fun getFiles(assistantId: String): List<MemoryFile> =
        memoryDAO.getFilesOfAssistant(assistantId).map { it.toMemoryFile() }

    suspend fun getFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
    }

    /** 新建或整体覆盖 */
    suspend fun writeFile(
        assistantId: String,
        path: String,
        content: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, allowCreate = true) { Edit.Content(content) }

    /** 把唯一匹配的 [oldStr] 换成 [newStr]，[newStr] 为空即删除 */
    suspend fun replaceInFile(
        assistantId: String,
        path: String,
        oldStr: String,
        newStr: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        if (oldStr.isEmpty()) return@update Edit.Reject("old_str must not be empty.")
        val index = current.indexOf(oldStr)
        when {
            index < 0 -> Edit.Reject("old_str was not found in $path.")
            current.indexOf(oldStr, index + 1) >= 0 ->
                Edit.Reject(
                    "old_str matches more than one place in $path; include more surrounding text to make it unique."
                )

            else -> Edit.Content(current.replaceAt(index, oldStr.length, newStr))
        }
    }

    /** 在文件末尾追加一行 */
    suspend fun appendToFile(
        assistantId: String,
        path: String,
        line: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        Edit.Content(current + separator + line.trimEnd('\n', '\r') + "\n")
    }

    suspend fun deleteFile(
        assistantId: String,
        path: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        if (current == null || (ifVersion != null && ifVersion != current.version)) {
            return MemoryWriteResult.Conflict(current)
        }
        memoryDAO.deleteFile(assistantId, normalized)
        MemoryWriteResult.Success(null)
    }

    suspend fun deleteFilesOfAssistant(assistantId: String) {
        memoryDAO.deleteFilesOfAssistant(assistantId)
    }

    /** 预检从 [fromMemoryId] 复制/合并到 [toMemoryId] 的变更与冲突，不改动任何数据 */
    suspend fun previewMigration(fromMemoryId: String, toMemoryId: String): MemoryMigrationPreview {
        require(fromMemoryId != toMemoryId) { "Source and target memory stores must differ" }
        val sourceFiles = getFiles(fromMemoryId)
        if (sourceFiles.isEmpty()) {
            return MemoryMigrationPreview(0, emptyList(), emptyList(), emptyList())
        }
        val targetByPath = getFiles(toMemoryId).associateBy { it.path }
        val newPaths = mutableListOf<String>()
        val identicalPaths = mutableListOf<String>()
        val conflicts = mutableListOf<MemoryMigrationConflict>()

        sourceFiles.forEach { source ->
            val target = targetByPath[source.path]
            when {
                target == null -> newPaths += source.path
                target.version == source.version -> identicalPaths += source.path
                else -> {
                    val outcome = mergeMemoryFiles(target, source)
                    if (!outcome.changed) {
                        identicalPaths += source.path
                    } else {
                        conflicts += MemoryMigrationConflict(
                            path = source.path,
                            title = target.title,
                            sourceBytes = source.content.toByteArray().size,
                            targetBytes = target.content.toByteArray().size,
                            mergedBytes = outcome.bytes,
                            mergeExceedsLimit = outcome.exceedsLimit,
                        )
                    }
                }
            }
        }
        return MemoryMigrationPreview(
            sourceCount = sourceFiles.size,
            newPaths = newPaths,
            identicalPaths = identicalPaths,
            conflicts = conflicts,
        )
    }

    /**
     * 安全迁移 [fromMemoryId] 中的记忆文件到 [toMemoryId]：
     * - 持有 [writeLock]，避免与后台做梦或主对话写入竞态；
     * - 永不修改或删除源库文件；
     * - 同名冲突严格按 [strategy] 处理，合并超 48KB 的文件拒绝写入并回报 [MemoryMigrationResult.failedOversized]。
     */
    suspend fun migrateFiles(
        fromMemoryId: String,
        toMemoryId: String,
        strategy: MemoryConflictStrategy,
    ): MemoryMigrationResult = writeLock.withLock {
        require(fromMemoryId != toMemoryId) { "Source and target memory stores must differ" }
        val sourceFiles = getFiles(fromMemoryId)
        if (sourceFiles.isEmpty()) {
            return MemoryMigrationResult(copied = 0, merged = 0, overwritten = 0, skipped = 0)
        }
        val targetByPath = getFiles(toMemoryId).associateBy { it.path }
        val now = System.currentTimeMillis()
        val toUpsert = mutableListOf<MemoryFileEntity>()
        var copied = 0
        var merged = 0
        var overwritten = 0
        var skipped = 0
        val failedOversized = mutableListOf<String>()

        sourceFiles.forEach { source ->
            val target = targetByPath[source.path]
            if (target == null) {
                toUpsert += MemoryFileEntity(
                    assistantId = toMemoryId,
                    path = source.path,
                    content = source.content,
                    updatedAt = now,
                )
                copied++
            } else if (target.version == source.version) {
                skipped++
            } else {
                when (strategy) {
                    MemoryConflictStrategy.SKIP -> skipped++
                    MemoryConflictStrategy.OVERWRITE -> {
                        toUpsert += MemoryFileEntity(
                            assistantId = toMemoryId,
                            path = source.path,
                            content = source.content,
                            updatedAt = now,
                        )
                        overwritten++
                    }

                    MemoryConflictStrategy.MERGE -> {
                        val outcome = mergeMemoryFiles(target, source)
                        when {
                            !outcome.changed -> skipped++
                            outcome.exceedsLimit -> failedOversized += source.path
                            else -> {
                                toUpsert += MemoryFileEntity(
                                    assistantId = toMemoryId,
                                    path = source.path,
                                    content = outcome.content,
                                    updatedAt = now,
                                )
                                merged++
                            }
                        }
                    }
                }
            }
        }
        if (toUpsert.isNotEmpty()) {
            memoryDAO.upsertFiles(toUpsert)
        }
        MemoryMigrationResult(
            copied = copied,
            merged = merged,
            overwritten = overwritten,
            skipped = skipped,
            failedOversized = failedOversized,
        )
    }

    private sealed interface Edit {
        data class Content(val content: String) : Edit
        data class Reject(val reason: String) : Edit
    }

    private suspend fun update(
        assistantId: String,
        path: String,
        ifVersion: String?,
        allowCreate: Boolean = false,
        edit: (current: String) -> Edit,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        val versionMatches = when (ifVersion) {
            null -> current != null || allowCreate
            NEW_FILE_VERSION -> current == null && allowCreate
            else -> current?.version == ifVersion
        }
        if (!versionMatches) return MemoryWriteResult.Conflict(current)

        val content = when (val result = edit(current?.content.orEmpty())) {
            is Edit.Reject -> return MemoryWriteResult.Rejected(result.reason)
            is Edit.Content -> result.content
        }
        // 只拦会让文件变大的超限写入：迁移来的旧记忆可能本来就超限，得允许一点点删小
        if (!MemoryFile.fitsLimit(content.toByteArray().size, current?.content?.toByteArray()?.size)) {
            return MemoryWriteResult.Rejected(
                "$normalized would exceed the ${MemoryFile.MAX_BYTES / 1024}KB limit. " +
                    "Merge duplicates and drop outdated details instead of adding more."
            )
        }
        val entity = MemoryFileEntity(
            assistantId = assistantId,
            path = normalized,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.upsertFile(entity)
        MemoryWriteResult.Success(entity.toMemoryFile())
    }

    private fun invalidPath(path: String) = MemoryWriteResult.Rejected(
        "Invalid path \"$path\". Use an absolute path ending in .md, e.g. /topics/food.md."
    )
}

/** 助手读写的记忆库：开启全局记忆后所有助手共用一份 */
val Assistant.memoryId: String
    get() = if (useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else id.toString()

private fun MemoryFileEntity.toMemoryFile() = MemoryFile(path = path, content = content, updatedAt = updatedAt)

// 删掉的是完整一行时连换行一起去掉，不留空行
private fun String.replaceAt(index: Int, length: Int, replacement: String): String {
    var start = index
    var end = index + length
    if (replacement.isEmpty()) {
        val atLineStart = start == 0 || this[start - 1] == '\n'
        val atLineEnd = end == this.length || this[end] == '\n'
        if (atLineStart && atLineEnd) {
            if (end < this.length) {
                end++
            } else if (start > 0) {
                start--
            }
        }
    }
    return substring(0, start) + replacement + substring(end)
}
