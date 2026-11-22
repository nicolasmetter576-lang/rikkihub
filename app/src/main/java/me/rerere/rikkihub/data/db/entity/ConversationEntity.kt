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
     * 酒馆会话状态（JSON 对象）：聊天变量、世界书计时状态、`{{pick}}` 重掷种子等。
     * 按酒馆的存法由 `:tavern` 读写，这里只当一段不透明 JSON；未知键原样保留。
     */
    @ColumnInfo("tavern_metadata", defaultValue = "{}")
    val tavernMetadata: String = "{}",
    /** 这个会话绑定的角色（`characters.id`），空串 = 没有角色。角色被删后这里的 id 失效，读取时按没有角色处理。 */
    @ColumnInfo("character_id", defaultValue = "")
    val characterId: String = "",
    /** 工作区 CLI 会话（`ConversationCliSession` 的 JSON），空串 = 普通对话。单列写：`updateCliSession`。 */
    @ColumnInfo("cli_session", defaultValue = "")
    val cliSession: String = "",
    /** 会话自己的压缩设置（`ConversationCompressionOverride` 的 JSON），空串 = 全部跟随全局。 */
    @ColumnInfo("compression", defaultValue = "")
    val compression: String = "",
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
