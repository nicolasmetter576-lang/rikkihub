package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * 一个对话在目标记忆库 [memoryId] 下的记忆整理进度：[consolidatedAt] 及之前的消息已整理进该记忆库。
 *
 * 单独成表而不是放在 ConversationEntity 上：对话每次保存都会整行重写，会把进度冲掉。
 * 复合主键以 conversation_id 为前导列，继续覆盖外键级联删除。
 */
@Entity(
    tableName = "memory_consolidation",
    primaryKeys = ["conversation_id", "memory_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
)
data class MemoryConsolidationEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("memory_id")
    val memoryId: String,
    @ColumnInfo("consolidated_at")
    val consolidatedAt: Long,
) {
    companion object {
        /** DB46 → DB47 迁移写入的哨兵值，首次读取设置后按各助手真实 memoryId 一次性回填 */
        const val LEGACY_MEMORY_ID = ""
    }
}
