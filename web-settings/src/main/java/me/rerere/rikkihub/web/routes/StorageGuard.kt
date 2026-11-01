package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.db.entity.ManagedFileEntity
import me.rerere.rikkihub.data.files.AttachmentLeaseRegistry
import me.rerere.rikkihub.data.files.LeaseDeleteResult
import me.rerere.rikkihub.data.files.extractUploadRelativePath
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.data.model.localFileUrls

/**
 * 存储清理的「保护原因」。wire 值见 [wireName]，前端按该值本地化展示。
 */
enum class StorageProtectionReason(val wireName: String) {
    /** message_node 表中命中（全分支 + 工具嵌套，DB 层）。 */
    REFERENCED_DB("referenced_db"),

    /** 活跃 session 内存态命中（全分支 + 消息队列 + submittingMessage）。 */
    REFERENCED_SESSION("referenced_session"),

    /** Settings 引用命中（助手头像/背景/预设消息/用户头像）。 */
    REFERENCED_SETTINGS("referenced_settings"),

    /** 存在未过期草稿租约（未发送附件仍在输入框/心跳窗口内）。 */
    LEASED("leased"),

    /** 创建不足 24h 的新文件兜底（进程重启后租约丢失的最后防线）。 */
    NEW_FILE("new_file"),

    /** 任一引用查询抛异常——fail-closed 按受保护处理，绝不当无引用。 */
    CHECK_FAILED("check_failed"),

    /** 处于持久附件保留期（被会话/头像等释放后的有界保留窗口内）。 */
    RETAINED("retained"),
}

/**
 * 一次保护判定的输入事实（路由层负责采集并传入）。
 */
data class StorageProtectionFacts(
    /** 形如 upload/<name>。 */
    val relativePath: String,
    /** `file.toUri().toString()` 产生的规范 URL，用于与 DB / session 原始字符串比对。 */
    val canonicalFileUrl: String,
    val createdAt: Long,
    /** 未过期租约到期时刻；无租约为 null（leaseExpiry 读前已做过期裁剪）。 */
    val leaseExpiresAt: Long?,
    val nowMillis: Long,
)

/**
 * 保护判定器：引用源全部通过函数注入，保证纯 Kotlin 可测且与生产共用同一份逻辑。
 * 任一引用源抛异常 → CHECK_FAILED（fail-closed）。DB 命中与否之外，session/settings
 * 走归一化 key 集合比对（见 [normalizeFileRefKeys]）。
 */
