package me.rerere.rikkihub.data.datastore.migration

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.utils.JsonInstant

private const val TAG = "PreferenceStoreV7"

/**
 * V7（2.35.0）：给每个助手加上酒馆模式开关 `tavernMode`，按内容判断开还是关（[TavernSettingsMigration.addTavernMode]）。
 * 和角色聊过、写过作者注释的会话在数据库里，这里直接查一次；查不到（库还没建、正在迁移）就当没有。
 */
class PreferenceStoreV7Migration(private val context: Context) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 7
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS]?.let { raw ->
            val array = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            if (array != null && array.isNotEmpty()) {
                val tavern = prefs[SettingsStore.TAVERN_SETTINGS]?.let { runCatching { JsonInstant.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                val ids = tavernConversationAssistantIds(context)
                prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(TavernSettingsMigration.addTavernMode(array, tavern, ids))
            }
        }
        prefs[SettingsStore.VERSION] = 7
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * 有会话和角色聊、或会话里写了作者注释的助手 id。作者注释存在 `tavern_metadata` 的 `note_prompt` 里（紧凑 JSON，空串就是没写）。
 * 备份恢复在暂存库上用同一句查（`BackupManager`）。
 */
internal const val TAVERN_CONVERSATION_ASSISTANTS_SQL =
    "SELECT DISTINCT assistant_id FROM ConversationEntity WHERE character_id != '' " +
        "OR (tavern_metadata LIKE '%\"note_prompt\":%' AND tavern_metadata NOT LIKE '%\"note_prompt\":\"\"%')"

private fun tavernConversationAssistantIds(context: Context): Set<String> {
    val file = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
    if (!file.exists()) return emptySet()
    return runCatching {
        // 只读查询；用读写方式打开是为了 WAL 库也能打开（只读打开要求 -shm 已存在），不创建文件
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery(TAVERN_CONVERSATION_ASSISTANTS_SQL, null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }
    }.onFailure { Log.w(TAG, "tavernConversationAssistantIds failed; treating as none", it) }.getOrDefault(emptySet())
}
