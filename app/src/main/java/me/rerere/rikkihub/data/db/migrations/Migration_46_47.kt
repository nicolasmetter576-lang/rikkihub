package me.rerere.rikkihub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkihub.data.db.DatabaseMigrationTracker
import me.rerere.rikkihub.data.db.entity.MemoryConsolidationEntity

private const val TAG = "Migration_46_47"

/**
 * 将 memory_consolidation 主键由 (conversation_id) 升级为复合主键 (conversation_id, memory_id)，
 * 使同一对话在助手独立记忆库与全局记忆库之间分别跟踪做梦进度。
 *
 * SQLite 迁移期读不到 DataStore 中的 Assistant.useGlobalMemory，故旧行先统一写入哨兵
 * [MemoryConsolidationEntity.LEGACY_MEMORY_ID]，应用冷启动或首次查库时由 MemoryConsolidator
 * 按设置中各助手真实的 memoryId 一次性精确回填（兼容普通升级与旧备份恢复）。
 */
val Migration_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 46 to 47 (memory_consolidation per-memoryId key)")
        DatabaseMigrationTracker.onMigrationStart(46, 47)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_consolidation_new` (" +
                    "`conversation_id` TEXT NOT NULL, " +
                    "`memory_id` TEXT NOT NULL, " +
                    "`consolidated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`conversation_id`, `memory_id`), " +
                    "FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO `memory_consolidation_new` (`conversation_id`, `memory_id`, `consolidated_at`) " +
                    "SELECT m.`conversation_id`, ?, m.`consolidated_at` " +
                    "FROM `memory_consolidation` AS m " +
                    "INNER JOIN `ConversationEntity` AS c ON c.`id` = m.`conversation_id`",
                arrayOf<Any>(MemoryConsolidationEntity.LEGACY_MEMORY_ID),
            )
            db.execSQL("DROP TABLE `memory_consolidation`")
            db.execSQL("ALTER TABLE `memory_consolidation_new` RENAME TO `memory_consolidation`")
            Log.i(TAG, "migrate: migrate from 46 to 47 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
