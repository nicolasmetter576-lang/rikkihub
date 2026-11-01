package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.web.ApiException
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.workspace.WorkspaceFileEntry
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.coroutines.coroutineContext

// ---- 批量文件操作 (batch-delete / archive) 的请求校验、选中归一化与并发控制 ----
// 路径安全 (越界/根/符号链接/存在性/祖先去重) 由 workspace 模块
// resolveWorkspaceSelection 在 repo 层再次权威校验; 这里只做 Web 层的
// schema/大小/词法归一化, 让坏请求在进仓库前就变成 400/413 而不是 500。

internal const val MAX_BATCH_PATH_COUNT = 200
internal const val MAX_BATCH_PATH_LENGTH = 1024
internal const val MAX_BATCH_TOTAL_PATH_CHARS = 256 * 1024
internal const val MAX_BATCH_BODY_BYTES = 256 * 1024L

internal const val MAX_ARCHIVE_OUTPUT_BYTES = 200L * 1024 * 1024   // 200 MiB ZIP 硬上限 (与前端 MAX_WORKSPACE_ARCHIVE_BYTES 一致)
internal const val MAX_ARCHIVE_ENTRIES = 20_000                     // 文件+目录条目数上限
internal const val MAX_ARCHIVE_INPUT_BYTES = 1L * 1024 * 1024 * 1024 // 源文件总大小上限
internal const val MAX_ARCHIVE_DEPTH = 64                           // 预检递归深度兜底
internal const val MAX_CONCURRENT_ARCHIVES = 2                      // 全局归档并发

internal const val L2S_PREFIX = ".l2s."

/** 请求体积/数量超限 → 413 */
internal class WorkspaceSelectionLimitException(message: String) : RuntimeException(message)

/** ZIP 输出字节超过 [MAX_ARCHIVE_OUTPUT_BYTES]; 临时文件由调用方回收, 不会给出半包 */
internal class WorkspaceArchiveOutputLimitException(message: String) : RuntimeException(message)

/**
 * 有界地读请求体并解析为 JSON 对象.
 * 超出 [MAX_BATCH_BODY_BYTES] → [WorkspaceSelectionLimitException];
 * 解析失败/非对象 → [BadRequestException].
 */
internal suspend fun readWorkspaceSelectionBody(call: ApplicationCall): JsonObject {
    val channel = call.receiveChannel()
    // readRemaining 只取前 limit+1 字节, 超限的 body 不会整体进内存
    val packet = channel.readRemaining(MAX_BATCH_BODY_BYTES + 1)
    val bytes = packet.readByteArray()
    if (bytes.size > MAX_BATCH_BODY_BYTES) {
        throw WorkspaceSelectionLimitException(
            "Request body too large: max ${MAX_BATCH_BODY_BYTES / 1024} KiB"
        )
    }
    val element = try {
        Json.parseToJsonElement(bytes.decodeToString())
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        throw BadRequestException("Request body must be a JSON object")
    }
    return element as? JsonObject
        ?: throw BadRequestException("Request body must be a JSON object")
}

/**
 * 严格 schema: 只允许 `paths` 键, 必须是非空字符串数组.
 * 数量/单条长度/总字符超限 → 413; 结构非法 → 400.
 */
internal fun extractWorkspaceSelectionPaths(body: JsonObject): List<String> {
    body.keys.firstOrNull { it != "paths" }?.let {
        throw BadRequestException("Unknown field: $it")
    }
    val raw = body["paths"] ?: throw BadRequestException("Missing required field: paths")
    val array = raw as? JsonArray
        ?: throw BadRequestException("paths must be an array of strings")
    if (array.isEmpty()) throw BadRequestException("paths must not be empty")
    if (array.size > MAX_BATCH_PATH_COUNT) {
        throw WorkspaceSelectionLimitException(
            "Too many paths: ${array.size}, max $MAX_BATCH_PATH_COUNT"
        )
    }
    var totalChars = 0
    return array.map { element ->
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BadRequestException("paths must be an array of strings")
        if (value.length > MAX_BATCH_PATH_LENGTH) {
            throw WorkspaceSelectionLimitException(
                "Path too long: ${value.length} chars, max $MAX_BATCH_PATH_LENGTH"
            )
        }
        totalChars += value.length
        if (totalChars > MAX_BATCH_TOTAL_PATH_CHARS) {
            throw WorkspaceSelectionLimitException(
                "Total path size too large: max ${MAX_BATCH_TOTAL_PATH_CHARS / 1024} KiB"
            )
        }
        value
    }
}

