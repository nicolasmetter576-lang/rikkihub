package me.rerere.rikkihub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions

/** 已移除的搜索服务类型（RikkaHub 官方搜索 api.rikka-ai.com），留在旧数据里会让整个 searchServices 反序列化失败。 */
private val REMOVED_SEARCH_SERVICE_TYPES = setOf("rikkahub")

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.SEARCH_SERVICES]?.let { servicesJson ->
            val (migrated, selected) =
                dropRemovedSearchServices(servicesJson, prefs[SettingsStore.SEARCH_SELECTED] ?: 0)
            prefs[SettingsStore.SEARCH_SERVICES] = migrated
            prefs[SettingsStore.SEARCH_SELECTED] = selected
        }
        prefs[SettingsStore.VERSION] = 4

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * 从 searchServices JSON 中移除已删除类型的搜索服务，并修正选中下标：
 * 选中项被移除时回到 0，否则按其前面被移除的数量前移。全部被移除时换成默认搜索服务。
 * 解析失败时原样返回。
 */
internal fun dropRemovedSearchServices(servicesJson: String, selected: Int): Pair<String, Int> {
    return runCatching {
        val services = JsonInstant.parseToJsonElement(servicesJson) as? JsonArray
            ?: return@runCatching servicesJson to selected

        val removed = services.indices.filter { index ->
            val type = ((services[index] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            type in REMOVED_SEARCH_SERVICE_TYPES
        }.toSet()
        if (removed.isEmpty()) return@runCatching servicesJson to selected

        val kept = services.filterIndexed { index, _ -> index !in removed }
        if (kept.isEmpty()) {
            val fallback = JsonInstant.encodeToJsonElement(SearchServiceOptions.serializer(), SearchServiceOptions.DEFAULT)
            return@runCatching JsonInstant.encodeToString(JsonArray(listOf(fallback))) to 0
        }

        val newSelected = if (selected in removed) 0 else selected - removed.count { it < selected }
        JsonInstant.encodeToString(JsonArray(kept)) to newSelected
    }.getOrElse { servicesJson to selected }
}
