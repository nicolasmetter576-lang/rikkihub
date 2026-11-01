package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index("assistant_id"),
        Index("folder_id"),
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("workspace_cwd_scoped", defaultValue = "1")
    val workspaceCwdScoped: Boolean = true,
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    /**
     * 乐观并发版本列。所有业务 UPDATE 带 revision=revision+1；
     * 整行 CAS 写用 `WHERE revision=:expected` 断言。不暴露给模型。
     */
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long = 0,
    /**
     * 删除后同 ID 恢复/重建 +1（ABA 防线）；软删除 tombstone 时同步 +1。
     */
    @ColumnInfo("generation", defaultValue = "0")
    val generation: Long = 0,
    /**
     * 删除状态：null=活跃；非 null=软删除 tombstone。
     * 所有普通列表/分页/search/count 查询必须过滤 `deleted_at IS NULL`。
     */
    @ColumnInfo("deleted_at")
    val deletedAt: Long? = null,
)
