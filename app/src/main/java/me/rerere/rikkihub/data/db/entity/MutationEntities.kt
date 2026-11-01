package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 操作 receipt：每个参与存储的最小非私有参与记录在 Room 域的落点。
 *
 * 与业务写**同一事务**写入：receipt 与实体/版本原子可见，不存在"业务已写、
 * receipt 未落"的半成品。字段刻意最小：operationId/阶段/对象键/token JSON/错误码，
 * 不含 transcript、批准材料或私有 schema。
 */
@Entity(
    tableName = "operation_receipts",
    indices = [Index("domain"), Index("created_at")],
)
data class OperationReceiptEntity(
    @PrimaryKey
    @ColumnInfo("operation_id")
    val operationId: String,
    @ColumnInfo("domain")
    val domain: String,
    @ColumnInfo("object_key")
    val objectKey: String,
    @ColumnInfo("phase")
    val phase: String,
    @ColumnInfo("before_token")
    val beforeToken: String = "",
    @ColumnInfo("after_token")
    val afterToken: String = "",
    @ColumnInfo("error_code")
    val errorCode: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

/**
 * 持久回收租约：会话删除/消息移除后，附件先进入有界保留，
 * 到期且复查无任何引用时才真正删盘——**不做差集即删**。
 *
 * 存活条件与 GC 共用一个协议：保留期内任何引用（消息树/Settings/队列/提交中/
 * 草稿租约）都阻止删除；到期后由 sweeper 复查引用再删盘。
 */
@Entity(
    tableName = "attachment_retention",
    indices = [Index("conversation_id"), Index("expires_at")],
)
data class AttachmentRetentionEntity(
    /** 归一化相对路径，如 `upload/<uuid>.png`（FilesManager.getRelativePathInFilesDir 口径）。 */
    @PrimaryKey
    @ColumnInfo("relative_path")
    val relativePath: String,
    /** 产生本保留记录的会话（诊断/恢复分组；不是可达性判定的唯一来源）。 */
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** 原始 file:// URL（恢复展示用；可达性判定以 relativePath 为准）。 */
    @ColumnInfo("source_url")
    val sourceUrl: String,
    @ColumnInfo("reason")
    val reason: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("expires_at")
    val expiresAt: Long,
)
