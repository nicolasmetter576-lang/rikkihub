package me.rerere.rikkihub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkihub.data.db.entity.TranslationHistoryEntity

@Dao
interface TranslationHistoryDAO {
    // id 作第二排序键：同一毫秒写入的两条也有确定的先后，分页不会重复或漏掉
    @Query("SELECT * FROM translation_history ORDER BY create_at DESC, id DESC")
    fun pagingAll(): PagingSource<Int, TranslationHistoryEntity>

    @Query("SELECT * FROM translation_history ORDER BY create_at DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun page(offset: Int, limit: Int): List<TranslationHistoryEntity>

    @Query("SELECT COUNT(*) FROM translation_history")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslationHistoryEntity)

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM translation_history")
    suspend fun deleteAll()

    // 只留最新的 keep 条
    @Query(
        "DELETE FROM translation_history WHERE id NOT IN " +
            "(SELECT id FROM translation_history ORDER BY create_at DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Transaction
    suspend fun insertAndTrim(entity: TranslationHistoryEntity, keep: Int) {
        insert(entity)
        trimTo(keep)
    }
}
