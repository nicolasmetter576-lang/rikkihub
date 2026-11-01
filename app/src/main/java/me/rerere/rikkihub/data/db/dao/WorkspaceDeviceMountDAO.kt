package me.rerere.rikkihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity

/**
 * `workspace_device_mounts` 的定向读写。
 *
 * 与 [WorkspaceDAO] 同一契约：没有任何 REPLACE/整行 upsert——INSERT OR REPLACE
 * 会先 delete 父行触发其它子表 CASCADE；这里创建用 ABORT，更新按列定向
 * UPDATE，全部「读最新→校验→写」在 [Transaction] 内完成，杜绝锁外旧快照覆盖。
 */
@Dao
interface WorkspaceDeviceMountDAO {

    @Query("SELECT * FROM workspace_device_mounts WHERE workspace_id = :workspaceId ORDER BY alias ASC")
    fun listFlowByWorkspace(workspaceId: String): Flow<List<WorkspaceDeviceMountEntity>>

    @Query("SELECT * FROM workspace_device_mounts WHERE workspace_id = :workspaceId ORDER BY alias ASC")
    suspend fun listByWorkspace(workspaceId: String): List<WorkspaceDeviceMountEntity>

    @Query("SELECT * FROM workspace_device_mounts ORDER BY workspace_id ASC, alias ASC")
    suspend fun listAll(): List<WorkspaceDeviceMountEntity>

    @Query("SELECT * FROM workspace_device_mounts WHERE id = :id")
    suspend fun getById(id: String): WorkspaceDeviceMountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(mount: WorkspaceDeviceMountEntity)

    @Query(
        "UPDATE workspace_device_mounts SET alias = :alias, updated_at = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateAlias(id: String, alias: String, updatedAt: Long): Int

    @Query(
        "UPDATE workspace_device_mounts SET source_relative_path = :sourceRelativePath, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateSource(id: String, sourceRelativePath: String, updatedAt: Long): Int

    @Query(
        "UPDATE workspace_device_mounts SET enabled = :enabled, updated_at = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query(
        "UPDATE workspace_device_mounts SET alias = :alias, " +
            "source_relative_path = :sourceRelativePath, enabled = :enabled, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateColumns(
        id: String,
        alias: String,
        sourceRelativePath: String,
        enabled: Boolean,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM workspace_device_mounts WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * 事务内读指定工作区最新连接列表交给 [transform] 校验/改写，再按差分定向写回。
     * `create`/`update`/`delete` 的「读 all latest → 校验（限额/alias/重叠/跨工作区
     * source）→ 写」链路由此保证不发生锁外 TOCTOU。
     */
    @Transaction
    suspend fun updateMountsInTransaction(
        workspaceId: String,
        transform: suspend (List<WorkspaceDeviceMountEntity>) -> WorkspaceDeviceMountPatch,
    ): List<WorkspaceDeviceMountEntity> {
        val current = listByWorkspace(workspaceId)
        val patch = transform(current)
        val now = System.currentTimeMillis()
        patch.insert?.let { insert(it.copy(updatedAt = now, createdAt = it.createdAt)) }
        for (update in patch.updates) {
            updateColumns(
                id = update.id,
                alias = update.alias,
                sourceRelativePath = update.sourceRelativePath,
                enabled = update.enabled,
                updatedAt = now,
            )
        }
        for (id in patch.deletes) {
            deleteById(id)
        }
        return listByWorkspace(workspaceId)
    }
}

/**
 * [WorkspaceDeviceMountDAO.updateMountsInTransaction] 的差分结果：
 * 单条 insert（ABORT）、按 id 的整列组定向 update、按 id 的 delete。
 */
data class WorkspaceDeviceMountPatch(
    val insert: WorkspaceDeviceMountEntity? = null,
    val updates: List<WorkspaceDeviceMountEntity> = emptyList(),
    val deletes: List<String> = emptyList(),
)