/**
 * 词法归一化 + 祖先覆盖去重 (与 workspace 模块 resolveWorkspaceSelection 的
 * 去重语义一致, 但在路径校验之前执行):
 * - `\` 视为 `/`, 剥前导/尾随 `/`, 空段与 `.` 折叠, `..` 折叠中间段
 * - 弹出根之上的 `..` (越界) 、NUL 、归一后为空 (存储区根) → BadRequestException
 * - 重复项丢弃; 后代已被先出现的祖先覆盖则折叠; 后出现的祖先挤掉已保留后代
 * 返回顺序 = 归一化后首次出现的顺序.
 */
internal fun normalizeWorkspaceSelectionPaths(paths: List<String>): List<String> {
    val normalized = paths.map { raw -> normalizeWorkspaceRelPath(raw) }
    val kept = mutableListOf<String>()
    for (rel in normalized) {
        if (kept.any { rel.startsWith("$it/") }) continue
        kept.removeAll { it.startsWith("$rel/") }
        if (rel !in kept) kept += rel
    }
    return kept
}

private fun normalizeWorkspaceRelPath(raw: String): String {
    if (raw.contains('\u0000')) throw BadRequestException("Path contains invalid character")
    val segments = raw.replace('\\', '/').split('/')
    val stack = ArrayDeque<String>()
    for (segment in segments) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (stack.isEmpty()) {
                throw BadRequestException("Path escapes workspace storage root: $raw")
            } else {
                stack.removeLast()
            }
            else -> stack.addLast(segment)
        }
    }
    if (stack.isEmpty()) {
        throw BadRequestException("Refusing to select the storage root itself")
    }
    return stack.joinToString("/")
}

/**
 * (workspaceId, area) 粒度的操作锁: 序列化 Web 侧单删 / 批删 / 归档.
 * 只覆盖 Web 路由; 终端、App、shell 命令的并发改动不经过这里,
 * 安全兜底仍在 workspace 模块的 NOFOLLOW/canonical 校验.
 */
internal class WorkspaceFileOperationLocks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(
        workspaceId: String,
        area: String,
        block: suspend () -> T,
    ): T = mutexes.getOrPut("$workspaceId|$area") { Mutex() }.withLock { block() }
}

/** 全局归档并发许可; 获取不到立即 false (上层回 429, 不排队) */
internal class WorkspaceArchivePermits(maxConcurrent: Int = MAX_CONCURRENT_ARCHIVES) {
    private val semaphore = Semaphore(maxConcurrent)
    fun tryAcquire(): Boolean = semaphore.tryAcquire()
    fun release() = semaphore.release()
}

/**
 * 归档临时文件目录. 默认 JVM `java.io.tmpdir`:
 * Android 运行时装进应用 cacheDir, JVM 测试落到系统 /tmp.
 * 如需改为工作区内 tmp 目录 (`WorkspaceManager.tempDir`), 需要
 * WorkspaceRepository 暴露一个 root→File 的访问器 —— 本轮不排它性地改
 * Repository, 由集成方按需注入 (见 A-IMPLEMENTATION.md).
 */
internal fun defaultWorkspaceArchiveTempDir(): File =
    File(System.getProperty("java.io.tmpdir") ?: ".")

