package me.rerere.rikkihub.data.datastore.migration

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已移除的 RikkaHub 官方搜索服务（type = "rikkahub"）：旧数据里残留的条目必须在
 * 反序列化前去掉，否则整个 searchServices 解码失败；选中下标随之修正。
 */
class RemovedSearchServicesMigrationTest {

    private val bing = """{"type":"bing_local","id":"00000000-0000-0000-0000-000000000001"}"""
    private val rikka = """{"type":"rikkahub","id":"00000000-0000-0000-0000-000000000002","apiKey":"k","depth":"standard"}"""
    private val exa = """{"type":"exa","id":"00000000-0000-0000-0000-000000000003","apiKey":""}"""

    private fun decode(json: String) = JsonInstant.decodeFromString<List<SearchServiceOptions>>(json)

    @Test
    fun `removed service is dropped and selection after it shifts left`() {
        val (json, selected) = dropRemovedSearchServices("[$bing,$rikka,$exa]", selected = 2)
        val services = decode(json)
        assertEquals(listOf("Bing", "Exa"), services.map { SearchServiceOptions.TYPES[it::class] })
        assertEquals(1, selected)
    }

    @Test
    fun `selection before removed service is kept`() {
        val (_, selected) = dropRemovedSearchServices("[$bing,$rikka,$exa]", selected = 0)
        assertEquals(0, selected)
    }

    @Test
    fun `selected removed service falls back to first`() {
        val (json, selected) = dropRemovedSearchServices("[$bing,$rikka,$exa]", selected = 1)
        assertEquals(2, decode(json).size)
        assertEquals(0, selected)
    }

    @Test
    fun `only removed service is replaced by default`() {
        val (json, selected) = dropRemovedSearchServices("[$rikka]", selected = 0)
        val services = decode(json)
        assertEquals(1, services.size)
        assertTrue(services.single() is SearchServiceOptions.DuckDuckGoOptions)
        assertEquals(0, selected)
    }

    @Test
    fun `json without removed service passes through unchanged`() {
        val original = "[$bing,$exa]"
        assertEquals(original to 1, dropRemovedSearchServices(original, selected = 1))
    }

    @Test
    fun `datastore migration rewrites stored services and bumps version`() = runBlocking {
        val prefs = mutablePreferencesOf(
            SettingsStore.VERSION to 3,
            SettingsStore.SEARCH_SERVICES to "[$rikka,$exa]",
            SettingsStore.SEARCH_SELECTED to 1,
        )
        val migration = PreferenceStoreV4Migration()
        assertTrue(migration.shouldMigrate(prefs))

        val migrated = migration.migrate(prefs)
        assertEquals(4, migrated[SettingsStore.VERSION])
        assertEquals(0, migrated[SettingsStore.SEARCH_SELECTED])
        assertTrue(decode(migrated[SettingsStore.SEARCH_SERVICES]!!).single() is SearchServiceOptions.ExaOptions)
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test
    fun `backup settings json with removed service decodes after migration`() {
        val backup = """{"searchServices":[$bing,$rikka,$exa],"searchServiceSelected":2}"""
        val settings = JsonInstant.decodeFromString<Settings>(SettingsJsonMigrator.migrate(backup))
        assertEquals(2, settings.searchServices.size)
        assertTrue(settings.searchServices[1] is SearchServiceOptions.ExaOptions)
        assertEquals(1, settings.searchServiceSelected)
    }
}
