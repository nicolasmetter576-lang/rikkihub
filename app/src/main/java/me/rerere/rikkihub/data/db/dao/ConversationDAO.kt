package me.rerere.rikkihub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.ConversationEntity
import me.rerere.rikkihub.data.repository.LightConversationEntity

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND deleted_at IS NULL ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId AND deleted_at IS NULL ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId AND folder_id = '' AND deleted_at IS NULL ORDER BY is_pinned DESC, CASE WHEN :sortByCreateTime THEN create_at ELSE update_at END DESC")
    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: String,
        sortByCreateTime: Boolean,
    ): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE folder_id = :folderId AND deleted_at IS NULL ORDER BY is_pinned DESC, CASE WHEN :sortByCreateTime THEN create_at ELSE update_at END DESC")
    fun getConversationsOfFolderPaging(
        folderId: String,
        sortByCreateTime: Boolean,
    ): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND deleted_at IS NULL ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' AND deleted_at IS NULL ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    // 含软删除的会话：撤销删除后标题也不会和新 fork 重名
    @Query("SELECT title FROM conversationentity WHERE assistant_id = :assistantId")
    suspend fun getTitlesOfAssistant(assistantId: String): List<String>

    @Query("SELECT id FROM conversationentity WHERE deleted_at IS NULL")
    suspend fun getAllIds(): List<String>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun getLightConversationsByIds(ids: List<String>): List<LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id AND deleted_at IS NULL")
    suspend fun getConversationById(id: String): ConversationEntity?

    /** 包括 tombstone（软删除）的行；恢复/ABA 校验专用，普通读路径不得用它。 */
    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationByIdIncludingDeleted(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id AND deleted_at IS NULL)")
    suspend fun existsById(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsByIdIncludingDeleted(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    /**
     * 乐观并发整行 CAS：会话行/节点/FTS/receipt 的同事务写入口。
     * 仅在 (revision, generation) 与当前行一致且未删除时命中；返回受影响行数，
     * 0 = 并发冲突/已删除（调用方转 MutationConflictException）。
     */
    @Query(
        "UPDATE conversationentity SET assistant_id=:assistantId, title=:title, nodes=:nodes, " +
            "create_at=:createAt, update_at=:updateAt, suggestions=:chatSuggestions, is_pinned=:isPinned, " +
            "custom_system_prompt=:customSystemPrompt, mode_injection_ids=:modeInjectionIds, " +
            "lorebook_ids=:lorebookIds, workspace_cwd=:workspaceCwd, workspace_cwd_scoped=:workspaceCwdScoped, " +
            "folder_id=:folderId, " +
            "revision=revision+1 " +
            "WHERE id=:id AND revision=:expectedRevision AND generation=:expectedGeneration AND deleted_at IS NULL"
    )
    suspend fun updateCas(
        id: String,
        expectedRevision: Long,
        expectedGeneration: Long,
        assistantId: String,
        title: String,
        nodes: String,
        createAt: Long,
        updateAt: Long,
        chatSuggestions: String,
        isPinned: Boolean,
        customSystemPrompt: String,
        modeInjectionIds: String,
        lorebookIds: String,
        workspaceCwd: String,
        workspaceCwdScoped: Boolean,
        folderId: String,
    ): Int

    /** 软删除（tombstone）：同事务置 deleted_at、generation+1（防 ABA）、revision+1。 */
    @Query(
        "UPDATE conversationentity SET deleted_at=:deletedAt, generation=generation+1, revision=revision+1 " +
            "WHERE id=:id AND revision=:expectedRevision AND generation=:expectedGeneration AND deleted_at IS NULL"
    )
    suspend fun markDeletedCas(
        id: String,
        expectedRevision: Long,
        expectedGeneration: Long,
        deletedAt: Long,
    ): Int

    /**
     * 从 tombstone 恢复：清删除标记 + generation+1 + revision+1（同 ID 恢复换代）。
     * update_at 保持原值：撤销删除后会话回到它在列表里的原位置。
     */
    @Query(
        "UPDATE conversationentity SET deleted_at=NULL, generation=generation+1, revision=revision+1 " +
            "WHERE id=:id AND generation=:expectedGeneration AND deleted_at IS NOT NULL"
    )
    suspend fun restoreDeletedCas(
        id: String,
        expectedGeneration: Long,
    ): Int

    /** 物理删除 [cutoff] 之前软删除的会话；节点经外键级联删除，FTS 在软删除时已清理。 */
    @Query("DELETE FROM conversationentity WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 AND deleted_at IS NULL ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT is_pinned FROM conversationentity WHERE id = :id AND deleted_at IS NULL")
    suspend fun isPinned(id: String): Boolean?

    @Query("UPDATE conversationentity SET is_pinned = :isPinned, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET assistant_id = :assistantId, folder_id = '', revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateAssistant(id: String, assistantId: String)

    @Query("UPDATE conversationentity SET title = :title, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE conversationentity SET suggestions = :chatSuggestions, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateChatSuggestions(id: String, chatSuggestions: String)

    @Query("UPDATE conversationentity SET mode_injection_ids = :modeInjectionIds, lorebook_ids = :lorebookIds, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateInjectionIds(id: String, modeInjectionIds: String, lorebookIds: String)

    @Query("UPDATE conversationentity SET folder_id = :folderId, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateFolderId(id: String, folderId: String)

    @Query("UPDATE conversationentity SET custom_system_prompt = :customSystemPrompt, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateCustomSystemPrompt(id: String, customSystemPrompt: String)

    @Query("UPDATE conversationentity SET workspace_cwd = :workspaceCwd, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateWorkspaceCwd(id: String, workspaceCwd: String)

    @Query("UPDATE conversationentity SET workspace_cwd_scoped = :scoped, revision=revision+1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateWorkspaceCwdScoped(id: String, scoped: Boolean)

    @Query("UPDATE conversationentity SET folder_id = '', revision=revision+1 WHERE folder_id = :folderId AND deleted_at IS NULL")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT assistant_id AS assistantId, COUNT(*) AS count FROM conversationentity WHERE deleted_at IS NULL GROUP BY assistant_id")
    suspend fun countByAssistant(): List<ConversationAssistantCount>

    @Query("SELECT COUNT(*) FROM conversationentity WHERE deleted_at IS NULL")
    suspend fun countAll(): Int

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis AND deleted_at IS NULL " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>

    @Query("SELECT id, mode_injection_ids AS modeInjectionIds, lorebook_ids AS lorebookIds, revision, generation FROM conversationentity WHERE deleted_at IS NULL ORDER BY id")
    suspend fun referenceRows(): List<ConversationReferenceRow>

    /** Creator listing: conversations of every assistant except [excludedAssistantId], deleted ones last. */
    @Query(
        "SELECT id, assistant_id AS assistantId, title, is_pinned AS isPinned, update_at AS updateAt, deleted_at AS deletedAt " +
            "FROM conversationentity WHERE assistant_id != :excludedAssistantId AND title LIKE '%' || :filter || '%' " +
            "ORDER BY (deleted_at IS NOT NULL), update_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun managedConversationPage(excludedAssistantId: String, filter: String, limit: Int, offset: Int): List<ManagedConversationRow>
}

/** One row of [ConversationDAO.managedConversationPage]. */
data class ManagedConversationRow(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val updateAt: Long,
    val deletedAt: Long?,
)

data class ConversationAssistantCount(val assistantId: String, val count: Long)

data class ConversationDayCount(val day: String, val count: Int)

/** Reference projection used for management predicate baselines (no bodies). */
data class ConversationReferenceRow(
    val id: String,
    val modeInjectionIds: String,
    val lorebookIds: String,
    val revision: Long,
    val generation: Long,
)
