package me.rerere.rikkihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.FavoriteEntity

// 节点收藏的 ref_key 是 node:<会话 id>:<节点 id>，会话 id 是 36 位 UUID。
// 收藏和会话没有外键：会话软删除期间收藏只是不列出（撤销删除后回来），
// 物理删除或节点被移除后由 deleteOrphanNodeFavorites / deleteByRefKeys 删掉。
private const val NOT_IN_DELETED_CONVERSATION =
    "NOT (type = 'node' AND EXISTS (SELECT 1 FROM conversationentity c " +
        "WHERE c.id = substr(favorites.ref_key, 6, 36) AND c.deleted_at IS NOT NULL))"

@Dao
interface FavoriteDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites WHERE $NOT_IN_DELETED_CONVERSATION ORDER BY created_at DESC")
    fun listAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type AND $NOT_IN_DELETED_CONVERSATION ORDER BY created_at DESC")
    fun listByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT ref_key FROM favorites WHERE type = :type")
    suspend fun getRefKeysByType(type: String): List<String>

    @Query("SELECT substr(ref_key, length('node:' || :conversationId || ':') + 1) FROM favorites WHERE ref_key LIKE 'node:' || :conversationId || ':%'")
    suspend fun getFavoriteNodeIdsOfConversation(conversationId: String): List<String>

    @Query("SELECT * FROM favorites WHERE ref_key = :refKey LIMIT 1")
    suspend fun getByRefKey(refKey: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE ref_key = :refKey)")
    suspend fun existsByRefKey(refKey: String): Boolean

    @Query("DELETE FROM favorites WHERE ref_key = :refKey")
    suspend fun deleteByRefKey(refKey: String): Int

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM favorites WHERE ref_key IN (:refKeys)")
    suspend fun deleteByRefKeys(refKeys: List<String>): Int

    /** 删掉节点已不在库里的收藏（会话被物理删除、节点被移除，或更早版本留下的）。 */
    @Query(
        "DELETE FROM favorites WHERE type = 'node' AND NOT EXISTS (SELECT 1 FROM message_node n " +
            "WHERE n.id = substr(favorites.ref_key, 43) AND n.conversation_id = substr(favorites.ref_key, 6, 36))"
    )
    suspend fun deleteOrphanNodeFavorites(): Int
}
