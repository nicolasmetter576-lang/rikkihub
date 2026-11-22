package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次模型请求的用量流水（重试的每次尝试、多步对话的每一步各一行）。
 *
 * 不加外键：删除会话、助手或提供商后，用量仍计入统计。
 * [totalTokens] 是提供商报告的 total，未报告时为输入 + 输出（与消息上的 usage 同口径）。
 * [accountKey] 只给多账号提供商（Devin / Antigravity / Codex）用，形如 `devin:<账号 id>`，
 * 用来把用量对到对应账号的额度变化上，界面不按账号拆分。
 */
@Entity(
    tableName = "usage_record",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["account_key", "created_at"]),
    ]
)
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("started_at", defaultValue = "0")
    val startedAt: Long = 0,
    @ColumnInfo("usage_complete", defaultValue = "0")
    val usageComplete: Boolean = false,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("provider_id")
    val providerId: String? = null,
    @ColumnInfo("model_id")
    val modelId: String? = null,
    @ColumnInfo("model_name")
    val modelName: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("account_key")
    val accountKey: String? = null,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Long,
    @ColumnInfo("completion_tokens")
    val completionTokens: Long,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Long,
    @ColumnInfo("total_tokens")
    val totalTokens: Long,
)

/**
 * 订阅类提供商的一次额度读数（每次刷新到额度时记一行）。
 *
 * [window] 是额度窗口：Devin 的 `daily` / `weekly`，Codex 的 `primary` / `secondary`，
 * Antigravity 按模型分开，为 `model:<模型 id>`。[remaining] 是剩余比例 0..1。
 * [resetAt] 为窗口的重置时间（毫秒），未知时为 null。
 */
@Entity(
    tableName = "quota_snapshot",
    indices = [Index(value = ["account_key", "window", "created_at"])]
)
data class QuotaSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("account_key")
    val accountKey: String,
    @ColumnInfo("window")
    val window: String,
    @ColumnInfo("remaining")
    val remaining: Double,
    @ColumnInfo("reset_at")
    val resetAt: Long? = null,
)
