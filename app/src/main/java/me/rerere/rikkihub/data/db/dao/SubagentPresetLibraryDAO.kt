package me.rerere.rikkihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.SubagentPresetLibraryEntity

@Dao
interface SubagentPresetLibraryDAO {
    @Query("SELECT * FROM subagent_preset_library WHERE id = 1")
    fun observe(): Flow<SubagentPresetLibraryEntity?>

    @Query("SELECT * FROM subagent_preset_library WHERE id = 1")
    suspend fun read(): SubagentPresetLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun initialize(entity: SubagentPresetLibraryEntity): Long

    /** CAS and targeted SQL, never REPLACE or a whole-workspace update. */
    @Query("UPDATE subagent_preset_library SET presets = :presets, revision = :revision WHERE id = 1 AND revision = :expectedRevision")
    suspend fun compareAndSet(presets: String, revision: Long, expectedRevision: Long): Int
}
