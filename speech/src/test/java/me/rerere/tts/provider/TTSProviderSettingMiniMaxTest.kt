package me.rerere.tts.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingMiniMaxTest {

    // Mirrors app JsonInstant semantics: saved ttsProviders JSON encodes
    // default values explicitly, so an old default like speech-2.6-turbo is
    // persisted in existing user settings and must survive a decode round trip.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun minimax_new_instance_defaults_to_speech_2_8_hd() {
        val setting = TTSProviderSetting.MiniMax()

        assertEquals("MiniMax TTS", setting.name)
        assertEquals("https://api.minimaxi.com/v1", setting.baseUrl)
        assertEquals("speech-2.8-hd", setting.model)
        assertEquals("female-shaonv", setting.voiceId)
        assertEquals("", setting.apiKey)
        assertEquals(1.0f, setting.speed)
    }

    @Test
    fun minimax_saved_old_default_model_survives_decode() {
        // Simulates persisted user settings written before the default change:
        // the old default model was stored explicitly in the JSON.
        val savedJson = """
            {
              "type": "minimax",
              "id": "11111111-1111-4111-8111-111111111111",
              "name": "MiniMax TTS",
              "apiKey": "saved-key",
              "baseUrl": "https://api.minimaxi.com/v1",
              "model": "speech-2.6-turbo",
              "voiceId": "female-shaonv",
              "speed": 1.0
            }
        """.trimIndent()

        val decoded = json.decodeFromString<TTSProviderSetting>(savedJson)
        assertTrue(decoded is TTSProviderSetting.MiniMax)

        val setting = decoded as TTSProviderSetting.MiniMax
        assertEquals("speech-2.6-turbo", setting.model)
        assertEquals("saved-key", setting.apiKey)
        assertEquals("https://api.minimaxi.com/v1", setting.baseUrl)
        assertEquals("female-shaonv", setting.voiceId)
    }

    @Test
    fun minimax_saved_custom_model_base_url_and_key_survive_decode() {
        val savedJson = """
            {
              "type": "minimax",
              "id": "22222222-2222-4222-8222-222222222222",
              "name": "My MiniMax",
              "apiKey": "custom-key",
              "baseUrl": "https://minimax-proxy.example.com/v1",
              "model": "speech-01-hd",
              "voiceId": "male-qn-qingse",
              "speed": 1.25
            }
        """.trimIndent()

        val decoded = json.decodeFromString<TTSProviderSetting>(savedJson)
        val setting = decoded as TTSProviderSetting.MiniMax

        assertEquals("speech-01-hd", setting.model)
        assertEquals("custom-key", setting.apiKey)
        assertEquals("https://minimax-proxy.example.com/v1", setting.baseUrl)
        assertEquals("male-qn-qingse", setting.voiceId)
        assertEquals(1.25f, setting.speed)
    }

    @Test
    fun minimax_missing_fields_fall_back_to_new_defaults() {
        // A freshly added / minimal MiniMax entry without an explicit model
        // resolves to the new default; this is not restoring a lost old value.
        val minimalJson = """
            {
              "type": "minimax",
              "id": "33333333-3333-4333-8333-333333333333",
              "apiKey": "key"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<TTSProviderSetting>(minimalJson)
        val setting = decoded as TTSProviderSetting.MiniMax

        assertEquals("speech-2.8-hd", setting.model)
        assertEquals("https://api.minimaxi.com/v1", setting.baseUrl)
        assertEquals("key", setting.apiKey)
    }
}
