package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "QwenTTSProvider"

class QwenTTSProvider(client: OkHttpClient) : TTSProvider<TTSProviderSetting.Qwen> {
    private val httpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Qwen,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        require(!providerSetting.model.startsWith("qwen3-tts")) {
            "旧版 Qwen3 TTS 模型已不再支持，请在 TTS 设置中改用 qwen-audio-3.0-tts-plus 或 qwen-audio-3.0-tts-flash"
        }
        require(!providerSetting.baseUrl.contains("{WorkspaceId}")) {
            "请在 Base URL 中将 {WorkspaceId} 替换为阿里云百炼业务空间 ID"
        }

        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("input", buildJsonObject {
                put("text", request.text)
                put("voice", providerSetting.voice)
                put("format", providerSetting.format)
                put("sample_rate", providerSetting.sampleRate)
            })
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/services/audio/tts/SpeechSynthesizer")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-SSE", "enable")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).await().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                Log.e(
                    TAG,
                    "Qwen TTS request failed: ${response.code} ${response.message}, body: $errorBody"
                )
                throw TTSProviderException(
                    message = "Qwen TTS request failed: ${response.code} ${response.message}",
                    statusCode = response.code
                )
            }

            response.body.byteStream().bufferedReader().use { reader ->
                var currentData = StringBuilder()

                reader.lineSequence().forEach { line ->
                    when {
                        line.startsWith("data:") -> {
                            currentData.append(line.removePrefix("data:").trimStart())
                        }

                        line.isEmpty() && currentData.isNotEmpty() -> {
                            parseSSEData(currentData.toString(), providerSetting)?.let { emit(it) }
                            currentData = StringBuilder()
                        }
                    }
                }

                // 兼容最后一个 SSE event 后没有空行、直接 EOF 的响应。
                if (currentData.isNotEmpty()) {
                    parseSSEData(currentData.toString(), providerSetting)?.let { emit(it) }
                }
            }
        }
    }

    private fun parseSSEData(
        data: String,
        providerSetting: TTSProviderSetting.Qwen,
    ): AudioChunk? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val output = json["output"] as? JsonObject ?: return null
            val audio = output["audio"] as? JsonObject ?: return null
            val audioBase64 = audio["data"]?.jsonPrimitive?.contentOrNull ?: ""
            val finishReason = output["finish_reason"]?.jsonPrimitive?.contentOrNull ?: ""

            if (audioBase64.isNotEmpty()) {
                val audioData = Base64.decode(audioBase64, Base64.DEFAULT)
                val isLast = finishReason == "stop"
                AudioChunk(
                    data = audioData,
                    format = when (providerSetting.format.lowercase()) {
                        "mp3" -> AudioFormat.MP3
                        "pcm" -> AudioFormat.PCM
                        "opus" -> AudioFormat.OPUS
                        else -> AudioFormat.WAV
                    },
                    sampleRate = providerSetting.sampleRate,
                    isLast = isLast,
                    metadata = mapOf(
                        "provider" to "qwen",
                        "model" to providerSetting.model,
                        "voice" to providerSetting.voice,
                        "format" to providerSetting.format,
                        "sampleRate" to providerSetting.sampleRate.toString(),
                    )
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SSE data: $data", e)
            null
        }
    }
}
