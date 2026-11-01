package me.rerere.rikkihub.web.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 存储清理页 DTO。序列化字段名即 Web 契约（STORAGE-CONTRACT.md），勿随意改名。
 */

@Serializable
data class StorageFileDto(
    /** managed_files 主键。删除/下载一律走 id，不接受路径。 */
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    /** 盘上文件是否仍存在（不存在时仅 DB 行残留，仍可被清理）。 */
    val existsOnDisk: Boolean,
    /** 任一保护原因非空即 true。序列化字段名固定为 "protected"。 */
    @SerialName("protected")
    val isProtected: Boolean,
    /** 保护原因 wire 值：referenced_db|referenced_session|referenced_settings|leased|new_file|check_failed */
    val protectionReasons: List<String> = emptyList(),
    /** 活跃租约到期时刻（epoch millis），无租约为 null。 */
    val leaseExpiresAt: Long? = null,
)

@Serializable
data class StorageFileListResponse(
    /** 当前页条目（offset/limit 分页，limit clamp 1..100）。 */
    val files: List<StorageFileDto>,
    /** 满足筛选条件（query/olderThanDays）的总条数。 */
    val total: Int,
    /** 满足筛选条件的总字节数。 */
    val totalSizeBytes: Long,
    /** upload 目录全部登记条数（未筛选）。 */
    val overallTotal: Int,
    /** upload 目录全部登记总字节数（未筛选）。 */
    val overallSizeBytes: Long,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

@Serializable
data class StorageLeaseRenewRequest(
    /** 草稿附件引用：managed id（纯数字）/ file: URL / upload/<name> / /api/files/path/upload/<name>。上限见契约。 */
    val urls: List<String> = emptyList(),
)

@Serializable
data class StorageLeaseRenewResponse(
    /** 成功续租的 managed id 数。 */
    val renewed: Int,
    /** 解析失败/非 upload/文件已不存在的原始 token。 */
    val rejected: List<String>,
    /** 单次续租有效期（毫秒）。 */
    val leaseTtlMillis: Long,
)

/** 单项删除结果（DELETE /files/{id} 与 cleanup items 共用）。 */
@Serializable
data class StorageDeleteResult(
    /** deleted | skipped | failed */
    val status: String,
    val id: Long? = null,
    val reasons: List<String> = emptyList(),
    val bytesFreed: Long = 0,
    /** 盘上文件是否本就缺失（仅删 DB 行也算 deleted）。 */
    val alreadyMissing: Boolean = false,
    /** 错误描述（status=failed 时填）。 */
    val error: String? = null,
)

@Serializable
data class StorageCleanupRequest(
    /** 指定删除的 managed id 列表（可与其他条件并存）。 */
    val ids: List<Long> = emptyList(),
    /** 清理早于该天数的文件；>=1。与 ids 至少给其一。 */
    val olderThanDays: Int? = null,
)

@Serializable
data class StorageCleanupResponse(
    /** 实际扫描（评估）的候选数。 */
    val scanned: Int,
    val deletedBytes: Long,
    val items: List<StorageDeleteResult>,
    /** 候选超过单次上限（2000）被截断。 */
    val truncated: Boolean = false,
)
