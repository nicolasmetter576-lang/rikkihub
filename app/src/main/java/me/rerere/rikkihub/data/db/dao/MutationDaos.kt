package me.rerere.rikkihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.AttachmentRetentionEntity
import me.rerere.rikkihub.data.db.entity.OperationReceiptEntity

/**
 * 操作 receipt DAO。receipt 与业务写同事务（Repository 的 withTransaction 内调用）；
 * phase 单调推进由业务层保证（同一 operationId 的 INTENTS/APPLIED 多次写是合法的
 * 相位更新，不是独立事务）。
 */
@Dao
interface OperationReceiptDAO {
    /** UPSERT 相位：同 operationId 多次写覆盖（INTENT→APPLIED/FAILED/NEEDS_RECOVERY）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: OperationReceiptEntity)

    @Query("SELECT * FROM operation_receipts WHERE operation_id = :operationId LIMIT 1")
    suspend fun get(operationId: String): OperationReceiptEntity?

    @Query("SELECT * FROM operation_receipts WHERE object_key = :objectKey ORDER BY created_at DESC")
    suspend fun listByObject(objectKey: String): List<OperationReceiptEntity>

    /** 启动扫描：仍停在 INTENT/APPLYING 相位的 operationId（Vault 缺失时的参与记录重建）。 */
    @Query("SELECT operation_id FROM operation_receipts WHERE phase IN ('INTENT', 'APPLYING')")
    suspend fun openOperationIds(): List<String>

    @Query("SELECT * FROM operation_receipts ORDER BY created_at DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<OperationReceiptEntity>>

    @Query("DELETE FROM operation_receipts WHERE created_at < :olderThan AND phase IN ('APPLIED','FAILED','ROLLED_BACK','CANCELLED')")
    suspend fun pruneFinishedBefore(olderThan: Long): Int

    @Query("DELETE FROM operation_receipts WHERE operation_id = :operationId")
    suspend fun delete(operationId: String): Int
}

/**
 * 持久回收租约 DAO（重启后仍生效；纯内存 AttachmentLeaseRegistry 不能代替它）。
 * 删除路径：保留期内任何引用复查阻止删盘；到期经 sweeper 复查后逐条移除。
 */
@Dao
interface AttachmentRetentionDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AttachmentRetentionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<AttachmentRetentionEntity>)

    @Query("SELECT * FROM attachment_retention WHERE relative_path = :relativePath LIMIT 1")
    suspend fun get(relativePath: String): AttachmentRetentionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM attachment_retention WHERE relative_path = :relativePath AND expires_at > :now)")
    suspend fun isRetained(relativePath: String, now: Long): Boolean

    @Query("SELECT * FROM attachment_retention WHERE expires_at <= :now ORDER BY expires_at ASC")
    suspend fun expired(now: Long): List<AttachmentRetentionEntity>

    @Query("SELECT * FROM attachment_retention WHERE conversation_id = :conversationId")
    suspend fun listByConversation(conversationId: String): List<AttachmentRetentionEntity>

    @Query("SELECT * FROM attachment_retention ORDER BY created_at ASC")
    suspend fun listAll(): List<AttachmentRetentionEntity>

    @Query("DELETE FROM attachment_retention WHERE relative_path = :relativePath")
    suspend fun delete(relativePath: String): Int

    @Query("DELETE FROM attachment_retention WHERE relative_path IN (:paths)")
    suspend fun deleteAll(paths: List<String>): Int

    @Query("DELETE FROM attachment_retention WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String): Int
}
