package me.rerere.rikkihub.ui.pages.setting

import java.io.File
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkihub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SpeechDeletionTest {
    private val system = TTSProviderSetting.SystemTTS(id = DEFAULT_SYSTEM_TTS_ID)
    private val tts = TTSProviderSetting.OpenAI(name = "Voice", apiKey = "secret")
    private val asr = ASRProviderSetting.OpenAIRealtime(name = "Recognizer")
    private val other = ASRProviderSetting.OpenAIRealtime(name = "Other")
    private fun settings() = Settings.dummy().copy(init = false, ttsProviders = listOf(system, tts),
        asrProviders = listOf(asr, other), selectedTTSProviderId = tts.id, selectedASRProviderId = asr.id)

    @Test fun `selected TTS deletion falls back to original system provider`() {
        val s = settings(); val n = deleteSpeechProvider(s, SpeechDeletionTarget(tts.id, tts.name, true))
        assertEquals(listOf(system), n.ttsProviders); assertEquals(DEFAULT_SYSTEM_TTS_ID, n.selectedTTSProviderId)
        assertEquals(s.asrProviders, n.asrProviders)
    }
    @Test fun `selected ASR deletion chooses first remaining or null`() {
        val s = settings(); val n = deleteSpeechProvider(s, SpeechDeletionTarget(asr.id, asr.name, false))
        assertEquals(listOf(other), n.asrProviders); assertEquals(other.id, n.selectedASRProviderId)
        val last = deleteSpeechProvider(n, SpeechDeletionTarget(other.id, other.name, false))
        assertTrue(last.asrProviders.isEmpty()); assertNull(last.selectedASRProviderId)
    }
    @Test fun `unselected deletion preserves current selections and unrelated settings`() {
        val s = settings().copy(selectedASRProviderId = other.id, selectedTTSProviderId = system.id, sponsorAlertDismissedAt = 777)
        val n = deleteSpeechProvider(deleteSpeechProvider(s, SpeechDeletionTarget(asr.id, "snapshot", false)),
            SpeechDeletionTarget(tts.id, "snapshot", true))
        assertEquals(other.id, n.selectedASRProviderId); assertEquals(system.id, n.selectedTTSProviderId)
        assertEquals(777, n.sponsorAlertDismissedAt)
    }
    @Test fun `system TTS cannot be removed even with a stale forged target`() {
        val s = settings(); assertSame(s, deleteSpeechProvider(s, SpeechDeletionTarget(system.id, "System", true)))
    }
    @Test fun `absent or repeated targets are no ops`() {
        val s = settings(); assertSame(s, deleteSpeechProvider(s, SpeechDeletionTarget(Uuid.random(), "gone", false)))
        val target = SpeechDeletionTarget(tts.id, "Voice", true); val n = deleteSpeechProvider(s, target)
        assertSame(n, deleteSpeechProvider(n, target))
    }
    @Test fun `latest edits to target are still deleted by frozen ID while new providers survive`() {
        val s = settings(); val target = SpeechDeletionTarget(tts.id, tts.name, true)
        val added = TTSProviderSetting.OpenAI(name = "New")
        val latest = s.copy(ttsProviders = listOf(system, tts.copy(name = "edited"), added))
        assertEquals(listOf(system, added), deleteSpeechProvider(latest, target).ttsProviders)
    }
    @Test fun `TTS and ASR namespaces remain independent even for equal IDs`() {
        val s = settings().copy(asrProviders = listOf(asr.copy(id = tts.id)))
        assertEquals(s.asrProviders, deleteSpeechProvider(s, SpeechDeletionTarget(tts.id, "tts", true)).asrProviders)
    }
    @Test fun `menus request confirmation and store mutation uses latest settings`() {
        val root = File("src/main/java/me/rerere/rikkihub").takeIf { it.isDirectory } ?: File("app/src/main/java/me/rerere/rikkihub")
        val page = File(root, "ui/pages/setting/SettingSpeechPage.kt").readText()
        assertEquals(2, Regex("onDeleteRequest\\(provider\\)").findAll(page).count())
        assertTrue(page.contains("RikkaConfirmDialog(")); assertTrue(page.contains("vm.deleteSpeechProvider(target)"))
        assertFalse(page.contains("settings.ttsProviders - provider")); assertFalse(page.contains("settings.asrProviders - provider"))
        assertTrue(File(root, "ui/pages/setting/SettingVM.kt").readText().contains("settingsStore.update { current -> deleteSpeechProvider(current, target) }"))
        assertTrue(File(root, "ui/components/ai/SearchPicker.kt").readText().contains("BackHandler(enabled = selectingProvider)"))
    }
}
