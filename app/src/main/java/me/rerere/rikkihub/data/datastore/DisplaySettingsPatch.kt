package me.rerere.rikkihub.data.datastore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.utils.JsonInstant

/**
 * Field patch from a display UI snapshot, applied to the latest value under SettingsStore's
 * mutex. Changes outside this UI action (including other display fields) are preserved.
 * Serialization uses the local typed model, not untrusted or arbitrary Settings JSON.
 */
internal fun applyDisplaySettingsPatch(
    latest: DisplaySetting,
    previous: DisplaySetting,
    next: DisplaySetting,
): DisplaySetting {
    if (previous == next) return latest
    val before = JsonInstant.encodeToJsonElement(DisplaySetting.serializer(), previous).jsonObject
    val after = JsonInstant.encodeToJsonElement(DisplaySetting.serializer(), next).jsonObject
    val current = JsonInstant.encodeToJsonElement(DisplaySetting.serializer(), latest).jsonObject
    val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
    val patched = current.toMutableMap()
    for (key in changed) {
        val value = after[key]
        if (value == null) patched.remove(key) else patched[key] = value
    }
    return JsonInstant.decodeFromJsonElement(DisplaySetting.serializer(), JsonObject(patched))
}
