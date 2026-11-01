package me.rerere.rikkihub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkihub.data.db.DatabaseMigrationTracker
import me.rerere.rikkihub.data.model.MemoryFile

private const val TAG = "Migration_39_40"

/**
 * 条目式记忆 → Markdown 记忆库（上游 RikkaHub 的 27→28 与 28→29 合在一起）：
 * - 新表 memory_file（主键 assistant_id + path），每个记忆库（助手 id 或全局 `__global__`）的旧条目
 *   按原顺序合成一份 /profile.md：旧记忆每次都完整注入，profile 同样完整注入，升级后模型看到的内容不变；
 * - 删掉 MemoryEntity；
 * - 新表 memory_consolidation 记录各对话的整理进度，已有的对话一律算作整理过，
 *   否则升级后第一次「做梦」会把整段历史都交给模型。
 */
val Migration_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: memory records -> memory files")
        DatabaseMigrationTracker.onMigrationStart(39, 40)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))"
            )

            val memories = linkedMapOf<String, MutableList<String>>()
            db.query("SELECT assistant_id, content FROM MemoryEntity ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    memories.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1))
                }
            }
            val now = System.currentTimeMillis()
            var migrated = 0
            memories.forEach { (assistantId, contents) ->
                val profile = legacyMemoriesToProfile(contents) ?: return@forEach
                db.execSQL(
                    "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(assistantId, MemoryFile.PROFILE_PATH, profile, now)
                )
                migrated++
            }
            db.execSQL("DROP TABLE IF EXISTS MemoryEntity")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, " +
                    "`consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), " +
                    "FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO memory_consolidation (conversation_id, consolidated_at) " +
                    "SELECT id, update_at FROM ConversationEntity"
            )
            Log.i(TAG, "migrate: done ($migrated memory stores migrated)")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

/** 把旧的逐条记忆拼成一份 profile 文件，没有有效内容时返回 null。多行的条目合成一行，每条一个列表项 */
internal fun legacyMemoriesToProfile(contents: List<String>): String? {
    val lines = contents
        .map { it.lines().joinToString(" ") { line -> line.trim() }.trim() }
        .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    return buildString {
        appendLine("---")
        appendLine("name: profile")
        appendLine("description: Who the user is")
        appendLine("---")
        appendLine()
        lines.forEach { appendLine("- [stated] $it") }
    }
}
