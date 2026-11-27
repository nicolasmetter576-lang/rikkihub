package me.rerere.rikkihub.data.datastore.migration

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV8MigrationTest {
    private val creator = CreatorAssistantDefinition.ID.toString()
    private val guide = CreatorAssistantDefinition.GUIDE_SKILL
    private val other = "0f0e0d0c-0b0a-4908-8706-050403020100"

    private fun skillsOf(array: JsonArray, id: String): List<String>? =
        array.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }["enabledSkills"]
            ?.jsonArray?.map { it.jsonPrimitive.content }

    private fun parse(raw: String) = JsonInstant.parseToJsonElement(raw) as JsonArray

    @Test
    fun `the creator gets the guide, keeps its own skills, and nobody else is touched`() {
        val omitted = enableGuideForCreator(parse("""[{"id":"$creator"},{"id":"$other"}]"""))
        assertEquals(listOf(guide), skillsOf(omitted, creator))
        assertEquals(null, skillsOf(omitted, other))

        val withSkills = enableGuideForCreator(parse("""[{"id":"$creator","enabledSkills":["mine"]},{"id":"$other","enabledSkills":["x"]}]"""))
        assertEquals(listOf("mine", guide), skillsOf(withSkills, creator))
        assertEquals(listOf("x"), skillsOf(withSkills, other))

        val already = parse("""[{"id":"$creator","enabledSkills":["$guide","mine"]}]""")
        assertEquals(already, enableGuideForCreator(already))
    }

    @Test
    fun `it runs once, so a guide the user switched off stays off`() = runBlocking {
        val migration = PreferenceStoreV8Migration()
        val prefs = mutablePreferencesOf(SettingsStore.VERSION to 7, SettingsStore.ASSISTANTS to """[{"id":"$creator"}]""")
        assertTrue(migration.shouldMigrate(prefs))
        val migrated = migration.migrate(prefs)
        assertEquals(8, migrated[SettingsStore.VERSION])
        assertEquals(listOf(guide), skillsOf(parse(migrated[SettingsStore.ASSISTANTS]!!), creator))

        val switchedOff = mutablePreferencesOf(SettingsStore.VERSION to 8, SettingsStore.ASSISTANTS to """[{"id":"$creator","enabledSkills":[]}]""")
        assertFalse(migration.shouldMigrate(switchedOff))
    }

    @Test
    fun `a fresh install without stored assistants just records the version`() = runBlocking {
        val migrated = PreferenceStoreV8Migration().migrate(mutablePreferencesOf())
        assertEquals(8, migrated[SettingsStore.VERSION])
        assertEquals(null, migrated[SettingsStore.ASSISTANTS])
    }
}
