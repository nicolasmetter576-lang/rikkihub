package me.rerere.rikkihub.ui.pages.setting

import me.rerere.rikkihub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkihub.data.datastore.Settings
import kotlin.uuid.Uuid

internal data class SpeechDeletionTarget(val id: Uuid, val name: String, val tts: Boolean)

/** Confirmation freezes identity, while the mutation uses current settings inside the store lock. */
internal fun deleteSpeechProvider(settings: Settings, target: SpeechDeletionTarget): Settings {
    if (settings.init) return settings
    return if (target.tts) {
        if (target.id == DEFAULT_SYSTEM_TTS_ID || settings.ttsProviders.none { it.id == target.id }) return settings
        settings.copy(
            ttsProviders = settings.ttsProviders.filterNot { it.id == target.id },
            selectedTTSProviderId = if (settings.selectedTTSProviderId == target.id) DEFAULT_SYSTEM_TTS_ID else settings.selectedTTSProviderId,
        )
    } else {
        if (settings.asrProviders.none { it.id == target.id }) return settings
        val remaining = settings.asrProviders.filterNot { it.id == target.id }
        settings.copy(
            asrProviders = remaining,
            selectedASRProviderId = if (settings.selectedASRProviderId == target.id) remaining.firstOrNull()?.id else settings.selectedASRProviderId,
        )
    }
}