class StorageProtectionEvaluator(
    /** 输入 canonical file:// URL 与 upload/<name> 相对路径；实现方负责多形态 needle。 */
    private val dbHasReference: suspend (canonicalFileUrl: String, relativePath: String) -> Boolean,
    /** 活跃 session + 队列 + submitting 的 file:// URL 快照。 */
    private val sessionRefUrls: () -> Set<String>,
    /** Settings 中实际文件引用（avatar/background/preset/userAvatar…）原始 URL 集合。 */
    private val settingsRefUrls: () -> Set<String>,
    /** Durable retention window (attachment_retention); true while unexpired. */
    private val isRetained: suspend (relativePath: String) -> Boolean = { false },
) {
    suspend fun evaluateProtection(facts: StorageProtectionFacts): Set<StorageProtectionReason> {
        val reasons = mutableSetOf<StorageProtectionReason>()

        val retained = try {
            isRetained(facts.relativePath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reasons += StorageProtectionReason.CHECK_FAILED
            false
        }
        if (retained) reasons += StorageProtectionReason.RETAINED

        // DB 层（全库 message_node，覆盖所有会话所有分支；多形态 needle 由实现方决定）
        val dbHit = try {
            dbHasReference(facts.canonicalFileUrl, facts.relativePath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reasons += StorageProtectionReason.CHECK_FAILED
            null
        }
        if (dbHit == true) reasons += StorageProtectionReason.REFERENCED_DB

        reasons += evaluateInMemory(facts)
        return reasons
    }

    /**
     * 非挂起的内存层复查：session 快照 + settings + 租约 + 24h 新文件。
     * 可以在 [AttachmentLeaseRegistry.withLeaseLock] 临界区内调用（无 suspend），
     * 用于删盘前最后一刻的复查，把「快照读取」与「删盘」收进同一把锁。
     * DB 层查询是 suspend，必须留在锁外（见 [evaluateProtection]）。
     */
    fun evaluateInMemory(facts: StorageProtectionFacts): Set<StorageProtectionReason> {
        val reasons = mutableSetOf<StorageProtectionReason>()

        val sessionHit = try {
            normalizeFileRefKeys(sessionRefUrls()).let { keys ->
                facts.relativePath in keys || facts.canonicalFileUrl in keys
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reasons += StorageProtectionReason.CHECK_FAILED
            null
        }
        if (sessionHit == true) reasons += StorageProtectionReason.REFERENCED_SESSION

        val settingsHit = try {
            normalizeFileRefKeys(settingsRefUrls()).let { keys ->
                facts.relativePath in keys || facts.canonicalFileUrl in keys
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reasons += StorageProtectionReason.CHECK_FAILED
            null
        }
        if (settingsHit == true) reasons += StorageProtectionReason.REFERENCED_SETTINGS

        if (facts.leaseExpiresAt != null && facts.leaseExpiresAt > facts.nowMillis) {
            reasons += StorageProtectionReason.LEASED
        }

        if (facts.nowMillis - facts.createdAt < AttachmentLeaseRegistry.NEW_FILE_GUARD_MILLIS) {
            reasons += StorageProtectionReason.NEW_FILE
        }

        return reasons
    }
}

/**
 * 把一组引用 URL/路径归一化为可比对 key 集合：
 * - 能解析出 `upload/<name>` 的（file: URL、绝对路径、/api/files/path/…、裸相对路径）
 *   一律归一为相对路径 key；
 * - 无法归一的保留原字符串 key（即使内容一样形态不同也不会误命中）。
 * 逐条解析、不整串正则——这是比「整包 instr」更严格的等价物，
 * 因为 session/settings 侧是内存对象而不是 DB 文本。
 */
fun normalizeFileRefKeys(urls: Collection<String>): Set<String> = buildSet {
    urls.forEach { raw ->
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return@forEach
        add(trimmed)
        extractUploadRelativePath(trimmed)?.let { add(it) }
    }
}

/**
 * Settings 里实际可能持有文件引用的字段（保守覆盖）：
 * - 每个助手的 `avatar`（Avatar.Image）与 `background`；
 * - 每个助手 `presetMessages` 的全部本地附件 part（含工具嵌套）；
 * - `displaySetting.userAvatar`。
 * 其余字段（lorebook/modeInjection/quickMessage 等）为纯文本，不携带附件。
 */
fun collectSettingsFileRefs(settings: Settings): Set<String> = buildSet {
    settings.assistants.forEach { assistant ->
        collectAssistantFileRefs(assistant, this)
    }
    (settings.displaySetting.userAvatar as? Avatar.Image)?.url
        ?.takeIf(::isLocalFileRef)
        ?.let { add(it) }
}

private fun collectAssistantFileRefs(assistant: Assistant, out: MutableSet<String>) {
    // 头像/背景可以是网络 URL——只有本地文件引用才影响存储清理判定。
    (assistant.avatar as? Avatar.Image)?.url?.takeIf(::isLocalFileRef)?.let { out.add(it) }
    assistant.background?.takeIf(::isLocalFileRef)?.let { out.add(it) }
    assistant.presetMessages.forEach { message ->
        out.addAll(message.parts.localFileUrls())
    }
}

/** 本地文件引用判定：file: scheme、绝对路径、upload/ 相对路径。 */
internal fun isLocalFileRef(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("file:") ||
        trimmed.startsWith("${me.rerere.rikkihub.data.files.FileFolders.UPLOAD}/") ||
        trimmed.startsWith("/api/files/path/") ||
        (trimmed.startsWith("/") && !trimmed.startsWith("//"))
}

internal const val STORAGE_FILES_DEFAULT_LIMIT = 50
internal const val STORAGE_FILES_MAX_LIMIT = 100
internal const val STORAGE_MAX_LEASE_URLS = 200
internal const val STORAGE_CLEANUP_MAX_IDS = 500
internal const val STORAGE_CLEANUP_MAX_CANDIDATES = 2000

/** cleanup 的 olderThanDays 白名单：只允许固定档位，拒绝任意天数。 */
internal val STORAGE_CLEANUP_ALLOWED_DAYS = setOf(7, 14, 30)

/** 列表分页参数校验（负数截到 0，limit 截到上限；纯函数可测）。 */
internal fun sanitizeStoragePageParams(offset: Int?, limit: Int?): Pair<Int, Int> {
    val safeOffset = (offset ?: 0).coerceAtLeast(0)
    val safeLimit = (limit ?: STORAGE_FILES_DEFAULT_LIMIT).coerceIn(1, STORAGE_FILES_MAX_LIMIT)
    return safeOffset to safeLimit
}

/**
 * 列表筛选：名称/mime 模糊匹配 + olderThanDays 过滤。query 大小写不敏感。
 */
internal fun filterStorageFiles(
    entities: List<ManagedFileEntity>,
    query: String?,
    olderThanDays: Int?,
    nowMillis: Long,
): List<ManagedFileEntity> {
    val needle = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val cutoff = olderThanDays?.takeIf { it >= 0 }?.let { nowMillis - it * 24L * 60 * 60 * 1000 }
    return entities.filter { entity ->
        (needle == null ||
            entity.displayName.lowercase().contains(needle) ||
            entity.mimeType.lowercase().contains(needle)) &&
            (cutoff == null || entity.createdAt < cutoff)
    }
}

/** 单项删除的出口（路由层翻译成 HTTP 语义）。 */
internal sealed interface StorageDeleteOutcome {
    /** 受保护未删。 */
    data class Protected(val reasons: List<String>) : StorageDeleteOutcome

    /** 盘上已删（或本就不在盘上）；DB 行清理结果看 [rowRemoved]。 */
    data class Deleted(val bytesFreed: Long, val alreadyMissing: Boolean, val rowRemoved: Boolean) : StorageDeleteOutcome

    /** 文件系统/内部错误，DB 行保留。 */
    data class Failed(val error: String) : StorageDeleteOutcome
}

/**
 * 单项删除管线：与生产路由共用同一套判定。
 *
 * 顺序（每项独立执行）：
 * 1. `resolveUpload(id)`：managed 行 + canonical 归属校验（锁外，可 suspend）。
 * 2. `evaluator.evaluateProtection`（suspend，锁外）：DB + session + settings + lease + 24h。
 * 3. `registry.withLeaseLock` 临界区：重新跑 `evaluateInMemory`（session/settings/lease/24h
 *    最后一刻复查，把快照读取与删盘收进同一把锁），然后 `guardedDeleteIfUnleased`
 *    锁内复查租约并删盘。
 * 4. 锁外 `deleteRow(id)`：盘上已删成功才清登记行；行删除失败保留 deleted 状态并记 error
 *    （孤儿行由 syncFolder 兜底，不能让“文件已没但行删不掉”回滚成“未删”）。
 *
 * 绝不在 Java 锁内执行 suspend/DB；DB 引用检查永远在第 2 步（锁外）。
 */
internal class StorageDeleter(
    private val evaluator: StorageProtectionEvaluator,
    private val registry: AttachmentLeaseRegistry,
    private val resolveUpload: suspend (Long) -> Pair<ManagedFileEntity, java.io.File>?,
    private val deleteRow: suspend (Long) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    sealed interface Resolve {
        data class Found(val entity: ManagedFileEntity, val file: java.io.File) : Resolve
        /** id 不存在 / 非 upload / canonical 越界 —— 路由层报 404。 */
        data object NotFound : Resolve
    }

    suspend fun resolve(id: Long): Resolve {
        val pair = try {
            resolveUpload(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return Resolve.NotFound
        return Resolve.Found(pair.first, pair.second)
    }

    /**
     * 对已解析实体执行删除。返回 null 表示“解析后再次校验 canonical 失败”（极端异常）。
     */
    suspend fun deleteResolved(entity: ManagedFileEntity, file: java.io.File): StorageDeleteOutcome {
        val now = nowMillis()
        val facts = StorageProtectionFacts(
            relativePath = entity.relativePath,
            canonicalFileUrl = "file://${file.absolutePath}",
            createdAt = entity.createdAt,
            leaseExpiresAt = registry.leaseExpiry(entity.relativePath),
            nowMillis = now,
        )

        // 1) 锁外完整评估（含 suspend DB 查询）
        val outerReasons = evaluator.evaluateProtection(facts)
        if (outerReasons.isNotEmpty()) {
            return StorageDeleteOutcome.Protected(outerReasons.sortedBy { it.ordinal }.map { it.wireName })
        }

        // 2) 锁内：最后一次 in-memory 复查 + 租约复查 + 删盘（同一临界区）
        var innerProtected: List<String>? = null
        val leaseOutcome = registry.withLeaseLock {
            val recheck = evaluator.evaluateInMemory(
                facts.copy(leaseExpiresAt = registry.leaseExpiry(entity.relativePath))
            )
            if (recheck.isNotEmpty()) {
                innerProtected = recheck.sortedBy { it.ordinal }.map { it.wireName }
                null
            } else {
                registry.guardedDeleteIfUnleased(entity.relativePath, file)
            }
        }

        innerProtected?.let { return StorageDeleteOutcome.Protected(it) }

        return when (val outcome = leaseOutcome) {
            is LeaseDeleteResult.Leased ->
                StorageDeleteOutcome.Protected(listOf(StorageProtectionReason.LEASED.wireName))

            is LeaseDeleteResult.Deleted -> {
                // 盘上已删但行清理遇到协程取消：用 NonCancellable 完成登记行清理，
                // 不留「文件没了但行还在且批量中断」的中间态；清理后立刻重抛取消。
                val rowRemoved = try {
                    withContext(NonCancellable) { deleteRow(entity.id) }
                } catch (e: CancellationException) {
                    false
                } catch (_: Exception) {
                    false
                }
                currentCoroutineContext().ensureActive()
                StorageDeleteOutcome.Deleted(
                    bytesFreed = outcome.bytesFreed,
                    alreadyMissing = outcome.alreadyMissing,
                    rowRemoved = rowRemoved,
                )
            }

            is LeaseDeleteResult.DeleteFailed ->
                StorageDeleteOutcome.Failed("disk_delete_failed")

            null -> StorageDeleteOutcome.Failed("internal_no_outcome")
        }
    }
}
