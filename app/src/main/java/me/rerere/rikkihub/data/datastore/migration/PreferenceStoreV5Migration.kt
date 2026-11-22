package me.rerere.rikkihub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.utils.JsonInstant

/**
 * V5（2.33.0）：助手的系统提示词/分块/正则 → 酒馆预设，模式注入 → 提示词库，
 * 世界书条目 → 酒馆条目，补全局酒馆设置。转换本身在 [TavernSettingsMigration]，备份恢复走同一套（[SettingsJsonMigrator]）。
 * 任何一个键解析失败就原样留着，不让迁移把设置整个丢掉。
 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.ASSISTANTS]?.let { raw ->
            parseArray(raw)?.let { prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(TavernSettingsMigration.migrateAssistants(it)) }
        }
        // 新安装时旧键不存在：不写库，读取时退回内置默认
        prefs[SettingsStore.MODE_INJECTIONS]?.let { raw ->
            parseArray(raw)?.let {
                prefs[SettingsStore.PROMPT_LIBRARY] = JsonInstant.encodeToString(TavernSettingsMigration.migrateModeInjections(it))
                prefs.remove(SettingsStore.MODE_INJECTIONS)
            }
        }
        var hadLorebooks = false
        prefs[SettingsStore.LOREBOOKS]?.let { raw ->
            parseArray(raw)?.let {
                hadLorebooks = it.isNotEmpty()
                prefs[SettingsStore.LOREBOOKS] = JsonInstant.encodeToString(TavernSettingsMigration.migrateLorebooks(it))
            }
        }
        if (prefs[SettingsStore.TAVERN_SETTINGS] == null) {
            prefs[SettingsStore.TAVERN_SETTINGS] = JsonInstant.encodeToString(TavernSettingsMigration.defaultTavernSettings(hadLorebooks))
        }
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}

    private fun parseArray(raw: String): JsonArray? = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonArray }.getOrNull()
}
