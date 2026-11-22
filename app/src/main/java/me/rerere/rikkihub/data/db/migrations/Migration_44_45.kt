package me.rerere.rikkihub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkihub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_44_45"

/**
 * 将 message_node 的索引从 (conversation_id) 替换为非唯一复合索引 (conversation_id, node_index)。
 *
 * 加载会话按 node_index 排序分页读取，只有 conversation_id 索引时每页都要取出全会话行做 TEMP B-TREE 排序；
 * 复合索引仍以 conversation_id 为前导列，外键级联删除和按会话查询照常覆盖，不改动表内数据。
 */
val Migration_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 44 to 45 (message_node index)")
        DatabaseMigrationTracker.onMigrationStart(44, 45)
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                    "ON `message_node` (`conversation_id`, `node_index`)"
            )
            db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
            Log.i(TAG, "migrate: migrate from 44 to 45 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
