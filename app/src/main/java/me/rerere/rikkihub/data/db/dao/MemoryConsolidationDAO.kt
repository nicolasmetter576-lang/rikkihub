package me.rerere.rikkihub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import me.rerere.rikkihub.data.db.entity.MemoryConsolidationEntity

data class LegacyMemoryConsolidationRow(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("consolidated_at")
    val consolidatedAt: Long,
)

@Dao
interface MemoryConsolidationDAO {
    @Query("SELECT consolidated_at FROM memory_consolidation WHERE conversation_id = :conversationId AND memory_id = :memoryId")
    suspend fun getConsolidatedAt(conversationId: String, memoryId: String): Long?

    @Upsert
    suspend fun upsert(entity: MemoryConsolidationEntity)

    @Query(
        "INSERT INTO memory_consolidation (conversation_id, memory_id, consolidated_at) " +
            "VALUES (:conversationId, :memoryId, :consolidatedAt) " +
            "ON CONFLICT(conversation_id, memory_id) DO UPDATE " +
            "SET consolidated_at = MAX(memory_consolidation.consolidated_at, excluded.consolidated_at)"
    )
    suspend fun upsertMax(conversationId: String, memoryId: String, consolidatedAt: Long)

    /**
     * 上次整理进 [memoryId] 之后又有更新的对话，最近更新的在前；是否真有新的轮次要读出消息才知道。
     * 软删除（等待物理清除）的对话不算：用户已经删了它，不该再拿去整理记忆。
     */
    @Query(
        "SELECT c.id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id AND m.memory_id = :memoryId " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.deleted_at IS NULL " +
            "AND c.cli_session = '' " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0) " +
            "ORDER BY c.update_at DESC LIMIT :limit"
    )
    suspend fun getUpdatedConversationIds(
        assistantIds: List<String>,
        memoryId: String,
        since: Long,
        limit: Int,
    ): List<String>

    @Query(
        "SELECT COUNT(*) FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id AND m.memory_id = :memoryId " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.deleted_at IS NULL " +
            "AND c.cli_session = '' " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0)"
    )
    suspend fun countUpdatedConversations(
        assistantIds: List<String>,
        memoryId: String,
        since: Long,
    ): Int

    /** 独立记忆库助手（memory_id == assistant_id）里有哪些存在上次整理之后又有更新的对话 */
    @Query(
        "SELECT DISTINCT c.assistant_id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id AND m.memory_id = c.assistant_id " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.deleted_at IS NULL " +
            "AND c.cli_session = '' " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0)"
    )
    suspend fun getPrivateAssistantIdsWithUpdatedConversations(assistantIds: List<String>, since: Long): List<String>

    /** 共享同一目标 [memoryId] 的助手里有哪些存在上次整理之后又有更新的对话 */
    @Query(
        "SELECT DISTINCT c.assistant_id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id AND m.memory_id = :memoryId " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.deleted_at IS NULL " +
            "AND c.cli_session = '' " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0)"
    )
    suspend fun getAssistantIdsWithUpdatedConversations(
        assistantIds: List<String>,
        memoryId: String,
        since: Long,
    ): List<String>

    /** 把某助手的私有整理进度复制到全局库（保留较大水位），迁移文件后避免重复做梦 */
    @Query(
        "INSERT INTO memory_consolidation (conversation_id, memory_id, consolidated_at) " +
            "SELECT m.conversation_id, :targetMemoryId, m.consolidated_at " +
            "FROM memory_consolidation AS m " +
            "INNER JOIN conversationentity AS c ON c.id = m.conversation_id " +
            "WHERE c.assistant_id = :assistantId AND m.memory_id = :assistantId " +
            "ON CONFLICT(conversation_id, memory_id) DO UPDATE " +
            "SET consolidated_at = MAX(memory_consolidation.consolidated_at, excluded.consolidated_at)"
    )
    suspend fun copyMarks(assistantId: String, targetMemoryId: String)

    @Query(
        "SELECT m.conversation_id, c.assistant_id, m.consolidated_at " +
            "FROM memory_consolidation AS m " +
            "INNER JOIN conversationentity AS c ON c.id = m.conversation_id " +
            "WHERE m.memory_id = :legacyMemoryId"
    )
    suspend fun getLegacyRows(legacyMemoryId: String = MemoryConsolidationEntity.LEGACY_MEMORY_ID): List<LegacyMemoryConsolidationRow>

    @Query("DELETE FROM memory_consolidation WHERE memory_id = :legacyMemoryId")
    suspend fun deleteLegacyRows(legacyMemoryId: String = MemoryConsolidationEntity.LEGACY_MEMORY_ID)

    @Transaction
    suspend fun reconcileLegacyRows(resolved: List<MemoryConsolidationEntity>) {
        resolved.forEach { entity ->
            upsertMax(entity.conversationId, entity.memoryId, entity.consolidatedAt)
        }
        deleteLegacyRows()
    }
}
