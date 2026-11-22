package me.rerere.rikkihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.CharacterEntity
import me.rerere.rikkihub.data.db.entity.CharacterSummaryRow

@Dao
interface CharacterDAO {
    @Query(
        "SELECT id, name, avatar_path AS avatarPath, fav, tags, created_at AS createdAt, updated_at AS updatedAt, revision " +
            "FROM characters ORDER BY name COLLATE NOCASE ASC, id ASC"
    )
    fun observeSummaries(): Flow<List<CharacterSummaryRow>>

    @Query(
        "SELECT id, name, avatar_path AS avatarPath, fav, tags, created_at AS createdAt, updated_at AS updatedAt, revision " +
            "FROM characters ORDER BY name COLLATE NOCASE ASC, id ASC"
    )
    suspend fun summaries(): List<CharacterSummaryRow>

    /** 搜索：[pattern] 是已经转义好的 `%…%`（见 [escapeLike]）。 */
    @Query("SELECT id FROM characters WHERE search_text LIKE :pattern ESCAPE '\\'")
    suspend fun searchIds(pattern: String): List<String>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun get(id: String): CharacterEntity?

    @Query("SELECT * FROM characters WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<CharacterEntity>

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CharacterEntity)

    /** 整行 CAS：只在版本号还是 [expectedRevision] 时写，返回受影响行数（0 = 被别处改过或已删除）。 */
    @Query(
        "UPDATE characters SET name=:name, avatar_path=:avatarPath, card_json=:cardJson, source_name=:sourceName, fav=:fav, " +
            "tags=:tags, search_text=:searchText, updated_at=:updatedAt, local_settings=:localSettings, revision=revision+1 " +
            "WHERE id=:id AND revision=:expectedRevision"
    )
    suspend fun updateCas(
        id: String,
        expectedRevision: Long,
        name: String,
        avatarPath: String,
        cardJson: String,
        sourceName: String,
        fav: Boolean,
        tags: String,
        searchText: String,
        updatedAt: Long,
        localSettings: String,
    ): Int

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COUNT(*) FROM conversationentity WHERE character_id = :id AND deleted_at IS NULL")
    suspend fun conversationCount(id: String): Int
}

/** 把用户输入变成 LIKE 的字面子串模式（`%`、`_`、转义符本身都转义）。 */
fun escapeLike(raw: String): String =
    "%" + raw.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