/**
 * 把 [export] 产出的 ZIP 完整写入 [tempDir] 下的新临时文件后返回该文件.
 * 任何失败/取消都删掉半成品再重抛, 绝不返回截断 ZIP;
 * 输出经 [WorkspaceBoundedOutputStream] 硬限 [maxOutputBytes].
 * 调用方负责在响应完成后删除返回的文件 (finally 回收).
 */
internal suspend fun writeArchiveToTempFile(
    tempDir: File,
    maxOutputBytes: Long = MAX_ARCHIVE_OUTPUT_BYTES,
    export: suspend (OutputStream) -> Unit,
): File {
    tempDir.mkdirs()
    val tmp = File.createTempFile("workspace-archive-", ".zip", tempDir)
    try {
        WorkspaceBoundedOutputStream(tmp.outputStream().buffered(), maxOutputBytes).use { out ->
            export(out)
        }
        return tmp
    } catch (error: Throwable) {
        tmp.delete()
        throw error
    }
}

/** 输出字节硬上限; 超限抛 [WorkspaceArchiveOutputLimitException] */
internal class WorkspaceBoundedOutputStream(
    private val out: OutputStream,
    private val maxBytes: Long,
) : OutputStream() {
    private var written = 0L

    override fun write(b: Int) {
        add(1)
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        add(len)
        out.write(b, off, len)
    }

    private fun add(count: Int) {
        if (written + count > maxBytes) {
            throw WorkspaceArchiveOutputLimitException(
                "Archive output exceeds size limit: ${maxBytes / (1024 * 1024)} MiB"
            )
        }
        written += count
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}

/**
 * 选中项预检 (归档前, 仍在 (id, area) 操作锁内调用):
 * - 用 repo 的 list/fileSize 逐选中项探测: 文件计 1 项+计大小,
 *   目录用 [listDir] 递归展开 (listDir 自带 area 根内 resolve,
 *   越界/`..`/不存在走 IllegalArgumentException → 上层 400);
 * - 跳过 `.l2s.` 工件 (与 list/glob 一致); 已访词法路径去重 + 深度上限;
 * - 累计 [maxEntries]/[maxInputBytes] 超限 → [WorkspaceSelectionLimitException].
 * 只做计数, 不触碰符号链接语义 —— exporter 遍历时会再次校验并拒绝链接.
 * TOCTOU: 预检后发生的并发改动仍可能让 ZIP 变大, 由输出字节硬上限兜底.
 */
internal suspend fun measureWorkspaceSelection(
    relPaths: List<String>,
    listDir: suspend (String) -> List<WorkspaceFileEntry>,
    fileSize: suspend (String) -> Long,
    maxEntries: Int = MAX_ARCHIVE_ENTRIES,
    maxInputBytes: Long = MAX_ARCHIVE_INPUT_BYTES,
): WorkspaceArchiveEstimate {
    var entries = 0L
    var inputBytes = 0L
    val visited = HashSet<String>()

    fun addEntry(path: String, fileBytes: Long) {
        entries++
        inputBytes += fileBytes
        if (entries > maxEntries) {
            throw WorkspaceSelectionLimitException(
                "Too many entries to archive: max $maxEntries"
            )
        }
        if (inputBytes > maxInputBytes) {
            throw WorkspaceSelectionLimitException(
                "Selection too large to archive: max ${maxInputBytes / (1024 * 1024)} MiB"
            )
        }
    }

    suspend fun walk(rel: String, depth: Int) {
        coroutineContext.ensureActive()
        if (depth > MAX_ARCHIVE_DEPTH) {
            throw WorkspaceSelectionLimitException("Archive selection nesting too deep")
        }
        if (!visited.add(rel)) return
        // 探测条目类型: fileSize 成功 → 普通文件; 否则试 listDir → 目录;
        // 两者都抛 IllegalArgumentException (不存在/越界) → 透传为 400
        val size = try {
            fileSize(rel)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            -1L
        } catch (error: IllegalStateException) {
            -1L
        }
        if (size >= 0) {
            addEntry(rel, size)
            return
        }
        val children = listDir(rel) // 非法/不存在会在这里抛 IllegalArgumentException
        addEntry(rel, 0)
        for (child in children) {
            if (child.name.startsWith(L2S_PREFIX)) continue
            walk(child.path, depth + 1)
        }
    }

    for (rel in relPaths) walk(rel, 0)
    return WorkspaceArchiveEstimate(entries = entries, inputBytes = inputBytes)
}

internal data class WorkspaceArchiveEstimate(
    val entries: Long,
    val inputBytes: Long,
)

/** 批量操作错误 → (HTTP 状态, 消息) 映射; 可单测的纯函数. */
internal data class WorkspaceBatchError(
    val status: HttpStatusCode,
    val message: String,
)

/**
 * 异常映射规则:
 * - CancellationException 永远透传, 不响应
 * - 限额类 (路径/条目/体积/输出上限) → 413, 保留脱敏后的具体提示
 * - ApiException → 原状态码 (400/404...), 消息仍过一遍宿主路径脱敏
 * - IllegalArgumentException (越界/根/不存在/符号链接) → 400, 脱敏后保留提示
 * - IllegalStateException 含 "not found" → 404 (workspace 中途被删)
 * - 其余任何异常 (IO/磁盘满/未知) → 500 通用文案, 绝不把 cause.message
 *   (可能含宿主绝对路径) 回给用户
 */
internal fun workspaceBatchError(
    error: Throwable,
    workspaceRootName: String,
): WorkspaceBatchError = when (error) {
    is CancellationException -> throw error
    is WorkspaceSelectionLimitException -> WorkspaceBatchError(
        HttpStatusCode.PayloadTooLarge,
        sanitizeWorkspaceHostPaths(error.message, workspaceRootName)
            .ifBlank { "Selection too large" },
    )
    is WorkspaceArchiveOutputLimitException -> WorkspaceBatchError(
        HttpStatusCode.PayloadTooLarge,
        sanitizeWorkspaceHostPaths(error.message, workspaceRootName)
            .ifBlank { "Archive too large" },
    )
    is ApiException -> WorkspaceBatchError(
        error.status,
        sanitizeWorkspaceHostPaths(error.message, workspaceRootName)
            .ifBlank { "Invalid request" },
    )
    is IllegalArgumentException -> WorkspaceBatchError(
        HttpStatusCode.BadRequest,
        sanitizeWorkspaceHostPaths(error.message, workspaceRootName)
            .ifBlank { "Invalid selection" },
    )
    is IllegalStateException ->
        if (error.message?.contains("not found", ignoreCase = true) == true) {
            WorkspaceBatchError(HttpStatusCode.NotFound, "Workspace not found")
        } else {
            WorkspaceBatchError(HttpStatusCode.InternalServerError, "Workspace operation failed")
        }
    else -> WorkspaceBatchError(HttpStatusCode.InternalServerError, "Workspace operation failed")
}

/**
 * 错误消息脱敏: 宿主机上落在 .../workspaces/<root>/(files|linux)/ 下的
 * 绝对路径一律替换成 <workspace>/<area>/..., 避免把宿主绝对路径回给前端.
 */
internal fun sanitizeWorkspaceHostPaths(message: String?, workspaceRootName: String): String {
    if (message.isNullOrEmpty() || workspaceRootName.isBlank()) return message.orEmpty()
    val regex = Regex(
        "\\S*?/workspaces/" + Regex.escape(workspaceRootName) + "/(files|linux)(/\\S*)?"
    )
    return regex.replace(message) { match ->
        val area = match.groupValues[1]
        val tail = match.groupValues[2]
        if (tail.isEmpty()) "<workspace>/$area" else "<workspace>/$area$tail"
    }
}
