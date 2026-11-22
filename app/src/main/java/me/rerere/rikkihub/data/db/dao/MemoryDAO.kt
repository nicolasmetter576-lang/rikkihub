package me.rerere.rikkihub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.MemoryFileEntity

data class AssistantMemoryCount(
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("file_count")
    val count: Int,
)

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT COUNT(*) FROM memory_file WHERE assistant_id = :assistantId")
    fun getFileCountFlow(assistantId: String): Flow<Int>

    @Query("SELECT assistant_id, COUNT(*) AS file_count FROM memory_file GROUP BY assistant_id")
    fun getFileCountsByAssistantFlow(): Flow<List<AssistantMemoryCount>>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    suspend fun getFile(assistantId: String, path: String): MemoryFileEntity?

    @Upsert
    suspend fun upsertFile(file: MemoryFileEntity)

    @Upsert
    suspend fun upsertFiles(files: List<MemoryFileEntity>)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    suspend fun deleteFile(assistantId: String, path: String)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId")
    suspend fun deleteFilesOfAssistant(assistantId: String)
}
