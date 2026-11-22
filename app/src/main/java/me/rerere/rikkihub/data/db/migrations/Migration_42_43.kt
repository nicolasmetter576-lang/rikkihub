package me.rerere.rikkihub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkihub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_42_43"

/**
 * 新增用量流水表 usage_record 与额度读数表 quota_snapshot，并用现有助手消息上的 usage 回填，
 * 升级后统计不断档。
 *
 * - 旧消息只有整条消息的累计 usage（多步回复是各步之和），每条助手消息记一行 CHAT；所有分支都算，
 *   和实际消耗一致；回收站里的会话也算。
 * - total 取消息上的 totalTokens，没有（旧数据为 0）时用输入 + 输出。
 * - 消息的 createdAt 是本地时间的 ISO 字符串，按设备时区换算成时间戳，解析不了时用会话的创建时间。
 * - 旧消息没有提供商和账号，这两列留空：统计页按模型 id 在当前设置里找提供商，不参与额度反推。
 */
val Migration_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: usage records + quota snapshots")
        DatabaseMigrationTracker.onMigrationStart(42, 43)
        try {
            createUsageTables(db)
            backfillUsageRecords(db)
            Log.i(TAG, "migrate: done")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

internal fun createUsageTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `usage_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, `started_at` INTEGER NOT NULL DEFAULT 0, " +
            "`usage_complete` INTEGER NOT NULL DEFAULT 0, `source` TEXT NOT NULL, `provider_id` TEXT, `model_id` TEXT, " +
            "`model_name` TEXT, `assistant_id` TEXT, `conversation_id` TEXT, `account_key` TEXT, " +
            "`prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, " +
            "`cached_tokens` INTEGER NOT NULL, `total_tokens` INTEGER NOT NULL)"
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_usage_record_account_key_created_at` " +
            "ON `usage_record` (`account_key`, `created_at`)"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `quota_snapshot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, `account_key` TEXT NOT NULL, `window` TEXT NOT NULL, " +
            "`remaining` REAL NOT NULL, `reset_at` INTEGER)"
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_quota_snapshot_account_key_window_created_at` " +
            "ON `quota_snapshot` (`account_key`, `window`, `created_at`)"
    )
}

internal fun backfillUsageRecords(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT INTO usage_record (
            created_at, source, provider_id, model_id, model_name, assistant_id, conversation_id, account_key,
            prompt_tokens, completion_tokens, cached_tokens, total_tokens
        )
        SELECT
            COALESCE(
                CAST(strftime('%s', substr(json_extract(j.value, '$.createdAt'), 1, 19), 'utc') AS INTEGER) * 1000,
                c.create_at
            ),
            'CHAT',
            NULL,
            json_extract(j.value, '$.modelId'),
            json_extract(j.value, '$.modelSnapshot.modelId'),
            c.assistant_id,
            mn.conversation_id,
            NULL,
            COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0),
            COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0),
            COALESCE(json_extract(j.value, '$.usage.cachedTokens'), 0),
            CASE
                WHEN COALESCE(json_extract(j.value, '$.usage.totalTokens'), 0) > 0
                    THEN json_extract(j.value, '$.usage.totalTokens')
                ELSE COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0)
                    + COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0)
            END
        FROM message_node mn
        JOIN ConversationEntity c ON c.id = mn.conversation_id,
            json_each(CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END) raw,
            json_each(json_array(CASE WHEN raw.type = 'object' THEN json(raw.value) ELSE json('{}') END)) j
        WHERE json_extract(j.value, '$.role') = 'assistant'
            AND json_type(j.value, '$.usage') = 'object'
            AND (
                COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) > 0
                OR COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0) > 0
                OR COALESCE(json_extract(j.value, '$.usage.totalTokens'), 0) > 0
            )
        """.trimIndent()
    )
}
