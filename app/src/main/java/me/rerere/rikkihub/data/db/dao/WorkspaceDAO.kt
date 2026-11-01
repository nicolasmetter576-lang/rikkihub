package me.rerere.rikkihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.utils.JsonInstant

@Dao
interface WorkspaceDAO {
    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    /**
     * 创建工作区专用. 刻意用 ABORT 而不是 REPLACE:
     * INSERT OR REPLACE 在冲突时是 DELETE+INSERT, 将来挂 CASCADE 子表
     * (设备连接等) 时, 一次「普通更新误用 upsert」就会把子表记录级联删掉.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(workspace: WorkspaceEntity)

    // 注意: 本 DAO 刻意不提供任何整行写入 (@Update/REPLACE upsert).
    // 全部更新只走定向 @Query 或 @Transaction 内的差分定向 UPDATE,
    // 杜绝旧 entity 快照覆盖并发写入、以及 REPLACE 触发 CASCADE 子表删除.

    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    suspend fun getAll(): List<WorkspaceEntity>

    @Query("UPDATE workspaces SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET subagent_model_id = :modelId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSubagentModelId(id: String, modelId: String?, updatedAt: Long): Int

    @Query("UPDATE workspaces SET subagent_allow_context = :allowContext, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSubagentAllowContext(id: String, allowContext: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET subagent_presets = :json, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSubagentPresetsJson(id: String, json: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET expose_upload = :exposed, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateExposeUpload(id: String, exposed: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET expose_skills = :exposed, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateExposeSkills(id: String, exposed: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET expose_tool_outputs = :exposed, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateExposeToolOutputs(id: String, exposed: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET tool_approvals = :json, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolApprovalsJson(id: String, json: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET tool_enablement = :json, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolEnablementJson(id: String, json: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET last_access_at = :lastAccessAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLastAccessAt(id: String, lastAccessAt: Long?, updatedAt: Long): Int

    /** 仅刷新 updated_at (transform 没有产生任何列差异时使用) */
    @Query("UPDATE workspaces SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET shell_status = :shellStatus, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET shell_compatibility_mode = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET first_message_file_snapshot = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setFirstMessageFileSnapshot(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET first_message_file_snapshot_depth = :depth, updated_at = :updatedAt WHERE id = :id")
    suspend fun setFirstMessageFileSnapshotDepth(id: String, depth: Int, updatedAt: Long): Int

    /**
     * 事务内读最新行 → [transform] → 按差分列逐条定向 UPDATE.
     * App/Web 配置更新统一入口: 一次调用只写真实变化的列, 全部 UPDATE
     * 在同一事务内执行. 不同列的并发更新不会互相覆盖; 同列并发写由
     * SQLite 事务保证失败/串行, 不会静默丢更新.
     *
     * 身份与创建期固定字段 (id/root/createdAt/harnessMinimal) 不允许被
     * transform 修改, 尝试修改整次抛错回滚. tool_enablement 新值会按
     * [WorkspaceToolEnablement] 严格校验, 非法 JSON/未知键整次回滚;
     * changed subagent_presets is strictly checked by [WorkspaceSubagentPresets],
     * while unchanged corrupt preset JSON is preserved. transform 无差异时仅刷新 updated_at.
     */
    @Transaction
    suspend fun updateInTransaction(
        id: String,
        transform: suspend (WorkspaceEntity) -> WorkspaceEntity,
    ): WorkspaceEntity? {
        val current = getById(id) ?: return null
        val next = transform(current)
        require(next.id == current.id && next.root == current.root &&
            next.createdAt == current.createdAt && next.harnessMinimal == current.harnessMinimal
        ) { "updateInTransaction must not change id/root/createdAt/harnessMinimal" }
        // enablement 写入前严格校验新值, 防止把坏配置写进存储
        if (next.toolEnablement != current.toolEnablement) {
            WorkspaceToolEnablement.parse(next.toolEnablement)
        }
        // Only changed JSON is validated: unrelated metadata edits must preserve legacy corruption.
        if (next.subagentPresets != current.subagentPresets) {
            WorkspaceSubagentPresets.parse(next.subagentPresets)
        }
        val now = System.currentTimeMillis()
        val patch = WorkspaceConfigPatch.diff(current, next)
        if (!patch.hasChanges) {
            touchUpdatedAt(id, now)
        } else {
            patch.name?.let { updateName(id, it, now) }
            if (patch.hasSubagentModelId) updateSubagentModelId(id, patch.subagentModelId, now)
            patch.subagentAllowContext?.let { updateSubagentAllowContext(id, it, now) }
            patch.subagentPresets?.let { updateSubagentPresetsJson(id, it, now) }
            patch.exposeUpload?.let { updateExposeUpload(id, it, now) }
            patch.exposeSkills?.let { updateExposeSkills(id, it, now) }
            patch.exposeToolOutputs?.let { updateExposeToolOutputs(id, it, now) }
            if (patch.hasLastAccessAt) updateLastAccessAt(id, patch.lastAccessAt, now)
            patch.shellStatus?.let { updateShellStatus(id, it, now) }
            patch.shellCompatibilityMode?.let { setShellCompatibilityMode(id, it, now) }
            patch.firstMessageFileSnapshot?.let { setFirstMessageFileSnapshot(id, it, now) }
            patch.firstMessageFileSnapshotDepth?.let { setFirstMessageFileSnapshotDepth(id, it, now) }
            patch.toolApprovals?.let { updateToolApprovalsJson(id, it, now) }
            patch.toolEnablement?.let { updateToolEnablementJson(id, it, now) }
        }
        return next.copy(updatedAt = now)
    }

    /**
     * 事务内读最新 tool_approvals JSON → 增量 merge [patch] → 写回.
     * 旧实现是「先 getById 再整行 upsert」, 两个不同工具键的并发写入会
     * 互相把对方的键盖掉; 这里读与写在同一事务, 且只写 tool_approvals 列.
     * [patch] 的键合法性由调用方校验 (App/Web 都有自己的 allowlist).
     */
    @Transaction
    suspend fun mergeToolApprovals(
        id: String,
        patch: Map<String, Boolean>,
        updatedAt: Long,
    ): Boolean {
        val current = getById(id) ?: return false
        val merged = current.toolApprovalOverrides() + patch
        return updateToolApprovalsJson(id, JsonInstant.encodeToString(merged), updatedAt) > 0
    }

    /**
     * 事务内读最新 tool_enablement JSON → 增量 merge [patch] → 写回.
     * [patch] 只接受可配置稳定 ID + boolean; 已存 JSON 损坏或键非法时整次
     * 抛错回滚 (fail-closed), 不会把坏数据或未知键写回.
     */
    @Transaction
    suspend fun mergeToolEnablement(
        id: String,
        patch: Map<String, Boolean>,
        updatedAt: Long,
    ): Boolean {
        val current = getById(id) ?: return false
        val merged = WorkspaceToolEnablement.mergedJson(current.toolEnablement, patch)
        return updateToolEnablementJson(id, merged, updatedAt) > 0
    }

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
