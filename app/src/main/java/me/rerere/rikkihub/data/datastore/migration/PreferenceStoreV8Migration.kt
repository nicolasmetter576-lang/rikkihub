package me.rerere.rikkihub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.utils.JsonInstant

/**
 * V8（2.57.0）：给已有的创造助手打开内置使用指南技能，只做这一次；之后用户关掉就一直关着。
 * 备份恢复不走这一步：恢复的是备份里的样子（[SettingsJsonMigrator] 每次恢复都会重跑，补上就关不掉了）。
 */
class PreferenceStoreV8Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 8
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS]?.let { raw ->
            val array = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            if (array != null) prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(enableGuideForCreator(array))
        }
        prefs[SettingsStore.VERSION] = 8
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun enableGuideForCreator(assistants: JsonArray): JsonArray = JsonArray(assistants.map { element ->
    val assistant = element as? JsonObject ?: return@map element
    val isCreator = (assistant["id"] as? JsonPrimitive)?.content == CreatorAssistantDefinition.ID.toString()
    val skills = assistant["enabledSkills"] as? JsonArray ?: JsonArray(emptyList())
    if (!isCreator || skills.any { it.jsonPrimitive.content == CreatorAssistantDefinition.GUIDE_SKILL }) return@map element
    JsonObject(assistant + ("enabledSkills" to JsonArray(skills + JsonPrimitive(CreatorAssistantDefinition.GUIDE_SKILL))))
})
