package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A persistent singleton also records initialization, so deleting all roles never reseeds them. */
@Entity(tableName = "subagent_preset_library")
data class SubagentPresetLibraryEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = "'[]'") val presets: String = "[]",
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
)
