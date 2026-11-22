package me.rerere.rikkihub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.utils.JsonInstant

/**
 * V6（2.34.0）：给每个助手的预设补上角色卡与用户设定的四个标记（`personaDescription`、`charDescription`、
 * `charPersonality`、`scenario`），没有角色时它们不产出任何消息，所以已有助手的请求不变。
 * 转换本身在 [TavernSettingsMigration.addCardMarkers]，备份恢复走同一套（[SettingsJsonMigrator]）。
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS]?.let { raw ->
            val array = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            if (array != null) prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(TavernSettingsMigration.addCardMarkers(array))
        }
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
