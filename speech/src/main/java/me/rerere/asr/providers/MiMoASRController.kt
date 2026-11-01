package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.MicCaptureLoop
import me.rerere.asr.appendAmplitude
import me.rerere.speech.internal.pcmToWav
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

private const val TAG = "MiMoASR"

// MiMo 官方限制: 单次请求 base64 不超过 10MB ≈ 7.5MB raw。
// 16kHz / 16bit / mono 下 7.5MB ≈ 234 秒。提前在 6MB 触发自动 flush 留余量。
private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

// 最小段长度: 16kHz/16bit/mono 下 100ms = 3200 字节。短于这个长度直接丢弃,
// 避免把超短碎片 (比如 stop 时缓冲区里的残留) 发给服务端导致 400。
private const val MIN_SEGMENT_BYTES = 3200

private val mimoAsrJson = Json { ignoreUnknownKeys = true }

/**
 * 小米 MiMo ASR Controller。
 *
 * MiMo ASR 不是 WebSocket 流式接口, 而是 OpenAI 兼容的 chat/completions HTTP 一次性
 * 识别接口。本 Controller 在录音期间按时间或字节阈值把 PCM 切成段, 每段独立转 WAV
 * 后 POST 到 MiMo, 返回的文本拼接到 completedTranscripts 并通过 onTranscriptChange
 * 回调。stop() 时把剩余 PCM 做最后一次 flush。
 *
 * 官方文档: https://platform.xiaomimimo.com/docs/zh-CN/api/audio/Speech-Recognition
 */
class MiMoASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.MiMo
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var onTranscriptChange: ((String) -> Unit)? = null

    // 同一时刻只允许一个 flush 协程在跑, 避免乱序拼结果
    private var flushJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    private val micCapture = MicCaptureLoop(
        scope = scope,
        sampleRate = provider.sampleRate,
        onAmplitude = { amplitude ->
            _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
        },
        onFrame = { buffer, read -> onAudioFrame(buffer, read) },
        onError = { e ->
            Log.e(TAG, "Audio recording failed", e)
            setError(e.message ?: "Audio recording failed")
        },
    )

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()
        flushJob = null

        // MiMo 是 HTTP 一次性接口, 没有 WebSocket 连接阶段, 直接进入 Listening
        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true
            )
        }
        micCapture.start()
    }

    override fun stop() {
        micCapture.stop()
        _state.update {
            if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Stopping)
        }

        // 把剩余 PCM 做最后一次 flush, 完成后切回 Idle (Error 状态保留不覆盖)
        scope.launch(Dispatchers.IO) {
            try {
                // 等当前正在跑的 flushJob 完成, 避免并发 flush 导致结果乱序
                flushJob?.join()
                flushSegment()
            } catch (e: Exception) {
                Log.e(TAG, "Final flush failed", e)
                setError(e.message ?: "MiMo ASR final flush failed")
            } finally {
                _state.update {
                    if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
                }
            }
        }
    }

    override fun dispose() {
        micCapture.stop()
        flushJob?.cancel()
        scope.cancel()
    }

    private fun onAudioFrame(buffer: ByteArray, read: Int) {
        val shouldFlush = synchronized(bufferLock) {
            currentBuffer.write(buffer, 0, read)
            val segmentMs = provider.segmentDurationSec.coerceAtLeast(0) * 1000L
            if (segmentMs <= 0) {
                currentBuffer.size() >= MAX_SEGMENT_BYTES
            } else {
                val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                currentBuffer.size() >= MAX_SEGMENT_BYTES || elapsed >= segmentMs
            }
        }

        if (shouldFlush) {
            // 用单独协程异步 flush, 不阻塞录音主循环
            triggerFlush()
        }
    }

    private fun triggerFlush() {
        // 同一时刻只跑一个 flush, 避免后发先至导致结果乱序
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            runCatching { flushSegment() }
                .onFailure { Log.e(TAG, "Segment flush failed", it) }
        }
    }

    /**
     * 取出当前缓冲区里的 PCM, 转 WAV 后 POST 到 MiMo; 把识别结果加到 completedTranscripts。
     * 在 bufferLock 内拷贝出 PCM 并立刻重置缓冲区, 不持有锁等待网络, 避免阻塞录音写。
     * 网络/5xx 失败由 executeAsrRequestWithRetry 自动重试, 4xx (除 408/429) 直接失败。
     */
    private suspend fun flushSegment() {
        val pcmBytes = synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        // 太短的段直接丢弃, 避免服务端因音频过短返回 400
        if (pcmBytes.size < MIN_SEGMENT_BYTES) {
            Log.d(TAG, "Skip flush: PCM too short (${pcmBytes.size} bytes)")
            return
        }

        val wavBytes = pcmToWav(
            pcm = pcmBytes,
            sampleRate = provider.sampleRate,
            channels = 1,
            bitsPerSample = 16
        )
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val message = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                addJsonObject {
                    put("type", "input_audio")
                    put("input_audio", buildJsonObject {
                        put("data", "data:audio/wav;base64,$b64")
                    })
                }
            })
        }

        val body = buildJsonObject {
            put("model", provider.model)
            put("messages", buildJsonArray { add(message) })
            if (provider.language.isNotBlank()) {
                put("asr_options", buildJsonObject {
                    put("language", provider.language)
                })
            }
        }

        val request = Request.Builder()
            .url("${provider.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("api-key", provider.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val text = executeAsrRequestWithRetry(TAG, httpClient, request) { resp ->
            val respBody = resp.body.string()
            val json = runCatching { mimoAsrJson.parseToJsonElement(respBody).jsonObject }.getOrElse {
                throw IOException("MiMo ASR response is not valid JSON: $respBody")
            }
            val message = (json["choices"] as? JsonArray)
                ?.firstOrNull() as? JsonObject
            (message?.get("message") as? JsonObject)
                ?.get("content")?.jsonPrimitive?.contentOrNull
                ?.trim()
                .orEmpty()
        }.trim()

        if (text.isNotEmpty()) {
            completedTranscripts.add(text)
            publishTranscript()
        }
    }

    private fun publishTranscript() {
        val transcript = completedTranscripts
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
